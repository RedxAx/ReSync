package restudio.resync.network;

import java.util.Objects;
import java.util.UUID;

public record NetworkEditorChunk(UUID tunnelId, long sequence, int offset, int totalBytes, byte[] bytes) {
    public static final int MAXIMUM_CHUNK_BYTES = 32_768;
    public static final int MAXIMUM_FRAME_BYTES = 8 * 1024 * 1024;

    public NetworkEditorChunk {
        Objects.requireNonNull(tunnelId, "Tunnel ID");
        Objects.requireNonNull(bytes, "Editor Bytes");
        if (sequence < 0 || offset < 0 || totalBytes < 0 || totalBytes > MAXIMUM_FRAME_BYTES
            || bytes.length > MAXIMUM_CHUNK_BYTES || offset > totalBytes - bytes.length
            || (totalBytes == 0 ? offset != 0 || bytes.length != 0 : bytes.length == 0)) {
            throw new IllegalArgumentException("Editor Chunk Bounds Are Invalid");
        }
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    public int size() {
        return bytes.length;
    }

    public boolean receipt() {
        return totalBytes == 0;
    }
}
