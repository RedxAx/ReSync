package restudio.resync.server;

import restudio.resync.migration.CloseablePersistenceParticipant;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.PersistenceOwnershipContext;
import restudio.resync.migration.PersistenceOwnershipIndex;
import restudio.resync.migration.PersistenceOwnershipProvider;
import restudio.resync.storage.StorageSafety;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class TlsPersistenceParticipant implements CloseablePersistenceParticipant, PersistenceOwnershipProvider {
    public static final String OWNER = "resync.tls-identity";
    private static final int MAX_FILE_BYTES = 1_048_576;
    private final Path scopeRoot;
    private final Path relativeMetadata;
    private final List<String> identityNames;
    private final Map<String, String> identityHashes;
    private final byte[] runtimeContent;
    private final String runtimeHash;
    private Path activeScope;
    private boolean published;
    private boolean quiesced;
    private boolean closed;

    TlsPersistenceParticipant(Path scopeRoot, Path metadataFile, byte[] runtimeContent) throws IOException {
        this.scopeRoot = MigrationPaths.requireDirectory(scopeRoot, "TLS scopeRoot");
        Path metadata = MigrationPaths.requirePath(metadataFile, "TLS metadataFile");
        if (!metadata.startsWith(this.scopeRoot) || metadata.equals(this.scopeRoot)) {
            throw new IOException("TLS Metadata Must Stay Inside Its Persistence Root");
        }
        relativeMetadata = this.scopeRoot.relativize(metadata);
        identityNames = List.of(ReSyncTlsIdentity.KEY_STORE_FILE, ReSyncTlsIdentity.PASSWORD_FILE, ReSyncTlsIdentity.ROTATION_FILE);
        if (identityNames.contains(metadata.getFileName().toString())) {
            throw new IOException("TLS Metadata Must Not Replace Its Identity Files");
        }
        this.runtimeContent = runtimeContent == null ? null : runtimeContent.clone();
        runtimeHash = runtimeContent == null ? null : hash(runtimeContent);
        activeScope = this.scopeRoot;
        identityHashes = Map.copyOf(identityHashes(activeScope));
        validateRuntime(activeScope);
    }

    @Override
    public String owner() {
        return OWNER;
    }

    @Override
    public synchronized Path root() {
        return metadataFile().getParent().resolve(ReSyncTlsIdentity.KEY_STORE_FILE);
    }

    @Override
    public boolean rootMayBeAbsent() {
        return !identityHashes.containsKey(ReSyncTlsIdentity.KEY_STORE_FILE);
    }

    @Override
    public Path rebindScope() {
        return scopeRoot;
    }

    @Override
    public PersistenceOwnershipIndex ownershipIndex(PersistenceOwnershipContext context) {
        return PersistenceOwnershipIndex.builder(context).exactRoot()
            .rootSiblingExact(ReSyncTlsIdentity.PASSWORD_FILE)
            .rootSiblingExact(ReSyncTlsIdentity.ROTATION_FILE)
            .rootSiblingExact(relativeMetadata.getFileName().toString())
            .rootSiblingAtomicTemp(".tmp").build();
    }

    @Override
    public synchronized boolean owns(Path path) {
        Path candidate = MigrationPaths.requirePath(path, "TLS file");
        if (!candidate.startsWith(activeScope) || candidate.equals(activeScope)) return false;
        PersistenceOwnershipContext context = new PersistenceOwnershipContext(activeScope, root());
        return ownershipIndex(context).owns(context.relativeToSource(candidate));
    }

    public synchronized Path metadataFile() {
        return activeScope.resolve(relativeMetadata);
    }

    synchronized void publish() throws IOException {
        requireOpen();
        if (runtimeContent == null) throw new IOException("TLS Runtime Identity Is Not Prepared");
        healthCheck();
        ReSyncTlsIdentity.writePrivate(metadataFile(), runtimeContent);
        published = true;
    }

    synchronized void withdraw() throws IOException {
        if (closed) return;
        requireOpen();
        removeRuntime();
        published = false;
    }

    @Override
    public synchronized void flush() throws IOException {
        healthCheck();
        Path directory = metadataFile().getParent();
        if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) StorageSafety.forceDirectory(directory);
    }

    @Override
    public synchronized void quiesce() throws IOException {
        if (closed || quiesced) return;
        flush();
        quiesced = true;
    }

    @Override
    public synchronized void resume() throws IOException {
        if (closed) throw new IOException("TLS Persistence Is Closed");
        healthCheck();
        if (published) ReSyncTlsIdentity.writePrivate(metadataFile(), Objects.requireNonNull(runtimeContent));
        else removeRuntime();
        quiesced = false;
    }

    @Override
    public synchronized void rebind(Path activeRoot) throws IOException {
        if (closed || !quiesced) throw new IOException("TLS Persistence Must Be Quiesced Before Rebind");
        Path candidate = MigrationPaths.requireDirectory(activeRoot, "TLS activeRoot");
        if (!identityHashes.equals(identityHashes(candidate))) {
            throw new IOException("Restored TLS Identity Differs From The Running Listener; Restart Is Required");
        }
        validateRuntime(candidate);
        activeScope = candidate;
    }

    @Override
    public synchronized void healthCheck() throws IOException {
        if (!identityHashes.equals(identityHashes(activeScope))) throw new IOException("TLS Identity Files Changed Outside Their Persistence Owner");
        validateRuntime(activeScope);
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        quiesced = true;
        removeRuntime();
        published = false;
        closed = true;
    }

    private void requireOpen() throws IOException {
        if (quiesced || closed) throw new IOException("TLS Persistence Is Quiesced; Runtime Publication Is Unavailable");
    }

    private void removeRuntime() throws IOException {
        Path file = metadataFile();
        checkedHash(activeScope, file);
        if (Files.deleteIfExists(file)) StorageSafety.forceDirectory(file.getParent());
    }

    private Map<String, String> identityHashes(Path scope) throws IOException {
        Path directory = scope.resolve(relativeMetadata).getParent();
        Map<String, String> hashes = new LinkedHashMap<>();
        for (String name : identityNames) {
            String hash = checkedHash(scope, directory.resolve(name));
            if (hash != null) hashes.put(name, hash);
        }
        if (hashes.containsKey(ReSyncTlsIdentity.KEY_STORE_FILE) != hashes.containsKey(ReSyncTlsIdentity.PASSWORD_FILE)) {
            throw new IOException("TLS Identity Files Are Incomplete");
        }
        return hashes;
    }

    private void validateRuntime(Path scope) throws IOException {
        String actual = checkedHash(scope, scope.resolve(relativeMetadata));
        if (actual != null && runtimeHash != null && !actual.equals(runtimeHash)) {
            throw new IOException("TLS Runtime Metadata Does Not Match The Running Listener");
        }
    }

    private static String checkedHash(Path scope, Path path) throws IOException {
        MigrationPaths.requireNoSymlinkTraversal(scope, path);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_FILE_BYTES) {
            throw new IOException("TLS Persistence File Is Invalid");
        }
        byte[] bytes;
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_FILE_BYTES + 1);
        }
        try {
            if (bytes.length > MAX_FILE_BYTES) throw new IOException("TLS Persistence File Exceeds Its Limit");
            return hash(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
