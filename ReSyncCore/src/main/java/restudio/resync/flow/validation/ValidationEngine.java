package restudio.resync.flow.validation;

import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.diagnostic.DiagnosticPhase;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import restudio.resync.contract.diagnostic.DiagnosticCodeCatalog;

public final class ValidationEngine<I> {
    private static final Comparator<ValidationRule<?>> RULE_ORDER = Comparator
        .comparingInt((ValidationRule<?> rule) -> rule.phase().ordinal())
        .thenComparingInt(rule -> rule.order())
        .thenComparing(rule -> rule.id().owner().canonicalText())
        .thenComparing(rule -> rule.id().id().canonicalText());

    private final List<ValidationRule<I>> rules;
    private final DiagnosticCodeCatalog catalog;

    public ValidationEngine(Collection<? extends ValidationRule<I>> rules) {
        this(rules, null);
    }

    public ValidationEngine(Collection<? extends ValidationRule<I>> rules, DiagnosticCodeCatalog catalog) {
        Objects.requireNonNull(rules, "Validation rules are required");
        this.catalog = catalog == null ? DiagnosticCodeCatalog.defaultCatalog() : catalog;
        if (!this.catalog.authoritative()) {
            throw new IllegalArgumentException("Validation requires the frozen generation-1 diagnostic catalog");
        }
        List<ValidationRule<I>> copied = new ArrayList<>();
        Set<ContractRef<CapabilityId>> identities = new HashSet<>();
        for (ValidationRule<I> rule : rules) {
            ValidationRule<I> normalized = Objects.requireNonNull(rule, "Validation rule");
            if (normalized.id() == null) {
                throw new IllegalArgumentException("Validation rule ID is required");
            }
            if (normalized.phase() == null) {
                throw new IllegalArgumentException("Validation rule phase is required");
            }
            if (normalized.order() < 0) {
                throw new IllegalArgumentException("Validation rule order cannot be negative");
            }
            if (!identities.add(normalized.id())) {
                throw new IllegalArgumentException("Duplicate validation rule ID: " + normalized.id().canonicalText());
            }
            if (normalized.targets() == null) {
                throw new IllegalArgumentException("Validation rule targets are required");
            }
            if (!normalized.sideEffectFree()) {
                throw new IllegalArgumentException("Validation rules must be side-effect free");
            }
            copied.add(new BoundRule<>(normalized));
        }
        copied.sort((left, right) -> RULE_ORDER.compare(left, right));
        this.rules = List.copyOf(copied);
    }

    public ValidationEngine() {
        this(List.of(), null);
    }

    public ValidationEngine(DiagnosticCodeCatalog catalog) {
        this(List.of(), catalog);
    }

    public List<ValidationRule<I>> rules() {
        return rules;
    }

    public DiagnosticCodeCatalog catalog() {
        return catalog;
    }

    public ValidationEngine<I> compose(Collection<? extends ValidationRule<I>> additionalRules) {
        Objects.requireNonNull(additionalRules, "Additional validation rules are required");
        List<ValidationRule<I>> composed = new ArrayList<>(rules);
        composed.addAll(additionalRules);
        return new ValidationEngine<>(composed, catalog);
    }

