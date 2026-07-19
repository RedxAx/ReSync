package restudio.resync.flow;

public interface FlowValueCodec<T> {
    String id();

    int version();

    Class<T> javaType();

    Object encode(T value);

    T decode(Object value);
}
