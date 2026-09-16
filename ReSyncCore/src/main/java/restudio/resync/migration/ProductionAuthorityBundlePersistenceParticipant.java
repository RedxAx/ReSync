package restudio.resync.migration;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import restudio.resync.flow.identity.ServerId;

public final class ProductionAuthorityBundlePersistenceParticipant implements RebindablePersistenceParticipant,
    PersistenceOwnershipProvider {
    public static final String OWNER = "resync.authority-bundle";
    public static final String DIRECTORY = ProductionAuthorityBundle.AUTHORITY_DIRECTORY;
    public static final String TRUST_ANCHOR_FILE = "authority-trust-anchor.json";
    private static final String AUTHORITY_QUARANTINE_DIRECTORY = ".quarantine/authority-bundle";
    private static final int SHA256_HEX_LENGTH = 64;
    private static final String JSON_SUFFIX = ".json";
    private static final String TRUST_ANCHOR_TEMP_SUFFIX = ".tmp";
    private static final long MAX_TRUST_ANCHOR_BYTES = 64 * 1024;

    private volatile Path root;
    private final ProductionAuthorityBundle.TrustedAuthority trustedAuthority;
    private final ProductionAuthorityBundle.TrustContext trustContext;
    private final boolean bundleRequired;
    private final boolean freshInstall;
    private volatile boolean quiesced;
    private boolean reboundMissingDerivedOutput;

    public ProductionAuthorityBundlePersistenceParticipant(Path dataRoot) throws IOException {
        this(dataRoot, null, null, false, false);
    }

    public ProductionAuthorityBundlePersistenceParticipant(Path dataRoot,
                                                           ProductionAuthorityBundle.TrustedAuthority trustedAuthority) throws IOException {
        this(dataRoot, trustedAuthority, null, true, false);
    }

    public ProductionAuthorityBundlePersistenceParticipant(Path dataRoot,
                                                           ProductionAuthorityBundle.TrustedAuthority trustedAuthority,
                                                           boolean freshInstall) throws IOException {
        this(dataRoot, trustedAuthority, null, true, freshInstall);
    }

    public ProductionAuthorityBundlePersistenceParticipant(Path dataRoot,
                                                           ProductionAuthorityBundle.TrustedAuthority trustedAuthority,
                                                           boolean bundleRequired,
                                                           boolean freshInstall) throws IOException {
        this(dataRoot, trustedAuthority, null, bundleRequired, freshInstall);
    }

    public ProductionAuthorityBundlePersistenceParticipant(Path dataRoot,
                                                           ServerId serverId,
                                                           String trustedPublicKey) throws IOException {
        this(dataRoot, ProductionAuthorityBundle.TrustedAuthority.from(dataRoot, serverId, trustedPublicKey));
    }

    private ProductionAuthorityBundlePersistenceParticipant(Path dataRoot,
                                                            ProductionAuthorityBundle.TrustedAuthority trustedAuthority,
                                                            ProductionAuthorityBundle.TrustContext trustContext,
                                                            boolean bundleRequired,
                                                            boolean freshInstall) throws IOException {
        Path normalized = MigrationPaths.requireDirectory(dataRoot, "dataRoot");
        root = MigrationPaths.resolveInside(normalized, DIRECTORY);
        this.trustedAuthority = trustedAuthority;
        this.trustContext = trustContext;
        this.bundleRequired = bundleRequired;
        this.freshInstall = freshInstall;
        Files.createDirectories(root);
        validateRoot(root);
        ensureTrustAnchor(root);
    }

    public ProductionAuthorityBundlePersistenceParticipant(Path dataRoot,
                                                           ProductionAuthorityBundle.TrustContext trustContext) throws IOException {
        this(dataRoot, trustContext == null ? null : trustContext.installationAuthority(), trustContext, true, false);
    }

    public ProductionAuthorityBundlePersistenceParticipant(Path dataRoot,
                                                           ProductionAuthorityBundle.TrustContext trustContext,
                                                           boolean freshInstall) throws IOException {
        this(dataRoot, trustContext == null ? null : trustContext.installationAuthority(), trustContext, true, freshInstall);
    }

    @Override
    public String owner() {
        return OWNER;
    }

    @Override
    public Path root() {
        return root;
    }

    @Override
    public Path rebindScope() {
        return MigrationPaths.requirePath(root.getParent(), "dataRoot");
    }

    @Override
    public PersistenceParticipantClassification classification() {
        return PersistenceParticipantClassification.DERIVED_CACHE;
    }

    @Override
    public PersistenceOwnershipIndex ownershipIndex(PersistenceOwnershipContext context) {
        return PersistenceOwnershipIndex.builder(context)
            .subtreeRoot()
            .rootSiblingHashJson(AUTHORITY_QUARANTINE_DIRECTORY)
            .build();
    }

    @Override
    public boolean owns(Path file) {
        Path candidate = MigrationPaths.requirePath(file, "file");
        Path currentRoot = MigrationPaths.requirePath(root, "authority participant root");
        if (candidate.startsWith(currentRoot)) {
            return true;
        }
        Path dataRoot = currentRoot.getParent();
        if (dataRoot == null || candidate.getParent() == null) {
            return false;
        }
        Path quarantineRoot = dataRoot.resolve(AUTHORITY_QUARANTINE_DIRECTORY).toAbsolutePath().normalize();
        return candidate.getParent().equals(quarantineRoot)
            && isAuthorityQuarantineFileName(candidate.getFileName().toString());
    }

    public boolean bundleRequired() {
        return bundleRequired;
    }

    public boolean requiresBundle() {
        return bundleRequired;
    }

    public Path trustAnchorPath() {
        return trustAnchor(root);
    }

    public Health health() {
        Path currentRoot = root;
        try {
            validateRoot(currentRoot);
            ProductionAuthorityTrustAnchor anchor = requireTrustAnchor(currentRoot);
            if (bundleRequired && trustedAuthority == null) {
                return Health.regenerationRequired("Trusted Installation Authority Context Is Unavailable");
            }
            if (!bundleRequired) {
                return Health.availableWithoutBundle();
            }
            Path target = target(currentRoot);
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                return Health.regenerationRequired("Production Authority Bundle Is Missing And Must Be Regenerated");
            }
            ProductionAuthorityBundle bundle = ProductionAuthorityBundle.read(target);
            if (!authenticated(bundle, currentRoot.getParent(), anchor)) {
                return Health.regenerationRequired("Production Authority Bundle Is Foreign, Stale, Tampered, Or Not Trusted");
            }
            if (!bundle.freshAt(Instant.now())) {
                return Health.regenerationRequired("Production Authority Bundle Is Stale And Must Be Regenerated");
            }
            return Health.available(bundle);
        } catch (IOException | RuntimeException exception) {
            return Health.regenerationRequired(reason(exception));
        }
    }

    public Optional<ProductionAuthorityBundle> currentBundle() {
        Health current = health();
        return Optional.ofNullable(current.bundle());
    }

    @Override
    public synchronized void flush() throws IOException {
        requireLifecycleRoot(root);
    }

    @Override
    public synchronized void quiesce() throws IOException {
        requireLifecycleRoot(root);
        quiesced = true;
    }

    @Override
    public synchronized void resume() throws IOException {
        requireLifecycleRoot(root);
        quiesced = false;
    }

    @Override
    public synchronized void rebind(Path activeRoot) throws IOException {
        if (!quiesced) {
            throw new MigrationException("Authority Bundle Participant Must Be Quiesced Before Rebind");
        }
        Path candidateRoot = MigrationPaths.requireDirectory(activeRoot, "activeRoot");
        Path candidateAuthorityRoot = MigrationPaths.resolveInside(candidateRoot, DIRECTORY);
        Files.createDirectories(candidateAuthorityRoot);
        Path previousRoot = root;
        boolean previousMissingDerivedOutput = reboundMissingDerivedOutput;
        root = candidateAuthorityRoot;
        try {
            reboundMissingDerivedOutput = requireRebindCandidateRoot(root);
        } catch (IOException | RuntimeException exception) {
            root = previousRoot;
            reboundMissingDerivedOutput = previousMissingDerivedOutput;
            try {
                if (previousMissingDerivedOutput) {
                    requireRebindCandidateRoot(previousRoot);
                } else {
                    requireHealthyOrAbsentRoot(previousRoot);
                }
            } catch (IOException | RuntimeException rollbackFailure) {
                exception.addSuppressed(rollbackFailure);
            }
            throw exception;
        }
    }

    @Override
    public synchronized void healthCheck() throws IOException {
        requireHealthyRoot(root);
    }

    @Override
    public synchronized void readinessCheck() throws IOException {
        requireHealthyRoot(root);
    }

    private void requireHealthyOrAbsentRoot(Path root) throws IOException {
        if (freshBootstrapRoot(root)) {
            return;
        }
        requireHealthyRoot(root);
    }

    private void requireLifecycleRoot(Path root) throws IOException {
        if (!reboundMissingDerivedOutput) {
            requireHealthyOrAbsentRoot(root);
            return;
        }
        reboundMissingDerivedOutput = requireRebindCandidateRoot(root);
    }

    private boolean requireRebindCandidateRoot(Path root) throws IOException {
        validateRoot(root);
        List<Path> entries;
        try (var children = Files.list(root)) {
            entries = children.toList();
        }
        if (entries.isEmpty()) {
            if (!bundleRequired && trustedAuthority != null) {
                ensureTrustAnchor(root);
                requireHealthyRoot(root);
                return false;
            }
            return true;
        }
        boolean trustAnchorTemporaryEvidence = false;
        for (Path entry : entries) {
            String name = entry.getFileName().toString();
            if (!TRUST_ANCHOR_FILE.equals(name) && !ProductionAuthorityBundle.AUTHORITY_FILE.equals(name)
                && !isCanonicalTrustAnchorTemporaryName(name)) {
                throw new MigrationException("Authority Bundle Rebind Candidate Contains An Unexpected Entry: " + name);
            }
            if (Files.isSymbolicLink(entry) || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("Authority Bundle Rebind Candidate Contains An Invalid Entry");
            }
            if (isCanonicalTrustAnchorTemporaryName(name)) {
                trustAnchorTemporaryEvidence = true;
            }
        }
        if (trustAnchorTemporaryEvidence) {
            if (trustedAuthority == null) {
                throw new MigrationException("Authority Bundle Rebind Trust Anchor Evidence Requires A Trusted Installation Authority");
            }
            ProductionAuthorityTrustAnchor activeAnchor = requireTrustAnchor(root);
            Path dataRoot = MigrationPaths.requirePath(root.getParent(), "dataRoot");
            for (Path entry : entries) {
                if (isCanonicalTrustAnchorTemporaryName(entry.getFileName().toString())) {
                    requireTrustAnchorTemporaryEvidence(entry, dataRoot, activeAnchor);
                }
            }
        }
        requireHealthyOrAbsentRoot(root);
        return false;
    }

    private void requireTrustAnchorTemporaryEvidence(Path path, Path dataRoot,
                                                     ProductionAuthorityTrustAnchor activeAnchor) throws IOException {
        MigrationPaths.requireNoSymlinkTraversal(dataRoot, path);
        String canonical = readStableUtf8(path, "Authority Bundle Rebind Trust Anchor Evidence");
        ProductionAuthorityTrustAnchor evidence;
        try {
            evidence = ProductionAuthorityTrustAnchor.fromCanonical(canonical);
        } catch (RuntimeException exception) {
            throw new MigrationException("Authority Bundle Rebind Trust Anchor Evidence Is Invalid", exception);
        }
        if (!evidence.canonical().equals(canonical)) {
            throw new MigrationException("Authority Bundle Rebind Trust Anchor Evidence Is Not Canonical");
        }
        if (!evidence.canonical().equals(activeAnchor.canonical())) {
            throw new MigrationException("Authority Bundle Rebind Trust Anchor Evidence Conflicts With The Active Trust Anchor");
        }
        if (!evidence.canonical().equals(trustedAuthority.trustAnchor().canonical())) {
            throw new MigrationException("Authority Bundle Rebind Trust Anchor Evidence Does Not Match The Trusted Installation Authority");
        }
        if (!evidence.matchesDurableInstall(dataRoot)) {
            throw new MigrationException("Authority Bundle Rebind Trust Anchor Evidence Is Not Bound To The Durable Installation");
        }
    }

    private void requireHealthyRoot(Path root) throws IOException {
        validateRoot(root);
        Path dataRoot = MigrationPaths.requirePath(root.getParent(), "dataRoot");
        ProductionAuthorityTrustAnchor anchor = requireTrustAnchor(root);
        if (!bundleRequired) {
            return;
        }
        if (trustedAuthority == null) {
            throw new MigrationException("Trusted Installation Authority Context Is Unavailable");
        }
        Path target = target(root);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Production Authority Bundle Is Missing And Must Be Regenerated");
        }
        ProductionAuthorityBundle bundle = ProductionAuthorityBundle.read(target);
        throwIfUnauthenticated(bundle, dataRoot, anchor);
    }

    private void throwIfUnauthenticated(ProductionAuthorityBundle bundle, Path dataRoot,
                                        ProductionAuthorityTrustAnchor anchor) throws IOException {
        if (!authenticated(bundle, dataRoot, anchor)
            || !bundle.freshAt(Instant.now())) {
            throw new MigrationException("Production Authority Bundle Is Unavailable And Must Be Regenerated");
        }
    }

    private boolean authenticated(ProductionAuthorityBundle bundle, Path dataRoot,
                                  ProductionAuthorityTrustAnchor anchor) {
        if (trustedAuthority == null) {
            return false;
        }
        if (trustContext != null) {
            try {
                if (!trustContext.sourceRoot().toRealPath().equals(dataRoot.toRealPath())) {
                    return false;
                }
            } catch (IOException exception) {
                return false;
            }
        }
        if (anchor == null || !anchor.canonical().equals(trustedAuthority.trustAnchor().canonical())) {
            return false;
        }
        return trustContext == null
            ? bundle.verifySignature(dataRoot, anchor)
            : bundle.verifySignature(trustContext);
    }

    private ProductionAuthorityTrustAnchor requireTrustAnchor(Path root) throws IOException {
        Path dataRoot = MigrationPaths.requirePath(root.getParent(), "dataRoot");
        Path path = trustAnchor(root);
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Production Authority Trust Anchor Is Not A Regular File");
        }
        MigrationPaths.requireNoSymlinkTraversal(dataRoot, path);
        String canonical = readStableUtf8(path, "Production Authority Trust Anchor");
        ProductionAuthorityTrustAnchor anchor;
        try {
            anchor = ProductionAuthorityTrustAnchor.fromCanonical(canonical);
        } catch (RuntimeException exception) {
            throw new MigrationException("Production Authority Trust Anchor Is Invalid", exception);
        }
        if (!anchor.canonical().equals(canonical) || !anchor.matchesDurableInstall(dataRoot)) {
            throw new MigrationException("Production Authority Trust Anchor Is Not Bound To The Durable Installation");
        }
        if (trustedAuthority != null && !anchor.canonical().equals(trustedAuthority.trustAnchor().canonical())) {
            throw new MigrationException("Production Authority Trust Anchor Does Not Match The Trusted Installation Authority");
        }
        return anchor;
    }

    private void ensureTrustAnchor(Path root) throws IOException {
        if (trustedAuthority == null) {
            return;
        }
        Path path = trustAnchor(root);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            requireTrustAnchor(root);
            return;
        }
        AtomicFiles.writeNew(path, trustedAuthority.trustAnchor().canonicalBytes());
    }

    private static void validateRoot(Path root) throws IOException {
        Path normalized = MigrationPaths.requirePath(root, "authority participant root");
        if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Authority Bundle Participant Root Is Unavailable");
        }
        Path target = target(normalized);
        if (Files.isSymbolicLink(target)
            || Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Authority Bundle Participant Target Is Invalid");
        }
        Path dataRoot = normalized.getParent();
        if (dataRoot == null) {
            throw new MigrationException("Authority Bundle Participant Root Has No Data Root");
        }
        validateAuthorityQuarantine(dataRoot);
    }

    private static void validateAuthorityQuarantine(Path dataRoot) throws IOException {
        Path normalizedDataRoot = MigrationPaths.requirePath(dataRoot, "dataRoot");
        Path quarantine = normalizedDataRoot.resolve(".quarantine").toAbsolutePath().normalize();
        if (!Files.exists(quarantine, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.notExists(quarantine, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("Authority Bundle Quarantine Directory Is Unavailable");
            }
            return;
        }
        if (Files.isSymbolicLink(quarantine) || !Files.isDirectory(quarantine, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Authority Bundle Quarantine Root Is Invalid");
        }
        Path authorityQuarantine = quarantine.resolve("authority-bundle").toAbsolutePath().normalize();
        if (!Files.exists(authorityQuarantine, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.notExists(authorityQuarantine, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("Authority Bundle Quarantine Directory Is Unavailable");
            }
            return;
        }
        if (Files.isSymbolicLink(authorityQuarantine)
            || !Files.isDirectory(authorityQuarantine, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Authority Bundle Quarantine Directory Is Invalid");
        }
        try (var entries = Files.list(authorityQuarantine)) {
            for (Path entry : entries.toList()) {
                if (Files.isSymbolicLink(entry) || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    throw new MigrationException("Authority Bundle Quarantine Contains An Invalid Entry: " + entry);
                }
                String fileName = entry.getFileName().toString();
                if (!isAuthorityQuarantineFileName(fileName)) {
                    throw new MigrationException("Authority Bundle Quarantine Contains An Unexpected Entry: " + entry);
                }
                byte[] content = Files.readAllBytes(entry);
                String expectedHash = fileName.substring(0, SHA256_HEX_LENGTH);
                if (!expectedHash.equals(MigrationCanonical.sha256(content))) {
                    throw new MigrationException("Authority Bundle Quarantine Content Hash Does Not Match Its File Name: " + entry);
                }
            }
        }
    }

    private static boolean isAuthorityQuarantineFileName(String fileName) {
        if (fileName == null || fileName.length() != SHA256_HEX_LENGTH + JSON_SUFFIX.length()
            || !fileName.endsWith(JSON_SUFFIX)) {
            return false;
        }
        for (int index = 0; index < SHA256_HEX_LENGTH; index++) {
            char character = fileName.charAt(index);
            if (!isLowercaseHex(character)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isCanonicalTrustAnchorTemporaryName(String fileName) {
        String prefix = TRUST_ANCHOR_FILE + ".";
        if (fileName == null || !fileName.startsWith(prefix) || !fileName.endsWith(TRUST_ANCHOR_TEMP_SUFFIX)) {
            return false;
        }
        String uuid = fileName.substring(prefix.length(), fileName.length() - TRUST_ANCHOR_TEMP_SUFFIX.length());
        if (uuid.length() != 36) {
            return false;
        }
        for (int index = 0; index < uuid.length(); index++) {
            char character = uuid.charAt(index);
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

    private static String readStableUtf8(Path path, String description) throws IOException {
        BasicFileAttributes before;
        byte[] bytes;
        try {
            before = readRegularFileAttributes(path, description);
            if (before.size() > MAX_TRUST_ANCHOR_BYTES) {
                throw new MigrationException(description + " Is Too Large");
            }
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes opened = readRegularFileAttributes(path, description);
                if (!sameFileIdentity(before, opened)) {
                    throw new MigrationException(description + " Changed During Read");
                }
                if (opened.size() > MAX_TRUST_ANCHOR_BYTES || opened.size() > Integer.MAX_VALUE) {
                    throw new MigrationException(description + " Is Too Large");
                }
                ByteBuffer buffer = ByteBuffer.allocate((int) opened.size());
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) < 0) {
                        throw new MigrationException(description + " Changed During Read");
                    }
                }
                ByteBuffer extra = ByteBuffer.allocate(1);
                if (channel.read(extra) >= 0) {
                    throw new MigrationException(description + " Changed During Read");
                }
                BasicFileAttributes after = readRegularFileAttributes(path, description);
                if (!sameFileIdentity(opened, after) || after.size() != buffer.position()) {
                    throw new MigrationException(description + " Changed During Read");
                }
                bytes = buffer.array();
            }
        } catch (NoSuchFileException exception) {
            throw new MigrationException(description + " Is Missing", exception);
        } catch (IOException exception) {
            if (exception instanceof MigrationException) {
                throw exception;
            }
            throw new MigrationException(description + " Is Unavailable", exception);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new MigrationException(description + " Encoding Is Invalid", exception);
        }
    }

    private static BasicFileAttributes readRegularFileAttributes(Path path, String description) throws IOException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) {
                throw new MigrationException(description + " Is Not A Regular File");
            }
            return attributes;
        } catch (NoSuchFileException exception) {
            throw new MigrationException(description + " Is Missing", exception);
        }
    }

    private static boolean sameFileIdentity(BasicFileAttributes first, BasicFileAttributes second) {
        if (first.fileKey() != null || second.fileKey() != null) {
            if (!Objects.equals(first.fileKey(), second.fileKey())) {
                return false;
            }
        }
        return first.size() == second.size() && first.creationTime().equals(second.creationTime())
            && first.lastModifiedTime().equals(second.lastModifiedTime());
    }

    private static boolean isLowercaseHex(char character) {
        return character >= '0' && character <= '9' || character >= 'a' && character <= 'f';
    }

    private boolean freshBootstrapRoot(Path root) throws IOException {
        if (!freshInstall) {
            return false;
        }
        Path normalized = MigrationPaths.requirePath(root, "authority participant root");
        if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try (var children = Files.list(normalized)) {
            List<Path> entries = children.toList();
            if (entries.size() != 1) {
                return false;
            }
            Path trustAnchor = entries.getFirst();
            boolean bootstrap = TRUST_ANCHOR_FILE.equals(trustAnchor.getFileName().toString())
                && !Files.isSymbolicLink(trustAnchor)
                && Files.isRegularFile(trustAnchor, LinkOption.NOFOLLOW_LINKS)
                && !Files.exists(target(normalized), LinkOption.NOFOLLOW_LINKS);
            if (bootstrap) {
                requireTrustAnchor(normalized);
            }
            return bootstrap;
        }
    }

    private static Path target(Path root) {
        Path normalized = MigrationPaths.requirePath(root, "authority participant root");
        return normalized.resolve(ProductionAuthorityBundle.AUTHORITY_FILE).toAbsolutePath().normalize();
    }

    private static Path trustAnchor(Path root) {
        Path normalized = MigrationPaths.requirePath(root, "authority participant root");
        return normalized.resolve(TRUST_ANCHOR_FILE).toAbsolutePath().normalize();
    }

    private static String reason(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "Production Authority Bundle Is Unavailable" : message;
    }

    public record Health(boolean available, boolean regenerationRequired, String reason,
                         ProductionAuthorityBundle bundle) {
        public Health {
            reason = reason == null ? "" : reason.trim();
            if (!available && reason.isBlank()) {
                throw new IllegalArgumentException("Unavailable Authority Bundle Health Requires A Reason");
            }
        }

        public static Health available(ProductionAuthorityBundle bundle) {
            return new Health(true, false, "", bundle);
        }

        public static Health availableWithoutBundle() {
            return new Health(true, false, "", null);
        }

        public static Health regenerationRequired(String reason) {
            return new Health(false, true, reason, null);
        }

        public boolean healthy() {
            return available;
        }
    }
}
