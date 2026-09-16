package restudio.resync.flow.validation;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.diagnostic.DiagnosticPhase;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;

public interface ValidationRule<I> {
    ContractRef<CapabilityId> id();

    DiagnosticPhase phase();

    Collection<Diagnostic> validate(ValidationContext<I> context) throws Exception;

    default int order() {
        return 0;
    }

    default Set<ValidationTarget> targets() {
        return ValidationTarget.all();
    }

    default boolean sideEffectFree() {
        return false;
    }

    static <I> ValidationRule<I> of(
        ContractRef<CapabilityId> id,
        DiagnosticPhase phase,
        ValidationFunction<I> function
    ) {
        return of(id, phase, 0, ValidationTarget.all(), function);
    }

    static <I> ValidationRule<I> of(
        ContractRef<CapabilityId> id,
        DiagnosticPhase phase,
        int order,
        Set<ValidationTarget> targets,
        ValidationFunction<I> function
    ) {
        return new ImmutableValidationRule<>(id, phase, order, targets, function);
    }

    static <I> ValidationRule<I> compose(
        ContractRef<CapabilityId> id,
        DiagnosticPhase phase,
        int order,
        Set<ValidationTarget> targets,
        Collection<? extends ValidationRule<I>> children
    ) {
        Objects.requireNonNull(children, "Composed validation rules are required");
        List<ValidationRule<I>> copied = new ArrayList<>();
        Set<ContractRef<CapabilityId>> identities = new HashSet<>();
        for (ValidationRule<I> child : children) {
            ValidationRule<I> normalized = Objects.requireNonNull(child, "Composed validation rule");
            if (normalized.phase() != phase) {
                throw new IllegalArgumentException("Composed validation rules must use one phase");
            }
            if (!normalized.sideEffectFree()) {
                throw new IllegalArgumentException("Composed validation rules must be side-effect free");
            }
            if (!identities.add(normalized.id())) {
                throw new IllegalArgumentException("Duplicate composed validation rule ID: " + normalized.id().canonicalText());
            }
            copied.add(normalized);
        }
        copied.sort(Comparator
            .comparingInt((ValidationRule<I> rule) -> rule.order())
            .thenComparing(rule -> rule.id().owner().canonicalText())
            .thenComparing(rule -> rule.id().id().canonicalText()));
        List<ValidationRule<I>> immutableChildren = List.copyOf(copied);
        return of(id, phase, order, targets, context -> {
            List<Diagnostic> diagnostics = new ArrayList<>();
            for (ValidationRule<I> child : immutableChildren) {
                ValidationContext<I> childContext = new ValidationContext<>(context.request(), phase, child, context.ruleIndex());
                Collection<Diagnostic> childDiagnostics = child.validate(childContext);
                if (childDiagnostics == null) {
                    diagnostics.add(childContext.engineDiagnostic(
                        "VALIDATION.RULE_RESULT_NULL",
                        "A validation rule returned no result collection.",
                        "Correct the validator to return an immutable diagnostic collection.",
                        Map.of()
                    ));
                    continue;
                }
                diagnostics.addAll(childDiagnostics);
            }
            return List.copyOf(diagnostics);
        });
    }

    @FunctionalInterface
    interface ValidationFunction<I> {
        Collection<Diagnostic> validate(ValidationContext<I> context) throws Exception;
    }

    final class ImmutableValidationRule<I> implements ValidationRule<I> {
        private final ContractRef<CapabilityId> id;
        private final DiagnosticPhase phase;
        private final int order;
        private final Set<ValidationTarget> targets;
        private final ValidationFunction<I> function;

        private ImmutableValidationRule(
            ContractRef<CapabilityId> id,
            DiagnosticPhase phase,
            int order,
            Set<ValidationTarget> targets,
            ValidationFunction<I> function
        ) {
            this.id = Objects.requireNonNull(id, "Validation rule ID is required");
            this.phase = Objects.requireNonNull(phase, "Validation rule phase is required");
            if (order < 0) {
                throw new IllegalArgumentException("Validation rule order cannot be negative");
            }
            this.order = order;
            EnumSet<ValidationTarget> copied = EnumSet.noneOf(ValidationTarget.class);
            if (targets != null) {
                copied.addAll(targets);
            }
            this.targets = Collections.unmodifiableSet(copied);
            this.function = Objects.requireNonNull(function, "Validation function is required");
        }

        @Override
        public ContractRef<CapabilityId> id() {
            return id;
        }

        @Override
        public DiagnosticPhase phase() {
            return phase;
        }

        @Override
        public Collection<Diagnostic> validate(ValidationContext<I> context) throws Exception {
            return function.validate(context);
        }

        @Override
        public int order() {
            return order;
        }

        @Override
        public Set<ValidationTarget> targets() {
            return targets;
        }

        @Override
        public boolean sideEffectFree() {
            return true;
        }
    }
}
