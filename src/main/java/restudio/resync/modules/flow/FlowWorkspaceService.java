package restudio.resync.modules.flow;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import restudio.flow.data.CustomContentDefinition;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowSerializer;
import restudio.resync.core.CollaborationIdentity;
import restudio.resync.core.Session;
import restudio.resync.customcontent.CustomContentStorage;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.workspace.WorkspacePatch;
import restudio.resync.flow.workspace.WorkspaceRevision;
import restudio.resync.resources.ReSyncResourceCatalog;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public final class FlowWorkspaceService {
    private static final int MAX_PATCHES = 512;
    private static final int MAX_PATCH_PATH_LENGTH = 512;
    private static final Set<String> GRAPH_TYPES = Set.of("flow", "function", "command", ReSyncResourceCatalog.CUSTOM_CONTENT);
    private static final Set<String> PATCH_OPERATIONS = Set.of("set", "remove", "array_add", "array_remove");
    private static final Set<String> IMMUTABLE_ROOTS = Set.of("id", "worldName", "flowId", "function", "resourceType", "resourceRevision", "resourceHash",
        "resourceMutationId", "enabled");
    private static final Set<String> REVISION_ROOTS = Set.of("resourceRevision", "resourceHash", "resourceMutationId");
    private final FlowStorage storage;
    private final CustomContentStorage customContentStorage;
    private final FlowPacketSender sender;
    private final FlowResourceRegistry resources;
    private final Map<String, FlowWorkspaceDocumentProvider> documentProviders = new ConcurrentHashMap<>();
    private final Gson gson = new Gson();
    private final Map<String, Workspace> workspaces = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final ThreadLocal<Boolean> workspaceSaveCommit = ThreadLocal.withInitial(() -> false);

    public FlowWorkspaceService(FlowStorage storage, FlowPacketSender sender, FlowCollaborationService collaboration) {
        this(storage, null, sender, collaboration, null);
    }

    public FlowWorkspaceService(FlowStorage storage, CustomContentStorage customContentStorage, FlowPacketSender sender, FlowCollaborationService collaboration) {
        this(storage, customContentStorage, sender, collaboration, null);
    }

    public FlowWorkspaceService(FlowStorage storage, CustomContentStorage customContentStorage, FlowPacketSender sender,
                                FlowCollaborationService collaboration, FlowResourceRegistry resources) {
        this.storage = storage;
        this.customContentStorage = customContentStorage;
        this.sender = sender;
        this.resources = resources;
    }

    public void handleJoin(Session session, ByteBuffer buffer) {
        JoinRequest request = read(buffer, JoinRequest.class);
        if (!valid(request)) {
            sender.sendWorkspaceResync(session, gson.toJson(new ResyncEvent("", "", "Invalid Workspace")));
            return;
        }
        String type = safe(request.type());
        String resourceId = safe(request.resourceId());
        String key = key(type, resourceId);
        while (true) {
            Workspace workspace = workspace(type, resourceId);
            if (workspace == null) {
                sender.sendWorkspaceResync(session, gson.toJson(new ResyncEvent(type, resourceId, "Resource Unavailable")));
                return;
            }
            sessions.put(session.getSessionId(), session);
            SnapshotEvent snapshot;
            synchronized (workspace) {
                if (workspaces.get(key) != workspace) {
                    continue;
                }
                workspace.members.add(session.getSessionId());
                snapshot = new SnapshotEvent(type, resourceId, workspace.revision.sequence(), workspace.document.deepCopy(),
                    List.copyOf(workspace.awareness.values()));
            }
            sender.sendWorkspaceSnapshot(session, gson.toJson(snapshot));
            return;
        }
    }

    public void registerDocumentProvider(FlowWorkspaceDocumentProvider provider) {
        if (provider != null && provider.type() != null && !provider.type().isBlank()) {
            documentProviders.put(provider.type().trim(), provider);
        }
    }

    public void handleLeave(Session session, ByteBuffer buffer) {
        JoinRequest request = read(buffer, JoinRequest.class);
        if (request == null) {
            return;
        }
        leave(session, key(safe(request.type()), safe(request.resourceId())));
    }

    public void handleOperation(Session session, ByteBuffer buffer) {
        OperationRequest request = read(buffer, OperationRequest.class);
        if (!valid(request) || request.patches() == null || request.patches().isEmpty() || request.patches().size() > MAX_PATCHES
            || request.patches().stream().anyMatch(patch -> !validClientPatch(patch))) {
            sender.sendWorkspaceResync(session, gson.toJson(new ResyncEvent(request != null ? request.type() : "", request != null ? request.resourceId() : "", "Invalid Operation")));
            return;
        }
        String type = safe(request.type());
        String resourceId = safe(request.resourceId());
        Workspace workspace = workspaces.get(key(type, resourceId));
        if (workspace == null) {
            sender.sendWorkspaceResync(session, gson.toJson(new ResyncEvent(type, resourceId, "Workspace Not Joined")));
            return;
        }
        OperationEvent event = null;
        OperationEvent existing = null;
        String resyncReason = null;
        List<Session> targets = List.of();
        synchronized (workspace) {
            if (!isWorkspaceMember(workspace, session)) {
                resyncReason = "Workspace Not Joined";
            } else {
                WorkspaceRevision.Assessment<OperationEvent> assessment =
                    workspace.revision.assess(request.baseSequence(), request.operationId());
                if (assessment.status() == WorkspaceRevision.Status.DUPLICATE) {
                    existing = assessment.existing();
                } else if (assessment.status() == WorkspaceRevision.Status.CONFLICT) {
                    resyncReason = "Workspace Sequence Conflict";
                } else {
                    try {
                        JsonObject next = workspace.document.deepCopy();
                        for (WorkspacePatch<JsonElement> patch : request.patches()) {
                            apply(next, patch);
                        }
                        workspace.document = next;
                    } catch (RuntimeException exception) {
                        resyncReason = "Invalid Operation";
                    }
                    if (resyncReason == null) {
                        request.patches().stream().map(this::copy).forEach(workspace.pendingPatches::add);
                        event = workspace.revision.advance(request.operationId(), sequence ->
                            new OperationEvent(type, resourceId, sequence, safe(request.operationId()), session.getSessionId(),
                                identity(session), List.copyOf(request.patches())));
                        targets = members(workspace, null);
                    }
                }
            }
        }
        if (resyncReason != null) {
            sendResync(List.of(session), type, resourceId, resyncReason);
        } else if (existing != null) {
            sender.sendWorkspaceOperation(session, gson.toJson(existing));
        } else if (event != null) {
            sendOperation(targets, event);
        }
    }

    public void handleAwareness(Session session, ByteBuffer buffer) {
        AwarenessRequest request = read(buffer, AwarenessRequest.class);
        if (!valid(request)) {
            return;
        }
        String type = safe(request.type());
        String resourceId = safe(request.resourceId());
        Workspace workspace = workspaces.get(key(type, resourceId));
        if (workspace == null) {
            return;
        }
        JsonObject state = request.state() != null ? request.state().deepCopy() : new JsonObject();
        AwarenessEvent event = new AwarenessEvent(type, resourceId, session.getSessionId(), identity(session), state,
            System.currentTimeMillis());
        List<Session> targets;
        synchronized (workspace) {
            if (!isWorkspaceMember(workspace, session)) {
                return;
            }
            workspace.awareness.put(session.getSessionId(), event);
            targets = members(workspace, event.authorSessionId());
        }
        sendAwareness(targets, event);
    }

    public void cleanup(Session session) {
        sessions.remove(session.getSessionId());
        for (Map.Entry<String, Workspace> entry : workspaces.entrySet()) {
            leave(session, entry.getKey());
        }
    }

    public void resourceDeleted(String type, String resourceId) {
        delete(type, resourceId, () -> {
            if (storage != null && storage.getGraph(type, resourceId) != null) {
                storage.deleteGraph(type, resourceId);
            }
        });
    }

    void delete(String type, String resourceId, Runnable action) {
        Workspace workspace = workspaces.get(key(type, resourceId));
        if (workspace == null) {
            action.run();
            return;
        }
        List<Session> targets = List.of();
        workspace.commitLock.lock();
        try {
            action.run();
            synchronized (workspace) {
                if (workspaces.remove(key(workspace.type, workspace.resourceId), workspace)) {
                    workspace.deleted = true;
                    targets = members(workspace, null);
                    workspace.members.clear();
                    workspace.awareness.clear();
                }
            }
        } finally {
            workspace.commitLock.unlock();
        }
        sendResync(targets, workspace.type, workspace.resourceId, "Resource Unavailable");
    }

    public void resourceSaved(String type, String resourceId, String payload) {
        if (workspaceSaveCommit.get()) {
            return;
        }
        Workspace workspace = workspaces.get(key(type, resourceId));
        JsonObject latest = workspaceDocument(type, resourceId, payload);
        if (workspace == null || latest == null) {
            return;
        }
        List<Session> targets;
        synchronized (workspace) {
            if (workspaces.get(key(workspace.type, workspace.resourceId)) != workspace || workspace.deleted) {
                return;
            }
            workspace.document = rebase(latest, workspace.pendingPatches);
            targets = members(workspace, null);
        }
        sendResync(targets, workspace.type, workspace.resourceId, "Resource Updated");
    }

    <T> T save(Session session, FlowResourceAdapter<T> adapter, T value) {
        String type = adapter.descriptor().typeId();
        String resourceId = adapter.id(value);
        Workspace workspace = workspaces.get(key(type, resourceId));
        if (workspace == null || !isWorkspaceMember(workspace, session)) {
            adapter.save(value);
            return value;
        }
        workspace.commitLock.lock();
        try {
            T submitted = withCurrentImmutableRoots(adapter, value, adapter.get(resourceId), false);
            workspaceSaveCommit.set(true);
            try {
                adapter.save(submitted);
            } finally {
                workspaceSaveCommit.remove();
            }
            JsonObject latest = workspaceDocument(type, resourceId, adapter.serialize(submitted));
            List<Session> targets;
            synchronized (workspace) {
                if (latest != null && workspaces.get(key(type, resourceId)) == workspace && !workspace.deleted) {
                    workspace.document = latest;
                    workspace.pendingPatches.clear();
                }
                targets = members(workspace, null);
            }
            sendResync(targets, type, resourceId, "Resource Updated");
            return submitted;
        } finally {
            workspace.commitLock.unlock();
        }
    }

    private boolean isWorkspaceMember(Workspace workspace, Session session) {
        synchronized (workspace) {
            return !workspace.deleted && workspaces.get(key(workspace.type, workspace.resourceId)) == workspace
                && workspace.members.contains(session.getSessionId());
        }
    }

    private <T> T withCurrentImmutableRoots(FlowResourceAdapter<T> adapter, T submitted, T current, boolean includeRevision) {
        if (submitted == null || current == null) {
            return submitted;
        }
        try {
            JsonElement submittedElement = JsonParser.parseString(adapter.serialize(submitted));
            JsonElement currentElement = JsonParser.parseString(adapter.serialize(current));
            if (!submittedElement.isJsonObject() || !currentElement.isJsonObject()) {
                return null;
            }
            JsonObject submittedDocument = submittedElement.getAsJsonObject();
            JsonObject currentDocument = currentElement.getAsJsonObject();
            boolean copied = false;
            for (String field : IMMUTABLE_ROOTS) {
                if (!includeRevision && REVISION_ROOTS.contains(field)) {
                    continue;
                }
                if (currentDocument.has(field)) {
                    submittedDocument.add(field, currentDocument.get(field).deepCopy());
                    copied = true;
                }
            }
            return copied ? adapter.deserialize(submittedDocument.toString()) : submitted;
        } catch (RuntimeException exception) {
            return submitted;
        }
    }

    private Workspace workspace(String type, String resourceId) {
        String key = key(type, resourceId);
        synchronized (workspaces) {
            Workspace existing = workspaces.get(key);
            if (existing != null) {
                return existing;
            }
            JsonObject document = loadDocument(type, resourceId);
            if (document == null) {
                return null;
            }
            Workspace created = new Workspace(type, resourceId, document);
            workspaces.put(key, created);
            return created;
        }
    }

    private void leave(Session session, String key) {
        Workspace workspace = workspaces.get(key);
        if (workspace == null) {
            return;
        }
        AwarenessEvent event;
        List<Session> targets;
        synchronized (workspace) {
            if (!workspace.members.remove(session.getSessionId())) {
                return;
            }
            workspace.awareness.remove(session.getSessionId());
            event = new AwarenessEvent(workspace.type, workspace.resourceId, session.getSessionId(), identity(session), new JsonObject(),
                System.currentTimeMillis());
            targets = members(workspace, event.authorSessionId());
            if (workspace.members.isEmpty()) {
                workspaces.remove(key, workspace);
            }
        }
        sendAwareness(targets, event);
    }

    private List<Session> members(Workspace workspace, String excludedSessionId) {
        return workspace.members.stream()
            .filter(member -> excludedSessionId == null || !member.equals(excludedSessionId))
            .map(sessions::get)
            .filter(target -> target != null)
            .toList();
    }

    private void sendOperation(List<Session> targets, OperationEvent event) {
        String json = gson.toJson(event);
        for (Session target : targets) {
            sender.sendWorkspaceOperation(target, json);
        }
    }

    private void sendAwareness(List<Session> targets, AwarenessEvent event) {
        String json = gson.toJson(event);
        for (Session target : targets) {
            sender.sendWorkspaceAwareness(target, json);
        }
    }

    JsonObject rebase(JsonObject latest, List<WorkspacePatch<JsonElement>> patches) {
        JsonObject document = latest.deepCopy();
        if (patches != null) {
            patches.forEach(patch -> apply(document, patch));
        }
        return document;
    }

    private JsonObject workspaceDocument(String type, String resourceId, String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            if (isGraphWorkspace(type) && !ReSyncResourceCatalog.CUSTOM_CONTENT.equals(type)) {
                FlowGraph graph = FlowSerializer.deserialize(payload);
                return JsonParser.parseString(FlowSerializer.serialize(graph)).getAsJsonObject();
            }
            if (ReSyncResourceCatalog.CUSTOM_CONTENT.equals(type)) {
                CustomContentDefinition content = gson.fromJson(payload, CustomContentDefinition.class);
                if (content == null || content.getGraph() == null) {
                    return null;
                }
                FlowGraph graph = content.getGraph();
                graph.setId(content.getFlowId() != null && !content.getFlowId().isBlank() ? content.getFlowId() : resourceId);
                return JsonParser.parseString(FlowSerializer.serialize(graph)).getAsJsonObject();
            }
            JsonElement document = JsonParser.parseString(payload);
            return document.isJsonObject() ? document.getAsJsonObject() : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    JsonObject loadDocument(String type, String resourceId) {
        if (isGraphWorkspace(type)) {
            FlowGraph graph = loadGraph(type, resourceId);
            return graph != null && compatibleGraphType(type, graph)
                ? JsonParser.parseString(FlowSerializer.serialize(graph)).getAsJsonObject() : null;
        }
        FlowWorkspaceDocumentProvider provider = documentProviders.get(type);
        if (provider != null) {
            JsonObject document = provider.load(resourceId);
            return document != null ? document.deepCopy() : null;
        }
        FlowResourceAdapter<?> adapter = resources != null ? resources.get(type) : null;
        if (adapter == null) {
            return null;
        }
        Object value = adapter.get(resourceId);
        if (value == null) {
            return null;
        }
        JsonElement document = JsonParser.parseString(serialize(adapter, value));
        return document.isJsonObject() ? document.getAsJsonObject() : null;
    }

    JsonObject persistDocument(String type, String resourceId, JsonObject document) {
        Persistence persistence = persistDocumentDurable(type, resourceId, document, () -> {
        }, () -> {
        });
        persistence.completion().run();
        return persistence.document();
    }

    private Persistence persistDocumentDurable(String type, String resourceId, JsonObject document, Runnable beforeVisible, Runnable afterVisible) {
        if (isGraphWorkspace(type)) {
            FlowGraph graph = FlowSerializer.deserialize(document.toString());
            graph.setId(resourceId);
            graph.setResourceType(type);
            Runnable completion = persistGraph(type, resourceId, graph, beforeVisible, afterVisible);
            return new Persistence(JsonParser.parseString(FlowSerializer.serialize(graph)).getAsJsonObject(), completion);
        }
        FlowWorkspaceDocumentProvider provider = documentProviders.get(type);
        if (provider != null) {
            provider.persist(resourceId, document.deepCopy());
            return new Persistence(document.deepCopy(), () -> {
            });
        }
        FlowResourceAdapter<?> adapter = resources != null ? resources.get(type) : null;
        if (adapter == null) {
            throw new IllegalStateException("Resource adapter unavailable: " + type);
        }
        Object value = adapter.deserialize(document.toString());
        if (value == null || !resourceId.equals(resourceId(adapter, value))) {
            throw new IllegalStateException("Workspace resource identity changed");
        }
        Runnable completion = resources.saveAuthoritativeDurable(type, value, beforeVisible, afterVisible);
        return new Persistence(JsonParser.parseString(serialize(adapter, value)).getAsJsonObject(), completion);
    }

    @SuppressWarnings("unchecked")
    private String serialize(FlowResourceAdapter<?> adapter, Object value) {
        return ((FlowResourceAdapter<Object>) adapter).serialize(value);
    }

    @SuppressWarnings("unchecked")
    private String resourceId(FlowResourceAdapter<?> adapter, Object value) {
        return ((FlowResourceAdapter<Object>) adapter).id(value);
    }

    private boolean isGraphWorkspace(String type) {
        return ReSyncResourceCatalog.FLOW.equals(type) || ReSyncResourceCatalog.FUNCTION.equals(type)
            || ReSyncResourceCatalog.COMMAND.equals(type) || ReSyncResourceCatalog.CUSTOM_CONTENT.equals(type);
    }

    private void sendResync(List<Session> targets, String type, String resourceId, String reason) {
        String json = gson.toJson(new ResyncEvent(type, resourceId, reason));
        for (Session target : targets) {
            sender.sendWorkspaceResync(target, json);
        }
    }

    void apply(JsonObject document, WorkspacePatch<JsonElement> patch) {
        if (patch == null || patch.path() == null || !patch.path().startsWith("/")) {
            throw new IllegalArgumentException("Invalid workspace path");
        }
        List<String> path = segments(patch.path());
        if (path.isEmpty()) {
            throw new IllegalArgumentException("Invalid workspace path");
        }
        JsonElement parent = parent(document, path);
        String leaf = path.getLast();
        switch (safe(patch.op())) {
            case "set" -> set(parent, leaf, patch.value());
            case "remove" -> remove(parent, leaf);
            case "array_add" -> addArrayValue(array(parent, leaf), patch.value());
            case "array_remove" -> removeArrayValue(array(parent, leaf), patch.value());
            default -> throw new IllegalArgumentException("Invalid workspace operation");
        }
    }

    private JsonElement parent(JsonObject document, List<String> path) {
        JsonElement current = document;
        for (int index = 0; index < path.size() - 1; index++) {
            String segment = path.get(index);
            if (current.isJsonObject()) {
                JsonObject object = current.getAsJsonObject();
                JsonElement next = object.get(segment);
                if (next == null || next.isJsonNull()) {
                    next = new JsonObject();
                    object.add(segment, next);
                }
                current = next;
            } else if (current.isJsonArray()) {
                current = current.getAsJsonArray().get(Integer.parseInt(segment));
            } else {
                throw new IllegalArgumentException("Invalid workspace path");
            }
        }
        return current;
    }

    private void set(JsonElement parent, String leaf, JsonElement value) {
        if (parent.isJsonObject()) {
            parent.getAsJsonObject().add(leaf, copy(value));
        } else if (parent.isJsonArray()) {
            parent.getAsJsonArray().set(Integer.parseInt(leaf), copy(value));
        } else {
            throw new IllegalArgumentException("Invalid workspace path");
        }
    }

    private void remove(JsonElement parent, String leaf) {
        if (parent.isJsonObject()) {
            parent.getAsJsonObject().remove(leaf);
        } else if (parent.isJsonArray()) {
            parent.getAsJsonArray().remove(Integer.parseInt(leaf));
        }
    }

    private JsonArray array(JsonElement parent, String leaf) {
        JsonElement value = parent.isJsonObject() ? parent.getAsJsonObject().get(leaf) : parent.getAsJsonArray().get(Integer.parseInt(leaf));
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException("Invalid workspace array");
        }
        return value.getAsJsonArray();
    }

    private void addArrayValue(JsonArray array, JsonElement value) {
        JsonElement next = copy(value);
        for (JsonElement existing : array) {
            if (existing.equals(next)) {
                return;
            }
        }
        array.add(next);
    }

    private void removeArrayValue(JsonArray array, JsonElement value) {
        for (int index = array.size() - 1; index >= 0; index--) {
            if (array.get(index).equals(value)) {
                array.remove(index);
            }
        }
    }

    boolean validClientPatch(WorkspacePatch<JsonElement> patch) {
        if (patch == null || patch.op() == null || !patch.op().equals(safe(patch.op())) || !PATCH_OPERATIONS.contains(patch.op())
            || patch.path() == null || patch.path().length() > MAX_PATCH_PATH_LENGTH || !patch.path().startsWith("/")) {
            return false;
        }
        List<String> path = segments(patch.path());
        return !path.isEmpty() && !path.getFirst().isBlank() && !IMMUTABLE_ROOTS.contains(path.getFirst())
            && (!"remove".equals(patch.op()) || patch.value() == null || patch.value().isJsonNull())
            && ("remove".equals(patch.op()) || patch.value() != null);
    }

    private List<String> segments(String path) {
        String[] raw = path.substring(1).split("/", -1);
        ArrayList<String> segments = new ArrayList<>(raw.length);
        for (String segment : raw) {
            segments.add(segment.replace("~1", "/").replace("~0", "~"));
        }
        return segments;
    }

    private JsonElement copy(JsonElement value) {
        return value != null ? value.deepCopy() : JsonNull.INSTANCE;
    }

    private WorkspacePatch<JsonElement> copy(WorkspacePatch<JsonElement> patch) {
        return new WorkspacePatch<>(patch.op(), patch.path(), copy(patch.value()));
    }

    private CollaborationIdentity identity(Session session) {
        return session.getCollaborationIdentity() != null ? session.getCollaborationIdentity() : CollaborationIdentity.client(session.getClientId());
    }

    private boolean valid(JoinRequest request) {
        return request != null && supportsWorkspaceType(request.type()) && validId(request.resourceId());
    }

    private boolean valid(OperationRequest request) {
        return request != null && supportsWorkspaceType(request.type()) && validId(request.resourceId())
            && request.baseSequence() >= 0L && validOperationId(request.operationId());
    }

    private boolean valid(AwarenessRequest request) {
        return request != null && supportsWorkspaceType(request.type()) && validId(request.resourceId());
    }

    boolean supportsWorkspaceType(String type) {
        String normalized = safe(type);
        return GRAPH_TYPES.contains(normalized) || documentProviders.containsKey(normalized)
            || resources != null && resources.get(normalized) != null;
    }

    private boolean validId(String value) {
        String id = safe(value);
        return !id.isBlank() && id.length() <= 160 && !id.contains("..") && id.indexOf('/') < 0 && id.indexOf('\\') < 0;
    }

    private boolean validOperationId(String value) {
        String id = safe(value);
        return !id.isBlank() && id.length() <= 128 && id.equals(value);
    }

    private boolean compatibleGraphType(String requestedType, FlowGraph graph) {
        if (ReSyncResourceCatalog.CUSTOM_CONTENT.equals(requestedType)) {
            return graph != null;
        }
        String type = safe(graph.getResourceType());
        boolean function = "function".equals(type) || graph.isFunction();
        if ("function".equals(requestedType)) {
            return function;
        }
        return !function;
    }

    private FlowGraph loadGraph(String type, String resourceId) {
        if (!ReSyncResourceCatalog.CUSTOM_CONTENT.equals(type)) {
            return storage.getGraph(type, resourceId);
        }
        CustomContentDefinition content = customContentStorage != null ? customContentStorage.get(resourceId) : null;
        return content != null ? content.getGraph() : null;
    }

    private Runnable persistGraph(String type, String resourceId, FlowGraph graph, Runnable beforeVisible, Runnable afterVisible) {
        if (resources != null) {
            Object value = graph;
            if (ReSyncResourceCatalog.CUSTOM_CONTENT.equals(type)) {
                CustomContentDefinition cached = customContentStorage != null ? customContentStorage.get(resourceId) : null;
                if (cached == null) {
                    throw new IllegalStateException("Custom content not found: " + resourceId);
                }
                CustomContentDefinition content = gson.fromJson(gson.toJsonTree(cached), CustomContentDefinition.class);
                graph.setId(content.getFlowId() != null && !content.getFlowId().isBlank() ? content.getFlowId() : graph.getId());
                content.setGraph(graph);
                value = content;
            }
            return resources.saveAuthoritativeDurable(type, value, beforeVisible, afterVisible);
        }
        if (!ReSyncResourceCatalog.CUSTOM_CONTENT.equals(type)) {
            storage.saveGraph(graph);
            return () -> {
            };
        }
        CustomContentDefinition cached = customContentStorage != null ? customContentStorage.get(resourceId) : null;
        if (cached == null) {
            throw new IllegalStateException("Custom content not found: " + resourceId);
        }
        CustomContentDefinition content = gson.fromJson(gson.toJsonTree(cached), CustomContentDefinition.class);
        graph.setId(content.getFlowId() != null && !content.getFlowId().isBlank() ? content.getFlowId() : graph.getId());
        content.setGraph(graph);
        customContentStorage.save(content);
        return () -> {
        };
    }

    private String key(String type, String resourceId) {
        return safe(type) + '\u0000' + safe(resourceId);
    }

    private String safe(String value) {
        return value != null ? value.trim() : "";
    }

    private <T> T read(ByteBuffer buffer, Class<T> type) {
        if (buffer == null || !buffer.hasRemaining()) {
            return null;
        }
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        try {
            return gson.fromJson(new String(bytes, StandardCharsets.UTF_8), type);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private record JoinRequest(String type, String resourceId) {
    }

    private record OperationRequest(String type, String resourceId, String operationId, long baseSequence,
                                    List<WorkspacePatch<JsonElement>> patches) {
    }

    private record AwarenessRequest(String type, String resourceId, JsonObject state) {
    }

    private record SnapshotEvent(String type, String resourceId, long sequence, JsonObject document, List<AwarenessEvent> awareness) {
    }

    private record OperationEvent(String type, String resourceId, long sequence, String operationId, String authorSessionId,
                                  CollaborationIdentity author, List<WorkspacePatch<JsonElement>> patches) {
    }

    private record AwarenessEvent(String type, String resourceId, String authorSessionId, CollaborationIdentity author, JsonObject state, long updatedAt) {
    }

    private record ResyncEvent(String type, String resourceId, String reason) {
    }

    private record Persistence(JsonObject document, Runnable completion) {
    }

    private static final class Workspace {
        private final String type;
        private final String resourceId;
        private JsonObject document;
        private final Set<String> members = ConcurrentHashMap.newKeySet();
        private final Map<String, AwarenessEvent> awareness = new ConcurrentHashMap<>();
        private final List<WorkspacePatch<JsonElement>> pendingPatches = new ArrayList<>();
        private final WorkspaceRevision<OperationEvent> revision = new WorkspaceRevision<>();
        private final ReentrantLock commitLock = new ReentrantLock();
        private boolean deleted;

        private Workspace(String type, String resourceId, JsonObject document) {
            this.type = type;
            this.resourceId = resourceId;
            this.document = document;
        }
    }
}
