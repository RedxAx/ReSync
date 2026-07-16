package restudio.resync.network;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class NetworkPayloads {
    private NetworkPayloads() {
    }

    public static String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload == null ? new byte[0] : payload));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 Is Unavailable", exception);
        }
    }

    public static void requireLimit(byte[] payload, int maximumBytes) {
        int length = payload == null ? 0 : payload.length;
        if (maximumBytes < 0 || length > maximumBytes) {
            throw new IllegalArgumentException("Network Payload Exceeds " + maximumBytes + " Bytes");
        }
    }
}
