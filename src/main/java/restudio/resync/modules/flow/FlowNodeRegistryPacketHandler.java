package restudio.resync.modules.flow;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
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
import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.catalog.CatalogOwned;
import restudio.resync.flow.catalog.CatalogCanonicalizer;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.canonical.CanonicalLimits;
import restudio.resync.flow.handler.property.PropertyRegistry;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.contract.FlowCategoryMetadata;
import restudio.resync.flow.sync.FlowConversionRule;
import restudio.resync.flow.sync.FlowOptionSourceMetadata;
import restudio.resync.flow.sync.FlowPropertyMetadata;
import restudio.resync.flow.sync.FlowResourceMetadata;
import restudio.resync.flow.contract.FlowTypeMetadata;
import restudio.resync.flow.sync.NodePluginPayload;
import restudio.resync.flow.sync.NodeRegistryPinSerializer;
import restudio.resync.flow.sync.NodeRegistryRequest;
import restudio.resync.flow.sync.NodeRegistrySnapshot;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.protocol.ReSyncProtocolContract;
import restudio.resync.resources.ReSyncResourceCatalog;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Array;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

public class FlowNodeRegistryPacketHandler {
    private static final long REGISTRY_COMPATIBILITY_WINDOW_MILLIS = 30L * 24 * 60 * 60 * 1000;
    public static final String LEGACY_COMPATIBILITY_CAPABILITY = NodeRegistryRequest.LEGACY_COMPATIBILITY_CAPABILITY;
    public static final String LEGACY_AUTHORITY_DISABLED_DIAGNOSTIC = "CATALOG_LEGACY_AUTHORITY_DISABLED";
    public static final String LEGACY_AUTHORITY_DISABLED_MESSAGE = "The legacy node registry is disabled; request the typed catalog publication";
    private static final List<String> REGISTRY_CAPABILITIES = List.of("nodes", "types", "categories", "properties", "resources", "catalogs", "catalog_authority", "catalog_canonical", "opaque_registry_data", "conversions", "extensions", "deltas", "diagnostics", "contextual_catalogs", "authorization", "destructive_safety", "function_tests", "jobs", "job_events", "resource_operation_diagnostics", "extension_validators");
    private final NodeDefinitionRegistry definitionRegistry;
    private final FlowPacketSender sender;
    private final PropertyRegistry propertyRegistry;
    private final ReSyncExtensionData extensionData;
    private final OptionCatalogRegistry optionCatalogRegistry;
    private final FlowResourceRegistry resourceRegistry;
    private final FlowValueCodecRegistry valueCodecs;
    private volatile TypeAdapterRegistry conversionAdapters;
    private Supplier<Map<String, Object>> diagnosticsSupplier = Map::of;
    private volatile Supplier<ActiveCatalogMetadata> activeCatalogMetadataSupplier = ActiveCatalogMetadata::unavailable;
    private volatile ActiveCatalogMetadata lastActiveCatalogMetadata = ActiveCatalogMetadata.unavailable();
    private volatile boolean catalogAuthorityConfigured;
    private volatile boolean canonicalCatalogAuthorityRequired;
    private volatile boolean typedCatalogPublicationAuthority;
    private volatile ServerId canonicalServerIdentity;
    private final Gson gson = new GsonBuilder()
        .registerTypeAdapter(FlowDataType.class, new FlowDataTypeAdapter())
        .registerTypeAdapter(NodeDefinition.PinDefinition.class, new NodeRegistryPinSerializer())
        .registerTypeAdapter(NodeDefinition.NodeCategory.class,
            (JsonSerializer<NodeDefinition.NodeCategory>) (category, type, context) ->
                category == null ? JsonNull.INSTANCE : new JsonPrimitive(category.getId()))
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
                JsonElement parsed = JsonParser.parseString(json);
                request = gson.fromJson(parsed, NodeRegistryRequest.class);
            }
        } catch (Exception e) {
            Log.warn("Failed to parse node registry request: " + e.getMessage());
        }
        if (typedCatalogPublicationAuthority && (request == null || !request.requestsLegacyCompatibility())) {
            if (session != null) {
                sender.sendError(session, LEGACY_AUTHORITY_DISABLED_DIAGNOSTIC, LEGACY_AUTHORITY_DISABLED_MESSAGE);
            }
            return;
        }
        sender.sendNodeRegistrySnapshot(session, buildSnapshot(request));
    }

    public void requireTypedCatalogPublicationAuthority() {
        typedCatalogPublicationAuthority = true;
    }

    public boolean typedCatalogPublicationAuthorityRequired() {
        return typedCatalogPublicationAuthority;
    }

    static boolean legacyCompatibilityNegotiated(NodeRegistryRequest request) {
        return request != null && request.requestsLegacyCompatibility();
    }

    NodeRegistrySnapshot buildSnapshot(NodeRegistryRequest request) {
        NodeRegistrySnapshot snapshot = new NodeRegistrySnapshot();
        populateCanonicalServerIdentity(snapshot, request);
        ActiveCatalogMetadata catalogMetadata = activeCatalogMetadataSnapshot();
        ActiveCatalogProjection activeProjection = activeCatalogProjection(catalogMetadata);
        snapshot.setContractVersion(ReSyncProtocolContract.FLOW_CONTRACT.version());
        snapshot.setMinimumClientContractVersion(ReSyncProtocolContract.FLOW_CONTRACT.minimumClientVersion());
        snapshot.setCompatibleUntil(System.currentTimeMillis() + REGISTRY_COMPATIBILITY_WINDOW_MILLIS);
        snapshot.setCapabilities(REGISTRY_CAPABILITIES);
        Map<String, String> clientChecksums = request != null ? request.getPluginChecksums() : Map.of();
        boolean compatibleRequest = request != null
            && request.getContractVersion() >= ReSyncProtocolContract.FLOW_CONTRACT.minimumClientVersion()
            && request.getContractVersion() <= ReSyncProtocolContract.FLOW_CONTRACT.version();
        boolean fullSync = !compatibleRequest || request.getRegistryChecksum().isBlank();
        snapshot.setFullSync(fullSync);
        if (!fullSync) {
            snapshot.setBaseRegistryChecksum(request.getRegistryChecksum());
        }

        List<NodeDefinition> definitions = new ArrayList<>(definitionRegistry.getAllDefinitions().values());
        if (activeProjection.authoritative()) {
            definitions.removeIf(definition -> !activeProjection.includes(definition));
        }
        List<String> nodeIds = activeProjection.authoritative()
            ? new ArrayList<>(activeProjection.nodeIds())
            : new ArrayList<>(definitions.stream().map(NodeDefinition::getId).distinct().toList());
        nodeIds.sort(String.CASE_INSENSITIVE_ORDER);
        snapshot.setNodeIds(nodeIds);

        List<NodePluginPayload> pluginPayloads = new ArrayList<>();
        List<String> pluginIds = new ArrayList<>(getPluginIds());
        pluginIds.sort(String.CASE_INSENSITIVE_ORDER);
        for (String pluginId : pluginIds) {
            String checksum = getChecksum(pluginId, activeProjection);
            String clientChecksum = clientChecksums != null ? clientChecksums.get(pluginId) : null;
            if (fullSync || checksum == null || !checksum.equals(clientChecksum)) {
                NodePluginPayload payload = buildPayload(pluginId, activeProjection, catalogMetadata);
                if (payload != null) {
                    pluginPayloads.add(payload);
                }
            }
        }
        snapshot.setPlugins(pluginPayloads);

        List<String> removed = new ArrayList<>();
        if (clientChecksums != null) {
            Set<String> serverPluginIds = activePluginIds(activeProjection);
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
        populateCatalogMetadata(snapshot, catalogMetadata, activeProjection);
        snapshot.setRegistryDiagnostics(diagnosticsSnapshot());
        stampRegistry(snapshot, definitions, activeProjection);
        Map<String, Object> compatibilityMetadata = new LinkedHashMap<>(snapshot.getCatalogMetadata());
        compatibilityMetadata.put("activeAuthority", typedCatalogPublicationAuthority ? "catalog-cache-publication" : "node-registry");
        compatibilityMetadata.put("compatibilityOnly", typedCatalogPublicationAuthority);
        compatibilityMetadata.put("compatibilityCapability", LEGACY_COMPATIBILITY_CAPABILITY);
        compatibilityMetadata.put("legacyAuthorityDiagnostic", LEGACY_AUTHORITY_DISABLED_DIAGNOSTIC);
        snapshot.setCatalogMetadata(compatibilityMetadata);
        return snapshot;
    }

    public NodeRegistrySnapshot buildFullSnapshot() {
        return buildSnapshot(null);
    }

    public void setDiagnosticsSupplier(Supplier<Map<String, Object>> diagnosticsSupplier) {
        this.diagnosticsSupplier = diagnosticsSupplier != null ? diagnosticsSupplier : Map::of;
    }

    public void setCanonicalServerIdentity(ServerId serverIdentity) {
        if (serverIdentity == null) {
            throw new IllegalArgumentException("Canonical server identity is required");
        }
        this.canonicalServerIdentity = serverIdentity;
    }

    private void populateCanonicalServerIdentity(NodeRegistrySnapshot snapshot, NodeRegistryRequest request) {
        ServerId serverIdentity = canonicalServerIdentity;
        if (serverIdentity == null) {
            if (legacyCompatibilityNegotiated(request)) {
                throw new IllegalStateException("Canonical server identity is unavailable");
            }
            return;
        }
        snapshot.setServerIdentity(serverIdentity.canonicalText());
    }

    public void setConversionAdapterRegistry(TypeAdapterRegistry conversionAdapters) {
        this.conversionAdapters = conversionAdapters;
    }

    public void setActiveCatalogMetadata(ActiveCatalogMetadata metadata) {
        ActiveCatalogMetadata value = metadata != null ? metadata : ActiveCatalogMetadata.unavailable();
        if (value.generation() >= 0) {
            lastActiveCatalogMetadata = value;
        }
        catalogAuthorityConfigured = true;
        setActiveCatalogMetadataSupplier(() -> value);
    }

    public void setActiveCatalogMetadataSupplier(Supplier<ActiveCatalogMetadata> supplier) {
        catalogAuthorityConfigured = true;
        this.activeCatalogMetadataSupplier = supplier != null ? supplier : ActiveCatalogMetadata::unavailable;
    }

    public void requireCanonicalCatalogAuthority() {
        canonicalCatalogAuthorityRequired = true;
    }

    public static ActiveCatalogMetadata activeCatalogMetadata(CatalogSnapshot snapshot) {
        if (snapshot == null) {
            return ActiveCatalogMetadata.unavailable();
        }
        List<Map<String, Object>> drops = new ArrayList<>();
        List<Map<String, Object>> boundaries = new ArrayList<>();
        for (CatalogOwned<CatalogNodeDescriptor> owned : snapshot.definitions()) {
            deriveDropContributions(owned, drops);
            deriveFunctionBoundary(owned, boundaries);
        }
        List<String> activeNodeIds = snapshot.definitions().stream()
            .map(FlowNodeRegistryPacketHandler::authoredNodeId)
            .distinct()
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
        List<Map<String, Object>> activeNodeReferences = snapshot.definitions().stream()
            .sorted(Comparator.comparing((CatalogOwned<CatalogNodeDescriptor> value) -> value.key().owner())
                .thenComparing(value -> value.descriptor().id().value()))
            .map(FlowNodeRegistryPacketHandler::ownerQualifiedNodeReference)
            .toList();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("bindingManifestHash", snapshot.bindingManifestHash().canonicalText());
        metadata.put("catalogCanonicalContent", snapshot.canonicalContent());
        metadata.put("catalogContractVersion", Map.of(
            "generation", snapshot.contractVersion().generation(),
            "minor", snapshot.contractVersion().minor()));
        metadata.put("catalogBinding", Map.of(
            "generation", snapshot.generation(),
            "catalogChecksum", snapshot.contentChecksum().canonicalText(),
            "bindingManifestHash", snapshot.bindingManifestHash().canonicalText()));
        metadata.put("activeNodeIds", activeNodeIds);
        metadata.put("activeNodeReferences", activeNodeReferences);
        Map<String, Object> projection = new LinkedHashMap<>();
        projection.put("catalogGeneration", snapshot.generation());
        projection.put("catalogChecksum", snapshot.contentChecksum().canonicalText());
        projection.put("bindingManifestHash", snapshot.bindingManifestHash().canonicalText());
        projection.put("catalogBindingManifestHash", snapshot.bindingManifestHash().canonicalText());
        projection.put("activeNodeIds", activeNodeIds);
        projection.put("activeNodeReferences", activeNodeReferences);
        metadata.put("registryProjection", projection);
        metadata.put("registryProjectionCanonical", CanonicalJson.canonicalize(projection));
        metadata.put("registryProjectionHash", CanonicalJson.sha256("registry-projection", projection));
        return ActiveCatalogMetadata.of(snapshot.generation(), snapshot.contentChecksum().canonicalText(), drops, boundaries,
            metadata);
    }

    private static Map<String, Object> ownerQualifiedNodeReference(CatalogOwned<CatalogNodeDescriptor> owned) {
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("owner", owned.key().owner().canonicalText());
        reference.put("descriptorId", owned.descriptor().id().value());
        reference.put("nodeId", authoredNodeId(owned));
        Object sourceOwner = owned.descriptor().metadata().get("sourceOwner");
        reference.put("sourceOwner", sourceOwner instanceof String value && !value.isBlank()
            ? value : owned.key().owner().canonicalText());
        Object customFunctionIdentity = owned.descriptor().metadata().get("customFunctionIdentity");
        if (customFunctionIdentity instanceof Map<?, ?>) {
            reference.put("customFunctionIdentity", customFunctionIdentity);
        }
        return Collections.unmodifiableMap(reference);
    }

    private static void deriveDropContributions(CatalogOwned<CatalogNodeDescriptor> owned, List<Map<String, Object>> target) {
        CatalogNodeDescriptor descriptor = owned.descriptor();
        for (CatalogNodeDescriptor.Pin pin : descriptor.pins()) {
            if (pin.direction() != CatalogNodeDescriptor.Direction.INPUT || !(pin.type() instanceof TypeExpr.ResourceType resource)) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("resourceType", resource.resourceType().localId());
            entry.put("resourceTypeOwner", resource.resourceType().ownerId());
            entry.put("resourceTypeIdentity", resourceIdentity(resource.resourceType()));
            entry.put("capability", pin.resourceRole() != null && !pin.resourceRole().isBlank() ? pin.resourceRole() : "reference");
            entry.put("owner", owned.key().owner().canonicalText());
            entry.put("nodeId", authoredNodeId(owned));
            entry.put("inputPin", pin.id().value());
            entry.put("referenceKind", resourceIdentity(resource.resourceType()));
            entry.put("referenceOwner", resource.resourceType().ownerId());
            entry.put("priority", 0);
            target.add(Collections.unmodifiableMap(entry));
        }
    }

    private static void deriveFunctionBoundary(CatalogOwned<CatalogNodeDescriptor> owned, List<Map<String, Object>> target) {
        CatalogNodeDescriptor descriptor = owned.descriptor();
        String authoredNodeId = authoredNodeId(owned);
        String owner = owned.key().owner().canonicalText();
        String id = owned.key().id().canonicalText();
        String role = functionBoundaryRole(descriptor, owner, id);
        if (role == null) {
            return;
        }
        CatalogNodeDescriptor.Direction parameterDirection = "inputs".equals(role)
            ? CatalogNodeDescriptor.Direction.OUTPUT : CatalogNodeDescriptor.Direction.INPUT;
        String flowPin = descriptor.pins().stream()
            .filter(pin -> pin.direction() == parameterDirection && isExecutionType(pin.type()))
            .map(CatalogNodeDescriptor.Pin::id)
            .map(value -> value.value())
            .findFirst()
            .orElse("flow");
        List<Map<String, Object>> parameters = descriptor.pins().stream()
            .filter(pin -> pin.direction() == parameterDirection && !isExecutionType(pin.type()))
            .map(pin -> functionParameterPin(descriptor, pin))
            .toList();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("role", role);
        entry.put("owner", owned.key().owner().canonicalText());
        Object sourceOwner = descriptor.metadata().get("sourceOwner");
        entry.put("sourceOwner", sourceOwner instanceof String value && !value.isBlank()
            ? value : owned.key().owner().canonicalText());
        entry.put("descriptorId", descriptor.id().value());
        entry.put("nodeReference", owned.key().owner().canonicalText() + "/" + descriptor.id().value());
        entry.put("nodeId", authoredNodeId);
        entry.put("flowPin", flowPin);
        entry.put("parameterPins", parameters);
        Object legacyIds = descriptor.metadata().get("legacyIds");
        if (legacyIds instanceof List<?> values && !values.isEmpty()) {
            entry.put("legacyNodeReferences", values);
        }
        target.add(Collections.unmodifiableMap(entry));
    }

    private static String functionBoundaryRole(CatalogNodeDescriptor descriptor, String owner, String id) {
        Object boundary = descriptor.metadata().get("functionBoundary");
        if (boundary instanceof Map<?, ?> metadata) {
            String role = textValue(metadata.get("role"));
            if ("inputs".equals(role) || "outputs".equals(role)) {
                return role;
            }
        }
        if (("builtin".equals(owner) || "restudio.resync".equals(owner))
            && (id.equals("function.start") || id.equals("function_start"))) {
            return "inputs";
        }
        if (("builtin".equals(owner) || "restudio.resync".equals(owner))
            && (id.equals("function.end") || id.equals("function_end"))) {
            return "outputs";
        }
        return null;
    }

    private static Map<String, Object> functionParameterPin(CatalogNodeDescriptor descriptor, CatalogNodeDescriptor.Pin pin) {
        Map<String, Object> parameter = new LinkedHashMap<>();
        parameter.put("id", pin.id().value());
        parameter.put("name", pin.displayName());
        parameter.put("typeRef", typeName(pin.type()));
        parameter.put("runtimeName", runtimePinName(descriptor, pin));
        return Collections.unmodifiableMap(parameter);
    }

    private static String runtimePinName(CatalogNodeDescriptor descriptor, CatalogNodeDescriptor.Pin pin) {
        Object pins = descriptor.metadata().get(pin.direction() == CatalogNodeDescriptor.Direction.INPUT ? "inputs" : "outputs");
        if (!(pins instanceof Iterable<?> values)) {
            return pin.id().value();
        }
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> metadata)) {
                continue;
            }
            String metadataId = textValue(metadata.get("id"));
            String metadataName = textValue(metadata.get("name"));
            String stableId = !metadataId.isBlank() ? metadataId : metadataName;
            if (!pin.id().value().equals(stableId)) {
                continue;
            }
            String runtimeName = textValue(metadata.get("runtimeName"));
            if (!runtimeName.isBlank()) {
                return runtimeName;
            }
            if (!metadataId.isBlank() && !metadataName.isBlank()) {
                return metadataName;
            }
            return pin.id().value();
        }
        return pin.id().value();
    }

    private static boolean isExecutionType(TypeExpr type) {
        return type instanceof TypeExpr.Named named && "execution".equals(named.reference().localId());
    }

    private static String authoredNodeId(CatalogOwned<CatalogNodeDescriptor> owned) {
        Object sourceNodeId = owned.descriptor().metadata().get("sourceNodeId");
        return sourceNodeId instanceof String value && !value.isBlank() ? value : owned.descriptor().id().value();
    }

    private static String typeName(TypeExpr type) {
        return switch (type) {
            case TypeExpr.Named named -> named.reference().localId();
            case TypeExpr.OptionalType optional -> "optional<" + typeName(optional.element()) + ">";
            case TypeExpr.ListType list -> "list<" + typeName(list.element()) + ">";
            case TypeExpr.MapType map -> "map<" + typeName(map.key()) + "," + typeName(map.value()) + ">";
            case TypeExpr.ResultType result -> "result<" + typeName(result.success()) + "," + typeName(result.failure()) + ">";
            case TypeExpr.ResourceType resource -> "resource_reference<" + resourceIdentity(resource.resourceType()) + ">";
            case TypeExpr.TupleType tuple -> "tuple";
            case TypeExpr.UnionType union -> "any";
            case TypeExpr.OpaqueType opaque -> opaque.reference().localId();
        };
    }

    private static String resourceIdentity(TypeReference reference) {
        return "builtin".equals(reference.ownerId()) ? reference.localId() : reference.ownerId() + ":" + reference.localId();
    }

    private Map<String, Object> diagnosticsSnapshot() {
        try {
            Map<String, Object> diagnostics = diagnosticsSupplier.get();
            return diagnostics != null ? diagnostics : Map.of();
        } catch (RuntimeException exception) {
            return Map.of("diagnosticsError", exception.getMessage() != null ? exception.getMessage() : exception.getClass().getSimpleName());
        }
    }

    private void stampRegistry(NodeRegistrySnapshot snapshot, List<NodeDefinition> definitions, ActiveCatalogProjection activeProjection) {
        snapshot.setGeneratedAt(System.currentTimeMillis());
        snapshot.setRegistryChecksum(computeRegistryChecksum(definitions, snapshot, activeProjection));
    }

    public String computeRegistryChecksum() {
        ActiveCatalogMetadata catalogMetadata = activeCatalogMetadataSnapshot();
        ActiveCatalogProjection activeProjection = activeCatalogProjection(catalogMetadata);
        List<NodeDefinition> definitions = new ArrayList<>(definitionRegistry.getAllDefinitions().values());
        if (activeProjection.authoritative()) {
            definitions.removeIf(definition -> !activeProjection.includes(definition));
        }
        NodeRegistrySnapshot snapshot = new NodeRegistrySnapshot();
        snapshot.setContractVersion(ReSyncProtocolContract.FLOW_CONTRACT.version());
        snapshot.setCapabilities(REGISTRY_CAPABILITIES);
        populatePropertyMetadata(snapshot);
        if (resourceRegistry != null) {
            snapshot.setResourceMetadata(resourceRegistry.metadata());
        }
        populateServerMetadata(snapshot);
        populateCatalogMetadata(snapshot, catalogMetadata, activeProjection);
        return computeRegistryChecksum(definitions, snapshot, activeProjection);
    }

    public List<NodePluginPayload> buildPluginPayloads() {
        ActiveCatalogMetadata catalogMetadata = activeCatalogMetadataSnapshot();
        ActiveCatalogProjection activeProjection = activeCatalogProjection(catalogMetadata);
        List<NodePluginPayload> payloads = new ArrayList<>();
        List<String> pluginIds = new ArrayList<>(getPluginIds());
        pluginIds.sort(String.CASE_INSENSITIVE_ORDER);
        for (String pluginId : pluginIds) {
            NodePluginPayload payload = buildPayload(pluginId, activeProjection);
            if (payload != null) {
                payloads.add(payload);
            }
        }
        return payloads;
    }

    private String computeRegistryChecksum(List<NodeDefinition> definitions, NodeRegistrySnapshot snapshot, ActiveCatalogProjection activeProjection) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            definitions.stream()
                .sorted(Comparator.comparing((NodeDefinition value) -> value.getOwner(), String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(NodeDefinition::getId, String.CASE_INSENSITIVE_ORDER))
                .forEach(definition -> {
                    updateDigest(digest, definitionIdentity(definition));
                    updateDefinitionDigest(digest, definition);
                });
            List<String> pluginIds = new ArrayList<>(getPluginIds());
            pluginIds.sort(String.CASE_INSENSITIVE_ORDER);
            ActiveCatalogProjection servedProjection = activeProjection;
            for (String pluginId : pluginIds) {
                if (activeDefinitions(pluginId, servedProjection).isEmpty()) {
                    continue;
                }
                updateDigest(digest, pluginId);
                updateDigest(digest, getChecksum(pluginId, servedProjection));
                updateDigest(digest, extensionData != null ? extensionData.version(pluginId) : "builtin");
                updateDigest(digest, extensionData != null ? extensionData.description(pluginId) : "BuiltInNodeDefinitions");
            }
            updateCanonicalDigest(digest, snapshot.getContractVersion());
            updateCanonicalDigest(digest, snapshot.getCapabilities());
            updateCanonicalDigest(digest, snapshot.getCatalogGeneration());
            updateCanonicalDigest(digest, snapshot.getCatalogChecksum());
            updateCanonicalDigest(digest, snapshot.getCatalogProjectionIdentity());
            updateCanonicalDigest(digest, snapshot.getDropContributions());
            updateCanonicalDigest(digest, snapshot.getFunctionBoundaries());
            updateCanonicalDigest(digest, snapshot.getCatalogMetadata());
            updateCanonicalDigest(digest, snapshot.getOpaqueData());
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
        updateCanonicalDigest(digest, definition);
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

    private void populateCatalogMetadata(NodeRegistrySnapshot snapshot, ActiveCatalogMetadata metadata, ActiveCatalogProjection activeProjection) {
        snapshot.setCatalogGeneration(metadata.generation());
        snapshot.setCatalogChecksum(metadata.checksum());
        snapshot.setDropContributions(metadata.dropContributions());
        snapshot.setFunctionBoundaries(metadata.functionBoundaries());
        Map<String, Object> catalogMetadata = new LinkedHashMap<>(metadata.metadata());
        Set<String> activeNodeIds = activeProjection.nodeIds();
        List<Map<String, Object>> pluginProjection = new ArrayList<>();
        List<String> pluginIds = new ArrayList<>(getPluginIds());
        pluginIds.sort(String.CASE_INSENSITIVE_ORDER);
        for (String pluginId : pluginIds) {
            String checksum = getChecksum(pluginId, activeProjection);
            if (checksum == null) {
                continue;
            }
            Map<String, Object> plugin = new LinkedHashMap<>();
            plugin.put("pluginId", pluginId);
            plugin.put("checksum", checksum);
            plugin.put("version", extensionData != null ? extensionData.version(pluginId) : "builtin");
            plugin.put("description", extensionData != null ? extensionData.description(pluginId) : "BuiltInNodeDefinitions");
            pluginProjection.add(Collections.unmodifiableMap(plugin));
        }
        Map<String, Object> projection = new LinkedHashMap<>();
        projection.put("catalogGeneration", snapshot.getCatalogGeneration());
        projection.put("catalogChecksum", snapshot.getCatalogChecksum());
        projection.put("catalogBindingManifestHash", catalogMetadata.getOrDefault("bindingManifestHash", ""));
        projection.put("runtimeBindingManifestHash", catalogMetadata.getOrDefault("runtimeBindingManifestHash", ""));
        projection.put("activeNodeIds", activeNodeIds.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList());
        projection.put("activeNodeReferences", catalogMetadata.getOrDefault("activeNodeReferences", List.of()));
        projection.put("dropContributions", snapshot.getDropContributions());
        projection.put("functionBoundaries", snapshot.getFunctionBoundaries());
        projection.put("plugins", pluginProjection);
        catalogMetadata.put("registryProjection", projection);
        catalogMetadata.put("registryProjectionCanonical", CanonicalJson.canonicalize(projection));
        catalogMetadata.put("registryProjectionHash", CanonicalJson.sha256("registry-projection", projection));
        String canonicalContent = textValue(catalogMetadata.get("catalogCanonicalContent"));
        if (canonicalCatalogAuthorityRequired && canonicalContent.isBlank()) {
            throw new IllegalStateException("Active flow catalog canonical content is unavailable");
        }
        if (!canonicalContent.isBlank()) {
            verifyCanonicalCatalogAuthority(metadata, canonicalContent);
            String bindingManifestHash = textValue(catalogMetadata.get("bindingManifestHash"));
            snapshot.setCatalogProjectionIdentity(bindingManifestHash);
            snapshot.setOpaqueData(catalogMetadata);
        } else if (metadata.generation() >= 0L) {
            snapshot.setCatalogProjectionIdentity(textValue(catalogMetadata.get("bindingManifestHash")));
            snapshot.setOpaqueData(Map.of(
                "authority", typedCatalogPublicationAuthority ? "catalog-cache-publication" : "legacy-compatibility",
                "compatibilityOnly", typedCatalogPublicationAuthority,
                "catalogGeneration", metadata.generation(),
                "catalogChecksum", metadata.checksum()));
        }
        snapshot.setCatalogMetadata(catalogMetadata);
    }

    private static void verifyCanonicalCatalogAuthority(ActiveCatalogMetadata metadata, String canonicalContent) {
        try {
            Object parsed = CanonicalJson.parse(canonicalContent, CanonicalLimits.catalog());
            if (!(parsed instanceof Map<?, ?> values)) {
                throw new IllegalStateException("Active catalog canonical content is not an object");
            }
            String calculatedChecksum = CatalogCanonicalizer.checksumForCanonicalContent(canonicalContent).canonicalText();
            String embeddedChecksum = textValue(values.get("contentChecksum"));
            String embeddedBindingManifestHash = textValue(values.get("bindingManifestHash"));
            Object embeddedGeneration = values.get("generation");
            String declaredBindingManifestHash = textValue(metadata.metadata().get("bindingManifestHash"));
            if (metadata.checksum().isBlank() || !metadata.checksum().equals(calculatedChecksum)
                || !metadata.checksum().equals(embeddedChecksum)
                || !(embeddedGeneration instanceof Number number) || number.longValue() != metadata.generation()
                || declaredBindingManifestHash.isBlank() || !declaredBindingManifestHash.equals(embeddedBindingManifestHash)) {
                throw new IllegalStateException("Active catalog canonical content does not match its binding proof");
            }
        } catch (RuntimeException exception) {
            throw exception instanceof IllegalStateException
                ? (IllegalStateException) exception
                : new IllegalStateException("Active catalog canonical content is invalid", exception);
        }
    }

    private ActiveCatalogMetadata activeCatalogMetadataSnapshot() {
        ActiveCatalogMetadata metadata = null;
        try {
            metadata = activeCatalogMetadataSupplier.get();
        } catch (RuntimeException exception) {
            Log.warn("Active flow catalog metadata is unavailable: " + exception.getMessage());
        }
        if (metadata != null && metadata.generation() >= 0) {
            verifyCatalogMetadataIfCanonical(metadata);
            lastActiveCatalogMetadata = metadata;
            return metadata;
        }
        if (catalogAuthorityConfigured && lastActiveCatalogMetadata.generation() < 0) {
            throw new IllegalStateException("Active flow catalog metadata is unavailable");
        }
        return lastActiveCatalogMetadata;
    }

    private static void verifyCatalogMetadataIfCanonical(ActiveCatalogMetadata metadata) {
        String canonicalContent = textValue(metadata.metadata().get("catalogCanonicalContent"));
        if (!canonicalContent.isBlank()) {
            verifyCanonicalCatalogAuthority(metadata, canonicalContent);
        }
    }

    private Set<String> getPluginIds() {
        return new HashSet<>(definitionRegistry.getPluginIds());
    }

    private NodePluginPayload buildPayload(String pluginId) {
        return buildPayload(pluginId, ActiveCatalogProjection.unrestricted(), ActiveCatalogMetadata.unavailable());
    }

    private NodePluginPayload buildPayload(String pluginId, ActiveCatalogProjection activeProjection) {
        return buildPayload(pluginId, activeProjection, activeCatalogMetadataSnapshot());
    }

    private NodePluginPayload buildPayload(String pluginId, ActiveCatalogProjection activeProjection, ActiveCatalogMetadata catalogMetadata) {
        List<NodeDefinition> definitions = activeDefinitions(pluginId, activeProjection);
        if (definitions.isEmpty()) {
            return null;
        }
        List<NodeDefinition> sortedDefinitions = new ArrayList<>(definitions);
        sortedDefinitions.sort(Comparator.comparing(NodeDefinition::getOwner, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(NodeDefinition::getId, String.CASE_INSENSITIVE_ORDER));
        NodePluginPayload payload = new NodePluginPayload();
        payload.setPluginId(pluginId);
        payload.setVersion(extensionData != null ? extensionData.version(pluginId) : "builtin");
        payload.setDescription(extensionData != null ? extensionData.description(pluginId) : "BuiltInNodeDefinitions");
        payload.setChecksum(computeDefinitionChecksum(definitions));
        payload.setNodes(sortedDefinitions);
        if (activeProjection.authoritative()) {
            Map<String, Object> authority = new LinkedHashMap<>();
            authority.put("authority", typedCatalogPublicationAuthority ? "catalog-cache-publication" : "catalog");
            authority.put("legacyCompatibility", true);
            authority.put("compatibilityOnly", typedCatalogPublicationAuthority);
            authority.put("compatibilityCapability", LEGACY_COMPATIBILITY_CAPABILITY);
            authority.put("catalogGeneration", catalogMetadata.generation());
            authority.put("catalogChecksum", catalogMetadata.checksum());
            authority.put("bindingManifestHash", textValue(catalogMetadata.metadata().get("bindingManifestHash")));
            authority.put("projectionHash", textValue(catalogMetadata.metadata().get("registryProjectionHash")));
            payload.setOpaqueData(authority);
        }
        return payload;
    }

    private String getChecksum(String pluginId) {
        return getChecksum(pluginId, ActiveCatalogProjection.unrestricted());
    }

    private String getChecksum(String pluginId, ActiveCatalogProjection activeProjection) {
        List<NodeDefinition> definitions = activeDefinitions(pluginId, activeProjection);
        return definitions.isEmpty() ? null : computeDefinitionChecksum(definitions);
    }

    private List<NodeDefinition> activeDefinitions(String pluginId, ActiveCatalogProjection activeProjection) {
        List<NodeDefinition> definitions = definitionRegistry.getDefinitionsForPlugin(pluginId);
        if (!activeProjection.authoritative()) {
            return definitions;
        }
        return definitions.stream().filter(activeProjection::includes).toList();
    }

    private Set<String> activePluginIds(ActiveCatalogProjection activeProjection) {
        Set<String> pluginIds = getPluginIds();
        if (!activeProjection.authoritative()) {
            return pluginIds;
        }
        pluginIds.removeIf(pluginId -> activeDefinitions(pluginId, activeProjection).isEmpty());
        return pluginIds;
    }

    private ActiveCatalogProjection activeCatalogProjection(ActiveCatalogMetadata metadata) {
        ActiveCatalogProjection projection = activeCatalogProjection(metadata, catalogAuthorityConfigured);
        validateActiveCatalogReferences(metadata, projection);
        return projection;
    }

    private void validateActiveCatalogReferences(ActiveCatalogMetadata metadata, ActiveCatalogProjection projection) {
        if (!projection.authoritative() || !projection.ownerQualified()) {
            return;
        }
        Object references = metadata.metadata().get("activeNodeReferences");
        if (!(references instanceof Iterable<?> iterable)) {
            throw new IllegalStateException("Active catalog owner-qualified references are unavailable");
        }
        List<NodeDefinition> definitions = new ArrayList<>(definitionRegistry.getAllDefinitions().values());
        Set<String> seenReferences = new HashSet<>();
        Set<String> registeredReferences = new HashSet<>();
        boolean coreCatalogAuthority = !textValue(metadata.metadata().get("catalogCanonicalContent")).isBlank();
        for (NodeDefinition definition : definitions) {
            if (definition == null) {
                throw new IllegalStateException("Active catalog registry contains a null node definition");
            }
            String registeredReference = definitionReference(definition);
            if (!registeredReferences.add(registeredReference)) {
                throw new IllegalStateException("Active catalog registry contains an ambiguous owner-qualified node reference: " + registeredReference.replace('\u0000', '/'));
            }
        }
        for (Object value : iterable) {
            if (!(value instanceof Map<?, ?> reference)) {
                throw new IllegalStateException("Active catalog contains an invalid owner-qualified node reference");
            }
            String owner = textValue(reference.get("owner"));
            String nodeId = textValue(reference.get("nodeId"));
            if (owner.isBlank() || nodeId.isBlank()) {
                throw new IllegalStateException("Active catalog contains an incomplete owner-qualified node reference");
            }
            String identity = definitionReference(owner, nodeId);
            if (!seenReferences.add(identity)) {
                throw new IllegalStateException("Active catalog owner-qualified node reference is duplicated or ambiguous: " + owner + "/" + nodeId);
            }
            long matches = definitions.stream()
                .filter(definition -> identity.equals(definitionReference(definition)))
                .count();
            if (matches == 0L) {
                throw new IllegalStateException("Active catalog owner-qualified node reference is missing: " + owner + "/" + nodeId);
            }
            if (matches != 1L) {
                throw new IllegalStateException("Active catalog owner-qualified node reference is ambiguous: " + owner + "/" + nodeId);
            }
        }
        if (coreCatalogAuthority && !registeredReferences.equals(seenReferences)) {
            Set<String> missingFromRegistry = new HashSet<>(seenReferences);
            missingFromRegistry.removeAll(registeredReferences);
            Set<String> missingFromCatalog = new HashSet<>(registeredReferences);
            missingFromCatalog.removeAll(seenReferences);
            throw new IllegalStateException("Core catalog and node registry references differ: missingFromRegistry="
                + formatReferences(missingFromRegistry) + ", missingFromCatalog=" + formatReferences(missingFromCatalog));
        }
    }

    private static String formatReferences(Set<String> references) {
        return references.stream()
            .map(value -> value.replace('\u0000', '/'))
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList()
            .toString();
    }

    private static ActiveCatalogProjection activeCatalogProjection(ActiveCatalogMetadata metadata, boolean authorityConfigured) {
        if (metadata == null || metadata.metadata() == null) {
            return authorityConfigured ? ActiveCatalogProjection.empty() : ActiveCatalogProjection.unrestricted();
        }
        Map<String, Object> values = metadata.metadata();
        Set<String> nodeIds = new HashSet<>();
        Set<String> typedReferences = new HashSet<>();
        boolean hasReferences = values.containsKey("activeNodeReferences");
        Object references = values.get("activeNodeReferences");
        if (references instanceof Iterable<?> iterable) {
            for (Object reference : iterable) {
                if (!(reference instanceof Map<?, ?> entry)) {
                    continue;
                }
                String nodeId = textValue(entry.get("nodeId"));
                if (nodeId.isBlank()) {
                    continue;
                }
                nodeIds.add(nodeId);
                String owner = textValue(entry.get("owner"));
                if (owner.isBlank()) {
                    owner = textValue(entry.get("sourceOwner"));
                }
                if (!owner.isBlank()) {
                    typedReferences.add(definitionReference(owner, nodeId));
                }
            }
        }
        if (!hasReferences && values.containsKey("activeNodeIds")) {
            Object ids = values.get("activeNodeIds");
            if (ids instanceof Iterable<?> iterable) {
                for (Object value : iterable) {
                    String nodeId = textValue(value);
                    if (!nodeId.isBlank()) {
                        nodeIds.add(nodeId);
                    }
                }
            }
        }
        if (!hasReferences && !values.containsKey("activeNodeIds") && authorityConfigured) {
            String canonicalContent = textValue(values.get("catalogCanonicalContent"));
            return canonicalContent.isBlank() ? ActiveCatalogProjection.unrestricted() : ActiveCatalogProjection.empty();
        }
        return new ActiveCatalogProjection(hasReferences || values.containsKey("activeNodeIds"), hasReferences, nodeIds, typedReferences);
    }

    private static String definitionReference(NodeDefinition definition) {
        return definitionReference(definition != null ? definition.getOwner() : "", definition != null ? definition.getId() : "");
    }

    private static String definitionIdentity(NodeDefinition definition) {
        return definitionReference(definition);
    }

    private static String definitionReference(String owner, String nodeId) {
        return owner + '\u0000' + nodeId;
    }

    private static String textValue(Object value) {
        return value instanceof String text ? text : "";
    }

    private record ActiveCatalogProjection(boolean authoritative, boolean ownerQualified, Set<String> nodeIds, Set<String> typedReferences) {
        private ActiveCatalogProjection {
            nodeIds = Set.copyOf(nodeIds);
            typedReferences = Set.copyOf(typedReferences);
        }

        private static ActiveCatalogProjection unrestricted() {
            return new ActiveCatalogProjection(false, false, Set.of(), Set.of());
        }

        private static ActiveCatalogProjection empty() {
            return new ActiveCatalogProjection(true, true, Set.of(), Set.of());
        }

        private boolean includes(NodeDefinition definition) {
            if (definition == null) {
                return false;
            }
            if (ownerQualified) {
                return typedReferences.contains(definitionReference(definition));
            }
            return nodeIds.contains(definition.getId());
        }
    }

    private String computeDefinitionChecksum(List<NodeDefinition> definitions) {
        List<NodeDefinition> sorted = new ArrayList<>(definitions);
        sorted.sort(Comparator.comparing((NodeDefinition value) -> value.getOwner(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(NodeDefinition::getId, String.CASE_INSENSITIVE_ORDER));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateCanonicalDigest(digest, sorted);
            byte[] hash = digest.digest();
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
        String domainName = switch (id) {
            case "resource_reference" -> "Resource";
            case "job_reference" -> "Job";
            case "flow_id" -> "Flow";
            case "function" -> "Function";
            case "command_id" -> "Command";
            case "custom_content_id" -> "Custom Content";
            case "gui_id" -> "GUI";
            case "scoreboard_id" -> "Scoreboard";
            case "tab_id" -> "Tab List";
            case "chat_id" -> "Chat Profile";
            case "motd_profile_id" -> "MOTD Profile";
            case "message_rule_id" -> "Message Rule";
            case "recipe_id" -> "Recipe";
            case "text_template_id" -> "Text Template";
            case "advancement_tree_id" -> "Advancement Tree";
            case "dialog_id" -> "Dialog";
            case "trade_profile_id" -> "Trade Profile";
            case "npc_id" -> "NPC";
            case "loot_table_id" -> "Loot Table";
            case "worldgen_id" -> "Worldgen Project";
            case "flow_definition" -> "Flow";
            case "function_definition" -> "Function";
            case "command_definition" -> "Command";
            case "gui_definition" -> "GUI";
            case "gui_session" -> "Open GUI";
            case "scoreboard_definition" -> "Scoreboard";
            case "sidebar_session" -> "Active Scoreboard";
            case "tab_definition" -> "Tab List";
            case "dialog_definition" -> "Dialog";
            case "npc_definition" -> "NPC";
            case "npc_handle" -> "Active NPC";
            case "chat_profile" -> "Chat Profile";
            case "motd_profile" -> "MOTD Profile";
            case "message_rule" -> "Message Rule";
            case "text_template" -> "Text Template";
            default -> "";
        };
        if (!domainName.isBlank()) {
            return domainName;
        }
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
            CategoryGroup group = categoryGroup(cat.getId());
            list.add(new FlowCategoryMetadata(cat.getId(), cat.getDisplayName(), cat.getColor(), cat.getPriority(), group.id(), group.name(), group.color(), group.priority()));
        }
        if (extensionData != null) {
            for (FlowCategoryMetadata category : extensionData.categories()) {
                if (category.getGroupId() == null || category.getGroupId().isBlank()) {
                    category.setGroupId("integrations");
                    category.setGroupName("Integrations");
                    category.setGroupColor(0xFF7289DA);
                    category.setGroupPriority(400);
                }
                list.add(category);
            }
        }
        list.sort(Comparator.comparingInt(FlowCategoryMetadata::getGroupPriority)
            .thenComparingInt(FlowCategoryMetadata::getPriority)
            .thenComparing(FlowCategoryMetadata::getDisplayName, Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER)));
        return list;
    }

    private CategoryGroup categoryGroup(String categoryId) {
        return switch (categoryId) {
            case "logic", "data", "variable", "flow", "function", "utility" -> new CategoryGroup("flow", "Flow", 0xFF55FFFF, 100);
            case "event", "action", "player", "entity", "block", "world", "inventory", "item", "visual" -> new CategoryGroup("minecraft", "Minecraft", 0xFF55AA55, 200);
            case "command", "network", "chat", "scoreboard", "trade", "npc", "loot", "menu", "tab_list", "dialog", "custom_content", "recipe", "advancement", "text", "permission", "ability" -> new CategoryGroup("resync", "ReSync", 0xFF5CC8FF, 300);
            default -> new CategoryGroup("integrations", "Integrations", 0xFF7289DA, 400);
        };
    }

    private record CategoryGroup(String id, String name, int color, int priority) {
    }

    List<FlowOptionSourceMetadata> buildOptionSourceMetadata() {
        Map<String, FlowOptionSourceMetadata> metadata = new HashMap<>();
        if (optionCatalogRegistry != null) {
            for (OptionCatalogProvider provider : optionCatalogRegistry.providers()) {
                FlowOptionSourceMetadata source = new FlowOptionSourceMetadata(provider.sourceId(), provider.providerId(), provider.widgetType(), provider.searchable(), "",
                    provider.runtimeDataType() != null ? provider.runtimeDataType().toString() : "string",
                    provider.contextKeys() != null ? provider.contextKeys().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList() : List.of());
                applyResourceSelectorIdentity(source);
                metadata.put(provider.sourceId(), source);
            }
        }
        List<FlowOptionSourceMetadata> list = new ArrayList<>(metadata.values());
        list.sort((left, right) -> String.CASE_INSENSITIVE_ORDER.compare(left.getId(), right.getId()));
        return list;
    }

    private void applyResourceSelectorIdentity(FlowOptionSourceMetadata source) {
        String resourceType = resourceTypeFromSource(source.getId());
        if (resourceType == null) {
            return;
        }
        FlowResourceMetadata resource = resourceRegistry != null ? resourceRegistry.metadata(resourceType) : null;
        if (resource == null) {
            source.setTyped(false);
            source.setAvailable(false);
            source.setUnavailableReason("No authoritative resource type is registered for this selector");
            return;
        }
        String owner = resource.getOwner();
        String declaredType = resource.getTypeId();
        if (owner == null || owner.isBlank() || declaredType == null || declaredType.isBlank()) {
            source.setTyped(false);
            source.setAvailable(false);
            source.setUnavailableReason("Resource selector owner and type are required");
            return;
        }
        if (resourceRegistry == null || !resourceRegistry.hasAuthoritativeAdapter(owner, declaredType)) {
            source.setTyped(false);
            source.setAvailable(false);
            source.setUnavailableReason("No authoritative lifecycle adapter is registered for this selector");
            return;
        }
        String localType = declaredType;
        int separator = localType.indexOf(':');
        if (separator >= 0) {
            localType = localType.substring(separator + 1);
        }
        source.setResourceTypeOwner(owner);
        source.setResourceTypeId(localType);
        source.setValueType(owner + ":" + localType);
        source.setTyped(true);
        source.setAvailable(true);
        source.setUnavailableReason("");
    }

    private String resourceTypeFromSource(String sourceId) {
        String prefix = "server:resync:";
        if (sourceId == null || !sourceId.startsWith(prefix)) {
            return null;
        }
        String type = sourceId.substring(prefix.length()).strip().toLowerCase(Locale.ROOT);
        if (type.isBlank()) {
            return null;
        }
        return ReSyncResourceCatalog.byType(type) != null || resourceRegistry != null && resourceRegistry.metadata(type) != null ? type : null;
    }

    private List<FlowConversionRule> buildConversionRules() {
        List<FlowConversionRule> list = new ArrayList<>();
        Map<String, String> classToType = buildClassToTypeMap();
        TypeAdapterRegistry adapters = conversionAdapters != null ? conversionAdapters : new TypeAdapterRegistry();
        for (Map.Entry<TypeAdapterRegistry.ClassPair, Function<Object, Object>> entry : adapters.getAdapters().entrySet()) {
            String sourceId = classToType.get(entry.getKey().getSource().getName());
            String targetId = classToType.get(entry.getKey().getTarget().getName());
            if (sourceId != null && targetId != null && !sourceId.equals(targetId) && !rawStringResourceConversion(sourceId, targetId)) {
                boolean lossy = "string".equals(targetId)
                    || "number".equals(sourceId) && ("integer".equals(targetId) || "float".equals(targetId));
                list.add(new FlowConversionRule(sourceId, targetId, "builtin:adapter/" + sourceId + "-to-" + targetId,
                    !lossy, lossy, lossy ? 3 : 1, null));
            }
        }
        for (Map.Entry<Class<?>, Function<String, ?>> entry : adapters.getStringParsers().entrySet()) {
            String targetId = classToType.get(entry.getKey().getName());
            Function<String, ?> parser = entry.getValue();
            if (targetId != null && !targetId.equals("string") && parser != null
                && !rawStringResourceConversion("string", targetId)) {
                list.add(new FlowConversionRule("string", targetId, "builtin:parser/string-to-" + targetId, false, false, 2, null));
            }
        }
        list.add(new FlowConversionRule("number", "instant", "builtin:temporal/epoch-milliseconds", true, false, 1, null));
        list.add(new FlowConversionRule("instant", "number", "builtin:temporal/epoch-milliseconds", true, false, 1, null));
        list.add(new FlowConversionRule("number", "duration", "builtin:temporal/duration-milliseconds", true, false, 1, null));
        list.add(new FlowConversionRule("duration", "number", "builtin:temporal/duration-milliseconds", true, false, 1, null));
        if (extensionData != null) {
            for (FlowConversionRule rule : extensionData.conversions()) {
                if (isReplacementConversion(rule, adapters)) {
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

    private boolean isReplacementConversion(FlowConversionRule rule, TypeAdapterRegistry adapters) {
        if (rule == null || adapters == null) {
            return false;
        }
        String sourceId = rule.getSourceTypeId();
        String targetId = rule.getTargetTypeId();
        if (sourceId == null || sourceId.isBlank() || targetId == null || targetId.isBlank()
            || sourceId.equalsIgnoreCase(targetId) || rawStringResourceConversion(sourceId, targetId)) {
            return false;
        }
        if (!"available".equalsIgnoreCase(rule.getAvailability())
            || rule.getImplementationId() == null || rule.getImplementationId().isBlank()) {
            return false;
        }
        if (conversionAdapters == null) {
            return false;
        }
        FlowDataType source = FlowDataType.fromString(sourceId);
        FlowDataType target = FlowDataType.fromString(targetId);
        if (!source.isResolved() || !target.isResolved()) {
            return false;
        }
        Set<Class<?>> sourceClasses = runtimeClasses(source);
        Set<Class<?>> targetClasses = runtimeClasses(target);
        for (Class<?> sourceClass : sourceClasses) {
            for (Class<?> targetClass : targetClasses) {
                if (adapters.getAdapters().containsKey(new TypeAdapterRegistry.ClassPair(sourceClass, targetClass))) {
                    return true;
                }
            }
        }
        if (sourceClasses.contains(String.class)) {
            for (Class<?> targetClass : targetClasses) {
                if (adapters.getStringParsers().get(targetClass) != null
                    && !rawStringResourceConversion(sourceId, targetId)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Set<Class<?>> runtimeClasses(FlowDataType type) {
        Set<Class<?>> classes = new HashSet<>();
        if (type.getJavaType() != null) {
            classes.add(type.getJavaType());
        }
        if (type.getDataClass() != null) {
            classes.add(type.getDataClass());
        }
        return classes;
    }

    private boolean rawStringResourceConversion(String sourceId, String targetId) {
        if (sourceId == null || !"string".equalsIgnoreCase(sourceId) || targetId == null) {
            return false;
        }
        FlowDataType target = FlowDataType.fromString(targetId);
        return target == FlowDataType.RESOURCE_REFERENCE || target.getParent() == FlowDataType.RESOURCE_REFERENCE;
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

    public record ActiveCatalogMetadata(long generation, String checksum,
                                        List<Map<String, Object>> dropContributions,
                                        List<Map<String, Object>> functionBoundaries,
                                        Map<String, Object> metadata) {
        public ActiveCatalogMetadata {
            checksum = checksum != null ? checksum.trim() : "";
            dropContributions = copyEntries(dropContributions);
            functionBoundaries = copyEntries(functionBoundaries);
            metadata = copyMap(metadata);
        }

        public static ActiveCatalogMetadata unavailable() {
            return new ActiveCatalogMetadata(-1L, "", List.of(), List.of(), Map.of());
        }

        public static ActiveCatalogMetadata of(long generation, String checksum,
                                                List<Map<String, Object>> dropContributions,
                                                List<Map<String, Object>> functionBoundaries) {
            return new ActiveCatalogMetadata(generation, checksum, dropContributions, functionBoundaries, Map.of());
        }

        public static ActiveCatalogMetadata of(long generation, String checksum,
                                                List<Map<String, Object>> dropContributions,
                                                List<Map<String, Object>> functionBoundaries,
                                                Map<String, Object> metadata) {
            return new ActiveCatalogMetadata(generation, checksum, dropContributions, functionBoundaries, metadata);
        }

        public String catalogChecksum() {
            return checksum;
        }

        public List<Map<String, Object>> functionBoundaryIntents() {
            return functionBoundaries;
        }

        private static List<Map<String, Object>> copyEntries(List<Map<String, Object>> values) {
            if (values == null || values.isEmpty()) {
                return List.of();
            }
            List<Map<String, Object>> copy = new ArrayList<>(values.size());
            for (Map<String, Object> value : values) {
                copy.add(value != null ? copyMap(value) : null);
            }
            return Collections.unmodifiableList(copy);
        }

        private static Map<String, Object> copyMap(Map<String, Object> value) {
            if (value == null || value.isEmpty()) {
                return Map.of();
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : value.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    throw new IllegalArgumentException("Catalog metadata keys must be non-blank strings");
                }
                copy.put(entry.getKey(), portable(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }

        private static Object portable(Object value) {
            if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Character) {
                return value;
            }
            if (value instanceof Enum<?> enumValue) {
                return enumValue.name();
            }
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key) || key.isBlank()) {
                        throw new IllegalArgumentException("Catalog metadata map keys must be non-blank strings");
                    }
                    copy.put(key, portable(entry.getValue()));
                }
                return Collections.unmodifiableMap(copy);
            }
            if (value instanceof Collection<?> collection) {
                return collection.stream().map(ActiveCatalogMetadata::portable).toList();
            }
            if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                List<Object> copy = new ArrayList<>(length);
                for (int index = 0; index < length; index++) {
                    copy.add(portable(Array.get(value, index)));
                }
                return Collections.unmodifiableList(copy);
            }
            throw new IllegalArgumentException("Unsupported catalog metadata value: " + value.getClass().getName());
        }
    }
}
