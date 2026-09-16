package restudio.resync.filesystem.windows;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class WindowsMutationNative implements WindowsFileMutation.Backend {
    private static final int GENERIC_READ = 0x80000000;
    private static final int GENERIC_WRITE = 0x40000000;
    private static final int DELETE = 0x00010000;
    private static final int FILE_READ_ATTRIBUTES = 0x00000080;
    private static final int FILE_WRITE_ATTRIBUTES = 0x00000100;
    private static final int FILE_LIST_DIRECTORY = 0x00000001;
    private static final int FILE_ADD_FILE = 0x00000002;
    private static final int FILE_ADD_SUBDIRECTORY = 0x00000004;
    private static final int FILE_TRAVERSE = 0x00000020;
    private static final int FILE_DELETE_CHILD = 0x00000040;
    private static final int SYNCHRONIZE = 0x00100000;
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
    private static final int FILE_RENAME_INFO_CLASS = 3;
    private static final int FILE_RENAME_INFORMATION_CLASS = 10;
    private static final int FILE_DISPOSITION_INFO_EX_CLASS = 21;
    private static final int FILE_NAME_NORMALIZED = 0;
    private static final int VOLUME_NAME_DOS = 0;
    private static final int FILE_OPEN = 1;
    private static final int FILE_SYNCHRONOUS_IO_NONALERT = 0x00000020;
    private static final int FILE_OPEN_REPARSE_POINT = 0x00200000;
    private static final int FILE_WRITE_THROUGH = 0x00000002;
    private static final int OBJ_CASE_INSENSITIVE = 0x00000040;
    private static final int FILE_NAMES_INFORMATION_CLASS = 12;
    private static final int FILE_DISPOSITION_DELETE = 0x00000001;
    private static final int FILE_DISPOSITION_POSIX_SEMANTICS = 0x00000002;
    private static final int FILE_DISPOSITION_IGNORE_READONLY_ATTRIBUTE = 0x00000010;
    private static final int ERROR_INVALID_PARAMETER = 87;
    private static final int FILE_ID_INFO_IDENTIFIER_LENGTH = 16;
    private static final int MAX_FINAL_PATH_LENGTH = 32768;
    private static final int DIRECTORY_ACCESS = GENERIC_READ | FILE_LIST_DIRECTORY | FILE_ADD_FILE | FILE_ADD_SUBDIRECTORY
        | FILE_TRAVERSE | FILE_DELETE_CHILD | FILE_READ_ATTRIBUTES | SYNCHRONIZE;
    private static final int DIRECTORY_MUTATION_ACCESS = DIRECTORY_ACCESS | DELETE | FILE_WRITE_ATTRIBUTES;
    private static final int DIRECTORY_RENAME_ACCESS = DIRECTORY_MUTATION_ACCESS | GENERIC_WRITE;
    private static final int CHILD_ACCESS = GENERIC_READ | GENERIC_WRITE | DELETE | FILE_READ_ATTRIBUTES
        | FILE_WRITE_ATTRIBUTES | SYNCHRONIZE;
    private static final int MUTATION_SHARE_MODE = FILE_SHARE_READ | FILE_SHARE_WRITE;
    private static final int ENUMERATION_BUFFER_SIZE = 1024 * 1024;
    private static final int STATUS_SUCCESS = 0x00000000;
    private static final int STATUS_BUFFER_OVERFLOW = 0x80000005;
    private static final int STATUS_NO_MORE_FILES = 0x80000006;
    private static final int STATUS_NO_SUCH_FILE = 0xC000000F;
    private static final int STATUS_OBJECT_NAME_NOT_FOUND = 0xC0000034;
    private static final int STATUS_OBJECT_PATH_NOT_FOUND = 0xC000003A;
    private static final int STATUS_OBJECT_TYPE_MISMATCH = 0xC0000024;
    private static final int STATUS_BUFFER_TOO_SMALL = 0xC0000023;

    private final WindowsNative identityBackend = new WindowsNative();
    private volatile Kernel32 kernel32;
    private volatile Ntdll ntdll;
    private volatile String availabilityFailure;

    @Override
    public boolean available() {
        if (!isWindows()) {
            availabilityFailure = "Windows File Mutation Is Unsupported On This Operating System";
            return false;
        }
        try {
            kernel();
            ntdll();
            return true;
        } catch (IOException exception) {
            availabilityFailure = exception.getMessage();
            return false;
        }
    }

    @Override
    public String unavailableReason(Path root) {
        if (available()) {
            return "";
        }
        String reason = availabilityFailure;
        return reason == null || reason.isBlank() ? "Windows File Mutation Is Unavailable" : reason;
    }

    @Override
    public WindowsFileIdentity.Observation observe(Path path) throws IOException {
        return identityBackend.observe(path);
    }

    @Override
    public void flush(Path path) throws IOException {
        requireWindows(path);
        try (NativeHandle handle = openAbsolute(path, GENERIC_READ | GENERIC_WRITE, false)) {
            flushHandle(handle, path);
        }
    }

    @Override
    public void flushParent(Path path) throws IOException {
        Path normalized = normalize(path);
        Path parent = normalized.getParent();
        if (parent == null) {
            throw new IOException("Windows file path has no stable parent: " + normalized);
        }
        try (NativeHandle handle = openAbsolute(parent, GENERIC_READ | GENERIC_WRITE, true)) {
            flushHandle(handle, parent);
        }
    }

    @Override
    public void rename(Path source, Path destination) throws IOException {
        requireWindows(source);
        Path normalizedSource = normalize(source);
        Path normalizedDestination = normalize(destination);
        Path sourceParentPath = requireParent(normalizedSource, "rename source");
        Path destinationParentPath = requireParent(normalizedDestination, "rename destination");
        String sourceName = requireName(normalizedSource, "rename source");
        String destinationName = requireName(normalizedDestination, "rename destination");
        if (samePath(sourceParentPath, destinationParentPath)) {
            try (NativeHandle parent = openAbsolute(sourceParentPath, DIRECTORY_RENAME_ACCESS, true);
                 NativeHandle sourceHandle = openRelative(parent, sourceName, CHILD_ACCESS, false)) {
                renameWithParents(sourceHandle, parent, parent, sourceParentPath, destinationParentPath,
                    normalizedSource, normalizedDestination, destinationName);
            }
            return;
        }
        try (NativeHandle sourceParent = openAbsolute(sourceParentPath, DIRECTORY_RENAME_ACCESS, true);
             NativeHandle destinationParent = openAbsolute(destinationParentPath, DIRECTORY_RENAME_ACCESS, true);
             NativeHandle sourceHandle = openRelative(sourceParent, sourceName, CHILD_ACCESS, false)) {
            renameWithParents(sourceHandle, sourceParent, destinationParent, sourceParentPath, destinationParentPath,
                normalizedSource, normalizedDestination, destinationName);
        }
    }

    private void renameWithParents(NativeHandle sourceHandle, NativeHandle sourceParent, NativeHandle destinationParent,
                                   Path sourceParentPath, Path destinationParentPath, Path source, Path destination,
                                   String destinationName) throws IOException {
        HandleInfo sourceInfo = verifyHandle(sourceHandle, source);
        requireMovable(sourceInfo, source, "rename source");
        verifyHandle(sourceParent, sourceParentPath);
        verifyHandle(destinationParent, destinationParentPath);
        setRename(sourceHandle, destinationParent, destinationName, destination);
        HandleInfo renamedInfo = verifyHandle(sourceHandle, destination);
        if (!sourceInfo.identity().equals(renamedInfo.identity())) {
            throw new IOException("Windows rename changed the source file identity: " + source);
        }
        flushHandle(sourceHandle, destination);
        flushHandle(sourceParent, sourceParentPath);
        if (!samePath(sourceParentPath, destinationParentPath)) {
            flushHandle(destinationParent, destinationParentPath);
        }
    }

    @Override
    public void deleteFile(Path path) throws IOException {
        requireWindows(path);
        Path normalized = normalize(path);
        Path parentPath = requireParent(normalized, "delete file");
        String name = requireName(normalized, "delete file");
        try (NativeHandle parent = openAbsolute(parentPath, DIRECTORY_ACCESS, true);
             NativeHandle child = openRelative(parent, name, CHILD_ACCESS, false)) {
            HandleInfo info = verifyHandle(child, normalized);
            if (info.directory() || info.other() || info.reparsePoint()) {
                throw new IOException("Windows delete file target is not a regular file: " + normalized);
            }
            verifyHandle(parent, parentPath);
            setDisposition(child, normalized);
            flushHandle(child, normalized);
            flushHandle(parent, parentPath);
        } catch (NativeStatusException exception) {
            if (!isMissing(exception.status())) {
                throw exception.toIOException("Windows delete file failed", normalized);
            }
        }
    }

    @Override
    public void deleteTree(Path path) throws IOException {
        requireWindows(path);
        Path normalized = normalize(path);
        Path parentPath = requireParent(normalized, "delete tree");
        String name = requireName(normalized, "delete tree");
        try (NativeHandle parent = openAbsolute(parentPath, DIRECTORY_ACCESS, true);
             NativeHandle tree = openRelative(parent, name, DIRECTORY_MUTATION_ACCESS, true)) {
            HandleInfo info = verifyHandle(tree, normalized);
            if (!info.directory() || info.other() || info.reparsePoint()) {
                throw new IOException("Windows delete tree target is not a regular directory: " + normalized);
            }
            verifyHandle(parent, parentPath);
            deleteTreeContents(tree, normalized);
            setDisposition(tree, normalized);
            flushHandle(tree, normalized);
            flushHandle(parent, parentPath);
        } catch (NativeStatusException exception) {
            if (!isMissing(exception.status())) {
                throw exception.toIOException("Windows delete tree failed", normalized);
            }
        }
    }

    private void deleteTreeContents(NativeHandle directory, Path logicalDirectory) throws IOException {
        List<String> names = enumerate(directory, logicalDirectory);
        for (String name : names) {
            Path logical = logicalDirectory.resolve(name).normalize();
            try (NativeHandle child = openRelative(directory, name, CHILD_ACCESS, false)) {
                HandleInfo info = verifyHandle(child, logical);
                if (info.reparsePoint() || info.other()) {
                    throw new IOException("Windows delete tree encountered an unsafe entry: " + logical);
                }
                if (info.directory()) {
                    deleteTreeContents(child, logical);
                    verifyHandle(child, logical);
                    setDisposition(child, logical);
                    flushHandle(child, logical);
                    continue;
                }
                verifyHandle(child, logical);
                setDisposition(child, logical);
                flushHandle(child, logical);
            }
            flushHandle(directory, logicalDirectory);
        }
        if (!enumerate(directory, logicalDirectory).isEmpty()) {
            throw new IOException("Windows delete tree found entries after recursive deletion: " + logicalDirectory);
        }
    }

    private List<String> enumerate(NativeHandle directory, Path logicalDirectory) throws IOException {
        Ntdll api = ntdll();
        Set<String> names = new LinkedHashSet<>();
        try (Memory buffer = new Memory(ENUMERATION_BUFFER_SIZE)) {
            boolean restart = true;
            while (true) {
                IoStatusBlockNative statusBlock = new IoStatusBlockNative();
                statusBlock.write();
                int status = api.NtQueryDirectoryFile(directory.value(), null, null, null, statusBlock,
                    buffer, (int) buffer.size(), FILE_NAMES_INFORMATION_CLASS, false, null, restart);
                statusBlock.read();
                if (status == STATUS_NO_MORE_FILES || status == STATUS_NO_SUCH_FILE) {
                    break;
                }
                if (!ntSuccess(status) && status != STATUS_BUFFER_OVERFLOW && status != STATUS_BUFFER_TOO_SMALL) {
                    throw ntFailure("NtQueryDirectoryFile", logicalDirectory, status);
                }
                long reported = statusBlock.information;
                if (reported <= 0 || reported > buffer.size()) {
                    throw new IOException("NtQueryDirectoryFile returned an invalid directory buffer for "
                        + logicalDirectory);
                }
                parseNames(buffer, (int) reported, names, logicalDirectory);
                restart = false;
            }
        }
        return List.copyOf(names);
    }

    private static void parseNames(Memory buffer, int length, Set<String> names, Path logicalDirectory) throws IOException {
        int offset = 0;
        while (offset < length) {
            if (length - offset < 12) {
                throw new IOException("NtQueryDirectoryFile returned a truncated name record for " + logicalDirectory);
            }
            int next = buffer.getInt(offset);
            int nameLength = buffer.getInt(offset + 8);
            if ((nameLength & 1) != 0 || nameLength == 0 || nameLength > length - offset - 12) {
                throw new IOException("NtQueryDirectoryFile returned an invalid name record for " + logicalDirectory);
            }
            byte[] bytes = buffer.getByteArray(offset + 12, nameLength);
            String name = new String(bytes, StandardCharsets.UTF_16LE);
            if (!name.equals(".") && !name.equals("..")) {
                if (name.isEmpty() || name.indexOf('\\') >= 0 || name.indexOf('/') >= 0 || !names.add(name)) {
                    throw new IOException("NtQueryDirectoryFile returned an invalid or duplicate entry " + name
                        + " at offset " + offset + " for " + logicalDirectory);
                }
            }
            if (next == 0) {
                return;
            }
            if (next < 12 || next > length - offset) {
                throw new IOException("NtQueryDirectoryFile returned an invalid record offset for " + logicalDirectory);
            }
            offset += next;
        }
        throw new IOException("NtQueryDirectoryFile returned an unterminated name record for " + logicalDirectory);
    }

    private void setRename(NativeHandle source, NativeHandle destinationParent, String name, Path destination)
        throws IOException {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_16LE);
        int rootDirectoryOffset = 8;
        int nameLengthOffset = 16;
        int nameOffset = 20;
        try (Memory buffer = new Memory(nameOffset + nameBytes.length + 2L)) {
            buffer.clear();
            buffer.setByte(0, (byte) 0);
            buffer.setPointer(rootDirectoryOffset, destinationParent.value());
            buffer.setInt(nameLengthOffset, nameBytes.length);
            buffer.write(nameOffset, nameBytes, 0, nameBytes.length);
            Kernel32 api = kernel();
            if (!api.SetFileInformationByHandle(source.value(), FILE_RENAME_INFO_CLASS, buffer,
                (int) buffer.size())) {
                int error = api.GetLastError();
                if (error != ERROR_INVALID_PARAMETER) {
                    throw failure(api, "SetFileInformationByHandle(FileRenameInfo)", destination, error);
                }
                IoStatusBlockNative statusBlock = new IoStatusBlockNative();
                statusBlock.write();
                int status = ntdll().NtSetInformationFile(source.value(), statusBlock, buffer,
                    (int) buffer.size(), FILE_RENAME_INFORMATION_CLASS);
                if (!ntSuccess(status)) {
                    throw ntFailure("NtSetInformationFile(FileRenameInformation)", destination, status);
                }
            }
        }
    }

    private void setDisposition(NativeHandle handle, Path path) throws IOException {
        try (Memory buffer = new Memory(Integer.BYTES)) {
            buffer.setInt(0, FILE_DISPOSITION_DELETE | FILE_DISPOSITION_POSIX_SEMANTICS
                | FILE_DISPOSITION_IGNORE_READONLY_ATTRIBUTE);
            Kernel32 api = kernel();
            if (!api.SetFileInformationByHandle(handle.value(), FILE_DISPOSITION_INFO_EX_CLASS, buffer,
                (int) buffer.size())) {
                throw failure(api, "SetFileInformationByHandle(FileDispositionInfoEx)", path);
            }
        }
    }

    private void flushHandle(NativeHandle handle, Path path) throws IOException {
        Kernel32 api = kernel();
        if (!api.FlushFileBuffers(handle.value())) {
            throw failure(api, "FlushFileBuffers", path);
        }
    }

    private NativeHandle openAbsolute(Path path, int access, boolean directory) throws IOException {
        Path normalized = normalize(path);
        Kernel32 api = kernel();
        Pointer value = api.CreateFileW(new WString(normalized.toString()), access, MUTATION_SHARE_MODE, null,
            OPEN_EXISTING, FILE_FLAG_WRITE_THROUGH | FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT, null);
        if (isInvalidHandle(value)) {
            throw failure(api, "CreateFileW", normalized);
        }
        NativeHandle handle = new NativeHandle(api, value, normalized);
        try {
            HandleInfo info = verifyHandle(handle, normalized);
            if (directory != info.directory()) {
                throw new IOException("Windows path kind changed while opening " + normalized);
            }
            return handle;
        } catch (IOException exception) {
            try {
                handle.close();
            } catch (IOException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw exception;
        }
    }

    private NativeHandle openRelative(NativeHandle parent, String name, int access, boolean directory)
        throws IOException {
        return openRelative(parent, name, access, directory, MUTATION_SHARE_MODE);
    }

    private NativeHandle openRelative(NativeHandle parent, String name, int access, boolean directory, int shareMode)
        throws IOException {
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.indexOf('\\') >= 0
            || name.indexOf('/') >= 0) {
            throw new IOException("Windows relative child name is invalid: " + name);
        }
        Ntdll api = ntdll();
        try (Memory nameMemory = new Memory((long) (name.length() + 1) * 2L)) {
            nameMemory.clear();
            nameMemory.setWideString(0, name);
            UnicodeStringNative unicode = new UnicodeStringNative(nameMemory, name.length() * 2);
            unicode.write();
            ObjectAttributesNative attributes = new ObjectAttributesNative(parent.value(), unicode.getPointer());
            attributes.write();
            IoStatusBlockNative statusBlock = new IoStatusBlockNative();
            statusBlock.write();
            PointerByReference result = new PointerByReference();
            int options = FILE_OPEN_REPARSE_POINT | FILE_SYNCHRONOUS_IO_NONALERT | FILE_WRITE_THROUGH;
            if (directory) {
                options |= 0x00000001;
            }
            int status = api.NtCreateFile(result, access, attributes, statusBlock, null, 0, shareMode,
                FILE_OPEN, options, null, 0);
            if (!ntSuccess(status)) {
                throw new NativeStatusException(status, "NtCreateFile", name);
            }
            Pointer value = result.getValue();
            if (isInvalidHandle(value)) {
                throw new IOException("NtCreateFile returned an invalid child handle: " + name);
            }
            return new NativeHandle(kernel(), value, Path.of(name));
        }
    }

    private HandleInfo verifyHandle(NativeHandle handle, Path expected) throws IOException {
        Path normalizedExpected = normalize(expected);
        Kernel32 api = kernel();
        WindowsFileIdentity.FileIdInfo identity = readIdentity(api, handle.value(), normalizedExpected);
        AttributeTag tag = readAttributeTag(api, handle.value(), normalizedExpected);
        Path finalPath = readFinalPath(api, handle.value(), normalizedExpected);
        if (!samePath(normalizedExpected, finalPath)) {
            throw new IOException("Windows handle resolved to an unexpected path: " + finalPath + " expected "
                + normalizedExpected);
        }
        boolean directory = (tag.attributes() & FILE_ATTRIBUTE_DIRECTORY) != 0;
        boolean reparse = (tag.attributes() & FILE_ATTRIBUTE_REPARSE_POINT) != 0 || tag.reparseTag() != 0;
        if (reparse && tag.reparseTag() == 0) {
            throw new IOException("Windows reparse tag is missing for " + normalizedExpected);
        }
        boolean regular = !directory && !reparse && (tag.attributes() & FILE_ATTRIBUTE_DEVICE) == 0;
        boolean other = !directory && !regular;
        return new HandleInfo(identity, directory, regular, other, reparse, tag.reparseTag(), finalPath);
    }

    private static void requireMovable(HandleInfo info, Path path, String operation) throws IOException {
        if (info.other() || info.reparsePoint()) {
            throw new IOException("Windows " + operation + " target is not a regular file or directory: " + path);
        }
    }

    private WindowsFileIdentity.FileIdInfo readIdentity(Kernel32 api, Pointer handle, Path path) throws IOException {
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
        ByHandleNative fallback = new ByHandleNative();
        fallback.write();
        if (!api.GetFileInformationByHandle(handle, fallback)) {
            throw failure(api, "GetFileInformationByHandleEx(FileIdInfo)", path, error);
        }
        fallback.read();
        long fileIndex = Integer.toUnsignedLong(fallback.fileIndexHigh) << Integer.SIZE
            | Integer.toUnsignedLong(fallback.fileIndexLow);
        if (fallback.volumeSerialNumber == 0 || fileIndex == 0 || fallback.numberOfLinks <= 0) {
            throw new IOException("Windows fallback file identity is not unique for " + path);
        }
        byte[] identifier = new byte[FILE_ID_INFO_IDENTIFIER_LENGTH];
        for (int index = 0; index < Long.BYTES; index++) {
            identifier[Long.BYTES + index] = (byte) (fileIndex >>> ((Long.BYTES - index - 1) * Byte.SIZE));
        }
        return new WindowsFileIdentity.FileIdInfo(Integer.toUnsignedLong(fallback.volumeSerialNumber), identifier, true);
    }

    private AttributeTag readAttributeTag(Kernel32 api, Pointer handle, Path path) throws IOException {
        AttributeTagNative info = new AttributeTagNative();
        info.write();
        if (!api.GetFileInformationByHandleEx(handle, FILE_ATTRIBUTE_TAG_INFO_CLASS, info.getPointer(), info.size())) {
            throw failure(api, "GetFileInformationByHandleEx(FileAttributeTagInfo)", path);
        }
        info.read();
        return new AttributeTag(info.fileAttributes, info.reparseTag);
    }

    private Path readFinalPath(Kernel32 api, Pointer handle, Path original) throws IOException {
        int capacity = 512;
        while (capacity <= MAX_FINAL_PATH_LENGTH) {
            try (Memory buffer = new Memory((long) capacity * Native.WCHAR_SIZE)) {
                int length = api.GetFinalPathNameByHandleW(handle, buffer, capacity,
                    FILE_NAME_NORMALIZED | VOLUME_NAME_DOS);
                if (length == 0) {
                    throw failure(api, "GetFinalPathNameByHandleW", original);
                }
                if (length < capacity) {
                    return normalizeFinalPath(buffer.getWideString(0), original);
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

    private Kernel32 kernel() throws IOException {
        Kernel32 current = kernel32;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            current = kernel32;
            if (current == null) {
                try {
                    current = Native.load("kernel32", Kernel32.class);
                } catch (LinkageError | RuntimeException exception) {
                    throw new IOException("Windows kernel32 is unavailable", exception);
                }
                kernel32 = current;
            }
            return current;
        }
    }

    private Ntdll ntdll() throws IOException {
        Ntdll current = ntdll;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            current = ntdll;
            if (current == null) {
                try {
                    current = Native.load("ntdll", Ntdll.class);
                } catch (LinkageError | RuntimeException exception) {
                    throw new IOException("Windows ntdll is unavailable", exception);
                }
                ntdll = current;
            }
            return current;
        }
    }

    private static void requireWindows(Path path) throws IOException {
        if (!isWindows()) {
            throw new IOException("Windows file mutation is unsupported on this operating system: " + path);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static Path requireParent(Path path, String operation) throws IOException {
        Path parent = path.getParent();
        if (parent == null) {
            throw new IOException("Windows " + operation + " path has no stable parent: " + path);
        }
        return parent;
    }

    private static String requireName(Path path, String operation) throws IOException {
        Path name = path.getFileName();
        if (name == null || name.toString().isEmpty() || name.toString().equals(".") || name.toString().equals("..")) {
            throw new IOException("Windows " + operation + " path has no stable name: " + path);
        }
        return name.toString();
    }

    private static boolean isInvalidHandle(Pointer handle) {
        return handle == null || Pointer.nativeValue(handle) == -1L || Pointer.nativeValue(handle) == 0L;
    }

    private static boolean samePath(Path first, Path second) {
        return normalize(first).toString().equalsIgnoreCase(normalize(second).toString());
    }

    private static boolean allZero(byte[] value) {
        for (byte current : value) {
            if (current != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean ntSuccess(int status) {
        return status >= STATUS_SUCCESS;
    }

    private static boolean isMissing(int status) {
        return status == STATUS_OBJECT_NAME_NOT_FOUND || status == STATUS_OBJECT_PATH_NOT_FOUND
            || status == STATUS_NO_SUCH_FILE || status == STATUS_OBJECT_TYPE_MISMATCH;
    }

    private static IOException ntFailure(String operation, Path path, int status) {
        return new IOException(operation + " failed for " + path + " (NTSTATUS 0x"
            + Integer.toHexString(status) + ")");
    }

    private static IOException failure(Kernel32 api, String operation, Path path) {
        return failure(api, operation, path, api.GetLastError());
    }

    private static IOException failure(Kernel32 api, String operation, Path path, int error) {
        return new IOException(operation + " failed for " + path + " (Windows error "
            + Integer.toUnsignedString(error) + ")");
    }

    private record AttributeTag(int attributes, int reparseTag) {
    }

    private record HandleInfo(WindowsFileIdentity.FileIdInfo identity, boolean directory, boolean regular,
                              boolean other, boolean reparsePoint, int reparseTag, Path finalPath) {
    }

    private static final class NativeStatusException extends IOException {
        private final int status;
        private final String operation;
        private final String name;

        private NativeStatusException(int status, String operation, String name) {
            super(operation + " failed for " + name + " (NTSTATUS 0x" + Integer.toHexString(status) + ")");
            this.status = status;
            this.operation = operation;
            this.name = name;
        }

        private int status() {
            return status;
        }

        private IOException toIOException(String prefix, Path path) {
            return new IOException(prefix + " for " + path + " (" + operation + ": " + name + ")", this);
        }
    }

    private static final class NativeHandle implements AutoCloseable {
        private final Kernel32 api;
        private final Pointer value;
        private final Path logicalPath;
        private boolean closed;

        private NativeHandle(Kernel32 api, Pointer value, Path logicalPath) {
            this.api = api;
            this.value = value;
            this.logicalPath = logicalPath;
        }

        private Pointer value() {
            return value;
        }

        @Override
        public void close() throws IOException {
            if (!closed) {
                closed = true;
                if (!api.CloseHandle(value)) {
                    throw failure(api, "CloseHandle", logicalPath);
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

        boolean SetFileInformationByHandle(Pointer handle, int fileInformationClass, Pointer fileInformation,
                                            int bufferSize);

        int GetLastError();
    }

    private interface Ntdll extends StdCallLibrary {
        int NtCreateFile(PointerByReference fileHandle, int desiredAccess, ObjectAttributesNative objectAttributes,
                         IoStatusBlockNative ioStatusBlock, Pointer allocationSize, int fileAttributes,
                         int shareAccess, int createDisposition, int createOptions, Pointer eaBuffer, int eaLength);

        int NtQueryDirectoryFile(Pointer fileHandle, Pointer event, Pointer apcRoutine, Pointer apcContext,
                                 IoStatusBlockNative ioStatusBlock, Pointer fileInformation, int length,
                                 int fileInformationClass, boolean returnSingleEntry, Pointer fileName,
                                 boolean restartScan);

        int NtSetInformationFile(Pointer fileHandle, IoStatusBlockNative ioStatusBlock, Pointer fileInformation,
                                  int length, int fileInformationClass);
    }

    @Structure.FieldOrder({"volumeSerialNumber", "identifier"})
    public static final class FileIdNative extends Structure {
        public long volumeSerialNumber;
        public byte[] identifier = new byte[FILE_ID_INFO_IDENTIFIER_LENGTH];
    }

    @Structure.FieldOrder({"fileAttributes", "reparseTag"})
    public static final class AttributeTagNative extends Structure {
        public int fileAttributes;
        public int reparseTag;
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
    }

    @Structure.FieldOrder({"length", "maximumLength", "buffer"})
    public static final class UnicodeStringNative extends Structure {
        public short length;
        public short maximumLength;
        public Pointer buffer;

        private UnicodeStringNative(Memory memory, int length) {
            this.length = (short) length;
            this.maximumLength = (short) (length + 2);
            this.buffer = memory;
        }
    }

    @Structure.FieldOrder({"length", "rootDirectory", "objectName", "attributes", "securityDescriptor",
        "securityQualityOfService"})
    public static final class ObjectAttributesNative extends Structure {
        public int length;
        public Pointer rootDirectory;
        public Pointer objectName;
        public int attributes;
        public Pointer securityDescriptor;
        public Pointer securityQualityOfService;

        private ObjectAttributesNative(Pointer rootDirectory, Pointer objectName) {
            this.length = size();
            this.rootDirectory = rootDirectory;
            this.objectName = objectName;
            this.attributes = OBJ_CASE_INSENSITIVE;
        }
    }

    @Structure.FieldOrder({"status", "reserved", "information"})
    public static final class IoStatusBlockNative extends Structure {
        public int status;
        public int reserved;
        public long information;
    }
}
