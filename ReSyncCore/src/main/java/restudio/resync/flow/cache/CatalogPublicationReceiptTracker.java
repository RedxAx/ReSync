package restudio.resync.flow.cache;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

public final class CatalogPublicationReceiptTracker implements CatalogPublicationReceiptOperations {
    static final String OWNER_TOKEN_REQUIRED = "CATALOG_PUBLICATION.OWNER_TOKEN_REQUIRED";
    static final String OWNER_TOKEN_MISMATCH = "CATALOG_PUBLICATION.OWNER_TOKEN_MISMATCH";
    static final String OWNER_STALE = "CATALOG_PUBLICATION.RECEIPT_OWNER_STALE";

    private final Map<String, CatalogPublicationReceipt> baselines = new LinkedHashMap<>();

    public CatalogPublicationReceiptTracker() {
    }

    CatalogPublicationReceiptTracker(Collection<CatalogPublicationReceipt> initialBaselines) {
        restore(initialBaselines);
    }

    public static CatalogPublicationReceiptTracker snapshot(Collection<CatalogPublicationReceipt> baselines) {
        return new CatalogPublicationReceiptTracker(baselines);
    }

    @Override
    public synchronized CatalogPublicationReceipt.Claim claim(String sessionKey, String ownerToken) {
        String key = requireSessionKey(sessionKey);
        if (!validOwnerToken(ownerToken)) {
            return rejectedClaim(OWNER_TOKEN_REQUIRED, baselines.get(key));
        }
        CatalogPublicationReceipt current = baselines.get(key);
        if (current == null) {
            return rejectedClaim("CATALOG_PUBLICATION.RECEIPT_NO_BASELINE", null);
        }
        if (current.ownerState() == CatalogPublicationReceipt.OwnerState.CLAIMED
            && !current.ownerToken().equals(ownerToken)) {
            return rejectedClaim(OWNER_TOKEN_MISMATCH, current);
        }
        if (current.ownerState() == CatalogPublicationReceipt.OwnerState.CLAIMED) {
            return acceptedClaim(current, null);
        }
        CatalogPublicationReceipt next = current.withOwnerToken(ownerToken);
        baselines.put(key, next);
        return acceptedClaim(next, null);
    }

    @Override
    public synchronized CatalogPublicationReceipt.Claim adopt(String sessionKey, String ownerToken) {
        String key = requireSessionKey(sessionKey);
        if (!validOwnerToken(ownerToken)) {
            return rejectedClaim(OWNER_TOKEN_REQUIRED, baselines.get(key));
        }
        CatalogPublicationReceipt current = baselines.get(key);
        if (current == null) {
            return rejectedClaim("CATALOG_PUBLICATION.RECEIPT_NO_BASELINE", null);
        }
        if (ownerToken.equals(current.ownerToken())) {
            return acceptedClaim(current, null);
        }
        CatalogPublicationReceipt next = current.withOwnerToken(ownerToken);
        baselines.put(key, next);
        return acceptedClaim(next, current.ownerToken());
    }

    @Override
    public synchronized CatalogPublicationReceipt.Claim adoptForRedispatch(String sessionKey, String ownerToken,
                                                                            CatalogCachePublication publication) {
        String key = requireSessionKey(sessionKey);
        Objects.requireNonNull(publication, "Publication is required");
        if (!validOwnerToken(ownerToken)) {
            return rejectedClaim(OWNER_TOKEN_REQUIRED, baselines.get(key));
        }
        CatalogPublicationReceipt current = baselines.get(key);
        if (current == null) {
            return rejectedClaim("CATALOG_PUBLICATION.RECEIPT_NO_BASELINE", null);
        }
        if (current.ownerState() != CatalogPublicationReceipt.OwnerState.CLAIMED) {
            return rejectedClaim(OWNER_TOKEN_REQUIRED, current);
        }
        if (ownerToken.equals(current.ownerToken())) {
            return acceptedClaim(current, null);
        }
        CatalogPublicationReceipt next = current.withOwnerToken(ownerToken);
        boolean rejected = current.dispatch() == CatalogPublicationReceipt.DispatchState.FAILED
            || current.clientReceipt() == CatalogPublicationReceipt.ClientReceiptState.REJECTED
            || current.cacheApplication() == CatalogPublicationReceipt.CacheApplicationState.REJECTED;
        if (current.matches(publication.key(), publication.revision()) && rejected
            && current.cacheApplication() != CatalogPublicationReceipt.CacheApplicationState.APPLIED) {
            next = CatalogPublicationReceipt.pending(key, ownerToken, publication).dispatched();
        }
        baselines.put(key, next);
        return acceptedClaim(next, current.ownerToken());
    }

