package restudio.resync.network;

import restudio.resync.contract.canonical.CanonicalDigests;

public final class NetworkPayloads {
    private NetworkPayloads() {
    }

    public static String sha256(byte[] payload) {
        return CanonicalDigests.hex(CanonicalDigests.sha256(payload == null ? new byte[0] : payload));
    }

    public static void requireLimit(byte[] payload, int maximumBytes) {
        int length = payload == null ? 0 : payload.length;
        if (maximumBytes < 0 || length > maximumBytes) {
            throw new IllegalArgumentException("Network Payload Exceeds " + maximumBytes + " Bytes");
        }
    }
}
