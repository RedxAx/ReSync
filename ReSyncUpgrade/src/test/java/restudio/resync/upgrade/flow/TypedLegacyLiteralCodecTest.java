package restudio.resync.upgrade.flow;

import org.junit.jupiter.api.Test;
import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypedLegacyLiteralCodecTest {
    @Test
    void rejectsLegacyStructureNamesWithoutTypedResourceAuthority() {
        TypeExpr structure = TypeExpr.resource(TypeReference.of("restudio.resync", "structure"));
        assertTrue(TypedLegacyLiteralCodec.decode(structure, parse("\"castle\"")).isEmpty());
        assertTrue(TypedLegacyLiteralCodec.decode(structure, parse("{\"id\":\"castle\"}")).isEmpty());
    }

    @Test
    void decodesOnlyExactPrimitiveBuiltinValues() {
        assertEquals("value", decode("string", "\"value\""));
        assertEquals(true, decode("boolean", "true"));
        assertEquals(new BigInteger("42"), decode("integer", "42"));
        assertEquals(new BigDecimal("1.25"), decode("number", "1.250"));
    }

    @Test
    void rejectsCoercionAndUnprovenTypes() {
        assertTrue(TypedLegacyLiteralCodec.decode(named("string"), parse("42")).isEmpty());
        assertTrue(TypedLegacyLiteralCodec.decode(named("integer"), parse("1.5")).isEmpty());
        assertTrue(TypedLegacyLiteralCodec.decode(named("any"), parse("{}" )).isEmpty());
        assertTrue(TypedLegacyLiteralCodec.decode(new TypeExpr.ListType(named("string"), Map.of()), parse("[]")).isEmpty());
        assertTrue(TypedLegacyLiteralCodec.decode(new TypeExpr.Named(TypeReference.of("custom", "value"), List.of(), Map.of()),
            parse("\"value\"")).isEmpty());
        assertTrue(TypedLegacyLiteralCodec.decode(named("uuid"),
            parse("\"22222222-2222-4222-8222-22222222222A\"")).isEmpty());
    }

    private static Object decode(String type, String json) {
        return TypedLegacyLiteralCodec.decode(named(type), parse(json)).orElseThrow().value();
    }

    private static JsonValue parse(String json) {
        return CanonicalCodec.decodePermissive(json);
    }

    private static TypeExpr.Named named(String id) {
        return new TypeExpr.Named(TypeReference.of("builtin", id), List.of(), Map.of());
    }
}
