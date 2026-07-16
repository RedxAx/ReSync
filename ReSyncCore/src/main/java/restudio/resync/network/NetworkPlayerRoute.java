package restudio.resync.network;

import java.util.UUID;
import java.util.Locale;

public record NetworkPlayerRoute(UUID playerId, String routeName) {
    public NetworkPlayerRoute {
        if (playerId == null) {
            throw new IllegalArgumentException("Network Player ID Is Required");
        }
        routeName = NetworkValues.required(routeName, "Network Player Route").toLowerCase(Locale.ROOT);
        if (!routeName.matches("[a-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Network Player Route Is Invalid");
        }
    }
}
