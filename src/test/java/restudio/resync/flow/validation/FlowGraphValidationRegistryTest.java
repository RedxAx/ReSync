package restudio.resync.flow.validation;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowGraph;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowGraphValidationRegistryTest {
    @Test
    void validatorsAreNamespacedOrderedAndLifecycleOwned() {
        FlowGraphValidationRegistry registry = new FlowGraphValidationRegistry();
        registry.register("request", "request:second", graph -> List.of(diagnostic("SECOND", graph)));
        registry.register("request", "request:first", graph -> List.of(diagnostic("FIRST", graph)));

        List<FlowGraphDiagnostic> diagnostics = registry.validate(graph());

        assertEquals(List.of("FIRST", "SECOND"), diagnostics.stream().map(FlowGraphDiagnostic::code).toList());
        assertEquals(List.of("request:first", "request:second"), registry.inventory().stream().map(item -> item.get("id")).toList());
        registry.unregisterOwner("request");
        assertTrue(registry.validate(graph()).isEmpty());
    }

    @Test
    void brokenValidatorProducesStructuredFailureWithoutSuppressingSiblings() {
        FlowGraphValidationRegistry registry = new FlowGraphValidationRegistry();
        registry.register("request", "request:broken", graph -> {
            throw new IllegalStateException("Broken rule");
        });
        registry.register("request", "request:healthy", graph -> List.of(diagnostic("HEALTHY", graph)));

        List<FlowGraphDiagnostic> diagnostics = registry.validate(graph());

        assertEquals(List.of("EXTENSION_VALIDATOR_FAILED", "HEALTHY"), diagnostics.stream().map(FlowGraphDiagnostic::code).toList());
        assertTrue(diagnostics.getFirst().message().contains("request:broken"));
    }

    @Test
    void duplicateAndForeignNamespaceRegistrationsAreRejectedPrecisely() {
        FlowGraphValidationRegistry registry = new FlowGraphValidationRegistry();
        registry.register("request", "request:quests", graph -> List.of());

        assertThrows(IllegalArgumentException.class, () -> registry.register("request", "other:quests", graph -> List.of()));
        assertThrows(IllegalArgumentException.class, () -> registry.register("request", "request:quests", graph -> List.of()));
        registry.unregister("other", "request:quests");
        assertTrue(registry.contains("request:quests"));
        registry.unregister("request", "request:quests");
        assertFalse(registry.contains("request:quests"));
    }

    private FlowGraphDiagnostic diagnostic(String code, FlowGraph graph) {
        return new FlowGraphDiagnostic(FlowGraphDiagnostic.Severity.ERROR, code, graph.getId(), "", "", code, "Fix " + code);
    }

    private FlowGraph graph() {
        return new FlowGraph("test", Map.of(), List.of(), List.of());
    }
}
