package restudio.resync.contract.canonical;

public final class CanonicalDigests {
    private static final int[] SHA256_K = {
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
    };
    private static final int[] SHA1_K = {0x5a827999, 0x6ed9eba1, 0x8f1bbcdc, 0xca62c1d6};
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private CanonicalDigests() {
    }

    public static byte[] sha256(byte[] value) {
        return sha256Parts(value);
    }

    public static byte[] sha256(byte[]... parts) {
        return sha256Parts(join(parts));
    }

    public static byte[] sha1(byte[] value) {
        int[] state = {0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476, 0xc3d2e1f0};
        int[] words = new int[80];
        byte[] block = new byte[64];
        int offset = 0;
        for (int index = 0; index < value.length; index++) {
            block[offset++] = value[index];
            if (offset == 64) {
                sha1Block(state, words, block);
                offset = 0;
            }
        }
        long bitLength = (long) value.length * 8;
        block[offset++] = (byte) 0x80;
        if (offset > 56) {
            while (offset < 64) {
                block[offset++] = 0;
            }
            sha1Block(state, words, block);
            offset = 0;
        }
        while (offset < 56) {
            block[offset++] = 0;
        }
        putLong(block, 56, bitLength);
        sha1Block(state, words, block);
        byte[] digest = new byte[20];
        for (int index = 0; index < 5; index++) {
            putInt(digest, index * 4, state[index]);
        }
        return digest;
    }

