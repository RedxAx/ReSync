package restudio.resync.flow.trigger;

import restudio.resync.flow.graph.ExecutionTarget;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.TriggerBindingId;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record TriggerBindingDocument(
    int formatVersion,
    ServerId serverId,
    long revision,
    UUID mutationId,
    List<TriggerBinding> bindings,
    OpaqueData unknown
) {
    public static final int FORMAT_VERSION = 2;
    private static final Set<String> KNOWN_FIELDS = Set.of("formatVersion", "serverId", "revision", "mutationId", "bindings");

    public TriggerBindingDocument {
        if (formatVersion != FORMAT_VERSION) {
            throw new IllegalArgumentException("Trigger Binding Format Version Must Be " + FORMAT_VERSION);
        }
        serverId = Objects.requireNonNull(serverId, "Trigger Binding Server ID Is Required");
        if (revision < 0) {
            throw new IllegalArgumentException("Trigger Binding Revision Cannot Be Negative");
        }
        mutationId = Objects.requireNonNull(mutationId, "Trigger Binding Mutation ID Is Required");
        bindings = ordered(bindings);
        unknown = unknown != null ? unknown : OpaqueData.empty();
        for (String field : KNOWN_FIELDS) {
            if (unknown.contains(field)) {
                throw new IllegalArgumentException("Trigger Binding Document Unknown Data Collides With Known Field: " + field);
            }
        }
    }

    public TriggerBindingDocument(ServerId serverId, long revision, UUID mutationId, List<TriggerBinding> bindings, OpaqueData unknown) {
        this(FORMAT_VERSION, serverId, revision, mutationId, bindings, unknown);
    }

    public TriggerBindingDocument(ServerId serverId, long revision, UUID mutationId, List<TriggerBinding> bindings) {
        this(FORMAT_VERSION, serverId, revision, mutationId, bindings, OpaqueData.empty());
    }

    public TriggerBinding require(TriggerBindingId id) {
        Objects.requireNonNull(id, "Trigger Binding ID Is Required");
        return bindings.stream().filter(binding -> binding.id().equals(id)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown Trigger Binding: " + id));
    }

    private static List<TriggerBinding> ordered(List<TriggerBinding> bindings) {
        List<TriggerBinding> ordered = (bindings != null ? bindings : List.<TriggerBinding>of()).stream()
            .map(binding -> Objects.requireNonNull(binding, "Trigger Bindings Cannot Contain Null"))
            .sorted((left, right) -> left.id().compareTo(right.id()))
            .toList();
        Set<TriggerBindingId> ids = new HashSet<>();
        Set<RouteTarget> routes = new HashSet<>();
        for (TriggerBinding binding : ordered) {
            if (!ids.add(binding.id())) {
                throw new IllegalArgumentException("Duplicate Trigger Binding ID: " + binding.id());
            }
            if (!routes.add(new RouteTarget(binding.route(), binding.target()))) {
                throw new IllegalArgumentException("Duplicate Trigger Binding Route And Target: " + binding.id());
            }
        }
        return ordered;
    }

    private record RouteTarget(TriggerRoute route, ExecutionTarget target) {
    }
}
