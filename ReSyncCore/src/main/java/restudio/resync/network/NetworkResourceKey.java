package restudio.resync.network;

import java.util.Locale;

public record NetworkResourceKey(String type, String resourceId) {
    public NetworkResourceKey {
        type = NetworkValues.required(type, "Resource Type").toLowerCase(Locale.ROOT);
        resourceId = NetworkValues.required(resourceId, "Resource ID");
    }
}