    public ValidationResult validate(ValidationRequest<I> request) {
        Objects.requireNonNull(request, "Validation request is required");
        List<ValidationRule<I>> applicable = rules.stream()
            .filter(rule -> rule.targets().contains(request.target()))
            .toList();
        List<Diagnostic> diagnostics = new ArrayList<>();
        List<ValidationStep> steps = new ArrayList<>();
        EnumMap<DiagnosticPhase, List<ContractRef<CapabilityId>>> executed = new EnumMap<>(DiagnosticPhase.class);
        List<DiagnosticPhase> completedPhases = new ArrayList<>();
        ValidationContext<I> root = new ValidationContext<>(request, DiagnosticPhase.SYNTACTIC, null, -1);
        DiagnosticCollector collector = new DiagnosticCollector(diagnostics, request.budget().maxDiagnostics());
        boolean noRules = applicable.isEmpty();
        boolean inputMissing = request.input() == null;
        int validatorsRun = 0;

        if (noRules) {
            collector.add(root.engineDiagnostic(
                "VALIDATION.NO_RULES",
                "No validation rules are registered for this target.",
                "Register at least one applicable validation rule before accepting the input.",
                Map.of("target", request.target().wireName())
            ));
        }
        if (inputMissing) {
            collector.add(root.engineDiagnostic(
                "VALIDATION.INPUT_MISSING",
                "Validation input is missing.",
                "Provide an immutable validation input and retry.",
                Map.of()
            ));
        }
        if (noRules || inputMissing) {
            if (collector.truncated()) {
                appendBudgetDiagnostic(diagnostics, root, request);
            }
            return result(request, diagnostics, steps, executed, completedPhases, validatorsRun, collector.truncated(), noRules, inputMissing);
        }

        boolean stopAfterPhase = false;
        for (DiagnosticPhase phase : DiagnosticPhase.values()) {
            List<ValidationRule<I>> phaseRules = applicable.stream()
                .filter(rule -> rule.phase() == phase)
                .toList();
            if (phaseRules.isEmpty()) {
                continue;
            }
            boolean phaseFailure = false;
            List<ContractRef<CapabilityId>> phaseExecuted = new ArrayList<>();
            boolean phaseComplete = true;
            for (ValidationRule<I> rule : phaseRules) {
                if (validatorsRun >= request.budget().maxValidators()) {
                    collector.markTruncated();
                    phaseComplete = false;
                    break;
                }
                validatorsRun++;
                phaseExecuted.add(rule.id());
                ValidationContext<I> context = new ValidationContext<>(request, phase, rule, validatorsRun - 1);
                List<Diagnostic> stepDiagnostics = new ArrayList<>();
                Collection<Diagnostic> findings = null;
                boolean ruleFailure = false;
                try {
                    findings = rule.validate(context);
                } catch (Exception exception) {
                    Diagnostic failure = context.engineDiagnostic(
                        "VALIDATION.RULE_EXCEPTION",
                        "A validation rule failed before producing a result.",
                        "Inspect the validator failure and retry validation.",
                        Map.of("exception", exception.getClass().getName())
                    );
                    stepDiagnostics.add(failure);
                    collector.add(failure);
                    ruleFailure = true;
                } catch (LinkageError error) {
                    Diagnostic failure = context.engineDiagnostic(
                        "VALIDATION.RULE_LINKAGE_FAILURE",
                        "A validation rule could not be linked safely.",
                        "Repair the validator dependency before accepting the input.",
                        Map.of("error", error.getClass().getName())
                    );
                    stepDiagnostics.add(failure);
                    collector.add(failure);
                    ruleFailure = true;
                }
                if (findings == null && !ruleFailure) {
                    Diagnostic failure = context.engineDiagnostic(
                        "VALIDATION.RULE_RESULT_NULL",
                        "A validation rule returned no result collection.",
                        "Correct the validator to return an immutable diagnostic collection.",
                        Map.of()
                    );
                    stepDiagnostics.add(failure);
                    collector.add(failure);
                    ruleFailure = true;
                }
                if (findings != null) {
                    int ruleDiagnosticCount = 0;
                    for (Diagnostic finding : findings) {
                        if (ruleDiagnosticCount >= request.budget().maxDiagnosticsPerValidator()) {
                            collector.markTruncated();
                            phaseComplete = false;
                            break;
                        }
                        ruleDiagnosticCount++;
                        Diagnostic normalized = finding;
                        if (finding == null) {
                            normalized = context.engineDiagnostic(
                            "VALIDATION.DIAGNOSTIC_NULL",
                            "A validation rule returned a null diagnostic.",
                            "Correct the validator to return only structured diagnostics.",
                            Map.of()
                            );
                            ruleFailure = true;
                        } else if (finding.phase() != phase) {
                            normalized = context.engineDiagnostic(
                            "VALIDATION.PHASE_MISMATCH",
                            "A validation diagnostic does not belong to its rule phase.",
                            "Emit the diagnostic through the shared validation context for its declared phase.",
                            Map.of("reportedPhase", finding.phase().wireName())
                            );
                            ruleFailure = true;
                        } else if (catalog != null && !catalog.contains(finding.code())) {
                            throw new IllegalArgumentException("Validation diagnostic code is not in the frozen catalog: " + finding.code());
                        } else if (!request.correlationId().equals(finding.correlationId())) {
                            normalized = context.engineDiagnostic(
                            "VALIDATION.CORRELATION_MISMATCH",
                            "A validation diagnostic uses a different correlation ID.",
                            "Emit the diagnostic through the validation context for this request.",
                            Map.of("reportedCorrelationId", finding.correlationId().toString())
                            );
                            ruleFailure = true;
                        } else if (!Objects.equals(finding.arguments().get("subject"), request.subject().canonicalText())
                            || !Objects.equals(finding.arguments().get("subjectType"), request.subject().typeName())
                            || !Objects.equals(finding.arguments().get("subjectTypeKey"), request.subject().typeKey())
                            || !request.subject().exactIdentityMatches(finding.resource(), finding.nodeId(), finding.pinId())
                        ) {
                            normalized = context.engineDiagnostic(
                            "VALIDATION.SUBJECT_MISMATCH",
                            "A validation diagnostic is not attached to the requested typed subject.",
                            "Emit the diagnostic through the validation context for the exact subject being checked.",
                            Map.of("reportedSubject", String.valueOf(finding.arguments().get("subject")))
                            );
                            ruleFailure = true;
                        } else if (request.policy().blocks(finding.severity())) {
                            ruleFailure = true;
                        }
                        if (!collector.add(normalized)) {
                            phaseComplete = false;
                            break;
                        }
                        stepDiagnostics.add(normalized);
                    }
                }
                phaseFailure |= ruleFailure;
                if (collector.truncated()) {
                    phaseComplete = false;
                }
                steps.add(new ValidationStep(rule.id(), phase, rule.order(), true, stepDiagnostics));
                if (!phaseComplete) {
                    break;
                }
                if (request.policy().stopAfterFailure() && phaseFailure) {
                    stopAfterPhase = true;
                }
            }
            executed.put(phase, List.copyOf(phaseExecuted));
            if (phaseComplete) {
                completedPhases.add(phase);
            }
            if (!phaseComplete || stopAfterPhase) {
                break;
            }
        }
        if (collector.truncated()) {
            appendBudgetDiagnostic(diagnostics, root, request);
        }
        return result(request, diagnostics, steps, executed, completedPhases, validatorsRun, collector.truncated(), false, false);
    }

