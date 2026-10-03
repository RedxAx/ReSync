package restudio.resync.flow.runtime;

import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.graph.FunctionBinding;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public interface RuntimeLeaseInput {
    List<BindingRequirement> bindings();

    default List<FunctionBinding> functionBindings() {
        return List.of();
    }

    default RuntimeScope scope() {
        return null;
    }

    default RuntimeLeaseInput withFunctionBindings(List<FunctionBinding> functions) {
        return new Default(bindings(), planFingerprint(), authority(), principal(), catalogGeneration(), catalogHash(),
            mutationId(), invocationId(), requestedDeadlineMillis(), functions, scope());
    }

    default RuntimeLeaseInput withScope(RuntimeScope scope) {
        return new Default(bindings(), planFingerprint(), authority(), principal(), catalogGeneration(), catalogHash(),
            mutationId(), invocationId(), requestedDeadlineMillis(), functionBindings(), scope);
    }

    default ContentHash planFingerprint() {
        return null;
    }

    default RuntimeAuthority authority() {
        return RuntimeAuthority.anonymous();
    }

    default RuntimePrincipal principal() {
        return null;
    }

    default long catalogGeneration() {
        return -1;
    }

    default ContentHash catalogHash() {
        return null;
    }

    default String mutationId() {
        return null;
    }

    default CorrelationId invocationId() {
        return null;
    }

    default long requestedDeadlineMillis() {
        return RuntimeExecutionContext.NO_DEADLINE;
    }

    static RuntimeLeaseInput of(Collection<BindingRequirement> bindings) {
        return new Default(List.copyOf(Objects.requireNonNull(bindings, "Lease Bindings Are Required")), null,
            RuntimeAuthority.anonymous(), null, -1, null, null, CorrelationId.random());
    }

    static RuntimeLeaseInput plan(Collection<BindingRequirement> bindings, ContentHash planFingerprint, RuntimeAuthority authority) {
        return new Default(
            List.copyOf(Objects.requireNonNull(bindings, "Lease Bindings Are Required")),
            Objects.requireNonNull(planFingerprint, "Plan Fingerprint Is Required"),
            Objects.requireNonNull(authority, "Runtime Authority Is Required"), null, -1, null, null,
            CorrelationId.random());
    }

    static RuntimeLeaseInput plan(Collection<BindingRequirement> bindings, ContentHash planFingerprint,
                                  RuntimeAuthority authority, RuntimePrincipal principal) {
        return new Default(
            List.copyOf(Objects.requireNonNull(bindings, "Lease Bindings Are Required")),
            Objects.requireNonNull(planFingerprint, "Plan Fingerprint Is Required"),
            Objects.requireNonNull(authority, "Runtime Authority Is Required"), principal, -1, null, null,
            CorrelationId.random());
    }

    static RuntimeLeaseInput plan(Collection<BindingRequirement> bindings, ContentHash planFingerprint,
                                  RuntimeAuthority authority, RuntimePrincipal principal,
                                  long catalogGeneration, ContentHash catalogHash, String mutationId) {
        return new Default(
            List.copyOf(Objects.requireNonNull(bindings, "Lease Bindings Are Required")),
            Objects.requireNonNull(planFingerprint, "Plan Fingerprint Is Required"),
            Objects.requireNonNull(authority, "Runtime Authority Is Required"), principal,
            catalogGeneration, catalogHash, mutationId, CorrelationId.random());
    }

    static RuntimeLeaseInput plan(Collection<BindingRequirement> bindings, ContentHash planFingerprint,
                                  RuntimeAuthority authority, RuntimePrincipal principal,
                                  long catalogGeneration, ContentHash catalogHash, String mutationId,
                                  CorrelationId invocationId) {
        return new Default(
            List.copyOf(Objects.requireNonNull(bindings, "Lease Bindings Are Required")),
            Objects.requireNonNull(planFingerprint, "Plan Fingerprint Is Required"),
            Objects.requireNonNull(authority, "Runtime Authority Is Required"), principal,
            catalogGeneration, catalogHash, mutationId, Objects.requireNonNull(invocationId, "Invocation ID Is Required"));
    }

    static RuntimeLeaseInput plan(Collection<BindingRequirement> bindings, ContentHash planFingerprint,
                                  RuntimeAuthority authority, RuntimePrincipal principal,
                                  long catalogGeneration, ContentHash catalogHash, String mutationId,
                                  CorrelationId invocationId, long requestedDeadlineMillis) {
        return new Default(
            List.copyOf(Objects.requireNonNull(bindings, "Lease Bindings Are Required")),
            Objects.requireNonNull(planFingerprint, "Plan Fingerprint Is Required"),
            Objects.requireNonNull(authority, "Runtime Authority Is Required"), principal,
            catalogGeneration, catalogHash, mutationId, Objects.requireNonNull(invocationId, "Invocation ID Is Required"),
            requestedDeadlineMillis);
    }

    record AuditEvent(
        UUID leaseId,
        String authorityReference,
        String bindingReference,
        String idempotencyReference,
        RuntimeResult.Status status,
        boolean sensitive,
        String phase,
        RuntimeExecutionProvenance provenance,
        UUID deliveryId
    ) {
        public AuditEvent(UUID leaseId, String authorityReference, String bindingReference,
                          String idempotencyReference, RuntimeResult.Status status, boolean sensitive) {
            this(leaseId, authorityReference, bindingReference, idempotencyReference, status, sensitive, "outcome", null, null);
        }

        public AuditEvent(UUID leaseId, String authorityReference, String bindingReference,
                          String idempotencyReference, RuntimeResult.Status status, boolean sensitive,
                          String phase, RuntimeExecutionProvenance provenance) {
            this(leaseId, authorityReference, bindingReference, idempotencyReference, status, sensitive,
                phase, provenance, null);
        }

        public AuditEvent {
            leaseId = Objects.requireNonNull(leaseId, "Lease ID Is Required");
            authorityReference = reference(authorityReference, "Audit Authority Reference Is Required");
            bindingReference = reference(bindingReference, "Audit Binding Reference Is Required");
            idempotencyReference = reference(idempotencyReference, "Idempotency Reference Is Required");
            status = Objects.requireNonNull(status, "Audit Status Is Required");
            phase = Objects.requireNonNull(phase, "Audit Phase Is Required").trim();
            if (phase.isEmpty()) {
                throw new IllegalArgumentException("Audit Phase Is Required");
            }
            deliveryId = deliveryId == null ? deliveryId(leaseId, authorityReference, bindingReference,
                idempotencyReference, phase, provenance) : deliveryId;
        }

        private static UUID deliveryId(UUID leaseId, String authorityReference, String bindingReference,
                                       String idempotencyReference, String phase,
                                       RuntimeExecutionProvenance provenance) {
            String identity = provenance == null
                ? leaseId + "|" + authorityReference + "|" + bindingReference + "|" + idempotencyReference + "|" + phase
                : provenance.invocationId().canonicalText() + "|" + provenance.idempotencyKey() + "|"
                    + provenance.binding().canonical() + "|" + phase;
            return UUID.nameUUIDFromBytes(("runtime-audit-delivery|" + identity)
                .getBytes(StandardCharsets.UTF_8));
        }

        private static String reference(String value, String label) {
            String normalized = Objects.requireNonNull(value, label).trim();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException(label);
            }
            return normalized;
        }
    }

    record BindingRequirement(
        RuntimeBindingKey binding,
        ContentHash fingerprint,
        List<PinId> inputPins,
        List<PinId> outputPins
    ) {
        public BindingRequirement {
            binding = Objects.requireNonNull(binding, "Binding Is Required");
            fingerprint = Objects.requireNonNull(fingerprint, "Binding Fingerprint Is Required");
            inputPins = immutablePins(inputPins, "Input Pins");
            outputPins = immutablePins(outputPins, "Output Pins");
            if (Set.copyOf(inputPins).size() != inputPins.size() || Set.copyOf(outputPins).size() != outputPins.size()) {
                throw new IllegalArgumentException("Lease Pin Identities Must Be Unique");
            }
        }

        public BindingRequirement(RuntimeBindingKey binding, ContentHash fingerprint) {
            this(binding, fingerprint, List.of(), List.of());
        }

        public ContentHash executionFingerprint() {
            return fingerprint;
        }

        private static List<PinId> immutablePins(List<PinId> values, String label) {
            return List.copyOf(Objects.requireNonNull(values, label + " Are Required").stream()
                .map(value -> Objects.requireNonNull(value, label + " Cannot Contain Null"))
                .toList());
        }
    }

    record Default(
        List<BindingRequirement> bindings,
        ContentHash planFingerprint,
        RuntimeAuthority authority,
        RuntimePrincipal principal,
        long catalogGeneration,
        ContentHash catalogHash,
        String mutationId,
        CorrelationId invocationId,
        long requestedDeadlineMillis,
        List<FunctionBinding> functionBindings,
        RuntimeScope scope
    ) implements RuntimeLeaseInput {
        public Default(List<BindingRequirement> bindings, ContentHash planFingerprint, RuntimeAuthority authority,
                       RuntimePrincipal principal, long catalogGeneration, ContentHash catalogHash, String mutationId,
                       CorrelationId invocationId, long requestedDeadlineMillis) {
            this(bindings, planFingerprint, authority, principal, catalogGeneration, catalogHash, mutationId,
                invocationId, requestedDeadlineMillis, List.of(), null);
        }
        public Default(List<BindingRequirement> bindings, ContentHash planFingerprint, RuntimeAuthority authority) {
            this(bindings, planFingerprint, authority, null, -1, null, null, CorrelationId.random(),
                RuntimeExecutionContext.NO_DEADLINE);
        }

        public Default(List<BindingRequirement> bindings, ContentHash planFingerprint, RuntimeAuthority authority,
                       RuntimePrincipal principal, long catalogGeneration, ContentHash catalogHash, String mutationId,
                       CorrelationId invocationId) {
            this(bindings, planFingerprint, authority, principal, catalogGeneration, catalogHash, mutationId,
                invocationId, RuntimeExecutionContext.NO_DEADLINE);
        }

        public Default {
            bindings = List.copyOf(Objects.requireNonNull(bindings, "Lease Bindings Are Required"));
            functionBindings = List.copyOf(Objects.requireNonNull(functionBindings, "Lease Function Bindings Are Required"));
            authority = Objects.requireNonNull(authority, "Runtime Authority Is Required");
            invocationId = Objects.requireNonNull(invocationId, "Invocation ID Is Required");
            if (requestedDeadlineMillis < 0) {
                throw new IllegalArgumentException("Requested Deadline Cannot Be Negative");
            }
            if (catalogGeneration < -1) {
                throw new IllegalArgumentException("Catalog Generation Cannot Be Less Than Minus One");
            }
            if (mutationId != null && mutationId.isBlank()) {
                throw new IllegalArgumentException("Mutation ID Cannot Be Blank");
            }
        }
    }
}
