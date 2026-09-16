package restudio.resync.flow.runtime;

import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.flow.identity.ProviderId;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.OptionalLong;

public interface RuntimeReceiptStore {
    enum IdempotencyKind {
        MUTATION_ID,
        OPERATION_KEY
    }

    Claim claim(Key key, ContentHash inputHash);

    default boolean available() {
        return true;
    }

    default boolean quiesced() {
        return false;
    }

    default InvocationLease acquireInvocationLease() {
        return InvocationLease.NOOP;
    }

    default void reserve(Key key, ContentHash inputHash, RuntimeExecutionProvenance provenance,
                         RuntimeLeaseInput.AuditEvent auditAttempt) {
    }

    default void complete(Key key, RuntimeResult result, RuntimeExecutionProvenance provenance,
                          RuntimeLeaseInput.AuditEvent auditEvent) {
    }

    default void recordPendingAudit(Key key, RuntimeExecutionProvenance provenance,
                                    RuntimeLeaseInput.AuditEvent auditEvent, Throwable failure) {
    }

    default void markAuditRecorded(Key key, RuntimeLeaseInput.AuditEvent auditEvent) {
    }

    default int retryPendingAudits(RuntimeAuditBoundary boundary) {
        return retryPendingAudits();
    }

    default int retryPendingAudits() {
        return 0;
    }

    default OptionalLong storedDeadline(String authorityIdentity, String principalReference, CorrelationId invocationId) {
        return OptionalLong.empty();
    }

    default void flush() throws IOException {
    }

    default void quiesce() throws IOException {
    }

    default void resume() throws IOException {
    }

    default void rebind(java.nio.file.Path activeRoot) throws IOException {
    }

    default void healthCheck() throws IOException {
    }

    boolean durable();

    void releaseProvider(ContractRef<ProviderId> provider);

    static RuntimeReceiptStore inMemory(boolean durable) {
        return new Memory(durable);
    }

    record Key(
        ContractRef<ProviderId> provider,
        ContentHash planFingerprint,
        RuntimeBindingKey binding,
        ContentHash executionFingerprint,
        String authorityIdentity,
        String idempotencyKey,
        String principalReference,
        IdempotencyKind idempotencyKind
    ) {
        public Key(
            ContractRef<ProviderId> provider,
            ContentHash planFingerprint,
            RuntimeBindingKey binding,
            ContentHash executionFingerprint,
            String authorityIdentity,
            String idempotencyKey
        ) {
            this(provider, planFingerprint, binding, executionFingerprint, authorityIdentity, idempotencyKey, "",
                IdempotencyKind.OPERATION_KEY);
        }

        public Key(
            ContractRef<ProviderId> provider,
            ContentHash planFingerprint,
            RuntimeBindingKey binding,
            ContentHash executionFingerprint,
            String authorityIdentity,
            String idempotencyKey,
            String principalReference
        ) {
            this(provider, planFingerprint, binding, executionFingerprint, authorityIdentity, idempotencyKey,
                principalReference, IdempotencyKind.OPERATION_KEY);
        }

        public Key(
            ContractRef<ProviderId> provider,
            ContentHash planFingerprint,
            RuntimeBindingKey binding,
            ContentHash executionFingerprint,
            String authorityIdentity,
            String idempotencyKey,
            IdempotencyKind idempotencyKind,
            String principalReference
        ) {
            this(provider, planFingerprint, binding, executionFingerprint, authorityIdentity, idempotencyKey,
                principalReference, idempotencyKind);
        }

        public Key {
            provider = Objects.requireNonNull(provider, "Receipt Provider Is Required");
            planFingerprint = Objects.requireNonNull(planFingerprint, "Receipt Plan Fingerprint Is Required");
            binding = Objects.requireNonNull(binding, "Receipt Binding Is Required");
            executionFingerprint = Objects.requireNonNull(executionFingerprint, "Receipt Execution Fingerprint Is Required");
            authorityIdentity = Objects.requireNonNull(authorityIdentity, "Receipt Authority Is Required");
            idempotencyKey = Objects.requireNonNull(idempotencyKey, "Receipt Idempotency Key Is Required");
            principalReference = Objects.requireNonNull(principalReference, "Receipt Principal Is Required");
            idempotencyKind = Objects.requireNonNull(idempotencyKind, "Receipt Idempotency Kind Is Required");
        }

        public boolean sameOperation(Key other) {
            Objects.requireNonNull(other, "Receipt Key Is Required");
            if (idempotencyKind != other.idempotencyKind
                || !authorityIdentity.equals(other.authorityIdentity)
                || !idempotencyKey.equals(other.idempotencyKey)) {
                return false;
            }
            if (idempotencyKind == IdempotencyKind.MUTATION_ID) {
                return true;
            }
            return provider.equals(other.provider)
                && planFingerprint.equals(other.planFingerprint)
                && binding.equals(other.binding)
                && executionFingerprint.equals(other.executionFingerprint);
        }
    }

