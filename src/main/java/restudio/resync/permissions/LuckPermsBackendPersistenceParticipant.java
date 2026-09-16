package restudio.resync.permissions;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
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
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class LuckPermsBackendPersistenceParticipant
    implements CloseablePersistenceParticipant, PersistenceOwnershipProvider {
    public static final String OWNER = "resync.runtime.luckperms-backend";
    public static final String DIRECTORY = "runtime/luckperms-backend";
    public static final String MANIFEST_FILE = "snapshot-manifest.json";
    public static final String ARTIFACT_FILE = "snapshot-artifact.bin";
    private static final String SNAPSHOT_DIRECTORY = "adapter-snapshot";
    private static final Gson GSON = new GsonBuilder().create();

    private final Path scopeRoot;
    private final LuckPermsBackendPersistenceCapability capability;
    private volatile Path activeScopeRoot;
    private volatile Path activeRoot;
    private volatile Path previousScopeRoot;
    private volatile Path previousRoot;
    private volatile boolean closed;

    public LuckPermsBackendPersistenceParticipant(Path scopeRoot,
                                                  LuckPermsBackendPersistenceCapability capability) {
        this.scopeRoot = requireDirectory(scopeRoot, "scopeRoot");
        this.capability = Objects.requireNonNull(capability, "capability");
        this.activeScopeRoot = this.scopeRoot;
        this.activeRoot = rootFor(this.scopeRoot);
        try {
            ensureRoot(activeRoot);
        } catch (IOException exception) {
            throw new IllegalArgumentException("LuckPerms backend participant root is unavailable", exception);
        }
    }

    @Override
    public String owner() {
        return OWNER;
    }

    @Override
    public Path root() {
        return activeRoot;
    }

    @Override
    public Path rebindScope() {
        return scopeRoot;
    }

    @Override
    public PersistenceOwnershipIndex ownershipIndex(PersistenceOwnershipContext context) {
        return PersistenceOwnershipIndex.builder(context).subtreeRoot().build();
    }

    @Override
    public synchronized void flush() throws IOException {
        requireOpen();
        ensureRoot(activeRoot);
        capability.flush();
    }

    @Override
    public synchronized void quiesce() throws IOException {
        requireOpen();
        ensureRoot(activeRoot);
        capability.quiesce();
        try {
            Path staging = activeRoot.resolve(SNAPSHOT_DIRECTORY).toAbsolutePath().normalize();
            ensureChildDirectory(activeRoot, staging, SNAPSHOT_DIRECTORY);
            LuckPermsBackendPersistenceCapability.SnapshotManifest manifest = capability.backup(staging);
            persistManifest(activeRoot, manifest);
        } catch (IOException | RuntimeException failure) {
            try {
                capability.resume();
            } catch (IOException | RuntimeException compensation) {
                failure.addSuppressed(compensation);
            }
            throw asIOException("LuckPerms backend snapshot could not be staged", failure);
        }
    }

    @Override
    public synchronized void resume() throws IOException {
        requireOpen();
        try {
            capability.resume();
            previousScopeRoot = null;
            previousRoot = null;
        } catch (IOException | RuntimeException failure) {
            Path rollbackScope = previousScopeRoot;
            Path rollbackRoot = previousRoot;
            if (rollbackScope != null && rollbackRoot != null) {
                activeScopeRoot = rollbackScope;
                activeRoot = rollbackRoot;
            }
            previousScopeRoot = null;
            previousRoot = null;
            throw asIOException("LuckPerms backend participant could not resume", failure);
        }
    }

    @Override
    public synchronized void rebind(Path activeScopeRoot) throws IOException {
        requireOpen();
        Path scope = requireDirectory(activeScopeRoot, "activeRoot");
        Path candidate = rootFor(scope);
        ensureRoot(candidate);
        if (previousRoot != null && candidate.equals(previousRoot)) {
            capability.rollbackPendingRebind();
            this.activeScopeRoot = previousScopeRoot == null ? scopeRoot : previousScopeRoot;
            activeRoot = previousRoot;
            previousScopeRoot = null;
            previousRoot = null;
            return;
        }
        if (candidate.equals(activeRoot)) {
            if (previousRoot != null) {
                throw new IOException("LuckPerms backend participant rebind is already pending resume");
            }
            return;
        }
        LuckPermsBackendPersistenceCapability.SnapshotManifest manifest = readManifest(candidate);
        Path backendTarget = capability.adapter().rebindTarget(scope);
        if (backendTarget == null) {
            throw new IOException("LuckPerms backend adapter returned no rebind target");
        }
        backendTarget = MigrationPaths.requirePath(backendTarget, "LuckPerms backend rebind target");
        previousScopeRoot = this.activeScopeRoot;
        previousRoot = activeRoot;
        try {
            capability.rebind(manifest, backendTarget);
            this.activeScopeRoot = scope;
            activeRoot = candidate;
        } catch (IOException | RuntimeException failure) {
            throw asIOException("LuckPerms backend participant could not rebind", failure);
        }
    }

    @Override
    public synchronized void healthCheck() throws IOException {
        requireOpen();
        ensureRoot(activeRoot);
        capability.healthCheck();
        if (capability.rebindReady()) {
            Path target = capability.adapter().rebindTarget(activeScopeRoot);
            if (target == null) {
                throw new IOException("LuckPerms backend adapter returned no rebind target");
            }
            MigrationPaths.requirePath(target, "LuckPerms backend rebind target");
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        if (capability.state() == LuckPermsBackendPersistenceCapability.State.OPEN) {
            capability.quiesce();
        }
        capability.close();
        closed = true;
    }

    public LuckPermsBackendPersistenceCapability capability() {
        return capability;
    }

    public Path snapshotManifestPath() {
        return activeRoot.resolve(MANIFEST_FILE).toAbsolutePath().normalize();
    }

    private void persistManifest(Path root, LuckPermsBackendPersistenceCapability.SnapshotManifest manifest) throws IOException {
        LuckPermsBackendPersistenceCapability.SnapshotReceipt receipt = manifest.receipt();
        String artifact = "";
        if (receipt.artifact() != null) {
            Path source = MigrationPaths.requirePath(receipt.artifact(), "snapshot artifact");
            Path target = root.resolve(ARTIFACT_FILE).toAbsolutePath().normalize();
            if (!target.startsWith(root) || target.equals(root)) {
                throw new IOException("LuckPerms backend snapshot artifact escaped participant root");
            }
            if (Files.isSymbolicLink(target)
                || (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("LuckPerms backend snapshot artifact target is not a regular file");
            }
            if (!source.equals(target)) {
                if (Files.isSymbolicLink(source) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("LuckPerms backend snapshot artifact is not a regular file");
                }
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
            if (!receipt.contentHash().equalsIgnoreCase(hashRegularFile(target))) {
                throw new IOException("LuckPerms backend snapshot artifact copy failed verification");
            }
            artifact = ARTIFACT_FILE;
        }
        PersistedManifest persisted = new PersistedManifest(
            manifest.backendIdentity().type(),
            manifest.backendIdentity().instance(),
            manifest.generation(),
            manifest.adapterContract().id(),
            manifest.adapterContract().version(),
            manifest.contentHash(),
            artifact,
            receipt.receiptId());
        StorageSafety.writeUtf8Atomic(root.resolve(MANIFEST_FILE), GSON.toJson(persisted));
        readManifest(root);
    }

    private LuckPermsBackendPersistenceCapability.SnapshotManifest readManifest(Path root) throws IOException {
        Path file = root.resolve(MANIFEST_FILE).toAbsolutePath().normalize();
        if (!file.startsWith(root) || Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("LuckPerms backend snapshot manifest is missing or unsafe");
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(StorageSafety.readUtf8(file));
        } catch (RuntimeException exception) {
            throw new IOException("LuckPerms backend snapshot manifest is invalid", exception);
        }
        if (!parsed.isJsonObject()) {
            throw new IOException("LuckPerms backend snapshot manifest must be an object");
        }
        PersistedManifest persisted;
        try {
            persisted = GSON.fromJson(parsed, PersistedManifest.class);
        } catch (RuntimeException exception) {
            throw new IOException("LuckPerms backend snapshot manifest is invalid", exception);
        }
        if (persisted == null || blank(persisted.backendType) || blank(persisted.backendInstance)
            || persisted.generation < 0 || blank(persisted.contractId) || blank(persisted.contractVersion)
            || blank(persisted.contentHash)) {
            throw new IOException("LuckPerms backend snapshot manifest is incomplete");
        }
        Path artifact = null;
        if (!blank(persisted.artifact)) {
            if (!ARTIFACT_FILE.equals(persisted.artifact)) {
                throw new IOException("LuckPerms backend snapshot manifest contains an unsafe artifact path");
            }
            artifact = root.resolve(ARTIFACT_FILE).toAbsolutePath().normalize();
            if (Files.isSymbolicLink(artifact) || !Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("LuckPerms backend snapshot artifact is missing or unsafe");
            }
        } else if (blank(persisted.receiptId)) {
            throw new IOException("LuckPerms backend snapshot manifest has no receipt");
        }
        LuckPermsBackendPersistenceCapability.BackendIdentity identity =
            new LuckPermsBackendPersistenceCapability.BackendIdentity(persisted.backendType, persisted.backendInstance);
        LuckPermsBackendPersistenceCapability.AdapterContract contract =
            new LuckPermsBackendPersistenceCapability.AdapterContract(persisted.contractId, persisted.contractVersion);
        LuckPermsBackendPersistenceCapability.SnapshotReceipt receipt = new LuckPermsBackendPersistenceCapability.SnapshotReceipt(
            identity, persisted.generation, contract, persisted.contentHash, artifact,
            persisted.receiptId == null ? "" : persisted.receiptId);
        return new LuckPermsBackendPersistenceCapability.SnapshotManifest(identity, persisted.generation, contract, receipt);
    }

    private Path rootFor(Path scope) {
        return MigrationPaths.resolveInside(scope, DIRECTORY);
    }

    private static void ensureRoot(Path root) throws IOException {
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            MigrationPaths.requireDirectory(root, "LuckPerms backend participant root");
        } else {
            MigrationPaths.requireWritableParent(root);
            Files.createDirectories(root);
            MigrationPaths.requireDirectory(root, "LuckPerms backend participant root");
        }
        MigrationPaths.requireNoSymlinkTraversal(root, root);
    }

    private static void ensureChildDirectory(Path root, Path child, String name) throws IOException {
        if (!child.startsWith(root) || child.equals(root)) {
            throw new IOException("LuckPerms backend snapshot staging escaped participant root");
        }
        if (Files.exists(child, LinkOption.NOFOLLOW_LINKS)) {
            MigrationPaths.requireDirectory(child, name);
        } else {
            Files.createDirectories(child);
            MigrationPaths.requireDirectory(child, name);
        }
    }

    private void requireOpen() throws IOException {
        if (closed) {
            throw new IOException("LuckPerms backend persistence participant is closed");
        }
    }

    private static Path requireDirectory(Path path, String name) {
        try {
            return MigrationPaths.requireDirectory(path, name);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException(name + " is unavailable", exception);
        }
    }

    private static IOException asIOException(String message, Throwable failure) {
        if (failure instanceof IOException exception) {
            return exception;
        }
        return new IOException(message, failure);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String hashRegularFile(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private record PersistedManifest(String backendType, String backendInstance, long generation,
                                     String contractId, String contractVersion, String contentHash,
                                     String artifact, String receiptId) {
    }
}
