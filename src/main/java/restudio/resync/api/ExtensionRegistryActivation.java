package restudio.resync.api;

import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.FlowValueCodecRegistry;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.flow.handler.event.FlowEventRegistry;
import restudio.resync.flow.handler.property.PropertyHandler;
import restudio.resync.flow.handler.property.PropertyRegistry;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.validation.FlowGraphValidationRegistry;
import restudio.resync.modules.flow.FlowResourceRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Consumer;

public final class ExtensionRegistryActivation {
    interface ExtensionLifecycle {
        String extensionId();
    }

    public static final class StaleProjectionException extends IllegalStateException {
        private StaleProjectionException(String message) {
            super(message);
        }
    }

    private final AtomicReference<State> active;
    private final AtomicReference<Throwable> projectionFailure = new AtomicReference<>();
    private final CopyOnWriteArrayList<Consumer<State>> listeners = new CopyOnWriteArrayList<>();
    private final ThreadLocal<State> readState = new ThreadLocal<>();
    private final Object publicationMonitor = new Object();

    public ExtensionRegistryActivation(State initial) {
        active = new AtomicReference<>(Objects.requireNonNull(initial, "Initial registry state is required"));
    }

    public State snapshot() {
        State pinned = readState.get();
        return pinned != null ? pinned : active.get();
    }

    public <T> T read(Function<State, T> reader) {
        return Objects.requireNonNull(reader, "Registry state reader is required").apply(snapshot());
    }

    public <T> T readConsistent(Function<State, T> reader) {
        Objects.requireNonNull(reader, "Registry state reader is required");
        State previous = readState.get();
        if (previous != null) {
            return reader.apply(previous);
        }
        State current = active.get();
        readState.set(current);
        try {
            return reader.apply(current);
        } finally {
            readState.remove();
        }
    }

    public boolean compareAndSet(State expected, State candidate) {
        return compareAndSet(expected, candidate, false);
    }

    public boolean compareAndSet(State expected, State candidate, boolean force) {
        Objects.requireNonNull(expected, "Expected registry state is required");
        Objects.requireNonNull(candidate, "Candidate registry state is required");
        synchronized (publicationMonitor) {
            requireProjectionHealthy();
            if (!force && expected.semanticallyEquals(candidate)) {
                return active.get() == expected;
            }
            boolean published = active.compareAndSet(expected, candidate);
            if (published) {
                notifyPublished(expected, candidate);
            }
            return published;
        }
    }

    public State publish(State candidate) {
        Objects.requireNonNull(candidate, "Candidate registry state is required");
        synchronized (publicationMonitor) {
            requireProjectionHealthy();
            for (;;) {
                State current = active.get();
                if (current.semanticallyEquals(candidate)) {
                    return current;
                }
                if (active.compareAndSet(current, candidate)) {
                    notifyPublished(current, candidate);
                    return candidate;
                }
            }
        }
    }

    public State publishDefinitionProjection(State expected, NodeDefinitionRegistry definitions) {
        Objects.requireNonNull(expected, "Expected registry state is required");
        Objects.requireNonNull(definitions, "Projected node definitions are required");
        State candidate = expected.withDefinitionProjection(definitions);
        boolean changed = !expected.semanticallyEquals(candidate);
        if (!compareAndSet(expected, candidate)) {
            throw new StaleProjectionException("Registry definition projection became stale before publication");
        }
        return changed ? candidate : expected;
    }

    public <T, R> R update(Class<T> type, Function<T, R> mutation) {
        Objects.requireNonNull(type, "Registry type is required");
        Objects.requireNonNull(mutation, "Registry mutation is required");
        synchronized (publicationMonitor) {
            requireProjectionHealthy();
            for (;;) {
                State current = active.get();
                T slice = current.registry(type);
                R result = mutation.apply(slice);
                State candidate = current.withRegistry(type, slice);
                if (current.semanticallyEquals(candidate)) {
                    return result;
                }
                if (active.compareAndSet(current, candidate)) {
                    notifyPublished(current, candidate);
                    return result;
                }
            }
        }
    }

    public Map<String, NodeHandler> clearHandlersForShutdown() {
        synchronized (publicationMonitor) {
            requireProjectionHealthy();
            State current = active.get();
            Map<String, NodeHandler> retired = current.handlers != null ? current.handlers.snapshot() : Map.of();
            if (retired.isEmpty()) {
                return retired;
            }
            State candidate = current.withHandlersClearedForShutdown();
            if (!active.compareAndSet(current, candidate)) {
                throw new IllegalStateException("Registry activation changed while handlers were retiring");
            }
            notifyPublished(current, candidate);
            return retired;
        }
    }

