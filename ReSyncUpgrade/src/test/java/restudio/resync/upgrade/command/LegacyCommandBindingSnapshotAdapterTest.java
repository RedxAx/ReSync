package restudio.resync.upgrade.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.migration.MigrationFence;
import restudio.resync.migration.PersistenceParticipant;
import restudio.resync.migration.PersistenceParticipantRegistry;
import restudio.resync.migration.ProductionPersistenceOwners;
import restudio.resync.migration.ProductionSnapshotMetadataManifest;
import restudio.resync.migration.QuarantineAcceptance;
import restudio.resync.migration.QuarantineReport;
import restudio.resync.migration.Snapshot;
import restudio.resync.migration.SnapshotManifest;
import restudio.resync.migration.SnapshotMetadata;
import restudio.resync.migration.SnapshotState;
import restudio.resync.migration.SnapshotVerification;
import restudio.resync.migration.SnapshotService;
import restudio.resync.upgrade.ImmutableSnapshotAdapter;
import restudio.resync.upgrade.flow.LegacyResyncSnapshotAdapter;
import restudio.resync.upgrade.StandaloneUpgradePlanner;
import restudio.resync.upgrade.StandaloneUpgradeStager;
import restudio.resync.upgrade.UpgradeProposal;
import restudio.resync.upgrade.UpgradeSourceWindow;
import restudio.resync.upgrade.UpgradeVersion;
import restudio.resync.upgrade.adapter.OfflineUpgradeAdapter;
import restudio.resync.upgrade.adapter.OfflineUpgradeAdapterRegistry;
import restudio.resync.upgrade.adapter.OfflineUpgradeSnapshotAdapter;
import restudio.resync.upgrade.flow.LegacyFlowGraphSnapshotAdapter;
import restudio.resync.upgrade.adapter.OfflineUpgradeSnapshotInput;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class LegacyCommandBindingSnapshotAdapterTest {
    @TempDir
    Path temporary;

    @Test
    void legacyProviderIsRegisteredOnlyForItsFixedSourceWindow() {
        OfflineUpgradeAdapterRegistry registry = OfflineUpgradeAdapterRegistry.discover(getClass().getClassLoader());

        assertTrue(registry.adapters().isEmpty());
        assertEquals(List.of(LegacyResyncSnapshotAdapter.ADAPTER_ID + "#" + LegacyResyncSnapshotAdapter.ADAPTER_VERSION),
            registry.snapshotAdapters().stream().map(OfflineUpgradeSnapshotAdapter::wireId).toList());
    }

    @Test
    void migratesBindingsAcrossDocumentsInDeterministicOrderAndPreservesUnknownFields() throws Exception {
        Path source = source("cross-document", """
            [
              {"id":"second:command:start","flowId":"second","type":"COMMAND","context":"{\\"command\\":\\"second\\",\\"structured\\":true}"},
              {"id":"first:command:start","flowId":"first","type":"COMMAND","context":"{\\"command\\":\\"first\\",\\"subcommands\\":[\\"one\\"],\\"structured\\":false}"}
            ]
            """, graph("second", "start", "second", "unknown-second"), graph("first", "start", "first", "unknown-first"));
        Snapshot snapshot = snapshot(source, "cross-document-snapshot");
        LegacyCommandBindingSnapshotAdapter adapter = new LegacyCommandBindingSnapshotAdapter();

        OfflineUpgradeSnapshotAdapter.SnapshotTransform transformed = adapter.transform(input(snapshot));

        assertTrue(adapter.claims(input(snapshot)));
        assertEquals(List.of("assets/Blueprints/Commands/first.json", "assets/Blueprints/Commands/second.json", "triggers.json"),
            transformed.files().stream().map(OfflineUpgradeSnapshotAdapter.FileTransform::sourcePath).toList());
        assertTrue(transformed.quarantines().isEmpty());
        var outputs = transformed.files().stream().toList();
        String first = new String(outputs.getFirst().bytes(), StandardCharsets.UTF_8);
        String second = new String(outputs.get(1).bytes(), StandardCharsets.UTF_8);
        assertTrue(first.contains("\"command\":\"first\""));
        assertTrue(first.contains("\"unknownRoot\":{\"keep\":true}"));
        assertTrue(first.contains("\"unknownNode\":{\"keep\":true}"));
        assertTrue(second.contains("\"command\":\"second\""));
        String triggers = new String(outputs.getLast().bytes(), StandardCharsets.UTF_8);
        assertFalse(triggers.contains(":command:start"));
    }

    @Test
    void plannerAndStagerUseTheSameTransformAndSecondRunHasZeroChanges() throws Exception {
        Path source = source("apply", """
            [{"id":"apply:command:start","flowId":"apply","type":"COMMAND","context":"{\\"command\\":\\"apply\\"}"}]
            """, graph("apply", "start", "apply", "unknown"));
        Snapshot snapshot = snapshot(source, "apply-snapshot");
        LegacyCommandBindingSnapshotAdapter adapter = new LegacyCommandBindingSnapshotAdapter();
        OfflineUpgradeAdapterRegistry registry = registry(adapter);
        UpgradeProposal proposal = new StandaloneUpgradePlanner(registry).plan(snapshot, window());
        assertTrue(proposal.quarantineReport().records().isEmpty());
        assertEquals(2, proposal.plan().operations().size());

        Path beforeGraph = source.resolve("assets/Blueprints/Commands/apply.json");
        byte[] beforeSource = Files.readAllBytes(beforeGraph);
        Path staging = temporary.resolve("apply-staging");
        QuarantineReport report = proposal.quarantineReport();
        QuarantineAcceptance acceptance = report.accept("test", Instant.EPOCH);
        SnapshotManifest manifest = SnapshotManifest.read(source.resolveSibling("apply.manifest"));
        new StandaloneUpgradeStager(registry).stage(source, staging, proposal.plan(), report, acceptance);

        assertArrayEquals(beforeSource, Files.readAllBytes(beforeGraph));
        assertTrue(Files.readString(staging.resolve("assets/Blueprints/Commands/apply.json")).contains("\"command\":\"apply\""));
        String stagedTriggerDocument = Files.readString(staging.resolve("triggers.json"));
        assertFalse(stagedTriggerDocument.contains("apply:command:start"));
        assertFalse(stagedTriggerDocument.contains("\"type\":\"COMMAND\""));
        assertEquals(5L, AssetFileIdentity.readRevision(staging.resolve("assets/Blueprints/Commands/apply.json")));
        assertTrue(AssetFileIdentity.verify(staging.resolve("assets/Blueprints/Commands/apply.json")));
        Snapshot staged = snapshot(staging, "apply-staged-snapshot");
        OfflineUpgradeSnapshotAdapter.SnapshotTransform second = adapter.transform(input(staged));
        assertTrue(second.files().isEmpty());
        assertTrue(second.quarantines().isEmpty());
        UpgradeProposal secondProposal = new StandaloneUpgradePlanner(registry).plan(staged, window());
        assertTrue(secondProposal.plan().operations().isEmpty());
        assertTrue(secondProposal.quarantineReport().records().isEmpty());
        assertEquals(manifest.manifestHash(), snapshot.manifest().manifestHash());
    }

    @Test
    void changedSourceFailsBeforeGeneratedWritesAndLeavesSourceUntouched() throws Exception {
        Path source = source("rollback", """
            [{"id":"rollback:command:start","flowId":"rollback","type":"COMMAND","context":"rollback"}]
            """, graph("rollback", "start", "rollback", "unknown"));
        Snapshot snapshot = snapshot(source, "rollback-snapshot");
        OfflineUpgradeAdapterRegistry registry = registry(new LegacyCommandBindingSnapshotAdapter());
        UpgradeProposal proposal = new StandaloneUpgradePlanner(registry).plan(snapshot, window());
        Path trigger = source.resolve("triggers.json");
        byte[] before = Files.readAllBytes(trigger);
        Files.writeString(trigger, "[]", StandardCharsets.UTF_8);

        assertThrows(Exception.class, () -> new StandaloneUpgradeStager(registry).stage(source,
            temporary.resolve("rollback-staging"), proposal.plan(), proposal.quarantineReport(),
            proposal.quarantineReport().accept("test", Instant.EPOCH)));
        assertFalse(Files.exists(temporary.resolve("rollback-staging")));
        assertFalse(java.util.Arrays.equals(before, Files.readAllBytes(trigger)));
        assertEquals("[]", Files.readString(trigger));
    }

    @Test
    void ambiguityDuplicateAndMissingOwnerQuarantineWithoutOperations() throws Exception {
        Path ambiguous = source("ambiguous", """
            [{"id":"ambiguous:command","flowId":"ambiguous","type":"COMMAND","context":"restart"}]
            """, graphWithNodes("ambiguous", "first", "second"));
        UpgradeProposal ambiguousProposal = plan(ambiguous, "ambiguous-snapshot");
        assertTrue(ambiguousProposal.plan().operations().isEmpty());
        assertTrue(ambiguousProposal.quarantineReport().records().stream().anyMatch(record ->
            record.code().equals("MIGRATION.COMMAND_BINDING_AMBIGUOUS")));

        Path duplicate = source("duplicate", """
            [{"id":"duplicate:command:start","flowId":"duplicate","type":"COMMAND","context":"one"},
             {"id":"duplicate:command:start","flowId":"duplicate","type":"COMMAND","context":"two"}]
            """, graph("duplicate", "start", "duplicate", "unknown"));
        UpgradeProposal duplicateProposal = plan(duplicate, "duplicate-snapshot");
        assertTrue(duplicateProposal.plan().operations().isEmpty());
        assertTrue(duplicateProposal.quarantineReport().records().stream().anyMatch(record ->
            record.code().equals("MIGRATION.COMMAND_BINDING_DUPLICATE")));

        Path missing = source("missing", """
            [{"id":"missing:command:start","flowId":"missing","type":"COMMAND","context":"missing"}]
            """, graph("other", "start", "other", "unknown"));
        UpgradeProposal missingProposal = plan(missing, "missing-snapshot");
        assertTrue(missingProposal.plan().operations().isEmpty());
        assertTrue(missingProposal.quarantineReport().records().stream().anyMatch(record ->
            record.code().equals("MIGRATION.COMMAND_BINDING_MISSING_OWNER")));
    }

    @Test
    void bindingOwnerResolutionUsesExactFlowIdentity() throws Exception {
        Path source = source("exact", """
            [{"id":"exact:command:start","flowId":" exact ","type":"COMMAND","context":"exact"}]
            """, graph("exact", "start", "exact", "unknown"));

        UpgradeProposal proposal = plan(source, "exact-snapshot");

        assertTrue(proposal.plan().operations().isEmpty());
        assertTrue(proposal.quarantineReport().records().stream().anyMatch(record ->
            record.code().equals("MIGRATION.COMMAND_BINDING_MISSING_OWNER")));
    }

    @Test
    void unsupportedTriggerShapeIsNotClaimed() throws Exception {
        Path source = source("unsupported", "{\"version\":1,\"commands\":[]}", graph("unsupported", "start", "unsupported", "unknown"));
        Snapshot snapshot = snapshot(source, "unsupported-snapshot");

        assertFalse(new LegacyCommandBindingSnapshotAdapter().claims(input(snapshot)));
    }

    @Test
    void fixedSourceWindowRejectsOtherBuildsAndFormats() throws Exception {
        Path source = source("window", "[]", graph("window", "start", "window", "unknown"));
        Snapshot snapshot = snapshot(source, "window-snapshot");
        LegacyCommandBindingSnapshotAdapter adapter = new LegacyCommandBindingSnapshotAdapter();

        assertFalse(adapter.claims(input(withMetadata(snapshot, 1, "other-build"))));
        assertFalse(adapter.claims(input(withMetadata(snapshot, 2, LegacyCommandBindingSnapshotAdapter.SOURCE_BUILD))));
    }

    @Test
    void sanitizedLegacyFixtureRetiresOnlyCommandRowsAndRegeneratesIdentity() throws Exception {
        Path root = copyFixture("fixture");
        Snapshot snapshot = snapshot(root, "fixture-snapshot");
        LegacyCommandBindingSnapshotAdapter adapter = new LegacyCommandBindingSnapshotAdapter();

        OfflineUpgradeSnapshotAdapter.SnapshotTransform transformed = adapter.transform(input(snapshot));

        assertTrue(adapter.claims(input(snapshot)));
        assertTrue(transformed.quarantines().isEmpty());
        OfflineUpgradeSnapshotAdapter.SnapshotTransform repeated = adapter.transform(input(snapshot));
        assertEquals(transformed.files().stream().map(file -> new String(file.bytes(), StandardCharsets.UTF_8)).toList(),
            repeated.files().stream().map(file -> new String(file.bytes(), StandardCharsets.UTF_8)).toList());
        List<? extends OfflineUpgradeSnapshotAdapter.FileTransform> files = transformed.files().stream().toList();
        assertEquals(List.of("assets/Blueprints/Commands/fixture-command.json", "triggers.json"),
            files.stream().map(OfflineUpgradeSnapshotAdapter.FileTransform::sourcePath).toList());
        JsonValue.JsonObject graph = (JsonValue.JsonObject) CanonicalCodec.decodePermissive(files.getFirst().bytes());
        AssetFileIdentity.requireValid(graph, "command");
        assertEquals(5L, ((JsonValue.JsonNumber) graph.value(AssetFileIdentity.REVISION)).value().longValueExact());
        JsonValue.JsonObject node = (JsonValue.JsonObject) ((JsonValue.JsonObject) graph.value("nodes")).value("start");
        assertEquals("fixture", ((JsonValue.JsonString) ((JsonValue.JsonObject) node.value("inputValues")).value("command")).value());
        String triggers = new String(files.getLast().bytes(), StandardCharsets.UTF_8);
        assertFalse(triggers.contains("fixture-command:command:start"));
        assertTrue(triggers.contains("fixture-event"));
        assertTrue(triggers.contains("fixture-system"));
        assertTrue(triggers.contains("\"unknown\":{"));
    }

    @Test
    void commandMigrationPreservesUnknownAndNonCommandRows() throws Exception {
        Path source = source("preserve-rows", """
            [{"opaque":{"keep":true}},
             {"id":"event-row","type":"EVENT","payload":{"keep":true}},
             {"id":"preserve-rows:command:start","flowId":"preserve-rows","type":"COMMAND","context":"run"}]
            """, graph("preserve-rows", "start", "preserve-rows", "unknown"));

        OfflineUpgradeSnapshotAdapter.SnapshotTransform transformed = new LegacyCommandBindingSnapshotAdapter()
            .transform(input(snapshot(source, "preserve-rows-snapshot")));

        String triggers = new String(transformed.files().stream()
            .filter(file -> file.sourcePath().equals("triggers.json"))
            .findFirst().orElseThrow().bytes(), StandardCharsets.UTF_8);
        assertTrue(triggers.contains("\"opaque\":{"));
        assertTrue(triggers.contains("event-row"));
        assertFalse(triggers.contains("preserve-rows:command:start"));
    }

    @Test
    void legacyGraphWithoutAssetFieldsReceivesAValidIdentity() throws Exception {
        Path source = source("identity-repair", """
            [{"id":"identity-repair:command:start","flowId":"identity-repair","type":"COMMAND","context":"repair"}]
            """, """
            {"id":"identity-repair","resourceType":"command","resourceRevision":4,"resourceHash":"",
             "resourceMutationId":"legacy-mutation-004","nodes":{"start":{"type":"event.resync.command","inputs":{}}},"connections":[]}
            """);
        OfflineUpgradeSnapshotAdapter.SnapshotTransform transformed = new LegacyCommandBindingSnapshotAdapter()
            .transform(input(snapshot(source, "identity-repair-snapshot")));

        assertTrue(transformed.quarantines().isEmpty());
        JsonValue.JsonObject graph = (JsonValue.JsonObject) CanonicalCodec.decodePermissive(transformed.files().stream()
            .filter(file -> file.sourcePath().endsWith("identity-repair.json")).findFirst().orElseThrow().bytes());
        AssetFileIdentity.requireValid(graph, "command");
        assertEquals(5L, ((JsonValue.JsonNumber) graph.value(AssetFileIdentity.REVISION)).value().longValueExact());
    }

    @Test
    void invalidAssetIdentityQuarantinesTheGraphAndLeavesTriggerAuthorityUntouched() throws Exception {
        Path source = source("invalid-identity", """
            [{"id":"invalid-identity:command:start","flowId":"invalid-identity","type":"COMMAND","context":"repair"}]
            """, graph("invalid-identity", "start", "invalid-identity", "unknown"));
        Path graph = source.resolve("assets/Blueprints/Commands/invalid-identity.json");
        Files.writeString(graph, Files.readString(graph).replaceFirst("\\\"assetHash\\\":\\\"[0-9a-f]+\\\"",
            "\\\"assetHash\\\":\\\"" + "0".repeat(64) + "\\\""),
            StandardCharsets.UTF_8);
        UpgradeProposal proposal = plan(source, "invalid-identity-snapshot");

        assertTrue(proposal.plan().operations().isEmpty());
        assertTrue(proposal.quarantineReport().records().stream().anyMatch(record ->
            record.code().equals("MIGRATION.ASSET_IDENTITY_INVALID")));
        assertTrue(Files.readString(source.resolve("triggers.json")).contains("invalid-identity:command:start"));
    }

    @Test
    void emptyTriggersDoNotSilentlyClaimMalformedOrUnreferencedLegacyGraphs() throws Exception {
        Path malformed = source("unreferenced-malformed", "[]",
            "{\"id\":\"unreferenced-malformed\",\"resourceType\":\"command\",\"nodes\":{");
        UpgradeProposal malformedProposal = plan(malformed, "unreferenced-malformed-snapshot");

        assertTrue(malformedProposal.plan().operations().isEmpty());
        assertTrue(malformedProposal.quarantineReport().records().stream().anyMatch(record ->
            record.sourceLocation().endsWith("unreferenced-malformed.json")));

        Path legacy = source("unreferenced-legacy", "[]",
            "{\"id\":\"unreferenced-legacy\",\"resourceType\":\"command\",\"nodes\":{},\"connections\":[]}");
        UpgradeProposal legacyProposal = plan(legacy, "unreferenced-legacy-snapshot");

        assertTrue(legacyProposal.plan().operations().isEmpty());
        assertTrue(legacyProposal.quarantineReport().records().stream().anyMatch(record ->
            record.sourceLocation().endsWith("unreferenced-legacy.json")));
    }

    @Test
    void currentUnreferencedGraphsMustPassTypedCommandSemantics() throws Exception {
        List<String> invalidGraphs = List.of(
            "{\"id\":\"current-empty\",\"resourceType\":\"command\",\"nodes\":{},\"connections\":[]}",
            "{\"id\":\"current-string\",\"resourceType\":\"command\",\"nodes\":\"not-an-object\",\"connections\":[]}",
            "{\"id\":\"current-zero\",\"resourceType\":\"command\",\"nodes\":{\"start\":{\"type\":\"event.resync.message\",\"inputValues\":{}}},\"connections\":[]}",
            "{\"id\":\"current-multiple\",\"resourceType\":\"command\",\"nodes\":{\"first\":{\"type\":\"event.resync.command\",\"inputValues\":{}},\"second\":{\"type\":\"event:resync_command\",\"inputValues\":{}}},\"connections\":[]}",
            "{\"id\":\"current-input-shape\",\"resourceType\":\"command\",\"nodes\":{\"start\":{\"type\":\"event.resync.command\",\"inputValues\":\"not-an-object\"}},\"connections\":[]}"
        );
        for (String raw : invalidGraphs) {
            int idStart = raw.indexOf("\"id\":\"") + 6;
            String id = raw.substring(idStart, raw.indexOf('"', idStart));
            Path source = source(id, "[]", identity(raw, id, 7L, "current-" + id));
            UpgradeProposal proposal = plan(source, id + "-snapshot");

            assertTrue(proposal.plan().operations().isEmpty(), id);
            assertTrue(proposal.quarantineReport().records().stream().anyMatch(record ->
                record.sourceLocation().endsWith(id + ".json")), id);
        }

        String validId = "current-valid";
        Path valid = source(validId, "[]", identity(
            "{\"id\":\"current-valid\",\"resourceType\":\"command\",\"nodes\":{\"start\":{\"type\":\"event.resync.command\",\"inputValues\":{\"command\":\"valid\",\"subcommands\":[],\"structured\":false}}},\"connections\":[]}",
            validId, 7L, "current-valid-mutation"));
        UpgradeProposal validProposal = plan(valid, "current-valid-snapshot");

        assertTrue(validProposal.plan().operations().isEmpty());
        assertTrue(validProposal.quarantineReport().records().isEmpty());
    }

    @Test
    void quarantineRetainsUnsafeRowsAndStillTransformsSafeGraph() throws Exception {
        Path source = source("partial", """
            [{"id":"partial:command:start","flowId":"partial","type":"COMMAND","context":"safe"},
             {"id":"missing:command:start","flowId":"missing","type":"COMMAND","context":"unsafe"},
             {"id":"server:start","flowId":"server","type":"EVENT","context":"server_start","unknown":true}]
            """, graph("partial", "start", "partial", "unknown"));
        UpgradeProposal proposal = plan(source, "partial-snapshot");

        assertEquals(2, proposal.plan().operations().size());
        assertTrue(proposal.quarantineReport().records().stream().anyMatch(record ->
            record.code().equals("MIGRATION.COMMAND_BINDING_MISSING_OWNER")));

        Path staging = temporary.resolve("partial-staging");
        new StandaloneUpgradeStager(registry(new LegacyCommandBindingSnapshotAdapter())).stage(source, staging,
            proposal.plan(), proposal.quarantineReport(), proposal.quarantineReport().accept("test", Instant.EPOCH));
        String stagedTriggers = Files.readString(staging.resolve("triggers.json"));
        assertFalse(stagedTriggers.contains("partial:command:start"));
        assertTrue(stagedTriggers.contains("missing:command:start"));
        assertTrue(stagedTriggers.contains("server:start"));
        assertTrue(Files.exists(staging.resolve("assets/Blueprints/Commands/partial.json")));
        try (var files = Files.walk(staging.resolve(".quarantine"))) {
            assertTrue(files.filter(Files::isRegularFile).anyMatch(file -> {
                try {
                    String value = Files.readString(file);
                    return value.contains("missing:command:start") && value.contains("server:start");
                } catch (Exception exception) {
                    return false;
                }
            }));
        }
    }

    private UpgradeProposal plan(Path source, String snapshotId) throws Exception {
        return new StandaloneUpgradePlanner(registry(new LegacyCommandBindingSnapshotAdapter()))
            .plan(snapshot(source, snapshotId), window());
    }

    private OfflineUpgradeAdapterRegistry registry(LegacyCommandBindingSnapshotAdapter adapter) {
        return new OfflineUpgradeAdapterRegistry(List.<OfflineUpgradeAdapter>of(), List.of(adapter));
    }

    private Snapshot snapshot(Path root, String snapshotId) throws Exception {
        SnapshotMetadata metadata = new SnapshotMetadata(1, snapshotId, Instant.EPOCH,
            LegacyCommandBindingSnapshotAdapter.SOURCE_BUILD, "a".repeat(64), java.util.Map.of());
        PersistenceParticipantRegistry participants = participants(root);
        SnapshotManifest manifest = SnapshotManifest.scan(root, metadata, participants);
        Path manifestPath = root.resolveSibling(root.getFileName() + ".manifest");
        manifest.write(manifestPath);
        ProductionSnapshotMetadataManifest.write(root, manifest);
        SnapshotVerification verification = manifest.verify(root);
        verification.requireVerified();
        Path statePath = root.resolveSibling(root.getFileName() + ".state");
        Files.writeString(statePath, "state=VERIFIED\nverified=true\nmanifest-hash=" + verification.manifestHash()
            + "\nfailures=0\n", StandardCharsets.UTF_8);
        return new SnapshotService(new MigrationFence()).admitExported(root).snapshot();
    }

    private Snapshot withMetadata(Snapshot snapshot, int format, String build) {
        SnapshotMetadata metadata = new SnapshotMetadata(format, snapshot.metadata().snapshotId(), snapshot.metadata().createdAt(),
            build, snapshot.metadata().catalogChecksum(), snapshot.metadata().extensionVersions());
        return new Snapshot(snapshot.root(), snapshot.manifestPath(), snapshot.statePath(), metadata, snapshot.manifest(),
            SnapshotState.VERIFIED, snapshot.verification());
    }

    private Path copyFixture(String name) throws Exception {
        Path root = Files.createDirectories(temporary.resolve(name));
        copyResource("/fixtures/legacy-command-binding-v1/triggers.json", root.resolve("triggers.json"));
        Path graph = Files.createDirectories(root.resolve("assets/Blueprints/Commands"))
            .resolve("fixture-command.json");
        copyResource("/fixtures/legacy-command-binding-v1/assets/Blueprints/Commands/fixture-command.json", graph);
        return root;
    }

    private void copyResource(String resource, Path target) throws Exception {
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Fixture resource is missing: " + resource);
            }
            Files.copy(input, target);
        }
    }

    private OfflineUpgradeSnapshotInput input(Snapshot snapshot) throws Exception {
        return new OfflineUpgradeSnapshotInput(snapshot.root(), ImmutableSnapshotAdapter.adapt(snapshot));
    }

    private PersistenceParticipantRegistry participants(Path root) {
        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry();
        participants.register(participant(ProductionPersistenceOwners.TRIGGERS, root.resolve("triggers.json")));
        participants.register(participant(ProductionPersistenceOwners.FLOW_ASSETS, root.resolve("assets")));
        return participants;
    }

    private PersistenceParticipant participant(String owner, Path path) {
        return new PersistenceParticipant() {
            @Override
            public String owner() {
                return owner;
            }

            @Override
            public Path root() {
                return path;
            }
        };
    }

    private Path source(String name, String triggers, String... graphs) throws Exception {
        Path root = Files.createDirectories(temporary.resolve(name));
        Files.writeString(root.resolve("triggers.json"), triggers, StandardCharsets.UTF_8);
        Path commands = Files.createDirectories(root.resolve("assets/Blueprints/Commands"));
        for (String graph : graphs) {
            int idStart = graph.indexOf("\"id\":\"") + 6;
            String id = graph.substring(idStart, graph.indexOf("\"", idStart));
            Files.writeString(commands.resolve(id + ".json"), graph, StandardCharsets.UTF_8);
        }
        return root;
    }

    private String graph(String id, String nodeId, String command, String unknownKey) {
        String raw = "{\"id\":\"" + id + "\",\"resourceType\":\"command\",\"unknownRoot\":{\"keep\":true},\"nodes\":{\""
            + nodeId + "\":{\"type\":\"event.resync.command\",\"unknownNode\":{\"keep\":true},\"inputs\":{}}},\"connections\":[],\""
            + unknownKey + "\":true}";
        return identity(raw, id, 4L, "mutation-" + id + "-004");
    }

    private String graphWithNodes(String id, String first, String second) {
        String raw = "{\"id\":\"" + id + "\",\"resourceType\":\"command\",\"nodes\":{\"" + first
            + "\":{\"type\":\"event.resync.command\",\"inputs\":{}},\"" + second
            + "\":{\"type\":\"event.resync.command\",\"inputs\":{}}}}";
        return identity(raw, id, 4L, "mutation-" + id + "-004");
    }

    private UpgradeSourceWindow window() {
        return new UpgradeSourceWindow(UpgradeVersion.current(), LegacyCommandBindingSnapshotAdapter.SOURCE_FORMAT_VERSION,
            LegacyCommandBindingSnapshotAdapter.SOURCE_BUILD, 2, "replacement-1");
    }

    private String identity(String raw, String id, long revision, String mutationId) {
        JsonValue.JsonObject root = (JsonValue.JsonObject) CanonicalCodec.decodePermissive(raw);
        return new String(AssetFileIdentity.bytes(AssetFileIdentity.withResourceIdentity(root, "command", revision, mutationId)),
            StandardCharsets.UTF_8);
    }
}
