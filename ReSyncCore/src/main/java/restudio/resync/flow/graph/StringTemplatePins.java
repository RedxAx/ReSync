package restudio.resync.flow.graph;

import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class StringTemplatePins {
    public static final TypeExpr STRING = TypeExpr.named(TypeReference.of("builtin", "string"));

    private StringTemplatePins() {
    }

    public static Map<PinId, TypeExpr> derive(GraphNode node, CatalogNodeDescriptor definition) {
        Map<PinId, TypeExpr> inputs = new LinkedHashMap<>();
        Set<PinId> reserved = new LinkedHashSet<>();
        definition.pins().forEach(pin -> {
            reserved.add(pin.id());
            if (pin.direction() == CatalogNodeDescriptor.Direction.INPUT) {
                inputs.put(pin.id(), pin.type());
            }
        });
        return derive(node, inputs, reserved);
    }

    public static Map<PinId, TypeExpr> derive(GraphNode node, Map<PinId, TypeExpr> inputs, Set<PinId> reserved) {
        Map<PinId, TypeExpr> pins = new LinkedHashMap<>();
        for (Map.Entry<PinId, TypeExpr> pin : inputs.entrySet()) {
            if (!STRING.equals(pin.getValue())) {
                continue;
            }
            PinValue stored = node.values().get(pin.getKey());
            if (stored == null || stored.value().state() != TypedValue.State.VALUE
                || !(stored.value().value() instanceof String value)) {
                continue;
            }
            for (String name : names(value)) {
                PinId id = PinId.of(name);
                if (!reserved.contains(id)) {
                    pins.putIfAbsent(id, STRING);
                }
            }
        }
        return Map.copyOf(pins);
    }

    public static List<String> names(String text) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        parse(text).parts().stream().filter(Part::placeholder).map(Part::text).forEach(names::add);
        return List.copyOf(names);
    }

    public static Template parse(String text) {
        if (text == null || text.isEmpty()) {
            return new Template(List.of(new Part("", false)));
        }
        List<Part> parts = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int index = 0;
        int escaped = 0;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (current == '{' && index + 1 < text.length() && text.charAt(index + 1) == '{') {
                literal.append('{');
                escaped++;
                index += 2;
                continue;
            }
            if (current == '}' && escaped > 0 && index + 1 < text.length() && text.charAt(index + 1) == '}') {
                literal.append('}');
                escaped--;
                index += 2;
                continue;
            }
            if (current == '{') {
                int end = text.indexOf('}', index + 1);
                if (end > index + 1) {
                    String name = text.substring(index + 1, end).trim();
                    if (validName(name)) {
                        if (!literal.isEmpty()) {
                            parts.add(new Part(literal.toString(), false));
                            literal.setLength(0);
                        }
                        parts.add(new Part(name, true));
                        index = end + 1;
                        continue;
                    }
                }
            }
            literal.append(current);
            index++;
        }
        if (!literal.isEmpty()) {
            parts.add(new Part(literal.toString(), false));
        }
        return new Template(parts);
    }

    private static boolean validName(String name) {
        if (name.isEmpty() || !(Character.isLetter(name.charAt(0)) || name.charAt(0) == '_')) {
            return false;
        }
        for (int index = 1; index < name.length(); index++) {
            char value = name.charAt(index);
            if (!(Character.isLetterOrDigit(value) || value == '_')) {
                return false;
            }
        }
        try {
            PinId.of(name);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public record Part(String text, boolean placeholder) {
    }

    public record Template(List<Part> parts) {
        public Template {
            parts = List.copyOf(parts);
        }

        public String render(Map<String, ?> values, Set<String> reserved) {
            StringBuilder result = new StringBuilder();
            for (Part part : parts) {
                if (!part.placeholder()) {
                    result.append(part.text());
                } else if (reserved.contains(part.text())) {
                    result.append('{').append(part.text()).append('}');
                } else {
                    Object value = values.get(part.text());
                    if (value != null) {
                        result.append(value);
                    }
                }
            }
            return result.toString();
        }
    }
}
