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
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.customcontent.CustomContentStorage;
import restudio.resync.flow.CoreGraphStorageBoundary;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ProtocolEditability;
import restudio.resync.flow.workspace.GraphWorkspacePatchEngine;
import restudio.resync.flow.workspace.WorkspacePatch;
import restudio.resync.flow.workspace.WorkspaceRevision;
import restudio.resync.resources.ReSyncResourceCatalog;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

public final class FlowWorkspaceService {
    private static final int MAX_PATCHES = GraphWorkspacePatchEngine.MAX_OPERATIONS;
    private static final int MAX_PATCH_PATH_LENGTH = GraphWorkspacePatchEngine.MAX_PATH_LENGTH;
    private static final Set<String> GRAPH_TYPES = Set.of("flow", "function", "command", ReSyncResourceCatalog.CUSTOM_CONTENT);
    private static final Set<String> PATCH_OPERATIONS = Set.of("set", "remove", "array_add", "array_remove");
    private static final Set<String> IMMUTABLE_ROOTS = Set.of("id", "worldName", "flowId", "function", "resourceType", "resourceRevision", "resourceHash",
        "resourceMutationId", "enabled");
    private static final Set<String> REVISION_ROOTS = Set.of("resourceRevision", "resourceHash", "resourceMutationId");
    private final FlowStorage storage;
    private final CustomContentStorage customContentStorage;
    private final FlowPacketSender sender;
    private final FlowResourceRegistry resources;
    private final CoreGraphWorkspaceDocumentProvider coreGraphs;
    private final GraphWorkspacePatchEngine corePatches = new GraphWorkspacePatchEngine();
    private final Map<String, FlowWorkspaceDocumentProvider> documentProviders = new ConcurrentHashMap<>();
    private final Gson gson = new Gson();
    private final Map<String, Workspace> workspaces = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> authorityLocks = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, ProtocolEditability> editability = new ConcurrentHashMap<>();
    private final Map<FlowResourceKey, WorkspaceCompletion> pendingCompletions = new ConcurrentHashMap<>();
    private final AtomicBoolean shutdown = new AtomicBoolean();
    private final ThreadLocal<Boolean> workspaceSaveCommit = ThreadLocal.withInitial(() -> false);
    private final ThreadLocal<Boolean> workspaceDeleteCommit = ThreadLocal.withInitial(() -> false);

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
        this.coreGraphs = storage != null ? new CoreGraphWorkspaceDocumentProvider(storage) : null;
    }

    public void handleJoin(Session session, ByteBuffer buffer) {
        JoinRequest request = read(buffer, JoinRequest.class);
        if (!valid(request)) {
            sender.sendWorkspaceResync(session, gson.toJson(new ResyncEvent("", "", "Invalid Workspace")));
            return;
        }
        String type = normalizeType(request.type());
        String resourceId = safe(request.resourceId());
        String key = key(type, resourceId);
        ReentrantLock authority = authorityLock(type, resourceId);
        authority.lock();
        try {
            while (true) {
                Workspace workspace = workspace(type, resourceId);
                if (workspace == null) {
                    sender.sendWorkspaceResync(session, gson.toJson(new ResyncEvent(type, resourceId, "Resource Unavailable")));
                    return;
                }
                sessions.put(session.getSessionId(), session);
                SnapshotEvent snapshot;
                workspace.commitLock.lock();
                try {
                    synchronized (workspace) {
                        if (workspaces.get(key) != workspace || workspace.deleted) {
                            continue;
                        }
                        workspace.members.add(session.getSessionId());
                        snapshot = new SnapshotEvent(type, resourceId, workspace.revision.sequence(), workspace.document.deepCopy(),
                            List.copyOf(workspace.awareness.values()), editability(session, type, resourceId));
                    }
                } finally {
                    workspace.commitLock.unlock();
                }
                sender.sendWorkspaceSnapshot(session, gson.toJson(snapshot));
                return;
            }
        } finally {
            authority.unlock();
        }
    }

    public void registerDocumentProvider(FlowWorkspaceDocumentProvider provider) {
        if (provider != null && provider.type() != null && !provider.type().isBlank()) {
            documentProviders.put(normalizeType(provider.type()), provider);
        }
    }

    public void setEditability(Session session, String type, String resourceId, ProtocolEditability value) {
        if (session == null || type == null || type.isBlank() || resourceId == null || resourceId.isBlank() || value == null) {
            throw new IllegalArgumentException("Workspace editability requires a session, resource, and negotiated value");
        }
        editability.put(editabilityKey(session, type, resourceId), value);
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
            || request.patches().stream().anyMatch(patch -> !validClientPatch(patch)
                && !(isCoreGraphWorkspace(safe(request.type())) && patch != null && "array_reorder".equals(patch.op())))
            || (request != null && isCoreGraphWorkspace(safe(request.type())) && request.patches().stream().anyMatch(patch -> !validCorePatch(patch)))) {
            sender.sendWorkspaceResync(session, gson.toJson(new ResyncEvent(request != null ? request.type() : "", request != null ? request.resourceId() : "", "Invalid Operation")));
            return;
        }
        String type = normalizeType(request.type());
        String resourceId = safe(request.resourceId());
        Workspace workspace = workspaces.get(key(type, resourceId));
        if (workspace == null) {
            sender.sendWorkspaceResync(session, gson.toJson(new ResyncEvent(type, resourceId, "Workspace Not Joined")));
            return;
        }
        if (isCoreGraphWorkspace(type) && editability(session, type, resourceId) != ProtocolEditability.EDITABLE) {
            sender.sendWorkspaceResync(session, gson.toJson(new ResyncEvent(type, resourceId, "Workspace Read Only")));
            return;
        }
        OperationEvent event = null;
        OperationEvent existing = null;
        String resyncReason = null;
        List<Session> targets = List.of();
        workspace.commitLock.lock();
        try {
            synchronized (workspace) {
                if (!isWorkspaceMember(workspace, session)) {
                    resyncReason = "Workspace Not Joined";
                } else {
                    String payloadHash = operationHash(type, resourceId, request.patches());
                    WorkspaceRevision.Assessment<OperationEvent> assessment =
                        workspace.revision.assess(request.baseSequence(), request.operationId(), payloadHash);
                    if (assessment.status() == WorkspaceRevision.Status.DUPLICATE) {
                        existing = assessment.existing();
                    } else if (assessment.status() == WorkspaceRevision.Status.MISMATCH) {
                        resyncReason = "Operation ID Conflict";
                    } else if (assessment.status() == WorkspaceRevision.Status.CONFLICT) {
                        resyncReason = "Workspace Sequence Conflict";
                    } else {
                        try {
                            JsonObject next = applyBatch(type, workspace.document, request.patches());
                            workspace.document = next;
                        } catch (RuntimeException exception) {
                            resyncReason = "Invalid Operation";
                        }
                        if (resyncReason == null) {
                            request.patches().stream().map(this::copy).forEach(workspace.pendingPatches::add);
                            event = workspace.revision.advance(request.operationId(), payloadHash, sequence ->
                                new OperationEvent(type, resourceId, sequence, safe(request.operationId()), session.getSessionId(),
                                    identity(session), payloadHash, List.copyOf(request.patches())));
                            targets = members(workspace, null);
                        }
                    }
                }
            }
        } finally {
            workspace.commitLock.unlock();
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
        String type = normalizeType(request.type());
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
        editability.keySet().removeIf(key -> key.startsWith(session.getSessionId() + "\u0000"));
        if (resources != null) {
            resources.closeSessionSaves(session);
        }
        for (Map.Entry<String, Workspace> entry : workspaces.entrySet()) {
            leave(session, entry.getKey());
        }
    }

    public void shutdown() {
        synchronized (pendingCompletions) {
            if (!shutdown.compareAndSet(false, true)) {
                return;
            }
            for (WorkspaceCompletion completion : List.copyOf(pendingCompletions.values())) {
                completion.cancel();
            }
        }
        if (resources != null) {
            resources.closeAllSessionSaves();
        }
        workspaces.clear();
        sessions.clear();
        editability.clear();
    }

    public void resourceDeleted(String type, String resourceId) {
        if (workspaceDeleteCommit.get()) {
            return;
        }
        type = normalizeType(type);
        resourceId = safe(resourceId);
        ReentrantLock authority = authorityLock(type, resourceId);
        authority.lock();
        try {
            resourceDeletedLocked(type, resourceId);
        } finally {
            authority.unlock();
        }
    }

    private void resourceDeletedLocked(String type, String resourceId) {
        if (isCoreGraphWorkspace(type)) {
            Workspace workspace = workspaces.get(key(type, resourceId));
            if (workspace != null) {
                workspace.commitLock.lock();
                try {
                    retireCoreWorkspace(workspace, safe(type), safe(resourceId));
                } finally {
                    workspace.commitLock.unlock();
                }
            }
            return;
        }
        cancelPendingCompletion(type, resourceId);
        delete(type, resourceId, () -> {
        });
    }

    void delete(String type, String resourceId, Runnable action) {
        type = normalizeType(type);
        resourceId = safe(resourceId);
        ReentrantLock authority = authorityLock(type, resourceId);
        authority.lock();
        try {
            deleteLocked(type, resourceId, action);
        } finally {
            authority.unlock();
        }
    }

    private void deleteLocked(String type, String resourceId, Runnable action) {
        if (isCoreGraphWorkspace(type)) {
            throw new IllegalStateException("Core graph deletion requires the generic Core resource tombstone authority");
        }
        cancelPendingCompletion(type, resourceId);
        Workspace workspace = workspaces.get(key(type, resourceId));
        if (workspace == null) {
            runDeleteAction(action);
            return;
        }
        List<Session> targets = List.of();
        List<Session> pendingSessions = List.of();
        workspace.commitLock.lock();
        try {
            runDeleteAction(action);
            synchronized (workspace) {
                if (workspaces.remove(key(workspace.type, workspace.resourceId), workspace)) {
                    workspace.deleted = true;
                    targets = members(workspace, null);
                    pendingSessions = workspace.members.stream().map(sessions::get).filter(session -> session != null).toList();
                    workspace.members.clear();
                    workspace.awareness.clear();
                }
            }
        } finally {
            workspace.commitLock.unlock();
        }
        for (Session session : pendingSessions) {
            cancelSessionCompletion(session, workspace.type, workspace.resourceId);
        }
        sendResync(targets, workspace.type, workspace.resourceId, "Resource Unavailable");
    }

    private void runDeleteAction(Runnable action) {
        workspaceDeleteCommit.set(true);
        try {
            action.run();
        } finally {
            workspaceDeleteCommit.remove();
        }
    }

    public void resourceSaved(String type, String resourceId, String payload) {
        if (workspaceSaveCommit.get()) {
            return;
        }
        type = normalizeType(type);
        resourceId = safe(resourceId);
        ReentrantLock authority = authorityLock(type, resourceId);
        authority.lock();
        try {
            resourceSavedLocked(type, resourceId, payload);
        } finally {
            authority.unlock();
        }
    }

    private void resourceSavedLocked(String type, String resourceId, String payload) {
        Workspace workspace = workspaces.get(key(type, resourceId));
        JsonObject latest = workspaceDocument(type, resourceId, payload);
        if (workspace == null || latest == null) {
            return;
        }
        List<Session> targets;
        String reason = "Resource Updated";
        workspace.commitLock.lock();
        try {
            synchronized (workspace) {
                if (workspaces.get(key(workspace.type, workspace.resourceId)) != workspace || workspace.deleted) {
                    return;
                }
                try {
                    workspace.document = rebase(type, latest, workspace.pendingPatches);
                } catch (RuntimeException exception) {
                    reason = "Workspace Rebase Conflict";
                }
                targets = members(workspace, null);
            }
        } finally {
            workspace.commitLock.unlock();
        }
        sendResync(targets, workspace.type, workspace.resourceId, reason);
    }

    public CoreGraphStorageBoundary.Decoded saveCore(Session session, ServerResourceLocator resource, byte[] canonicalEnvelope,
                                                      UUID mutationId, long expectedRevision, ContentHash payloadChecksum) {
        String type = normalizeType(resource.resourceType().value());
        String resourceId = safe(resource.id());
        ReentrantLock authority = authorityLock(type, resourceId);
        authority.lock();
        try {
            return saveCoreLocked(session, resource, canonicalEnvelope, mutationId, expectedRevision, payloadChecksum, type, resourceId);
        } finally {
            authority.unlock();
        }
    }

    private CoreGraphStorageBoundary.Decoded saveCoreLocked(Session session, ServerResourceLocator resource, byte[] canonicalEnvelope,
                                                             UUID mutationId, long expectedRevision, ContentHash payloadChecksum,
                                                             String type, String resourceId) {
        requireCoreMutation(session, type, resourceId);
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        boundary.decode(canonicalEnvelope, resource);
        Workspace workspace = workspaces.get(key(type, resourceId));
        if (workspace != null) {
            workspace.commitLock.lock();
        }
        try {
            if (workspace != null && !isWorkspaceMember(workspace, session)) {
                throw new IllegalStateException("Core workspace save requires membership in the current workspace");
            }
            CoreGraphStorageBoundary.Decoded saved;
            workspaceSaveCommit.set(true);
            try {
                saved = resources.protocolSave(resource, canonicalEnvelope, mutationId, expectedRevision, payloadChecksum);
            } finally {
                workspaceSaveCommit.remove();
            }
            verifyCoreReceipt(resource, mutationId, expectedRevision, payloadChecksum, saved);
            JsonObject persisted = coreGraphs.project(type, resourceId, new String(boundary.encode(saved), StandardCharsets.UTF_8));
            if (persisted == null) {
                throw new IllegalStateException("Core graph save receipt cannot be projected into the workspace");
            }
            if (workspace != null) {
                List<Session> targets;
                synchronized (workspace) {
                    if (workspaces.get(key(type, resourceId)) != workspace || workspace.deleted) {
                        throw new IllegalStateException("Core workspace changed during save");
                    }
                    workspace.document = persisted;
                    workspace.pendingPatches.clear();
                    targets = members(workspace, null);
                }
                sendResync(targets, type, resourceId, "Resource Updated");
            }
            return saved;
        } finally {
            if (workspace != null) {
                workspace.commitLock.unlock();
            }
        }
    }

    public CoreGraphStorageBoundary.CoreGraphTombstone deleteCore(Session session, ServerResourceLocator resource, UUID mutationId,
                                                                   long expectedRevision, ContentHash payloadChecksum) {
        String type = normalizeType(resource.resourceType().value());
        String resourceId = safe(resource.id());
        ReentrantLock authority = authorityLock(type, resourceId);
        authority.lock();
        try {
            return deleteCoreLocked(session, resource, mutationId, expectedRevision, payloadChecksum, type, resourceId);
        } finally {
            authority.unlock();
        }
    }

    private CoreGraphStorageBoundary.CoreGraphTombstone deleteCoreLocked(Session session, ServerResourceLocator resource, UUID mutationId,
                                                                          long expectedRevision, ContentHash payloadChecksum,
                                                                          String type, String resourceId) {
        requireCoreMutation(session, type, resourceId);
        Workspace workspace = workspaces.get(key(type, resourceId));
        if (workspace != null) {
            workspace.commitLock.lock();
        }
        try {
            if (workspace != null && !isWorkspaceMember(workspace, session)) {
                throw new IllegalStateException("Core workspace delete requires membership in the current workspace");
            }
            CoreGraphStorageBoundary.CoreGraphTombstone tombstone;
            workspaceDeleteCommit.set(true);
            try {
                tombstone = resources.protocolDelete(resource, mutationId, expectedRevision, payloadChecksum);
            } finally {
                workspaceDeleteCommit.remove();
            }
            if (!resource.equals(tombstone.resource()) || !mutationId.equals(tombstone.mutationId()) || !tombstone.deleted()
                || tombstone.revision() != Math.addExact(expectedRevision, 1L) || !payloadChecksum.equals(tombstone.priorPayloadHash())) {
                throw new IllegalStateException("Core graph tombstone receipt does not match the requested mutation");
            }
            retireCoreWorkspace(workspace, type, resourceId);
            return tombstone;
        } finally {
            if (workspace != null) {
                workspace.commitLock.unlock();
            }
        }
    }

    private void requireCoreMutation(Session session, String type, String resourceId) {
        if (resources == null || coreGraphs == null || !coreGraphs.supports(normalizeType(type)) || !isCoreGraphWorkspace(type)) {
            throw new IllegalStateException("Core graph resource authority is unavailable");
        }
        if (editability(session, type, resourceId) != ProtocolEditability.EDITABLE) {
            throw new IllegalStateException("Core workspace is read only");
        }
    }

    private void verifyCoreReceipt(ServerResourceLocator resource, UUID mutationId, long expectedRevision, ContentHash payloadChecksum,
                                   CoreGraphStorageBoundary.Decoded saved) {
        ServerResourceLocator savedResource = saved.graphDocument() != null ? saved.graphDocument().resource()
            : saved.functionSourceDocument().graph().resource();
        if (!resource.equals(savedResource) || !mutationId.toString().equals(saved.envelope().assetMutationId())
            || saved.envelope().assetRevision() != Math.addExact(expectedRevision, 1L)
            || !saved.envelope().assetHash().equals(payloadChecksum)) {
            throw new IllegalStateException("Core graph save receipt does not match the requested mutation");
        }
    }

    private void retireCoreWorkspace(Workspace workspace, String type, String resourceId) {
        if (workspace == null) {
            return;
        }
        List<Session> targets;
        synchronized (workspace) {
            if (!workspaces.remove(key(type, resourceId), workspace)) {
                throw new IllegalStateException("Core workspace changed during deletion");
            }
            workspace.deleted = true;
            targets = members(workspace, null);
            workspace.members.clear();
            workspace.awareness.clear();
        }
        sendResync(targets, type, resourceId, "Resource Unavailable");
    }

    <T> T save(Session session, FlowResourceAdapter<T> adapter, T value) {
        String type = normalizeType(adapter.descriptor().typeId());
        String resourceId = adapter.id(value);
        ReentrantLock authority = authorityLock(type, resourceId);
        authority.lock();
        try {
            return saveLocked(session, adapter, value, type, resourceId);
        } finally {
            authority.unlock();
        }
    }

    private <T> T saveLocked(Session session, FlowResourceAdapter<T> adapter, T value, String type, String resourceId) {
        if (isCoreGraphWorkspace(type)) {
            throw new IllegalStateException("Core graph save requires the generic Core resource authority");
        }
        Workspace workspace = workspaces.get(key(type, resourceId));
        if (workspace == null || !isWorkspaceMember(workspace, session)) {
            adapter.save(value);
            return value;
        }
        workspace.commitLock.lock();
        try {
            if (!isWorkspaceMember(workspace, session)) {
                adapter.save(value);
                return value;
            }
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
        boolean removed = false;
        cancelSessionCompletion(session, workspace.type, workspace.resourceId);
        synchronized (workspace) {
            if (!workspace.members.remove(session.getSessionId())) {
                return;
            }
            workspace.awareness.remove(session.getSessionId());
            event = new AwarenessEvent(workspace.type, workspace.resourceId, session.getSessionId(), identity(session), new JsonObject(),
                System.currentTimeMillis());
            targets = members(workspace, event.authorSessionId());
            if (workspace.members.isEmpty() && !isCoreGraphWorkspace(workspace.type)) {
                workspaces.remove(key, workspace);
                removed = true;
            }
        }
        if (removed) {
            cancelPendingCompletion(workspace.type, workspace.resourceId);
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

    private JsonObject rebase(String type, JsonObject latest, List<WorkspacePatch<JsonElement>> patches) {
        if (isCoreGraphWorkspace(type) && patches != null && !patches.isEmpty()) {
            return applyCorePatches(latest, patches, true);
        }
        return rebase(latest, patches);
    }

    private JsonObject workspaceDocument(String type, String resourceId, String payload) {
        type = normalizeType(type);
        resourceId = safe(resourceId);
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            if (isCoreGraphWorkspace(type)) {
                return coreGraphs != null ? coreGraphs.project(type, resourceId, payload) : null;
            }
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
        type = normalizeType(type);
        resourceId = safe(resourceId);
        if (isCoreGraphWorkspace(type)) {
            return coreGraphs != null ? coreGraphs.load(type, resourceId) : null;
        }
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
        try {
            persistence.completion().run();
            return persistence.document();
        } catch (RuntimeException | Error exception) {
            persistence.cancellation().run();
            throw exception;
        }
    }

    private Persistence persistDocumentDurable(String type, String resourceId, JsonObject document, Runnable beforeVisible, Runnable afterVisible) {
        if (isCoreGraphWorkspace(type)) {
            throw new IllegalStateException("Core graph persistence requires the generic Core resource authority");
        }
        if (isGraphWorkspace(type)) {
            FlowGraph graph = FlowSerializer.deserialize(document.toString());
            graph.setId(resourceId);
            graph.setResourceType(type);
            return withCompletionGuard(type, resourceId, () -> {
                WorkspaceCompletion tracked;
                if (resources != null) {
                    FlowResourceMutationLease.DeferredCompletion handle = null;
                    try {
                        handle = persistGraphHandle(type, resourceId, graph, beforeVisible, afterVisible);
                        tracked = trackCompletion(type, resourceId, handle);
                    } catch (RuntimeException | Error exception) {
                        if (handle != null) {
                            handle.cancel();
                        }
                        throw exception;
                    }
                } else {
                    tracked = directCompletion(persistGraph(type, resourceId, graph, beforeVisible, afterVisible));
                }
                try {
                    return new Persistence(JsonParser.parseString(FlowSerializer.serialize(graph)).getAsJsonObject(), tracked::run, tracked::cancel);
                } catch (RuntimeException | Error exception) {
                    tracked.cancel();
                    throw exception;
                }
            });
        }
        FlowWorkspaceDocumentProvider provider = documentProviders.get(type);
        if (provider != null) {
            provider.persist(resourceId, document.deepCopy());
            return new Persistence(document.deepCopy(), () -> {
            }, () -> {
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
        return withCompletionGuard(type, resourceId, () -> {
            FlowResourceMutationLease.DeferredCompletion handle = null;
            WorkspaceCompletion tracked = null;
            try {
                handle = resources.saveAuthoritativeDurableHandle(type, value, beforeVisible, afterVisible);
                tracked = trackCompletion(type, resourceId, handle);
                return new Persistence(JsonParser.parseString(serialize(adapter, value)).getAsJsonObject(), tracked::run, tracked::cancel);
            } catch (RuntimeException | Error exception) {
                if (tracked != null) {
                    tracked.cancel();
                } else if (handle != null) {
                    handle.cancel();
                }
                throw exception;
            }
        });
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
        type = normalizeType(type);
        return ReSyncResourceCatalog.FLOW.equals(type) || ReSyncResourceCatalog.FUNCTION.equals(type)
            || ReSyncResourceCatalog.COMMAND.equals(type) || ReSyncResourceCatalog.CUSTOM_CONTENT.equals(type);
    }

    private boolean isCoreGraphWorkspace(String type) {
        return Set.of("flow", "function", "command").contains(normalizeType(type));
    }

    private void sendResync(List<Session> targets, String type, String resourceId, String reason) {
        String json = gson.toJson(new ResyncEvent(type, resourceId, reason));
        for (Session target : targets) {
            sender.sendWorkspaceResync(target, json);
        }
    }

    private JsonObject applyBatch(String type, JsonObject document, List<WorkspacePatch<JsonElement>> patches) {
        if (isCoreGraphWorkspace(type)) {
            return applyCorePatches(document, patches, false);
        }
        JsonObject next = document.deepCopy();
        for (WorkspacePatch<JsonElement> patch : patches) {
            apply(next, patch);
        }
        return next;
    }

    private JsonObject applyCorePatches(JsonObject document, List<WorkspacePatch<JsonElement>> patches, boolean rebase) {
        JsonValue current = GsonJsonValues.convert(document);
        if (!(current instanceof JsonValue.JsonObject object)) {
            throw new IllegalArgumentException("Core workspace document must be an object");
        }
        List<WorkspacePatch<JsonValue>> converted = patches.stream().map(this::corePatch).toList();
        JsonValue.JsonObject next = rebase ? corePatches.rebase(object, converted) : corePatches.apply(object, converted);
        return JsonParser.parseString(next.canonicalText()).getAsJsonObject();
    }

    private WorkspacePatch<JsonValue> corePatch(WorkspacePatch<JsonElement> patch) {
        JsonValue value = patch.value() != null ? GsonJsonValues.convert(patch.value()) : null;
        return new WorkspacePatch<>(patch.op(), patch.path(), value);
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
        String normalized = normalizeType(type);
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
        String type = normalizeType(graph.getResourceType());
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

    private boolean validCorePatch(WorkspacePatch<JsonElement> patch) {
        try {
            return corePatches.valid(corePatch(patch));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private FlowResourceMutationLease.DeferredCompletion persistGraphHandle(String type, String resourceId, FlowGraph graph,
                                                                            Runnable beforeVisible, Runnable afterVisible) {
        if (resources == null) {
            throw new IllegalStateException("Resource registry unavailable");
        }
        return resources.saveAuthoritativeDurableHandle(type, graphPersistenceValue(type, resourceId, graph), beforeVisible, afterVisible);
    }

    private Runnable persistGraph(String type, String resourceId, FlowGraph graph, Runnable beforeVisible, Runnable afterVisible) {
        if (!ReSyncResourceCatalog.CUSTOM_CONTENT.equals(type)) {
            storage.saveGraph(graph);
            return () -> {
            };
        }
        customContentStorage.save((CustomContentDefinition) graphPersistenceValue(type, resourceId, graph));
        return () -> {
        };
    }

    private Object graphPersistenceValue(String type, String resourceId, FlowGraph graph) {
        if (!ReSyncResourceCatalog.CUSTOM_CONTENT.equals(type)) {
            return graph;
        }
        CustomContentDefinition cached = customContentStorage != null ? customContentStorage.get(resourceId) : null;
        if (cached == null) {
            throw new IllegalStateException("Custom content not found: " + resourceId);
        }
        CustomContentDefinition content = gson.fromJson(gson.toJsonTree(cached), CustomContentDefinition.class);
        graph.setId(content.getFlowId() != null && !content.getFlowId().isBlank() ? content.getFlowId() : graph.getId());
        content.setGraph(graph);
        return content;
    }

    private String key(String type, String resourceId) {
        return normalizeType(type) + '\u0000' + safe(resourceId);
    }

    private ReentrantLock authorityLock(String type, String resourceId) {
        return authorityLocks.computeIfAbsent(key(type, resourceId), ignored -> new ReentrantLock());
    }

    private String normalizeType(String value) {
        return safe(value).toLowerCase(java.util.Locale.ROOT);
    }

    private String editabilityKey(Session session, String type, String resourceId) {
        return session.getSessionId() + '\u0000' + key(type, resourceId);
    }

    private ProtocolEditability editability(Session session, String type, String resourceId) {
        if (!isCoreGraphWorkspace(type)) {
            return ProtocolEditability.EDITABLE;
        }
        return session != null ? editability.getOrDefault(editabilityKey(session, type, resourceId), ProtocolEditability.REJECTED)
            : ProtocolEditability.REJECTED;
    }

    private String safe(String value) {
        return value != null ? value.trim() : "";
    }

    private String operationHash(String type, String resourceId, List<WorkspacePatch<JsonElement>> patches) {
        JsonObject payload = new JsonObject();
        payload.addProperty("type", type);
        payload.addProperty("resourceId", resourceId);
        payload.add("patches", JsonParser.parseString(gson.toJson(patches)));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(GsonJsonValues.convert(payload).canonicalBytes()));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
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

    private record SnapshotEvent(String type, String resourceId, long sequence, JsonObject document, List<AwarenessEvent> awareness,
                                 ProtocolEditability editability) {
    }

    private record OperationEvent(String type, String resourceId, long sequence, String operationId, String authorSessionId,
                                  CollaborationIdentity author, String payloadHash, List<WorkspacePatch<JsonElement>> patches) {
    }

    private record AwarenessEvent(String type, String resourceId, String authorSessionId, CollaborationIdentity author, JsonObject state, long updatedAt) {
    }

    private record ResyncEvent(String type, String resourceId, String reason) {
    }

    private record Persistence(JsonObject document, Runnable completion, Runnable cancellation) {
    }

    private WorkspaceCompletion directCompletion(Runnable completion) {
        return new WorkspaceCompletion(completion);
    }

    private WorkspaceCompletion trackCompletion(String type, String resourceId,
                                                FlowResourceMutationLease.DeferredCompletion completion) {
        FlowResourceKey key = new FlowResourceKey(type, resourceId);
        WorkspaceCompletion tracked = new WorkspaceCompletion(key, completion, true);
        synchronized (pendingCompletions) {
            if (shutdown.get()) {
                throw new IllegalStateException("Workspace service is shut down");
            }
            if (pendingCompletions.containsKey(key)) {
                throw new IllegalStateException("Workspace persistence is already pending: " + key);
            }
            pendingCompletions.put(key, tracked);
        }
        return tracked;
    }

    private void cancelPendingCompletion(String type, String resourceId) {
        if (resources == null || type == null || resourceId == null) {
            return;
        }
        FlowResourceKey key;
        try {
            key = new FlowResourceKey(type, resourceId);
        } catch (RuntimeException exception) {
            return;
        }
        WorkspaceCompletion completion = pendingCompletions.get(key);
        if (completion != null) {
            completion.cancel();
        }
    }

    private Persistence withCompletionGuard(String type, String resourceId, Supplier<Persistence> action) {
        if (resources == null) {
            return action.get();
        }
        FlowResourceKey key = new FlowResourceKey(type, resourceId);
        synchronized (pendingCompletions) {
            if (shutdown.get()) {
                throw new IllegalStateException("Workspace service is shut down");
            }
            if (pendingCompletions.containsKey(key)) {
                throw new IllegalStateException("Workspace persistence is already pending: " + key);
            }
            return action.get();
        }
    }

    private void cancelSessionCompletion(Session session, String type, String resourceId) {
        if (resources == null || session == null || type == null || resourceId == null) {
            return;
        }
        FlowResourceKey key;
        try {
            key = new FlowResourceKey(type, resourceId);
        } catch (RuntimeException exception) {
            return;
        }
        FlowResourceAdapter<?> adapter = resources.get(key.typeId());
        if (adapter != null) {
            resources.cancelSessionSave(session, adapter, key.resourceId());
        }
    }

    private final class WorkspaceCompletion {
        private final FlowResourceKey key;
        private final FlowResourceMutationLease.DeferredCompletion handle;
        private final Runnable directCompletion;
        private final boolean tracked;
        private final AtomicBoolean settled = new AtomicBoolean();

        private WorkspaceCompletion(Runnable directCompletion) {
            this(null, null, directCompletion, false);
        }

        private WorkspaceCompletion(FlowResourceKey key, FlowResourceMutationLease.DeferredCompletion handle, boolean tracked) {
            this(key, handle, null, tracked);
        }

        private WorkspaceCompletion(FlowResourceKey key, FlowResourceMutationLease.DeferredCompletion handle,
                                    Runnable directCompletion, boolean tracked) {
            this.key = key;
            this.handle = handle;
            this.directCompletion = directCompletion;
            this.tracked = tracked;
        }

        private void run() {
            if (!settled.compareAndSet(false, true)) {
                throw new IllegalStateException("Workspace persistence completion has already been settled");
            }
            try {
                if (handle != null) {
                    handle.run();
                } else {
                    directCompletion.run();
                }
            } finally {
                remove();
            }
        }

        private boolean cancel() {
            if (!settled.compareAndSet(false, true)) {
                return false;
            }
            try {
                if (tracked) {
                    handle.cancel();
                }
                return true;
            } finally {
                remove();
            }
        }

        private void remove() {
            if (tracked) {
                pendingCompletions.remove(key, this);
            }
        }
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
