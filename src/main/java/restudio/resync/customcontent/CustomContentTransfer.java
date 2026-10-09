package restudio.resync.customcontent;

import com.google.gson.JsonParser;
import restudio.flow.data.CustomContentDefinition;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowSerializer;
import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.flow.graph.BranchBinding;
import restudio.resync.flow.graph.BranchCase;
import restudio.resync.flow.graph.FunctionBinding;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphDocumentCodec;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.GraphVariable;
import restudio.resync.flow.graph.PinValue;
import restudio.resync.flow.graph.RepeatableBinding;
import restudio.resync.flow.graph.RepeatableElement;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.type.TypedValue;
import restudio.resync.resources.ReSyncResourceCatalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CustomContentTransfer {
    private static final String GRAPH = "contentCoreGraph";
    private static final ServerId SHARED = ServerId.deterministic("resync-custom-content-transfer-v1");
    private static final ContractRef<ResourceTypeId> TYPE = ContractRef.of(OwnerId.of("restudio.resync"),
        ResourceTypeId.of(ReSyncResourceCatalog.CUSTOM_CONTENT));

    private CustomContentTransfer() {
    }

    public static String serialize(CustomContentDefinition definition) {
        if (definition.getGraph() == null || !definition.getGraph().getOpaqueProperties().containsKey(GRAPH)) {
            return FlowSerializer.serializeCustomContent(definition);
        }
        CustomContentDefinition copy = FlowSerializer.deserializeCustomContent(FlowSerializer.serializeCustomContent(definition));
        bind(copy, SHARED, 0L);
        return FlowSerializer.serializeCustomContent(copy);
    }

    static void bind(CustomContentDefinition definition, ServerId server, long revision) {
        FlowGraph graph = definition.getGraph();
        if (graph == null || !graph.getOpaqueProperties().containsKey(GRAPH)) {
            return;
        }
        GraphDocument source = GraphDocumentCodec.INSTANCE.decode(CanonicalCodec.decodePermissive(
            graph.getOpaqueProperties().get(GRAPH).toString()));
        if (!TYPE.equals(source.resource().type()) || !definition.getId().equals(source.resource().id())) {
            throw new IllegalArgumentException("Shared content graph does not match its aggregate identity");
        }
        ServerId origin = source.resource().serverId();
        GraphDocument bound = new GraphDocument(source.schemaVersion(), new ServerResourceLocator(server, source.resource().key(), source.resource().unknown()),
            revision, source.catalogBinding(), source.requiredCapabilities(), source.nodes().stream().map(node -> node(node, origin, server)).toList(),
            source.connections(), source.passthroughs(), source.variables().stream().map(variable -> new GraphVariable(variable.variableId(),
                variable.name(), variable.type(), variable.value() == null ? null : value(variable.value(), origin, server), variable.unknown())).toList(),
            source.functions().stream().map(function -> new FunctionBinding(locator(function.function(), origin, server), function.revision(),
                function.inputs(), function.outputs(), function.unknown())).toList(), source.unknown());
        FlowGraph copy = graph.copy();
        copy.getOpaqueProperties().put(GRAPH, JsonParser.parseString(GraphDocumentCodec.INSTANCE.encodeText(bound)));
        copy.setResourceType(ReSyncResourceCatalog.CUSTOM_CONTENT);
        copy.setResourceRevision(revision);
        copy.setResourceHash(null);
        copy.setResourceMutationId(null);
        definition.setGraph(copy);
    }

    private static GraphNode node(GraphNode node, ServerId origin, ServerId target) {
        Map<Object, Object> inspector = new LinkedHashMap<>();
        node.inspector().forEach((pin, value) -> inspector.put(pin, new PinValue(pin, value(value.value(), origin, target), value.unknown())));
        node.inspectorFields().forEach((field, value) -> inspector.put(field, value(value, origin, target)));
        return new GraphNode(node.instanceId(), node.definition(), node.definitionVersion(), node.modeId(), pins(node.values(), origin, target),
            inspector, node.branches().stream().map(branch -> new BranchBinding(branch.branchId(), branch.selectedCaseId(),
                branch.cases().stream().map(branchCase -> new BranchCase(branchCase.caseId(), pins(branchCase.values(), origin, target),
                    branchCase.inspectorState(), branchCase.unknown())).toList(), branch.unknown())).toList(),
            node.repeatables().stream().map(repeatable -> new RepeatableBinding(repeatable.groupId(), repeatable.ordered(),
                repeatable.elements().stream().map(element -> new RepeatableElement(element.elementId(), pins(element.values(), origin, target),
                    element.unknown())).toList(), repeatable.unknown())).toList(), node.inspectorState(), node.x(), node.y(), node.unknown());
    }

    private static Map<PinId, PinValue> pins(Map<PinId, PinValue> pins, ServerId origin, ServerId target) {
        Map<PinId, PinValue> result = new LinkedHashMap<>();
        pins.forEach((pin, value) -> result.put(pin, new PinValue(pin, value(value.value(), origin, target), value.unknown())));
        return result;
    }

    private static TypedValue value(TypedValue value, ServerId origin, ServerId target) {
        return new TypedValue(value.type(), value.state(), value.variantId(), material(value.value(), origin, target),
            value.locator() == null ? null : locator(value.locator(), origin, target), value.unknown());
    }

    private static Object material(Object value, ServerId origin, ServerId target) {
        if (value instanceof ServerResourceLocator locator) {
            return locator(locator, origin, target);
        }
        if (value instanceof List<?> values) {
            return values.stream().map(entry -> material(entry, origin, target)).toList();
        }
        if (value instanceof Map<?, ?> values) {
            Map<Object, Object> result = new LinkedHashMap<>();
            values.forEach((key, entry) -> result.put(key, material(entry, origin, target)));
            return result;
        }
        return value;
    }

    private static ServerResourceLocator locator(ServerResourceLocator locator, ServerId origin, ServerId target) {
        return locator.serverId().equals(origin) ? new ServerResourceLocator(target, locator.key(), locator.unknown()) : locator;
    }
}