    @Override
    public synchronized CatalogPublicationReceipt recordDispatch(String sessionKey, String ownerToken,
                                                                  CatalogCachePublication publication) {
        CatalogPublicationReceipt.Transition result = tryRecordDispatch(sessionKey, ownerToken, publication);
        return acceptedReceipt(result);
    }

    @Override
    public synchronized CatalogPublicationReceipt.Transition tryRecordDispatch(String sessionKey, String ownerToken,
                                                                                 CatalogCachePublication publication) {
        Objects.requireNonNull(publication, "Publication is required");
        String key = requireSessionKey(sessionKey);
        CatalogPublicationReceipt current = baselines.get(key);
        CatalogPublicationReceipt.Transition ownerCheck = ownerCheck(current, ownerToken);
        if (ownerCheck != null && !ownerCheck.accepted()) {
            return ownerCheck;
        }
        CatalogPublicationReceipt next;
        if (current == null || !current.matches(publication.key(), publication.revision())) {
            next = CatalogPublicationReceipt.pending(key, ownerToken, publication).dispatched();
        } else {
            next = current.dispatched();
        }
        baselines.put(key, next);
        return acceptedTransition(next);
    }

    @Override
    public synchronized void recordDispatchBatch(Map<String, String> sessionOwners,
                                                 CatalogCachePublication publication) {
        CatalogPublicationReceipt.BatchTransition result = tryRecordDispatchBatch(sessionOwners, publication);
        if (!result.accepted()) {
            throw new IllegalStateException(result.code());
        }
    }

