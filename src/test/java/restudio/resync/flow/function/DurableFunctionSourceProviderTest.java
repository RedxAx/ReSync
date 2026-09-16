package restudio.resync.flow.function;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.graph.PinValue;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurableFunctionSourceProviderTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final ServerResourceLocator RESOURCE = new ServerResourceLocator(SERVER,
        ContractRef.of(new OwnerId("resync"), ResourceTypeId.of("function")), "source-provider-test");
    private static final FunctionLocator FUNCTION = new FunctionLocator(RESOURCE);
    private static final CatalogBinding BINDING = new CatalogBinding(1, new ContentHash("0".repeat(64)), new ContentHash("1".repeat(64)));
    private static final TypeExpr TEXT = TypeExpr.named(TypeReference.of("builtin", "text"));
    private static final NodeInstanceId NODE = NodeInstanceId.of(UUID.fromString("22222222-2222-4222-8222-222222222222"));
    private static final PinId VALUE = PinId.of("value");

    @Test
    void missingSourceFailsClosed() {
        DurableFunctionSourceProvider provider = new DurableFunctionSourceProvider((function, revision) -> Optional.empty());

        assertTrue(provider.resolve(FUNCTION, new FunctionRevision(4)).isEmpty());
        assertTrue(provider.resolve(new FunctionSourceLocator(FUNCTION, new FunctionRevision(4), hash("a"))).isEmpty());
    }

    @Test
    void mismatchedLocatorAndRevisionFailClosed() {
        FunctionSourceDocument source = source(new FunctionRevision(4), OpaqueData.empty());
        FunctionSourceRecord wrongLocator = new FunctionSourceRecord(
            new FunctionLocator(new ServerResourceLocator(SERVER,
                ContractRef.of(new OwnerId("resync"), ResourceTypeId.of("function")), "other-function")),
            new FunctionRevision(4), source.checksum(), source);
        FunctionSourceRecord wrongRevision = new FunctionSourceRecord(FUNCTION, new FunctionRevision(5), source.checksum(), source);
        DurableFunctionSourceProvider locatorProvider = new DurableFunctionSourceProvider(
            (function, revision) -> Optional.of(wrongLocator));
        DurableFunctionSourceProvider revisionProvider = new DurableFunctionSourceProvider(
            (function, revision) -> Optional.of(wrongRevision));

        assertTrue(locatorProvider.resolve(FUNCTION, new FunctionRevision(4)).isEmpty());
        assertTrue(revisionProvider.resolve(FUNCTION, new FunctionRevision(4)).isEmpty());
    }

    @Test
    void mismatchedOrMissingContentHashFailsClosed() {
        FunctionRevision revision = new FunctionRevision(4);
        FunctionSourceDocument source = source(revision, OpaqueData.empty());
        FunctionSourceRecord wrongHash = new FunctionSourceRecord(FUNCTION, revision, hash("a"), source);
        FunctionSourceRecord missingHash = new FunctionSourceRecord(FUNCTION, revision, null, source);
        DurableFunctionSourceProvider wrongHashProvider = new DurableFunctionSourceProvider(
            (function, requestedRevision) -> Optional.of(wrongHash));
        DurableFunctionSourceProvider missingHashProvider = new DurableFunctionSourceProvider(
            (function, requestedRevision) -> Optional.of(missingHash));

        assertTrue(wrongHashProvider.resolve(FUNCTION, revision).isEmpty());
        assertTrue(missingHashProvider.resolve(FUNCTION, revision).isEmpty());
        assertTrue(wrongHashProvider.resolve(new FunctionSourceLocator(FUNCTION, revision, source.checksum())).isEmpty());
    }

    @Test
    void exactLocatorAndHashResolveWhileUnknownFieldsRoundTrip() {
        FunctionRevision revision = new FunctionRevision(4);
        OpaqueData unknown = OpaqueData.of(Map.of("future", Map.of("keep", true), "futureList", List.of("one", 2)));
        FunctionSourceDocument source = source(revision, unknown);
        FunctionSourceRecord record = new FunctionSourceRecord(source);
        ImmutableFunctionSourceStore store = ImmutableFunctionSourceStore.of(record);
        DurableFunctionSourceProvider provider = new DurableFunctionSourceProvider(store);

        Optional<FunctionSourceDocument> resolved = provider.resolve(new FunctionSourceLocator(FUNCTION, revision, source.checksum()));

        assertTrue(resolved.isPresent());
        assertEquals(unknown, resolved.get().unknown());
        assertEquals(source.checksum(), resolved.get().checksum());
        assertTrue(resolved.get().canonicalJson().contains("\"future\""));
        assertEquals(1, store.size());
    }

    @Test
    void storeRejectsDuplicateTypedIdentity() {
        FunctionRevision revision = new FunctionRevision(4);
        FunctionSourceDocument source = source(revision, OpaqueData.empty());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> new ImmutableFunctionSourceStore(List.of(new FunctionSourceRecord(source), new FunctionSourceRecord(source))));
    }

    @Test
    void locatorRequiresEveryAuthorityField() {
        FunctionRevision revision = new FunctionRevision(4);

        assertThrowsNull(() -> new FunctionSourceLocator(null, revision, hash("a")));
        assertThrowsNull(() -> new FunctionSourceLocator(FUNCTION, null, hash("a")));
        assertThrowsNull(() -> new FunctionSourceLocator(FUNCTION, revision, null));
    }

    private static FunctionSourceDocument source(FunctionRevision revision, OpaqueData unknown) {
        GraphNode node = new GraphNode(NODE, ContractRef.of(new OwnerId("typed"), NodeId.of("source")), 1,
            Map.of(VALUE, new PinValue(VALUE, TypedValue.value(TEXT, "hello"))));
        GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), RESOURCE, revision.value(), BINDING,
            Set.of(), List.of(node), List.of(), List.of(), List.of(), OpaqueData.empty());
        FunctionSignature signature = new FunctionSignature(FUNCTION, revision, List.of(), List.of());
        return new FunctionSourceMaterializer().materialize(signature, graph, unknown).source();
    }

    private static ContentHash hash(String value) {
        return new ContentHash(value.repeat(64).substring(0, 64));
    }

    private static void assertThrowsNull(Runnable action) {
        assertThrows(NullPointerException.class, action::run);
    }
}
