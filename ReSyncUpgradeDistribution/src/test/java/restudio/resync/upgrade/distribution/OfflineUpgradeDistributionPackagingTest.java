package restudio.resync.upgrade.distribution;

import org.junit.jupiter.api.Test;
import restudio.resync.upgrade.adapter.OfflineUpgradeAdapterRegistry;
import restudio.resync.upgrade.flow.ManagedFlowFileMigrationProvider;
import restudio.resync.upgrade.flow.ManagedFlowFileMigrationProviders;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OfflineUpgradeDistributionPackagingTest {
    @Test
    void productionRuntimeDiscoversTheManagedFlowFileProvider() {
        List<ManagedFlowFileMigrationProvider> providers = ManagedFlowFileMigrationProviders.discover();
        assertEquals(List.of("resync.managed-flow-files.sqlite"),
            providers.stream().map(ManagedFlowFileMigrationProvider::id).toList());
        assertNotNull(OfflineUpgradeDistribution.class.getClassLoader().getResource(
            "META-INF/services/restudio.resync.upgrade.flow.ManagedFlowFileMigrationProvider"));
        assertEquals(1, OfflineUpgradeAdapterRegistry.discover().snapshotAdapters().size());
        assertNotNull(OfflineUpgradeDistribution.class.getClassLoader().getResource(
            "restudio/resync/flow/migration/flow-graph-schema-v2.json"));
    }

    @Test
    void productionDistributionContainsTheCompiledRuntimeAndNoMigratedJson() throws IOException {
        String archivePath = System.getProperty("resync.offline.upgrader.distribution.zip");
        assertNotNull(archivePath);
        Map<String, List<byte[]>> entries = new LinkedHashMap<>();
        List<String> launchers = new ArrayList<>();
        try (ZipFile distribution = new ZipFile(Path.of(archivePath).toFile())) {
            distribution.stream()
                .filter(entry -> !entry.isDirectory())
                .forEach(entry -> collectEntry(distribution, entry, entries, launchers));
        }

        assertTrue(entries.containsKey("restudio/resync/flow/migration/flow-graph-schema-v2.json"));
        assertTrue(entries.containsKey("restudio/resync/upgrade/adapter/OfflineUpgradeAdapterProvider.class"));
        assertTrue(entries.containsKey("restudio/resync/upgrade/sqlite/SqliteManagedFlowFileMigrationProvider.class"));
        assertEquals(List.of("restudio.resync.upgrade.flow.LegacyFlowGraphUpgradeProvider"),
            serviceEntries(entries, "META-INF/services/restudio.resync.upgrade.adapter.OfflineUpgradeAdapterProvider"));
        assertEquals(List.of("restudio.resync.upgrade.sqlite.SqliteManagedFlowFileMigrationProvider"),
            serviceEntries(entries, "META-INF/services/restudio.resync.upgrade.flow.ManagedFlowFileMigrationProvider"));
        assertTrue(launchers.stream().anyMatch(value -> value.contains(
            "restudio.resync.upgrade.distribution.OfflineUpgradeDistribution")));
        assertFalse(entries.keySet().stream().anyMatch(value -> value.matches("nodes/migrated/.+\\.json")));
    }

    private static void collectEntry(ZipFile distribution, ZipEntry entry,
                                     Map<String, List<byte[]>> entries, List<String> launchers) {
        try (InputStream input = distribution.getInputStream(entry)) {
            byte[] bytes = input.readAllBytes();
            if ((entry.getName().contains("/bin/") || entry.getName().startsWith("bin/"))
                && (entry.getName().endsWith(".bat") || !entry.getName().contains("."))) {
                launchers.add(new String(bytes, StandardCharsets.UTF_8));
            }
            if (!entry.getName().endsWith(".jar")) {
                return;
            }
            try (JarInputStream jar = new JarInputStream(new ByteArrayInputStream(bytes))) {
                JarEntry nested;
                while ((nested = jar.getNextJarEntry()) != null) {
                    if (!nested.isDirectory()) {
                        entries.computeIfAbsent(nested.getName(), ignored -> new ArrayList<>())
                            .add(jar.readAllBytes());
                    }
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable To Inspect Distribution Entry: " + entry.getName(), exception);
        }
    }

    private static List<String> serviceEntries(Map<String, List<byte[]>> entries, String path) {
        return entries.getOrDefault(path, List.of()).stream()
            .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
            .flatMap(value -> value.lines().map(String::strip).filter(line -> !line.isBlank()))
            .toList();
    }
}
