package restudio.resync.network;

import java.util.Arrays;
import java.util.Objects;

public record NetworkFrame(NetworkRequestContext context, String channel, NetworkFrameType type, byte[] payload) {
    public NetworkFrame {
        if (context == null) {
            throw new IllegalArgumentException("Network Request Context Is Required");
        }
        channel = NetworkValues.required(channel, "Network Channel");
        if (!NetworkChannels.ALL.contains(channel)) {
            throw new IllegalArgumentException("Unknown Network Channel " + channel);
        }
        if (type == null) {
            throw new IllegalArgumentException("Network Frame Type Is Required");
        }
        payload = payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof NetworkFrame other && context.equals(other.context) && channel.equals(other.channel) && type == other.type && Arrays.equals(payload, other.payload);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(context, channel, type) + Arrays.hashCode(payload);
    }
}
