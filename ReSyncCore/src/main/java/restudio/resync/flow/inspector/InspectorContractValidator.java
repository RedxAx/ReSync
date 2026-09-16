package restudio.resync.flow.inspector;

import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.diagnostic.DiagnosticPhase;
import restudio.resync.flow.diagnostic.DiagnosticSeverity;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.InspectorSectionId;
import restudio.resync.flow.identity.OwnerId;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class InspectorContractValidator {
    private static final ContractRef<CapabilityId> MESSAGE_KEY = new ContractRef<>(new OwnerId("restudio.resync"), new CapabilityId("inspector-validation"));

    private InspectorContractValidator() {
    }

    public static void requireValid(InspectorDescriptor descriptor) {
        var diagnostics = validate(descriptor);
        if (!diagnostics.isEmpty()) {
            throw new InspectorContractException(diagnostics);
        }
    }

    public static List<Diagnostic> validate(InspectorDescriptor descriptor) {
        var diagnostics = new ArrayList<Diagnostic>();
        var capabilities = new LinkedHashSet<ContractRef<?>>();
        for (var capability : descriptor.capabilities()) {
            if (!capabilities.add(capability.id())) {
                diagnostics.add(diagnostic("INSPECTOR.DUPLICATE_CAPABILITY", DiagnosticPhase.SYNTACTIC, capability.id().toString(), "Capability identity is declared more than once", null));
            }
        }
        for (var capability : descriptor.capabilities()) {
            if (capability.fallbackCapability() != null && capability.fallbackCapability().equals(capability.id())) {
                diagnostics.add(diagnostic("INSPECTOR.SELF_FALLBACK", DiagnosticPhase.SEMANTIC, capability.id().toString(), "A capability cannot fall back to itself", null));
            }
            if (capability.fallbackCapability() != null && !capabilities.contains(capability.fallbackCapability())) {
                diagnostics.add(diagnostic("INSPECTOR.MISSING_CAPABILITY", DiagnosticPhase.CAPABILITY, capability.id().toString(), "Capability fallback is not declared", null));
            }
        }
        var options = new LinkedHashSet<InspectorFieldId>();
        for (var optionSource : descriptor.optionSources()) {
            if (!options.add(optionSource.id())) {
                diagnostics.add(diagnostic("INSPECTOR.DUPLICATE_OPTION", DiagnosticPhase.SYNTACTIC, optionSource.id().value(), "Option source identity is declared more than once", optionSource.id()));
            }
            if (!capabilities.contains(optionSource.capability())) {
                diagnostics.add(diagnostic("INSPECTOR.MISSING_CAPABILITY", DiagnosticPhase.CAPABILITY, optionSource.id().value(), "Option source capability is not declared", optionSource.id()));
            }
        }
        var fields = new LinkedHashMap<InspectorFieldId, FieldLocation>();
        var dependencies = new LinkedHashMap<InspectorFieldId, Set<InspectorFieldId>>();
        var rows = new HashSet<RowIdentity>();
        var sections = new HashSet<InspectorSectionId>();
        for (var section : descriptor.sections()) {
            if (!sections.add(section.id())) {
                diagnostics.add(diagnostic("INSPECTOR.DUPLICATE_SECTION", DiagnosticPhase.SYNTACTIC, section.id().value(), "Section identity is declared more than once", null));
            }
            for (var row : section.rows()) {
                var rowLocation = section.id().value() + "." + row.id().value();
                if (!rows.add(new RowIdentity(section.id(), row.id()))) {
                    diagnostics.add(diagnostic("INSPECTOR.DUPLICATE_ROW", DiagnosticPhase.SYNTACTIC, rowLocation, "Row identity is declared more than once in its section", null));
                }
                for (var field : row.fields()) {
                    collectField(field, rowLocation, fields, dependencies, capabilities, diagnostics);
                }
            }
        }
        for (var section : descriptor.sections()) {
            validateReferences(section.visibility(), section.id().value(), fields, diagnostics);
            for (var row : section.rows()) {
                validateReferences(row.visibility(), section.id().value() + "." + row.id().value(), fields, diagnostics);
            }
        }
        for (var signature : descriptor.functionSignatures()) {
            validateReferences(signature.visibility(), signature.id().value(), fields, diagnostics);
        }
        for (var rule : descriptor.validationRules()) {
            if (!capabilities.contains(rule.capability())) {
                diagnostics.add(diagnostic("INSPECTOR.MISSING_CAPABILITY", DiagnosticPhase.CAPABILITY, rule.id().value(), "Validation capability is not declared", rule.id()));
            }
            for (var target : rule.appliesTo()) {
                if (!fields.containsKey(target)) {
                    diagnostics.add(diagnostic("INSPECTOR.MISSING_FIELD", DiagnosticPhase.SEMANTIC, rule.id().value(), "Validation rule references an unknown field: " + target.value(), rule.id()));
                }
            }
        }
        for (var entry : dependencies.entrySet()) {
            for (var reference : entry.getValue()) {
                if (!fields.containsKey(reference)) {
                    diagnostics.add(diagnostic("INSPECTOR.MISSING_FIELD", DiagnosticPhase.SEMANTIC, entry.getKey().value(), "Conditional reference points to an unknown field: " + reference.value(), entry.getKey()));
                }
                if (entry.getKey().equals(reference)) {
                    diagnostics.add(diagnostic("INSPECTOR.SELF_REFERENCE", DiagnosticPhase.SEMANTIC, entry.getKey().value(), "A field condition cannot reference its own field", entry.getKey()));
                }
            }
        }
        diagnostics.addAll(cycles(dependencies));
        return List.copyOf(diagnostics);
    }

    private static void collectField(InspectorField field, String parentLocation, Map<InspectorFieldId, FieldLocation> fields, Map<InspectorFieldId, Set<InspectorFieldId>> dependencies, Set<ContractRef<?>> capabilities, List<Diagnostic> diagnostics) {
        var location = parentLocation + "." + field.id().value();
        if (fields.putIfAbsent(field.id(), new FieldLocation(location, field)) != null) {
            diagnostics.add(diagnostic("INSPECTOR.DUPLICATE_FIELD", DiagnosticPhase.SYNTACTIC, location, "Field identity is declared more than once", field.id()));
        }
        if (!capabilities.contains(field.editor().id())) {
            diagnostics.add(diagnostic("INSPECTOR.MISSING_CAPABILITY", DiagnosticPhase.CAPABILITY, location, "Field editor capability is not declared", field.id()));
        }
        if (field instanceof InspectorSelectorField selector && !capabilities.contains(selector.optionSource().capability())) {
            diagnostics.add(diagnostic("INSPECTOR.MISSING_CAPABILITY", DiagnosticPhase.CAPABILITY, location, "Selector option source capability is not declared", field.id()));
        }
        var references = new LinkedHashSet<InspectorFieldId>(field.visibility().references());
        if (field instanceof InspectorBranchField branch) {
            references.add(branch.selectorField());
            for (var caseDescriptor : branch.cases()) {
                references.addAll(caseDescriptor.when().references());
            }
        }
        if (field instanceof InspectorTaggedUnionField union) {
            for (var caseDescriptor : union.cases()) {
                references.addAll(caseDescriptor.visibility().references());
            }
        }
        if (field instanceof InspectorSummaryField summary) {
            references.addAll(summary.sources());
        }
        if (field instanceof InspectorPreviewField preview) {
            references.addAll(preview.sources());
            if (!capabilities.contains(preview.previewCapability().id())) {
                diagnostics.add(diagnostic("INSPECTOR.MISSING_CAPABILITY", DiagnosticPhase.CAPABILITY, location, "Preview capability is not declared", field.id()));
            }
        }
        dependencies.put(field.id(), Set.copyOf(references));
        for (var child : field.children()) {
            collectField(child, location, fields, dependencies, capabilities, diagnostics);
        }
    }

    private static void validateReferences(InspectorCondition condition, String location, Map<InspectorFieldId, FieldLocation> fields, List<Diagnostic> diagnostics) {
        for (var reference : condition.references()) {
            if (!fields.containsKey(reference)) {
                diagnostics.add(diagnostic("INSPECTOR.MISSING_FIELD", DiagnosticPhase.SEMANTIC, location, "Conditional reference points to an unknown field: " + reference.value(), null));
            }
        }
    }

    private static List<Diagnostic> cycles(Map<InspectorFieldId, Set<InspectorFieldId>> dependencies) {
        var diagnostics = new ArrayList<Diagnostic>();
        var visited = new HashSet<InspectorFieldId>();
        var active = new LinkedHashSet<InspectorFieldId>();
        for (var field : dependencies.keySet()) {
            findCycle(field, dependencies, visited, active, diagnostics);
        }
        return diagnostics;
    }

    private static void findCycle(InspectorFieldId field, Map<InspectorFieldId, Set<InspectorFieldId>> dependencies, Set<InspectorFieldId> visited, Set<InspectorFieldId> active, List<Diagnostic> diagnostics) {
        if (active.contains(field)) {
            diagnostics.add(diagnostic("INSPECTOR.CONDITIONAL_CYCLE", DiagnosticPhase.SEMANTIC, field.value(), "Conditional field references contain a cycle", field));
            return;
        }
        if (!visited.add(field)) {
            return;
        }
        active.add(field);
        for (var dependency : dependencies.getOrDefault(field, Set.of())) {
            if (dependencies.containsKey(dependency)) {
                findCycle(dependency, dependencies, visited, active, diagnostics);
            }
        }
        active.remove(field);
    }

    private record FieldLocation(String location, InspectorField field) {
    }

    private record RowIdentity(InspectorSectionId section, InspectorFieldId row) {
    }

    private static Diagnostic diagnostic(String code, DiagnosticPhase phase, String location, String message, InspectorFieldId field) {
        var context = new LinkedHashMap<String, Object>();
        context.put("location", location);
        if (field != null) {
            context.put("fieldId", field.value());
        }
        var correlationSeed = code + '\u0000' + location + '\u0000' + message + '\u0000' + (field == null ? "" : field.value());
        return Diagnostic.builder(code, DiagnosticSeverity.ERROR, phase, "inspector-contract")
            .messageKey(MESSAGE_KEY)
            .message(message)
            .arguments(context)
            .evidence(context)
            .remediation("Correct the inspector contract and reload it.")
            .correlationId(UUID.nameUUIDFromBytes(correlationSeed.getBytes(StandardCharsets.UTF_8)))
            .build();
    }
}
