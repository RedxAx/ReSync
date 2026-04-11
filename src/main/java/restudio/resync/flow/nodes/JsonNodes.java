package restudio.resync.flow.nodes;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowType;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.registry.DefineNode;
import restudio.resync.flow.registry.FlowPin;
import restudio.resync.flow.registry.NodeDefinition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class JsonNodes {
    
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, BiConsumer<FlowContext, FlowNode>> LEGACY_EXECUTORS = new ConcurrentHashMap<>();
    private static volatile boolean initialized;
    
    private static void registerLegacyNodes(FlowRegistry registry) {
        registry.register("json_parse", (ctx, node) -> {
            String jsonString = ctx.getInputValue(node, "json_string", String.class, "{}");
            try {
                JsonElement element = JsonParser.parseString(jsonString);
                Object result = GSON.fromJson(element, Object.class);
                String nodeId = findNodeId(ctx, node);
                ctx.setNodeOutput(nodeId, "object", result);
            } catch (Exception e) {
                String nodeId = findNodeId(ctx, node);
                ctx.setNodeOutput(nodeId, "object", new HashMap<>());
            }
            ctx.triggerOutput("flow");
        });
        
        registry.register("json_to_string", (ctx, node) -> {
            Object object = ctx.getInputValue(node, "object", null);
            String jsonString = GSON.toJson(object);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "string", jsonString);
            ctx.triggerOutput("flow");
        });
        
        registry.register("json_get", (ctx, node) -> {
            Object object = ctx.getInputValue(node, "object", null);
            String path = ctx.getInputValue(node, "path", String.class, "");
            Object value = null;
            
            if (object instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) object;
                String[] keys = path.split("\\.");
                Object current = map;
                
                for (String key : keys) {
                    if (current instanceof Map) {
                        current = ((Map<?, ?>) current).get(key);
                    } else {
                        current = null;
                        break;
                    }
                }
                value = current;
            }
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "value", value);
            ctx.triggerOutput("flow");
        });
        
        registry.register("json_set", (ctx, node) -> {
            Object object = ctx.getInputValue(node, "object", null);
            String path = ctx.getInputValue(node, "path", String.class, "");
            Object value = ctx.getInputValue(node, "value", null);
            
            if (object instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) object;
                String[] keys = path.split("\\.");
                
                if (keys.length == 1) {
                    map.put(keys[0], value);
                } else {
                    Map<String, Object> current = map;
                    for (int i = 0; i < keys.length - 1; i++) {
                        if (!current.containsKey(keys[i]) || !(current.get(keys[i]) instanceof Map)) {
                            current.put(keys[i], new HashMap<String, Object>());
                        }
                        current = (Map<String, Object>) current.get(keys[i]);
                    }
                    current.put(keys[keys.length - 1], value);
                }
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("json_delete", (ctx, node) -> {
            Object object = ctx.getInputValue(node, "object", null);
            String path = ctx.getInputValue(node, "path", String.class, "");
            
            if (object instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) object;
                String[] keys = path.split("\\.");
                
                if (keys.length == 1) {
                    map.remove(keys[0]);
                } else {
                    Map<String, Object> current = map;
                    for (int i = 0; i < keys.length - 1; i++) {
                        if (current.containsKey(keys[i]) && current.get(keys[i]) instanceof Map) {
                            current = (Map<String, Object>) current.get(keys[i]);
                        } else {
                            break;
                        }
                    }
                    current.remove(keys[keys.length - 1]);
                }
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("json_has", (ctx, node) -> {
            Object object = ctx.getInputValue(node, "object", null);
            String path = ctx.getInputValue(node, "path", String.class, "");
            boolean hasKey = false;
            
            if (object instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) object;
                String[] keys = path.split("\\.");
                Object current = map;
                
                for (int i = 0; i < keys.length; i++) {
                    if (current instanceof Map) {
                        if (!((Map<?, ?>) current).containsKey(keys[i])) {
                            current = null;
                            break;
                        }
                        if (i == keys.length - 1) {
                            hasKey = true;
                        } else {
                            current = ((Map<?, ?>) current).get(keys[i]);
                        }
                    } else {
                        break;
                    }
                }
            }
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "has", hasKey);
            ctx.triggerOutput("flow");
        });
        
        registry.register("json_keys", (ctx, node) -> {
            Object object = ctx.getInputValue(node, "object", null);
            List<String> keys = new ArrayList<>();
            
            if (object instanceof Map) {
                keys.addAll(((Map<?, ?>) object).keySet().stream()
                    .map(k -> k != null ? k.toString() : "null")
                    .collect(java.util.stream.Collectors.toList()));
            }
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "keys", keys);
            ctx.triggerOutput("flow");
        });
        
        registry.register("json_merge", (ctx, node) -> {
            Object object1 = ctx.getInputValue(node, "object1", null);
            Object object2 = ctx.getInputValue(node, "object2", null);
            
            Map<String, Object> merged = new HashMap<>();
            if (object1 instanceof Map) {
                merged.putAll((Map<String, Object>) object1);
            }
            if (object2 instanceof Map) {
                merged.putAll((Map<String, Object>) object2);
            }
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "merged", merged);
            ctx.triggerOutput("flow");
        });
        
        registry.register("json_create", (ctx, node) -> {
            Map<String, Object> object = new HashMap<>();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "object", object);
            ctx.triggerOutput("flow");
        });
        
        registry.register("json_set_array", (ctx, node) -> {
            List<Object> values = ctx.getInputValue(node, "values", List.class, new ArrayList<>());
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "array", values);
            ctx.triggerOutput("flow");
        });
    }

    public void registerNodes(FlowRegistry registry) {
        registerLegacyNodes(registry);
    }

    private static void ensureLegacyInitialized() {
        if (initialized) {
            return;
        }
        synchronized (JsonNodes.class) {
            if (initialized) {
                return;
            }
            FlowRegistry legacyRegistry = new FlowRegistry();
            registerLegacyNodes(legacyRegistry);
            for (String type : legacyRegistry.getRegisteredTypes()) {
                LEGACY_EXECUTORS.put(type, legacyRegistry.getExecutor(type));
            }
            initialized = true;
        }
    }

    private void executeLegacy(String id, FlowContext ctx, FlowNode node) {
        ensureLegacyInitialized();
        BiConsumer<FlowContext, FlowNode> executor = LEGACY_EXECUTORS.get(id);
        if (executor == null) {
            ctx.triggerOutput("flow");
            return;
        }
        executor.accept(ctx, node);
    }

    @DefineNode(id = "json_parse", displayName = "Parse JSON", category = NodeDefinition.NodeCategory.DATABASE,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "json_string", dataType = FlowType.STRING)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "object", dataType = FlowType.JSON_OBJECT)
            })
    public void jsonParse(FlowContext ctx, FlowNode node) {
        executeLegacy("json_parse", ctx, node);
    }

    @DefineNode(id = "json_to_string", displayName = "JSON to String", category = NodeDefinition.NodeCategory.DATABASE,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "object", dataType = FlowType.JSON_OBJECT)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "string", dataType = FlowType.STRING)
            })
    public void jsonToString(FlowContext ctx, FlowNode node) {
        executeLegacy("json_to_string", ctx, node);
    }

    @DefineNode(id = "json_get", displayName = "Get JSON Value", category = NodeDefinition.NodeCategory.DATABASE,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "object", dataType = FlowType.JSON_OBJECT),
                    @FlowPin(name = "path", dataType = FlowType.STRING)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "value", dataType = FlowType.ANY)
            })
    public void jsonGet(FlowContext ctx, FlowNode node) {
        executeLegacy("json_get", ctx, node);
    }

    @DefineNode(id = "json_set", displayName = "Set JSON Value", category = NodeDefinition.NodeCategory.DATABASE,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "object", dataType = FlowType.JSON_OBJECT),
                    @FlowPin(name = "path", dataType = FlowType.STRING),
                    @FlowPin(name = "value", dataType = FlowType.ANY)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void jsonSet(FlowContext ctx, FlowNode node) {
        executeLegacy("json_set", ctx, node);
    }

    @DefineNode(id = "json_delete", displayName = "Delete JSON Key", category = NodeDefinition.NodeCategory.DATABASE,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "object", dataType = FlowType.JSON_OBJECT),
                    @FlowPin(name = "path", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void jsonDelete(FlowContext ctx, FlowNode node) {
        executeLegacy("json_delete", ctx, node);
    }

    @DefineNode(id = "json_has", displayName = "JSON Has Key", category = NodeDefinition.NodeCategory.DATABASE,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "object", dataType = FlowType.JSON_OBJECT),
                    @FlowPin(name = "path", dataType = FlowType.STRING)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "has", dataType = FlowType.BOOLEAN)
            })
    public void jsonHas(FlowContext ctx, FlowNode node) {
        executeLegacy("json_has", ctx, node);
    }

    @DefineNode(id = "json_keys", displayName = "Get JSON Keys", category = NodeDefinition.NodeCategory.DATABASE,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "object", dataType = FlowType.JSON_OBJECT)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "keys", dataType = FlowType.LIST)
            })
    public void jsonKeys(FlowContext ctx, FlowNode node) {
        executeLegacy("json_keys", ctx, node);
    }

    @DefineNode(id = "json_merge", displayName = "Merge JSON", category = NodeDefinition.NodeCategory.DATABASE,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "object1", dataType = FlowType.JSON_OBJECT),
                    @FlowPin(name = "object2", dataType = FlowType.JSON_OBJECT)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "merged", dataType = FlowType.JSON_OBJECT)
            })
    public void jsonMerge(FlowContext ctx, FlowNode node) {
        executeLegacy("json_merge", ctx, node);
    }

    @DefineNode(id = "json_create", displayName = "Create JSON Object", category = NodeDefinition.NodeCategory.DATABASE,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)},
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "object", dataType = FlowType.JSON_OBJECT)
            })
    public void jsonCreate(FlowContext ctx, FlowNode node) {
        executeLegacy("json_create", ctx, node);
    }

    @DefineNode(id = "json_set_array", displayName = "Create JSON Array", category = NodeDefinition.NodeCategory.DATABASE,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "values", dataType = FlowType.LIST)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "array", dataType = FlowType.LIST)
            })
    public void jsonSetArray(FlowContext ctx, FlowNode node) {
        executeLegacy("json_set_array", ctx, node);
    }
    
    private static String findNodeId(FlowContext ctx, FlowNode node) {
        for (var entry : ctx.getRuntime().getGraph().getNodes().entrySet()) {
            if (entry.getValue() == node) {
                return entry.getKey();
            }
        }
        return null;
    }
}
