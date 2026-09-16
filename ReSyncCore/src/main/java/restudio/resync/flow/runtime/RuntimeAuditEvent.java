package restudio.resync.flow.runtime;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.ContentHash;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

public final class RuntimeAuditEvent {
    private final RuntimeLeaseInput.AuditEvent event;

    private RuntimeAuditEvent(RuntimeLeaseInput.AuditEvent event) {
        this.event = event;
    }

    public static RuntimeAuditEvent create(
        UUID leaseId,
        RuntimeAuthority authority,
        RuntimeBindingKey binding,
        String idempotencyKey,
        RuntimeResult.Status status,
        boolean sensitive
    ) {
        return create(leaseId, authority, binding, idempotencyKey, status, sensitive, "outcome", null);
    }

    public static RuntimeAuditEvent create(
        UUID leaseId,
        RuntimeAuthority authority,
        RuntimeBindingKey binding,
        String idempotencyKey,
        RuntimeResult.Status status,
        boolean sensitive,
        String phase,
        RuntimeExecutionProvenance provenance
    ) {
        String authorityReference = reference("runtime-audit-authority", Map.of("authority", authority.identity()));
        String bindingReference = reference("runtime-audit-binding", Map.of("binding", binding.canonical()));
        String idempotencyReference = reference("runtime-idempotency", Map.of(
            "authorityReference", authorityReference,
            "bindingReference", bindingReference,
            "key", idempotencyKey));
        UUID opaqueLeaseId = UUID.nameUUIDFromBytes(reference("runtime-audit-lease", Map.of(
            "leaseId", leaseId.toString(),
            "authorityReference", authorityReference,
            "bindingReference", bindingReference)).getBytes(StandardCharsets.UTF_8));
        return new RuntimeAuditEvent(new RuntimeLeaseInput.AuditEvent(
            opaqueLeaseId,
            authorityReference,
            bindingReference,
            idempotencyReference,
            status,
            sensitive,
            phase,
            provenance));
    }

    public RuntimeLeaseInput.AuditEvent leaseEvent() {
        return event;
    }

    private static String reference(String domain, Map<String, Object> value) {
        return ContentHash.of(CanonicalJson.sha256(domain, value)).canonicalText();
    }
}
