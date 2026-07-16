package restudio.resync.network;

import java.util.Arrays;
import java.util.Objects;

public record NetworkEventPublish(String eventId, String channel, String subject, byte[] payload, long createdAt, long expiresAt) {
    public NetworkEventPublish {
        eventId = NetworkValues.required(eventId, "Event ID");
        channel = NetworkValues.required(channel, "Event Channel");
        subject = NetworkValues.normalized(subject);
        payload = payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
        if (createdAt < 0 || expiresAt < 0) {
            throw new IllegalArgumentException("Network Event Time Cannot Be Negative");
        }
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof NetworkEventPublish other && createdAt == other.createdAt && expiresAt == other.expiresAt && eventId.equals(other.eventId) && channel.equals(other.channel) && subject.equals(other.subject) && Arrays.equals(payload, other.payload);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(eventId, channel, subject, createdAt, expiresAt) + Arrays.hashCode(payload);
    }
}
