package restudio.resync.protocol;

import java.util.Arrays;
import java.util.Objects;

public record ReSyncFrame(ReSyncMessageType messageType, int channel, int sequence, byte[] payload,
                          boolean compressed, boolean batch, boolean hasAck) {
    public ReSyncFrame {
        if (messageType == null) {
            throw new ReSyncProtocolException(ReSyncProtocolException.Reason.UNKNOWN_MESSAGE_TYPE,
                "ReSync message type is required");
        }
        if (channel < 0 || channel > ReSyncProtocolContract.MAX_CHANNEL_ID) {
            throw new ReSyncProtocolException(ReSyncProtocolException.Reason.INVALID_CHANNEL,
                "Invalid ReSync channel " + channel);
        }
        payload = payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof ReSyncFrame other
            && messageType == other.messageType
            && channel == other.channel
            && sequence == other.sequence
            && compressed == other.compressed
            && batch == other.batch
            && hasAck == other.hasAck
            && Arrays.equals(payload, other.payload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(messageType, channel, sequence, compressed, batch, hasAck, Arrays.hashCode(payload));
    }
}
