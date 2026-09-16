package restudio.resync.migration;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.upgrade.AssetAdoptionArtifactProducer;

public final class ProductionAcceptedStagePublisher implements AcceptedStagePublisher {
    public static final ContractIdentity CONTRACT = new ContractIdentity(AssetAdoptionArtifactProducer.FORMAT);
    public static final String CONTRACT_IDENTITY = CONTRACT.value();

    private final Path coordinationRoot;
    private final AtomicReference<AcceptedStagePublisher.Result> lastResult = new AtomicReference<>(AcceptedStagePublisher.Result.none());

    public ProductionAcceptedStagePublisher(Path coordinationRoot) {
        this.coordinationRoot = requireCoordinationRoot(coordinationRoot);
    }

    public Path coordinationRoot() {
        return coordinationRoot;
    }

    public ContractIdentity contract() {
        return CONTRACT;
    }

    @Override
    public String contractIdentity() {
        return CONTRACT_IDENTITY;
    }

    @Override
    public boolean requiresTypedEvidenceForRecovery() {
        return true;
    }

    @Override
    public Optional<RecoveredTopology> recoverTopology(
        Path activeRoot, MigrationJournal.Binding binding, String planHash
    ) throws IOException {
        MigrationJournal.Binding retained = Objects.requireNonNull(binding, "binding");
        MigrationJournal.PublicationBinding publication = retained.publicationBinding();
        if (publication == null || !CONTRACT_IDENTITY.equals(publication.publisherContractIdentity())) {
            throw new MigrationException("Production Accepted Stage Recovery Publication Binding Is Missing");
        }
        Path expectedPath = MigrationPaths.resolveInside(
            coordinationRoot, AssetAdoptionArtifactProducer.ARTIFACT_RELATIVE_PATH);
        AssetAdoptionArtifactProducer.Result artifact = AssetAdoptionArtifactProducer.load(coordinationRoot);
        if (!artifact.artifactPath().equals(expectedPath)
            || FilesIdentity.isSymlinkOrMissing(expectedPath)
            || !artifact.artifactHash().equals(publication.artifactHash())
            || !artifact.planHash().equals(MigrationCanonical.requireDigest(planHash, "planHash"))
            || !artifact.sourceManifestHash().equals(retained.sourceManifestHash())
            || !artifact.sourceManifestHash().equals(publication.sourceManifestHash())
            || !artifact.postStageManifestHash().equals(publication.postStageManifestHash())) {
            throw new MigrationException("Production Accepted Stage Retained Topology Does Not Match Publication Binding");
        }
        Path root = MigrationPaths.requireDirectory(activeRoot, "activeRoot");
        SnapshotManifest manifest = requireEmbeddedPostStageBinding(artifact, root);
        String activeDigest = TreeDigest.of(root);
        if (!activeDigest.equals(retained.stagedReplacementDigest())) {
            throw new MigrationException("Production Accepted Stage Retained Topology Does Not Match Active Digest");
        }
        return Optional.of(new RecoveredTopology(manifest, root, activeDigest, Set.of(
            MigrationActivationMarker.MARKER_FILE, ReplacementActivationRecord.RECORD_FILE)));
    }

    public AcceptedStagePublisher.Result lastResult() {
        return lastResult.get();
    }

