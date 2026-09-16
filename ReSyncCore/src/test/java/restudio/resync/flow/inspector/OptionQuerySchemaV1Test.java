package restudio.resync.flow.inspector;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionQuerySchemaV1Test {
    private static final ServerId SERVER = new ServerId(UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
    private static final ServerId OTHER_SERVER = new ServerId(UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"));
    private static final TypeExpr STRING = TypeExpr.named(TypeReference.of("builtin", "string"));
    private static final TypeExpr NUMBER = TypeExpr.named(TypeReference.of("builtin", "number"));
    private static final TypeExpr.ResourceType FLOW = TypeExpr.resource(TypeReference.of("restudio.resync", "flow"));

    @Test
    void canonicalSchemaPreservesOwnedUnknownDataAndRejectsLegacyAmbiguity() {
        OptionQuerySchemaV1 schema = new OptionQuerySchemaV1(
            new OptionQuerySchemaV1.ResourceSlot(FLOW, true, Map.of("futureResource", List.of("kept"))),
            Map.of("project", new OptionQuerySchemaV1.Field(STRING, true, TypedValue.value(STRING, "main"),
                Map.of("futureField", true))),
            Map.of(),
            Map.of("futureSchema", Map.of("version", 2)));

        Map<String, Object> canonical = schema.canonicalValue();
        Map<?, ?> resource = (Map<?, ?>) canonical.get("resource");
        Map<?, ?> project = (Map<?, ?>) ((Map<?, ?>) canonical.get("context")).get("project");

        assertEquals(OptionQuerySchemaV1.SCHEMA_VERSION, canonical.get("schemaVersion"));
        assertEquals(Map.of("version", 2), canonical.get("futureSchema"));
        assertEquals(List.of("kept"), resource.get("futureResource"));
        assertEquals(true, project.get("futureField"));
        assertSame(schema, OptionQuerySchemaV1.fromLegacy(schema));
        assertEquals(OptionQuerySchemaV1.empty(), OptionQuerySchemaV1.fromLegacy(Map.of()));
        assertTrue(OptionQuerySchemaV1.fromLegacy(FLOW).resource().required());
        assertThrows(IllegalArgumentException.class, () -> OptionQuerySchemaV1.fromLegacy(STRING));
        assertThrows(IllegalArgumentException.class, () -> OptionQuerySchemaV1.fromLegacy(Map.of("dependencies", List.of("project"))));
        assertThrows(IllegalArgumentException.class, () -> new OptionQuerySchemaV1(null, Map.of(), Map.of(),
            Map.of("schemaVersion", 2)));
        assertThrows(IllegalArgumentException.class, () -> new OptionQuerySchemaV1.Field(STRING, false, null,
            Map.of("type", "collision")));
        assertThrows(UnsupportedOperationException.class, () -> schema.context().put("other",
            new OptionQuerySchemaV1.Field(STRING, false)));
    }

    @Test
    void normalizationMaterializesDefaultsAndRejectsUnownedOrMistypedFields() {
        TypedValue defaultProject = TypedValue.value(STRING, "main");
        TypeExpr.OptionalType optionalString = TypeExpr.optional(STRING);
        TypedValue defaultNull = TypedValue.nullValue(optionalString);
        OptionQuerySchemaV1 schema = new OptionQuerySchemaV1(null, Map.of(
            "optional", new OptionQuerySchemaV1.Field(STRING, false),
            "optionalNull", new OptionQuerySchemaV1.Field(optionalString, false),
            "project", new OptionQuerySchemaV1.Field(STRING, true, defaultProject),
            "required", new OptionQuerySchemaV1.Field(NUMBER, true)), Map.of(
            "defaultNull", new OptionQuerySchemaV1.Field(optionalString, false, defaultNull)));

        OptionQuerySchemaV1.Normalized normalized = schema.normalize(SERVER, null,
            Map.of("optionalNull", TypedValue.nullValue(optionalString), "project", TypedValue.absent(STRING),
                "required", TypedValue.value(NUMBER, 3)), Map.of());

        assertSame(defaultProject, normalized.context().get("project"));
        assertFalse(normalized.context().containsKey("optional"));
        assertEquals(TypedValue.State.NULL, normalized.context().get("optionalNull").state());
        assertEquals(3, normalized.context().get("required").value());
        assertSame(defaultNull, normalized.dependencies().get("defaultNull"));
        assertNull(normalized.resource());
        assertThrows(IllegalArgumentException.class, () -> schema.normalize(SERVER, null, Map.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> schema.normalize(SERVER, null,
            Map.of("project", TypedValue.absent(NUMBER), "required", TypedValue.value(NUMBER, 3)), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> schema.normalize(SERVER, null,
            Map.of("unexpected", TypedValue.value(STRING, "value"), "required", TypedValue.value(NUMBER, 3)), Map.of()));

        LinkedHashMap<String, TypedValue> nullValue = new LinkedHashMap<>();
        nullValue.put("required", null);
        assertThrows(IllegalArgumentException.class, () -> schema.normalize(SERVER, null, nullValue, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new OptionQuerySchemaV1.Field(STRING, false,
            TypedValue.absent(STRING)));
        assertThrows(IllegalArgumentException.class, () -> new OptionQuerySchemaV1.Field(STRING, false,
            TypedValue.nullValue(STRING)));
    }

    @Test
    void normalizationChecksTypedResourceShapeServerAndExplicitNestedNulls() {
        ServerResourceLocator flow = locator(SERVER, "flow", "main");
        ServerResourceLocator otherServerFlow = locator(OTHER_SERVER, "flow", "main");
        TypeExpr optionalFlowList = TypeExpr.list(TypeExpr.optional(FLOW));
        ArrayList<Object> values = new ArrayList<>();
        values.add(flow);
        values.add(null);
        OptionQuerySchemaV1 schema = new OptionQuerySchemaV1(new OptionQuerySchemaV1.ResourceSlot(FLOW, true),
            Map.of("flows", new OptionQuerySchemaV1.Field(optionalFlowList, true)), Map.of());

        OptionQuerySchemaV1.Normalized normalized = schema.normalize(SERVER, flow,
            Map.of("flows", TypedValue.value(optionalFlowList, values)), Map.of());

        assertEquals(flow, normalized.resource());
        assertEquals(values, normalized.context().get("flows").value());
        assertThrows(IllegalArgumentException.class, () -> schema.normalize(SERVER, otherServerFlow,
            Map.of("flows", TypedValue.value(optionalFlowList, values)), Map.of()));

        ArrayList<Object> wrongServerValues = new ArrayList<>();
        wrongServerValues.add(otherServerFlow);
        assertThrows(IllegalArgumentException.class, () -> schema.normalize(SERVER, flow,
            Map.of("flows", TypedValue.value(optionalFlowList, wrongServerValues)), Map.of()));

        TypeExpr plainList = TypeExpr.list(STRING);
        ArrayList<Object> nullText = new ArrayList<>();
        nullText.add(null);
        OptionQuerySchemaV1 plainSchema = new OptionQuerySchemaV1(null,
            Map.of("values", new OptionQuerySchemaV1.Field(plainList, true)), Map.of());
        assertThrows(IllegalArgumentException.class, () -> plainSchema.normalize(SERVER, null,
            Map.of("values", TypedValue.value(plainList, nullText)), Map.of()));

        OptionQuerySchemaV1 defaultSchema = new OptionQuerySchemaV1(null, Map.of(), Map.of(
            "flow", new OptionQuerySchemaV1.Field(FLOW, true, TypedValue.value(FLOW, otherServerFlow))));
        assertThrows(IllegalArgumentException.class, () -> defaultSchema.normalize(SERVER, null, Map.of(), Map.of()));
    }

    @Test
    void opaqueAndUnknownMaterialNeverGainLocatorAuthorityByShape() {
        ServerResourceLocator foreign = locator(OTHER_SERVER, "flow", "foreign");
        TypeExpr.OpaqueType opaqueType = TypeExpr.opaque(TypeReference.of("future.extension", "raw-option-context"));
        TypedValue opaque = TypedValue.opaque(opaqueType, Map.of("locator", foreign.canonicalValue()),
            Map.of("metadataLocator", foreign));
        TypedValue text = TypedValue.value(STRING, "main", Map.of("metadataLocator", foreign));
        OptionQuerySchemaV1 schema = new OptionQuerySchemaV1(null, Map.of(
            "opaque", new OptionQuerySchemaV1.Field(opaqueType, true),
            "text", new OptionQuerySchemaV1.Field(STRING, true)), Map.of());

        OptionQuerySchemaV1.Normalized normalized = schema.normalize(SERVER, null,
            Map.of("opaque", opaque, "text", text), Map.of());

        assertSame(opaque, normalized.context().get("opaque"));
        assertEquals(foreign.canonicalValue(), ((Map<?, ?>) opaque.value()).get("locator"));
        assertEquals(foreign, normalized.context().get("text").unknown().get("metadataLocator"));

        TypedValue opaqueLocator = TypedValue.opaque(opaqueType, Map.of("nested", List.of(foreign)));
        assertThrows(IllegalArgumentException.class, () -> schema.normalize(SERVER, null,
            Map.of("opaque", opaqueLocator, "text", text), Map.of()));

        TypeExpr externalType = TypeExpr.named(TypeReference.of("future.extension", "validated-option-context"));
        TypedValue external = TypedValue.value(externalType, Map.of("value", "unvalidated"));
        OptionQuerySchemaV1 externalSchema = new OptionQuerySchemaV1(null,
            Map.of("external", new OptionQuerySchemaV1.Field(externalType, true)), Map.of());
        assertThrows(IllegalArgumentException.class, () -> externalSchema.normalize(SERVER, null,
            Map.of("external", external), Map.of()));

        ServerResourceLocator wrongType = locator(SERVER, "world", "main");
        OptionQuerySchemaV1 resourceSchema = OptionQuerySchemaV1.requiredResource(FLOW);
        assertThrows(IllegalArgumentException.class, () -> resourceSchema.normalize(SERVER, wrongType, Map.of(), Map.of()));
    }

    @Test
    void typedValuesRejectMislabeledScalarAndStructuralMaterialBeforeNormalization() {
        TypeExpr stringList = TypeExpr.list(STRING);
        TypeExpr tuple = TypeExpr.tuple(List.of(STRING, NUMBER));

        assertThrows(IllegalArgumentException.class, () -> TypedValue.value(STRING, 3));
        assertThrows(IllegalArgumentException.class, () -> TypedValue.value(stringList, List.of("valid", 3)));
        assertThrows(IllegalArgumentException.class, () -> TypedValue.value(tuple, List.of("valid", "not-a-number")));
    }

    private static ServerResourceLocator locator(ServerId server, String type, String id) {
        return new ServerResourceLocator(server,
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of(type)), id);
    }
}
