package restudio.resync.network.paper;

import restudio.resync.migration.MigrationPaths;
import restudio.resync.storage.StorageSafety;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class NetworkCredentialStore {
    private static final String STORAGE_TEMP_PREFIX = ".resync-";
    private static final String TEMP_SUFFIX = ".tmp";
    private static final String QUARANTINE_DIRECTORY = ".quarantine";
    private static final String QUARANTINE_CATEGORY = "network-exact-files";
    private static final String TEMPORARY_EVIDENCE_PREFIX = "temporary";
    private static final String EVIDENCE_SUFFIX = ".evidence";
    private static final String LOCK_SUFFIX = ".lock";
    private static final ConcurrentMap<Path, Object> PROCESS_LOCKS = new ConcurrentHashMap<>();
    private final String fileName;
    private Path file;
    private String credential;
    private boolean quiesced;

    NetworkCredentialStore(Path file, String initialCredential) {
        this.file = MigrationPaths.requirePath(file, "credentialFile");
        Path name = this.file.getFileName();
        if (name == null || name.toString().isBlank() || name.toString().contains("..")) {
            throw new IllegalArgumentException("ReSync Network Credential File Name Is Invalid");
        }
        this.fileName = name.toString();
        this.credential = normalize(initialCredential);
        try {
            Path parent = this.file.getParent();
            if (parent != null && Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
                withLock(this.file, () -> {
                    recoverCanonicalTemporary(this.file);
                    return null;
                });
            }
        } catch (IOException exception) {
            throw new IllegalStateException("ReSync Network Credential Durability State Is Invalid", exception);
        }
    }

    synchronized String value() {
        return credential;
    }

    synchronized Path file() {
        return file;
    }

    synchronized void save(String value) throws IOException {
        requireWritable();
        String next = normalize(value);
        if (next.isBlank()) {
            throw new IllegalArgumentException("ReSync Network Credential Is Required");
        }
        Path parent = file.getParent();
        if (parent == null) {
            throw new IOException("ReSync Network Credential Parent Is Missing");
        }
        ensureParent(parent);
        withLock(file, () -> {
            recoverCanonicalTemporary(file);
            validateCurrentContentForMutation();
            writeCredentialAtomic(file, next);
            credential = next;
            return null;
        });
    }

    synchronized void clear() throws IOException {
        requireWritable();
        Path parent = file.getParent();
        if (parent == null) {
            throw new IOException("ReSync Network Credential Parent Is Missing");
        }
        validateParent(parent);
        withLock(file, () -> {
            recoverCanonicalTemporary(file);
            validateCurrentContentForMutation();
            Files.deleteIfExists(file);
            StorageSafety.forceDirectory(parent);
            credential = "";
            return null;
        });
    }

    synchronized void flush() throws IOException {
        requireHealthy();
        Path parent = file.getParent();
        if (parent == null) {
            throw new IOException("ReSync Network Credential Parent Is Missing");
        }
        validateParent(parent);
        withLock(file, () -> {
            recoverCanonicalTemporary(file);
            validateCurrentContentForMutation();
            StorageSafety.forceDirectory(parent);
            return null;
        });
    }

    synchronized void quiesce() throws IOException {
        flush();
        quiesced = true;
    }

    synchronized void resume() throws IOException {
        healthCheck();
        quiesced = false;
    }

    synchronized void validateRebind(Path candidateNetworkRoot) throws IOException {
        Path candidateRoot = MigrationPaths.requireDirectory(candidateNetworkRoot, "candidateNetworkRoot");
        Path candidate = candidateRoot.resolve(fileName).normalize();
        if (!candidate.startsWith(candidateRoot) || candidate.equals(candidateRoot)) {
            throw new IOException("ReSync Network Credential Rebind Escaped Network Root");
        }
        withLock(candidate, () -> {
            validateCandidate(candidate);
            return null;
        });
    }

    synchronized void rebind(Path candidateNetworkRoot) throws IOException {
        Path candidateRoot = MigrationPaths.requireDirectory(candidateNetworkRoot, "candidateNetworkRoot");
        validateRebind(candidateRoot);
        Path candidate = candidateRoot.resolve(fileName).normalize();
        withLock(candidate, () -> {
            validateCandidate(candidate);
            String candidateCredential = Files.exists(candidate, LinkOption.NOFOLLOW_LINKS) ? Files.readString(candidate).trim() : credential;
            file = candidate;
            credential = candidateCredential;
            return null;
        });
    }

    synchronized void healthCheck() throws IOException {
        requireHealthy();
        Path parent = file.getParent();
        if (parent == null || Files.isSymbolicLink(parent) || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("ReSync Network Credential Parent Is Unavailable");
        }
        withLock(file, () -> {
            validateParent(parent);
            recoverCanonicalTemporary(file);
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("ReSync Network Credential File Is Invalid");
            }
            if (!credential.isBlank() && !Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("ReSync Network Credential File Is Missing");
            }
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !Files.readString(file).trim().equals(credential)) {
                throw new IOException("ReSync Network Credential Changed Outside The Persistence Boundary");
            }
            return null;
        });
    }

    NetworkPersistenceDrainController.Component persistenceComponent(String owner) {
        return persistenceComponent(owner, () -> {
        });
    }

    NetworkPersistenceDrainController.Component persistenceComponent(String owner, Runnable rebound) {
        return new NetworkPersistenceDrainController.Component() {
            @Override
            public String owner() {
                return owner;
            }

            @Override
            public Path activePath() {
                return file();
            }

            @Override
            public void flush() throws IOException {
                NetworkCredentialStore.this.flush();
            }

            @Override
            public void quiesce() throws IOException {
                NetworkCredentialStore.this.quiesce();
            }

            @Override
            public void resume() throws IOException {
                NetworkCredentialStore.this.resume();
            }

            @Override
            public void validateRebind(Path candidateNetworkRoot) throws IOException {
                NetworkCredentialStore.this.validateRebind(candidateNetworkRoot);
            }

            @Override
            public void rebind(Path candidateNetworkRoot) throws IOException {
                NetworkCredentialStore.this.rebind(candidateNetworkRoot);
                rebound.run();
            }

            @Override
            public void healthCheck() throws IOException {
                NetworkCredentialStore.this.healthCheck();
            }
        };
    }

    private synchronized void requireHealthy() {
        if (file == null) {
            throw new IllegalStateException("ReSync Network Credential Is Unavailable");
        }
    }

    private synchronized void requireWritable() {
        requireHealthy();
        if (quiesced) {
            throw new IllegalStateException("ReSync Network Credential Persistence Is Quiesced");
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private void validateParent(Path parent) throws IOException {
        Path normalized = MigrationPaths.requireDirectory(parent, "ReSync Network Credential Parent");
        MigrationPaths.requireNoSymlinkTraversal(normalized, normalized);
        validateEvidenceQuarantine(normalized,
            normalized.resolve(QUARANTINE_DIRECTORY).resolve(QUARANTINE_CATEGORY), fileName);
    }

    private static void ensureParent(Path parent) throws IOException {
        Path normalized = MigrationPaths.requirePath(parent, "ReSync Network Credential Parent");
        Path existing = normalized;
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
            if (existing == null) {
                throw new IOException("ReSync Network Credential Parent Has No Existing Ancestor");
            }
        }
        MigrationPaths.requireDirectory(existing, "ReSync Network Credential Existing Parent");
        StorageSafety.createDirectoriesNoSymlinks(existing, normalized);
    }

    private static void validateExistingFile(Path candidate, String description) throws IOException {
        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)
            && (Files.isSymbolicLink(candidate) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException(description + " Is Invalid");
        }
    }

    private void validateCandidate(Path candidate) throws IOException {
        recoverCanonicalTemporary(candidate);
        validateExistingFile(candidate, "ReSync Network Credential Candidate");
        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)
            && (Files.isSymbolicLink(candidate) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("ReSync Network Credential Candidate Is Invalid");
        }
        if (!credential.isBlank() && !Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("ReSync Network Credential Candidate Is Missing");
        }
        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS) && Files.readString(candidate).trim().isBlank()) {
            throw new IOException("ReSync Network Credential Candidate Is Empty");
        }
    }

    private static void validateLockFile(Path lock) throws IOException {
        if (Files.isSymbolicLink(lock) || !Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("ReSync Network Credential Lock File Is Invalid");
        }
    }

    private static <T> T withLock(Path target, IOCallable<T> action) throws IOException {
        Path normalizedTarget = MigrationPaths.requirePath(target, "credentialFile");
        Path parent = normalizedTarget.getParent();
        if (parent == null) {
            throw new IOException("ReSync Network Credential Parent Is Missing");
        }
        Path lock = normalizedTarget.resolveSibling(normalizedTarget.getFileName() + LOCK_SUFFIX).normalize();
        if (!lock.startsWith(parent) || lock.equals(parent)) {
            throw new IOException("ReSync Network Credential Lock Path Is Invalid");
        }
        validateParentWithoutQuarantine(parent);
        if (Files.exists(lock, LinkOption.NOFOLLOW_LINKS)) {
            validateLockFile(lock);
        }
        Object processLock = PROCESS_LOCKS.computeIfAbsent(lock, ignored -> new Object());
        synchronized (processLock) {
            try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                validateLockFile(lock);
                channel.force(true);
                try (FileLock ignored = channel.lock()) {
                    return action.call();
                }
            }
        }
    }

    private void validateCurrentContentForMutation() throws IOException {
        validateExistingFile(file, "ReSync Network Credential File");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            if (!credential.isBlank()) {
                throw new IOException("ReSync Network Credential File Is Missing");
            }
            return;
        }
        if (!Files.readString(file).trim().equals(credential)) {
            throw new IOException("ReSync Network Credential Changed Outside The Persistence Boundary");
        }
    }

    private static void recoverCanonicalTemporary(Path target) throws IOException {
        Path normalizedTarget;
        try {
            normalizedTarget = MigrationPaths.requirePath(target, "ReSync Network Credential Target");
        } catch (IllegalArgumentException exception) {
            throw new IOException("ReSync Network Credential Target Is Invalid", exception);
        }
        Path parent = normalizedTarget.getParent();
        if (parent == null || !Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        validateParentWithoutQuarantine(parent);
        Path quarantine = parent.resolve(QUARANTINE_DIRECTORY).resolve(QUARANTINE_CATEGORY).normalize();
        validateEvidenceQuarantine(parent, quarantine, normalizedTarget.getFileName().toString());
        Path temporary = normalizedTarget.resolveSibling(normalizedTarget.getFileName() + TEMP_SUFFIX);
        Path lock = normalizedTarget.resolveSibling(normalizedTarget.getFileName() + LOCK_SUFFIX);
        try (var stream = Files.list(parent)) {
            var iterator = stream.iterator();
            while (iterator.hasNext()) {
                Path entry = iterator.next();
                String name = entry.getFileName().toString();
                if (name.equals(lock.getFileName().toString())) {
                    validateLockFile(entry);
                    continue;
                }
                if (name.equals(temporary.getFileName().toString())) {
                    if (Files.isSymbolicLink(entry) || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("ReSync Network Credential Temporary File Is Invalid");
                    }
                    if (Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
                        moveEvidence(entry, quarantine, normalizedTarget.getFileName().toString(),
                            normalizedTarget.getFileName().toString());
                        throw new IOException("ReSync Network Credential Temporary File Collides With The Target");
                    }
                    moveEvidence(entry, quarantine, normalizedTarget.getFileName().toString(),
                        normalizedTarget.getFileName().toString());
                    continue;
                }
                if (isPotentialStorageSafetyTemporaryName(name)) {
                    throw new IOException("ReSync Network Credential Temporary File Name Is Invalid");
                }
                if (isPotentialCredentialTemporaryName(name, normalizedTarget.getFileName().toString())) {
                    throw new IOException("ReSync Network Credential Temporary File Name Is Invalid");
                }
            }
        }
    }

    private static boolean isPotentialStorageSafetyTemporaryName(String name) {
        return name != null && name.startsWith(STORAGE_TEMP_PREFIX) && name.endsWith(TEMP_SUFFIX);
    }

    private static boolean isPotentialCredentialTemporaryName(String name, String targetName) {
        return name != null && targetName != null && name.endsWith(TEMP_SUFFIX)
            && name.startsWith(targetName + ".");
    }

    private static void validateParentWithoutQuarantine(Path parent) throws IOException {
        Path normalized = MigrationPaths.requireDirectory(parent, "ReSync Network Credential Parent");
        MigrationPaths.requireNoSymlinkTraversal(normalized, normalized);
    }

    private static void validateEvidenceQuarantine(Path root, Path quarantine, String targetName) throws IOException {
        Path normalizedRoot = MigrationPaths.requireDirectory(root, "ReSync Network Credential Root");
        Path normalizedQuarantine = MigrationPaths.requirePath(quarantine, "ReSync Network Credential Quarantine");
        if (!normalizedQuarantine.startsWith(normalizedRoot) || normalizedQuarantine.equals(normalizedRoot)) {
            throw new IOException("ReSync Network Credential Quarantine Escaped Its Root");
        }
        Path quarantineParent = normalizedQuarantine.getParent();
        if (quarantineParent == null || !quarantineParent.equals(normalizedRoot.resolve(QUARANTINE_DIRECTORY))) {
            throw new IOException("ReSync Network Credential Quarantine Path Is Invalid");
        }
        if (Files.exists(normalizedRoot.resolve(QUARANTINE_DIRECTORY), LinkOption.NOFOLLOW_LINKS)
            && (Files.isSymbolicLink(normalizedRoot.resolve(QUARANTINE_DIRECTORY))
            || !Files.isDirectory(normalizedRoot.resolve(QUARANTINE_DIRECTORY), LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("ReSync Network Credential Quarantine Parent Is Invalid");
        }
        if (!Files.exists(normalizedQuarantine, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(normalizedQuarantine)
            || !Files.isDirectory(normalizedQuarantine, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("ReSync Network Credential Quarantine Is Invalid");
        }
        MigrationPaths.requireNoSymlinkTraversal(normalizedRoot, normalizedQuarantine);
        try (var stream = Files.list(normalizedQuarantine)) {
            var iterator = stream.iterator();
            while (iterator.hasNext()) {
                Path artifact = iterator.next();
                if (artifact.getParent() == null || !artifact.getParent().equals(normalizedQuarantine)
                    || artifact.getFileName() == null || Files.isSymbolicLink(artifact)
                    || !Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("ReSync Network Credential Quarantine Contains An Invalid Entry");
                }
                String name = artifact.getFileName().toString();
                if (!isEvidenceName(name, targetName)) {
                    throw new IOException("ReSync Network Credential Quarantine Contains An Unknown Entry");
                }
            }
        }
    }

    private static boolean isEvidenceName(String name, String targetName) {
        if (name == null || !name.endsWith(EVIDENCE_SUFFIX)) {
            return false;
        }
        String prefix = targetName != null && name.startsWith(targetName + ".")
            ? targetName + "."
            : TEMPORARY_EVIDENCE_PREFIX + ".";
        if (!name.startsWith(prefix)) {
            return false;
        }
        String identifier = name.substring(prefix.length(), name.length() - EVIDENCE_SUFFIX.length());
        try {
            return UUID.fromString(identifier).toString().equals(identifier);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static void moveEvidence(Path temporary, Path quarantine, String targetName,
                                     String validationTargetName) throws IOException {
        Path root = quarantine.getParent() == null ? null : quarantine.getParent().getParent();
        if (root == null) {
            throw new IOException("ReSync Network Credential Quarantine Root Is Missing");
        }
        StorageSafety.createDirectoriesNoSymlinks(root, quarantine);
        validateEvidenceQuarantine(root, quarantine, validationTargetName);
        Path evidence = null;
        String evidencePrefix = targetName == null ? TEMPORARY_EVIDENCE_PREFIX : targetName;
        for (int attempt = 0; attempt < 128; attempt++) {
            Path candidate = quarantine.resolve(evidencePrefix + "." + UUID.randomUUID() + EVIDENCE_SUFFIX);
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                evidence = candidate;
                break;
            }
        }
        if (evidence == null) {
            throw new IOException("Unable To Reserve ReSync Network Credential Evidence Path");
        }
        try {
            Files.move(temporary, evidence, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, evidence);
        }
        if (Files.isSymbolicLink(evidence) || !Files.isRegularFile(evidence, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("ReSync Network Credential Evidence Is Invalid");
        }
        StorageSafety.forceDirectory(quarantine);
        StorageSafety.forceDirectory(quarantine.getParent());
    }

    private static void writeCredentialAtomic(Path target, String value) throws IOException {
        Path normalizedTarget = MigrationPaths.requirePath(target, "ReSync Network Credential Target");
        Path parent = normalizedTarget.getParent();
        if (parent == null) {
            throw new IOException("ReSync Network Credential Parent Is Missing");
        }
        validateParentWithoutQuarantine(parent);
        if (Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)
            && (Files.isSymbolicLink(normalizedTarget)
            || !Files.isRegularFile(normalizedTarget, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("ReSync Network Credential File Is Invalid");
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        Path temporary = normalizedTarget.resolveSibling(normalizedTarget.getFileName() + TEMP_SUFFIX);
        if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("ReSync Network Credential Temporary File Already Exists");
        }
        boolean published = false;
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            if (Files.isSymbolicLink(temporary)
                || !Files.isRegularFile(temporary, LinkOption.NOFOLLOW_LINKS)
                || !Arrays.equals(bytes, Files.readAllBytes(temporary))) {
                throw new IOException("ReSync Network Credential Temporary File Is Invalid");
            }
            if (Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(normalizedTarget)
                || !Files.isRegularFile(normalizedTarget, LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("ReSync Network Credential File Is Invalid");
            }
            Files.move(temporary, normalizedTarget, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            published = true;
            if (Files.isSymbolicLink(normalizedTarget)
                || !Files.isRegularFile(normalizedTarget, LinkOption.NOFOLLOW_LINKS)
                || !Arrays.equals(bytes, Files.readAllBytes(normalizedTarget))) {
                throw new IOException("ReSync Network Credential File Verification Failed");
            }
            StorageSafety.forceDirectory(parent);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("Atomic ReSync Network Credential Publication Is Not Supported", exception);
        } catch (FileAlreadyExistsException exception) {
            throw new IOException("ReSync Network Credential Temporary File Already Exists", exception);
        } finally {
            if (published) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    @FunctionalInterface
    private interface IOCallable<T> {
        T call() throws IOException;
    }
}
