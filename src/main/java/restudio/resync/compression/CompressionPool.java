package restudio.resync.compression;

import java.io.ByteArrayOutputStream;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public class CompressionPool {
    private final ConcurrentLinkedQueue<Deflater> compressors;
    private final ConcurrentLinkedQueue<Inflater> decompressors;
    private final int compressionLevel;
    private final int maxPoolSize;

    public CompressionPool(int compressionLevel, int maxPoolSize) {
        this.compressionLevel = compressionLevel;
        this.maxPoolSize = maxPoolSize;
        this.compressors = new ConcurrentLinkedQueue<>();
        this.decompressors = new ConcurrentLinkedQueue<>();
    }

    public byte[] compress(byte[] data) {
        Deflater compressor = compressors.poll();
        if (compressor == null) {
            compressor = new Deflater(compressionLevel);
        } else {
            compressor.reset();
        }

        try {
            compressor.setInput(data);
            compressor.finish();

            ByteArrayOutputStream baos = new ByteArrayOutputStream(data.length);
            byte[] buffer = new byte[8192];
            while (!compressor.finished()) {
                int count = compressor.deflate(buffer);
                baos.write(buffer, 0, count);
            }
            return baos.toByteArray();
        } finally {
            if (compressors.size() < maxPoolSize) {
                compressors.offer(compressor);
            } else {
                compressor.end();
            }
        }
    }

    public byte[] decompress(byte[] compressed) {
        return decompress(compressed, Integer.MAX_VALUE - 8);
    }

    public byte[] decompress(byte[] compressed, int maxOutputBytes) {
        Objects.requireNonNull(compressed, "Compressed payload is required");
        if (maxOutputBytes < 0) {
            throw new IllegalArgumentException("Maximum decompressed payload size cannot be negative");
        }
        Inflater decompressor = decompressors.poll();
        if (decompressor == null) {
            decompressor = new Inflater();
        } else {
            decompressor.reset();
        }

        try {
            decompressor.setInput(compressed);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.min(maxOutputBytes, 8192));
            byte[] buffer = new byte[8192];
            int total = 0;
            while (!decompressor.finished()) {
                int available = maxOutputBytes - total;
                int count = decompressor.inflate(buffer, 0, Math.min(buffer.length, available == 0 ? 1 : available));
                if (count > available) {
                    throw new IllegalArgumentException("Decompressed payload too large");
                }
                if (count > 0) {
                    baos.write(buffer, 0, count);
                    total += count;
                    continue;
                }
                if (decompressor.finished()) {
                    continue;
                }
                if (decompressor.needsDictionary()) {
                    throw new IllegalArgumentException("Compressed payload requires a dictionary");
                }
                if (decompressor.needsInput()) {
                    throw new IllegalArgumentException("Compressed payload ended before the stream completed");
                }
                throw new IllegalArgumentException("Compressed payload could not make progress");
            }
            if (decompressor.getRemaining() != 0) {
                throw new IllegalArgumentException("Compressed payload contains trailing data");
            }
            return baos.toByteArray();
        } catch (DataFormatException exception) {
            throw new IllegalArgumentException("Compressed payload is malformed", exception);
        } finally {
            if (decompressors.size() < maxPoolSize) {
                decompressors.offer(decompressor);
            } else {
                decompressor.end();
            }
        }
    }

    public void close() {
        while (!compressors.isEmpty()) {
            Deflater compressor = compressors.poll();
            if (compressor != null) {
                compressor.end();
            }
        }

        while (!decompressors.isEmpty()) {
            Inflater decompressor = decompressors.poll();
            if (decompressor != null) {
                decompressor.end();
            }
        }
    }
}
