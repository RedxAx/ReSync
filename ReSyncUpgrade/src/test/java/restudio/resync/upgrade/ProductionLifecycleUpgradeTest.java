package restudio.resync.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import restudio.resync.migration.MigrationFence;
import restudio.resync.migration.PersistenceParticipant;
import restudio.resync.migration.PersistenceParticipantRegistry;
import restudio.resync.migration.ProductionPersistenceOwners;
import restudio.resync.migration.Snapshot;
import restudio.resync.migration.SnapshotManifest;
import restudio.resync.migration.SnapshotMetadata;
import restudio.resync.migration.SnapshotService;
import restudio.resync.upgrade.lifecycle.AutomationMigrationAdapter;
import restudio.resync.upgrade.lifecycle.CustomContentMigrationAdapter;
import restudio.resync.upgrade.lifecycle.ExtensionStateMigrationAdapter;
import restudio.resync.upgrade.lifecycle.JsonAssetMigrationAdapter;
import restudio.resync.upgrade.lifecycle.ReplacementActivationRecordMigrationAdapter;
import restudio.resync.upgrade.lifecycle.TombstoneMigrationAdapter;
import restudio.resync.upgrade.lifecycle.TimerDefinitionMigrationAdapter;
import restudio.resync.upgrade.lifecycle.TriggerCommandMigrationAdapter;
import restudio.resync.upgrade.lifecycle.WorldGenMigrationAdapter;

class ProductionLifecycleUpgradeTest {
    @TempDir
    Path temporary;

    @Test
    void registersEveryProductionAdapterOnceAndReplansCanonicalOutputWithoutChanges() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("source"));
        Path network = Files.createDirectory(source.resolve("network"));
        Path assets = Files.createDirectory(source.resolve("assets"));
        Files.writeString(source.resolve("config.properties"), "enabled=true\n", StandardCharsets.UTF_8);
        Files.writeString(assets.resolve("project.json"), "{}", StandardCharsets.UTF_8);
        Files.writeString(network.resolve("resource-manifest.json"), "{\"version\":1,\"entries\":[]}", StandardCharsets.UTF_8);
        Files.writeString(network.resolve("network-state.json"), "{\"version\":1}", StandardCharsets.UTF_8);
        String invocation = "{\"productionLifecycle\":true}";
        Path invocationPath = temporary.resolve("control/plans/production.invocation");
        InvocationBinding.write(invocationPath, invocation);
        ProductionLifecycleUpgrade lifecycle = new ProductionLifecycleUpgrade(invocationPath, InvocationBinding.digest(invocation));

        List<String> adapterIds = lifecycle.adapters().stream().map(TypedLifecycleMigrationAdapter::adapterId).toList();
        assertEquals(List.of(
            AutomationMigrationAdapter.ID,
            CustomContentMigrationAdapter.ID,
            ExtensionStateMigrationAdapter.ID,
            JsonAssetMigrationAdapter.ID,
            "network-reconciliation-v2",
            "resync.lifecycle.managed-files-v1",
            ReplacementActivationRecordMigrationAdapter.ID,
            TimerDefinitionMigrationAdapter.ID,
            TombstoneMigrationAdapter.ID,
            TriggerCommandMigrationAdapter.ID,
            WorldGenMigrationAdapter.ID).stream().sorted().toList(), adapterIds);
        assertEquals(adapterIds.size(), Set.copyOf(adapterIds).size());
        assertEquals(ProductionPersistenceOwners.FLOW_ASSETS, CustomContentMigrationAdapter.OWNER);
        assertEquals(ProductionPersistenceOwners.FLOW_ASSETS, JsonAssetMigrationAdapter.OWNER);
        assertEquals(ProductionPersistenceOwners.FLOW_ASSETS, TimerDefinitionMigrationAdapter.OWNER);
        assertEquals(ProductionPersistenceOwners.FLOW_ASSETS, WorldGenMigrationAdapter.OWNER);

        Snapshot snapshot = snapshot(source, metadata(1, "production-source", "legacy-build"), temporary.resolve("snapshot"));
        Map<String, Long> claimCounts = lifecycle.authoritativeAdaptations(snapshot).values().stream()
            .flatMap(adaptation -> adaptation.claims().stream())
            .collect(Collectors.groupingBy(TypedLifecycleMigrationAdapter.Claim::relativePath, Collectors.counting()));
        assertEquals(snapshot.manifest().entries().stream().collect(Collectors.toMap(
            SnapshotManifest.Entry::relativePath, ignored -> 1L)), claimCounts);
        UpgradeProposal proposal = lifecycle.plan(snapshot, window(1, "legacy-build", 2, "replacement-build"));

        assertTrue(proposal.quarantineReport().records().isEmpty());
        assertEquals(Set.of("network/resource-manifest.json"), proposal.plan().operations().stream()
            .map(operation -> operation.sourcePath()).collect(Collectors.toSet()));
        var report = proposal.quarantineReport();
        var staged = lifecycle.stage(snapshot.root(), temporary.resolve("replacement"), proposal.plan(), report,
            report.accept("production-lifecycle-test", Instant.EPOCH));
        Snapshot canonical = snapshot(staged.root(), metadata(2, "production-canonical", "replacement-build"), temporary.resolve("canonical-snapshot"));
        UpgradeProposal second = lifecycle.plan(canonical, window(2, "replacement-build", 3, "next-build"));

        assertTrue(second.plan().operations().isEmpty());
        assertTrue(second.quarantineReport().records().isEmpty());
    }

    private Snapshot snapshot(Path source, SnapshotMetadata metadata, Path staging) throws IOException {
        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry();
        participants.register(participant(ProductionPersistenceOwners.CONFIGURATION, source.resolve("config.properties")));
        participants.register(participant(ProductionPersistenceOwners.FLOW_ASSETS, source.resolve("assets/project.json")));
        participants.register(participant(ProductionPersistenceOwners.NETWORK, source.resolve("network")));
        return new SnapshotService(new MigrationFence()).create(source, staging, metadata, participants);
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

    private SnapshotMetadata metadata(int format, String snapshotId, String build) {
        return new SnapshotMetadata(format, snapshotId, Instant.parse("2026-01-01T00:00:00Z"), build, "b".repeat(64), Map.of());
    }

    private UpgradeSourceWindow window(int sourceFormat, String sourceBuild, int targetFormat, String targetBuild) {
        return new UpgradeSourceWindow(ReplacementUpgrader.VERSION, sourceFormat, sourceBuild, targetFormat, targetBuild);
    }
}
