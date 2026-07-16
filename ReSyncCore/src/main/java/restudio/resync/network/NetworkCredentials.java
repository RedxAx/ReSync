package restudio.resync.network;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
        try {
            return MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 Is Unavailable", exception);
        }
    }

    public static boolean matches(byte[] expectedHash, byte[] actualHash) {
        return expectedHash != null && actualHash != null && MessageDigest.isEqual(expectedHash, actualHash);
    }
}
