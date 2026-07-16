package restudio.resync.network;

import java.util.Arrays;
import java.util.Objects;

public record NetworkVariableMutation(NetworkVariableScope scope, String scopeId, String key, NetworkVariableType type, byte[] value, long expectedRevision, long expiresAt) {
    public NetworkVariableMutation {
        scope = scope == null ? NetworkVariableScope.NETWORK : scope;
        scopeId = NetworkValues.normalized(scopeId);
        key = NetworkValues.required(key, "Variable Key");
        type = type == null ? NetworkVariableType.BYTES : type;
        value = value == null ? new byte[0] : Arrays.copyOf(value, value.length);
        if (scope != NetworkVariableScope.NETWORK && scopeId.isBlank()) {
            throw new IllegalArgumentException("Network Variable Scope ID Is Required");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("Expected Revision Cannot Be Negative");
        }
        if (expiresAt < 0) {
            throw new IllegalArgumentException("Network Variable Expiry Cannot Be Negative");
        }
    }

    @Override
    public byte[] value() {
        return Arrays.copyOf(value, value.length);
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof NetworkVariableMutation other && expectedRevision == other.expectedRevision && expiresAt == other.expiresAt && scope == other.scope && scopeId.equals(other.scopeId) && key.equals(other.key) && type == other.type && Arrays.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(scope, scopeId, key, type, expectedRevision, expiresAt) + Arrays.hashCode(value);
    }
}
