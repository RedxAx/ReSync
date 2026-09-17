package restudio.resync.flow.runtime;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ResourceTypeId;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public record RuntimeSemantics(
    Effect effect,
    ThreadMode thread,
    ContractRef<CapabilityId> authorization,
    Cancellation cancellation,
    long timeoutMillis,
    long drainDeadlineMillis,
    long hardDeadlineMillis,
    UnloadPolicy unloadPolicy,
    Retry retry,
    Idempotency idempotency,
    Audit audit,
    Confirmation confirmation,
    SensitiveData sensitiveData,
    Determinism determinism,
    Set<String> successBranches,
    Set<String> failureBranches,
    Set<String> cancellationBranches,
    RuntimeFailureContract failureContract,
    Set<ContractRef<ResourceTypeId>> resourceReads,
    Set<ContractRef<ResourceTypeId>> resourceWrites,
    Map<String, Object> unknown
) {
    public enum Effect {
        PURE,
        STATE_READING,
        STATE_MUTATING,
        EXTERNAL_IO,
        SCHEDULING,
        DESTRUCTIVE;

        public String wireValue() {
            return switch (this) {
                case PURE -> "pure";
                case STATE_READING -> "state-reading";
                case STATE_MUTATING -> "state-mutating";
                case EXTERNAL_IO -> "external-I/O";
                case SCHEDULING -> "scheduling";
                case DESTRUCTIVE -> "destructive";
            };
        }
    }

    public enum ThreadMode {
        MAIN,
        ASYNCHRONOUS,
        CURRENT,
        COMPOSED;

        public String wireValue() {
            return name().toLowerCase();
        }
    }

    public enum Cancellation {
        NONE,
        COOPERATIVE,
        INTERRUPTIBLE,
        SECURITY_REVOCABLE;

        public String wireValue() {
            return switch (this) {
                case NONE -> "none";
                case COOPERATIVE -> "cooperative";
                case INTERRUPTIBLE -> "interruptible";
                case SECURITY_REVOCABLE -> "security-revocable";
            };
        }
    }

    public enum UnloadPolicy {
        DRAIN,
        SECURITY_CANCEL,
        BLOCK;

        public String wireValue() {
            return switch (this) {
                case DRAIN -> "drain";
                case SECURITY_CANCEL -> "security-cancel";
                case BLOCK -> "block";
            };
        }
    }

    public enum Retry {
        NEVER,
        SAFE,
        POLICY;

        public String wireValue() {
            return name().toLowerCase();
        }
    }

    public enum Idempotency {
        NONE,
        MUTATION_ID,
        OPERATION_KEY,
        INTRINSIC;

        public String wireValue() {
            return switch (this) {
                case NONE -> "none";
                case MUTATION_ID -> "mutation-id";
                case OPERATION_KEY -> "operation-key";
                case INTRINSIC -> "intrinsic";
            };
        }
    }

    public enum Audit {
        NONE,
        METADATA,
        FULL_REDACTED;

        public String wireValue() {
            return switch (this) {
                case NONE -> "none";
                case METADATA -> "metadata";
                case FULL_REDACTED -> "full-redacted";
            };
        }
    }

    public enum Confirmation {
        NONE,
        CLIENT,
        SERVER;

        public String wireValue() {
            return name().toLowerCase();
        }
    }

    public enum SensitiveData {
        NONE,
        REDACTED,
        SECRET;

        public String wireValue() {
            return name().toLowerCase();
        }
    }

    public enum Determinism {
        DETERMINISTIC,
        ENVIRONMENT,
        NONDETERMINISTIC;

        public String wireValue() {
            return name().toLowerCase();
        }
    }

    public RuntimeSemantics {
        effect = Objects.requireNonNull(effect, "Effect Is Required");
        thread = Objects.requireNonNull(thread, "Thread Mode Is Required");
        authorization = Objects.requireNonNull(authorization, "Authorization Is Required");
        cancellation = Objects.requireNonNull(cancellation, "Cancellation Is Required");
        unloadPolicy = Objects.requireNonNull(unloadPolicy, "Unload Policy Is Required");
        retry = Objects.requireNonNull(retry, "Retry Policy Is Required");
        idempotency = Objects.requireNonNull(idempotency, "Idempotency Policy Is Required");
        audit = Objects.requireNonNull(audit, "Audit Policy Is Required");
        confirmation = Objects.requireNonNull(confirmation, "Confirmation Policy Is Required");
        sensitiveData = Objects.requireNonNull(sensitiveData, "Sensitive Data Policy Is Required");
        determinism = Objects.requireNonNull(determinism, "Determinism Is Required");
        successBranches = immutableStrings(successBranches, "Success Branches");
        failureBranches = immutableStrings(failureBranches, "Failure Branches");
        cancellationBranches = immutableStrings(cancellationBranches, "Cancellation Branches");
        failureContract = Objects.requireNonNull(failureContract, "Failure Contract Is Required");
        resourceReads = immutableResourceTypes(resourceReads, "Resource Reads");
        resourceWrites = immutableResourceTypes(resourceWrites, "Resource Writes");
        unknown = RuntimeCanonicalSupport.unknown(unknown, "Runtime Semantics Unknown Data");
        validateDeadlines(timeoutMillis, drainDeadlineMillis, hardDeadlineMillis);
        if (unloadPolicy == UnloadPolicy.SECURITY_CANCEL && cancellation == Cancellation.NONE) {
            throw new IllegalArgumentException("Security Cancellation Requires A Cancellable Operation");
        }
        if (retry != Retry.NEVER && idempotency == Idempotency.NONE) {
            throw new IllegalArgumentException("Retry Requires Idempotency");
        }
        if (retry == Retry.SAFE && determinism == Determinism.NONDETERMINISTIC) {
            throw new IllegalArgumentException("Safe Retry Cannot Be Nondeterministic");
        }
        if (cancellation != Cancellation.NONE && cancellationBranches.isEmpty()) {
            throw new IllegalArgumentException("Cancellable Operations Require Cancellation Branches");
        }
        if (effect == Effect.PURE && !resourceWrites.isEmpty()) {
            throw new IllegalArgumentException("Pure Operations Cannot Write Resources");
        }
        if (effect == Effect.STATE_READING && !resourceWrites.isEmpty()) {
            throw new IllegalArgumentException("State-Reading Operations Cannot Write Resources");
        }
        if (!failureBranches.containsAll(failureContract.branches())) {
            throw new IllegalArgumentException("Failure Contract Branches Must Be Declared By The Operation");
        }
    }

    public RuntimeSemantics(
        Effect effect,
        ThreadMode thread,
        ContractRef<CapabilityId> authorization,
        Cancellation cancellation,
        long timeoutMillis,
        long drainDeadlineMillis,
        long hardDeadlineMillis,
        UnloadPolicy unloadPolicy,
        Retry retry,
        Idempotency idempotency,
        Audit audit,
        Confirmation confirmation,
        SensitiveData sensitiveData,
        Determinism determinism,
        Set<String> successBranches,
        Set<String> failureBranches,
        Set<String> cancellationBranches,
        RuntimeFailureContract failureContract,
        Set<ContractRef<ResourceTypeId>> resourceReads,
        Set<ContractRef<ResourceTypeId>> resourceWrites
    ) {
        this(effect, thread, authorization, cancellation, timeoutMillis, drainDeadlineMillis, hardDeadlineMillis,
            unloadPolicy, retry, idempotency, audit, confirmation, sensitiveData, determinism, successBranches,
            failureBranches, cancellationBranches, failureContract, resourceReads, resourceWrites, Map.of());
    }

    private static void validateDeadlines(long timeoutMillis, long drainDeadlineMillis, long hardDeadlineMillis) {
        if (timeoutMillis < 0 || drainDeadlineMillis < 0 || hardDeadlineMillis < 0) {
            throw new IllegalArgumentException("Runtime Deadlines Cannot Be Negative");
        }
        if (hardDeadlineMillis < drainDeadlineMillis) {
            throw new IllegalArgumentException("Hard Deadline Cannot Precede Drain Deadline");
        }
        if (timeoutMillis > 0 && hardDeadlineMillis > 0 && timeoutMillis > hardDeadlineMillis) {
            throw new IllegalArgumentException("Operation Timeout Cannot Exceed Hard Deadline");
        }
    }

    private static Set<String> immutableStrings(Set<String> values, String label) {
        return RuntimeCanonicalSupport.localIds(values, label);
    }

    private static Set<ContractRef<ResourceTypeId>> immutableResourceTypes(Set<ContractRef<ResourceTypeId>> values, String label) {
        Objects.requireNonNull(values, label + " Are Required");
        TreeSet<ContractRef<ResourceTypeId>> sorted = new TreeSet<>();
        for (ContractRef<ResourceTypeId> value : values) {
            sorted.add(Objects.requireNonNull(value, label + " Cannot Contain Null"));
        }
        return Collections.unmodifiableSet(sorted);
    }

    public boolean cancellable() {
        return cancellation != Cancellation.NONE;
    }

    public boolean ephemeral() {
        return audit == Audit.NONE
            && (idempotency == Idempotency.NONE || idempotency == Idempotency.INTRINSIC)
            && cancellation == Cancellation.NONE
            && retry == Retry.NEVER;
    }

    public long executionBudgetMillis() {
        return timeoutMillis;
    }

    public long executionDeadlineMillis(long nowMillis) {
        if (nowMillis < 0) {
            throw new IllegalArgumentException("Runtime Clock Value Cannot Be Negative");
        }
        long budgetMillis = executionBudgetMillis();
        if (budgetMillis <= 0) {
            return Long.MAX_VALUE;
        }
        return RuntimeDeadline.deadlineMillisExact(nowMillis, budgetMillis);
    }

    public String canonical() {
        return CanonicalJson.canonicalize(canonicalValue());
    }

    public static RuntimeSemantics fromCanonical(String value) {
        return RuntimeCanonicalDecoder.parse(value, RuntimeCanonicalDecoder::semantics);
    }

    public static RuntimeSemantics fromCanonical(Map<String, ?> value) {
        return RuntimeCanonicalDecoder.semantics(RuntimeCanonicalDecoder.object(value, "semantics"));
    }

    public Map<String, Object> canonicalValue() {
        var values = new LinkedHashMap<String, Object>();
        values.put("effect", effect.wireValue());
        values.put("thread", thread.wireValue());
        values.put("authorization", authorization.canonicalValue());
        values.put("cancellation", cancellation.wireValue());
        values.put("timeoutMillis", timeoutMillis);
        values.put("drainDeadlineMillis", drainDeadlineMillis);
        values.put("hardDeadlineMillis", hardDeadlineMillis);
        values.put("unloadPolicy", unloadPolicy.wireValue());
        values.put("retry", retry.wireValue());
        values.put("idempotency", idempotency.wireValue());
        values.put("audit", audit.wireValue());
        values.put("confirmation", confirmation.wireValue());
        values.put("sensitiveData", sensitiveData.wireValue());
        values.put("determinism", determinism.wireValue());
        values.put("success", List.copyOf(successBranches));
        values.put("failure", List.copyOf(failureBranches));
        values.put("cancelled", List.copyOf(cancellationBranches));
        values.put("failureContract", failureContract.canonicalValue());
        values.put("resourceReads", resourceReads.stream().map(ContractRef::canonicalValue).toList());
        values.put("resourceWrites", resourceWrites.stream().map(ContractRef::canonicalValue).toList());
        return RuntimeCanonicalSupport.merge(unknown, values);
    }

    public Map<String, Object> executionCanonicalValue() {
        var values = new LinkedHashMap<String, Object>();
        values.put("effect", effect.wireValue());
        values.put("thread", thread.wireValue());
        values.put("authorization", authorization.canonicalValue());
        values.put("cancellation", cancellation.wireValue());
        values.put("timeoutMillis", timeoutMillis);
        values.put("drainDeadlineMillis", drainDeadlineMillis);
        values.put("hardDeadlineMillis", hardDeadlineMillis);
        values.put("unloadPolicy", unloadPolicy.wireValue());
        values.put("retry", retry.wireValue());
        values.put("idempotency", idempotency.wireValue());
        values.put("audit", audit.wireValue());
        values.put("confirmation", confirmation.wireValue());
        values.put("sensitiveData", sensitiveData.wireValue());
        values.put("determinism", determinism.wireValue());
        values.put("success", List.copyOf(successBranches));
        values.put("failure", List.copyOf(failureBranches));
        values.put("cancelled", List.copyOf(cancellationBranches));
        values.put("failureContract", failureContract.canonicalValue());
        values.put("resourceReads", resourceReads.stream().map(ContractRef::canonicalValue).toList());
        values.put("resourceWrites", resourceWrites.stream().map(ContractRef::canonicalValue).toList());
        return RuntimeCanonicalSupport.execution(unknown, values);
    }
}
