package restudio.resync.network;

import java.util.UUID;

public record NetworkPlayerLifecycle(NetworkPlayerLifecycleType type, UUID playerId, String playerName, String sourceRoute, String targetRoute, String failure, long occurredAt) {
    public NetworkPlayerLifecycle {
        if (type == null) {
            throw new IllegalArgumentException("Network Player Lifecycle Type Is Required");
        }
        if (playerId == null) {
            throw new IllegalArgumentException("Network Player ID Is Required");
        }
        playerName = NetworkValues.required(playerName, "Network Player Name");
        sourceRoute = NetworkValues.normalized(sourceRoute);
        targetRoute = NetworkValues.normalized(targetRoute);
        failure = NetworkValues.normalized(failure);
        if (occurredAt < 0) {
            throw new IllegalArgumentException("Network Player Lifecycle Time Is Invalid");
        }
    }
}
