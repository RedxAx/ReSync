package restudio.resync.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.core.Session;
import restudio.resync.flow.authoring.AuthoringTemplatePayload;
import restudio.resync.flow.cache.CatalogAuthoringPublication;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogPublicationReceiptStore;
import restudio.resync.flow.catalog.CatalogActivationAuthority;
import restudio.resync.flow.catalog.CatalogCanonicalizer;
import restudio.resync.flow.catalog.CatalogContractRange;
import restudio.resync.flow.catalog.CatalogContribution;
import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.catalog.CatalogProvenance;
import restudio.resync.flow.catalog.CatalogRuntimeActivation;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.command.CommandGraphContract;
import restudio.resync.flow.graph.GraphConnection;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.AuthoringTemplateRequest;
import restudio.resync.flow.protocol.AuthoringTemplateResponse;
import restudio.resync.flow.protocol.ProtocolRejectionCode;
import restudio.resync.flow.runtime.RuntimeFailureContract;
import restudio.resync.flow.runtime.RuntimeRegistrySnapshot;
import restudio.resync.flow.runtime.RuntimeSemantics;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.protocol.ReSyncProtocolContract;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthoringTemplateProducerTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final OwnerId CORE_OWNER = OwnerId.of("restudio.resync");
    private static final CatalogVersion SCHEMA = new CatalogVersion(1, 0);

    @TempDir
    Path temporary;

    @Test
    void rejectsRetiredReceiptBeforeRedispatchAndAcceptsCurrentConvergedReceipt() {
        RuntimeRegistrySnapshot runtime = RuntimeRegistrySnapshot.empty();
        CatalogSnapshot catalog = emptyCatalog(runtime);
        CatalogBinding binding = new CatalogBinding(catalog.generation(), catalog.contentChecksum(),
            catalog.bindingManifestHash());
        CatalogCacheKey key = CatalogCacheKey.of(SERVER, binding);
        CatalogAuthoringPublication authoring = CatalogAuthoringPublication.project(binding, catalog, Set.of());
        CatalogCachePublication publication = new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key,
            binding, 1L, List.of(), authoring, Map.of());
        CatalogRuntimeActivation.ActivationRecord activation = new CatalogRuntimeActivation.ActivationRecord(
            catalog, runtime, Optional.of(key));
        CatalogPublicationReceiptStore receipts = new CatalogPublicationReceiptStore(temporary,
            temporary.resolve(CatalogPublicationReceiptStore.FILE_NAME));
        receipts.recordDispatch("client", "retired-session", publication);
        assertTrue(receipts.acknowledgeClientReceipt("client", "retired-session", key, publication.revision()).accepted());
        assertTrue(receipts.acknowledgeCacheApplication("client", "retired-session", key,
            publication.revision()).accepted());

        Session replacement = new Session("replacement-session", "client", null);
        AtomicReference<Optional<CatalogCachePublication>> acknowledged = new AtomicReference<>(Optional.empty());
        AuthoringTemplateProducer producer = new AuthoringTemplateProducer(SERVER, () -> activation,
            CatalogActivationAuthority::freshInstall, receipts, session -> acknowledged.get(),
            session -> session == replacement);
        AuthoringTemplateRequest request = request(key, publication);

        AuthoringTemplateProducer.Rejected retired = assertThrows(AuthoringTemplateProducer.Rejected.class,
            () -> producer.produce(request, replacement, Set.of()));
        assertEquals(ProtocolRejectionCode.RESOURCE_REVISION_CONFLICT, retired.code());

        assertTrue(receipts.adopt("client", "replacement-session").accepted());
        assertThrows(AuthoringTemplateProducer.Rejected.class,
            () -> producer.produce(request, replacement, Set.of()));

        acknowledged.set(Optional.of(publication));
        AuthoringTemplateResponse response = producer.produce(request, replacement, Set.of());
        assertEquals(key, response.publicationKey());
        assertEquals(AuthoringTemplatePayload.Kind.FLOW, response.payload().kind());
    }

    @Test
    void commandTemplateUsesOnlyTheExactActiveHandlerOwnedCanonicalStart() {
        RuntimeRegistrySnapshot runtime = RuntimeRegistrySnapshot.empty();
        CatalogSnapshot catalog = commandCatalog(runtime);
        CatalogBinding binding = new CatalogBinding(catalog.generation(), catalog.contentChecksum(),
            catalog.bindingManifestHash());
        CatalogCacheKey key = CatalogCacheKey.of(SERVER, binding);
        CatalogAuthoringPublication authoring = CatalogAuthoringPublication.project(binding, catalog, Set.of());
        CatalogCachePublication publication = new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key,
            binding, 1L, List.of(), authoring, Map.of());
        CatalogRuntimeActivation.ActivationRecord activation = new CatalogRuntimeActivation.ActivationRecord(
            catalog, runtime, Optional.of(key));
        CatalogPublicationReceiptStore receipts = new CatalogPublicationReceiptStore(temporary,
            temporary.resolve(CatalogPublicationReceiptStore.FILE_NAME));
        Session session = new Session("command-session", "client", null);
        receipts.recordDispatch("client", "command-session", publication);
        assertTrue(receipts.acknowledgeClientReceipt("client", "command-session", key, publication.revision()).accepted());
        assertTrue(receipts.acknowledgeCacheApplication("client", "command-session", key, publication.revision()).accepted());
        AuthoringTemplateProducer producer = new AuthoringTemplateProducer(SERVER, () -> activation,
            CatalogActivationAuthority::freshInstall, receipts, ignored -> Optional.of(publication));
        ServerResourceLocator resource = new ServerResourceLocator(SERVER,
            ContractRef.of(CORE_OWNER, ResourceTypeId.of("command")), "canonical-command");

        AuthoringTemplateResponse response = producer.produce(
            new AuthoringTemplateRequest(resource, key, publication.authoringPublicationChecksum()), session, Set.of());

        assertEquals(AuthoringTemplatePayload.Kind.COMMAND, response.payload().kind());
        assertEquals(List.of(CommandGraphContract.CANONICAL_START), response.payload().graphDocument().nodes().stream()
            .map(node -> node.definition()).toList());
    }

    @Test
    void currentCatalogContractKeepsEveryGraphTemplateOnTheCurrentGraphSchemaAndBinding() {
        RuntimeRegistrySnapshot runtime = RuntimeRegistrySnapshot.empty();
        CatalogSnapshot catalog = currentAuthoringCatalog(runtime);
        CatalogBinding binding = new CatalogBinding(catalog.generation(), catalog.contentChecksum(),
            catalog.bindingManifestHash());
        CatalogCacheKey key = CatalogCacheKey.of(SERVER, binding);
        CatalogAuthoringPublication authoring = CatalogAuthoringPublication.project(binding, catalog, Set.of());
        CatalogCachePublication publication = new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key,
            binding, 1L, List.of(), authoring, Map.of());
        CatalogRuntimeActivation.ActivationRecord activation = new CatalogRuntimeActivation.ActivationRecord(
            catalog, runtime, Optional.of(key));
        CatalogPublicationReceiptStore receipts = new CatalogPublicationReceiptStore(temporary,
            temporary.resolve(CatalogPublicationReceiptStore.FILE_NAME));
        Session session = new Session("current-contract-session", "client", null);
        receipts.recordDispatch("client", "current-contract-session", publication);
        assertTrue(receipts.acknowledgeClientReceipt("client", "current-contract-session", key,
            publication.revision()).accepted());
        assertTrue(receipts.acknowledgeCacheApplication("client", "current-contract-session", key,
            publication.revision()).accepted());
        AuthoringTemplateProducer producer = new AuthoringTemplateProducer(SERVER, () -> activation,
            CatalogActivationAuthority::freshInstall, receipts, ignored -> Optional.of(publication));

        for (String type : List.of("flow", "command", "function")) {
            ServerResourceLocator resource = new ServerResourceLocator(SERVER,
                ContractRef.of(CORE_OWNER, ResourceTypeId.of(type)), "current-" + type);
            AuthoringTemplateResponse response = producer.produce(
                new AuthoringTemplateRequest(resource, key, publication.authoringPublicationChecksum()), session,
                Set.of());
            GraphDocument graph = response.payload().kind() == AuthoringTemplatePayload.Kind.FUNCTION
                ? response.payload().functionSourceDocument().graph()
                : response.payload().graphDocument();

            assertEquals(GraphDocument.CURRENT_SCHEMA_VERSION, graph.schemaVersion());
            assertEquals(binding, graph.catalogBinding());
            assertEquals(binding, response.catalogBinding());
            if ("function".equals(type)) {
                assertEquals(2, graph.nodes().size());
                assertEquals(120, graph.nodes().getFirst().x());
                assertEquals(120, graph.nodes().getFirst().y());
                assertEquals(380, graph.nodes().get(1).x());
                assertEquals(120, graph.nodes().get(1).y());
                assertEquals(1, graph.connections().size());
                GraphConnection connection = graph.connections().getFirst();
                assertEquals(graph.nodes().getFirst().instanceId(), connection.source().nodeId());
                assertEquals("flow", connection.source().pinId().canonicalText());
                assertEquals(graph.nodes().get(1).instanceId(), connection.target().nodeId());
                assertEquals("flow", connection.target().pinId().canonicalText());
            }
        }
    }

    private static AuthoringTemplateRequest request(CatalogCacheKey key, CatalogCachePublication publication) {
        ServerResourceLocator resource = new ServerResourceLocator(SERVER,
            ContractRef.of(OwnerId.of("restudio.resync"), new ResourceTypeId("flow")), "main");
        return new AuthoringTemplateRequest(resource, key, publication.authoringPublicationChecksum());
    }

    private static CatalogSnapshot commandCatalog(RuntimeRegistrySnapshot runtime) {
        CatalogNodeDescriptor canonical = commandStart("event.command", Map.of());
        CatalogNodeDescriptor legacy = commandStart("event.resync.command", Map.of("coreRole", "command-start"));
        CatalogContribution contribution = CatalogContribution.builder(CORE_OWNER, "1.0.0",
                new CatalogContractRange(SCHEMA, SCHEMA),
                CatalogProvenance.fromText(CatalogProvenance.SourceKind.BUNDLED, "test://command-template", "1.0.0",
                    "test", "command-template"))
            .definitions(List.of(canonical, legacy))
            .build();
        List<CatalogContribution> contributions = List.of(contribution);
        ContentHash bindingHash = runtime.bindingManifestHash();
        ContentHash checksum = CatalogCanonicalizer.contentChecksum(1L, SCHEMA, contributions, Set.of(), List.of());
        String canonicalContent = CatalogCanonicalizer.canonicalSnapshotContent(1L, SCHEMA, contributions, Set.of(),
            List.of(), bindingHash);
        return new CatalogSnapshot(1L, SCHEMA, checksum, bindingHash, Set.of(), contributions, List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), canonicalContent, bindingHash);
    }

    static CatalogSnapshot currentAuthoringCatalog(RuntimeRegistrySnapshot runtime) {
        CatalogVersion contract = ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION;
        CatalogContribution contribution = CatalogContribution.builder(CORE_OWNER, "1.0.0",
                new CatalogContractRange(contract, contract),
                CatalogProvenance.fromText(CatalogProvenance.SourceKind.BUNDLED, "test://current-authoring-template",
                    "1.0.0", "test", "current-authoring-template"))
            .definitions(List.of(
                commandStart("event.command", Map.of()),
                functionBoundary("function_start", "Function Start",
                    "Starts a new function graph with its declared inputs.", "function-start"),
                functionBoundary("function_end", "Function End",
                    "Ends a new function graph with its declared outputs.", "function-end")))
            .build();
        List<CatalogContribution> contributions = List.of(contribution);
        ContentHash bindingHash = runtime.bindingManifestHash();
        ContentHash checksum = CatalogCanonicalizer.contentChecksum(1L, contract, contributions, Set.of(), List.of());
        String canonicalContent = CatalogCanonicalizer.canonicalSnapshotContent(1L, contract, contributions, Set.of(),
            List.of(), bindingHash);
        return new CatalogSnapshot(1L, contract, checksum, bindingHash, Set.of(), contributions, List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), canonicalContent, bindingHash);
    }

    private static CatalogSnapshot emptyCatalog(RuntimeRegistrySnapshot runtime) {
        CatalogSnapshot base = CatalogSnapshot.empty(SCHEMA);
        ContentHash bindingHash = runtime.bindingManifestHash();
        String canonicalContent = CatalogCanonicalizer.canonicalSnapshotContent(base.generation(),
            base.contractVersion(), base.contributions(), base.minimumClientCapabilities(), base.diagnostics(),
            bindingHash);
        return new CatalogSnapshot(base.generation(), base.contractVersion(), base.contentChecksum(), bindingHash,
            base.minimumClientCapabilities(), base.contributions(), base.definitions(), base.types(),
            base.conversions(), base.categories(), base.inspectors(), base.capabilities(), base.runtimeRequirements(),
            base.optionSources(), base.validators(), base.editors(), base.previews(), base.migrations(),
            base.provenance(), base.diagnostics(), canonicalContent, bindingHash);
    }

    private static CatalogNodeDescriptor commandStart(String id, Map<String, Object> metadata) {
        ContractRef<CapabilityId> capability = ContractRef.of(CORE_OWNER, CapabilityId.of("flow.event"));
        return CatalogNodeDescriptor.builder(id)
            .displayName("Command Start")
            .description("Starts one typed command graph from an accepted player command.")
            .category(ContractRef.of(CORE_OWNER, CapabilityId.of("event")))
            .handler(new CatalogNodeDescriptor.Handler(capability,
                ContractRef.of(CORE_OWNER, OperationId.of("event.command"))))
            .semantics(commandSemantics())
            .metadata(metadata)
            .build();
    }

    private static CatalogNodeDescriptor functionBoundary(String id, String displayName, String description,
                                                          String role) {
        ContractRef<CapabilityId> capability = ContractRef.of(CORE_OWNER, CapabilityId.of("flow.function"));
        return CatalogNodeDescriptor.builder(id)
            .displayName(displayName)
            .description(description)
            .category(ContractRef.of(CORE_OWNER, CapabilityId.of("function")))
            .handler(new CatalogNodeDescriptor.Handler(capability,
                ContractRef.of(CORE_OWNER, OperationId.of(id))))
            .pins(List.of(new CatalogNodeDescriptor.Pin("flow",
                "function-start".equals(role) ? CatalogNodeDescriptor.Direction.OUTPUT
                    : CatalogNodeDescriptor.Direction.INPUT,
                TypeExpr.named(TypeReference.of("builtin", "execution")), "Flow", "Continues function execution.",
                CatalogNodeDescriptor.Requirement.REQUIRED, capability)))
            .semantics(commandSemantics())
            .metadata(Map.of("functionBoundary", Map.of(
                "role", "function-start".equals(role) ? "inputs" : "outputs",
                "flowPin", "flow")))
            .build();
    }

    private static RuntimeSemantics commandSemantics() {
        TypeExpr failure = TypeExpr.named(TypeReference.of("builtin", "string"));
        return new RuntimeSemantics(RuntimeSemantics.Effect.PURE, RuntimeSemantics.ThreadMode.CURRENT,
            ContractRef.of(CORE_OWNER, CapabilityId.of("authorization")), RuntimeSemantics.Cancellation.NONE,
            0L, 0L, 0L, RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER,
            RuntimeSemantics.Idempotency.INTRINSIC, RuntimeSemantics.Audit.NONE,
            RuntimeSemantics.Confirmation.NONE, RuntimeSemantics.SensitiveData.NONE,
            RuntimeSemantics.Determinism.DETERMINISTIC, Set.of(), Set.of("failed"), Set.of(),
            new RuntimeFailureContract(failure, Set.of("RUNTIME.FAILURE"), Set.of("failed"),
                RuntimeFailureContract.CommitBoundary.NO_MUTATION), Set.of(), Set.of());
    }
}
