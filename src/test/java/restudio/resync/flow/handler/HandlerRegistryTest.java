package restudio.resync.flow.handler;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandlerRegistryTest {
    @Test
    void configuredOperationsRequireAnExecutableDeclaration() {
        HandlerRegistry registry = new HandlerRegistry();
        registry.register("metadataOnly", (context, node) -> {
        });
        registry.register("executable", new DeclaredHandler());

        assertTrue(registry.hasOperation("metadataOnly", ""));
        assertFalse(registry.hasOperation("metadataOnly", "mutate"));
        assertFalse(registry.hasOperation("missing", "mutate"));
        assertTrue(registry.hasOperation("executable", "mutate"));
        assertFalse(registry.hasOperation("executable", "missing"));
    }

    private static final class DeclaredHandler implements NodeHandler {
        @Override
        public void execute(FlowContext context, FlowNode node) {
        }

        @Override
        public Set<String> getSupportedOperations() {
            return Set.of("mutate");
        }
    }
}