    record Claim(
        CompletableFuture<RuntimeResult> outcome,
        boolean owner,
        boolean inputMatches,
        boolean principalMatches
    ) {
        public Claim(CompletableFuture<RuntimeResult> outcome, boolean owner, boolean inputMatches) {
            this(outcome, owner, inputMatches, true);
        }

        public Claim {
            outcome = Objects.requireNonNull(outcome, "Receipt Outcome Is Required");
        }
    }

    interface InvocationLease extends AutoCloseable {
        InvocationLease NOOP = () -> {
        };

        @Override
        void close();
    }

    final class Memory implements RuntimeReceiptStore {
        private final ConcurrentHashMap<Key, Stored> receipts = new ConcurrentHashMap<>();
        private final Object monitor = new Object();
        private final boolean durable;

        private Memory(boolean durable) {
            this.durable = durable;
        }

        @Override
        public Claim claim(Key key, ContentHash inputHash) {
            Objects.requireNonNull(key, "Receipt Key Is Required");
            Objects.requireNonNull(inputHash, "Receipt Input Hash Is Required");
            synchronized (monitor) {
                Stored exact = receipts.get(key);
                if (exact != null) {
                    return new Claim(exact.outcome, false, exact.inputHash.equals(inputHash), true);
                }
                Map.Entry<Key, Stored> equivalent = receipts.entrySet().stream()
                    .filter(entry -> entry.getKey().sameOperation(key))
                    .findFirst()
                    .orElse(null);
                if (equivalent != null) {
                    Stored selected = equivalent.getValue();
                    boolean principalMatches = equivalent.getKey().principalReference().equals(key.principalReference());
                    return new Claim(principalMatches ? selected.outcome : new CompletableFuture<>(), false,
                        selected.inputHash.equals(inputHash),
                        principalMatches);
                }
                Stored candidate = new Stored(inputHash, new CompletableFuture<>());
                receipts.put(key, candidate);
                return new Claim(candidate.outcome, true, true, true);
            }
        }

        @Override
        public void reserve(Key key, ContentHash inputHash, RuntimeExecutionProvenance provenance,
                            RuntimeLeaseInput.AuditEvent auditAttempt) {
            Objects.requireNonNull(key, "Receipt Key Is Required");
            Objects.requireNonNull(inputHash, "Receipt Input Hash Is Required");
            synchronized (monitor) {
                Stored exact = receipts.get(key);
                if (exact == null) {
                    Map.Entry<Key, Stored> equivalent = receipts.entrySet().stream()
                        .filter(entry -> entry.getKey().sameOperation(key))
                        .findFirst()
                        .orElse(null);
                    if (equivalent != null) {
                        if (!equivalent.getKey().principalReference().equals(key.principalReference())) {
                            throw new IllegalStateException("Runtime Idempotency Key Belongs To Another Principal");
                        }
                        if (!equivalent.getValue().inputHash.equals(inputHash)) {
                            throw new IllegalStateException("Runtime Idempotency Key Was Reused With Different Inputs");
                        }
                        return;
                    }
                    exact = new Stored(inputHash, new CompletableFuture<>());
                    receipts.put(key, exact);
                }
                if (!exact.inputHash.equals(inputHash)) {
                    throw new IllegalStateException("Runtime Idempotency Key Was Reused With Different Inputs");
                }
            }
        }

        @Override
        public void complete(Key key, RuntimeResult result, RuntimeExecutionProvenance provenance,
                             RuntimeLeaseInput.AuditEvent auditEvent) {
            Objects.requireNonNull(key, "Receipt Key Is Required");
            Objects.requireNonNull(result, "Runtime Result Is Required");
            synchronized (monitor) {
                Stored entry = receipts.get(key);
                if (entry == null) {
                    throw new IllegalStateException("Runtime Outcome Has No Durable Reservation");
                }
                if (entry.outcomeValue != null && !entry.outcomeValue.canonicalJson().equals(result.canonicalJson())) {
                    throw new IllegalStateException("Runtime Receipt Outcome Changed After Completion");
                }
                entry.outcomeValue = result;
                entry.outcome.complete(result);
            }
        }

        @Override
        public boolean durable() {
            return durable;
        }

        @Override
        public void releaseProvider(ContractRef<ProviderId> provider) {
            if (!durable) {
                synchronized (monitor) {
                    receipts.keySet().removeIf(key -> key.provider().equals(provider));
                }
            }
        }

        private static final class Stored {
            private final ContentHash inputHash;
            private final CompletableFuture<RuntimeResult> outcome;
            private RuntimeResult outcomeValue;

            private Stored(ContentHash inputHash, CompletableFuture<RuntimeResult> outcome) {
                this.inputHash = inputHash;
                this.outcome = outcome;
            }
        }
    }
}
