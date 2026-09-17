package restudio.resync.customcontent;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import restudio.flow.data.CustomContentDefinition;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.Log;
import restudio.resync.flow.CompiledGraphMetadataProvider;
import restudio.resync.flow.CompiledTriggerExecution;
import restudio.resync.flow.CoreGraphStorageBoundary;
import restudio.resync.flow.ServerCompiledPlanRepository;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.modules.flow.FlowResourceMutationStamp;
import restudio.resync.server.CoreGraphResourceAuthority;
import restudio.resync.server.TemporaryLifecycleDiagnostics;
import restudio.resync.storage.AssetTransactionCoordinator;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class CustomContentExecution implements CoreGraphResourceAuthority, AutoCloseable {
    private static final String TYPE = "custom_content";
    private final CustomContentStorage storage;
    private final CoreGraphResourceAuthority graphs;
    private final CompiledGraphMetadataProvider metadata;
    private final ServerId serverId;
    private final CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
    private final Map<ServerResourceLocator, Projection> projections = new ConcurrentHashMap<>();
    private final AssetTransactionCoordinator.ListenerRegistration registration;
    private volatile ServerCompiledPlanRepository plans;
    private volatile CompiledTriggerExecution execution;
    private volatile boolean closed;
    private volatile boolean initialized;

    public CustomContentExecution(CustomContentStorage storage, CoreGraphResourceAuthority graphs,
                                  CompiledGraphMetadataProvider metadata, ServerId serverId,
                                  AssetTransactionCoordinator coordinator) {
        this.storage = Objects.requireNonNull(storage, "Custom content storage is required");
        this.graphs = Objects.requireNonNull(graphs, "Core graph authority is required");
        this.metadata = Objects.requireNonNull(metadata, "Compiled metadata provider is required");
        this.serverId = Objects.requireNonNull(serverId, "Server identity is required");
        registration = coordinator.addListener(result -> result.states().keySet().stream()
            .filter(key -> TYPE.equals(key.type())).forEach(key -> committed(key.id())));
    }

    public synchronized void bind(ServerCompiledPlanRepository plans, CompiledTriggerExecution execution) {
        if (this.plans != null || closed) {
            throw new IllegalStateException("Custom content execution is already bound or closed");
        }
        this.plans = Objects.requireNonNull(plans, "Compiled plans are required");
        this.execution = Objects.requireNonNull(execution, "Compiled trigger execution is required");
    }

    public synchronized void refreshAll() {
        requireOpen();
        projections.clear();
        for (String id : storage.listIds()) {
            prepare(id);
        }
        initialized = true;
    }

    public synchronized void refreshCatalog() {
        List<ServerResourceLocator> previous = List.copyOf(projections.keySet());
        refreshAll();
        List<ServerResourceLocator> changed = new ArrayList<>(previous);
        projections.keySet().stream().filter(resource -> !changed.contains(resource)).forEach(changed::add);
        changed.forEach(plans::refreshProjection);
    }

    private synchronized void committed(String id) {
        if (closed || plans == null || !initialized) {
            return;
        }
        projections.remove(resource(id));
        try {
            prepare(id);
        } finally {
            plans.reconcile(resource(id));
        }
    }

    private void prepare(String id) {
        long started = TemporaryLifecycleDiagnostics.start();
        Map<String, Object> identity = TemporaryLifecycleDiagnostics.identity(serverId, resource(id),
            "custom-content-prepare", null, null, null, null, null, null, null);
        try {
            FlowResourceMutationStamp before = storage.readMutationStamp(id);
            if (before != null) {
                identity = TemporaryLifecycleDiagnostics.with(identity, "revision", before.revision(), "mutationId", before.mutationId());
            }
            if (before == null || before.deleted()) {
                TemporaryLifecycleDiagnostics.terminal("custom_content_prepare", started, identity, "skipped", null,
                    before == null ? "Content does not exist" : "Content is deleted");
                return;
            }
            CustomContentDefinition content = storage.get(id);
            if (content == null || !content.isEnabled() || content.getGraph() == null) {
                TemporaryLifecycleDiagnostics.terminal("custom_content_prepare", started, identity, "skipped", null,
                    content == null ? "Content does not exist" : !content.isEnabled() ? "Content is disabled" : "Content has no graph");
                return;
            }
            FlowGraph graph = content.getGraph().copy();
            graph.setId(id);
            graph.setResourceType(TYPE);
            graph.setResourceRevision(before.revision());
            graph.setResourceHash(before.payloadHash());
            GraphDocument document = metadata.projectCustomContent(graph);
            CoreGraphStorageBoundary.Decoded envelope = boundary.decode(boundary.encode(document,
                new CoreGraphStorageBoundary.AssetMetadata(TYPE, before.revision(), before.mutationId().toString(),
                    ResourceActivationState.ACTIVE)), resource(id));
            FlowGraph projection = executionProjection(document, before);
            projection.setResourceHash(envelope.envelope().assetHash().canonicalText());
            if (!before.equals(storage.readMutationStamp(id))) {
                throw new IllegalStateException("Custom content changed while preparing its compiled execution");
            }
            projections.put(resource(id), new Projection(before, projection, CoreGraphResourceState.live(envelope)));
            TemporaryLifecycleDiagnostics.terminal("custom_content_prepare", started, identity, "success", null,
                "Committed content projection prepared");
        } catch (RuntimeException failure) {
            projections.remove(resource(id));
            if (failure instanceof CompiledGraphMetadataProvider.UnsupportedGraphException unsupported) {
                Map<String, Object> diagnosticIdentity = identity;
                unsupported.diagnostics().stream().limit(8).forEach(diagnostic ->
                    TemporaryLifecycleDiagnostics.terminal("custom_content_prepare", started,
                        TemporaryLifecycleDiagnostics.with(diagnosticIdentity,
                            "errorType", failure.getClass().getSimpleName(), "nodeId", diagnostic.context().nodeId() != null
                                ? diagnostic.context().nodeId() : diagnostic.evidence().get("definitionId"),
                            "nodeInstanceId", diagnostic.evidence().get("nodeInstanceId"),
                            "definitionId", diagnostic.evidence().get("definitionId"), "definitionOwner", diagnostic.evidence().get("definitionOwner"),
                            "metadataContract", diagnostic.evidence().get("metadataContract"), "matches", diagnostic.evidence().get("matches"),
                            "pin", diagnostic.evidence().get("pin"), "failureType", diagnostic.evidence().get("failureType")),
                        "rejected", diagnostic.code(), diagnostic.message()));
            }
            TemporaryLifecycleDiagnostics.terminal("custom_content_prepare", started,
                TemporaryLifecycleDiagnostics.with(identity, "errorType", failure.getClass().getSimpleName()),
                "failure", "CONTENT_EXECUTION_UNAVAILABLE", failure.getMessage());
            Log.warn("Custom content compiled execution unavailable for " + id + ": " + failure.getMessage(), failure);
        }
    }

    public FlowGraph graph(String id) {
        Projection projection = projection(id);
        return projection == null ? null : projection.graph();
    }

    FlowResourceMutationStamp projectionStamp(String id) {
        Projection projection = projection(id);
        return projection == null ? null : projection.stamp();
    }

    private static FlowGraph executionProjection(GraphDocument document, FlowResourceMutationStamp stamp) {
        Map<String, FlowNode> nodes = new LinkedHashMap<>();
        document.nodes().forEach(source -> {
            FlowNode node = new FlowNode(source.definition().canonicalText(), source.x(), source.y(), Map.of());
            node.setVersion(source.definitionVersion());
            nodes.put(source.instanceId().canonicalText(), node);
        });
        List<FlowConnection> connections = document.connections().stream().map(connection -> new FlowConnection(
            connection.source().nodeId().canonicalText(), connection.source().pinId().value(),
            connection.target().nodeId().canonicalText(), connection.target().pinId().value())).toList();
        FlowGraph graph = new FlowGraph(document.resource().id(), nodes, connections, List.of());
        graph.setResourceType(TYPE);
        graph.setResourceRevision(stamp.revision());
        graph.setResourceMutationId(stamp.mutationId().toString());
        graph.setEnabled(true);
        return graph;
    }

    public CompletableFuture<Void> execute(FlowGraph graph, String start, Player player, Event event, Map<String, Object> variables) {
        CompiledTriggerExecution current = execution;
        if (closed || current == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Custom content compiled execution is unavailable"));
        }
        if (TYPE.equals(graph.getResourceType())) {
            Projection projection = projection(graph.getId());
            if (projection == null || projection.graph() != graph) {
                return CompletableFuture.failedFuture(new IllegalStateException("Custom content compiled execution changed before dispatch"));
            }
        }
        return current.execute(graph, start, player, event, variables);
    }

    @Override
    public boolean available() {
        return !closed && graphs.available();
    }

    @Override
    public List<String> types() {
        List<String> types = new ArrayList<>(graphs.types());
        if (!types.contains(TYPE)) {
            types.add(TYPE);
        }
        return List.copyOf(types);
    }

    @Override
    public Optional<CoreGraphStorageBoundary.Decoded> load(ServerResourceLocator resource) {
        return content(resource) ? state(resource).map(CoreGraphResourceState::envelope) : graphs.load(resource);
    }

    @Override
    public List<CoreGraphResourceState> list(String type) {
        if (!TYPE.equals(type)) {
            return graphs.list(type);
        }
        requireOpen();
        return new ArrayList<>(projections.values().stream().map(Projection::state).toList());
    }

    @Override
    public Optional<CoreGraphResourceState> state(ServerResourceLocator resource) {
        if (!content(resource)) {
            return graphs.state(resource);
        }
        requireOpen();
        Projection projection = projections.get(resource);
        return projection != null && projection.stamp().equals(storage.readMutationStamp(resource.id()))
            ? Optional.of(projection.state()) : Optional.empty();
    }

    @Override
    public CoreGraphStorageBoundary.Decoded save(ServerResourceLocator resource, byte[] envelope, UUID mutationId,
                                                 long expectedRevision, ContentHash checksum) {
        throw new UnsupportedOperationException("Compiled execution sources are read only");
    }

    @Override
    public CoreGraphStorageBoundary.CoreGraphTombstone delete(ServerResourceLocator resource, UUID mutationId,
                                                               long expectedRevision, ContentHash checksum) {
        throw new UnsupportedOperationException("Compiled execution sources are read only");
    }

    @Override
    public CoreGraphStorageBoundary.Decoded activate(ServerResourceLocator resource, ResourceActivationState activation,
                                                     UUID mutationId, long expectedRevision, ContentHash checksum) {
        throw new UnsupportedOperationException("Compiled execution sources are read only");
    }

    @Override
    public synchronized void close() {
        closed = true;
        registration.close();
        projections.clear();
        execution = null;
    }

    private boolean content(ServerResourceLocator resource) {
        return TYPE.equals(resource.resourceType().value()) && resource.serverId().equals(serverId)
            && resource.owner().equals(OwnerId.of("restudio.resync"));
    }

    private Projection projection(String id) {
        if (closed || id == null || id.isBlank()) {
            return null;
        }
        return projections.get(resource(id));
    }

    private ServerResourceLocator resource(String id) {
        return new ServerResourceLocator(serverId, ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of(TYPE)), id);
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Custom content compiled execution is closed");
        }
    }

    private record Projection(FlowResourceMutationStamp stamp, FlowGraph graph, CoreGraphResourceState state) {
    }
}
