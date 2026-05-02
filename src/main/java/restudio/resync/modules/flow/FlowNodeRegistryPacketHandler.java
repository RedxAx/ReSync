package restudio.resync.modules.flow;

import com.google.gson.Gson;
import restudio.flow.data.FlowDataType;
import restudio.resync.Log;
import restudio.resync.customcontent.CustomContentService;
import restudio.resync.core.Session;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.handler.property.PropertyRegistry;
import restudio.resync.flow.plugins.FlowNodePluginRegistry;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.sync.FlowCategoryMetadata;
import restudio.resync.flow.sync.FlowConversionRule;
import restudio.resync.flow.sync.FlowOptionSourceMetadata;
import restudio.resync.flow.sync.FlowTypeMetadata;
import restudio.resync.flow.sync.NodePluginPayload;
import restudio.resync.flow.sync.NodeRegistryRequest;
import restudio.resync.flow.sync.NodeRegistrySnapshot;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class FlowNodeRegistryPacketHandler {
    private final NodeDefinitionRegistry definitionRegistry;
    private final FlowNodePluginRegistry pluginRegistry;
    private final FlowPacketSender sender;
    private final PropertyRegistry propertyRegistry;
    private final CustomContentService customContentService;
    private final Gson gson = new Gson();

    public FlowNodeRegistryPacketHandler(NodeDefinitionRegistry definitionRegistry, FlowNodePluginRegistry pluginRegistry, FlowPacketSender sender, PropertyRegistry propertyRegistry, CustomContentService customContentService) {
        this.definitionRegistry = definitionRegistry;
        this.pluginRegistry = pluginRegistry;
        this.sender = sender;
        this.propertyRegistry = propertyRegistry;
        this.customContentService = customContentService;
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
            Log.warn("Failed to parse node registry request: " + e.getMessage());
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
        stampRegistry(snapshot, nodeIds);

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
        populatePropertyMetadata(snapshot);
        populateServerMetadata(snapshot);
        return snapshot;
    }

    private NodeRegistrySnapshot buildDeltaSnapshot(List<NodePluginPayload> plugins, List<String> removedPlugins) {
        NodeRegistrySnapshot snapshot = new NodeRegistrySnapshot();
        snapshot.setFullSync(false);
        List<String> nodeIds = new ArrayList<>(definitionRegistry.getAllDefinitions().keySet());
        nodeIds.sort(String.CASE_INSENSITIVE_ORDER);
        snapshot.setNodeIds(nodeIds);
        stampRegistry(snapshot, nodeIds);
        snapshot.setPlugins(plugins);
        snapshot.setRemovedPlugins(removedPlugins);
        populatePropertyMetadata(snapshot);
        populateServerMetadata(snapshot);
        return snapshot;
    }

    private void stampRegistry(NodeRegistrySnapshot snapshot, List<String> nodeIds) {
        snapshot.setGeneratedAt(System.currentTimeMillis());
        snapshot.setRegistryChecksum(computeRegistryChecksum(nodeIds));
    }

    public String computeRegistryChecksum() {
        List<String> nodeIds = new ArrayList<>(definitionRegistry.getAllDefinitions().keySet());
        nodeIds.sort(String.CASE_INSENSITIVE_ORDER);
        return computeRegistryChecksum(nodeIds);
    }

    private String computeRegistryChecksum(List<String> nodeIds) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            Map<String, NodeDefinition> definitions = definitionRegistry.getAllDefinitions();
            for (String nodeId : nodeIds) {
                digest.update(nodeId.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                NodeDefinition definition = definitions.get(nodeId);
                if (definition != null) {
                    digest.update(gson.toJson(definition).getBytes(StandardCharsets.UTF_8));
                }
                digest.update((byte) 0);
            }
            if (pluginRegistry != null) {
                List<String> pluginIds = new ArrayList<>(pluginRegistry.getPluginIds());
                pluginIds.sort(String.CASE_INSENSITIVE_ORDER);
                for (String pluginId : pluginIds) {
                    digest.update(pluginId.getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    String checksum = pluginRegistry.getChecksum(pluginId);
                    if (checksum != null) {
                        digest.update(checksum.getBytes(StandardCharsets.UTF_8));
                    }
                    digest.update((byte) 0);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception ignored) {
            return "";
        }
    }

    private void populatePropertyMetadata(NodeRegistrySnapshot snapshot) {
        if (propertyRegistry == null) {
            return;
        }
        Map<String, Map<String, List<String>>> propertyActions = new HashMap<>();
        Map<String, Map<String, FlowDataType>> propertyOutputTypes = new HashMap<>();
        for (String family : List.of("player", "entity", "world", "block", "inventory", "itemstack")) {
            if (!propertyRegistry.hasFamily(family)) {
                continue;
            }
            Map<String, List<String>> familyActions = new HashMap<>();
            Map<String, FlowDataType> familyTypes = new HashMap<>();
            for (String property : propertyRegistry.getProperties(family)) {
                familyActions.put(property, propertyRegistry.getActions(family, property));
                familyTypes.put(property, propertyRegistry.getDataType(family, property));
            }
            propertyActions.put(family, familyActions);
            propertyOutputTypes.put(family, familyTypes);
        }
        snapshot.setPropertyActions(propertyActions);
        snapshot.setPropertyOutputTypes(propertyOutputTypes);
    }

    public void populateServerMetadata(NodeRegistrySnapshot snapshot) {
        snapshot.setTypeMetadata(buildTypeMetadata());
        snapshot.setCategoryMetadata(buildCategoryMetadata());
        snapshot.setOptionSourceMetadata(buildOptionSourceMetadata());
        snapshot.setConversionRules(buildConversionRules());
    }

    private List<FlowTypeMetadata> buildTypeMetadata() {
        List<FlowTypeMetadata> list = new ArrayList<>();
        for (FlowDataType type : FlowDataType.values()) {
            if (type == FlowDataType.EXECUTION) {
                continue;
            }
            String parentId = type.getParent() != null ? type.getParent().getId() : null;
            boolean literal = isLiteralType(type);
            boolean object = isObjectType(type);
            list.add(new FlowTypeMetadata(type.getId(), type.getId(), type.getColor(), parentId, type.canStringify(), literal, object));
        }
        if (pluginRegistry != null) {
            list.addAll(pluginRegistry.getAllCustomTypes());
        }
        return list;
    }

    private boolean isLiteralType(FlowDataType type) {
        return type == FlowDataType.STRING
            || type == FlowDataType.NUMBER
            || type == FlowDataType.BOOLEAN
            || type == FlowDataType.ANY;
    }

    private boolean isObjectType(FlowDataType type) {
        return type == FlowDataType.PLAYER
            || type == FlowDataType.ENTITY
            || type == FlowDataType.LIVING_ENTITY
            || type == FlowDataType.WORLD
            || type == FlowDataType.BLOCK
            || type == FlowDataType.LOCATION
            || type == FlowDataType.INVENTORY
            || type == FlowDataType.ITEMSTACK;
    }

    private List<FlowCategoryMetadata> buildCategoryMetadata() {
        List<FlowCategoryMetadata> list = new ArrayList<>();
        for (NodeDefinition.NodeCategory cat : NodeDefinition.NodeCategory.values()) {
            list.add(new FlowCategoryMetadata(cat.getId(), cat.getDisplayName(), cat.getColor(), cat.getPriority()));
        }
        if (pluginRegistry != null) {
            list.addAll(pluginRegistry.getAllCustomCategories());
        }
        return list;
    }

    private List<FlowOptionSourceMetadata> buildOptionSourceMetadata() {
        List<FlowOptionSourceMetadata> list = new ArrayList<>();
        list.add(new FlowOptionSourceMetadata("server:minecraft:advancement", "minecraft", "SEARCHABLE_LIST", true));
        list.add(new FlowOptionSourceMetadata("server:minecraft:biome", "minecraft", "DROPDOWN", false));
        list.add(new FlowOptionSourceMetadata("server:minecraft:difficulty", "minecraft", "DROPDOWN", false));
        list.add(new FlowOptionSourceMetadata("server:minecraft:enchantment", "minecraft", "SEARCHABLE_LIST", true));
        list.add(new FlowOptionSourceMetadata("server:minecraft:entity_type", "minecraft", "DROPDOWN", false));
        list.add(new FlowOptionSourceMetadata("server:minecraft:gamemode", "minecraft", "DROPDOWN", false));
        list.add(new FlowOptionSourceMetadata("server:minecraft:material", "minecraft", "SEARCHABLE_LIST", true));
        list.add(new FlowOptionSourceMetadata("server:minecraft:block", "minecraft", "SEARCHABLE_LIST", true));
        list.add(new FlowOptionSourceMetadata("server:minecraft:particle", "minecraft", "SEARCHABLE_LIST", true));
        list.add(new FlowOptionSourceMetadata("server:minecraft:potion_effect", "minecraft", "DROPDOWN", false));
        list.add(new FlowOptionSourceMetadata("server:minecraft:sound", "minecraft", "SEARCHABLE_LIST", true));
        list.add(new FlowOptionSourceMetadata("server:minecraft:world", "minecraft", "SEARCHABLE_LIST", false));
        if (customContentService != null) {
            list.add(new FlowOptionSourceMetadata("server:custom_content:provider", "custom_content", "DROPDOWN", false));
            if (customContentService.isProviderAvailable("nexo")) {
                list.add(new FlowOptionSourceMetadata("server:custom_content:nexo_item", "custom_content", "SEARCHABLE_LIST", true));
                list.add(new FlowOptionSourceMetadata("server:custom_content:nexo_block", "custom_content", "SEARCHABLE_LIST", true));
                list.add(new FlowOptionSourceMetadata("server:custom_content:nexo_furniture", "custom_content", "SEARCHABLE_LIST", true));
                list.add(new FlowOptionSourceMetadata("server:custom_content:nexo_armor", "custom_content", "SEARCHABLE_LIST", true));
            }
        }
        if (pluginRegistry != null) {
            list.addAll(pluginRegistry.getAllCustomOptionSources());
        }
        return list;
    }

    private List<FlowConversionRule> buildConversionRules() {
        List<FlowConversionRule> list = new ArrayList<>();
        Map<String, String> classToType = buildClassToTypeMap();
        TypeAdapterRegistry adapters = new TypeAdapterRegistry();
        for (Map.Entry<TypeAdapterRegistry.ClassPair, Function<Object, Object>> entry : adapters.getAdapters().entrySet()) {
            String sourceId = classToType.get(entry.getKey().getSource().getName());
            String targetId = classToType.get(entry.getKey().getTarget().getName());
            if (sourceId != null && targetId != null && !sourceId.equals(targetId)) {
                list.add(new FlowConversionRule(sourceId, targetId));
            }
        }
        for (Map.Entry<Class<?>, Function<String, ?>> entry : adapters.getStringParsers().entrySet()) {
            String targetId = classToType.get(entry.getKey().getName());
            if (targetId != null && !targetId.equals("string")) {
                list.add(new FlowConversionRule("string", targetId));
            }
        }
        if (pluginRegistry != null) {
            list.addAll(pluginRegistry.getAllCustomConversionRules());
        }
        return list;
    }

    private Map<String, String> buildClassToTypeMap() {
        Map<String, String> map = new HashMap<>();
        for (FlowDataType type : FlowDataType.values()) {
            if (type.getJavaType() != null) {
                map.put(type.getJavaType().getName(), type.getId());
            }
            if (type.getDataClass() != null) {
                map.put(type.getDataClass().getName(), type.getId());
            }
        }
        map.put(String.class.getName(), "string");
        map.put(Number.class.getName(), "number");
        map.put(Boolean.class.getName(), "boolean");
        map.put(Integer.class.getName(), "number");
        map.put(Long.class.getName(), "number");
        map.put(Double.class.getName(), "number");
        map.put(Float.class.getName(), "number");
        return map;
    }
}