    @Override
    public synchronized CatalogPublicationReceipt.BatchTransition tryRecordDispatchBatch(
        Map<String, String> sessionOwners, CatalogCachePublication publication) {
        Objects.requireNonNull(sessionOwners, "Publication session owners are required");
        Objects.requireNonNull(publication, "Publication is required");
        Map<String, String> owners = normalizedOwners(sessionOwners);
        Map<String, CatalogPublicationReceipt.Transition> rejected = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : owners.entrySet()) {
            CatalogPublicationReceipt.Transition ownerCheck = ownerCheck(baselines.get(entry.getKey()), entry.getValue());
            if (ownerCheck != null && !ownerCheck.accepted()) {
                rejected.put(entry.getKey(), ownerCheck);
            }
        }
        if (!rejected.isEmpty()) {
            String code = rejected.values().stream().findFirst().orElseThrow().code();
            return new CatalogPublicationReceipt.BatchTransition(false, code, rejected);
        }
        Map<String, CatalogPublicationReceipt> before = new LinkedHashMap<>(baselines);
        Map<String, CatalogPublicationReceipt.Transition> outcomes = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, String> entry : owners.entrySet()) {
                String key = entry.getKey();
                String ownerToken = entry.getValue();
                CatalogPublicationReceipt current = baselines.get(key);
                CatalogPublicationReceipt next = current == null || !current.matches(publication.key(), publication.revision())
                    ? CatalogPublicationReceipt.pending(key, ownerToken, publication).dispatched()
                    : current.dispatched();
                baselines.put(key, next);
                outcomes.put(key, acceptedTransition(next));
            }
        } catch (RuntimeException exception) {
            restore(before.values());
            throw exception;
        }
        return new CatalogPublicationReceipt.BatchTransition(true, "", outcomes);
    }

    @Override
    public synchronized CatalogPublicationReceipt recordDispatchFailure(String sessionKey, String ownerToken,
                                                                          CatalogCachePublication publication,
                                                                          String diagnosticCode) {
        CatalogPublicationReceipt.Transition result = tryRecordDispatchFailure(sessionKey, ownerToken, publication,
            diagnosticCode);
        return acceptedReceipt(result);
    }

    @Override
    public synchronized CatalogPublicationReceipt.Transition tryRecordDispatchFailure(String sessionKey,
                                                                                        String ownerToken,
                                                                                        CatalogCachePublication publication,
                                                                                        String diagnosticCode) {
        Objects.requireNonNull(publication, "Publication is required");
        String key = requireSessionKey(sessionKey);
        CatalogPublicationReceipt current = baselines.get(key);
        CatalogPublicationReceipt.Transition ownerCheck = ownerCheck(current, ownerToken);
        if (ownerCheck != null && !ownerCheck.accepted()) {
            return ownerCheck;
        }
        CatalogPublicationReceipt next = current == null || !current.matches(publication.key(), publication.revision())
            ? CatalogPublicationReceipt.pending(key, ownerToken, publication)
            : current;
        next = next.dispatchFailed(diagnosticCode);
        baselines.put(key, next);
        return acceptedTransition(next);
    }

    @Override
    public synchronized CatalogPublicationReceipt.Transition acknowledgeClientReceipt(String sessionKey,
                                                                                        String ownerToken,
                                                                                        CatalogCacheKey publicationKey,
                                                                                        long revision) {
        return transition(sessionKey, ownerToken, current -> current.clientReceived(publicationKey, revision),
            "CATALOG_PUBLICATION.RECEIPT_NO_BASELINE");
    }

    @Override
    public synchronized CatalogPublicationReceipt.Transition acknowledgeCacheApplication(String sessionKey,
                                                                                           String ownerToken,
                                                                                           CatalogCacheKey publicationKey,
                                                                                           long revision) {
        return transition(sessionKey, ownerToken, current -> current.cacheApplied(publicationKey, revision),
            "CATALOG_PUBLICATION.APPLICATION_NO_BASELINE");
    }

    @Override
    public synchronized CatalogPublicationReceipt.Transition rejectCacheApplication(String sessionKey,
                                                                                       String ownerToken,
                                                                                       CatalogCacheKey publicationKey,
                                                                                       long revision,
                                                                                       String diagnosticCode) {
        return transition(sessionKey, ownerToken,
            current -> current.cacheRejected(publicationKey, revision, diagnosticCode),
            "CATALOG_PUBLICATION.APPLICATION_NO_BASELINE");
    }

    @Override
    public synchronized Optional<CatalogPublicationReceipt> baseline(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank() || !sessionKey.equals(sessionKey.strip())) {
            return Optional.empty();
        }
        return Optional.ofNullable(baselines.get(sessionKey));
    }

    @Override
    public synchronized List<CatalogPublicationReceipt> baselines() {
        return baselines.values().stream()
            .sorted(Comparator.comparing(CatalogPublicationReceipt::sessionKey))
            .toList();
    }

    @Override
    public synchronized List<String> pendingSessionKeys() {
        return baselines.values().stream()
            .filter(receipt -> !receipt.converged())
            .map(CatalogPublicationReceipt::sessionKey)
            .sorted()
            .toList();
    }

    @Override
    public synchronized boolean remove(String sessionKey, String ownerToken) {
        if (!validSessionKey(sessionKey) || !validOwnerToken(ownerToken)) {
            return false;
        }
        CatalogPublicationReceipt current = baselines.get(sessionKey);
        if (current == null || current.ownerState() != CatalogPublicationReceipt.OwnerState.CLAIMED
            || !current.ownerToken().equals(ownerToken)) {
            return false;
        }
        return baselines.remove(sessionKey, current);
    }

    synchronized void restore(Collection<CatalogPublicationReceipt> values) {
        Objects.requireNonNull(values, "Receipt baselines are required");
        Map<String, CatalogPublicationReceipt> replacement = new LinkedHashMap<>();
        for (CatalogPublicationReceipt receipt : values) {
            Objects.requireNonNull(receipt, "Receipt baseline cannot be null");
            if (replacement.putIfAbsent(receipt.sessionKey(), receipt) != null) {
                throw new IllegalArgumentException("Duplicate catalog publication receipt session: " + receipt.sessionKey());
            }
        }
        baselines.clear();
        baselines.putAll(replacement);
    }

    private CatalogPublicationReceipt.Transition transition(String sessionKey, String ownerToken,
                                                             Function<CatalogPublicationReceipt, CatalogPublicationReceipt.Transition> operation,
                                                             String missingCode) {
        String key = sessionKey == null ? "" : sessionKey;
        CatalogPublicationReceipt current = baselines.get(key);
        if (current == null) {
            return new CatalogPublicationReceipt.Transition(false, missingCode, Optional.empty());
        }
        if (!validOwnerToken(ownerToken)) {
            return new CatalogPublicationReceipt.Transition(false, OWNER_TOKEN_REQUIRED, Optional.of(current));
        }
        if (current.ownerState() != CatalogPublicationReceipt.OwnerState.CLAIMED) {
            return new CatalogPublicationReceipt.Transition(false, OWNER_TOKEN_REQUIRED, Optional.of(current));
        }
        if (!current.ownerToken().equals(ownerToken)) {
            return new CatalogPublicationReceipt.Transition(false, OWNER_STALE, Optional.of(current));
        }
        CatalogPublicationReceipt.Transition result = operation.apply(current);
        if (result.accepted()) {
            CatalogPublicationReceipt next = result.receipt().orElseThrow();
            baselines.put(key, next);
            return new CatalogPublicationReceipt.Transition(true, "", Optional.of(next));
        }
        return new CatalogPublicationReceipt.Transition(false, result.code(), Optional.of(current));
    }

    private static String requireSessionKey(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank() || !sessionKey.equals(sessionKey.strip())) {
            throw new IllegalArgumentException("Publication session key must be non-blank and canonical");
        }
        return sessionKey;
    }

    private static boolean validSessionKey(String sessionKey) {
        return sessionKey != null && !sessionKey.isBlank() && sessionKey.equals(sessionKey.strip());
    }

    private static boolean validOwnerToken(String ownerToken) {
        return ownerToken != null && !ownerToken.isBlank() && ownerToken.equals(ownerToken.strip());
    }

    private static Map<String, String> normalizedOwners(Map<String, String> sessionOwners) {
        Map<String, String> owners = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : sessionOwners.entrySet()) {
            String key = requireSessionKey(entry.getKey());
            String ownerToken = CatalogPublicationReceipt.requireOwnerToken(entry.getValue());
            if (owners.putIfAbsent(key, ownerToken) != null) {
                throw new IllegalArgumentException("Duplicate catalog publication receipt session: " + key);
            }
        }
        return owners;
    }

    private static CatalogPublicationReceipt.Transition ownerCheck(CatalogPublicationReceipt current, String ownerToken) {
        if (!validOwnerToken(ownerToken)) {
            return rejectedTransition(OWNER_TOKEN_REQUIRED, current);
        }
        if (current == null) {
            return null;
        }
        if (current.ownerState() != CatalogPublicationReceipt.OwnerState.CLAIMED) {
            return rejectedTransition(OWNER_TOKEN_REQUIRED, current);
        }
        if (!current.ownerToken().equals(ownerToken)) {
            return rejectedTransition(OWNER_STALE, current);
        }
        return acceptedTransition(current);
    }

    private static CatalogPublicationReceipt acceptedReceipt(CatalogPublicationReceipt.Transition result) {
        if (!result.accepted()) {
            throw new IllegalStateException(result.code());
        }
        return result.receipt().orElseThrow(() -> new IllegalStateException(result.code()));
    }

    private static CatalogPublicationReceipt.Transition acceptedTransition(CatalogPublicationReceipt receipt) {
        return new CatalogPublicationReceipt.Transition(true, "", Optional.ofNullable(receipt));
    }

    private static CatalogPublicationReceipt.Transition rejectedTransition(String code,
                                                                            CatalogPublicationReceipt receipt) {
        return new CatalogPublicationReceipt.Transition(false, code, Optional.ofNullable(receipt));
    }

    private static CatalogPublicationReceipt.Claim acceptedClaim(CatalogPublicationReceipt receipt,
                                                                   String previousOwnerToken) {
        return new CatalogPublicationReceipt.Claim(true, "", Optional.of(receipt), previousOwnerToken);
    }

    private static CatalogPublicationReceipt.Claim rejectedClaim(String code,
                                                                   CatalogPublicationReceipt receipt) {
        return new CatalogPublicationReceipt.Claim(false, code, Optional.ofNullable(receipt),
            receipt == null ? null : receipt.ownerToken());
    }
}
