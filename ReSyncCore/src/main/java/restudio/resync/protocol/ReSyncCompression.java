package restudio.resync.protocol;

import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public interface ReSyncCompression {
    String algorithm();

    byte[] compress(byte[] payload);

    byte[] decompress(byte[] payload, int maximumOutputBytes);

    static ReSyncCompression deflate() {
        return DeflateHolder.DEFAULT;
    }

    static ReSyncCompression deflate(int compressionLevel) {
        if (compressionLevel == Deflater.DEFAULT_COMPRESSION) {
            return deflate();
        }
        return new Deflate(compressionLevel);
    }

    final class Deflate implements ReSyncCompression {
        private static final int BUFFER_BYTES = 8192;

        private final int compressionLevel;

        private Deflate(int compressionLevel) {
            if (compressionLevel < Deflater.DEFAULT_COMPRESSION || compressionLevel > Deflater.BEST_COMPRESSION) {
                throw new IllegalArgumentException("Invalid ReSync compression level");
            }
            this.compressionLevel = compressionLevel;
        }

        @Override
        public String algorithm() {
            return "deflate";
        }

        @Override
        public byte[] compress(byte[] payload) {
            if (payload == null) {
                throw failure(ReSyncProtocolException.Reason.COMPRESSION_FAILED, "ReSync compression payload is required");
            }
            if (payload.length > ReSyncProtocolContract.MAX_DECOMPRESSED_PAYLOAD_BYTES) {
                throw failure(ReSyncProtocolException.Reason.PAYLOAD_TOO_LARGE,
                    "ReSync compression payload exceeds the protocol limit");
            }
            Deflater deflater = new Deflater(compressionLevel);
            try {
                deflater.setInput(payload);
                deflater.finish();
                ByteArrayOutputStream output = new ByteArrayOutputStream(payload.length);
                byte[] buffer = new byte[BUFFER_BYTES];
                while (!deflater.finished()) {
                    int count = deflater.deflate(buffer);
                    if (count == 0) {
                        throw failure(ReSyncProtocolException.Reason.COMPRESSION_FAILED,
                            "ReSync compression made no progress");
                    }
                    output.write(buffer, 0, count);
                }
                return output.toByteArray();
            } finally {
                deflater.end();
            }
        }

        @Override
        public byte[] decompress(byte[] payload, int maximumOutputBytes) {
            if (payload == null || payload.length == 0) {
                throw failure(ReSyncProtocolException.Reason.DECOMPRESSION_FAILED,
                    "ReSync compressed payload is required");
            }
            if (maximumOutputBytes < 0 || maximumOutputBytes > ReSyncProtocolContract.MAX_DECOMPRESSED_PAYLOAD_BYTES) {
                throw failure(ReSyncProtocolException.Reason.INVALID_CONFIGURATION,
                    "ReSync decompression limit is invalid");
            }
            Inflater inflater = new Inflater();
            try {
                inflater.setInput(payload);
                int initialCapacity = (int) Math.min(maximumOutputBytes,
                    Math.max(32L, Math.min((long) payload.length * 2, Integer.MAX_VALUE)));
                ByteArrayOutputStream output = new ByteArrayOutputStream(initialCapacity);
                byte[] buffer = new byte[BUFFER_BYTES];
                while (!inflater.finished()) {
                    int count = inflate(inflater, buffer);
                    if (count > 0) {
                        if ((long) output.size() + count > maximumOutputBytes) {
                            throw failure(ReSyncProtocolException.Reason.PAYLOAD_TOO_LARGE,
                                "Decompressed ReSync payload exceeds the configured limit");
                        }
                        output.write(buffer, 0, count);
                        continue;
                    }
                    if (inflater.needsInput()) {
                        throw failure(ReSyncProtocolException.Reason.DECOMPRESSION_FAILED,
                            "ReSync compressed payload is incomplete");
                    }
                    if (inflater.needsDictionary()) {
                        throw failure(ReSyncProtocolException.Reason.DECOMPRESSION_FAILED,
                            "ReSync compressed payload requires a dictionary");
                    }
                    throw failure(ReSyncProtocolException.Reason.DECOMPRESSION_FAILED,
                        "ReSync decompression made no progress");
                }
                if (inflater.getRemaining() != 0) {
                    throw failure(ReSyncProtocolException.Reason.DECOMPRESSION_FAILED,
                        "ReSync compressed payload contains trailing bytes");
                }
                return output.toByteArray();
            } finally {
                inflater.end();
            }
        }

        private int inflate(Inflater inflater, byte[] buffer) {
            try {
                return inflater.inflate(buffer);
            } catch (DataFormatException exception) {
                throw new ReSyncProtocolException(ReSyncProtocolException.Reason.DECOMPRESSION_FAILED,
                    "ReSync compressed payload is malformed", exception);
            }
        }

        private ReSyncProtocolException failure(ReSyncProtocolException.Reason reason, String message) {
            return new ReSyncProtocolException(reason, message);
        }
    }

    final class DeflateHolder {
        private static final ReSyncCompression DEFAULT = new Deflate(Deflater.DEFAULT_COMPRESSION);

        private DeflateHolder() {
        }
    }
}
