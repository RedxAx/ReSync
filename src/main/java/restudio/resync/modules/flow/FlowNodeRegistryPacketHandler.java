package restudio.resync.modules.flow;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowDataTypeAdapter;
import restudio.flow.data.FlowJobReference;
import restudio.flow.data.FlowResourceReference;
import restudio.flow.data.FlowTypeRef;
import restudio.flow.data.GuiElement;
import restudio.resync.Log;
import restudio.resync.api.OptionCatalogProvider;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.api.ReSyncExtensionData;
import restudio.resync.customcontent.CustomContentService;
import restudio.resync.core.Session;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.FlowValueCodecRegistry;
import restudio.resync.flow.handler.property.PropertyRegistry;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.sync.FlowCategoryMetadata;
import restudio.resync.flow.sync.FlowConversionRule;
import restudio.resync.flow.sync.FlowOptionSourceMetadata;
import restudio.resync.flow.sync.FlowPropertyMetadata;
import restudio.resync.flow.sync.FlowTypeMetadata;
import restudio.resync.flow.sync.NodePluginPayload;
import restudio.resync.flow.sync.NodeRegistryRequest;
import restudio.resync.flow.sync.NodeRegistrySnapshot;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

public class FlowNodeRegistryPacketHandler {
    private static final long REGISTRY_COMPATIBILITY_WINDOW_MILLIS = 30L * 24 * 60 * 60 * 1000;
    private static final List<String> REGISTRY_CAPABILITIES = List.of("nodes", "types", "categories", "properties", "resources", "catalogs", "conversions", "extensions", "deltas", "diagnostics", "contextual_catalogs", "authorization", "destructive_safety", "function_tests", "jobs", "job_events", "resource_operation_diagnostics", "extension_validators");
    private final NodeDefinitionRegistry definitionRegistry;
    private final FlowPacketSender sender;
    private final PropertyRegistry propertyRegistry;
    private final ReSyncExtensionData extensionData;
    private final OptionCatalogRegistry optionCatalogRegistry;
    private final FlowResourceRegistry resourceRegistry;
    private final FlowValueCodecRegistry valueCodecs;
    private Supplier<Map<String, Object>> diagnosticsSupplier = Map::of;
    private final Gson gson = new GsonBuilder()
        .registerTypeAdapter(FlowDataType.class, new FlowDataTypeAdapter())
        .create();

    public FlowNodeRegistryPacketHandler(NodeDefinitionRegistry definitionRegistry, FlowPacketSender sender, PropertyRegistry propertyRegistry, CustomContentService customContentService) {
        this(definitionRegistry, sender, propertyRegistry, customContentService, null, null);
    }

    public FlowNodeRegistryPacketHandler(NodeDefinitionRegistry definitionRegistry, FlowPacketSender sender, PropertyRegistry propertyRegistry, CustomContentService customContentService, ReSyncExtensionData extensionData) {
        this(definitionRegistry, sender, propertyRegistry, customContentService, extensionData, null);
    }

    public FlowNodeRegistryPacketHandler(NodeDefinitionRegistry definitionRegistry, FlowPacketSender sender, PropertyRegistry propertyRegistry, CustomContentService customContentService,
                                         ReSyncExtensionData extensionData, OptionCatalogRegistry optionCatalogRegistry) {
        this(definitionRegistry, sender, propertyRegistry, customContentService, extensionData, optionCatalogRegistry, null);
    }

    public FlowNodeRegistryPacketHandler(NodeDefinitionRegistry definitionRegistry, FlowPacketSender sender, PropertyRegistry propertyRegistry, CustomContentService customContentService,
                                         ReSyncExtensionData extensionData, OptionCatalogRegistry optionCatalogRegistry, FlowResourceRegistry resourceRegistry) {
        this(definitionRegistry, sender, propertyRegistry, customContentService, extensionData, optionCatalogRegistry, resourceRegistry, new FlowValueCodecRegistry());
    }

