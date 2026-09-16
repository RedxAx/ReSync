package restudio.resync.modules.flow;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowDataTypeAdapter;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowSerializer;
import restudio.flow.data.GuiDefinition;
import restudio.flow.data.ScoreboardDefinition;
import restudio.flow.data.TabDefinition;
import restudio.flow.data.CustomContentDefinition;
import restudio.resync.Log;
import restudio.resync.api.OptionCatalogItem;
import restudio.resync.core.ConnectionInfo;
import restudio.resync.core.ConnectionState;
import restudio.resync.core.Session;
import restudio.resync.protocol.ReSyncProtocolContract;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.contract.EditorError;
import restudio.resync.flow.diagnostics.FlowTraceRecord;
import restudio.resync.flow.jobs.FlowJobRegistry;
import restudio.resync.server.AuthorityEpoch;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCachePublicationCodec;
import restudio.resync.flow.cache.CatalogPublicationChunkPacket;
import restudio.resync.flow.sync.NodeRegistryPinSerializer;
import restudio.resync.flow.sync.NodeRegistrySnapshot;
import restudio.resync.flow.sync.OptionCatalogSnapshot;
import restudio.resync.jobs.JobManager;
import restudio.resync.jobs.JobRecord;
import restudio.resync.protocol.Codec;
import restudio.resync.protocol.FrameSender;
import restudio.resync.protocol.messages.DataMessage;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class FlowPacketSender {
    public static final int MAX_PACKET_SIZE = 1024 * 1024;
    public static final int MAX_CATALOG_PUBLICATION_SIZE = CatalogPublicationChunkPacket.MAX_PUBLICATION_BYTES;
    public static final int MAX_CATALOG_PUBLICATION_PLAN_CACHE_ENTRIES = 64;
    public static final long MAX_CATALOG_PUBLICATION_PLAN_CACHE_BYTES = 64L * 1024L * 1024L;
    public static final int MAX_CATALOG_ENCODING_QUEUE = 8;
    public static final int MAX_STRING_LENGTH = 65536;
    public static final String CATALOG_AUTHORING_CAPABILITY = "restudio.resync/catalog_authoring";
    private static final int FRAME_HEADER_BYTES = 1 + 1 + Short.BYTES + Integer.BYTES + Integer.BYTES;
    private static final ThreadPoolExecutor CATALOG_ENCODING_EXECUTOR = new ThreadPoolExecutor(
        1, 2, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(MAX_CATALOG_ENCODING_QUEUE), runnable -> {
            Thread thread = new Thread(runnable, "resync-catalog-encoding");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    private final Codec codec;
    private final int channelId;
    private final Set<Session> subscribedSessions;
    private final JobManager jobManager;
    private final FlowResourceProtocolAuthority protocolAuthority;
    private volatile AuthorityEpoch authorityEpoch;
    private final CatalogCachePublicationCodec catalogPublicationCodec = new CatalogCachePublicationCodec();
    private final CatalogPublicationPlanCache catalogPublicationPlanCache = new CatalogPublicationPlanCache(
        MAX_CATALOG_PUBLICATION_PLAN_CACHE_ENTRIES, MAX_CATALOG_PUBLICATION_PLAN_CACHE_BYTES);
    private volatile String lastCatalogPublicationSendFailureCode = "";
    private final Gson gson = new GsonBuilder()
            .registerTypeAdapter(FlowDataType.class, new FlowDataTypeAdapter())
            .registerTypeAdapter(NodeDefinition.PinDefinition.class, new NodeRegistryPinSerializer())
            .registerTypeAdapter(NodeDefinition.NodeCategory.class, new TypeAdapter<NodeDefinition.NodeCategory>() {
                @Override
                public void write(JsonWriter out, NodeDefinition.NodeCategory value) throws IOException {
                    out.value(value != null ? value.getId() : null);
                }

                @Override
                public NodeDefinition.NodeCategory read(JsonReader in) throws IOException {
                    String id = in.nextString();
                    return NodeDefinition.NodeCategory.fromString(id);
                }
            })
            .create();

    public FlowPacketSender(Codec codec, int channelId, Set<Session> subscribedSessions) {
        this(codec, channelId, subscribedSessions, null, FlowResourceProtocolAuthority.shared());
    }

    public FlowPacketSender(Codec codec, int channelId, Set<Session> subscribedSessions, FlowJobRegistry flowJobs) {
        this(codec, channelId, subscribedSessions, flowJobs, FlowResourceProtocolAuthority.shared());
    }

    FlowPacketSender(Codec codec, int channelId, Set<Session> subscribedSessions, FlowJobRegistry flowJobs,
                     FlowResourceProtocolAuthority protocolAuthority) {
        this.codec = codec;
        this.channelId = channelId;
        this.subscribedSessions = subscribedSessions;
        this.jobManager = flowJobs == null ? null : new JobManager(flowJobs, job -> broadcastJob("jobStatus", job.snapshot()));
        this.protocolAuthority = Objects.requireNonNull(protocolAuthority, "Protocol authority is required");
    }

    public void setAuthorityEpoch(AuthorityEpoch authorityEpoch) {
        AuthorityEpoch bound = Objects.requireNonNull(authorityEpoch, "Authority epoch is required");
        if (bound.current() < 1L) {
            throw new IllegalArgumentException("Authority epoch must be positive");
        }
        this.authorityEpoch = bound;
    }

    public boolean acceptsLegacyMutationEpoch(String json) {
        return requiredAuthorityEpoch().acceptsLegacyJson(json);
    }

    public boolean acceptsLegacyMutationEpoch(String json, boolean legacyCompatible) {
        return requiredAuthorityEpoch().acceptsLegacyJson(json, legacyCompatible);
    }

    public void sendFlowData(Session session, FlowGraph graph) {
        sendJsonPacket(session, resourcePackets("flow").data(), FlowSerializer.serialize(graph), "FLOW_TOO_LARGE", "Flow data exceeds maximum size");
    }

    public void sendGuiData(Session session, GuiDefinition gui) {
        sendJsonPacket(session, resourcePackets("gui").data(), FlowSerializer.serializeGui(gui), "GUI_TOO_LARGE", "GUI data exceeds maximum size");
    }

    public void sendScoreboardData(Session session, ScoreboardDefinition scoreboard) {
        sendJsonPacket(session, resourcePackets("scoreboard").data(), FlowSerializer.serializeScoreboard(scoreboard), "SCOREBOARD_TOO_LARGE", "Scoreboard data exceeds maximum size");
    }

    public void sendTabData(Session session, TabDefinition tab) {
        sendJsonPacket(session, resourcePackets("tab").data(), FlowSerializer.serializeTab(tab), "TAB_TOO_LARGE", "Tab data exceeds maximum size");
    }

    public void sendCustomContentData(Session session, CustomContentDefinition content) {
        sendJsonPacket(session, resourcePackets("custom_content").data(), gson.toJson(content), "CONTENT_TOO_LARGE", "Custom content data exceeds maximum size");
    }

    public void sendProjectMetadataData(Session session, String json) {
        sendJsonPacket(session, resourcePackets("project_metadata").data(), json, "PROJECT_METADATA_TOO_LARGE", "Project metadata exceeds maximum size");
    }

    public void sendJsonResourceData(Session session, byte packetId, String json, String typeName) {
        String displayName = typeName != null && !typeName.isBlank() ? typeName : "Resource";
        sendJsonPacket(session, packetId, json, displayName.toUpperCase().replace(' ', '_') + "_TOO_LARGE", displayName + " data exceeds maximum size");
    }

    public void sendJsonPayload(Session session, byte packetId, String json, String errorCode, String errorMessage) {
        sendJsonPacket(session, packetId, json, errorCode, errorMessage);
    }

    public void sendFlowSaveAck(Session session, String flowId) {
        sendIdAck(session, resourcePackets("flow").saveAck(), flowId);
    }

    public void sendFlowSaveAck(Session session, String flowId, String requestId) {
        sendIdAck(session, resourcePackets("flow").saveAck(), flowId, requestId);
    }

    public void sendFlowSaveAck(Session session, String flowId, String requestId, long revision, String hash) {
        sendIdAck(session, resourcePackets("flow").saveAck(), flowId, requestId, revision, hash);
    }

    public void sendGraphSaveAck(Session session, String resourceType, String graphId, String requestId, long revision, String hash) {
        String type = Set.of("flow", "function", "command").contains(resourceType) ? resourceType : "flow";
        sendIdAck(session, resourcePackets(type).saveAck(), graphId, requestId, revision, hash);
    }

    public void sendGraphSaveAck(Session session, String resourceType, String graphId, String requestId) {
        String type = Set.of("flow", "function", "command").contains(resourceType) ? resourceType : "flow";
        sendIdAck(session, resourcePackets(type).saveAck(), graphId, requestId);
    }

    public void sendGuiSaveAck(Session session, String guiId) {
        sendIdAck(session, resourcePackets("gui").saveAck(), guiId);
    }

    public void sendGuiSaveAck(Session session, String guiId, String requestId) {
        sendIdAck(session, resourcePackets("gui").saveAck(), guiId, requestId);
    }

    public void sendScoreboardSaveAck(Session session, String scoreboardId) {
        sendIdAck(session, resourcePackets("scoreboard").saveAck(), scoreboardId);
    }

    public void sendScoreboardSaveAck(Session session, String scoreboardId, String requestId) {
        sendIdAck(session, resourcePackets("scoreboard").saveAck(), scoreboardId, requestId);
    }

    public void sendTabSaveAck(Session session, String tabId) {
        sendIdAck(session, resourcePackets("tab").saveAck(), tabId);
    }

    public void sendTabSaveAck(Session session, String tabId, String requestId) {
        sendIdAck(session, resourcePackets("tab").saveAck(), tabId, requestId);
    }

    public void sendCustomContentSaveAck(Session session, String contentId) {
        sendIdAck(session, resourcePackets("custom_content").saveAck(), contentId);
    }

    public void sendCustomContentSaveAck(Session session, String contentId, String requestId) {
        sendIdAck(session, resourcePackets("custom_content").saveAck(), contentId, requestId);
    }

    public void sendProjectMetadataSaveAck(Session session, String metadataId) {
        sendIdAck(session, resourcePackets("project_metadata").saveAck(), metadataId);
    }

    public void sendProjectMetadataSaveAck(Session session, String metadataId, String requestId) {
        sendIdAck(session, resourcePackets("project_metadata").saveAck(), metadataId, requestId);
    }

    public void sendJsonResourceSaveAck(Session session, byte packetId, String id) {
        sendIdAck(session, packetId, id);
    }

    public void sendJsonResourceSaveAck(Session session, byte packetId, String id, String requestId) {
        sendIdAck(session, packetId, id, requestId);
    }

    public void sendJsonResourceSaveAck(Session session, byte packetId, String id, String requestId, long revision, String hash) {
        sendIdAck(session, packetId, id, requestId, revision, hash);
    }

    public JobRecord<String> beginJob(Session session, String action, String target) {
        return beginJob(session, action, target, null);
    }

    public JobRecord<String> beginJob(Session session, String action, String target, String requestId) {
        return beginJob(session, action, target, requestId, null);
    }

    public JobRecord<String> beginJob(Session session, String action, String target, String requestId,
                                      String intentHash) {
        requireJobManager();
        JobManager.StartedJob<String> started = jobManager.createStarted(action,
            session != null ? session.getClientId() : "unknown", target == null ? "" : target, requestId,
            null, null, -1L, intentHash);
        JobRecord<String> job = started.job();
        if (started.identityConflict()) {
            sendError(session, "JOB_IDENTITY_CONFLICT",
                "The job request identity conflicts with a different operation", requestId);
            return null;
        }
        if (started.started() || started.inFlightDuplicate() || started.terminalReplay()) {
            sendJob(session, "jobAccepted", job.snapshot());
        }
        if (!started.started()) {
            if (started.execution() != null) {
                started.execution().complete();
            }
            if (started.disposition() == JobManager.StartDisposition.NOT_STARTED) {
                sendError(session, "JOB_NOT_STARTED", "The job could not be started");
            }
            return null;
        }
        return job;
    }

    public void setJobCancellation(JobRecord<?> job, Runnable cancellation) {
        requireJobManager();
        jobManager.setExecutionCancellation(job, cancellation);
    }

    public void completeJobExecution(JobRecord<?> job) {
        requireJobManager();
        jobManager.completeExecution(job);
    }

    public void succeedJob(JobRecord<String> job, String result, String message) {
        requireJobManager();
        if (job != null && job.markSucceeded(result, message == null || message.isBlank() ? "Succeeded" : message)) {
            jobManager.publish(job);
        }
    }

    public void failJob(JobRecord<String> job, String message, Throwable throwable) {
        requireJobManager();
        if (job != null && job.markFailed(message == null || message.isBlank() ? "Failed" : message, throwable)) {
            jobManager.publish(job);
        }
    }

    public void sendJobSnapshot(Session session, String actorClientId) {
        requireJobManager();
        sendJob(session, "jobSnapshot", jobManager.activeOrRecentSnapshot(actorClientId, 300000));
    }

    public void sendScheduledTaskSnapshot(Session session, List<FlowExecutor.ScheduledTaskSnapshot> snapshots) {
        List<Map<String, Object>> tasks = snapshots != null ? snapshots.stream().map(snapshot -> Map.<String, Object>of(
            "taskId", snapshot.taskId(),
            "kind", "scheduled_flow",
            "runtimeOwner", snapshot.runtimeOwner(),
            "graphId", snapshot.graphId(),
            "createdAt", snapshot.createdAt(),
            "nextFireAt", snapshot.nextFireAt(),
            "recurring", snapshot.recurring(),
            "status", snapshot.state().name().toLowerCase(Locale.ROOT),
            "lastFailure", snapshot.lastFailure()
        )).toList() : List.of();
        sendJob(session, "scheduledTaskSnapshot", tasks);
    }

    public void sendFlowList(Session session, List<String> flowIds) {
        sendStringList(session, resourcePackets("flow").list(), flowIds);
    }

    public void sendGuiList(Session session, List<String> guiIds) {
        sendStringList(session, resourcePackets("gui").list(), guiIds);
    }

    public void sendScoreboardList(Session session, List<String> scoreboardIds) {
        sendStringList(session, resourcePackets("scoreboard").list(), scoreboardIds);
    }

    public void sendTabList(Session session, List<String> tabIds) {
        sendStringList(session, resourcePackets("tab").list(), tabIds);
    }

    public void sendCustomContentList(Session session, List<String> contentIds) {
        sendStringList(session, resourcePackets("custom_content").list(), contentIds);
    }

    public void sendProjectMetadataList(Session session, List<String> metadataIds) {
        sendStringList(session, resourcePackets("project_metadata").list(), metadataIds);
    }

    public void sendJsonResourceList(Session session, byte packetId, List<String> ids) {
        sendStringList(session, packetId, ids);
    }

    public void sendGuiState(Session session, boolean editable, String guiId, String flowId) {
        if (guiId != null && guiId.length() > MAX_STRING_LENGTH) {
            sendError(session, "INVALID_GUI_ID", "GUI ID too long");
            return;
        }
        if (flowId != null && flowId.length() > MAX_STRING_LENGTH) {
            sendError(session, "INVALID_FLOW_ID", "Flow ID too long");
            return;
        }

        byte[] guiBytes = guiId != null ? guiId.getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] flowBytes = flowId != null ? flowId.getBytes(StandardCharsets.UTF_8) : new byte[0];
        ByteBuffer buffer = ByteBuffer.allocate(1 + 1 + 4 + guiBytes.length + 4 + flowBytes.length);
        buffer.put(ReSyncProtocolContract.FLOW_PACKET_GUI_STATE);
        buffer.put((byte) (editable ? 1 : 0));
        buffer.putInt(guiBytes.length);
        buffer.put(guiBytes);
        buffer.putInt(flowBytes.length);
        buffer.put(flowBytes);
        sendRaw(session, buffer.array(), false);
    }

    public void sendEditTargetState(Session session, boolean editable, String type, String resourceId, String flowId) {
        if (type != null && type.length() > MAX_STRING_LENGTH) {
            sendError(session, "INVALID_EDIT_TARGET_TYPE", "Edit target type too long");
            return;
        }
        if (resourceId != null && resourceId.length() > MAX_STRING_LENGTH) {
            sendError(session, "INVALID_EDIT_TARGET_ID", "Edit target ID too long");
            return;
        }
        if (flowId != null && flowId.length() > MAX_STRING_LENGTH) {
            sendError(session, "INVALID_FLOW_ID", "Flow ID too long");
            return;
        }

        byte[] typeBytes = type != null ? type.getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] idBytes = resourceId != null ? resourceId.getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] flowBytes = flowId != null ? flowId.getBytes(StandardCharsets.UTF_8) : new byte[0];
        ByteBuffer buffer = ByteBuffer.allocate(1 + 1 + 4 + typeBytes.length + 4 + idBytes.length + 4 + flowBytes.length);
        buffer.put(ReSyncProtocolContract.FLOW_PACKET_EDIT_TARGET_STATE);
        buffer.put((byte) (editable ? 1 : 0));
        buffer.putInt(typeBytes.length);
        buffer.put(typeBytes);
        buffer.putInt(idBytes.length);
        buffer.put(idBytes);
        buffer.putInt(flowBytes.length);
        buffer.put(flowBytes);
        sendRaw(session, buffer.array(), false);
    }

    public void sendPlaceholderPreview(Session session, int requestId, String rendered) {
        byte[] renderedBytes = rendered.getBytes(StandardCharsets.UTF_8);
        ByteBuffer out = ByteBuffer.allocate(1 + 4 + 4 + renderedBytes.length);
        out.put(ReSyncProtocolContract.FLOW_PACKET_PLACEHOLDER_PREVIEW);
        out.putInt(requestId);
        out.putInt(renderedBytes.length);
        out.put(renderedBytes);
        sendRaw(session, out.array(), false);
    }

    public void sendNodeRegistrySnapshot(Session session, NodeRegistrySnapshot snapshot) {
        if (session == null || snapshot == null) {
            return;
        }
        String json = gson.toJson(snapshot);
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        byte packetId = snapshot.isFullSync() ? ReSyncProtocolContract.FLOW_PACKET_NODE_REGISTRY : ReSyncProtocolContract.FLOW_PACKET_NODE_REGISTRY_DELTA;
        ByteBuffer buffer = ByteBuffer.allocate(1 + jsonBytes.length);
        buffer.put(packetId);
        buffer.put(jsonBytes);
        sendRaw(session, buffer.array(), true);
    }

    public void sendCatalogCachePublication(Session session, CatalogCachePublication publication) {
        if (!sendCatalogCachePublicationAcknowledged(session, publication)) {
            String code = lastCatalogPublicationSendFailureCode;
            if ("CATALOG_PUBLICATION.SEND_ENCODE_FAILED".equals(code)) {
                sendError(session, "CATALOG_PUBLICATION_INVALID", "Catalog publication could not be encoded");
            } else if ("CATALOG_PUBLICATION.SEND_TOO_LARGE".equals(code)) {
                sendError(session, "CATALOG_PUBLICATION_TOO_LARGE", "Catalog publication exceeds maximum size");
            }
        }
    }

    public boolean sendCatalogCachePublicationAcknowledged(Session session, CatalogCachePublication publication) {
        Optional<CatalogPublicationSendPlan> plan = preflightCatalogCachePublication(session, publication);
        return plan.isPresent() && sendPreparedCatalogCachePublication(session, plan.orElseThrow());
    }

    public Optional<CatalogPublicationSendPlan> preflightCatalogCachePublication(Session session,
                                                                                  CatalogCachePublication publication) {
        return preflightCatalogCachePublicationAsync(session, publication).join();
    }

    public Optional<CatalogPublicationSendPlan> preflightCatalogCachePublication(CatalogCachePublication publication) {
        return preflightCatalogCachePublicationAsync(publication).join();
    }

    public CompletableFuture<Optional<CatalogPublicationSendPlan>> preflightCatalogCachePublicationAsync(
        Session session, CatalogCachePublication publication) {
        Set<String> capabilities = sessionCapabilities(session);
        CatalogCachePublication outbound = publication == null ? null : publicationForSession(publication, capabilities);
        int maxFrameBytes = Math.max(0, maxEncodedFrameBytes(session));
        try {
            return CompletableFuture.supplyAsync(() -> preflightCatalogCachePublicationInternal(session, publication,
                outbound, capabilities, maxFrameBytes), CATALOG_ENCODING_EXECUTOR);
        } catch (RejectedExecutionException exception) {
            clearCatalogPublicationSendFailure();
            recordCatalogPublicationSendFailure("CATALOG_PUBLICATION.SEND_BUSY");
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    public CompletableFuture<Optional<CatalogPublicationSendPlan>> preflightCatalogCachePublicationAsync(
        CatalogCachePublication publication) {
        return preflightCatalogCachePublicationAsync(null, publication);
    }

    public CompletableFuture<Optional<CatalogPublicationSendPlan>> encodeCatalogCachePublicationAsync(
        Session session, CatalogCachePublication publication) {
        return preflightCatalogCachePublicationAsync(session, publication);
    }

    public CompletableFuture<Optional<CatalogPublicationSendPlan>> encodeCatalogCachePublicationAsync(
        CatalogCachePublication publication) {
        return preflightCatalogCachePublicationAsync(publication);
    }

    private Optional<CatalogPublicationSendPlan> preflightCatalogCachePublicationInternal(Session session,
                                                                                            CatalogCachePublication cacheIdentity,
                                                                                            CatalogCachePublication publication,
                                                                                            Set<String> capabilities,
                                                                                            int maxFrameBytes) {
        clearCatalogPublicationSendFailure();
        if (publication == null) {
            recordCatalogPublicationSendFailure("CATALOG_PUBLICATION.SEND_INVALID");
            return Optional.empty();
        }
        Set<String> exactCapabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        CatalogPublicationPlanCacheKey cacheKey = new CatalogPublicationPlanCacheKey(cacheIdentity, session,
            exactCapabilities, maxFrameBytes);
        CatalogPublicationFrames cachedFrames = catalogPublicationPlanCache.get(cacheKey);
        int maxFramePayloadBytes = framePayloadBytes((long) maxFrameBytes - FRAME_HEADER_BYTES);
        int maxChunkDataBytes = effectiveCatalogChunkDataBytes(maxFramePayloadBytes);
        int effectiveMaxPublicationBytes = effectiveCatalogPublicationBytes(maxFramePayloadBytes);
        if (cachedFrames != null) {
            return Optional.of(new CatalogPublicationSendPlan(session, publication, null, cachedFrames,
                exactCapabilities, maxFrameBytes, maxChunkDataBytes, effectiveMaxPublicationBytes, session != null));
        }
        synchronized (catalogPublicationPlanCache) {
            cachedFrames = catalogPublicationPlanCache.get(cacheKey);
            if (cachedFrames != null) {
                return Optional.of(new CatalogPublicationSendPlan(session, publication, null, cachedFrames,
                    exactCapabilities, maxFrameBytes, maxChunkDataBytes, effectiveMaxPublicationBytes, session != null));
            }
            byte[] jsonBytes;
            try {
                jsonBytes = catalogPublicationCodec.encodeBytes(publication);
            } catch (RuntimeException exception) {
                recordCatalogPublicationSendFailure("CATALOG_PUBLICATION.SEND_ENCODE_FAILED");
                return Optional.empty();
            }
            if (effectiveMaxPublicationBytes < 1 || jsonBytes.length > effectiveMaxPublicationBytes) {
                recordCatalogPublicationSendFailure("CATALOG_PUBLICATION.SEND_TOO_LARGE");
                return Optional.empty();
            }
            if (maxFramePayloadBytes < 1) {
                recordCatalogPublicationSendFailure("CATALOG_PUBLICATION.SEND_TOO_LARGE");
                return Optional.empty();
            }
            List<byte[]> payloads;
            if (jsonBytes.length + 1 <= Math.min(CatalogPublicationChunkPacket.MAX_CHUNK_BYTES, maxFramePayloadBytes)) {
                ByteBuffer buffer = ByteBuffer.allocate(1 + jsonBytes.length);
                buffer.put(ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION);
                buffer.put(jsonBytes);
                payloads = List.of(buffer.array());
            } else {
                if (maxChunkDataBytes < 1) {
                    recordCatalogPublicationSendFailure("CATALOG_PUBLICATION.SEND_TOO_LARGE");
                    return Optional.empty();
                }
                try {
                    payloads = CatalogPublicationChunkPacket.encodeChunks(jsonBytes, maxChunkDataBytes);
                } catch (RuntimeException exception) {
                    recordCatalogPublicationSendFailure("CATALOG_PUBLICATION.SEND_TOO_LARGE");
                    return Optional.empty();
                }
                if (payloads.isEmpty()) {
                    recordCatalogPublicationSendFailure("CATALOG_PUBLICATION.SEND_FAILED");
                    return Optional.empty();
                }
            }
            if (payloads.stream().anyMatch(payload -> !fitsFramePayload(payload, maxFrameBytes))) {
                recordCatalogPublicationSendFailure("CATALOG_PUBLICATION.SEND_TOO_LARGE");
                return Optional.empty();
            }
            long payloadBytes = payloads.stream().mapToLong(payload -> payload.length).sum();
            int[] payloadLengths = payloads.stream().mapToInt(payload -> payload.length).toArray();
            if (codec == null) {
                CatalogPublicationPayloads immutablePayloads = new CatalogPublicationPayloads(payloads);
                return Optional.of(new CatalogPublicationSendPlan(session, publication, immutablePayloads, null,
                    exactCapabilities, maxFrameBytes, maxChunkDataBytes, effectiveMaxPublicationBytes, session != null));
            }
            List<byte[]> encodedFrames = encodeCatalogFrames(session, payloads, maxFrameBytes);
            if (encodedFrames == null) {
                return Optional.empty();
            }
            CatalogPublicationFrames frames = new CatalogPublicationFrames(encodedFrames, payloadLengths, payloadBytes);
            catalogPublicationPlanCache.put(cacheKey, frames);
            return Optional.of(new CatalogPublicationSendPlan(session, publication, null, frames, exactCapabilities,
                maxFrameBytes, maxChunkDataBytes, effectiveMaxPublicationBytes, session != null));
        }
    }

    private List<byte[]> encodeCatalogFrames(Session session, List<byte[]> payloads, int maxFrameBytes) {
        List<byte[]> frames = new ArrayList<>(payloads.size());
        try {
            for (int index = 0; index < payloads.size(); index++) {
                DataMessage message = new DataMessage();
                message.setChannel(channelId);
                message.setPayload(payloads.get(index));
                byte[] frame = codec.encodeFrame(message, channelId, true);
                if (!fitsEncodedFrame(session, frame) || frame.length > maxFrameBytes) {
                    recordCatalogPublicationSendFailure("CATALOG_PUBLICATION.SEND_TOO_LARGE");
                    return null;
                }
                frames.add(frame);
            }
        } catch (RuntimeException exception) {
            recordCatalogPublicationSendFailure("CATALOG_PUBLICATION.SEND_ENCODE_FAILED");
            return null;
        }
        return List.copyOf(frames);
    }

    public Optional<CatalogPublicationBroadcastPlan> preflightCatalogCachePublicationBroadcast(
        CatalogCachePublication publication) {
        clearCatalogPublicationSendFailure();
        if (publication == null) {
            recordCatalogPublicationSendFailure("CATALOG_PUBLICATION.SEND_INVALID");
            return Optional.empty();
        }
        Map<Session, CatalogPublicationSendPlan> plans = new LinkedHashMap<>();
        String failureCode = null;
        for (Session session : subscribedSessionsSnapshot()) {
            if (session == null) {
                failureCode = "CATALOG_PUBLICATION.SEND_UNAVAILABLE";
                break;
            }
            Optional<CatalogPublicationSendPlan> plan = preflightCatalogCachePublication(session, publication);
            if (plan.isEmpty()) {
                failureCode = lastCatalogPublicationSendFailureCode == null
                    || lastCatalogPublicationSendFailureCode.isBlank()
                    ? "CATALOG_PUBLICATION.SEND_FAILED" : lastCatalogPublicationSendFailureCode;
                break;
            }
            plans.put(session, plan.orElseThrow());
        }
        if (failureCode != null) {
            recordCatalogPublicationSendFailure(failureCode);
            return Optional.empty();
        }
        return Optional.of(new CatalogPublicationBroadcastPlan(plans));
    }

    public List<Session> subscribedSessionsSnapshot() {
        return subscribedSessions == null ? List.of() : List.copyOf(subscribedSessions);
    }

    public boolean sendPreparedCatalogCachePublication(Session session, CatalogPublicationSendPlan plan) {
        if (plan == null) {
            clearCatalogPublicationSendFailure();
            recordCatalogPublicationSendFailure("CATALOG_PUBLICATION.SEND_INVALID");
            return false;
        }
        if (plan.session() != null && plan.session() != session) {
            clearCatalogPublicationSendFailure();
            recordCatalogPublicationSendFailure("CATALOG_PUBLICATION.SEND_SESSION_MISMATCH");
            return false;
        }
        if (!plan.matchesSessionBudget(session, maxEncodedFrameBytes(session), sessionCapabilities(session))) {
            clearCatalogPublicationSendFailure();
            recordCatalogPublicationSendFailure("CATALOG_PUBLICATION.SEND_SESSION_MISMATCH");
            return false;
        }
        return sendPreparedCatalogCachePublicationPlan(session, plan);
    }

    public boolean sendCatalogCachePublicationFrame(Session session, CatalogPublicationSendPlan plan, int frameIndex) {
        CatalogFrameSendResult result = sendCatalogCachePublicationFrameResult(session, plan, frameIndex);
        publishCatalogFrameSendResult(result);
        return result.accepted();
    }

    public CatalogFrameSendResult sendCatalogCachePublicationFrameResult(Session session, CatalogPublicationSendPlan plan,
                                                                         int frameIndex) {
        if (plan == null) {
            return CatalogFrameSendResult.failure("CATALOG_PUBLICATION.SEND_INVALID");
        }
        if (plan.session() != null && plan.session() != session) {
            return CatalogFrameSendResult.failure("CATALOG_PUBLICATION.SEND_SESSION_MISMATCH");
        }
        if (!plan.matchesSessionBudget(session, maxEncodedFrameBytes(session), sessionCapabilities(session))) {
            return CatalogFrameSendResult.failure("CATALOG_PUBLICATION.SEND_SESSION_MISMATCH");
        }
        if (frameIndex < 0 || frameIndex >= plan.frameCount()) {
            return CatalogFrameSendResult.failure("CATALOG_PUBLICATION.SEND_INVALID");
        }
        if (plan.hasEncodedFrames()) {
            if (!plan.claimTransmission()) {
                return CatalogFrameSendResult.failure("CATALOG_PUBLICATION.SEND_PLAN_CONFLICT");
            }
            catalogPublicationPlanCache.remove(plan.frames());
            return sendEncodedCatalogFrameResult(session, plan.frame(frameIndex));
        }
        return sendCatalogFrameResult(session, plan.payload(frameIndex));
    }

    private CatalogFrameSendResult sendEncodedCatalogFrameResult(Session session, byte[] frame) {
        if (!isSendAvailable(session, frame)) {
            return CatalogFrameSendResult.failure("CATALOG_PUBLICATION.SEND_UNAVAILABLE");
        }
        if (!fitsEncodedFrame(session, frame)) {
            return CatalogFrameSendResult.failure("CATALOG_PUBLICATION.SEND_TOO_LARGE");
        }
        try {
            return catalogFrameSendResult(session.getConnection().getFrameSender().trySend(frame));
        } catch (RuntimeException exception) {
            return CatalogFrameSendResult.failure("CATALOG_PUBLICATION.SEND_FAILED");
        }
    }

    public boolean sendCatalogPublicationFrame(Session session, CatalogPublicationSendPlan plan, int frameIndex) {
        return sendCatalogCachePublicationFrame(session, plan, frameIndex);
    }

    private boolean sendPreparedCatalogCachePublicationPlan(Session session, CatalogPublicationSendPlan plan) {
        clearCatalogPublicationSendFailure();
        for (int frameIndex = 0; frameIndex < plan.frameCount(); frameIndex++) {
            CatalogFrameSendResult result = sendCatalogCachePublicationFrameResult(session, plan, frameIndex);
            if (!result.accepted()) {
                publishCatalogFrameSendResult(result);
                return false;
            }
        }
        lastCatalogPublicationSendFailureCode = "";
        return true;
    }

    protected boolean sendCatalogFrameAcknowledged(Session session, byte[] payload) {
        CatalogFrameSendResult result = sendCatalogFrameResult(session, payload);
        publishCatalogFrameSendResult(result);
        return result.accepted();
    }

    protected CatalogFrameSendResult sendCatalogFrameResult(Session session, byte[] payload) {
        if (!isSendAvailable(session, payload)) {
            return CatalogFrameSendResult.failure("CATALOG_PUBLICATION.SEND_UNAVAILABLE");
        }
        if (!fitsFramePayload(session, payload)) {
            return CatalogFrameSendResult.failure("CATALOG_PUBLICATION.SEND_TOO_LARGE");
        }
        DataMessage message = new DataMessage();
        message.setChannel(channelId);
        message.setPayload(payload);
        try {
            byte[] frame = codec.encodeFrame(message, channelId, false);
            if (!fitsEncodedFrame(session, frame)) {
                return CatalogFrameSendResult.failure("CATALOG_PUBLICATION.SEND_TOO_LARGE");
            }
            return catalogFrameSendResult(session.getConnection().getFrameSender().trySend(frame));
        } catch (Codec.FrameTooLargeException exception) {
            return CatalogFrameSendResult.failure("CATALOG_PUBLICATION.SEND_TOO_LARGE");
        } catch (RuntimeException exception) {
            return CatalogFrameSendResult.failure("CATALOG_PUBLICATION.SEND_FAILED");
        }
    }

    private CatalogFrameSendResult catalogFrameSendResult(FrameSender.SendResult result) {
        return result == FrameSender.SendResult.ACCEPTED
            ? CatalogFrameSendResult.success()
            : CatalogFrameSendResult.failure(result == FrameSender.SendResult.BACKPRESSURED
                ? "CATALOG_PUBLICATION.SEND_BACKPRESSURE" : "CATALOG_PUBLICATION.SEND_UNAVAILABLE");
    }

    private void publishCatalogFrameSendResult(CatalogFrameSendResult result) {
        if (result.accepted()) {
            clearCatalogPublicationSendFailure();
        } else {
            recordCatalogPublicationSendFailure(result.failureCode());
        }
    }

    public void sendOptionCatalog(Session session, String sourceId, List<String> values, String revision) {
        sendOptionCatalog(session, sourceId, values, List.of(), revision);
    }

    public void sendOptionCatalog(Session session, String sourceId, List<String> values, List<OptionCatalogItem> items, String revision) {
        sendOptionCatalog(session, sourceId, values, items, revision, 0L);
    }

    public void sendOptionCatalog(Session session, String sourceId, List<String> values, List<OptionCatalogItem> items, String revision, long sequence) {
        sendOptionCatalog(session, sourceId, "", values, items, revision, sequence);
    }

    public void sendOptionCatalog(Session session, String sourceId, String contextKey, List<String> values, List<OptionCatalogItem> items, String revision, long sequence) {
        sendOptionCatalog(session, sourceId, contextKey, values, items, revision, sequence, "available", "");
    }

    public void sendOptionCatalog(Session session, String sourceId, String contextKey, List<String> values, List<OptionCatalogItem> items, String revision, long sequence,
                                  String status, String diagnostic) {
        String json = gson.toJson(new OptionCatalogSnapshot(sourceId, contextKey, revision, sequence, values, items, status, diagnostic));
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(1 + jsonBytes.length);
        buffer.put(ReSyncProtocolContract.FLOW_PACKET_OPTION_CATALOG);
        buffer.put(jsonBytes);
        sendRaw(session, buffer.array(), true);
    }

    public void broadcastOptionCatalog(String sourceId, List<String> values, List<OptionCatalogItem> items, String revision) {
        broadcastOptionCatalog(sourceId, values, items, revision, 0L);
    }

    public void broadcastOptionCatalog(String sourceId, List<String> values, List<OptionCatalogItem> items, String revision, long sequence) {
        broadcastOptionCatalog(sourceId, values, items, revision, sequence, "available", "");
    }

    public void broadcastOptionCatalog(String sourceId, List<String> values, List<OptionCatalogItem> items, String revision, long sequence, String status, String diagnostic) {
        for (Session session : subscribedSessions) {
            sendOptionCatalog(session, sourceId, "", values, items, revision, sequence, status, diagnostic);
        }
    }

    public void sendTraceSnapshot(Session session, List<FlowTraceRecord> records) {
        sendTracePacket(session, ReSyncProtocolContract.FLOW_PACKET_TRACE_SNAPSHOT, records == null ? List.of() : records);
    }

    public void sendTraceEvent(Session session, FlowTraceRecord record) {
        sendTracePacket(session, ReSyncProtocolContract.FLOW_PACKET_TRACE_EVENT, record);
    }

    public void sendDebugSnapshot(Session session, Object payload) {
        sendTracePacket(session, ReSyncProtocolContract.FLOW_PACKET_DEBUG_EVENT, payload);
    }

    private void sendTracePacket(Session session, byte packetId, Object payload) {
        String json = gson.toJson(payload);
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(1 + jsonBytes.length);
        buffer.put(packetId);
        buffer.put(jsonBytes);
        sendRaw(session, buffer.array(), true);
    }

    public void broadcastNodeRegistry(NodeRegistrySnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        for (Session session : subscribedSessions) {
            sendNodeRegistrySnapshot(session, snapshot);
        }
    }

    public void broadcastCatalogCachePublication(CatalogCachePublication publication) {
        broadcastCatalogCachePublicationAcknowledged(publication);
    }

    public boolean broadcastCatalogCachePublicationAcknowledged(CatalogCachePublication publication) {
        clearCatalogPublicationSendFailure();
        if (publication == null) {
            recordCatalogPublicationSendFailure("CATALOG_PUBLICATION.SEND_INVALID");
            return false;
        }
        String failureCode = null;
        for (Session session : subscribedSessions) {
            clearCatalogPublicationSendFailure();
            if (!sendCatalogCachePublicationAcknowledged(session, publication)) {
                String targetFailureCode = lastCatalogPublicationSendFailureCode;
                if (targetFailureCode == null || targetFailureCode.isBlank()) {
                    targetFailureCode = "CATALOG_PUBLICATION.SEND_FAILED";
                    recordCatalogPublicationSendFailure(targetFailureCode);
                }
                if (failureCode == null) {
                    failureCode = targetFailureCode;
                }
            }
        }
        if (failureCode != null && !failureCode.isBlank()) {
            recordCatalogPublicationSendFailure(failureCode);
            return false;
        }
        return true;
    }

    public boolean supportsCatalogAuthoring(Session session) {
        return supportsCatalogAuthoring(sessionCapabilities(session));
    }

    private CatalogCachePublication publicationForSession(CatalogCachePublication publication, Set<String> capabilities) {
        return publication.hasAuthoringPublication() && !supportsCatalogAuthoring(capabilities)
            ? publication.withAuthoringPublication(null) : publication;
    }

    private Set<String> sessionCapabilities(Session session) {
        if (session == null || session.getConnection() == null || session.getConnection().getClientCapabilities() == null) {
            return Set.of();
        }
        return Set.copyOf(session.getConnection().getClientCapabilities());
    }

    private boolean supportsCatalogAuthoring(Set<String> capabilities) {
        return capabilities != null && capabilities.contains(CATALOG_AUTHORING_CAPABILITY);
    }

    private void broadcastJob(String action, Object data) {
        for (Session session : subscribedSessions) {
            sendJob(session, action, data);
        }
    }

    private void sendJob(Session session, String action, Object data) {
        String json = gson.toJson(Map.of(
            "type", "job",
            "action", action,
            "data", data == null ? Map.of() : data,
            "timestamp", System.currentTimeMillis()
        ));
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(1 + jsonBytes.length);
        buffer.put(ReSyncProtocolContract.FLOW_PACKET_JOB);
        buffer.put(jsonBytes);
        sendRaw(session, buffer.array(), false);
    }

    public void sendError(Session session, String errorCode, String message) {
        sendError(session, errorCode, message, null);
    }

    public void sendError(Session session, String errorCode, String message, String requestId) {
        String safeCode = errorCode == null || errorCode.isBlank() ? "FLOW_ERROR" : errorCode;
        String safeMessage = message == null ? "" : message;
        String payload = safeMessage;
        if (requestId != null && !requestId.isBlank()) {
            Map<String, String> diagnostic = new LinkedHashMap<>();
            diagnostic.put("requestId", requestId);
            diagnostic.put("errorCode", safeCode);
            diagnostic.put("message", safeMessage);
            payload = gson.toJson(diagnostic);
        }
        byte[] errorBytes = payload.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + errorBytes.length);
        buffer.put(ReSyncProtocolContract.FLOW_PACKET_ERROR);
        buffer.putInt(errorBytes.length);
        buffer.put(errorBytes);
        sendRaw(session, buffer.array(), false);
        Log.warn("Error sent to " + session.getClientId() + ": " + safeCode + " - " + safeMessage);
    }

    public void sendEditorError(Session session, EditorError error) {
        String payload = EditorError.PREFIX + gson.toJson(error);
        byte[] errorBytes = payload.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + errorBytes.length);
        buffer.put(ReSyncProtocolContract.FLOW_PACKET_ERROR);
        buffer.putInt(errorBytes.length);
        buffer.put(errorBytes);
        sendRaw(session, buffer.array(), false);
        Log.warn("Editor issue sent to " + session.getClientId() + ": " + error.title() + " - " + error.diagnostics().size() + " issue"
            + (error.diagnostics().size() == 1 ? "" : "s"));
    }

    public void sendPresenceSnapshot(Session session, String json) {
        sendJsonPacket(session, ReSyncProtocolContract.FLOW_PACKET_PRESENCE_SNAPSHOT, json, "PRESENCE_TOO_LARGE", "Presence snapshot exceeds maximum size");
    }

    public void sendCollaborationMessage(Session session, String json) {
        sendJsonPacket(session, ReSyncProtocolContract.FLOW_PACKET_COLLABORATION_CHAT, json, "CHAT_TOO_LARGE", "Chat message exceeds maximum size");
    }

    public void sendResourceChanged(Session session, String json) {
        sendJsonPacket(session, ReSyncProtocolContract.FLOW_PACKET_RESOURCE_CHANGED, json, "RESOURCE_EVENT_TOO_LARGE", "Resource event exceeds maximum size");
    }

    public void sendResourceDeleted(Session session, String json) {
        sendJsonPacket(session, ReSyncProtocolContract.FLOW_PACKET_RESOURCE_DELETED, json, "RESOURCE_EVENT_TOO_LARGE", "Resource event exceeds maximum size");
    }

    public void sendWorkspaceSnapshot(Session session, String json) {
        sendJsonPacket(session, ReSyncProtocolContract.FLOW_PACKET_WORKSPACE_SNAPSHOT, json, "WORKSPACE_TOO_LARGE", "Workspace snapshot exceeds maximum size");
    }

    public void sendWorkspaceOperation(Session session, String json) {
        sendJsonPacket(session, ReSyncProtocolContract.FLOW_PACKET_WORKSPACE_OPERATION, json, "WORKSPACE_TOO_LARGE", "Workspace operation exceeds maximum size");
    }

    public void sendWorkspaceAwareness(Session session, String json) {
        sendJsonPacket(session, ReSyncProtocolContract.FLOW_PACKET_WORKSPACE_AWARENESS, json, "WORKSPACE_TOO_LARGE", "Workspace awareness exceeds maximum size");
    }

    public void sendWorkspaceResync(Session session, String json) {
        sendJsonPacket(session, ReSyncProtocolContract.FLOW_PACKET_WORKSPACE_RESYNC, json, "WORKSPACE_TOO_LARGE", "Workspace resync exceeds maximum size");
    }

    private void sendJsonPacket(Session session, byte packetId, String json, String errorCode, String errorMessage) {
        String outboundJson;
        try {
            outboundJson = requiredAuthorityEpoch().withCurrentEpoch(json);
        } catch (RuntimeException exception) {
            sendError(session, errorCode, errorMessage);
            return;
        }
        byte[] jsonBytes = (outboundJson == null ? "" : outboundJson).getBytes(StandardCharsets.UTF_8);
        if (jsonBytes.length > MAX_PACKET_SIZE) {
            sendError(session, errorCode, errorMessage);
            return;
        }
        ByteBuffer buffer = ByteBuffer.allocate(1 + jsonBytes.length);
        buffer.put(packetId);
        buffer.put(jsonBytes);
        sendRaw(session, buffer.array(), false);
    }

    private void sendIdAck(Session session, byte packetId, String id) {
        sendIdAck(session, packetId, id, "", 0L, "");
    }

    private ReSyncProtocolContract.ResourceFlowPackets resourcePackets(String typeId) {
        return protocolAuthority.requireFlowPackets(typeId);
    }

    private void requireJobManager() {
        if (jobManager == null) {
            throw new IllegalStateException("Flow Job Registry Is Required");
        }
    }

    private void sendIdAck(Session session, byte packetId, String id, String requestId) {
        if (requestId == null || requestId.isBlank()) {
            sendIdAck(session, packetId, id, "", 0L, "");
            return;
        }
        sendIdAck(session, packetId, id, requestId, 0L, "");
    }

    private void sendIdAck(Session session, byte packetId, String id, String requestId, long revision, String hash) {
        if (id == null) {
            return;
        }
        byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);
        byte[] requestIdBytes = requestId != null ? requestId.getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] hashBytes = hash != null ? hash.getBytes(StandardCharsets.UTF_8) : new byte[0];
        ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + idBytes.length + 4 + requestIdBytes.length + Long.BYTES + 4 + hashBytes.length + Long.BYTES);
        buffer.put(packetId);
        buffer.putInt(idBytes.length);
        buffer.put(idBytes);
        buffer.putInt(requestIdBytes.length);
        buffer.put(requestIdBytes);
        buffer.putLong(Math.max(0L, revision));
        buffer.putInt(hashBytes.length);
        buffer.put(hashBytes);
        buffer.putLong(requiredAuthorityEpoch().current());
        sendRaw(session, buffer.array(), false);
    }

    private AuthorityEpoch requiredAuthorityEpoch() {
        return Objects.requireNonNull(authorityEpoch, "Authority epoch is not bound");
    }

    private void sendStringList(Session session, byte packetId, List<String> ids) {
        List<String> values = ids != null ? ids : List.of();
        int totalBytes = 1 + 4;
        for (String id : values) {
            totalBytes += 4 + id.getBytes(StandardCharsets.UTF_8).length;
        }
        ByteBuffer buffer = ByteBuffer.allocate(totalBytes);
        buffer.put(packetId);
        buffer.putInt(values.size());
        for (String id : values) {
            byte[] bytes = id.getBytes(StandardCharsets.UTF_8);
            buffer.putInt(bytes.length);
            buffer.put(bytes);
        }
        sendRaw(session, buffer.array(), false);
    }

    public void sendRaw(Session session, byte[] payload, boolean compress) {
        sendRawAcknowledged(session, payload, compress);
    }

    public boolean sendRawAcknowledged(Session session, byte[] payload, boolean compress) {
        clearCatalogPublicationSendFailure();
        try {
            if (!isSendAvailable(session, payload)) {
                recordCatalogPublicationSendFailure("CATALOG_PUBLICATION.SEND_UNAVAILABLE");
                return false;
            }
            DataMessage msg = new DataMessage();
            msg.setChannel(channelId);
            msg.setPayload(payload);
            codec.sendMessage(session.getConnection().getFrameSender(), msg, channelId, compress);
            return true;
        } catch (RuntimeException exception) {
            recordCatalogPublicationSendFailure("CATALOG_PUBLICATION.SEND_FAILED");
            return false;
        }
    }

    public Optional<String> lastCatalogPublicationSendFailureCode() {
        return lastCatalogPublicationSendFailureCode.isBlank()
            ? Optional.empty()
            : Optional.of(lastCatalogPublicationSendFailureCode);
    }

    private boolean isSendAvailable(Session session, byte[] payload) {
        if (session == null || payload == null || codec == null) {
            return false;
        }
        ConnectionInfo connection = session.getConnection();
        if (connection == null || !connection.isOpen() || connection.getFrameSender() == null) {
            return false;
        }
        ConnectionState state = connection.getState();
        return state != null && state != ConnectionState.CLOSING && state != ConnectionState.CLOSED
            && state != ConnectionState.TIMED_OUT;
    }

    public int effectiveCatalogChunkDataBytes() {
        int maxFramePayloadBytes = maxCatalogFramePayloadBytesForAllSessions();
        return effectiveCatalogChunkDataBytes(maxFramePayloadBytes);
    }

    public int effectiveCatalogChunkDataBytes(Session session) {
        return effectiveCatalogChunkDataBytes(maxCatalogFramePayloadBytes(session));
    }

    private int effectiveCatalogChunkDataBytes(int maxFramePayloadBytes) {
        if (maxFramePayloadBytes <= CatalogPublicationChunkPacket.HEADER_BYTES) {
            return 0;
        }
        return Math.min(CatalogPublicationChunkPacket.MAX_CHUNK_BYTES,
            maxFramePayloadBytes - CatalogPublicationChunkPacket.HEADER_BYTES);
    }

    public int effectiveCatalogPublicationBytes() {
        long effective = (long) effectiveCatalogChunkDataBytes() * CatalogPublicationChunkPacket.MAX_CHUNKS;
        return (int) Math.min(CatalogPublicationChunkPacket.MAX_PUBLICATION_BYTES, effective);
    }

    public int effectiveCatalogPublicationBytes(Session session) {
        long effective = (long) effectiveCatalogChunkDataBytes(session) * CatalogPublicationChunkPacket.MAX_CHUNKS;
        return (int) Math.min(CatalogPublicationChunkPacket.MAX_PUBLICATION_BYTES, effective);
    }

    public int effectiveMaxChunkDataBytes() {
        return effectiveCatalogChunkDataBytes();
    }

    public int effectiveMaxPublicationBytes() {
        return effectiveCatalogPublicationBytes();
    }

    private int maxCatalogFramePayloadBytes(Session session) {
        long payloadBytes = (long) maxEncodedFrameBytes(session) - FRAME_HEADER_BYTES;
        return framePayloadBytes(payloadBytes);
    }

    private int maxCatalogFramePayloadBytesForAllSessions() {
        long payloadBytes = (long) maxEncodedFrameBytesForAllSessions() - FRAME_HEADER_BYTES;
        return framePayloadBytes(payloadBytes);
    }

    private int framePayloadBytes(long payloadBytes) {
        return payloadBytes <= 0 ? 0 : payloadBytes >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) payloadBytes;
    }

    private int effectiveCatalogPublicationBytes(int maxFramePayloadBytes) {
        long effective = (long) effectiveCatalogChunkDataBytes(maxFramePayloadBytes)
            * CatalogPublicationChunkPacket.MAX_CHUNKS;
        return (int) Math.min(CatalogPublicationChunkPacket.MAX_PUBLICATION_BYTES, effective);
    }

    private int maxEncodedFrameBytes(Session session) {
        int max = codec == null ? Integer.MAX_VALUE : codec.getMaxEncodedFrameBytes();
        if (session == null || session.getConnection() == null || session.getConnection().getFrameSender() == null) {
            return max;
        }
        return Math.min(max, session.getConnection().getMaxEncodedFrameBytes());
    }

    private int maxEncodedFrameBytesForAllSessions() {
        int max = codec == null ? Integer.MAX_VALUE : codec.getMaxEncodedFrameBytes();
        for (Session session : subscribedSessionsSnapshot()) {
            if (session == null || session.getConnection() == null || session.getConnection().getFrameSender() == null) {
                continue;
            }
            max = Math.min(max, session.getConnection().getMaxEncodedFrameBytes());
        }
        return max;
    }

    private boolean fitsFramePayload(Session session, byte[] payload) {
        return fitsFramePayload(payload, maxEncodedFrameBytes(session));
    }

    private boolean fitsFramePayload(byte[] payload, int maxFrameBytes) {
        return payload != null && (long) payload.length + FRAME_HEADER_BYTES <= maxFrameBytes;
    }

    private boolean fitsEncodedFrame(Session session, byte[] frame) {
        return frame != null && frame.length <= maxEncodedFrameBytes(session);
    }

    private void recordCatalogPublicationSendFailure(String code) {
        lastCatalogPublicationSendFailureCode = code == null || code.isBlank()
            ? "CATALOG_PUBLICATION.SEND_FAILED"
            : code;
    }

    private void clearCatalogPublicationSendFailure() {
        lastCatalogPublicationSendFailureCode = "";
    }

    public void clearCatalogPublicationPlanCache() {
        catalogPublicationPlanCache.clear();
    }

    public int catalogPublicationPlanCacheEntryCount() {
        return catalogPublicationPlanCache.entryCount();
    }

    public long catalogPublicationPlanCacheBytes() {
        return catalogPublicationPlanCache.byteCount();
    }

    public record CatalogFrameSendResult(boolean accepted, String failureCode) {
        public CatalogFrameSendResult {
            failureCode = failureCode == null ? "" : failureCode;
            if (accepted == !failureCode.isBlank()) {
                throw new IllegalArgumentException("Catalog frame send result is inconsistent");
            }
        }

        public static CatalogFrameSendResult success() {
            return new CatalogFrameSendResult(true, "");
        }

        public static CatalogFrameSendResult failure(String failureCode) {
            return new CatalogFrameSendResult(false, failureCode);
        }
    }

    public static final class CatalogPublicationSendPlan {
        private final Session session;
        private final CatalogCachePublication publication;
        private final CatalogPublicationPayloads payloads;
        private final CatalogPublicationFrames frames;
        private final Set<String> capabilities;
        private final int maxEncodedFrameBytes;
        private final int maxChunkDataBytes;
        private final int maxPublicationBytes;
        private final boolean contextBound;

        public CatalogPublicationSendPlan(CatalogCachePublication publication, List<byte[]> payloads) {
            this(null, publication, new CatalogPublicationPayloads(payloads), null, Set.of(), Integer.MAX_VALUE,
                CatalogPublicationChunkPacket.MAX_CHUNK_BYTES, CatalogPublicationChunkPacket.MAX_PUBLICATION_BYTES,
                false);
        }

        public CatalogPublicationSendPlan(Session session, CatalogCachePublication publication, List<byte[]> payloads) {
            this(session, publication, new CatalogPublicationPayloads(payloads), null, Set.of(), Integer.MAX_VALUE,
                CatalogPublicationChunkPacket.MAX_CHUNK_BYTES, CatalogPublicationChunkPacket.MAX_PUBLICATION_BYTES,
                false);
        }

        private CatalogPublicationSendPlan(Session session, CatalogCachePublication publication,
                                           CatalogPublicationPayloads payloads, CatalogPublicationFrames frames,
                                           Set<String> capabilities,
                                           int maxEncodedFrameBytes, int maxChunkDataBytes,
                                           int maxPublicationBytes, boolean contextBound) {
            this.session = session;
            this.publication = Objects.requireNonNull(publication, "Catalog publication is required");
            if (payloads == null && frames == null || payloads != null && frames != null) {
                throw new IllegalArgumentException("Catalog publication plan requires payloads or encoded frames");
            }
            this.payloads = payloads;
            this.frames = frames;
            this.capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
            this.maxEncodedFrameBytes = maxEncodedFrameBytes;
            this.maxChunkDataBytes = maxChunkDataBytes;
            this.maxPublicationBytes = maxPublicationBytes;
            this.contextBound = contextBound;
        }

        public Session session() {
            return session;
        }

        public CatalogCachePublication publication() {
            return publication;
        }

        public List<byte[]> payloads() {
            if (payloads == null) {
                throw new IllegalStateException("Prepared catalog publication plans retain encoded frames only");
            }
            return payloads.copies();
        }

        public byte[] payload(int frameIndex) {
            if (payloads == null) {
                throw new IllegalStateException("Prepared catalog publication plans retain encoded frames only");
            }
            return payloads.copyAt(frameIndex);
        }

        public int frameBytes(int frameIndex) {
            return frames == null ? payloads.sizeAt(frameIndex) : frames.sizeAt(frameIndex);
        }

        private boolean hasEncodedFrames() {
            return frames != null;
        }

        private CatalogPublicationFrames frames() {
            return frames;
        }

        private boolean claimTransmission() {
            return frames == null || frames.claim(this);
        }

        public byte[] frame(int frameIndex) {
            return frames == null ? payloads.copyAt(frameIndex) : frames.copyAt(frameIndex);
        }

        public int frameCount() {
            return frames == null ? payloads.size() : frames.size();
        }

        public long payloadBytes() {
            return frames == null ? payloads.bytes() : frames.payloadBytes();
        }

        public long retainedBytes() {
            long preparedBytes = frames == null ? payloads.retainedBytes() : frames.retainedBytes();
            long capabilityBytes = capabilities.stream().mapToLong(value -> alignedBytes(value.length() * 2L)).sum();
            return Math.addExact(Math.addExact(payloadBytes(), preparedBytes), Math.addExact(256L, capabilityBytes));
        }

        public Set<String> capabilities() {
            return capabilities;
        }

        public int maxEncodedFrameBytes() {
            return maxEncodedFrameBytes;
        }

        public int maxChunkDataBytes() {
            return maxChunkDataBytes;
        }

        public int maxPublicationBytes() {
            return maxPublicationBytes;
        }

        private boolean matchesSessionBudget(Session candidate, int frameBudget, Set<String> candidateCapabilities) {
            if (session != null && session != candidate) {
                return false;
            }
            if (!contextBound) {
                return true;
            }
            return maxEncodedFrameBytes == frameBudget && capabilities.equals(candidateCapabilities);
        }

    }

    private static final class CatalogPublicationPayloads {
        private final List<byte[]> values;
        private final long bytes;

        private CatalogPublicationPayloads(List<byte[]> payloads) {
            if (payloads == null || payloads.isEmpty()) {
                throw new IllegalArgumentException("Catalog publication payloads are required");
            }
            List<byte[]> copy = new ArrayList<>(payloads.size());
            long total = 0L;
            for (byte[] payload : payloads) {
                if (payload == null || payload.length == 0) {
                    throw new IllegalArgumentException("Catalog publication payload is invalid");
                }
                copy.add(payload.clone());
                total = Math.addExact(total, payload.length);
            }
            values = List.copyOf(copy);
            bytes = total;
        }

        private int size() {
            return values.size();
        }

        private long bytes() {
            return bytes;
        }

        private byte[] copyAt(int index) {
            return values.get(index).clone();
        }

        private int sizeAt(int index) {
            return values.get(index).length;
        }

        private long retainedBytes() {
            return retainedArrayBytes(values);
        }

        private List<byte[]> copies() {
            List<byte[]> copy = new ArrayList<>(values.size());
            for (byte[] value : values) {
                copy.add(value.clone());
            }
            return List.copyOf(copy);
        }
    }

    private static final class CatalogPublicationFrames {
        private final List<byte[]> values;
        private final long payloadBytes;
        private final long retainedBytes;
        private CatalogPublicationSendPlan transmissionOwner;

        private CatalogPublicationFrames(List<byte[]> frames, int[] payloadLengths, long payloadBytes) {
            if (frames == null || frames.isEmpty() || payloadLengths == null || frames.size() != payloadLengths.length
                || payloadBytes < 1L) {
                throw new IllegalArgumentException("Catalog publication encoded frames are invalid");
            }
            long exactPayloadBytes = 0L;
            for (int index = 0; index < frames.size(); index++) {
                if (frames.get(index) == null || frames.get(index).length == 0 || payloadLengths[index] < 1) {
                    throw new IllegalArgumentException("Catalog publication encoded frame is invalid");
                }
                exactPayloadBytes = Math.addExact(exactPayloadBytes, payloadLengths[index]);
            }
            if (exactPayloadBytes != payloadBytes) {
                throw new IllegalArgumentException("Catalog publication payload byte count is invalid");
            }
            this.values = List.copyOf(frames);
            this.payloadBytes = payloadBytes;
            this.retainedBytes = retainedArrayBytes(values);
        }

        private int size() {
            return values.size();
        }

        private int sizeAt(int index) {
            return values.get(index).length;
        }

        private byte[] copyAt(int index) {
            return values.get(index).clone();
        }

        private long payloadBytes() {
            return payloadBytes;
        }

        private long retainedBytes() {
            return retainedBytes;
        }

        private synchronized boolean claim(CatalogPublicationSendPlan candidate) {
            if (transmissionOwner == null) {
                transmissionOwner = candidate;
            }
            return transmissionOwner == candidate;
        }
    }

    private static final class CatalogPublicationPlanCacheKey {
        private final WeakReference<CatalogCachePublication> publication;
        private final WeakReference<Session> session;
        private final boolean sessionBound;
        private final List<String> capabilities;
        private final int maxEncodedFrameBytes;
        private final long retainedBytes;

        private CatalogPublicationPlanCacheKey(CatalogCachePublication publication, Session session,
                                               Set<String> capabilities, int maxEncodedFrameBytes) {
            this.publication = new WeakReference<>(Objects.requireNonNull(publication,
                "Catalog publication is required"));
            this.session = new WeakReference<>(session);
            this.sessionBound = session != null;
            this.capabilities = capabilities == null ? List.of() : capabilities.stream().sorted().toList();
            this.maxEncodedFrameBytes = maxEncodedFrameBytes;
            if (maxEncodedFrameBytes < 0) {
                throw new IllegalArgumentException("Catalog publication frame budget is invalid");
            }
            long capabilityBytes = this.capabilities.stream().mapToLong(value -> alignedBytes(value.length() * 2L)).sum();
            this.retainedBytes = Math.addExact(128L + alignedBytes(Long.BYTES * (long) this.capabilities.size()),
                capabilityBytes);
        }

        private boolean matches(CatalogPublicationPlanCacheKey other) {
            CatalogCachePublication publicationValue = publication.get();
            CatalogCachePublication otherPublication = other.publication.get();
            Session sessionValue = session.get();
            Session otherSession = other.session.get();
            return publicationValue != null && publicationValue == otherPublication
                && sessionBound == other.sessionBound
                && (!sessionBound || sessionValue != null && sessionValue == otherSession)
                && maxEncodedFrameBytes == other.maxEncodedFrameBytes
                && capabilities.equals(other.capabilities);
        }

        private boolean live() {
            return publication.get() != null && (!sessionBound || session.get() != null);
        }

        private long retainedBytes() {
            return retainedBytes;
        }
    }

    private static final class CatalogPublicationPlanCache {
        private final int maxEntries;
        private final long maxBytes;
        private final LinkedHashMap<CatalogPublicationPlanCacheKey, CatalogPublicationFrames> entries =
            new LinkedHashMap<>(16, 0.75f, true);
        private long bytes;

        private CatalogPublicationPlanCache(int maxEntries, long maxBytes) {
            if (maxEntries < 1 || maxBytes < 1L) {
                throw new IllegalArgumentException("Catalog publication plan cache bounds are invalid");
            }
            this.maxEntries = maxEntries;
            this.maxBytes = maxBytes;
        }

        private synchronized CatalogPublicationFrames get(CatalogPublicationPlanCacheKey key) {
            removeStale();
            CatalogPublicationPlanCacheKey match = null;
            for (CatalogPublicationPlanCacheKey existing : entries.keySet()) {
                if (existing.matches(key)) {
                    match = existing;
                    break;
                }
            }
            return match == null ? null : entries.get(match);
        }

        private synchronized void put(CatalogPublicationPlanCacheKey key, CatalogPublicationFrames value) {
            if (key == null || value == null) {
                return;
            }
            removeStale();
            long weight = Math.addExact(key.retainedBytes(), value.retainedBytes());
            for (CatalogPublicationPlanCacheKey existing : entries.keySet()) {
                if (existing.matches(key)) {
                    return;
                }
            }
            if (weight > maxBytes) {
                return;
            }
            entries.put(key, value);
            bytes += weight;
            while (entries.size() > maxEntries || bytes > maxBytes) {
                Map.Entry<CatalogPublicationPlanCacheKey, CatalogPublicationFrames> eldest =
                    entries.entrySet().iterator().next();
                entries.remove(eldest.getKey());
                bytes -= retainedBytes(eldest.getKey(), eldest.getValue());
            }
        }

        private void removeStale() {
            var iterator = entries.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<CatalogPublicationPlanCacheKey, CatalogPublicationFrames> entry = iterator.next();
                if (!entry.getKey().live()) {
                    bytes -= retainedBytes(entry.getKey(), entry.getValue());
                    iterator.remove();
                }
            }
        }

        private static long retainedBytes(CatalogPublicationPlanCacheKey key, CatalogPublicationFrames value) {
            return Math.addExact(key.retainedBytes(), value.retainedBytes());
        }

        private synchronized void clear() {
            entries.clear();
            bytes = 0L;
        }

        private synchronized void remove(CatalogPublicationFrames value) {
            if (value == null) {
                return;
            }
            var iterator = entries.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<CatalogPublicationPlanCacheKey, CatalogPublicationFrames> entry = iterator.next();
                if (entry.getValue() == value) {
                    bytes -= retainedBytes(entry.getKey(), entry.getValue());
                    iterator.remove();
                }
            }
        }

        private synchronized int entryCount() {
            removeStale();
            return entries.size();
        }

        private synchronized long byteCount() {
            removeStale();
            return bytes;
        }
    }

    private static long retainedArrayBytes(List<byte[]> values) {
        long bytes = Math.addExact(64L, alignedBytes(Long.BYTES * (long) values.size()));
        for (byte[] value : values) {
            bytes = Math.addExact(bytes, alignedBytes(value.length));
        }
        return bytes;
    }

    private static long alignedBytes(long contentBytes) {
        return Math.addExact(16L, Math.addExact(contentBytes, 7L) & ~7L);
    }

    public record CatalogPublicationBroadcastPlan(Map<Session, CatalogPublicationSendPlan> plans) {
        public CatalogPublicationBroadcastPlan {
            if (plans == null) {
                throw new IllegalArgumentException("Catalog publication broadcast plans are required");
            }
            Map<Session, CatalogPublicationSendPlan> copy = new LinkedHashMap<>();
            for (Map.Entry<Session, CatalogPublicationSendPlan> entry : plans.entrySet()) {
                copy.put(Objects.requireNonNull(entry.getKey(), "Catalog publication broadcast session is required"),
                    Objects.requireNonNull(entry.getValue(), "Catalog publication broadcast plan is required"));
            }
            plans = Map.copyOf(copy);
        }

        @Override
        public Map<Session, CatalogPublicationSendPlan> plans() {
            return Map.copyOf(plans);
        }
    }

}
