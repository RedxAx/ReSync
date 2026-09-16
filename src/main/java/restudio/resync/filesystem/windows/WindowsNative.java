package restudio.resync.filesystem.windows;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Locale;

final class WindowsNative implements WindowsFileIdentity.Backend {
    private static final int GENERIC_READ = 0x80000000;
    private static final int GENERIC_WRITE = 0x40000000;
    private static final int FILE_SHARE_READ = 0x00000001;
    private static final int FILE_SHARE_WRITE = 0x00000002;
    private static final int FILE_SHARE_DELETE = 0x00000004;
    private static final int OPEN_EXISTING = 3;
    private static final int FILE_FLAG_WRITE_THROUGH = 0x80000000;
    private static final int FILE_FLAG_BACKUP_SEMANTICS = 0x02000000;
    private static final int FILE_FLAG_OPEN_REPARSE_POINT = 0x00200000;
    private static final int FILE_ATTRIBUTE_DIRECTORY = 0x00000010;
    private static final int FILE_ATTRIBUTE_DEVICE = 0x00000040;
    private static final int FILE_ATTRIBUTE_REPARSE_POINT = 0x00000400;
    private static final int FILE_ID_INFO_CLASS = 18;
    private static final int FILE_ATTRIBUTE_TAG_INFO_CLASS = 9;
    private static final int FILE_NAME_NORMALIZED = 0;
    private static final int VOLUME_NAME_DOS = 0;
    private static final int ERROR_INVALID_FUNCTION = 1;
    private static final int ERROR_NOT_SUPPORTED = 50;
    private static final int ERROR_INVALID_PARAMETER = 87;
    private static final int ERROR_CALL_NOT_IMPLEMENTED = 120;
    private static final int ERROR_INVALID_LEVEL = 124;
    private static final int MAX_FINAL_PATH_LENGTH = 32768;
    private static final int SHARE_MODE = FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE;
    private static final Object NATIVE_LOCK = new Object();

    private static volatile Kernel32 nativeApi;

    @Override
    public WindowsFileIdentity.Observation observe(Path path) throws IOException {
        requireWindows(path);
        Kernel32 api = nativeApi();
        try (NativeHandle handle = open(api, path, 0, FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT)) {
            WindowsFileIdentity.FileIdInfo identity = readIdentity(api, handle.value(), path);
            AttributeTag tag = readAttributeTag(api, handle.value(), path);
            Path normalized = readFinalPath(api, handle.value(), path);
            boolean directory = (tag.attributes() & FILE_ATTRIBUTE_DIRECTORY) != 0;
            boolean reparse = (tag.attributes() & FILE_ATTRIBUTE_REPARSE_POINT) != 0 || tag.reparseTag() != 0;
            if (reparse && tag.reparseTag() == 0) {
                throw new IOException("Windows reparse tag is missing for " + path);
            }
            boolean regular = !directory && !reparse && (tag.attributes() & FILE_ATTRIBUTE_DEVICE) == 0;
            boolean other = !directory && !regular;
            return new WindowsFileIdentity.Observation(normalized, identity, directory, regular, other, reparse,
                reparse ? tag.reparseTag() : 0);
        }
    }

    @Override
    public void flush(Path path) throws IOException {
        requireWindows(path);
        Kernel32 api = nativeApi();
        try (NativeHandle handle = open(api, path, GENERIC_READ | GENERIC_WRITE,
            FILE_FLAG_WRITE_THROUGH | FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT)) {
            if (!api.FlushFileBuffers(handle.value())) {
                throw failure(api, "FlushFileBuffers", path);
            }
        }
    }

    private static NativeHandle open(Kernel32 api, Path path, int access, int flags) throws IOException {
        Pointer handle = api.CreateFileW(new WString(path.toString()), access, SHARE_MODE, null, OPEN_EXISTING, flags, null);
        if (isInvalidHandle(handle)) {
            throw failure(api, "CreateFileW", path);
        }
        return new NativeHandle(api, handle, path);
    }

