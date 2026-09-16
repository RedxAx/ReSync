package restudio.resync.flow.validation;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

import restudio.resync.flow.diagnostic.DiagnosticContext;

public record ValidationRequest<I>(
    I input,
    ValidationTarget target,
    ValidationSubject subject,
    ValidationBudget budget,
    ValidationPolicy policy,
    UUID correlationId,
    DiagnosticContext diagnosticContext
) {
    public ValidationRequest {
        target = Objects.requireNonNull(target, "Validation target is required");
        subject = Objects.requireNonNull(subject, "Validation subject is required");
        if (subject.target() != target) {
            throw new IllegalArgumentException("Validation request target does not match subject target");
        }
        budget = budget == null ? ValidationBudget.defaults() : budget;
        policy = policy == null ? ValidationPolicy.strict() : policy;
        correlationId = correlationId == null ? deterministicCorrelation(target, subject) : correlationId;
        diagnosticContext = subject.diagnosticContext(diagnosticContext);
    }

    public ValidationRequest(I input, ValidationTarget target, ValidationSubject subject) {
        this(input, target, subject, null, null, null, null);
    }

    public static <I> ValidationRequest<I> of(I input, ValidationTarget target, ValidationSubject subject) {
        return new ValidationRequest<>(input, target, subject);
    }

    private static UUID deterministicCorrelation(ValidationTarget target, ValidationSubject subject) {
        return UUID.nameUUIDFromBytes((target.wireName() + "|" + subject.canonicalText()).getBytes(StandardCharsets.UTF_8));
    }
}
