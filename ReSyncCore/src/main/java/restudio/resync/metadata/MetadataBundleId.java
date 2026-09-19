package restudio.resync.metadata;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.CanonicalDigests;
import restudio.resync.flow.canonical.CanonicalLimits;

import java.util.Objects;

public record MetadataBundleId(String sha256) implements Comparable<MetadataBundleId> {
    private static final int DIGEST_LENGTH = 64;

    public MetadataBundleId {
        Objects.requireNonNull(sha256, "Metadata bundle SHA-256 is required");
        if (sha256.length() != DIGEST_LENGTH) {
            throw new IllegalArgumentException("Metadata bundle SHA-256 must contain 64 lowercase hexadecimal characters");
        }
        for (int index = 0; index < sha256.length(); index++) {
            char value = sha256.charAt(index);
            if (!((value >= '0' && value <= '9') || (value >= 'a' && value <= 'f'))) {
                throw new IllegalArgumentException("Metadata bundle SHA-256 must contain 64 lowercase hexadecimal characters");
            }
        }
    }

    public static MetadataBundleId ofCanonicalBytes(byte[] bytes) {
        byte[] canonical = CanonicalCodec.canonicalBytes(Objects.requireNonNull(bytes, "Canonical metadata bundle bytes are required"),
            CanonicalLimits.catalog());
        return new MetadataBundleId(CanonicalDigests.hex(CanonicalDigests.sha256(canonical)));
    }

    public static MetadataBundleId parseCanonicalText(String value) {
        return new MetadataBundleId(value);
    }

    public String canonicalText() {
        return sha256;
    }

    public boolean verifiesCanonicalBytes(byte[] bytes) {
        return equals(ofCanonicalBytes(bytes));
    }

    @Override
    public int compareTo(MetadataBundleId other) {
        return sha256.compareTo(other.sha256);
    }

    @Override
    public String toString() {
        return sha256;
    }
}
