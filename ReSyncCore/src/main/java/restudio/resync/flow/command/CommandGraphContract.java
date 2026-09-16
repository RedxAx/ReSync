package restudio.resync.flow.command;

import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;

import java.util.Locale;
import java.util.Set;

public final class CommandGraphContract {
    public static final ContractRef<NodeId> CANONICAL_START = ContractRef.of(
        OwnerId.of("restudio.resync"), NodeId.of("event.command"));
    public static final String CANONICAL_SERIALIZED_START = "restudio.resync:event.command";
    public static final Set<String> CANONICAL_SERIALIZED_STARTS = Set.of(
        "event.command", CANONICAL_SERIALIZED_START, "restudio.resync/event.command");
    public static final Set<ContractRef<NodeId>> LEGACY_TYPED_STARTS = Set.of(
        ContractRef.of(OwnerId.of("restudio.resync"), NodeId.of("event.resync.command")),
        ContractRef.of(OwnerId.of("event"), NodeId.of("resync_command")));
    public static final Set<String> LEGACY_SERIALIZED_STARTS = Set.of(
        "event.resync.command", "event:resync_command", "restudio.resync:event.resync.command",
        "restudio.resync/event.resync.command");

    private CommandGraphContract() {
    }

    public static boolean isCanonicalStart(ContractRef<NodeId> definition) {
        return CANONICAL_START.equals(definition);
    }

    public static boolean isLegacyStart(ContractRef<NodeId> definition) {
        return definition != null && LEGACY_TYPED_STARTS.contains(definition);
    }

    public static boolean isAnyStart(ContractRef<NodeId> definition) {
        return isCanonicalStart(definition) || isLegacyStart(definition);
    }

    public static boolean isCanonicalStart(String definition) {
        String normalized = normalize(definition);
        return normalized != null && CANONICAL_SERIALIZED_STARTS.contains(normalized);
    }

    public static boolean isLegacyStart(String definition) {
        String normalized = normalize(definition);
        return normalized != null && LEGACY_SERIALIZED_STARTS.contains(normalized);
    }

    public static boolean isAnyStart(String definition) {
        return isCanonicalStart(definition) || isLegacyStart(definition);
    }

    private static String normalize(String definition) {
        return definition == null ? null : definition.trim().toLowerCase(Locale.ROOT);
    }
}
