package restudio.resync.network;

import java.nio.charset.StandardCharsets;

public record NetworkProxyAction(NetworkProxyActionType type, String value) {
    public NetworkProxyAction {
        if (type == null) {
            throw new IllegalArgumentException("Network Proxy Action Type Is Required");
        }
        value = NetworkValues.required(value, "Network Proxy Action");
        if (type == NetworkProxyActionType.COMMAND && value.startsWith("/")) {
            value = value.substring(1).trim();
        }
        int maximumBytes = type == NetworkProxyActionType.COMMAND ? 2048 : 8192;
        if (value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > maximumBytes) {
            throw new IllegalArgumentException("Network Proxy Action Is Too Large");
        }
    }
}
