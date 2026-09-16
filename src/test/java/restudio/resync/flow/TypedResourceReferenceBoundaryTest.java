package restudio.resync.flow;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowResourceReference;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TypedResourceReferenceBoundaryTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-5111-8111-111111111111"));
    private static final ServerId OTHER_SERVER = new ServerId(UUID.fromString("22222222-2222-5222-8222-222222222222"));
    private static final TypeExpr.ResourceType QUEST = TypeExpr.resource(TypeReference.of("fixture", "quest"));
    private static final ContractRef<ResourceTypeId> QUEST_TYPE = ContractRef.of(OwnerId.of("fixture"), ResourceTypeId.of("quest"));

    @Test
    void acceptsCompleteTypedLocatorAndPreservesUnknownValueData() {
        ServerResourceLocator locator = new ServerResourceLocator(SERVER, QUEST_TYPE, "main", Map.of("futureLocator", Map.of("enabled", true)));
        TypedValue value = TypedValue.locator(QUEST, locator, Map.of("futureValue", Map.of("revision", 4)));

        assertSame(locator, TypedResourceReferenceBoundary.requireLocator(QUEST, locator, SERVER));
        assertSame(value, TypedResourceReferenceBoundary.requireValue(QUEST, value, SERVER));
        assertEquals(true, value.unknown().get("futureValue") instanceof Map);
        assertEquals(true, locator.unknown().get("futureLocator") instanceof Map);
    }

    @Test
    void convertsACompleteKnownLegacyReferenceToTheInjectedServer() {
        FlowResourceReference legacy = new FlowResourceReference("quest", "main", "fixture");

        ServerResourceLocator locator = TypedResourceReferenceBoundary.requireLocator(QUEST, legacy, SERVER);

        assertEquals(SERVER, locator.serverId());
        assertEquals(QUEST_TYPE, locator.type());
        assertEquals("main", locator.id());
    }

    @Test
    void preservesNonIdentityLegacyReferenceMetadataDuringConversion() {
        FlowResourceReference legacy = new FlowResourceReference("quest", "main", "fixture", true,
            Map.of("revision", 4, "label", "Main Quest"));

        ServerResourceLocator locator = TypedResourceReferenceBoundary.requireLocator(QUEST, legacy, SERVER);

        assertEquals(4, locator.unknown().get("revision"));
        assertEquals("Main Quest", locator.unknown().get("label"));
    }

    @Test
    void rejectsUnknownLegacyReferencesBeforeCreatingAnAuthoritativeLocator() {
        FlowResourceReference unavailable = new FlowResourceReference("quest", "main", "fixture", false, Map.of());
        FlowResourceReference wrongOwner = new FlowResourceReference("quest", "main", "other");

        assertEquals("RESOURCE_REFERENCE_LEGACY_UNKNOWN", assertThrows(IllegalArgumentException.class,
            () -> TypedResourceReferenceBoundary.requireLocator(QUEST, unavailable, SERVER)).getMessage());
        assertEquals("RESOURCE_REFERENCE_TYPE_MISMATCH", assertThrows(IllegalArgumentException.class,
            () -> TypedResourceReferenceBoundary.requireLocator(QUEST, wrongOwner, SERVER)).getMessage());
    }

    @Test
    void rejectsWrongServerAndOwnerQualifiedType() {
        ServerResourceLocator wrongServer = new ServerResourceLocator(OTHER_SERVER, QUEST_TYPE, "main");
        ServerResourceLocator wrongType = new ServerResourceLocator(SERVER,
            ContractRef.of(OwnerId.of("fixture"), ResourceTypeId.of("other")), "main");

        IllegalArgumentException serverFailure = assertThrows(IllegalArgumentException.class,
            () -> TypedResourceReferenceBoundary.requireLocator(QUEST, wrongServer, SERVER));
        IllegalArgumentException typeFailure = assertThrows(IllegalArgumentException.class,
            () -> TypedResourceReferenceBoundary.requireLocator(QUEST, wrongType, SERVER));

        assertEquals("RESOURCE_REFERENCE_SERVER_MISMATCH", serverFailure.getMessage());
        assertEquals("RESOURCE_REFERENCE_TYPE_MISMATCH", typeFailure.getMessage());
    }

    @Test
    void rejectsRawStringAndLegacyReferenceWithoutCompleteTypedIdentity() {
        IllegalArgumentException rawString = assertThrows(IllegalArgumentException.class,
            () -> TypedResourceReferenceBoundary.requireLocator(QUEST, "main", SERVER));
        IllegalArgumentException legacyReference = assertThrows(IllegalArgumentException.class,
            () -> TypedResourceReferenceBoundary.requireLocator(QUEST, new FlowResourceReference("", "main", "fixture"), SERVER));

        assertEquals("RESOURCE_REFERENCE_TYPED_LOCATOR_REQUIRED", rawString.getMessage());
        assertEquals("RESOURCE_REFERENCE_TYPED_LOCATOR_REQUIRED", legacyReference.getMessage());
    }

    @Test
    void acceptsResourceLocatorInsideResultListMapAndOptionalShapes() {
        TypeExpr resultType = TypeExpr.result(
            TypeExpr.list(TypeExpr.map(TypeExpr.named(TypeReference.of("restudio.resync", "string")), TypeExpr.optional(QUEST))),
            QUEST);
        ServerResourceLocator locator = new ServerResourceLocator(SERVER, QUEST_TYPE, "nested");
        Map<String, Object> resultMaterial = Map.of(
            "success", true,
            "value", List.of(Map.of("selected", locator)),
            "futureResultField", Map.of("preserve", true));

        TypedValue value = TypedValue.value(resultType, resultMaterial);

        assertSame(value, TypedResourceReferenceBoundary.requireValue(resultType, value, SERVER));
        assertEquals(true, ((Map<?, ?>) value.value()).get("futureResultField") instanceof Map);
    }

    @Test
    void rejectsMalformedOrRawResourceResultBranchesBeforeCompilation() {
        TypeExpr resultType = TypeExpr.result(QUEST, QUEST);
        ServerResourceLocator locator = new ServerResourceLocator(SERVER, QUEST_TYPE, "nested");

        assertThrows(IllegalArgumentException.class,
            () -> TypedValue.value(resultType, Map.of("success", true)));
        assertThrows(IllegalArgumentException.class,
            () -> TypedValue.value(resultType, Map.of("success", true, "value", "nested")));
        assertThrows(IllegalArgumentException.class,
            () -> TypedValue.value(resultType, Map.of("success", false, "value", new FlowResourceReference("", "nested", "fixture"))));

        TypeExpr nestedNamed = TypeExpr.result(TypeExpr.named(TypeReference.of("fixture", "box"), List.of(QUEST)), QUEST);
        TypedValue preWrapped = TypedValue.value(nestedNamed, Map.of("success", true, "value", Map.of("selected", "nested")));
        assertThrows(IllegalArgumentException.class,
            () -> TypedResourceReferenceBoundary.requireValue(nestedNamed, preWrapped, SERVER));
        TypedValue valid = TypedResourceReferenceBoundary.requireValue(resultType,
            TypedValue.value(resultType, Map.of("success", true, "value", locator)), SERVER);
        assertSame(locator, ((Map<?, ?>) valid.value()).get("value"));
    }

    @Test
    void validatesServerAndOwnerQualifiedTypeInsideBothResultBranches() {
        TypeExpr resultType = TypeExpr.result(QUEST, QUEST);
        ServerResourceLocator wrongServer = new ServerResourceLocator(OTHER_SERVER, QUEST_TYPE, "nested");
        TypedValue wrongServerValue = TypedValue.value(resultType, Map.of("success", true, "value", wrongServer));

        IllegalArgumentException serverFailure = assertThrows(IllegalArgumentException.class,
            () -> TypedResourceReferenceBoundary.requireValue(resultType, wrongServerValue, SERVER));
        assertEquals("RESOURCE_REFERENCE_SERVER_MISMATCH", serverFailure.getMessage());

        TypeExpr namedResult = TypeExpr.result(TypeExpr.named(TypeReference.of("fixture", "box"), List.of(QUEST)), QUEST);
        ServerResourceLocator wrongType = new ServerResourceLocator(SERVER,
            ContractRef.of(OwnerId.of("fixture"), ResourceTypeId.of("other")), "nested");
        TypedValue wrongTypeValue = TypedValue.value(namedResult,
            Map.of("success", true, "value", Map.of("selected", wrongType)));

        IllegalArgumentException typeFailure = assertThrows(IllegalArgumentException.class,
            () -> TypedResourceReferenceBoundary.requireValue(namedResult, wrongTypeValue, SERVER));
        assertEquals("RESOURCE_REFERENCE_TYPE_MISMATCH", typeFailure.getMessage());

        TypedValue failureBranch = TypedValue.value(resultType, Map.of("success", false, "value", wrongServer));
        assertThrows(IllegalArgumentException.class,
            () -> TypedResourceReferenceBoundary.requireValue(resultType, failureBranch, SERVER));
    }

    @Test
    void enforcesTopLevelResourceNullability() {
        assertThrows(IllegalArgumentException.class,
            () -> TypedResourceReferenceBoundary.requireValue(QUEST, TypedValue.nullValue(QUEST), SERVER));

        TypeExpr optional = TypeExpr.optional(QUEST);
        TypedValue optionalNull = TypedValue.nullValue(optional);
        assertSame(optionalNull, TypedResourceReferenceBoundary.requireValue(optional, optionalNull, SERVER));
    }

    @Test
    void rejectsRawMapKeysWhenTheDeclaredKeyIsAResource() {
        TypeExpr mapType = TypeExpr.map(QUEST, TypeExpr.named(TypeReference.of("restudio.resync", "string")));

        assertThrows(IllegalArgumentException.class,
            () -> TypedValue.value(mapType, Map.of("raw-resource-id", "value")));
    }

    @Test
    void rejectsAResourceLocatorWithoutAKnownNestedShape() {
        TypeExpr namedContainer = TypeExpr.named(TypeReference.of("fixture", "box"), List.of(QUEST));
        ServerResourceLocator locator = new ServerResourceLocator(SERVER, QUEST_TYPE, "nested");
        TypedValue value = TypedValue.value(namedContainer, locator);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> TypedResourceReferenceBoundary.requireValue(namedContainer, value, SERVER));
        assertEquals("RESOURCE_REFERENCE_AMBIGUOUS_TYPE", failure.getMessage());
    }

    @Test
    void preservesEmptyOptionalEntriesInsideNestedCollections() {
        TypeExpr listType = TypeExpr.list(TypeExpr.optional(QUEST));
        ServerResourceLocator locator = new ServerResourceLocator(SERVER, QUEST_TYPE, "nested");
        List<Object> material = new ArrayList<>(List.of(locator));
        material.add(null);
        TypedValue value = TypedValue.value(listType, material);

        assertSame(value, TypedResourceReferenceBoundary.requireValue(listType, value, SERVER));
        List<Object> expected = new ArrayList<>(List.of(locator));
        expected.add(null);
        assertEquals(expected, value.value());
    }
}
