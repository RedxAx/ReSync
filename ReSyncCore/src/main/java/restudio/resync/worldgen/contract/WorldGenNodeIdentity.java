package restudio.resync.worldgen.contract;

import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;

public record WorldGenNodeIdentity(ContractRef<NodeId> reference) {
    public static final OwnerId OWNER = OwnerId.of("worldgen");

    public WorldGenNodeIdentity {
        if (reference == null || !OWNER.equals(reference.owner())) {
            throw new IllegalArgumentException("WorldGen node owner must be worldgen");
        }
    }

    public static WorldGenNodeIdentity parse(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException("WorldGen node identity must be a canonical worldgen:<id> reference");
        }
        int separator = value.indexOf(':');
        if (separator <= 0 || separator == value.length() - 1 || value.indexOf(':', separator + 1) >= 0) {
            throw new IllegalArgumentException("WorldGen node identity must be a canonical worldgen:<id> reference");
        }
        OwnerId owner = OwnerId.of(value.substring(0, separator));
        NodeId id = NodeId.of(value.substring(separator + 1));
        return new WorldGenNodeIdentity(ContractRef.of(owner, id));
    }

    public static String canonical(String value, boolean legacyCompatibility) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException("WorldGen node identity must be a canonical worldgen:<id> reference");
        }
        if (value.indexOf(':') < 0) {
            if (!legacyCompatibility) {
                throw new IllegalArgumentException("Unqualified WorldGen node identity requires explicit legacy compatibility: " + value);
            }
            return new WorldGenNodeIdentity(ContractRef.of(OWNER, NodeId.of(value))).wireText();
        }
        return parse(value).wireText();
    }

    public static String require(String value) {
        return canonical(value, false);
    }

    public static String localId(String value) {
        return parse(value).reference().id().value();
    }

    public static String localId(String value, boolean legacyCompatibility) {
        return parse(canonical(value, legacyCompatibility)).reference().id().value();
    }

    public String wireText() {
        return reference.owner().canonicalText() + ":" + reference.id().canonicalText();
    }
}
