package restudio.resync.network;

import java.util.Locale;

public record NetworkPlayerRouteResult(NetworkPlayerRouteStatus status, String routeName) {
    public NetworkPlayerRouteResult {
        if (status == null) {
            throw new IllegalArgumentException("Network Player Route Status Is Required");
        }
        routeName = NetworkValues.required(routeName, "Network Player Route").toLowerCase(Locale.ROOT);
    }

    public boolean successful() {
        return status.successful();
    }
}
