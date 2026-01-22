package restudio.resync.flow.nodes;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class JsonNodes implements NodeCategory {
    
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
    @Override
    public void registerNodes(FlowRegistry registry) {
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
    
    private static String findNodeId(FlowContext ctx, FlowNode node) {
        for (var entry : ctx.getRuntime().getGraph().getNodes().entrySet()) {
            if (entry.getValue() == node) {
                return entry.getKey();
            }
        }
        return null;
    }
}
