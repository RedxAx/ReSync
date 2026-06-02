package restudio.resync.advancement;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class AdvancementTreeValidator {
    private static final Pattern ID = Pattern.compile("^[a-z0-9._-]+$");
    private static final Set<String> FRAMES = Set.of("task", "goal", "challenge");

    public void validate(Map<String, JsonObject> trees) {
        Map<String, NodeRef> nodes = new HashMap<>();
        for (Map.Entry<String, JsonObject> treeEntry : trees.entrySet()) {
            String treeId = treeEntry.getKey();
            JsonObject tree = treeEntry.getValue();
            requireId(treeId, "Tree");
            if (!text(tree, "id").isBlank() && !treeId.equals(text(tree, "id"))) {
                throw new IllegalArgumentException("Advancement tree ID " + text(tree, "id") + " does not match resource ID " + treeId);
            }
            JsonObject treeNodes = object(tree, "nodes");
            if (treeNodes == null || treeNodes.isEmpty()) {
                throw new IllegalArgumentException("Advancement tree " + treeId + " must contain one root");
            }
            int roots = 0;
            for (Map.Entry<String, JsonElement> nodeEntry : treeNodes.entrySet()) {
                String nodeId = nodeEntry.getKey();
                requireId(nodeId, "Node");
                if (!nodeEntry.getValue().isJsonObject()) {
                    throw new IllegalArgumentException("Advancement node " + treeId + "/" + nodeId + " must be an object");
                }
                JsonObject node = nodeEntry.getValue().getAsJsonObject();
                if (!bool(node, "enabled", true)) {
                    continue;
                }
                String parent = text(node, "parent");
                if (parent.isBlank()) {
                    roots++;
                }
                validateDisplay(treeId, nodeId, object(node, "display"));
                validateCriteria(treeId, nodeId, object(node, "criteria"));
                validateRequirements(treeId, nodeId, node);
                nodes.put(treeId + "/" + nodeId, new NodeRef(treeId, nodeId, parent));
            }
            if (roots != 1 && bool(tree, "enabled", true)) {
                throw new IllegalArgumentException("Advancement tree " + treeId + " must contain exactly one enabled root");
            }
        }
        for (NodeRef node : nodes.values()) {
            String parent = localParent(node);
            if (parent != null && !nodes.containsKey(parent)) {
                throw new IllegalArgumentException("Missing advancement parent " + node.parent() + " for " + node.treeId() + "/" + node.nodeId());
            }
        }
        for (NodeRef node : nodes.values()) {
            detectCycle(nodes, node, new HashSet<>(), new HashSet<>());
        }
    }

    private void validateDisplay(String treeId, String nodeId, JsonObject display) {
        if (display == null) {
            throw new IllegalArgumentException("Advancement node " + treeId + "/" + nodeId + " requires display");
        }
        String frame = text(display, "frame");
        if (!frame.isBlank() && !FRAMES.contains(frame)) {
            throw new IllegalArgumentException("Unsupported advancement frame " + frame + " for " + treeId + "/" + nodeId);
        }
        if (text(display, "icon").isBlank()) {
            throw new IllegalArgumentException("Advancement node " + treeId + "/" + nodeId + " requires an icon");
        }
    }

    private void validateCriteria(String treeId, String nodeId, JsonObject criteria) {
        if (criteria == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : criteria.entrySet()) {
            requireId(entry.getKey(), "Criterion");
            if (!entry.getValue().isJsonObject()) {
                throw new IllegalArgumentException("Criterion " + treeId + "/" + nodeId + "/" + entry.getKey() + " must be an object");
            }
            String trigger = text(entry.getValue().getAsJsonObject(), "trigger");
            if (trigger.isBlank() || !AdvancementTriggerDescriptors.IDS.contains(trigger)) {
                throw new IllegalArgumentException("Unsupported advancement trigger " + trigger + " for " + treeId + "/" + nodeId + "/" + entry.getKey());
            }
        }
    }

    private void validateRequirements(String treeId, String nodeId, JsonObject node) {
        if (!node.has("requirements")) {
            return;
        }
        if (!node.get("requirements").isJsonArray()) {
            throw new IllegalArgumentException("Advancement requirements for " + treeId + "/" + nodeId + " must be an array");
        }
        JsonObject criteria = object(node, "criteria");
        for (JsonElement group : node.getAsJsonArray("requirements")) {
            if (!group.isJsonArray() || group.getAsJsonArray().isEmpty()) {
                throw new IllegalArgumentException("Advancement requirements for " + treeId + "/" + nodeId + " must contain non-empty groups");
            }
            for (JsonElement criterion : group.getAsJsonArray()) {
                if (!criterion.isJsonPrimitive() || !criterion.getAsJsonPrimitive().isString() || criteria == null || !criteria.has(criterion.getAsString())) {
                    throw new IllegalArgumentException("Unknown advancement criterion in requirements for " + treeId + "/" + nodeId);
                }
            }
        }
    }

    private void detectCycle(Map<String, NodeRef> nodes, NodeRef node, Set<String> visiting, Set<String> visited) {
        String key = node.treeId() + "/" + node.nodeId();
        if (visited.contains(key)) {
            return;
        }
        if (!visiting.add(key)) {
            throw new IllegalArgumentException("Advancement parent cycle contains " + key);
        }
        String parent = localParent(node);
        if (parent != null && nodes.containsKey(parent)) {
            detectCycle(nodes, nodes.get(parent), visiting, visited);
        }
        visiting.remove(key);
        visited.add(key);
    }

    private String localParent(NodeRef node) {
        if (node.parent().isBlank() || node.parent().contains(":")) {
            return null;
        }
        return node.parent().contains("/") ? node.parent() : node.treeId() + "/" + node.parent();
    }

    private void requireId(String id, String label) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException(label + " ID must use lowercase letters, numbers, dots, dashes, or underscores");
        }
    }

    private JsonObject object(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonObject() ? object.getAsJsonObject(key) : null;
    }

    private String text(JsonObject object, String key) {
        return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    private boolean bool(JsonObject object, String key, boolean fallback) {
        return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsBoolean() : fallback;
    }

    private record NodeRef(String treeId, String nodeId, String parent) {
    }
}
