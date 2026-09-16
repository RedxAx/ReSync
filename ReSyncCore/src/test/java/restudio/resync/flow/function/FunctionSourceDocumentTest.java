package restudio.resync.flow.function;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FunctionSourceDocumentTest {
    private static final ServerResourceLocator RESOURCE = new ServerResourceLocator(
        new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111")),
        ContractRef.of(new OwnerId("resync"), new ResourceTypeId("function")),
        "identity");
    private static final TypeExpr TEXT = TypeExpr.named(new TypeReference("builtin", "text"));
    private static final FunctionParameterId INPUT = FunctionParameterId.of(UUID.fromString("22222222-2222-4222-8222-222222222222"));
    private static final FunctionParameterId OUTPUT = FunctionParameterId.of(UUID.fromString("33333333-3333-4333-8333-333333333333"));

    @Test
    void immutableSourcePreservesTypedSignatureAndGraphBody() {
        FunctionSourceDocument source = new FunctionSourceDocument(signature(9), graph(9), OpaqueData.of(Map.of("future", Map.of("keep", true))));

        assertEquals(RESOURCE, source.signature().function().resource());
        assertEquals(9, source.signature().revision().value());
        assertEquals(RESOURCE, source.graph().resource());
        assertEquals(true, ((Map<?, ?>) source.unknown().get("future")).get("keep"));
        assertEquals(source.canonicalJson(), CanonicalJson.canonicalize(CanonicalJson.parse(source.canonicalJson())));
        assertEquals(source.checksum(), new FunctionSourceDocument(signature(9), graph(9), source.unknown()).checksum());
    }

    @Test
    void locatorAndRevisionMismatchesAreRejectedBeforeExecution() {
        ServerResourceLocator other = new ServerResourceLocator(RESOURCE.serverId(), RESOURCE.type(), "other");
        FunctionSignature wrongLocator = new FunctionSignature(new FunctionLocator(other), new FunctionRevision(9),
            signature(9).inputs(), signature(9).outputs());

        assertThrows(IllegalArgumentException.class, () -> new FunctionSourceDocument(wrongLocator, graph(9)));
        assertThrows(IllegalArgumentException.class, () -> new FunctionSourceDocument(signature(8), graph(9)));
    }

    @Test
    void unknownKnownFieldCollisionIsRejectedThroughPublicOpaqueDataApi() {
        assertThrows(IllegalArgumentException.class, () -> new FunctionSourceDocument(
            signature(9), graph(9), OpaqueData.of(Map.of("graph", Map.of("future", true)))));
    }

    @Test
    void nonFunctionGraphCannotMasqueradeAsFunctionSource() {
        ServerResourceLocator flowResource = new ServerResourceLocator(
            RESOURCE.serverId(),
            ContractRef.of(new OwnerId("resync"), new ResourceTypeId("flow")),
            "flow");
        GraphDocument flow = new GraphDocument(
            flowResource,
            9,
            binding(),
            List.of(),
            List.of());
        FunctionSignature flowSignature = new FunctionSignature(new FunctionLocator(flowResource), new FunctionRevision(9),
            signature(9).inputs(), signature(9).outputs());
        GraphDocument mismatched = new GraphDocument(
            flow.schemaVersion(),
            flowResource,
            flow.revision(),
            flow.catalogBinding(),
            flow.requiredCapabilities(),
            flow.nodes(),
            flow.connections(),
            flow.variables(),
            flow.functions(),
            flow.unknown());

        assertThrows(IllegalArgumentException.class, () -> new FunctionSourceDocument(flowSignature, mismatched));
    }

    private static FunctionSignature signature(long revision) {
        return new FunctionSignature(new FunctionLocator(RESOURCE), new FunctionRevision(revision),
            List.of(new FunctionParameterContract(INPUT, TEXT, true)),
            List.of(new FunctionParameterContract(OUTPUT, TEXT, true)));
    }

    private static GraphDocument graph(long revision) {
        return new GraphDocument(RESOURCE, revision, binding(), List.of(), List.of());
    }

    private static CatalogBinding binding() {
        return new CatalogBinding(1, new ContentHash("0".repeat(64)), new ContentHash("1".repeat(64)));
    }
}
