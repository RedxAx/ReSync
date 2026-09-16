package restudio.resync.flow.runtime;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompiledRuntimeContextTest {
    private static final TypeExpr STRING = TypeExpr.named(TypeReference.of("builtin", "string"));

    @Test
    void emptyContextIsTheOnlyCurrentlySupportedCompiledContext() {
        CompiledRuntimeContext context = CompiledRuntimeContext.empty();

        assertTrue(context.isEmpty());
        assertTrue(context.supportedByCompiledCore());
        assertEquals(Map.of("contract", "compiled-runtime-context-v1", "supported", true,
            "playerPresent", false, "eventPresent", false, "variablesPresent", false,
            "principalPresent", false), context.supportSummary());
    }

    @Test
    void typedContextIsCanonicalAndSupportedWhenValuesAreDeterministic() {
        Map<String, TypedValue> fields = new LinkedHashMap<>();
        fields.put("zeta", TypedValue.value(STRING, "last"));
        fields.put("alpha", TypedValue.value(STRING, "first"));
        CompiledRuntimeContext context = new CompiledRuntimeContext(
            new CompiledRuntimeContext.PlayerIdentity(UUID.fromString("11111111-1111-4111-8111-111111111111"), "Player"),
            new CompiledRuntimeContext.EventIdentity("restudio.Event", fields),
            fields);

        fields.put("mutated", TypedValue.value(STRING, "no"));

        assertFalse(context.isEmpty());
        assertTrue(context.supportedByCompiledCore());
        assertEquals(java.util.List.of("alpha", "zeta"), context.variables().keySet().stream().toList());
        assertEquals(java.util.List.of("alpha", "zeta"), context.event().fields().keySet().stream().toList());
        assertEquals("compiled-runtime-context-v1", context.supportSummary().get("contract"));
        assertThrows(UnsupportedOperationException.class,
            () -> context.variables().put("blocked", TypedValue.value(STRING, "no")));
    }

    @Test
    void opaqueValuesCannotCrossCompiledContextBoundary() {
        TypeExpr.OpaqueType opaqueType = TypeExpr.opaque(TypeReference.of("extension", "opaque"));
        TypedValue opaque = TypedValue.opaque(opaqueType, "legacy-object");

        assertThrows(IllegalArgumentException.class,
            () -> new CompiledRuntimeContext(null, null, Map.of("value", opaque)));
        assertThrows(IllegalArgumentException.class,
            () -> new CompiledRuntimeContext(null,
                new CompiledRuntimeContext.EventIdentity("restudio.Event", Map.of("value", opaque)), Map.of()));
    }
}