    public void addListener(Consumer<State> listener) {
        Objects.requireNonNull(listener, "Registry activation listener is required");
        synchronized (publicationMonitor) {
            listeners.addIfAbsent(listener);
            try {
                listener.accept(active.get());
            } catch (RuntimeException | Error failure) {
                listeners.remove(listener);
                throw failure;
            }
        }
    }

    public void removeListener(Consumer<State> listener) {
        if (listener != null) {
            synchronized (publicationMonitor) {
                listeners.remove(listener);
            }
        }
    }

    public boolean hasProjectionFailure() {
        return projectionFailure.get() != null;
    }

    public Throwable projectionFailure() {
        return projectionFailure.get();
    }

    public void bind(NodeDefinitionRegistry nodeDefinitions,
                     HandlerRegistry handlers,
                     PropertyRegistry properties,
                     OptionCatalogRegistry optionCatalogs,
                     RuntimeDataRegistry runtimeData,
                     FlowValueCodecRegistry valueCodecs,
                     TypeAdapterRegistry typeAdapters,
                     FlowGraphValidationRegistry validators,
                     FlowResourceRegistry resources,
                     ReSyncExtensionData extensionData,
                     FlowEventRegistry events,
                     FlowRegistry flowRegistry) {
        if (nodeDefinitions != null) nodeDefinitions.bindActivation(this);
        if (handlers != null) handlers.bindActivation(this);
        if (properties != null) properties.bindActivation(this);
        if (optionCatalogs != null) optionCatalogs.bindActivation(this);
        if (runtimeData != null) runtimeData.bindActivation(this);
        if (valueCodecs != null) valueCodecs.bindActivation(this);
        if (typeAdapters != null) typeAdapters.bindActivation(this);
        if (validators != null) validators.bindActivation(this);
        if (resources != null) resources.bindActivation(this);
        if (extensionData != null) extensionData.bindActivation(this);
        if (events != null) events.bindActivation(this);
        if (flowRegistry != null) flowRegistry.bindActivation(this);
    }

