package restudio.resync.modules.flow;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import restudio.resync.Log;
import restudio.flow.data.CustomContentDefinition;
import restudio.flow.data.CustomContentGraphAdapter;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowSerializer;
import restudio.resync.customcontent.CustomContentAccess;
import restudio.resync.customcontent.CustomContentService;
import restudio.resync.customcontent.CustomContentStorage;
import restudio.resync.core.Session;
import restudio.resync.flow.FlowFunctionInUseException;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.CoreGraphStorageBoundary;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.ResourceRevisionConflictException;
import restudio.resync.flow.GlobalTriggers;
import restudio.resync.flow.handler.event.FlowEventRegistry;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.triggers.TriggerBinding;
import restudio.resync.flow.triggers.TriggerRegistry;
import restudio.resync.flow.triggers.TriggerType;
import restudio.resync.flow.validation.FlowGraphValidationException;
import restudio.resync.jobs.JobRecord;
import restudio.resync.protocol.ReSyncProtocolContract;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.server.AuthorityEpoch;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class FlowBlueprintPacketHandler {
    private static final Set<String> DIRECT_DISPATCH_EVENT_NODES = Set.of("resync_command", "click");
    private final FlowStorage storage;
    private final TriggerRegistry triggerRegistry;
    private final GlobalTriggers globalTriggers;
    private final NodeDefinitionRegistry definitionRegistry;
    private final FlowPacketSender sender;
    private final FlowResourceRegistry resourceRegistry;
    private final AuthorityEpoch authorityEpoch;
    private final Predicate<Session> legacyCompatibility;
    private final Gson gson = new Gson();

    public FlowBlueprintPacketHandler(FlowStorage storage, TriggerRegistry triggerRegistry, GlobalTriggers globalTriggers, FlowPacketSender sender) {
        this(storage, triggerRegistry, globalTriggers, NodeDefinitionRegistry.getInstance(), sender, null,
            requireExplicitAuthorityEpoch(sender), FlowMutationPayloadReader::legacyCompatible);
    }

    public FlowBlueprintPacketHandler(FlowStorage storage, TriggerRegistry triggerRegistry, GlobalTriggers globalTriggers, NodeDefinitionRegistry definitionRegistry, FlowPacketSender sender) {
        this(storage, triggerRegistry, globalTriggers, definitionRegistry, sender, null,
            requireExplicitAuthorityEpoch(sender), FlowMutationPayloadReader::legacyCompatible);
    }

    public FlowBlueprintPacketHandler(FlowStorage storage, TriggerRegistry triggerRegistry, GlobalTriggers globalTriggers, NodeDefinitionRegistry definitionRegistry, FlowPacketSender sender, FlowResourceRegistry resourceRegistry) {
        this(storage, triggerRegistry, globalTriggers, definitionRegistry, sender, resourceRegistry,
            requireExplicitAuthorityEpoch(sender), FlowMutationPayloadReader::legacyCompatible);
    }

    public FlowBlueprintPacketHandler(FlowStorage storage, TriggerRegistry triggerRegistry, GlobalTriggers globalTriggers,
                                      NodeDefinitionRegistry definitionRegistry, FlowPacketSender sender,
                                      FlowResourceRegistry resourceRegistry, AuthorityEpoch authorityEpoch,
                                      boolean legacyCompatible) {
        this(storage, triggerRegistry, globalTriggers, definitionRegistry, sender, resourceRegistry, authorityEpoch,
            legacyCompatible ? FlowMutationPayloadReader::legacyCompatible : ignored -> false);
    }

    public FlowBlueprintPacketHandler(FlowStorage storage, TriggerRegistry triggerRegistry, GlobalTriggers globalTriggers,
                                      NodeDefinitionRegistry definitionRegistry, FlowPacketSender sender,
                                      FlowResourceRegistry resourceRegistry, AuthorityEpoch authorityEpoch) {
        this(storage, triggerRegistry, globalTriggers, definitionRegistry, sender, resourceRegistry, authorityEpoch, false);
    }

    public FlowBlueprintPacketHandler(FlowStorage storage, TriggerRegistry triggerRegistry, GlobalTriggers globalTriggers,
                                      NodeDefinitionRegistry definitionRegistry, FlowPacketSender sender,
                                      FlowResourceRegistry resourceRegistry, AuthorityEpoch authorityEpoch,
                                      Predicate<Session> legacyCompatibility) {
        this.storage = storage;
        this.triggerRegistry = triggerRegistry;
        this.globalTriggers = globalTriggers;
        this.definitionRegistry = definitionRegistry == null ? new NodeDefinitionRegistry(false) : definitionRegistry;
        this.sender = sender;
        this.resourceRegistry = resourceRegistry;
        if (authorityEpoch != null && authorityEpoch.current() < 1L) {
            throw new IllegalArgumentException("Authority epoch must be positive");
        }
        if (sender != null && authorityEpoch == null) {
            throw new IllegalStateException("Authority epoch is required when packet handling is enabled");
        }
        this.authorityEpoch = authorityEpoch;
        Predicate<Session> configuredLegacyCompatibility = Objects.requireNonNull(legacyCompatibility,
            "Legacy compatibility policy is required");
        this.legacyCompatibility = session -> configuredLegacyCompatibility.test(session)
            && FlowMutationPayloadReader.legacyCompatible(session);
    }

    public void handleRequest(Session session, ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            sender.sendError(session, "INVALID_REQUEST", "Flow ID not provided");
            return;
        }
        byte[] idBytes = new byte[buffer.remaining()];
        buffer.get(idBytes);
        String flowId = new String(idBytes, StandardCharsets.UTF_8);
        if (flowId.length() > FlowPacketSender.MAX_STRING_LENGTH) {
            sender.sendError(session, "INVALID_FLOW_ID", "Flow ID too long");
            return;
        }
        FlowGraph graph = storage.getGraph(flowId);
        if (graph != null) {
            sender.sendFlowData(session, graph);
        } else {
            sender.sendError(session, "FLOW_NOT_FOUND", "Flow not found: " + flowId);
        }
    }

    public void handleSave(Session session, ByteBuffer buffer) {
        if (resourceRegistry != null && resourceRegistry.genericMutationAuthorityRequired()) {
            sender.sendError(session, "RESOURCE_GENERIC_AUTHORITY_REQUIRED", resourceRegistry.genericMutationAuthorityReason());
            return;
        }
        if (!buffer.hasRemaining()) {
            sender.sendError(session, "INVALID_SAVE", "No data provided");
            return;
        }
        if (buffer.remaining() > FlowPacketSender.MAX_PACKET_SIZE) {
            sender.sendError(session, "SAVE_TOO_LARGE", "Save data exceeds maximum size");
            return;
        }
        FlowMutationPayload payload = FlowMutationPayloadReader.read(buffer);
        if (!validateMutationEpoch(session, payload)) {
            return;
        }
        String json = payload.payload();
        JobRecord<String> job = sender.beginJob(session, "saveFlow", "", payload.requestId());
        if (job == null) {
            return;
        }
        FlowGraph previousGraph = null;
        Map<String, CustomContentDefinition> previousContent = Map.of();
        List<TriggerBinding> previousEventBindings = List.of();
        String rollbackFlowId = null;
        boolean graphPersisted = false;
        try {
            FlowGraph graph = FlowSerializer.deserialize(json);
            if (graph == null) {
                sender.failJob(job, "Failed to parse flow graph", null);
                return;
            }
            if (graph.getId() == null) {
                graph.setId(UUID.randomUUID().toString());
            }
            graph.setResourceMutationId(payload.requestId());
            String flowId = graph.getId().toString();
            String storedType = storage.getGraphResourceType(flowId);
            if (storage.getGraph(ReSyncResourceCatalog.FLOW, flowId) == null
                && (ReSyncResourceCatalog.FUNCTION.equals(storedType) || ReSyncResourceCatalog.COMMAND.equals(storedType))) {
                graph.setResourceType(storedType);
                graph.setFunction(ReSyncResourceCatalog.FUNCTION.equals(storedType));
            }
            storage.requireValidGraph(graph);
            rollbackFlowId = flowId;
            previousEventBindings = eventBindings(flowId);
            previousGraph = storage.getGraph(flowId);
            CustomContentStorage customContentStorage = CustomContentAccess.getStorage();
            CustomContentDefinition content = CustomContentGraphAdapter.toDefinition(graph);
            if (content != null) {
                if (customContentStorage != null) {
                    content = customContentStorage.repairMalformedFlowIdentity(content);
                    previousContent = customContentStorage.getByFlow(flowId).stream()
                        .collect(Collectors.toMap(CustomContentDefinition::getId, Function.identity(), (left, right) -> left));
                    CustomContentService customContentService = CustomContentAccess.getService();
                    customContentStorage.save(content);
                    if (customContentService != null) {
                        customContentService.reconcileContentItems(content.getId());
                    }
                    for (String previousContentId : previousContent.keySet()) {
                        if (!previousContentId.equalsIgnoreCase(content.getId())) {
                            customContentStorage.delete(previousContentId);
                            if (customContentService != null) {
                                customContentService.clearContentItems(previousContentId);
                            }
                        }
                    }
                }
                Log.fine("Custom content saved from flow: " + content.getId());
                notifySaved(ReSyncResourceCatalog.CUSTOM_CONTENT, content);
                for (String previousContentId : previousContent.keySet()) {
                    if (!previousContentId.equalsIgnoreCase(content.getId())) {
                        notifyDeleted(ReSyncResourceCatalog.CUSTOM_CONTENT, previousContentId);
                    }
                }
                sender.sendFlowSaveAck(session, flowId, payload.requestId());
                sender.succeedJob(job, flowId, "Saved");
                return;
            }
            storage.saveGraph(graph);
            graphPersisted = true;
            if (customContentStorage != null) {
                previousContent = customContentStorage.getByFlow(flowId).stream()
                    .collect(Collectors.toMap(CustomContentDefinition::getId, Function.identity(), (left, right) -> left));
            }
            updateGraphBindings(graph);
            Log.fine("Flow saved: " + flowId);
            String resourceType = storage.getGraphResourceType(flowId);
            notifySaved(resourceType, graph);
            sender.sendGraphSaveAck(session, resourceType, flowId, payload.requestId(), graph.getResourceRevision(), graph.getResourceHash());
            sender.succeedJob(job, flowId, "Saved");
        } catch (FlowGraphValidationException e) {
            String diagnostics = gson.toJson(e.getResult().errors());
            sender.failJob(job, diagnostics, null);
            Log.error("Flow validation error: " + e.getMessage());
        } catch (ResourceRevisionConflictException e) {
            sender.failJob(job, "Reload this resource before saving your changes", e);
            Log.warn("Flow save conflict: " + e.getMessage());
        } catch (Exception e) {
            restoreFlowSave(rollbackFlowId, previousGraph, previousContent, previousEventBindings, graphPersisted);
            sender.failJob(job, "Failed to save flow: " + e.getMessage(), e);
            Log.error("Flow save error: " + e.getMessage());
        } finally {
            sender.completeJobExecution(job);
        }
    }

    public void handleDelete(Session session, ByteBuffer buffer) {
        if (resourceRegistry != null && resourceRegistry.genericMutationAuthorityRequired()) {
            sender.sendError(session, "RESOURCE_GENERIC_AUTHORITY_REQUIRED", resourceRegistry.genericMutationAuthorityReason());
            return;
        }
        if (!buffer.hasRemaining()) {
            sender.sendError(session, "INVALID_DELETE", "Flow ID not provided");
            return;
        }
        JobRecord<String> job = null;
        FlowGraph previousGraph = null;
        Map<String, CustomContentDefinition> previousContent = Map.of();
        List<TriggerBinding> previousEventBindings = List.of();
        String rollbackFlowId = null;
        boolean graphDeleted = false;

        try {
            FlowMutationPayload payload = FlowMutationPayloadReader.read(buffer);
            if (!validateMutationEpoch(session, payload)) {
                return;
            }
            String flowId = payload.payload();
            long expectedRevision = 0L;
            if (flowId.startsWith("{")) {
                JsonObject deleteRequest = gson.fromJson(flowId, JsonObject.class);
                flowId = deleteRequest != null && deleteRequest.has("id") ? deleteRequest.get("id").getAsString() : "";
                expectedRevision = deleteRequest != null && deleteRequest.has("expectedRevision") ? deleteRequest.get("expectedRevision").getAsLong() : 0L;
            }
            rollbackFlowId = flowId;
            previousEventBindings = eventBindings(flowId);
            job = sender.beginJob(session, "deleteFlow", flowId, payload.requestId());
            if (job == null) {
                return;
            }
            previousGraph = storage.getGraph(flowId);
            if (previousGraph != null && expectedRevision > 0L && previousGraph.getResourceRevision() != expectedRevision) {
                throw new ResourceRevisionConflictException(flowId, expectedRevision, previousGraph.getResourceRevision());
            }
            String resourceType = storage.getGraphResourceType(flowId);
            storage.deleteGraph(flowId);
            graphDeleted = true;
            CustomContentStorage customContentStorage = CustomContentAccess.getStorage();
            if (customContentStorage != null) {
                previousContent = customContentStorage.getByFlow(flowId).stream()
                    .collect(Collectors.toMap(CustomContentDefinition::getId, Function.identity(), (left, right) -> left));
                for (CustomContentDefinition definition : customContentStorage.getByFlow(flowId)) {
                    customContentStorage.delete(definition.getId());
                    CustomContentService customContentService = CustomContentAccess.getService();
                    if (customContentService != null) {
                        customContentService.clearContentItems(definition.getId());
                    }
                }
            }
            if (triggerRegistry != null && !ReSyncResourceCatalog.COMMAND.equals(resourceType)) {
                triggerRegistry.replaceFlowBindings(flowId, TriggerType.EVENT, List.of());
            }
            if (globalTriggers != null) {
                globalTriggers.refreshBindings();
            }
            Log.fine("Flow deleted: " + flowId);
            notifyDeleted(resourceType, flowId);
            previousContent.keySet().forEach(contentId -> notifyDeleted(ReSyncResourceCatalog.CUSTOM_CONTENT, contentId));
            sender.succeedJob(job, flowId, "Deleted");
        } catch (FlowFunctionInUseException e) {
            sender.failJob(job, e.getMessage(), e);
            sender.sendError(session, "FUNCTION_IN_USE", gson.toJson(e.getReferences()));
            Log.warn("Function delete blocked: " + e.getMessage());
        } catch (ResourceRevisionConflictException e) {
            sender.failJob(job, "Reload this resource before deleting it", e);
            Log.warn("Flow delete conflict: " + e.getMessage());
        } catch (Exception e) {
            restoreFlowSave(rollbackFlowId, previousGraph, previousContent, previousEventBindings, graphDeleted);
            sender.failJob(job, e.getMessage(), e);
            sender.sendError(session, "DELETE_FAILED", "Failed to delete flow: " + e.getMessage());
            Log.error("Flow delete error: " + e.getMessage());
        } finally {
            sender.completeJobExecution(job);
        }
    }

    public void handleListRequest(Session session) {
        sender.sendFlowList(session, storage.listFlowIds());
    }

    public void handleTriggerUpdate(Session session, ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            sender.sendError(session, "INVALID_TRIGGER_UPDATE", "No trigger update data provided");
            return;
        }
        FlowMutationPayload payload = FlowMutationPayloadReader.read(buffer);
        String requestId = payload.requestId();
        if (requestId == null || requestId.isBlank()) {
            sender.sendError(session, "INVALID_TRIGGER_UPDATE", "A trigger update request ID is required");
            return;
        }
        List<TriggerBinding> bindings;
        String bindingHash;
        try {
            bindings = parseTriggerBindings(payload);
            bindingHash = triggerRegistry != null ? triggerRegistry.bindingHash(bindings) : "";
        } catch (RuntimeException exception) {
            sender.sendError(session, "INVALID_TRIGGER_UPDATE", exception.getMessage(), requestId);
            return;
        }
        String intentHash = triggerIntentHash(bindingHash, payload);
        JobRecord<String> job = sender.beginJob(session, "updateTriggers", "", requestId, intentHash);
        if (job == null) {
            return;
        }
        boolean durableCommitted = false;
        TriggerRegistry.BindingMutationResult mutation = null;
        try {
            if (triggerRegistry == null) {
                sender.failJob(job, "Trigger registry is unavailable", null);
                return;
            }
            FlowMutationPayloadReader.EpochDecision decision = FlowMutationPayloadReader.validateAuthorityEpoch(
                payload, authorityEpoch, legacyCompatibility.test(session));
            if (!decision.accepted()) {
                sender.sendError(session, decision.code(), decision.message());
                sender.failJob(job, decision.message(), null);
                return;
            }
            if (payload.hasExpectedBindingEpoch() || payload.expectedBindingHash() != null) {
                if (!payload.hasAuthorityEpoch() || !payload.hasExpectedBindingEpoch()
                    || payload.expectedBindingHash() == null
                    || payload.expectedBindingHash().isBlank()) {
                    String message = "Expected trigger binding state is incomplete";
                    sender.sendError(session, "TRIGGER_BINDING_STATE_REQUIRED", message);
                    sender.failJob(job, message, null);
                    return;
                }
                mutation = triggerRegistry.setBindingsPreservingTypesIfCurrent(
                    bindings, Set.of(TriggerType.EVENT, TriggerType.SYSTEM), payload.expectedBindingEpoch(),
                    payload.expectedBindingHash());
                if (!mutation.accepted()) {
                    String state = triggerMutationState(false, true, false, false, null,
                        triggerRegistry.bindingState());
                    sender.sendError(session, "TRIGGER_BINDING_STATE_STALE", state);
                    sender.failJob(job, state, null);
                    return;
                }
            } else {
                triggerRegistry.setBindingsPreservingTypes(bindings, Set.of(TriggerType.EVENT, TriggerType.SYSTEM));
                mutation = new TriggerRegistry.BindingMutationResult(true, triggerRegistry.bindingEpoch(),
                    triggerRegistry.bindingHash());
            }
            durableCommitted = true;
            boolean runtimeReady = true;
            String finalizationError = null;
            try {
                if (globalTriggers != null) {
                    globalTriggers.refreshBindings();
                }
            } catch (RuntimeException | Error exception) {
                runtimeReady = false;
                finalizationError = exception.getMessage();
                Log.warn("Trigger runtime refresh deferred: " + finalizationError);
            }
            String state = triggerMutationState(true, false, true, runtimeReady, finalizationError,
                triggerRegistry.bindingState());
            sender.succeedJob(job, state, runtimeReady ? "Saved" : "Saved; Runtime Refresh Pending");
        } catch (Exception e) {
            if (durableCommitted) {
                String state = triggerMutationState(true, false, true, false, e.getMessage(),
                    triggerRegistry.bindingState());
                sender.succeedJob(job, state, "Saved; Runtime Refresh Pending");
            } else {
                sender.failJob(job, e.getMessage(), e);
                sender.sendError(session, "TRIGGER_UPDATE_FAILED", "Failed to update triggers: " + e.getMessage());
            }
        } finally {
            sender.completeJobExecution(job);
        }
    }

    private String triggerIntentHash(String bindingHash, FlowMutationPayload payload) {
        String expectedHash = payload.expectedBindingHash() == null ? "" : payload.expectedBindingHash();
        return (bindingHash == null ? "" : bindingHash) + "\u0000" + payload.authorityEpoch()
            + "\u0000" + payload.expectedBindingEpoch() + "\u0000" + expectedHash;
    }

    private String triggerMutationState(boolean accepted, boolean stale, boolean durable, boolean runtimeReady,
                                        String finalizationError, TriggerRegistry.BindingState bindingState) {
        JsonObject result = new JsonObject();
        result.addProperty(ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_RESULT_ACCEPTED_FIELD, accepted);
        result.addProperty(ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_RESULT_DURABLE_FIELD, durable);
        result.addProperty(ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_RESULT_RUNTIME_READY_FIELD, runtimeReady);
        result.addProperty(ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_RESULT_STALE_FIELD, stale);
        result.addProperty(ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_RESULT_EPOCH_FIELD,
            bindingState != null ? bindingState.epoch() : 0L);
        result.addProperty(ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_RESULT_HASH_FIELD,
            bindingState != null ? bindingState.hash() : "");
        result.add(ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_RESULT_BINDINGS_FIELD,
            gson.toJsonTree(bindingState != null ? bindingState.bindings() : List.of()));
        if (finalizationError != null && !finalizationError.isBlank()) {
            result.addProperty(ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_RESULT_FINALIZATION_ERROR_FIELD,
                finalizationError);
        }
        return gson.toJson(result);
    }

    private List<TriggerBinding> parseTriggerBindings(FlowMutationPayload payload) {
        JsonElement parsed = JsonParser.parseString(payload.payload());
        if (parsed.isJsonArray()) {
            return gson.fromJson(parsed, new TypeToken<List<TriggerBinding>>() {
            }.getType());
        }
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Trigger update payload must be an object");
        }
        JsonObject envelope = parsed.getAsJsonObject();
        Set<String> fields = Set.of(
            ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_FORMAT_VERSION_FIELD,
            ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_REQUEST_ID_FIELD,
            ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_BINDINGS_FIELD);
        if (envelope.size() != fields.size() || envelope.keySet().stream().anyMatch(field -> !fields.contains(field))) {
            throw new IllegalArgumentException("Trigger update payload contains unexpected fields");
        }
        JsonElement version = envelope.get(ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_FORMAT_VERSION_FIELD);
        if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()
            || version.getAsInt() != ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported trigger update payload version");
        }
        JsonElement requestId = envelope.get(ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_REQUEST_ID_FIELD);
        if (requestId == null || !requestId.isJsonPrimitive() || !requestId.getAsJsonPrimitive().isString()
            || !payload.requestId().equals(requestId.getAsString())) {
            throw new IllegalArgumentException("Trigger update request ID does not match its transport request");
        }
        JsonElement bindings = envelope.get(ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_BINDINGS_FIELD);
        if (bindings == null || !bindings.isJsonArray()) {
            throw new IllegalArgumentException("Trigger update bindings must be an array");
        }
        return gson.fromJson(bindings, new TypeToken<List<TriggerBinding>>() {
        }.getType());
    }

    public void refreshAllGraphBindings() {
        if (triggerRegistry == null) {
            return;
        }
        for (String graphId : storage.listGraphIds(ReSyncResourceCatalog.FLOW)) {
            refreshEventBinding(graphId);
        }
    }

    public void refreshGraphBinding(String type, String flowId, boolean deleted) {
        if (type == null || flowId == null || flowId.isBlank() || triggerRegistry == null) {
            return;
        }
        if (ReSyncResourceCatalog.FUNCTION.equals(type)) {
            return;
        }
        if (ReSyncResourceCatalog.FLOW.equals(type)) {
            if (deleted) {
                triggerRegistry.replaceFlowBindings(flowId, TriggerType.EVENT, List.of());
            } else {
                refreshEventBinding(flowId);
            }
        }
        if (globalTriggers != null) {
            globalTriggers.refreshBindings();
        }
    }

    private void refreshEventBinding(String flowId) {
        var core = storage.hasCoreGraphAuthority() ? storage.getCoreGraph(ReSyncResourceCatalog.FLOW, flowId) : Optional.<CoreGraphStorageBoundary.Decoded>empty();
        if (core.isPresent()) {
            var source = core.orElseThrow();
            GraphDocument graph = source.graphDocument();
            if (graph == null) {
                throw new IllegalStateException("A Flow Requires A Typed Graph Document");
            }
            Set<String> contexts = new HashSet<>();
            if (source.envelope().assetActivationState() == ResourceActivationState.ACTIVE) {
                for (var node : graph.nodes()) {
                    NodeDefinition definition = definitionRegistry.get(node.definition().owner().canonicalText(), node.definition().localId());
                    String context = definition != null ? FlowEventRegistry.bindingContext(definition)
                        : "restudio.resync".equals(node.definition().owner().canonicalText()) ? mapEventContext(node.definition().localId()) : null;
                    if (context != null) {
                        contexts.add(context);
                    }
                }
            }
            replaceEventBindings(flowId, contexts);
            return;
        }
        FlowGraph graph = storage.getGraph(ReSyncResourceCatalog.FLOW, flowId);
        if (graph != null) {
            updateEventBindings(graph);
        }
    }

    private void notifyDeleted(String type, String resourceId) {
        if (resourceRegistry != null && type != null && !type.isBlank()) {
            resourceRegistry.notifyDeleted(type, resourceId);
        }
    }

    private void notifySaved(String type, Object value) {
        if (resourceRegistry == null || type == null || type.isBlank() || value == null) {
            return;
        }
        FlowResourceAdapter<?> adapter = resourceRegistry.get(type);
        if (adapter != null) {
            notifySaved(adapter, value);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void notifySaved(FlowResourceAdapter<T> adapter, Object value) {
        resourceRegistry.notifySaved(adapter, (T) value);
    }

    private void updateGraphBindings(FlowGraph graph) {
        if (triggerRegistry == null || graph == null || graph.getId() == null || graph.getId().isBlank() || graph.getNodes() == null) {
            return;
        }
        updateEventBindings(graph);
        if (globalTriggers != null) {
            globalTriggers.refreshBindings();
        }
    }

    private void updateEventBindings(FlowGraph graph) {
        if (!ReSyncResourceCatalog.FLOW.equals(graph.getResourceType())) {
            return;
        }
        Set<String> contexts = new HashSet<>();
        for (var entry : graph.getNodes().entrySet()) {
            FlowNode node = entry.getValue();
            if (node == null) {
                continue;
            }
            String context = mapEventContext(node.getType());
            if (context != null) {
                contexts.add(context);
            }
        }
        replaceEventBindings(graph.getId(), contexts);
    }

    private void replaceEventBindings(String flowId, Set<String> contexts) {
        List<TriggerBinding> bindings = contexts.stream().sorted()
            .map(context -> new TriggerBinding(flowId + ':' + context, flowId, TriggerType.EVENT, context)).toList();
        triggerRegistry.replaceFlowBindings(flowId, TriggerType.EVENT, bindings);
    }

    private List<TriggerBinding> eventBindings(String flowId) {
        if (triggerRegistry == null || flowId == null || flowId.isBlank()) {
            return List.of();
        }
        return triggerRegistry.getBindings(TriggerType.EVENT).stream()
            .filter(binding -> flowId.equals(binding.getFlowId()))
            .toList();
    }

    private boolean validateMutationEpoch(Session session, FlowMutationPayload payload) {
        FlowMutationPayloadReader.EpochDecision decision = FlowMutationPayloadReader.validateAuthorityEpoch(
            payload, authorityEpoch, legacyCompatibility.test(session));
        if (decision.accepted()) {
            return true;
        }
        sender.sendError(session, decision.code(), decision.message());
        return false;
    }

    private void restoreFlowSave(String flowId, FlowGraph previousGraph, Map<String, CustomContentDefinition> previousContent, List<TriggerBinding> previousEventBindings, boolean graphPersisted) {
        try {
            if (graphPersisted) {
                if (previousGraph != null && previousGraph.getId() != null) {
                    storage.restoreGraph(previousGraph);
                } else if (flowId != null && !flowId.isBlank()) {
                    storage.forceDeleteGraph(flowId);
                }
            }
            CustomContentStorage customContentStorage = CustomContentAccess.getStorage();
            if (customContentStorage != null && previousContent != null) {
                for (CustomContentDefinition current : customContentStorage.getByFlow(flowId)) {
                    if (current != null && current.getId() != null && !previousContent.containsKey(current.getId())) {
                        customContentStorage.delete(current.getId());
                    }
                }
                for (CustomContentDefinition definition : previousContent.values()) {
                    customContentStorage.save(definition);
                }
            }
            if (triggerRegistry != null && previousEventBindings != null && flowId != null && !flowId.isBlank()) {
                triggerRegistry.replaceFlowBindings(flowId, TriggerType.EVENT, previousEventBindings);
            }
            if (globalTriggers != null) {
                globalTriggers.refreshBindings();
            }
        } catch (Exception restoreError) {
            Log.error("Flow rollback failed: " + restoreError.getMessage());
        }
    }

    private String mapEventContext(String nodeType) {
        if (nodeType == null) {
            return null;
        }
        NodeDefinition definition = definitionRegistry.get(nodeType);
        if (definition != null) {
            return FlowEventRegistry.bindingContext(definition);
        }
        String normalized = nodeType.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("event:")) {
            normalized = normalized.substring(6);
        } else if (normalized.startsWith("event.")) {
            normalized = normalized.substring(6);
        } else {
            return null;
        }
        normalized = normalized.replace('.', '_');
        if (DIRECT_DISPATCH_EVENT_NODES.contains(normalized)) {
            return null;
        }
        return normalized.isBlank() ? null : normalized;
    }

    private static AuthorityEpoch requireExplicitAuthorityEpoch(FlowPacketSender sender) {
        if (sender == null) {
            return null;
        }
        throw new IllegalStateException("Authority epoch is required; use the bound-epoch constructor");
    }
}
