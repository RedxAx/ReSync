package restudio.resync.server;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.resync.ReSync;
import restudio.resync.migration.FreshRootProvenance;
import restudio.resync.migration.LegacyInstallBoundary;
import restudio.resync.migration.ReSyncDataFixer;
import restudio.resync.migration.ReSyncPersistenceCoordinator;
import restudio.resync.upgrade.AssetCoordinatorMigration;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncPreparedRootTest {
    @TempDir
    Path temporary;

    private ReSync plugin;
    private Path data;
    private Path coordination;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkit.mock();
        plugin = MockBukkit.loadSimple(TestReSync.class);
        data = temporary.resolve("plugins/ReSync");
        coordination = data.resolveSibling(".resync-coordination");
        Files.createDirectories(data.getParent());
        Field field = JavaPlugin.class.getDeclaredField("dataFolder");
        field.setAccessible(true);
        field.set(plugin, data.toFile());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void admitsFreshBootstrapWithoutRecreatingAnAbsentSourceRoot() throws Exception {
        ReSyncPersistenceCoordinator.PreparedBootstrap prepared = bootstrap();
        ReSyncPersistenceCoordinator persistence = prepared.coordinator();
        try {
            Files.delete(data);

            assertDoesNotThrow(() -> admit(persistence, prepared.activeRoot()));

            assertFalse(Files.exists(data));
            assertEquals(prepared.activeRoot(), persistence.activeDataRoot());
            assertEquals(1, ReSyncDataFixer.installedVersion(prepared.activeRoot()).orElseThrow());
        } finally {
            persistence.close();
        }
    }

    @Test
    void resumesArchivedInstallationWithAnAbsentSourceAndPreservesBothRoots() throws Exception {
        Files.createDirectory(data);
        Files.writeString(data.resolve("legacy.json"), "legacy content");
        Files.createDirectory(coordination);
        Files.writeString(coordination.resolve("partial"), "partial coordination");
        Files.writeString(data.resolveSibling(LegacyInstallBoundary.RESET_MARKER), "");
        LegacyInstallBoundary.Result archive = LegacyInstallBoundary.prepare(data, coordination);
        assertTrue(archive.archived());
        ReSyncPersistenceCoordinator.PreparedBootstrap initial = bootstrap();
        Path active = initial.activeRoot();
        ReSyncPersistenceCoordinator initialPersistence = initial.coordinator();
        try {
            assertDoesNotThrow(() -> admit(initialPersistence, active));
            Files.writeString(active.resolve("current.json"), "current content");
            Files.delete(data);
        } finally {
            initialPersistence.close();
        }
        assertFalse(LegacyInstallBoundary.prepare(data, coordination).archived());

        ReSyncPersistenceCoordinator.PreparedBootstrap resumed = ReSyncPersistenceCoordinator.bootstrapPrepared(data, coordination);
        ReSyncPersistenceCoordinator persistence = resumed.coordinator();
        try {
            assertDoesNotThrow(() -> admit(persistence, resumed.activeRoot()));

            assertEquals(active, resumed.activeRoot());
            assertEquals("current content", Files.readString(active.resolve("current.json")));
            assertEquals("legacy content", Files.readString(archive.dataBackup().resolve("legacy.json")));
            assertEquals("partial coordination", Files.readString(archive.coordinationBackup().resolve("partial")));
            assertFalse(Files.exists(data));
        } finally {
            persistence.close();
        }
    }

    @Test
    void rejectsAFileAtTheOriginalRoot() throws Exception {
        ReSyncPersistenceCoordinator.PreparedBootstrap prepared = bootstrap();
        ReSyncPersistenceCoordinator persistence = prepared.coordinator();
        try {
            Files.delete(data);
            Files.writeString(data, "preserve");

            assertThrows(IllegalStateException.class, () -> admit(persistence, prepared.activeRoot()));

            assertEquals("preserve", Files.readString(data));
        } finally {
            persistence.close();
        }
    }

    @Test
    void rejectsASymlinkAtTheOriginalRoot() throws Exception {
        ReSyncPersistenceCoordinator.PreparedBootstrap prepared = bootstrap();
        ReSyncPersistenceCoordinator persistence = prepared.coordinator();
        try {
            Files.delete(data);
            Files.createSymbolicLink(data, prepared.activeRoot());

            assertThrows(RuntimeException.class, () -> admit(persistence, prepared.activeRoot()));

            assertTrue(Files.isSymbolicLink(data));
        } finally {
            persistence.close();
        }
    }

    @Test
    void rejectsAnUnrelatedActiveRootWhenTheSourceIsAbsent() throws Exception {
        ReSyncPersistenceCoordinator.PreparedBootstrap prepared = bootstrap();
        ReSyncPersistenceCoordinator persistence = prepared.coordinator();
        try {
            Files.delete(data);
            Path unrelated = Files.createDirectory(temporary.resolve("unrelated"));

            assertThrows(IllegalArgumentException.class, () -> admit(persistence, unrelated));

            assertEquals(prepared.activeRoot(), persistence.activeDataRoot());
        } finally {
            persistence.close();
        }
    }

    @Test
    void rejectsADifferentCoordinatorDataRoot() throws Exception {
        ReSyncPersistenceCoordinator.PreparedBootstrap prepared = ReSyncPersistenceCoordinator.bootstrapPrepared(
            temporary.resolve("other-data"), temporary.resolve("other-coordination"));
        ReSyncPersistenceCoordinator persistence = prepared.coordinator();
        try {
            assertThrows(IllegalArgumentException.class, () -> admit(persistence, prepared.activeRoot()));
            assertFalse(Files.exists(data));
        } finally {
            persistence.close();
        }
    }

    @Test
    void rejectsAMissingActiveRoot() throws Exception {
        ReSyncPersistenceCoordinator.PreparedBootstrap prepared = bootstrap();
        ReSyncPersistenceCoordinator persistence = prepared.coordinator();
        try {
            assertThrows(IllegalStateException.class, () -> admit(persistence, temporary.resolve("missing-active")));
            assertEquals(prepared.activeRoot(), persistence.activeDataRoot());
        } finally {
            persistence.close();
        }
    }

    private ReSyncPersistenceCoordinator.PreparedBootstrap bootstrap() throws Exception {
        ReSyncPersistenceCoordinator.PreparedBootstrap prepared = ReSyncPersistenceCoordinator.bootstrapPrepared(data, coordination);
        FreshRootProvenance provenance = prepared.coordinator().freshRootProvenance().orElseThrow();
        AssetCoordinatorMigration.Result migration = AssetCoordinatorMigration.prepareEmptyUnconsumed(coordination, provenance);
        prepared.coordinator().prepareDataFixes(new ReSyncDataFixer(1, List.of()), true);
        provenance.consume(migration.artifactHash());
        return prepared;
    }

    private void admit(ReSyncPersistenceCoordinator persistence, Path activeRoot) throws Exception {
        Method method = ReSyncServer.class.getDeclaredMethod("preparePersistence", ReSync.class,
            ReSyncPersistenceCoordinator.class, Path.class);
        method.setAccessible(true);
        try {
            method.invoke(null, plugin, persistence, activeRoot);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) {
                throw exception;
            }
            throw new AssertionError(failure.getCause());
        }
    }

    public static class TestReSync extends ReSync {
        @Override
        public void onEnable() {
        }

        @Override
        public void onDisable() {
        }
    }
}
