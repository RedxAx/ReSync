package restudio.resync.upgrade.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record CommandBindingInput(RawGraphDocument graph, List<CommandBinding> bindings) {
    public CommandBindingInput {
        graph = Objects.requireNonNull(graph, "graph");
        List<CommandBinding> copy = new ArrayList<>(bindings == null ? List.of() : bindings);
        if (copy.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("COMMAND_BINDING_INVALID: bindings cannot contain null values");
        }
        bindings = List.copyOf(copy);
    }

    public static CommandBindingInput single(RawGraphDocument graph, CommandBinding binding) {
        return new CommandBindingInput(graph, List.of(Objects.requireNonNull(binding, "binding")));
    }
}
