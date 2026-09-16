package restudio.resync.migration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

final class MigrationCanonical {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private MigrationCanonical() {
    }

    static String encode(String value) {
        return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    static String decode(String value) throws MigrationException {
        try {
            byte[] bytes = DECODER.decode(value);
            String decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString();
            if (!encode(decoded).equals(value)) {
                throw new MigrationException("Non-Canonical Value");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new MigrationException("Invalid Canonical Value", exception);
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw new MigrationException("Invalid Canonical Value", exception);
        }
    }

    static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 Is Unavailable", exception);
        }
    }

    static String requireDigest(String value, String field) {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException(field + " Must Be A SHA-256 Digest");
        }
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " Is Required");
        }
        if (value.indexOf('\u0000') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(field + " Contains A Control Character");
        }
        return value;
    }
}
