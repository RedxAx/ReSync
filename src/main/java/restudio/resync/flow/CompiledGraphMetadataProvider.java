package restudio.resync.flow;

import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowTypeRef;
import restudio.flow.data.CustomContentGraphAdapter;
import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.catalog.CatalogMigrationEdge;
import restudio.resync.flow.catalog.CatalogOwned;
import restudio.resync.flow.catalog.CatalogRuntimeActivation;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.diagnostic.DiagnosticPhase;
import restudio.resync.flow.diagnostic.DiagnosticSeverity;
import restudio.resync.flow.function.FunctionDiagnostic;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.function.FunctionSourceMaterializer;
import restudio.resync.flow.graph.GraphConnection;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphDocumentCodec;
import restudio.resync.flow.graph.GraphEndpoint;
import restudio.resync.flow.graph.FunctionBoundaryPins;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.GraphValidator;
import restudio.resync.flow.graph.PinValue;
import restudio.resync.flow.graph.ValidationResult;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ConnectionId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.identity.SnapshotId;
import restudio.resync.flow.runtime.RuntimeBindingManifest;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;
import restudio.resync.resources.ReSyncResourceCatalog;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class CompiledGraphMetadataProvider {
    private static final OwnerId RESOURCE_OWNER = OwnerId.of("restudio.resync");
    private static final Set<String> GRAPH_TYPES = Set.of(
        ReSyncResourceCatalog.FLOW,
        ReSyncResourceCatalog.FUNCTION,
        ReSyncResourceCatalog.COMMAND,
        ReSyncResourceCatalog.CUSTOM_CONTENT
    );
    private final Supplier<CatalogSnapshot> catalogSupplier;
    private final Supplier<RuntimeBindingManifest> runtimeManifestSupplier;
    private final Supplier<CatalogRuntimeActivation.ActivationRecord> activationSupplier;
    private final ServerId serverId;
    private final FlowValueCodecRegistry valueCodecs;

    public CompiledGraphMetadataProvider(
        Supplier<CatalogSnapshot> catalogSupplier,
        Supplier<RuntimeBindingManifest> runtimeManifestSupplier,
        ServerId serverId,
        FlowValueCodecRegistry valueCodecs
    ) {
        this.catalogSupplier = Objects.requireNonNull(catalogSupplier, "Compiled Catalog Supplier Is Required");
        this.runtimeManifestSupplier = Objects.requireNonNull(runtimeManifestSupplier, "Compiled Runtime Manifest Supplier Is Required");
        this.activationSupplier = null;
        this.serverId = Objects.requireNonNull(serverId, "Compiled Server ID Is Required");
        this.valueCodecs = Objects.requireNonNull(valueCodecs, "Compiled Value Codecs Are Required");
    }

    public CompiledGraphMetadataProvider(
        Supplier<CatalogRuntimeActivation.ActivationRecord> activationSupplier,
        ServerId serverId,
        FlowValueCodecRegistry valueCodecs
    ) {
        this.catalogSupplier = null;
        this.runtimeManifestSupplier = null;
        this.activationSupplier = Objects.requireNonNull(activationSupplier, "Compiled Activation Supplier Is Required");
        this.serverId = Objects.requireNonNull(serverId, "Compiled Server ID Is Required");
        this.valueCodecs = Objects.requireNonNull(valueCodecs, "Compiled Value Codecs Are Required");
    }

    public Result provide(GraphDocument graph, FunctionSourceDocument functionSource) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        ContentHash graphChecksum = null;
        ContentHash functionSourceChecksum = null;
        if (graph == null) {
            diagnostics.add(coreDiagnostic("GRAPH.NULL", null, null, null, "A loaded graph is required", Map.of()));
            return Result.rejected(diagnostics);
        }
        String resourceType = graph.resource().resourceType().value();
        if (!RESOURCE_OWNER.equals(graph.resource().owner()) || !GRAPH_TYPES.contains(resourceType)) {
            diagnostics.add(coreDiagnostic("GRAPH.OPAQUE_UNAVAILABLE", graph, null, null,
                "The graph resource locator is not an authoritative executable graph resource", Map.of(
                    "field", "resource",
                    "resource", graph.resource().canonicalText())));
            return Result.rejected(diagnostics);
        }
        boolean function = ReSyncResourceCatalog.FUNCTION.equals(resourceType);
        if (function && functionSource == null) {
            diagnostics.add(coreDiagnostic("GRAPH.OPAQUE_UNAVAILABLE", graph, null, null,
                "Function graphs require an explicit immutable Core Function source document before compiled execution", functionSourceEvidence(graph, false)));
            return Result.rejected(diagnostics);
        }
        if (!function && functionSource != null) {
            diagnostics.add(coreDiagnostic("GRAPH.OPAQUE_UNAVAILABLE", graph, null, null,
                "A Function source document can only accompany a function graph", Map.of("field", "functionSource")));
            return Result.rejected(diagnostics);
        }
        if (functionSource != null) {
            if (!graph.resource().equals(functionSource.signature().function().resource())
                || graph.revision() != functionSource.signature().revision().value()
                || !graph.resource().equals(functionSource.graph().resource())
                || graph.revision() != functionSource.graph().revision()) {
                diagnostics.add(coreDiagnostic("GRAPH.OPAQUE_UNAVAILABLE", graph, null, null,
                    "The Function source document does not match the persisted graph locator and revision", Map.of(
                        "field", "functionSource",
                        "graphResource", graph.resource().canonicalText(),
                        "sourceResource", functionSource.signature().function().resource().canonicalText(),
                        "graphRevision", graph.revision(),
                        "sourceRevision", functionSource.signature().revision().value())));
                return Result.rejected(diagnostics);
            }
            try {
                graphChecksum = graph.checksum();
                ContentHash sourceGraphChecksum = functionSource.graph().checksum();
                if (!graphChecksum.equals(sourceGraphChecksum)) {
                    diagnostics.add(coreDiagnostic("GRAPH.OPAQUE_UNAVAILABLE", graph, null, null,
                        "The Function source body checksum does not match the supplied Core graph", Map.of(
                            "field", "functionSource",
                            "boundary", "function-source-v1",
                            "graphChecksum", graphChecksum.canonicalText(),
                            "sourceGraphChecksum", sourceGraphChecksum.canonicalText())));
                    return Result.rejected(diagnostics);
                }
                functionSourceChecksum = functionSource.checksum();
            } catch (RuntimeException failure) {
                diagnostics.add(coreDiagnostic("GRAPH.OPAQUE_UNAVAILABLE", graph, null, null,
                    "The Function source checksum could not be validated", Map.of(
                        "field", "functionSource",
                        "boundary", "function-source-v1",
                        "failureType", failure.getClass().getName(),
                        "failure", value(failure.getMessage()))));
                return Result.rejected(diagnostics);
            }
            try {
                FunctionSourceMaterializer.Result validation = new FunctionSourceMaterializer().validate(functionSource);
                diagnostics.addAll(validation.diagnostics().stream().map(FunctionDiagnostic::diagnostic).toList());
            } catch (RuntimeException failure) {
                diagnostics.add(coreDiagnostic("GRAPH.OPAQUE_UNAVAILABLE", graph, null, null,
                    "The Function source document could not be validated", Map.of(
                        "field", "functionSource",
                        "failureType", failure.getClass().getName(),
                        "failure", value(failure.getMessage()))));
            }
            if (!diagnostics.isEmpty()) {
                return Result.rejected(diagnostics);
            }
        }
        CatalogSnapshot catalog = null;
        RuntimeBindingManifest manifest = null;
        if (activationSupplier != null) {
            try {
                CatalogRuntimeActivation.ActivationRecord activation = activationSupplier.get();
                if (activation == null) {
                    throw new IllegalStateException("Active Catalog Runtime Activation Is Missing");
                }
                catalog = activation.catalog();
                manifest = activation.runtimeManifest();
            } catch (RuntimeException failure) {
                diagnostics.add(coreDiagnostic("GRAPH.CATALOG_REQUIRED", graph, null, null,
                    "The active catalog runtime activation could not be read", Map.of("failureType", failure.getClass().getName())));
            }
        } else {
            try {
                catalog = catalogSupplier.get();
            } catch (RuntimeException failure) {
                diagnostics.add(coreDiagnostic("GRAPH.CATALOG_REQUIRED", graph, null, null,
                    "The active catalog could not be read", Map.of("failureType", failure.getClass().getName())));
            }
            try {
                manifest = runtimeManifestSupplier.get();
            } catch (RuntimeException failure) {
                diagnostics.add(coreDiagnostic("GRAPH.RUNTIME_BINDING_MISSING", graph, null, null,
                    "The active runtime binding manifest could not be read", Map.of("failureType", failure.getClass().getName())));
            }
        }
        if (catalog == null) {
            diagnostics.add(coreDiagnostic("GRAPH.CATALOG_REQUIRED", graph, null, null,
                "An active catalog snapshot is required", Map.of()));
        }
        if (manifest == null) {
            diagnostics.add(coreDiagnostic("GRAPH.RUNTIME_BINDING_MISSING", graph, null, null,
                "An active runtime binding manifest is required", Map.of()));
        } else if (catalog != null && !manifest.bindingManifestHash().equals(catalog.bindingManifestHash())) {
            diagnostics.add(coreDiagnostic("GRAPH.RUNTIME_BINDING_MISSING", graph, null, null,
                "The active runtime bindings do not match the active catalog", Map.of(
                    "catalogBindingManifestHash", catalog.bindingManifestHash().canonicalText(),
                    "runtimeBindingManifestHash", manifest.bindingManifestHash().canonicalText())));
        }
        if (!diagnostics.isEmpty()) {
            return Result.rejected(diagnostics);
        }
        ValidationResult validation;
        try {
            validation = functionSource == null
                ? new GraphValidator().validate(graph, catalog, manifest)
                : new GraphValidator().validate(functionSource, catalog, manifest);
        } catch (RuntimeException failure) {
            diagnostics.add(coreDiagnostic("GRAPH.OPAQUE_UNAVAILABLE", graph, null, null,
                "The Core graph could not be validated against the active catalog and runtime", Map.of(
                    "failureType", failure.getClass().getName(),
                    "failure", value(failure.getMessage()))));
            return Result.rejected(diagnostics);
        }
        if (!validation.diagnostics().isEmpty()) {
            return Result.rejected(validation.diagnostics());
        }
        CatalogBinding binding = graph.catalogBinding();
        if (graphChecksum == null) {
            try {
                graphChecksum = graph.checksum();
            } catch (RuntimeException failure) {
                diagnostics.add(coreDiagnostic("GRAPH.OPAQUE_UNAVAILABLE", graph, null, null,
                    "The persisted Core graph has no canonical checksum", Map.of(
                        "field", "checksum",
                        "failureType", failure.getClass().getName(),
                        "failure", value(failure.getMessage()))));
                return Result.rejected(diagnostics);
            }
        }
        SnapshotId snapshotId = SnapshotId.deterministic("compiled-graph|" + graph.resource().canonicalText() + "|"
            + graph.revision() + "|" + graphChecksum.canonicalText() + "|" + binding.canonicalText() + "|"
            + (functionSourceChecksum == null ? "" : functionSourceChecksum.canonicalText()));
        Map<String, NodeInstanceId> nodeInstances = new LinkedHashMap<>();
        Map<String, ContractRef<NodeId>> definitions = new LinkedHashMap<>();
        Map<CompiledGraphMetadata.PinAddress, PinId> pins = new LinkedHashMap<>();
        Map<CompiledGraphMetadata.ConnectionAddress, ConnectionId> connections = new LinkedHashMap<>();
        Map<CompiledGraphMetadata.PinAddress, TypedValue> inputValues = new LinkedHashMap<>();
        Map<String, String> handlerConfigCanonical = new LinkedHashMap<>();
        for (GraphNode node : graph.nodes().stream().sorted(Comparator.comparing(GraphNode::instanceId)).toList()) {
            String nodeKey = node.instanceId().canonicalText();
            CatalogOwned<CatalogNodeDescriptor> owned = catalog.definition(node.definition()).orElse(null);
            if (owned == null) {
                diagnostics.add(coreDiagnostic("GRAPH.DEFINITION_MISSING", graph, node.instanceId(), null,
                    "The loaded node has no authoritative catalog definition", Map.of(
                        "nodeInstanceId", nodeKey,
                        "definition", node.definition().canonicalText())));
                continue;
            }
            CatalogNodeDescriptor definition = owned.descriptor();
            nodeInstances.put(nodeKey, node.instanceId());
            definitions.put(nodeKey, node.definition());
            try {
                handlerConfigCanonical.put(nodeKey, canonicalHandlerConfig(definition));
            } catch (RuntimeException failure) {
                diagnostics.add(coreDiagnostic("GRAPH.OPAQUE_UNAVAILABLE", graph, node.instanceId(), null,
                    "The active node definition has no canonical handler configuration", Map.of(
                        "field", "handlerConfig",
                        "failureType", failure.getClass().getName(),
                        "failure", value(failure.getMessage()))));
            }
            Map<PinId, FunctionBoundaryPins.EffectivePin> pinsById = FunctionBoundaryPins.resolve(owned,
                functionSource == null ? null : functionSource.signature(), node);
            for (FunctionBoundaryPins.EffectivePin pin : pinsById.values()) {
                pins.put(new CompiledGraphMetadata.PinAddress(nodeKey, pin.id().canonicalText()), pin.id());
            }
            for (PinValue pinValue : node.configuredValues().values()) {
                FunctionBoundaryPins.EffectivePin pin = pinsById.get(pinValue.pinId());
                if (pin == null || pin.direction() != CatalogNodeDescriptor.Direction.INPUT) {
                    continue;
                }
                try {
                    TypedValue value = TypedResourceReferenceBoundary.requireValue(
                        pin.type(), pinValue.value(), graph.resource().serverId());
                    inputValues.put(new CompiledGraphMetadata.PinAddress(nodeKey, pin.id().canonicalText()), value);
                } catch (RuntimeException failure) {
                    diagnostics.add(coreDiagnostic("GRAPH.PIN_TYPE_MISMATCH", graph, node.instanceId(), pin.id(),
                        "A configured Core graph input cannot be bound to its authoritative type", Map.of(
                            "pin", pin.id().canonicalText(),
                            "failureType", failure.getClass().getName(),
                            "failure", value(failure.getMessage()))));
                }
            }
        }
        Set<CompiledGraphMetadata.ConnectionAddress> seenConnections = new HashSet<>();
        for (GraphConnection connection : graph.connections()) {
            GraphEndpoint source = connection.source();
            GraphEndpoint target = connection.target();
            CompiledGraphMetadata.ConnectionAddress address = CompiledGraphMetadata.ConnectionAddress.of(source, target);
            if (!seenConnections.add(address)) {
                diagnostics.add(coreDiagnostic("GRAPH.OPAQUE_UNAVAILABLE", graph, null, null,
                    "Duplicate Core graph connection endpoints cannot be projected deterministically", Map.of(
                        "field", "connections",
                        "connection", address.canonicalText())));
                continue;
            }
            connections.put(address, connection.connectionId());
        }
        if (!diagnostics.isEmpty()) {
            return Result.rejected(diagnostics);
        }
        CompiledGraphMetadata metadata = new CompiledGraphMetadata(graph.resource(), binding, snapshotId,
            nodeInstances, definitions, pins, connections, inputValues, handlerConfigCanonical, functionSource);
        FlowExecutionBridge.MappingContext mappingContext;
        try {
            Map<String, NodeId> nodeMappings = new LinkedHashMap<>();
            definitions.forEach((nodeId, definition) -> nodeMappings.put(nodeId, definition.id()));
            Map<String, PinId> pinMappings = new LinkedHashMap<>();
            pins.forEach((address, pin) -> pinMappings.put(FlowExecutionBridge.MappingContext.pinMappingKey(address.nodeId(), address.pin()), pin));
            mappingContext = new FlowExecutionBridge.MappingContext(graph.resource(), binding, snapshotId,
                nodeMappings, pinMappings, List.of(), graph.connections().stream()
                    .collect(Collectors.toUnmodifiableMap(GraphConnection::connectionId, connection -> connection)));
        } catch (RuntimeException failure) {
            diagnostics.add(coreDiagnostic("GRAPH.OPAQUE_UNAVAILABLE", graph, null, null,
                "The complete Core mapping context could not be constructed without losing persisted identities", Map.of(
                    "field", "mappingContext",
                    "failureType", failure.getClass().getName(),
                    "failure", value(failure.getMessage()))));
            return Result.rejected(diagnostics);
        }
        return new Result(metadata, mappingContext, List.of());
    }

    public GraphDocument projectCustomContent(FlowGraph source) {
        if (source == null || !ReSyncResourceCatalog.CUSTOM_CONTENT.equals(source.getResourceType())) {
            throw new IllegalArgumentException("A typed custom content graph is required");
        }
        CatalogSnapshot catalog = activationSupplier != null
            ? Objects.requireNonNull(activationSupplier.get(), "Active catalog runtime is required").catalog()
            : Objects.requireNonNull(catalogSupplier.get(), "Active catalog is required");
        if (source.getOpaqueProperties().containsKey("contentCoreGraph")) {
            GraphDocument stored = GraphDocumentCodec.INSTANCE.decode(CanonicalCodec.decodePermissive(
                source.getOpaqueProperties().get("contentCoreGraph").toString()));
            ServerResourceLocator resource = new ServerResourceLocator(serverId,
                ContractRef.of(RESOURCE_OWNER, ResourceTypeId.of(ReSyncResourceCatalog.CUSTOM_CONTENT)), source.getId());
            if (!resource.equals(stored.resource()) || stored.revision() > source.getResourceRevision()) {
                throw new IllegalArgumentException("Embedded Core content graph does not match its aggregate identity and revision");
            }
            CatalogBinding target = new CatalogBinding(catalog.generation(), catalog.contentChecksum(), catalog.bindingManifestHash());
            if (!stored.catalogBinding().equals(target) && stored.catalogBinding().generation() >= target.generation()) {
                throw new IllegalArgumentException("Embedded Core content graph requires an exact or compatible forward catalog binding");
            }
            GraphDocument rebound = new GraphDocument(stored.schemaVersion(), resource, source.getResourceRevision(),
                target,
                stored.requiredCapabilities(), stored.nodes(), stored.connections(), stored.passthroughs(), stored.variables(), stored.functions(), stored.unknown());
            Result admitted = provide(rebound, null);
            if (!admitted.accepted()) {
                throw admitted.failure();
            }
            return rebound;
        }
        FlowGraph graph = source.copy();
        graph.getContentProperties().clear();
        for (Map.Entry<String, FlowNode> entry : graph.getNodes().entrySet()) {
            FlowNode node = entry.getValue();
            CatalogOwned<CatalogNodeDescriptor> owned = catalog.definitions().stream()
                .filter(value -> value.descriptor().id().value().equals(node.getType()))
                .filter(value -> RESOURCE_OWNER.equals(value.key().owner())).findFirst()
                .orElseThrow(() -> new UnsupportedGraphException(List.of(diagnostic("GRAPH.DEFINITION_MISSING", source, entry.getKey(),
                    "The custom content node has no authoritative catalog definition", Map.of("definitionId", node.getType(),
                        "definitionOwner", RESOURCE_OWNER.value(), "matches", 0, "metadataContract", "authored-source-or-legacy-source-node")))));
            CatalogNodeDescriptor definition = owned.descriptor();
            if (CustomContentGraphAdapter.typeFromNode(node.getType()) != null) {
                node.getInputValues().remove(CustomContentGraphAdapter.FLOW_BRANCHES_KEY);
            }
            if (CustomContentGraphAdapter.ARMOR_NODE.equals(node.getType())
                && definition.pins().stream().noneMatch(pin -> pin.direction() == CatalogNodeDescriptor.Direction.INPUT
                    && "hand_filter".equals(pin.id().value()))) {
                node.getInputValues().remove("hand_filter");
            }
            while (node.getVersion() < definition.schemaVersion()) {
                List<CatalogMigrationEdge> edges = catalog.migrations().stream().map(CatalogOwned::descriptor)
                    .filter(edge -> RESOURCE_OWNER.equals(edge.ownerId()) && definition.id().equals(edge.nodeId()))
                    .filter(edge -> edge.fromVersion() == node.getVersion() && edge.toVersion() <= definition.schemaVersion())
                    .toList();
                if (edges.size() != 1) {
                    throw new IllegalArgumentException("Custom content node has no unique declared schema migration: " + node.getType());
                }
                CatalogMigrationEdge edge = edges.getFirst();
                Map<String, Object> inputs = new LinkedHashMap<>();
                node.getInputValues().forEach((pin, value) -> {
                    String target = migratedPin(edge, pin, CatalogNodeDescriptor.Direction.INPUT);
                    if (inputs.containsKey(target)) {
                        throw new IllegalArgumentException("Custom content schema migration has conflicting inputs: " + target);
                    }
                    inputs.put(target, value);
                });
                node.setInputValues(inputs);
                for (FlowConnection connection : graph.getConnections()) {
                    if (entry.getKey().equals(connection.getSourceNodeId())) {
                        connection.setSourcePin(migratedPin(edge, connection.getSourcePin(), CatalogNodeDescriptor.Direction.OUTPUT));
                    }
                    if (entry.getKey().equals(connection.getTargetNodeId())) {
                        connection.setTargetPin(migratedPin(edge, connection.getTargetPin(), CatalogNodeDescriptor.Direction.INPUT));
                    }
                }
                node.setVersion(edge.toVersion());
            }
            if (node.getHandlerConfigValues().isEmpty() && definitionSource(definition).get("handlerConfig") instanceof Map<?, ?> config) {
                Map<String, Object> values = new LinkedHashMap<>();
                config.forEach((key, value) -> values.put((String) key, value));
                node.setHandlerConfig(values);
            }
        }
        Result mapped = provide(graph, true);
        if (!mapped.accepted()) {
            throw mapped.failure();
        }
        CompiledGraphMaterializer.Result result = new CompiledGraphMaterializer().materialize(graph, mapped.metadata());
        if (!result.materialized()) {
            throw new IllegalArgumentException("Custom content Core projection was rejected: " + result.diagnostics());
        }
        return result.document();
    }

    private static String migratedPin(CatalogMigrationEdge edge, String pin, CatalogNodeDescriptor.Direction direction) {
        return edge.pinMappings().stream().filter(mapping -> mapping.direction() == direction && mapping.source().value().equals(pin))
            .map(mapping -> mapping.target().value()).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Custom content schema migration has no mapping for " + direction + " pin " + pin));
    }

    public Result provide(FlowGraph graph) {
        return provide(graph, false);
    }

    private Result provide(FlowGraph graph, boolean contentProjection) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (graph == null) {
            diagnostics.add(diagnostic("GRAPH.NULL", null, null, "A loaded graph is required", Map.of()));
            return Result.rejected(diagnostics);
        }
        if (graph.getId() == null || graph.getId().isBlank() || !graph.getId().equals(graph.getId().strip())) {
            diagnostics.add(diagnostic("GRAPH.OPAQUE_UNAVAILABLE", graph, null, "The loaded graph has no canonical resource ID", Map.of("field", "id")));
        }
        String resourceType = graph.getResourceType();
        if (!GRAPH_TYPES.contains(resourceType) || ReSyncResourceCatalog.byType(resourceType) == null) {
            diagnostics.add(diagnostic("GRAPH.OPAQUE_UNAVAILABLE", graph, null, "The graph resource type is not an authoritative executable graph type", Map.of("resourceType", value(resourceType))));
        }
        if (graph.getResourceRevision() < 1) {
            diagnostics.add(diagnostic("GRAPH.OPAQUE_UNAVAILABLE", graph, null, "The loaded graph has no persisted positive revision", Map.of("resourceRevision", graph.getResourceRevision())));
        }
        ContentHash resourceHash = null;
        try {
            resourceHash = ContentHash.of(graph.getResourceHash());
        } catch (RuntimeException failure) {
            diagnostics.add(diagnostic("GRAPH.OPAQUE_UNAVAILABLE", graph, null, "The loaded graph has no canonical content hash", Map.of("field", "resourceHash")));
        }
        if (graph.isFunction() || ReSyncResourceCatalog.FUNCTION.equals(resourceType)) {
            diagnostics.add(diagnostic("GRAPH.OPAQUE_UNAVAILABLE", graph, null,
                "Function graphs require an explicit immutable Core Function source document before compiled execution", Map.of(
                    "field", "function",
                    "boundary", "function-source-v1",
                    "requiredMetadata", List.of(
                        "typedFunctionLocator",
                        "functionRevision",
                        "immutableParameterIds",
                        "typedParameterDefaults",
                        "immutableBodyNodeIds",
                        "immutablePinIds",
                        "typedBodyLiterals",
                        "durableConnectionIds",
                        "requiredCapabilityIdentities",
                        "runtimeBindingFingerprint"),
                    "legacyMetadataAvailable", Map.of(
                        "functionMarker", graph.isFunction(),
                        "resourceType", value(resourceType),
                        "inputParameterCount", graph.getFunctionInputs().size(),
                        "outputParameterCount", graph.getFunctionOutputs().size(),
                        "nodeCount", graph.getNodes().size(),
                        "connectionCount", graph.getConnections().size()))));
            return Result.rejected(diagnostics);
        }
        CatalogSnapshot catalog = null;
        RuntimeBindingManifest manifest = null;
        if (activationSupplier != null) {
            try {
                CatalogRuntimeActivation.ActivationRecord activation = activationSupplier.get();
                if (activation == null) {
                    throw new IllegalStateException("Active Catalog Runtime Activation Is Missing");
                }
                catalog = activation.catalog();
                manifest = activation.runtimeManifest();
            } catch (RuntimeException failure) {
                diagnostics.add(diagnostic("GRAPH.CATALOG_REQUIRED", graph, null, "The active catalog runtime activation could not be read", Map.of("failureType", failure.getClass().getName())));
            }
        } else {
            try {
                catalog = catalogSupplier.get();
            } catch (RuntimeException failure) {
                diagnostics.add(diagnostic("GRAPH.CATALOG_REQUIRED", graph, null, "The active catalog could not be read", Map.of("failureType", failure.getClass().getName())));
            }
            if (catalog == null) {
                diagnostics.add(diagnostic("GRAPH.CATALOG_REQUIRED", graph, null, "An active catalog snapshot is required", Map.of()));
            }
            try {
                manifest = runtimeManifestSupplier.get();
            } catch (RuntimeException failure) {
                diagnostics.add(diagnostic("GRAPH.RUNTIME_BINDING_MISSING", graph, null, "The active runtime binding manifest could not be read", Map.of("failureType", failure.getClass().getName())));
            }
        }
        if (catalog == null) {
            diagnostics.add(diagnostic("GRAPH.CATALOG_REQUIRED", graph, null, "An active catalog snapshot is required", Map.of()));
        }
        if (manifest == null) {
            diagnostics.add(diagnostic("GRAPH.RUNTIME_BINDING_MISSING", graph, null, "An active runtime binding manifest is required", Map.of()));
        } else if (catalog != null && !manifest.bindingManifestHash().equals(catalog.bindingManifestHash())) {
            diagnostics.add(diagnostic("GRAPH.RUNTIME_BINDING_MISSING", graph, null, "The active runtime bindings do not match the active catalog", Map.of(
                "catalogBindingManifestHash", catalog.bindingManifestHash().canonicalText(),
                "runtimeBindingManifestHash", manifest.bindingManifestHash().canonicalText())));
        }
        if (!diagnostics.isEmpty()) {
            return Result.rejected(diagnostics);
        }
        ServerResourceLocator resource;
        try {
            resource = new ServerResourceLocator(serverId,
                ContractRef.of(RESOURCE_OWNER, ResourceTypeId.of(resourceType)), graph.getId());
        } catch (RuntimeException failure) {
            diagnostics.add(diagnostic("GRAPH.OPAQUE_UNAVAILABLE", graph, null,
                "The graph resource identity is not canonical", Map.of(
                    "field", "resource",
                    "failureType", failure.getClass().getName(),
                    "failure", value(failure.getMessage()))));
            return Result.rejected(diagnostics);
        }
        CatalogBinding binding = new CatalogBinding(catalog.generation(), catalog.contentChecksum(), catalog.bindingManifestHash());
        SnapshotId snapshotId = SnapshotId.deterministic("compiled-graph|" + resource.canonicalText() + "|"
            + graph.getResourceRevision() + "|" + resourceHash.canonicalText() + "|" + binding.canonicalText());
        Map<String, NodeInstanceId> nodeInstances = new LinkedHashMap<>();
        Map<String, ContractRef<NodeId>> definitions = new LinkedHashMap<>();
        Map<CompiledGraphMetadata.PinAddress, PinId> pins = new LinkedHashMap<>();
        Map<CompiledGraphMetadata.ConnectionAddress, ConnectionId> connections = new LinkedHashMap<>();
        Map<CompiledGraphMetadata.PinAddress, TypedValue> inputValues = new LinkedHashMap<>();
        Map<String, String> handlerConfigCanonical = new LinkedHashMap<>();
        Map<String, CatalogNodeDescriptor> nodeDefinitions = new HashMap<>();
        for (Map.Entry<String, FlowNode> entry : graph.getNodes().entrySet().stream()
            .sorted(Comparator.comparing((Map.Entry<String, FlowNode> value) -> value.getKey(),
                Comparator.nullsFirst(Comparator.naturalOrder())))
            .toList()) {
            String nodeId = entry.getKey();
            FlowNode node = entry.getValue();
            if (!canonicalText(nodeId) || node == null) {
                diagnostics.add(diagnostic("GRAPH.OPAQUE_UNAVAILABLE", graph, null, "A graph node has no complete canonical identity", Map.of("nodeId", value(nodeId))));
                continue;
            }
            if (node.getType() == null || node.getType().isBlank() || node.getVersion() < 1) {
                diagnostics.add(diagnostic("GRAPH.OPAQUE_UNAVAILABLE", graph, nodeId, "A graph node has no canonical type or positive version", Map.of(
                    "nodeType", value(node.getType()), "nodeVersion", node.getVersion())));
                continue;
            }
            List<CatalogOwned<CatalogNodeDescriptor>> matches = catalog.definitions().stream()
                .filter(value -> value.descriptor().id().value().equals(node.getType()))
                .filter(value -> !contentProjection || RESOURCE_OWNER.equals(value.key().owner()))
                .filter(value -> matchesSource(value, node.getType()))
                .toList();
            if (matches.size() != 1) {
                diagnostics.add(diagnostic("GRAPH.DEFINITION_MISSING", graph, nodeId, "The loaded node has no unique authoritative catalog definition", Map.of(
                    "nodeType", node.getType(), "definitionId", node.getType(), "definitionOwner", RESOURCE_OWNER.value(),
                    "matches", matches.size(), "metadataContract", "authored-source-or-legacy-source-node")));
                continue;
            }
            CatalogOwned<CatalogNodeDescriptor> owned = matches.getFirst();
            CatalogNodeDescriptor definition = owned.descriptor();
            if (node.getVersion() != definition.schemaVersion()) {
                diagnostics.add(diagnostic("GRAPH.OPAQUE_UNAVAILABLE", graph, nodeId,
                    "The persisted node schema version does not match the active catalog definition", Map.of(
                        "field", "version",
                        "nodeVersion", node.getVersion(),
                        "catalogSchemaVersion", definition.schemaVersion())));
                continue;
            }
            String activeHandlerConfig;
            try {
                activeHandlerConfig = canonicalHandlerConfig(definition);
            } catch (RuntimeException failure) {
                diagnostics.add(diagnostic("GRAPH.OPAQUE_UNAVAILABLE", graph, nodeId,
                    "The active node definition has no canonical handler configuration", Map.of(
                        "field", "handlerConfig",
                        "failureType", failure.getClass().getName(),
                        "failure", value(failure.getMessage()))));
                continue;
            }
            Map<String, Object> authoredConfig = node.getHandlerConfigValues();
            String persistedHandlerConfig;
            try {
                persistedHandlerConfig = CanonicalJson.canonicalize(ItemStackPropertySelector.canonicalHandlerConfig(
                    node.getType(), authoredConfig));
            } catch (RuntimeException failure) {
                diagnostics.add(diagnostic("GRAPH.OPAQUE_UNAVAILABLE", graph, nodeId,
                    "Persisted handler configuration is not canonical JSON", Map.of(
                        "field", "handlerConfig",
                        "failureType", failure.getClass().getName(),
                        "failure", value(failure.getMessage()))));
                continue;
            }
            if (!activeHandlerConfig.equals(persistedHandlerConfig)) {
                diagnostics.add(diagnostic("GRAPH.OPAQUE_UNAVAILABLE", graph, nodeId,
                    "Persisted handler configuration does not exactly match the active node definition", Map.of(
                        "field", "handlerConfig",
                        "expected", activeHandlerConfig,
                        "actual", persistedHandlerConfig)));
                continue;
            }
            handlerConfigCanonical.put(nodeId, activeHandlerConfig);
            nodeDefinitions.put(nodeId, definition);
            nodeInstances.put(nodeId, NodeInstanceId.deterministic(snapshotId.value(), "compiled-graph-node", graph.getId() + "\u0000" + nodeId));
            definitions.put(nodeId, ContractRef.of(owned.key().owner(), definition.id()));
            Map<String, CatalogNodeDescriptor.Pin> pinsById = new HashMap<>();
            for (CatalogNodeDescriptor.Pin pin : definition.pins()) {
                if (pinsById.put(pin.id().value(), pin) != null) {
                    diagnostics.add(diagnostic("GRAPH.PIN_UNRESOLVED", graph, nodeId, "The authoritative node definition contains duplicate pin identity", Map.of("pin", pin.id().value())));
                    continue;
                }
                pins.put(new CompiledGraphMetadata.PinAddress(nodeId, pin.id().value()), pin.id());
            }
            Map<String, Object> configuredInputs = ItemStackPropertySelector.effectiveInputs(
                node.getType(), node.getInputValues(), authoredConfig);
            for (Map.Entry<String, Object> input : configuredInputs.entrySet()) {
                String pinName = input.getKey();
                CatalogNodeDescriptor.Pin pin = pinsById.get(pinName);
                if (pin == null || pin.direction() != CatalogNodeDescriptor.Direction.INPUT) {
                    diagnostics.add(diagnostic("GRAPH.PIN_UNRESOLVED", graph, nodeId, "A configured graph input is not an authoritative input pin", Map.of("pin", value(pinName))));
                    continue;
                }
                try {
                    TypedValue inputValue = contentProjection && pin.type() instanceof TypeExpr.Named named
                        && named.reference().equals(TypeReference.of("builtin", "any")) && named.arguments().isEmpty()
                        ? input.getValue() == null ? TypedValue.nullValue(pin.type())
                            : TypedValue.value(pin.type(), CanonicalJson.parse(CanonicalJson.canonicalize(input.getValue())))
                        : contentProjection && pin.type() instanceof TypeExpr.Named named && named.arguments().isEmpty()
                            && FlowDataType.fromString(named.reference().localId()).getJavaType() != null
                            && FlowDataType.fromString(named.reference().localId()).getJavaType().isEnum()
                            ? typedValue(pin.type(), valueCodecs.decode(FlowTypeRef.simple(named.reference().localId()), input.getValue()), graph, nodeId, pin)
                            : typedValue(pin.type(), input.getValue(), graph, nodeId, pin);
                    inputValues.put(new CompiledGraphMetadata.PinAddress(nodeId, pinName), inputValue);
                } catch (RuntimeException failure) {
                    diagnostics.add(diagnostic("GRAPH.PIN_TYPE_MISMATCH", graph, nodeId, "A configured graph input cannot be converted to its authoritative type", Map.of(
                        "pin", pinName, "type", pin.type().canonicalJson(), "failureType", failure.getClass().getName(),
                        "failure", value(failure.getMessage()))));
                }
            }
        }
        Set<CompiledGraphMetadata.ConnectionAddress> seenConnections = new HashSet<>();
        for (FlowConnection connection : graph.getConnections()) {
            if (connection == null || connection.getSourceNodeId() == null || connection.getSourcePin() == null
                || connection.getTargetNodeId() == null || connection.getTargetPin() == null
                || !nodeDefinitions.containsKey(connection.getSourceNodeId())
                || !nodeDefinitions.containsKey(connection.getTargetNodeId())) {
                diagnostics.add(diagnostic("GRAPH.OPAQUE_UNAVAILABLE", graph, null, "A graph connection has incomplete authoritative endpoints", Map.of("field", "connections")));
                continue;
            }
            if (connection.getEditorSourceNodeId() != null || connection.getEditorSourcePin() != null) {
                diagnostics.add(diagnostic("GRAPH.OPAQUE_UNAVAILABLE", graph, null, "Editor-only connection metadata cannot be compiled", Map.of("field", "editorSource")));
                continue;
            }
            CompiledGraphMetadata.ConnectionAddress address;
            try {
                address = new CompiledGraphMetadata.ConnectionAddress(
                    connection.getSourceNodeId(), connection.getSourcePin(), connection.getTargetNodeId(), connection.getTargetPin());
            } catch (RuntimeException failure) {
                diagnostics.add(diagnostic("GRAPH.OPAQUE_UNAVAILABLE", graph, null,
                    "A graph connection contains non-canonical endpoint text", Map.of(
                        "field", "connections",
                        "failureType", failure.getClass().getName(),
                        "failure", value(failure.getMessage()))));
                continue;
            }
            if (!seenConnections.add(address)) {
                diagnostics.add(diagnostic("GRAPH.OPAQUE_UNAVAILABLE", graph, null, "Duplicate graph connection endpoints cannot receive canonical identity", Map.of("connection", address.canonicalText())));
                continue;
            }
            if (!pins.containsKey(new CompiledGraphMetadata.PinAddress(connection.getSourceNodeId(), connection.getSourcePin()))
                || !pins.containsKey(new CompiledGraphMetadata.PinAddress(connection.getTargetNodeId(), connection.getTargetPin()))) {
                diagnostics.add(diagnostic("GRAPH.PIN_UNRESOLVED", graph, null, "A graph connection endpoint is not an authoritative pin", Map.of("connection", address.canonicalText())));
                continue;
            }
            connections.put(address, ConnectionId.deterministic(snapshotId.value(), "compiled-graph-connection", address.canonicalText()));
        }
        if (graph.getNodes().isEmpty()) {
            diagnostics.add(diagnostic("GRAPH.OPAQUE_UNAVAILABLE", graph, null, "The compiled graph must contain at least one node", Map.of("field", "nodes")));
        }
        if (!diagnostics.isEmpty()) {
            return Result.rejected(diagnostics);
        }
        CompiledGraphMetadata metadata = new CompiledGraphMetadata(resource, binding, snapshotId, nodeInstances, definitions,
            pins, connections, inputValues, handlerConfigCanonical);
        try {
            Map<String, NodeId> nodeMappings = new LinkedHashMap<>();
            definitions.forEach((nodeId, definition) -> nodeMappings.put(nodeId, definition.id()));
            Map<String, PinId> pinMappings = new LinkedHashMap<>();
            pins.forEach((address, pin) -> pinMappings.put(FlowExecutionBridge.MappingContext.pinMappingKey(address.nodeId(), address.pin()), pin));
            FlowExecutionBridge.MappingContext mappingContext = new FlowExecutionBridge.MappingContext(
                resource, binding, snapshotId, nodeMappings, pinMappings, List.of());
            return new Result(metadata, mappingContext, List.of());
        } catch (RuntimeException failure) {
            diagnostics.add(diagnostic("GRAPH.OPAQUE_UNAVAILABLE", graph, null, "The complete compiled mapping context could not be constructed", Map.of(
                "failureType", failure.getClass().getName(), "failure", value(failure.getMessage()))));
            return Result.rejected(diagnostics);
        }
    }

    private TypedValue typedValue(TypeExpr type, Object raw, FlowGraph graph, String nodeId, CatalogNodeDescriptor.Pin pin) {
        if (raw instanceof TypedValue typed) {
            return TypedResourceReferenceBoundary.requireValue(type, typed, serverId);
        }
        if (raw == null) {
            TypedResourceReferenceBoundary.canonicalize(type, null, serverId);
            return TypedValue.nullValue(type);
        }
        Object value = canonicalValue(type, raw);
        if (value == null && type instanceof TypeExpr.OptionalType) {
            return TypedValue.nullValue(type);
        }
        if (type instanceof TypeExpr.ResourceType resource && value instanceof ServerResourceLocator locator) {
            return TypedValue.locator(type, locator);
        }
        return TypedValue.value(type, value);
    }

    private Object canonicalValue(TypeExpr type, Object raw) {
        raw = TypedResourceReferenceBoundary.canonicalize(type, raw, serverId);
        if (raw == null) {
            if (type instanceof TypeExpr.OptionalType) {
                return null;
            }
            throw new IllegalArgumentException("A non-optional typed input cannot be null");
        }
        return switch (type) {
            case TypeExpr.ResourceType resource -> resourceLocator(resource, raw);
            case TypeExpr.Named named -> namedValue(named, raw);
            case TypeExpr.ListType list -> {
                if (!(raw instanceof Iterable<?> iterable)) {
                    throw new IllegalArgumentException("List input is not iterable");
                }
                List<Object> values = new ArrayList<>();
                for (Object value : iterable) {
                    values.add(canonicalValue(list.element(), value));
                }
                yield Collections.unmodifiableList(values);
            }
            case TypeExpr.MapType map -> {
                if (!(raw instanceof Map<?, ?> source)) {
                    throw new IllegalArgumentException("Map input is not a map");
                }
                Map<String, Object> values = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : source.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        throw new IllegalArgumentException("Map input keys must be strings");
                    }
                    values.put(key, canonicalValue(map.value(), entry.getValue()));
                }
                yield Collections.unmodifiableMap(values);
            }
            case TypeExpr.TupleType tuple -> {
                if (!(raw instanceof List<?> values) || values.size() != tuple.elements().size()) {
                    throw new IllegalArgumentException("Tuple input shape does not match catalog pin");
                }
                List<Object> result = new ArrayList<>(values.size());
                for (int index = 0; index < values.size(); index++) {
                    result.add(canonicalValue(tuple.elements().get(index), values.get(index)));
                }
                yield Collections.unmodifiableList(result);
            }
            case TypeExpr.OptionalType optional -> {
                if (raw instanceof Optional<?> value) {
                    yield value.isPresent() ? canonicalValue(optional.element(), value.get()) : null;
                }
                yield canonicalValue(optional.element(), raw);
            }
            case TypeExpr.ResultType result -> {
                if (!(raw instanceof Map<?, ?> source)
                    || !(source.get("success") instanceof Boolean success)
                    || !source.containsKey("value")) {
                    throw new IllegalArgumentException("Result input shape does not match catalog pin");
                }
                Map<String, Object> values = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : source.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        throw new IllegalArgumentException("Result input keys must be strings");
                    }
                    values.put(key, entry.getValue());
                }
                TypeExpr branch = success ? result.success() : result.failure();
                values.put("value", canonicalValue(branch, source.get("value")));
                yield Collections.unmodifiableMap(values);
            }
            case TypeExpr.UnionType ignored -> throw new IllegalArgumentException("Union input needs an explicit canonical variant");
            case TypeExpr.OpaqueType ignored -> throw new IllegalArgumentException("Opaque input cannot be compiled");
        };
    }

    private Object namedValue(TypeExpr.Named type, Object raw) {
        if (!type.arguments().isEmpty() || "any".equals(type.reference().localId())) {
            throw new IllegalArgumentException("Unresolved named input type");
        }
        FlowTypeRef reference = FlowTypeRef.simple(type.reference().localId());
        if (!valueCodecs.hasCodec(reference)) {
            throw new IllegalArgumentException("No canonical codec for " + type.reference().canonicalKey());
        }
        return valueCodecs.encode(reference, raw);
    }

    private ServerResourceLocator resourceLocator(TypeExpr.ResourceType type, Object raw) {
        return TypedResourceReferenceBoundary.requireLocator(type, raw, serverId);
    }

    private static String canonicalHandlerConfig(CatalogNodeDescriptor definition) {
        Object value = definitionSource(definition).get("handlerConfig");
        if (value == null) {
            return "{}";
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Active handler configuration must be an object");
        }
        return CanonicalJson.canonicalize(map);
    }

    private static boolean matchesSource(CatalogOwned<CatalogNodeDescriptor> owned, String nodeType) {
        CatalogNodeDescriptor definition = owned.descriptor();
        if (definition.metadata().containsKey("authoredSource")) {
            return definition.metadata().get("authoredSource") instanceof Map<?, ?> source
                && nodeType.equals(source.get("id")) && owned.key().owner().value().equals(source.get("owner"));
        }
        return nodeType.equals(definition.metadata().get("sourceNodeId"));
    }

    private static Map<?, ?> definitionSource(CatalogNodeDescriptor definition) {
        if (!definition.metadata().containsKey("authoredSource")) {
            return definition.metadata();
        }
        if (!(definition.metadata().get("authoredSource") instanceof Map<?, ?> source)) {
            throw new IllegalArgumentException("Active authored node metadata must be an object: " + definition.id().value());
        }
        return source;
    }

    private static Diagnostic coreDiagnostic(String code, GraphDocument graph, NodeInstanceId nodeId, PinId pinId,
                                             String reason, Map<String, ?> evidence) {
        String identity = code + "\u0000" + (graph == null ? "" : graph.resource().canonicalText()) + "\u0000"
            + (nodeId == null ? "" : nodeId.canonicalText()) + "\u0000" + (pinId == null ? "" : pinId.canonicalText())
            + "\u0000" + reason;
        Map<String, Object> details = new LinkedHashMap<>(evidence);
        details.put("reason", reason);
        var builder = Diagnostic.builder(code, DiagnosticSeverity.ERROR, phase(code), stage(code))
            .messageKey(ContractRef.of(OwnerId.of("resync"), CapabilityId.of(messageKey(code))))
            .evidence(details)
            .correlationId(UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)));
        if (graph != null) {
            builder.resource(graph.resource()).catalogGeneration(graph.catalogBinding().generation());
        }
        return builder.build();
    }

    private static Map<String, Object> functionSourceEvidence(GraphDocument graph, boolean provided) {
        return Map.of(
            "field", "functionSource",
            "boundary", "function-source-v1",
            "functionSourceProvided", provided,
            "resource", graph.resource().canonicalText(),
            "revision", graph.revision());
    }

    public static Diagnostic diagnostic(String code, FlowGraph graph, String nodeId, String reason, Map<String, ?> evidence) {
        String identity = code + "\u0000" + (graph == null || graph.getId() == null ? "" : graph.getId()) + "\u0000" + value(nodeId) + "\u0000" + reason;
        Map<String, Object> details = new LinkedHashMap<>(evidence);
        details.put("reason", reason);
        if (nodeId != null) {
            details.put("nodeInstanceId", nodeId);
        }
        var builder = Diagnostic.builder(code, DiagnosticSeverity.ERROR, phase(code), stage(code))
            .messageKey(ContractRef.of(OwnerId.of("resync"), CapabilityId.of(messageKey(code))))
            .evidence(details)
            .correlationId(UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)));
        return builder.build();
    }

    private static String messageKey(String code) {
        return switch (code) {
            case "GRAPH.NULL" -> "graph-null";
            case "GRAPH.CATALOG_REQUIRED" -> "graph-catalog-required";
            case "GRAPH.RUNTIME_BINDING_MISSING" -> "graph-runtime-binding-missing";
            case "GRAPH.DEFINITION_MISSING" -> "graph-definition-missing";
            case "GRAPH.PIN_UNRESOLVED" -> "graph-pin-unresolved";
            case "GRAPH.PIN_TYPE_MISMATCH" -> "graph-pin-type-mismatch";
            case "GRAPH.OPAQUE_UNAVAILABLE" -> "graph-opaque-unavailable";
            default -> throw new IllegalArgumentException("Unsupported compiled graph diagnostic code: " + code);
        };
    }

    private static DiagnosticPhase phase(String code) {
        return switch (code) {
            case "GRAPH.NULL", "GRAPH.PIN_UNRESOLVED", "GRAPH.PIN_TYPE_MISMATCH", "GRAPH.RUNTIME_BINDING_MISSING" -> DiagnosticPhase.SEMANTIC;
            default -> DiagnosticPhase.CAPABILITY;
        };
    }

    private static String stage(String code) {
        return switch (code) {
            case "GRAPH.NULL" -> "null";
            case "GRAPH.CATALOG_REQUIRED" -> "catalog-required";
            case "GRAPH.DEFINITION_MISSING" -> "definition-missing";
            case "GRAPH.PIN_TYPE_MISMATCH" -> "pin-type-mismatch";
            case "GRAPH.RUNTIME_BINDING_MISSING" -> "runtime-binding-missing";
            default -> "graph";
        };
    }

    private static String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean canonicalText(String value) {
        return value != null && !value.isBlank() && value.equals(value.strip())
            && value.indexOf('\u0000') < 0 && Normalizer.isNormalized(value, Normalizer.Form.NFC);
    }

    public record Result(CompiledGraphMetadata metadata, FlowExecutionBridge.MappingContext mappingContext, List<Diagnostic> diagnostics) {
        public Result {
            diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "Compiled Metadata Diagnostics Are Required"));
            if (metadata != null && mappingContext == null) {
                throw new IllegalArgumentException("Accepted compiled metadata requires a mapping context");
            }
            if (metadata != null && !diagnostics.isEmpty()) {
                throw new IllegalArgumentException("Accepted compiled metadata cannot contain diagnostics");
            }
            if (metadata == null && diagnostics.isEmpty()) {
                throw new IllegalArgumentException("Rejected compiled metadata requires diagnostics");
            }
        }

        public static Result rejected(List<Diagnostic> diagnostics) {
            return new Result(null, null, diagnostics);
        }

        public boolean accepted() {
            return metadata != null && mappingContext != null && diagnostics.isEmpty();
        }

        public UnsupportedGraphException failure() {
            return new UnsupportedGraphException(diagnostics);
        }
    }

    public static final class UnsupportedGraphException extends IllegalStateException {
        private final List<Diagnostic> diagnostics;

        public UnsupportedGraphException(List<Diagnostic> diagnostics) {
            super("Compiled graph metadata was rejected: " + diagnostics.stream().map(Diagnostic::code).distinct().sorted(Comparator.naturalOrder()).toList());
            this.diagnostics = List.copyOf(diagnostics);
        }

        public List<Diagnostic> diagnostics() {
            return diagnostics;
        }
    }
}
