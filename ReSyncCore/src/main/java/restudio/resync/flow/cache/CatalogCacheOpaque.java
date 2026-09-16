package restudio.resync.flow.cache;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.canonical.CanonicalLimits;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

public final class CatalogCacheOpaque {
    private final byte[] canonicalBytes;

    public CatalogCacheOpaque(byte[] canonicalBytes) {
        this(ownedCopy(canonicalBytes), false);
    }

    private CatalogCacheOpaque(byte[] canonicalBytes, boolean trustedCanonicalJson) {
        if (canonicalBytes.length == 0) {
            throw new IllegalArgumentException("Canonical opaque bytes cannot be empty");
        }
        if (canonicalBytes.length > CanonicalLimits.standard().opaqueSubtreeBytes()) {
            throw new IllegalArgumentException("Canonical opaque data exceeds the configured limit");
        }
        if (!trustedCanonicalJson) {
            CanonicalCodec.decode(canonicalBytes, CanonicalLimits.catalog());
        }
        this.canonicalBytes = canonicalBytes;
    }

    public static CatalogCacheOpaque of(byte[] canonicalBytes) {
        return new CatalogCacheOpaque(canonicalBytes);
    }

    static CatalogCacheOpaque fromCanonicalJson(JsonValue value) {
        Objects.requireNonNull(value, "Canonical JSON value is required");
        return new CatalogCacheOpaque(value.canonicalBytes(CanonicalLimits.catalog()), true);
    }

    static CatalogCacheOpaque fromCanonicalJson(CanonicalCodec.ValidatedJson validated, JsonValue value) {
        Objects.requireNonNull(validated, "Validated canonical JSON is required");
        return new CatalogCacheOpaque(validated.canonicalBytes(value), true);
    }

    public byte[] canonicalBytes() {
        return canonicalBytes.clone();
    }

    public String canonicalText() {
        return new String(canonicalBytes, StandardCharsets.UTF_8);
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof CatalogCacheOpaque other && Arrays.equals(canonicalBytes, other.canonicalBytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(canonicalBytes);
    }

    @Override
    public String toString() {
        return canonicalText();
    }

    private static byte[] ownedCopy(byte[] canonicalBytes) {
        byte[] input = Objects.requireNonNull(canonicalBytes, "Canonical opaque bytes are required");
        if (input.length == 0) {
            throw new IllegalArgumentException("Canonical opaque bytes cannot be empty");
        }
        if (input.length > CanonicalLimits.standard().opaqueSubtreeBytes()) {
            throw new IllegalArgumentException("Canonical opaque data exceeds the configured limit");
        }
        return input.clone();
    }
}
