package restudio.resync.flow.validation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.diagnostic.DiagnosticContext;
import restudio.resync.flow.diagnostic.DiagnosticMetricPolicy;
import restudio.resync.flow.diagnostic.DiagnosticPhase;
import restudio.resync.flow.diagnostic.DiagnosticRedaction;
import restudio.resync.flow.diagnostic.DiagnosticSeverity;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;

public final class ValidationContext<I> {
    private static final ContractRef<CapabilityId> MESSAGE_KEY = new ContractRef<>(
        new OwnerId("resync"),
        new CapabilityId("validation.diagnostic")
    );

    private final ValidationRequest<I> request;
    private final DiagnosticPhase phase;
    private final ValidationRule<I> rule;
    private final int ruleIndex;

    ValidationContext(
        ValidationRequest<I> request,
        DiagnosticPhase phase,
        ValidationRule<I> rule,
        int ruleIndex
    ) {
        this.request = Objects.requireNonNull(request, "Validation request is required");
        this.phase = Objects.requireNonNull(phase, "Validation phase is required");
        this.rule = rule;
        this.ruleIndex = ruleIndex;
    }

    public I input() {
        return request.input();
    }

    public ValidationRequest<I> request() {
        return request;
    }

    public ValidationTarget target() {
        return request.target();
    }

    public ValidationSubject subject() {
        return request.subject();
    }

    public ValidationBudget budget() {
        return request.budget();
    }

    public ValidationPolicy policy() {
        return request.policy();
    }

    public DiagnosticPhase phase() {
        return phase;
    }

    public ValidationRule<I> rule() {
        return rule;
    }

    public ContractRef<CapabilityId> ruleId() {
        return rule == null ? null : rule.id();
    }

    public int ruleIndex() {
        return ruleIndex;
    }

    public Diagnostic diagnostic(
        String code,
        DiagnosticSeverity severity,
        String message,
        String remediation
    ) {
        return diagnostic(code, severity, message, remediation, Map.of(), Map.of());
    }

    public Diagnostic diagnostic(
        String code,
        DiagnosticSeverity severity,
        String message,
        String remediation,
        Map<String, ?> arguments,
        Map<String, ?> evidence
    ) {
        Map<String, Object> normalizedArguments = new LinkedHashMap<>();
        normalizedArguments.put("target", target().wireName());
        normalizedArguments.put("subject", subject().canonicalText());
        normalizedArguments.put("subjectType", subject().typeName());
        normalizedArguments.put("subjectTypeKey", subject().typeKey());
        if (rule != null) {
            normalizedArguments.put("validator", rule.id().canonicalText());
        }
        if (arguments != null) {
            arguments.forEach(normalizedArguments::putIfAbsent);
        }
        return Diagnostic.builder(code, severity, phase, target().wireName())
            .messageKey(MESSAGE_KEY)
            .message(message)
            .arguments(normalizedArguments)
            .evidence(evidence)
            .remediation(remediation)
            .correlationId(request.correlationId())
            .context(subject().diagnosticContext(request.diagnosticContext()))
            .redaction(DiagnosticRedaction.PUBLIC)
            .metricPolicy(DiagnosticMetricPolicy.NONE)
            .build();
    }

    Diagnostic engineDiagnostic(
        String code,
        String message,
        String remediation,
        Map<String, ?> arguments
    ) {
        return diagnostic(code, DiagnosticSeverity.ERROR, message, remediation, arguments, Map.of());
    }
}
