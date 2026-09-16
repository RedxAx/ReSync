package restudio.resync.flow.catalog;

import org.junit.jupiter.api.Test;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.identity.BranchId;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CaseId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.inspector.InspectorFallback;
import restudio.resync.flow.runtime.RuntimeBinding;
import restudio.resync.flow.runtime.RuntimeBindingRegistry;
import restudio.resync.flow.runtime.RuntimeFailureContract;
import restudio.resync.flow.runtime.RuntimeOperationDescriptor;
import restudio.resync.flow.runtime.RuntimeProviderDescriptor;
import restudio.resync.flow.runtime.RuntimeResult;
import restudio.resync.flow.runtime.RuntimeRegistrySnapshot;
import restudio.resync.flow.runtime.RuntimeSemantics;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;

import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogRuntimeActivationTest {
    private static final CatalogVersion CONTRACT = new CatalogVersion(1, 0);
    private static final ServerId SERVER = ServerId.deterministic("catalog-runtime-activation-test");

    @Test
    void oneRecordBindsCatalogRuntimeAndPublicationIdentity() {
        RuntimeRegistrySnapshot runtime = RuntimeRegistrySnapshot.empty();
        CatalogSnapshot catalog = compileEmpty(runtime, 1);
        CatalogCacheKey key = new CatalogCacheKey(SERVER, catalog.generation(), catalog.contentChecksum());
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(
            new CatalogRuntimeActivation.ActivationRecord(catalog, runtime, Optional.of(key)));

        assertSame(catalog, activation.active().catalog());
        assertSame(runtime, activation.active().runtime());
        assertEquals(key, activation.active().publicationKey().orElseThrow());
        assertEquals(runtime.manifest(), activation.active().runtimeManifest());
    }

    @Test
    void mismatchedCatalogAndRuntimeCannotBecomeAnActivationRecord() {
        CatalogSnapshot catalog = compileEmpty(RuntimeRegistrySnapshot.empty(), 1);
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry();
        assertThrows(IllegalArgumentException.class, () -> {
            ContractRef<ProviderId> provider = ContractRef.of(OwnerId.of("test"), ProviderId.of("provider"));
            ContractRef<CapabilityId> capability = ContractRef.of(OwnerId.of("test"), CapabilityId.of("capability"));
            ContractRef<OperationId> operation = ContractRef.of(OwnerId.of("test"), OperationId.of("operation"));
            RuntimeSemantics semantics = new RuntimeSemantics(
                RuntimeSemantics.Effect.PURE,
                RuntimeSemantics.ThreadMode.CURRENT,
                capability,
                RuntimeSemantics.Cancellation.NONE,
                0, 0, 0,
                RuntimeSemantics.UnloadPolicy.DRAIN,
                RuntimeSemantics.Retry.NEVER,
                RuntimeSemantics.Idempotency.INTRINSIC,
                RuntimeSemantics.Audit.NONE,
                RuntimeSemantics.Confirmation.NONE,
                RuntimeSemantics.SensitiveData.NONE,
                RuntimeSemantics.Determinism.DETERMINISTIC,
                Set.of("success"), Set.of("failure"), Set.of(),
                new RuntimeFailureContract(
                    TypeExpr.named(TypeReference.of("test", "failure")),
                    Set.of("RUNTIME.FAILURE"), Set.of("failure"),
                    RuntimeFailureContract.CommitBoundary.NO_MUTATION),
                Set.of(), Set.of());
            RuntimeOperationDescriptor descriptor = new RuntimeOperationDescriptor(capability, operation,
                List.of(new RuntimeOperationDescriptor.Pin("value", RuntimeOperationDescriptor.Direction.INPUT,
                    TypeExpr.named(TypeReference.of("test", "input")))), semantics);
            registry.activate(new RuntimeProviderDescriptor(provider, "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN),
                List.of(RuntimeBinding.available(descriptor, provider, "1.0.0",
                    ignored -> CompletableFuture.completedFuture(RuntimeResult.success()))));
            new CatalogRuntimeActivation.ActivationRecord(catalog, registry.snapshot(), Optional.empty());
        });
    }

    @Test
    void noOpActivationDoesNotAdvanceOrReplaceRecord() {
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry();
        RuntimeRegistrySnapshot runtime = registry.snapshot();
        CatalogSnapshot catalog = compileEmpty(runtime, 1);
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(catalog, runtime);
        RuntimeBindingRegistry.RuntimeReplacement replacement = registry.prepareReplacement(List.of(), List.of());

        CatalogRuntimeActivation.ActivationResult result = activation.activate(catalog, replacement, null);

        assertEquals(CatalogRuntimeActivation.ActivationStatus.NOOP, result.status());
        assertSame(catalog, activation.active().catalog());
        assertSame(runtime, activation.active().runtime());
    }

    @Test
    void semanticDeltaPublishesCatalogAndRuntimeFromOneActivationRecord() {
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry();
        RuntimeRegistrySnapshot runtime = registry.snapshot();
        CatalogSnapshot initial = compileEmpty(runtime, 1);
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(initial, runtime);
        CatalogContribution delta = CatalogContribution.builder(
                OwnerId.of("resync.catalog.delta"), "1.0.0", new CatalogContractRange(CONTRACT, CONTRACT),
                CatalogProvenance.fromText(CatalogProvenance.SourceKind.BUNDLED,
                    "classpath:/nodes/catalog-delta.json", "1.0.0", "test", "resync.catalog.delta"))
            .build();
        CatalogSnapshot candidate = new CatalogCompiler(CONTRACT, CatalogBindingProof.snapshot(runtime))
            .compile(List.of(delta), 2)
            .snapshot()
            .orElseThrow();
        RuntimeBindingRegistry.RuntimeReplacement replacement = registry.prepareReplacement(List.of(), List.of());

        CatalogRuntimeActivation.ActivationResult result = activation.activate(candidate, replacement, null);

        assertEquals(CatalogRuntimeActivation.ActivationStatus.COMMITTED, result.status());
        assertSame(candidate, activation.active().catalog());
        assertSame(runtime, activation.active().runtime());
        assertEquals(candidate.bindingManifestHash(), activation.active().runtime().bindingManifestHash());
        assertNotEquals(initial.contentChecksum(), activation.active().catalog().contentChecksum());
        assertEquals(initial.generation() + 1, activation.active().catalog().generation());
    }

    @Test
    void generationJumpIsRejectedWithoutChangingTheActiveRecord() {
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry();
        RuntimeRegistrySnapshot runtime = registry.snapshot();
        CatalogSnapshot initial = compileEmpty(runtime, 1);
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(initial, runtime);
        CatalogContribution delta = CatalogContribution.builder(
                OwnerId.of("resync.catalog.jump"), "1.0.0", new CatalogContractRange(CONTRACT, CONTRACT),
                CatalogProvenance.fromText(CatalogProvenance.SourceKind.BUNDLED,
                    "classpath:/nodes/catalog-jump.json", "1.0.0", "test", "resync.catalog.jump"))
            .build();
        CatalogSnapshot candidate = new CatalogCompiler(CONTRACT, CatalogBindingProof.snapshot(runtime))
            .compile(List.of(delta), 3)
            .snapshot()
            .orElseThrow();
        RuntimeBindingRegistry.RuntimeReplacement replacement = registry.prepareReplacement(List.of(), List.of());

        assertThrows(IllegalArgumentException.class, () -> activation.stage(candidate, replacement, null));
        assertSame(initial, activation.active().catalog());
        assertSame(runtime, activation.active().runtime());
        replacement.close();
    }

    @Test
    void bootstrapPublishesCatalogAndRuntimeFromOneCommittedReplacement() {
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry();
        CatalogSnapshot catalog = compileEmpty(registry.snapshot(), 1);
        RuntimeBindingRegistry.RuntimeReplacement replacement = registry.prepareReplacement(List.of(), List.of());

        CatalogRuntimeActivation activation = CatalogRuntimeActivation.bootstrap(catalog, replacement, null);

        assertSame(registry.snapshot(), activation.active().runtime());
        assertSame(catalog, activation.active().catalog());
        assertEquals(catalog.bindingManifestHash(), activation.active().runtime().bindingManifestHash());
    }

    @Test
    void inheritedPublicationKeyIsValidatedBeforeAReplacementCanCommit() {
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry();
        RuntimeRegistrySnapshot runtime = registry.snapshot();
        CatalogSnapshot catalog = compileEmpty(runtime, 1);
        CatalogCacheKey key = new CatalogCacheKey(SERVER, catalog.generation(), catalog.contentChecksum());
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(
            new CatalogRuntimeActivation.ActivationRecord(catalog, runtime, Optional.of(key)));
        CatalogSnapshot candidate = new CatalogCompiler(new CatalogVersion(2, 0), CatalogBindingProof.snapshot(runtime))
            .compile(List.of(), 2)
            .snapshot()
            .orElseThrow();
        RuntimeBindingRegistry.RuntimeReplacement replacement = registry.prepareReplacement(List.of(), List.of());

        assertThrows(IllegalArgumentException.class, () -> activation.stage(candidate, replacement, null));
        assertSame(catalog, activation.active().catalog());
        assertSame(runtime, activation.active().runtime());
        replacement.close();
    }

    @Test
    void publicationKeyWithWrongGenerationCannotReplaceTheActiveRecord() {
        RuntimeRegistrySnapshot runtime = RuntimeRegistrySnapshot.empty();
        CatalogSnapshot catalog = compileEmpty(runtime, 1);
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(catalog, runtime);
        CatalogRuntimeActivation.ActivationRecord before = activation.active();
        CatalogCacheKey wrongGeneration = new CatalogCacheKey(SERVER, catalog.generation() + 1, catalog.contentChecksum());

        assertThrows(IllegalArgumentException.class, () -> activation.publishPublicationKey(wrongGeneration));
        assertSame(before, activation.active());
        assertEquals(Optional.empty(), activation.activePublicationKey());
    }

    @Test
    void publicationKeyCannotChangeServerIdentityAfterItIsEstablished() {
        RuntimeRegistrySnapshot runtime = RuntimeRegistrySnapshot.empty();
        CatalogSnapshot catalog = compileEmpty(runtime, 1);
        CatalogCacheKey initialKey = new CatalogCacheKey(SERVER, catalog.generation(), catalog.contentChecksum());
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(
            new CatalogRuntimeActivation.ActivationRecord(catalog, runtime, Optional.of(initialKey)));
        CatalogCacheKey otherServer = new CatalogCacheKey(ServerId.deterministic("other-server"),
            catalog.generation(), catalog.contentChecksum());

        assertThrows(IllegalArgumentException.class, () -> activation.publishPublicationKey(otherServer));
        assertEquals(Optional.of(initialKey), activation.activePublicationKey());
    }

    @Test
    void catalogReplacementMayAdvanceBindingWithinStablePublicationIdentity() {
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry();
        RuntimeRegistrySnapshot runtime = registry.snapshot();
        CatalogSnapshot initial = compileEmpty(runtime, 1);
        CatalogCacheKey initialKey = new CatalogCacheKey(SERVER,
            new CatalogBinding(initial.generation(), initial.contentChecksum(), initial.bindingManifestHash()),
            CatalogProjectionVersion.current());
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(
            new CatalogRuntimeActivation.ActivationRecord(initial, runtime, Optional.of(initialKey)));
        CatalogSnapshot candidate = compileEmpty(runtime, 2);
        RuntimeBindingRegistry.RuntimeReplacement replacement = registry.prepareReplacement(List.of(), List.of());

        CatalogCacheKey candidateKey = new CatalogCacheKey(SERVER,
            new CatalogBinding(candidate.generation(), candidate.contentChecksum(), candidate.bindingManifestHash()),
            CatalogProjectionVersion.current());
        assertNotEquals(initialKey.catalogBinding(), candidateKey.catalogBinding());

        CatalogRuntimeActivation.ActivationResult result = activation.activate(candidate, replacement, candidateKey);

        assertEquals(CatalogRuntimeActivation.ActivationStatus.COMMITTED, result.status());
        assertSame(candidate, activation.catalog());
        assertEquals(Optional.of(candidateKey), activation.activePublicationKey());
    }

    @Test
    void publicationKeyWithWrongProjectionContractIsRejected() {
        RuntimeRegistrySnapshot runtime = RuntimeRegistrySnapshot.empty();
        CatalogSnapshot catalog = compileEmpty(runtime, 1);
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(catalog, runtime);
        CatalogCacheKey wrongContract = new CatalogCacheKey(SERVER, catalog.generation(), catalog.contentChecksum(),
            new CatalogProjectionVersion(CatalogProjectionVersion.current().generation() + 1, 0));

        assertThrows(IllegalArgumentException.class, () -> activation.publishPublicationKey(wrongContract));
        assertEquals(Optional.empty(), activation.activePublicationKey());
    }

    @Test
    void publicationKeyWaitsForTheActivationCommitAndBindsTheCommittedRecord() throws Exception {
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry();
        RuntimeRegistrySnapshot runtime = registry.snapshot();
        CatalogSnapshot catalog = compileEmpty(runtime, 1);
        CatalogSnapshot candidate = compileEmpty(runtime, 2);
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(catalog, runtime);
        RuntimeBindingRegistry.RuntimeReplacement replacement = registry.prepareReplacement(List.of(), List.of());
        CatalogRuntimeActivation.ActivationTransaction transaction = activation.stage(candidate, replacement, null);
        CatalogCacheKey key = new CatalogCacheKey(SERVER, candidate.generation(), candidate.contentChecksum());
        CompletableFuture<CatalogRuntimeActivation.ActivationRecord> publication = new CompletableFuture<>();
        CountDownLatch publisherReady = new CountDownLatch(1);

        synchronized (activation) {
            Thread publisher = Thread.startVirtualThread(() -> {
                publisherReady.countDown();
                try {
                    publication.complete(activation.publishPublicationKey(key));
                } catch (RuntimeException | Error failure) {
                    publication.completeExceptionally(failure);
                }
            });
            assertTrue(publisherReady.await(5, TimeUnit.SECONDS));
            long waitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (publisher.getState() != Thread.State.BLOCKED && !publication.isDone()
                && System.nanoTime() < waitDeadline) {
                Thread.onSpinWait();
            }
            assertFalse(publication.isDone());
            assertTrue(transaction.commit().committed());
        }

        CatalogRuntimeActivation.ActivationRecord published = publication.get(5, TimeUnit.SECONDS);
        assertSame(candidate, published.catalog());
        assertEquals(Optional.of(key), published.publicationKey());
        assertSame(published, activation.active());
    }

    @Test
    void publicationFenceRequiresTheExactActiveRecordAndPublicationKey() {
        RuntimeRegistrySnapshot runtime = RuntimeRegistrySnapshot.empty();
        CatalogSnapshot catalog = compileEmpty(runtime, 1);
        CatalogCacheKey key = new CatalogCacheKey(SERVER, catalog.generation(), catalog.contentChecksum());
        CatalogRuntimeActivation.ActivationRecord initial = new CatalogRuntimeActivation.ActivationRecord(
            catalog, runtime, Optional.of(key));
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(initial);

        assertTrue(activation.withPublicationFence(initial, key, () -> true));
        CatalogRuntimeActivation.ActivationRecord equivalent = new CatalogRuntimeActivation.ActivationRecord(
            catalog, runtime, Optional.of(key));
        assertFalse(activation.withPublicationFence(equivalent, key, () -> true));
        CatalogCacheKey wrongKey = new CatalogCacheKey(ServerId.deterministic("wrong-server"),
            catalog.generation(), catalog.contentChecksum());
        assertFalse(activation.withPublicationFence(initial, wrongKey, () -> true));
    }

    @Test
    void displayOnlyPinRenameDoesNotRequireSchemaMigration() {
        PinFixture fixture = pinFixture();
        CatalogNodeDescriptor renamed = node(1, "Renamed Node", List.of(
            pin("value", CatalogNodeDescriptor.Direction.INPUT, STRING, "Renamed Value"),
            pin("result", CatalogNodeDescriptor.Direction.OUTPUT, STRING, "Result")));
        CatalogSnapshot candidate = compileNode(renamed, 2, List.of(), fixture.runtime().bindingManifestHash());
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(fixture.catalog(), fixture.runtime());
        RuntimeBindingRegistry.RuntimeReplacement replacement = fixture.registry().prepareReplacement(List.of(), List.of());

        CatalogRuntimeActivation.ActivationResult result = activation.activate(candidate, replacement, null);

        assertEquals(CatalogRuntimeActivation.ActivationStatus.COMMITTED, result.status());
        assertSame(candidate, activation.catalog());
        assertEquals(1, candidate.definitions().getFirst().descriptor().schemaVersion());
    }

    @Test
    void semanticPinChangeWithoutSchemaAdvancePreservesThePriorActivation() {
        PinFixture fixture = pinFixture();
        CatalogNodeDescriptor changed = node(1, "Node", List.of(
            pin("value", CatalogNodeDescriptor.Direction.OUTPUT, STRING, "Value"),
            pin("result", CatalogNodeDescriptor.Direction.INPUT, STRING, "Result")));
        CatalogSnapshot candidate = compileNode(changed, 2, List.of(), fixture.runtime().bindingManifestHash());
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(fixture.catalog(), fixture.runtime());
        CatalogRuntimeActivation.ActivationRecord before = activation.active();
        RuntimeBindingRegistry.RuntimeReplacement replacement = fixture.registry().prepareReplacement(List.of(), List.of());

        assertThrows(IllegalArgumentException.class, () -> activation.stage(candidate, replacement, null));
        assertSame(before, activation.active());
        replacement.close();
    }

    @Test
    void exactRestoreReinstatesThePublishedTransactionBaselineSnapshot() {
        PinFixture fixture = pinFixture();
        CatalogNodeDescriptor renamed = node(1, "Renamed Node", List.of(
            pin("value", CatalogNodeDescriptor.Direction.INPUT, STRING, "Value"),
            pin("result", CatalogNodeDescriptor.Direction.OUTPUT, STRING, "Result")));
        CatalogSnapshot candidate = compileNode(renamed, 2, List.of(), fixture.runtime().bindingManifestHash());
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(fixture.catalog(), fixture.runtime());
        RuntimeBindingRegistry.RuntimeReplacement forwardReplacement = fixture.registry().prepareReplacement(List.of(), List.of());
        CatalogRuntimeActivation.ActivationTransaction published = activation.stage(candidate, forwardReplacement, null);

        assertEquals(CatalogRuntimeActivation.ActivationStatus.COMMITTED, published.commit().status());
        RuntimeBindingRegistry.RuntimeReplacement normalReplacement = fixture.registry().prepareReplacement(List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> activation.stage(fixture.catalog(), normalReplacement, null));
        normalReplacement.close();

        RuntimeBindingRegistry.RuntimeReplacement restoreReplacement = fixture.registry().prepareReplacement(List.of(), List.of());
        CatalogRuntimeActivation.ActivationTransaction restore = activation.stageExactRestore(published, restoreReplacement);
        assertEquals(CatalogRuntimeActivation.ActivationStatus.COMMITTED, restore.commit().status());
        assertSame(fixture.catalog(), activation.catalog());
    }

    @Test
    void schemaAdvanceWithoutACompletePinMigrationPreservesThePriorActivation() {
        PinFixture fixture = pinFixture();
        CatalogNodeDescriptor changed = node(2, "Node", List.of(
            pin("renamed-value", CatalogNodeDescriptor.Direction.INPUT, BOOLEAN, "Value"),
            pin("result", CatalogNodeDescriptor.Direction.OUTPUT, STRING, "Result")));
        CatalogSnapshot candidate = compileNode(changed, 2, List.of(), fixture.runtime().bindingManifestHash());
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(fixture.catalog(), fixture.runtime());
        CatalogRuntimeActivation.ActivationRecord before = activation.active();
        RuntimeBindingRegistry.RuntimeReplacement replacement = fixture.registry().prepareReplacement(List.of(), List.of());

        assertThrows(IllegalArgumentException.class, () -> activation.stage(candidate, replacement, null));
        assertSame(before, activation.active());
        replacement.close();
    }

    @Test
    void restudioResyncNodeRemovalRequiresCompleteMigration() {
        PinFixture fixture = pinFixture(OwnerId.of("restudio.resync"));
        CatalogSnapshot candidate = compileEmpty(fixture.runtime(), 2);
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(fixture.catalog(), fixture.runtime());
        RuntimeBindingRegistry.RuntimeReplacement replacement = fixture.registry().prepareReplacement(List.of(), List.of());

        assertThrows(IllegalArgumentException.class, () -> activation.stage(candidate, replacement, null));
        assertSame(fixture.catalog(), activation.catalog());
        replacement.close();
    }

    @Test
    void extensionNodeRemovalDoesNotRequireMigration() {
        PinFixture fixture = pinFixture();
        CatalogSnapshot candidate = compileEmpty(fixture.runtime(), 2);
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(fixture.catalog(), fixture.runtime());
        RuntimeBindingRegistry.RuntimeReplacement replacement = fixture.registry().prepareReplacement(List.of(), List.of());

        CatalogRuntimeActivation.ActivationResult result = activation.activate(candidate, replacement, null);

        assertEquals(CatalogRuntimeActivation.ActivationStatus.COMMITTED, result.status());
        assertSame(candidate, activation.catalog());
    }

    @Test
    void completePinMigrationAllowsAnOrderedTypedSignatureChange() {
        PinFixture fixture = pinFixture();
        CatalogNodeDescriptor changed = node(2, "Node", List.of(
            pin("result", CatalogNodeDescriptor.Direction.OUTPUT, STRING, "Result"),
            pin("renamed-value", CatalogNodeDescriptor.Direction.INPUT, BOOLEAN, "Value")));
        CatalogMigrationEdge migration = new CatalogMigrationEdge(
            CapabilityId.of("pin-migration"), PIN_OWNER, PIN_NODE, 1, 2,
            CatalogMigrationEdge.Kind.DECLARATIVE, List.of(PIN_NODE.value()),
            CatalogMigrationEdge.ConnectionPolicy.REMAP, List.of(), List.of(
                new CatalogMigrationEdge.PinMapping(PIN_OWNER, PIN_NODE, 1, 2,
                    PinId.of("value"), PinId.of("renamed-value"), CatalogNodeDescriptor.Direction.INPUT),
                new CatalogMigrationEdge.PinMapping(PIN_OWNER, PIN_NODE, 1, 2,
                    PinId.of("result"), PinId.of("result"), CatalogNodeDescriptor.Direction.OUTPUT, true)));
        CatalogSnapshot candidate = compileNode(changed, 2, List.of(migration), fixture.runtime().bindingManifestHash());
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(fixture.catalog(), fixture.runtime());
        RuntimeBindingRegistry.RuntimeReplacement replacement = fixture.registry().prepareReplacement(List.of(), List.of());

        CatalogRuntimeActivation.ActivationResult result = activation.activate(candidate, replacement, null);

        assertEquals(CatalogRuntimeActivation.ActivationStatus.COMMITTED, result.status());
        assertSame(candidate, activation.catalog());
    }

    @Test
    void completePinMigrationPreservesCaseSensitiveRawSourceIdentity() {
        PinFixture fixture = pinFixture();
        CatalogNodeDescriptor changed = node(2, "Node", List.of(
            pin("renamed-value", CatalogNodeDescriptor.Direction.INPUT, BOOLEAN, "Value"),
            pin("result", CatalogNodeDescriptor.Direction.OUTPUT, STRING, "Result")));
        CatalogMigrationEdge migration = new CatalogMigrationEdge(
            CapabilityId.of("raw-pin-migration"), PIN_OWNER, PIN_NODE, 1, 2,
            CatalogMigrationEdge.Kind.DECLARATIVE, List.of(PIN_NODE.value()),
            CatalogMigrationEdge.ConnectionPolicy.REMAP, List.of(), List.of(
                new CatalogMigrationEdge.PinMapping(PIN_OWNER, PIN_NODE, 1, 2,
                    CatalogMigrationEdge.LegacyPinId.of("listA"), PinId.of("renamed-value"),
                    CatalogNodeDescriptor.Direction.INPUT),
                new CatalogMigrationEdge.PinMapping(PIN_OWNER, PIN_NODE, 1, 2,
                    CatalogMigrationEdge.LegacyPinId.of("result"), PinId.of("result"),
                    CatalogNodeDescriptor.Direction.OUTPUT, true)));
        CatalogSnapshot candidate = compileNode(changed, 2, List.of(migration), fixture.runtime().bindingManifestHash());
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(fixture.catalog(), fixture.runtime());
        RuntimeBindingRegistry.RuntimeReplacement replacement = fixture.registry().prepareReplacement(List.of(), List.of());

        CatalogRuntimeActivation.ActivationResult result = activation.activate(candidate, replacement, null);

        assertEquals(CatalogRuntimeActivation.ActivationStatus.COMMITTED, result.status());
        assertSame(candidate, activation.catalog());
    }

    @Test
    void completePinMigrationAllowsTheSameRawSourceAcrossDirections() {
        PinFixture fixture = pinFixture();
        CatalogNodeDescriptor changed = node(2, "Node", List.of(
            pin("renamed-value", CatalogNodeDescriptor.Direction.INPUT, BOOLEAN, "Value"),
            pin("renamed-result", CatalogNodeDescriptor.Direction.OUTPUT, STRING, "Result")));
        CatalogMigrationEdge migration = new CatalogMigrationEdge(
            CapabilityId.of("directional-raw-pin-migration"), PIN_OWNER, PIN_NODE, 1, 2,
            CatalogMigrationEdge.Kind.DECLARATIVE, List.of(PIN_NODE.value()),
            CatalogMigrationEdge.ConnectionPolicy.REMAP, List.of(), List.of(
                new CatalogMigrationEdge.PinMapping(PIN_OWNER, PIN_NODE, 1, 2,
                    CatalogMigrationEdge.LegacyPinId.of("flow"), PinId.of("renamed-value"),
                    CatalogNodeDescriptor.Direction.INPUT),
                new CatalogMigrationEdge.PinMapping(PIN_OWNER, PIN_NODE, 1, 2,
                    CatalogMigrationEdge.LegacyPinId.of("flow"), PinId.of("renamed-result"),
                    CatalogNodeDescriptor.Direction.OUTPUT)));
        CatalogSnapshot candidate = compileNode(changed, 2, List.of(migration), fixture.runtime().bindingManifestHash());
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(fixture.catalog(), fixture.runtime());
        RuntimeBindingRegistry.RuntimeReplacement replacement = fixture.registry().prepareReplacement(List.of(), List.of());

        CatalogRuntimeActivation.ActivationResult result = activation.activate(candidate, replacement, null);

        assertEquals(CatalogRuntimeActivation.ActivationStatus.COMMITTED, result.status());
        assertSame(candidate, activation.catalog());
    }

    @Test
    void sourceCompleteIdentityMappingAllowsOnlyAdditiveOutputs() {
        PinFixture fixture = pinFixture();
        List<CatalogNodeDescriptor.Pin> pins = new ArrayList<>(fixture.catalog().definitions().getFirst().descriptor().pins());
        pins.add(pin("added", CatalogNodeDescriptor.Direction.OUTPUT, STRING, "Added"));
        CatalogSnapshot candidate = compileNode(node(2, "Node", pins), 2, List.of(identityMigration()),
            fixture.runtime().bindingManifestHash());
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(fixture.catalog(), fixture.runtime());
        RuntimeBindingRegistry.RuntimeReplacement replacement = fixture.registry().prepareReplacement(List.of(), List.of());

        assertEquals(CatalogRuntimeActivation.ActivationStatus.COMMITTED, activation.activate(candidate, replacement, null).status());
        assertSame(candidate, activation.catalog());
    }

    @Test
    void additiveOutputsCannotHideChangedPinsExtraInputsOrMissingMigration() {
        for (String defect : List.of("input", "type", "order", "missing", "incomplete", "version", "source")) {
            PinFixture fixture = pinFixture();
            List<CatalogNodeDescriptor.Pin> pins = new ArrayList<>(fixture.catalog().definitions().getFirst().descriptor().pins());
            pins.add(pin("added", defect.equals("input") ? CatalogNodeDescriptor.Direction.INPUT
                : CatalogNodeDescriptor.Direction.OUTPUT, STRING, "Added"));
            if (defect.equals("type")) {
                pins.set(0, pin("value", CatalogNodeDescriptor.Direction.INPUT, BOOLEAN, "Value"));
            } else if (defect.equals("order")) {
                CatalogNodeDescriptor.Pin first = pins.removeFirst();
                pins.add(1, first);
            }
            CatalogMigrationEdge mapping = identityMigration();
            if (defect.equals("incomplete") || defect.equals("source")) {
                List<CatalogMigrationEdge.PinMapping> mappings = defect.equals("incomplete")
                    ? List.of(mapping.pinMappings().getFirst())
                    : List.of(new CatalogMigrationEdge.PinMapping(PIN_OWNER, PIN_NODE, 1, 2,
                        PinId.of("unknown"), PinId.of("value"), CatalogNodeDescriptor.Direction.INPUT),
                        mapping.pinMappings().getLast());
                mapping = new CatalogMigrationEdge(mapping.id(), PIN_OWNER, PIN_NODE, 1, 2,
                    mapping.kind(), mapping.touchedIds(), mapping.connectionPolicy(), mapping.quarantineCodes(), mappings);
            }
            List<CatalogMigrationEdge> mappings = defect.equals("missing") || defect.equals("version") ? List.of() : List.of(mapping);
            CatalogSnapshot candidate = compileNode(node(defect.equals("version") ? 1 : 2, "Node", pins), 2, mappings,
                fixture.runtime().bindingManifestHash());
            CatalogRuntimeActivation activation = new CatalogRuntimeActivation(fixture.catalog(), fixture.runtime());
            RuntimeBindingRegistry.RuntimeReplacement replacement = fixture.registry().prepareReplacement(List.of(), List.of());
            assertThrows(IllegalArgumentException.class, () -> activation.stage(candidate, replacement, null), defect);
            assertSame(fixture.catalog(), activation.catalog());
            replacement.close();
        }
    }

    private static CatalogMigrationEdge identityMigration() {
        return new CatalogMigrationEdge(CapabilityId.of("identity-migration.v1-v2"), PIN_OWNER, PIN_NODE, 1, 2,
            CatalogMigrationEdge.Kind.DECLARATIVE, List.of(PIN_NODE.value()), CatalogMigrationEdge.ConnectionPolicy.REMAP,
            List.of(), List.of(
                new CatalogMigrationEdge.PinMapping(PIN_OWNER, PIN_NODE, 1, 2,
                    PinId.of("value"), PinId.of("value"), CatalogNodeDescriptor.Direction.INPUT, true),
                new CatalogMigrationEdge.PinMapping(PIN_OWNER, PIN_NODE, 1, 2,
                    PinId.of("result"), PinId.of("result"), CatalogNodeDescriptor.Direction.OUTPUT, true)));
    }

    @Test
    void unscopedPinMigrationCannotReplaceAnExactNodeActivation() {
        PinFixture fixture = pinFixture();
        CatalogNodeDescriptor changed = node(2, "Node", List.of(
            pin("renamed-value", CatalogNodeDescriptor.Direction.INPUT, BOOLEAN, "Value"),
            pin("result", CatalogNodeDescriptor.Direction.OUTPUT, STRING, "Result")));
        CatalogMigrationEdge migration = new CatalogMigrationEdge(
            CapabilityId.of("unscoped-pin-migration"), 1, 2,
            CatalogMigrationEdge.Kind.DECLARATIVE, List.of(PIN_NODE.value()),
            CatalogMigrationEdge.ConnectionPolicy.REMAP, List.of(), List.of(
                new CatalogMigrationEdge.PinMapping(PIN_OWNER, PIN_NODE, 1, 2,
                    PinId.of("value"), PinId.of("renamed-value"), CatalogNodeDescriptor.Direction.INPUT),
                new CatalogMigrationEdge.PinMapping(PIN_OWNER, PIN_NODE, 1, 2,
                    PinId.of("result"), PinId.of("result"), CatalogNodeDescriptor.Direction.OUTPUT, true)));
        CatalogSnapshot candidate = compileNode(changed, 2, List.of(migration), fixture.runtime().bindingManifestHash());
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(fixture.catalog(), fixture.runtime());
        CatalogRuntimeActivation.ActivationRecord before = activation.active();
        RuntimeBindingRegistry.RuntimeReplacement replacement = fixture.registry().prepareReplacement(List.of(), List.of());

        assertThrows(IllegalArgumentException.class, () -> activation.stage(candidate, replacement, null));
        assertSame(before, activation.active());
        replacement.close();
    }

    private static CatalogSnapshot compileEmpty(RuntimeRegistrySnapshot runtime, long generation) {
        return new CatalogCompiler(CONTRACT, CatalogBindingProof.snapshot(runtime))
            .compile(List.of(), generation)
            .snapshot()
            .orElseThrow();
    }

    private static PinFixture pinFixture() {
        return pinFixture(PIN_OWNER);
    }

    private static PinFixture pinFixture(OwnerId owner) {
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry();
        CatalogNodeDescriptor initialNode = node(owner, 1, "Node", List.of(
            pin(owner, "value", CatalogNodeDescriptor.Direction.INPUT, STRING, "Value"),
            pin(owner, "result", CatalogNodeDescriptor.Direction.OUTPUT, STRING, "Result")));
        RuntimeOperationDescriptor requirement = requirement(owner, initialNode);
        ContractRef<ProviderId> provider = ContractRef.of(owner, ProviderId.of("provider"));
        registry.activate(new RuntimeProviderDescriptor(provider, "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN),
            List.of(RuntimeBinding.available(requirement, provider, "1.0.0",
                ignored -> CompletableFuture.completedFuture(RuntimeResult.success()))));
        RuntimeRegistrySnapshot runtime = registry.snapshot();
        CatalogSnapshot catalog = compileNode(owner, initialNode, 1, List.of(), runtime.bindingManifestHash());
        return new PinFixture(registry, runtime, catalog);
    }

    private static CatalogSnapshot compileNode(
        CatalogNodeDescriptor node,
        long generation,
        List<CatalogMigrationEdge> migrations,
        ContentHash bindingManifestHash
    ) {
        return compileNode(PIN_OWNER, node, generation, migrations, bindingManifestHash);
    }

    private static CatalogSnapshot compileNode(
        OwnerId owner,
        CatalogNodeDescriptor node,
        long generation,
        List<CatalogMigrationEdge> migrations,
        ContentHash bindingManifestHash
    ) {
        RuntimeOperationDescriptor requirement = requirement(owner, node);
        CatalogContribution contribution = CatalogContribution.builder(owner, "1.0.0",
                new CatalogContractRange(CONTRACT, CONTRACT),
                CatalogProvenance.fromText(CatalogProvenance.SourceKind.BUNDLED,
                    "classpath:/nodes/catalog-runtime-activation.json", "1.0.0", "test", owner.value()))
            .categories(List.of(new CatalogCategoryDescriptor(CapabilityId.of("flow"), "Flow",
                "Flow nodes used by activation tests.", 1)))
            .capabilities(List.of(
                new CatalogCapabilityDescriptor(CapabilityId.of("execute"), 1, false,
                    InspectorFallback.GENERIC),
                new CatalogCapabilityDescriptor(CapabilityId.of("generic-editor"), 1, false,
                    InspectorFallback.READ_ONLY_FIELD)))
            .definitions(List.of(node))
            .runtimeRequirements(List.of(requirement))
            .migrations(migrations)
            .build();
        CatalogBindingProof proof = CatalogBindingProof.fixed(
            Map.of(requirement.key(), requirement.executionFingerprint()), bindingManifestHash);
        return new CatalogCompiler(CONTRACT, proof)
            .compile(List.of(contribution), generation)
            .snapshot()
            .orElseThrow();
    }

    private static CatalogNodeDescriptor node(int schemaVersion, String displayName, List<CatalogNodeDescriptor.Pin> pins) {
        return node(PIN_OWNER, schemaVersion, displayName, pins);
    }

    private static CatalogNodeDescriptor node(OwnerId owner, int schemaVersion, String displayName,
                                              List<CatalogNodeDescriptor.Pin> pins) {
        ContractRef<CapabilityId> handler = ContractRef.of(owner, CapabilityId.of("execute"));
        return CatalogNodeDescriptor.builder(PIN_NODE)
            .schemaVersion(schemaVersion)
            .displayName(displayName)
            .description("A node used to verify runtime activation pin compatibility.")
            .category(ContractRef.of(owner, CapabilityId.of("flow")))
            .pins(pins)
            .branches(List.of(new CatalogNodeDescriptor.Branch(BranchId.of("failed"), "Failed",
                "The operation failed.", List.of(new CatalogNodeDescriptor.Case(CaseId.of("failure"),
                    "Failure", "The operation reported a failure.")))))
            .handler(new CatalogNodeDescriptor.Handler(handler, ContractRef.of(owner, OperationId.of("run"))))
            .semantics(semantics(owner))
            .requiredCapabilities(Set.of(handler))
            .build();
    }

    private static CatalogNodeDescriptor.Pin pin(
        String id,
        CatalogNodeDescriptor.Direction direction,
        TypeExpr type,
        String displayName
    ) {
        return pin(PIN_OWNER, id, direction, type, displayName);
    }

    private static CatalogNodeDescriptor.Pin pin(
        OwnerId owner,
        String id,
        CatalogNodeDescriptor.Direction direction,
        TypeExpr type,
        String displayName
    ) {
        return new CatalogNodeDescriptor.Pin(PinId.of(id), direction, type, displayName,
            "A pin used by the activation test.", CatalogNodeDescriptor.Requirement.REQUIRED, null,
            ContractRef.of(owner, CapabilityId.of("generic-editor")), null, null, null);
    }

    private static RuntimeOperationDescriptor requirement(CatalogNodeDescriptor node) {
        return requirement(PIN_OWNER, node);
    }

    private static RuntimeOperationDescriptor requirement(OwnerId owner, CatalogNodeDescriptor node) {
        return new RuntimeOperationDescriptor(ContractRef.of(owner, CapabilityId.of("execute")),
            ContractRef.of(owner, OperationId.of("run")),
            node.pins().stream().map(pin -> new RuntimeOperationDescriptor.Pin(pin.id(),
                pin.direction() == CatalogNodeDescriptor.Direction.INPUT
                    ? RuntimeOperationDescriptor.Direction.INPUT : RuntimeOperationDescriptor.Direction.OUTPUT,
                pin.type())).toList(), semantics(owner));
    }

    private static RuntimeSemantics semantics() {
        return semantics(PIN_OWNER);
    }

    private static RuntimeSemantics semantics(OwnerId owner) {
        return new RuntimeSemantics(RuntimeSemantics.Effect.PURE, RuntimeSemantics.ThreadMode.CURRENT,
            ContractRef.of(owner, CapabilityId.of("authorize")), RuntimeSemantics.Cancellation.NONE, 0, 0, 0,
            RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER,
            RuntimeSemantics.Idempotency.INTRINSIC, RuntimeSemantics.Audit.NONE,
            RuntimeSemantics.Confirmation.NONE, RuntimeSemantics.SensitiveData.NONE,
            RuntimeSemantics.Determinism.DETERMINISTIC, Set.of(), Set.of("failed"), Set.of(),
            new RuntimeFailureContract(STRING, Set.of("RUNTIME.FAILURE"), Set.of("failed"),
                RuntimeFailureContract.CommitBoundary.NO_MUTATION), Set.of(), Set.of());
    }

    private record PinFixture(RuntimeBindingRegistry registry, RuntimeRegistrySnapshot runtime, CatalogSnapshot catalog) {
    }

    private static final OwnerId PIN_OWNER = OwnerId.of("resync.catalog.activation");
    private static final NodeId PIN_NODE = NodeId.of("pin-node");
    private static final TypeExpr STRING = TypeExpr.named(TypeReference.of("builtin", "string"));
    private static final TypeExpr BOOLEAN = TypeExpr.named(TypeReference.of("builtin", "boolean"));
}
