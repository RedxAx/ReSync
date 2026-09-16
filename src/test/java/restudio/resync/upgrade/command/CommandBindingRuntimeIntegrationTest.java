package restudio.resync.upgrade.command;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowSerializer;
import restudio.resync.flow.FlowStorage;
import restudio.resync.migration.PersistenceParticipant;
import restudio.resync.migration.PersistenceParticipantRegistry;
import restudio.resync.migration.ProductionPersistenceOwners;
import restudio.resync.migration.ProductionSnapshotMetadataManifest;
import restudio.resync.migration.Snapshot;
import restudio.resync.migration.SnapshotManifest;
import restudio.resync.migration.SnapshotMetadata;
import restudio.resync.migration.SnapshotState;
import restudio.resync.migration.SnapshotVerification;
import restudio.resync.resources.AssetFileFormat;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.StorageSafety;
import restudio.resync.upgrade.ImmutableSnapshotAdapter;
import restudio.resync.upgrade.adapter.OfflineUpgradeSnapshotAdapter;
import restudio.resync.upgrade.adapter.OfflineUpgradeSnapshotInput;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CommandBindingRuntimeIntegrationTest {
    @TempDir
    Path temporary;

    @Test
    void emittedCommandGraphBytesPassProductionVerificationAndFlowStorageLoad() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("source"));
        Files.writeString(source.resolve("triggers.json"),
            "[{\"id\":\"runtime-fixture:command:start\",\"flowId\":\"runtime-fixture\",\"type\":\"COMMAND\",\"context\":\"runtime-command\"}]",
            StandardCharsets.UTF_8);
        Path commandFile = Files.createDirectories(source.resolve("assets/Blueprints/Commands"))
            .resolve("runtime-fixture.json");
        Files.writeString(commandFile,
            "{\"id\":\"runtime-fixture\",\"resourceType\":\"command\",\"resourceRevision\":4,"
                + "\"resourceHash\":\"\",\"resourceMutationId\":\"legacy-mutation\","
                + "\"nodes\":{\"start\":{\"type\":\"event.resync.command\",\"inputs\":{}}},"
                + "\"connections\":[],\"unknownRoot\":{\"keep\":true}}",
            StandardCharsets.UTF_8);

        SnapshotMetadata metadata = new SnapshotMetadata(1, "runtime-fixture-snapshot", Instant.EPOCH,
            LegacyCommandBindingSnapshotAdapter.SOURCE_BUILD, "a".repeat(64), Map.of());
        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry();
        participants.register(participant(ProductionPersistenceOwners.TRIGGERS, source.resolve("triggers.json")));
        participants.register(participant(ProductionPersistenceOwners.FLOW_ASSETS, source.resolve("assets")));
        SnapshotManifest manifest = SnapshotManifest.scan(source, metadata, participants);
        Path manifestPath = source.resolveSibling("source.manifest");
        manifest.write(manifestPath);
        ProductionSnapshotMetadataManifest.write(source, manifest);
        SnapshotVerification verification = manifest.verify(source);
        verification.requireVerified();
        Snapshot snapshot = new Snapshot(source, manifestPath, source.resolveSibling("source.state"), metadata,
            manifest, SnapshotState.VERIFIED, verification);

        OfflineUpgradeSnapshotAdapter.SnapshotTransform transformed = new LegacyCommandBindingSnapshotAdapter()
            .transform(new OfflineUpgradeSnapshotInput(source, ImmutableSnapshotAdapter.adapt(snapshot)));
        assertTrue(transformed.quarantines().isEmpty());
        byte[] emitted = transformed.files().stream()
            .filter(file -> file.targetPath().equals("assets/Blueprints/Commands/runtime-fixture.json"))
            .findFirst()
            .orElseThrow()
            .bytes();

        Path dataFolder = Files.createDirectories(temporary.resolve("runtime"));
        Path runtimeFile = Files.createDirectories(dataFolder.resolve("assets/Blueprints/Commands"))
            .resolve("runtime-fixture.json");
        Files.write(runtimeFile, emitted);

        assertTrue(AssetFileFormat.verify(runtimeFile));
        FlowGraph deserialized = FlowSerializer.deserialize(Files.readString(runtimeFile));
        assertEquals("runtime-fixture", deserialized.getId());
        assertEquals("command", deserialized.getResourceType());
        assertEquals("runtime-command", deserialized.getNodes().get("start").getInputValues().get("command"));

        try (AssetTransactionCoordinator coordinator = adoptedCoordinator(dataFolder.resolve("assets"))) {
            FlowStorage storage = new FlowStorage(dataFolder.toFile(), coordinator);
            FlowGraph loaded = storage.getGraph("command", "runtime-fixture");
            assertNotNull(loaded);
            assertEquals("runtime-fixture", loaded.getId());
            assertEquals("command", loaded.getResourceType());
            assertEquals("runtime-command", loaded.getNodes().get("start").getInputValues().get("command"));
        }
    }

    private AssetTransactionCoordinator adoptedCoordinator(Path assets) throws Exception {
        Path file = assets.resolve("Blueprints/Commands/runtime-fixture.json");
        byte[] content = Files.readAllBytes(file);
        return AssetTransactionCoordinator.adoptExisting(assets, new Gson(),
            new AssetTransactionCoordinator.AdoptionInventory("command-binding-runtime-integration-test", "{}",
                List.of(new AssetTransactionCoordinator.AdoptedAsset(
                    new AssetTransactionCoordinator.AssetKey("command", "runtime-fixture"),
                    Path.of("Blueprints/Commands/runtime-fixture.json"),
                    new AssetTransactionCoordinator.Live(AssetFileFormat.readRevision(file),
                        StorageSafety.sha256(content)),
                    new AssetTransactionCoordinator.AssetMutationId(AssetFileFormat.readMutationId(file)), content,
                    null))));
    }

    private PersistenceParticipant participant(String owner, Path root) {
        return new PersistenceParticipant() {
            @Override
            public String owner() {
                return owner;
            }

            @Override
            public Path root() {
                return root;
            }
        };
    }
}
