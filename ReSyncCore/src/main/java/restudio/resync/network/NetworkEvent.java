package restudio.resync.network;

import java.util.Arrays;
import java.util.Objects;

public record NetworkEvent(String eventId, String networkId, String channel, String subject, byte[] payload, String originNodeId, long createdAt, long expiresAt) {
    public NetworkEvent {
        eventId = NetworkValues.required(eventId, "Event ID");
        networkId = NetworkValues.required(networkId, "Network ID");
        channel = NetworkValues.required(channel, "Event Channel");
        subject = NetworkValues.normalized(subject);
        payload = payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
        originNodeId = NetworkValues.required(originNodeId, "Origin Node ID");
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof NetworkEvent other && createdAt == other.createdAt && expiresAt == other.expiresAt && eventId.equals(other.eventId) && networkId.equals(other.networkId) && channel.equals(other.channel) && subject.equals(other.subject) && Arrays.equals(payload, other.payload) && originNodeId.equals(other.originNodeId);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(eventId, networkId, channel, subject, originNodeId, createdAt, expiresAt) + Arrays.hashCode(payload);
    }
}
