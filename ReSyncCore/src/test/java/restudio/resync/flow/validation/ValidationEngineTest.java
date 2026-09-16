package restudio.resync.flow.validation;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.diagnostic.DiagnosticPhase;
import restudio.resync.flow.diagnostic.DiagnosticSeverity;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationEngineTest {
    private static final OwnerId OWNER = new OwnerId("test");
    private static final UUID CORRELATION = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final ValidationSubject SUBJECT = ValidationSubject.node(
        ValidationTarget.GRAPH,
        new ContractRef<>(OWNER, new NodeId("graph")),
        null,
        new NodeId("node")
    );

    @Test
    void phasesAndRuleOrderAreStableAndScoped() {
        ValidationRule<String> environment = rule("environment", DiagnosticPhase.ENVIRONMENT, 1, context -> List.of());
        ValidationRule<String> syntacticLate = rule("syntactic-late", DiagnosticPhase.SYNTACTIC, 20, context -> List.of());
        ValidationRule<String> syntacticEarly = rule("syntactic-early", DiagnosticPhase.SYNTACTIC, 10, context -> List.of());
        ValidationRule<String> catalogOnly = ValidationRule.of(
            reference("catalog-only"),
            DiagnosticPhase.SEMANTIC,
            0,
            Set.of(ValidationTarget.CATALOG),
            context -> List.of()
        );

        ValidationResult result = new ValidationEngine<>(List.of(environment, catalogOnly, syntacticLate, syntacticEarly))
            .validate(request("value"));

        assertEquals(List.of(reference("syntactic-early"), reference("syntactic-late"), reference("environment")), result.executedRules());
        assertEquals(List.of(DiagnosticPhase.SYNTACTIC, DiagnosticPhase.ENVIRONMENT), result.completedPhases());
        assertTrue(result.valid(), result.diagnostics()::toString);
    }

    @Test
    void validatorFailureAndMissingRulesFailClosedWithSharedDiagnostics() {
        ValidationRule<String> broken = rule("broken", DiagnosticPhase.SEMANTIC, 0, context -> {
            throw new IllegalStateException("broken");
        });

        ValidationResult brokenResult = new ValidationEngine<>(List.of(broken)).validate(request("value"));
        ValidationResult emptyResult = new ValidationEngine<String>().validate(request("value"));

        assertFalse(brokenResult.valid());
        assertTrue(hasCode(brokenResult, "VALIDATION.RULE_EXCEPTION"));
        assertFalse(emptyResult.valid());
        assertTrue(hasCode(emptyResult, "VALIDATION.NO_RULES"));
        assertEquals(DiagnosticPhase.SEMANTIC, emptyResult.diagnostics().diagnostics().getFirst().phase());
    }

    @Test
    void diagnosticsUseTypedSubjectContextAndResultsAreImmutable() {
        ValidationRule<String> rule = rule("subject", DiagnosticPhase.SEMANTIC, 0, context -> List.of(
            context.diagnostic(
                "GRAPH.PIN_UNRESOLVED",
                DiagnosticSeverity.ERROR,
                "The graph value is invalid.",
                "Correct the value and validate again.",
                Map.of("field", "value"),
                Map.of()
            )
        ));

        ValidationResult result = new ValidationEngine<>(List.of(rule)).validate(request("value"));
        Diagnostic diagnostic = result.diagnostics().diagnostics().getFirst();

        assertEquals(SUBJECT.nodeId(), diagnostic.nodeId());
        assertEquals("NodeId", diagnostic.arguments().get("subjectType"));
        assertEquals(CORRELATION, diagnostic.correlationId());
        assertThrows(UnsupportedOperationException.class, () -> result.executedRules().add(reference("new")));
        assertThrows(UnsupportedOperationException.class, () -> result.diagnostics().diagnostics().add(diagnostic));
    }

    @Test
    void budgetStopsWorkAndRemainsInvalid() {
        ValidationRule<String> first = rule("first", DiagnosticPhase.SYNTACTIC, 0, context -> List.of());
        ValidationRule<String> second = rule("second", DiagnosticPhase.SEMANTIC, 0, context -> List.of());
        ValidationRule<String> third = rule("third", DiagnosticPhase.CAPABILITY, 0, context -> List.of());
        ValidationRequest<String> request = new ValidationRequest<>(
            "value",
            ValidationTarget.GRAPH,
            SUBJECT,
            new ValidationBudget(2, 8),
            ValidationPolicy.strict(),
            CORRELATION,
            null
        );

        ValidationResult result = new ValidationEngine<>(List.of(third, first, second)).validate(request);

        assertFalse(result.valid());
        assertTrue(result.truncated());
        assertEquals(2, result.validatorsRun());
        assertTrue(hasCode(result, "VALIDATION.BUDGET_EXCEEDED"));
    }

    @Test
    void perRuleDiagnosticBudgetAndCorrelationMismatchFailClosed() {
        ValidationRule<String> noisy = rule("noisy", DiagnosticPhase.SYNTACTIC, 0, context -> List.of(
            context.diagnostic("CANON.INVALID_NUMBER", DiagnosticSeverity.INFO, "First finding.", "Review the first finding."),
            context.diagnostic("CANON.INVALID_UTF8", DiagnosticSeverity.INFO, "Second finding.", "Review the second finding.")
        ));
        ValidationRequest<String> boundedRequest = new ValidationRequest<>(
            "value",
            ValidationTarget.GRAPH,
            SUBJECT,
            new ValidationBudget(8, 4, 1),
            ValidationPolicy.strict(),
            CORRELATION,
            null
        );

        ValidationResult bounded = new ValidationEngine<>(List.of(noisy)).validate(boundedRequest);
        assertFalse(bounded.valid());
        assertTrue(bounded.truncated());
        assertTrue(hasCode(bounded, "VALIDATION.BUDGET_EXCEEDED"));
        assertEquals(1, bounded.steps().getFirst().diagnostics().size());

        ValidationRule<String> mismatched = rule("mismatched", DiagnosticPhase.SYNTACTIC, 0, context -> List.of(
            Diagnostic.builder("CANON.HASH_MISMATCH", DiagnosticSeverity.ERROR, DiagnosticPhase.SYNTACTIC, "canonical")
                .messageKey(new ContractRef<>(OWNER, new NodeId("message")))
                .message("The correlation does not match the request.")
                .remediation("Use the request correlation ID.")
                .correlationId(UUID.fromString("22222222-2222-4222-8222-222222222222"))
                .build()
        ));

        ValidationResult mismatch = new ValidationEngine<>(List.of(mismatched)).validate(request("value"));
        assertFalse(mismatch.valid());
        assertTrue(hasCode(mismatch, "VALIDATION.CORRELATION_MISMATCH"));
    }

    @Test
    void nullRuleResultIsReportedAfterEarlierPhaseFailure() {
        ValidationRule<String> failure = rule("failure", DiagnosticPhase.SEMANTIC, 0, context -> List.of(
            context.diagnostic("GRAPH.PIN_UNRESOLVED", DiagnosticSeverity.ERROR, "Failure.", "Repair it.")
        ));
        ValidationRule<String> nullResult = rule("null-result", DiagnosticPhase.SEMANTIC, 1, context -> null);

        ValidationResult result = new ValidationEngine<>(List.of(failure, nullResult)).validate(request("value"));

        assertFalse(result.valid());
        assertTrue(hasCode(result, "VALIDATION.RULE_RESULT_NULL"));
    }

    @Test
    void composedNullRuleResultUsesTheSharedNullResultDiagnostic() {
        ValidationRule<String> nullChild = ValidationRule.of(
            reference("null-child"),
            DiagnosticPhase.SEMANTIC,
            0,
            ValidationTarget.all(),
            context -> null
        );
        ValidationRule<String> composed = ValidationRule.compose(
            reference("composed"),
            DiagnosticPhase.SEMANTIC,
            0,
            ValidationTarget.all(),
            List.of(nullChild)
        );

        ValidationResult result = new ValidationEngine<>(List.of(composed)).validate(request("value"));

        assertFalse(result.valid());
        assertTrue(hasCode(result, "VALIDATION.RULE_RESULT_NULL"));
    }

    @Test
    void typedReferencesWithSameTextRemainDistinct() {
        ValidationSubject node = ValidationSubject.of(
            ValidationTarget.CATALOG,
            new ContractRef<>(OWNER, new NodeId("same"))
        );
        ValidationSubject pin = ValidationSubject.of(
            ValidationTarget.CATALOG,
            new ContractRef<>(OWNER, new PinId("same"))
        );

        assertNotEquals(node, pin);
        assertNotEquals(node.canonicalText(), pin.canonicalText());
    }

    private static ValidationRequest<String> request(String input) {
        return new ValidationRequest<>(
            input,
            ValidationTarget.GRAPH,
            SUBJECT,
            ValidationBudget.defaults(),
            ValidationPolicy.strict(),
            CORRELATION,
            null
        );
    }

    private static ValidationRule<String> rule(
        String id,
        DiagnosticPhase phase,
        int order,
        ValidationRule.ValidationFunction<String> function
    ) {
        return ValidationRule.of(reference(id), phase, order, ValidationTarget.all(), function);
    }

    private static ContractRef<CapabilityId> reference(String id) {
        return new ContractRef<>(OWNER, new CapabilityId(id));
    }

    private static boolean hasCode(ValidationResult result, String code) {
        return result.diagnostics().diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals(code));
    }
}
