package restudio.resync.flow.catalog;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.identity.BranchId;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.CaseId;
import restudio.resync.flow.identity.LocalId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.inspector.InspectorFallback;
import restudio.resync.flow.inspector.InspectorOptionSource;
import restudio.resync.flow.inspector.OptionQuerySchemaV1;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.runtime.RuntimeFailureContract;
import restudio.resync.flow.runtime.RuntimeBinding;
import restudio.resync.flow.runtime.RuntimeBindingRegistry;
import restudio.resync.flow.runtime.RuntimeBindingKey;
import restudio.resync.flow.runtime.RuntimeOperationDescriptor;
import restudio.resync.flow.runtime.RuntimeProviderDescriptor;
import restudio.resync.flow.runtime.RuntimeResult;
import restudio.resync.flow.runtime.RuntimeSemantics.UnloadPolicy;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.runtime.RuntimeSemantics;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogCompilerTest {
    private static final CatalogVersion CONTRACT = new CatalogVersion(1, 0);
    private static final CatalogContractRange RANGE = new CatalogContractRange(CONTRACT, CONTRACT);
    private static final TypeExpr STRING = TypeExpr.named(TypeReference.of("builtin", "string"));
    private static final TypeExpr BOOLEAN = TypeExpr.named(TypeReference.of("builtin", "boolean"));
    private static final ContentHash TEST_MANIFEST_HASH = new ContentHash("f".repeat(64));

    @Test
    void canonicalCompilationDoesNotDependOnContributionOrDescriptorOrder() {
        CatalogContribution first = contribution(OwnerId.of("resync.alpha"), NodeId.of("alpha-node"));
        CatalogContribution second = contribution(OwnerId.of("resync.beta"), NodeId.of("beta-node"));
        CatalogCompiler compiler = compilerFor(first, second);

        CatalogSnapshot left = compiler.compile(List.of(first, second), 4).snapshot().orElseThrow();
        CatalogSnapshot right = compiler.compile(List.of(second, first), 9).snapshot().orElseThrow();

        assertEquals(left.contentChecksum(), right.contentChecksum());
        assertEquals(left.bindingManifestHash(), right.bindingManifestHash());
        assertNotEquals(left.canonicalContent(), right.canonicalContent());
        assertTrue(left.canonicalContent().contains("\"generation\":4"));
        assertTrue(right.canonicalContent().contains("\"generation\":9"));
        assertEquals(List.of(ref(OwnerId.of("resync.alpha"), NodeId.of("alpha-node")), ref(OwnerId.of("resync.beta"), NodeId.of("beta-node"))), left.definitions().stream().map(CatalogOwned::key).toList());
        assertThrows(UnsupportedOperationException.class, () -> left.definitions().add(null));
    }

    @Test
    void rejectsCollisionsAndUnresolvedCapabilitiesAsOneContribution() {
        CatalogNodeDescriptor duplicate = node(NodeId.of("same-node"), ref(OwnerId.of("resync.good"), CapabilityId.of("flow-execute")));
        CatalogContribution collision = CatalogContribution.builder(OwnerId.of("resync.good"), "1.0.0", RANGE, provenance(OwnerId.of("resync.collision")))
            .categories(List.of(category()))
            .capabilities(capabilities())
            .definitions(List.of(duplicate, duplicate))
            .build();
        CatalogCompilationResult collisionResult = new CatalogCompiler(CONTRACT).compile(List.of(collision), 1);

        assertFalse(collisionResult.accepted());
        assertTrue(collisionResult.diagnostics().stream().anyMatch(value -> value.code().equals("CATALOG.IDENTITY_COLLISION")));
        assertTrue(collisionResult.diagnostics().stream().anyMatch(value -> value.code().equals("CATALOG.CONTRIBUTION_REJECTED")));

        CatalogNodeDescriptor unresolved = node(NodeId.of("unresolved-node"), ref(OwnerId.of("resync.good"), CapabilityId.of("missing-handler")));
        CatalogContribution broken = CatalogContribution.builder(OwnerId.of("resync.good"), "1.0.0", RANGE, provenance(OwnerId.of("resync.unresolved")))
            .categories(List.of(category()))
            .capabilities(capabilities())
            .definitions(List.of(unresolved))
            .build();
        CatalogCompilationResult brokenResult = new CatalogCompiler(CONTRACT).compile(List.of(broken), 1);

        assertFalse(brokenResult.accepted());
        assertTrue(brokenResult.diagnostics().stream().anyMatch(value -> value.code().equals("CATALOG.BINDING_MISSING")));
    }

    @Test
    void rejectsUnsatisfiedDependencies() {
        CatalogContribution base = contribution(OwnerId.of("resync.base"), NodeId.of("base-node"));
        CatalogContribution dependent = CatalogContribution.builder(OwnerId.of("resync.dependent"), "1.0.0", RANGE, provenance(OwnerId.of("resync.dependent")))
            .dependencies(List.of(new CatalogDependency(OwnerId.of("resync.base"), ">=2.0.0 <3.0.0", false)))
            .categories(List.of(category()))
            .capabilities(capabilities())
            .definitions(List.of(node(NodeId.of("dependent-node"), ref(OwnerId.of("resync.dependent"), CapabilityId.of("flow-execute")))))
            .build();

        CatalogCompilationResult result = new CatalogCompiler(CONTRACT).compile(List.of(dependent, base), 1);

        assertFalse(result.accepted());
        assertTrue(result.diagnostics().stream().anyMatch(value -> value.code().equals("CATALOG.DEPENDENCY_UNSATISFIED")));
    }

    @Test
    void rejectedCompilationDoesNotProduceACandidate() {
        CatalogContribution good = contribution(OwnerId.of("resync.good"), NodeId.of("good-node"));
        CatalogCompiler compiler = compilerFor(good);
        CatalogCompilationResult accepted = compiler.compile(List.of(good), 1);
        CatalogSnapshot lastGood = accepted.snapshot().orElseThrow();

        CatalogContribution broken = CatalogContribution.builder(OwnerId.of("resync.bad"), "1.0.0", RANGE, provenance(OwnerId.of("resync.bad")))
            .categories(List.of(category()))
            .capabilities(capabilities())
            .definitions(List.of(node(NodeId.of("bad-node"), ref(OwnerId.of("resync.bad"), CapabilityId.of("missing")))))
            .build();
        CatalogCompilationResult rejected = compiler.compile(List.of(broken), 2);

        assertTrue(accepted.accepted());
        assertFalse(rejected.accepted());
        assertEquals(lastGood.generation(), accepted.snapshot().orElseThrow().generation());
        assertTrue(rejected.diagnostics().stream().anyMatch(value -> value.code().equals("CATALOG.BINDING_MISSING")));
    }

    @Test
    void activeSnapshotRetainsTypedInspectorIndexesAndAuthoredNodeMetadata() {
        OwnerId owner = OwnerId.of("resync.metadata");
        CatalogVersion optionContract = new CatalogVersion(1, 3);
        CatalogNodeDescriptor metadataNode = CatalogNodeDescriptor.builder(NodeId.of("metadata-node"))
            .domain("flow")
            .family("operation")
            .displayName("Metadata Node")
            .description("Publishes authored server metadata for this operation.")
            .category(ref(owner, CapabilityId.of("flow")))
            .branches(List.of(new CatalogNodeDescriptor.Branch("failed", "Failed", "Reports that this operation could not complete.", List.of(new CatalogNodeDescriptor.Case("failure", "Failure", "The operation reported a structured failure.")))))
            .handler(new CatalogNodeDescriptor.Handler(ref(owner, CapabilityId.of("flow-execute")), ContractRef.of(owner, OperationId.of("execute"))))
            .semantics(pure(ref(OwnerId.of("resync.system"), CapabilityId.of("flow-authorize")), "failed"))
            .requiredCapabilities(Set.of(ref(owner, CapabilityId.of("flow-execute"))))
            .metadata(Map.of("functionBoundary", Map.of("role", "input")))
            .build();
        CatalogContribution contribution = CatalogContribution.builder(owner, "1.0.0",
                new CatalogContractRange(CONTRACT, optionContract), provenance(owner))
            .categories(List.of(category()))
            .capabilities(capabilities())
            .optionSources(List.of(new InspectorOptionSource(InspectorFieldId.of("resource"), "Resource",
                "Provides server-authored resource choices.", STRING, OptionQuerySchemaV1.empty(),
                ref(owner, CapabilityId.of("flow-execute")), 50)))
            .definitions(List.of(metadataNode))
            .runtimeRequirements(List.of(requirement(metadataNode)))
            .build();

        CatalogCompilationResult oldContract = compilerFor(CONTRACT, contribution).compile(List.of(contribution), 6);
        CatalogCompilationResult result = compilerFor(optionContract, contribution).compile(List.of(contribution), 7);
        assertFalse(oldContract.accepted());
        assertTrue(oldContract.diagnostics().stream().anyMatch(value -> value.code().equals("CATALOG.OPTION_SCHEMA_UNSUPPORTED")));
        assertTrue(result.accepted(), result.diagnostics().stream().map(value -> value.code() + ":" + value.message()).toList().toString());
        CatalogSnapshot snapshot = result.snapshot().orElseThrow();

        assertEquals(1, snapshot.optionSources().size());
        assertTrue(snapshot.optionSource(ref(owner, InspectorFieldId.of("resource"))).isPresent());
        assertEquals(Map.of("role", "input"), snapshot.definitions().getFirst().descriptor().metadata().get("functionBoundary"));
        assertTrue(snapshot.canonicalContent().contains("functionBoundary"));
        assertEquals(snapshot.contentChecksum(), CatalogCanonicalizer.checksumForCanonicalContent(snapshot.canonicalContent()));
    }

    @Test
    void snapshotRetainsAuthoredSourceRowAndOwnerProvenance() {
        OwnerId owner = OwnerId.of("resync.provenance");
        NodeId nodeId = NodeId.of("provenance-node");
        CatalogNodeDescriptor definition = node(nodeId, ref(owner, CapabilityId.of("flow-execute")), Map.of(
            "authoredSource", Map.of(
                "id", nodeId.value(),
                "handlerCapability", "flow-execute",
                "sourceProvenance", Map.of(
                    "sourceUri", "nodes/automation.json",
                    "rowIndex", 3,
                    "owner", owner.value(),
                    "sourceHash", "a".repeat(64)))));
        CatalogContribution contribution = CatalogContribution.builder(owner, "1.0.0", RANGE, provenance(owner))
            .categories(List.of(category()))
            .capabilities(capabilities())
            .definitions(List.of(definition))
            .runtimeRequirements(List.of(requirement(definition)))
            .build();

        CatalogSnapshot snapshot = compilerFor(contribution).compile(List.of(contribution), 2).snapshot().orElseThrow();

        CatalogProvenance.SourceEntry entry = snapshot.definitions().getFirst().provenance().entries().getFirst();
        assertEquals("nodes/automation.json", entry.sourceUri());
        assertEquals(3, entry.rowIndex());
        assertEquals(owner, entry.owner());
        assertEquals(nodeId.value(), entry.definitionId());
        assertEquals("nodes/automation.json", contribution.provenanceFor(definition).sourceUri());
    }

    @Test
    void rejectsAuthoredSourceRowOutsideIntegerRange() {
        OwnerId owner = OwnerId.of("resync.provenance.overflow");
        NodeId nodeId = NodeId.of("provenance-overflow-node");
        CatalogNodeDescriptor definition = node(nodeId, ref(owner, CapabilityId.of("flow-execute")), Map.of(
            "authoredSource", Map.of(
                "id", nodeId.value(),
                "handlerCapability", "flow-execute",
                "sourceProvenance", Map.of(
                    "sourceUri", "nodes/automation.json",
                    "rowIndex", (long) Integer.MAX_VALUE + 1,
                    "owner", owner.value(),
                    "sourceHash", "a".repeat(64)))));
        CatalogContribution contribution = CatalogContribution.builder(owner, "1.0.0", RANGE, provenance(owner))
            .categories(List.of(category()))
            .capabilities(capabilities())
            .definitions(List.of(definition))
            .runtimeRequirements(List.of(requirement(definition)))
            .build();

        CatalogCompilationResult result = compilerFor(contribution).compile(List.of(contribution), 2);

        assertFalse(result.accepted());
        assertTrue(result.diagnostics().stream().anyMatch(value -> value.code().equals("CATALOG.CONTRIBUTION_REJECTED")));
    }

    @Test
    void rejectsCatalogContractGenerationNarrowingOverflowDuringChecksumRecovery() {
        String content = "{\"contentChecksum\":\"" + "a".repeat(64)
            + "\",\"contractVersion\":{\"generation\":4294967297,\"minor\":0}}";

        assertThrows(IllegalArgumentException.class, () -> CatalogCanonicalizer.checksumForCanonicalContent(content));
    }

    @Test
    void missingAuthoredSourceProvenanceRejectsContribution() {
        OwnerId owner = OwnerId.of("resync.provenance.missing");
        NodeId nodeId = NodeId.of("missing-provenance-node");
        CatalogNodeDescriptor definition = node(nodeId, ref(owner, CapabilityId.of("flow-execute")), Map.of(
            "authoredSource", Map.of("id", nodeId.value())));
        CatalogContribution contribution = CatalogContribution.builder(owner, "1.0.0", RANGE, provenance(owner))
            .categories(List.of(category()))
            .capabilities(capabilities())
            .definitions(List.of(definition))
            .runtimeRequirements(List.of(requirement(definition)))
            .build();

        CatalogCompilationResult result = compilerFor(contribution).compile(List.of(contribution), 1);

        assertFalse(result.accepted());
        assertTrue(result.diagnostics().stream().anyMatch(value -> value.code().equals("CATALOG.CONTRIBUTION_REJECTED")));
    }

    @Test
    void rejectsMissingMismatchedAndInactiveRuntimeBindings() {
        OwnerId owner = OwnerId.of("resync.binding");
        CatalogNodeDescriptor definition = node(NodeId.of("bound-node"), ref(owner, CapabilityId.of("flow-execute")));
        CatalogContribution missing = CatalogContribution.builder(owner, "1.0.0", RANGE, provenance(owner))
            .categories(List.of(category()))
            .capabilities(capabilities())
            .definitions(List.of(definition))
            .build();
        RuntimeOperationDescriptor mismatched = new RuntimeOperationDescriptor(definition.handler().capability(), definition.handler().operation(),
            List.of(new RuntimeOperationDescriptor.Pin("wrong-value", RuntimeOperationDescriptor.Direction.INPUT, STRING)), definition.semantics());
        CatalogContribution wrong = CatalogContribution.builder(owner, "1.0.0", RANGE, provenance(owner))
            .categories(List.of(category()))
            .capabilities(capabilities())
            .definitions(List.of(definition))
            .runtimeRequirements(List.of(mismatched))
            .build();
        CatalogContribution exact = CatalogContribution.builder(owner, "1.0.0", RANGE, provenance(owner))
            .categories(List.of(category()))
            .capabilities(capabilities())
            .definitions(List.of(definition))
            .runtimeRequirements(List.of(requirement(definition)))
            .build();

        assertFalse(new CatalogCompiler(CONTRACT).compile(List.of(missing), 1).accepted());
        assertFalse(new CatalogCompiler(CONTRACT, CatalogBindingProof.fixed(Map.of(mismatched.key(), mismatched.executionFingerprint()))).compile(List.of(wrong), 1).accepted());
        assertFalse(new CatalogCompiler(CONTRACT).compile(List.of(exact), 1).accepted());
        assertTrue(compilerFor(exact).compile(List.of(exact), 1).accepted());
    }

    @Test
    void rejectsAuthoredHandlerCapabilityDriftAndUnregisteredCapability() {
        OwnerId owner = OwnerId.of("resync.authored.binding");
        NodeId nodeId = NodeId.of("authored-binding-node");
        CatalogNodeDescriptor mismatchedNode = node(nodeId, ref(owner, CapabilityId.of("flow-execute")), authoredSource("other-handler"));
        CatalogContribution mismatched = CatalogContribution.builder(owner, "1.0.0", RANGE, provenance(owner))
            .categories(List.of(category()))
            .capabilities(capabilities())
            .definitions(List.of(mismatchedNode))
            .runtimeRequirements(List.of(requirement(mismatchedNode)))
            .build();

        CatalogNodeDescriptor unregisteredNode = node(nodeId, ref(owner, CapabilityId.of("missing-handler")), authoredSource("missing-handler"));
        CatalogContribution unregistered = CatalogContribution.builder(owner, "1.0.0", RANGE, provenance(owner))
            .categories(List.of(category()))
            .capabilities(List.of(new CatalogCapabilityDescriptor(CapabilityId.of("generic-editor"), 1, false, InspectorFallback.READ_ONLY_FIELD)))
            .definitions(List.of(unregisteredNode))
            .runtimeRequirements(List.of(requirement(unregisteredNode)))
            .build();

        assertFalse(compilerFor(mismatched).compile(List.of(mismatched), 1).accepted());
        assertFalse(compilerFor(unregistered).compile(List.of(unregistered), 1).accepted());
    }

    @Test
    void liveBindingProofRequiresAnAvailableBindingWithAHandler() {
        CatalogContribution contribution = contribution(OwnerId.of("resync.live"), NodeId.of("live-node"));
        RuntimeOperationDescriptor requirement = contribution.runtimeRequirements().getFirst();
        OwnerId owner = OwnerId.of("resync");
        ContractRef<ProviderId> provider = ContractRef.of(owner, ProviderId.of("flow"));
        RuntimeProviderDescriptor descriptor = new RuntimeProviderDescriptor(provider, "1.0.0", 0, 0, UnloadPolicy.DRAIN);

        RuntimeBindingRegistry unavailableRegistry = new RuntimeBindingRegistry();
        unavailableRegistry.activate(descriptor, List.of(RuntimeBinding.unavailable(requirement, provider, "1.0.0")));
        assertFalse(CatalogBindingProof.live(unavailableRegistry).proves(requirement));

        RuntimeBindingRegistry liveRegistry = new RuntimeBindingRegistry();
        RuntimeBinding live = RuntimeBinding.available(requirement, provider, "1.0.0",
            invocation -> CompletableFuture.completedFuture(restudio.resync.flow.runtime.RuntimeResult.success()));
        liveRegistry.activate(descriptor, List.of(live));
        assertTrue(CatalogBindingProof.live(liveRegistry).proves(requirement));
    }

    @Test
    void snapshotBindingProofPreservesExactRuntimePins() {
        OwnerId owner = OwnerId.of("resync.pin-proof");
        ContractRef<CapabilityId> capability = ref(owner, CapabilityId.of("execute"));
        ContractRef<OperationId> operation = ref(owner, OperationId.of("run"));
        List<RuntimeOperationDescriptor.Pin> pins = List.of(
            new RuntimeOperationDescriptor.Pin("value", RuntimeOperationDescriptor.Direction.INPUT, STRING),
            new RuntimeOperationDescriptor.Pin("enabled", RuntimeOperationDescriptor.Direction.INPUT, BOOLEAN),
            new RuntimeOperationDescriptor.Pin("result", RuntimeOperationDescriptor.Direction.OUTPUT, STRING));
        RuntimeOperationDescriptor exact = new RuntimeOperationDescriptor(capability, operation, pins,
            pure(ref(owner, CapabilityId.of("authorize")), "failed"));
        RuntimeOperationDescriptor renamed = new RuntimeOperationDescriptor(capability, operation, List.of(
            new RuntimeOperationDescriptor.Pin("renamed", RuntimeOperationDescriptor.Direction.INPUT, STRING),
            pins.get(1),
            pins.get(2)), exact.semantics());
        RuntimeOperationDescriptor reordered = new RuntimeOperationDescriptor(capability, operation, List.of(
            pins.get(1), pins.get(0), pins.get(2)), exact.semantics());
        RuntimeOperationDescriptor reversed = new RuntimeOperationDescriptor(capability, operation, List.of(
            new RuntimeOperationDescriptor.Pin("value", RuntimeOperationDescriptor.Direction.OUTPUT, STRING),
            pins.get(1), pins.get(2)), exact.semantics());
        RuntimeOperationDescriptor typed = new RuntimeOperationDescriptor(capability, operation, List.of(
            new RuntimeOperationDescriptor.Pin("value", RuntimeOperationDescriptor.Direction.INPUT, BOOLEAN),
            new RuntimeOperationDescriptor.Pin("enabled", RuntimeOperationDescriptor.Direction.INPUT, STRING),
            pins.get(2)), exact.semantics());
        ContractRef<ProviderId> provider = ref(owner, ProviderId.of("provider"));
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry();
        registry.activate(new RuntimeProviderDescriptor(provider, "1.0.0", 0, 0, UnloadPolicy.DRAIN), List.of(
            RuntimeBinding.available(exact, provider, "1.0.0",
                ignored -> CompletableFuture.completedFuture(RuntimeResult.success()))));

        CatalogBindingProof proof = CatalogBindingProof.snapshot(registry.snapshot());

        assertEquals(exact.executionFingerprint(), proof.activeFingerprint(exact.key()).orElseThrow());
        assertTrue(proof.proves(exact));
        assertFalse(proof.proves(renamed));
        assertFalse(proof.proves(reordered));
        assertFalse(proof.proves(reversed));
        assertFalse(proof.proves(typed));
    }

    @Test
    void canonicalRuntimePinsChangeBindingManifestHashForIdentityAndDirectionChanges() {
        OwnerId owner = OwnerId.of("resync.pin-canonical");
        ContractRef<CapabilityId> capability = ref(owner, CapabilityId.of("execute"));
        ContractRef<OperationId> operation = ref(owner, OperationId.of("run"));
        RuntimeSemantics semantics = pure(ref(owner, CapabilityId.of("authorize")), "failed");
        RuntimeOperationDescriptor exact = new RuntimeOperationDescriptor(capability, operation, List.of(
            new RuntimeOperationDescriptor.Pin("value", RuntimeOperationDescriptor.Direction.INPUT, STRING),
            new RuntimeOperationDescriptor.Pin("result", RuntimeOperationDescriptor.Direction.OUTPUT, STRING)), semantics);
        RuntimeOperationDescriptor changed = new RuntimeOperationDescriptor(capability, operation, List.of(
            new RuntimeOperationDescriptor.Pin("value", RuntimeOperationDescriptor.Direction.OUTPUT, STRING),
            new RuntimeOperationDescriptor.Pin("result", RuntimeOperationDescriptor.Direction.INPUT, STRING)), semantics);
        RuntimeOperationDescriptor reordered = new RuntimeOperationDescriptor(capability, operation, List.of(
            new RuntimeOperationDescriptor.Pin("result", RuntimeOperationDescriptor.Direction.OUTPUT, STRING),
            new RuntimeOperationDescriptor.Pin("value", RuntimeOperationDescriptor.Direction.INPUT, STRING)), semantics);
        RuntimeOperationDescriptor typed = new RuntimeOperationDescriptor(capability, operation, List.of(
            new RuntimeOperationDescriptor.Pin("value", RuntimeOperationDescriptor.Direction.INPUT, BOOLEAN),
            new RuntimeOperationDescriptor.Pin("result", RuntimeOperationDescriptor.Direction.OUTPUT, STRING)), semantics);
        CatalogContribution first = runtimeContribution(owner, exact);
        CatalogContribution second = runtimeContribution(owner, changed);
        CatalogContribution reorderedContribution = runtimeContribution(owner, reordered);
        CatalogContribution typedContribution = runtimeContribution(owner, typed);

        assertNotEquals(CatalogCanonicalizer.canonicalContribution(first), CatalogCanonicalizer.canonicalContribution(second));
        assertNotEquals(CatalogCanonicalizer.bindingManifestHash(List.of(first)), CatalogCanonicalizer.bindingManifestHash(List.of(second)));
        assertNotEquals(CatalogCanonicalizer.bindingManifestHash(List.of(first)), CatalogCanonicalizer.bindingManifestHash(List.of(reorderedContribution)));
        assertNotEquals(CatalogCanonicalizer.bindingManifestHash(List.of(first)), CatalogCanonicalizer.bindingManifestHash(List.of(typedContribution)));
        assertTrue(CatalogCanonicalizer.canonicalContribution(first).contains("\"pins\""));
        assertTrue(CatalogCanonicalizer.canonicalContribution(first).contains("\"value\""));
        assertTrue(CatalogCanonicalizer.canonicalContribution(first).contains("\"result\""));
    }

    @Test
    void compilerRejectsRuntimeRequirementsWithSameTypesButDifferentPinIdentityOrDirection() {
        OwnerId owner = OwnerId.of("resync.pin-compiler");
        CatalogNodeDescriptor definition = node(NodeId.of("pin-node"), ref(owner, CapabilityId.of("flow-execute")), List.of(
            new CatalogNodeDescriptor.Pin(PinId.of("value"), CatalogNodeDescriptor.Direction.INPUT, STRING,
                "Value", "The input value.", CatalogNodeDescriptor.Requirement.REQUIRED, null,
                ref(owner, CapabilityId.of("generic-editor")), null, null, null),
            new CatalogNodeDescriptor.Pin(PinId.of("result"), CatalogNodeDescriptor.Direction.OUTPUT, STRING,
                "Result", "The output value.", CatalogNodeDescriptor.Requirement.REQUIRED, null,
                ref(owner, CapabilityId.of("generic-editor")), null, null, null)), Map.of());
        RuntimeOperationDescriptor renamed = new RuntimeOperationDescriptor(definition.handler().capability(), definition.handler().operation(), List.of(
            new RuntimeOperationDescriptor.Pin("renamed", RuntimeOperationDescriptor.Direction.INPUT, STRING),
            new RuntimeOperationDescriptor.Pin("result", RuntimeOperationDescriptor.Direction.OUTPUT, STRING)), definition.semantics());
        RuntimeOperationDescriptor reversed = new RuntimeOperationDescriptor(definition.handler().capability(), definition.handler().operation(), List.of(
            new RuntimeOperationDescriptor.Pin("value", RuntimeOperationDescriptor.Direction.OUTPUT, STRING),
            new RuntimeOperationDescriptor.Pin("result", RuntimeOperationDescriptor.Direction.INPUT, STRING)), definition.semantics());

        CatalogCompilationResult renamedResult = compilerFor(CatalogContribution.builder(owner, "1.0.0", RANGE, provenance(owner))
            .categories(List.of(category())).capabilities(capabilities()).definitions(List.of(definition))
            .runtimeRequirements(List.of(renamed)).build()).compile(List.of(CatalogContribution.builder(owner, "1.0.0", RANGE, provenance(owner))
            .categories(List.of(category())).capabilities(capabilities()).definitions(List.of(definition))
            .runtimeRequirements(List.of(renamed)).build()), 1);
        CatalogCompilationResult reversedResult = compilerFor(CatalogContribution.builder(owner, "1.0.0", RANGE, provenance(owner))
            .categories(List.of(category())).capabilities(capabilities()).definitions(List.of(definition))
            .runtimeRequirements(List.of(reversed)).build()).compile(List.of(CatalogContribution.builder(owner, "1.0.0", RANGE, provenance(owner))
            .categories(List.of(category())).capabilities(capabilities()).definitions(List.of(definition))
            .runtimeRequirements(List.of(reversed)).build()), 1);

        assertFalse(renamedResult.accepted());
        assertFalse(reversedResult.accepted());
        assertTrue(renamedResult.diagnostics().stream().anyMatch(value -> value.code().equals("CATALOG.BINDING_MISSING")));
        assertTrue(reversedResult.diagnostics().stream().anyMatch(value -> value.code().equals("CATALOG.BINDING_MISSING")));
    }

    @Test
    void snapshotRejectsCanonicalStateThatDoesNotMatchItsContributions() {
        CatalogContribution first = contribution(OwnerId.of("resync.first"), NodeId.of("first-node"));
        CatalogContribution second = contribution(OwnerId.of("resync.second"), NodeId.of("second-node"));
        CatalogSnapshot snapshot = compilerFor(first).compile(List.of(first), 1).snapshot().orElseThrow();

        assertThrows(IllegalArgumentException.class, () -> new CatalogSnapshot(1, CONTRACT, snapshot.contentChecksum(), snapshot.bindingManifestHash(), snapshot.minimumClientCapabilities(), List.of(second), snapshot.definitions(), snapshot.types(), snapshot.conversions(), snapshot.categories(), snapshot.inspectors(), snapshot.capabilities(), snapshot.runtimeRequirements(), snapshot.optionSources(), snapshot.validators(), snapshot.editors(), snapshot.previews(), snapshot.migrations(), snapshot.provenance(), snapshot.diagnostics(), snapshot.canonicalContent()));
    }

    @Test
    void fixedProofWithoutManifestHashRejectsRuntimeBoundContributions() {
        CatalogContribution contribution = contribution(OwnerId.of("resync.manifest-required"), NodeId.of("manifest-required-node"));
        RuntimeOperationDescriptor requirement = contribution.runtimeRequirements().getFirst();

        CatalogCompilationResult result = new CatalogCompiler(CONTRACT, CatalogBindingProof.fixed(
            Map.of(requirement.key(), requirement.executionFingerprint())))
            .compile(List.of(contribution), 1);

        assertFalse(result.accepted());
        assertTrue(result.diagnostics().stream().anyMatch(value -> value.code().equals("CATALOG.BINDING_MISSING")));
    }

    @Test
    void compilerAcceptsCaseSensitiveRawSourcePinIdentity() {
        OwnerId owner = OwnerId.of("resync.catalog.raw-source");
        NodeId nodeId = NodeId.of("raw-source-node");
        CatalogNodeDescriptor base = node(nodeId, ref(owner, CapabilityId.of("flow-execute")));
        CatalogNodeDescriptor target = CatalogNodeDescriptor.builder(nodeId)
            .schemaVersion(2)
            .domain(base.domain())
            .family(base.family())
            .displayName(base.displayName())
            .description(base.description())
            .category(base.category())
            .pins(base.pins())
            .branches(base.branches())
            .handler(base.handler())
            .semantics(base.semantics())
            .requiredCapabilities(base.requiredCapabilities())
            .build();
        CatalogMigrationEdge migration = new CatalogMigrationEdge(
            CapabilityId.of("raw-source-migration"), owner, nodeId, 1, 2,
            CatalogMigrationEdge.Kind.DECLARATIVE, List.of(nodeId.value()),
            CatalogMigrationEdge.ConnectionPolicy.REMAP, List.of(), List.of(
                new CatalogMigrationEdge.PinMapping(owner, nodeId, 1, 2,
                    CatalogMigrationEdge.LegacyPinId.of("listA"), PinId.of("value"),
                    CatalogNodeDescriptor.Direction.INPUT)));
        CatalogContribution contribution = CatalogContribution.builder(owner, "1.0.0", RANGE, provenance(owner))
            .categories(List.of(category()))
            .capabilities(capabilities())
            .definitions(List.of(target))
            .runtimeRequirements(List.of(requirement(target)))
            .migrations(List.of(migration))
            .build();

        CatalogCompilationResult result = compilerFor(contribution).compile(List.of(contribution), 1);

        assertTrue(result.accepted(), result.diagnostics().stream()
            .map(value -> value.code() + ":" + value.message()).toList().toString());
    }

    @Test
    void compilerAllowsUnmappedTargetPinsButRejectsInvalidMappedTargets() {
        OwnerId owner = OwnerId.of("resync.catalog.target-addition");
        NodeId nodeId = NodeId.of("target-addition-node");
        CatalogNodeDescriptor base = node(nodeId, ref(owner, CapabilityId.of("flow-execute")), List.of(
            new CatalogNodeDescriptor.Pin(PinId.of("value"), CatalogNodeDescriptor.Direction.INPUT, STRING,
                "Value", "The value supplied to the operation.", CatalogNodeDescriptor.Requirement.REQUIRED, null,
                ref(owner, CapabilityId.of("generic-editor")), null, null, null),
            new CatalogNodeDescriptor.Pin(PinId.of("result"), CatalogNodeDescriptor.Direction.OUTPUT, STRING,
                "Result", "The value returned by the operation.", CatalogNodeDescriptor.Requirement.REQUIRED, null,
                ref(owner, CapabilityId.of("generic-editor")), null, null, null),
            new CatalogNodeDescriptor.Pin(PinId.of("diagnostics"), CatalogNodeDescriptor.Direction.OUTPUT, STRING,
                "Diagnostics", "Additional operation diagnostics.", CatalogNodeDescriptor.Requirement.OPTIONAL, null,
                ref(owner, CapabilityId.of("generic-editor")), null, null, null)), Map.of());
        CatalogNodeDescriptor target = CatalogNodeDescriptor.builder(nodeId)
            .schemaVersion(2)
            .domain(base.domain())
            .family(base.family())
            .displayName(base.displayName())
            .description(base.description())
            .category(base.category())
            .pins(base.pins())
            .branches(base.branches())
            .handler(base.handler())
            .semantics(base.semantics())
            .requiredCapabilities(base.requiredCapabilities())
            .build();
        CatalogMigrationEdge valid = new CatalogMigrationEdge(
            CapabilityId.of("target-addition-migration"), owner, nodeId, 1, 2,
            CatalogMigrationEdge.Kind.DECLARATIVE, List.of(nodeId.value()),
            CatalogMigrationEdge.ConnectionPolicy.REMAP, List.of(), List.of(
                new CatalogMigrationEdge.PinMapping(owner, nodeId, 1, 2,
                    CatalogMigrationEdge.LegacyPinId.of("legacy_value"), PinId.of("value"),
                    CatalogNodeDescriptor.Direction.INPUT),
                new CatalogMigrationEdge.PinMapping(owner, nodeId, 1, 2,
                    CatalogMigrationEdge.LegacyPinId.of("legacy_result"), PinId.of("result"),
                    CatalogNodeDescriptor.Direction.OUTPUT)));
        CatalogContribution acceptedContribution = CatalogContribution.builder(owner, "1.0.0", RANGE, provenance(owner))
            .categories(List.of(category()))
            .capabilities(capabilities())
            .definitions(List.of(target))
            .runtimeRequirements(List.of(requirement(target)))
            .migrations(List.of(valid))
            .build();

        CatalogCompilationResult accepted = compilerFor(acceptedContribution).compile(List.of(acceptedContribution), 1);

        assertTrue(accepted.accepted(), accepted.diagnostics().stream()
            .map(value -> value.code() + ":" + value.message()).toList().toString());

        CatalogMigrationEdge unresolvedTarget = new CatalogMigrationEdge(
            CapabilityId.of("unresolved-target-migration"), owner, nodeId, 1, 2,
            CatalogMigrationEdge.Kind.DECLARATIVE, List.of(nodeId.value()),
            CatalogMigrationEdge.ConnectionPolicy.REMAP, List.of(), List.of(
                new CatalogMigrationEdge.PinMapping(owner, nodeId, 1, 2,
                    CatalogMigrationEdge.LegacyPinId.of("legacy_value"), PinId.of("missing"),
                    CatalogNodeDescriptor.Direction.INPUT),
                new CatalogMigrationEdge.PinMapping(owner, nodeId, 1, 2,
                    CatalogMigrationEdge.LegacyPinId.of("legacy_result"), PinId.of("result"),
                    CatalogNodeDescriptor.Direction.OUTPUT)));
        CatalogContribution unresolvedContribution = CatalogContribution.builder(owner, "1.0.0", RANGE, provenance(owner))
            .categories(List.of(category()))
            .capabilities(capabilities())
            .definitions(List.of(target))
            .runtimeRequirements(List.of(requirement(target)))
            .migrations(List.of(unresolvedTarget))
            .build();
        CatalogCompilationResult unresolved = compilerFor(unresolvedContribution).compile(List.of(unresolvedContribution), 1);

        assertFalse(unresolved.accepted());
        assertTrue(unresolved.diagnostics().stream().anyMatch(value -> value.code().equals("CATALOG.PIN_REFERENCE_INVALID")));

        CatalogMigrationEdge wrongDirection = new CatalogMigrationEdge(
            CapabilityId.of("wrong-direction-migration"), owner, nodeId, 1, 2,
            CatalogMigrationEdge.Kind.DECLARATIVE, List.of(nodeId.value()),
            CatalogMigrationEdge.ConnectionPolicy.REMAP, List.of(), List.of(
                new CatalogMigrationEdge.PinMapping(owner, nodeId, 1, 2,
                    CatalogMigrationEdge.LegacyPinId.of("legacy_value"), PinId.of("value"),
                    CatalogNodeDescriptor.Direction.INPUT),
                new CatalogMigrationEdge.PinMapping(owner, nodeId, 1, 2,
                    CatalogMigrationEdge.LegacyPinId.of("legacy_result"), PinId.of("result"),
                    CatalogNodeDescriptor.Direction.INPUT)));
        CatalogContribution wrongDirectionContribution = CatalogContribution.builder(owner, "1.0.0", RANGE, provenance(owner))
            .categories(List.of(category()))
            .capabilities(capabilities())
            .definitions(List.of(target))
            .runtimeRequirements(List.of(requirement(target)))
            .migrations(List.of(wrongDirection))
            .build();
        CatalogCompilationResult wrongDirectionResult = compilerFor(wrongDirectionContribution).compile(List.of(wrongDirectionContribution), 1);

        assertFalse(wrongDirectionResult.accepted());
        assertTrue(wrongDirectionResult.diagnostics().stream().anyMatch(value -> value.code().equals("CATALOG.MIGRATION_BROKEN")));
    }

    @Test
    void compilerDoesNotTreatOppositeDirectionsAsPinMappingCycle() {
        OwnerId owner = OwnerId.of("resync.catalog.direction-cycle");
        NodeId nodeId = NodeId.of("direction-cycle-node");
        ContractRef<CapabilityId> handler = ref(owner, CapabilityId.of("flow-execute"));
        CatalogNodeDescriptor definition = node(nodeId, handler, List.of(
            new CatalogNodeDescriptor.Pin(PinId.of("left"), CatalogNodeDescriptor.Direction.INPUT, STRING,
                "Input", "The input value.", CatalogNodeDescriptor.Requirement.REQUIRED, null,
                ref(owner, CapabilityId.of("generic-editor")), null, null, null, null),
            new CatalogNodeDescriptor.Pin(PinId.of("right"), CatalogNodeDescriptor.Direction.OUTPUT, STRING,
                "Output", "The output value.", CatalogNodeDescriptor.Requirement.REQUIRED, null,
                ref(owner, CapabilityId.of("generic-editor")), null, null, null, null)), Map.of());
        CatalogMigrationEdge migration = new CatalogMigrationEdge(
            CapabilityId.of("direction-cycle-migration"), owner, nodeId, 1, 2,
            CatalogMigrationEdge.Kind.DECLARATIVE, List.of(nodeId.value()),
            CatalogMigrationEdge.ConnectionPolicy.REMAP, List.of(), List.of(
                new CatalogMigrationEdge.PinMapping(owner, nodeId, 1, 2,
                    CatalogMigrationEdge.LegacyPinId.of("left"), PinId.of("right"),
                    CatalogNodeDescriptor.Direction.INPUT),
                new CatalogMigrationEdge.PinMapping(owner, nodeId, 1, 2,
                    CatalogMigrationEdge.LegacyPinId.of("right"), PinId.of("left"),
                    CatalogNodeDescriptor.Direction.OUTPUT)));
        CatalogContribution contribution = CatalogContribution.builder(owner, "1.0.0", RANGE, provenance(owner))
            .categories(List.of(category()))
            .capabilities(capabilities())
            .definitions(List.of(definition))
            .runtimeRequirements(List.of(requirement(definition)))
            .migrations(List.of(migration))
            .build();

        CatalogCompilationResult result = compilerFor(contribution).compile(List.of(contribution), 1);

        assertTrue(result.accepted(), result.diagnostics().stream()
            .map(value -> value.code() + ":" + value.message()).toList().toString());
    }

    private static CatalogContribution contribution(OwnerId owner, NodeId nodeId) {
        CatalogNodeDescriptor definition = node(nodeId, ref(owner, CapabilityId.of("flow-execute")));
        return CatalogContribution.builder(owner, "1.0.0", RANGE, provenance(owner))
            .categories(List.of(category()))
            .capabilities(capabilities())
            .definitions(List.of(definition))
            .runtimeRequirements(List.of(requirement(definition)))
            .build();
    }

    private static CatalogContribution runtimeContribution(OwnerId owner, RuntimeOperationDescriptor requirement) {
        return CatalogContribution.builder(owner, "1.0.0", RANGE, provenance(owner))
            .runtimeRequirements(List.of(requirement))
            .build();
    }

    private static RuntimeOperationDescriptor requirement(CatalogNodeDescriptor node) {
        return new RuntimeOperationDescriptor(node.handler().capability(), node.handler().operation(),
            node.pins().stream().map(pin -> new RuntimeOperationDescriptor.Pin(pin.id(),
                pin.direction() == CatalogNodeDescriptor.Direction.INPUT
                    ? RuntimeOperationDescriptor.Direction.INPUT
                    : RuntimeOperationDescriptor.Direction.OUTPUT,
                pin.type())).toList(), node.semantics());
    }

    private static CatalogCompiler compilerFor(CatalogContribution... contributions) {
        return compilerFor(CONTRACT, contributions);
    }

    private static CatalogCompiler compilerFor(CatalogVersion version, CatalogContribution... contributions) {
        Map<RuntimeBindingKey, ContentHash> fingerprints = new LinkedHashMap<>();
        for (CatalogContribution contribution : contributions) {
            for (RuntimeOperationDescriptor requirement : contribution.runtimeRequirements()) {
                fingerprints.put(requirement.key(), requirement.executionFingerprint());
            }
        }
        return new CatalogCompiler(version, CatalogBindingProof.fixed(fingerprints, TEST_MANIFEST_HASH));
    }

    private static CatalogBindingProof proof(RuntimeOperationDescriptor requirement) {
        return proof(requirement, new boolean[]{true});
    }

    private static CatalogBindingProof proof(RuntimeOperationDescriptor requirement, boolean[] available) {
        return new CatalogBindingProof() {
            @Override
            public Optional<ContentHash> activeFingerprint(RuntimeBindingKey binding) {
                return available[0] && binding.equals(requirement.key())
                    ? Optional.of(requirement.executionFingerprint()) : Optional.empty();
            }

            @Override
            public Optional<ContentHash> activeBindingManifestHash() {
                return Optional.of(TEST_MANIFEST_HASH);
            }
        };
    }

    private static CatalogNodeDescriptor node(NodeId id, ContractRef<CapabilityId> handlerCapability) {
        return node(id, handlerCapability, Map.of());
    }

    private static CatalogNodeDescriptor node(NodeId id, ContractRef<CapabilityId> handlerCapability, Map<String, Object> metadata) {
        CatalogNodeDescriptor.Pin pin = new CatalogNodeDescriptor.Pin(PinId.of("value"), CatalogNodeDescriptor.Direction.INPUT, STRING, "Value", "The value supplied to the operation.", CatalogNodeDescriptor.Requirement.REQUIRED, null, ref(handlerCapability.owner(), CapabilityId.of("generic-editor")), null, null, null);
        return node(id, handlerCapability, List.of(pin), metadata);
    }

    private static CatalogNodeDescriptor node(NodeId id, ContractRef<CapabilityId> handlerCapability, List<CatalogNodeDescriptor.Pin> pins, Map<String, Object> metadata) {
        CatalogNodeDescriptor.Branch failure = new CatalogNodeDescriptor.Branch(BranchId.of("failed"), "Failed", "Describes the failure outcome for this operation.", List.of(new CatalogNodeDescriptor.Case(CaseId.of("failure"), "Failure", "The operation completed with a structured failure.")));
        RuntimeSemantics semantics = pure(ref(OwnerId.of("resync.system"), CapabilityId.of("flow-authorize")), "failed");
        return CatalogNodeDescriptor.builder(id)
            .domain("flow")
            .family("operation")
            .displayName("Operation")
            .description("Executes one deterministic catalog operation with explicit behavior.")
            .category(ref(handlerCapability.owner(), CapabilityId.of("flow")))
            .pins(pins)
            .branches(List.of(failure))
            .handler(new CatalogNodeDescriptor.Handler(handlerCapability, ContractRef.of(handlerCapability.owner(), OperationId.of("flow-operation"))))
            .semantics(semantics)
            .requiredCapabilities(Set.of(handlerCapability))
            .metadata(metadata)
            .build();
    }

    private static Map<String, Object> authoredSource(String handlerCapability) {
        return Map.of(
            "authoredSource", Map.of(
                "id", "authored-binding-node",
                "handlerCapability", handlerCapability,
                "sourceProvenance", Map.of(
                    "sourceUri", "nodes/automation.json",
                    "rowIndex", 0,
                    "owner", "resync.authored.binding",
                    "sourceHash", "a".repeat(64))));
    }

    private static RuntimeSemantics pure(ContractRef<CapabilityId> authorization, String failureBranch) {
        return new RuntimeSemantics(RuntimeSemantics.Effect.PURE, RuntimeSemantics.ThreadMode.CURRENT, authorization, RuntimeSemantics.Cancellation.NONE, 0, 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER, RuntimeSemantics.Idempotency.INTRINSIC, RuntimeSemantics.Audit.NONE, RuntimeSemantics.Confirmation.NONE, RuntimeSemantics.SensitiveData.NONE, RuntimeSemantics.Determinism.DETERMINISTIC, Set.of(), Set.of(failureBranch), Set.of(), new RuntimeFailureContract(STRING, Set.of("RUNTIME.FAILURE"), Set.of(failureBranch), RuntimeFailureContract.CommitBoundary.NO_MUTATION), Set.of(), Set.of());
    }

    private static CatalogCategoryDescriptor category() {
        return new CatalogCategoryDescriptor(CapabilityId.of("flow"), "Flow", "Operations that compose into a reusable flow graph.", 1);
    }

    private static List<CatalogCapabilityDescriptor> capabilities() {
        return List.of(new CatalogCapabilityDescriptor(CapabilityId.of("flow-execute"), 1, false, InspectorFallback.GENERIC), new CatalogCapabilityDescriptor(CapabilityId.of("generic-editor"), 1, false, InspectorFallback.READ_ONLY_FIELD));
    }

    private static <T extends LocalId> ContractRef<T> ref(OwnerId owner, T id) {
        return ContractRef.of(owner, id);
    }

    private static CatalogProvenance provenance(OwnerId source) {
        return CatalogProvenance.fromText(CatalogProvenance.SourceKind.BUNDLED, "classpath:/nodes/" + source.canonicalText() + ".json", "1.0.0", "test", source.canonicalText());
    }
}
