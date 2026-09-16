package restudio.resync.flow.cache;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record CatalogPublicationReceipt(
    String sessionKey,
    String ownerToken,
    OwnerState ownerState,
    CatalogCacheKey publicationKey,
    long revision,
    DispatchState dispatch,
    ClientReceiptState clientReceipt,
    CacheApplicationState cacheApplication,
    String diagnosticCode
) {
    public CatalogPublicationReceipt {
        sessionKey = sessionKey(sessionKey);
        ownerToken = normalizeOwnerToken(ownerToken);
        ownerState = Objects.requireNonNull(ownerState, "Owner state is required");
        if (ownerState == OwnerState.CLAIMED && ownerToken == null) {
            throw new IllegalArgumentException("Claimed catalog publication receipt requires an owner token");
        }
        if (ownerState == OwnerState.LEGACY_UNCLAIMED && ownerToken != null) {
            throw new IllegalArgumentException("Legacy-unclaimed catalog publication receipt cannot have an owner token");
        }
        publicationKey = Objects.requireNonNull(publicationKey, "Publication key is required");
        if (revision < 0) {
            throw new IllegalArgumentException("Publication revision must not be negative");
        }
        dispatch = Objects.requireNonNull(dispatch, "Dispatch state is required");
        clientReceipt = Objects.requireNonNull(clientReceipt, "Client receipt state is required");
        cacheApplication = Objects.requireNonNull(cacheApplication, "Cache application state is required");
        diagnosticCode = diagnosticCode == null ? "" : diagnosticCode;
        requireStateInvariant(dispatch, clientReceipt, cacheApplication, diagnosticCode);
    }

    public CatalogPublicationReceipt(String sessionKey, String ownerToken, CatalogCacheKey publicationKey,
                                     long revision, DispatchState dispatch, ClientReceiptState clientReceipt,
                                     CacheApplicationState cacheApplication, String diagnosticCode) {
        this(sessionKey, ownerToken, ownerToken == null ? OwnerState.LEGACY_UNCLAIMED : OwnerState.CLAIMED,
            publicationKey, revision, dispatch, clientReceipt, cacheApplication, diagnosticCode);
    }

    public static CatalogPublicationReceipt pending(String sessionKey, String ownerToken,
                                                    CatalogCachePublication publication) {
        Objects.requireNonNull(publication, "Publication is required");
        requireOwnerToken(ownerToken);
        return new CatalogPublicationReceipt(sessionKey, ownerToken, OwnerState.CLAIMED, publication.key(),
            publication.revision(), DispatchState.NOT_ATTEMPTED, ClientReceiptState.NOT_RECEIVED,
            CacheApplicationState.NOT_APPLIED, "");
    }

    public boolean owned() {
        return ownerState == OwnerState.CLAIMED;
    }

    public boolean matches(CatalogCacheKey key, long expectedRevision) {
        return publicationKey.equals(key) && revision == expectedRevision;
    }

    public CatalogPublicationReceipt dispatched() {
        if (dispatch == DispatchState.DISPATCHED) {
            return this;
        }
        if (cacheApplication == CacheApplicationState.APPLIED) {
            return this;
        }
        return new CatalogPublicationReceipt(sessionKey, ownerToken, ownerState, publicationKey, revision,
            DispatchState.DISPATCHED,
            clientReceipt == ClientReceiptState.RECEIVED ? ClientReceiptState.RECEIVED : ClientReceiptState.NOT_RECEIVED,
            CacheApplicationState.NOT_APPLIED, "");
    }

    public CatalogPublicationReceipt dispatchFailed(String code) {
        String failure = requireDiagnostic(code);
        if (dispatch == DispatchState.FAILED || cacheApplication == CacheApplicationState.APPLIED
            || clientReceipt == ClientReceiptState.RECEIVED) {
            return this;
        }
        return new CatalogPublicationReceipt(sessionKey, ownerToken, ownerState, publicationKey, revision,
            DispatchState.FAILED, ClientReceiptState.REJECTED, CacheApplicationState.REJECTED, failure);
    }

    public Transition clientReceived(CatalogCacheKey key, long expectedRevision) {
        if (!matches(key, expectedRevision)) {
            return rejected("CATALOG_PUBLICATION.RECEIPT_KEY_MISMATCH");
        }
        if (dispatch != DispatchState.DISPATCHED) {
            return rejected("CATALOG_PUBLICATION.RECEIPT_BEFORE_DISPATCH");
        }
        if (clientReceipt == ClientReceiptState.RECEIVED) {
            return accepted(this);
        }
        if (clientReceipt == ClientReceiptState.REJECTED) {
            return rejected("CATALOG_PUBLICATION.RECEIPT_ALREADY_REJECTED");
        }
        return accepted(new CatalogPublicationReceipt(sessionKey, ownerToken, ownerState, publicationKey, revision,
            dispatch, ClientReceiptState.RECEIVED, cacheApplication, ""));
    }

    public Transition cacheApplied(CatalogCacheKey key, long expectedRevision) {
        if (!matches(key, expectedRevision)) {
            return rejected("CATALOG_PUBLICATION.APPLICATION_KEY_MISMATCH");
        }
        if (clientReceipt != ClientReceiptState.RECEIVED) {
            return rejected("CATALOG_PUBLICATION.APPLICATION_BEFORE_RECEIPT");
        }
        if (cacheApplication == CacheApplicationState.APPLIED) {
            return accepted(this);
        }
        if (cacheApplication == CacheApplicationState.REJECTED) {
            return rejected("CATALOG_PUBLICATION.APPLICATION_ALREADY_REJECTED");
        }
        return accepted(new CatalogPublicationReceipt(sessionKey, ownerToken, ownerState, publicationKey, revision,
            dispatch, clientReceipt, CacheApplicationState.APPLIED, ""));
    }

    public Transition cacheRejected(CatalogCacheKey key, long expectedRevision, String diagnosticCode) {
        if (!matches(key, expectedRevision)) {
            return rejected("CATALOG_PUBLICATION.APPLICATION_KEY_MISMATCH");
        }
        if (dispatch != DispatchState.DISPATCHED) {
            return rejected("CATALOG_PUBLICATION.APPLICATION_BEFORE_DISPATCH");
        }
        if (clientReceipt != ClientReceiptState.RECEIVED) {
            return rejected("CATALOG_PUBLICATION.APPLICATION_BEFORE_RECEIPT");
        }
        String diagnostic = requireDiagnostic(diagnosticCode);
        if (cacheApplication == CacheApplicationState.APPLIED) {
            return rejected("CATALOG_PUBLICATION.APPLICATION_ALREADY_APPLIED");
        }
        if (cacheApplication == CacheApplicationState.REJECTED) {
            return diagnostic.equals(this.diagnosticCode) ? accepted(this)
                : rejected("CATALOG_PUBLICATION.APPLICATION_ALREADY_REJECTED");
        }
        return accepted(new CatalogPublicationReceipt(sessionKey, ownerToken, ownerState, publicationKey, revision,
            dispatch, clientReceipt, CacheApplicationState.REJECTED, diagnostic));
    }

    CatalogPublicationReceipt withOwnerToken(String value) {
        requireOwnerToken(value);
        return new CatalogPublicationReceipt(sessionKey, value, OwnerState.CLAIMED, publicationKey, revision, dispatch,
            clientReceipt, cacheApplication, diagnosticCode);
    }

    public boolean serverDispatched() {
        return dispatch == DispatchState.DISPATCHED;
    }

    public boolean clientReceived() {
        return clientReceipt == ClientReceiptState.RECEIVED;
    }

    public boolean cacheApplied() {
        return cacheApplication == CacheApplicationState.APPLIED;
    }

    public boolean converged() {
        return serverDispatched() && clientReceived() && cacheApplied();
    }

    private static Transition accepted(CatalogPublicationReceipt receipt) {
        return new Transition(true, "", Optional.of(receipt));
    }

    private static Transition rejected(String code) {
        return new Transition(false, code, Optional.empty());
    }

    private static String sessionKey(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException("Publication session key must be non-blank and canonical");
        }
        return value;
    }

    static String requireOwnerToken(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException("Publication owner token must be non-blank and canonical");
        }
        return value;
    }

    private static String normalizeOwnerToken(String value) {
        return value == null ? null : requireOwnerToken(value);
    }

    private static String requireDiagnostic(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException("Publication diagnostic code must be non-blank and canonical");
        }
        return value;
    }

    private static void requireStateInvariant(DispatchState dispatch, ClientReceiptState clientReceipt,
                                              CacheApplicationState cacheApplication, String diagnosticCode) {
        if (!diagnosticCode.equals(diagnosticCode.strip())) {
            throw new IllegalArgumentException("Publication diagnostic code must be canonical");
        }
        switch (dispatch) {
            case NOT_ATTEMPTED -> {
                if (clientReceipt != ClientReceiptState.NOT_RECEIVED
                    || cacheApplication != CacheApplicationState.NOT_APPLIED
                    || !diagnosticCode.isEmpty()) {
                    throw new IllegalArgumentException("Not-attempted catalog publication has an invalid receipt state");
                }
            }
            case DISPATCHED -> {
                if ((clientReceipt == ClientReceiptState.NOT_RECEIVED
                        && cacheApplication != CacheApplicationState.NOT_APPLIED)
                    || (clientReceipt == ClientReceiptState.REJECTED
                        && cacheApplication != CacheApplicationState.NOT_APPLIED)
                    || (cacheApplication != CacheApplicationState.REJECTED && !diagnosticCode.isEmpty())
                    || (cacheApplication == CacheApplicationState.REJECTED
                        && clientReceipt != ClientReceiptState.RECEIVED)) {
                    throw new IllegalArgumentException("Dispatched catalog publication has an invalid receipt state");
                }
            }
            case FAILED -> {
                if (clientReceipt != ClientReceiptState.REJECTED
                    || cacheApplication != CacheApplicationState.REJECTED
                    || diagnosticCode.isEmpty()) {
                    throw new IllegalArgumentException("Failed catalog publication has an invalid receipt state");
                }
            }
        }
        if (cacheApplication == CacheApplicationState.APPLIED
            && (dispatch != DispatchState.DISPATCHED || clientReceipt != ClientReceiptState.RECEIVED)) {
            throw new IllegalArgumentException("Applied catalog publication requires dispatch and client receipt");
        }
        if (clientReceipt == ClientReceiptState.RECEIVED && dispatch != DispatchState.DISPATCHED) {
            throw new IllegalArgumentException("Client receipt requires server dispatch");
        }
    }

    public enum DispatchState {
        NOT_ATTEMPTED,
        DISPATCHED,
        FAILED
    }

    public enum OwnerState {
        CLAIMED,
        LEGACY_UNCLAIMED
    }

    public enum ClientReceiptState {
        NOT_RECEIVED,
        RECEIVED,
        REJECTED
    }

    public enum CacheApplicationState {
        NOT_APPLIED,
        APPLIED,
        REJECTED
    }

    public record Transition(boolean accepted, String code, Optional<CatalogPublicationReceipt> receipt) {
        public Transition {
            code = code == null ? "" : code;
            receipt = receipt == null ? Optional.empty() : receipt;
            if (accepted && receipt.isEmpty()) {
                throw new IllegalArgumentException("Accepted receipt transition must carry a receipt");
            }
            if (!accepted && code.isBlank()) {
                throw new IllegalArgumentException("Rejected receipt transition must carry a diagnostic code");
            }
        }
    }

    public record Claim(boolean accepted, String code, Optional<CatalogPublicationReceipt> receipt,
                        String previousOwnerToken) {
        public Claim {
            code = code == null ? "" : code;
            receipt = receipt == null ? Optional.empty() : receipt;
            if (accepted && receipt.isEmpty()) {
                throw new IllegalArgumentException("Accepted receipt claim must carry a receipt");
            }
            if (!accepted && code.isBlank()) {
                throw new IllegalArgumentException("Rejected receipt claim must carry a diagnostic code");
            }
            if (previousOwnerToken != null) {
                requireOwnerToken(previousOwnerToken);
            }
        }
    }

    public record BatchTransition(boolean accepted, String code,
                                  Map<String, Transition> outcomes) {
        public BatchTransition {
            code = code == null ? "" : code;
            outcomes = outcomes == null ? Map.of() : Map.copyOf(outcomes);
            if (accepted && outcomes.values().stream().anyMatch(outcome -> !outcome.accepted())) {
                throw new IllegalArgumentException("Accepted receipt batch transition cannot contain rejected outcomes");
            }
            if (!accepted && code.isBlank()) {
                throw new IllegalArgumentException("Rejected receipt batch transition must carry a diagnostic code");
            }
        }
    }
}
