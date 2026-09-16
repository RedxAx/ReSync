package restudio.resync.flow.validation;

import java.util.Objects;

import restudio.resync.flow.diagnostic.DiagnosticSeverity;

public record ValidationPolicy(
    DiagnosticSeverity failureSeverity,
    boolean rejectTruncation,
    boolean rejectNoRules,
    boolean stopAfterFailure
) {
    public ValidationPolicy {
        failureSeverity = Objects.requireNonNull(failureSeverity, "failureSeverity");
    }

    public static ValidationPolicy strict() {
        return new ValidationPolicy(DiagnosticSeverity.ERROR, true, true, false);
    }

    public static ValidationPolicy stopOnFailure() {
        return new ValidationPolicy(DiagnosticSeverity.ERROR, true, true, true);
    }

    public static ValidationPolicy warningsFail() {
        return new ValidationPolicy(DiagnosticSeverity.WARNING, true, true, false);
    }

    public boolean blocks(DiagnosticSeverity severity) {
        return severity.ordinal() >= failureSeverity.ordinal();
    }
}
