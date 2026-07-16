package restudio.resync.network;

import java.util.UUID;

public record NetworkSnapshotQuery(UUID playerId, int offset, int limit) {
    public NetworkSnapshotQuery {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID Is Required");
        }
        if (offset < 0 || offset > 1000000) {
            throw new IllegalArgumentException("Snapshot Query Offset Is Invalid");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Snapshot Query Limit Must Be Between 1 And 100");
        }
    }

    public NetworkSnapshotQuery(UUID playerId, int limit) {
        this(playerId, 0, limit);
    }
}
