package restudio.resync.contract.canonical;

import java.util.UUID;

public final class CanonicalUuids {
    private CanonicalUuids() {
    }

    public static int version(UUID value) {
        return Character.digit(value.toString().charAt(14), 16);
    }

    public static int variant(UUID value) {
        int nibble = Character.digit(value.toString().charAt(19), 16);
        if ((nibble & 0x8) == 0) {
            return 0;
        }
        if ((nibble & 0xC) == 0x8) {
            return 2;
        }
        if ((nibble & 0xE) == 0xC) {
            return 6;
        }
        return 7;
    }

    public static long mostSignificantBits(UUID value) {
        return parseHalf(value.toString(), 0);
    }

    public static long leastSignificantBits(UUID value) {
        return parseHalf(value.toString(), 16);
    }

    public static UUID fromBits(long mostSignificantBits, long leastSignificantBits) {
        byte[] bytes = new byte[16];
        writeLong(bytes, 0, mostSignificantBits);
        writeLong(bytes, 8, leastSignificantBits);
        String hex = CanonicalDigests.hex(bytes);
        return UUID.fromString(hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16)
            + "-" + hex.substring(16, 20) + "-" + hex.substring(20));
    }

    public static UUID nameUuidFromBytes(byte[] name) {
        byte[] digest = CanonicalDigests.md5(name);
        digest[6] = (byte) ((digest[6] & 0x0f) | 0x30);
        digest[8] = (byte) ((digest[8] & 0x3f) | 0x80);
        return fromBits(readLong(digest, 0), readLong(digest, 8));
    }

    private static void writeLong(byte[] bytes, int offset, long value) {
        bytes[offset] = (byte) (value >>> 56);
        bytes[offset + 1] = (byte) (value >>> 48);
        bytes[offset + 2] = (byte) (value >>> 40);
        bytes[offset + 3] = (byte) (value >>> 32);
        bytes[offset + 4] = (byte) (value >>> 24);
        bytes[offset + 5] = (byte) (value >>> 16);
        bytes[offset + 6] = (byte) (value >>> 8);
        bytes[offset + 7] = (byte) value;
    }

    private static long readLong(byte[] bytes, int offset) {
        return ((long) (bytes[offset] & 0xff) << 56)
            | ((long) (bytes[offset + 1] & 0xff) << 48)
            | ((long) (bytes[offset + 2] & 0xff) << 40)
            | ((long) (bytes[offset + 3] & 0xff) << 32)
            | ((long) (bytes[offset + 4] & 0xff) << 24)
            | ((long) (bytes[offset + 5] & 0xff) << 16)
            | ((long) (bytes[offset + 6] & 0xff) << 8)
            | (bytes[offset + 7] & 0xff);
    }

    private static long parseHalf(String text, int hexOffset) {
        char[] digits = new char[16];
        int filled = 0;
        int skipped = 0;
        for (int index = 0; index < text.length() && filled < 16; index++) {
            char current = text.charAt(index);
            if (current == '-') {
                continue;
            }
            if (skipped < hexOffset) {
                skipped++;
                continue;
            }
            digits[filled++] = current;
        }
        long high = Long.parseLong(new String(digits, 0, 8), 16);
        long low = Long.parseLong(new String(digits, 8, 8), 16);
        return (high << 32) | (low & 0xffffffffL);
    }
}
