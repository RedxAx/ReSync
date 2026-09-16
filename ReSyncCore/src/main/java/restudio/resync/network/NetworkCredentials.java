package restudio.resync.network;

import restudio.resync.contract.canonical.CanonicalDigests;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

public final class NetworkCredentials {
    private static final SecureRandom RANDOM = new SecureRandom();

    private NetworkCredentials() {
    }

    public static String generate() {
        byte[] value = new byte[32];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public static byte[] hash(String credential) {
        String normalized = credential == null ? "" : credential.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Network Credential Is Required");
        }
        return CanonicalDigests.sha256(normalized.getBytes(StandardCharsets.UTF_8));
    }

    public static boolean matches(byte[] expectedHash, byte[] actualHash) {
        return expectedHash != null && actualHash != null && CanonicalDigests.equal(expectedHash, actualHash);
    }
}
