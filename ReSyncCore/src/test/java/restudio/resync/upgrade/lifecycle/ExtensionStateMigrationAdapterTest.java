package restudio.resync.upgrade.lifecycle;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.migration.MigrationFence;
import restudio.resync.migration.PersistenceParticipant;
import restudio.resync.migration.PersistenceParticipantRegistry;
import restudio.resync.migration.Snapshot;
import restudio.resync.migration.SnapshotMetadata;
import restudio.resync.migration.SnapshotService;
import restudio.resync.migration.StagedMigration;
import restudio.resync.upgrade.ReSyncTypedLifecycleUpgrade;
import restudio.resync.upgrade.ReplacementUpgrader;
import restudio.resync.upgrade.TypedLifecycleMigrationAdapter;
import restudio.resync.upgrade.UpgradeProposal;
import restudio.resync.upgrade.UpgradeSourceWindow;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionStateMigrationAdapterTest {
    @TempDir
    Path temporary;

    @Test
    void bindsRegistryStateArtifactsMetadataAndExternalDependenciesWithoutLosingOpaqueData() throws IOException {
        byte[] jar = new byte[]{7, 3, 9, 1};
        byte[] future = new byte[]{0, 4, 0, 8, -1};
        write("extensions/jars/example.jar", jar);
        write("extensions/example/future.bin", future);
        writeJson("extensions/extensions.json", Map.of(
            "extensions", List.of(Map.of(
                "id", "example",
                "version", "1.2.3",
                "contractRange", "current-head-only",
                "enabled", true,
                "artifact", "extensions/jars/example.jar",
                "dependencies", List.of(Map.of("id", "platform-api", "versionRange", "^2.0.0", "external", true)),
                "opaqueManifest", Map.of("future", List.of("keep", 5))
            )),
            "jars", List.of(Map.of(
                "id", "example",
                "version", "1.2.3",
                "path", "extensions/jars/example.jar",
                "sha256", sha256(jar),
                "opaqueArtifact", "keep"
            )),
            "opaqueRegistry", Map.of("retain", true),
            "snapshot", Map.of("futureSnapshot", "keep")
        ));
        writeJson("extensions/example/state.json", Map.of(
            "revision", 4,
            "mutationId", "mutation-example-004",
            "state", "available",
            "opaqueState", Map.of("nested", List.of(1, 2, 3))
        ));
        SnapshotMetadata metadata = metadata(Map.of("example", "1.2.3", "platform-api", "2.4.0"));

        TypedLifecycleMigrationAdapter.Adaptation adaptation = new ExtensionStateMigrationAdapter().adapt(input(metadata));

        assertTrue(adaptation.quarantineRecords().isEmpty());
        assertEquals(List.of(
            "extensions/example/future.bin",
            "extensions/example/state.json",
            "extensions/extensions.json",
            "extensions/jars/example.jar"
        ), adaptation.claims().stream().map(TypedLifecycleMigrationAdapter.Claim::relativePath).toList());
        assertEquals(List.of("extensions/example/state.json", "extensions/extensions.json"),
            adaptation.changes().stream().map(TypedLifecycleMigrationAdapter.Change::targetPath).toList());

        Map<String, Object> registry = object(change(adaptation, "extensions/extensions.json"));
        Map<String, Object> extension = object(array(registry.get("extensions")).getFirst());
        Map<String, Object> artifact = object(array(registry.get("jars")).getFirst());
        Map<String, Object> state = object(change(adaptation, "extensions/example/state.json"));
        assertEquals(Map.of("retain", true), registry.get("opaqueRegistry"));
        assertEquals("keep", object(registry.get("snapshot")).get("futureSnapshot"));
        assertEquals(Map.of("future", List.of("keep", decimal(5))), extension.get("opaqueManifest"));
        assertEquals("keep", artifact.get("opaqueArtifact"));
        assertEquals(Map.of("nested", List.of(decimal(1), decimal(2), decimal(3))), state.get("opaqueState"));
        assertEquals("example", state.get("extensionId"));
        assertEquals("1.2.3", state.get("extensionVersion"));
        Map<String, Object> snapshot = object(state.get("snapshot"));
        assertEquals(metadata.snapshotId(), snapshot.get("snapshotId"));
        assertEquals(metadata.createdAt().toString(), snapshot.get("createdAt"));
        assertEquals("a".repeat(64), snapshot.get("manifestHash"));
        assertEquals(metadata.catalogChecksum(), snapshot.get("catalogChecksum"));
        assertArrayEquals(future, Files.readAllBytes(temporary.resolve("extensions/example/future.bin")));
        assertFalse(adaptation.changes().stream().anyMatch(change -> change.sourcePath().endsWith("future.bin")));
    }

    @Test
    void producesNoChangesWhenCanonicalOutputIsMigratedAgain() throws IOException {
        writeJson("extensions/extensions.json", Map.of(
            "extensions", List.of(Map.of(
                "id", "external-example",
                "version", "3.1.0",
                "contractRange", "current-head-only",
                "enabled", true,
                "source", "external"
            )),
            "jars", List.of()
        ));
        writeJson("extensions/external-example/state.json", Map.of(
            "revision", 1,
            "mutationId", "mutation-external-001",
            "state", "available"
        ));
        SnapshotMetadata metadata = metadata(Map.of("external-example", "3.1.0"));
        ExtensionStateMigrationAdapter adapter = new ExtensionStateMigrationAdapter();

        TypedLifecycleMigrationAdapter.Adaptation first = adapter.adapt(input(metadata));
        apply(first);
        SnapshotMetadata repeatedMetadata = new SnapshotMetadata(1, "extension-snapshot-repeated", Instant.parse("2026-08-24T00:00:00Z"),
            "current-head", "b".repeat(64), Map.of("external-example", "3.1.0"));
        TypedLifecycleMigrationAdapter.Adaptation second = adapter.adapt(input(repeatedMetadata, ExtensionStateMigrationAdapter.OWNER, "e".repeat(64)));

        assertTrue(first.quarantineRecords().isEmpty());
        assertFalse(first.changes().isEmpty());
        assertTrue(second.quarantineRecords().isEmpty());
        assertTrue(second.changes().isEmpty());
        assertEquals(first.claims(), second.claims());
    }

    @Test
    void stagedOutputResnapshotsWithNewEphemeralMetadataAndProducesAZeroChangePlan() throws IOException {
        Path live = Files.createDirectories(temporary.resolve("live"));
        writeJson(live, "extensions/extensions.json", Map.of(
            "extensions", List.of(Map.of(
                "id", "external-example",
                "version", "3.1.0",
                "contractRange", "current-head-only",
                "enabled", true,
                "source", "external"
            )),
            "jars", List.of()
        ));
        writeJson(live, "extensions/external-example/state.json", Map.of(
            "revision", 1,
            "mutationId", "mutation-external-001",
            "state", "available"
        ));
        SnapshotMetadata firstMetadata = new SnapshotMetadata(1, "extension-source-1", Instant.parse("2026-08-23T00:00:00Z"),
            "current-head", "b".repeat(64), Map.of("external-example", "3.1.0"));
        SnapshotService snapshots = new SnapshotService(new MigrationFence());
        Snapshot firstSnapshot = snapshots.create(live, temporary.resolve("snapshot-1"), firstMetadata, participants(live));
        ReSyncTypedLifecycleUpgrade upgrade = new ReSyncTypedLifecycleUpgrade(List.of(new ExtensionStateMigrationAdapter()));
        UpgradeSourceWindow window = new UpgradeSourceWindow(ReplacementUpgrader.VERSION, 1, "current-head", 2, "replacement-1");

        UpgradeProposal firstPlan = upgrade.plan(firstSnapshot, window);
        StagedMigration staged = upgrade.stage(firstSnapshot.root(), temporary.resolve("migrated"), firstPlan.plan());
        SnapshotMetadata secondMetadata = new SnapshotMetadata(2, "extension-source-2", Instant.parse("2026-08-24T00:00:00Z"),
            "replacement-build", "c".repeat(64), Map.of("external-example", "3.1.0"));
        Snapshot secondSnapshot = snapshots.create(staged.root(), temporary.resolve("snapshot-2"), secondMetadata, participants(staged.root()));
        UpgradeProposal secondPlan = upgrade.plan(secondSnapshot,
            new UpgradeSourceWindow(ReplacementUpgrader.VERSION, 2, "replacement-build", 3, "next-replacement"));

        assertFalse(firstPlan.plan().operations().isEmpty());
        assertNotEquals(firstSnapshot.manifest().manifestHash(), secondSnapshot.manifest().manifestHash());
        assertNotEquals(firstSnapshot.metadata().snapshotId(), secondSnapshot.metadata().snapshotId());
        assertTrue(secondPlan.plan().operations().isEmpty());
        assertTrue(secondPlan.quarantineReport().records().isEmpty());
    }

    @Test
    void quarantinesAValidStateBindingThatDiffersFromTheAuthoritativeRegistryBinding() throws IOException {
        writeJson("extensions/extensions.json", Map.of(
            "extensions", List.of(Map.of(
                "id", "external-example",
                "version", "1.0.0",
                "contractRange", "any",
                "enabled", true,
                "source", "external"
            )),
            "jars", List.of()
        ));
        writeJson("extensions/external-example/state.json", state("mutation-external"));
        SnapshotMetadata metadata = metadata(Map.of("external-example", "1.0.0"));
        ExtensionStateMigrationAdapter adapter = new ExtensionStateMigrationAdapter();
        apply(adapter.adapt(input(metadata)));
        Map<String, Object> state = object(Files.readAllBytes(temporary.resolve("extensions/external-example/state.json")));
        LinkedHashMap<String, Object> differentBinding = new LinkedHashMap<>(object(state.get("snapshot")));
        differentBinding.put("snapshotId", "different-source");
        differentBinding.put("manifestHash", "f".repeat(64));
        differentBinding.remove("bindingHash");
        differentBinding.put("bindingHash", CanonicalJson.sha256("migration.extension-snapshot-binding", differentBinding));
        state.put("snapshot", differentBinding);
        writeJson("extensions/external-example/state.json", state);

        TypedLifecycleMigrationAdapter.Adaptation adaptation = adapter.adapt(input(metadata));

        assertTrue(adaptation.changes().isEmpty());
        assertEquals(1, adaptation.quarantineRecords().size());
        assertEquals(ExtensionStateMigrationAdapter.CODE_STATE_INVALID, adaptation.quarantineRecords().getFirst().code());
        assertEquals("extensions/external-example/state.json", adaptation.quarantineRecords().getFirst().sourceLocation());
        assertTrue(adaptation.quarantineRecords().getFirst().reason().contains("authoritative registry binding"));
        assertEquals(sha256(Files.readAllBytes(temporary.resolve("extensions/external-example/state.json"))),
            adaptation.quarantineRecords().getFirst().sourceHash());
    }

    @Test
    void quarantinesMissingAndIncompatibleArtifactsWithoutPartiallyRewritingState() throws IOException {
        writeJson("extensions/extensions.json", Map.of(
            "extensions", List.of(
                Map.of("id", "missing", "version", "1.0.0", "contractRange", "any", "enabled", true),
                Map.of("id", "wrong", "version", "2.0.0", "contractRange", "any", "enabled", true, "artifact", "extensions/jars/wrong.jar")
            ),
            "jars", List.of(Map.of(
                "id", "wrong",
                "version", "1.0.0",
                "path", "extensions/jars/wrong.jar",
                "sha256", "0".repeat(64)
            ))
        ));
        writeJson("extensions/missing/state.json", state("mutation-missing"));
        writeJson("extensions/wrong/state.json", state("mutation-wrong"));
        write("extensions/jars/wrong.jar", new byte[]{1, 2, 3});

        TypedLifecycleMigrationAdapter.Adaptation adaptation = new ExtensionStateMigrationAdapter().adapt(
            input(metadata(Map.of("missing", "1.0.0", "wrong", "2.0.0"))));

        assertTrue(adaptation.changes().isEmpty());
        assertTrue(adaptation.quarantineRecords().stream().anyMatch(record -> record.code().equals(ExtensionStateMigrationAdapter.CODE_ARTIFACT_MISSING)));
        assertTrue(adaptation.quarantineRecords().stream().anyMatch(record -> record.code().equals(ExtensionStateMigrationAdapter.CODE_ARTIFACT_INCOMPATIBLE)));
    }

    @Test
    void quarantinesVersionStateAndExternalDependencyDriftDeterministically() throws IOException {
        writeJson("extensions/extensions.json", Map.of(
            "extensions", List.of(Map.of(
                "id", "example",
                "version", "1.0.0",
                "contractRange", "any",
                "enabled", true,
                "source", "external",
                "dependencies", List.of(Map.of("id", "platform-api", "versionRange", "^3.0.0", "external", true))
            )),
            "jars", List.of()
        ));
        writeJson("extensions/example/state.json", Map.of(
            "revision", 1,
            "mutationId", "mutation-example",
            "state", "available",
            "extensionId", "another-extension",
            "extensionVersion", "0.9.0"
        ));
        SnapshotMetadata metadata = metadata(Map.of("example", "1.1.0", "platform-api", "2.0.0"));
        ExtensionStateMigrationAdapter adapter = new ExtensionStateMigrationAdapter();

        TypedLifecycleMigrationAdapter.Adaptation first = adapter.adapt(input(metadata));
        TypedLifecycleMigrationAdapter.Adaptation second = adapter.adapt(input(metadata));

        assertTrue(first.changes().isEmpty());
        assertTrue(first.quarantineRecords().stream().anyMatch(record -> record.code().equals(ExtensionStateMigrationAdapter.CODE_VERSION_MISMATCH)));
        assertTrue(first.quarantineRecords().stream().anyMatch(record -> record.code().equals(ExtensionStateMigrationAdapter.CODE_STATE_INVALID)));
        assertTrue(first.quarantineRecords().stream().anyMatch(record -> record.code().equals(ExtensionStateMigrationAdapter.CODE_DEPENDENCY_INCOMPATIBLE)));
        assertTrue(first.quarantineRecords().stream().filter(record -> record.code().equals(ExtensionStateMigrationAdapter.CODE_VERSION_MISMATCH))
            .flatMap(record -> record.affectedReferences().stream()).anyMatch(reference -> reference.equals("expectedVersion=1.1.0")));
        assertTrue(first.quarantineRecords().stream().flatMap(record -> record.affectedReferences().stream())
            .anyMatch(reference -> reference.equals("manifestHash=" + "a".repeat(64))));
        assertEquals(first.quarantineRecords(), second.quarantineRecords());
    }

    @Test
    void requiresTheAuthoritativeExtensionsOwner() throws IOException {
        writeJson("extensions/extensions.json", Map.of("extensions", List.of(), "jars", List.of()));

        TypedLifecycleMigrationAdapter.Adaptation adaptation = new ExtensionStateMigrationAdapter().adapt(input(metadata(Map.of()), "other-owner"));

        assertEquals(List.of(ExtensionStateMigrationAdapter.OWNER), adaptation.claims().stream().map(TypedLifecycleMigrationAdapter.Claim::owner).distinct().toList());
        assertTrue(adaptation.changes().isEmpty());
        assertTrue(adaptation.quarantineRecords().isEmpty());
    }

    @Test
    void rejectsHashlessVersionlessAndStringArtifactRows() throws IOException {
        writeJson("extensions/extensions.json", Map.of(
            "extensions", List.of(Map.of(
                "id", "external-example",
                "version", "1.0.0",
                "contractRange", "any",
                "enabled", true,
                "source", "external"
            )),
            "jars", List.of(
                "extensions/jars/string.jar",
                Map.of("id", "hashless", "version", "1.0.0", "path", "extensions/jars/hashless.jar"),
                Map.of("id", "versionless", "path", "extensions/jars/versionless.jar", "sha256", "c".repeat(64))
            )
        ));
        writeJson("extensions/external-example/state.json", state("mutation-external"));
        write("extensions/jars/hashless.jar", new byte[]{1, 3, 5});
        write("extensions/external-example/future.bin", new byte[]{2, 4, 6});

        TypedLifecycleMigrationAdapter.Adaptation adaptation = new ExtensionStateMigrationAdapter().adapt(
            input(metadata(Map.of("external-example", "1.0.0"))));

        assertTrue(adaptation.changes().isEmpty());
        assertEquals(4, adaptation.quarantineRecords().stream()
            .filter(record -> record.code().equals(ExtensionStateMigrationAdapter.CODE_REGISTRY_INVALID)).count());
        assertEquals(List.of(
            "extensions/extensions.json",
            "extensions/external-example/future.bin",
            "extensions/external-example/state.json",
            "extensions/jars/hashless.jar"
        ), adaptation.quarantineRecords().stream()
            .filter(record -> record.code().equals(ExtensionStateMigrationAdapter.CODE_REGISTRY_INVALID))
            .map(record -> record.sourceLocation()).sorted().toList());
    }

    @Test
    void quarantinesConflictingSnapshotBindingsInsteadOfOverwritingThem() throws IOException {
        writeJson("extensions/extensions.json", Map.of(
            "extensions", List.of(Map.of(
                "id", "external-example",
                "version", "1.0.0",
                "contractRange", "any",
                "enabled", true,
                "source", "external"
            )),
            "jars", List.of(),
            "snapshot", Map.of("build", "wrong-build")
        ));
        writeJson("extensions/external-example/state.json", state("mutation-external"));

        TypedLifecycleMigrationAdapter.Adaptation adaptation = new ExtensionStateMigrationAdapter().adapt(
            input(metadata(Map.of("external-example", "1.0.0"))));

        assertTrue(adaptation.changes().isEmpty());
        assertEquals(ExtensionStateMigrationAdapter.CODE_REGISTRY_INVALID, adaptation.quarantineRecords().getFirst().code());
        assertTrue(adaptation.quarantineRecords().getFirst().reason().contains("expected"));
        assertTrue(adaptation.quarantineRecords().getFirst().reason().contains("observed"));
    }

    @Test
    void quarantinesConflictingStateSnapshotBindingsAtTheExactStateSource() throws IOException {
        writeJson("extensions/extensions.json", Map.of(
            "extensions", List.of(Map.of(
                "id", "external-example",
                "version", "1.0.0",
                "contractRange", "any",
                "enabled", true,
                "source", "external"
            )),
            "jars", List.of()
        ));
        writeJson("extensions/external-example/state.json", Map.of(
            "revision", 1,
            "mutationId", "mutation-external",
            "state", "available",
            "snapshot", Map.of("manifestHash", "d".repeat(64))
        ));

        TypedLifecycleMigrationAdapter.Adaptation adaptation = new ExtensionStateMigrationAdapter().adapt(
            input(metadata(Map.of("external-example", "1.0.0"))));

        assertTrue(adaptation.changes().isEmpty());
        assertEquals(ExtensionStateMigrationAdapter.CODE_STATE_INVALID, adaptation.quarantineRecords().getFirst().code());
        assertEquals("extensions/external-example/state.json", adaptation.quarantineRecords().getFirst().sourceLocation());
        assertEquals(sha256(Files.readAllBytes(temporary.resolve("extensions/external-example/state.json"))),
            adaptation.quarantineRecords().getFirst().sourceHash());
    }

    @Test
    void quarantinesOrphanArtifactsAndFilesForUnregisteredExtensionDirectories() throws IOException {
        byte[] orphan = new byte[]{4, 5, 6};
        byte[] rogueJar = new byte[]{7, 8, 9};
        byte[] misplacedJar = new byte[]{10, 11, 12};
        writeJson("extensions/extensions.json", Map.of(
            "extensions", List.of(Map.of(
                "id", "external-example",
                "version", "1.0.0",
                "contractRange", "any",
                "enabled", true,
                "source", "external"
            )),
            "jars", List.of()
        ));
        writeJson("extensions/external-example/state.json", state("mutation-external"));
        write("extensions/orphan/private.bin", orphan);
        write("extensions/jars/rogue.jar", rogueJar);
        write("extensions/external-example/rogue.jar", misplacedJar);

        TypedLifecycleMigrationAdapter.Adaptation adaptation = new ExtensionStateMigrationAdapter().adapt(
            input(metadata(Map.of("external-example", "1.0.0"))));

        assertTrue(adaptation.changes().isEmpty());
        assertEquals(3, adaptation.quarantineRecords().stream().filter(record -> record.code().equals(ExtensionStateMigrationAdapter.CODE_ORPHAN_FILE)).count());
        for (var record : adaptation.quarantineRecords()) {
            Path source = temporary.resolve(record.sourceLocation());
            assertTrue(Files.isRegularFile(source));
            assertEquals(sha256(Files.readAllBytes(source)), record.sourceHash());
            assertFalse(record.recordId().contains("/"));
        }
    }

    @Test
    void quarantinesMissingStateWithoutCreatingAReplacement() throws IOException {
        writeJson("extensions/extensions.json", Map.of(
            "extensions", List.of(Map.of(
                "id", "external-example",
                "version", "1.0.0",
                "contractRange", "any",
                "enabled", true,
                "source", "external"
            )),
            "jars", List.of()
        ));

        TypedLifecycleMigrationAdapter.Adaptation adaptation = new ExtensionStateMigrationAdapter().adapt(
            input(metadata(Map.of("external-example", "1.0.0"))));

        assertTrue(adaptation.changes().isEmpty());
        assertEquals(ExtensionStateMigrationAdapter.CODE_STATE_MISSING, adaptation.quarantineRecords().getFirst().code());
    }

    @Test
    void quarantinesMetadataExtensionsWithoutARegistryOrExternalDependencyDeclaration() throws IOException {
        writeJson("extensions/extensions.json", Map.of("extensions", List.of(), "jars", List.of()));

        TypedLifecycleMigrationAdapter.Adaptation adaptation = new ExtensionStateMigrationAdapter().adapt(
            input(metadata(Map.of("undeclared-extension", "1.0.0"))));

        assertTrue(adaptation.changes().isEmpty());
        assertEquals(ExtensionStateMigrationAdapter.CODE_VERSION_MISMATCH, adaptation.quarantineRecords().getFirst().code());
        assertTrue(adaptation.quarantineRecords().getFirst().affectedReferences().contains("observedExtension=undeclared-extension"));
        assertTrue(adaptation.quarantineRecords().getFirst().affectedReferences().contains("observedVersion=1.0.0"));
    }

    @Test
    void claimsAndQuarantinesOrphanedFutureFilesWhenRegistryIsMissing() throws IOException {
        byte[] future = new byte[]{11, 12, 13};
        write("extensions/future/private.dat", future);

        TypedLifecycleMigrationAdapter.Adaptation adaptation = new ExtensionStateMigrationAdapter().adapt(input(metadata(Map.of())));

        assertEquals(List.of("extensions/future/private.dat"), adaptation.claims().stream().map(TypedLifecycleMigrationAdapter.Claim::relativePath).toList());
        assertTrue(adaptation.changes().isEmpty());
        assertEquals(ExtensionStateMigrationAdapter.CODE_REGISTRY_MISSING, adaptation.quarantineRecords().getFirst().code());
        assertArrayEquals(future, Files.readAllBytes(temporary.resolve("extensions/future/private.dat")));
    }

    private TypedLifecycleMigrationAdapter.Input input(SnapshotMetadata metadata) throws IOException {
        return input(metadata, ExtensionStateMigrationAdapter.OWNER);
    }

    private TypedLifecycleMigrationAdapter.Input input(SnapshotMetadata metadata, String owner) throws IOException {
        return input(metadata, owner, "a".repeat(64));
    }

    private TypedLifecycleMigrationAdapter.Input input(SnapshotMetadata metadata, String owner, String manifestHash) throws IOException {
        ArrayList<TypedLifecycleMigrationAdapter.SourceFile> files = new ArrayList<>();
        try (var paths = Files.walk(temporary)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                byte[] bytes = Files.readAllBytes(path);
                files.add(new TypedLifecycleMigrationAdapter.SourceFile(
                    temporary.relativize(path).toString().replace('\\', '/'),
                    bytes.length,
                    sha256(bytes),
                    owner
                ));
            }
        }
        return new TypedLifecycleMigrationAdapter.Input(temporary, metadata, manifestHash, files);
    }

    private SnapshotMetadata metadata(Map<String, String> extensions) {
        return new SnapshotMetadata(1, "extension-snapshot", Instant.parse("2026-08-23T00:00:00Z"), "current-head", "b".repeat(64), extensions);
    }

    private PersistenceParticipantRegistry participants(Path root) {
        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry();
        participants.register(new PersistenceParticipant() {
            @Override
            public String owner() {
                return ExtensionStateMigrationAdapter.OWNER;
            }

            @Override
            public Path root() {
                return root;
            }
        });
        return participants;
    }

    private void apply(TypedLifecycleMigrationAdapter.Adaptation adaptation) throws IOException {
        for (TypedLifecycleMigrationAdapter.Change change : adaptation.changes()) {
            Path target = temporary.resolve(change.targetPath());
            Files.createDirectories(target.getParent());
            Files.write(target, change.targetBytes());
        }
    }

    private byte[] change(TypedLifecycleMigrationAdapter.Adaptation adaptation, String path) {
        return adaptation.changes().stream().filter(change -> change.targetPath().equals(path)).findFirst().orElseThrow().targetBytes();
    }

    private void writeJson(String path, Object value) throws IOException {
        write(path, CanonicalJson.canonicalBytes(value));
    }

    private void writeJson(Path root, String path, Object value) throws IOException {
        Path target = root.resolve(path);
        Files.createDirectories(target.getParent());
        Files.write(target, CanonicalJson.canonicalBytes(value));
    }

    private void write(String path, byte[] value) throws IOException {
        Path target = temporary.resolve(path);
        Files.createDirectories(target.getParent());
        Files.write(target, value);
    }

    private Map<String, Object> state(String mutationId) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("revision", 1);
        value.put("mutationId", mutationId);
        value.put("state", "available");
        return value;
    }

    private static Map<String, Object> object(byte[] value) {
        return object(CanonicalJson.parseOpaque(value));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value) {
        return (List<Object>) value;
    }

    private static BigDecimal decimal(int value) {
        return BigDecimal.valueOf(value);
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