    public FlowNodeRegistryPacketHandler(NodeDefinitionRegistry definitionRegistry, FlowPacketSender sender, PropertyRegistry propertyRegistry, CustomContentService customContentService,
                                         ReSyncExtensionData extensionData, OptionCatalogRegistry optionCatalogRegistry, FlowResourceRegistry resourceRegistry,
                                         FlowValueCodecRegistry valueCodecs) {
        this.definitionRegistry = definitionRegistry;
        this.sender = sender;
        this.propertyRegistry = propertyRegistry;
        this.extensionData = extensionData;
        this.optionCatalogRegistry = optionCatalogRegistry;
        this.resourceRegistry = resourceRegistry;
        this.valueCodecs = valueCodecs != null ? valueCodecs : new FlowValueCodecRegistry();
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

    NodeRegistrySnapshot buildSnapshot(NodeRegistryRequest request) {
        NodeRegistrySnapshot snapshot = new NodeRegistrySnapshot();
        snapshot.setContractVersion(NodeRegistrySnapshot.CURRENT_CONTRACT_VERSION);
        snapshot.setMinimumClientContractVersion(NodeRegistrySnapshot.MINIMUM_SUPPORTED_CONTRACT_VERSION);
        snapshot.setCompatibleUntil(System.currentTimeMillis() + REGISTRY_COMPATIBILITY_WINDOW_MILLIS);
        snapshot.setCapabilities(REGISTRY_CAPABILITIES);
        Map<String, String> clientChecksums = request != null ? request.getPluginChecksums() : Map.of();
        boolean compatibleRequest = request != null
            && request.getContractVersion() >= NodeRegistrySnapshot.MINIMUM_SUPPORTED_CONTRACT_VERSION
            && request.getContractVersion() <= NodeRegistrySnapshot.CURRENT_CONTRACT_VERSION;
        boolean fullSync = !compatibleRequest || request.getRegistryChecksum().isBlank();
        snapshot.setFullSync(fullSync);
        if (!fullSync) {
            snapshot.setBaseRegistryChecksum(request.getRegistryChecksum());
        }

        List<String> nodeIds = new ArrayList<>(definitionRegistry.getAllDefinitions().keySet());
        nodeIds.sort(String.CASE_INSENSITIVE_ORDER);
        snapshot.setNodeIds(nodeIds);

        List<NodePluginPayload> pluginPayloads = new ArrayList<>();
        List<String> pluginIds = new ArrayList<>(getPluginIds());
        pluginIds.sort(String.CASE_INSENSITIVE_ORDER);
        for (String pluginId : pluginIds) {
            String checksum = getChecksum(pluginId);
            String clientChecksum = clientChecksums != null ? clientChecksums.get(pluginId) : null;
            if (fullSync || checksum == null || !checksum.equals(clientChecksum)) {
                NodePluginPayload payload = buildPayload(pluginId);
                if (payload != null) {
                    pluginPayloads.add(payload);
                }
            }
        }
        snapshot.setPlugins(pluginPayloads);

        List<String> removed = new ArrayList<>();
        if (clientChecksums != null) {
            Set<String> serverPluginIds = getPluginIds();
            for (String pluginId : clientChecksums.keySet()) {
                if (!serverPluginIds.contains(pluginId)) {
                    removed.add(pluginId);
                }
            }
        }
        removed.sort(String.CASE_INSENSITIVE_ORDER);
        snapshot.setRemovedPlugins(removed);
        populatePropertyMetadata(snapshot);
        if (resourceRegistry != null) {
            snapshot.setResourceMetadata(resourceRegistry.metadata());
        }
        populateServerMetadata(snapshot);
        snapshot.setRegistryDiagnostics(diagnosticsSnapshot());
        stampRegistry(snapshot, nodeIds);
        return snapshot;
    }

    public NodeRegistrySnapshot buildFullSnapshot() {
        return buildSnapshot(null);
    }

    public void setDiagnosticsSupplier(Supplier<Map<String, Object>> diagnosticsSupplier) {
        this.diagnosticsSupplier = diagnosticsSupplier != null ? diagnosticsSupplier : Map::of;
    }

    private Map<String, Object> diagnosticsSnapshot() {
        try {
            Map<String, Object> diagnostics = diagnosticsSupplier.get();
            return diagnostics != null ? diagnostics : Map.of();
        } catch (RuntimeException exception) {
            return Map.of("diagnosticsError", exception.getMessage() != null ? exception.getMessage() : exception.getClass().getSimpleName());
        }
    }

    private void stampRegistry(NodeRegistrySnapshot snapshot, List<String> nodeIds) {
        snapshot.setGeneratedAt(System.currentTimeMillis());
        snapshot.setRegistryChecksum(computeRegistryChecksum(nodeIds, snapshot));
    }

    public String computeRegistryChecksum() {
        List<String> nodeIds = new ArrayList<>(definitionRegistry.getAllDefinitions().keySet());
        nodeIds.sort(String.CASE_INSENSITIVE_ORDER);
        NodeRegistrySnapshot snapshot = new NodeRegistrySnapshot();
        snapshot.setContractVersion(NodeRegistrySnapshot.CURRENT_CONTRACT_VERSION);
        snapshot.setCapabilities(REGISTRY_CAPABILITIES);
        populatePropertyMetadata(snapshot);
        if (resourceRegistry != null) {
            snapshot.setResourceMetadata(resourceRegistry.metadata());
        }
        populateServerMetadata(snapshot);
        return computeRegistryChecksum(nodeIds, snapshot);
    }

    public List<NodePluginPayload> buildPluginPayloads() {
        List<NodePluginPayload> payloads = new ArrayList<>();
        List<String> pluginIds = new ArrayList<>(getPluginIds());
        pluginIds.sort(String.CASE_INSENSITIVE_ORDER);
        for (String pluginId : pluginIds) {
            NodePluginPayload payload = buildPayload(pluginId);
            if (payload != null) {
                payloads.add(payload);
            }
        }
        return payloads;
    }

    private String computeRegistryChecksum(List<String> nodeIds, NodeRegistrySnapshot snapshot) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            Map<String, NodeDefinition> definitions = definitionRegistry.getAllDefinitions();
            for (String nodeId : nodeIds) {
                updateDigest(digest, nodeId);
                NodeDefinition definition = definitions.get(nodeId);
                if (definition != null) {
                    updateDefinitionDigest(digest, definition);
                }
            }
            List<String> pluginIds = new ArrayList<>(getPluginIds());
            pluginIds.sort(String.CASE_INSENSITIVE_ORDER);
            for (String pluginId : pluginIds) {
                updateDigest(digest, pluginId);
                updateDigest(digest, getChecksum(pluginId));
                updateDigest(digest, extensionData != null ? extensionData.version(pluginId) : "builtin");
                updateDigest(digest, extensionData != null ? extensionData.description(pluginId) : "BuiltInNodeDefinitions");
            }
            updateCanonicalDigest(digest, snapshot.getContractVersion());
            updateCanonicalDigest(digest, snapshot.getCapabilities());
            updateCanonicalDigest(digest, snapshot.getPropertyActions());
            updateCanonicalDigest(digest, snapshot.getPropertyOutputTypes());
            updateCanonicalDigest(digest, snapshot.getPropertyMetadata());
            updateCanonicalDigest(digest, snapshot.getResourceMetadata());
            updateCanonicalDigest(digest, snapshot.getTypeMetadata());
            updateCanonicalDigest(digest, snapshot.getCategoryMetadata());
            updateCanonicalDigest(digest, snapshot.getOptionSourceMetadata());
            updateCanonicalDigest(digest, snapshot.getConversionRules());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable for the registry checksum", exception);
        }
    }

    private void updateDefinitionDigest(MessageDigest digest, NodeDefinition definition) {
        updateDigest(digest, definition.getId());
        updateDigest(digest, definition.getDisplayName());
        updateDigest(digest, definition.getCategory() != null ? definition.getCategory().getId() : "");
        updateDigest(digest, definition.getColor());
        updateDigest(digest, definition.getPriority());
        updateDigest(digest, definition.isHidden());
        updateDigest(digest, definition.getHiddenReason());
        updateDigest(digest, definition.getOwner());
        updateDigest(digest, definition.getDescription());
        updateDigest(digest, definition.getHandler());
        updateDigest(digest, definition.getHandlerConfig());
        updateDigest(digest, definition.isTrigger());
        updateDigest(digest, definition.getEventType());
        updateDigest(digest, definition.getAliases());
        updateMappingsDigest(digest, definition.getOutputMappings());
        updateDigest(digest, definition.getSchemaVersion());
        updateDigest(digest, definition.getKind());
        updateAvailabilityDigest(digest, definition.getAvailability());
        updateDigest(digest, definition.getCanonicalId());
        updateDigest(digest, definition.getLegacyIds());
        updateDigest(digest, definition.isDeprecated());
        updateDigest(digest, definition.getTags());
        updateDigest(digest, definition.getExamples());
        updateDigest(digest, definition.getFamily());
        updateDigest(digest, definition.isRecommended());
        updateDigest(digest, definition.getReplacementFor());
        updateDigest(digest, definition.getAuthorizationPolicy());
        updateDigest(digest, definition.isSensitive());
        updateDigest(digest, definition.isDestructive());
        updateDigest(digest, definition.getAuditPolicy());
        updateDigest(digest, definition.getConfirmationPolicy());
        updateDigest(digest, definition.getClockDomain());
        updatePinsDigest(digest, definition.getInputs());
        updatePinsDigest(digest, definition.getOutputs());
    }

    private void updatePinsDigest(MessageDigest digest, List<NodeDefinition.PinDefinition> pins) {
        if (pins == null) {
            updateDigest(digest, 0);
            return;
        }
        updateDigest(digest, pins.size());
        for (NodeDefinition.PinDefinition pin : pins) {
            updateDigest(digest, pin.getName());
            updateDigest(digest, pin.getType());
            updateDigest(digest, pin.getDirection());
            FlowDataType dataType = pin.getDataType();
            updateDigest(digest, dataType != null ? dataType.getId() : "");
            updateDigest(digest, pin.getTypeRef());
            NodeDefinition.RepeatablePin repeatable = pin.getRepeatable();
            if (repeatable != null) {
                updateDigest(digest, repeatable.getGroupId());
                updateDigest(digest, repeatable.getMinItems());
                updateDigest(digest, repeatable.getMaxItems());
                updateDigest(digest, repeatable.getItemLabel());
            }
            updateDigest(digest, pin.getWidgetType());
            updateDigest(digest, pin.getOptions());
            updateDigest(digest, pin.getOptionsSource());
            updateDigest(digest, pin.getDefaultValue());
            updateConstraintsDigest(digest, pin.getConstraints());
            updateDigest(digest, pin.getVisibleWhen());
            updateDigest(digest, pin.getDescription());
            updateDigest(digest, pin.isOptional());
        }
    }

    private void updateMappingsDigest(MessageDigest digest, List<NodeDefinition.PinMapping> mappings) {
        if (mappings == null) {
            updateDigest(digest, 0);
            return;
        }
        updateDigest(digest, mappings.size());
        for (NodeDefinition.PinMapping mapping : mappings) {
            updateDigest(digest, mapping.source());
            updateDigest(digest, mapping.target());
        }
    }

    private void updateAvailabilityDigest(MessageDigest digest, NodeDefinition.Availability availability) {
        if (availability == null) {
            updateDigest(digest, "");
            return;
        }
        updateDigest(digest, availability.getPlugin());
        updateDigest(digest, availability.getPlatform());
        updateDigest(digest, availability.getMinVersion());
    }

    private void updateConstraintsDigest(MessageDigest digest, NodeDefinition.PinConstraints constraints) {
        if (constraints == null) {
            updateDigest(digest, "");
            return;
        }
        updateDigest(digest, constraints.getMin());
        updateDigest(digest, constraints.getMax());
        updateDigest(digest, constraints.getStep());
    }

    private void updateDigest(MessageDigest digest, Object value) {
        digest.update(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private void updateCanonicalDigest(MessageDigest digest, Object value) {
        updateJsonDigest(digest, gson.toJsonTree(value));
    }

    private void updateJsonDigest(MessageDigest digest, JsonElement element) {
        if (element == null || element.isJsonNull()) {
            updateDigest(digest, "null");
            return;
        }
        if (element.isJsonObject()) {
            List<String> names = new ArrayList<>(element.getAsJsonObject().keySet());
            names.sort(String.CASE_INSENSITIVE_ORDER);
            updateDigest(digest, names.size());
            for (String name : names) {
                updateDigest(digest, name);
                updateJsonDigest(digest, element.getAsJsonObject().get(name));
            }
            return;
        }
        if (element.isJsonArray()) {
            updateDigest(digest, element.getAsJsonArray().size());
            element.getAsJsonArray().forEach(item -> updateJsonDigest(digest, item));
            return;
        }
        updateDigest(digest, element.toString());
    }

    private Set<String> getPluginIds() {
        return new HashSet<>(definitionRegistry.getPluginIds());
    }

    private NodePluginPayload buildPayload(String pluginId) {
        List<NodeDefinition> definitions = definitionRegistry.getDefinitionsForPlugin(pluginId);
        if (definitions.isEmpty()) {
            return null;
        }
        List<NodeDefinition> sortedDefinitions = new ArrayList<>(definitions);
        sortedDefinitions.sort(Comparator.comparing(NodeDefinition::getId, String.CASE_INSENSITIVE_ORDER));
        NodePluginPayload payload = new NodePluginPayload();
        payload.setPluginId(pluginId);
        payload.setVersion(extensionData != null ? extensionData.version(pluginId) : "builtin");
        payload.setDescription(extensionData != null ? extensionData.description(pluginId) : "BuiltInNodeDefinitions");
        payload.setChecksum(computeDefinitionChecksum(definitions));
        payload.setNodes(sortedDefinitions);
        return payload;
    }

    private String getChecksum(String pluginId) {
        List<NodeDefinition> definitions = definitionRegistry.getDefinitionsForPlugin(pluginId);
        return definitions.isEmpty() ? null : computeDefinitionChecksum(definitions);
    }

    private String computeDefinitionChecksum(List<NodeDefinition> definitions) {
        List<NodeDefinition> sorted = new ArrayList<>(definitions);
        sorted.sort(Comparator.comparing(NodeDefinition::getId, String.CASE_INSENSITIVE_ORDER));
        String json = gson.toJson(sorted);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable for the node checksum", exception);
        }
    }

    private void populatePropertyMetadata(NodeRegistrySnapshot snapshot) {
        if (propertyRegistry == null) {
            return;
        }
        Map<String, Map<String, List<String>>> propertyActions = new HashMap<>();
        Map<String, Map<String, FlowDataType>> propertyOutputTypes = new HashMap<>();
        List<FlowPropertyMetadata> propertyMetadata = new ArrayList<>();
        for (String family : propertyRegistry.getFamilies()) {
            if (!propertyRegistry.hasFamily(family)) {
                continue;
            }
            Map<String, List<String>> familyActions = new HashMap<>();
            Map<String, FlowDataType> familyTypes = new HashMap<>();
            for (String property : propertyRegistry.getProperties(family)) {
                familyActions.put(property, propertyRegistry.getActions(family, property));
                familyTypes.put(property, propertyRegistry.getDataType(family, property));
                PropertyRegistry.PropertyDescriptor descriptor = propertyRegistry.getDescriptor(family, property);
                if (descriptor != null) {
                    propertyMetadata.add(new FlowPropertyMetadata(descriptor.family(), descriptor.property(), descriptor.type(), descriptor.actions(),
                        descriptor.readable(), descriptor.writable(), descriptor.observable(), descriptor.invokable(), descriptor.owner()));
                }
            }
            propertyActions.put(family, familyActions);
            propertyOutputTypes.put(family, familyTypes);
        }
        propertyMetadata.sort(Comparator.comparing(FlowPropertyMetadata::getFamily, Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER))
            .thenComparing(FlowPropertyMetadata::getProperty, Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER)));
        snapshot.setPropertyActions(propertyActions);
        snapshot.setPropertyOutputTypes(propertyOutputTypes);
        snapshot.setPropertyMetadata(propertyMetadata);
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
            boolean boundarySafe = isBoundarySafeType(type);
            FlowTypeMetadata metadata = new FlowTypeMetadata(type.getId(), displayName(type.getId()), type.getColor(), parentId,
                type.canStringify(), literal, object);
            metadata.setCanonicalId(type.getCanonicalId());
            metadata.setLegacyIds(List.of(type.getId()));
            metadata.setOwner("builtin");
            metadata.setRuntimeType(type.getJavaType() != null ? type.getJavaType().getName() : null);
            metadata.setTransportable(boundarySafe);
            metadata.setPersistable(boundarySafe);
            metadata.setCodecId(boundarySafe ? "resync:flow/" + type.getId() : null);
            metadata.setLiteralEditor(literalEditor(type));
            metadata.setCatalogSource(catalogSource(type));
            list.add(metadata);
        }
        if (extensionData != null) {
            for (FlowTypeMetadata metadata : extensionData.types()) {
                if (metadata != null && (metadata.getCanonicalId() == null || !metadata.getCanonicalId().contains(":"))) {
                    String owner = metadata.getOwner() != null && !metadata.getOwner().isBlank() ? metadata.getOwner() : "extension";
                    metadata.setCanonicalId(owner + ":" + metadata.getId());
                    metadata.setLegacyIds(List.of(metadata.getId()));
                }
                FlowDataType runtimeType = metadata != null ? FlowDataType.fromString(metadata.getId()) : null;
                if (metadata != null && (runtimeType == null || !runtimeType.isResolved())) {
                    metadata.setAvailable(false);
                    metadata.setUnavailableReason("No executable runtime type is registered");
                    metadata.setTransportable(false);
                    metadata.setPersistable(false);
                }
                if (metadata != null) {
                    list.add(metadata);
                }
            }
        }
        list.sort(Comparator.comparing(FlowTypeMetadata::getId, Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER)));
        return list;
    }

    private boolean isLiteralType(FlowDataType type) {
        return type == FlowDataType.STRING
            || type == FlowDataType.NUMBER
            || type == FlowDataType.INTEGER
            || type == FlowDataType.FLOAT
            || type == FlowDataType.INSTANT
            || type == FlowDataType.DURATION
            || type == FlowDataType.BOOLEAN
            || type == FlowDataType.UUID
            || type == FlowDataType.COLOR
            || type == FlowDataType.RGB_COLOR
            || type == FlowDataType.NAMED_TEXT_COLOR
            || type == FlowDataType.MATERIAL
            || type == FlowDataType.BIOME
            || type == FlowDataType.ENTITY_TYPE
            || type == FlowDataType.GAMEMODE
            || type == FlowDataType.DIFFICULTY
            || type == FlowDataType.SOUND
            || type.getJavaType() != null && type.getJavaType().isEnum();
    }

    private boolean isObjectType(FlowDataType type) {
        return type == FlowDataType.PLAYER
            || type == FlowDataType.ENTITY
            || type == FlowDataType.LIVING_ENTITY
            || type == FlowDataType.NPC_HANDLE
            || type == FlowDataType.WORLD
            || type == FlowDataType.BLOCK
            || type == FlowDataType.LOCATION
            || type == FlowDataType.INVENTORY
            || type == FlowDataType.ITEMSTACK
            || type == FlowDataType.GUI_DEFINITION
            || type == FlowDataType.SCOREBOARD_DEFINITION
            || type == FlowDataType.TAB_DEFINITION
            || type == FlowDataType.CUSTOM_CONTENT_DEFINITION
            || type == FlowDataType.DIALOG_DEFINITION
            || type == FlowDataType.TRADE_PROFILE
            || type == FlowDataType.TRADE_DEFINITION
            || type == FlowDataType.LOOT_TABLE_DEFINITION
            || type == FlowDataType.LOOT_POOL_DEFINITION
            || type == FlowDataType.LOOT_ENTRY_DEFINITION
            || type == FlowDataType.NPC_DEFINITION
            || type == FlowDataType.ADVANCEMENT_TREE_DEFINITION
            || type == FlowDataType.RECIPE_DEFINITION
            || type == FlowDataType.RECIPE_INGREDIENT_DEFINITION
            || type.getJavaType() == JsonObject.class
            || type.getJavaType() == FlowResourceReference.class
            || type.getJavaType() == FlowJobReference.class
            || type.getJavaType() == GuiElement.class
            || type.getJavaType() == List.class
            || type.getJavaType() == Map.class;
    }

    private boolean isBoundarySafeType(FlowDataType type) {
        return valueCodecs.hasCodec(FlowTypeRef.simple(type.getId()));
    }

    private String literalEditor(FlowDataType type) {
        if (type == FlowDataType.BOOLEAN) {
            return "toggle";
        }
        if (type == FlowDataType.NUMBER || type == FlowDataType.INTEGER || type == FlowDataType.FLOAT
            || type == FlowDataType.INSTANT || type == FlowDataType.DURATION) {
            return "number";
        }
        if (type == FlowDataType.COLOR || type == FlowDataType.RGB_COLOR) {
            return "color";
        }
        if (catalogSource(type) != null) {
            return "searchable_list";
        }
        return isLiteralType(type) ? "text" : null;
    }

    private String catalogSource(FlowDataType type) {
        if (type == FlowDataType.MATERIAL) {
            return "server:minecraft:material";
        }
        if (type == FlowDataType.BIOME) {
            return "server:minecraft:biome";
        }
        if (type == FlowDataType.ENTITY_TYPE) {
            return "server:minecraft:entity_type";
        }
        if (type == FlowDataType.GAMEMODE) {
            return "server:minecraft:gamemode";
        }
        if (type == FlowDataType.DIFFICULTY) {
            return "server:minecraft:difficulty";
        }
        if (type == FlowDataType.SOUND) {
            return "server:minecraft:sound";
        }
        if (type == FlowDataType.NAMED_TEXT_COLOR) {
            return "server:minecraft:named_text_color";
        }
        if (type == FlowDataType.DISPLAY_SLOT) {
            return "server:minecraft:display_slot";
        }
        if (type == FlowDataType.TEXT_DECORATION) {
            return "server:minecraft:text_decoration";
        }
        if (type == FlowDataType.NETWORK_SCOPE) {
            return "server:resync:network_scope";
        }
        if (type == FlowDataType.NETWORK_NODE) {
            return "server:resync:network_node";
        }
        return null;
    }

    private String displayName(String id) {
        String[] words = id.replace(':', '_').split("_");
        StringBuilder displayName = new StringBuilder();
        for (String word : words) {
            if (word.isBlank()) {
                continue;
            }
            if (!displayName.isEmpty()) {
                displayName.append(' ');
            }
            displayName.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return displayName.toString();
    }

    private List<FlowCategoryMetadata> buildCategoryMetadata() {
        List<FlowCategoryMetadata> list = new ArrayList<>();
        for (NodeDefinition.NodeCategory cat : NodeDefinition.NodeCategory.values()) {
            list.add(new FlowCategoryMetadata(cat.getId(), cat.getDisplayName(), cat.getColor(), cat.getPriority()));
        }
        if (extensionData != null) {
            list.addAll(extensionData.categories());
        }
        list.sort(Comparator.comparing(FlowCategoryMetadata::getId, Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER)));
        return list;
    }

    List<FlowOptionSourceMetadata> buildOptionSourceMetadata() {
        Map<String, FlowOptionSourceMetadata> metadata = new HashMap<>();
        if (optionCatalogRegistry != null) {
            for (OptionCatalogProvider provider : optionCatalogRegistry.providers()) {
                metadata.put(provider.sourceId(), new FlowOptionSourceMetadata(provider.sourceId(), provider.providerId(), provider.widgetType(), provider.searchable(), "", "string",
                    provider.contextKeys() != null ? provider.contextKeys().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList() : List.of()));
            }
        }
        List<FlowOptionSourceMetadata> list = new ArrayList<>(metadata.values());
        list.sort((left, right) -> String.CASE_INSENSITIVE_ORDER.compare(left.getId(), right.getId()));
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
                boolean lossy = "string".equals(targetId)
                    || "number".equals(sourceId) && ("integer".equals(targetId) || "float".equals(targetId));
                list.add(new FlowConversionRule(sourceId, targetId, "builtin:adapter/" + sourceId + "-to-" + targetId,
                    !lossy, lossy, lossy ? 3 : 1, null));
            }
        }
        for (Map.Entry<Class<?>, Function<String, ?>> entry : adapters.getStringParsers().entrySet()) {
            String targetId = classToType.get(entry.getKey().getName());
            if (targetId != null && !targetId.equals("string")) {
                list.add(new FlowConversionRule("string", targetId, "builtin:parser/string-to-" + targetId, false, false, 2, null));
            }
        }
        for (FlowDataType type : FlowDataType.values().stream().sorted(Comparator.comparing(FlowDataType::getId)).toList()) {
            if (type.getParent() == FlowDataType.RESOURCE_REFERENCE) {
                list.add(new FlowConversionRule("string", type.getId(), "builtin:adapter/string-to-resource-reference", false, true, 4, null));
            }
        }
        list.add(new FlowConversionRule("number", "instant", "builtin:temporal/epoch-milliseconds", true, false, 1, null));
        list.add(new FlowConversionRule("instant", "number", "builtin:temporal/epoch-milliseconds", true, false, 1, null));
        list.add(new FlowConversionRule("number", "duration", "builtin:temporal/duration-milliseconds", true, false, 1, null));
        list.add(new FlowConversionRule("duration", "number", "builtin:temporal/duration-milliseconds", true, false, 1, null));
        if (extensionData != null) {
            for (FlowConversionRule rule : extensionData.conversions()) {
                if (rule != null && (rule.getAvailability() == null || rule.getAvailability().isBlank())) {
                    rule.setAvailability("unavailable: No executable conversion adapter is registered");
                }
                if (rule != null) {
                    list.add(rule);
                }
            }
        }
        list.sort(Comparator.comparing(FlowConversionRule::getSourceTypeId, Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER))
            .thenComparing(FlowConversionRule::getTargetTypeId, Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER))
            .thenComparingInt(FlowConversionRule::getCost)
            .thenComparing(FlowConversionRule::getImplementationId, Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER)));
        return list;
    }

    private Map<String, String> buildClassToTypeMap() {
        Map<String, String> map = new HashMap<>();
        for (FlowDataType type : FlowDataType.values().stream().sorted(Comparator.comparing(FlowDataType::getId)).toList()) {
            if (type.getJavaType() != null) {
                map.putIfAbsent(type.getJavaType().getName(), type.getId());
            }
            if (type.getDataClass() != null) {
                map.putIfAbsent(type.getDataClass().getName(), type.getId());
            }
        }
        map.put(JsonObject.class.getName(), "json_object");
        map.put(Map.class.getName(), "map");
        map.put(FlowResourceReference.class.getName(), "resource_reference");
        map.put(FlowJobReference.class.getName(), "job_reference");
        map.put(String.class.getName(), "string");
        map.put(Number.class.getName(), "number");
        map.put(Boolean.class.getName(), "boolean");
        map.put(Integer.class.getName(), "integer");
        map.put(Long.class.getName(), "number");
        map.put(Double.class.getName(), "number");
        map.put(Float.class.getName(), "float");
        return map;
    }
}
