package restudio.resync.protocol;

public interface FrameSender {
    int UNBOUNDED_MAX_ENCODED_FRAME_BYTES = Integer.MAX_VALUE;

    void send(byte[] frame);

    default SendResult trySend(byte[] frame) {
        send(frame);
        return SendResult.ACCEPTED;
    }

    void close(int code, String reason);

    default int getMaxEncodedFrameBytes() {
        return UNBOUNDED_MAX_ENCODED_FRAME_BYTES;
    }

    enum SendResult {
        ACCEPTED,
        BACKPRESSURED,
        CLOSED
    }
}
