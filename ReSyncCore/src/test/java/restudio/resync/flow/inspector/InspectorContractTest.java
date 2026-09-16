package restudio.resync.flow.inspector;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.diagnostic.DiagnosticPhase;
import restudio.resync.flow.diagnostic.DiagnosticSeverity;
import restudio.resync.flow.identity.BranchId;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CaseId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.InspectorSectionId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.RepeatableGroupId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InspectorContractTest {
    private static final OwnerId OWNER = OwnerId.of("example.reference");
    private static final TypeExpr TEXT = TypeExpr.named(TypeReference.of("restudio.resync", "text"));
    private static final TypeExpr BOOL = TypeExpr.named(TypeReference.of("restudio.resync", "boolean"));
    private static final TypeExpr NUMBER = TypeExpr.named(TypeReference.of("restudio.resync", "number"));

    @Test
    void structuralDescriptorsAreImmutableAndUseStableFieldIds() {
        var editor = capability("edit");
        var preview = capability("preview");
        var enabledId = InspectorIds.stableField(OWNER, "settings.enabled");
        var enabled = new InspectorScalarField(enabledId, "Enabled", "Controls whether this setting is active.", BOOL, true, null, List.of(), null, InspectorFallback.GENERIC, editor);
        var text = new InspectorScalarField(InspectorIds.stableField(OWNER, "settings.text"), "Text", "Provides the text stored by this setting.", TEXT, false, null, List.of(), InspectorCondition.present(enabledId), InspectorFallback.GENERIC, editor);
        var optionSource = new InspectorOptionSource(InspectorIds.stableField(OWNER, "settings.options"), "Options", "Provides typed options that can be selected for this setting.", TEXT, OptionQuerySchemaV1.empty(), editor.id(), 25);
        var selector = new InspectorSelectorField(InspectorIds.stableField(OWNER, "settings.selector"), "Selector", "Selects one typed option from the declared option source.", TEXT, optionSource, true, false, null, InspectorFallback.GENERIC, editor);
        var object = new InspectorObjectField(InspectorIds.stableField(OWNER, "settings.object"), "Object", "Groups related values into one structured setting.", TypeExpr.named(TypeReference.of("restudio.resync", "settings")), List.of(text), null, InspectorFallback.GENERIC, editor);
        var list = new InspectorListField(InspectorIds.stableField(OWNER, "settings.values"), "Values", "Stores an ordered collection of typed setting values.", TEXT, 0, 8, true, null, null, InspectorFallback.GENERIC, editor);
        var map = new InspectorMapField(InspectorIds.stableField(OWNER, "settings.labels"), "Labels", "Stores named setting values without losing their keys.", TEXT, TEXT, 0, 8, null, null, InspectorFallback.GENERIC, editor);
        var unionType = TypeExpr.union(List.of(new TypeExpr.UnionVariant("plain", TEXT), new TypeExpr.UnionVariant("number", NUMBER)));
        var union = new InspectorTaggedUnionField(InspectorIds.stableField(OWNER, "settings.value"), "Value", "Selects one explicitly tagged value representation.", unionType, List.of(
            new InspectorUnionCase(CaseId.of("number"), "Number", "Uses the numeric representation for this value.", new InspectorScalarField(InspectorIds.stableField(OWNER, "settings.value.number"), "Number", "Stores the numeric union value selected by this case.", NUMBER, false, null, List.of(), null, InspectorFallback.GENERIC, editor), null),
            new InspectorUnionCase(CaseId.of("plain"), "Plain", "Uses the text representation for this value.", new InspectorScalarField(InspectorIds.stableField(OWNER, "settings.value.plain"), "Plain", "Stores the text union value selected by this case.", TEXT, false, null, List.of(), null, InspectorFallback.GENERIC, editor), null)
        ), null, InspectorFallback.GENERIC, editor);
        var repeatable = new InspectorRepeatableField(InspectorIds.stableField(OWNER, "settings.entries"), "Entries", "Allows an ordered set of independently editable entries.", TypeExpr.list(TEXT), RepeatableGroupId.of("entries"), 0, 4, true, new InspectorScalarField(InspectorIds.stableField(OWNER, "settings.entries.item"), "Entry", "Stores one editable entry in this repeated collection.", TEXT, true, null, List.of(), null, InspectorFallback.GENERIC, editor), null, InspectorFallback.GENERIC, editor);
        var activeText = new InspectorScalarField(InspectorIds.stableField(OWNER, "settings.branch.active-text"), "Active Text", "Stores the text shown while the active branch case is selected.", TEXT, false, null, List.of(), null, InspectorFallback.GENERIC, editor);
        var branch = new InspectorBranchField(InspectorIds.stableField(OWNER, "settings.branch"), "Branch", "Chooses the case whose condition matches the current setting.", TEXT, BranchId.of("settings"), enabledId, List.of(new InspectorBranchCase(CaseId.of("active"), "Active", "Shows values used while the setting is active.", InspectorCondition.present(enabledId), List.of(activeText))), null, InspectorFallback.GENERIC, editor);
        var parameter = new InspectorFunctionParameter(InspectorIds.stableParameter(OWNER, "settings.function.value"), "Value", "Supplies the value passed to the function signature.", TEXT, true, null);
        var signature = new InspectorFunctionSignature(InspectorIds.stableField(OWNER, "settings.function.signature"), "Function Signature", "Describes the stable typed parameters accepted by this function.", List.of(parameter), TEXT, null);
        var function = new InspectorFunctionField(InspectorIds.stableField(OWNER, "settings.function"), "Function", "Describes a typed function invocation without node-specific behavior.", TEXT, signature, null, InspectorFallback.GENERIC, editor);
        var summary = new InspectorSummaryField(InspectorIds.stableField(OWNER, "settings.summary"), "Summary", "Summarizes the values selected by this inspector configuration.", TEXT, List.of(enabledId, text.id()), "enabled and text", null, InspectorFallback.GENERIC, editor);
        var previewField = new InspectorPreviewField(InspectorIds.stableField(OWNER, "settings.preview"), "Preview", "Shows a read-only projection of the current inspector values.", TEXT, editor, preview, List.of(text.id()), null, InspectorFallback.READ_ONLY_FIELD);
        var row = new InspectorRow(InspectorIds.stableField(OWNER, "settings.row"), "Settings", "Edits the settings exposed by this generic inspector row.", List.of(enabled, object, selector, list, map, union, repeatable, branch, function, summary, previewField), null);
        var descriptor = new InspectorDescriptor(OWNER, InspectorId.of("settings"), "Settings", "Edits the typed settings for this resource through shared inspector capabilities.", List.of(new InspectorSection(InspectorSectionId.of("settings"), "Settings", "Contains the values that control this resource behavior.", List.of(row), null)), List.of(editor, preview), List.of(optionSource), List.of(), List.of());

        assertEquals(enabledId, InspectorIds.stableField(OWNER, "settings.enabled"));
        assertEquals(1, descriptor.sections().getFirst().rows().size());
        assertThrows(UnsupportedOperationException.class, () -> descriptor.sections().add(null));
        assertThrows(UnsupportedOperationException.class, () -> descriptor.sections().getFirst().rows().getFirst().fields().add(enabled));
        assertEquals(OptionQuerySchemaV1.empty(), optionSource.querySchema());
        assertThrows(IllegalArgumentException.class, () -> new InspectorOptionSource(optionSource.id(), optionSource.title(),
            optionSource.description(), optionSource.optionType(), Map.of("dependencies", List.of("project")), optionSource.capability(),
            optionSource.pageLimit()));
    }

    @Test
    void missingDescriptionsAndInvalidConditionsAreRejected() {
        var editor = capability("edit");
        var id = InspectorIds.stableField(OWNER, "enabled");
        var missing = assertThrows(IllegalArgumentException.class, () -> new InspectorScalarField(id, "Enabled", "", BOOL, true, null, List.of(), null, InspectorFallback.GENERIC, editor));
        assertTrue(missing.getMessage().contains("description"));

        var unknown = InspectorIds.stableField(OWNER, "unknown");
        var field = new InspectorScalarField(id, "Enabled", "Controls whether this setting is active.", BOOL, true, null, List.of(), InspectorCondition.present(unknown), InspectorFallback.GENERIC, editor);
        var row = new InspectorRow(InspectorIds.stableField(OWNER, "row"), "Settings", "Edits the settings exposed by this generic inspector row.", List.of(field), null);
        assertThrows(InspectorContractException.class, () -> new InspectorDescriptor(OWNER, InspectorId.of("invalid"), "Invalid", "Rejects a descriptor that contains invalid conditional references.", List.of(new InspectorSection(InspectorSectionId.of("settings"), "Settings", "Contains the values that control this resource behavior.", List.of(row), null)), List.of(editor)));

        var firstId = InspectorIds.stableField(OWNER, "cycle.first");
        var secondId = InspectorIds.stableField(OWNER, "cycle.second");
        var first = new InspectorScalarField(firstId, "First", "Stores the first value in this conditional cycle.", BOOL, false, null, List.of(), InspectorCondition.present(secondId), InspectorFallback.GENERIC, editor);
        var second = new InspectorScalarField(secondId, "Second", "Stores the second value in this conditional cycle.", BOOL, false, null, List.of(), InspectorCondition.present(firstId), InspectorFallback.GENERIC, editor);
        var cycleRow = new InspectorRow(InspectorIds.stableField(OWNER, "cycle.row"), "Cycle", "Exposes the values that deliberately form an invalid condition cycle.", List.of(first, second), null);
        assertThrows(InspectorContractException.class, () -> new InspectorDescriptor(OWNER, InspectorId.of("cycle"), "Cycle", "Rejects conditional dependencies that cannot be evaluated in a stable order.", List.of(new InspectorSection(InspectorSectionId.of("cycle"), "Cycle", "Contains conditional values used to test dependency validation.", List.of(cycleRow), null)), List.of(editor)));
    }

    @Test
    void draftsMutationsConflictsAndSerializedTypedStatePreserveIdentity() {
        var resource = new ServerResourceLocator(UUID.randomUUID(), ContractRef.of(OWNER, ResourceTypeId.of("flow")), "main");
        var field = InspectorIds.stableField(OWNER, "enabled");
        var value = TypedValue.value(BOOL, true);
        var draft = new InspectorDraft(UUID.randomUUID(), resource, 4, Map.of(field, value), Map.of("future", List.of("kept")), List.of(), InspectorFallback.GENERIC);
        var mutation = draft.mutation(UUID.randomUUID());
        var conflict = new InspectorConflict(mutation.mutationId(), resource, 5, Map.of("schema", "authoritative"), draft, List.of(diagnostic(field)));

        assertEquals(value, mutation.fields().get(field));
        assertTrue(draft.serialized().contains("inspector-draft"));
        assertTrue(mutation.serialized().contains("inspector-mutation"));
        assertTrue(conflict.serialized().contains("inspector-conflict"));
        assertEquals(field.value(), conflict.diagnostics().getFirst().arguments().get("fieldId"));
        assertTrue(resource.canonicalText().endsWith("/example.reference/flow/main"));
    }

    private static Diagnostic diagnostic(InspectorFieldId field) {
        return Diagnostic.builder("INSPECTOR.MISSING_FIELD", DiagnosticSeverity.ERROR, DiagnosticPhase.SEMANTIC, "inspector-conflict")
            .messageKey(new ContractRef<>(OWNER, CapabilityId.of("inspector-validation")))
            .message("The resource changed before this draft was saved.")
            .arguments(Map.of("fieldId", field.value()))
            .evidence(Map.of("fieldId", field.value()))
            .remediation("Review the authoritative value and save the draft again.")
            .correlationId(UUID.randomUUID())
            .build();
    }

    private static InspectorCapability capability(String id) {
        var reference = InspectorIds.capability(OWNER, id);
        return new InspectorCapability(reference, id, "Provides the shared capability used by this inspector field.", new InspectorValueSchema(TEXT), new InspectorValueSchema(TEXT), List.of(), null, InspectorFallback.READ_ONLY_FIELD);
    }

}