    private static void appendBudgetDiagnostic(
        List<Diagnostic> diagnostics,
        ValidationContext<?> root,
        ValidationRequest<?> request
    ) {
        Diagnostic budget = root.engineDiagnostic(
            "VALIDATION.BUDGET_EXCEEDED",
            "Validation stopped at its configured safety bound.",
            "Reduce the input or raise the explicit validation budget after review.",
            Map.of(
                "maxValidators", request.budget().maxValidators(),
                "maxDiagnostics", request.budget().maxDiagnostics()
            )
        );
        if (diagnostics.size() >= request.budget().maxDiagnostics()) {
            diagnostics.remove(diagnostics.size() - 1);
        }
        diagnostics.add(budget);
    }

    private static ValidationResult result(
        ValidationRequest<?> request,
        List<Diagnostic> diagnostics,
        List<ValidationStep> steps,
        Map<DiagnosticPhase, List<ContractRef<CapabilityId>>> executed,
        List<DiagnosticPhase> completedPhases,
        int validatorsRun,
        boolean truncated,
        boolean noRules,
        boolean inputMissing
    ) {
        return new ValidationResult(
            request.target(),
            request.subject(),
            diagnostics,
            steps,
            executed,
            completedPhases,
            validatorsRun,
            truncated,
            noRules,
            inputMissing,
            request.policy()
        );
    }

    private static final class DiagnosticCollector {
        private final List<Diagnostic> diagnostics;
        private final int limit;
        private boolean truncated;

        private DiagnosticCollector(List<Diagnostic> diagnostics, int limit) {
            this.diagnostics = diagnostics;
            this.limit = limit;
        }

        private boolean add(Diagnostic diagnostic) {
            if (diagnostics.size() >= limit) {
                truncated = true;
                return false;
            }
            diagnostics.add(Objects.requireNonNull(diagnostic, "diagnostic"));
            return true;
        }

        private void markTruncated() {
            truncated = true;
        }

        private boolean truncated() {
            return truncated;
        }
    }

    private static final class BoundRule<I> implements ValidationRule<I> {
        private final ValidationRule<I> delegate;
        private final Set<ValidationTarget> targets;

        private BoundRule(ValidationRule<I> delegate) {
            this.delegate = delegate;
            this.targets = Set.copyOf(delegate.targets());
        }

        @Override
        public ContractRef<CapabilityId> id() {
            return delegate.id();
        }

        @Override
        public DiagnosticPhase phase() {
            return delegate.phase();
        }

        @Override
        public Collection<Diagnostic> validate(ValidationContext<I> context) throws Exception {
            return delegate.validate(context);
        }

        @Override
        public int order() {
            return delegate.order();
        }

        @Override
        public Set<ValidationTarget> targets() {
            return targets;
        }

        @Override
        public boolean sideEffectFree() {
            return delegate.sideEffectFree();
        }
    }
}
