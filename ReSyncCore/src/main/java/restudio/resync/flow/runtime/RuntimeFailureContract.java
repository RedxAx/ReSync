package restudio.resync.flow.runtime;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.type.TypeExpr;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record RuntimeFailureContract(
    TypeExpr payloadType,
    Set<String> diagnosticCodes,
    Set<String> branches,
    CommitBoundary commitBoundary,
    Map<String, Object> unknown
) {
    public enum CommitBoundary {
        NO_MUTATION,
        BEFORE_MUTATION,
        ATOMIC,
        AFTER_MUTATION;

        public String wireValue() {
            return switch (this) {
                case NO_MUTATION -> "no-mutation";
                case BEFORE_MUTATION -> "before-mutation";
                case ATOMIC -> "atomic";
                case AFTER_MUTATION -> "after-mutation";
            };
        }
    }

    public RuntimeFailureContract {
        payloadType = Objects.requireNonNull(payloadType, "Failure Payload Type Is Required");
        diagnosticCodes = RuntimeCanonicalSupport.diagnosticCodes(diagnosticCodes, "Diagnostic Codes");
        branches = RuntimeCanonicalSupport.localIds(branches, "Failure Branches");
        commitBoundary = Objects.requireNonNull(commitBoundary, "Commit Boundary Is Required");
        unknown = RuntimeCanonicalSupport.unknown(unknown, "Failure Contract Unknown Data");
        if (diagnosticCodes.isEmpty() || branches.isEmpty()) {
            throw new IllegalArgumentException("Failure Contract Requires Diagnostic Codes And Branches");
        }
    }

    public RuntimeFailureContract(
        TypeExpr payloadType,
        Set<String> diagnosticCodes,
        Set<String> branches,
        CommitBoundary commitBoundary
    ) {
        this(payloadType, diagnosticCodes, branches, commitBoundary, Map.of());
    }

    public String canonical() {
        return CanonicalJson.canonicalize(canonicalValue());
    }

    public static RuntimeFailureContract fromCanonical(String value) {
        return RuntimeCanonicalDecoder.parse(value, RuntimeCanonicalDecoder::failureContract);
    }

    public static RuntimeFailureContract fromCanonical(Map<String, ?> value) {
        return RuntimeCanonicalDecoder.failureContract(RuntimeCanonicalDecoder.object(value, "failureContract"));
    }

    public Map<String, Object> canonicalValue() {
        return RuntimeCanonicalSupport.merge(unknown, Map.of(
            "payloadType", payloadType.canonicalValue(),
            "diagnosticCodes", List.copyOf(diagnosticCodes),
            "branches", List.copyOf(branches),
            "commitBoundary", commitBoundary.wireValue()));
    }

    public Map<String, Object> executionCanonicalValue() {
        return RuntimeCanonicalSupport.execution(unknown, Map.of(
            "payloadType", payloadType.canonicalValue(),
            "diagnosticCodes", List.copyOf(diagnosticCodes),
            "branches", List.copyOf(branches),
            "commitBoundary", commitBoundary.wireValue()));
    }

    public boolean acceptsDiagnosticCode(String code) {
        return diagnosticCodes.contains(Objects.requireNonNull(code, "Diagnostic Code Is Required"));
    }
}
