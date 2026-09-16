package restudio.resync.upgrade.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record CommandBindingOutput(
    RawGraphDocument graph,
    String adapterId,
    int adapterVersion,
    boolean changed,
    List<AppliedBinding> appliedBindings
) {
    public CommandBindingOutput {
        graph = Objects.requireNonNull(graph, "graph");
        adapterId = requireText(adapterId, "adapterId");
        if (adapterVersion < 1) {
            throw new IllegalArgumentException("adapterVersion must be positive");
        }
        List<AppliedBinding> copy = new ArrayList<>(appliedBindings == null ? List.of() : appliedBindings);
        if (copy.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("appliedBindings cannot contain null values");
        }
        appliedBindings = List.copyOf(copy);
    }

    public String canonicalText() {
        return graph.canonicalText();
    }

    public byte[] canonicalBytes() {
        return graph.canonicalBytes();
    }

    public String contentHash() {
        return graph.contentHash();
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isBlank() || normalized.indexOf('\u0000') >= 0 || normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    public record AppliedBinding(String bindingId, String flowId, String nodeId, boolean changed) {
        public AppliedBinding {
            bindingId = requireText(bindingId, "bindingId");
            flowId = requireText(flowId, "flowId");
            nodeId = requireText(nodeId, "nodeId");
        }

        private static String requireText(String value, String field) {
            String normalized = Objects.requireNonNull(value, field).trim();
            if (normalized.isBlank() || normalized.indexOf('\u0000') >= 0 || normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0) {
                throw new IllegalArgumentException(field + " is invalid");
            }
            return normalized;
        }
    }
}
