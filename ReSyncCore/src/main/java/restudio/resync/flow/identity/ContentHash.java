package restudio.resync.flow.identity;

public record ContentHash(String value) implements Comparable<ContentHash> {
    public ContentHash {
        value = IdentityValidation.hash(value, "Content hash");
    }

    public static ContentHash of(String value) {
        return new ContentHash(value);
    }

    public static ContentHash parseCanonicalText(String value) {
        return new ContentHash(value);
    }

    public String canonicalText() {
        return value;
    }

    @Override
    public int compareTo(ContentHash other) {
        return CanonicalText.compare(value, other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
