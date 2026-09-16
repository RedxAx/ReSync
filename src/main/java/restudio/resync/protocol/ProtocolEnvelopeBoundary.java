package restudio.resync.protocol;

import restudio.resync.flow.protocol.ProtocolEnvelope;
import restudio.resync.flow.protocol.ProtocolEnvelopeCodec;
import restudio.resync.flow.protocol.ProtocolPayloadCodec;
import restudio.resync.flow.resource.ResourcePayloadCodecs;

import java.util.Map;
import java.util.Objects;

public final class ProtocolEnvelopeBoundary {
    private final ProtocolEnvelopeCodec<Map<String, Object>> codec;

    public ProtocolEnvelopeBoundary() {
        this(ResourcePayloadCodecs.json());
    }

    public ProtocolEnvelopeBoundary(ProtocolPayloadCodec<Map<String, Object>> payloadCodec) {
        codec = new ProtocolEnvelopeCodec<>(Objects.requireNonNull(payloadCodec, "Payload codec is required"));
    }

    public byte[] encode(ProtocolEnvelope<Map<String, Object>> envelope) {
        return codec.encodeBytes(envelope);
    }

    public ProtocolEnvelope<Map<String, Object>> decode(byte[] payload) {
        return codec.decodeBytes(payload);
    }
}
