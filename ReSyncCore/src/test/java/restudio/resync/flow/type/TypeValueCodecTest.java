package restudio.resync.flow.type;

import org.junit.jupiter.api.Test;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TypeValueCodecTest {
    private static final TypeExpr TEXT = TypeExpr.named(TypeReference.of("builtin", "string"));
    private static final TypeExpr INTEGER = TypeExpr.named(TypeReference.of("builtin", "integer"));
    private static final TypeExpr RESOURCE = TypeExpr.resource(TypeReference.of("restudio", "world"));

    @Test
    void everyTypeExpressionVariantRoundTripsWithNestedUnknownData() {
        TypeReference future = new TypeReference("future", "value", Map.of("referenceFuture", true));
        List<TypeExpr> expressions = List.of(
            new TypeExpr.Named(future, List.of(TEXT), Map.of("namedFuture", true)),
            new TypeExpr.OptionalType(TEXT, Map.of("optionalFuture", true)),
            new TypeExpr.ListType(TEXT, Map.of("listFuture", true)),
            new TypeExpr.MapType(TEXT, INTEGER, Map.of("mapFuture", true)),
            new TypeExpr.TupleType(List.of(TEXT, INTEGER), Map.of("tupleFuture", true)),
            new TypeExpr.ResultType(TEXT, INTEGER, Map.of("resultFuture", true)),
            new TypeExpr.ResourceType(future, Map.of("resourceFuture", true)),
            new TypeExpr.UnionType(List.of(
                new TypeExpr.UnionVariant("failure", INTEGER, "Failure", "Failure branch value.", Map.of("variantFuture", true)),
                new TypeExpr.UnionVariant("success", TEXT)), Map.of("unionFuture", true)),
            new TypeExpr.OpaqueType(future, Map.of("opaqueFuture", true)));

        for (TypeExpr expression : expressions) {
            JsonValue encoded = TypeValueCodec.INSTANCE.encodeType(expression);
            TypeExpr decoded = TypeValueCodec.INSTANCE.decodeType(encoded);
            assertEquals(expression.canonicalJson(), decoded.canonicalJson());
        }
    }

    @Test
    void everyTypedValueStateAndUnionTagRoundTripsExactly() {
        ServerResourceLocator locator = new ServerResourceLocator(ServerId.deterministic("type-value-codec"),
            ContractRef.of(new OwnerId("restudio"), new ResourceTypeId("world")), "spawn");
        TypeExpr.OpaqueType opaque = new TypeExpr.OpaqueType(TypeReference.of("future", "blob"), Map.of("typeFuture", true));
        TypeExpr.UnionType union = new TypeExpr.UnionType(List.of(
            new TypeExpr.UnionVariant("resource", RESOURCE),
            new TypeExpr.UnionVariant("text", TEXT)));
        List<TypedValue> values = List.of(
            TypedValue.absent(TypeExpr.optional(TEXT), Map.of("absentFuture", true)),
            TypedValue.nullValue(TypeExpr.optional(TEXT), Map.of("nullFuture", true)),
            TypedValue.value(TypeExpr.list(INTEGER), List.of(BigInteger.ONE, BigInteger.TWO), Map.of("valueFuture", true)),
            TypedValue.locator(RESOURCE, locator, Map.of("locatorFuture", true)),
            TypedValue.opaque(opaque, Map.of("raw", List.of(1, 2)), Map.of("opaqueFuture", true)),
            TypedValue.unionValue(union, "text", "hello", Map.of("unionValueFuture", true)),
            TypedValue.unionLocator(union, "resource", locator, Map.of("unionLocatorFuture", true)));

        for (TypedValue value : values) {
            byte[] encoded = TypeValueCodec.INSTANCE.encodeBytes(value);
            TypedValue decoded = TypeValueCodec.INSTANCE.decodeBytes(encoded);
            assertArrayEquals(encoded, TypeValueCodec.INSTANCE.encodeBytes(decoded));
            assertEquals(value.canonicalJson(), decoded.canonicalJson());
        }
    }

    @Test
    void builtinMaterialRestoresExactRuntimeTypes() {
        TypeExpr uuidType = TypeExpr.named(TypeReference.of("builtin", "uuid"));
        TypeExpr numberType = TypeExpr.named(TypeReference.of("builtin", "number"));
        UUID uuid = UUID.fromString("11111111-1111-4111-8111-111111111111");

        assertEquals(uuid, TypeValueCodec.INSTANCE.decode(TypeValueCodec.INSTANCE.encode(TypedValue.value(uuidType, uuid))).value());
        assertEquals(new BigInteger("12345678901234567890"), TypeValueCodec.INSTANCE.decode(
            TypeValueCodec.INSTANCE.encode(TypedValue.value(INTEGER, new BigInteger("12345678901234567890")))).value());
        assertEquals(new BigDecimal("12.5"), TypeValueCodec.INSTANCE.decode(
            TypeValueCodec.INSTANCE.encode(TypedValue.value(numberType, new BigDecimal("12.500")))).value());
    }

    @Test
    void unsupportedSemanticVariantsFailClosed() {
        Map<String, JsonValue> unknownType = new LinkedHashMap<>();
        unknownType.put("kind", JsonValue.of("future"));
        assertThrows(IllegalArgumentException.class, () -> TypeValueCodec.INSTANCE.decodeType(JsonValue.object(unknownType)));

        Map<String, JsonValue> unknownState = new LinkedHashMap<>(TypeValueCodec.INSTANCE.encode(TypedValue.value(TEXT, "ok")).fields());
        unknownState.put("state", JsonValue.of("future"));
        assertThrows(IllegalArgumentException.class, () -> TypeValueCodec.INSTANCE.decode(JsonValue.object(unknownState)));

        Map<String, JsonValue> unresolvedUnion = new LinkedHashMap<>(TypeValueCodec.INSTANCE.encode(
            TypedValue.unionValue(new TypeExpr.UnionType(List.of(new TypeExpr.UnionVariant("a", TEXT),
                new TypeExpr.UnionVariant("b", INTEGER))), "a", "ok")).fields());
        unresolvedUnion.remove("variantId");
        assertThrows(IllegalArgumentException.class, () -> TypeValueCodec.INSTANCE.decode(JsonValue.object(unresolvedUnion)));
    }
}
