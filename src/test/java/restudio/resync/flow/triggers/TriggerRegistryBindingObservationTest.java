package restudio.resync.flow.triggers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TriggerRegistryBindingObservationTest {
    @TempDir
    Path temporary;

    @Test
    void observationDoesNotWaitForRegistryMonitor() throws Exception {
        TriggerRegistry registry = registry("nonblocking");
        TriggerRegistry.BindingObservation observation = registry.bindingObservation();
        CountDownLatch monitorHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> holder = executor.submit(() -> {
            synchronized (registry) {
                monitorHeld.countDown();
                await(release);
            }
        });
        try {
            assertTrue(monitorHeld.await(5, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                assertTrue(registry.isCurrent(observation));
                assertTrue(registry.isCurrent(registry.bindingObservation()));
            });
        } finally {
            release.countDown();
            holder.get();
            executor.shutdownNow();
        }
    }

    @Test
    void mutationAndPersistenceLifecycleReplaceObservationIdentity() throws Exception {
        TriggerRegistry registry = registry("lifecycle");
        TriggerRegistry.BindingObservation initial = registry.bindingObservation();

        registry.addBinding(new TriggerBinding("binding", "flow", TriggerType.SYSTEM, null));
        TriggerRegistry.BindingObservation mutated = registry.bindingObservation();

        assertFalse(registry.isCurrent(initial));
        assertTrue(registry.isCurrent(mutated));

        registry.quiescePersistence();
        TriggerRegistry.BindingObservation quiesced = registry.bindingObservation();
        assertFalse(registry.isCurrent(mutated));
        assertFalse(registry.isCurrent(quiesced));

        Path replacement = Files.createDirectory(temporary.resolve("replacement"));
        Files.copy(registry.getPersistenceFile(), replacement.resolve("triggers.json"));
        registry.rebindPersistence(replacement);
        TriggerRegistry.BindingObservation rebound = registry.bindingObservation();
        assertFalse(registry.isCurrent(quiesced));
        assertFalse(registry.isCurrent(rebound));

        registry.resumePersistence();
        TriggerRegistry.BindingObservation resumed = registry.bindingObservation();
        assertFalse(registry.isCurrent(rebound));
        assertTrue(registry.isCurrent(resumed));

        registry.healthCheckPersistence();
        assertFalse(registry.isCurrent(resumed));
        assertTrue(registry.isCurrent(registry.bindingObservation()));
    }

    private TriggerRegistry registry(String name) throws Exception {
        Path root = Files.createDirectory(temporary.resolve(name));
        return new TriggerRegistry(root.resolve("triggers.json").toFile());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