    public static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            out[index * 2] = HEX[value >>> 4];
            out[index * 2 + 1] = HEX[value & 0x0f];
        }
        return new String(out);
    }

    public static boolean equal(byte[] left, byte[] right) {
        if (left == null || right == null || left.length != right.length) {
            return false;
        }
        int diff = 0;
        for (int index = 0; index < left.length; index++) {
            diff |= left[index] ^ right[index];
        }
        return diff == 0;
    }

    private static byte[] sha256Parts(byte[] value) {
        int[] state = {
            0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
        };
        int[] words = new int[64];
        byte[] block = new byte[64];
        int offset = 0;
        for (int index = 0; index < value.length; index++) {
            block[offset++] = value[index];
            if (offset == 64) {
                sha256Block(state, words, block);
                offset = 0;
            }
        }
        long bitLength = (long) value.length * 8;
        block[offset++] = (byte) 0x80;
        if (offset > 56) {
            while (offset < 64) {
                block[offset++] = 0;
            }
            sha256Block(state, words, block);
            offset = 0;
        }
        while (offset < 56) {
            block[offset++] = 0;
        }
        putLong(block, 56, bitLength);
        sha256Block(state, words, block);
        byte[] digest = new byte[32];
        for (int index = 0; index < 8; index++) {
            putInt(digest, index * 4, state[index]);
        }
        return digest;
    }

    private static void sha256Block(int[] state, int[] words, byte[] block) {
        for (int index = 0; index < 16; index++) {
            words[index] = getInt(block, index * 4);
        }
        for (int index = 16; index < 64; index++) {
            int s0 = rotate(words[index - 15], 7) ^ rotate(words[index - 15], 18) ^ (words[index - 15] >>> 3);
            int s1 = rotate(words[index - 2], 17) ^ rotate(words[index - 2], 19) ^ (words[index - 2] >>> 10);
            words[index] = words[index - 16] + s0 + words[index - 7] + s1;
        }
        int a = state[0];
        int b = state[1];
        int c = state[2];
        int d = state[3];
        int e = state[4];
        int f = state[5];
        int g = state[6];
        int h = state[7];
        for (int index = 0; index < 64; index++) {
            int s1 = rotate(e, 6) ^ rotate(e, 11) ^ rotate(e, 25);
            int ch = (e & f) ^ ((~e) & g);
            int temp1 = h + s1 + ch + SHA256_K[index] + words[index];
            int s0 = rotate(a, 2) ^ rotate(a, 13) ^ rotate(a, 22);
            int maj = (a & b) ^ (a & c) ^ (b & c);
            int temp2 = s0 + maj;
            h = g;
            g = f;
            f = e;
            e = d + temp1;
            d = c;
            c = b;
            b = a;
            a = temp1 + temp2;
        }
        state[0] += a;
        state[1] += b;
        state[2] += c;
        state[3] += d;
        state[4] += e;
        state[5] += f;
        state[6] += g;
        state[7] += h;
    }

    private static void sha1Block(int[] state, int[] words, byte[] block) {
        for (int index = 0; index < 16; index++) {
            words[index] = getInt(block, index * 4);
        }
        for (int index = 16; index < 80; index++) {
            words[index] = rotate(words[index - 3] ^ words[index - 8] ^ words[index - 14] ^ words[index - 16], 1);
        }
        int a = state[0];
        int b = state[1];
        int c = state[2];
        int d = state[3];
        int e = state[4];
        for (int index = 0; index < 80; index++) {
            int f;
            int k;
            if (index < 20) {
                f = (b & c) | ((~b) & d);
                k = SHA1_K[0];
            } else if (index < 40) {
                f = b ^ c ^ d;
                k = SHA1_K[1];
            } else if (index < 60) {
                f = (b & c) | (b & d) | (c & d);
                k = SHA1_K[2];
            } else {
                f = b ^ c ^ d;
                k = SHA1_K[3];
            }
            int temp = rotate(a, 5) + f + e + k + words[index];
            e = d;
            d = c;
            c = rotate(b, 30);
            b = a;
            a = temp;
        }
        state[0] += a;
        state[1] += b;
        state[2] += c;
        state[3] += d;
        state[4] += e;
    }

    private static byte[] join(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] joined = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, joined, offset, part.length);
            offset += part.length;
        }
        return joined;
    }

    private static int rotate(int value, int distance) {
        return (value >>> distance) | (value << (32 - distance));
    }

    private static int getInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24)
            | ((bytes[offset + 1] & 0xff) << 16)
            | ((bytes[offset + 2] & 0xff) << 8)
            | (bytes[offset + 3] & 0xff);
    }

    private static void putInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 24);
        bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8);
        bytes[offset + 3] = (byte) value;
    }

    private static void putLong(byte[] bytes, int offset, long value) {
        putInt(bytes, offset, (int) (value >>> 32));
        putInt(bytes, offset + 4, (int) value);
    }

    public static byte[] md5(byte[] value) {
        int[] state = {0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476};
        byte[] block = new byte[64];
        int offset = 0;
        for (int index = 0; index < value.length; index++) {
            block[offset++] = value[index];
            if (offset == 64) {
                md5Block(state, block);
                offset = 0;
            }
        }
        long bitLength = (long) value.length * 8;
        block[offset++] = (byte) 0x80;
        if (offset > 56) {
            while (offset < 64) {
                block[offset++] = 0;
            }
            md5Block(state, block);
            offset = 0;
        }
        while (offset < 56) {
            block[offset++] = 0;
        }
        block[56] = (byte) bitLength;
        block[57] = (byte) (bitLength >>> 8);
        block[58] = (byte) (bitLength >>> 16);
        block[59] = (byte) (bitLength >>> 24);
        block[60] = (byte) (bitLength >>> 32);
        block[61] = (byte) (bitLength >>> 40);
        block[62] = (byte) (bitLength >>> 48);
        block[63] = (byte) (bitLength >>> 56);
        md5Block(state, block);
        byte[] digest = new byte[16];
        writeLittleInt(digest, 0, state[0]);
        writeLittleInt(digest, 4, state[1]);
        writeLittleInt(digest, 8, state[2]);
        writeLittleInt(digest, 12, state[3]);
        return digest;
    }

    private static void md5Block(int[] state, byte[] block) {
        int a = state[0];
        int b = state[1];
        int c = state[2];
        int d = state[3];
        int[] words = new int[16];
        for (int index = 0; index < 16; index++) {
            int offset = index * 4;
            words[index] = (block[offset] & 0xff)
                | ((block[offset + 1] & 0xff) << 8)
                | ((block[offset + 2] & 0xff) << 16)
                | ((block[offset + 3] & 0xff) << 24);
        }
        a = md5FF(a, b, c, d, words[0], 7, 0xd76aa478);
        d = md5FF(d, a, b, c, words[1], 12, 0xe8c7b756);
        c = md5FF(c, d, a, b, words[2], 17, 0x242070db);
        b = md5FF(b, c, d, a, words[3], 22, 0xc1bdceee);
        a = md5FF(a, b, c, d, words[4], 7, 0xf57c0faf);
        d = md5FF(d, a, b, c, words[5], 12, 0x4787c62a);
        c = md5FF(c, d, a, b, words[6], 17, 0xa8304613);
        b = md5FF(b, c, d, a, words[7], 22, 0xfd469501);
        a = md5FF(a, b, c, d, words[8], 7, 0x698098d8);
        d = md5FF(d, a, b, c, words[9], 12, 0x8b44f7af);
        c = md5FF(c, d, a, b, words[10], 17, 0xffff5bb1);
        b = md5FF(b, c, d, a, words[11], 22, 0x895cd7be);
        a = md5FF(a, b, c, d, words[12], 7, 0x6b901122);
        d = md5FF(d, a, b, c, words[13], 12, 0xfd987193);
        c = md5FF(c, d, a, b, words[14], 17, 0xa679438e);
        b = md5FF(b, c, d, a, words[15], 22, 0x49b40821);
        a = md5GG(a, b, c, d, words[1], 5, 0xf61e2562);
        d = md5GG(d, a, b, c, words[6], 9, 0xc040b340);
        c = md5GG(c, d, a, b, words[11], 14, 0x265e5a51);
        b = md5GG(b, c, d, a, words[0], 20, 0xe9b6c7aa);
        a = md5GG(a, b, c, d, words[5], 5, 0xd62f105d);
        d = md5GG(d, a, b, c, words[10], 9, 0x02441453);
        c = md5GG(c, d, a, b, words[15], 14, 0xd8a1e681);
        b = md5GG(b, c, d, a, words[4], 20, 0xe7d3fbc8);
        a = md5GG(a, b, c, d, words[9], 5, 0x21e1cde6);
        d = md5GG(d, a, b, c, words[14], 9, 0xc33707d6);
        c = md5GG(c, d, a, b, words[3], 14, 0xf4d50d87);
        b = md5GG(b, c, d, a, words[8], 20, 0x455a14ed);
        a = md5GG(a, b, c, d, words[13], 5, 0xa9e3e905);
        d = md5GG(d, a, b, c, words[2], 9, 0xfcefa3f8);
        c = md5GG(c, d, a, b, words[7], 14, 0x676f02d9);
        b = md5GG(b, c, d, a, words[12], 20, 0x8d2a4c8a);
        a = md5HH(a, b, c, d, words[5], 4, 0xfffa3942);
        d = md5HH(d, a, b, c, words[8], 11, 0x8771f681);
        c = md5HH(c, d, a, b, words[11], 16, 0x6d9d6122);
        b = md5HH(b, c, d, a, words[14], 23, 0xfde5380c);
        a = md5HH(a, b, c, d, words[1], 4, 0xa4beea44);
        d = md5HH(d, a, b, c, words[4], 11, 0x4bdecfa9);
        c = md5HH(c, d, a, b, words[7], 16, 0xf6bb4b60);
        b = md5HH(b, c, d, a, words[10], 23, 0xbebfbc70);
        a = md5HH(a, b, c, d, words[13], 4, 0x289b7ec6);
        d = md5HH(d, a, b, c, words[0], 11, 0xeaa127fa);
        c = md5HH(c, d, a, b, words[3], 16, 0xd4ef3085);
        b = md5HH(b, c, d, a, words[6], 23, 0x04881d05);
        a = md5HH(a, b, c, d, words[9], 4, 0xd9d4d039);
        d = md5HH(d, a, b, c, words[12], 11, 0xe6db99e5);
        c = md5HH(c, d, a, b, words[15], 16, 0x1fa27cf8);
        b = md5HH(b, c, d, a, words[2], 23, 0xc4ac5665);
        a = md5II(a, b, c, d, words[0], 6, 0xf4292244);
        d = md5II(d, a, b, c, words[7], 10, 0x432aff97);
        c = md5II(c, d, a, b, words[14], 15, 0xab9423a7);
        b = md5II(b, c, d, a, words[5], 21, 0xfc93a039);
        a = md5II(a, b, c, d, words[12], 6, 0x655b59c3);
        d = md5II(d, a, b, c, words[3], 10, 0x8f0ccc92);
        c = md5II(c, d, a, b, words[10], 15, 0xffeff47d);
        b = md5II(b, c, d, a, words[1], 21, 0x85845dd1);
        a = md5II(a, b, c, d, words[8], 6, 0x6fa87e4f);
        d = md5II(d, a, b, c, words[15], 10, 0xfe2ce6e0);
        c = md5II(c, d, a, b, words[6], 15, 0xa3014314);
        b = md5II(b, c, d, a, words[13], 21, 0x4e0811a1);
        a = md5II(a, b, c, d, words[4], 6, 0xf7537e82);
        d = md5II(d, a, b, c, words[11], 10, 0xbd3af235);
        c = md5II(c, d, a, b, words[2], 15, 0x2ad7d2bb);
        b = md5II(b, c, d, a, words[9], 21, 0xeb86d391);
        state[0] += a;
        state[1] += b;
        state[2] += c;
        state[3] += d;
    }

    private static int md5FF(int a, int b, int c, int d, int x, int s, int t) {
        return md5Rotate(a + ((b & c) | ((~b) & d)) + x + t, s) + b;
    }

    private static int md5GG(int a, int b, int c, int d, int x, int s, int t) {
        return md5Rotate(a + ((b & d) | (c & (~d))) + x + t, s) + b;
    }

    private static int md5HH(int a, int b, int c, int d, int x, int s, int t) {
        return md5Rotate(a + (b ^ c ^ d) + x + t, s) + b;
    }

    private static int md5II(int a, int b, int c, int d, int x, int s, int t) {
        return md5Rotate(a + (c ^ (b | (~d))) + x + t, s) + b;
    }

    private static int md5Rotate(int value, int distance) {
        return (value << distance) | (value >>> (32 - distance));
    }

    private static void writeLittleInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) (value >>> 16);
        bytes[offset + 3] = (byte) (value >>> 24);
    }
}
