package restudio.resync.flow.nodes;

import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;

import java.util.List;
import java.util.regex.Pattern;

public class StringNodes implements NodeCategory {
    
    @Override
    public void registerNodes(FlowRegistry registry) {
        registry.register("string_concat", (ctx, node) -> {
            String a = ctx.getInputValue(node, "a", String.class, "");
            String b = ctx.getInputValue(node, "b", String.class, "");
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", a + b);
        });
        
        registry.register("string_substring", (ctx, node) -> {
            String value = ctx.getInputValue(node, "value", String.class, "");
            Integer start = ctx.getInputValue(node, "start", Integer.class, 0);
            Integer length = ctx.getInputValue(node, "length", Integer.class, null);
            String nodeId = findNodeId(ctx, node);
            
            if (value != null && start >= 0 && start < value.length()) {
                int end = length != null ? Math.min(start + length, value.length()) : value.length();
                ctx.setNodeOutput(nodeId, "result", value.substring(start, end));
            } else {
                ctx.setNodeOutput(nodeId, "result", "");
            }
        });
        
        registry.register("string_split", (ctx, node) -> {
            String value = ctx.getInputValue(node, "value", String.class, "");
            String delimiter = ctx.getInputValue(node, "delimiter", String.class, ",");
            String nodeId = findNodeId(ctx, node);
            
            if (value != null && delimiter != null) {
                String[] parts = value.split(Pattern.quote(delimiter), -1);
                ctx.setNodeOutput(nodeId, "result", List.of(parts));
            } else {
                ctx.setNodeOutput(nodeId, "result", List.of());
            }
        });
        
        registry.register("string_join", (ctx, node) -> {
            List<String> list = ctx.getInputValue(node, "list", List.class, List.of());
            String separator = ctx.getInputValue(node, "separator", String.class, ",");
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", String.join(separator, list));
        });
        
        registry.register("string_replace", (ctx, node) -> {
            String value = ctx.getInputValue(node, "value", String.class, "");
            String target = ctx.getInputValue(node, "target", String.class, "");
            String replacement = ctx.getInputValue(node, "replacement", String.class, "");
            String nodeId = findNodeId(ctx, node);
            
            if (value != null) {
                ctx.setNodeOutput(nodeId, "result", value.replace(target, replacement));
            } else {
                ctx.setNodeOutput(nodeId, "result", "");
            }
        });
        
        registry.register("string_replace_regex", (ctx, node) -> {
            String value = ctx.getInputValue(node, "value", String.class, "");
            String pattern = ctx.getInputValue(node, "pattern", String.class, "");
            String replacement = ctx.getInputValue(node, "replacement", String.class, "");
            String nodeId = findNodeId(ctx, node);
            
            if (value != null && pattern != null) {
                try {
                    ctx.setNodeOutput(nodeId, "result", value.replaceAll(pattern, replacement));
                } catch (Exception e) {
                    ctx.setNodeOutput(nodeId, "result", value);
                }
            } else {
                ctx.setNodeOutput(nodeId, "result", "");
            }
        });
        
        registry.register("string_upper", (ctx, node) -> {
            String value = ctx.getInputValue(node, "value", String.class, "");
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", value != null ? value.toUpperCase() : "");
        });
        
        registry.register("string_lower", (ctx, node) -> {
            String value = ctx.getInputValue(node, "value", String.class, "");
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", value != null ? value.toLowerCase() : "");
        });
        
        registry.register("string_capitalize", (ctx, node) -> {
            String value = ctx.getInputValue(node, "value", String.class, "");
            String nodeId = findNodeId(ctx, node);
            
            if (value != null && !value.isEmpty()) {
                String[] words = value.split("\\s+");
                StringBuilder result = new StringBuilder();
                for (String word : words) {
                    if (!word.isEmpty()) {
                        result.append(Character.toUpperCase(word.charAt(0)));
                        if (word.length() > 1) {
                            result.append(word.substring(1).toLowerCase());
                        }
                        result.append(" ");
                    }
                }
                ctx.setNodeOutput(nodeId, "result", result.toString().trim());
            } else {
                ctx.setNodeOutput(nodeId, "result", "");
            }
        });
        
        registry.register("string_trim", (ctx, node) -> {
            String value = ctx.getInputValue(node, "value", String.class, "");
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", value != null ? value.trim() : "");
        });
        
        registry.register("string_length", (ctx, node) -> {
            String value = ctx.getInputValue(node, "value", String.class, "");
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", value != null ? value.length() : 0);
        });
        
        registry.register("string_reverse", (ctx, node) -> {
            String value = ctx.getInputValue(node, "value", String.class, "");
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", value != null ? new StringBuilder(value).reverse().toString() : "");
        });
        
        registry.register("string_repeat", (ctx, node) -> {
            String value = ctx.getInputValue(node, "value", String.class, "");
            Integer count = ctx.getInputValue(node, "count", Integer.class, 1);
            String nodeId = findNodeId(ctx, node);
            
            if (value != null && count > 0) {
                ctx.setNodeOutput(nodeId, "result", value.repeat(count));
            } else {
                ctx.setNodeOutput(nodeId, "result", "");
            }
        });
        
        registry.register("string_contains", (ctx, node) -> {
            String value = ctx.getInputValue(node, "value", String.class, "");
            String substring = ctx.getInputValue(node, "substring", String.class, "");
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", value != null && substring != null && value.contains(substring));
        });
        
        registry.register("string_starts_with", (ctx, node) -> {
            String value = ctx.getInputValue(node, "value", String.class, "");
            String prefix = ctx.getInputValue(node, "prefix", String.class, "");
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", value != null && prefix != null && value.startsWith(prefix));
        });
        
        registry.register("string_ends_with", (ctx, node) -> {
            String value = ctx.getInputValue(node, "value", String.class, "");
            String suffix = ctx.getInputValue(node, "suffix", String.class, "");
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", value != null && suffix != null && value.endsWith(suffix));
        });
        
        registry.register("string_matches", (ctx, node) -> {
            String value = ctx.getInputValue(node, "value", String.class, "");
            String pattern = ctx.getInputValue(node, "pattern", String.class, "");
            String nodeId = findNodeId(ctx, node);
            
            if (value != null && pattern != null) {
                try {
                    ctx.setNodeOutput(nodeId, "result", Pattern.matches(pattern, value));
                } catch (Exception e) {
                    ctx.setNodeOutput(nodeId, "result", false);
                }
            } else {
                ctx.setNodeOutput(nodeId, "result", false);
            }
        });
        
        registry.register("string_index_of", (ctx, node) -> {
            String value = ctx.getInputValue(node, "value", String.class, "");
            String substring = ctx.getInputValue(node, "substring", String.class, "");
            String nodeId = findNodeId(ctx, node);
            
            if (value != null && substring != null) {
                ctx.setNodeOutput(nodeId, "result", value.indexOf(substring));
            } else {
                ctx.setNodeOutput(nodeId, "result", -1);
            }
        });
        
        registry.register("string_last_index_of", (ctx, node) -> {
            String value = ctx.getInputValue(node, "value", String.class, "");
            String substring = ctx.getInputValue(node, "substring", String.class, "");
            String nodeId = findNodeId(ctx, node);
            
            if (value != null && substring != null) {
                ctx.setNodeOutput(nodeId, "result", value.lastIndexOf(substring));
            } else {
                ctx.setNodeOutput(nodeId, "result", -1);
            }
        });
        
        registry.register("string_equals_ignore_case", (ctx, node) -> {
            String a = ctx.getInputValue(node, "a", String.class, "");
            String b = ctx.getInputValue(node, "b", String.class, "");
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", a != null && b != null && a.equalsIgnoreCase(b));
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
