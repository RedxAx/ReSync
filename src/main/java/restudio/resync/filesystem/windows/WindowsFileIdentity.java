package restudio.resync.filesystem.windows;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

public final class WindowsFileIdentity {
    private static final Backend SYSTEM_BACKEND = new WindowsNative();

    private final Backend backend;

    public WindowsFileIdentity() {
        this(SYSTEM_BACKEND);
    }

    public WindowsFileIdentity(Backend backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    public static WindowsFileIdentity system() {
        return new WindowsFileIdentity(SYSTEM_BACKEND);
    }

    public Observation observe(Path path) throws IOException {
        return backend.observe(requirePath(path));
    }

    public void flush(Path path) throws IOException {
        backend.flush(requirePath(path));
    }

    public interface Backend {
        Observation observe(Path path) throws IOException;

        void flush(Path path) throws IOException;
    }

    public record Observation(Path normalizedPath, FileIdInfo fileIdInfo, boolean directory, boolean regularFile,
                              boolean other, boolean reparsePoint, int reparseTag) {
        public Observation {
            normalizedPath = requirePath(normalizedPath);
            Objects.requireNonNull(fileIdInfo, "fileIdInfo");
            int kinds = (directory ? 1 : 0) + (regularFile ? 1 : 0) + (other ? 1 : 0);
            if (kinds != 1) {
                throw new IllegalArgumentException("Exactly one Windows file kind must be set");
            }
            if (reparsePoint && reparseTag == 0) {
                throw new IllegalArgumentException("A reparse point must have a tag");
            }
            if (!reparsePoint && reparseTag != 0) {
                throw new IllegalArgumentException("A non-reparse file cannot have a reparse tag");
            }
        }

        public Path path() {
            return normalizedPath;
        }

        public FileIdInfo identity() {
            return fileIdInfo;
        }

        public FileIdInfo fileId() {
            return fileIdInfo;
        }

        public long volumeSerialNumber() {
            return fileIdInfo.volumeSerialNumber();
        }

        public boolean isDirectory() {
            return directory;
        }

        public boolean isRegularFile() {
            return regularFile;
        }

        public boolean isOther() {
            return other;
        }

        public boolean isReparsePoint() {
            return reparsePoint;
        }

        public long unsignedReparseTag() {
            return Integer.toUnsignedLong(reparseTag);
        }
    }

    public record FileIdInfo(long volumeSerialNumber, byte[] identifier, boolean fallback) {
        public FileIdInfo {
            if (volumeSerialNumber == 0) {
                throw new IllegalArgumentException("Volume serial number is missing");
            }
            if (identifier == null || identifier.length != 16) {
                throw new IllegalArgumentException("File ID must contain exactly 128 bits");
            }
            identifier = identifier.clone();
            if (Arrays.equals(identifier, new byte[16])) {
                throw new IllegalArgumentException("File ID is missing");
            }
        }

        @Override
        public byte[] identifier() {
            return identifier.clone();
        }

        public byte[] fileId128() {
            return identifier();
        }

        public byte[] bytes() {
            return identifier();
        }

        public long high() {
            return readLong(0);
        }

        public long low() {
            return readLong(8);
        }

        public long volumeSerial() {
            return volumeSerialNumber;
        }

        public boolean isFallback() {
            return fallback;
        }

        private long readLong(int offset) {
            long value = 0;
            for (int index = offset; index < offset + Long.BYTES; index++) {
                value = (value << Byte.SIZE) | Byte.toUnsignedLong(identifier[index]);
            }
            return value;
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) {
                return true;
            }
            if (!(value instanceof FileIdInfo other)) {
                return false;
            }
            return volumeSerialNumber == other.volumeSerialNumber
                && fallback == other.fallback
                && Arrays.equals(identifier, other.identifier);
        }

        @Override
        public int hashCode() {
            int result = Long.hashCode(volumeSerialNumber);
            result = 31 * result + Arrays.hashCode(identifier);
            return 31 * result + Boolean.hashCode(fallback);
        }
    }

    private static Path requirePath(Path path) {
        Objects.requireNonNull(path, "path");
        return path.toAbsolutePath().normalize();
    }
}
