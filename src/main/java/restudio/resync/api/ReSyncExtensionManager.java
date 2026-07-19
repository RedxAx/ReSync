package restudio.resync.api;

import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.java.JavaPlugin;
import restudio.resync.Log;
import restudio.resync.customcontent.CustomContentProvider;
import restudio.resync.customcontent.CustomContentService;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.FlowValueCodec;
import restudio.resync.flow.FlowValueCodecRegistry;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowTypeRef;
import restudio.resync.modules.FlowModule;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.flow.handler.property.PropertyHandler;
import restudio.resync.flow.handler.property.PropertyRegistry;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionLoader;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.sync.FlowCategoryMetadata;
import restudio.resync.flow.sync.FlowConversionRule;
import restudio.resync.flow.sync.FlowOptionSourceMetadata;
import restudio.resync.flow.sync.FlowTypeMetadata;
import restudio.resync.flow.validation.FlowGraphValidationRegistry;
import restudio.resync.flow.validation.FlowGraphValidationRule;
import restudio.resync.modules.Module;
import restudio.resync.modules.ModuleContext;
import restudio.resync.modules.flow.FlowResourceAdapter;
import restudio.resync.modules.flow.FlowResourceRegistry;
import restudio.resync.world.WorldMapExtension;
import restudio.resync.world.WorldMapService;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class ReSyncExtensionManager {
    private static final long SCAN_INTERVAL_MS = 5000L;
    private final ModuleContext moduleContext;
    private final List<Path> extensionDirectories;
    private final Map<String, ExtensionState> extensions = new ConcurrentHashMap<>();
    private final Map<Path, JarState> jarStates = new ConcurrentHashMap<>();
    private long lastScan;

    public ReSyncExtensionManager(ModuleContext moduleContext, Path extensionDirectory) {
        this(moduleContext, List.of(extensionDirectory));
    }

    public ReSyncExtensionManager(ModuleContext moduleContext, List<Path> extensionDirectories) {
        this.moduleContext = moduleContext;
        this.extensionDirectories = extensionDirectories == null ? List.of() : List.copyOf(extensionDirectories);
        ensureDirectory();
    }

    public void loadInitialExtensions() {
        scanDirectory();
    }

    public void tick() {
        unloadDisabledOwners();
        long now = System.currentTimeMillis();
        if (now - lastScan < SCAN_INTERVAL_MS) {
            return;
        }
        lastScan = now;
        scanDirectory();
    }

    public ExtensionRegistration registerBukkitExtension(JavaPlugin owner, ReSyncExtension extension) {
        return registerExtension(owner, extension, null, null);
    }

    public void shutdown() {
        for (String pluginId : new ArrayList<>(extensions.keySet())) {
            unregister(pluginId);
        }
        for (JarState jarState : jarStates.values()) {
            close(jarState.classLoader);
        }
        jarStates.clear();
    }

    public Set<String> getPluginIds() {
        return Set.copyOf(extensions.keySet());
    }

    public List<Map<String, Object>> contributionInventory() {
        return extensions.values().stream()
            .sorted((first, second) -> String.CASE_INSENSITIVE_ORDER.compare(first.pluginId, second.pluginId))
            .map(state -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("pluginId", state.pluginId);
                item.put("version", nullToEmpty(state.extension.getVersion()));
                item.put("description", nullToEmpty(state.extension.getDescription()));
                item.put("owner", state.owner != null ? state.owner.getName() : moduleContext.getPlugin().getName());
                item.put("source", state.jarPath != null ? state.jarPath.toString() : "bukkit");
                item.put("nodes", sorted(state.nodeIds));
                item.put("handlers", sorted(state.handlerIds));
                item.put("modules", sorted(state.moduleIds));
                item.put("properties", state.propertyIds.stream().map(value -> value.family() + "." + value.property()).sorted(String.CASE_INSENSITIVE_ORDER).toList());
                item.put("catalogs", sorted(state.optionCatalogIds));
                item.put("runtimeDataAdapters", sorted(state.runtimeDataAdapterIds));
                item.put("types", sorted(state.typeIds));
                item.put("conversions", state.conversions.stream().map(value -> value.source().getName() + " -> " + value.target().getName()).sorted(String.CASE_INSENSITIVE_ORDER).toList());
                item.put("resources", sorted(state.resourceTypeIds));
                item.put("validators", sorted(state.validatorIds));
                item.put("customContentProviders", sorted(state.customContentProviderIds));
                item.put("worldMapExtensions", sorted(state.worldMapExtensionIds));
                item.put("listeners", state.listeners.size());
                item.put("disposition", "supported");
                item.put("requirements", List.of("EXT-001", "EXT-002", "EXT-006", "EXT-008"));
                return Map.copyOf(item);
            })
            .toList();
    }

    private List<String> sorted(Set<String> values) {
        return values.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public void reloadExtensions() {
        for (ExtensionState state : new ArrayList<>(extensions.values())) {
            try {
                state.extension.stop();
            } catch (Exception exception) {
                Log.warn("ReSync extension stop failed for " + state.pluginId + ": " + exception.getMessage());
            }
            cleanupState(state);
            state.clearOwnedIds();
            try {
                moduleContext.getRequiredService(ReSyncExtensionData.class).addPlugin(state.pluginId, state.extension.getVersion(), state.extension.getDescription());
                initializeExtension(state, new ExtensionContext(state));
            } catch (Exception exception) {
                extensions.remove(state.pluginId);
                cleanupState(state);
                Log.error("[ReSync] Failed to reload extension " + state.pluginId + ": " + exception.getMessage(), exception);
            }
        }
        refreshNodeRegistry();
    }

    private ExtensionRegistration registerExtension(JavaPlugin owner, ReSyncExtension extension, URLClassLoader classLoader, Path jarPath) {
        String pluginId = normalizePluginId(extension.getPluginId());
        if (extensions.containsKey(pluginId)) {
            throw new IllegalArgumentException("Duplicate ReSync extension id: " + pluginId);
        }
        ExtensionState state = new ExtensionState(pluginId, owner, extension, classLoader, jarPath);
        ExtensionContext context = new ExtensionContext(state);
        extensions.put(pluginId, state);
        try {
            moduleContext.getRequiredService(ReSyncExtensionData.class).addPlugin(pluginId, extension.getVersion(), extension.getDescription());
            initializeExtension(state, context);
            refreshNodeRegistry();
            Log.info("[ReSync] Registered extension " + pluginId + " " + nullToEmpty(extension.getVersion()));
            return new ExtensionHandle(pluginId);
        } catch (Exception exception) {
            extensions.remove(pluginId);
            cleanupState(state);
            throw new IllegalStateException("Failed to register ReSync extension " + pluginId, exception);
        }
    }

    private void initializeExtension(ExtensionState state, ExtensionContext context) {
        Set<Listener> listenersBefore = registeredListeners(context.owner());
        try {
            state.extension.initialize(context);
            state.extension.start();
        } finally {
            for (Listener listener : registeredListeners(context.owner())) {
                if (!listenersBefore.contains(listener)) {
                    state.listeners.add(listener);
                }
            }
        }
    }

    private Set<Listener> registeredListeners(JavaPlugin owner) {
        Set<Listener> listeners = Collections.newSetFromMap(new IdentityHashMap<>());
        for (RegisteredListener registeredListener : HandlerList.getRegisteredListeners(owner)) {
            listeners.add(registeredListener.getListener());
        }
        return listeners;
    }

    private void unregister(String pluginId) {
        ExtensionState state = extensions.remove(pluginId);
        if (state == null) {
            return;
        }
        try {
            state.extension.stop();
        } catch (Exception exception) {
            Log.warn("ReSync extension stop failed for " + pluginId + ": " + exception.getMessage());
        }
        cleanupState(state);
        refreshNodeRegistry();
        Log.info("[ReSync] Unregistered extension " + pluginId);
    }

    private void refreshNodeRegistry() {
        FlowModule flowModule = moduleContext.getService(FlowModule.class);
        if (flowModule != null) {
            flowModule.refreshCustomFunctionDefinitions();
        }
    }

    private void cleanupState(ExtensionState state) {
        ReSyncExtensionData data = moduleContext.getService(ReSyncExtensionData.class);
        if (data != null) {
            data.removePlugin(state.pluginId);
        }
        NodeDefinitionRegistry nodeDefinitions = moduleContext.getService(NodeDefinitionRegistry.class);
        if (nodeDefinitions != null) {
            nodeDefinitions.unregisterPlugin(state.pluginId);
        }
        FlowRegistry flowRegistry = moduleContext.getService(FlowRegistry.class);
        if (flowRegistry != null) {
            for (String nodeId : state.nodeIds) {
                flowRegistry.unregister(nodeId);
            }
        }
        HandlerRegistry handlers = moduleContext.getService(HandlerRegistry.class);
        if (handlers != null) {
            for (String handlerId : state.handlerIds) {
                handlers.unregister(handlerId);
            }
        }
        PropertyRegistry properties = moduleContext.getService(PropertyRegistry.class);
        if (properties != null) {
            for (PropertyRegistration property : state.propertyIds) {
                properties.unregister(property.family(), property.property());
            }
        }
        OptionCatalogRegistry optionCatalogs = moduleContext.getService(OptionCatalogRegistry.class);
        if (optionCatalogs != null) {
            for (String sourceId : state.optionCatalogIds) {
                optionCatalogs.unregister(sourceId);
            }
        }
        RuntimeDataRegistry runtimeData = moduleContext.getService(RuntimeDataRegistry.class);
        if (runtimeData != null) {
            for (String adapterId : state.runtimeDataAdapterIds) {
                runtimeData.unregister(adapterId);
            }
        }
        FlowValueCodecRegistry valueCodecs = moduleContext.getService(FlowValueCodecRegistry.class);
        for (String typeId : state.typeIds) {
            FlowDataType.unregisterExtensionType(state.pluginId, typeId);
            if (valueCodecs != null) {
                valueCodecs.unregister(typeId);
            }
        }
        TypeAdapterRegistry typeAdapters = moduleContext.getService(TypeAdapterRegistry.class);
        if (typeAdapters != null) {
            for (ConversionRegistration conversion : state.conversions) {
                typeAdapters.unregister(conversion.source(), conversion.target());
            }
        }
        FlowResourceRegistry resources = moduleContext.getService(FlowResourceRegistry.class);
        if (resources != null) {
            for (String resourceTypeId : state.resourceTypeIds) {
                resources.unregister(state.pluginId, resourceTypeId);
            }
        }
        FlowGraphValidationRegistry validators = moduleContext.getService(FlowGraphValidationRegistry.class);
        if (validators != null) {
            validators.unregisterOwner(state.pluginId);
        }
        CustomContentService customContentService = moduleContext.getService(CustomContentService.class);
        if (customContentService != null) {
            for (String providerId : state.customContentProviderIds) {
                customContentService.unregisterProvider(providerId);
            }
        }
        WorldMapService worldMapService = moduleContext.getService(WorldMapService.class);
        if (worldMapService != null) {
            for (String extensionId : state.worldMapExtensionIds) {
                worldMapService.unregisterExtension(extensionId);
            }
        }
        for (Listener listener : state.listeners) {
            HandlerList.unregisterAll(listener);
        }
        for (String moduleId : new ArrayList<>(state.moduleIds)) {
            moduleContext.getModuleRegistry().unregisterRuntimeModule(moduleId, moduleContext);
        }
    }

    private void unloadDisabledOwners() {
        for (ExtensionState state : new ArrayList<>(extensions.values())) {
            if (state.owner != null && !state.owner.isEnabled()) {
                unregister(state.pluginId);
            }
        }
    }

    private void scanDirectory() {
        ensureDirectory();
        Map<Path, Long> currentFiles = new ConcurrentHashMap<>();
        for (Path directory : extensionDirectories) {
            try (var stream = Files.list(directory)) {
                stream.filter(path -> path.toString().toLowerCase().endsWith(".jar")).forEach(path -> {
                    try {
                        currentFiles.put(path, Files.getLastModifiedTime(path).toMillis());
                    } catch (IOException exception) {
                        Log.warn("Failed to stat extension jar " + path + ": " + exception.getMessage());
                    }
                });
            } catch (IOException exception) {
                Log.warn("Failed to scan extension directory " + directory + ": " + exception.getMessage());
            }
        }
        for (Path existing : new ArrayList<>(jarStates.keySet())) {
            if (!currentFiles.containsKey(existing)) {
                unloadJar(existing);
            }
        }
        for (Map.Entry<Path, Long> entry : currentFiles.entrySet()) {
            JarState existing = jarStates.get(entry.getKey());
            if (existing == null || existing.lastModified != entry.getValue()) {
                if (existing != null) {
                    unloadJar(entry.getKey());
                }
                loadJar(entry.getKey(), entry.getValue());
            }
        }
    }

    private void loadJar(Path jarPath, long modified) {
        try {
            URLClassLoader classLoader = new URLClassLoader(new URL[]{jarPath.toUri().toURL()}, ReSyncExtension.class.getClassLoader());
            ServiceLoader<ReSyncExtension> loader = ServiceLoader.load(ReSyncExtension.class, classLoader);
            List<String> pluginIds = new ArrayList<>();
            for (ReSyncExtension extension : loader) {
                try {
                    if (extension == null || extension.getPluginId() == null || extensions.containsKey(extension.getPluginId().trim().toLowerCase())) {
                        continue;
                    }
                    ExtensionRegistration registration = registerExtension(null, extension, classLoader, jarPath);
                    pluginIds.add(registration.pluginId());
                } catch (Exception exception) {
                    Log.error("[ReSync] Failed to load extension from " + jarPath + ": " + exception.getMessage(), exception);
                }
            }
            if (pluginIds.isEmpty()) {
                close(classLoader);
                return;
            }
            jarStates.put(jarPath, new JarState(modified, classLoader, pluginIds));
        } catch (Exception exception) {
            Log.error("[ReSync] Failed to load extension jar " + jarPath + ": " + exception.getMessage(), exception);
        }
    }

    private void unloadJar(Path jarPath) {
        JarState state = jarStates.remove(jarPath);
        if (state == null) {
            return;
        }
        for (String pluginId : state.pluginIds) {
            unregister(pluginId);
        }
        close(state.classLoader);
    }

    private void ensureDirectory() {
        for (Path directory : extensionDirectories) {
            try {
                Files.createDirectories(directory);
            } catch (IOException exception) {
                Log.warn("Failed to create extension directory " + directory + ": " + exception.getMessage());
            }
        }
    }

    private String normalizePluginId(String pluginId) {
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalArgumentException("Extension plugin id is required");
        }
        String normalized = pluginId.trim().toLowerCase();
        if (!normalized.matches("[a-z0-9][a-z0-9_.-]{1,63}")) {
            throw new IllegalArgumentException("Invalid ReSync extension id: " + pluginId);
        }
        return normalized;
    }

    private void validateNamespaced(String pluginId, String id, String kind) {
        if (id == null || id.isBlank() || !id.startsWith(pluginId + ":")) {
            throw new IllegalArgumentException(kind + " id must be namespaced as " + pluginId + ":name");
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private void close(URLClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }
        try {
            classLoader.close();
        } catch (IOException exception) {
            Log.warn("Failed to close extension classloader: " + exception.getMessage());
        }
    }

    private final class ExtensionContext implements ReSyncExtensionContext {
        private final ExtensionState state;

        private ExtensionContext(ExtensionState state) {
            this.state = state;
        }

        @Override
        public String pluginId() {
            return state.pluginId;
        }

        @Override
        public JavaPlugin owner() {
            return state.owner != null ? state.owner : moduleContext.getPlugin();
        }

        @Override
        public FlowRegistration flow() {
            return new ExtensionFlowRegistration(state);
        }

        @Override
        public ModuleRegistration modules() {
            return new ExtensionModuleRegistration(state);
        }

        @Override
        public CustomContentRegistration customContent() {
            return provider -> {
                CustomContentService service = moduleContext.getService(CustomContentService.class);
                if (service != null && provider != null) {
                    validateNamespaced(state.pluginId, provider.getId(), "Custom content provider");
                    if (service.hasProvider(provider.getId())) {
                        throw new IllegalArgumentException("Duplicate custom content provider id: " + provider.getId());
                    }
                    service.registerProvider(provider);
                    state.customContentProviderIds.add(provider.getId());
                }
            };
        }

        @Override
        public WorldMapRegistration worldMap() {
            return extension -> {
                WorldMapService service = moduleContext.getService(WorldMapService.class);
                if (service != null && extension != null) {
                    validateNamespaced(state.pluginId, extension.getExtensionId(), "World map extension");
                    boolean exists = service.getExtensions().stream()
                        .anyMatch(existing -> extension.getExtensionId().equals(existing.getExtensionId()));
                    if (exists) {
                        throw new IllegalArgumentException("Duplicate world map extension id: " + extension.getExtensionId());
                    }
                    service.registerExtension(extension);
                    state.worldMapExtensionIds.add(extension.getExtensionId());
                }
            };
        }

        @Override
        public OptionCatalogRegistration optionCatalogs() {
            return provider -> {
                if (provider == null) {
                    return;
                }
                OptionCatalogRegistry registry = moduleContext.getRequiredService(OptionCatalogRegistry.class);
                validateNamespaced(state.pluginId, provider.sourceId(), "Option catalog");
                if (registry.provider(provider.sourceId()) != null) {
                    throw new IllegalArgumentException("Duplicate option catalog id: " + provider.sourceId());
                }
                if (!registry.register(provider)) {
                    throw new IllegalArgumentException("Duplicate option catalog id: " + provider.sourceId());
                }
                state.optionCatalogIds.add(provider.sourceId());
                moduleContext.getRequiredService(ReSyncExtensionData.class)
                    .addOptionSource(state.pluginId, new FlowOptionSourceMetadata(provider.sourceId(), provider.providerId(), provider.widgetType(), provider.searchable(), "", "string",
                        provider.contextKeys() != null ? provider.contextKeys().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList() : List.of()));
            };
        }

        @Override
        public RuntimeDataRegistration runtimeData() {
            return adapter -> {
                if (adapter == null) {
                    return;
                }
                validateNamespaced(state.pluginId, adapter.id(), "Runtime data adapter");
                RuntimeDataRegistry registry = moduleContext.getRequiredService(RuntimeDataRegistry.class);
                if (!registry.register(adapter)) {
                    throw new IllegalArgumentException("Duplicate runtime data adapter id: " + adapter.id());
                }
                state.runtimeDataAdapterIds.add(adapter.id());
            };
        }

        @Override
        public EventRegistration events() {
            return listener -> {
                if (listener == null) {
                    return;
                }
                Bukkit.getPluginManager().registerEvents(listener, owner());
                state.listeners.add(listener);
            };
        }

        @Override
        public ExtensionStorage storage() {
            Path root = moduleContext.getPlugin().getDataFolder().toPath().resolve("extensions").resolve(state.pluginId).normalize();
            try {
                Files.createDirectories(root);
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to create extension storage for " + state.pluginId, exception);
            }
            return () -> root;
        }

        @Override
        public <T> T service(Class<T> type) {
            return moduleContext.getService(type);
        }

        @Override
        public <T> T requiredService(Class<T> type) {
            return moduleContext.getRequiredService(type);
        }
    }

    private final class ExtensionFlowRegistration implements ReSyncExtensionContext.FlowRegistration {
        private final ExtensionState state;

        private ExtensionFlowRegistration(ExtensionState state) {
            this.state = state;
        }

        @Override
        public FlowRegistry runtimeRegistry() {
            return moduleContext.getRequiredService(FlowRegistry.class);
        }

        @Override
        public NodeDefinitionRegistry nodeDefinitions() {
            return moduleContext.getRequiredService(NodeDefinitionRegistry.class);
        }

        @Override
        public HandlerRegistry handlers() {
            return moduleContext.getRequiredService(HandlerRegistry.class);
        }

        @Override
        public PropertyRegistry properties() {
            return moduleContext.getRequiredService(PropertyRegistry.class);
        }

        @Override
        public void registerNode(NodeDefinition definition) {
            if (definition == null) {
                return;
            }
            validateNamespaced(state.pluginId, definition.getId(), "Flow node");
            definition.assignOwner(state.pluginId);
            nodeDefinitions().register(state.pluginId, definition);
            state.nodeIds.add(definition.getId());
        }

        @Override
        public void registerNodes(String resourcePath) {
            ClassLoader classLoader = state.classLoader != null ? state.classLoader : state.extension.getClass().getClassLoader();
            NodeDefinitionLoader loader = new NodeDefinitionLoader();
            List<NodeDefinition> definitions = loader.loadFromClassLoader(classLoader, resourcePath);
            for (NodeDefinition definition : definitions) {
                registerNode(definition);
            }
        }

        @Override
        public void registerHandler(String handlerId, NodeHandler handler) {
            validateNamespaced(state.pluginId, handlerId, "Handler");
            handlers().register(handlerId, handler);
            state.handlerIds.add(handlerId);
        }

        @Override
        public void registerProperty(String family, String property, PropertyHandler handler) {
            validateNamespaced(state.pluginId, property, "Property");
            properties().register(family, property, handler);
            state.propertyIds.add(new PropertyRegistration(family, property));
        }

        @Override
        public void registerType(FlowTypeMetadata metadata) {
            validateNamespaced(state.pluginId, metadata != null ? metadata.getId() : null, "Flow type");
            metadata.setAvailable(false);
            metadata.setUnavailableReason("No executable runtime type is registered");
            moduleContext.getRequiredService(ReSyncExtensionData.class).addType(state.pluginId, metadata);
        }

        @Override
        public void registerType(FlowDataType type, FlowTypeMetadata metadata) {
            registerType(type, null, metadata);
        }

        @Override
        public void registerType(FlowDataType type, FlowValueCodec<?> codec, FlowTypeMetadata metadata) {
            String typeId = type != null ? type.getId() : null;
            validateNamespaced(state.pluginId, typeId, "Flow type");
            if (metadata == null || metadata.getId() == null || !typeId.equalsIgnoreCase(metadata.getId())) {
                throw new IllegalArgumentException("Flow type metadata must match runtime type " + typeId);
            }
            FlowDataType registered = FlowDataType.registerExtensionType(state.pluginId, type);
            FlowValueCodecRegistry valueCodecs = moduleContext.getRequiredService(FlowValueCodecRegistry.class);
            try {
                boolean boundarySafe = false;
                if (codec != null) {
                    if (!typeId.equalsIgnoreCase(codec.id())) {
                        throw new IllegalArgumentException("Flow codec id must match runtime type " + typeId);
                    }
                    valueCodecs.register(codec);
                    boundarySafe = true;
                } else if (registered.getParent() != null && valueCodecs.hasCodec(FlowTypeRef.simple(registered.getParent().getId()))) {
                    valueCodecs.registerAlias(typeId, registered.getParent().getId());
                    boundarySafe = true;
                }
                metadata.setOwner(state.pluginId);
                metadata.setRuntimeType(registered.getJavaType() != null ? registered.getJavaType().getName() : null);
                metadata.setAvailable(true);
                metadata.setUnavailableReason(null);
                metadata.setTransportable(boundarySafe);
                metadata.setPersistable(boundarySafe);
                metadata.setCodecId(boundarySafe ? "extension:" + state.pluginId + "/" + typeId : null);
                moduleContext.getRequiredService(ReSyncExtensionData.class).addType(state.pluginId, metadata);
                state.typeIds.add(typeId);
            } catch (RuntimeException exception) {
                valueCodecs.unregister(typeId);
                FlowDataType.unregisterExtensionType(state.pluginId, typeId);
                throw exception;
            }
        }

        @Override
        public void registerCategory(FlowCategoryMetadata metadata) {
            validateNamespaced(state.pluginId, metadata != null ? metadata.getId() : null, "Flow category");
            moduleContext.getRequiredService(ReSyncExtensionData.class).addCategory(state.pluginId, metadata);
        }

        @Override
        public void registerConversion(FlowConversionRule rule) {
            if (rule != null) {
                rule.setAvailability("unavailable: No executable conversion adapter is registered");
            }
            moduleContext.getRequiredService(ReSyncExtensionData.class).addConversion(state.pluginId, rule);
        }

        @Override
        public <S, T> void registerConversion(Class<S> source, Class<T> target, Function<S, T> adapter, FlowConversionRule rule) {
            if (source == null || target == null || adapter == null || rule == null) {
                throw new IllegalArgumentException("Conversion classes, adapter, and rule are required");
            }
            FlowDataType sourceType = FlowDataType.fromString(rule.getSourceTypeId());
            FlowDataType targetType = FlowDataType.fromString(rule.getTargetTypeId());
            if (!sourceType.isResolved() || !targetType.isResolved()) {
                throw new IllegalArgumentException("Conversion types must be executable before registering the adapter");
            }
            TypeAdapterRegistry adapters = moduleContext.getRequiredService(TypeAdapterRegistry.class);
            adapters.register(source, target, adapter);
            rule.setImplementationId(state.pluginId + ":adapter/" + rule.getSourceTypeId() + "-to-" + rule.getTargetTypeId());
            rule.setAvailability("available");
            moduleContext.getRequiredService(ReSyncExtensionData.class).addConversion(state.pluginId, rule);
            state.conversions.add(new ConversionRegistration(source, target));
        }

        @Override
        public void registerOptionSource(FlowOptionSourceMetadata metadata) {
            validateNamespaced(state.pluginId, metadata != null ? metadata.getId() : null, "Option source");
            moduleContext.getRequiredService(ReSyncExtensionData.class).addOptionSource(state.pluginId, metadata);
        }

        @Override
        public void registerResource(FlowResourceAdapter<?> adapter) {
            String typeId = adapter != null && adapter.descriptor() != null ? adapter.descriptor().typeId() : null;
            validateNamespaced(state.pluginId, typeId, "Flow resource");
            FlowResourceRegistry resources = moduleContext.getRequiredService(FlowResourceRegistry.class);
            OptionCatalogRegistry catalogs = moduleContext.getRequiredService(OptionCatalogRegistry.class);
            String catalogSource = adapter.catalogSource();
            resources.register(state.pluginId, adapter);
            boolean catalogRegistered = false;
            try {
                OptionCatalogProvider existingCatalog = catalogs.provider(catalogSource);
                if (existingCatalog != null) {
                    if (!state.optionCatalogIds.contains(catalogSource)) {
                        throw new IllegalStateException("Resource catalog source is owned by another contribution: " + catalogSource);
                    }
                } else {
                    catalogRegistered = catalogs.register(new OptionCatalogProvider() {
                        @Override
                        public String sourceId() {
                            return catalogSource;
                        }

                        @Override
                        public String revision() {
                            List<String> ids = new ArrayList<>(adapter.listIds());
                            ids.sort(String.CASE_INSENSITIVE_ORDER);
                            return typeId + ":" + ids.size() + ":" + String.join(",", ids);
                        }

                        @Override
                        public List<String> values() {
                            return adapter.listIds();
                        }
                    });
                    if (!catalogRegistered) {
                        throw new IllegalStateException("Resource catalog source is already registered: " + catalogSource);
                    }
                    state.optionCatalogIds.add(catalogSource);
                }
                state.resourceTypeIds.add(typeId);
                moduleContext.getRequiredService(ReSyncExtensionData.class).addResource(state.pluginId, typeId);
            } catch (RuntimeException exception) {
                if (catalogRegistered) {
                    catalogs.unregister(catalogSource);
                }
                resources.unregister(state.pluginId, typeId);
                throw exception;
            }
        }

        @Override
        public void registerValidator(String validatorId, FlowGraphValidationRule validator) {
            validateNamespaced(state.pluginId, validatorId, "Flow validator");
            FlowGraphValidationRegistry validators = moduleContext.getRequiredService(FlowGraphValidationRegistry.class);
            validators.register(state.pluginId, validatorId, validator);
            state.validatorIds.add(validatorId);
        }
    }

    private final class ExtensionModuleRegistration implements ReSyncExtensionContext.ModuleRegistration {
        private final ExtensionState state;

        private ExtensionModuleRegistration(ExtensionState state) {
            this.state = state;
        }

        @Override
        public void register(Module module) {
            validateNamespaced(state.pluginId, module.getModuleId(), "Module");
            for (String channel : module.getChannels()) {
                validateNamespaced(state.pluginId, channel, "Channel");
            }
            moduleContext.getModuleRegistry().registerRuntimeModule(module, moduleContext);
            state.moduleIds.add(module.getModuleId());
        }

        @Override
        public void unregister(String moduleId) {
            validateNamespaced(state.pluginId, moduleId, "Module");
            moduleContext.getModuleRegistry().unregisterRuntimeModule(moduleId, moduleContext);
            state.moduleIds.remove(moduleId);
        }
    }

    private final class ExtensionHandle implements ExtensionRegistration {
        private final String pluginId;

        private ExtensionHandle(String pluginId) {
            this.pluginId = pluginId;
        }

        @Override
        public String pluginId() {
            return pluginId;
        }

        @Override
        public void close() {
            unregister(pluginId);
        }
    }

    private static final class JarState {
        private final long lastModified;
        private final URLClassLoader classLoader;
        private final List<String> pluginIds;

        private JarState(long lastModified, URLClassLoader classLoader, List<String> pluginIds) {
            this.lastModified = lastModified;
            this.classLoader = classLoader;
            this.pluginIds = pluginIds;
        }
    }

    private static final class ExtensionState {
        private final String pluginId;
        private final JavaPlugin owner;
        private final ReSyncExtension extension;
        private final URLClassLoader classLoader;
        private final Path jarPath;
        private final Set<String> nodeIds = new HashSet<>();
        private final Set<String> handlerIds = new HashSet<>();
        private final Set<String> moduleIds = new HashSet<>();
        private final Set<PropertyRegistration> propertyIds = new HashSet<>();
        private final Set<String> optionCatalogIds = new HashSet<>();
        private final Set<String> runtimeDataAdapterIds = new HashSet<>();
        private final Set<String> typeIds = new HashSet<>();
        private final Set<ConversionRegistration> conversions = new HashSet<>();
        private final Set<String> resourceTypeIds = new HashSet<>();
        private final Set<String> validatorIds = new HashSet<>();
        private final Set<String> customContentProviderIds = new HashSet<>();
        private final Set<String> worldMapExtensionIds = new HashSet<>();
        private final Set<Listener> listeners = Collections.newSetFromMap(new IdentityHashMap<>());

        private ExtensionState(String pluginId, JavaPlugin owner, ReSyncExtension extension, URLClassLoader classLoader, Path jarPath) {
            this.pluginId = pluginId;
            this.owner = owner;
            this.extension = extension;
            this.classLoader = classLoader;
            this.jarPath = jarPath;
        }

        private void clearOwnedIds() {
            nodeIds.clear();
            handlerIds.clear();
            moduleIds.clear();
            propertyIds.clear();
            optionCatalogIds.clear();
            runtimeDataAdapterIds.clear();
            typeIds.clear();
            conversions.clear();
            resourceTypeIds.clear();
            validatorIds.clear();
            customContentProviderIds.clear();
            worldMapExtensionIds.clear();
            listeners.clear();
        }
    }

    private record PropertyRegistration(String family, String property) {
    }

    private record ConversionRegistration(Class<?> source, Class<?> target) {
    }
}