    @Override
    public AcceptedStagePublisher.Result publish(AcceptedStagePublisher.Publication publication) throws IOException {
        Objects.requireNonNull(publication, "publication");
        try {
            if (!CONTRACT_IDENTITY.equals(publication.expectedPublisherContractIdentity())) {
                throw new MigrationException("Production Accepted Stage Publication Contract Identity Is Not Bound");
            }
            Snapshot sourceSnapshot = publication.sourceSnapshot();
            VerifiedSnapshotAdmission sourceAdmission = new SnapshotService(new MigrationFence())
                .admitExported(sourceSnapshot.root());
            Snapshot admittedSnapshot = sourceAdmission.snapshot();
            if (!admittedSnapshot.metadata().equals(sourceSnapshot.metadata())
                || !admittedSnapshot.manifest().canonicalText().equals(sourceSnapshot.manifest().canonicalText())
                || !admittedSnapshot.manifest().manifestHash().equals(sourceSnapshot.manifest().manifestHash())) {
                throw new MigrationException("Production Accepted Stage Source Admission Changed");
            }
            AssetAdoptionArtifactProducer.StageOutput stageOutput = publication.typedStageOutput().orElse(null);
            Optional<MigrationJournal.PublicationBinding> existingBinding = publication.existingPublicationBinding();
            if (stageOutput == null) {
                if (existingBinding.isPresent()) {
                    return replayBoundArtifact(publication, sourceSnapshot, existingBinding.get());
                }
                throw new AcceptedStagePublisher.Failure(
                    "Production Accepted Stage Recovery Requires Exact Typed Stage Evidence", null, true);
            }
            requireActivationRootPolicy(publication.staged(), stageOutput);
            if (existingBinding.isPresent()) {
                requirePublicationBinding(existingBinding.get(), publication, sourceSnapshot);
            }
            Path expectedPath = MigrationPaths.resolveInside(coordinationRoot, AssetAdoptionArtifactProducer.ARTIFACT_RELATIVE_PATH);
            AssetAdoptionArtifactProducer.Result retained = Files.exists(expectedPath, LinkOption.NOFOLLOW_LINKS)
                ? AssetAdoptionArtifactProducer.load(coordinationRoot) : null;
            if (retained != null) {
                requireArtifactBinding(retained, publication, sourceSnapshot, stageOutput);
                if (existingBinding.isPresent()) {
                    requireRecoveredArtifactBinding(retained, existingBinding.get(), publication, sourceSnapshot);
                }
            }
            AssetAdoptionArtifactProducer.Result artifact = AssetAdoptionArtifactProducer.produce(
                coordinationRoot, sourceAdmission, stageOutput);
            requireArtifactBinding(artifact, publication, sourceSnapshot, stageOutput);
            if (existingBinding.isPresent()) {
                requireRecoveredArtifactBinding(artifact, existingBinding.get(), publication, sourceSnapshot);
            }
            Path artifactPath = MigrationPaths.requirePath(artifact.artifactPath(), "adoptionArtifact");
            if (!artifactPath.equals(expectedPath)
                || FilesIdentity.isSymlinkOrMissing(artifactPath)) {
                throw new MigrationException("Production Accepted Stage Artifact Path Is Not Bound To Coordination Root");
            }
            AcceptedStagePublisher.Result result = new AcceptedStagePublisher.Result(CONTRACT_IDENTITY, artifactPath.toString(), artifact.artifactHash(),
                artifact.sourceManifestHash(), artifact.postStageManifestHash());
            lastResult.set(result);
            return result;
        } catch (AcceptedStagePublisher.Failure exception) {
            throw exception;
        } catch (MigrationException exception) {
            throw new AcceptedStagePublisher.Failure(
                "Production Accepted Stage Publication Identity Is Terminally Invalid: " + exception.getMessage(), exception, false);
        } catch (IOException | RuntimeException exception) {
            throw new AcceptedStagePublisher.Failure("Production Accepted Stage Publication Failed", exception);
        }
    }

    private AcceptedStagePublisher.Result replayBoundArtifact(
        AcceptedStagePublisher.Publication publication,
        Snapshot sourceSnapshot,
        MigrationJournal.PublicationBinding binding
    ) throws IOException {
        requirePublicationBinding(binding, publication, sourceSnapshot);
        Path activeRoot = requireActivationRootDigest(publication.staged());
        Path expectedPath = MigrationPaths.resolveInside(coordinationRoot, AssetAdoptionArtifactProducer.ARTIFACT_RELATIVE_PATH);
        AssetAdoptionArtifactProducer.Result artifact = AssetAdoptionArtifactProducer.load(coordinationRoot);
        requireRecoveredArtifactBinding(artifact, binding, publication, sourceSnapshot);
        Path artifactPath = MigrationPaths.requirePath(artifact.artifactPath(), "adoptionArtifact");
        if (!artifactPath.equals(expectedPath) || FilesIdentity.isSymlinkOrMissing(artifactPath)) {
            throw new MigrationException("Production Accepted Stage Artifact Path Is Not Bound To Coordination Root");
        }
        requireEmbeddedPostStageBinding(artifact, activeRoot);
        AcceptedStagePublisher.Result result = new AcceptedStagePublisher.Result(CONTRACT_IDENTITY,
            artifactPath.toString(), artifact.artifactHash(), artifact.sourceManifestHash(), artifact.postStageManifestHash());
        lastResult.set(result);
        return result;
    }

