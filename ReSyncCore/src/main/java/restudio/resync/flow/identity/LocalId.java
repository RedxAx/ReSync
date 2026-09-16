package restudio.resync.flow.identity;

public interface LocalId {
    String value();

    default String canonicalText() {
        return value();
    }
}
