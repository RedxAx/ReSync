package restudio.resync.server;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import restudio.flow.data.FlowOperationResult;
import restudio.flow.data.FlowResourceReference;
import restudio.resync.Log;
import restudio.resync.core.ConnectionInfo;
import restudio.resync.core.Session;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.CanonicalPayload;
import restudio.resync.flow.protocol.AuthoringTemplateRequest;
import restudio.resync.flow.protocol.AuthoringTemplateResponse;
import restudio.resync.flow.protocol.OptionPage;
import restudio.resync.flow.protocol.OptionQuery;
import restudio.resync.flow.protocol.ProtocolBody;
import restudio.resync.flow.protocol.ProtocolEnvelope;
import restudio.resync.flow.protocol.ProtocolRejectionCode;
import restudio.resync.flow.protocol.ResourceActivateRequest;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.protocol.ResourceCreateRequest;
import restudio.resync.flow.protocol.ResourceDeleteRequest;
import restudio.resync.flow.protocol.ResourceDocument;
import restudio.resync.flow.protocol.ResourceDuplicateRequest;
import restudio.resync.flow.protocol.ResourceListRequest;
import restudio.resync.flow.protocol.ResourceLoadRequest;
import restudio.resync.flow.protocol.ResourceMoveRequest;
import restudio.resync.flow.protocol.ResourceOperation;
import restudio.resync.flow.protocol.ResourceOperationKind;
import restudio.resync.flow.protocol.ResourcePage;
import restudio.resync.flow.protocol.ResourceQueryRequest;
import restudio.resync.flow.protocol.ResourceRenameRequest;
import restudio.resync.flow.protocol.ResourceSaveRequest;
import restudio.resync.flow.protocol.ResourceSubscribeRequest;
import restudio.resync.flow.resource.ResourcePayloadCodec;
import restudio.resync.flow.resource.ResourcePayloadCodecs;
import restudio.resync.flow.sync.FlowResourceMetadata;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.modules.flow.FlowResourceAdapter;
import restudio.resync.modules.flow.FlowResourceRegistry;
import restudio.resync.protocol.ReSyncProtocolContract;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class FlowResourceProtocolEnvelopeHandler implements ProtocolEnvelopeHandler {
    private static final CatalogVersion CONTRACT_VERSION = ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_MINIMUM_VERSION;
    private static final OwnerId PROTOCOL_OWNER = new OwnerId("restudio.resync");
    private static final Set<String> CORE_RESOURCE_TYPES = Set.of("flow", "function", "command");
    private static final ContractRef<ResourceTypeId> DOCUMENT_TYPE = ContractRef.of(PROTOCOL_OWNER, new ResourceTypeId("resource.document"));
    private static final ContractRef<ResourceTypeId> PAGE_TYPE = ContractRef.of(PROTOCOL_OWNER, new ResourceTypeId("resource.page"));
    private static final Set<String> SUPPORTED_CAPABILITIES = Set.of(
        "resources", "resource_revisions", "resource_events", "opaque_resources", "authorization", "asset_integrity",
        "transaction_recovery", "deltas", "diagnostics", "resource_activation", "resource_create_presentation",
        "option_queries"
    );
    private static final ContractRef<CapabilityId> AUTHORING_CAPABILITY = ContractRef.of(PROTOCOL_OWNER,
        new CapabilityId("catalog_authoring"));
    private final FlowResourceRegistry registry;
    private final ServerId serverId;
    private final ProtocolResourceMutationAuthority mutationAuthority;
    private final ProtocolResourceAuthorizer authorizer;
    private final AuthorityEpoch authorityEpoch;
    private final AuthoringTemplateProducer authoringTemplateProducer;
    private final OptionQueryAuthority optionQueryAuthority;
    private final ProviderOptionQueryService optionQueryService;
    private final FlowResourceOptionQueryAdapter optionQueryAdapter;
    private final Gson gson = new Gson();
    private final ResourcePayloadCodec<Map<String, Object>> payloadCodec = ResourcePayloadCodecs.json();

    public FlowResourceProtocolEnvelopeHandler(FlowResourceRegistry registry, ServerId serverId) {
        this(registry, serverId, ProtocolResourceMutationAuthority.failClosed(), ProtocolResourceAuthorizer.serverGranted(),
            requireExplicitAuthorityEpoch());
    }

    public FlowResourceProtocolEnvelopeHandler(FlowResourceRegistry registry, ServerId serverId,
                                               ProtocolResourceMutationAuthority mutationAuthority) {
        this(registry, serverId, mutationAuthority, ProtocolResourceAuthorizer.serverGranted(), requireExplicitAuthorityEpoch());
    }

    public FlowResourceProtocolEnvelopeHandler(FlowResourceRegistry registry, ServerId serverId,
                                               ProtocolResourceMutationAuthority mutationAuthority,
                                               ProtocolResourceAuthorizer authorizer) {
        this(registry, serverId, mutationAuthority, authorizer, requireExplicitAuthorityEpoch());
    }

    public FlowResourceProtocolEnvelopeHandler(FlowResourceRegistry registry, ServerId serverId,
                                               ProtocolResourceMutationAuthority mutationAuthority,
                                               ProtocolResourceAuthorizer authorizer, AuthorityEpoch authorityEpoch) {
        this(registry, serverId, mutationAuthority, authorizer, authorityEpoch, null);
    }

    public FlowResourceProtocolEnvelopeHandler(FlowResourceRegistry registry, ServerId serverId,
                                               ProtocolResourceMutationAuthority mutationAuthority,
                                               ProtocolResourceAuthorizer authorizer, AuthorityEpoch authorityEpoch,
                                               AuthoringTemplateProducer authoringTemplateProducer) {
        this(registry, serverId, mutationAuthority, authorizer, authorityEpoch, authoringTemplateProducer,
            OptionQueryAuthority.failClosed());
    }

    public FlowResourceProtocolEnvelopeHandler(FlowResourceRegistry registry, ServerId serverId,
                                               ProtocolResourceMutationAuthority mutationAuthority,
                                               ProtocolResourceAuthorizer authorizer, AuthorityEpoch authorityEpoch,
                                               AuthoringTemplateProducer authoringTemplateProducer,
                                               OptionQueryAuthority optionQueryAuthority) {
        this(registry, serverId, mutationAuthority, authorizer, authorityEpoch, authoringTemplateProducer,
            optionQueryAuthority, null);
    }

    public FlowResourceProtocolEnvelopeHandler(FlowResourceRegistry registry, ServerId serverId,
                                               ProtocolResourceMutationAuthority mutationAuthority,
                                               ProtocolResourceAuthorizer authorizer, AuthorityEpoch authorityEpoch,
                                               AuthoringTemplateProducer authoringTemplateProducer,
                                               ProviderOptionQueryService optionQueryService) {
        this(registry, serverId, mutationAuthority, authorizer, authorityEpoch, authoringTemplateProducer,
            OptionQueryAuthority.failClosed(), optionQueryService);
    }

    private FlowResourceProtocolEnvelopeHandler(FlowResourceRegistry registry, ServerId serverId,
                                                ProtocolResourceMutationAuthority mutationAuthority,
                                                ProtocolResourceAuthorizer authorizer, AuthorityEpoch authorityEpoch,
                                                AuthoringTemplateProducer authoringTemplateProducer,
                                                OptionQueryAuthority optionQueryAuthority,
                                                ProviderOptionQueryService optionQueryService) {
        this.registry = Objects.requireNonNull(registry, "Resource registry is required");
        this.serverId = Objects.requireNonNull(serverId, "Server ID is required");
        this.mutationAuthority = ProtocolResourceMutationAuthority.require(mutationAuthority);
        this.authorizer = ProtocolResourceAuthorizer.require(authorizer);
        this.authoringTemplateProducer = authoringTemplateProducer;
        this.optionQueryAuthority = Objects.requireNonNull(optionQueryAuthority, "Option query authority is required");
        this.optionQueryService = optionQueryService;
        this.optionQueryAdapter = new FlowResourceOptionQueryAdapter(registry, serverId);
        AuthorityEpoch boundAuthorityEpoch = Objects.requireNonNull(authorityEpoch, "Authority epoch is required");
        if (boundAuthorityEpoch.current() < 1L) {
            throw new IllegalArgumentException("Authority epoch must be positive");
        }
        this.authorityEpoch = boundAuthorityEpoch;
    }

    @Override
    public boolean supports(ProtocolEnvelope<Map<String, Object>> envelope) {
        long started = TemporaryLifecycleDiagnostics.start();
        boolean supported;
        if (isAuthoringTemplate(envelope)) {
            supported = authoringContractSupported(envelope) && authoringCapabilitiesSupported(envelope.capabilities());
        } else if (envelope != null && envelope.body() instanceof ProtocolBody.OptionQueryRequest) {
            supported = envelope.kind() == ProtocolEnvelope.Kind.REQUEST
                && OptionQueryAuthority.PAGE_TYPE.equals(envelope.payloadType())
                && OptionQueryAuthority.OPERATION.equals(envelope.operation())
                && ReSyncProtocolContract.supportsGenericResourceContract(envelope.contractVersion())
                && envelope.capabilities().contains(OptionQueryAuthority.PROTOCOL_CAPABILITY)
                && capabilitiesSupported(envelope.capabilities());
        } else {
            supported = envelope != null && envelope.kind() == ProtocolEnvelope.Kind.REQUEST
                && envelope.body() instanceof ProtocolBody.ResourceRequest
                && payloadTypeMatches(envelope)
                && PROTOCOL_OWNER.equals(envelope.operation().owner())
                && operationMatches(envelope)
                && contractSupported(envelope)
                && capabilitiesSupported(envelope.capabilities());
        }
        TemporaryLifecycleDiagnostics.event("protocol_handler_support", started,
            TemporaryLifecycleDiagnostics.with(handlerDiagnosticIdentity(envelope), "outcome",
                supported ? "supported" : "unsupported", "matchedHandler", supported));
        return supported;
    }

    private boolean payloadTypeMatches(ProtocolEnvelope<Map<String, Object>> envelope) {
        if (!(envelope.body() instanceof ProtocolBody.ResourceRequest request)) {
            return false;
        }
        return switch (request.operation().kind()) {
            case LIST, QUERY -> PAGE_TYPE.equals(envelope.payloadType());
            default -> DOCUMENT_TYPE.equals(envelope.payloadType());
        };
    }

    @Override
    public boolean authorize(ConnectionInfo connection, Session session, ProtocolEnvelope<Map<String, Object>> envelope) {
        long started = TemporaryLifecycleDiagnostics.start();
        boolean authorized = authorizeInternal(connection, session, envelope);
        TemporaryLifecycleDiagnostics.event("protocol_handler_authorization", started,
            TemporaryLifecycleDiagnostics.with(handlerDiagnosticIdentity(envelope), "outcome",
                authorized ? "authorized" : "denied", "matchedHandler", authorized));
        return authorized;
    }

    private boolean authorizeInternal(ConnectionInfo connection, Session session,
                                      ProtocolEnvelope<Map<String, Object>> envelope) {
        if (!supports(envelope)) {
            return false;
        }
        if (isAuthoringTemplate(envelope)) {
            String clientId = ProtocolRequestAuthority.trustedClientId(connection, session);
            if (clientId == null || !serverId.equals(envelope.serverId())) {
                return false;
            }
            try {
                AuthoringTemplateRequest request = authoringTemplateProducer.decodeRequest(templateValues(envelope));
                return serverId.equals(request.resource().serverId())
                    && authoringCapabilitySet(connection, session, envelope) != null
                    && authorizer.authorize(connection, session, envelope, new ResourceLoadRequest(request.resource()));
            } catch (RuntimeException exception) {
                return false;
            }
        }
        if (envelope.body() instanceof ProtocolBody.OptionQueryRequest request) {
            return authorizeOptionQuery(connection, session, envelope, request.query());
        }
        String clientId = ProtocolRequestAuthority.trustedClientId(connection, session);
        if (clientId == null) {
            return false;
        }
        if (!ProtocolRequestAuthority.owns(serverId, envelope)) {
            return false;
        }
        ProtocolBody.ResourceRequest request = envelope.body() instanceof ProtocolBody.ResourceRequest resourceRequest
            ? resourceRequest : null;
        if (request == null) {
            return false;
        }
        if (!authorizer.authorize(connection, session, envelope, request.operation())) {
            return false;
        }
        return requestResource(request).map(resource -> ownerMatches(resource.type())).orElseGet(() -> requestType(request)
            .map(type -> ownerMatches(type)).orElse(false));
    }

    @Override
    public ProtocolEnvelopeDispatchResult handle(ConnectionInfo connection, Session session,
                                                  ProtocolEnvelope<Map<String, Object>> envelope) {
        if (!supports(envelope)) {
            return reject(ProtocolRejectionCode.UNSUPPORTED_GENERATION, "Protocol envelope operation is unsupported");
        }
        if (isAuthoringTemplate(envelope)) {
            String clientId = ProtocolRequestAuthority.trustedClientId(connection, session);
            if (clientId == null || !serverId.equals(envelope.serverId())) {
                return reject(ProtocolRejectionCode.AUTHORIZATION_DENIED, "Protocol envelope operation is not authorized");
            }
            try {
                AuthoringTemplateRequest request = authoringTemplateProducer.decodeRequest(templateValues(envelope));
                if (!serverId.equals(request.resource().serverId())
                    || !authorizer.authorize(connection, session, envelope, new ResourceLoadRequest(request.resource()))) {
                    return reject(ProtocolRejectionCode.AUTHORIZATION_DENIED, "Protocol envelope operation is not authorized");
                }
                Set<ContractRef<CapabilityId>> supported = authoringCapabilitySet(connection, session, envelope);
                AuthoringTemplateResponse response = authoringTemplateProducer.produce(request, session, supported);
                return authoringTemplateResponse(envelope, response);
            } catch (AuthoringTemplateProducer.Rejected rejection) {
                return reject(rejection.code(), rejection.getMessage());
            } catch (RuntimeException exception) {
                return reject(ProtocolRejectionCode.INVALID_PAYLOAD, "Authoring template request is invalid");
            }
        }
        if (envelope.body() instanceof ProtocolBody.OptionQueryRequest request) {
            return handleOptionQuery(connection, session, envelope, request);
        }
        if (requiresMutationEpoch(envelope) && !authorityEpoch.acceptsTyped(envelope.authorityEpoch())) {
            return reject(ProtocolRejectionCode.RESOURCE_REVISION_CONFLICT, "Protocol envelope authority epoch is stale");
        }
        String clientId = ProtocolRequestAuthority.trustedClientId(connection, session);
        if (clientId == null || !ProtocolRequestAuthority.owns(serverId, envelope)) {
            return reject(ProtocolRejectionCode.AUTHORIZATION_DENIED, "Protocol envelope operation is not authorized");
        }
        if (!(envelope.body() instanceof ProtocolBody.ResourceRequest request)) {
            return reject(ProtocolRejectionCode.INVALID_BODY, "Protocol envelope must contain a resource request");
        }
        if (!authorizer.authorize(connection, session, envelope, request.operation())) {
            return reject(ProtocolRejectionCode.AUTHORIZATION_DENIED, "Protocol envelope operation is not authorized");
        }
        ResourceOperation operation = request.operation();
        long started = TemporaryLifecycleDiagnostics.start();
        Map<String, Object> diagnosticIdentity = diagnosticIdentity(connection, envelope, operation);
        TemporaryLifecycleDiagnostics.event("protocol_admission", started,
            TemporaryLifecycleDiagnostics.with(diagnosticIdentity, "operation", operation.kind(), "outcome", "accepted"));
        try {
            ProtocolEnvelopeDispatchResult result = switch (operation) {
                case ResourceListRequest list -> list(envelope, list);
                case ResourceQueryRequest query -> query(envelope, query);
                case ResourceLoadRequest load -> load(envelope, load);
                case ResourceCreateRequest<?> create -> create(connection, envelope, session, create);
                case ResourceSaveRequest<?> save -> save(connection, envelope, session, save);
                case ResourceDeleteRequest delete -> delete(connection, envelope, session, delete);
                case ResourceDuplicateRequest duplicate -> duplicate(connection, envelope, session, duplicate);
                case ResourceActivateRequest activate -> activate(connection, envelope, session, activate);
                case ResourceRenameRequest rename -> mutate(connection, envelope, session, rename);
                case ResourceMoveRequest move -> mutate(connection, envelope, session, move);
                case ResourceSubscribeRequest subscribe -> mutate(connection, envelope, session, subscribe);
                default -> reject(ProtocolRejectionCode.UNSUPPORTED_OPERATION, "Resource operation is not available on this authority");
            };
            ProtocolEnvelope<Map<String, Object>> response = result.response();
            TemporaryLifecycleDiagnostics.event("protocol_settlement", started,
                TemporaryLifecycleDiagnostics.with(diagnosticIdentity, "operation", operation.kind(), "outcome", result.status(),
                    "rejection", result.rejectionCode(), "responseRevision", response == null ? null : response.revision(),
                    "responseMutationId", response == null ? null : response.mutationId()));
            return result;
        } catch (RuntimeException exception) {
            TemporaryLifecycleDiagnostics.event("protocol_settlement", started,
                TemporaryLifecycleDiagnostics.with(diagnosticIdentity, "operation", operation.kind(), "outcome", "failed",
                    "failure", exception.getClass().getSimpleName()));
            return reject(ProtocolRejectionCode.RESOURCE_OPERATION_FAILED, "Resource operation failed");
        }
    }

    private boolean authorizeOptionQuery(ConnectionInfo connection, Session session,
                                         ProtocolEnvelope<Map<String, Object>> envelope, OptionQuery query) {
        if (!ownsOptionQuery(connection, session, envelope, query)) {
            return false;
        }
        if (optionQueryService != null) {
            return optionQueryService.authorize(connection, session, envelope, query);
        }
        try {
            OptionQueryAuthority.Source source = optionQueryAuthority.require(query.sourceRef(), query.query());
            if (source.descriptor().optionType() instanceof TypeExpr.ResourceType) {
                ContractRef<ResourceTypeId> type = optionQueryAdapter.requireResourceType(source);
                return authorizer.authorize(connection, session, envelope,
                    new ResourceQueryRequest(type, Map.of(), query.cursor(), query.limit(), query.search()));
            }
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private ProtocolEnvelopeDispatchResult handleOptionQuery(ConnectionInfo connection, Session session,
                                                              ProtocolEnvelope<Map<String, Object>> envelope,
                                                              ProtocolBody.OptionQueryRequest body) {
        OptionQuery query = body.query();
        if (!ownsOptionQuery(connection, session, envelope, query)) {
            return reject(ProtocolRejectionCode.AUTHORIZATION_DENIED, "Option query is not authorized");
        }
        try {
            if (optionQueryService != null) {
                ProviderOptionQueryService.Result result = optionQueryService.query(session, envelope, query);
                return ProtocolEnvelopeDispatchResult.handled(optionResponseEnvelope(envelope, body, result.page(),
                    result.selectedVersion(), result.binding().catalogChecksum(), result.binding().bindingManifestHash()));
            }
            OptionQueryAuthority.Source source = optionQueryAuthority.require(query.sourceRef(), query.query());
            if (source.descriptor().optionType() instanceof TypeExpr.ResourceType) {
                ContractRef<ResourceTypeId> type = optionQueryAdapter.requireResourceType(source);
                ResourceQueryRequest authorization = new ResourceQueryRequest(type, Map.of(), query.cursor(), query.limit(),
                    query.search());
                if (!authorizer.authorize(connection, session, envelope, authorization)) {
                    return reject(ProtocolRejectionCode.AUTHORIZATION_DENIED, "Option query is not authorized");
                }
            }
            OptionPage page = optionQueryAdapter.query(query, source);
            return ProtocolEnvelopeDispatchResult.handled(optionResponseEnvelope(envelope, body, page,
                envelope.selectedVersion(), envelope.catalogChecksum(), envelope.bindingManifestHash()));
        } catch (OptionQueryAuthority.Rejected rejection) {
            return reject(rejection.code(), rejection.getMessage());
        } catch (RuntimeException exception) {
            return reject(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE,
                "Authoritative option query failed");
        }
    }

    private boolean ownsOptionQuery(ConnectionInfo connection, Session session,
                                    ProtocolEnvelope<Map<String, Object>> envelope, OptionQuery query) {
        return ProtocolRequestAuthority.trustedClientId(connection, session) != null
            && serverId.equals(envelope.serverId())
            && serverId.equals(query.serverId())
            && envelope.revision() == 0L
            && envelope.resource() == null
            && (query.resource() == null || serverId.equals(query.resource().serverId()));
    }

    private ProtocolEnvelopeDispatchResult list(ProtocolEnvelope<Map<String, Object>> envelope, ResourceListRequest request) {
        String type = request.type().id().value();
        if (!ownerMatches(request.type()) || !supports(type, "discover")) {
            return reject(ProtocolRejectionCode.RESOURCE_TYPE_UNAVAILABLE, "Resource type is unavailable");
        }
        if (coreResourceType(type)) {
            if (!mutationAuthority.authoritativeCoreReads()) {
                return reject(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE,
                    "Authoritative Core resource reads are unavailable");
            }
            try {
                List<ResourceDocument<Map<String, Object>>> documents = mutationAuthority.list(serverId, request.type(), request.search()).stream()
                    .filter(document -> document != null && !document.deleted())
                    .toList();
                return pageDocuments(envelope, ResourceOperationKind.LIST, documents, request.cursor(), request.limit());
            } catch (RuntimeException exception) {
                Log.warn("Authoritative Core resource LIST failed: type=" + type + ", reason="
                    + exception.getClass().getSimpleName() + ": " + safeMessage(exception));
                return reject(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE,
                    "Authoritative Core resource reads are unavailable");
            }
        }
        if (mutationAuthority.authoritativeReads()) {
            try {
                List<ResourceDocument<Map<String, Object>>> documents = mutationAuthority.list(serverId, request.type(), request.search()).stream()
                    .filter(document -> document != null && !document.deleted())
                    .toList();
                return pageDocuments(envelope, ResourceOperationKind.LIST, documents, request.cursor(), request.limit());
            } catch (RuntimeException exception) {
                Log.warn("Generic resource LIST failed: type=" + type + ", reason=" + exception.getClass().getSimpleName()
                    + ": " + safeMessage(exception));
                return reject(ProtocolRejectionCode.RESOURCE_OPERATION_FAILED, "Resource operation failed");
            }
        }
        if (!mutationAuthority.authoritativeReads() && !mutationAuthority.allowLegacyReads()) {
            return reject(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE, "Authoritative resource reads are unavailable");
        }
        FlowOperationResult<List<FlowResourceReference>> result = registry.discover(type, request.search());
        if (!result.success()) {
            return failure(result);
        }
        return page(envelope, ResourceOperationKind.LIST, type, result.value(), request.cursor(), request.limit());
    }

    private ProtocolEnvelopeDispatchResult query(ProtocolEnvelope<Map<String, Object>> envelope, ResourceQueryRequest request) {
        String type = request.type().id().value();
        if (!ownerMatches(request.type()) || !supports(type, "query")) {
            return reject(ProtocolRejectionCode.RESOURCE_TYPE_UNAVAILABLE, "Resource type is unavailable");
        }
        if (!request.filters().isEmpty()) {
            return reject(ProtocolRejectionCode.UNSUPPORTED_QUERY, "Typed resource filters are not available on this authority");
        }
        if (coreResourceType(type)) {
            if (!mutationAuthority.authoritativeCoreReads()) {
                return reject(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE,
                    "Authoritative Core resource reads are unavailable");
            }
            try {
                List<ResourceDocument<Map<String, Object>>> documents = mutationAuthority.list(serverId, request.type(), request.search()).stream()
                    .filter(document -> document != null && !document.deleted())
                    .toList();
                return pageDocuments(envelope, ResourceOperationKind.QUERY, documents, request.cursor(), request.limit());
            } catch (RuntimeException exception) {
                Log.warn("Authoritative Core resource QUERY failed: type=" + type + ", reason="
                    + exception.getClass().getSimpleName() + ": " + safeMessage(exception));
                return reject(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE,
                    "Authoritative Core resource reads are unavailable");
            }
        }
        if (mutationAuthority.authoritativeReads()) {
            List<ResourceDocument<Map<String, Object>>> documents = mutationAuthority.list(serverId, request.type(), request.search()).stream()
                .filter(document -> document != null && !document.deleted())
                .toList();
            return pageDocuments(envelope, ResourceOperationKind.QUERY, documents, request.cursor(), request.limit());
        }
        if (!mutationAuthority.authoritativeReads() && !mutationAuthority.allowLegacyReads()) {
            return reject(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE, "Authoritative resource reads are unavailable");
        }
        FlowOperationResult<List<FlowResourceReference>> result = registry.query(type, request.search());
        if (!result.success()) {
            return failure(result);
        }
        return page(envelope, ResourceOperationKind.QUERY, type, result.value(), request.cursor(), request.limit());
    }

    private ProtocolEnvelopeDispatchResult load(ProtocolEnvelope<Map<String, Object>> envelope, ResourceLoadRequest request) {
        ServerResourceLocator resource = request.resource();
        if (!resourceAvailable(resource) || !supports(resource, "get")) {
            return reject(ProtocolRejectionCode.RESOURCE_TYPE_UNAVAILABLE, "Resource type is unavailable");
        }
        if (coreResourceType(resource.resourceType().value())) {
            if (!mutationAuthority.authoritativeCoreReads()) {
                return reject(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE,
                    "Authoritative Core resource reads are unavailable");
            }
            ResourceDocument<Map<String, Object>> document;
            try {
                document = mutationAuthority.load(resource);
            } catch (RuntimeException exception) {
                Log.warn("Authoritative Core resource LOAD failed: type=" + resource.resourceType().value()
                    + ", id=" + resource.id() + ", reason=" + exception.getClass().getSimpleName()
                    + ": " + safeMessage(exception));
                return reject(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE,
                    "Authoritative Core resource reads are unavailable");
            }
            if (document == null) {
                return reject(ProtocolRejectionCode.RESOURCE_NOT_FOUND, "Resource was not found");
            }
            return documentResponse(envelope, ResourceOperationKind.LOAD, document, ProtocolEnvelope.Status.OK);
        }
        if (!mutationAuthority.authoritativeReads() && !mutationAuthority.allowLegacyReads()) {
            return reject(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE, "Authoritative resource reads are unavailable");
        }
        ResourceDocument<Map<String, Object>> document = mutationAuthority.authoritativeReads()
            ? mutationAuthority.load(resource) : document(resource);
        if (document == null) {
            return reject(ProtocolRejectionCode.RESOURCE_NOT_FOUND, "Resource was not found");
        }
        return documentResponse(envelope, ResourceOperationKind.LOAD, document, ProtocolEnvelope.Status.OK);
    }

    private ProtocolEnvelopeDispatchResult create(ConnectionInfo connection, ProtocolEnvelope<Map<String, Object>> envelope, Session session,
                                                  ResourceCreateRequest<?> request) {
        return mutate(connection, envelope, session, request);
    }

    private ProtocolEnvelopeDispatchResult save(ConnectionInfo connection, ProtocolEnvelope<Map<String, Object>> envelope, Session session,
                                                ResourceSaveRequest<?> request) {
        return mutate(connection, envelope, session, request);
    }

    private ProtocolEnvelopeDispatchResult mutate(ConnectionInfo connection, ProtocolEnvelope<Map<String, Object>> envelope,
                                                  Session session, ResourceOperation operation) {
        if (!mutationAuthority.supports(operation)) {
            return mutationAuthority.rejectUnsupported(operation);
        }
        long started = TemporaryLifecycleDiagnostics.start();
        ProtocolEnvelopeDispatchResult result = mutationAuthority.mutate(connection, session, envelope, operation);
        TemporaryLifecycleDiagnostics.event("mutation_authority", started,
            TemporaryLifecycleDiagnostics.with(diagnosticIdentity(connection, envelope, operation), "operation", operation.kind(),
                "outcome", result == null ? "missing" : result.status()));
        return stampMutationResponse(result);
    }

    private ProtocolEnvelopeDispatchResult stampMutationResponse(ProtocolEnvelopeDispatchResult result) {
        if (result == null || result.response() == null) {
            return result;
        }
        ProtocolEnvelope<Map<String, Object>> response = result.response();
        if (!supportsResourceActivation(response) && containsInactiveDocument(response.body())) {
            return rejectInactiveLegacyResource();
        }
        ProtocolEnvelope<Map<String, Object>> stamped = new ProtocolEnvelope<>(
            response.kind(),
            response.contractVersion(),
            response.messageId(),
            response.requestId(),
            response.correlationId(),
            response.traceId(),
            response.serverId(),
            response.resource(),
            response.revision(),
            authorityEpoch.current(),
            response.mutationId(),
            response.operation(),
            response.capabilities(),
            response.payloadType(),
            response.canonicalPayload(),
            response.payloadHash(),
            response.deleted(),
            response.selectedVersion(),
            response.catalogChecksum(),
            response.bindingManifestHash(),
            response.editability(),
            response.fallbackReason(),
            response.sequence(),
            response.status(),
            response.diagnostics(),
            response.unknown(),
            response.body());
        return new ProtocolEnvelopeDispatchResult(result.status(), result.transportCode(), result.statusCode(),
            result.rejectionCode(), result.message(), stamped);
    }

    private ProtocolEnvelopeDispatchResult authoringTemplateResponse(ProtocolEnvelope<Map<String, Object>> request,
                                                                      AuthoringTemplateResponse response) {
        Set<ContractRef<CapabilityId>> capabilities = new LinkedHashSet<>(request.capabilities());
        capabilities.add(ContractRef.of(PROTOCOL_OWNER, new CapabilityId("resources")));
        ProtocolEnvelope<Map<String, Object>> envelope = new ProtocolEnvelope<>(
            ProtocolEnvelope.Kind.RESPONSE,
            request.contractVersion(),
            UUID.randomUUID(),
            request.requestId(),
            request.correlationId(),
            request.traceId(),
            serverId,
            null,
            0L,
            authorityEpoch.current(),
            null,
            AuthoringTemplateProducer.OPERATION,
            capabilities,
            AuthoringTemplateProducer.RESPONSE_TYPE,
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            request.sequence(),
            ProtocolEnvelope.Status.OK,
            List.of(),
            request.unknown(),
            new ProtocolBody.ControlResponse(AuthoringTemplateProducer.ACTION,
                Map.of("response", authoringTemplateProducer.encodeResponse(response))));
        return ProtocolEnvelopeDispatchResult.handled(envelope);
    }

    private ProtocolEnvelopeDispatchResult delete(ConnectionInfo connection, ProtocolEnvelope<Map<String, Object>> envelope, Session session,
                                                  ResourceDeleteRequest request) {
        return mutate(connection, envelope, session, request);
    }

    private ProtocolEnvelopeDispatchResult duplicate(ConnectionInfo connection, ProtocolEnvelope<Map<String, Object>> envelope, Session session,
                                                     ResourceDuplicateRequest request) {
        return mutate(connection, envelope, session, request);
    }

    private ProtocolEnvelopeDispatchResult activate(ConnectionInfo connection, ProtocolEnvelope<Map<String, Object>> envelope,
                                                    Session session, ResourceActivateRequest request) {
        return mutate(connection, envelope, session, request);
    }

    private ProtocolEnvelopeDispatchResult page(ProtocolEnvelope<Map<String, Object>> envelope, ResourceOperationKind operation,
                                                 String type, List<FlowResourceReference> references, String cursor, int limit) {
        List<ResourceDocument<Map<String, Object>>> documents = new ArrayList<>();
        for (FlowResourceReference reference : references) {
            ResourceDocument<Map<String, Object>> document = document(new ServerResourceLocator(serverId,
                typeReference(reference.kind()), reference.id()));
            if (document != null && !document.deleted()) {
                documents.add(document);
            }
        }
        return pageDocuments(envelope, operation, documents, cursor, limit, references.size());
    }

    private ProtocolEnvelopeDispatchResult pageDocuments(ProtocolEnvelope<Map<String, Object>> envelope,
                                                           ResourceOperationKind operation,
                                                           List<ResourceDocument<Map<String, Object>>> documents,
                                                           String cursor, int limit) {
        return pageDocuments(envelope, operation, documents, cursor, limit, documents == null ? 0 : documents.size());
    }

    private ProtocolEnvelopeDispatchResult pageDocuments(ProtocolEnvelope<Map<String, Object>> envelope,
                                                           ResourceOperationKind operation,
                                                           List<ResourceDocument<Map<String, Object>>> documents,
                                                           String cursor, int limit, int discoveredCount) {
        long started = TemporaryLifecycleDiagnostics.start();
        int materializedCount = documents == null ? 0 : documents.size();
        documents = visibleDocuments(envelope, documents);
        int visibleCount = documents.size();
        int start;
        try {
            start = cursor == null || cursor.isBlank() ? 0 : parseCursor(cursor);
        } catch (IllegalArgumentException exception) {
            TemporaryLifecycleDiagnostics.event("protocol_resource_page", started,
                TemporaryLifecycleDiagnostics.with(diagnosticPageIdentity(envelope, operation),
                    "operation", operation, "outcome", "rejected", "diagnosticCode", ProtocolRejectionCode.INVALID_CURSOR,
                    "cursorPresent", cursor != null && !cursor.isBlank(), "requestedLimit", limit,
                    "discoveredCount", discoveredCount, "materializedCount", materializedCount,
                    "visibleCount", visibleCount));
            return reject(ProtocolRejectionCode.INVALID_CURSOR, "Resource cursor is invalid");
        }
        if (start > documents.size()) {
            TemporaryLifecycleDiagnostics.event("protocol_resource_page", started,
                TemporaryLifecycleDiagnostics.with(diagnosticPageIdentity(envelope, operation),
                    "operation", operation, "outcome", "rejected", "diagnosticCode", ProtocolRejectionCode.INVALID_CURSOR,
                    "cursorPresent", cursor != null && !cursor.isBlank(), "requestedLimit", limit,
                    "discoveredCount", discoveredCount, "materializedCount", materializedCount,
                    "visibleCount", visibleCount, "pageStart", start));
            return reject(ProtocolRejectionCode.INVALID_CURSOR, "Resource cursor is outside the result set");
        }
        int end = Math.min(documents.size(), start + limit);
        List<ResourceDocument<Map<String, Object>>> items = List.copyOf(documents.subList(start, end));
        boolean complete = end >= documents.size();
        ResourcePage<Map<String, Object>> page = new ResourcePage<>(items, complete ? null : Integer.toString(end), complete);
        ProtocolEnvelope<Map<String, Object>> response = responseEnvelope(envelope, operation, null, 0L, null, null, false,
            ProtocolEnvelope.Kind.RESPONSE, ProtocolEnvelope.Status.OK,
            new ProtocolBody.ResourcePageResponse(operation, page, bodyUnknown(envelope)));
        TemporaryLifecycleDiagnostics.event("protocol_resource_page", started,
            TemporaryLifecycleDiagnostics.with(diagnosticPageIdentity(envelope, operation),
                "operation", operation, "outcome", "returned", "cursorPresent", cursor != null && !cursor.isBlank(),
                "requestedLimit", limit, "discoveredCount", discoveredCount, "materializedCount", materializedCount,
                "visibleCount", visibleCount, "pageStart", start, "pageEnd", end, "pageComplete", complete));
        return ProtocolEnvelopeDispatchResult.handled(response);
    }

    private Map<String, Object> diagnosticPageIdentity(ProtocolEnvelope<Map<String, Object>> envelope,
                                                        ResourceOperationKind operation) {
        if (envelope != null && envelope.body() instanceof ProtocolBody.ResourceRequest request) {
            return diagnosticIdentity(null, envelope, request.operation());
        }
        return TemporaryLifecycleDiagnostics.identity(serverId, operation, null,
            envelope == null ? null : envelope.requestId(), envelope == null ? null : envelope.correlationId(),
            null, envelope == null ? authorityEpoch.current() : envelope.authorityEpoch(), null);
    }

    private ProtocolEnvelopeDispatchResult documentResponse(ProtocolEnvelope<Map<String, Object>> envelope,
                                                             ResourceOperationKind operation,
                                                             ResourceDocument<Map<String, Object>> document,
                                                             ProtocolEnvelope.Status status) {
        if (legacyInactive(envelope, document)) {
            return rejectInactiveLegacyResource();
        }
        ProtocolEnvelope<Map<String, Object>> response = responseEnvelope(envelope, operation, document.resource(), document.revision(),
            document.mutationId(), document.payloadHash(), document.deleted(), ProtocolEnvelope.Kind.ACK, status,
            new ProtocolBody.ResourceDocumentResponse(operation, document, bodyUnknown(envelope)));
        return ProtocolEnvelopeDispatchResult.handled(response);
    }

    private ProtocolEnvelopeDispatchResult conflict(ProtocolEnvelope<Map<String, Object>> envelope, ResourceOperationKind operation,
                                                     ServerResourceLocator resource, ResourceDocument<Map<String, Object>> current) {
        if (legacyInactive(envelope, current)) {
            return rejectInactiveLegacyResource();
        }
        ProtocolEnvelope<Map<String, Object>> response = responseEnvelope(envelope, operation, resource,
            current == null ? 0L : current.revision(), current == null ? null : current.mutationId(),
            current == null ? null : current.payloadHash(), current != null && current.deleted(),
            ProtocolEnvelope.Kind.CONFLICT, ProtocolEnvelope.Status.CONFLICT,
            new ProtocolBody.ConflictResponse(resource, current, bodyUnknown(envelope)));
        return ProtocolEnvelopeDispatchResult.handled(response);
    }

    private List<ResourceDocument<Map<String, Object>>> visibleDocuments(ProtocolEnvelope<Map<String, Object>> envelope,
                                                                           Collection<ResourceDocument<Map<String, Object>>> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        return documents.stream()
            .filter(Objects::nonNull)
            .filter(document -> supportsResourceActivation(envelope) || !isInactive(document))
            .toList();
    }

    private boolean containsInactiveDocument(ProtocolBody body) {
        if (body == null) {
            return false;
        }
        return switch (body) {
            case ProtocolBody.ResourceDocumentResponse response -> isInactive(response.document());
            case ProtocolBody.ResourceCreateResponse response -> isInactive(response.result().resource())
                || isInactive(response.result().projectMetadata());
            case ProtocolBody.ResourcePageResponse response -> response.page().items().stream().anyMatch(this::isInactive);
            case ProtocolBody.ConflictResponse response -> isInactive(response.current());
            default -> false;
        };
    }

    private boolean legacyInactive(ProtocolEnvelope<Map<String, Object>> envelope, ResourceDocument<?> document) {
        return !supportsResourceActivation(envelope) && isInactive(document);
    }

    private boolean supportsResourceActivation(ProtocolEnvelope<?> envelope) {
        return envelope != null && ProtocolEnvelope.supportsResourceActivation(envelope.contractVersion(), envelope.capabilities());
    }

    private boolean isInactive(ResourceDocument<?> document) {
        return document != null && !document.deleted() && document.activationState() == ResourceActivationState.INACTIVE;
    }

    private ProtocolEnvelopeDispatchResult rejectInactiveLegacyResource() {
        return reject(ProtocolRejectionCode.RESOURCE_OPERATION_UNSUPPORTED,
            "Inactive resources require generic resource contract 1.1 and resource_activation capability");
    }

    private ProtocolEnvelope<Map<String, Object>> responseEnvelope(ProtocolEnvelope<Map<String, Object>> request,
                                                                    ResourceOperationKind operation,
                                                                    ServerResourceLocator resource, long revision, UUID mutationId,
                                                                    ContentHash payloadHash, boolean deleted,
                                                                    ProtocolEnvelope.Kind kind, ProtocolEnvelope.Status status,
                                                                    ProtocolBody body) {
        Set<ContractRef<CapabilityId>> capabilities = new LinkedHashSet<>(request.capabilities());
        capabilities.add(ContractRef.of(PROTOCOL_OWNER, new CapabilityId("resources")));
        return new ProtocolEnvelope<>(
            kind,
            request.contractVersion() != null ? request.contractVersion() : CONTRACT_VERSION,
            UUID.randomUUID(),
            request.requestId(),
            request.correlationId(),
            request.traceId(),
            serverId,
            resource,
            revision,
            authorityEpoch.current(),
            mutationId,
            operationReference(operation),
            capabilities,
            request.payloadType(),
            null,
            payloadHash,
            deleted,
            null,
            null,
            null,
            null,
            null,
            request.sequence(),
            status,
            List.of(),
            request.unknown(),
            body
        );
    }

    private ProtocolEnvelope<Map<String, Object>> optionResponseEnvelope(ProtocolEnvelope<Map<String, Object>> request,
                                                                          ProtocolBody.OptionQueryRequest body,
                                                                          OptionPage page,
                                                                          CatalogVersion selectedVersion,
                                                                          ContentHash catalogChecksum,
                                                                          ContentHash bindingManifestHash) {
        Set<ContractRef<CapabilityId>> capabilities = new LinkedHashSet<>(request.capabilities());
        capabilities.add(OptionQueryAuthority.PROTOCOL_CAPABILITY);
        return new ProtocolEnvelope<>(
            ProtocolEnvelope.Kind.RESPONSE,
            request.contractVersion() != null ? request.contractVersion() : CONTRACT_VERSION,
            UUID.randomUUID(),
            request.requestId(),
            request.correlationId(),
            request.traceId(),
            serverId,
            null,
            0L,
            authorityEpoch.current(),
            null,
            OptionQueryAuthority.OPERATION,
            capabilities,
            OptionQueryAuthority.PAGE_TYPE,
            null,
            null,
            false,
            selectedVersion,
            catalogChecksum,
            bindingManifestHash,
            null,
            null,
            request.sequence(),
            ProtocolEnvelope.Status.OK,
            page.diagnostics(),
            request.unknown(),
            new ProtocolBody.OptionPageResponse(page, body.unknown())
        );
    }

    private Map<String, Object> bodyUnknown(ProtocolEnvelope<Map<String, Object>> envelope) {
        return switch (envelope.body()) {
            case ProtocolBody.ResourceRequest request -> request.unknown();
            case ProtocolBody.OptionQueryRequest request -> request.unknown();
            default -> Map.of();
        };
    }

    private ResourceDocument<Map<String, Object>> document(ServerResourceLocator resource) {
        if (resource != null && coreResourceType(resource.resourceType().value())) {
            return registry.protocolDocument(resource);
        }
        FlowResourceAdapter<Object> adapter = adapter(resource);
        Object value = adapter != null ? adapter.get(resource.id()) : null;
        return value != null ? document(resource, value, null, null) : null;
    }

    private ResourceDocument<Map<String, Object>> document(ServerResourceLocator resource, Object value, Long forcedRevision,
                                                           UUID forcedMutation) {
        FlowResourceAdapter<Object> adapter = adapter(resource);
        if (adapter == null || value == null) {
            return null;
        }
        Map<String, Object> payload = payload(adapter, value);
        CanonicalPayload<Map<String, Object>> canonical = payloadCodec.canonicalize(payload);
        long revision = forcedRevision != null ? Math.max(1L, forcedRevision) : 1L;
        UUID mutation = forcedMutation != null ? forcedMutation : UUID.nameUUIDFromBytes(
            ("resource-bootstrap-v1|" + serverId.canonicalText() + "|" + resource.canonicalText() + "|"
                + canonical.checksum().canonicalText()).getBytes(StandardCharsets.UTF_8));
        return ResourceDocument.live(resource, revision, mutation, canonical, activationState(canonical.value()), "server");
    }

    private ResourceActivationState activationState(Map<String, Object> payload) {
        return payload != null && Boolean.FALSE.equals(payload.get("enabled"))
            ? ResourceActivationState.INACTIVE : ResourceActivationState.ACTIVE;
    }

    private Map<String, Object> payload(FlowResourceAdapter<Object> adapter, Object value) {
        String serialized = adapter.serialize(value);
        JsonElement parsed = JsonParser.parseString(serialized);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Resource payload must be an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = gson.fromJson(parsed, Map.class);
        return payload;
    }

    private FlowResourceAdapter<Object> adapter(ServerResourceLocator resource) {
        if (resource == null || !serverId.equals(resource.serverId()) || !ownerMatches(resource.type())) {
            return null;
        }
        @SuppressWarnings("unchecked")
        FlowResourceAdapter<Object> adapter = (FlowResourceAdapter<Object>) registry.get(resource.resourceType().value());
        return adapter;
    }

    private boolean resourceAvailable(ServerResourceLocator resource) {
        return resource != null && ownerMatches(resource.type()) && supports(resource, "get");
    }

    private boolean supports(ServerResourceLocator resource, String operation) {
        return resource != null && supports(resource.resourceType().value(), operation);
    }

    private boolean supports(String type, String operation) {
        if (coreResourceType(type)) {
            return registry.protocolSupports(type, operation);
        }
        FlowResourceAdapter<Object> adapter = adapter(type);
        return adapter != null && adapter.supportedOperations().contains(operation);
    }

    private boolean ownerMatches(ContractRef<ResourceTypeId> type) {
        if (type == null) {
            return false;
        }
        if (coreResourceType(type.id().value())) {
            String owner = registry.protocolOwner(type.id().value());
            return PROTOCOL_OWNER.canonicalText().equals(owner);
        }
        FlowResourceMetadata metadata = registry.metadata(type.id().value());
        if (metadata == null || !metadata.isAvailable()) {
            return false;
        }
        String owner = type.owner().canonicalText();
        return owner.equals(metadata.getOwner()) || "builtin".equals(metadata.getOwner()) && PROTOCOL_OWNER.canonicalText().equals(owner);
    }

    private FlowResourceAdapter<Object> adapter(String type) {
        if (coreResourceType(type)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        FlowResourceAdapter<Object> adapter = (FlowResourceAdapter<Object>) registry.get(type);
        return adapter;
    }

    private boolean coreResourceType(String type) {
        return type != null && CORE_RESOURCE_TYPES.contains(type.toLowerCase(Locale.ROOT));
    }

    private boolean capabilitiesSupported(Collection<ContractRef<CapabilityId>> capabilities) {
        return capabilities == null || capabilities.stream().allMatch(capability -> capability != null
            && PROTOCOL_OWNER.equals(capability.owner()) && SUPPORTED_CAPABILITIES.contains(capability.id().value()));
    }

    private boolean authoringCapabilitiesSupported(Collection<ContractRef<CapabilityId>> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return false;
        }
        return capabilities.stream().anyMatch(AUTHORING_CAPABILITY::equals)
            && capabilities.stream().allMatch(capability -> capability != null && capability.id() != null
                && !capability.id().value().isBlank());
    }

    private Set<ContractRef<CapabilityId>> authoringCapabilitySet(ConnectionInfo connection, Session session,
                                                                   ProtocolEnvelope<Map<String, Object>> envelope) {
        if (connection == null || session == null || session.getConnection() != connection) {
            throw new AuthoringTemplateProducer.Rejected(ProtocolRejectionCode.AUTHORIZATION_DENIED,
                "Authoring capabilities are not bound to the authenticated session");
        }
        Set<String> acknowledged = connection.getClientCapabilities();
        if (acknowledged == null || acknowledged.isEmpty()) {
            throw new AuthoringTemplateProducer.Rejected(ProtocolRejectionCode.RESOURCE_AUTHORIZATION_DENIED,
                "The authenticated session has no acknowledged authoring capabilities");
        }
        if (!envelope.capabilities().contains(AUTHORING_CAPABILITY)
            || !acknowledgedCapability(acknowledged, AUTHORING_CAPABILITY)) {
            throw new AuthoringTemplateProducer.Rejected(ProtocolRejectionCode.RESOURCE_AUTHORIZATION_DENIED,
                "Catalog authoring is not negotiated by this session");
        }
        Set<ContractRef<CapabilityId>> requested = envelope.capabilities().stream()
            .filter(capability -> !protocolCapability(capability) && !AUTHORING_CAPABILITY.equals(capability))
            .collect(Collectors.toUnmodifiableSet());
        if (requested.stream().anyMatch(capability -> !acknowledgedCapability(acknowledged, capability))) {
            throw new AuthoringTemplateProducer.Rejected(ProtocolRejectionCode.RESOURCE_AUTHORIZATION_DENIED,
                "The requested authoring capabilities were not acknowledged by this session");
        }
        return requested;
    }

    static boolean acknowledgedCapability(Set<String> acknowledged, ContractRef<CapabilityId> capability) {
        return acknowledged != null && capability != null && acknowledged.contains(capability.canonicalText());
    }

    private boolean protocolCapability(ContractRef<CapabilityId> capability) {
        return capability != null && PROTOCOL_OWNER.equals(capability.owner()) && capability.id() != null
            && SUPPORTED_CAPABILITIES.contains(capability.id().value());
    }

    private boolean isAuthoringTemplate(ProtocolEnvelope<Map<String, Object>> envelope) {
        return envelope != null && envelope.kind() == ProtocolEnvelope.Kind.REQUEST
            && envelope.body() instanceof ProtocolBody.ControlRequest request
            && authoringTemplateProducer != null
            && AuthoringTemplateProducer.ACTION.equals(request.action())
            && AuthoringTemplateProducer.OPERATION.equals(envelope.operation())
            && AuthoringTemplateProducer.REQUEST_TYPE.equals(envelope.payloadType());
    }

    private Map<String, Object> templateValues(ProtocolEnvelope<Map<String, Object>> envelope) {
        if (!(envelope.body() instanceof ProtocolBody.ControlRequest request)) {
            throw new AuthoringTemplateProducer.Rejected(ProtocolRejectionCode.INVALID_BODY,
                "Authoring template request body is invalid");
        }
        return request.values();
    }

    private boolean authoringContractSupported(ProtocolEnvelope<Map<String, Object>> envelope) {
        return envelope != null && ReSyncProtocolContract.supportsGenericResourceContract(envelope.contractVersion());
    }

    private boolean contractSupported(ProtocolEnvelope<Map<String, Object>> envelope) {
        if (!(envelope.body() instanceof ProtocolBody.ResourceRequest request)) {
            return false;
        }
        if (request.operation().kind() == ResourceOperationKind.ACTIVATE) {
            return ProtocolEnvelope.supportsResourceActivation(envelope.contractVersion(), envelope.capabilities());
        }
        if (request.operation() instanceof ResourceCreateRequest<?> create && create.presentation() != null) {
            return ProtocolEnvelope.supportsResourceCreatePresentation(envelope.contractVersion(), envelope.capabilities());
        }
        return ReSyncProtocolContract.supportsGenericResourceContract(envelope.contractVersion());
    }

    private boolean operationMatches(ProtocolEnvelope<Map<String, Object>> envelope) {
        if (!(envelope.body() instanceof ProtocolBody.ResourceRequest request)) {
            return false;
        }
        String expected = "resource." + request.operation().kind().name().toLowerCase(Locale.ROOT);
        return expected.equals(envelope.operation().id().value());
    }

    private boolean requiresMutationEpoch(ProtocolEnvelope<Map<String, Object>> envelope) {
        if (!(envelope.body() instanceof ProtocolBody.ResourceRequest request)) {
            return false;
        }
        return switch (request.operation().kind()) {
            case CREATE, SAVE, DELETE, DUPLICATE, RENAME, MOVE, ACTIVATE -> true;
            default -> false;
        };
    }

    private Optional<ContractRef<ResourceTypeId>> requestType(ProtocolBody.ResourceRequest request) {
        return switch (request.operation()) {
            case ResourceListRequest list -> Optional.of(list.type());
            case ResourceQueryRequest query -> Optional.of(query.type());
            case ResourceSubscribeRequest subscribe -> Optional.ofNullable(subscribe.type());
            default -> Optional.empty();
        };
    }

    private Optional<ServerResourceLocator> requestResource(ProtocolBody.ResourceRequest request) {
        return Optional.ofNullable(request.resource());
    }

    private ContractRef<ResourceTypeId> typeReference(String type) {
        if (coreResourceType(type)) {
            String owner = registry.protocolOwner(type);
            return ContractRef.of(new OwnerId(owner), new ResourceTypeId(type));
        }
        FlowResourceMetadata metadata = registry.metadata(type);
        OwnerId owner = metadata != null && !metadata.getOwner().isBlank() && !"builtin".equals(metadata.getOwner())
            ? new OwnerId(metadata.getOwner()) : PROTOCOL_OWNER;
        return ContractRef.of(owner, new ResourceTypeId(type));
    }

    private ContractRef<OperationId> operationReference(ResourceOperationKind operation) {
        return ContractRef.of(PROTOCOL_OWNER, new OperationId("resource." + operation.name().toLowerCase(Locale.ROOT)));
    }

    private int parseCursor(String cursor) {
        try {
            int value = Integer.parseInt(cursor);
            if (value < 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Resource cursor is invalid", exception);
        }
    }

    private ProtocolEnvelopeDispatchResult failure(FlowOperationResult<?> result) {
        return reject(ProtocolRejectionCode.fromResourceError(result.errorCode()), result.message());
    }

    private ProtocolEnvelopeDispatchResult reject(ProtocolRejectionCode code, String message) {
        return ProtocolEnvelopeDispatchResult.rejected(code, message);
    }

    private Map<String, Object> diagnosticIdentity(ConnectionInfo connection, ProtocolEnvelope<Map<String, Object>> envelope,
                                                   ResourceOperation operation) {
        ServerResourceLocator resource = diagnosticResource(operation);
        return TemporaryLifecycleDiagnostics.identity(serverId, resource == null ? diagnosticType(operation) : resource.canonicalText(),
            diagnosticMutationId(operation), envelope == null ? null : envelope.requestId(),
            envelope == null ? null : envelope.correlationId(), diagnosticRevision(operation),
            envelope == null ? authorityEpoch.current() : envelope.authorityEpoch(), connection == null ? null : connection.getConnectionId());
    }

    private Map<String, Object> handlerDiagnosticIdentity(ProtocolEnvelope<Map<String, Object>> envelope) {
        if (envelope != null && envelope.body() instanceof ProtocolBody.ResourceRequest request
            && request.operation() != null) {
            return diagnosticIdentity(null, envelope, request.operation());
        }
        return TemporaryLifecycleDiagnostics.identity(serverId, envelope == null ? null : envelope.kind(), null,
            envelope == null ? null : envelope.requestId(), envelope == null ? null : envelope.correlationId(), null,
            envelope == null ? authorityEpoch.current() : envelope.authorityEpoch(), null);
    }

    private ServerResourceLocator diagnosticResource(ResourceOperation operation) {
        if (operation == null) {
            return null;
        }
        return switch (operation) {
            case ResourceLoadRequest load -> load.resource();
            case ResourceCreateRequest<?> create -> create.resource();
            case ResourceSaveRequest<?> save -> save.resource();
            case ResourceDeleteRequest delete -> delete.resource();
            case ResourceDuplicateRequest duplicate -> duplicate.target();
            case ResourceActivateRequest activate -> activate.resource();
            case ResourceRenameRequest rename -> rename.resource();
            case ResourceMoveRequest move -> move.resource();
            case ResourceSubscribeRequest subscribe -> subscribe.resource();
            default -> null;
        };
    }

    private String diagnosticType(ResourceOperation operation) {
        return switch (operation) {
            case ResourceListRequest list -> list.type().id().value();
            case ResourceQueryRequest query -> query.type().id().value();
            default -> "";
        };
    }

    private UUID diagnosticMutationId(ResourceOperation operation) {
        return switch (operation) {
            case ResourceCreateRequest<?> create -> create.mutationId();
            case ResourceSaveRequest<?> save -> save.mutationId();
            case ResourceDeleteRequest delete -> delete.mutationId();
            case ResourceDuplicateRequest duplicate -> duplicate.mutationId();
            case ResourceActivateRequest activate -> activate.mutationId();
            case ResourceRenameRequest rename -> rename.mutationId();
            case ResourceMoveRequest move -> move.mutationId();
            default -> null;
        };
    }

    private Long diagnosticRevision(ResourceOperation operation) {
        return switch (operation) {
            case ResourceSaveRequest<?> save -> save.expectedRevision();
            case ResourceDeleteRequest delete -> delete.expectedRevision();
            case ResourceDuplicateRequest duplicate -> duplicate.expectedRevision();
            case ResourceActivateRequest activate -> activate.expectedRevision();
            case ResourceRenameRequest rename -> rename.expectedRevision();
            case ResourceMoveRequest move -> move.expectedRevision();
            default -> null;
        };
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        String normalized = message.replace('\n', ' ').replace('\r', ' ');
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240);
    }

    private static AuthorityEpoch requireExplicitAuthorityEpoch() {
        throw new IllegalStateException("Authority epoch is required; use the bound-epoch constructor");
    }

}
