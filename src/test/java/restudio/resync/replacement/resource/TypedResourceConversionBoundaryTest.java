package restudio.resync.replacement.resource;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowResourceReference;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.type.TypeReference;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypedResourceConversionBoundaryTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-5111-8111-111111111111"));
    private static final ServerId OTHER_SERVER = new ServerId(UUID.fromString("22222222-2222-5222-8222-222222222222"));
    private static final TypeReference QUEST = TypeReference.of("fixture", "quest");

    @Test
    void convertsCompleteLocatorAndFreezesUnknownFields() {
        Map<String, Object> future = new LinkedHashMap<>();
        future.put("nested", new ArrayList<>(List.of(Map.of("enabled", true))));
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("serverId", SERVER.canonicalText());
        raw.put("type", Map.of("ownerId", "fixture", "localId", "quest", "futureType", future));
        raw.put("id", "main");
        raw.put("futureLocator", future);

        ServerResourceLocator locator = TypedResourceConversionBoundary.convert(raw, SERVER, QUEST);

        assertEquals(SERVER, locator.serverId());
        assertEquals("fixture", locator.owner().value());
        assertEquals("quest", locator.resourceType().value());
        assertEquals("main", locator.id());
        assertEquals(true, ((Map<?, ?>) locator.unknown().get("futureLocator")).containsKey("nested"));
        assertEquals(true, locator.type().unknown().containsKey("futureType"));
        assertThrows(UnsupportedOperationException.class,
            () -> ((Map<String, Object>) locator.unknown().get("futureLocator")).put("newField", true));
        assertThrows(UnsupportedOperationException.class,
            () -> ((List<?>) ((Map<?, ?>) locator.unknown().get("futureLocator")).get("nested")).clear());
    }

    @Test
    void preservesAndValidatesExplicitGenericArguments() {
        Map<String, Object> argument = Map.of(
            "kind", "resource",
            "resourceType", Map.of("ownerId", "fixture", "localId", "quest"));
        Map<String, Object> raw = locator(Map.of(
            "ownerId", "fixture",
            "localId", "box",
            "arguments", List.of(argument)));

        ServerResourceLocator locator = TypedResourceConversionBoundary.requireLocator(raw, SERVER);

        assertEquals(List.of(argument), locator.type().unknown().get("arguments"));
        assertThrows(IllegalArgumentException.class,
            () -> TypedResourceConversionBoundary.requireLocator(locator(Map.of(
                "ownerId", "fixture", "localId", "box", "arguments", List.of("quest"))), SERVER));
        assertThrows(IllegalArgumentException.class,
            () -> TypedResourceConversionBoundary.requireLocator(locator(Map.of(
                "ownerId", "fixture", "localId", "box", "arguments", List.of(Map.of("kind", "named")))), SERVER));
    }

    @Test
    void rejectsLegacyFieldsInsideNestedGenericTypeReferences() {
        assertReason(TypedResourceConversionBoundary.LEGACY_SHAPE,
            () -> TypedResourceConversionBoundary.requireLocator(locator(Map.of(
                "ownerId", "fixture",
                "localId", "box",
                "arguments", List.of(Map.of(
                    "kind", "resource",
                    "resourceType", Map.of("ownerId", "fixture", "localId", "quest", "kind", "resource"))))), SERVER));
        assertReason(TypedResourceConversionBoundary.LEGACY_SHAPE,
            () -> TypedResourceConversionBoundary.requireLocator(locator(Map.of(
                "ownerId", "fixture",
                "localId", "box",
                "arguments", List.of(Map.of(
                    "kind", "named",
                    "type", Map.of("ownerId", "fixture", "localId", "quest", "resourceType", "legacy"),
                    "arguments", List.of())))), SERVER));
    }

    @Test
    void enforcesCoreIdentityGrammarForEveryNestedTypeReference() {
        Map<String, Object> invalidOwner = locator(Map.of("ownerId", "fixture", "localId", "box",
            "arguments", List.of(resourceType(Map.of("ownerId", "fixture owner", "localId", "quest")))));
        assertReason(TypedResourceConversionBoundary.TYPE_ARGUMENTS_REQUIRED,
            () -> TypedResourceConversionBoundary.requireLocator(invalidOwner, SERVER));

        Map<String, Object> invalidLocal = locator(Map.of("ownerId", "fixture", "localId", "box",
            "arguments", List.of(resourceType(Map.of("ownerId", "fixture", "localId", "Quest")))));
        assertReason(TypedResourceConversionBoundary.TYPE_ARGUMENTS_REQUIRED,
            () -> TypedResourceConversionBoundary.requireLocator(invalidLocal, SERVER));

        Map<String, Object> invalidNestedLocal = locator(Map.of("ownerId", "fixture", "localId", "box",
            "arguments", List.of(Map.of("kind", "optional",
                "element", resourceType(Map.of("ownerId", "fixture", "localId", "quest type"))))));
        assertReason(TypedResourceConversionBoundary.TYPE_ARGUMENTS_REQUIRED,
            () -> TypedResourceConversionBoundary.requireLocator(invalidNestedLocal, SERVER));

        Map<String, Object> overlongLocal = locator(Map.of("ownerId", "fixture", "localId", "box",
            "arguments", List.of(resourceType(Map.of("ownerId", "fixture", "localId", "a".repeat(129))))));
        assertReason(TypedResourceConversionBoundary.TYPE_ARGUMENTS_REQUIRED,
            () -> TypedResourceConversionBoundary.requireLocator(overlongLocal, SERVER));

        Map<String, Object> invalidVariant = locator(Map.of("ownerId", "fixture", "localId", "box",
            "arguments", List.of(Map.of("kind", "union", "variants", List.of(
                Map.of("variantId", "variant with space", "type", named("quest")),
                Map.of("variantId", "other", "type", named("quest")))))));
        assertReason(TypedResourceConversionBoundary.TYPE_ARGUMENTS_REQUIRED,
            () -> TypedResourceConversionBoundary.requireLocator(invalidVariant, SERVER));
    }

    @Test
    void enforcesCoreGenericCardinalityAndCanonicalVariantIds() {
        assertThrows(IllegalArgumentException.class,
            () -> TypedResourceConversionBoundary.requireLocator(locator(Map.of(
                "ownerId", "fixture", "localId", "box", "arguments", namedArguments(17))), SERVER));
        assertThrows(IllegalArgumentException.class,
            () -> TypedResourceConversionBoundary.requireLocator(locator(Map.of(
                "ownerId", "fixture", "localId", "box", "arguments", List.of(
                    Map.of("kind", "tuple", "elements", List.of())))), SERVER));
        assertThrows(IllegalArgumentException.class,
            () -> TypedResourceConversionBoundary.requireLocator(locator(Map.of(
                "ownerId", "fixture", "localId", "box", "arguments", List.of(
                    Map.of("kind", "tuple", "elements", namedArguments(17))))), SERVER));
        assertThrows(IllegalArgumentException.class,
            () -> TypedResourceConversionBoundary.requireLocator(locator(Map.of(
                "ownerId", "fixture", "localId", "box", "arguments", List.of(
                    Map.of("kind", "union", "variants", unionVariants(17))))), SERVER));
        assertThrows(IllegalArgumentException.class,
            () -> TypedResourceConversionBoundary.requireLocator(locator(Map.of(
                "ownerId", "fixture", "localId", "box", "arguments", List.of(
                    Map.of("kind", "union", "variants", List.of(
                        Map.of("variantId", "NotCanonical", "type", named("quest")),
                        Map.of("variantId", "other", "type", named("quest"))))))), SERVER));
        assertThrows(IllegalArgumentException.class,
            () -> TypedResourceConversionBoundary.requireLocator(locator(Map.of(
                "ownerId", "fixture", "localId", "box", "arguments", List.of(
                    Map.of("kind", "union", "variants", List.of(
                        Map.of("variantId", "same", "type", named("quest")),
                        Map.of("variantId", "same", "type", named("other"))))))), SERVER));
    }

    @Test
    void acceptsOnlyMatchingFrozenKeyAndRejectsAlternateLocatorEncodings() {
        Map<String, Object> raw = new LinkedHashMap<>(locator(Map.of(
            "ownerId", "fixture", "localId", "quest")));
        raw.put("key", Map.of(
            "type", Map.of("ownerId", "fixture", "localId", "quest"),
            "id", "main"));
        assertEquals("main", TypedResourceConversionBoundary.requireLocator(raw, SERVER).id());

        Map<String, Object> wrongId = new LinkedHashMap<>(raw);
        wrongId.put("key", Map.of(
            "type", Map.of("ownerId", "fixture", "localId", "quest"),
            "id", "other"));
        assertReason(TypedResourceConversionBoundary.TYPED_LOCATOR_REQUIRED,
            () -> TypedResourceConversionBoundary.requireLocator(wrongId, SERVER));

        Map<String, Object> alternate = new LinkedHashMap<>(locator(Map.of(
            "ownerId", "fixture", "localId", "quest")));
        alternate.put("resourceKey", Map.of(
            "type", Map.of("ownerId", "fixture", "localId", "quest"),
            "id", "main"));
        assertReason(TypedResourceConversionBoundary.LEGACY_SHAPE,
            () -> TypedResourceConversionBoundary.requireLocator(alternate, SERVER));

        Map<String, Object> flat = new LinkedHashMap<>(locator(Map.of(
            "ownerId", "fixture", "localId", "quest")));
        flat.put("ownerId", "fixture");
        flat.put("localId", "quest");
        assertReason(TypedResourceConversionBoundary.LEGACY_SHAPE,
            () -> TypedResourceConversionBoundary.requireLocator(flat, SERVER));
    }

    @Test
    void rejectsRawLegacyFlatAndIncompleteShapes() {
        assertReason(TypedResourceConversionBoundary.TYPED_LOCATOR_REQUIRED,
            () -> TypedResourceConversionBoundary.convert("main", SERVER));
        assertReason(TypedResourceConversionBoundary.TYPED_LOCATOR_REQUIRED,
            () -> TypedResourceConversionBoundary.convert(new FlowResourceReference("quest", "main", "fixture"), SERVER));
        assertReason(TypedResourceConversionBoundary.LEGACY_SHAPE,
            () -> TypedResourceConversionBoundary.convert(Map.of(
                "serverId", SERVER.canonicalText(), "kind", "quest", "owner", "fixture", "id", "main"), SERVER));
        assertReason(TypedResourceConversionBoundary.TYPED_LOCATOR_REQUIRED,
            () -> TypedResourceConversionBoundary.convert(Map.of(
                "serverId", SERVER.canonicalText(), "id", "main"), SERVER));
        assertReason(TypedResourceConversionBoundary.TYPED_LOCATOR_REQUIRED,
            () -> TypedResourceConversionBoundary.convert(Map.of(
                "serverId", SERVER.canonicalText(), "type", Map.of("ownerId", "fixture", "localId", "quest"), "id", ""), SERVER));
    }

    @Test
    void rejectsSuppliedServerAndTypeThatDoNotMatchAuthoritativeContext() {
        assertReason(TypedResourceConversionBoundary.SERVER_MISMATCH,
            () -> TypedResourceConversionBoundary.convert(locator(OTHER_SERVER, Map.of(
                "ownerId", "fixture", "localId", "quest"), "main"), SERVER));
        assertReason(TypedResourceConversionBoundary.TYPE_MISMATCH,
            () -> TypedResourceConversionBoundary.convert(locator(Map.of(
                "ownerId", "fixture", "localId", "other")), SERVER, QUEST));
    }

    @Test
    void keepsActivationForbiddenAtConversionBoundary() {
        assertFalse(TypedResourceConversionBoundary.activationAllowed());
        assertEquals("forbidden", TypedResourceConversionBoundary.ACTIVATION_FORBIDDEN);
    }

    private static Map<String, Object> locator(Map<String, Object> type) {
        return locator(SERVER, type, "main");
    }

    private static Map<String, Object> locator(ServerId server, Map<String, Object> type, String id) {
        return Map.of("serverId", server.canonicalText(), "type", type, "id", id);
    }

    private static Map<String, Object> named(String localId) {
        return Map.of(
            "kind", "named",
            "type", Map.of("ownerId", "fixture", "localId", localId),
            "arguments", List.of());
    }

    private static Map<String, Object> resourceType(Map<String, String> type) {
        return Map.of("kind", "resource", "resourceType", type);
    }

    private static List<Object> namedArguments(int count) {
        List<Object> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            values.add(named("quest"));
        }
        return values;
    }

    private static List<Object> unionVariants(int count) {
        List<Object> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            values.add(Map.of("variantId", "variant-" + index, "type", named("quest")));
        }
        return values;
    }

    private static void assertReason(String reason, Runnable action) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, action::run);
        assertTrue(failure.getMessage().startsWith(reason));
    }
}
