package restudio.resync.flow.graph;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.catalog.CatalogCanonicalizer;
import restudio.resync.flow.catalog.CatalogContractRange;
import restudio.resync.flow.catalog.CatalogContribution;
import restudio.resync.flow.catalog.CatalogProvenance;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.inspector.InspectorDescriptor;
import restudio.resync.flow.inspector.InspectorFunctionParameter;
import restudio.resync.flow.inspector.InspectorFunctionSignature;
import restudio.resync.flow.inspector.InspectorId;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;
import restudio.resync.flow.runtime.RuntimeBindingManifest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphValidatorContractTest {
    private static final UUID SERVER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final TypeExpr TEXT = TypeExpr.named(new TypeReference("builtin", "text"));
    private static final TypeExpr NUMBER = TypeExpr.named(new TypeReference("builtin", "number"));
    private static final FunctionParameterId FIRST_PARAMETER = FunctionParameterId.of(UUID.fromString("44444444-4444-4444-8444-444444444444"));
    private static final FunctionParameterId SECOND_PARAMETER = FunctionParameterId.of(UUID.fromString("55555555-5555-4555-8555-555555555555"));
    private static final FunctionParameterId OUTPUT_PARAMETER = FunctionParameterId.of(UUID.fromString("66666666-6666-4666-8666-666666666666"));

    @Test
    void runtimeManifestHashIsRequiredWhenAManifestIsSupplied() {
        CatalogSnapshot catalog = CatalogSnapshot.empty(new CatalogVersion(1, 0));
        RuntimeBindingManifest manifest = RuntimeBindingManifest.create(List.of(), List.of(), Map.of(), List.of());
        GraphDocument graph = new GraphDocument(catalog.contractVersion(), resource(), 0,
            CatalogBinding.of(catalog.generation(), catalog.contentChecksum(), ContentHash.of("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")),
            Set.of(), List.of(), List.of(), List.of(), OpaqueData.empty());

        ContentHash before = graph.checksum();
        ValidationResult result = new GraphValidator().validate(graph, catalog, manifest);

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(value -> value.code().equals("GRAPH.RUNTIME_FINGERPRINT_MISMATCH")));
        assertEquals(before, graph.checksum());
    }

    @Test
    void inspectorFieldIdentityAndVariablesRemainTypedAndOpaque() {
        Map<String, Object> unknown = new LinkedHashMap<>();
        unknown.put("future", Map.of("enabled", true));
        GraphVariable variable = new GraphVariable(UUID.fromString("22222222-2222-4222-8222-222222222222"), "message", TEXT,
            TypedValue.value(TEXT, "hello"));
        GraphNode node = new GraphNode(
            NodeInstanceId.of(UUID.fromString("33333333-3333-4333-8333-333333333333")),
            ContractRef.of(new OwnerId("builtin"), NodeId.of("text")), 1, null, Map.of(),
            Map.of(InspectorFieldId.of("message"), TypedValue.value(TEXT, "hello")), List.of(), List.of(),
            InspectorState.empty(), 0, 0, OpaqueData.of(unknown));
        CatalogSnapshot catalog = CatalogSnapshot.empty(new CatalogVersion(1, 0));
        GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), resource(), 0,
            CatalogBinding.of(catalog.generation(), catalog.contentChecksum(), catalog.bindingManifestHash()),
            Set.of(), List.of(node), List.of(), List.of(variable), List.of(), OpaqueData.empty());

        assertEquals(1, graph.variables().size());
        assertEquals(TypedValue.value(TEXT, "hello"), graph.variables().getFirst().value());
        assertEquals(TypedValue.value(TEXT, "hello"), graph.nodes().getFirst().inspectorFields().get(InspectorFieldId.of("message")));
        assertTrue(graph.canonicalJson().contains("\"future\""));
        assertThrows(IllegalArgumentException.class, () -> new GraphVariable(variable.variableId(), "message", TEXT, null,
            OpaqueData.of(Map.of("name", "collision"))));
    }

    @Test
    void functionParametersMatchByIdentityAcrossReorderAndRename() {
        CatalogSnapshot catalog = functionCatalog();
        FunctionBinding function = new FunctionBinding(functionResource(), 0,
            List.of(new FunctionParameter(SECOND_PARAMETER, "renamed-second", NUMBER),
                new FunctionParameter(FIRST_PARAMETER, "renamed-first", TEXT)),
            List.of(new FunctionParameter(OUTPUT_PARAMETER, "renamed-result", TEXT)));
        GraphDocument graph = functionGraph(catalog, function);

        ValidationResult result = new GraphValidator().validate(graph, catalog);

        assertTrue(result.valid());
    }

    @Test
    void functionParameterIdentityMismatchFailsClosed() {
        CatalogSnapshot catalog = functionCatalog();
        FunctionParameterId unexpected = FunctionParameterId.of(UUID.fromString("77777777-7777-4777-8777-777777777777"));
        FunctionBinding function = new FunctionBinding(functionResource(), 0,
            List.of(new FunctionParameter(unexpected, "renamed-first", TEXT),
                new FunctionParameter(SECOND_PARAMETER, "second", NUMBER)),
            List.of(new FunctionParameter(OUTPUT_PARAMETER, "result", TEXT)));
        GraphDocument graph = functionGraph(catalog, function);

        ValidationResult result = new GraphValidator().validate(graph, catalog);

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(value -> value.code().equals("GRAPH.PIN_TYPE_MISMATCH")));
    }

    @Test
    void inspectorFunctionSignaturesCannotAliasAnotherOwnerOrResourceKind() {
        CatalogSnapshot catalog = functionCatalog();
        List<FunctionParameter> inputs = List.of(new FunctionParameter(FIRST_PARAMETER, "first", TEXT),
            new FunctionParameter(SECOND_PARAMETER, "second", NUMBER));
        List<FunctionParameter> outputs = List.of(new FunctionParameter(OUTPUT_PARAMETER, "result", TEXT));
        for (ServerResourceLocator alias : List.of(
            new ServerResourceLocator(SERVER, ContractRef.of(new OwnerId("other"), new ResourceTypeId("function")), "compute"),
            new ServerResourceLocator(SERVER, ContractRef.of(new OwnerId("example"), new ResourceTypeId("flow")), "compute"))) {
            ValidationResult result = new GraphValidator().validate(functionGraph(catalog,
                new FunctionBinding(alias, 0, inputs, outputs)), catalog);
            assertFalse(result.valid());
            assertTrue(result.diagnostics().stream().anyMatch(value -> value.code().equals("GRAPH.DEFINITION_MISSING")));
        }
    }

    private static ServerResourceLocator resource() {
        return new ServerResourceLocator(SERVER, ContractRef.of(new OwnerId("resync"), new ResourceTypeId("flow")), "example");
    }

    private static ServerResourceLocator functionResource() {
        return new ServerResourceLocator(SERVER, ContractRef.of(new OwnerId("example"), new ResourceTypeId("function")), "compute");
    }

    private static GraphDocument functionGraph(CatalogSnapshot catalog, FunctionBinding function) {
        return new GraphDocument(new CatalogVersion(1, 0), resource(), 0,
            CatalogBinding.of(catalog.generation(), catalog.contentChecksum(), catalog.bindingManifestHash()),
            Set.of(), List.of(), List.of(), List.of(function), OpaqueData.empty());
    }

    private static CatalogSnapshot functionCatalog() {
        CatalogVersion version = new CatalogVersion(1, 0);
        InspectorFunctionParameter first = new InspectorFunctionParameter(FIRST_PARAMETER, "First", "Supplies the first value to the function.", TEXT, true, null);
        InspectorFunctionParameter second = new InspectorFunctionParameter(SECOND_PARAMETER, "Second", "Supplies the second value to the function.", NUMBER, true, null);
        InspectorFunctionSignature signature = new InspectorFunctionSignature(InspectorFieldId.of("compute"), "Compute",
            "Describes the stable parameters accepted by compute.", List.of(first, second), TEXT, null);
        InspectorDescriptor inspector = new InspectorDescriptor(new OwnerId("example"), InspectorId.of("functions"), "Functions",
            "Describes functions available to flow graphs.", List.of(), List.of(), List.of(), List.of(signature), List.of());
        CatalogContribution contribution = CatalogContribution.builder(new OwnerId("example"), "1.0.0",
                new CatalogContractRange(version, version),
                CatalogProvenance.fromText(CatalogProvenance.SourceKind.BUNDLED, "classpath:/functions", "1.0.0", "test", "functions"))
            .inspectors(List.of(inspector)).build();
        List<CatalogContribution> contributions = List.of(contribution);
        String canonical = CatalogCanonicalizer.canonicalSnapshotContent(1, version, contributions, Set.of(), List.of());
        return new CatalogSnapshot(1, version,
            CatalogCanonicalizer.contentChecksum(1, version, contributions, Set.of(), List.of()),
            CatalogCanonicalizer.bindingManifestHash(contributions), Set.of(), contributions,
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), canonical);
    }
}