    private static void requirePublicationBinding(
        MigrationJournal.PublicationBinding binding,
        AcceptedStagePublisher.Publication publication,
        Snapshot sourceSnapshot
    ) throws MigrationException {
        if (!CONTRACT_IDENTITY.equals(binding.publisherContractIdentity())
            || !CONTRACT_IDENTITY.equals(publication.expectedPublisherContractIdentity())
            || !binding.sourceManifestHash().equals(sourceSnapshot.manifest().manifestHash())
            || !binding.sourceManifestHash().equals(publication.sourceManifestHash())
            || publication.typedStageOutput().map(output -> !binding.postStageManifestHash()
                .equals(output.postStageAdmission().manifestHash())).orElse(false)) {
            throw new MigrationException("Production Accepted Stage Recovery Publication Binding Does Not Match");
        }
    }

    private static void requireRecoveredArtifactBinding(
        AssetAdoptionArtifactProducer.Result artifact,
        MigrationJournal.PublicationBinding binding,
        AcceptedStagePublisher.Publication publication,
        Snapshot sourceSnapshot
    ) throws MigrationException {
        if (!artifact.artifactHash().equals(binding.artifactHash())
            || !artifact.planHash().equals(publication.planHash())
            || !artifact.sourceManifestHash().equals(sourceSnapshot.manifest().manifestHash())
            || !artifact.sourceManifestHash().equals(binding.sourceManifestHash())
            || !artifact.postStageManifestHash().equals(binding.postStageManifestHash())) {
            throw new MigrationException("Production Accepted Stage Retained Artifact Does Not Match Publication Binding");
        }
    }

    private static void requireArtifactBinding(AssetAdoptionArtifactProducer.Result artifact,
                                               AcceptedStagePublisher.Publication publication,
                                               Snapshot sourceSnapshot,
                                               AssetAdoptionArtifactProducer.StageOutput stageOutput)
        throws IOException {
        if (!artifact.planHash().equals(publication.planHash())
            || !artifact.sourceManifestHash().equals(sourceSnapshot.manifest().manifestHash())
            || !artifact.postStageManifestHash().equals(stageOutput.postStageAdmission().manifestHash())) {
            throw new MigrationException("Production Accepted Stage Artifact Does Not Match Publication");
        }
        Snapshot postStageSnapshot = stageOutput.postStageAdmission().snapshot();
        if (!artifact.postStageManifest().equals(encoded(postStageSnapshot.manifest().canonicalBytes()))
            || !artifact.postStageMetadata().equals(metadataJson(postStageSnapshot.metadata()))
            || !artifact.postStageState().equals(stateText(postStageSnapshot))) {
            throw new MigrationException("Production Accepted Stage Artifact Does Not Match Publication");
        }
    }

    private static SnapshotManifest requireEmbeddedPostStageBinding(
        AssetAdoptionArtifactProducer.Result artifact,
        Path activeRoot
    ) throws IOException {
        SnapshotManifest manifest = embeddedPostStageManifest(artifact);
        if (!artifact.postStageMetadata().equals(metadataJson(manifest.metadata()))
            || !artifact.postStageState().equals(verifiedState(manifest.manifestHash()))) {
            throw new MigrationException("Production Accepted Stage Retained Artifact Post-Stage Admission Is Invalid");
        }
        requireActiveRootMatches(manifest, activeRoot);
        return manifest;
    }

