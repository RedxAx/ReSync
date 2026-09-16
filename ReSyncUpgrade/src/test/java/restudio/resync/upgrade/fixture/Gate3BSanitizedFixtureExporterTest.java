package restudio.resync.upgrade.fixture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.migration.ProductionSnapshotMetadataManifest;
import restudio.resync.migration.SnapshotManifest;
import restudio.resync.migration.SnapshotVerification;
import restudio.resync.upgrade.adapter.OfflineUpgradeAdapterRegistry;
import restudio.resync.upgrade.LegacySnapshotWindow;
import restudio.resync.upgrade.cli.OfflineUpgradeCli;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Gate3BSanitizedFixtureExporterTest {
    private static final String FIXTURE = "src/test/resources/fixtures/node-replacement/full-folder";

    @TempDir
    Path temporary;

    @Test
    void exportsVerifiedSnapshotWithExactFixtureOwners() throws Exception {
        Path fixture = fixtureRoot();
        Map<String, byte[]> before = bytes(fixture);
        Gate3BSanitizedFixtureExporter.ExportResult result = Gate3BSanitizedFixtureExporter.export(
            fixture, temporary.resolve("gate3b-export"));

        SnapshotVerification verification = result.snapshot().manifest().verify(result.snapshot().root());
        verification.requireVerified();
        assertEquals(4, result.skippedSanitizedConflicts().size());
        assertTrue(result.skippedSanitizedConflicts().contains("assets/project.json"));
        assertTrue(result.skippedSanitizedConflicts().contains("config.properties"));
        assertTrue(result.skippedSanitizedConflicts().contains("config.yml"));
        assertTrue(result.skippedSanitizedConflicts().contains("world-management/worlds.json"));
        assertEquals(result.snapshot().manifest().entries().size(), 25);
        assertEquals(result.snapshot().manifest().entries().stream().map(SnapshotManifest.Entry::owner).distinct().sorted().toList(),
            List.of("automation", "core-config", "diagnostics", "extensions", "json-resources", "local-catalog",
                "migration", "network", "quarantine", "resync.flow.assets", "resync.triggers", "world-management", "worldgen"));
        ProductionSnapshotMetadataManifest.Values metadata = ProductionSnapshotMetadataManifest.read(result.snapshot().root());
        assertEquals(result.snapshot().manifest().manifestHash(), metadata.manifestHash());
        assertByteTreeEquals(before, bytes(fixture));
    }

    @Test
    void secondExportIsByteStableAcrossOutputDirectories() throws Exception {
        Path fixture = fixtureRoot();
        Gate3BSanitizedFixtureExporter.ExportResult first = Gate3BSanitizedFixtureExporter.export(
            fixture, temporary.resolve("first"));
        Gate3BSanitizedFixtureExporter.ExportResult second = Gate3BSanitizedFixtureExporter.export(
            fixture, temporary.resolve("second"));

        assertByteTreeEquals(bytes(first.snapshot().root()), bytes(second.snapshot().root()));
        assertArrayEquals(Files.readAllBytes(first.snapshot().manifestPath()), Files.readAllBytes(second.snapshot().manifestPath()));
        assertArrayEquals(Files.readAllBytes(first.metadataPath()), Files.readAllBytes(second.metadataPath()));
        assertArrayEquals(Files.readAllBytes(first.snapshot().statePath()), Files.readAllBytes(second.snapshot().statePath()));
    }

    @Test
    void offlineUpgradeCliAcceptsExportedSnapshotMetadataAndManifest() throws Exception {
        Gate3BSanitizedFixtureExporter.ExportResult result = Gate3BSanitizedFixtureExporter.export(
            fixtureRoot(), temporary.resolve("cli-source"));
        StringWriter output = new StringWriter();
        StringWriter errors = new StringWriter();
        var metadata = result.snapshot().metadata();
        int exitCode = OfflineUpgradeCli.run(new String[] {
            "dry-run",
            "--source", result.snapshot().root().toString(),
            "--snapshot-id", metadata.snapshotId(),
            "--source-format", Integer.toString(metadata.formatVersion()),
            "--source-build", metadata.build(),
            "--target-format", Integer.toString(LegacySnapshotWindow.TARGET_FORMAT_VERSION),
            "--replacement-contract", LegacySnapshotWindow.REPLACEMENT_CONTRACT,
            "--catalog-checksum", metadata.catalogChecksum(),
            "--migration-id", "gate3b-cli-dry-run",
            "--extension", Gate3BSanitizedFixtureExporter.DEFAULT_EXTENSION_OWNER + "="
                + Gate3BSanitizedFixtureExporter.DEFAULT_EXTENSION_VERSION,
            "--output", "json"
        }, new PrintWriter(output), new PrintWriter(errors), OfflineUpgradeAdapterRegistry.discover());

        assertEquals(OfflineUpgradeCli.ExitCode.QUARANTINE_REQUIRED.value(), exitCode, output.toString());
        assertTrue(output.toString().contains("\"status\":\"AWAITING_QUARANTINE_ACCEPTANCE\""), output.toString());
        assertTrue(errors.toString().isBlank(), errors.toString());
    }

    private Path fixtureRoot() throws Exception {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path candidate = current.resolve(FIXTURE);
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        return Gate3BSanitizedFixtureExporter.locateFixtureRoot(current);
    }

    private Map<String, byte[]> bytes(Path root) throws Exception {
        Map<String, byte[]> values = new LinkedHashMap<>();
        try (var stream = Files.walk(root)) {
            for (Path file : stream.filter(Files::isRegularFile)
                .sorted(Comparator.comparing(path -> root.relativize(path).toString())).toList()) {
                values.put(root.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
            }
        }
        return values;
    }

    private void assertByteTreeEquals(Map<String, byte[]> expected, Map<String, byte[]> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        for (String path : expected.keySet()) {
            assertArrayEquals(expected.get(path), actual.get(path), path);
        }
    }
}
