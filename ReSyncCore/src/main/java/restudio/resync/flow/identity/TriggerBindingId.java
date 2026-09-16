package restudio.resync.flow.identity;

import java.util.UUID;

public record TriggerBindingId(UUID value) implements UuidIdentity, Comparable<TriggerBindingId> {
    public TriggerBindingId {
        value = IdentityValidation.uuid(value, "Trigger Binding ID");
    }

    public static TriggerBindingId of(UUID value) {
        return new TriggerBindingId(value);
    }

    public static TriggerBindingId random() {
        return new TriggerBindingId(UuidIdentity.interactive());
    }

    public static TriggerBindingId interactive() {
        return random();
    }

    public static TriggerBindingId deterministic(String name) {
        return deterministic(UuidIdentity.MIGRATION_NAMESPACE, name);
    }

    public static TriggerBindingId deterministic(UUID namespace, String name) {
        return new TriggerBindingId(UuidIdentity.deterministic(namespace, "trigger-binding", name));
    }

    public static TriggerBindingId deterministic(UUID namespace, String domain, String name) {
        return new TriggerBindingId(UuidIdentity.deterministic(namespace, domain, name));
    }

    public static TriggerBindingId parseCanonicalText(String value) {
        return new TriggerBindingId(IdentityValidation.uuid(value, "Trigger Binding ID"));
    }

    @Override
    public int compareTo(TriggerBindingId other) {
        return CanonicalText.compare(canonicalText(), other.canonicalText());
    }

    @Override
    public String toString() {
        return canonicalText();
    }
}
