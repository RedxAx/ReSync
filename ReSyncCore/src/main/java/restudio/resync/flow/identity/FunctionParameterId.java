package restudio.resync.flow.identity;

import java.util.UUID;

public record FunctionParameterId(UUID value) implements UuidIdentity, Comparable<FunctionParameterId> {
    public FunctionParameterId {
        value = IdentityValidation.uuid(value, "Function parameter ID");
    }

    public static FunctionParameterId of(UUID value) {
        return new FunctionParameterId(value);
    }

    public static FunctionParameterId random() {
        return new FunctionParameterId(UuidIdentity.interactive());
    }

    public static FunctionParameterId interactive() {
        return random();
    }

    public static FunctionParameterId deterministic(String name) {
        return deterministic(UuidIdentity.MIGRATION_NAMESPACE, name);
    }

    public static FunctionParameterId deterministic(UUID namespace, String name) {
        return new FunctionParameterId(UuidIdentity.deterministic(namespace, "function-parameter", name));
    }

    public static FunctionParameterId deterministic(UUID namespace, String domain, String name) {
        return new FunctionParameterId(UuidIdentity.deterministic(namespace, domain, name));
    }

    public static FunctionParameterId parseCanonicalText(String value) {
        return new FunctionParameterId(IdentityValidation.uuid(value, "Function parameter ID"));
    }

    @Override
    public int compareTo(FunctionParameterId other) {
        return CanonicalText.compare(canonicalText(), other.canonicalText());
    }

    @Override
    public String toString() {
        return canonicalText();
    }
}
