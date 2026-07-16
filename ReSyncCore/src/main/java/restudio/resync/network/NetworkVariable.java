package restudio.resync.network;

import java.util.Arrays;
import java.util.Objects;

public record NetworkVariable(String networkId, NetworkVariableScope scope, String scopeId, String key, NetworkVariableType type, byte[] value, long revision, long expiresAt, String originNodeId, long updatedAt) {
    public NetworkVariable {
        networkId = NetworkValues.required(networkId, "Network ID");
        scope = scope == null ? NetworkVariableScope.NETWORK : scope;
        scopeId = NetworkValues.normalized(scopeId);
        key = NetworkValues.required(key, "Variable Key");
        type = type == null ? NetworkVariableType.BYTES : type;
        value = value == null ? new byte[0] : Arrays.copyOf(value, value.length);
        originNodeId = NetworkValues.required(originNodeId, "Origin Node ID");
    }

    @Override
    public byte[] value() {
        return Arrays.copyOf(value, value.length);
    }

    public boolean expired(long now) {
        return expiresAt > 0 && expiresAt <= now;
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof NetworkVariable other && revision == other.revision && expiresAt == other.expiresAt && updatedAt == other.updatedAt && networkId.equals(other.networkId) && scope == other.scope && scopeId.equals(other.scopeId) && key.equals(other.key) && type == other.type && Arrays.equals(value, other.value) && originNodeId.equals(other.originNodeId);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(networkId, scope, scopeId, key, type, revision, expiresAt, originNodeId, updatedAt) + Arrays.hashCode(value);
    }
}
