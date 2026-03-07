package restudio.resync.modules;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record ModuleMetadata(String id, String displayName, List<String> dependencies, Set<String> channels) {
    public ModuleMetadata {
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        channels = channels == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(channels));
    }

    public static ModuleMetadata of(String id, String displayName, String... channels) {
        return new ModuleMetadata(id, displayName, List.of(), Set.of(channels));
    }

    public ModuleMetadata withDependencies(String... dependencies) {
        return new ModuleMetadata(id, displayName, List.of(dependencies), channels);
    }

    public String primaryChannel() {
        return channels.isEmpty() ? null : channels.stream().findFirst().orElse(null);
    }
}
