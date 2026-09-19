package restudio.resync.metadata;

final class PortableSha256 {
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final int[] CONSTANTS = {
            0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
            0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
            0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
            0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
    };

    private PortableSha256() {
    }

    static String hex(byte[] value) {
        byte[] digest = digest(value);
        char[] result = new char[digest.length * 2];
        for (int index = 0; index < digest.length; index++) {
            int current = digest[index] & 0xff;
            result[index * 2] = HEX[current >>> 4];
            result[index * 2 + 1] = HEX[current & 0x0f];
        }
        return new String(result);
    }

    private static byte[] digest(byte[] value) {
        byte[] source = value == null ? new byte[0] : value;
        long blocks = ((long) source.length + 72) / 64;
        if (blocks > Integer.MAX_VALUE / 64) {
            throw new IllegalArgumentException("SHA-256 input is too large");
        }
        byte[] padded = new byte[(int) blocks * 64];
        for (int index = 0; index < source.length; index++) {
            padded[index] = source[index];
        }
        padded[source.length] = (byte) 0x80;
        long bitLength = (long) source.length << 3;
        for (int index = 0; index < Long.BYTES; index++) {
            padded[padded.length - 1 - index] = (byte) (bitLength >>> index * 8);
        }

        int[] hash = {
                0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
                0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
        };
        int[] words = new int[64];
        for (int offset = 0; offset < padded.length; offset += 64) {
            for (int index = 0; index < 16; index++) {
                int wordOffset = offset + index * 4;
                words[index] = (padded[wordOffset] & 0xff) << 24 | (padded[wordOffset + 1] & 0xff) << 16
                        | (padded[wordOffset + 2] & 0xff) << 8 | padded[wordOffset + 3] & 0xff;
            }
            for (int index = 16; index < words.length; index++) {
                int first = Integer.rotateRight(words[index - 15], 7) ^ Integer.rotateRight(words[index - 15], 18)
                        ^ words[index - 15] >>> 3;
                int second = Integer.rotateRight(words[index - 2], 17) ^ Integer.rotateRight(words[index - 2], 19)
                        ^ words[index - 2] >>> 10;
                words[index] = words[index - 16] + first + words[index - 7] + second;
            }
            compress(hash, words);
        }

        byte[] result = new byte[32];
        for (int index = 0; index < hash.length; index++) {
            result[index * 4] = (byte) (hash[index] >>> 24);
            result[index * 4 + 1] = (byte) (hash[index] >>> 16);
            result[index * 4 + 2] = (byte) (hash[index] >>> 8);
            result[index * 4 + 3] = (byte) hash[index];
        }
        return result;
    }

    private static void compress(int[] hash, int[] words) {
        int a = hash[0];
        int b = hash[1];
        int c = hash[2];
        int d = hash[3];
        int e = hash[4];
        int f = hash[5];
        int g = hash[6];
        int h = hash[7];
        for (int index = 0; index < words.length; index++) {
            int sumOne = Integer.rotateRight(e, 6) ^ Integer.rotateRight(e, 11) ^ Integer.rotateRight(e, 25);
            int choice = e & f ^ ~e & g;
            int first = h + sumOne + choice + CONSTANTS[index] + words[index];
            int sumZero = Integer.rotateRight(a, 2) ^ Integer.rotateRight(a, 13) ^ Integer.rotateRight(a, 22);
            int majority = a & b ^ a & c ^ b & c;
            int second = sumZero + majority;
            h = g;
            g = f;
            f = e;
            e = d + first;
            d = c;
            c = b;
            b = a;
            a = first + second;
        }
        hash[0] += a;
        hash[1] += b;
        hash[2] += c;
        hash[3] += d;
        hash[4] += e;
        hash[5] += f;
        hash[6] += g;
        hash[7] += h;
    }
}