    private static WindowsFileIdentity.FileIdInfo readIdentity(Kernel32 api, Pointer handle, Path path) throws IOException {
        FileIdNative info = new FileIdNative();
        info.write();
        if (api.GetFileInformationByHandleEx(handle, FILE_ID_INFO_CLASS, info.getPointer(), info.size())) {
            info.read();
            if (info.volumeSerialNumber == 0 || allZero(info.identifier)) {
                throw new IOException("Windows file identity is missing for " + path);
            }
            return new WindowsFileIdentity.FileIdInfo(info.volumeSerialNumber, info.identifier, false);
        }
        int error = api.GetLastError();
        if (!isIdentityUnsupported(error)) {
            throw failure(api, "GetFileInformationByHandleEx(FileIdInfo)", path, error);
        }
        ByHandleNative fallback = new ByHandleNative();
        fallback.write();
        if (!api.GetFileInformationByHandle(handle, fallback)) {
            throw failure(api, "GetFileInformationByHandle", path);
        }
        fallback.read();
        long fileIndex = Integer.toUnsignedLong(fallback.fileIndexHigh) << Integer.SIZE
            | Integer.toUnsignedLong(fallback.fileIndexLow);
        if (fallback.volumeSerialNumber == 0 || fileIndex == 0 || fallback.numberOfLinks <= 0) {
            throw new IOException("Windows fallback file identity is not unique for " + path);
        }
        byte[] identifier = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            .putLong(0L)
            .putLong(fileIndex)
            .array();
        return new WindowsFileIdentity.FileIdInfo(Integer.toUnsignedLong(fallback.volumeSerialNumber), identifier, true);
    }

    private static AttributeTag readAttributeTag(Kernel32 api, Pointer handle, Path path) throws IOException {
        AttributeTagNative nativeTag = new AttributeTagNative();
        nativeTag.write();
        if (!api.GetFileInformationByHandleEx(handle, FILE_ATTRIBUTE_TAG_INFO_CLASS, nativeTag.getPointer(), nativeTag.size())) {
            throw failure(api, "GetFileInformationByHandleEx(FileAttributeTagInfo)", path);
        }
        nativeTag.read();
        return new AttributeTag(nativeTag.fileAttributes, nativeTag.reparseTag);
    }

    private static Path readFinalPath(Kernel32 api, Pointer handle, Path original) throws IOException {
        int capacity = 512;
        while (capacity <= MAX_FINAL_PATH_LENGTH) {
            try (Memory buffer = new Memory((long) capacity * Native.WCHAR_SIZE)) {
                int length = api.GetFinalPathNameByHandleW(handle, buffer, capacity, FILE_NAME_NORMALIZED | VOLUME_NAME_DOS);
                if (length == 0) {
                    throw failure(api, "GetFinalPathNameByHandleW", original);
                }
                if (length < capacity) {
                    String value = buffer.getWideString(0);
                    return normalizeFinalPath(value, original);
                }
                capacity = Math.max(capacity + 1, length + 1);
            }
        }
        throw new IOException("Windows final path is too long for " + original);
    }

    private static Path normalizeFinalPath(String value, Path original) throws IOException {
        if (value == null || value.isEmpty()) {
            throw new IOException("Windows final path is missing for " + original);
        }
        String normalized = value;
        if (normalized.startsWith("\\\\?\\UNC\\")) {
            normalized = "\\\\" + normalized.substring("\\\\?\\UNC\\".length());
        } else if (normalized.startsWith("\\\\?\\")) {
            normalized = normalized.substring("\\\\?\\".length());
        }
        try {
            Path result = Path.of(normalized).toAbsolutePath().normalize();
            if (!result.isAbsolute()) {
                throw new IOException("Windows final path is not absolute for " + original);
            }
            return result;
        } catch (RuntimeException exception) {
            throw new IOException("Windows final path is invalid for " + original, exception);
        }
    }

    private static Kernel32 nativeApi() throws IOException {
        Kernel32 current = nativeApi;
        if (current != null) {
            return current;
        }
        synchronized (NATIVE_LOCK) {
            current = nativeApi;
            if (current == null) {
                try {
                    current = Native.load("kernel32", Kernel32.class);
                } catch (LinkageError | RuntimeException exception) {
                    throw new IOException("Windows kernel32 is unavailable", exception);
                }
                nativeApi = current;
            }
            return current;
        }
    }

