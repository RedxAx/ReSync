package restudio.resync.compression;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.ConcurrentLinkedQueue;
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
        Inflater decompressor = decompressors.poll();
        if (decompressor == null) {
            decompressor = new Inflater();
        } else {
            decompressor.reset();
        }

        try {
            decompressor.setInput(compressed);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(compressed.length * 2);
            byte[] buffer = new byte[8192];
            while (!decompressor.finished()) {
                int count = decompressor.inflate(buffer);
                if (count == 0 && decompressor.needsInput()) {
                    break;
                }
                baos.write(buffer, 0, count);
            }
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Decompression failed", e);
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
