package restudio.resync.flow.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.diagnostic.DiagnosticPhase;
import restudio.resync.flow.diagnostic.DiagnosticSet;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;

public final class ValidationResult {
    private final ValidationTarget target;
    private final ValidationSubject subject;
    private final DiagnosticSet diagnostics;
    private final List<ValidationStep> steps;
    private final Map<DiagnosticPhase, List<ContractRef<CapabilityId>>> rulesByPhase;
    private final List<DiagnosticPhase> completedPhases;
    private final int validatorsRun;
    private final boolean truncated;
    private final boolean noRules;
    private final boolean inputMissing;
    private final ValidationPolicy policy;

    ValidationResult(
        ValidationTarget target,
        ValidationSubject subject,
        List<Diagnostic> diagnostics,
        List<ValidationStep> steps,
        Map<DiagnosticPhase, List<ContractRef<CapabilityId>>> rulesByPhase,
        List<DiagnosticPhase> completedPhases,
        int validatorsRun,
        boolean truncated,
        boolean noRules,
        boolean inputMissing,
        ValidationPolicy policy
    ) {
        this.target = Objects.requireNonNull(target, "Validation result target is required");
        this.subject = Objects.requireNonNull(subject, "Validation result subject is required");
        this.diagnostics = new DiagnosticSet(diagnostics);
        this.steps = List.copyOf(steps);
        EnumMap<DiagnosticPhase, List<ContractRef<CapabilityId>>> copiedRules = new EnumMap<>(DiagnosticPhase.class);
        rulesByPhase.forEach((phase, rules) -> copiedRules.put(phase, List.copyOf(rules)));
        this.rulesByPhase = Collections.unmodifiableMap(copiedRules);
        this.completedPhases = List.copyOf(completedPhases);
        if (validatorsRun < 0) {
            throw new IllegalArgumentException("validatorsRun cannot be negative");
        }
        this.validatorsRun = validatorsRun;
        this.truncated = truncated;
        this.noRules = noRules;
        this.inputMissing = inputMissing;
        this.policy = Objects.requireNonNull(policy, "Validation result policy is required");
    }

    public ValidationTarget target() {
        return target;
    }

    public ValidationSubject subject() {
        return subject;
    }

    public DiagnosticSet diagnostics() {
        return diagnostics;
    }

    public List<ValidationStep> steps() {
        return steps;
    }

    public Map<DiagnosticPhase, List<ContractRef<CapabilityId>>> rulesByPhase() {
        return rulesByPhase;
    }

    public List<ContractRef<CapabilityId>> executedRules() {
        List<ContractRef<CapabilityId>> rules = new ArrayList<>();
        for (DiagnosticPhase phase : DiagnosticPhase.values()) {
            rules.addAll(rulesByPhase.getOrDefault(phase, List.of()));
        }
        return List.copyOf(rules);
    }

    public List<DiagnosticPhase> completedPhases() {
        return completedPhases;
    }

    public int validatorsRun() {
        return validatorsRun;
    }

    public boolean truncated() {
        return truncated;
    }

    public boolean noRules() {
        return noRules;
    }

    public boolean inputMissing() {
        return inputMissing;
    }

    public ValidationPolicy policy() {
        return policy;
    }

    public boolean valid() {
        if (inputMissing || (noRules && policy.rejectNoRules()) || (truncated && policy.rejectTruncation())) {
            return false;
        }
        return diagnostics.diagnostics().stream().noneMatch(diagnostic -> policy.blocks(diagnostic.severity()));
    }

    public boolean invalid() {
        return !valid();
    }

    public boolean phaseCompleted(DiagnosticPhase phase) {
        return completedPhases.contains(phase);
    }
}
