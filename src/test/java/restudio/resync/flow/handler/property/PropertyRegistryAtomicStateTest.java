package restudio.resync.flow.handler.property;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowTypeRef;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertyRegistryAtomicStateTest {
    @Test
    void snapshotsAreImmutableAndIndependentFromTheirSource() {
        PropertyRegistry source = registry("alpha", FlowDataType.STRING, List.of("get"));
        PropertyRegistry target = new PropertyRegistry();
        target.replaceFrom(source);

        assertThrows(UnsupportedOperationException.class, () -> target.getFamilies().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> target.getProperties("alpha").add("x"));
        assertThrows(UnsupportedOperationException.class, () -> target.getDescriptor("alpha", "marker").actions().add("set"));

        source.replaceNodeDefinitions(List.of());

        assertTrue(target.hasFamily("alpha"));
        assertEquals(List.of("marker"), target.getProperties("alpha"));
        assertEquals(List.of("get"), target.getActions("alpha", "marker"));
    }

    @Test
    void replacementPublishesFamiliesAndDescriptorsTogether() throws Exception {
        PropertyRegistry alpha = registry("alpha", FlowDataType.STRING, List.of("get"));
        PropertyRegistry beta = registry("beta", FlowDataType.INTEGER, List.of("set"));
        PropertyRegistry target = new PropertyRegistry();
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
                    List<String> families = target.getFamilies();
                    assertEquals(1, families.size());
                    String family = families.getFirst();
                    assertTrue(family.equals("alpha") || family.equals("beta"));
                    List<String> properties = target.getProperties(family);
                    assertTrue(properties.isEmpty() || properties.equals(List.of("marker")));
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

        assertNullFailure(failure);
        assertFalse(target.getFamilies().isEmpty());
    }

    @Test
    void unregisterRemovesDescriptorOnlyProperties() {
        PropertyRegistry registry = new PropertyRegistry();
        registry.registerDescriptor(new PropertyRegistry.PropertyDescriptor("inventory", "size", FlowTypeRef.simple("integer"),
            List.of("get"), true, false, false, false, "builtin"));

        assertTrue(registry.hasFamily("inventory"));
        assertTrue(registry.hasProperty("inventory", "size"));

        registry.unregister("inventory", "size");

        assertFalse(registry.hasFamily("inventory"));
        assertFalse(registry.hasProperty("inventory", "size"));
    }

    @Test
    void invalidHandlerRegistrationDoesNotPublishState() {
        PropertyRegistry registry = new PropertyRegistry();

        assertThrows(IllegalArgumentException.class, () -> registry.register("inventory", "size", null));
        assertThrows(IllegalArgumentException.class, () -> registry.register("", "size", handler(FlowDataType.INTEGER, List.of("get"))));

        assertTrue(registry.getFamilies().isEmpty());
    }

    private PropertyRegistry registry(String family, FlowDataType type, List<String> actions) {
        PropertyRegistry registry = new PropertyRegistry();
        registry.register(family, "marker", handler(type, actions));
        return registry;
    }

    private PropertyHandler<Object, Object> handler(FlowDataType type, List<String> actions) {
        return new PropertyHandler<>() {
            @Override
            public String getPropertyName() {
                return "marker";
            }

            @Override
            public FlowDataType getDataType() {
                return type;
            }

            @Override
            public List<String> getSupportedActions() {
                return actions;
            }

            @Override
            public Object get(Object target) {
                return null;
            }

            @Override
            public boolean set(Object target, Object value) {
                return true;
            }

            @Override
            public boolean execute(Object target) {
                return true;
            }
        };
    }

    private void assertNullFailure(AtomicReference<Throwable> failure) {
        Throwable throwable = failure.get();
        if (throwable != null) {
            throw new AssertionError(throwable);
        }
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
