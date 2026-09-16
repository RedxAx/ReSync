package restudio.resync.flow.handler;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

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

    @Test
    void replacementPublishesBeforePreviousHandlerShutsDown() {
        HandlerRegistry registry = new HandlerRegistry();
        AtomicBoolean oldShutdown = new AtomicBoolean();
        NodeHandler oldHandler = new NodeHandler() {
            @Override
            public void execute(FlowContext context, FlowNode node) {
            }

            @Override
            public void shutdown() {
                oldShutdown.set(registry.getHandler("shared") != this);
            }
        };
        NodeHandler replacement = (context, node) -> {
        };

        registry.register("shared", oldHandler);
        registry.register("shared", replacement);

        assertTrue(oldShutdown.get());
        assertTrue(registry.getHandler("shared") == replacement);
    }

    @Test
    void stagedReplacementDoesNotShutdownUntilRetiredHandlersAreExplicitlyClosed() {
        HandlerRegistry active = new HandlerRegistry();
        AtomicBoolean oldShutdown = new AtomicBoolean();
        NodeHandler oldHandler = new NodeHandler() {
            @Override
            public void execute(FlowContext context, FlowNode node) {
            }

            @Override
            public void shutdown() {
                oldShutdown.set(true);
            }
        };
        NodeHandler replacement = (context, node) -> {
        };
        active.register("shared", oldHandler);

        HandlerRegistry staged = active.copy();
        staged.unregisterWithoutShutdown("shared");
        staged.register("shared", replacement);

        assertFalse(oldShutdown.get());
        active.replaceFrom(staged);
        assertFalse(oldShutdown.get());

        active.shutdown(Set.of(oldHandler));

        assertTrue(oldShutdown.get());
        assertTrue(active.getHandler("shared") == replacement);
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
