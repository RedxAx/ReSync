package restudio.resync.flow.runtime;

import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.diagnostic.DiagnosticPhase;
import restudio.resync.flow.diagnostic.DiagnosticSeverity;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record RuntimeResult(Status status, TypedValue value, Map<PinId, TypedValue> outputs, String branch, RuntimeFailure failure) {
    public enum Status {
        SUCCESS,
        FAILURE,
        CANCELLED
    }

    public RuntimeResult {
        status = Objects.requireNonNull(status, "Result Status Is Required");
        outputs = immutableOutputs(outputs);
        branch = branch == null ? null : branch.trim();
        if (branch != null && branch.isEmpty()) {
            throw new IllegalArgumentException("Result Branch Cannot Be Blank");
        }
        if (status == Status.SUCCESS) {
            if (value != null && !outputs.isEmpty()) {
                throw new IllegalArgumentException("Successful Result Cannot Mix Scalar And Pin Outputs");
            }
            if (failure != null) {
                throw new IllegalArgumentException("Successful Result Cannot Contain A Failure");
            }
        }
        if (status != Status.SUCCESS) {
            if (value != null || !outputs.isEmpty()) {
                throw new IllegalArgumentException("Non-Successful Result Cannot Contain Typed Output Values");
            }
            if (failure == null) {
                throw new IllegalArgumentException("Non-Successful Result Requires A Failure");
            }
        }
    }

    public RuntimeResult(Status status, TypedValue value, RuntimeFailure failure) {
        this(status, value, Map.of(), null, failure);
    }

    public static RuntimeResult success(TypedValue value) {
        return new RuntimeResult(Status.SUCCESS, value, Map.of(), null, null);
    }

    public static RuntimeResult success() {
        return new RuntimeResult(Status.SUCCESS, null, Map.of(), null, null);
    }

    public static RuntimeResult success(Map<PinId, TypedValue> outputs, String branch) {
        return new RuntimeResult(Status.SUCCESS, null, outputs, branch, null);
    }

    public static RuntimeResult failure(RuntimeFailure failure) {
        return new RuntimeResult(Status.FAILURE, null, Map.of(), null, Objects.requireNonNull(failure, "Failure Is Required"));
    }

    public static RuntimeResult failure(RuntimeFailure failure, String branch) {
        return new RuntimeResult(Status.FAILURE, null, Map.of(), branch, Objects.requireNonNull(failure, "Failure Is Required"));
    }

    public static RuntimeResult cancelled(Diagnostic diagnostic) {
        return new RuntimeResult(Status.CANCELLED, null, Map.of(), "cancelled", new RuntimeFailure(diagnostic, false));
    }

    public static RuntimeResult cancelled(Diagnostic diagnostic, String branch) {
        return new RuntimeResult(Status.CANCELLED, null, Map.of(), branch, new RuntimeFailure(diagnostic, false));
    }

    public static RuntimeResult cancelled(RuntimeFailure failure) {
        return cancelled(failure, "cancelled");
    }

    public static RuntimeResult cancelled(RuntimeFailure failure, String branch) {
        return new RuntimeResult(Status.CANCELLED, null, Map.of(), branch, Objects.requireNonNull(failure, "Failure Is Required"));
    }

    public static RuntimeResult recoveredReservation() {
        return recoveredReservation("runtime-reservation-recovery");
    }

    public static RuntimeResult recoveredReservation(String identity) {
        String normalized = Objects.requireNonNull(identity, "Recovery Identity Is Required").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Recovery Identity Is Required");
        }
        Diagnostic diagnostic = Diagnostic.builder(
                "RUNTIME.PROVIDER_DRAINING",
                DiagnosticSeverity.ERROR,
                DiagnosticPhase.ENVIRONMENT,
                "execution")
            .messageKey(new ContractRef<>(new OwnerId("restudio.resync"), new NodeId("runtime-provider-draining")))
            .message("Runtime Operation Was Reserved Before The Previous Process Stopped")
            .remediation("Inspect the runtime receipt before retrying this operation.")
            .correlationId(UUID.nameUUIDFromBytes(normalized.getBytes(StandardCharsets.UTF_8)))
            .build();
        return failure(new RuntimeFailure(diagnostic, false,
            TypedValue.nullValue(TypeExpr.named(TypeReference.of("builtin", "any")))));
    }

    public boolean successful() {
        return status == Status.SUCCESS;
    }

    public String canonicalJson() {
        return CanonicalJson.canonicalize(canonicalValue());
    }

    public static RuntimeResult fromCanonical(String value) {
        return RuntimeCanonicalDecoder.parse(value, RuntimeCanonicalDecoder::result);
    }

    public static RuntimeResult fromCanonical(Map<String, ?> value) {
        return RuntimeCanonicalDecoder.result(RuntimeCanonicalDecoder.object(value, "runtime result"));
    }

    public Map<String, Object> canonicalValue() {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("status", status.name().toLowerCase(Locale.ROOT));
        if (branch != null) {
            value.put("branch", branch);
        }
        if (this.value != null) {
            value.put("value", this.value.canonicalValue());
        }
        if (!outputs.isEmpty()) {
            LinkedHashMap<String, Object> outputValues = new LinkedHashMap<>();
            outputs.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> outputValues.put(
                entry.getKey().canonicalText(), entry.getValue().canonicalValue()));
            value.put("outputs", outputValues);
        }
        if (failure != null) {
            LinkedHashMap<String, Object> failureValue = new LinkedHashMap<>();
            failureValue.put("diagnostic", failure.diagnostic().toMap());
            failureValue.put("retryable", failure.retryable());
            if (failure.payload() != null) {
                failureValue.put("payload", failure.payload().canonicalValue());
            }
            value.put("failure", failureValue);
        }
        return Map.copyOf(value);
    }

    private static Map<PinId, TypedValue> immutableOutputs(Map<PinId, TypedValue> values) {
        Objects.requireNonNull(values, "Result Outputs Are Required");
        LinkedHashMap<PinId, TypedValue> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(
            Objects.requireNonNull(key, "Output Pin Cannot Be Null"),
            Objects.requireNonNull(value, "Output Value Cannot Be Null")));
        return Collections.unmodifiableMap(copy);
    }
}