    private void notifyPublished(State previous, State candidate) {
        try {
            notifyListeners(candidate);
        } catch (RuntimeException | Error failure) {
            if (active.compareAndSet(candidate, previous)) {
                try {
                    notifyListeners(previous);
                } catch (RuntimeException | Error rollbackFailure) {
                    projectionFailure.compareAndSet(null, rollbackFailure);
                    failure.addSuppressed(rollbackFailure);
                }
            } else {
                IllegalStateException rollbackFailure = new IllegalStateException("Registry activation changed while listener projection was rolling back");
                projectionFailure.compareAndSet(null, rollbackFailure);
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }

    private void requireProjectionHealthy() {
        Throwable failure = projectionFailure.get();
        if (failure != null) {
            throw new IllegalStateException("Registry activation is fail-closed after projection rollback failure", failure);
        }
    }

    private void notifyListeners(State candidate) {
        for (Consumer<State> listener : listeners) {
            listener.accept(candidate);
        }
    }

    public static State capture(long generation,
                                NodeDefinitionRegistry nodeDefinitions,
                                HandlerRegistry handlers,
                                PropertyRegistry properties,
                                OptionCatalogRegistry optionCatalogs,
                                RuntimeDataRegistry runtimeData,
                                FlowValueCodecRegistry valueCodecs,
                                TypeAdapterRegistry typeAdapters,
                                FlowGraphValidationRegistry validators,
                                FlowResourceRegistry resources,
                                ReSyncExtensionData extensionData,
                                FlowEventRegistry events,
                                FlowRegistry flowRegistry) {
        return capture(generation, nodeDefinitions, handlers, properties, optionCatalogs, runtimeData, valueCodecs,
            typeAdapters, validators, resources, extensionData, events, flowRegistry, Map.of());
    }

    static State capture(long generation,
                         NodeDefinitionRegistry nodeDefinitions,
                         HandlerRegistry handlers,
                         PropertyRegistry properties,
                         OptionCatalogRegistry optionCatalogs,
                         RuntimeDataRegistry runtimeData,
                         FlowValueCodecRegistry valueCodecs,
                         TypeAdapterRegistry typeAdapters,
                         FlowGraphValidationRegistry validators,
                         FlowResourceRegistry resources,
                         ReSyncExtensionData extensionData,
                         FlowEventRegistry events,
                         FlowRegistry flowRegistry,
                         Map<String, ? extends ExtensionLifecycle> extensionLifecycles) {
        return new State(generation,
            copy(nodeDefinitions),
            copy(handlers),
            copy(properties),
            copy(optionCatalogs),
            copy(runtimeData),
            copy(valueCodecs),
            copy(typeAdapters),
            copy(validators),
            copy(resources),
            copy(extensionData),
            copy(events, typeAdapters),
            copy(flowRegistry, handlers),
            extensionLifecycles);
    }

    private static NodeDefinitionRegistry copy(NodeDefinitionRegistry value) {
        return value != null ? value.copy() : null;
    }

    private static HandlerRegistry copy(HandlerRegistry value) {
        return value != null ? value.copy() : null;
    }

    private static PropertyRegistry copy(PropertyRegistry value) {
        return value != null ? value.copy() : null;
    }

    private static OptionCatalogRegistry copy(OptionCatalogRegistry value) {
        return value != null ? value.copy() : null;
    }

    private static RuntimeDataRegistry copy(RuntimeDataRegistry value) {
        return value != null ? value.copy() : null;
    }

    private static FlowValueCodecRegistry copy(FlowValueCodecRegistry value) {
        return value != null ? value.copy() : null;
    }

    private static TypeAdapterRegistry copy(TypeAdapterRegistry value) {
        return value != null ? value.copy() : null;
    }

    private static FlowGraphValidationRegistry copy(FlowGraphValidationRegistry value) {
        return value != null ? value.copy() : null;
    }

    private static FlowResourceRegistry copy(FlowResourceRegistry value) {
        return value != null ? value.copy() : null;
    }

    private static ReSyncExtensionData copy(ReSyncExtensionData value) {
        return value != null ? value.copy() : null;
    }

    private static FlowEventRegistry copy(FlowEventRegistry value, TypeAdapterRegistry adapters) {
        return value != null ? value.copyForStaging(adapters) : null;
    }

    private static FlowRegistry copy(FlowRegistry value) {
        return value != null ? value.copy() : null;
    }

    private static FlowRegistry copy(FlowRegistry value, HandlerRegistry handlers) {
        return value != null ? value.copy(handlers) : null;
    }

    private static Map<String, ExtensionLifecycle> copyExtensionLifecycles(
        Map<String, ? extends ExtensionLifecycle> values
    ) {
        Objects.requireNonNull(values, "Extension lifecycles are required");
        Map<String, ExtensionLifecycle> result = new java.util.LinkedHashMap<>();
        values.forEach((pluginId, lifecycle) -> {
            if (pluginId == null || pluginId.isBlank() || lifecycle == null || !pluginId.equals(lifecycle.extensionId())) {
                throw new IllegalArgumentException("Extension lifecycle entries require an ID and state");
            }
            result.put(pluginId, lifecycle);
        });
        return Map.copyOf(result);
    }

    public static final class State {
        private final long generation;
        private final NodeDefinitionRegistry nodeDefinitions;
        private final HandlerRegistry handlers;
        private final PropertyRegistry properties;
        private final OptionCatalogRegistry optionCatalogs;
        private final RuntimeDataRegistry runtimeData;
        private final FlowValueCodecRegistry valueCodecs;
        private final TypeAdapterRegistry typeAdapters;
        private final FlowGraphValidationRegistry validators;
        private final FlowResourceRegistry resources;
        private final ReSyncExtensionData extensionData;
        private final FlowEventRegistry events;
        private final FlowRegistry flowRegistry;
        private final Map<String, ExtensionLifecycle> extensionLifecycles;
        private final Map<String, Object> fingerprint;

        private State(long generation,
                      NodeDefinitionRegistry nodeDefinitions,
                      HandlerRegistry handlers,
                      PropertyRegistry properties,
                      OptionCatalogRegistry optionCatalogs,
                      RuntimeDataRegistry runtimeData,
                      FlowValueCodecRegistry valueCodecs,
                      TypeAdapterRegistry typeAdapters,
                      FlowGraphValidationRegistry validators,
                      FlowResourceRegistry resources,
                      ReSyncExtensionData extensionData,
                      FlowEventRegistry events,
                      FlowRegistry flowRegistry,
                      Map<String, ? extends ExtensionLifecycle> extensionLifecycles) {
            if (generation < 0) {
                throw new IllegalArgumentException("Registry generation cannot be negative");
            }
            this.generation = generation;
            HandlerRegistry detachedHandlers = copy(handlers);
            TypeAdapterRegistry detachedAdapters = copy(typeAdapters);
            this.nodeDefinitions = copy(nodeDefinitions);
            this.handlers = detachedHandlers;
            this.properties = copy(properties);
            this.optionCatalogs = copy(optionCatalogs);
            this.runtimeData = copy(runtimeData);
            this.valueCodecs = copy(valueCodecs);
            this.typeAdapters = detachedAdapters;
            this.validators = copy(validators);
            this.resources = copy(resources);
            this.extensionData = copy(extensionData);
            this.events = copy(events, detachedAdapters);
            this.flowRegistry = copy(flowRegistry, detachedHandlers);
            this.extensionLifecycles = copyExtensionLifecycles(extensionLifecycles);
            fingerprint = Map.copyOf(fingerprint());
        }

        private State(State previous,
                      HandlerRegistry handlers,
                      FlowRegistry flowRegistry,
                      Map<String, Object> fingerprint) {
            generation = previous.generation + 1;
            nodeDefinitions = previous.nodeDefinitions;
            this.handlers = handlers;
            properties = previous.properties;
            optionCatalogs = previous.optionCatalogs;
            runtimeData = previous.runtimeData;
            valueCodecs = previous.valueCodecs;
            typeAdapters = previous.typeAdapters;
            validators = previous.validators;
            resources = previous.resources;
            extensionData = previous.extensionData;
            events = previous.events;
            this.flowRegistry = flowRegistry;
            extensionLifecycles = previous.extensionLifecycles;
            this.fingerprint = Map.copyOf(fingerprint);
        }

        public long generation() {
            return generation;
        }

        public Set<String> extensionIds() {
            return extensionLifecycles.keySet();
        }

        Map<String, ExtensionLifecycle> extensionLifecycles() {
            return extensionLifecycles;
        }

        public NodeDefinitionRegistry nodeDefinitions() {
            return copy(nodeDefinitions);
        }

        public NodeDefinition nodeDefinition(String id) {
            NodeDefinition definition = nodeDefinitions == null ? null : nodeDefinitions.get(id);
            return definition == null ? null : definition.copy();
        }

        public NodeDefinition nodeDefinition(String owner, String id) {
            NodeDefinition definition = nodeDefinitions == null ? null : nodeDefinitions.get(owner, id);
            return definition == null ? null : definition.copy();
        }

        public HandlerRegistry handlers() {
            return copy(handlers);
        }

        public PropertyRegistry properties() {
            return copy(properties);
        }

        public OptionCatalogRegistry optionCatalogs() {
            return copy(optionCatalogs);
        }

        public RuntimeDataRegistry runtimeData() {
            return copy(runtimeData);
        }

        public FlowValueCodecRegistry valueCodecs() {
            return copy(valueCodecs);
        }

        public TypeAdapterRegistry typeAdapters() {
            return copy(typeAdapters);
        }

        public FlowGraphValidationRegistry validators() {
            return copy(validators);
        }

        public FlowResourceRegistry resources() {
            return copy(resources);
        }

        public ReSyncExtensionData extensionData() {
            return copy(extensionData);
        }

        public FlowEventRegistry events() {
            return copy(events, typeAdapters);
        }

        public FlowRegistry flowRegistry() {
            return copy(flowRegistry);
        }

        public <T> T registry(Class<T> type) {
            Objects.requireNonNull(type, "Registry type is required");
            Object value = type == NodeDefinitionRegistry.class ? nodeDefinitions()
                : type == HandlerRegistry.class ? handlers()
                : type == PropertyRegistry.class ? properties()
                : type == OptionCatalogRegistry.class ? optionCatalogs()
                : type == RuntimeDataRegistry.class ? runtimeData()
                : type == FlowValueCodecRegistry.class ? valueCodecs()
                : type == TypeAdapterRegistry.class ? typeAdapters()
                : type == FlowGraphValidationRegistry.class ? validators()
                : type == FlowResourceRegistry.class ? resources()
                : type == ReSyncExtensionData.class ? extensionData()
                : type == FlowEventRegistry.class ? events()
                : type == FlowRegistry.class ? flowRegistry() : null;
            return type.cast(value);
        }

        private <T> State withRegistry(Class<T> type, T value) {
            Objects.requireNonNull(type, "Registry type is required");
            Objects.requireNonNull(value, "Registry value is required");
            NodeDefinitionRegistry nextNodeDefinitions = nodeDefinitions;
            HandlerRegistry nextHandlers = handlers;
            PropertyRegistry nextProperties = properties;
            OptionCatalogRegistry nextOptionCatalogs = optionCatalogs;
            RuntimeDataRegistry nextRuntimeData = runtimeData;
            FlowValueCodecRegistry nextValueCodecs = valueCodecs;
            TypeAdapterRegistry nextTypeAdapters = typeAdapters;
            FlowGraphValidationRegistry nextValidators = validators;
            FlowResourceRegistry nextResources = resources;
            ReSyncExtensionData nextExtensionData = extensionData;
            FlowEventRegistry nextEvents = events;
            FlowRegistry nextFlowRegistry = flowRegistry;
            if (type == NodeDefinitionRegistry.class) nextNodeDefinitions = (NodeDefinitionRegistry) value;
            else if (type == HandlerRegistry.class) {
                nextHandlers = (HandlerRegistry) value;
                if (nextFlowRegistry != null) nextFlowRegistry = nextFlowRegistry.copy(nextHandlers);
            } else if (type == PropertyRegistry.class) nextProperties = (PropertyRegistry) value;
            else if (type == OptionCatalogRegistry.class) {
                nextOptionCatalogs = (OptionCatalogRegistry) value;
                nextRuntimeData = nextOptionCatalogs.runtimeData().copy();
            } else if (type == RuntimeDataRegistry.class) {
                nextRuntimeData = (RuntimeDataRegistry) value;
                if (nextOptionCatalogs != null) {
                    nextOptionCatalogs = nextOptionCatalogs.copy();
                    nextOptionCatalogs.runtimeData().replaceFrom(nextRuntimeData);
                }
            } else if (type == FlowValueCodecRegistry.class) nextValueCodecs = (FlowValueCodecRegistry) value;
            else if (type == TypeAdapterRegistry.class) {
                nextTypeAdapters = (TypeAdapterRegistry) value;
                if (nextEvents != null) nextEvents = nextEvents.copyForStaging(nextTypeAdapters);
            } else if (type == FlowGraphValidationRegistry.class) nextValidators = (FlowGraphValidationRegistry) value;
            else if (type == FlowResourceRegistry.class) nextResources = (FlowResourceRegistry) value;
            else if (type == ReSyncExtensionData.class) nextExtensionData = (ReSyncExtensionData) value;
            else if (type == FlowEventRegistry.class) nextEvents = (FlowEventRegistry) value;
            else if (type == FlowRegistry.class) {
                nextFlowRegistry = (FlowRegistry) value;
                if (nextFlowRegistry.handlerRegistry() != null) {
                    nextHandlers = nextFlowRegistry.handlerRegistry().copy();
                    nextFlowRegistry = nextFlowRegistry.copy(nextHandlers);
                }
            }
            else throw new IllegalArgumentException("Unsupported registry type: " + type.getName());
            return new State(generation + 1, nextNodeDefinitions, nextHandlers, nextProperties, nextOptionCatalogs,
                nextRuntimeData, nextValueCodecs, nextTypeAdapters, nextValidators, nextResources, nextExtensionData,
                nextEvents, nextFlowRegistry, extensionLifecycles);
        }

        private State withDefinitionProjection(NodeDefinitionRegistry definitions) {
            NodeDefinitionRegistry nextDefinitions = copy(definitions);
            PropertyRegistry nextProperties = copy(properties);
            if (nextProperties != null) {
                nextProperties.replaceNodeDefinitions(nextDefinitions.getAllDefinitions().values());
            }
            FlowEventRegistry nextEvents = copy(events, typeAdapters);
            if (nextEvents != null) {
                nextEvents.replaceDefinitions(new ArrayList<>(nextDefinitions.getAllDefinitions().values()));
            }
            return new State(generation + 1, nextDefinitions, handlers, nextProperties, optionCatalogs,
                runtimeData, valueCodecs, typeAdapters, validators, resources, extensionData, nextEvents, flowRegistry,
                extensionLifecycles);
        }

        private State withHandlersClearedForShutdown() {
            HandlerRegistry nextHandlers = new HandlerRegistry().copy();
            FlowRegistry nextFlowRegistry = flowRegistry != null ? flowRegistry.copy(nextHandlers) : null;
            Map<String, Object> nextFingerprint = new LinkedHashMap<>(fingerprint);
            nextFingerprint.put("handlers", List.of());
            return new State(this, nextHandlers, nextFlowRegistry, nextFingerprint);
        }

        public Map<String, Object> fingerprint() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("extensionLifecycles", sorted(extensionLifecycles.entrySet().stream()
                .map(entry -> entry.getKey() + ":" + incarnationFingerprint(entry.getValue())).toList()));
            result.put("defaultPluginId", nodeDefinitions != null ? nodeDefinitions.defaultPluginId() : "");
            result.put("nodes", nodeDefinitions != null ? sorted(nodeDefinitions.getAllDefinitions().values().stream()
                .map(State::nodeFingerprint).toList()) : List.of());
            result.put("handlers", handlers != null ? sorted(handlers.snapshot().entrySet().stream()
                .map(entry -> entry.getKey() + ":" + handlerFingerprint(entry.getValue(), handlers.getSupportedOperations(entry.getKey()))).toList()) : List.of());
            result.put("properties", properties != null ? sorted(properties.getFamilies().stream()
                .flatMap(family -> properties.getProperties(family).stream().map(property -> propertyFingerprint(properties, family, property))).toList()) : List.of());
            result.put("catalogs", optionCatalogs != null ? sorted(optionCatalogs.providers().stream()
                .map(provider -> provider.sourceId() + ":" + provider.providerId() + ":" + provider.runtimeDataDomain() + ":"
                    + provider.widgetType() + ":" + provider.searchable() + ":" + canonical(provider.contextKeys()) + ":" + provider.captureAffinity()
                    + ":" + incarnationFingerprint(provider)).toList()) : List.of());
            result.put("catalogDiagnostics", optionCatalogs != null ? sorted(optionCatalogs.diagnostics().stream()
                .map(diagnostic -> diagnostic.code() + ":" + diagnostic.sourceId() + ":" + diagnostic.message()).toList()) : List.of());
            result.put("runtimeData", runtimeData != null ? sorted(runtimeData.domains().stream()
                .flatMap(domain -> runtimeData.adapters(domain).stream().map(adapter -> domain + ":" + adapter.id() + ":" + adapter.valueType() + ":"
                    + adapter.valueClass().getName() + ":" + adapter.capabilities() + ":" + adapter.available() + ":"
                    + (adapter instanceof OptionCatalogRuntimeDataAdapter ? "catalog-capture" : adapter.revision())
                    + ":" + incarnationFingerprint(adapter))).toList()) : List.of());
            result.put("codecs", valueCodecs != null ? sorted(valueCodecs.codecs().values().stream()
                .map(codec -> codec.id() + ":" + codec.version() + ":" + incarnationFingerprint(codec)).toList()) : List.of());
            result.put("codecAliases", valueCodecs != null ? sorted(valueCodecs.aliases().entrySet().stream()
                .map(entry -> entry.getKey() + ":" + entry.getValue()).toList()) : List.of());
            result.put("adapters", typeAdapters != null ? sorted(typeAdapters.getAdapters().keySet().stream()
                .map(pair -> pair.getSource().getName() + "->" + pair.getTarget().getName() + ":"
                    + incarnationFingerprint(typeAdapters.getAdapters().get(pair))).toList()) : List.of());
            result.put("stringParsers", typeAdapters != null ? sorted(typeAdapters.getStringParsers().keySet().stream()
                .map(type -> type.getName() + ":" + incarnationFingerprint(typeAdapters.getStringParsers().get(type))).toList()) : List.of());
            result.put("validators", validators != null ? validators.inventory() : List.of());
            result.put("validatorIncarnations", validators != null ? validators.incarnationFingerprint() : List.of());
            result.put("resources", resources != null ? sorted(resources.adapters().stream()
                .map(adapter -> adapter.descriptor().typeId() + ":" + adapter.getClass().getName()
                    + ":" + classLoaderFingerprint(adapter.getClass().getClassLoader()) + ":" + incarnationFingerprint(adapter)).toList()) : List.of());
            result.put("resourceMutationAuthority", resources != null ? resources.genericMutationAuthorityReason() : "");
            result.put("extensions", extensionData != null ? sorted(extensionData.pluginIds().stream()
                .map(pluginId -> pluginId + ":" + extensionData.version(pluginId) + ":" + extensionData.description(pluginId)
                    + ":" + canonical(extensionData.contributionCounts().get(pluginId))).toList()) : List.of());
            result.put("events", events != null ? sorted(events.getEventDefinitions().entrySet().stream()
                .map(entry -> entry.getKey() + ":" + entry.getValue().eventClass().getName()).toList()) : List.of());
            result.put("flows", flowRegistry != null ? sorted(flowRegistry.getRegisteredTypes()) : List.of());
            result.put("flowIncarnations", flowRegistry != null ? flowRegistry.executorIncarnationFingerprint() : List.of());
            return Map.copyOf(result);
        }

        private static String nodeFingerprint(NodeDefinition definition) {
            Map<String, Object> fields = new java.util.LinkedHashMap<>();
            fields.put("id", definition.getId());
            fields.put("owner", definition.getOwner());
            fields.put("displayName", definition.getDisplayName());
            fields.put("category", definition.getCategory() != null ? definition.getCategory().getId() : "");
            fields.put("inputs", definition.getInputs().stream().map(State::pinFingerprint).toList());
            fields.put("outputs", definition.getOutputs().stream().map(State::pinFingerprint).toList());
            fields.put("color", definition.getColor());
            fields.put("priority", definition.getPriority());
            fields.put("hidden", definition.isHidden());
            fields.put("hiddenReason", definition.getHiddenReason());
            fields.put("description", definition.getDescription());
            fields.put("handler", definition.getHandler());
            fields.put("handlerConfig", definition.getHandlerConfig());
            fields.put("trigger", definition.isTrigger());
            fields.put("eventType", definition.getEventType());
            fields.put("aliases", definition.getAliases());
            fields.put("outputMappings", definition.getOutputMappings());
            fields.put("schemaVersion", definition.getSchemaVersion());
            fields.put("kind", definition.getKind());
            fields.put("availability", availabilityFingerprint(definition.getAvailability()));
            fields.put("canonicalId", definition.getCanonicalId());
            fields.put("legacyIds", definition.getLegacyIds());
            fields.put("deprecated", definition.isDeprecated());
            fields.put("tags", definition.getTags());
            fields.put("examples", definition.getExamples());
            fields.put("family", definition.getFamily());
            fields.put("recommended", definition.isRecommended());
            fields.put("replacementFor", definition.getReplacementFor());
            fields.put("authorizationPolicy", definition.getAuthorizationPolicy());
            fields.put("sensitive", definition.isSensitive());
            fields.put("destructive", definition.isDestructive());
            fields.put("auditPolicy", definition.getAuditPolicy());
            fields.put("confirmationPolicy", definition.getConfirmationPolicy());
            fields.put("clockDomain", definition.getClockDomain());
            fields.put("authoredMetadata", definition.getAuthoredMetadata() != null ? definition.getAuthoredMetadata().toMetadata() : "");
            fields.put("migrationMapping", migrationFingerprint(definition.getMigrationMapping()));
            return canonical(fields);
        }

        private static String migrationFingerprint(NodeDefinition.MigrationMapping mapping) {
            if (mapping == null) {
                return "";
            }
            Map<String, Object> fields = new java.util.LinkedHashMap<>();
            fields.put("sourceSchemaVersion", mapping.sourceSchemaVersion());
            fields.put("targetSchemaVersion", mapping.targetSchemaVersion());
            fields.put("complete", mapping.complete());
            fields.put("pins", mapping.pins().stream().map(State::pinMigrationFingerprint).toList());
            return canonical(fields);
        }

        private static String pinMigrationFingerprint(NodeDefinition.PinMigrationMapping mapping) {
            Map<String, Object> fields = new java.util.LinkedHashMap<>();
            fields.put("sourcePinId", mapping.sourcePinId().value());
            fields.put("targetPinId", mapping.targetPinId().value());
            fields.put("direction", mapping.direction().name());
            fields.put("sourceSchemaVersion", mapping.sourceSchemaVersion());
            fields.put("targetSchemaVersion", mapping.targetSchemaVersion());
            return canonical(fields);
        }

        private static String availabilityFingerprint(NodeDefinition.Availability availability) {
            if (availability == null) {
                return "";
            }
            Map<String, Object> fields = new java.util.LinkedHashMap<>();
            fields.put("plugin", availability.getPlugin());
            fields.put("platform", availability.getPlatform());
            fields.put("minVersion", availability.getMinVersion());
            return canonical(fields);
        }

        private static String pinFingerprint(NodeDefinition.PinDefinition pin) {
            NodeDefinition.PinConstraints constraints = pin.getConstraints();
            NodeDefinition.RepeatablePin repeatable = pin.getRepeatable();
            Map<String, Object> fields = new java.util.LinkedHashMap<>();
            fields.put("name", pin.getName());
            fields.put("displayName", pin.getDisplayName());
            fields.put("runtimeName", pin.getRuntimeName());
            fields.put("type", pin.getType());
            fields.put("direction", pin.getDirection());
            fields.put("dataType", pin.getDataType() != null ? pin.getDataType().getId() : "");
            fields.put("typeRef", pin.getTypeRef());
            fields.put("repeatable", repeatableFingerprint(repeatable));
            fields.put("widgetType", pin.getWidgetType());
            fields.put("options", pin.getOptions());
            fields.put("optionsSource", pin.getOptionsSource());
            fields.put("defaultValue", pin.getDefaultValue());
            fields.put("constraints", constraintsFingerprint(constraints));
            fields.put("visibleWhen", pin.getVisibleWhen());
            fields.put("description", pin.getDescription());
            fields.put("optional", pin.isOptional());
            return canonical(fields);
        }

        private static String repeatableFingerprint(NodeDefinition.RepeatablePin repeatable) {
            if (repeatable == null) {
                return "";
            }
            Map<String, Object> fields = new java.util.LinkedHashMap<>();
            fields.put("groupId", repeatable.getGroupId());
            fields.put("minItems", repeatable.getMinItems());
            fields.put("maxItems", repeatable.getMaxItems());
            fields.put("itemLabel", repeatable.getItemLabel());
            return canonical(fields);
        }

        private static String constraintsFingerprint(NodeDefinition.PinConstraints constraints) {
            if (constraints == null) {
                return "";
            }
            Map<String, Object> fields = new java.util.LinkedHashMap<>();
            fields.put("min", constraints.getMin());
            fields.put("max", constraints.getMax());
            fields.put("step", constraints.getStep());
            return canonical(fields);
        }

        private static String handlerFingerprint(NodeHandler handler, Set<String> operations) {
            if (handler == null) {
                return "";
            }
            return handler.getClass().getName() + ":" + classLoaderFingerprint(handler.getClass().getClassLoader()) + ":"
                + incarnationFingerprint(handler) + ":" + canonical(operations);
        }

        private static String propertyFingerprint(PropertyRegistry registry, String family, String property) {
            PropertyRegistry.PropertyDescriptor descriptor = registry.getDescriptor(family, property);
            PropertyHandler<?, ?> handler = registry.get(family, property);
            return family + ":" + property + ":" + canonical(descriptor) + ":"
                + (handler == null ? "" : handler.getClass().getName() + ":" + classLoaderFingerprint(handler.getClass().getClassLoader())
                    + ":" + incarnationFingerprint(handler));
        }

        private static String incarnationFingerprint(Object value) {
            if (value == null) {
                return "";
            }
            return Integer.toHexString(System.identityHashCode(value));
        }

        private static String classLoaderFingerprint(ClassLoader loader) {
            return loader == null ? "bootstrap" : Integer.toHexString(System.identityHashCode(loader));
        }

        private static String canonical(Object value) {
            if (value == null) {
                return "null";
            }
            if (value instanceof Map<?, ?> map) {
                return map.entrySet().stream()
                    .sorted((first, second) -> String.valueOf(first.getKey()).compareTo(String.valueOf(second.getKey())))
                    .map(entry -> canonical(entry.getKey()) + "=" + canonical(entry.getValue()))
                    .collect(java.util.stream.Collectors.joining(",", "{", "}"));
            }
            if (value instanceof java.util.Set<?> set) {
                return set.stream().map(State::canonical).sorted()
                    .collect(java.util.stream.Collectors.joining(",", "[", "]"));
            }
            if (value instanceof Iterable<?> iterable) {
                List<String> values = new ArrayList<>();
                iterable.forEach(item -> values.add(canonical(item)));
                return values.stream().collect(java.util.stream.Collectors.joining(",", "[", "]"));
            }
            if (value.getClass().isArray()) {
                List<String> values = new ArrayList<>();
                int length = java.lang.reflect.Array.getLength(value);
                for (int index = 0; index < length; index++) {
                    values.add(canonical(java.lang.reflect.Array.get(value, index)));
                }
                return values.stream().collect(java.util.stream.Collectors.joining(",", "[", "]"));
            }
            return String.valueOf(value);
        }

        private static List<String> sorted(Iterable<String> values) {
            List<String> result = new ArrayList<>();
            values.forEach(value -> {
                if (value != null) {
                    result.add(value);
                }
            });
            result.sort(String.CASE_INSENSITIVE_ORDER);
            return List.copyOf(result);
        }

        public boolean semanticallyEquals(State other) {
            return other != null && fingerprint.equals(other.fingerprint);
        }
    }
}
