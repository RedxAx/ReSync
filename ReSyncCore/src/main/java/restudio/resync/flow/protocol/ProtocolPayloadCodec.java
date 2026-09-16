package restudio.resync.flow.protocol;

import restudio.resync.flow.identity.ContentHash;

import java.util.Objects;

public interface ProtocolPayloadCodec<P> {
    P immutable(P payload);

    default Object canonicalValue(P immutablePayload) {
        return immutablePayload;
    }

    ContentHash checksum(P immutablePayload);

    default CanonicalPayload<P> canonicalize(P payload) {
        P immutablePayload = Objects.requireNonNull(immutable(Objects.requireNonNull(payload, "payload")), "immutablePayload");
        ContentHash checksum = Objects.requireNonNull(checksum(immutablePayload), "checksum");
        return new CanonicalPayload<>(immutablePayload, checksum);
    }

    default CanonicalPayload<P> verify(P payload, ContentHash expectedChecksum) {
        CanonicalPayload<P> canonicalPayload = canonicalize(payload);
        if (!canonicalPayload.checksum().equals(Objects.requireNonNull(expectedChecksum, "expectedChecksum"))) {
            throw new IllegalArgumentException("Payload checksum does not match canonical content");
        }
        return canonicalPayload;
    }
}
