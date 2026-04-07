package restudio.resync.flow.nodes;

import restudio.flow.data.FlowType;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.registry.DefineNode;
import restudio.resync.flow.registry.FlowPin;
import restudio.resync.flow.registry.NodeDefinition;

import java.util.List;
import java.util.regex.Pattern;

public class StringNodes {

    @DefineNode(id = "string_concat", displayName = "Concat", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "a", dataType = FlowType.STRING), @FlowPin(name = "b", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.STRING)})
    public void concat(FlowContext ctx, restudio.flow.data.FlowNode node) {
        String a = ctx.getInputValue(node, "a", String.class, "");
        String b = ctx.getInputValue(node, "b", String.class, "");
        ctx.setOutput(node, "result", a + b);
    }

    @DefineNode(id = "string_substring", displayName = "Substring", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "value", dataType = FlowType.STRING), @FlowPin(name = "start", dataType = FlowType.NUMBER), @FlowPin(name = "length", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.STRING)})
    public void substring(FlowContext ctx, restudio.flow.data.FlowNode node) {
        String value = ctx.getInputValue(node, "value", String.class, "");
        Integer start = ctx.getInputValue(node, "start", Integer.class, 0);
        Integer length = ctx.getInputValue(node, "length", Integer.class, null);
        if (value != null && start >= 0 && start < value.length()) {
            int end = length != null ? Math.min(start + length, value.length()) : value.length();
            ctx.setOutput(node, "result", value.substring(start, end));
        } else {
            ctx.setOutput(node, "result", "");
        }
    }

    @DefineNode(id = "string_split", displayName = "Split", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "value", dataType = FlowType.STRING), @FlowPin(name = "delimiter", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.LIST)})
    public void split(FlowContext ctx, restudio.flow.data.FlowNode node) {
        String value = ctx.getInputValue(node, "value", String.class, "");
        String delimiter = ctx.getInputValue(node, "delimiter", String.class, ",");
        if (value != null && delimiter != null) {
            ctx.setOutput(node, "result", List.of(value.split(Pattern.quote(delimiter), -1)));
        } else {
            ctx.setOutput(node, "result", List.of());
        }
    }

    @DefineNode(id = "string_join", displayName = "Join", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "list", dataType = FlowType.LIST), @FlowPin(name = "separator", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.STRING)})
    public void join(FlowContext ctx, restudio.flow.data.FlowNode node) {
        List<String> list = ctx.getInputValue(node, "list", List.class, List.of());
        String separator = ctx.getInputValue(node, "separator", String.class, ",");
        ctx.setOutput(node, "result", String.join(separator, list));
    }

    @DefineNode(id = "string_replace", displayName = "Replace", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "value", dataType = FlowType.STRING), @FlowPin(name = "target", dataType = FlowType.STRING), @FlowPin(name = "replacement", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.STRING)})
    public void replace(FlowContext ctx, restudio.flow.data.FlowNode node) {
        String value = ctx.getInputValue(node, "value", String.class, "");
        String target = ctx.getInputValue(node, "target", String.class, "");
        String replacement = ctx.getInputValue(node, "replacement", String.class, "");
        ctx.setOutput(node, "result", value != null ? value.replace(target, replacement) : "");
    }

    @DefineNode(id = "string_replace_regex", displayName = "Replace Regex", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "value", dataType = FlowType.STRING), @FlowPin(name = "pattern", dataType = FlowType.STRING), @FlowPin(name = "replacement", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.STRING)})
    public void replaceRegex(FlowContext ctx, restudio.flow.data.FlowNode node) {
        String value = ctx.getInputValue(node, "value", String.class, "");
        String pattern = ctx.getInputValue(node, "pattern", String.class, "");
        String replacement = ctx.getInputValue(node, "replacement", String.class, "");
        if (value != null && pattern != null) {
            try {
                ctx.setOutput(node, "result", value.replaceAll(pattern, replacement));
            } catch (Exception e) {
                ctx.setOutput(node, "result", value);
            }
        } else {
            ctx.setOutput(node, "result", "");
        }
    }

    @DefineNode(id = "string_upper", displayName = "Uppercase", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "value", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.STRING)})
    public void upper(FlowContext ctx, restudio.flow.data.FlowNode node) {
        String value = ctx.getInputValue(node, "value", String.class, "");
        ctx.setOutput(node, "result", value != null ? value.toUpperCase() : "");
    }

    @DefineNode(id = "string_lower", displayName = "Lowercase", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "value", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.STRING)})
    public void lower(FlowContext ctx, restudio.flow.data.FlowNode node) {
        String value = ctx.getInputValue(node, "value", String.class, "");
        ctx.setOutput(node, "result", value != null ? value.toLowerCase() : "");
    }

    @DefineNode(id = "string_capitalize", displayName = "Capitalize", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "value", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.STRING)})
    public void capitalize(FlowContext ctx, restudio.flow.data.FlowNode node) {
        String value = ctx.getInputValue(node, "value", String.class, "");
        if (value != null && !value.isEmpty()) {
            String[] words = value.split("\\s+");
            StringBuilder result = new StringBuilder();
            for (String word : words) {
                if (!word.isEmpty()) {
                    result.append(Character.toUpperCase(word.charAt(0)));
                    if (word.length() > 1) result.append(word.substring(1).toLowerCase());
                    result.append(" ");
                }
            }
            ctx.setOutput(node, "result", result.toString().trim());
        } else {
            ctx.setOutput(node, "result", "");
        }
    }

    @DefineNode(id = "string_trim", displayName = "Trim", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "value", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.STRING)})
    public void trim(FlowContext ctx, restudio.flow.data.FlowNode node) {
        String value = ctx.getInputValue(node, "value", String.class, "");
        ctx.setOutput(node, "result", value != null ? value.trim() : "");
    }

    @DefineNode(id = "string_length", displayName = "Length", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "value", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.NUMBER)})
    public void length(FlowContext ctx, restudio.flow.data.FlowNode node) {
        String value = ctx.getInputValue(node, "value", String.class, "");
        ctx.setOutput(node, "result", value != null ? value.length() : 0);
    }

    @DefineNode(id = "string_reverse", displayName = "Reverse", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "value", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.STRING)})
    public void reverse(FlowContext ctx, restudio.flow.data.FlowNode node) {
        String value = ctx.getInputValue(node, "value", String.class, "");
        ctx.setOutput(node, "result", value != null ? new StringBuilder(value).reverse().toString() : "");
    }

    @DefineNode(id = "string_repeat", displayName = "Repeat", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "value", dataType = FlowType.STRING), @FlowPin(name = "count", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.STRING)})
    public void repeat(FlowContext ctx, restudio.flow.data.FlowNode node) {
        String value = ctx.getInputValue(node, "value", String.class, "");
        Integer count = ctx.getInputValue(node, "count", Integer.class, 1);
        ctx.setOutput(node, "result", value != null && count > 0 ? value.repeat(count) : "");
    }

    @DefineNode(id = "string_contains", displayName = "Contains", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "value", dataType = FlowType.STRING), @FlowPin(name = "substring", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.BOOLEAN)})
    public void contains(FlowContext ctx, restudio.flow.data.FlowNode node) {
        String value = ctx.getInputValue(node, "value", String.class, "");
        String substring = ctx.getInputValue(node, "substring", String.class, "");
        ctx.setOutput(node, "result", value != null && substring != null && value.contains(substring));
    }

    @DefineNode(id = "string_starts_with", displayName = "Starts With", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "value", dataType = FlowType.STRING), @FlowPin(name = "prefix", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.BOOLEAN)})
    public void startsWith(FlowContext ctx, restudio.flow.data.FlowNode node) {
        String value = ctx.getInputValue(node, "value", String.class, "");
        String prefix = ctx.getInputValue(node, "prefix", String.class, "");
        ctx.setOutput(node, "result", value != null && prefix != null && value.startsWith(prefix));
    }

    @DefineNode(id = "string_ends_with", displayName = "Ends With", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "value", dataType = FlowType.STRING), @FlowPin(name = "suffix", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.BOOLEAN)})
    public void endsWith(FlowContext ctx, restudio.flow.data.FlowNode node) {
        String value = ctx.getInputValue(node, "value", String.class, "");
        String suffix = ctx.getInputValue(node, "suffix", String.class, "");
        ctx.setOutput(node, "result", value != null && suffix != null && value.endsWith(suffix));
    }

    @DefineNode(id = "string_matches", displayName = "Matches", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "value", dataType = FlowType.STRING), @FlowPin(name = "pattern", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.BOOLEAN)})
    public void matches(FlowContext ctx, restudio.flow.data.FlowNode node) {
        String value = ctx.getInputValue(node, "value", String.class, "");
        String pattern = ctx.getInputValue(node, "pattern", String.class, "");
        if (value != null && pattern != null) {
            try {
                ctx.setOutput(node, "result", Pattern.matches(pattern, value));
            } catch (Exception e) {
                ctx.setOutput(node, "result", false);
            }
        } else {
            ctx.setOutput(node, "result", false);
        }
    }

    @DefineNode(id = "string_index_of", displayName = "Index Of", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "value", dataType = FlowType.STRING), @FlowPin(name = "substring", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.NUMBER)})
    public void indexOf(FlowContext ctx, restudio.flow.data.FlowNode node) {
        String value = ctx.getInputValue(node, "value", String.class, "");
        String substring = ctx.getInputValue(node, "substring", String.class, "");
        ctx.setOutput(node, "result", value != null && substring != null ? value.indexOf(substring) : -1);
    }

    @DefineNode(id = "string_last_index_of", displayName = "Last Index Of", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "value", dataType = FlowType.STRING), @FlowPin(name = "substring", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.NUMBER)})
    public void lastIndexOf(FlowContext ctx, restudio.flow.data.FlowNode node) {
        String value = ctx.getInputValue(node, "value", String.class, "");
        String substring = ctx.getInputValue(node, "substring", String.class, "");
        ctx.setOutput(node, "result", value != null && substring != null ? value.lastIndexOf(substring) : -1);
    }

    @DefineNode(id = "string_equals_ignore_case", displayName = "Equals Ignore Case", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "a", dataType = FlowType.STRING), @FlowPin(name = "b", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.BOOLEAN)})
    public void equalsIgnoreCase(FlowContext ctx, restudio.flow.data.FlowNode node) {
        String a = ctx.getInputValue(node, "a", String.class, "");
        String b = ctx.getInputValue(node, "b", String.class, "");
        ctx.setOutput(node, "result", a != null && b != null && a.equalsIgnoreCase(b));
    }
}
