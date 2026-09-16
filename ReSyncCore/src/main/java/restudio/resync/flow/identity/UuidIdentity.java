package restudio.resync.flow.identity;

import java.util.UUID;

public interface UuidIdentity {
    UUID MIGRATION_NAMESPACE = UUID.fromString("b6d7f3d4-3d48-5ce2-8a83-6fc6730f3f2f");

    UUID value();

    static UUID interactive() {
        return UUID.randomUUID();
    }

    static UUID deterministic(String domain, String name) {
        return deterministic(MIGRATION_NAMESPACE, domain, name);
    }

    static UUID deterministic(UUID namespace, String domain, String name) {
        return IdentityValidation.uuidV5(namespace, domain, name);
    }

    default String canonicalText() {
        return value().toString();
    }
}
