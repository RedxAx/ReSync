package restudio.resync.worldgen.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class WorldGenGraph {
    public static final int CURRENT_VERSION = 1;
    private String id;
    private int version;
    private Map<String, WorldGenNode> nodes;
    private List<WorldGenConnection> connections;
    private transient Map<String, List<WorldGenConnection>> connectionsBySource = new HashMap<>();
    private transient Map<String, List<WorldGenConnection>> connectionsByTarget = new HashMap<>();
    private transient IdentityHashMap<WorldGenNode, String> nodeToId = new IdentityHashMap<>();

    public WorldGenGraph() {
        this.id = UUID.randomUUID().toString();
        this.version = CURRENT_VERSION;
        this.nodes = new HashMap<>();
        this.connections = new ArrayList<>();
        rebuildIndices();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public Map<String, WorldGenNode> getNodes() {
        return nodes;
    }

    public void setNodes(Map<String, WorldGenNode> nodes) {
        this.nodes = nodes != null ? nodes : new HashMap<>();
        rebuildIndices();
    }

    public List<WorldGenConnection> getConnections() {
        return connections;
    }

    public void setConnections(List<WorldGenConnection> connections) {
        this.connections = connections != null ? connections : new ArrayList<>();
        rebuildIndices();
    }

    public void rebuildIndices() {
        connectionsBySource = new HashMap<>();
        connectionsByTarget = new HashMap<>();
        nodeToId = new IdentityHashMap<>();
        if (nodes != null) {
            for (Map.Entry<String, WorldGenNode> entry : nodes.entrySet()) {
                nodeToId.put(entry.getValue(), entry.getKey());
            }
        }
        if (connections != null) {
            for (WorldGenConnection connection : connections) {
                connectionsBySource.computeIfAbsent(connection.getSourceNodeId(), key -> new ArrayList<>()).add(connection);
                connectionsByTarget.computeIfAbsent(connection.getTargetNodeId(), key -> new ArrayList<>()).add(connection);
            }
        }
    }

    public List<WorldGenConnection> getConnectionsFromSource(String nodeId) {
        ensureIndicesBuilt();
        return connectionsBySource.getOrDefault(nodeId, Collections.emptyList());
    }

    public List<WorldGenConnection> getConnectionsToTarget(String nodeId) {
        ensureIndicesBuilt();
        return connectionsByTarget.getOrDefault(nodeId, Collections.emptyList());
    }

    public String findNodeId(WorldGenNode node) {
        ensureIndicesBuilt();
        return nodeToId.get(node);
    }

    private void ensureIndicesBuilt() {
        if (connectionsBySource == null || connectionsByTarget == null || nodeToId == null || nodeToId.size() != nodes.size()) {
            rebuildIndices();
        }
    }
}
