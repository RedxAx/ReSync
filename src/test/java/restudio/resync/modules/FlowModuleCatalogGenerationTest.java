package restudio.resync.modules;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import restudio.resync.flow.catalog.CatalogBindingProof;
import restudio.resync.flow.catalog.CatalogCanonicalizer;
import restudio.resync.flow.catalog.CatalogCompiler;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.runtime.RuntimeBindingRegistry;
import restudio.resync.flow.runtime.RuntimeBinding;
import restudio.resync.flow.runtime.RuntimeFailureContract;
import restudio.resync.flow.runtime.RuntimeOperationDescriptor;
import restudio.resync.flow.runtime.RuntimeProviderDescriptor;
import restudio.resync.flow.runtime.RuntimeResult;
import restudio.resync.flow.runtime.RuntimeSemantics;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.server.CoreCatalogEvolution;
import restudio.resync.storage.StorageSafety;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FlowModuleCatalogGenerationTest {
    private static final CoreCatalogEvolution EVOLUTION = CoreCatalogEvolution.load();
    private static final CatalogBinding REBIND_SOURCE = CatalogBinding.parseCanonicalText(
        "57|d9c25c92807d45e79d80fcb941fdf181111d7af53c7f9f93126a62b310377a13|eb30c3b9bad4a5f57d1e62af0f66b25bf3d31d1d6b4c2158d6e5f2d2e417d0b4");
    private static final CatalogBinding REBIND_TARGET = CatalogBinding.parseCanonicalText(
        "58|d79bad6b646cf8b0483bbd29d4936734b6eb1cf2e20dfbfcb8738098b88f72ad|eb30c3b9bad4a5f57d1e62af0f66b25bf3d31d1d6b4c2158d6e5f2d2e417d0b4");
    private static final String REBIND_TARGET_HASH = "0d515173207d5e9df65d9b3537226229b71d422a25468ded36fcbc2f46a29266";
    private static final CatalogBinding RESOURCE_DECLARATION_TARGET = CatalogBinding.parseCanonicalText(
        "59|ff977f428b6131db6ba8d9e4f8c94419f28efdba5764f5b5fb8387150ff186ea|c60b31780fb0a24a10a2dc3fbf8bb3cef3af7f291c24669cc950e9dee5111273");
    private static final String RESOURCE_DECLARATION_TARGET_HASH =
        "f8921e75a1e09281baa0da132983684a41c423cb71318102d651aab6ad8dfbe1";
    private static final CatalogSnapshot UNRELATED = unrelatedCatalog();
    private static String authenticatedTarget;
    private static String authenticatedRebindTarget;
    private static String authenticatedResourceDeclarationTarget;

    @Test
    void exactRegisteredSourcesAllocateAboveTheAuthenticatedHistoricalHighWater() throws Exception {
        List<CatalogBinding> sources = registeredSources();
        assertEquals(List.of(54L, 55L), sources.stream().map(CatalogBinding::generation).toList());
        for (CatalogBinding source : sources) {
            CatalogBinding allocated = target(source, targetContent());
            assertEquals(56L, allocated.generation());
            assertEquals(EVOLUTION.targetChecksum(), allocated.catalogChecksum());
            assertEquals(new ContentHash(JsonParser.parseString(targetContent()).getAsJsonObject().get("bindingManifestHash").getAsString()),
                allocated.bindingManifestHash());
            JsonObject envelope = JsonParser.parseString(targetContent()).getAsJsonObject();
            envelope.addProperty("generation", source.generation());
            assertEquals(allocated, EVOLUTION.bootstrapProof(source, allocated, envelope.toString()).orElseThrow().target());
        }
    }

    @Test
    void alreadyCurrentTargetAndInterruptedBootstrapDoNotAllocateAnotherGeneration() throws Exception {
        CatalogBinding source = registeredSources().getFirst();
        CatalogBinding allocated = target(source, targetContent());
        assertEquals(allocated, target(allocated, targetContent()));
        assertEquals(allocated, target(source, targetContent()));
        CatalogBinding belowFloor = new CatalogBinding(55L, allocated.catalogChecksum(), allocated.bindingManifestHash());
        assertThrows(IllegalArgumentException.class, () -> target(belowFloor, targetContent()));
    }

    @Test
    void targetRuntimeManifestChangePreservesNormalGenerationSemantics() throws Exception {
        RuntimeBindingRegistry runtime = new RuntimeBindingRegistry();
        ContentHash previousManifest = runtime.snapshot().bindingManifestHash();
        addRuntimeBinding(runtime);
        ContentHash changedManifest = runtime.snapshot().bindingManifestHash();
        assertNotEquals(previousManifest, changedManifest);
        CatalogBinding previous = new CatalogBinding(56L, EVOLUTION.targetChecksum(), previousManifest);
        CatalogBinding expected = new CatalogBinding(57L, EVOLUTION.targetChecksum(), changedManifest);

        CatalogBinding allocated = FlowModule.startupCatalogBinding(previous, EVOLUTION.targetChecksum(),
            changedManifest, targetContent());

        assertEquals(expected, allocated);
    }

    @Test
    void freshAndUnrelatedInstallationsDoNotInheritTheRegisteredFloor() throws Exception {
        assertEquals(1L, target(null, targetContent()).generation());
        CatalogBinding unrelated = new CatalogBinding(7L, UNRELATED.contentChecksum(), UNRELATED.bindingManifestHash());
        assertEquals(8L, target(unrelated, targetContent()).generation());
    }

    @Test
    void restrictedRebindAllocatesOnlyTheExactRegistered57To58Edge() throws Exception {
        String sourceEnvelope = rebindTargetContent(REBIND_SOURCE.generation());
        CatalogBinding allocated = FlowModule.startupCatalogBinding(REBIND_SOURCE, REBIND_TARGET.catalogChecksum(),
            REBIND_TARGET.bindingManifestHash(), sourceEnvelope);

        assertEquals(REBIND_TARGET, allocated);
        assertEquals(REBIND_TARGET, FlowModule.startupCatalogBinding(REBIND_TARGET, REBIND_TARGET.catalogChecksum(),
            REBIND_TARGET.bindingManifestHash(), rebindTargetContent(REBIND_TARGET.generation())));
        CatalogBinding fresh = FlowModule.startupCatalogBinding(null, REBIND_TARGET.catalogChecksum(),
            REBIND_TARGET.bindingManifestHash(), rebindTargetContent(1L));
        assertEquals(1L, fresh.generation());
        assertTrue(CoreCatalogEvolution.select(fresh).isEmpty());
    }

    @Test
    void restrictedRebindBootstrapRejectsTamperedCanonicalContent() throws Exception {
        JsonObject tampered = JsonParser.parseString(rebindTargetContent(REBIND_SOURCE.generation())).getAsJsonObject();
        tampered.getAsJsonArray("definitions").get(0).getAsJsonObject().addProperty("schemaVersion", 99);

        assertThrows(IllegalArgumentException.class, () -> FlowModule.startupCatalogBinding(REBIND_SOURCE,
            REBIND_TARGET.catalogChecksum(), REBIND_TARGET.bindingManifestHash(), tampered.toString()));
    }

    @Test
    void resourceDeclarationEvolutionAllocatesOnlyTheExactRegistered58To59Edge() throws Exception {
        String sourceEnvelope = resourceDeclarationTargetContent(REBIND_TARGET.generation());
        CatalogBinding allocated = FlowModule.startupCatalogBinding(REBIND_TARGET,
            RESOURCE_DECLARATION_TARGET.catalogChecksum(), RESOURCE_DECLARATION_TARGET.bindingManifestHash(), sourceEnvelope);

        assertEquals(RESOURCE_DECLARATION_TARGET, allocated);
        assertEquals(RESOURCE_DECLARATION_TARGET, FlowModule.startupCatalogBinding(RESOURCE_DECLARATION_TARGET,
            RESOURCE_DECLARATION_TARGET.catalogChecksum(), RESOURCE_DECLARATION_TARGET.bindingManifestHash(),
            resourceDeclarationTargetContent(RESOURCE_DECLARATION_TARGET.generation())));
        CatalogBinding fresh = FlowModule.startupCatalogBinding(null, RESOURCE_DECLARATION_TARGET.catalogChecksum(),
            RESOURCE_DECLARATION_TARGET.bindingManifestHash(), resourceDeclarationTargetContent(1L));
        assertEquals(1L, fresh.generation());
        assertTrue(CoreCatalogEvolution.select(fresh).isEmpty());
    }

    @Test
    void resourceDeclarationEvolutionCannotSkipTheRegisteredRebindPrerequisite() throws Exception {
        CatalogBinding staged = FlowModule.startupCatalogBinding(REBIND_SOURCE,
            RESOURCE_DECLARATION_TARGET.catalogChecksum(), RESOURCE_DECLARATION_TARGET.bindingManifestHash(),
            resourceDeclarationTargetContent(REBIND_SOURCE.generation()));

        assertEquals(58L, staged.generation());
        assertEquals(RESOURCE_DECLARATION_TARGET.catalogChecksum(), staged.catalogChecksum());
        assertEquals(RESOURCE_DECLARATION_TARGET.bindingManifestHash(), staged.bindingManifestHash());
        assertTrue(CoreCatalogEvolution.select(staged).isEmpty());
    }

    @Test
    void ordinaryCatalogsKeepNormalContentAndRuntimeGenerationSemantics() {
        CatalogBinding fresh = FlowModule.startupCatalogBinding(null, UNRELATED.contentChecksum(),
            UNRELATED.bindingManifestHash(), UNRELATED.canonicalContent());
        assertEquals(1L, fresh.generation());
        CatalogBinding persisted = new CatalogBinding(7L, fresh.catalogChecksum(), fresh.bindingManifestHash());
        assertEquals(persisted, FlowModule.startupCatalogBinding(persisted, fresh.catalogChecksum(),
            fresh.bindingManifestHash(), UNRELATED.canonicalContent()));
        ContentHash otherManifest = new ContentHash("a".repeat(64));
        CatalogBinding changed = FlowModule.startupCatalogBinding(persisted, fresh.catalogChecksum(),
            otherManifest, UNRELATED.canonicalContent());
        assertEquals(8L, changed.generation());
        assertEquals(otherManifest, changed.bindingManifestHash());
    }

    @Test
    void durablePublicationHighWaterCannotBeReusedAfterAStaleActivationRecord() {
        CatalogBinding persisted = new CatalogBinding(62L, UNRELATED.contentChecksum(), UNRELATED.bindingManifestHash());

        CatalogBinding advanced = FlowModule.startupCatalogBinding(persisted, persisted.catalogChecksum(),
            persisted.bindingManifestHash(), UNRELATED.canonicalContent(), 63L);
        CatalogBinding current = FlowModule.startupCatalogBinding(advanced, advanced.catalogChecksum(),
            advanced.bindingManifestHash(), UNRELATED.canonicalContent(), 63L);

        assertEquals(64L, advanced.generation());
        assertEquals(advanced, current);
        CatalogBinding foreignOrAbsent = FlowModule.startupCatalogBinding(persisted, persisted.catalogChecksum(),
            persisted.bindingManifestHash(), UNRELATED.canonicalContent(), 0L);
        assertEquals(persisted, foreignOrAbsent);
    }

    @Test
    void publicationHighWaterAndBindingChangeAdvanceOnlyOnceAboveTheLargestGeneration() {
        CatalogBinding persisted = new CatalogBinding(62L, UNRELATED.contentChecksum(), UNRELATED.bindingManifestHash());
        ContentHash changedManifest = new ContentHash("b".repeat(64));

        CatalogBinding advanced = FlowModule.startupCatalogBinding(persisted, persisted.catalogChecksum(),
            changedManifest, UNRELATED.canonicalContent(), 63L);

        assertEquals(64L, advanced.generation());
        assertEquals(changedManifest, advanced.bindingManifestHash());
        assertThrows(ArithmeticException.class, () -> FlowModule.startupCatalogBinding(persisted,
            persisted.catalogChecksum(), changedManifest, UNRELATED.canonicalContent(), Long.MAX_VALUE));
    }

    @Test
    void claimedRegisteredTargetCannotAllocateFromTamperedContent() throws Exception {
        JsonObject tampered = JsonParser.parseString(targetContent()).getAsJsonObject();
        JsonObject command = null;
        for (JsonElement value : tampered.getAsJsonArray("definitions")) {
            JsonObject definition = value.getAsJsonObject();
            if ("restudio.resync".equals(definition.get("ownerId").getAsString())
                && "event.command".equals(definition.get("id").getAsString())) {
                command = definition;
            }
        }
        assertNotNull(command);
        command.addProperty("schemaVersion", 4);
        CatalogBinding source = registeredSources().getFirst();
        assertThrows(IllegalArgumentException.class, () -> target(source, tampered.toString()));
    }

    @Test
    void exhaustedGenerationFailsClosedWithoutWrappingOrUsingTheMigrationFloor() {
        CatalogBinding exhausted = new CatalogBinding(Long.MAX_VALUE, UNRELATED.contentChecksum(),
            UNRELATED.bindingManifestHash());
        assertEquals(exhausted, FlowModule.startupCatalogBinding(exhausted, UNRELATED.contentChecksum(),
            UNRELATED.bindingManifestHash(), UNRELATED.canonicalContent()));
        assertThrows(ArithmeticException.class, () -> FlowModule.startupCatalogBinding(exhausted,
            EVOLUTION.targetChecksum(), UNRELATED.bindingManifestHash(), UNRELATED.canonicalContent()));
    }

    private static CatalogBinding target(CatalogBinding persisted, String content) {
        JsonObject snapshot = JsonParser.parseString(content).getAsJsonObject();
        long generation = persisted == null ? 1L : persisted.generation();
        snapshot.addProperty("generation", generation);
        return FlowModule.startupCatalogBinding(persisted, EVOLUTION.targetChecksum(),
            new ContentHash(snapshot.get("bindingManifestHash").getAsString()), snapshot.toString());
    }

    private static void addRuntimeBinding(RuntimeBindingRegistry runtime) {
        OwnerId owner = OwnerId.of("test");
        var capability = ContractRef.of(owner, CapabilityId.of("runtime"));
        var provider = ContractRef.of(owner, ProviderId.of("runtime"));
        RuntimeSemantics semantics = new RuntimeSemantics(RuntimeSemantics.Effect.PURE, RuntimeSemantics.ThreadMode.CURRENT,
            capability, RuntimeSemantics.Cancellation.NONE, 0L, 0L, 0L, RuntimeSemantics.UnloadPolicy.DRAIN,
            RuntimeSemantics.Retry.NEVER, RuntimeSemantics.Idempotency.INTRINSIC, RuntimeSemantics.Audit.NONE,
            RuntimeSemantics.Confirmation.NONE, RuntimeSemantics.SensitiveData.NONE, RuntimeSemantics.Determinism.DETERMINISTIC,
            Set.of(), Set.of("failed"), Set.of(),
            new RuntimeFailureContract(TypeExpr.named(TypeReference.of("builtin", "string")), Set.of("RUNTIME.FAILURE"),
                Set.of("failed"), RuntimeFailureContract.CommitBoundary.NO_MUTATION), Set.of(), Set.of());
        RuntimeOperationDescriptor operation = new RuntimeOperationDescriptor(capability, ContractRef.of(owner, OperationId.of("runtime")),
            List.of(), semantics);
        runtime.activate(new RuntimeProviderDescriptor(provider, "1.0.0", 0L, 0L, RuntimeSemantics.UnloadPolicy.DRAIN),
            List.of(RuntimeBinding.available(operation, provider, "1.0.0", invocation -> CompletableFuture.completedFuture(RuntimeResult.success()))));
    }

    private static CatalogSnapshot unrelatedCatalog() {
        var runtime = new RuntimeBindingRegistry().snapshot();
        var compilation = new CatalogCompiler(new CatalogVersion(1, 0), CatalogBindingProof.snapshot(runtime)).compile(List.of(), 1L);
        assertTrue(compilation.accepted(), compilation.diagnostics().toString());
        return compilation.snapshot().orElseThrow();
    }

    private static List<CatalogBinding> registeredSources() throws Exception {
        String content;
        try (InputStream input = CoreCatalogEvolution.class.getResourceAsStream("/restudio/resync/migration/core-catalog-evolution-v1.json")) {
            assertNotNull(input);
            content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertEquals(EVOLUTION.registrationHash().canonicalText(), StorageSafety.sha256(content));
        List<CatalogBinding> bindings = new ArrayList<>();
        for (JsonElement value : JsonParser.parseString(content).getAsJsonObject().getAsJsonArray("sourceBindings")) {
            JsonObject source = value.getAsJsonObject();
            bindings.add(new CatalogBinding(source.get("generation").getAsLong(), source.get("catalogChecksum").getAsString(),
                source.get("bindingManifestHash").getAsString()));
        }
        return bindings.stream().sorted().toList();
    }

    private static synchronized String targetContent() throws Exception {
        if (authenticatedTarget == null) {
            String configured = System.getenv("RESYNC_CATALOG_EVOLUTION_TARGET");
            assumeTrue(configured != null && !configured.isBlank(), "An explicit authenticated target snapshot is required");
            Path path = Path.of(configured);
            assertTrue(path.isAbsolute());
            String content = Files.readString(path);
            assertEquals(EVOLUTION.targetChecksum(), CatalogCanonicalizer.checksumForCanonicalContent(content));
            authenticatedTarget = content;
        }
        return authenticatedTarget;
    }

    private static synchronized String rebindTargetContent(long generation) throws Exception {
        if (authenticatedRebindTarget == null) {
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
            authenticatedRebindTarget = content;
        }
        JsonObject snapshot = JsonParser.parseString(authenticatedRebindTarget).getAsJsonObject();
        snapshot.addProperty("generation", generation);
        return snapshot.toString();
    }

    private static synchronized String resourceDeclarationTargetContent(long generation) throws Exception {
        if (authenticatedResourceDeclarationTarget == null) {
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
            authenticatedResourceDeclarationTarget = content;
        }
        JsonObject snapshot = JsonParser.parseString(authenticatedResourceDeclarationTarget).getAsJsonObject();
        snapshot.addProperty("generation", generation);
        return snapshot.toString();
    }
}
