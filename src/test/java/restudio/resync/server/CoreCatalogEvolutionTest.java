package restudio.resync.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import restudio.resync.flow.CoreGraphStorageBoundary;
import restudio.resync.flow.catalog.CatalogCanonicalizer;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.GraphConnection;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphEndpoint;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.InspectorState;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.graph.PinValue;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ConnectionId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;
import restudio.resync.storage.StorageSafety;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CoreCatalogEvolutionTest {
    static final ServerId SERVER = ServerId.deterministic("core-catalog-evolution-test");
    static final CoreGraphStorageBoundary BOUNDARY = new CoreGraphStorageBoundary();
    static final CoreCatalogEvolution EVOLUTION = CoreCatalogEvolution.load();
    static final CatalogBinding SOURCE = EVOLUTION.sources().stream().sorted().toList().getLast();
    static final CatalogBinding TARGET = new CatalogBinding(56L, EVOLUTION.targetChecksum(),
        new ContentHash("8f0915fc00c8e51c98234f096c81166045e8c1e3bd4ef49f269d2cfda9d423c8"));
    static final CatalogBinding REBIND_SOURCE = CatalogBinding.parseCanonicalText(
        "57|d9c25c92807d45e79d80fcb941fdf181111d7af53c7f9f93126a62b310377a13|eb30c3b9bad4a5f57d1e62af0f66b25bf3d31d1d6b4c2158d6e5f2d2e417d0b4");
    static final CatalogBinding REBIND_TARGET = CatalogBinding.parseCanonicalText(
        "58|d79bad6b646cf8b0483bbd29d4936734b6eb1cf2e20dfbfcb8738098b88f72ad|eb30c3b9bad4a5f57d1e62af0f66b25bf3d31d1d6b4c2158d6e5f2d2e417d0b4");
    private static final String REBIND_TARGET_HASH = "0d515173207d5e9df65d9b3537226229b71d422a25468ded36fcbc2f46a29266";
    static final CatalogBinding RESOURCE_DECLARATION_TARGET = CatalogBinding.parseCanonicalText(
        "59|ff977f428b6131db6ba8d9e4f8c94419f28efdba5764f5b5fb8387150ff186ea|c60b31780fb0a24a10a2dc3fbf8bb3cef3af7f291c24669cc950e9dee5111273");
    private static final String RESOURCE_DECLARATION_TARGET_HASH =
        "f8921e75a1e09281baa0da132983684a41c423cb71318102d651aab6ad8dfbe1";
    private static final List<String> REBIND_BLOCKED = List.of("flow.switch_case", "permission.perm_has",
        "resource.build_custom_content", "resource.build_loot_pool", "resource.build_loot_table", "resource.build_recipe",
        "resource.build_scoreboard", "resource.build_trade_profile", "schedule.at.time", "schedule.cron", "schedule.interval",
        "schedule.schedule", "schedule.schedule_repeating", "variable.access");
    private static CoreCatalogEvolution.Proof contentProof;
    private static CoreCatalogEvolution.Proof rebindProof;
    private static CoreCatalogEvolution.Proof resourceDeclarationProof;
    private static final CacheFixture CACHE_FIXTURE = cacheFixture();

    static CoreCatalogEvolution.Proof structureProof() throws Exception {
        String configured = System.getenv("RESYNC_STRUCTURE_EVOLUTION_TARGET");
        assumeTrue(configured != null && !configured.isBlank(), "An explicit authenticated structure target snapshot is required");
        JsonObject snapshot = JsonParser.parseString(Files.readString(Path.of(configured))).getAsJsonObject();
        snapshot.addProperty("generation", 57L);
        CatalogBinding binding = new CatalogBinding(57L, snapshot.get("contentChecksum").getAsString(),
            snapshot.get("bindingManifestHash").getAsString());
        CoreCatalogEvolution evolution = CoreCatalogEvolution.select(binding).orElseThrow();
        assertEquals(CoreCatalogEvolution.STRUCTURE_ID, evolution.id());
        return evolution.proveContent(snapshot.toString(), binding);
    }

    @Test
    void authenticatesRegisteredStructureTargetAndRejectsUnregisteredContent() throws Exception {
        CoreCatalogEvolution.Proof proof = structureProof();
        assertEquals(57L, proof.target().generation());
        assertEquals(Set.of(TARGET), proof.evolution().sources());
        assertTrue(proof.eligible(source("flow", "unchanged", false, TARGET)));
        assertSame(proof.evolution(), CoreCatalogEvolution.registered(proof.evolution().registrationHash().canonicalText()));
    }

    @Test
    void structureEvolutionPreservesUnaffectedGraphsAndRejectsOldStructureNodes() {
        CacheFixture fixture = structureFixture();
        CoreCatalogEvolution evolution = fixture.evolution();
        CoreCatalogEvolution.Proof proof = evolution.proveContent(fixture.content(), fixture.target());
        CatalogBinding sourceBinding = evolution.sources().iterator().next();
        for (String type : List.of("flow", "command", "function")) {
            CoreGraphStorageBoundary.Decoded source = source(type, "structure-rebind", false, sourceBinding);
            assertTrue(proof.eligible(source));
            CoreGraphStorageBoundary.Decoded result = proof.project(source, proof.mutationId(source));
            GraphDocument before = CoreCatalogCompatibilityRebind.graph(source);
            GraphDocument after = CoreCatalogCompatibilityRebind.graph(result);
            JsonObject normalized = JsonParser.parseString(after.canonicalJson()).getAsJsonObject();
            JsonObject original = JsonParser.parseString(before.canonicalJson()).getAsJsonObject();
            normalized.add("catalogBinding", original.get("catalogBinding"));
            normalized.add("revision", original.get("revision"));
            assertEquals(original, normalized);
            assertEquals(fixture.target(), after.catalogBinding());
            assertEquals(before.revision() + 1L, after.revision());
            assertEquals(source.envelope().assetActivationState(), result.envelope().assetActivationState());
        }
        CoreGraphStorageBoundary.Decoded empty = source("flow", "old-structure", false, sourceBinding);
        GraphDocument graph = empty.graphDocument();
        GraphNode node = new GraphNode(NodeInstanceId.deterministic("delete"),
            ContractRef.of(OwnerId.of("restudio.resync"), NodeId.of("structure_delete")), 2, Map.of());
        GraphDocument affected = new GraphDocument(graph.schemaVersion(), graph.resource(), graph.revision(), graph.catalogBinding(),
            graph.requiredCapabilities(), List.of(node), graph.connections(), graph.variables(), graph.functions(), graph.unknown());
        CoreGraphStorageBoundary.Decoded rejected = BOUNDARY.decode(BOUNDARY.encode(affected,
            new CoreGraphStorageBoundary.AssetMetadata("flow", graph.revision(), UUID.fromString(empty.envelope().assetMutationId()),
                empty.envelope().assetActivationState()), graph.resource()), graph.resource());
        assertFalse(proof.eligible(rejected));
        assertThrows(IllegalArgumentException.class, () -> proof.project(rejected, UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> evolution.projectReceipt(rejected, fixture.target(), UUID.randomUUID()));
        CatalogBinding unexpected = new CatalogBinding(58L, fixture.target().catalogChecksum(), fixture.target().bindingManifestHash());
        assertThrows(IllegalArgumentException.class, () -> evolution.proveContent(contentAtGeneration(fixture.content(), 58L), unexpected));
    }

    @Test
    void authenticatesExactRestrictedRebindAndRejectsInapplicableTargets() throws Exception {
        CoreCatalogEvolution.Proof proof = rebindProof();
        CoreCatalogEvolution evolution = proof.evolution();

        assertEquals(CoreCatalogEvolution.REBIND_ID, evolution.id());
        assertEquals(REBIND_TARGET, proof.target());
        assertEquals(Set.of(REBIND_SOURCE), evolution.sources());
        assertEquals("f2a8183c1e52ae159d464294b115caa9a13456f26e9db3dfbb9e7d63d987da55",
            evolution.registrationHash().canonicalText());
        assertSame(evolution, CoreCatalogEvolution.select(REBIND_TARGET).orElseThrow());
        assertSame(evolution, CoreCatalogEvolution.select(REBIND_TARGET).orElseThrow());
        assertSame(evolution, CoreCatalogEvolution.registered(evolution.registrationHash().canonicalText()));
        assertTrue(CoreCatalogEvolution.isActor(CoreCatalogEvolution.REBIND_ID));
        assertTrue(CoreCatalogEvolution.select(new CatalogBinding(1L, REBIND_TARGET.catalogChecksum(),
            REBIND_TARGET.bindingManifestHash())).isEmpty());
        assertTrue(CoreCatalogEvolution.select(new CatalogBinding(59L, REBIND_TARGET.catalogChecksum(),
            REBIND_TARGET.bindingManifestHash())).isEmpty());
        assertTrue(CoreCatalogEvolution.select(new CatalogBinding(58L, REBIND_TARGET.catalogChecksum(),
            new ContentHash("0".repeat(64)))).isEmpty());
        assertTrue(CoreCatalogEvolution.select(new CatalogBinding(58L, new ContentHash("0".repeat(64)),
            REBIND_TARGET.bindingManifestHash())).isEmpty());
    }

    @Test
    void restrictedRebindRejectsTamperedContentAndWrongBootstrapSources() throws Exception {
        CoreCatalogEvolution evolution = CoreCatalogEvolution.select(REBIND_TARGET).orElseThrow();
        String content = rebindTargetContent();
        JsonObject tampered = JsonParser.parseString(content).getAsJsonObject();
        tampered.getAsJsonArray("definitions").get(0).getAsJsonObject().addProperty("schemaVersion", 99);

        assertThrows(IllegalArgumentException.class, () -> evolution.proveContent(tampered.toString(), REBIND_TARGET));
        CatalogBinding wrongSource = new CatalogBinding(57L, REBIND_SOURCE.catalogChecksum(), new ContentHash("0".repeat(64)));
        assertTrue(evolution.bootstrapProof(wrongSource, REBIND_TARGET, contentAtGeneration(content, 57L)).isEmpty());
        assertTrue(evolution.bootstrapProof(null, REBIND_TARGET, contentAtGeneration(content, 57L)).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> evolution.bootstrapProof(REBIND_SOURCE,
            new CatalogBinding(58L, REBIND_TARGET.catalogChecksum(), new ContentHash("0".repeat(64))),
            contentAtGeneration(content, 57L)));

        JsonObject registration;
        try (InputStream input = CoreCatalogEvolution.class.getResourceAsStream(
            "/restudio/resync/migration/core-catalog-evolution-v3.json")) {
            assertTrue(input != null);
            registration = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
        registration.getAsJsonArray("blockedDefinitions").remove(0);
        String altered = registration.toString();
        CoreCatalogEvolution alteredPolicy = new CoreCatalogEvolution(altered,
            new ContentHash(StorageSafety.sha256(altered)));
        assertThrows(IllegalArgumentException.class, () -> alteredPolicy.proveContent(content, REBIND_TARGET));
    }

    @Test
    void restrictedRebindPreservesSafeGraphsAndFunctionsWithoutChangingNodes() throws Exception {
        CoreCatalogEvolution.Proof proof = rebindProof();
        for (String type : List.of("flow", "command", "function")) {
            CoreGraphStorageBoundary.Decoded source = sourceWithDefinition(type, "safe-" + type, "schedule.delay", REBIND_SOURCE);
            assertTrue(proof.eligible(source));
            UUID mutationId = proof.mutationId(source);
            CoreGraphStorageBoundary.Decoded result = proof.project(source, mutationId);
            GraphDocument before = CoreCatalogCompatibilityRebind.graph(source);
            GraphDocument after = CoreCatalogCompatibilityRebind.graph(result);
            JsonObject normalized = JsonParser.parseString(after.canonicalJson()).getAsJsonObject();
            JsonObject original = JsonParser.parseString(before.canonicalJson()).getAsJsonObject();
            normalized.add("catalogBinding", original.get("catalogBinding"));
            normalized.add("revision", original.get("revision"));
            assertEquals(original, normalized);
            assertEquals(REBIND_TARGET, after.catalogBinding());
            assertEquals(before.revision() + 1L, after.revision());
            assertEquals(source.envelope().assetActivationState(), result.envelope().assetActivationState());
            if ("function".equals(type)) {
                assertEquals(source.functionSourceDocument().signature().function(),
                    result.functionSourceDocument().signature().function());
                assertEquals(source.functionSourceDocument().signature().revision().value() + 1L,
                    result.functionSourceDocument().signature().revision().value());
                assertEquals(source.functionSourceDocument().signature().inputs(), result.functionSourceDocument().signature().inputs());
                assertEquals(source.functionSourceDocument().signature().outputs(), result.functionSourceDocument().signature().outputs());
                assertEquals(source.functionSourceDocument().signature().unknown(), result.functionSourceDocument().signature().unknown());
                assertEquals(source.functionSourceDocument().unknown(), result.functionSourceDocument().unknown());
            }
        }
    }

    @Test
    void restrictedRebindRejectsEveryRegisteredBlockedDefinitionBeforeProjection() throws Exception {
        CoreCatalogEvolution evolution = CoreCatalogEvolution.select(REBIND_TARGET).orElseThrow();
        for (String definition : REBIND_BLOCKED) {
            CoreGraphStorageBoundary.Decoded source = sourceWithDefinition("flow", "blocked-" + definition, definition, REBIND_SOURCE);
            assertThrows(IllegalArgumentException.class,
                () -> evolution.projectReceipt(source, REBIND_TARGET, UUID.randomUUID()), definition);
        }
        CoreCatalogEvolution.Proof proof = rebindProof();
        for (String definition : REBIND_BLOCKED) {
            CoreGraphStorageBoundary.Decoded source = sourceWithDefinition("flow", "blocked-" + definition, definition, REBIND_SOURCE);
            assertFalse(proof.eligible(source), definition);
            assertThrows(IllegalArgumentException.class, () -> proof.project(source, UUID.randomUUID()), definition);
        }
    }

    @Test
    void authenticatesExactResourceDeclarationEvolution() throws Exception {
        CoreCatalogEvolution.Proof proof = resourceDeclarationProof();
        CoreCatalogEvolution evolution = proof.evolution();

        assertEquals(CoreCatalogEvolution.RESOURCE_DECLARATION_ID, evolution.id());
        assertEquals(RESOURCE_DECLARATION_TARGET, proof.target());
        assertEquals(Set.of(REBIND_TARGET), evolution.sources());
        assertEquals("a58aab1e0ddfd35fa3829b43e33ba614d1d5d0345ff889c9dfe8e7e0fd159428",
            evolution.registrationHash().canonicalText());
        assertSame(evolution, CoreCatalogEvolution.select(RESOURCE_DECLARATION_TARGET).orElseThrow());
        assertSame(evolution, CoreCatalogEvolution.registered(evolution.registrationHash().canonicalText()));
        assertTrue(CoreCatalogEvolution.isActor(CoreCatalogEvolution.RESOURCE_DECLARATION_ID));
        assertTrue(CoreCatalogEvolution.select(new CatalogBinding(58L, RESOURCE_DECLARATION_TARGET.catalogChecksum(),
            RESOURCE_DECLARATION_TARGET.bindingManifestHash())).isEmpty());
        assertTrue(CoreCatalogEvolution.select(new CatalogBinding(60L, RESOURCE_DECLARATION_TARGET.catalogChecksum(),
            RESOURCE_DECLARATION_TARGET.bindingManifestHash())).isEmpty());
    }

    @Test
    void resourceDeclarationEvolutionRejectsTamperedContentAndWrongSources() throws Exception {
        CoreCatalogEvolution evolution = CoreCatalogEvolution.select(RESOURCE_DECLARATION_TARGET).orElseThrow();
        String content = resourceDeclarationTargetContent();
        JsonObject alteredRead = JsonParser.parseString(content).getAsJsonObject();
        JsonArray reads = alteredRead.getAsJsonArray("definitions").get(0).getAsJsonObject()
            .getAsJsonObject("semantics").getAsJsonArray("resourceReads");
        if (reads.isEmpty()) {
            JsonObject undeclared = new JsonObject();
            undeclared.addProperty("ownerId", "restudio.resync");
            undeclared.addProperty("localId", "undeclared");
            reads.add(undeclared);
        } else {
            reads.remove(0);
        }
        JsonObject alteredBody = JsonParser.parseString(content).getAsJsonObject();
        alteredBody.getAsJsonArray("definitions").get(0).getAsJsonObject().addProperty("schemaVersion", 99);

        assertThrows(IllegalArgumentException.class,
            () -> evolution.proveContent(alteredRead.toString(), RESOURCE_DECLARATION_TARGET));
        assertThrows(IllegalArgumentException.class,
            () -> evolution.proveContent(alteredBody.toString(), RESOURCE_DECLARATION_TARGET));
        CatalogBinding wrongSource = new CatalogBinding(REBIND_TARGET.generation(), REBIND_TARGET.catalogChecksum(),
            new ContentHash("0".repeat(64)));
        assertTrue(evolution.bootstrapProof(wrongSource, RESOURCE_DECLARATION_TARGET,
            contentAtGeneration(content, REBIND_TARGET.generation())).isEmpty());
        assertTrue(evolution.bootstrapProof(null, RESOURCE_DECLARATION_TARGET,
            contentAtGeneration(content, REBIND_TARGET.generation())).isEmpty());
    }

    @Test
    void resourceDeclarationEvolutionPreservesEveryCoreGraphBody() throws Exception {
        CoreCatalogEvolution.Proof proof = resourceDeclarationProof();
        for (String type : List.of("flow", "command", "function")) {
            CoreGraphStorageBoundary.Decoded source = sourceWithDefinition(type, "resource-declaration-" + type,
                "schedule.delay", 2, REBIND_TARGET);
            assertTrue(proof.eligible(source));
            CoreGraphStorageBoundary.Decoded result = proof.project(source, proof.mutationId(source));
            GraphDocument before = CoreCatalogCompatibilityRebind.graph(source);
            GraphDocument after = CoreCatalogCompatibilityRebind.graph(result);
            JsonObject original = JsonParser.parseString(before.canonicalJson()).getAsJsonObject();
            JsonObject normalized = JsonParser.parseString(after.canonicalJson()).getAsJsonObject();
            normalized.add("catalogBinding", original.get("catalogBinding"));
            normalized.add("revision", original.get("revision"));

            assertEquals(original, normalized);
            assertEquals(RESOURCE_DECLARATION_TARGET, after.catalogBinding());
            assertEquals(before.revision() + 1L, after.revision());
            assertEquals(source.envelope().assetActivationState(), result.envelope().assetActivationState());
        }
    }

    @Test
    void retainsHistoricalRegistrationIdentityAndRejectsUnknownRegistrations() {
        assertSame(EVOLUTION, CoreCatalogEvolution.registered(EVOLUTION.registrationHash().canonicalText()));
        assertSame(EVOLUTION, CoreCatalogEvolution.select(TARGET).orElseThrow());
        assertEquals("9a90bc28495574d4ec0d701dcf2af9d4fe078b813ae8b9295488dff2fdcf3fba",
            EVOLUTION.registrationHash().canonicalText());
        assertEquals(CoreCatalogEvolution.ID, EVOLUTION.id());
        assertTrue(CoreCatalogEvolution.isActor(CoreCatalogEvolution.STRUCTURE_ID));
        assertThrows(IllegalStateException.class, () -> CoreCatalogEvolution.registered("0".repeat(64)));
    }

    private static CacheFixture structureFixture() {
        ContentHash manifest = new ContentHash("b".repeat(64));
        JsonObject source = catalogContent(56L, manifest, 2, 4);
        source.getAsJsonArray("definitions").get(0).getAsJsonObject().addProperty("id", "structure_delete");
        ContentHash sourceChecksum = CatalogCanonicalizer.checksumForCanonicalContent(source.toString());
        source.addProperty("contentChecksum", sourceChecksum.canonicalText());
        JsonObject target = source.deepCopy();
        target.addProperty("generation", 57L);
        target.getAsJsonArray("definitions").get(0).getAsJsonObject().addProperty("schemaVersion", 3);
        ContentHash targetChecksum = CatalogCanonicalizer.checksumForCanonicalContent(target.toString());
        target.addProperty("contentChecksum", targetChecksum.canonicalText());
        CatalogBinding binding = new CatalogBinding(57L, targetChecksum, manifest);
        JsonObject registration = new JsonObject();
        registration.addProperty("id", CoreCatalogEvolution.STRUCTURE_ID);
        registration.addProperty("sourceChecksum", sourceChecksum.canonicalText());
        registration.addProperty("targetChecksum", targetChecksum.canonicalText());
        registration.addProperty("targetBinding", binding.canonicalText());
        JsonArray sources = new JsonArray();
        sources.add(binding(56L, sourceChecksum, "b"));
        registration.add("sourceBindings", sources);
        JsonArray changes = new JsonArray();
        changes.add(replacementChange());
        registration.add("changes", changes);
        String content = registration.toString();
        return new CacheFixture(new CoreCatalogEvolution(content, new ContentHash(StorageSafety.sha256(content))),
            target.toString(), binding);
    }

    @Test
    void authenticatesFullProductionContentWithoutClaimingDeployedRuntimeAuthority() throws Exception {
        assertEquals(TARGET, proof().target());
        assertTrue(proof().eligible(source("flow", "whole-catalog", false)));
        assertTrue(proof().eligible(source("command", "command", true)));
        assertEquals(2, EVOLUTION.sources().size());
    }

    @Test
    void preservesTypedInspectorWiresLiteralsPositionsAndUnknownFields() {
        CoreGraphStorageBoundary.Decoded source = source("command", "rich-command", true);
        UUID mutationId = EVOLUTION.mutationId(source, TARGET);
        CoreGraphStorageBoundary.Decoded result = EVOLUTION.projectReceipt(source, TARGET, mutationId);
        GraphDocument before = CoreCatalogCompatibilityRebind.graph(source);
        GraphDocument after = CoreCatalogCompatibilityRebind.graph(result);
        JsonObject normalized = JsonParser.parseString(after.canonicalJson()).getAsJsonObject();
        JsonObject original = JsonParser.parseString(before.canonicalJson()).getAsJsonObject();
        normalized.add("catalogBinding", original.get("catalogBinding"));
        normalized.add("revision", original.get("revision"));
        for (JsonElement entry : normalized.getAsJsonArray("nodes")) {
            JsonObject node = entry.getAsJsonObject();
            if ("event.command".equals(node.getAsJsonObject("definition").get("localId").getAsString())) {
                assertEquals(3, node.get("definitionVersion").getAsInt());
                node.addProperty("definitionVersion", 2);
            }
        }
        assertEquals(original, normalized);
        assertEquals(source.envelope().assetActivationState(), result.envelope().assetActivationState());
        assertEquals(source.envelope().assetRevision() + 1L, result.envelope().assetRevision());
        assertNotEquals(source.envelope().assetHash(), result.envelope().assetHash());
        assertEquals(mutationId.toString(), result.envelope().assetMutationId());
        assertEquals(mutationId, EVOLUTION.mutationId(source, TARGET));
    }

    @Test
    void rebindsFlowAndFunctionWithoutCommandNodesAndPreservesSignature() {
        for (String type : List.of("flow", "function")) {
            CoreGraphStorageBoundary.Decoded source = source(type, type, false);
            CoreGraphStorageBoundary.Decoded result = EVOLUTION.projectReceipt(source, TARGET,
                EVOLUTION.mutationId(source, TARGET));
            assertEquals(TARGET, CoreCatalogCompatibilityRebind.graph(result).catalogBinding());
            assertTrue(CoreCatalogCompatibilityRebind.graph(result).nodes().isEmpty());
            assertEquals(ResourceActivationState.INACTIVE, result.envelope().assetActivationState());
            if ("function".equals(type)) {
                assertEquals(7L, result.functionSourceDocument().signature().revision().value());
                assertEquals(source.functionSourceDocument().signature().unknown(),
                    result.functionSourceDocument().signature().unknown());
                assertEquals(source.functionSourceDocument().unknown(), result.functionSourceDocument().unknown());
            }
        }
    }

    @Test
    void admitsBothExactHistoricalBindingsButRejectsUnknownDefinitionVersions() throws Exception {
        CatalogBinding source54 = EVOLUTION.sources().stream().sorted().toList().getFirst();
        CoreGraphStorageBoundary.Decoded source = source("flow", "source54", false, source54);
        assertTrue(proof().eligible(source));
        assertEquals(TARGET, proof().project(source, EVOLUTION.mutationId(source, TARGET)).graphDocument().catalogBinding());
        CoreGraphStorageBoundary.Decoded command = source("command", "wrong-version", true);
        GraphDocument graph = command.graphDocument();
        GraphNode node = graph.nodes().getFirst();
        GraphNode wrongVersion = new GraphNode(node.instanceId(), node.definition(), 1, Map.of());
        GraphDocument wrong = new GraphDocument(graph.schemaVersion(), graph.resource(), graph.revision(), graph.catalogBinding(),
            graph.requiredCapabilities(), List.of(wrongVersion), graph.connections(), graph.variables(), graph.functions(), graph.unknown());
        CoreGraphStorageBoundary.Decoded invalid = BOUNDARY.decode(BOUNDARY.encode(wrong,
            new CoreGraphStorageBoundary.AssetMetadata("command", graph.revision(), UUID.fromString(command.envelope().assetMutationId()),
                command.envelope().assetActivationState()), graph.resource()), graph.resource());
        assertFalse(proof().eligible(invalid));
    }

    @Test
    void target55AdmitsOnlyTheExact54SourceWhile56AdmitsBothSources() throws Exception {
        CatalogBinding source54 = EVOLUTION.sources().stream().sorted().toList().getFirst();
        CatalogBinding target55 = new CatalogBinding(55L, TARGET.catalogChecksum(), TARGET.bindingManifestHash());
        CoreCatalogEvolution.Proof proof55 = EVOLUTION.proveContent(targetContent(), target55);
        CoreGraphStorageBoundary.Decoded old54 = source("flow", "old54", false, source54);
        CoreGraphStorageBoundary.Decoded old55 = source("flow", "old55", false, SOURCE);
        assertTrue(proof55.eligible(old54));
        assertEquals(target55, proof55.project(old54, EVOLUTION.mutationId(old54, target55)).graphDocument().catalogBinding());
        assertFalse(proof55.eligible(old55));
        assertThrows(IllegalArgumentException.class, () -> proof55.project(old55, UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> EVOLUTION.projectReceipt(old55, target55, UUID.randomUUID()));
        assertTrue(proof().eligible(old54));
        assertTrue(proof().eligible(old55));
        assertThrows(IllegalArgumentException.class, () -> EVOLUTION.proveContent("{}",
            new CatalogBinding(54L, TARGET.catalogChecksum(), TARGET.bindingManifestHash())));
    }

    @Test
    void rejectsUnknownSourceManifestBackwardGenerationAndUnknownTargetContent() {
        assertFalse(EVOLUTION.accepts(new CatalogBinding(55L, SOURCE.catalogChecksum(), new ContentHash("1".repeat(64))), TARGET));
        assertFalse(EVOLUTION.accepts(CoreCatalogBindingMigration.SOURCE, TARGET));
        assertFalse(EVOLUTION.accepts(SOURCE, new CatalogBinding(55L, TARGET.catalogChecksum(), TARGET.bindingManifestHash())));
        assertFalse(EVOLUTION.accepts(SOURCE, new CatalogBinding(56L, SOURCE.catalogChecksum(), TARGET.bindingManifestHash())));
        assertThrows(IllegalArgumentException.class, () -> EVOLUTION.proveContent("{}", TARGET));
    }

    @Test
    void reusesTheExactProofForEqualCanonicalContent() throws Exception {
        CoreCatalogEvolution.Proof first = CACHE_FIXTURE.evolution().proveContent(CACHE_FIXTURE.content(), CACHE_FIXTURE.target());
        CoreCatalogEvolution.Proof second = CACHE_FIXTURE.evolution().proveContent(
            new String(CACHE_FIXTURE.content().toCharArray()), CACHE_FIXTURE.target());

        assertSame(first, second);
    }

    @Test
    void exactBindingCacheNeverAcceptsModifiedContent() throws Exception {
        CoreCatalogEvolution.Proof proof = CACHE_FIXTURE.evolution().proveContent(
            CACHE_FIXTURE.content(), CACHE_FIXTURE.target());
        JsonObject modified = JsonParser.parseString(CACHE_FIXTURE.content()).getAsJsonObject();
        modified.addProperty("contentChecksum", "0".repeat(64));

        assertThrows(IllegalArgumentException.class,
            () -> CACHE_FIXTURE.evolution().proveContent(modified.toString(), CACHE_FIXTURE.target()));
        assertSame(proof, CACHE_FIXTURE.evolution().proveContent(CACHE_FIXTURE.content(), CACHE_FIXTURE.target()));
    }

    @Test
    void differentGenerationProducesAnIndependentlyValidatedProof() throws Exception {
        CatalogBinding next = new CatalogBinding(CACHE_FIXTURE.target().generation() + 1L,
            CACHE_FIXTURE.target().catalogChecksum(), CACHE_FIXTURE.target().bindingManifestHash());
        String nextContent = contentAtGeneration(CACHE_FIXTURE.content(), next.generation());
        CoreCatalogEvolution.Proof first = CACHE_FIXTURE.evolution().proveContent(
            CACHE_FIXTURE.content(), CACHE_FIXTURE.target());
        CoreCatalogEvolution.Proof second = CACHE_FIXTURE.evolution().proveContent(nextContent, next);

        assertNotSame(first, second);
        assertEquals(CACHE_FIXTURE.target(), first.target());
        assertEquals(next, second.target());
        assertSame(second, CACHE_FIXTURE.evolution().proveContent(new String(nextContent.toCharArray()), next));
    }

    @Test
    void concurrentExactProofRequestsPublishOneImmutableResult() throws Exception {
        CatalogBinding concurrentTarget = new CatalogBinding(CACHE_FIXTURE.target().generation() + 10L,
            CACHE_FIXTURE.target().catalogChecksum(), CACHE_FIXTURE.target().bindingManifestHash());
        String content = contentAtGeneration(CACHE_FIXTURE.content(), concurrentTarget.generation());
        int callers = 24;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(callers)) {
            List<Future<CoreCatalogEvolution.Proof>> futures = new ArrayList<>();
            for (int index = 0; index < callers; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return CACHE_FIXTURE.evolution().proveContent(new String(content.toCharArray()), concurrentTarget);
                }));
            }
            ready.await();
            start.countDown();
            CoreCatalogEvolution.Proof published = futures.getFirst().get();
            for (Future<CoreCatalogEvolution.Proof> future : futures) {
                assertSame(published, future.get());
            }
            assertSame(published, CACHE_FIXTURE.evolution().proveContent(content, concurrentTarget));
        }
    }

    @Test
    void rejectsEnvelopeManifestThatDoesNotMatchTheSuppliedBinding() {
        CatalogBinding mismatched = new CatalogBinding(CACHE_FIXTURE.target().generation(),
            CACHE_FIXTURE.target().catalogChecksum(), new ContentHash("f".repeat(64)));

        assertThrows(IllegalArgumentException.class,
            () -> CACHE_FIXTURE.evolution().proveContent(CACHE_FIXTURE.content(), mismatched));
    }

    @Test
    void bootstrapProofExplicitlyAllowsOnlyTheRegisteredGenerationFloorTransition() {
        CatalogBinding source = CACHE_FIXTURE.evolution().sources().stream().sorted().toList().getFirst();
        CatalogBinding candidate = new CatalogBinding(source.generation() + 1L,
            CACHE_FIXTURE.target().catalogChecksum(), CACHE_FIXTURE.target().bindingManifestHash());
        String preflightContent = contentAtGeneration(CACHE_FIXTURE.content(), source.generation());

        assertEquals(CACHE_FIXTURE.target(), CACHE_FIXTURE.evolution()
            .bootstrapProof(source, candidate, preflightContent).orElseThrow().target());
        CatalogBinding beyondFloor = new CatalogBinding(CACHE_FIXTURE.target().generation() + 1L,
            CACHE_FIXTURE.target().catalogChecksum(), CACHE_FIXTURE.target().bindingManifestHash());
        assertThrows(IllegalArgumentException.class,
            () -> CACHE_FIXTURE.evolution().proveContent(preflightContent, beyondFloor));
    }

    @Test
    void bootstrapReservesTheRegisteredSourceHighWaterOnlyAfterFullContentProof() throws Exception {
        String content = targetContent();
        CatalogBinding candidate55 = new CatalogBinding(55L, TARGET.catalogChecksum(), TARGET.bindingManifestHash());
        for (CatalogBinding source : EVOLUTION.sources()) {
            assertEquals(TARGET, EVOLUTION.bootstrapProof(source, candidate55,
                contentAtGeneration(content, source.generation())).orElseThrow().target());
        }
        assertEquals(TARGET, EVOLUTION.bootstrapProof(TARGET, TARGET,
            contentAtGeneration(content, TARGET.generation())).orElseThrow().target());
        assertThrows(IllegalArgumentException.class, () -> EVOLUTION.bootstrapProof(candidate55, candidate55, content));
        assertTrue(EVOLUTION.bootstrapProof(null, candidate55, "{}").isEmpty());
        assertTrue(EVOLUTION.bootstrapProof(CoreCatalogBindingMigration.SOURCE, candidate55, "{}").isEmpty());
        assertTrue(EVOLUTION.bootstrapProof(SOURCE, SOURCE, "{}").isEmpty());
        CatalogBinding unknownManifest = new CatalogBinding(SOURCE.generation(), SOURCE.catalogChecksum(),
            new ContentHash("1".repeat(64)));
        assertTrue(EVOLUTION.bootstrapProof(unknownManifest, candidate55, "{}").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> EVOLUTION.bootstrapProof(SOURCE, candidate55, "{}"));
        assertThrows(IllegalArgumentException.class, () -> EVOLUTION.bootstrapProof(TARGET, TARGET, "{}"));
    }

    static synchronized CoreCatalogEvolution.Proof proof() throws Exception {
        if (contentProof == null) {
            contentProof = EVOLUTION.bootstrapProof(SOURCE, TARGET,
                contentAtGeneration(targetContent(), SOURCE.generation())).orElseThrow();
        }
        return contentProof;
    }

    private static String targetContent() throws Exception {
        String configured = System.getenv("RESYNC_CATALOG_EVOLUTION_TARGET");
        assumeTrue(configured != null && !configured.isBlank(), "An explicit authenticated target snapshot is required");
        Path path = Path.of(configured);
        assertTrue(path.isAbsolute());
        String content = Files.readString(path);
        assertEquals("314c95c181d3792fe94365224771b7c9c6ae2c0facbdd32dc888742e77332bbe", StorageSafety.sha256(content));
        return content;
    }

    private static CacheFixture cacheFixture() {
        ContentHash manifest = new ContentHash("b".repeat(64));
        JsonObject source = catalogContent(54L, manifest, 2, 4);
        ContentHash sourceChecksum = CatalogCanonicalizer.checksumForCanonicalContent(source.toString());
        source.addProperty("contentChecksum", sourceChecksum.canonicalText());
        JsonObject target = catalogContent(56L, manifest, 3, 10);
        ContentHash targetChecksum = CatalogCanonicalizer.checksumForCanonicalContent(target.toString());
        target.addProperty("contentChecksum", targetChecksum.canonicalText());

        JsonObject registration = new JsonObject();
        registration.addProperty("id", CoreCatalogEvolution.ID);
        registration.addProperty("sourceChecksum", sourceChecksum.canonicalText());
        registration.addProperty("targetChecksum", targetChecksum.canonicalText());
        JsonArray sources = new JsonArray();
        sources.add(binding(54L, sourceChecksum, "a"));
        sources.add(binding(55L, sourceChecksum, "c"));
        registration.add("sourceBindings", sources);
        JsonArray changes = new JsonArray();
        changes.add(replacementChange());
        changes.add(pinInsertionChange());
        registration.add("changes", changes);
        String registered = registration.toString();
        CoreCatalogEvolution evolution = new CoreCatalogEvolution(registered,
            new ContentHash(StorageSafety.sha256(registered)));
        return new CacheFixture(evolution, target.toString(),
            new CatalogBinding(56L, targetChecksum, manifest));
    }

    private static JsonObject catalogContent(long generation, ContentHash manifest, int schemaVersion, int pinCount) {
        JsonObject root = new JsonObject();
        root.addProperty("kind", "snapshot");
        root.addProperty("generation", generation);
        JsonObject contract = new JsonObject();
        contract.addProperty("generation", 1);
        root.add("contractVersion", contract);
        root.addProperty("contentChecksum", "0".repeat(64));
        root.addProperty("bindingManifestHash", manifest.canonicalText());
        JsonObject command = new JsonObject();
        command.addProperty("ownerId", "restudio.resync");
        command.addProperty("id", "event.command");
        command.addProperty("schemaVersion", schemaVersion);
        JsonArray pins = new JsonArray();
        for (int index = 0; index < pinCount; index++) {
            pins.add("pin-" + index);
        }
        command.add("pins", pins);
        JsonArray definitions = new JsonArray();
        definitions.add(command);
        root.add("definitions", definitions);
        return root;
    }

    private static JsonObject binding(long generation, ContentHash checksum, String manifestDigit) {
        JsonObject binding = new JsonObject();
        binding.addProperty("generation", generation);
        binding.addProperty("catalogChecksum", checksum.canonicalText());
        binding.addProperty("bindingManifestHash", manifestDigit.repeat(64));
        return binding;
    }

    private static JsonObject replacementChange() {
        JsonObject change = new JsonObject();
        change.addProperty("kind", "replace");
        change.addProperty("beforePresent", true);
        change.addProperty("afterPresent", true);
        change.addProperty("before", 2);
        change.addProperty("after", 3);
        change.add("paths", paths("definitions", "0", "schemaVersion"));
        return change;
    }

    private static JsonObject pinInsertionChange() {
        JsonObject change = new JsonObject();
        change.addProperty("kind", "insert");
        change.addProperty("index", 4);
        JsonArray values = new JsonArray();
        for (int index = 4; index < 10; index++) {
            values.add("pin-" + index);
        }
        change.add("values", values);
        change.add("paths", paths("definitions", "0", "pins"));
        return change;
    }

    private static JsonArray paths(String... segments) {
        JsonArray path = new JsonArray();
        for (String segment : segments) {
            path.add(segment);
        }
        JsonArray paths = new JsonArray();
        paths.add(path);
        return paths;
    }

    static synchronized CoreCatalogEvolution.Proof rebindProof() throws Exception {
        if (rebindProof == null) {
            CoreCatalogEvolution evolution = CoreCatalogEvolution.select(REBIND_TARGET).orElseThrow();
            rebindProof = evolution.proveContent(rebindTargetContent(), REBIND_TARGET);
        }
        return rebindProof;
    }

    private static String rebindTargetContent() throws Exception {
        String configured = System.getenv("RESYNC_CATALOG_REBIND_TARGET");
        assumeTrue(configured != null && !configured.isBlank(), "An explicit authenticated rebind target snapshot is required");
        Path path = Path.of(configured);
        assertTrue(path.isAbsolute());
        String content = Files.readString(path);
        assertEquals(REBIND_TARGET_HASH, StorageSafety.sha256(content));
        JsonObject snapshot = JsonParser.parseString(content).getAsJsonObject();
        assertEquals(1L, snapshot.get("generation").getAsLong());
        assertEquals(REBIND_TARGET.catalogChecksum(), CatalogCanonicalizer.checksumForCanonicalContent(content));
        assertEquals(REBIND_TARGET.bindingManifestHash(),
            new ContentHash(snapshot.get("bindingManifestHash").getAsString()));
        snapshot.addProperty("generation", REBIND_TARGET.generation());
        return snapshot.toString();
    }

    static synchronized CoreCatalogEvolution.Proof resourceDeclarationProof() throws Exception {
        if (resourceDeclarationProof == null) {
            CoreCatalogEvolution evolution = CoreCatalogEvolution.select(RESOURCE_DECLARATION_TARGET).orElseThrow();
            resourceDeclarationProof = evolution.proveContent(resourceDeclarationTargetContent(), RESOURCE_DECLARATION_TARGET);
        }
        return resourceDeclarationProof;
    }

    private static String resourceDeclarationTargetContent() throws Exception {
        String configured = System.getenv("RESYNC_RESOURCE_DECLARATION_EVOLUTION_TARGET");
        assumeTrue(configured != null && !configured.isBlank(),
            "An explicit authenticated resource declaration target snapshot is required");
        Path path = Path.of(configured);
        assertTrue(path.isAbsolute());
        String content = Files.readString(path);
        assertEquals(RESOURCE_DECLARATION_TARGET_HASH, StorageSafety.sha256(content));
        JsonObject snapshot = JsonParser.parseString(content).getAsJsonObject();
        assertEquals(1L, snapshot.get("generation").getAsLong());
        assertEquals(RESOURCE_DECLARATION_TARGET.catalogChecksum(), CatalogCanonicalizer.checksumForCanonicalContent(content));
        assertEquals(RESOURCE_DECLARATION_TARGET.bindingManifestHash(),
            new ContentHash(snapshot.get("bindingManifestHash").getAsString()));
        snapshot.addProperty("generation", RESOURCE_DECLARATION_TARGET.generation());
        return snapshot.toString();
    }

    private static String contentAtGeneration(String content, long generation) {
        JsonObject root = JsonParser.parseString(content).getAsJsonObject();
        root.addProperty("generation", generation);
        return root.toString();
    }

    private record CacheFixture(CoreCatalogEvolution evolution, String content, CatalogBinding target) {
    }

    static CoreGraphStorageBoundary.Decoded source(String type, String id, boolean withCommand) {
        return source(type, id, withCommand, SOURCE);
    }

    static CoreGraphStorageBoundary.Decoded source(String type, String id, boolean withCommand, CatalogBinding binding) {
        ServerResourceLocator resource = resource(type, id);
        List<GraphNode> nodes = List.of();
        List<GraphConnection> connections = List.of();
        if (withCommand) {
            NodeInstanceId nodeId = NodeInstanceId.deterministic(id);
            PinId literal = PinId.of("literal");
            TypedValue text = TypedValue.value(TypeExpr.named(new TypeReference("builtin", "string")), "exact value");
            Map<Object, Object> inspector = new LinkedHashMap<>();
            inspector.put(InspectorFieldId.of("new-inspector"), text);
            GraphNode node = new GraphNode(nodeId, ContractRef.of(OwnerId.of("restudio.resync"), NodeId.of("event.command")),
                2, null, Map.of(literal, new PinValue(literal, text)), inspector, List.of(), List.of(),
                InspectorState.empty(), 73.25, -91.5, OpaqueData.of(Map.of("preserve-node", true)));
            nodes = List.of(node);
            connections = List.of(new GraphConnection(ConnectionId.deterministic(id),
                new GraphEndpoint(nodeId, PinId.of("event.command")), new GraphEndpoint(nodeId, literal),
                OpaqueData.of(Map.of("preserve-wire", "yes"))));
        }
        GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), resource, 6L, binding, Set.of(), nodes,
            connections, List.of(), List.of(), OpaqueData.of(Map.of("preserve-graph", List.of("exact", 7L))));
        UUID mutationId = UUID.nameUUIDFromBytes((type + ":" + id).getBytes(StandardCharsets.UTF_8));
        CoreGraphStorageBoundary.AssetMetadata metadata = new CoreGraphStorageBoundary.AssetMetadata(type, 6L,
            mutationId, ResourceActivationState.INACTIVE);
        if (!"function".equals(type)) {
            return BOUNDARY.decode(BOUNDARY.encode(graph, metadata, resource), resource);
        }
        FunctionSignature signature = new FunctionSignature(new FunctionLocator(resource), new FunctionRevision(6L),
            List.of(), List.of(), Map.of("preserve-signature", true));
        FunctionSourceDocument function = new FunctionSourceDocument(signature, graph,
            OpaqueData.of(Map.of("preserve-function", true)));
        return BOUNDARY.decode(BOUNDARY.encode(function, metadata, resource), resource);
    }

    private static CoreGraphStorageBoundary.Decoded sourceWithDefinition(String type, String id, String definition,
                                                                          CatalogBinding binding) {
        return sourceWithDefinition(type, id, definition, 2, binding);
    }

    private static CoreGraphStorageBoundary.Decoded sourceWithDefinition(String type, String id, String definition,
                                                                          int version, CatalogBinding binding) {
        CoreGraphStorageBoundary.Decoded empty = source(type, id, false, binding);
        GraphDocument graph = CoreCatalogCompatibilityRebind.graph(empty);
        GraphNode node = new GraphNode(NodeInstanceId.deterministic(id),
            ContractRef.of(OwnerId.of("restudio.resync"), NodeId.of(definition)), version, Map.of());
        GraphDocument populated = new GraphDocument(graph.schemaVersion(), graph.resource(), graph.revision(), graph.catalogBinding(),
            graph.requiredCapabilities(), List.of(node), graph.connections(), graph.variables(), graph.functions(), graph.unknown());
        CoreGraphStorageBoundary.AssetMetadata metadata = new CoreGraphStorageBoundary.AssetMetadata(type, graph.revision(),
            UUID.fromString(empty.envelope().assetMutationId()), empty.envelope().assetActivationState());
        if (!"function".equals(type)) {
            return BOUNDARY.decode(BOUNDARY.encode(populated, metadata, graph.resource()), graph.resource());
        }
        FunctionSourceDocument original = empty.functionSourceDocument();
        FunctionSourceDocument function = new FunctionSourceDocument(original.signature(), populated, original.unknown());
        return BOUNDARY.decode(BOUNDARY.encode(function, metadata, graph.resource()), graph.resource());
    }

    static ServerResourceLocator resource(String type, String id) {
        return new ServerResourceLocator(SERVER, ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of(type)), id);
    }
}
