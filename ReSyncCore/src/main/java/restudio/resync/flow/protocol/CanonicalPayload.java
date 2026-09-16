package restudio.resync.flow.protocol;

import restudio.resync.flow.identity.ContentHash;

import java.util.Objects;

public final class CanonicalPayload<P> {
    private final P value;
    private final ContentHash checksum;

    CanonicalPayload(P value, ContentHash checksum) {
        this.value = Objects.requireNonNull(value, "value");
        this.checksum = Objects.requireNonNull(checksum, "checksum");
    }

    public P value() {
        return value;
    }

    public ContentHash checksum() {
        return checksum;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof CanonicalPayload<?> that && value.equals(that.value) && checksum.equals(that.checksum);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, checksum);
    }

    @Override
    public String toString() {
        return "CanonicalPayload[value=" + value + ", checksum=" + checksum + ']';
    }
}
