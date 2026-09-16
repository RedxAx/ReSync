package restudio.resync.flow.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class RuntimeBoundaryHealth {
    private final FlowRuntimeSecurityBoundary security;
    private final StructuredRuntimeAuditBoundary audit;
    private final FlowRuntimeExecutionBoundary execution;
    private final RuntimeBindingRegistry registry;
    private final RuntimeReceiptStore receiptStore;
    private final DurableRuntimeReceiptStore observedReceipts;

    public RuntimeBoundaryHealth(
        FlowRuntimeSecurityBoundary security,
        StructuredRuntimeAuditBoundary audit,
        FlowRuntimeExecutionBoundary execution,
        RuntimeBindingRegistry registry,
        RuntimeReceiptStore receiptStore
    ) {
        this.security = Objects.requireNonNull(security, "Runtime Security Boundary Is Required");
        this.audit = Objects.requireNonNull(audit, "Runtime Audit Boundary Is Required");
        this.execution = Objects.requireNonNull(execution, "Runtime Execution Boundary Is Required");
        this.registry = Objects.requireNonNull(registry, "Runtime Binding Registry Is Required");
        this.receiptStore = Objects.requireNonNull(receiptStore, "Runtime Receipt Store Is Required");
        this.observedReceipts = receiptStore instanceof DurableRuntimeReceiptStore durable ? durable : null;
    }

    public record Prepared(RuntimeBoundaryHealth owner, RuntimeRegistrySnapshot runtime,
                           DurableRuntimeReceiptStore.Observation receipts, boolean receiptsStable,
                           boolean receiptAvailable, boolean receiptQuiesced, boolean receiptDurable,
                           int pendingAudits, StructuredRuntimeAuditBoundary.PreparedAvailability audit) {
    }

    public Prepared prepare() {
        RuntimeRegistrySnapshot runtime = registry.snapshot();
        DurableRuntimeReceiptStore.Observation before = observedReceipts == null ? null : observedReceipts.observation();
        boolean receiptAvailable = receiptStore.available();
        boolean receiptQuiesced = receiptStore.quiesced();
        boolean receiptDurable = receiptStore.durable();
        int pendingAudits = receiptStore.retryPendingAudits();
        StructuredRuntimeAuditBoundary.PreparedAvailability preparedAudit = audit.prepareAvailability();
        DurableRuntimeReceiptStore.Observation after = observedReceipts == null ? null : observedReceipts.observation();
        boolean stable = before != null && before == after && before.stable();
        return new Prepared(this, runtime, after, stable, receiptAvailable, receiptQuiesced, receiptDurable,
            pendingAudits, preparedAudit);
    }

    public Map<String, Object> snapshot(Prepared prepared) {
        RuntimeRegistrySnapshot runtime = registry.snapshot();
        boolean owned = prepared != null && prepared.owner() == this;
        boolean runtimeCurrent = owned && prepared.runtime() == runtime;
        boolean receiptsCurrent = runtimeCurrent && observedReceipts != null && prepared.receiptsStable()
            && observedReceipts.isCurrent(prepared.receipts());
        boolean auditCurrent = runtimeCurrent && audit.isCurrent(prepared.audit());
        boolean receiptAvailable = receiptsCurrent && prepared.receiptAvailable();
        boolean receiptQuiesced = owned && prepared.receiptQuiesced();
        boolean receiptDurable = owned && prepared.receiptDurable();
        int pendingAudits = owned ? prepared.pendingAudits() : 0;
        Map<String, Object> boundaries = new LinkedHashMap<>();
        boundaries.put("security", Map.of(
            "available", security.available(),
            "trustedAuthority", security.trustedAuthority().identity()));
        boundaries.put("audit", Map.of(
            "available", auditCurrent && prepared.audit().available() && receiptAvailable && !receiptQuiesced,
            "durable", receiptDurable,
            "quiesced", receiptQuiesced,
            "pending", pendingAudits,
            "degraded", pendingAudits > 0 || !receiptsCurrent || !auditCurrent,
            "current", receiptsCurrent && auditCurrent));
        boundaries.put("execution", Map.of(
            "available", execution.available()));
        boundaries.put("receipts", Map.of(
            "available", receiptAvailable,
            "durable", receiptDurable,
            "quiesced", receiptQuiesced,
            "pendingAudits", pendingAudits,
            "current", receiptsCurrent));
        boundaries.put("registry", Map.of(
            "available", true,
            "generation", runtime.generation(),
            "bindings", runtime.bindings().size(),
            "providers", runtime.providers().size()));
        return Map.copyOf(boundaries);
    }

    public boolean ready() {
        return security.available()
            && audit.available()
            && execution.available()
            && receiptStore.available()
            && !receiptStore.quiesced()
            && receiptStore.retryPendingAudits() == 0;
    }

    public Map<String, Object> snapshot() {
        RuntimeRegistrySnapshot runtime = registry.snapshot();
        boolean receiptAvailable = receiptStore.available();
        boolean receiptQuiesced = receiptStore.quiesced();
        boolean receiptDurable = receiptStore.durable();
        int pendingAudits = receiptStore.retryPendingAudits();
        Map<String, Object> boundaries = new LinkedHashMap<>();
        boundaries.put("security", Map.of(
            "available", security.available(),
            "trustedAuthority", security.trustedAuthority().identity()));
        boundaries.put("audit", Map.of(
            "available", audit.available() && receiptAvailable && !receiptQuiesced,
            "durable", receiptDurable,
            "quiesced", receiptQuiesced,
            "pending", pendingAudits,
            "degraded", pendingAudits > 0));
        boundaries.put("execution", Map.of(
            "available", execution.available()));
        boundaries.put("receipts", Map.of(
            "available", receiptAvailable,
            "durable", receiptDurable,
            "quiesced", receiptQuiesced,
            "pendingAudits", pendingAudits));
        boundaries.put("registry", Map.of(
            "available", true,
            "generation", runtime.generation(),
            "bindings", runtime.bindings().size(),
            "providers", runtime.providers().size()));
        return Map.copyOf(boundaries);
    }
}
