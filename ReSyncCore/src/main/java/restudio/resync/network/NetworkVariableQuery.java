package restudio.resync.network;

public record NetworkVariableQuery(NetworkVariableScope scope, String scopeId, String key) {
    public NetworkVariableQuery {
        scope = scope == null ? NetworkVariableScope.NETWORK : scope;
        scopeId = NetworkValues.normalized(scopeId);
        key = NetworkValues.required(key, "Variable Key");
        if (scope != NetworkVariableScope.NETWORK && scopeId.isBlank()) {
            throw new IllegalArgumentException("Network Variable Scope ID Is Required");
        }
    }
}
