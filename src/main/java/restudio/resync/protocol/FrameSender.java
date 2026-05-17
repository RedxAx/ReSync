package restudio.resync.protocol;

public interface FrameSender {
    void send(byte[] frame);

    void close(int code, String reason);
}
