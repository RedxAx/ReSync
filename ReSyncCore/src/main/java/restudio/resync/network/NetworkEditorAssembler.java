package restudio.resync.network;

import java.util.UUID;

public final class NetworkEditorAssembler {
    private UUID tunnelId;
    private long sequence;
    private byte[] frame;
    private int offset;

    public synchronized byte[] accept(NetworkEditorChunk chunk) {
        if (tunnelId == null) tunnelId = chunk.tunnelId();
        if (!tunnelId.equals(chunk.tunnelId()) || chunk.receipt() || chunk.sequence() != sequence || chunk.offset() != offset) {
            throw new IllegalArgumentException("Editor Frame Sequence Is Invalid");
        }
        if (frame == null) frame = new byte[chunk.totalBytes()];
        if (frame.length != chunk.totalBytes()) throw new IllegalArgumentException("Editor Frame Length Changed");
        byte[] bytes = chunk.bytes();
        System.arraycopy(bytes, 0, frame, offset, bytes.length);
        offset += bytes.length;
        if (offset != frame.length) return null;
        byte[] complete = frame;
        frame = null;
        offset = 0;
        sequence = Math.addExact(sequence, 1);
        return complete;
    }

    public synchronized void clear() {
        frame = null;
        offset = 0;
    }
}
