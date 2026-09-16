package restudio.resync.upgrade.flow;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.CoreGraphProjectionContext;
import restudio.resync.flow.catalog.CatalogBindingProof;
import restudio.resync.flow.catalog.CatalogCategoryDescriptor;
import restudio.resync.flow.catalog.CatalogCapabilityDescriptor;
import restudio.resync.flow.catalog.CatalogCompiler;
import restudio.resync.flow.catalog.CatalogContractRange;
import restudio.resync.flow.catalog.CatalogContribution;
import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.catalog.CatalogProvenance;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.inspector.InspectorFallback;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.runtime.RuntimeFailureContract;
import restudio.resync.flow.runtime.RuntimeBindingKey;
import restudio.resync.flow.runtime.RuntimeOperationDescriptor;
import restudio.resync.flow.runtime.RuntimeSemantics;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalLegacyGraphMaterializerTest {
    private static final CatalogVersion CONTRACT = new CatalogVersion(1, 0);
    private static final OwnerId OWNER = OwnerId.of("fixture");
    private static final OwnerId AMBIGUOUS_ONE = OwnerId.of("fixture.one");
    private static final OwnerId AMBIGUOUS_TWO = OwnerId.of("fixture.two");
    private static final UUID SERVER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID IDENTITY_NAMESPACE = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Test
    void materializesExactReferencesTypedLiteralsStableIdentitiesAndOpaqueRawData() {
        CoreGraphProjectionContext context = context();
        String first = """
            {"id":"main","resourceType":"flow","enabled":false,"futureRoot":{"nested":true},"nodes":{"target":{"type":"fixture/target","version":1,"x":4,"y":5},"source":{"type":"fixture/source","version":1,"x":1.5,"y":-2.25,"inputValues":{"ratio":1.250,"count":42,"flag":true,"text":"hello"},"futureNode":{"answer":7}}},"connections":[{"targetPin":"in","sourceNodeId":"source","targetNodeId":"target","sourcePin":"out"}]}
            """;
        String reordered = """
            {"connections":[{"sourcePin":"out","targetNodeId":"target","sourceNodeId":"source","targetPin":"in"}],"nodes":{"source":{"futureNode":{"answer":7},"inputValues":{"text":"hello","flag":true,"count":42,"ratio":1.250},"y":-2.25,"x":1.5,"version":1,"type":"fixture/source"},"target":{"y":5,"x":4,"version":1,"type":"fixture/target"}},"futureRoot":{"nested":true},"resourceType":"flow","enabled":false,"id":"main"}
            """;

        var firstAdmitted = RawLegacyGraphAdmission.admit(first, context);
        var secondAdmitted = RawLegacyGraphAdmission.admit(reordered, context);
        var firstResult = assertInstanceOf(CanonicalLegacyGraphMaterializer.Materialized.class,
            CanonicalLegacyGraphMaterializer.materialize(firstAdmitted, new LegacyNodeReferenceResolver(context)));
        var secondResult = assertInstanceOf(CanonicalLegacyGraphMaterializer.Materialized.class,
            CanonicalLegacyGraphMaterializer.materialize(secondAdmitted, new LegacyNodeReferenceResolver(context)));

        assertEquals(firstAdmitted.canonicalRaw(), secondAdmitted.canonicalRaw());
        assertEquals(firstResult.document().canonicalJson(), secondResult.document().canonicalJson());
        assertEquals(firstResult.document().checksum(), secondResult.document().checksum());
        assertEquals(Set.of(ContractRef.of(OWNER, NodeId.of("source")), ContractRef.of(OWNER, NodeId.of("target"))),
            firstResult.document().nodes().stream().map(GraphNode::definition).collect(Collectors.toSet()));

        GraphNode source = firstResult.document().nodes().stream()
            .filter(node -> node.definition().equals(ContractRef.of(OWNER, NodeId.of("source")))).findFirst().orElseThrow();
        assertEquals("hello", source.values().get(PinId.of("text")).value().value());
        assertEquals(true, source.values().get(PinId.of("flag")).value().value());
        assertEquals(new BigInteger("42"), source.values().get(PinId.of("count")).value().value());
        assertEquals(new BigDecimal("1.25"), source.values().get(PinId.of("ratio")).value().value());
        assertEquals(1.5, source.x());
        assertEquals(-2.25, source.y());
        assertEquals(Map.of("answer", new BigDecimal("7")), source.unknown().get("futureNode"));
        assertEquals(1, firstResult.document().connections().size());
        assertEquals(Set.of(ContractRef.of(OWNER, CapabilityId.of("execute"))), firstResult.document().requiredCapabilities());

        @SuppressWarnings("unchecked")
        Map<String, Object> opaque = (Map<String, Object>) firstResult.document().unknown().get("restudio.resync.legacyGraph");
        assertEquals(firstAdmitted.canonicalRaw(), opaque.get("raw"));
        assertEquals(firstAdmitted.canonicalInner(), opaque.get("inner"));
        assertEquals(2, opaque.get("format"));
        assertTrue(opaque.containsKey("outer"));
    }

    @Test
    void quarantinesMissingAndAmbiguousNodeReferences() {
        CoreGraphProjectionContext context = context();
        LegacyNodeReferenceResolver resolver = new LegacyNodeReferenceResolver(context);

        var missing = RawLegacyGraphAdmission.admit(singleNode("missing"), context);
        var ambiguous = RawLegacyGraphAdmission.admit(singleNode("same"), context);

        var missingResult = assertInstanceOf(CanonicalLegacyGraphMaterializer.Quarantined.class,
            CanonicalLegacyGraphMaterializer.materialize(missing, resolver));
        var ambiguousResult = assertInstanceOf(CanonicalLegacyGraphMaterializer.Quarantined.class,
            CanonicalLegacyGraphMaterializer.materialize(ambiguous, resolver));
        assertTrue(missingResult.reason().contains("unavailable"));
        assertTrue(ambiguousResult.reason().contains("ambiguous"));
        assertEquals(missing.canonicalRaw(), missingResult.canonicalRaw());
        assertEquals(ambiguous.canonicalInner(), ambiguousResult.canonicalInner());
    }

    @Test
    void quarantinesUnsupportedFunctionsVariablesAndPassthroughFields() {
        CoreGraphProjectionContext context = context();
        LegacyNodeReferenceResolver resolver = new LegacyNodeReferenceResolver(context);
        List<String> unsupported = List.of(
            "\"function\":true",
            "\"localVariables\":[{\"id\":\"value\"}]",
            "\"functionInputs\":[{\"id\":\"value\"}]",
            "\"functionOutputs\":[{\"id\":\"value\"}]",
            "\"contentProperties\":{\"future\":true}",
            "\"editorPassthroughs\":{\"future\":true}");

        for (String field : unsupported) {
            String source = "{\"id\":\"main\",\"resourceType\":\"flow\"," + field + ",\"nodes\":{},\"connections\":[]}";
            var admitted = RawLegacyGraphAdmission.admit(source, context);
            var result = assertInstanceOf(CanonicalLegacyGraphMaterializer.Quarantined.class,
                CanonicalLegacyGraphMaterializer.materialize(admitted, resolver));
            assertEquals(admitted.canonicalRaw(), result.canonicalRaw());
            assertEquals(admitted.canonicalInner(), result.canonicalInner());
        }
    }

    @Test
    void acceptsNormalizedFlowFunctionDefaults() {
        CoreGraphProjectionContext context = context();
        String source = "{\"id\":\"main\",\"resourceType\":\"flow\",\"function\":false,"
            + "\"functionOwner\":\"server\",\"functionNamespace\":\"local\",\"functionVersion\":1,"
            + "\"functionDescription\":\"\",\"functionInputs\":[],\"functionOutputs\":[],"
            + "\"editorPassthroughs\":[],\"localVariables\":[],\"nodes\":{},\"connections\":[]}";
        var admitted = RawLegacyGraphAdmission.admit(source, context);
        assertInstanceOf(CanonicalLegacyGraphMaterializer.Materialized.class,
            CanonicalLegacyGraphMaterializer.materialize(admitted, new LegacyNodeReferenceResolver(context)));
    }

    @Test
    void preservesTypedOuterEvidenceAndUsesOnlyUsedCapabilities() {
        String mutation = "22222222-2222-4222-8222-222222222222";
        String source = "{\"id\":\"main\",\"resourceType\":\"flow\",\"assetFormatVersion\":3,"
            + "\"assetActivationState\":\"inactive\",\"resourceRevision\":11,\"assetRevision\":11,"
            + "\"resourceMutationId\":\"" + mutation + "\",\"assetMutationId\":\"" + mutation + "\","
            + "\"resourceHash\":\"\",\"assetHash\":\"" + "a".repeat(64) + "\","
            + "\"nodes\":{\"source\":{\"type\":\"fixture/source\",\"version\":1,\"x\":0,\"y\":0}},"
            + "\"connections\":[]}";
        CoreGraphProjectionContext context = context();
        var admitted = RawLegacyGraphAdmission.admit(source, context);
        var result = assertInstanceOf(CanonicalLegacyGraphMaterializer.Materialized.class,
            CanonicalLegacyGraphMaterializer.materialize(admitted, new LegacyNodeReferenceResolver(context)));

        assertEquals(11, result.document().revision());
        assertEquals(ResourceActivationState.INACTIVE, admitted.outer().activationState());
        assertEquals(11, admitted.outer().effectiveRevision(0));
        assertEquals(UUID.fromString(mutation), admitted.outer().effectiveMutationId());
        assertEquals(Set.of(ContractRef.of(OWNER, CapabilityId.of("execute"))), result.document().requiredCapabilities());

        var validation = assertInstanceOf(CanonicalLegacyGraphMaterializer.Materialized.class,
            CanonicalLegacyGraphMaterializer.materialize(admitted, new LegacyNodeReferenceResolver(context), true));
        assertTrue(validation.document().requiredCapabilities().isEmpty());
    }

    @Test
    void quarantinesResolverContextMismatchWithStableCanonicalRecordId() {
        CoreGraphProjectionContext admittedContext = context();
        CoreGraphProjectionContext resolverContext = context();
        var admitted = RawLegacyGraphAdmission.admit(singleNode("missing"), admittedContext);
        var resolver = new LegacyNodeReferenceResolver(resolverContext);

        var first = assertInstanceOf(CanonicalLegacyGraphMaterializer.Quarantined.class,
            CanonicalLegacyGraphMaterializer.materialize(admitted, resolver));
        var second = assertInstanceOf(CanonicalLegacyGraphMaterializer.Quarantined.class,
            CanonicalLegacyGraphMaterializer.materialize(admitted, resolver));
        assertEquals("resolver-context-mismatch", first.reason());
        assertEquals(first.recordId(), second.recordId());
        assertEquals(UUID.fromString(first.recordId()).toString(), first.recordId());
    }

    private static String singleNode(String type) {
        return "{\"id\":\"main\",\"resourceType\":\"flow\",\"nodes\":{\"node\":{\"type\":\"" + type + "\",\"version\":1,\"x\":0,\"y\":0}},\"connections\":[]}";
    }

    private static CoreGraphProjectionContext context() {
        CatalogNodeDescriptor source = node(OWNER, "source", List.of(
            pin("out", CatalogNodeDescriptor.Direction.OUTPUT, named("string")),
            pin("text", CatalogNodeDescriptor.Direction.INPUT, named("string")),
            pin("flag", CatalogNodeDescriptor.Direction.INPUT, named("boolean")),
            pin("count", CatalogNodeDescriptor.Direction.INPUT, named("integer")),
            pin("ratio", CatalogNodeDescriptor.Direction.INPUT, named("number"))));
        CatalogNodeDescriptor target = node(OWNER, "target", List.of(
            pin("in", CatalogNodeDescriptor.Direction.INPUT, named("string"))));
        List<CatalogContribution> contributions = List.of(
            contribution(OWNER, List.of(source, target)),
            contribution(AMBIGUOUS_ONE, List.of(node(AMBIGUOUS_ONE, "same", List.of()))),
            contribution(AMBIGUOUS_TWO, List.of(node(AMBIGUOUS_TWO, "same", List.of()))));
        List<RuntimeOperationDescriptor> requirements = contributions.stream().flatMap(value -> value.runtimeRequirements().stream()).toList();
        Map<RuntimeBindingKey, ContentHash> fingerprints = new LinkedHashMap<>();
        requirements.forEach(requirement -> fingerprints.put(requirement.key(), requirement.executionFingerprint()));
        var compilation = new CatalogCompiler(CONTRACT,
            CatalogBindingProof.fixed(fingerprints, new ContentHash("f".repeat(64))))
            .compile(contributions, 1);
        if (!compilation.accepted()) {
            throw new AssertionError(compilation.diagnostics());
        }
        CatalogSnapshot catalog = compilation.snapshot().orElseThrow();
        CatalogBinding binding = CatalogBinding.of(catalog.generation(), catalog.contentChecksum(), catalog.bindingManifestHash());
        return new CoreGraphProjectionContext(new ServerResourceLocator(SERVER,
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("flow")), "main"),
            catalog, binding, 7, IDENTITY_NAMESPACE);
    }

    private static CatalogContribution contribution(OwnerId owner, List<CatalogNodeDescriptor> nodes) {
        return CatalogContribution.builder(owner, "1.0.0", new CatalogContractRange(CONTRACT, CONTRACT),
                CatalogProvenance.fromText(CatalogProvenance.SourceKind.BUNDLED, "fixture:" + owner.value(), "1.0.0", "materializer", owner.value()))
            .categories(List.of(new CatalogCategoryDescriptor("flow", "Flow", "Fixture graph operations for materializer verification.", 1)))
            .capabilities(List.of(
                new CatalogCapabilityDescriptor(CapabilityId.of("execute"), 1, false, InspectorFallback.GENERIC),
                new CatalogCapabilityDescriptor(CapabilityId.of("generic-editor"), 1, false, InspectorFallback.READ_ONLY_FIELD)))
            .definitions(nodes)
            .runtimeRequirements(nodes.stream().map(CanonicalLegacyGraphMaterializerTest::requirement).toList())
            .build();
    }

    private static CatalogNodeDescriptor node(OwnerId owner, String id, List<CatalogNodeDescriptor.Pin> pins) {
        ContractRef<CapabilityId> execute = ContractRef.of(owner, CapabilityId.of("execute"));
        return CatalogNodeDescriptor.builder(NodeId.of(id))
            .domain("flow")
            .family("operation")
            .displayName("Fixture " + id)
            .description("Fixture operation used to verify lossless legacy graph materialization.")
            .category(ContractRef.of(owner, CapabilityId.of("flow")))
            .pins(pins)
            .branches(List.of(new CatalogNodeDescriptor.Branch("failed", "Failed", "Reports a fixture operation failure.",
                List.of(new CatalogNodeDescriptor.Case("failure", "Failure", "The fixture operation reported a failure.")))))
            .handler(new CatalogNodeDescriptor.Handler(execute, ContractRef.of(owner, OperationId.of("run-" + id))))
            .semantics(semantics(owner))
            .requiredCapabilities(Set.of(execute))
            .build();
    }

    private static CatalogNodeDescriptor.Pin pin(String id, CatalogNodeDescriptor.Direction direction, TypeExpr type) {
        return new CatalogNodeDescriptor.Pin(PinId.of(id), direction, type, id, "Fixture pin used for typed materializer verification.",
            CatalogNodeDescriptor.Requirement.REQUIRED, null,
            ContractRef.of(OWNER, CapabilityId.of("generic-editor")), null, null, null);
    }

    private static RuntimeOperationDescriptor requirement(CatalogNodeDescriptor node) {
        return new RuntimeOperationDescriptor(node.handler().capability(), node.handler().operation(), node.pins().stream()
            .map(pin -> new RuntimeOperationDescriptor.Pin(pin.id(), pin.direction() == CatalogNodeDescriptor.Direction.INPUT
                ? RuntimeOperationDescriptor.Direction.INPUT : RuntimeOperationDescriptor.Direction.OUTPUT, pin.type())).toList(), node.semantics());
    }

    private static RuntimeSemantics semantics(OwnerId owner) {
        TypeExpr failureType = named("string");
        return new RuntimeSemantics(RuntimeSemantics.Effect.PURE, RuntimeSemantics.ThreadMode.CURRENT,
            ContractRef.of(owner, CapabilityId.of("authorize")), RuntimeSemantics.Cancellation.NONE, 0, 0, 0,
            RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER, RuntimeSemantics.Idempotency.INTRINSIC,
            RuntimeSemantics.Audit.NONE, RuntimeSemantics.Confirmation.NONE, RuntimeSemantics.SensitiveData.NONE,
            RuntimeSemantics.Determinism.DETERMINISTIC, Set.of(), Set.of("failed"), Set.of(),
            new RuntimeFailureContract(failureType, Set.of("RUNTIME.FAILURE"), Set.of("failed"), RuntimeFailureContract.CommitBoundary.NO_MUTATION),
            Set.of(), Set.of());
    }

    private static TypeExpr.Named named(String id) {
        return new TypeExpr.Named(TypeReference.of("builtin", id), List.of(), Map.of());
    }
}
