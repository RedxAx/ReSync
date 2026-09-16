package restudio.flow.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import restudio.resync.flow.identity.FunctionParameterId;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FlowSerializer {
    private static final Set<String> GRAPH_PROPERTIES = Set.of("id", "enabled", "version", "nodes", "connections", "localVariables", "function", "functionOwner", "functionNamespace", "functionVersion", "functionDescription", "functionInputs", "functionOutputs", "editorPassthroughs", "contentProperties", "resourceType", "resourceRevision", "resourceHash", "resourceMutationId", "assetFormatVersion", "assetRevision", "assetHash", "assetMutationId");
    private static final Set<String> NODE_PROPERTIES = Set.of("type", "version", "x", "y", "inputValues", "handlerConfig");
    private static final Set<String> CONNECTION_PROPERTIES = Set.of("sourceNodeId", "sourcePin", "sourcePinId", "sourcePinDisplayName", "targetNodeId", "targetPin", "targetPinId", "targetPinDisplayName", "editorSourceNodeId", "editorSourcePin", "editorSourcePinId", "editorSourcePinDisplayName");
    private static final FlowDataObjectAdapter DATA_OBJECT_ADAPTER = new FlowDataObjectAdapter();
    private static final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(FlowDataType.class, new FlowDataTypeAdapter())
            .registerTypeAdapter(FlowDataObject.class, DATA_OBJECT_ADAPTER)
            .registerTypeAdapter(FunctionParameterId.class, new FunctionParameterIdAdapter())
            .create();

    public static FlowDataObjectAdapter getDataObjectAdapter() {
        return DATA_OBJECT_ADAPTER;
    }

    public static String serialize(FlowGraph graph) {
        JsonObject object = gson.toJsonTree(graph).getAsJsonObject();
        omitImplicitHandlerConfig(graph, object);
        mergeOpaque(object, graph.getOpaqueProperties());
        writeNodeProperties(graph, object);
        writeConnectionProperties(graph, object);
        return gson.toJson(object);
    }

    public static FlowGraph deserialize(String json) {
        return deserialize(json, null);
    }

    public static FlowGraph deserialize(String json, String expectedServerId) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        FlowGraph graph = gson.fromJson(object, FlowGraph.class);
        graph = decodeLegacyFunctionParameterIds(graph, object);
        restoreTypedReferences(graph, object, expectedServerId);
        restoreHandlerConfigPresence(graph, object);
        Map<String, JsonElement> opaque = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (!GRAPH_PROPERTIES.contains(entry.getKey())) {
                opaque.put(entry.getKey(), entry.getValue().deepCopy());
            }
        }
        graph.setOpaqueProperties(opaque);
        readNodeProperties(graph, object);
        readConnectionProperties(graph, object);
        return graph;
    }

    private static FlowGraph decodeLegacyFunctionParameterIds(FlowGraph graph, JsonObject source) {
        if (!hasLegacyFunctionParameterIds(source)) {
            return graph;
        }
        JsonElement graphId = source.get("id");
        if (graphId == null || graphId.isJsonNull() || !graphId.isJsonPrimitive() || graphId.getAsString().isBlank()) {
            throw new IllegalArgumentException("A stable Flow graph ID is required for legacy parameter adaptation");
        }
        return FlowGraph.LegacyFunctionParameterAdapter.adapt(graph);
    }

    private static boolean hasLegacyFunctionParameterIds(JsonObject source) {
        return hasLegacyFunctionParameterIds(source.get("functionInputs"))
            || hasLegacyFunctionParameterIds(source.get("functionOutputs"));
    }

    private static boolean hasLegacyFunctionParameterIds(JsonElement value) {
        if (value == null || value.isJsonNull() || !value.isJsonArray()) {
            return false;
        }
        for (JsonElement parameter : value.getAsJsonArray()) {
            if (parameter == null || parameter.isJsonNull() || !parameter.isJsonObject()) {
                continue;
            }
            JsonObject object = parameter.getAsJsonObject();
            if (!object.has("parameterId") || object.get("parameterId").isJsonNull()) {
                return true;
            }
        }
        return false;
    }

    private static final class FunctionParameterIdAdapter implements JsonSerializer<FunctionParameterId>, JsonDeserializer<FunctionParameterId> {
        @Override
        public JsonElement serialize(FunctionParameterId value, Type type, JsonSerializationContext context) {
            return value == null ? null : new JsonPrimitive(value.canonicalText());
        }

        @Override
        public FunctionParameterId deserialize(JsonElement value, Type type, JsonDeserializationContext context) throws JsonParseException {
            if (value == null || value.isJsonNull()) {
                return null;
            }
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new JsonParseException("Function parameter ID must be canonical text");
            }
            return FunctionParameterId.parseCanonicalText(value.getAsString());
        }
    }

    private static void restoreTypedReferences(FlowGraph graph, JsonObject source, String expectedServerId) {
        if (graph == null) {
            return;
        }
        if (graph.getNodes() != null) {
            for (FlowNode node : graph.getNodes().values()) {
                if (node == null) {
                    continue;
                }
                node.setInputValues(FlowResourceReferenceCodec.decodeMap(node.getInputValues(), expectedServerId));
                node.setHandlerConfig(FlowResourceReferenceCodec.decodeMap(node.getHandlerConfigValues(), expectedServerId));
            }
        }
        if (graph.getLocalVariables() != null) {
            for (FlowVariable variable : graph.getLocalVariables()) {
                if (variable != null) {
                    variable.setInitialValue(FlowResourceReferenceCodec.decode(variable.getInitialValue(), expectedServerId));
                }
            }
        }
        if (source.has("contentProperties") && !source.get("contentProperties").isJsonNull()) {
            graph.setContentProperties(FlowResourceReferenceCodec.decodeMap(graph.getContentProperties(), expectedServerId));
        }
    }

    public static String serializeGui(GuiDefinition gui) {
        return gson.toJson(gui);
    }

    public static GuiDefinition deserializeGui(String json) {
        return gson.fromJson(json, GuiDefinition.class);
    }

    public static String serializeScoreboard(ScoreboardDefinition scoreboard) {
        return gson.toJson(scoreboard);
    }

    public static ScoreboardDefinition deserializeScoreboard(String json) {
        return gson.fromJson(json, ScoreboardDefinition.class);
    }

    public static String serializeTab(TabDefinition tab) {
        return gson.toJson(tab);
    }

    public static TabDefinition deserializeTab(String json) {
        return gson.fromJson(json, TabDefinition.class);
    }

    public static String serializeCustomContent(CustomContentDefinition content) {
        JsonObject object = gson.toJsonTree(content).getAsJsonObject();
        if (content.getGraph() != null) {
            object.add("graph", JsonParser.parseString(serialize(content.getGraph())));
        }
        return gson.toJson(object);
    }

    public static CustomContentDefinition deserializeCustomContent(String json) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        CustomContentDefinition content = gson.fromJson(object, CustomContentDefinition.class);
        if (content != null && object.has("graph") && object.get("graph").isJsonObject()) {
            content.setGraph(deserialize(object.get("graph").toString()));
        }
        return content;
    }

    private static void omitImplicitHandlerConfig(FlowGraph graph, JsonObject object) {
        if (!object.has("nodes") || !object.get("nodes").isJsonObject()) {
            return;
        }
        JsonObject nodes = object.getAsJsonObject("nodes");
        graph.getNodes().forEach((id, node) -> {
            JsonElement serialized = nodes.get(id);
            if (node != null && !node.handlerConfigDeclared() && node.getHandlerConfigValues().isEmpty()
                && serialized != null && serialized.isJsonObject()) {
                serialized.getAsJsonObject().remove("handlerConfig");
            }
        });
    }

    private static void restoreHandlerConfigPresence(FlowGraph graph, JsonObject object) {
        if (!object.has("nodes") || !object.get("nodes").isJsonObject()) {
            return;
        }
        JsonObject nodes = object.getAsJsonObject("nodes");
        graph.getNodes().forEach((id, node) -> {
            JsonElement serialized = nodes.get(id);
            if (node != null && serialized != null && serialized.isJsonObject()) {
                node.markHandlerConfigDeclared(serialized.getAsJsonObject().has("handlerConfig"));
            }
        });
    }

    private static void writeNodeProperties(FlowGraph graph, JsonObject object) {
        JsonObject nodes = object.getAsJsonObject("nodes");
        if (nodes == null || graph.getNodes() == null) {
            return;
        }
        for (Map.Entry<String, FlowNode> entry : graph.getNodes().entrySet()) {
            FlowNode node = entry.getValue();
            JsonElement value = nodes.get(entry.getKey());
            if (node != null && value != null && value.isJsonObject()) {
                mergeOpaque(value.getAsJsonObject(), node.peekOpaqueProperties());
            }
        }
    }

    private static void writeConnectionProperties(FlowGraph graph, JsonObject object) {
        JsonArray values = object.getAsJsonArray("connections");
        if (values == null || graph.getConnections() == null) {
            return;
        }
        List<FlowConnection> connections = graph.getConnections();
        for (int index = 0; index < connections.size() && index < values.size(); index++) {
            FlowConnection connection = connections.get(index);
            JsonElement value = values.get(index);
            if (connection == null || !value.isJsonObject()) {
                continue;
            }
            connection.adaptLegacyIdentity();
            JsonObject output = value.getAsJsonObject();
            add(output, "sourcePinId", connection.getSourcePinId());
            add(output, "targetPinId", connection.getTargetPinId());
            add(output, "editorSourcePinId", connection.getEditorSourcePinId());
            mergeOpaque(output, connection.peekOpaqueProperties());
        }
    }

    private static void readNodeProperties(FlowGraph graph, JsonObject object) {
        JsonObject nodes = object.getAsJsonObject("nodes");
        if (nodes == null || graph.getNodes() == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : nodes.entrySet()) {
            FlowNode node = graph.getNodes().get(entry.getKey());
            if (node != null && entry.getValue().isJsonObject()) {
                node.setOpaqueProperties(unknownProperties(entry.getValue().getAsJsonObject(), NODE_PROPERTIES));
            }
        }
    }

    private static void readConnectionProperties(FlowGraph graph, JsonObject object) {
        JsonArray values = object.getAsJsonArray("connections");
        if (values == null || graph.getConnections() == null) {
            return;
        }
        List<FlowConnection> connections = graph.getConnections();
        for (int index = 0; index < connections.size() && index < values.size(); index++) {
            FlowConnection connection = connections.get(index);
            JsonElement value = values.get(index);
            if (connection != null && value.isJsonObject()) {
                connection.adaptLegacyIdentity();
                connection.setOpaqueProperties(unknownProperties(value.getAsJsonObject(), CONNECTION_PROPERTIES));
            }
        }
    }

    private static Map<String, JsonElement> unknownProperties(JsonObject object, Set<String> known) {
        Map<String, JsonElement> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (!known.contains(entry.getKey()) && entry.getValue() != null) {
                result.put(entry.getKey(), entry.getValue().deepCopy());
            }
        }
        return result;
    }

    private static void mergeOpaque(JsonObject output, Map<String, JsonElement> opaque) {
        if (opaque == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : opaque.entrySet()) {
            if (!output.has(entry.getKey()) && entry.getValue() != null) {
                output.add(entry.getKey(), entry.getValue().deepCopy());
            }
        }
    }

    private static void add(JsonObject object, String name, String value) {
        if (value != null && !value.isBlank()) {
            object.addProperty(name, value);
        }
    }

}
