package restudio.resync.server;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.runtime.ReplacementRuntimeProviderAuthority;
import restudio.resync.migration.PersistenceShutdownStatus;
import restudio.resync.migration.ReSyncPersistenceCoordinator;
import restudio.resync.modules.FlowRuntimeModule;
import restudio.resync.modules.Module;
import restudio.resync.modules.ModuleRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

class ReSyncServerModuleTopologyTest {
    @Test
    void productionModuleGraphIsCompleteAndAcyclic() {
        ModuleRegistry registry = new ModuleRegistry();
        List<Module> modules = ReSyncServer.coreModules();
        modules.forEach(registry::registerModule);

        List<String> order = registry.getInitializationOrder();

        assertEquals(modules.size(), order.size());
        assertTrue(order.indexOf("flowJobs") < order.indexOf("worldGen"));
        assertTrue(order.indexOf("playerTracking") < order.indexOf("worldManagement"));
        assertTrue(order.indexOf("worldManagement") < order.indexOf("flow"));
        assertTrue(order.indexOf("flow") < order.indexOf("chat"));
    }

    @Test
    void explicitReplacementProviderAuthorityIsPassedOnlyToFlowRuntimeModule() {
        ReplacementRuntimeProviderAuthority authority = ReplacementRuntimeProviderAuthority.unavailable();

        FlowRuntimeModule runtime = ReSyncServer.coreModules(authority).stream()
            .filter(FlowRuntimeModule.class::isInstance)
            .map(FlowRuntimeModule.class::cast)
            .findFirst()
            .orElseThrow();

        assertSame(authority, runtime.replacementRuntimeProviderAuthority());
    }

    @Test
    void shutsDownModulesBeforePersistenceQuiescesTheirStorage(@org.junit.jupiter.api.io.TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync"));
        Path stateRoot = Files.createDirectory(dataRoot.resolve("state"));
        Path stateFile = stateRoot.resolve("world-state.json");
        List<String> events = new ArrayList<>();
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(dataRoot, temporary.resolve("coordination"));
        coordinator.register(new restudio.resync.migration.RebindablePersistenceParticipant() {
            @Override
            public String owner() {
                return "resync.world-management";
            }

            @Override
            public Path root() {
                return stateRoot;
            }

            @Override
            public void flush() throws IOException {
                events.add("persistence.flush");
                assertEquals("final-state", Files.readString(stateFile));
            }

            @Override
            public void quiesce() {
                events.add("persistence.quiesce");
            }

            @Override
            public void resume() {
            }

            @Override
            public void rebind(Path activeRoot) {
            }

            @Override
            public void healthCheck() {
                events.add("persistence.health");
            }
        });

        ModuleRegistry modules = new ModuleRegistry() {
            @Override
            public void shutdownModules(restudio.resync.modules.ModuleContext context) {
                events.add("world-management.stop");
                try {
                    Files.writeString(stateFile, "final-state");
                } catch (IOException exception) {
                    throw new RuntimeException(exception);
                }
            }
        };

        PersistenceShutdownStatus status = ReSyncServer.shutdownModulesBeforePersistence(modules, null, coordinator);

        assertEquals(PersistenceShutdownStatus.State.CLOSED, status.state());
        assertEquals(List.of("world-management.stop", "persistence.flush", "persistence.quiesce", "persistence.health"), events);
        assertTrue(Files.exists(stateFile));

        PersistenceShutdownStatus repeated = ReSyncServer.shutdownModulesBeforePersistence(modules, null, coordinator);
        assertEquals(PersistenceShutdownStatus.State.CLOSED, repeated.state());
        assertEquals(List.of("world-management.stop", "persistence.flush", "persistence.quiesce", "persistence.health"), events);
    }
}
