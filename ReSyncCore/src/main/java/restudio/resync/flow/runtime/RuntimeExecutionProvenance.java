package restudio.resync.flow.runtime;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.flow.identity.ProviderId;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record RuntimeExecutionProvenance(
    String authorityIdentity,
    RuntimePrincipal principal,
    RuntimeBindingKey binding,
    ContractRef<ProviderId> provider,
    String providerVersion,
    long runtimeGeneration,
    ContentHash runtimeManifestHash,
    long catalogGeneration,
    ContentHash catalogHash,
    ContentHash planFingerprint,
    ContentHash executionFingerprint,
    String idempotencyKey,
    CorrelationId invocationId,
    String mutationId,
    ContentHash inputHash,
    ContentHash contextHash,
    long deadlineMillis,
    UUID leaseId,
    String sessionReference,
    String creatorPrincipal,
    String creatorSessionReference
) {
    public RuntimeExecutionProvenance(
        String authorityIdentity,
        RuntimePrincipal principal,
        RuntimeBindingKey binding,
        ContractRef<ProviderId> provider,
        String providerVersion,
        long runtimeGeneration,
        ContentHash runtimeManifestHash,
        long catalogGeneration,
        ContentHash catalogHash,
        ContentHash planFingerprint,
        ContentHash executionFingerprint,
        String idempotencyKey,
        String mutationId
    ) {
        this(authorityIdentity, principal, binding, provider, providerVersion, runtimeGeneration, runtimeManifestHash,
            catalogGeneration, catalogHash, planFingerprint, executionFingerprint, idempotencyKey,
            CorrelationId.deterministic("legacy-runtime-invocation:" + idempotencyKey), mutationId, null,
            emptyContextHash(), RuntimeExecutionContext.NO_DEADLINE, null, null, null, null);
    }

    public RuntimeExecutionProvenance(
        String authorityIdentity,
        RuntimePrincipal principal,
        RuntimeBindingKey binding,
        ContractRef<ProviderId> provider,
        String providerVersion,
        long runtimeGeneration,
        ContentHash runtimeManifestHash,
        long catalogGeneration,
        ContentHash catalogHash,
        ContentHash planFingerprint,
        ContentHash executionFingerprint,
        String idempotencyKey,
        CorrelationId invocationId,
        String mutationId,
        ContentHash inputHash,
        ContentHash contextHash,
        long deadlineMillis,
        UUID leaseId
    ) {
        this(authorityIdentity, principal, binding, provider, providerVersion, runtimeGeneration, runtimeManifestHash,
            catalogGeneration, catalogHash, planFingerprint, executionFingerprint, idempotencyKey, invocationId,
            mutationId, inputHash, contextHash, deadlineMillis, leaseId, null, null, null);
    }

    public RuntimeExecutionProvenance(
        String authorityIdentity,
        RuntimePrincipal principal,
        RuntimeBindingKey binding,
        ContractRef<ProviderId> provider,
        String providerVersion,
        long runtimeGeneration,
        ContentHash runtimeManifestHash,
        long catalogGeneration,
        ContentHash catalogHash,
        ContentHash planFingerprint,
        ContentHash executionFingerprint,
        String idempotencyKey,
        CorrelationId invocationId,
        String mutationId,
        ContentHash inputHash,
        ContentHash contextHash,
        long deadlineMillis,
        UUID leaseId,
        String sessionReference
    ) {
        this(authorityIdentity, principal, binding, provider, providerVersion, runtimeGeneration, runtimeManifestHash,
            catalogGeneration, catalogHash, planFingerprint, executionFingerprint, idempotencyKey, invocationId,
            mutationId, inputHash, contextHash, deadlineMillis, leaseId, sessionReference, null, null);
    }

    public RuntimeExecutionProvenance {
        authorityIdentity = requireText(authorityIdentity, "Provenance Authority Identity");
        principal = Objects.requireNonNull(principal, "Provenance Principal Is Required");
        if (!authorityIdentity.equals(principal.authorityIdentity())) {
            throw new IllegalArgumentException("Provenance Principal Does Not Belong To Its Authority");
        }
        binding = Objects.requireNonNull(binding, "Provenance Binding Is Required");
        provider = Objects.requireNonNull(provider, "Provenance Provider Is Required");
        providerVersion = requireText(providerVersion, "Provenance Provider Version");
        if (runtimeGeneration < 0 || catalogGeneration < -1) {
            throw new IllegalArgumentException("Provenance Generations Are Invalid");
        }
        runtimeManifestHash = Objects.requireNonNull(runtimeManifestHash, "Provenance Runtime Manifest Hash Is Required");
        if ((catalogGeneration < 0) != (catalogHash == null)) {
            throw new IllegalArgumentException("Catalog Provenance Generation And Hash Must Agree");
        }
        planFingerprint = Objects.requireNonNull(planFingerprint, "Provenance Plan Fingerprint Is Required");
        executionFingerprint = Objects.requireNonNull(executionFingerprint, "Provenance Execution Fingerprint Is Required");
        idempotencyKey = requireText(idempotencyKey, "Provenance Idempotency Key");
        invocationId = Objects.requireNonNull(invocationId, "Provenance Invocation ID Is Required");
        mutationId = mutationId == null ? null : requireText(mutationId, "Provenance Mutation ID");
        inputHash = inputHash == null ? null : Objects.requireNonNull(inputHash, "Provenance Input Hash Is Required");
        contextHash = Objects.requireNonNull(contextHash, "Provenance Runtime Context Hash Is Required");
        if (deadlineMillis < 0) {
            throw new IllegalArgumentException("Provenance Deadline Cannot Be Negative");
        }
        sessionReference = sessionReference == null ? null : requireText(sessionReference, "Provenance Session Reference");
        creatorPrincipal = creatorPrincipal == null ? null : requireText(creatorPrincipal, "Provenance Creator Principal");
        creatorSessionReference = creatorSessionReference == null
            ? null : requireText(creatorSessionReference, "Provenance Creator Session Reference");
        if (creatorPrincipal == null && creatorSessionReference != null) {
            throw new IllegalArgumentException("Provenance Creator Session Requires A Creator Principal");
        }
    }

    public Map<String, Object> canonicalValue() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("authority", authorityIdentity);
        value.put("principal", Map.of("kind", principal.kind().name().toLowerCase(Locale.ROOT), "identity", principal.identity()));
        value.put("binding", binding.canonicalValue());
        value.put("provider", provider.canonicalValue());
        value.put("providerVersion", providerVersion);
        value.put("runtimeGeneration", runtimeGeneration);
        value.put("runtimeManifestHash", runtimeManifestHash.canonicalText());
        value.put("catalogGeneration", catalogGeneration);
        if (catalogHash != null) {
            value.put("catalogHash", catalogHash.canonicalText());
        }
        value.put("planFingerprint", planFingerprint.canonicalText());
        value.put("executionFingerprint", executionFingerprint.canonicalText());
        value.put("idempotencyKey", idempotencyKey);
        value.put("invocationId", invocationId.canonicalText());
        if (mutationId != null) {
            value.put("mutationId", mutationId);
        }
        if (inputHash != null) {
            value.put("inputHash", inputHash.canonicalText());
        }
        value.put("contextHash", contextHash.canonicalText());
        value.put("deadlineMillis", deadlineMillis);
        if (leaseId != null) {
            value.put("leaseId", leaseId.toString());
        }
        if (sessionReference != null) {
            value.put("sessionReference", sessionReference);
        }
        if (creatorPrincipal != null) {
            value.put("creatorPrincipal", creatorPrincipal);
        }
        if (creatorSessionReference != null) {
            value.put("creatorSessionReference", creatorSessionReference);
        }
        return Map.copyOf(value);
    }

    private static ContentHash emptyContextHash() {
        return ContentHash.of(CanonicalJson.sha256("runtime-context", Map.of()));
    }

    private static String requireText(String value, String label) {
        String normalized = Objects.requireNonNull(value, label + " Is Required").trim();
        if (normalized.isEmpty() || normalized.length() > 512 || normalized.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException(label + " Must Be Canonical Text");
        }
        return normalized;
    }
}
