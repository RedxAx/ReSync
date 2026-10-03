package restudio.resync.qa;

import org.bukkit.command.CommandSender;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.catalog.CatalogOwned;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceKey;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.inspector.InspectorCondition;
import restudio.resync.flow.protocol.CanonicalPayload;
import restudio.resync.flow.protocol.ProtocolBody;
import restudio.resync.flow.protocol.ProtocolEnvelope;
import restudio.resync.flow.protocol.ProtocolEnvelopeCodec;
import restudio.resync.flow.protocol.ProtocolRejectionCode;
import restudio.resync.flow.protocol.ResourceActivateRequest;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.protocol.ResourceCreateRequest;
import restudio.resync.flow.protocol.ResourceDeleteRequest;
import restudio.resync.flow.protocol.ResourceDuplicateRequest;
import restudio.resync.flow.protocol.ResourceListRequest;
import restudio.resync.flow.protocol.ResourceLoadRequest;
import restudio.resync.flow.protocol.ResourceOperation;
import restudio.resync.flow.protocol.ResourcePresentationIntent;
import restudio.resync.flow.protocol.ResourceSaveRequest;
import restudio.resync.flow.resource.ResourcePayloadCodec;
import restudio.resync.flow.resource.ResourcePayloadCodecs;
import restudio.resync.flow.sync.FlowResourceMetadata;
import restudio.resync.flow.type.TypeValueCodec;
import restudio.resync.modules.flow.FlowResourceRegistry;
import restudio.resync.protocol.ReSyncProtocolContract;
import restudio.resync.server.AuthorityEpoch;
import restudio.resync.server.FlowResourceProtocolEnvelopeHandler;
import restudio.resync.server.ProtocolEnvelopeDispatchResult;
import restudio.resync.server.ProtocolRequestAuthority;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class QaResourceAdapter {
    private static final OwnerId OWNER = OwnerId.of("restudio.resync");
    private static final ResourcePayloadCodec<Map<String, Object>> PAYLOAD = ResourcePayloadCodecs.json();
    private static final ProtocolEnvelopeCodec<Map<String, Object>> ENVELOPES = new ProtocolEnvelopeCodec<>(PAYLOAD);
    private static final Set<String> CATALOG_FIELDS = Set.of("owner", "nodeId", "functionId", "limit", "cursor",
        "requestId", "correlationId", "traceId", "serverId", "authorityEpoch", "catalogGeneration", "catalogChecksum", "bindingManifestHash");
    private static final Set<ContractRef<CapabilityId>> CAPABILITIES = Set.of(
        ContractRef.of(OWNER, CapabilityId.of("resources")), ReSyncProtocolContract.RESOURCE_ACTIVATION_CAPABILITY,
        ReSyncProtocolContract.RESOURCE_CREATE_PRESENTATION_CAPABILITY);
    private static final Map<String, Object> DESCRIPTION = PAYLOAD.normalize(Map.of(
        "operations", List.of(
            operation("catalog.inspect", "Inspect admitted node identities and exact pin contracts",
                List.of(), List.of("owner", "nodeId", "functionId", "limit", "cursor", "catalogGeneration")),
            operation("resource.discover", "List registered resource types, availability, and supported operations", List.of(), List.of()),
            operation("resource.list", "List a page of authoritative resource documents", List.of("type"), List.of("cursor", "limit", "search")),
            operation("resource.load", "Load one authoritative resource document and its revision", List.of("type", "id"), List.of()),
            operation("resource.create", "Create a resource and store its durable mutation receipt",
                List.of("type", "id", "payload", "payloadChecksum", "mutationId"), List.of("presentation")),
            operation("resource.save", "Save a resource when its current revision matches expectedRevision",
                List.of("type", "id", "expectedRevision", "payload", "payloadChecksum", "mutationId"), List.of()),
            operation("resource.delete", "Delete a resource when its current revision matches expectedRevision",
                List.of("type", "id", "expectedRevision", "mutationId"), List.of()),
            operation("resource.duplicate", "Copy a resource to a distinct target ID when its source revision matches",
                List.of("type", "sourceId", "targetId", "expectedRevision", "mutationId"), List.of()),
            operation("resource.activate", "Set a resource to active or inactive when its revision matches",
                List.of("type", "id", "expectedRevision", "activationState", "mutationId"), List.of()),
            Map.of("id", "resource.rename", "description", "Durable presentation rename is unavailable", "supported", false,
                "input", Map.of("required", List.of(), "optional", List.of())),
            operation("payload.canonicalize", "Normalize a JSON resource payload and return the checksum required for create or save",
                List.of("payload"), List.of())),
        "permission", "resync.qa",
        "optionalIdentity", List.of("requestId", "correlationId", "traceId", "serverId", "authorityEpoch", "catalogChecksum", "bindingManifestHash"),
        "payloadChecksum", "The canonical resource payload checksum returned by the typed resource protocol",
        "settlement", "The existing durable resource protocol response after physical worker completion"));
    private final Supplier<FlowResourceRegistry> resources;
    private final Supplier<FlowResourceProtocolEnvelopeHandler> handler;
    private final Supplier<ServerId> serverId;
    private final Supplier<CatalogSnapshot> catalog;
    private final AuthorityEpoch epoch;
    private final Consumer<Runnable> primaryThread;
    private final BiFunction<Integer, Runnable, CompletionStage<Void>> worker;

    public QaResourceAdapter(Supplier<FlowResourceRegistry> resources,
                             Supplier<FlowResourceProtocolEnvelopeHandler> handler,
                             Supplier<ServerId> serverId, Supplier<CatalogSnapshot> catalog,
                             AuthorityEpoch epoch, Consumer<Runnable> primaryThread,
                             BiFunction<Integer, Runnable, CompletionStage<Void>> worker) {
        this.resources = Objects.requireNonNull(resources, "Resource registry supplier is required");
        this.handler = Objects.requireNonNull(handler, "Resource protocol supplier is required");
        this.serverId = Objects.requireNonNull(serverId, "Server identity supplier is required");
        this.catalog = Objects.requireNonNull(catalog, "Catalog supplier is required");
        this.epoch = Objects.requireNonNull(epoch, "Authority epoch is required");
        this.primaryThread = Objects.requireNonNull(primaryThread, "Server thread scheduler is required");
        this.worker = Objects.requireNonNull(worker, "Protocol worker admission is required");
    }

    public Map<String, Object> describe() {
        return DESCRIPTION;
    }

    public CompletionStage<Map<String, Object>> invoke(CommandSender actor, String operation, Map<String, Object> input) {
        CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
        try {
            byte[] inputBytes = CanonicalJson.canonicalBytes(input == null ? Map.of() : input);
            Map<String, Object> admittedInput = object(CanonicalJson.parseTree(inputBytes).toJava(), "input");
            String name = Objects.requireNonNull(operation, "Operation is required").trim().toLowerCase(Locale.ROOT);
            if (name.startsWith("resource.")) {
                name = name.substring("resource.".length());
            }
            String admittedOperation = name;
            primaryThread.accept(() -> admit(actor, admittedOperation, admittedInput, inputBytes.length, result));
        } catch (RuntimeException failure) {
            result.complete(failure(ProtocolRejectionCode.INVALID_PAYLOAD, safeMessage(failure)));
        }
        return result.minimalCompletionStage();
    }

    private void admit(CommandSender actor, String operation, Map<String, Object> input, int inputBytes,
                       CompletableFuture<Map<String, Object>> result) {
        try {
            if (ProtocolRequestAuthority.trustedOperatorId(actor) == null) {
                throw new SecurityException("ReSync QA permission and server thread admission are required");
            }
            if ("rename".equals(operation)) {
                result.complete(failure(ProtocolRejectionCode.RESOURCE_OPERATION_UNSUPPORTED,
                    "Resource adapters do not expose a durable presentation rename transaction"));
                return;
            }
            ServerId currentServer = serverId.get();
            CatalogSnapshot currentCatalog = catalog.get();
            FlowResourceRegistry registry = resources.get();
            if (currentServer == null || currentCatalog == null || registry == null) {
                result.complete(failure(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE, "Resource authority is not ready"));
                return;
            }
            long currentEpoch = epoch.current();
            validateIdentity(input, currentServer, currentCatalog, currentEpoch);
            if ("catalog.inspect".equals(operation)) {
                CatalogQuery query = catalogQuery(input);
                submit(inputBytes, () -> inspectCatalog(query, currentServer, currentCatalog, currentEpoch), result,
                    () -> currentIdentity(currentServer, currentCatalog, currentEpoch));
                return;
            }
            if ("payload.canonicalize".equals(operation)) {
                Map<String, Object> payload = object(input.get("payload"), "payload");
                submit(inputBytes, () -> {
                    CanonicalPayload<Map<String, Object>> canonical = PAYLOAD.canonicalize(payload);
                    return Map.of("status", "handled", "payload", canonical.value(), "payloadChecksum", canonical.checksum().canonicalText());
                }, result);
                return;
            }
            if ("discover".equals(operation)) {
                submit(inputBytes, () -> discover(registry, currentServer, currentCatalog, currentEpoch), result);
                return;
            }
            ProtocolEnvelope<Map<String, Object>> request = request(operation, input, registry, currentServer, currentCatalog, currentEpoch);
            ProtocolRequestAuthority.OperatorGrant grant = ProtocolRequestAuthority.admitOperator(actor, request);
            FlowResourceProtocolEnvelopeHandler currentHandler = handler.get();
            if (currentHandler == null) {
                result.complete(failure(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE, "Resource protocol is not ready"));
                return;
            }
            int requestBytes = ENVELOPES.encodeBytes(request).length;
            submit(requestBytes, () -> currentIdentity(currentServer, currentCatalog, currentEpoch) && handler.get() == currentHandler
                ? response(request, currentHandler.handleOperator(grant, request))
                : failure(ProtocolRejectionCode.RESOURCE_REVISION_CONFLICT, "Resource request identity changed after admission"), result);
        } catch (SecurityException failure) {
            result.complete(failure(ProtocolRejectionCode.AUTHORIZATION_DENIED, safeMessage(failure)));
        } catch (StaleIdentity failure) {
            result.complete(failure(ProtocolRejectionCode.RESOURCE_REVISION_CONFLICT, safeMessage(failure)));
        } catch (RuntimeException failure) {
            result.complete(failure(ProtocolRejectionCode.INVALID_PAYLOAD, safeMessage(failure)));
        }
    }

    private void submit(int requestBytes, Supplier<Map<String, Object>> action,
                        CompletableFuture<Map<String, Object>> result) {
        submit(requestBytes, action, result, () -> true);
    }

    private void submit(int requestBytes, Supplier<Map<String, Object>> action,
                        CompletableFuture<Map<String, Object>> result, Supplier<Boolean> admissionCurrent) {
        CompletableFuture<Map<String, Object>> outcome = new CompletableFuture<>();
        CompletionStage<Void> physical = Objects.requireNonNull(worker.apply(requestBytes, () -> {
            try {
                outcome.complete(action.get());
            } catch (Throwable failure) {
                outcome.completeExceptionally(failure);
            }
        }), "Protocol worker completion is required");
        physical.whenComplete((ignored, physicalFailure) -> {
            if (physicalFailure != null) {
                result.complete(failure(ProtocolRejectionCode.RESOURCE_MUTATION_PENDING, "Protocol worker admission did not complete"));
                return;
            }
            outcome.whenComplete((value, actionFailure) -> {
                try {
                    result.complete(!Boolean.TRUE.equals(admissionCurrent.get())
                        ? failure(ProtocolRejectionCode.RESOURCE_REVISION_CONFLICT, "Request identity changed before worker completion")
                        : actionFailure == null ? value : failure(ProtocolRejectionCode.RESOURCE_OPERATION_FAILED, "Resource operation failed"));
                } catch (Throwable failure) {
                    result.complete(failure(ProtocolRejectionCode.RESOURCE_OPERATION_FAILED, "Resource operation failed"));
                }
            });
        });
    }

    private Map<String, Object> inspectCatalog(CatalogQuery query, ServerId server, CatalogSnapshot snapshot, long admittedEpoch) {
        if (!currentIdentity(server, snapshot, admittedEpoch)) {
            return failure(ProtocolRejectionCode.RESOURCE_REVISION_CONFLICT, "Catalog inspection identity changed after admission");
        }
        List<CatalogOwned<CatalogNodeDescriptor>> matching = query.owner() != null && query.nodeId() != null
            ? snapshot.definition(ContractRef.of(query.owner(), query.nodeId())).stream().filter(query::matches).toList()
            : snapshot.definitions().stream().filter(query::matches).toList();
        List<Map<String, Object>> nodes = matching.stream().skip(query.offset()).limit(query.limit())
            .map(QaResourceAdapter::catalogNode).toList();
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("status", "handled");
        page.put("serverId", server.canonicalText());
        page.put("authorityEpoch", admittedEpoch);
        page.put("catalogGeneration", snapshot.generation());
        page.put("catalogChecksum", snapshot.contentChecksum().canonicalText());
        page.put("bindingManifestHash", snapshot.bindingManifestHash().canonicalText());
        page.put("nodes", nodes);
        page.put("cursor", Integer.toString(query.offset()));
        page.put("limit", query.limit());
        int next = query.offset() + nodes.size();
        page.put("nextCursor", next < matching.size() ? Integer.toString(next) : null);
        Map<String, Object> captured = PAYLOAD.normalize(page);
        return currentIdentity(server, snapshot, admittedEpoch) ? captured
            : failure(ProtocolRejectionCode.RESOURCE_REVISION_CONFLICT, "Catalog inspection identity changed during capture");
    }

    private static Map<String, Object> catalogNode(CatalogOwned<CatalogNodeDescriptor> owned) {
        CatalogNodeDescriptor definition = owned.descriptor();
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("identity", reference(owned.key()));
        node.put("owner", owned.key().owner().value());
        node.put("nodeId", definition.id().value());
        node.put("schemaVersion", definition.schemaVersion());
        node.put("displayName", definition.displayName());
        node.put("description", definition.description());
        node.put("lifecycle", definition.lifecycle().name().toLowerCase(Locale.ROOT));
        node.put("domain", definition.domain());
        node.put("family", definition.family());
        node.put("category", reference(definition.category()));
        node.put("handler", Map.of("capability", reference(definition.handler().capability()),
            "operation", reference(definition.handler().operation())));
        node.put("semantics", definition.semantics().canonicalValue());
        node.put("metadata", definition.metadata());
        node.put("requiredCapabilities", definition.requiredCapabilities().stream()
            .sorted().map(QaResourceAdapter::reference).toList());
        node.put("pins", definition.pins().stream().map(QaResourceAdapter::catalogPin).toList());
        node.put("branches", definition.branches().stream().map(branch -> Map.of("id", branch.id().value(),
            "title", branch.title(), "description", branch.description(), "cases", branch.cases().stream()
                .map(value -> Map.of("id", value.id().value(), "title", value.title(), "description", value.description())).toList())).toList());
        node.put("modes", definition.modes().stream().map(mode -> Map.of("id", mode.id().value(),
            "displayName", mode.displayName(), "description", mode.description(), "visibility", condition(mode.visibility()),
            "transformation", reference(mode.transformation()))).toList());
        return node;
    }

    private static Map<String, Object> catalogPin(CatalogNodeDescriptor.Pin pin) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", pin.id().value());
        value.put("direction", pin.direction().name().toLowerCase(Locale.ROOT));
        value.put("type", TypeValueCodec.INSTANCE.encodeType(pin.type()).toJava());
        value.put("displayName", pin.displayName());
        value.put("description", pin.description());
        value.put("required", pin.requirement() == CatalogNodeDescriptor.Requirement.REQUIRED);
        value.put("requirement", pin.requirement().name().toLowerCase(Locale.ROOT));
        value.put("default", pin.defaultValue() == null ? null : TypeValueCodec.INSTANCE.encode(pin.defaultValue()).toJava());
        value.put("editor", reference(pin.editor()));
        value.put("optionSource", pin.optionSource() == null ? null : reference(pin.optionSource()));
        value.put("visibility", condition(pin.visibility()));
        value.put("resourceRole", pin.resourceRole());
        Map<String, Object> repeatable = new LinkedHashMap<>();
        repeatable.put("enabled", pin.repeatable().enabled());
        repeatable.put("ordered", pin.repeatable().ordered());
        repeatable.put("minimum", pin.repeatable().minimum());
        repeatable.put("maximum", pin.repeatable().maximum());
        repeatable.put("groupId", pin.repeatable().groupId() == null ? null : pin.repeatable().groupId().value());
        value.put("repeatable", repeatable);
        Map<String, Object> presentation = new LinkedHashMap<>();
        presentation.put("widget", pin.presentation().widget());
        presentation.put("options", pin.presentation().options().stream().map(option -> TypeValueCodec.INSTANCE.encode(option).toJava()).toList());
        presentation.put("constraints", pin.presentation().constraints());
        presentation.put("visibleWhen", pin.presentation().visibleWhen());
        value.put("presentation", presentation);
        return value;
    }

    private static Map<String, Object> reference(ContractRef<?> reference) {
        return reference.canonicalValue();
    }

    private static Map<String, Object> condition(InspectorCondition condition) {
        return switch (condition) {
            case InspectorCondition.Always ignored -> Map.of("kind", "always");
            case InspectorCondition.Present present -> Map.of("kind", "present", "fieldId", present.field().value());
            case InspectorCondition.Equals equal -> Map.of("kind", "equals", "fieldId", equal.field().value(),
                "value", TypeValueCodec.INSTANCE.encode(equal.value()).toJava());
            case InspectorCondition.NotEquals unequal -> Map.of("kind", "not-equals", "fieldId", unequal.field().value(),
                "value", TypeValueCodec.INSTANCE.encode(unequal.value()).toJava());
            case InspectorCondition.All all -> Map.of("kind", "all", "children", all.conditions().stream().map(QaResourceAdapter::condition).toList());
            case InspectorCondition.Any any -> Map.of("kind", "any", "children", any.conditions().stream().map(QaResourceAdapter::condition).toList());
            case InspectorCondition.Not not -> Map.of("kind", "not", "children", List.of(condition(not.condition())));
        };
    }

    private static CatalogQuery catalogQuery(Map<String, Object> input) {
        if (input.keySet().stream().anyMatch(key -> !CATALOG_FIELDS.contains(key))) {
            throw new IllegalArgumentException("Catalog inspection contains an unknown input field");
        }
        String owner = text(input, "owner", false);
        String nodeId = text(input, "nodeId", false);
        String functionId = text(input, "functionId", false);
        if (functionId != null) {
            ResourceKey.of(ContractRef.of(OWNER, ResourceTypeId.of("function")), functionId);
        }
        long limit = number(input, "limit", false, 50L);
        if (limit < 1L || limit > 200L) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
        String cursor = text(input, "cursor", false);
        if (cursor != null && !cursor.matches("0|[1-9][0-9]{0,9}")) {
            throw new IllegalArgumentException("cursor must be a nonnegative decimal offset");
        }
        return new CatalogQuery(owner == null ? null : OwnerId.of(owner), nodeId == null ? null : NodeId.of(nodeId),
            functionId, (int) limit, cursor == null ? 0 : Integer.parseInt(cursor));
    }

    private record CatalogQuery(OwnerId owner, NodeId nodeId, String functionId, int limit, int offset) {
        private boolean matches(CatalogOwned<CatalogNodeDescriptor> owned) {
            if (owner != null && !owner.equals(owned.key().owner()) || nodeId != null && !nodeId.equals(owned.descriptor().id())) {
                return false;
            }
            Object identity = owned.descriptor().metadata().get("customFunctionIdentity");
            return functionId == null || identity instanceof Map<?, ?> metadata && functionId.equals(metadata.get("id"));
        }
    }

    private Map<String, Object> discover(FlowResourceRegistry registry, ServerId server, CatalogSnapshot snapshot, long admittedEpoch) {
        if (!currentIdentity(server, snapshot, admittedEpoch)) {
            return failure(ProtocolRejectionCode.RESOURCE_REVISION_CONFLICT, "Resource discovery identity changed after admission");
        }
        List<Map<String, Object>> types = new ArrayList<>();
        for (FlowResourceMetadata metadata : registry.metadata()) {
            Map<String, Object> type = new LinkedHashMap<>();
            type.put("type", metadata.getTypeId());
            String owner = registry.protocolOwner(metadata.getTypeId());
            type.put("owner", owner == null || owner.isBlank() ? null : "builtin".equals(owner) ? OWNER.value() : owner);
            type.put("displayName", metadata.getDisplayName());
            type.put("identityRules", metadata.getIdentityRules());
            type.put("lifecycle", metadata.getLifecycle());
            type.put("operations", metadata.getOperations());
            type.put("operationAvailability", metadata.getOperationAvailability());
            type.put("durable", metadata.isDurable());
            type.put("available", metadata.isAvailable());
            type.put("unavailableReason", metadata.getUnavailableReason());
            types.add(type);
        }
        return PAYLOAD.normalize(Map.of("status", "handled", "types", types, "serverId", server.canonicalText(),
            "authorityEpoch", admittedEpoch, "catalogChecksum", snapshot.contentChecksum().canonicalText(),
            "bindingManifestHash", snapshot.bindingManifestHash().canonicalText(), "catalogGeneration", snapshot.generation()));
    }

    private ProtocolEnvelope<Map<String, Object>> request(String operation, Map<String, Object> input,
                                                          FlowResourceRegistry registry, ServerId server,
                                                          CatalogSnapshot snapshot, long admittedEpoch) {
        String typeId = text(input, "type", true);
        String typeOwner = registry.protocolOwner(typeId);
        if (typeOwner == null || typeOwner.isBlank()) {
            throw new IllegalArgumentException("Resource type is unavailable: " + typeId);
        }
        ContractRef<ResourceTypeId> type = ContractRef.of("builtin".equals(typeOwner) ? OWNER : OwnerId.of(typeOwner), ResourceTypeId.of(typeId));
        UUID mutationId = Set.of("create", "save", "delete", "duplicate", "activate").contains(operation)
            ? uuid(input, "mutationId", true) : null;
        long revision = Set.of("save", "delete", "duplicate", "activate").contains(operation)
            ? number(input, "expectedRevision", true, 0L) : 0L;
        ServerResourceLocator locator = "list".equals(operation) ? null
            : new ServerResourceLocator(server, type, text(input, "duplicate".equals(operation) ? "targetId" : "id", true));
        ResourceOperation body = switch (operation) {
            case "list" -> new ResourceListRequest(type, text(input, "cursor", false),
                Math.toIntExact(number(input, "limit", false, 100L)), text(input, "search", false));
            case "load" -> new ResourceLoadRequest(locator);
            case "create" -> new ResourceCreateRequest<>(locator, payload(input), mutationId, presentation(input));
            case "save" -> new ResourceSaveRequest<>(locator, revision, payload(input), mutationId);
            case "delete" -> new ResourceDeleteRequest(locator, revision, mutationId);
            case "duplicate" -> new ResourceDuplicateRequest(new ServerResourceLocator(server, type, text(input, "sourceId", true)),
                locator, revision, mutationId);
            case "activate" -> new ResourceActivateRequest(locator, revision,
                ResourceActivationState.fromWireName(text(input, "activationState", true)), mutationId);
            default -> throw new IllegalArgumentException("Resource operation is unsupported: " + operation);
        };
        ContentHash hash = switch (body) {
            case ResourceCreateRequest<?> create -> create.payloadHash();
            case ResourceSaveRequest<?> save -> save.payloadHash();
            default -> null;
        };
        UUID requestId = uuid(input, "requestId", false);
        UUID correlationId = uuid(input, "correlationId", false);
        UUID traceId = input.containsKey("traceId") ? uuid(input, "traceId", true) : correlationId;
        return new ProtocolEnvelope<>(ProtocolEnvelope.Kind.REQUEST, ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION,
            UUID.randomUUID(), requestId, correlationId, traceId, server, locator, revision, admittedEpoch, mutationId,
            ContractRef.of(OWNER, OperationId.of("resource." + operation)), CAPABILITIES,
            ContractRef.of(OWNER, ResourceTypeId.of("list".equals(operation) ? "resource.page" : "resource.document")),
            null, hash, false, null, snapshot.contentChecksum(), snapshot.bindingManifestHash(), null, null,
            0L, ProtocolEnvelope.Status.ACCEPTED, List.of(), Map.of(), new ProtocolBody.ResourceRequest(body));
    }

    private CanonicalPayload<Map<String, Object>> payload(Map<String, Object> input) {
        CanonicalPayload<Map<String, Object>> payload = PAYLOAD.canonicalize(object(input.get("payload"), "payload"));
        if (!payload.checksum().equals(ContentHash.parseCanonicalText(text(input, "payloadChecksum", true)))) {
            throw new IllegalArgumentException("Payload checksum does not match the canonical resource payload");
        }
        return payload;
    }

    private ResourcePresentationIntent presentation(Map<String, Object> input) {
        if (!input.containsKey("presentation")) {
            return null;
        }
        Map<String, Object> presentation = object(input.get("presentation"), "presentation");
        return new ResourcePresentationIntent(text(presentation, "displayName", true), text(presentation, "path", true),
            Math.toIntExact(number(presentation, "sortOrder", false, 0L)));
    }

    private Map<String, Object> response(ProtocolEnvelope<Map<String, Object>> request, ProtocolEnvelopeDispatchResult dispatch) {
        Map<String, Object> value = new LinkedHashMap<>(dispatch.structured());
        value.put("requestId", request.requestId().toString());
        value.put("authorityEpoch", request.authorityEpoch());
        if (request.mutationId() != null) {
            value.put("mutationId", request.mutationId().toString());
        }
        if (dispatch.response() != null) {
            value.put("response", ENVELOPES.encode(dispatch.response()).toJava());
        }
        return PAYLOAD.normalize(value);
    }

    private void validateIdentity(Map<String, Object> input, ServerId server, CatalogSnapshot snapshot, long admittedEpoch) {
        if (input.containsKey("serverId") && !server.equals(ServerId.parseCanonicalText(text(input, "serverId", true)))
            || input.containsKey("authorityEpoch") && number(input, "authorityEpoch", true, 0L) != admittedEpoch
            || input.containsKey("catalogGeneration") && number(input, "catalogGeneration", true, 0L) != snapshot.generation()
            || input.containsKey("catalogChecksum") && !snapshot.contentChecksum().equals(ContentHash.parseCanonicalText(text(input, "catalogChecksum", true)))
            || input.containsKey("bindingManifestHash") && !snapshot.bindingManifestHash().equals(ContentHash.parseCanonicalText(text(input, "bindingManifestHash", true)))) {
            throw new StaleIdentity("Resource request identity is stale or belongs to another server");
        }
    }

    private boolean currentIdentity(ServerId server, CatalogSnapshot snapshot, long admittedEpoch) {
        return epoch.acceptsTyped(admittedEpoch) && server.equals(serverId.get()) && catalog.get() == snapshot;
    }

    private static Map<String, Object> failure(ProtocolRejectionCode code, String message) {
        return ProtocolEnvelopeDispatchResult.rejected(code, message).structured();
    }

    private static Map<String, Object> operation(String id, String description, List<String> required, List<String> optional) {
        List<String> allOptional = new ArrayList<>(optional);
        allOptional.addAll(List.of("requestId", "correlationId", "traceId", "serverId", "authorityEpoch", "catalogChecksum", "bindingManifestHash"));
        if ("catalog.inspect".equals(id)) {
            return Map.of("id", id, "description", description, "input", Map.of("required", required, "optional", allOptional),
                "paging", Map.of("defaultLimit", 50, "maximumLimit", 200, "nextCursor", "Use the returned nextCursor and catalog identity for the next page"),
                "settlement", "Returns a current catalog page after physical worker completion");
        }
        return Map.of("id", id, "description", description, "input", Map.of("required", required, "optional", allOptional),
            "payloadChecksum", "Use payload.canonicalize to obtain the typed resource payload checksum",
            "settlement", "Returns the existing durable protocol response after physical worker completion. Replay mutations with the same mutationId");
    }

    private static String text(Map<String, Object> input, String field, boolean required) {
        Object value = input.get(field);
        if (value == null && !required) {
            return null;
        }
        if (!(value instanceof String text) || required && text.isBlank()) {
            throw new IllegalArgumentException(field + " must be a" + (required ? " nonempty" : "") + " string");
        }
        return text;
    }

    private static long number(Map<String, Object> input, String field, boolean required, long fallback) {
        Object value = input.get(field);
        if (value == null && !required) {
            return fallback;
        }
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(field + " must be a nonnegative integer");
        }
        try {
            long result = new BigDecimal(number.toString()).longValueExact();
            if (result < 0L) {
                throw new ArithmeticException();
            }
            return result;
        } catch (ArithmeticException | NumberFormatException failure) {
            throw new IllegalArgumentException(field + " must be a nonnegative integer", failure);
        }
    }

    private static UUID uuid(Map<String, Object> input, String field, boolean required) {
        String value = text(input, field, required);
        return value == null ? UUID.randomUUID() : UUID.fromString(value);
    }

    private static Map<String, Object> object(Object value, String field) {
        if (!(value instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
            throw new IllegalArgumentException(field + " must be a JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> object = (Map<String, Object>) map;
        return object;
    }

    private static String safeMessage(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() || message.length() > 512 ? "Resource request is invalid" : message;
    }

    private static final class StaleIdentity extends IllegalArgumentException {
        private StaleIdentity(String message) {
            super(message);
        }
    }
}
