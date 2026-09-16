package restudio.resync.server;

import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.core.Session;
import restudio.resync.flow.authoring.AuthoringTemplatePayload;
import restudio.resync.flow.cache.CatalogAuthoringPublication;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogPublicationReceipt;
import restudio.resync.flow.cache.CatalogPublicationReceiptStore;
import restudio.resync.flow.catalog.CatalogActivationAuthority;
import restudio.resync.flow.catalog.CatalogOwned;
import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.catalog.CatalogRuntimeActivation;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.command.CommandGraphContract;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.FunctionBoundaryPins;
import restudio.resync.flow.graph.GraphConnection;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphEndpoint;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ConnectionId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.AuthoringTemplateCodec;
import restudio.resync.flow.protocol.AuthoringTemplateRequest;
import restudio.resync.flow.protocol.AuthoringTemplateResponse;
import restudio.resync.flow.protocol.ProtocolRejectionCode;
import restudio.resync.flow.type.TypeExpr;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class AuthoringTemplateProducer {
    public static final String ACTION = "resource.authoring-template";
    public static final String AUTHORING_CHECKSUM_REQUIRED = "AUTHORING_CHECKSUM_REQUIRED";
    private static final OwnerId CORE_OWNER = OwnerId.of("restudio.resync");
    public static final ContractRef<OperationId> OPERATION = ContractRef.of(
        CORE_OWNER, OperationId.of(ACTION));
    public static final ContractRef<ResourceTypeId> REQUEST_TYPE = ContractRef.of(
        CORE_OWNER, ResourceTypeId.of("authoring-template.request"));
    public static final ContractRef<ResourceTypeId> RESPONSE_TYPE = ContractRef.of(
        CORE_OWNER, ResourceTypeId.of("authoring-template.response"));

    private static final Set<String> RESOURCE_TYPES = Set.of("flow", "function", "command");
    private static final String REQUEST_VALUE = "request";
    private final ServerId serverId;
    private final Supplier<CatalogRuntimeActivation.ActivationRecord> activationSupplier;
    private final Supplier<CatalogActivationAuthority> authoritySupplier;
    private final CatalogPublicationReceiptStore receiptStore;
    private final Function<Session, Optional<CatalogCachePublication>> publicationSupplier;
    private final Predicate<Session> currentSessionGuard;

    public AuthoringTemplateProducer(ServerId serverId,
                                     Supplier<CatalogRuntimeActivation.ActivationRecord> activationSupplier,
                                     Supplier<CatalogActivationAuthority> authoritySupplier) {
        this(serverId, activationSupplier, authoritySupplier, null, null, null);
    }

    public AuthoringTemplateProducer(ServerId serverId,
                                     Supplier<CatalogRuntimeActivation.ActivationRecord> activationSupplier,
                                     Supplier<CatalogActivationAuthority> authoritySupplier,
                                     CatalogPublicationReceiptStore receiptStore) {
        this(serverId, activationSupplier, authoritySupplier, receiptStore, null, null);
    }

    public AuthoringTemplateProducer(ServerId serverId,
                                     Supplier<CatalogRuntimeActivation.ActivationRecord> activationSupplier,
                                     Supplier<CatalogActivationAuthority> authoritySupplier,
                                     CatalogPublicationReceiptStore receiptStore,
                                     Function<Session, Optional<CatalogCachePublication>> publicationSupplier) {
        this(serverId, activationSupplier, authoritySupplier, receiptStore, publicationSupplier, null);
    }

    public AuthoringTemplateProducer(ServerId serverId,
                                     Supplier<CatalogRuntimeActivation.ActivationRecord> activationSupplier,
                                     Supplier<CatalogActivationAuthority> authoritySupplier,
                                     CatalogPublicationReceiptStore receiptStore,
                                     Function<Session, Optional<CatalogCachePublication>> publicationSupplier,
                                     Predicate<Session> currentSessionGuard) {
        this.serverId = Objects.requireNonNull(serverId, "Authoring template server ID is required");
        this.activationSupplier = Objects.requireNonNull(activationSupplier, "Authoring template activation supplier is required");
        this.authoritySupplier = Objects.requireNonNull(authoritySupplier, "Authoring template authority supplier is required");
        this.receiptStore = receiptStore;
        this.publicationSupplier = publicationSupplier;
        this.currentSessionGuard = currentSessionGuard != null ? currentSessionGuard
            : publicationSupplier == null ? null : session -> publicationSupplier.apply(session).isPresent();
    }

    public AuthoringTemplateRequest decodeRequest(Map<String, Object> values) {
        if (values == null || values.size() != 1 || !values.containsKey(REQUEST_VALUE)) {
            throw rejected(ProtocolRejectionCode.INVALID_BODY, "Authoring template request values are invalid");
        }
        Object raw = values.get(REQUEST_VALUE);
        try {
            return AuthoringTemplateCodec.INSTANCE.decodeRequest(JsonValue.fromJava(raw));
        } catch (RuntimeException failure) {
            throw rejected(ProtocolRejectionCode.INVALID_PAYLOAD, "Authoring template request is malformed", failure);
        }
    }

    public AuthoringTemplateResponse produce(AuthoringTemplateRequest request) {
        return produce(request, null, null);
    }

    public AuthoringTemplateResponse produce(AuthoringTemplateRequest request, Session session,
                                             Set<ContractRef<CapabilityId>> supportedCapabilities) {
        Objects.requireNonNull(request, "Authoring template request is required");
        if (!request.hasAcknowledgedAuthoringPublicationChecksum()) {
            throw rejected(ProtocolRejectionCode.INVALID_PAYLOAD, AUTHORING_CHECKSUM_REQUIRED);
        }
        ServerResourceLocator resource = request.resource();
        if (!serverId.equals(resource.serverId())) {
            throw rejected(ProtocolRejectionCode.AUTHORIZATION_DENIED, "Authoring template resource is owned by another server");
        }
        if (!CORE_OWNER.equals(resource.owner())) {
            throw rejected(ProtocolRejectionCode.RESOURCE_TYPE_UNAVAILABLE,
                "Authoring templates are available only for server-owned Core graph resources");
        }
        String type = resource.resourceType().value();
        if (!RESOURCE_TYPES.contains(type)) {
            throw rejected(ProtocolRejectionCode.RESOURCE_TYPE_UNAVAILABLE, "Authoring templates are unavailable for this resource type");
        }
        requireSessionAcknowledgement(request, session);
        if (supportedCapabilities == null) {
            throw rejected(ProtocolRejectionCode.RESOURCE_AUTHORIZATION_DENIED,
                "The client authoring capability set is unavailable for this session");
        }
        Set<ContractRef<CapabilityId>> requestedCapabilities;
        try {
            requestedCapabilities = Set.copyOf(supportedCapabilities);
        } catch (RuntimeException failure) {
            throw rejected(ProtocolRejectionCode.RESOURCE_AUTHORIZATION_DENIED,
                "The client authoring capability set is invalid", failure);
        }
        CatalogRuntimeActivation.ActivationRecord activation = activeActivation();
        CatalogSnapshot catalog = activation.catalog();
        CatalogCacheKey activeKey = activation.publicationKey()
            .orElseThrow(() -> rejected(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE,
                "An acknowledged catalog publication is required before requesting an authoring template"));
        CatalogCachePublication activePublication = activePublication(session);
        if (!activeKey.equals(activePublication.key()) || !activePublication.hasAuthoringPublication()) {
            throw rejected(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE,
                "The active catalog publication has no exact authoring projection");
        }
        CatalogBinding binding = activePublication.catalogBinding();
        CatalogAuthoringPublication authoringPublication = activePublication.authoringPublication();
        ContentHash authoringChecksum = activePublication.authoringPublicationChecksum();
        if (binding == null || !binding.equals(activeKey.catalogBinding()) || authoringPublication == null
            || authoringChecksum == null || !authoringChecksum.equals(
                request.acknowledgedAuthoringPublicationChecksum())
            || !activeKey.equals(request.acknowledgedCatalogKey())) {
            throw rejected(ProtocolRejectionCode.RESOURCE_REVISION_CONFLICT,
                "The acknowledged catalog authoring publication is stale; refresh the catalog and retry");
        }
        requireAuthority();
        if (!authoringPublication.compatible()) {
            throw rejected(ProtocolRejectionCode.UNSUPPORTED_GENERATION,
                "The active catalog authoring projection is incompatible with this client");
        }
        if (!authoringPublication.advertisedEditCapabilities().containsAll(requestedCapabilities)) {
            throw rejected(ProtocolRejectionCode.RESOURCE_AUTHORIZATION_DENIED,
                "The requested authoring capabilities are not editable in the active projection");
        }
        AuthoringTemplatePayload payload = payload(resource, catalog, binding);
        return AuthoringTemplateResponse.of(resource, activeKey, binding, payload, authoringChecksum,
            authoringPublication.advertisedEditCapabilities());
    }

    public Map<String, Object> encodeResponse(AuthoringTemplateResponse response) {
        return javaMap(AuthoringTemplateCodec.INSTANCE.encodeResponse(response));
    }

    private CatalogRuntimeActivation.ActivationRecord activeActivation() {
        CatalogRuntimeActivation.ActivationRecord activation;
        try {
            activation = activationSupplier.get();
        } catch (RuntimeException failure) {
            throw rejected(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE,
                "The active catalog runtime activation is unavailable", failure);
        }
        if (activation == null) {
            throw rejected(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE,
                "The active catalog runtime activation is unavailable");
        }
        return activation;
    }

    private CatalogCachePublication activePublication(Session session) {
        if (publicationSupplier == null) {
            throw rejected(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE,
                "The active catalog publication is unavailable");
        }
        try {
            return publicationSupplier.apply(session)
                .orElseThrow(() -> rejected(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE,
                    "The active catalog publication is unavailable"));
        } catch (Rejected rejection) {
            throw rejection;
        } catch (RuntimeException failure) {
            throw rejected(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE,
                "The active catalog publication is unavailable", failure);
        }
    }

    private void requireAuthority() {
        CatalogActivationAuthority authority;
        try {
            authority = authoritySupplier.get();
        } catch (RuntimeException failure) {
            throw rejected(ProtocolRejectionCode.RESOURCE_AUTHORIZATION_DENIED,
                "The active catalog authority could not be verified", failure);
        }
        if (authority == null || !authority.approved()) {
            throw rejected(ProtocolRejectionCode.RESOURCE_AUTHORIZATION_DENIED,
                "The active catalog authority is not approved");
        }
    }

    private AuthoringTemplatePayload payload(ServerResourceLocator resource, CatalogSnapshot catalog,
                                             CatalogBinding binding) {
        GraphDocument graph = graph(resource, catalog, binding);
        return switch (resource.resourceType().value()) {
            case "flow" -> AuthoringTemplatePayload.flow(graph);
            case "command" -> AuthoringTemplatePayload.command(graph);
            case "function" -> AuthoringTemplatePayload.function(functionSource(resource, graph));
            default -> throw rejected(ProtocolRejectionCode.RESOURCE_TYPE_UNAVAILABLE,
                "Authoring templates are unavailable for this resource type");
        };
    }

    private GraphDocument graph(ServerResourceLocator resource, CatalogSnapshot catalog, CatalogBinding binding) {
        List<GraphNode> nodes;
        List<GraphConnection> connections = List.of();
        switch (resource.resourceType().value()) {
            case "flow" -> nodes = List.of();
            case "command" -> nodes = List.of(boundaryNode(commandStart(catalog), resource, "command-start", 120, 120));
            case "function" -> {
                CatalogOwned<CatalogNodeDescriptor> inputBoundary = functionBoundary(catalog, "function_start");
                CatalogOwned<CatalogNodeDescriptor> outputBoundary = functionBoundary(catalog, "function_end");
                GraphNode input = boundaryNode(inputBoundary, resource, "function-start", 120, 120);
                GraphNode output = boundaryNode(outputBoundary, resource, "function-end", 380, 120);
                PinId inputFlow = functionBoundaryFlowPin(inputBoundary, CatalogNodeDescriptor.Direction.OUTPUT);
                PinId outputFlow = functionBoundaryFlowPin(outputBoundary, CatalogNodeDescriptor.Direction.INPUT);
                GraphConnection connection = new GraphConnection(ConnectionId.deterministic(
                    "authoring-template|" + resource.canonicalText() + "|function-boundary-flow"),
                    new GraphEndpoint(input.instanceId(), inputFlow), new GraphEndpoint(output.instanceId(), outputFlow));
                nodes = List.of(input, output);
                connections = List.of(connection);
            }
            default -> throw rejected(ProtocolRejectionCode.RESOURCE_TYPE_UNAVAILABLE,
                "Authoring templates are unavailable for this resource type");
        }
        Set<ContractRef<CapabilityId>> requiredCapabilities = new LinkedHashSet<>();
        for (GraphNode node : nodes) {
            CatalogOwned<CatalogNodeDescriptor> owned = catalog.definition(node.definition()).orElseThrow(() ->
                rejected(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE,
                    "The active catalog boundary definition is unavailable"));
            requiredCapabilities.addAll(owned.descriptor().requiredCapabilities());
        }
        return new GraphDocument(GraphDocument.CURRENT_SCHEMA_VERSION, resource, 0L, binding, Set.copyOf(requiredCapabilities),
            nodes, connections, List.of(), List.of(), OpaqueData.empty());
    }

    private GraphNode boundaryNode(CatalogOwned<CatalogNodeDescriptor> owned, ServerResourceLocator resource,
                                   String role, double x, double y) {
        ContractRef<NodeId> definition = nodeReference(owned);
        NodeInstanceId instance = NodeInstanceId.deterministic(
            "authoring-template|" + resource.canonicalText() + "|" + role + "|" + definition.canonicalText());
        return new GraphNode(instance, definition, owned.descriptor().schemaVersion(), null, Map.of(), Map.of(),
            List.of(), List.of(), null, x, y, OpaqueData.empty());
    }

    private PinId functionBoundaryFlowPin(CatalogOwned<CatalogNodeDescriptor> owned,
                                          CatalogNodeDescriptor.Direction direction) {
        List<PinId> pins = owned.descriptor().pins().stream()
            .filter(pin -> pin.direction() == direction && isExecutionType(pin.type()))
            .map(CatalogNodeDescriptor.Pin::id)
            .toList();
        if (pins.size() != 1) {
            throw rejected(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE,
                "The active catalog function boundary has no unique execution pin");
        }
        return pins.getFirst();
    }

    private boolean isExecutionType(TypeExpr type) {
        return type instanceof TypeExpr.Named named && "execution".equals(named.reference().localId());
    }

    private CatalogOwned<CatalogNodeDescriptor> commandStart(CatalogSnapshot catalog) {
        return catalog.definition(CommandGraphContract.CANONICAL_START)
            .filter(owned -> owned.descriptor().lifecycle() == CatalogNodeDescriptor.Lifecycle.ACTIVE)
            .filter(this::handlerBelongsToDefinition)
            .orElseThrow(() -> rejected(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE,
                "The active catalog has no canonical command-start definition"));
    }

    private CatalogOwned<CatalogNodeDescriptor> functionBoundary(CatalogSnapshot catalog, String operation) {
        return catalog.definitions().stream()
            .filter(owned -> owned.descriptor().lifecycle() == CatalogNodeDescriptor.Lifecycle.ACTIVE)
            .filter(owned -> isFunctionBoundaryDefinition(owned, operation))
            .sorted(Comparator.comparing(owned -> owned.key().canonicalText()))
            .findFirst()
            .orElseThrow(() -> rejected(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE,
                "The active catalog has no canonical " + operation.replace('_', '-') + " definition"));
    }

    private boolean isFunctionBoundaryDefinition(CatalogOwned<CatalogNodeDescriptor> owned, String operation) {
        return FunctionBoundaryPins.isBoundary(owned, operation);
    }

    private boolean handlerBelongsToDefinition(CatalogOwned<CatalogNodeDescriptor> owned) {
        CatalogNodeDescriptor.Handler handler = owned.descriptor().handler();
        return owned.key().owner().equals(handler.capability().owner())
            && owned.key().owner().equals(handler.operation().owner());
    }

    private ContractRef<NodeId> nodeReference(CatalogOwned<CatalogNodeDescriptor> owned) {
        if (!(owned.key().id() instanceof NodeId id)) {
            throw rejected(ProtocolRejectionCode.RESOURCE_OPERATION_FAILED,
                "The active catalog contains an invalid node boundary identity");
        }
        return new ContractRef<>(owned.key().owner(), id);
    }

    private void requireSessionAcknowledgement(AuthoringTemplateRequest request, Session session) {
        if (receiptStore == null || session == null) {
            throw rejected(ProtocolRejectionCode.RESOURCE_AUTHORIZATION_DENIED,
                "A session-scoped catalog acknowledgement is required before requesting an authoring template");
        }
        if (!isCurrentSession(session)) {
            throw rejected(ProtocolRejectionCode.RESOURCE_REVISION_CONFLICT,
                "This session has not converged on the acknowledged catalog publication");
        }
        String sessionKey = sessionKey(session);
        String ownerToken = session.getSessionId();
        Optional<CatalogPublicationReceipt> receipt = receiptStore.baseline(sessionKey);
        if (receipt.isEmpty()
            || receipt.get().ownerState() != CatalogPublicationReceipt.OwnerState.CLAIMED
            || ownerToken == null || ownerToken.isBlank() || !ownerToken.equals(ownerToken.strip())
            || !sessionKey.equals(receipt.get().sessionKey())
            || !ownerToken.equals(receipt.get().ownerToken())
            || !receipt.get().publicationKey().equals(request.acknowledgedCatalogKey())
            || !receipt.get().converged()) {
            throw rejected(ProtocolRejectionCode.RESOURCE_REVISION_CONFLICT,
                "This session has not converged on the acknowledged catalog publication");
        }
    }

    private boolean isCurrentSession(Session session) {
        if (currentSessionGuard == null || session == null) {
            return false;
        }
        try {
            return currentSessionGuard.test(session);
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private String sessionKey(Session session) {
        String clientId = session.getClientId();
        if (clientId != null && !clientId.isBlank()) {
            return clientId.strip();
        }
        String sessionId = session.getSessionId();
        return sessionId == null ? "" : sessionId.strip();
    }

    private FunctionSourceDocument functionSource(ServerResourceLocator resource, GraphDocument graph) {
        FunctionSignature signature = new FunctionSignature(new FunctionLocator(resource), FunctionRevision.initial(),
            List.of(), List.of());
        return new FunctionSourceDocument(signature, graph);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> javaMap(JsonValue value) {
        if (!(value instanceof JsonValue.JsonObject object)) {
            throw new IllegalArgumentException("Authoring template response must be an object");
        }
        return (Map<String, Object>) object.toJava();
    }

    private static Rejected rejected(ProtocolRejectionCode code, String message) {
        return new Rejected(code, message);
    }

    private static Rejected rejected(ProtocolRejectionCode code, String message, Throwable cause) {
        return new Rejected(code, message, cause);
    }

    public static final class Rejected extends IllegalArgumentException {
        private final ProtocolRejectionCode code;

        public Rejected(ProtocolRejectionCode code, String message) {
            super(message);
            this.code = Objects.requireNonNull(code, "Authoring template rejection code is required");
        }

        public Rejected(ProtocolRejectionCode code, String message, Throwable cause) {
            super(message, cause);
            this.code = Objects.requireNonNull(code, "Authoring template rejection code is required");
        }

        public ProtocolRejectionCode code() {
            return code;
        }
    }
}
