package restudio.resync.modules.flow;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
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
import restudio.resync.flow.ResourceRevisionConflictException;
import restudio.resync.flow.GlobalTriggers;
import restudio.resync.flow.migration.FlowGraphMigrator;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.triggers.TriggerBinding;
import restudio.resync.flow.triggers.TriggerRegistry;
import restudio.resync.flow.triggers.TriggerType;
import restudio.resync.flow.validation.FlowGraphValidationException;
import restudio.resync.jobs.JobRecord;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.storage.StorageSafety;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public class FlowBlueprintPacketHandler {
    private static final Set<String> DIRECT_DISPATCH_EVENT_NODES = Set.of("resync_command", "click");
    private final FlowStorage storage;
    private final TriggerRegistry triggerRegistry;
    private final GlobalTriggers globalTriggers;
    private final NodeDefinitionRegistry definitionRegistry;
    private final FlowPacketSender sender;
    private final FlowResourceRegistry resourceRegistry;
    private final Gson gson = new Gson();

    public FlowBlueprintPacketHandler(FlowStorage storage, TriggerRegistry triggerRegistry, GlobalTriggers globalTriggers, FlowPacketSender sender) {
        this(storage, triggerRegistry, globalTriggers, NodeDefinitionRegistry.getInstance(), sender, null);
    }

    public FlowBlueprintPacketHandler(FlowStorage storage, TriggerRegistry triggerRegistry, GlobalTriggers globalTriggers, NodeDefinitionRegistry definitionRegistry, FlowPacketSender sender) {
        this(storage, triggerRegistry, globalTriggers, definitionRegistry, sender, null);
    }

    public FlowBlueprintPacketHandler(FlowStorage storage, TriggerRegistry triggerRegistry, GlobalTriggers globalTriggers, NodeDefinitionRegistry definitionRegistry, FlowPacketSender sender, FlowResourceRegistry resourceRegistry) {
        this.storage = storage;
        this.triggerRegistry = triggerRegistry;
        this.globalTriggers = globalTriggers;
        this.definitionRegistry = definitionRegistry;
        this.sender = sender;
        this.resourceRegistry = resourceRegistry;
        migrateLegacyCommandBindings();
        refreshAllGraphBindings();
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
        if (!buffer.hasRemaining()) {
            sender.sendError(session, "INVALID_SAVE", "No data provided");
            return;
        }
        if (buffer.remaining() > FlowPacketSender.MAX_PACKET_SIZE) {
            sender.sendError(session, "SAVE_TOO_LARGE", "Save data exceeds maximum size");
            return;
        }
        FlowMutationPayload payload = FlowMutationPayloadReader.read(buffer);
        String json = payload.payload();
        JobRecord<String> job = sender.beginJob(session, "saveFlow", "", payload.requestId());
        if (job == null) {
            return;
        }
        FlowGraph previousGraph = null;
        Map<String, CustomContentDefinition> previousContent = Map.of();
        List<TriggerBinding> previousTriggers = triggerRegistry != null ? triggerRegistry.getBindings() : List.of();
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
            new FlowGraphMigrator(storage, definitionRegistry).migrateGraph(graph);
            storage.requireValidGraph(graph);
            rollbackFlowId = flowId;
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
            notifySaved(storage.getGraphResourceType(flowId), graph);
            sender.sendFlowSaveAck(session, flowId, payload.requestId(), graph.getResourceRevision(), graph.getResourceHash());
            sender.succeedJob(job, flowId, "Saved");
        } catch (FlowGraphValidationException e) {
            String diagnostics = gson.toJson(e.getResult().errors());
            sender.failJob(job, diagnostics, null);
            Log.error("Flow validation error: " + e.getMessage());
        } catch (ResourceRevisionConflictException e) {
            sender.failJob(job, "Reload this resource before saving your changes", e);
            Log.warn("Flow save conflict: " + e.getMessage());
        } catch (Exception e) {
            restoreFlowSave(rollbackFlowId, previousGraph, previousContent, previousTriggers, graphPersisted);
            sender.failJob(job, "Failed to save flow: " + e.getMessage(), e);
            Log.error("Flow save error: " + e.getMessage());
        }
    }

    public void handleDelete(Session session, ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            sender.sendError(session, "INVALID_DELETE", "Flow ID not provided");
            return;
        }
        JobRecord<String> job = null;
        FlowGraph previousGraph = null;
        Map<String, CustomContentDefinition> previousContent = Map.of();
        List<TriggerBinding> previousTriggers = triggerRegistry != null ? triggerRegistry.getBindings() : List.of();
        String rollbackFlowId = null;
        boolean graphDeleted = false;

        try {
            FlowMutationPayload payload = FlowMutationPayloadReader.read(buffer);
            String flowId = payload.payload();
            long expectedRevision = 0L;
            if (flowId.startsWith("{")) {
                JsonObject deleteRequest = gson.fromJson(flowId, JsonObject.class);
                flowId = deleteRequest != null && deleteRequest.has("id") ? deleteRequest.get("id").getAsString() : "";
                expectedRevision = deleteRequest != null && deleteRequest.has("expectedRevision") ? deleteRequest.get("expectedRevision").getAsLong() : 0L;
            }
            rollbackFlowId = flowId;
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
            if (triggerRegistry != null) {
                triggerRegistry.removeFlowBindings(flowId);
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
            restoreFlowSave(rollbackFlowId, previousGraph, previousContent, previousTriggers, graphDeleted);
            sender.failJob(job, e.getMessage(), e);
            sender.sendError(session, "DELETE_FAILED", "Failed to delete flow: " + e.getMessage());
            Log.error("Flow delete error: " + e.getMessage());
        }
    }

    public void handleListRequest(Session session) {
        sender.sendFlowList(session, storage.listFlowIds());
    }

    public void handleTriggerUpdate(Session session, ByteBuffer buffer) {
        if (!buffer.hasRemaining() || triggerRegistry == null) {
            return;
        }
        FlowMutationPayload payload = FlowMutationPayloadReader.read(buffer);
        JobRecord<String> job = sender.beginJob(session, "updateTriggers", "", payload.requestId());
        if (job == null) {
            return;
        }
        String json = payload.payload();
        try {
            List<TriggerBinding> bindings = gson.fromJson(json, new TypeToken<List<TriggerBinding>>() {
            }.getType());
            triggerRegistry.setBindingsPreservingTypes(bindings, Set.of(TriggerType.EVENT, TriggerType.COMMAND));
            if (globalTriggers != null) {
                globalTriggers.refreshBindings();
            }
            sender.succeedJob(job, "triggers", "Saved");
        } catch (Exception e) {
            sender.failJob(job, e.getMessage(), e);
            sender.sendError(session, "TRIGGER_UPDATE_FAILED", "Failed to update triggers: " + e.getMessage());
        }
    }

    private void refreshAllGraphBindings() {
        if (triggerRegistry == null) {
            return;
        }
        for (String type : List.of(ReSyncResourceCatalog.FLOW, ReSyncResourceCatalog.FUNCTION, ReSyncResourceCatalog.COMMAND)) {
            for (String graphId : storage.listGraphIds(type)) {
                FlowGraph graph = storage.getGraph(type, graphId);
                if (graph != null && graph.getId() != null && !graph.getId().isBlank() && graph.getNodes() != null) {
                    updateEventBindings(graph);
                    updateCommandBindings(graph);
                }
            }
        }
        if (globalTriggers != null) {
            globalTriggers.refreshBindings();
        }
    }

    public void refreshGraphBinding(String type, String flowId, boolean deleted) {
        if (type == null || flowId == null || flowId.isBlank() || triggerRegistry == null) {
            return;
        }
        if (deleted) {
            triggerRegistry.removeFlowBindings(flowId);
            if (globalTriggers != null) {
                globalTriggers.refreshBindings();
            }
            return;
        }
        FlowGraph graph = storage.getGraph(type, flowId);
        if (graph != null) {
            updateGraphBindings(graph);
        }
    }

    private void migrateLegacyCommandBindings() {
        if (triggerRegistry == null) {
            return;
        }
        Path reportFile = storage.getAssetsPath().resolve(".migrations").resolve("command-bindings-v1.json");
        if (Files.exists(reportFile)) {
            return;
        }
        int inspected = 0;
        int migrated = 0;
        int skipped = 0;
        List<String> migratedIds = new ArrayList<>();
        List<String> skippedIds = new ArrayList<>();
        Map<String, String> rejected = new HashMap<>();
        for (String commandId : storage.listGraphIds(ReSyncResourceCatalog.COMMAND)) {
            FlowGraph graph = storage.getGraph(ReSyncResourceCatalog.COMMAND, commandId);
            if (graph == null || graph.getNodes() == null) {
                continue;
            }
            FlowGraph candidate = FlowSerializer.deserialize(FlowSerializer.serialize(graph));
            List<TriggerBinding> legacy = triggerRegistry.getBindings(TriggerType.COMMAND).stream()
                .filter(binding -> commandId.equals(binding.getFlowId()) && binding.getContext() != null && !binding.getContext().isBlank())
                .sorted((left, right) -> String.CASE_INSENSITIVE_ORDER.compare(left.getId() != null ? left.getId() : "", right.getId() != null ? right.getId() : ""))
                .toList();
            if (legacy.isEmpty()) {
                continue;
            }
            inspected++;
            List<Map.Entry<String, FlowNode>> commandNodes = candidate.getNodes().entrySet().stream()
                .filter(entry -> entry.getValue() != null && isCommandNode(entry.getValue().getType()))
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .toList();
            boolean changed = false;
            for (Map.Entry<String, FlowNode> commandNode : commandNodes) {
                TriggerBinding binding = legacy.stream()
                    .filter(legacyBinding -> legacyBinding.getId() != null && legacyBinding.getId().equals(commandId + ":command:" + commandNode.getKey()))
                    .findFirst()
                    .orElse(legacy.size() == 1 ? legacy.getFirst() : null);
                if (binding != null) {
                    changed |= applyLegacyCommandContext(commandNode.getValue(), binding.getContext());
                } else if (!legacy.isEmpty()) {
                    Log.warn("Command binding migration skipped ambiguous paths for " + commandId + ':' + commandNode.getKey());
                }
            }
            if (changed) {
                try {
                    storage.saveGraph(candidate);
                    migrated++;
                    migratedIds.add(commandId);
                } catch (RuntimeException exception) {
                    skipped++;
                    rejected.put(commandId, exception.getMessage() != null ? exception.getMessage() : exception.getClass().getSimpleName());
                    Log.warn("Command binding migration rejected " + commandId + ": " + rejected.get(commandId));
                }
            } else {
                skipped++;
                skippedIds.add(commandId);
            }
        }
        JsonObject report = new JsonObject();
        report.addProperty("version", 1);
        report.addProperty("inspected", inspected);
        report.addProperty("migrated", migrated);
        report.addProperty("skipped", skipped);
        report.add("migratedIds", gson.toJsonTree(migratedIds));
        report.add("skippedIds", gson.toJsonTree(skippedIds));
        report.add("rejected", gson.toJsonTree(rejected));
        try {
            StorageSafety.writeUtf8Atomic(reportFile, gson.toJson(report));
        } catch (IOException exception) {
            Log.warn("Failed to save command binding migration report: " + exception.getMessage());
        }
    }

    private boolean applyLegacyCommandContext(FlowNode node, String context) {
        Map<String, Object> values = node.getInputValues();
        if (values == null) {
            values = new HashMap<>();
            node.setInputValues(values);
        }
        if (!text(values, "command").isBlank() || !text(values, "label").isBlank() || !text(values, "name").isBlank()) {
            return false;
        }
        String command = context.trim();
        List<String> subcommands = List.of();
        boolean structured = false;
        if (command.startsWith("{")) {
            JsonObject payload;
            try {
                payload = gson.fromJson(command, JsonObject.class);
            } catch (RuntimeException exception) {
                Log.warn("Command binding migration skipped malformed context: " + exception.getMessage());
                return false;
            }
            if (payload == null) {
                return false;
            }
            command = payload.has("command") && !payload.get("command").isJsonNull() ? payload.get("command").getAsString() : "";
            subcommands = payload.has("subcommands") ? stringList(gson.fromJson(payload.get("subcommands"), List.class)) : List.of();
            structured = payload.has("structured") && payload.get("structured").getAsBoolean();
        }
        if (command.isBlank()) {
            return false;
        }
        values.put("command", command);
        values.put("subcommands", subcommands);
        values.put("structured", structured);
        return true;
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
        updateCommandBindings(graph);
        if (globalTriggers != null) {
            globalTriggers.refreshBindings();
        }
    }

    private void updateEventBindings(FlowGraph graph) {
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
        List<TriggerBinding> bindings = new ArrayList<>();
        String flowId = graph.getId();
        for (String context : contexts) {
            bindings.add(new TriggerBinding(flowId + ':' + context, flowId, TriggerType.EVENT, context));
        }
        triggerRegistry.replaceFlowBindings(flowId, TriggerType.EVENT, bindings);
    }

    private void updateCommandBindings(FlowGraph graph) {
        String flowId = graph.getId();
        List<TriggerBinding> existing = triggerRegistry.getBindings(TriggerType.COMMAND).stream()
            .filter(binding -> flowId.equals(binding.getFlowId()))
            .toList();
        List<TriggerBinding> bindings = new ArrayList<>();
        int index = 0;
        for (var entry : graph.getNodes().entrySet()) {
            FlowNode node = entry.getValue();
            if (node == null || !isCommandNode(node.getType())) {
                continue;
            }
            String bindingId = flowId + ":command:" + entry.getKey();
            String fallbackCommand = index == 0 && "command".equals(storage.getGraphResourceType(flowId)) ? flowId : "";
            String context = commandContext(node, existing, bindingId, index, fallbackCommand);
            if (context == null || context.isBlank()) {
                continue;
            }
            bindings.add(new TriggerBinding(bindingId, flowId, TriggerType.COMMAND, context));
            index++;
        }
        triggerRegistry.replaceFlowBindings(flowId, TriggerType.COMMAND, bindings);
    }

    private boolean isCommandNode(String nodeType) {
        if (nodeType == null) {
            return false;
        }
        String normalized = nodeType.trim().toLowerCase(Locale.ROOT);
        return "event.resync.command".equals(normalized) || "event:resync_command".equals(normalized);
    }

    private String commandContext(FlowNode node, List<TriggerBinding> existing, String bindingId, int index, String fallbackCommand) {
        Map<String, Object> values = new HashMap<>(node.getHandlerConfigValues());
        if (node.getInputValues() != null) {
            values.putAll(node.getInputValues());
        }
        String command = text(values, "command");
        if (command.isBlank()) {
            command = text(values, "label");
        }
        if (command.isBlank()) {
            command = text(values, "name");
        }
        List<String> subcommands = stringList(values.get("subcommands"));
        if (subcommands.isEmpty()) {
            subcommands = stringList(values.get("allowedSubcommands"));
        }
        boolean structured = bool(values.get("structured"));
        if (!command.isBlank()) {
            return gson.toJson(Map.of("command", command, "subcommands", subcommands, "structured", structured));
        }
        for (TriggerBinding binding : existing) {
            if (bindingId.equals(binding.getId()) && binding.getContext() != null && !binding.getContext().isBlank()) {
                return binding.getContext();
            }
        }
        if (index < existing.size() && existing.get(index).getContext() != null && !existing.get(index).getContext().isBlank()) {
            return existing.get(index).getContext();
        }
        return fallbackCommand;
    }

    private String text(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value != null ? value.toString().trim() : "";
    }

    private boolean bool(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(value.toString());
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object entry : list) {
                if (entry != null && !entry.toString().isBlank()) {
                    result.add(entry.toString().trim());
                }
            }
            return result;
        }
        if (value instanceof String text && !text.isBlank()) {
            return List.of(text);
        }
        return List.of();
    }

    private void restoreFlowSave(String flowId, FlowGraph previousGraph, Map<String, CustomContentDefinition> previousContent, List<TriggerBinding> previousTriggers, boolean graphPersisted) {
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
            if (triggerRegistry != null && previousTriggers != null) {
                triggerRegistry.setBindings(previousTriggers);
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
}
