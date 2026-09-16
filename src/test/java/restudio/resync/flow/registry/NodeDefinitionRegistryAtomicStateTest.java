package restudio.resync.flow.registry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeDefinitionRegistryAtomicStateTest {
    @Test
    void snapshotsAreImmutableAndIndependentFromTheirSource() {
        NodeDefinitionRegistry source = registry("alpha", "alpha-plugin");
        NodeDefinitionRegistry target = new NodeDefinitionRegistry(false);
        target.replaceFrom(source);

        assertThrows(UnsupportedOperationException.class, () -> target.getAllDefinitions().put("x", definition("x", "x")));
        assertThrows(UnsupportedOperationException.class, () -> target.getDefinitionsForPlugin("alpha-plugin").add(definition("x", "x")));
        assertThrows(UnsupportedOperationException.class, () -> target.snapshotByPlugin().put("x", List.of()));
        assertThrows(UnsupportedOperationException.class, () -> target.snapshotByPlugin().get("alpha-plugin").add(definition("x", "x")));

        source.register("beta-plugin", definition("beta", "beta"));

        assertNotNull(target.get("alpha", "shared"));
        assertNull(target.get("beta", "beta"));
        assertEquals(1, target.getAllDefinitions().size());
    }

    @Test
    void replacementPublishesOneStateToConcurrentReaders() throws Exception {
        NodeDefinitionRegistry alpha = registry("alpha", "alpha-plugin");
        NodeDefinitionRegistry beta = registry("beta", "beta-plugin");
        NodeDefinitionRegistry target = new NodeDefinitionRegistry(false);
        target.replaceFrom(alpha);

        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean done = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            await(start);
            try {
                for (int index = 0; index < 20_000; index++) {
                    target.replaceFrom((index & 1) == 0 ? beta : alpha);
                }
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            } finally {
                done.set(true);
            }
        });
        Thread reader = new Thread(() -> {
            await(start);
            try {
                int reads = 0;
                while (!done.get() || reads < 20_000) {
                    NodeDefinition definition = target.get("shared");
                    assertNotNull(definition);
                    assertTrue(definition.getOwner().equals("alpha") || definition.getOwner().equals("beta"));
                    reads++;
                }
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }
        });
        writer.start();
        reader.start();
        start.countDown();
        writer.join();
        reader.join();

        assertNull(failure.get(), failure.get() != null ? failure.get().toString() : "");
        assertFalse(target.getAllDefinitions().isEmpty());
    }

    @Test
    void duplicateBatchDoesNotPublishAPartialState() {
        NodeDefinitionRegistry registry = registry("alpha", "alpha-plugin");

        assertThrows(IllegalArgumentException.class, () -> registry.registerAll("beta-plugin", List.of(
            definition("beta", "new"),
            definition("alpha", "shared"))));

        assertEquals(1, registry.getAllDefinitions().size());
        assertNull(registry.get("beta", "new"));
    }

    private NodeDefinitionRegistry registry(String owner, String plugin) {
        NodeDefinitionRegistry registry = new NodeDefinitionRegistry(false);
        registry.register(plugin, definition(owner, "shared"));
        return registry;
    }

    private NodeDefinition definition(String owner, String id) {
        return new NodeDefinition.Builder(id, id, NodeDefinition.NodeCategory.DATA)
            .owner(owner)
            .build();
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