    private static void requireWindows(Path path) throws IOException {
        String operatingSystem = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!operatingSystem.contains("win")) {
            throw new IOException("Windows file identity is unsupported on this operating system: " + path);
        }
    }

    private static boolean isInvalidHandle(Pointer handle) {
        return handle == null || Pointer.nativeValue(handle) == -1L;
    }

    private static boolean isIdentityUnsupported(int error) {
        return error == ERROR_INVALID_FUNCTION || error == ERROR_NOT_SUPPORTED || error == ERROR_INVALID_PARAMETER
            || error == ERROR_CALL_NOT_IMPLEMENTED || error == ERROR_INVALID_LEVEL;
    }

    private static boolean allZero(byte[] value) {
        for (byte current : value) {
            if (current != 0) {
                return false;
            }
        }
        return true;
    }

    private static IOException failure(Kernel32 api, String operation, Path path) {
        return failure(api, operation, path, api.GetLastError());
    }

    private static IOException failure(Kernel32 api, String operation, Path path, int error) {
        return new IOException(operation + " failed for " + path + " (Windows error " + Integer.toUnsignedString(error) + ")");
    }

    private record AttributeTag(int attributes, int reparseTag) {
    }

    private static final class NativeHandle implements AutoCloseable {
        private final Kernel32 api;
        private final Pointer value;
        private final Path path;
        private boolean closed;

        private NativeHandle(Kernel32 api, Pointer value, Path path) {
            this.api = api;
            this.value = value;
            this.path = path;
        }

        private Pointer value() {
            return value;
        }

        @Override
        public void close() throws IOException {
            if (!closed) {
                closed = true;
                if (!api.CloseHandle(value)) {
                    throw failure(api, "CloseHandle", path);
                }
            }
        }
    }

    private interface Kernel32 extends StdCallLibrary {
        Pointer CreateFileW(WString fileName, int desiredAccess, int shareMode, Pointer securityAttributes,
                            int creationDisposition, int flagsAndAttributes, Pointer templateFile);

        boolean CloseHandle(Pointer handle);

        boolean FlushFileBuffers(Pointer handle);

        boolean GetFileInformationByHandle(Pointer handle, ByHandleNative information);

        boolean GetFileInformationByHandleEx(Pointer handle, int informationClass, Pointer information,
                                             int bufferSize);

        int GetFinalPathNameByHandleW(Pointer handle, Pointer path, int pathLength, int flags);

        int GetLastError();
    }

    @Structure.FieldOrder({"volumeSerialNumber", "identifier"})
    public static final class FileIdNative extends Structure {
        public long volumeSerialNumber;
        public byte[] identifier = new byte[16];

        public FileIdNative() {
        }
    }

    @Structure.FieldOrder({"fileAttributes", "reparseTag"})
    public static final class AttributeTagNative extends Structure {
        public int fileAttributes;
        public int reparseTag;

        public AttributeTagNative() {
        }
    }

    @Structure.FieldOrder({"fileAttributes", "creationTimeLow", "creationTimeHigh", "lastAccessTimeLow",
        "lastAccessTimeHigh", "lastWriteTimeLow", "lastWriteTimeHigh", "volumeSerialNumber", "fileSizeHigh",
        "fileSizeLow", "numberOfLinks", "fileIndexHigh", "fileIndexLow"})
    public static final class ByHandleNative extends Structure {
        public int fileAttributes;
        public int creationTimeLow;
        public int creationTimeHigh;
        public int lastAccessTimeLow;
        public int lastAccessTimeHigh;
        public int lastWriteTimeLow;
        public int lastWriteTimeHigh;
        public int volumeSerialNumber;
        public int fileSizeHigh;
        public int fileSizeLow;
        public int numberOfLinks;
        public int fileIndexHigh;
        public int fileIndexLow;

        public ByHandleNative() {
        }
    }
}
