package restudio.resync.flow.resource;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.protocol.ProtocolPayloadCodec;

import java.util.Objects;

public interface ResourcePayloadCodec<P> extends ProtocolPayloadCodec<P> {
    String RESOURCE_PAYLOAD_HASH_DOMAIN = "resource-payload.v1";
    String MUTATION_FINGERPRINT_HASH_DOMAIN = "resource-mutation.v1";

    P normalize(P payload);

    @Override
    default P immutable(P payload) {
        return normalize(payload);
    }

    default Object canonicalValue(P normalizedPayload) {
        return Objects.requireNonNull(normalizedPayload, "Normalized payload is required");
    }

    default String canonicalInput(P normalizedPayload) {
        return CanonicalJson.canonicalize(canonicalValue(normalizedPayload));
    }

    default ContentHash hash(P normalizedPayload) {
        return new ContentHash(CanonicalJson.sha256(RESOURCE_PAYLOAD_HASH_DOMAIN, canonicalValue(normalizedPayload)));
    }

    @Override
    default ContentHash checksum(P immutablePayload) {
        return hash(immutablePayload);
    }

    default ContentHash hashPayload(P payload) {
        return canonicalize(Objects.requireNonNull(payload, "Payload is required")).checksum();
    }
}
