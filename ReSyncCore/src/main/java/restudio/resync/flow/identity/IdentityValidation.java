package restudio.resync.flow.identity;

import restudio.resync.contract.canonical.CanonicalDigests;
import restudio.resync.contract.canonical.CanonicalText;
import restudio.resync.contract.canonical.CanonicalUuids;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

final class IdentityValidation {
    static final int MAX_IDENTIFIER_LENGTH = 128;
    private static final Pattern RESOURCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern DOMAIN = Pattern.compile("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*");

    private IdentityValidation() {
    }

    static String owner(String value) {
        return segmentedIdentifier(value, "Owner ID", true);
    }

    static String local(String value, String field) {
        return segmentedIdentifier(value, field, false);
    }

    static String resource(String value) {
        return identifier(value, "Resource ID", RESOURCE);
    }

    static String hash(String value, String field) {
        Objects.requireNonNull(value, field + " is required");
        if (!HASH.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be 64 lowercase hexadecimal characters");
        }
        return value;
    }

    static UUID uuid(String value, String field) {
        Objects.requireNonNull(value, field + " is required");
        UUID parsed;
        try {
            parsed = UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(field + " must be a canonical lowercase UUID", exception);
        }
        if (!parsed.toString().equals(value) || !value.equals(value.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(field + " must be a canonical lowercase UUID");
        }
        return uuid(parsed, field);
    }

    static UUID uuid(UUID value, String field) {
        Objects.requireNonNull(value, field + " is required");
        if ((CanonicalUuids.version(value) != 4 && CanonicalUuids.version(value) != 5) || CanonicalUuids.variant(value) != 2) {
            throw new IllegalArgumentException(field + " must use an RFC 4122 UUID version 4 or 5");
        }
        return value;
    }

    static UUID uuidV4(UUID value, String field) {
        UUID checked = uuid(value, field);
        if (CanonicalUuids.version(checked) != 4) {
            throw new IllegalArgumentException(field + " must use UUID version 4");
        }
        return checked;
    }

    static UUID uuidV5(UUID value, String field) {
        UUID checked = uuid(value, field);
        if (CanonicalUuids.version(checked) != 5) {
            throw new IllegalArgumentException(field + " must use UUID version 5");
        }
        return checked;
    }

    static String domain(String value) {
        Objects.requireNonNull(value, "UUID domain is required");
        if (value.isEmpty() || value.length() > 64 || !CanonicalText.isNfc(value) || !DOMAIN.matcher(value).matches()) {
            throw new IllegalArgumentException("UUID domain must be a canonical lowercase identifier");
        }
        return value;
    }

    static String deterministicName(String value) {
        Objects.requireNonNull(value, "UUID name is required");
        if (value.isEmpty() || !CanonicalText.isNfc(value)) {
            throw new IllegalArgumentException("UUID name must be non-empty NFC text");
        }
        return value;
    }

    static UUID uuidV5(UUID namespace, String domain, String name) {
        UUID checkedNamespace = uuid(namespace, "UUID namespace");
        String qualifiedName = domain(domain) + "|" + deterministicName(name);
        byte[] namespaceBytes = ByteBuffer.allocate(16)
                .putLong(CanonicalUuids.mostSignificantBits(checkedNamespace))
                .putLong(CanonicalUuids.leastSignificantBits(checkedNamespace))
                .array();
        byte[] nameBytes = qualifiedName.getBytes(StandardCharsets.UTF_8);
        byte[] input = new byte[namespaceBytes.length + nameBytes.length];
        System.arraycopy(namespaceBytes, 0, input, 0, namespaceBytes.length);
        System.arraycopy(nameBytes, 0, input, namespaceBytes.length, nameBytes.length);
        byte[] digest = CanonicalDigests.sha1(input);
        digest[6] = (byte) ((digest[6] & 0x0f) | 0x50);
        digest[8] = (byte) ((digest[8] & 0x3f) | 0x80);
        ByteBuffer result = ByteBuffer.wrap(digest);
        return CanonicalUuids.fromBits(result.getLong(), result.getLong());
    }

    private static String segmentedIdentifier(String value, String field, boolean owner) {
        Objects.requireNonNull(value, field + " is required");
        if (value.isEmpty() || value.length() > MAX_IDENTIFIER_LENGTH) {
            throw new IllegalArgumentException(field + " must contain 1 to " + MAX_IDENTIFIER_LENGTH + " characters");
        }
        int segmentLength = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean letter = character >= 'a' && character <= 'z';
            boolean digit = character >= '0' && character <= '9';
            boolean separator = character == '.' || character == '-' || !owner && character == '_';
            if (separator && segmentLength > 0) {
                segmentLength = 0;
            } else if ((letter || digit && index > 0 && (!owner || segmentLength > 0)) && ++segmentLength <= 32) {
                continue;
            } else {
                throw new IllegalArgumentException("Invalid " + field + ": " + value);
            }
        }
        if (segmentLength == 0) {
            throw new IllegalArgumentException("Invalid " + field + ": " + value);
        }
        return value;
    }

    private static String identifier(String value, String field, Pattern pattern) {
        Objects.requireNonNull(value, field + " is required");
        if (value.isEmpty() || value.length() > MAX_IDENTIFIER_LENGTH) {
            throw new IllegalArgumentException(field + " must contain 1 to " + MAX_IDENTIFIER_LENGTH + " characters");
        }
        if (!CanonicalText.isNfc(value)) {
            throw new IllegalArgumentException(field + " must already be in NFC form");
        }
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid " + field + ": " + value);
        }
        return value;
    }
}
