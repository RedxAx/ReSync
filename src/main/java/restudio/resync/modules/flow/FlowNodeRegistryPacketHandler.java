package restudio.resync.modules.flow;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import restudio.resync.core.Session;
import restudio.resync.flow.plugins.FlowNodePluginRegistry;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.sync.NodePluginPayload;
import restudio.resync.flow.sync.NodeRegistryRequest;
import restudio.resync.flow.sync.NodeRegistrySnapshot;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class FlowNodeRegistryPacketHandler {
    private final NodeDefinitionRegistry definitionRegistry;
    private final FlowNodePluginRegistry pluginRegistry;
    private final FlowPacketSender sender;
    private final Gson gson = new Gson();

    public FlowNodeRegistryPacketHandler(NodeDefinitionRegistry definitionRegistry, FlowNodePluginRegistry pluginRegistry, FlowPacketSender sender) {
        this.definitionRegistry = definitionRegistry;
        this.pluginRegistry = pluginRegistry;
        this.sender = sender;
        if (pluginRegistry != null) {
            pluginRegistry.addListener(new FlowNodePluginRegistry.PluginChangeListener() {
                @Override
                public void onPluginLoaded(NodePluginPayload payload) {
                    sender.broadcastNodeRegistry(buildDeltaSnapshot(List.of(payload), List.of()));
                }

                @Override
                public void onPluginUnloaded(String pluginId) {
                    sender.broadcastNodeRegistry(buildDeltaSnapshot(List.of(), List.of(pluginId)));
                }
            });
        }
    }

    public void handleRequest(Session session, ByteBuffer buffer) {
        byte[] jsonBytes = new byte[buffer.remaining()];
        buffer.get(jsonBytes);
        String json = new String(jsonBytes, StandardCharsets.UTF_8);
        NodeRegistryRequest request = null;
        try {
            if (!json.isBlank()) {
                request = gson.fromJson(json, NodeRegistryRequest.class);
            }
        } catch (Exception e) {
            Bukkit.getLogger().warning("[ReSync] Failed to parse node registry request: " + e.getMessage());
        }
        sender.sendNodeRegistrySnapshot(session, buildSnapshot(request));
    }

    private NodeRegistrySnapshot buildSnapshot(NodeRegistryRequest request) {
        NodeRegistrySnapshot snapshot = new NodeRegistrySnapshot();
        Map<String, String> clientChecksums = request != null ? request.getPluginChecksums() : Map.of();
        boolean fullSync = clientChecksums == null || clientChecksums.isEmpty();
        snapshot.setFullSync(fullSync);

        List<String> nodeIds = new ArrayList<>(definitionRegistry.getAllDefinitions().keySet());
        nodeIds.sort(String.CASE_INSENSITIVE_ORDER);
        snapshot.setNodeIds(nodeIds);

        List<NodePluginPayload> pluginPayloads = new ArrayList<>();
        if (pluginRegistry != null) {
            for (String pluginId : pluginRegistry.getPluginIds()) {
                String checksum = pluginRegistry.getChecksum(pluginId);
                String clientChecksum = clientChecksums != null ? clientChecksums.get(pluginId) : null;
                if (fullSync || checksum == null || !checksum.equals(clientChecksum)) {
                    NodePluginPayload payload = pluginRegistry.buildPayload(pluginId);
                    if (payload != null) {
                        pluginPayloads.add(payload);
                    }
                }
            }
        }
        snapshot.setPlugins(pluginPayloads);

        List<String> removed = new ArrayList<>();
        if (clientChecksums != null && pluginRegistry != null) {
            for (String pluginId : clientChecksums.keySet()) {
                if (!pluginRegistry.getPluginIds().contains(pluginId)) {
                    removed.add(pluginId);
                }
            }
        }
        snapshot.setRemovedPlugins(removed);
        return snapshot;
    }

    private NodeRegistrySnapshot buildDeltaSnapshot(List<NodePluginPayload> plugins, List<String> removedPlugins) {
        NodeRegistrySnapshot snapshot = new NodeRegistrySnapshot();
        snapshot.setFullSync(false);
        List<String> nodeIds = new ArrayList<>(definitionRegistry.getAllDefinitions().keySet());
        nodeIds.sort(String.CASE_INSENSITIVE_ORDER);
        snapshot.setNodeIds(nodeIds);
        snapshot.setPlugins(plugins);
        snapshot.setRemovedPlugins(removedPlugins);
        return snapshot;
    }
}