    private static SnapshotManifest embeddedPostStageManifest(
        AssetAdoptionArtifactProducer.Result artifact
    ) throws MigrationException {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(artifact.postStageManifest());
            String canonical = decodeUtf8(bytes, "post-stage manifest");
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(artifact.postStageManifest())
                || !canonical.endsWith("\n")) {
                throw new MigrationException("Production Accepted Stage Retained Artifact Post-Stage Manifest Is Not Canonical");
            }
            List<String> lines = new ArrayList<>(Arrays.asList(canonical.split("\n", -1)));
            lines.removeLast();
            int cursor = 0;
            int format = manifestInteger(lines, cursor++, "format=");
            String snapshotId = manifestDecoded(lines, cursor++, "snapshot-id=");
            long createdAt = manifestLong(lines, cursor++, "created-at=");
            String build = manifestDecoded(lines, cursor++, "build=");
            String catalog = manifestRaw(lines, cursor++, "catalog=");
            int extensionCount = manifestInteger(lines, cursor++, "extensions=");
            Map<String, String> extensions = new TreeMap<>();
            for (int index = 0; index < extensionCount; index++) {
                String row = manifestRaw(lines, cursor++, "extension=");
                String[] fields = row.split("\\|", -1);
                if (fields.length != 2 || extensions.putIfAbsent(MigrationCanonical.decode(fields[0]),
                    MigrationCanonical.decode(fields[1])) != null) {
                    throw new MigrationException("Production Accepted Stage Retained Artifact Post-Stage Manifest Extension Is Invalid");
                }
            }
            int directoryCount = manifestInteger(lines, cursor++, "directories=");
            List<String> directories = new ArrayList<>();
            for (int index = 0; index < directoryCount; index++) {
                directories.add(manifestDecoded(lines, cursor++, "directory="));
            }
            int fileCount = manifestInteger(lines, cursor++, "files=");
            List<SnapshotManifest.Entry> entries = new ArrayList<>();
            for (int index = 0; index < fileCount; index++) {
                String row = manifestRaw(lines, cursor++, "file=");
                String[] fields = row.split("\\|", -1);
                if (fields.length != 4) {
                    throw new MigrationException("Production Accepted Stage Retained Artifact Post-Stage Manifest File Is Invalid");
                }
                entries.add(new SnapshotManifest.Entry(MigrationCanonical.decode(fields[0]),
                    manifestSize(fields[1]), fields[2], MigrationCanonical.decode(fields[3])));
            }
            if (cursor != lines.size()) {
                throw new MigrationException("Production Accepted Stage Retained Artifact Post-Stage Manifest Has Unexpected Content");
            }
            SnapshotManifest manifest = new SnapshotManifest(
                new SnapshotMetadata(format, snapshotId, Instant.ofEpochMilli(createdAt), build, catalog, extensions),
                directories, entries);
            if (!manifest.canonicalText().equals(canonical)
                || !manifest.manifestHash().equals(artifact.postStageManifestHash())) {
                throw new MigrationException("Production Accepted Stage Retained Artifact Post-Stage Manifest Does Not Match Its Hash");
            }
            return manifest;
        } catch (MigrationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MigrationException("Production Accepted Stage Retained Artifact Post-Stage Manifest Is Invalid", exception);
        }
    }

    private static void requireActiveRootMatches(SnapshotManifest manifest, Path activeRoot) throws IOException {
        Path root = MigrationPaths.requireDirectory(activeRoot, "activeRoot");
        MigrationPaths.requireNoSymlinkTree(root);
        Map<String, SnapshotManifest.Entry> expectedFiles = new TreeMap<>();
        manifest.entries().forEach(entry -> expectedFiles.put(entry.relativePath(), entry));
        Map<String, FileIdentity> actualFiles = new TreeMap<>();
        Set<String> actualDirectories = new TreeSet<>();
        Files.walkFileTree(root, Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (!directory.equals(root)) {
                    actualDirectories.add(MigrationPaths.relative(root, directory));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                String relative = MigrationPaths.relative(root, file);
                if (Files.isSymbolicLink(file) || !attributes.isRegularFile()) {
                    throw new MigrationException("Production Accepted Stage Active Root Contains A Non-Regular File");
                }
                if (isUnboundAuthorityFile(relative, expectedFiles)) {
                    return FileVisitResult.CONTINUE;
                }
                actualFiles.put(relative, new FileIdentity(attributes.size(), sha256(file)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                throw new MigrationException("Production Accepted Stage Active Root Cannot Be Read", exception);
            }
        });
        if (!expectedFiles.keySet().equals(actualFiles.keySet())) {
            throw new MigrationException("Production Accepted Stage Active Root Does Not Match Retained Post-Stage Manifest");
        }
        for (Map.Entry<String, SnapshotManifest.Entry> expected : expectedFiles.entrySet()) {
            FileIdentity actual = actualFiles.get(expected.getKey());
            SnapshotManifest.Entry value = expected.getValue();
            if (actual.size() != value.size() || !actual.sha256().equals(value.sha256())) {
                throw new MigrationException("Production Accepted Stage Active Root File Does Not Match Retained Post-Stage Manifest: "
                    + expected.getKey());
            }
        }
        Set<String> expectedDirectories = new TreeSet<>(manifest.directories());
        actualDirectories.removeIf(directory -> isAuthorityOnlyDirectory(directory, actualFiles.keySet(), expectedFiles.keySet(), expectedDirectories));
        if (!expectedDirectories.equals(actualDirectories)) {
            throw new MigrationException("Production Accepted Stage Active Root Directories Do Not Match Retained Post-Stage Manifest");
        }
    }

    private static boolean isUnboundAuthorityFile(String relative, Map<String, SnapshotManifest.Entry> expectedFiles) {
        return !expectedFiles.containsKey(relative)
            && (relative.equals(MigrationActivationMarker.MARKER_FILE)
                || relative.equals(ReplacementActivationRecord.RECORD_FILE));
    }

    private static boolean isAuthorityOnlyDirectory(
        String directory,
        Set<String> actualFiles,
        Set<String> expectedFiles,
        Set<String> expectedDirectories
    ) {
        if (!directory.equals("assets") && !directory.equals("assets/.migrations")) {
            return false;
        }
        String prefix = directory + "/";
        return actualFiles.stream().noneMatch(path -> !isAuthorityPath(path) && path.startsWith(prefix))
            && expectedFiles.stream().noneMatch(path -> path.startsWith(prefix))
            && expectedDirectories.stream().noneMatch(path -> path.startsWith(prefix));
    }

    private static boolean isAuthorityPath(String relative) {
        return relative.equals(MigrationActivationMarker.MARKER_FILE)
            || relative.equals(ReplacementActivationRecord.RECORD_FILE);
    }

    private static String verifiedState(String manifestHash) {
        return "state=VERIFIED\nverified=true\nmanifest-hash=" + manifestHash + "\nfailures=0\n";
    }

    private static String decodeUtf8(byte[] bytes, String label) throws MigrationException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new MigrationException("Production Accepted Stage Retained Artifact " + label + " Encoding Is Invalid", exception);
        }
    }

    private static String manifestRaw(List<String> lines, int index, String prefix) throws MigrationException {
        if (index >= lines.size() || !lines.get(index).startsWith(prefix)) {
            throw new MigrationException("Production Accepted Stage Retained Artifact Post-Stage Manifest Is Missing: " + prefix);
        }
        return lines.get(index).substring(prefix.length());
    }

    private static String manifestDecoded(List<String> lines, int index, String prefix) throws MigrationException {
        return MigrationCanonical.decode(manifestRaw(lines, index, prefix));
    }

    private static int manifestInteger(List<String> lines, int index, String prefix) throws MigrationException {
        try {
            int value = Integer.parseInt(manifestRaw(lines, index, prefix));
            if (value < 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new MigrationException("Production Accepted Stage Retained Artifact Post-Stage Manifest Number Is Invalid", exception);
        }
    }

    private static long manifestLong(List<String> lines, int index, String prefix) throws MigrationException {
        try {
            return Long.parseLong(manifestRaw(lines, index, prefix));
        } catch (NumberFormatException exception) {
            throw new MigrationException("Production Accepted Stage Retained Artifact Post-Stage Manifest Timestamp Is Invalid", exception);
        }
    }

    private static long manifestSize(String value) throws MigrationException {
        try {
            long size = Long.parseLong(value);
            if (size < 0L) {
                throw new NumberFormatException();
            }
            return size;
        } catch (NumberFormatException exception) {
            throw new MigrationException("Production Accepted Stage Retained Artifact Post-Stage Manifest File Size Is Invalid", exception);
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path); var stream = new DigestInputStream(input, digest)) {
                stream.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new MigrationException("SHA-256 Is Unavailable", exception);
        }
    }

    private static String encoded(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String metadataJson(SnapshotMetadata metadata) {
        TreeMap<String, JsonValue> extensions = new TreeMap<>();
        metadata.extensionVersions().forEach((key, value) -> extensions.put(key, JsonValue.of(value)));
        LinkedHashMap<String, JsonValue> fields = new LinkedHashMap<>();
        fields.put("formatVersion", JsonValue.of(metadata.formatVersion()));
        fields.put("snapshotId", JsonValue.of(metadata.snapshotId()));
        fields.put("createdAt", JsonValue.of(metadata.createdAt().toString()));
        fields.put("build", JsonValue.of(metadata.build()));
        fields.put("catalogChecksum", JsonValue.of(metadata.catalogChecksum()));
        fields.put("extensionVersions", JsonValue.object(extensions));
        return JsonValue.object(fields).canonicalText();
    }

    private static String stateText(Snapshot snapshot) {
        return "state=" + snapshot.state().name() + "\nverified=" + snapshot.verification().verified()
            + "\nmanifest-hash=" + snapshot.verification().manifestHash() + "\nfailures="
            + snapshot.verification().failures().size() + "\n"
            + snapshot.verification().failures().stream().map(value -> "failure=" + value + "\n").reduce("", String::concat);
    }

    private static void requireActivationRootPolicy(
        StagedMigration staged,
        AssetAdoptionArtifactProducer.StageOutput stageOutput
    ) throws IOException {
        Path activationRoot = requireActivationRootDigest(staged);
        Path evidenceRoot = MigrationPaths.requireDirectory(stageOutput.postStageAdmission().snapshot().root(), "postStageExportRoot");
        MigrationPaths.requireNoSymlinkTree(evidenceRoot);
        if (activationRoot.equals(evidenceRoot)) {
            throw new MigrationException("Production Accepted Stage Requires A Distinct Post-Stage Evidence Root");
        }
        if (!TreeDigest.of(evidenceRoot).equals(staged.contentHash())) {
            throw new MigrationException("Production Accepted Stage Evidence Root Digest Does Not Match Activation Root");
        }
    }

    private static Path requireActivationRootDigest(StagedMigration staged) throws IOException {
        Path activationRoot = MigrationPaths.requireDirectory(staged.root(), "activationRoot");
        MigrationPaths.requireNoSymlinkTree(activationRoot);
        if (!TreeDigest.of(activationRoot).equals(staged.contentHash())) {
            throw new MigrationException("Production Accepted Stage Activation Root Digest Does Not Match Staged Evidence");
        }
        return activationRoot;
    }

    private static Path requireCoordinationRoot(Path value) {
        return MigrationPaths.requirePath(Objects.requireNonNull(value, "coordinationRoot"), "coordinationRoot");
    }

    private record FileIdentity(long size, String sha256) {
    }

    public record ContractIdentity(String value) {
        public ContractIdentity {
            value = MigrationCanonical.requireText(value, "contractIdentity");
            if (!value.equals(value.strip()) || !value.equals(AssetAdoptionArtifactProducer.FORMAT)) {
                throw new IllegalArgumentException("Unsupported Production Accepted Stage Contract Identity");
            }
        }
    }

    private static final class FilesIdentity {
        private FilesIdentity() {
        }

        private static boolean isSymlinkOrMissing(Path path) {
            return Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
        }
    }
}
