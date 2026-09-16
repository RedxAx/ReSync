package restudio.resync.server;

import restudio.resync.contract.canonical.CanonicalHash;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.migration.MigrationException;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.ProductionAuthorityBundle;
import restudio.resync.migration.ProductionAuthoritySigner;
import restudio.resync.migration.ProductionAuthorityTrustAnchor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

final class ProductionAuthorityKeyStore implements ProductionAuthoritySigner {
    static final String SECRETS_DIRECTORY = ".resync-secrets";
    static final String PRIVATE_KEY_FILE_PREFIX = ".resync-install-authority-signing-";
    static final String PRIVATE_KEY_FILE_SUFFIX = ".key";
    static final String PRIVATE_KEY_TEMP_PREFIX = PRIVATE_KEY_FILE_PREFIX;
    static final String PRIVATE_KEY_TEMP_SUFFIX = ".tmp";
    static final String TEMP_SUFFIX = PRIVATE_KEY_TEMP_SUFFIX;
    static final String LOCK_FILE = "authority.lock";
    static final String TRUST_ANCHOR_DIRECTORY = ProductionAuthorityBundle.AUTHORITY_DIRECTORY;
    static final String TRUST_ANCHOR_FILE = "authority-trust-anchor.json";
    static final String TRUST_ANCHOR_RELATIVE_PATH = TRUST_ANCHOR_DIRECTORY + "/" + TRUST_ANCHOR_FILE;
    static final String TRUST_ANCHOR_TEMP_SUFFIX = ".tmp";
    static final String QUARANTINE_DIRECTORY = ".quarantine";
    static final String QUARANTINE_CATEGORY = "authority-exact-files";
    static final String EVIDENCE_SUFFIX = ".evidence";
    private static final String PRIVATE_FORMAT = "format=2\n";
    private static final byte[] KEY_CHECK_PAYLOAD = "resync-authority-private-key-check".getBytes(StandardCharsets.UTF_8);
    private static final Set<PosixFilePermission> PRIVATE_KEY_POSIX_PERMISSIONS = Set.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE
    );
    private static final Set<PosixFilePermission> SECRET_ROOT_POSIX_PERMISSIONS = Set.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE
    );
    private static final Set<AclEntryPermission> PRIVATE_KEY_ACL_PERMISSIONS = Set.copyOf(
        EnumSet.allOf(AclEntryPermission.class));
    private static final Map<Path, Object> JVM_LOCKS = new ConcurrentHashMap<>();
    private static final Map<LoadKey, CompletableFuture<AuthorityMaterial>> JVM_LOADS = new ConcurrentHashMap<>();
    private static final int MAXIMUM_RESERVATION_ATTEMPTS = 128;
    private static final DirectoryForce SYSTEM_DIRECTORY_FORCE = ProductionAuthorityKeyStore::forceDirectorySystem;
    private static final InitialLoadObserver NO_INITIAL_LOAD_OBSERVER = new InitialLoadObserver() {
    };

    private final ServerIdentityStore identity;
    private final Path identityScope;
    private final Path identityPath;
    private final Path dataRootPath;
    private final ServerId authorityServerId;
    private final Path secretRoot;
    private final Path privateKeyPath;
    private final Path legacyCombinedKeyPath;
    private final Path lockPath;
    private final DirectoryForce directoryForce;
    private final InitialLoadObserver initialLoadObserver;
    private volatile KeyPair keyPair;
    private volatile KeyFileState keyFileState;

    ProductionAuthorityKeyStore(ServerIdentityStore identity) {
        this(identity, DirectoryForce.system(), NO_INITIAL_LOAD_OBSERVER);
    }

    ProductionAuthorityKeyStore(ServerIdentityStore identity, DirectoryForce directoryForce) {
        this(identity, directoryForce, NO_INITIAL_LOAD_OBSERVER);
    }

    ProductionAuthorityKeyStore(ServerIdentityStore identity, DirectoryForce directoryForce,
                                InitialLoadObserver initialLoadObserver) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.directoryForce = Objects.requireNonNull(directoryForce, "directoryForce");
        this.initialLoadObserver = Objects.requireNonNull(initialLoadObserver, "initialLoadObserver");
        Path scope = MigrationPaths.requirePath(identity.rebindScope(), "identityScope");
        Path currentIdentityPath = MigrationPaths.requirePath(identity.path(), "identityPath");
        Path parent = scope.getParent();
        Path currentDataRoot = currentIdentityPath.getParent();
        if (parent == null || currentDataRoot == null) {
            throw new IllegalArgumentException("Server Identity Scope Has No Parent");
        }
        String suffix = CanonicalHash.rawSha256(scope.toAbsolutePath().normalize().toString()
            .getBytes(StandardCharsets.UTF_8));
        this.identityScope = scope;
        this.identityPath = currentIdentityPath;
        this.dataRootPath = currentDataRoot;
        this.authorityServerId = identity.serverId();
        this.secretRoot = parent.resolve(SECRETS_DIRECTORY).toAbsolutePath().normalize();
        String fileName = PRIVATE_KEY_FILE_PREFIX + suffix + PRIVATE_KEY_FILE_SUFFIX;
        this.privateKeyPath = secretRoot.resolve(fileName).toAbsolutePath().normalize();
        this.legacyCombinedKeyPath = parent.resolve(fileName).toAbsolutePath().normalize();
        this.lockPath = secretRoot.resolve(LOCK_FILE).toAbsolutePath().normalize();
    }

    @Override
    public ServerId serverId() {
        return identity.serverId();
    }

    @Override
    public String installAuthorityHash() throws IOException {
        ensureIdentityStable();
        return ProductionAuthorityBundle.installAuthorityDigest(dataRoot());
    }

    @Override
    public String signingPublicKey() throws IOException {
        return trustAnchor().publicKey();
    }

    @Override
    public String sign(byte[] canonicalPayload) throws IOException {
        Objects.requireNonNull(canonicalPayload, "canonicalPayload");
        AuthorityMaterial material = authority();
        KeyPair pair = material.pair();
        try {
            Signature signature = Signature.getInstance(ProductionAuthorityBundle.SIGNATURE_ALGORITHM);
            signature.initSign(pair.getPrivate());
            signature.update(canonicalPayload);
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GeneralSecurityException exception) {
            throw new IOException("Production Authority Signature Is Unavailable", exception);
        }
    }

    ProductionAuthorityTrustAnchor trustAnchor() throws IOException {
        return authority().anchor();
    }

    Path secretRoot() {
        return secretRoot;
    }

    Path privateKeyPath() {
        return privateKeyPath;
    }

    Path legacyCombinedKeyPath() {
        return legacyCombinedKeyPath;
    }

    Path trustAnchorPath() throws IOException {
        return dataRoot().resolve(TRUST_ANCHOR_RELATIVE_PATH).toAbsolutePath().normalize();
    }

    private synchronized AuthorityMaterial authority() throws IOException {
        KeyPair current = keyPair;
        if (current != null) {
            ensureIdentityStable();
            verifyCachedKey();
            ProductionAuthorityTrustAnchor anchor = readTrustAnchor();
            validatePair(anchor, current.getPublic(), current.getPrivate());
            return new AuthorityMaterial(current, keyFileState, anchor);
        }
        AuthorityMaterial loaded = loadInitialAuthority();
        keyPair = loaded.pair();
        keyFileState = loaded.state();
        return loaded;
    }

    private AuthorityMaterial loadInitialAuthority() throws IOException {
        LoadKey loadKey = new LoadKey(identity, identityPath, privateKeyPath, authorityServerId, directoryForce,
            initialLoadObserver);
        CompletableFuture<AuthorityMaterial> created = new CompletableFuture<>();
        CompletableFuture<AuthorityMaterial> current = JVM_LOADS.putIfAbsent(loadKey, created);
        if (current != null) {
            initialLoadObserver.followerJoined();
            return awaitInitialAuthority(current);
        }
        try {
            initialLoadObserver.leaderStarted();
            AuthorityMaterial loaded = withExclusiveLock(() -> {
                KeyPair pair = loadOrCreate();
                KeyFileState state = keyFileState;
                if (state == null) {
                    throw new MigrationException("Production Authority Signing Key State Is Missing");
                }
                ProductionAuthorityTrustAnchor anchor = readTrustAnchor();
                validatePair(anchor, pair.getPublic(), pair.getPrivate());
                return new AuthorityMaterial(pair, state, anchor);
            });
            created.complete(loaded);
            return loaded;
        } catch (IOException | RuntimeException | Error exception) {
            created.completeExceptionally(exception);
            throw exception;
        } finally {
            JVM_LOADS.remove(loadKey, created);
            initialLoadObserver.finished();
        }
    }

    private static AuthorityMaterial awaitInitialAuthority(CompletableFuture<AuthorityMaterial> load)
        throws IOException {
        try {
            return load.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Production Authority Signing Key Initialization Was Interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException("Production Authority Signing Key Initialization Failed", cause);
        }
    }

    private KeyPair loadOrCreate() throws IOException {
        ensureIdentityStable();
        rejectLegacyCombinedFile();
        StartupInventory inventory = inspectStartupInventory();
        if (inventory.anchorPresent()) {
            ProductionAuthorityTrustAnchor anchor = readTrustAnchor();
            quarantineStaleTrustAnchorTemporaryFiles(inventory.trustAnchorTemporaryFiles());
            if (inventory.privatePresent()) {
                LoadedPrivateKey loaded = readPrivateKey(privateKeyPath, anchor);
                validatePair(anchor, loaded.pair().getPublic(), loaded.pair().getPrivate());
                String anchorCanonical = anchor.canonical();
                quarantineStaleTemporaryFiles(inventory.temporaryFiles(), anchor);
                verifyKeyState(loaded.state());
                if (!anchorCanonical.equals(readTrustAnchor().canonical())) {
                    throw new MigrationException("Production Authority Trust Anchor Changed During Startup");
                }
                keyFileState = loaded.state();
                return loaded.pair();
            }
            if (inventory.temporaryFiles().isEmpty()) {
                throw new MigrationException("Production Authority Key And Trust Anchor Are Incomplete");
            }
            LoadedPrivateKey recovered = recoverMissingPrivateKey(anchor, inventory.temporaryFiles());
            keyFileState = recovered.state();
            return recovered.pair();
        }
        if (!inventory.trustAnchorTemporaryFiles().isEmpty()) {
            if (!inventory.privatePresent() && inventory.temporaryFiles().isEmpty()) {
                throw new MigrationException("Production Authority Trust Anchor Recovery Evidence Requires Its Signing Key");
            }
            AnchorCandidate candidate = selectTrustAnchorCandidate(inventory.trustAnchorTemporaryFiles());
            publishTrustAnchor(candidate.path(), candidate.anchor().canonicalBytes());
            ProductionAuthorityTrustAnchor anchor = readTrustAnchor();
            if (inventory.privatePresent()) {
                LoadedPrivateKey loaded = readPrivateKey(privateKeyPath, anchor);
                validatePair(anchor, loaded.pair().getPublic(), loaded.pair().getPrivate());
                quarantineStaleTemporaryFiles(inventory.temporaryFiles(), anchor);
                quarantineStaleTrustAnchorTemporaryFiles(inventory.trustAnchorTemporaryFiles(), candidate.path());
                verifyKeyState(loaded.state());
                if (!anchor.canonical().equals(readTrustAnchor().canonical())) {
                    throw new MigrationException("Production Authority Trust Anchor Changed During Startup");
                }
                keyFileState = loaded.state();
                return loaded.pair();
            }
            LoadedPrivateKey recovered = recoverMissingPrivateKey(anchor, inventory.temporaryFiles());
            quarantineStaleTrustAnchorTemporaryFiles(inventory.trustAnchorTemporaryFiles(), candidate.path());
            keyFileState = recovered.state();
            return recovered.pair();
        }
        if (inventory.privatePresent()) {
            throw new MigrationException("Production Authority Key And Trust Anchor Are Incomplete");
        }
        if (!inventory.temporaryFiles().isEmpty() || inventory.evidencePresent()) {
            throw new MigrationException("Production Authority Signing Key Recovery Evidence Requires Its Trust Anchor");
        }
        KeyPair generated = generateKeyPair();
        writePrivateKey(generated.getPrivate());
        ProductionAuthorityTrustAnchor anchor = createTrustAnchor(generated.getPublic());
        writeTrustAnchor(anchor);
        LoadedPrivateKey loaded = readPrivateKey(privateKeyPath, anchor);
        validatePair(anchor, loaded.pair().getPublic(), loaded.pair().getPrivate());
        keyFileState = loaded.state();
        return loaded.pair();
    }

    private LoadedPrivateKey recoverMissingPrivateKey(ProductionAuthorityTrustAnchor anchor,
                                                      List<Path> temporaryFiles) throws IOException {
        List<Candidate> matching = new ArrayList<>();
        List<Path> stale = new ArrayList<>();
        for (Path temporary : temporaryFiles) {
            LoadedPrivateKey loaded = readPrivateKey(temporary, anchor);
            try {
                validatePair(anchor, loaded.pair().getPublic(), loaded.pair().getPrivate());
                matching.add(new Candidate(temporary, loaded));
            } catch (MigrationException exception) {
                if (isKeyMismatch(exception)) {
                    stale.add(temporary);
                    continue;
                }
                throw exception;
            }
        }
        if (matching.isEmpty()) {
            quarantineTemporaryFiles(stale);
            throw new MigrationException("Production Authority Key And Trust Anchor Are Incomplete");
        }
        Set<String> hashes = matching.stream().map(candidate -> candidate.loaded().state().contentHash()).collect(Collectors.toUnmodifiableSet());
        if (hashes.size() != 1) {
            throw new MigrationException("Production Authority Signing Key Recovery Candidates Conflict");
        }
        Candidate winner = matching.getFirst();
        for (Candidate candidate : matching) {
            if (!candidate.path().equals(winner.path())) {
                stale.add(candidate.path());
            }
        }
        quarantineTemporaryFiles(stale);
        String anchorCanonical = anchor.canonical();
        publishPrivateKey(winner.path(), winner.loaded().state().contentHash());
        ProductionAuthorityTrustAnchor currentAnchor = readTrustAnchor();
        if (!anchorCanonical.equals(currentAnchor.canonical())) {
            throw new MigrationException("Production Authority Trust Anchor Changed During Key Recovery");
        }
        LoadedPrivateKey recovered = readPrivateKey(privateKeyPath, currentAnchor);
        validatePair(currentAnchor, recovered.pair().getPublic(), recovered.pair().getPrivate());
        return recovered;
    }

    private void quarantineStaleTemporaryFiles(List<Path> temporaryFiles,
                                               ProductionAuthorityTrustAnchor anchor) throws IOException {
        if (temporaryFiles.isEmpty()) {
            return;
        }
        List<Path> stale = new ArrayList<>();
        for (Path temporary : temporaryFiles) {
            LoadedPrivateKey loaded = readPrivateKey(temporary, anchor);
            try {
                validatePair(anchor, loaded.pair().getPublic(), loaded.pair().getPrivate());
            } catch (MigrationException exception) {
                if (!isKeyMismatch(exception)) {
                    throw exception;
                }
            }
            stale.add(temporary);
        }
        quarantineTemporaryFiles(stale);
    }

    private void quarantineStaleTrustAnchorTemporaryFiles(List<Path> temporaryFiles) throws IOException {
        quarantineStaleTrustAnchorTemporaryFiles(temporaryFiles, null);
    }

    private void quarantineStaleTrustAnchorTemporaryFiles(List<Path> temporaryFiles, Path consumedTemporary)
        throws IOException {
        if (temporaryFiles.isEmpty()) {
            return;
        }
        List<Path> present = new ArrayList<>();
        for (Path temporary : temporaryFiles) {
            if (!pathExists(temporary)) {
                if (consumedTemporary == null || !consumedTemporary.equals(temporary)) {
                    throw new MigrationException("Production Authority Trust Anchor Temporary File Disappeared");
                }
                continue;
            }
            readTrustAnchor(temporary, "Production Authority Trust Anchor Temporary File");
            present.add(temporary);
        }
        quarantineTemporaryFiles(present, "Production Authority Trust Anchor Temporary File");
    }

    private AnchorCandidate selectTrustAnchorCandidate(List<Path> temporaryFiles) throws IOException {
        List<AnchorCandidate> candidates = new ArrayList<>();
        for (Path temporary : temporaryFiles.stream()
            .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList()) {
            candidates.add(new AnchorCandidate(temporary,
                readTrustAnchor(temporary, "Production Authority Trust Anchor Temporary File")));
        }
        Set<String> canonicalValues = candidates.stream().map(candidate -> candidate.anchor().canonical())
            .collect(Collectors.toUnmodifiableSet());
        if (canonicalValues.size() != 1) {
            throw new MigrationException("Production Authority Trust Anchor Recovery Candidates Conflict");
        }
        return candidates.getFirst();
    }

    private void quarantineTemporaryFiles(List<Path> files) throws IOException {
        quarantineTemporaryFiles(files, "Production Authority Signing Key Temporary File");
    }

    private void quarantineTemporaryFiles(List<Path> files, String description) throws IOException {
        for (Path file : files.stream().sorted(Comparator.comparing(path -> path.getFileName().toString())).toList()) {
            moveToEvidence(file, description);
        }
    }

    private ProductionAuthorityTrustAnchor createTrustAnchor(PublicKey publicKey) throws IOException {
        String publicKeyValue = Base64.getEncoder().encodeToString(publicKey.getEncoded());
        String keyId = ProductionAuthorityTrustAnchor.fingerprint(publicKeyValue);
        return ProductionAuthorityTrustAnchor.pinned(serverId(), serverId().canonicalText(),
            installAuthorityHash(), keyId, publicKeyValue);
    }

    private KeyPair generateKeyPair() throws IOException {
        try {
            return KeyPairGenerator.getInstance(ProductionAuthorityBundle.SIGNATURE_ALGORITHM).generateKeyPair();
        } catch (GeneralSecurityException exception) {
            throw new IOException("Production Authority Signing Key Is Unavailable", exception);
        }
    }

    private LoadedPrivateKey readPrivateKey(Path path, ProductionAuthorityTrustAnchor anchor) throws IOException {
        StableFile file = readStableFile(path, "Production Authority Signing Key");
        String content = decodeUtf8(file.bytes(), "Production Authority Signing Key");
        String[] lines = content.split("\\n", -1);
        if (lines.length != 4 || !lines[3].isEmpty() || !PRIVATE_FORMAT.trim().equals(lines[0])
            || !lines[1].startsWith("private-key=") || !lines[2].startsWith("key-hash=")) {
            throw new MigrationException("Production Authority Signing Key Format Is Invalid");
        }
        String canonical = lines[0] + "\n" + lines[1] + "\n";
        String hash = lines[2].substring("key-hash=".length());
        if (!CanonicalHash.rawSha256(canonical.getBytes(StandardCharsets.UTF_8)).equals(hash)) {
            throw new MigrationException("Production Authority Signing Key Hash Does Not Match");
        }
        String privateValue = lines[1].substring("private-key=".length());
        try {
            byte[] privateBytes = Base64.getDecoder().decode(privateValue);
            if (!Base64.getEncoder().encodeToString(privateBytes).equals(privateValue)) {
                throw new MigrationException("Production Authority Signing Key Encoding Is Not Canonical");
            }
            PrivateKey privateKey = KeyFactory.getInstance(ProductionAuthorityBundle.SIGNATURE_ALGORITHM)
                .generatePrivate(new PKCS8EncodedKeySpec(privateBytes));
            PublicKey publicKey = parsePublicKey(anchor.publicKey());
            return new LoadedPrivateKey(new KeyPair(publicKey, privateKey), file.state());
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new MigrationException("Production Authority Signing Key Is Invalid", exception);
        }
    }

    private ProductionAuthorityTrustAnchor readTrustAnchor() throws IOException {
        return readTrustAnchor(trustAnchorPath(), "Production Authority Trust Anchor");
    }

    private ProductionAuthorityTrustAnchor readTrustAnchor(Path path, String description) throws IOException {
        Path root = dataRoot();
        MigrationPaths.requireNoSymlinkTraversal(root, path);
        StableFile file = readStableFile(path, description);
        String canonical = decodeUtf8(file.bytes(), description);
        try {
            ProductionAuthorityTrustAnchor anchor = ProductionAuthorityTrustAnchor.fromCanonical(canonical);
            if (!anchor.serverId().equals(serverId()) || !anchor.installationId().equals(serverId().canonicalText())
                || !anchor.authorityKeyId().equals(ProductionAuthorityTrustAnchor.fingerprint(anchor.publicKey()))
                || !anchor.installAuthorityHash().equals(installAuthorityHash())) {
                throw new MigrationException("Production Authority Trust Anchor Is Bound To A Different Installation");
            }
            if (!anchor.canonical().equals(canonical)) {
                throw new MigrationException("Production Authority Trust Anchor Is Not Canonical");
            }
            return anchor;
        } catch (MigrationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MigrationException("Production Authority Trust Anchor Is Invalid", exception);
        }
    }

    private KeyFileState writePrivateKey(PrivateKey privateKey) throws IOException {
        ensureSecretRoot();
        if (pathExists(privateKeyPath)) {
            throw new FileAlreadyExistsException(privateKeyPath.toString());
        }
        String privateValue = Base64.getEncoder().encodeToString(privateKey.getEncoded());
        String canonical = PRIVATE_FORMAT + "private-key=" + privateValue + "\n";
        byte[] content = (canonical + "key-hash="
            + CanonicalHash.rawSha256(canonical.getBytes(StandardCharsets.UTF_8)) + "\n")
            .getBytes(StandardCharsets.UTF_8);
        Path temporary = reservePrivateKeyTemporary();
        protectPrivateKey(temporary);
        writeForced(temporary, content);
        protectPrivateKey(temporary);
        StableFile verified = readStableFile(temporary, "Production Authority Signing Key Temporary File");
        if (!Arrays.equals(content, verified.bytes())) {
            throw new MigrationException("Production Authority Signing Key Temporary File Verification Failed");
        }
        return publishPrivateKey(temporary, verified.state().contentHash());
    }

    private KeyFileState publishPrivateKey(Path temporary, String expectedHash) throws IOException {
        requirePrivateKeyFile(temporary, "Production Authority Signing Key Temporary File");
        StableFile temporaryFile = readStableFile(temporary, "Production Authority Signing Key Temporary File");
        if (!expectedHash.equals(temporaryFile.state().contentHash())) {
            throw new MigrationException("Production Authority Signing Key Temporary File Hash Changed");
        }
        if (pathExists(privateKeyPath)) {
            throw new FileAlreadyExistsException(privateKeyPath.toString());
        }
        try {
            Files.createLink(privateKeyPath, temporary);
        } catch (UnsupportedOperationException exception) {
            throw new MigrationException("Production Authority Signing Key Atomic Installation Is Unavailable", exception);
        }
        StableFile published = readStableFile(privateKeyPath, "Production Authority Signing Key");
        if (!expectedHash.equals(published.state().contentHash())
            || !sameFileIdentity(temporaryFile.state().stamp(), published.state().stamp())) {
            throw new MigrationException("Production Authority Signing Key Publication Verification Failed");
        }
        protectPrivateKey(privateKeyPath);
        force(privateKeyPath);
        StableFile currentPublished = readStableFile(privateKeyPath, "Production Authority Signing Key");
        if (!published.state().contentHash().equals(currentPublished.state().contentHash())
            || !sameFileIdentity(published.state().stamp(), currentPublished.state().stamp())) {
            throw new MigrationException("Production Authority Signing Key Changed During Publication");
        }
        if (forceDirectory(secretRoot) == DirectoryForceOutcome.UNSUPPORTED) {
            return published.state();
        }
        Files.delete(temporary);
        try {
            if (forceDirectory(secretRoot) == DirectoryForceOutcome.UNSUPPORTED) {
                restoreTemporaryEvidence(temporary, privateKeyPath, published,
                    "Production Authority Signing Key Temporary File");
            }
        } catch (IOException exception) {
            try {
                restoreTemporaryEvidence(temporary, privateKeyPath, published,
                    "Production Authority Signing Key Temporary File");
            } catch (IOException restoreFailure) {
                exception.addSuppressed(restoreFailure);
            }
            throw exception;
        }
        return published.state();
    }

    private void restoreTemporaryEvidence(Path temporary, Path target, StableFile published, String description)
        throws IOException {
        if (!pathExists(temporary)) {
            try {
                Files.createLink(temporary, target);
            } catch (UnsupportedOperationException exception) {
                throw new MigrationException(description + " Evidence Link Is Unavailable", exception);
            } catch (FileAlreadyExistsException exception) {
                throw new MigrationException(description + " Evidence Path Changed", exception);
            }
        }
        StableFile restored = readStableFile(temporary, description);
        if (!published.state().contentHash().equals(restored.state().contentHash())
            || !sameFileIdentity(published.state().stamp(), restored.state().stamp())) {
            throw new MigrationException(description + " Evidence Restoration Failed");
        }
    }

    private Path reservePrivateKeyTemporary() throws IOException {
        String targetName = privateKeyPath.getFileName().toString();
        for (int attempt = 0; attempt < MAXIMUM_RESERVATION_ATTEMPTS; attempt++) {
            String name = targetName + "." + UUID.randomUUID() + PRIVATE_KEY_TEMP_SUFFIX;
            Path candidate = secretRoot.resolve(name).toAbsolutePath().normalize();
            try (FileChannel channel = FileChannel.open(candidate, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                channel.force(true);
            } catch (FileAlreadyExistsException exception) {
                continue;
            }
            requirePrivateKeyFile(candidate, "Production Authority Signing Key Temporary File");
            return candidate;
        }
        throw new MigrationException("Unable To Reserve A Production Authority Signing Key Temporary File");
    }

    private void writeTrustAnchor(ProductionAuthorityTrustAnchor anchor) throws IOException {
        Path path = trustAnchorPath();
        Path parent = path.getParent();
        if (parent == null) {
            throw new MigrationException("Production Authority Trust Anchor Has No Parent");
        }
        ensurePublicAuthorityDirectory(parent);
        byte[] bytes = anchor.canonicalBytes();
        if (pathExists(path)) {
            StableFile existing = readStableFile(path, "Production Authority Trust Anchor");
            if (!Arrays.equals(existing.bytes(), bytes)) {
                throw new MigrationException("Production Authority Trust Anchor Already Contains Different Content");
            }
            return;
        }
        Path temporary = reserveTrustAnchorTemporary(parent);
        protectPrivateKey(temporary);
        writeForced(temporary, bytes);
        protectPrivateKey(temporary);
        StableFile verified = readStableFile(temporary, "Production Authority Trust Anchor Temporary File");
        if (!Arrays.equals(verified.bytes(), bytes)) {
            throw new MigrationException("Production Authority Trust Anchor Temporary File Verification Failed");
        }
        publishTrustAnchor(temporary, bytes);
    }

    private Path reserveTrustAnchorTemporary(Path parent) throws IOException {
        for (int attempt = 0; attempt < MAXIMUM_RESERVATION_ATTEMPTS; attempt++) {
            String name = TRUST_ANCHOR_FILE + "." + UUID.randomUUID() + TRUST_ANCHOR_TEMP_SUFFIX;
            Path candidate = parent.resolve(name).toAbsolutePath().normalize();
            try (FileChannel channel = FileChannel.open(candidate, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                channel.force(true);
            } catch (FileAlreadyExistsException exception) {
                continue;
            }
            requirePrivateKeyFile(candidate, "Production Authority Trust Anchor Temporary File");
            return candidate;
        }
        throw new MigrationException("Unable To Reserve A Production Authority Trust Anchor Temporary File");
    }

    private void publishTrustAnchor(Path temporary, byte[] expectedBytes) throws IOException {
        Path target = trustAnchorPath();
        Path parent = target.getParent();
        if (parent == null) {
            throw new MigrationException("Production Authority Trust Anchor Has No Parent");
        }
        Objects.requireNonNull(expectedBytes, "expectedBytes");
        byte[] expected = Arrays.copyOf(expectedBytes, expectedBytes.length);
        String expectedHash = CanonicalHash.rawSha256(expected);
        requirePrivateKeyFile(temporary, "Production Authority Trust Anchor Temporary File");
        StableFile temporaryFile = readStableFile(temporary, "Production Authority Trust Anchor Temporary File");
        if (!expectedHash.equals(temporaryFile.state().contentHash())
            || !Arrays.equals(expected, temporaryFile.bytes())) {
            throw new MigrationException("Production Authority Trust Anchor Temporary File Hash Changed");
        }
        StableFile published = null;
        if (pathExists(target)) {
            published = readStableFile(target, "Production Authority Trust Anchor");
            if (!expectedHash.equals(published.state().contentHash())
                || !Arrays.equals(expected, published.bytes())
                || !Arrays.equals(temporaryFile.bytes(), published.bytes())) {
                throw new MigrationException("Production Authority Trust Anchor Already Contains Different Content");
            }
        } else {
            try {
                Files.createLink(target, temporary);
            } catch (UnsupportedOperationException exception) {
                throw new MigrationException("Production Authority Trust Anchor Atomic Installation Is Unavailable",
                    exception);
            } catch (FileAlreadyExistsException exception) {
                published = readStableFile(target, "Production Authority Trust Anchor");
                if (!expectedHash.equals(published.state().contentHash())
                    || !Arrays.equals(expected, published.bytes())
                    || !Arrays.equals(temporaryFile.bytes(), published.bytes())) {
                    throw new MigrationException("Production Authority Trust Anchor Already Contains Different Content",
                        exception);
                }
            }
            if (published == null) {
                published = readStableFile(target, "Production Authority Trust Anchor");
                if (!expectedHash.equals(published.state().contentHash())
                    || !Arrays.equals(expected, published.bytes())
                    || !sameFileIdentity(temporaryFile.state().stamp(), published.state().stamp())) {
                    throw new MigrationException("Production Authority Trust Anchor Publication Verification Failed");
                }
            }
        }
        protectPrivateKey(target);
        force(target);
        StableFile currentPublished = readStableFile(target, "Production Authority Trust Anchor");
        if (!published.state().contentHash().equals(currentPublished.state().contentHash())
            || !sameFileIdentity(published.state().stamp(), currentPublished.state().stamp())) {
            throw new MigrationException("Production Authority Trust Anchor Changed During Publication");
        }
        if (forceDirectory(parent) == DirectoryForceOutcome.UNSUPPORTED) {
            return;
        }
        Files.delete(temporary);
        try {
            if (forceDirectory(parent) == DirectoryForceOutcome.UNSUPPORTED) {
                restoreTemporaryEvidence(temporary, target, published,
                    "Production Authority Trust Anchor Temporary File");
            }
        } catch (IOException exception) {
            try {
                restoreTemporaryEvidence(temporary, target, published,
                    "Production Authority Trust Anchor Temporary File");
            } catch (IOException restoreFailure) {
                exception.addSuppressed(restoreFailure);
            }
            throw exception;
        }
    }

    private void ensurePublicAuthorityDirectory(Path parent) throws IOException {
        Path dataRoot = dataRoot();
        if (!parent.startsWith(dataRoot) || parent.equals(dataRoot)) {
            throw new MigrationException("Production Authority Trust Anchor Directory Is Invalid");
        }
        MigrationPaths.requireNoSymlinkTraversal(dataRoot, parent);
        if (!pathExists(parent)) {
            try {
                Files.createDirectory(parent);
            } catch (FileAlreadyExistsException ignored) {
            }
        }
        if (Files.isSymbolicLink(parent) || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Production Authority Trust Anchor Directory Is Invalid");
        }
    }

    private void ensureSecretRoot() throws IOException {
        Path parent = secretRoot.getParent();
        if (parent == null) {
            throw new MigrationException("Production Authority Secrets Root Has No Parent");
        }
        MigrationPaths.requireDirectory(parent, "production authority secrets parent");
        if (!pathExists(secretRoot)) {
            try {
                Files.createDirectory(secretRoot);
            } catch (FileAlreadyExistsException ignored) {
            }
        }
        if (Files.isSymbolicLink(secretRoot) || !Files.isDirectory(secretRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Production Authority Secrets Root Is Invalid");
        }
        protectSecretRoot(secretRoot);
    }

    private void rejectLegacyCombinedFile() throws IOException {
        if (pathExists(legacyCombinedKeyPath) && !legacyCombinedKeyPath.equals(privateKeyPath)) {
            throw new MigrationException("Production Authority Signing Key Uses A Retired Combined Storage Format");
        }
    }

    private void requirePrivateKeyFile(Path path, String description) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException(description + " Is Not A Regular File");
        }
        protectPrivateKey(path);
    }

    private StartupInventory inspectStartupInventory() throws IOException {
        ensureSecretRoot();
        TrustAnchorInventory anchorInventory = inspectTrustAnchorInventory();
        boolean evidencePresent = validateQuarantine();
        List<Path> temporaryFiles = new ArrayList<>();
        boolean privatePresent = false;
        try (var stream = Files.list(secretRoot)) {
            List<Path> entries = stream.sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (Files.isSymbolicLink(entry)) {
                    throw new MigrationException("Production Authority Secrets Root Contains A Symbolic Link");
                }
                if (name.equals(QUARANTINE_DIRECTORY)) {
                    if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                        throw new MigrationException("Production Authority Quarantine Root Is Invalid");
                    }
                    continue;
                }
                if (name.equals(LOCK_FILE)) {
                    requirePrivateKeyFile(entry, "Production Authority Secrets Lock");
                    continue;
                }
                if (name.equals(privateKeyPath.getFileName().toString())) {
                    requirePrivateKeyFile(entry, "Production Authority Signing Key");
                    privatePresent = true;
                    continue;
                }
                if (isCanonicalPrivateKeyName(name)) {
                    requireProtectedPrivateKeyFile(entry, "Production Authority Peer Signing Key");
                    continue;
                }
                if (isCanonicalTemporaryName(name)) {
                    requirePrivateKeyFile(entry, "Production Authority Signing Key Temporary File");
                    temporaryFiles.add(entry);
                    continue;
                }
                if (isCanonicalAnyTemporaryName(name)) {
                    requireProtectedPrivateKeyFile(entry, "Production Authority Peer Signing Key Temporary File");
                    continue;
                }
                throw new MigrationException("Production Authority Secrets Root Contains An Unknown Artifact");
            }
        }
        return new StartupInventory(privatePresent, List.copyOf(temporaryFiles), evidencePresent,
            anchorInventory.anchorPresent(), anchorInventory.temporaryFiles());
    }

    private TrustAnchorInventory inspectTrustAnchorInventory() throws IOException {
        Path anchor = trustAnchorPath();
        Path parent = anchor.getParent();
        if (parent == null) {
            throw new MigrationException("Production Authority Trust Anchor Has No Parent");
        }
        ensurePublicAuthorityDirectory(parent);
        List<Path> temporaryFiles = new ArrayList<>();
        boolean anchorPresent = false;
        try (var stream = Files.list(parent)) {
            for (Path entry : stream.sorted(Comparator.comparing(path -> path.getFileName().toString())).toList()) {
                String name = entry.getFileName().toString();
                if (Files.isSymbolicLink(entry)) {
                    throw new MigrationException("Production Authority Trust Anchor Directory Contains A Symbolic Link");
                }
                if (name.equals(TRUST_ANCHOR_FILE)) {
                    requirePrivateKeyFile(entry, "Production Authority Trust Anchor");
                    anchorPresent = true;
                    continue;
                }
                if (name.equals(ProductionAuthorityBundle.AUTHORITY_FILE)) {
                    if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                        throw new MigrationException("Production Authority Bundle Is Not A Regular File");
                    }
                    continue;
                }
                if (isCanonicalTrustAnchorTemporaryName(name)) {
                    requirePrivateKeyFile(entry, "Production Authority Trust Anchor Temporary File");
                    temporaryFiles.add(entry);
                    continue;
                }
                throw new MigrationException("Production Authority Trust Anchor Directory Contains An Unknown Artifact");
            }
        }
        return new TrustAnchorInventory(anchorPresent, List.copyOf(temporaryFiles));
    }

    private boolean validateQuarantine() throws IOException {
        Path root = secretRoot.resolve(QUARANTINE_DIRECTORY).toAbsolutePath().normalize();
        if (!pathExists(root)) {
            return false;
        }
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Production Authority Quarantine Root Is Invalid");
        }
        MigrationPaths.requireNoSymlinkTraversal(secretRoot, root);
        boolean evidencePresent = false;
        try (var stream = Files.list(root)) {
            for (Path entry : stream.sorted(Comparator.comparing(path -> path.getFileName().toString())).toList()) {
                String name = entry.getFileName().toString();
                if (!name.equals(QUARANTINE_CATEGORY)) {
                    throw new MigrationException("Production Authority Quarantine Root Contains An Unknown Artifact");
                }
                if (Files.isSymbolicLink(entry) || !Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    throw new MigrationException("Production Authority Quarantine Directory Is Invalid");
                }
                validateEvidenceDirectory(entry);
                try (var evidence = Files.list(entry)) {
                    evidencePresent |= evidence.findAny().isPresent();
                }
            }
        }
        return evidencePresent;
    }

    private void validateEvidenceDirectory(Path directory) throws IOException {
        MigrationPaths.requireNoSymlinkTraversal(secretRoot, directory);
        try (var stream = Files.list(directory)) {
            for (Path entry : stream.sorted(Comparator.comparing(path -> path.getFileName().toString())).toList()) {
                if (Files.isSymbolicLink(entry) || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                    || !isEvidenceName(entry.getFileName().toString())) {
                    throw new MigrationException("Production Authority Quarantine Contains An Invalid Artifact");
                }
            }
        }
    }

    private void moveToEvidence(Path source, String description) throws IOException {
        requirePrivateKeyFile(source, description);
        StableFile original = readStableFile(source, description);
        Path sourceParent = source.getParent();
        if (sourceParent == null) {
            throw new MigrationException(description + " Has No Parent");
        }
        if (forceDirectory(sourceParent) == DirectoryForceOutcome.UNSUPPORTED) {
            return;
        }
        Path quarantine = ensureQuarantineDirectory();
        String sourceName = source.getFileName().toString();
        Path evidence = reserveEvidencePath(quarantine, sourceName);
        try {
            Files.createLink(evidence, source);
        } catch (UnsupportedOperationException exception) {
            return;
        } catch (FileAlreadyExistsException exception) {
            moveToEvidence(source, description);
            return;
        }
        verifyEvidenceFile(evidence, original);
        protectPrivateKey(evidence);
        force(evidence);
        Path quarantineRoot = secretRoot.resolve(QUARANTINE_DIRECTORY);
        if (!forceDirectories(quarantine, quarantineRoot, secretRoot, sourceParent)) {
            return;
        }
        StableFile currentSource = readStableFile(source, description);
        if (!original.state().contentHash().equals(currentSource.state().contentHash())
            || !Arrays.equals(original.bytes(), currentSource.bytes())
            || !sameFileIdentity(original.state().stamp(), currentSource.state().stamp())) {
            throw new MigrationException(description + " Changed During Quarantine");
        }
        verifyEvidenceFile(evidence, original);
        Files.delete(source);
        try {
            if (forceDirectory(sourceParent) == DirectoryForceOutcome.UNSUPPORTED) {
                restoreEvidenceSource(source, evidence, original, description);
            }
        } catch (IOException exception) {
            try {
                restoreEvidenceSource(source, evidence, original, description);
            } catch (IOException restoreFailure) {
                exception.addSuppressed(restoreFailure);
            }
            throw exception;
        }
    }

    private void verifyEvidenceFile(Path evidence, StableFile original) throws IOException {
        StableFile current = readStableFile(evidence, "Production Authority Evidence");
        if (!original.state().contentHash().equals(current.state().contentHash())
            || !Arrays.equals(original.bytes(), current.bytes())
            || !sameFileIdentity(original.state().stamp(), current.state().stamp())) {
            throw new MigrationException("Production Authority Evidence Verification Failed");
        }
    }

    private Path reserveEvidencePath(Path quarantine, String sourceName) throws IOException {
        for (int attempt = 0; attempt < MAXIMUM_RESERVATION_ATTEMPTS; attempt++) {
            Path candidate = quarantine.resolve(sourceName + "." + UUID.randomUUID() + EVIDENCE_SUFFIX)
                .toAbsolutePath().normalize();
            if (!isEvidenceName(candidate.getFileName().toString())) {
                throw new MigrationException("Production Authority Evidence Name Is Invalid");
            }
            if (!pathExists(candidate)) {
                return candidate;
            }
        }
        throw new MigrationException("Unable To Reserve Production Authority Evidence Path");
    }

    private void restoreEvidenceSource(Path source, Path evidence, StableFile original, String description)
        throws IOException {
        if (!pathExists(source)) {
            try {
                Files.createLink(source, evidence);
            } catch (UnsupportedOperationException exception) {
                throw new MigrationException("Production Authority Evidence Source Link Is Unavailable", exception);
            } catch (FileAlreadyExistsException exception) {
                throw new MigrationException("Production Authority Evidence Source Path Changed", exception);
            }
        }
        StableFile restored = readStableFile(source, description);
        if (!original.state().contentHash().equals(restored.state().contentHash())
            || !Arrays.equals(original.bytes(), restored.bytes())
            || !sameFileIdentity(original.state().stamp(), restored.state().stamp())) {
            throw new MigrationException("Production Authority Evidence Source Restoration Failed");
        }
    }

    private Path ensureQuarantineDirectory() throws IOException {
        Path root = secretRoot.resolve(QUARANTINE_DIRECTORY).toAbsolutePath().normalize();
        Path directory = root.resolve(QUARANTINE_CATEGORY).toAbsolutePath().normalize();
        if (!pathExists(root)) {
            Files.createDirectory(root);
        }
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Production Authority Quarantine Root Is Invalid");
        }
        if (!pathExists(directory)) {
            Files.createDirectory(directory);
        }
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Production Authority Quarantine Directory Is Invalid");
        }
        protectSecretRoot(root);
        protectSecretRoot(directory);
        validateEvidenceDirectory(directory);
        return directory;
    }

    private void verifyCachedKey() throws IOException {
        KeyFileState expected = keyFileState;
        if (expected == null) {
            throw new MigrationException("Production Authority Signing Key State Is Missing");
        }
        verifyKeyState(expected);
    }

    private void verifyKeyState(KeyFileState expected) throws IOException {
        StableFile current = readStableFile(privateKeyPath, "Production Authority Signing Key");
        if (!expected.contentHash().equals(current.state().contentHash())
            || !sameFileIdentity(expected.stamp(), current.state().stamp())) {
            throw new MigrationException("Production Authority Signing Key Changed Outside The Persistence Boundary");
        }
    }

    private void validatePair(ProductionAuthorityTrustAnchor anchor, PublicKey publicKey, PrivateKey privateKey)
        throws IOException {
        if (!anchor.publicKey().equals(Base64.getEncoder().encodeToString(publicKey.getEncoded()))
            || !anchor.publicKeyFingerprint().equals(ProductionAuthorityTrustAnchor.fingerprint(publicKey))) {
            throw new MigrationException("Production Authority Trust Anchor Does Not Match The Signing Key");
        }
        try {
            Signature signer = Signature.getInstance(ProductionAuthorityBundle.SIGNATURE_ALGORITHM);
            signer.initSign(privateKey);
            signer.update(KEY_CHECK_PAYLOAD);
            byte[] signature = signer.sign();
            Signature verifier = Signature.getInstance(ProductionAuthorityBundle.SIGNATURE_ALGORITHM);
            verifier.initVerify(publicKey);
            verifier.update(KEY_CHECK_PAYLOAD);
            if (!verifier.verify(signature)) {
                throw new MigrationException("Production Authority Private Key Does Not Match The Public Key");
            }
        } catch (GeneralSecurityException exception) {
            throw new MigrationException("Production Authority Signing Key Is Invalid", exception);
        }
    }

    private static PublicKey parsePublicKey(String value) throws GeneralSecurityException {
        return KeyFactory.getInstance(ProductionAuthorityBundle.SIGNATURE_ALGORITHM).generatePublic(
            new X509EncodedKeySpec(Base64.getDecoder().decode(value)));
    }

    private <T> T withExclusiveLock(IoOperation<T> operation) throws IOException {
        Object monitor = JVM_LOCKS.computeIfAbsent(secretRoot, ignored -> new Object());
        synchronized (monitor) {
            ensureIdentityStable();
            ensureSecretRoot();
            if (pathExists(lockPath) && (Files.isSymbolicLink(lockPath)
                || !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS))) {
                throw new MigrationException("Production Authority Secrets Lock Is Invalid");
            }
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 FileLock ignored = channel.lock()) {
                protectPrivateKey(lockPath);
                channel.force(true);
                return operation.run();
            }
        }
    }

    private StableFile readStableFile(Path path, String description) throws IOException {
        requirePrivateKeyFile(path, description);
        FileStamp before = readFileStamp(path, description);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new IOException(description + " Is Unavailable", exception);
        }
        FileStamp after = readFileStamp(path, description);
        if (!sameFileIdentity(before, after)) {
            throw new MigrationException(description + " Changed During Read");
        }
        return new StableFile(bytes, new KeyFileState(after,
            CanonicalHash.rawSha256(bytes)));
    }

    private static FileStamp readFileStamp(Path path, String description) throws IOException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) {
                throw new MigrationException(description + " Is Not A Regular File");
            }
            return new FileStamp(attributes.fileKey(), attributes.size(), attributes.creationTime(),
                attributes.lastModifiedTime());
        } catch (NoSuchFileException exception) {
            throw new MigrationException(description + " Is Missing", exception);
        }
    }

    private static boolean sameFileIdentity(FileStamp first, FileStamp second) {
        if (first.fileKey() != null || second.fileKey() != null) {
            if (!Objects.equals(first.fileKey(), second.fileKey())) {
                return false;
            }
        }
        return first.size() == second.size() && first.creationTime().equals(second.creationTime())
            && first.lastModifiedTime().equals(second.lastModifiedTime());
    }

    private static boolean pathExists(Path path) throws IOException {
        try {
            Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return true;
        } catch (NoSuchFileException exception) {
            return false;
        }
    }

    private boolean isCanonicalTemporaryName(String name) {
        String targetName = privateKeyPath.getFileName().toString();
        String prefix = targetName + ".";
        if (name == null || !name.startsWith(prefix) || !name.endsWith(PRIVATE_KEY_TEMP_SUFFIX)) {
            return false;
        }
        String uuid = name.substring(prefix.length(), name.length() - PRIVATE_KEY_TEMP_SUFFIX.length());
        return isCanonicalUuid(uuid);
    }

    private static boolean isCanonicalPrivateKeyName(String name) {
        if (name == null || name.length() != PRIVATE_KEY_FILE_PREFIX.length() + 64 + PRIVATE_KEY_FILE_SUFFIX.length()
            || !name.startsWith(PRIVATE_KEY_FILE_PREFIX) || !name.endsWith(PRIVATE_KEY_FILE_SUFFIX)) {
            return false;
        }
        int hashStart = PRIVATE_KEY_FILE_PREFIX.length();
        int hashEnd = hashStart + 64;
        for (int index = hashStart; index < hashEnd; index++) {
            if (!isLowercaseHex(name.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private boolean isEvidenceName(String name) {
        if (name == null || !name.endsWith(EVIDENCE_SUFFIX)) {
            return false;
        }
        int uuidEnd = name.length() - EVIDENCE_SUFFIX.length();
        int uuidStart = uuidEnd - 36;
        int separator = uuidStart - 1;
        if (separator < 0 || name.charAt(separator) != '.') {
            return false;
        }
        String source = name.substring(0, separator);
        String uuid = name.substring(uuidStart, uuidEnd);
        return (isCanonicalAnyTemporaryName(source) || isCanonicalTrustAnchorTemporaryName(source)
            || source.equals(privateKeyPath.getFileName().toString()))
            && isCanonicalUuid(uuid);
    }

    private static boolean isCanonicalTrustAnchorTemporaryName(String name) {
        String prefix = TRUST_ANCHOR_FILE + ".";
        if (name == null || !name.startsWith(prefix) || !name.endsWith(TRUST_ANCHOR_TEMP_SUFFIX)) {
            return false;
        }
        String uuid = name.substring(prefix.length(), name.length() - TRUST_ANCHOR_TEMP_SUFFIX.length());
        return isCanonicalUuid(uuid);
    }

    private static boolean isCanonicalAnyTemporaryName(String name) {
        if (name == null || !name.startsWith(PRIVATE_KEY_FILE_PREFIX) || !name.endsWith(PRIVATE_KEY_TEMP_SUFFIX)) {
            return false;
        }
        int hashStart = PRIVATE_KEY_FILE_PREFIX.length();
        int hashEnd = hashStart + 64;
        int baseEnd = hashEnd + PRIVATE_KEY_FILE_SUFFIX.length();
        if (name.length() < baseEnd + 1 + 36 + PRIVATE_KEY_TEMP_SUFFIX.length()
            || name.length() < baseEnd || !name.startsWith(PRIVATE_KEY_FILE_SUFFIX, hashEnd)
            || name.charAt(baseEnd) != '.') {
            return false;
        }
        String hash = name.substring(hashStart, hashEnd);
        for (int index = 0; index < hash.length(); index++) {
            if (!isLowercaseHex(hash.charAt(index))) {
                return false;
            }
        }
        String uuid = name.substring(baseEnd + 1, name.length() - PRIVATE_KEY_TEMP_SUFFIX.length());
        return isCanonicalUuid(uuid);
    }

    private static boolean isCanonicalUuid(String value) {
        if (value == null || value.length() != 36) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (index == 8 || index == 13 || index == 18 || index == 23) {
                if (character != '-') {
                    return false;
                }
            } else if (!isLowercaseHex(character)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLowercaseHex(char value) {
        return value >= '0' && value <= '9' || value >= 'a' && value <= 'f';
    }

    private static String decodeUtf8(byte[] bytes, String description) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new MigrationException(description + " Encoding Is Invalid", exception);
        }
    }

    private static void writeForced(Path path, byte[] content) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(content);
            while (buffer.hasRemaining()) {
                int before = buffer.position();
                int written = channel.write(buffer);
                if (written <= 0 || buffer.position() == before) {
                    throw new IOException("Production Authority Atomic File Write Made No Progress");
                }
            }
            channel.force(true);
        }
    }

    private static void force(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            channel.force(true);
        }
    }

    private DirectoryForceOutcome forceDirectory(Path path) throws IOException {
        DirectoryForceOutcome outcome = directoryForce.force(path);
        if (outcome == null) {
            throw new MigrationException("Production Authority Directory Force Capability Is Unknown");
        }
        return outcome;
    }

    private boolean forceDirectories(Path... paths) throws IOException {
        for (Path path : paths) {
            if (forceDirectory(path) == DirectoryForceOutcome.UNSUPPORTED) {
                return false;
            }
        }
        return true;
    }

    private static DirectoryForceOutcome forceDirectorySystem(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            channel.force(true);
            return DirectoryForceOutcome.SUPPORTED;
        } catch (UnsupportedOperationException exception) {
            return DirectoryForceOutcome.UNSUPPORTED;
        } catch (AccessDeniedException exception) {
            if (isWindows()) {
                return DirectoryForceOutcome.UNSUPPORTED;
            }
            throw exception;
        } catch (FileSystemException exception) {
            if (isUnsupportedDirectoryForce(exception)) {
                return DirectoryForceOutcome.UNSUPPORTED;
            }
            throw exception;
        }
    }

    private static boolean isUnsupportedDirectoryForce(FileSystemException exception) {
        String details = (Objects.toString(exception.getReason(), "") + " "
            + Objects.toString(exception.getMessage(), "")).toLowerCase(Locale.ROOT);
        return details.contains("not supported") || details.contains("unsupported")
            || details.contains("incorrect function")
            || (isWindows() && (details.contains("access is denied")
                || details.contains("parameter is incorrect")));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean isKeyMismatch(MigrationException exception) {
        String message = exception.getMessage();
        return "Production Authority Private Key Does Not Match The Public Key".equals(message)
            || "Production Authority Trust Anchor Does Not Match The Signing Key".equals(message);
    }

    private static void protectSecretRoot(Path path) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            posix.setPermissions(SECRET_ROOT_POSIX_PERMISSIONS);
            if (!SECRET_ROOT_POSIX_PERMISSIONS.equals(posix.readAttributes().permissions())) {
                throw new MigrationException("Production Authority Secrets Root Permissions Are Not Restrictive");
            }
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS);
        if (acl != null) {
            UserPrincipal owner = Objects.requireNonNull(acl.getOwner(), "Production Authority Secrets Root Owner Is Missing");
            AclEntry entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                .setPermissions(PRIVATE_KEY_ACL_PERMISSIONS).build();
            acl.setAcl(List.of(entry));
            List<AclEntry> entries = acl.getAcl();
            if (entries.size() != 1 || entries.getFirst().type() != AclEntryType.ALLOW
                || !owner.equals(entries.getFirst().principal())
                || !PRIVATE_KEY_ACL_PERMISSIONS.equals(entries.getFirst().permissions())) {
                throw new MigrationException("Production Authority Secrets Root Permissions Are Not Restrictive");
            }
            return;
        }
        throw new MigrationException("Production Authority Secrets Root Storage Cannot Be Secured");
    }

    private static void protectPrivateKey(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Production Authority Signing Key Is Not A Regular File");
        }
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            posix.setPermissions(PRIVATE_KEY_POSIX_PERMISSIONS);
            if (!PRIVATE_KEY_POSIX_PERMISSIONS.equals(posix.readAttributes().permissions())) {
                throw new MigrationException("Production Authority Signing Key Permissions Are Not Restrictive");
            }
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS);
        if (acl != null) {
            UserPrincipal owner = Objects.requireNonNull(acl.getOwner(), "Production Authority Signing Key Owner Is Missing");
            AclEntry entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                .setPermissions(PRIVATE_KEY_ACL_PERMISSIONS).build();
            acl.setAcl(List.of(entry));
            List<AclEntry> entries = acl.getAcl();
            if (entries.size() != 1 || entries.getFirst().type() != AclEntryType.ALLOW
                || !owner.equals(entries.getFirst().principal())
                || !PRIVATE_KEY_ACL_PERMISSIONS.equals(entries.getFirst().permissions())) {
                throw new MigrationException("Production Authority Signing Key Permissions Are Not Restrictive");
            }
            return;
        }
        throw new MigrationException("Production Authority Signing Key Storage Cannot Be Secured");
    }

    private static void requireProtectedPrivateKeyFile(Path path, String description) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException(description + " Is Not A Regular File");
        }
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            if (!PRIVATE_KEY_POSIX_PERMISSIONS.equals(posix.readAttributes().permissions())) {
                throw new MigrationException(description + " Permissions Are Not Restrictive");
            }
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS);
        if (acl != null) {
            UserPrincipal owner = Objects.requireNonNull(acl.getOwner(), description + " Owner Is Missing");
            List<AclEntry> entries = acl.getAcl();
            if (entries.size() != 1 || entries.getFirst().type() != AclEntryType.ALLOW
                || !owner.equals(entries.getFirst().principal())
                || !PRIVATE_KEY_ACL_PERMISSIONS.equals(entries.getFirst().permissions())) {
                throw new MigrationException(description + " Permissions Are Not Restrictive");
            }
            return;
        }
        throw new MigrationException(description + " Storage Cannot Be Secured");
    }

    private Path dataRoot() throws IOException {
        return MigrationPaths.requireDirectory(dataRootPath, "production authority data root");
    }

    private void ensureIdentityStable() throws IOException {
        Path currentScope = MigrationPaths.requirePath(identity.rebindScope(), "identityScope");
        Path currentPath = MigrationPaths.requirePath(identity.path(), "identityPath");
        if (!identityScope.equals(currentScope) || !identityPath.equals(currentPath)
            || !authorityServerId.equals(identity.serverId())) {
            throw new MigrationException("Production Authority Identity Changed Outside The Persistence Boundary");
        }
        identity.healthCheck();
    }

    private record StartupInventory(boolean privatePresent, List<Path> temporaryFiles, boolean evidencePresent,
                                    boolean anchorPresent, List<Path> trustAnchorTemporaryFiles) {
    }

    private record TrustAnchorInventory(boolean anchorPresent, List<Path> temporaryFiles) {
    }

    private record FileStamp(Object fileKey, long size, FileTime creationTime, FileTime lastModifiedTime) {
    }

    private record KeyFileState(FileStamp stamp, String contentHash) {
    }

    private record StableFile(byte[] bytes, KeyFileState state) {
    }

    private record LoadedPrivateKey(KeyPair pair, KeyFileState state) {
    }

    private record AuthorityMaterial(KeyPair pair, KeyFileState state, ProductionAuthorityTrustAnchor anchor) {
    }

    private record LoadKey(ServerIdentityStore identity, Path identityPath, Path privateKeyPath, ServerId serverId,
                           DirectoryForce directoryForce, InitialLoadObserver initialLoadObserver) {
    }

    private record Candidate(Path path, LoadedPrivateKey loaded) {
    }

    private record AnchorCandidate(Path path, ProductionAuthorityTrustAnchor anchor) {
    }

    enum DirectoryForceOutcome {
        SUPPORTED,
        UNSUPPORTED
    }

    @FunctionalInterface
    interface DirectoryForce {
        DirectoryForceOutcome force(Path path) throws IOException;

        static DirectoryForce system() {
            return SYSTEM_DIRECTORY_FORCE;
        }
    }

    interface InitialLoadObserver {
        default void leaderStarted() throws IOException {
        }

        default void followerJoined() {
        }

        default void finished() {
        }
    }

    @FunctionalInterface
    private interface IoOperation<T> {
        T run() throws IOException;
    }
}
