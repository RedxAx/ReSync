package restudio.resync.migration;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class StagingArtifactRecovery {
    public static final String QUARANTINE_DIRECTORY = ".quarantine";
    public static final String SNAPSHOT_STAGING_DIRECTORY = "snapshot-staging";
    public static final String RESTORE_STAGING_DIRECTORY = "restore-staging";
    public static final String RESTORE_CURRENT_SNAPSHOT_DIRECTORY = "restore-current-snapshot";
    public static final String COPY_MARKER_SUFFIX = ".copying";

    private static final String JOURNAL_SUFFIX = ".journal";
    private static final String SIDECAR_DIRECTORY = ".quarantine-sidecars";
    private static final int JOURNAL_FORMAT = 1;

    private StagingArtifactRecovery() {
    }

    public static void prepareSnapshot(Path stagingRoot) throws IOException {
        Path root = MigrationPaths.requirePath(stagingRoot, "stagingRoot");
        if (recoverExistingTransaction(root, SNAPSHOT_STAGING_DIRECTORY, false)) {
            return;
        }
        SnapshotArtifacts artifacts = inspectSnapshot(root, false);
        if (!artifacts.present()) {
            return;
        }
        if (artifacts.state() == SnapshotState.VERIFIED) {
            throw new MigrationException("Verified Snapshot Staging Already Exists: " + artifacts.root());
        }
        quarantine(artifacts, SNAPSHOT_STAGING_DIRECTORY);
    }

    public static void quarantineSnapshotFailure(Path stagingRoot) throws IOException {
        Path root = MigrationPaths.requirePath(stagingRoot, "stagingRoot");
        if (recoverExistingTransaction(root, SNAPSHOT_STAGING_DIRECTORY, false)) {
            return;
        }
        SnapshotArtifacts artifacts = inspectSnapshot(root, false);
        if (!artifacts.present()) {
            return;
        }
        if (artifacts.state() == SnapshotState.VERIFIED) {
            throw new MigrationException("Verified Snapshot Cannot Be Quarantined As Failed Evidence: " + artifacts.root());
        }
        quarantine(artifacts, SNAPSHOT_STAGING_DIRECTORY);
    }

    public static void quarantineCurrentSnapshot(Path stagingRoot) throws IOException {
        Path root = MigrationPaths.requirePath(stagingRoot, "stagingRoot");
        if (recoverExistingTransaction(root, RESTORE_CURRENT_SNAPSHOT_DIRECTORY, true)) {
            return;
        }
        SnapshotArtifacts artifacts = inspectSnapshot(root, true);
        if (!artifacts.present()) {
            return;
        }
        if (artifacts.state() == SnapshotState.VERIFIED) {
            requireVerifiedSnapshot(artifacts.root());
        }
        quarantine(artifacts, RESTORE_CURRENT_SNAPSHOT_DIRECTORY);
    }

    public static void quarantineRestore(Path stagingRoot) throws IOException {
        Path root = MigrationPaths.requirePath(stagingRoot, "restoreStagingRoot");
        if (recoverExistingTransaction(root, RESTORE_STAGING_DIRECTORY, true)) {
            return;
        }
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            rejectUnknownSiblings(root, List.of());
            return;
        }
        requireExistingDirectory(root, "restoreStagingRoot");
        requireNoUnknownSiblings(root, List.of());
        MigrationPaths.requireNoSymlinkTree(root);
        quarantine(root, RESTORE_STAGING_DIRECTORY);
    }

    public static Path quarantinePath(Path stagingRoot, String category) {
        Path root = MigrationPaths.requirePath(stagingRoot, "stagingRoot");
        Path parent = root.getParent();
        if (parent == null || root.getFileName() == null) {
            throw new IllegalArgumentException("Staging Root Must Have A Parent");
        }
        String normalizedCategory = MigrationCanonical.requireText(category, "category");
        if (!normalizedCategory.equals(normalizedCategory.strip()) || normalizedCategory.contains("/")
            || normalizedCategory.contains("\\") || normalizedCategory.equals(".") || normalizedCategory.equals("..")) {
            throw new IllegalArgumentException("Quarantine Category Is Invalid");
        }
        return parent.resolve(QUARANTINE_DIRECTORY).resolve(normalizedCategory)
            .resolve(root.getFileName()).toAbsolutePath().normalize();
    }

    private static boolean recoverExistingTransaction(Path root, String category, boolean allowVerified) throws IOException {
        Path destination = quarantinePath(root, category);
        Path journalPath = journalPath(destination);
        boolean journalPresent = present(journalPath);
        boolean destinationPresent = present(destination);
        if (!journalPresent && !destinationPresent) {
            return false;
        }
        if (Files.isSymbolicLink(journalPath) || Files.isSymbolicLink(destination)) {
            throw new MigrationException("Quarantine Transaction Cannot Use A Symbolic Link: " + destination);
        }
        if (!journalPresent) {
            throw new MigrationException("Quarantine Destination Exists Without Its Journal: " + destination);
        }
        Journal journal = readJournal(journalPath);
        validateJournal(journal, root, category, allowVerified);
        if (journal.state() == JournalState.COMPLETE) {
            validateCurrentSource(root, journal);
            validateDestination(journal, destination);
            return true;
        }
        validateCurrentSource(root, journal);
        resume(journal, destination, journalPath);
        return true;
    }

    private static SnapshotArtifacts inspectSnapshot(Path stagingRoot, boolean allowVerified) throws IOException {
        Path root = MigrationPaths.requirePath(stagingRoot, "stagingRoot");
        if (root.getParent() == null || root.getFileName() == null) {
            throw new MigrationException("stagingRoot Has No Parent");
        }
        Path manifest = sidecar(root, ".manifest");
        Path state = sidecar(root, ".state");
        Path metadata = ProductionSnapshotMetadataManifest.pathFor(root);
        Path copying = sidecar(root, COPY_MARKER_SUFFIX);
        List<Path> expected = List.of(root, manifest, state, metadata, copying);
        rejectUnknownSiblings(root, expected.subList(1, expected.size()));
        boolean present = expected.stream().anyMatch(StagingArtifactRecovery::present);
        if (!present) {
            return new SnapshotArtifacts(root, null, List.of());
        }
        requireParent(root, "stagingRoot");
        for (Path path : expected) {
            if (!present(path)) {
                continue;
            }
            if (Files.isSymbolicLink(path)) {
                throw new MigrationException("Snapshot Staging Artifact Cannot Be A Symbolic Link: " + path);
            }
        }
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("Snapshot Staging Root Must Be A Directory: " + root);
            }
            MigrationPaths.requireNoSymlinkTree(root);
            requireReservedDirectoryAvailable(root);
        }
        Path statePath = Files.exists(copying, LinkOption.NOFOLLOW_LINKS) ? copying : state;
        if (!Files.exists(statePath, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Snapshot Staging State Is Missing: " + root);
        }
        if (!Files.isRegularFile(statePath, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Snapshot Staging State Must Be A Regular File: " + statePath);
        }
        SnapshotState stateValue = readState(statePath);
        if (!allowVerified && stateValue == SnapshotState.VERIFIED) {
            throw new MigrationException("Verified Snapshot Staging Cannot Be Replaced: " + root);
        }
        for (Path sidecar : List.of(manifest, state, metadata, copying)) {
            if (present(sidecar) && !Files.isRegularFile(sidecar, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("Snapshot Staging Sidecar Must Be A Regular File: " + sidecar);
            }
        }
        return new SnapshotArtifacts(root, stateValue, expected.stream()
            .filter(path -> !path.equals(root) && present(path)).toList());
    }

    private static SnapshotState readState(Path statePath) throws IOException {
        List<String> lines = Files.readAllLines(statePath, StandardCharsets.UTF_8);
        if (lines.size() < 4 || !lines.getFirst().startsWith("state=")
            || !lines.get(2).startsWith("manifest-hash=") || !lines.get(3).startsWith("failures=")) {
            throw new MigrationException("Snapshot Staging State Is Invalid: " + statePath);
        }
        if (!lines.get(1).equals("verified=true") && !lines.get(1).equals("verified=false")) {
            throw new MigrationException("Snapshot Staging Verification Flag Is Invalid: " + statePath);
        }
        int failureCount;
        try {
            failureCount = Integer.parseInt(lines.get(3).substring("failures=".length()));
        } catch (NumberFormatException exception) {
            throw new MigrationException("Snapshot Staging Failure Count Is Invalid: " + statePath, exception);
        }
        if (failureCount < 0 || lines.size() != failureCount + 4) {
            throw new MigrationException("Snapshot Staging Failure Count Does Not Match Content: " + statePath);
        }
        SnapshotState state;
        try {
            state = SnapshotState.valueOf(lines.getFirst().substring("state=".length()));
        } catch (RuntimeException exception) {
            throw new MigrationException("Snapshot Staging State Is Invalid: " + statePath, exception);
        }
        SnapshotVerification verification = SnapshotStateStore.readVerification(statePath);
        if (state == SnapshotState.VERIFIED && !verification.verified()) {
            throw new MigrationException("Verified Snapshot Staging State Is Not Verified: " + statePath);
        }
        if (state != SnapshotState.VERIFIED && verification.verified()) {
            throw new MigrationException("Incomplete Snapshot Staging State Is Marked Verified: " + statePath);
        }
        return state;
    }

    private static void requireVerifiedSnapshot(Path root) throws IOException {
        Path manifestPath = sidecar(root, ".manifest");
        Path statePath = sidecar(root, ".state");
        if (!Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)
            || !Files.isRegularFile(statePath, LinkOption.NOFOLLOW_LINKS)
            || !Files.isRegularFile(ProductionSnapshotMetadataManifest.pathFor(root), LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Verified Snapshot Evidence Is Incomplete: " + root);
        }
        SnapshotManifest manifest = SnapshotManifest.read(manifestPath);
        SnapshotVerification recorded = SnapshotStateStore.readVerification(statePath);
        if (!recorded.verified() || !recorded.manifestHash().equals(manifest.manifestHash()) || !recorded.failures().isEmpty()) {
            throw new MigrationException("Verified Snapshot Evidence State Does Not Match Its Manifest: " + root);
        }
        ProductionSnapshotMetadataManifest.Values metadata = ProductionSnapshotMetadataManifest.read(root);
        if (!metadata.metadata().equals(manifest.metadata()) || !metadata.manifestHash().equals(manifest.manifestHash())) {
            throw new MigrationException("Verified Snapshot Evidence Metadata Does Not Match Its Manifest: " + root);
        }
        SnapshotVerification verification = manifest.verify(root);
        verification.requireVerified();
        if (!verification.manifestHash().equals(manifest.manifestHash())) {
            throw new MigrationException("Verified Snapshot Evidence Hash Does Not Match Its Manifest: " + root);
        }
    }

    private static void quarantine(SnapshotArtifacts artifacts, String category) throws IOException {
        List<JournalArtifact> entries = new ArrayList<>();
        if (present(artifacts.root())) {
            entries.add(JournalArtifact.root(artifacts.root()));
        }
        for (Path sidecar : artifacts.sidecars()) {
            entries.add(JournalArtifact.sidecar(sidecar, kindFor(sidecar, artifacts.root())));
        }
        quarantine(artifacts.root(), artifacts.state(), entries, category);
    }

    private static void quarantine(Path root, String category) throws IOException {
        requireReservedDirectoryAvailable(root);
        quarantine(root, null, List.of(JournalArtifact.root(root)), category);
    }

    private static void quarantine(Path root, SnapshotState snapshotState, List<JournalArtifact> entries,
                                   String category) throws IOException {
        Path destination = quarantinePath(root, category);
        Path journalPath = journalPath(destination);
        requireQuarantineParent(destination);
        if (present(destination) || present(journalPath)) {
            throw new MigrationException("Quarantine Transaction Already Exists: " + destination);
        }
        Journal journal = new Journal(category, root, snapshotState, entries, JournalState.OPEN);
        writeJournal(journalPath, journal);
        if (!entries.stream().anyMatch(entry -> entry.kind() == ArtifactKind.ROOT)) {
            createDestination(destination);
        }
        resume(journal, destination, journalPath);
    }

    private static void resume(Journal journal, Path destination, Path journalPath) throws IOException {
        validateJournalPath(journalPath);
        ensureDestination(destination, journal);
        Journal current = journal;
        for (int index = 0; index < current.artifacts().size(); index++) {
            JournalArtifact artifact = current.artifacts().get(index);
            Path source = artifact.source();
            Path target = target(destination, artifact);
            if (artifact.status() == ArtifactStatus.MOVED) {
                if (present(source)) {
                    throw new MigrationException("Quarantine Source Reappeared After Move: " + source);
                }
                requireDestinationArtifact(target, artifact.type());
                continue;
            }
            boolean sourcePresent = present(source);
            boolean targetPresent = present(target);
            if (sourcePresent && targetPresent) {
                throw new MigrationException("Quarantine Source And Destination Both Exist: " + source);
            }
            if (!sourcePresent && !targetPresent) {
                throw new MigrationException("Quarantine Artifact Disappeared During Recovery: " + source);
            }
            if (targetPresent) {
                requireDestinationArtifact(target, artifact.type());
            } else {
                requireSourceArtifact(source, artifact.type());
                if (artifact.kind() != ArtifactKind.ROOT) {
                    ensureSidecarDirectory(destination);
                }
                moveAtomically(source, target);
                requireDestinationArtifact(target, artifact.type());
            }
            List<JournalArtifact> updated = new ArrayList<>(current.artifacts());
            updated.set(index, artifact.moved());
            current = new Journal(current.category(), current.sourceRoot(), current.snapshotState(), updated, JournalState.OPEN);
            writeJournal(journalPath, current);
        }
        validateDestination(current, destination);
        current = new Journal(current.category(), current.sourceRoot(), current.snapshotState(), current.artifacts(), JournalState.COMPLETE);
        writeJournal(journalPath, current);
        validateDestination(current, destination);
    }

    private static void ensureDestination(Path destination, Journal journal) throws IOException {
        if (present(destination)) {
            if (Files.isSymbolicLink(destination) || !Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("Quarantine Destination Must Be A Non-Symbolic-Link Directory: " + destination);
            }
            return;
        }
        boolean rootPending = journal.artifacts().stream().anyMatch(artifact -> artifact.kind() == ArtifactKind.ROOT
            && artifact.status() == ArtifactStatus.PENDING);
        if (rootPending) {
            JournalArtifact root = journal.artifacts().stream()
                .filter(artifact -> artifact.kind() == ArtifactKind.ROOT).findFirst().orElseThrow();
            if (!present(root.source())) {
                throw new MigrationException("Quarantine Root Is Missing Before Its Move: " + root.source());
            }
            return;
        }
        createDestination(destination);
    }

    private static void createDestination(Path destination) throws IOException {
        Path parent = destination.getParent();
        if (parent == null) {
            throw new MigrationException("Quarantine Destination Has No Parent");
        }
        requireQuarantineParent(destination);
        try {
            Files.createDirectory(destination);
        } catch (FileAlreadyExistsException exception) {
            throw new MigrationException("Quarantine Destination Already Exists: " + destination, exception);
        }
        if (Files.isSymbolicLink(destination) || !Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Quarantine Destination Is Invalid: " + destination);
        }
    }

    private static void validateCurrentSource(Path root, Journal journal) throws IOException {
        List<Path> expected = List.of(sidecar(root, ".manifest"), sidecar(root, ".state"),
            ProductionSnapshotMetadataManifest.pathFor(root), sidecar(root, COPY_MARKER_SUFFIX));
        rejectUnknownSiblings(root, expected);
        for (ArtifactKind kind : ArtifactKind.values()) {
            Path source = source(root, kind);
            JournalArtifact artifact = journal.artifacts().stream()
                .filter(candidate -> candidate.kind() == kind).findFirst().orElse(null);
            if (present(source) && artifact == null) {
                throw new MigrationException("New Quarantine Source Artifact Appeared: " + source);
            }
            if (present(source) && artifact.status() == ArtifactStatus.MOVED) {
                throw new MigrationException("Quarantine Source Reappeared After Move: " + source);
            }
            if (present(source)) {
                requireSourceArtifact(source, artifact.type());
            }
        }
    }

    private static void validateDestination(Journal journal, Path destination) throws IOException {
        if (!present(destination) || Files.isSymbolicLink(destination)
            || !Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Quarantine Destination Is Incomplete: " + destination);
        }
        MigrationPaths.requireNoSymlinkTree(destination);
        Set<String> expectedSidecars = journal.artifacts().stream()
            .filter(artifact -> artifact.kind() != ArtifactKind.ROOT)
            .map(artifact -> artifact.kind().suffix()).collect(Collectors.toSet());
        Path sidecars = destination.resolve(SIDECAR_DIRECTORY);
        if (expectedSidecars.isEmpty()) {
            if (present(sidecars)) {
                throw new MigrationException("Unexpected Quarantine Sidecar Directory: " + sidecars);
            }
        } else {
            if (!present(sidecars) || Files.isSymbolicLink(sidecars) || !Files.isDirectory(sidecars, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("Quarantine Sidecar Directory Is Incomplete: " + sidecars);
            }
            try (var entries = Files.list(sidecars)) {
                List<Path> unknown = entries.filter(path -> !expectedSidecars.contains(path.getFileName().toString()))
                    .sorted(Comparator.comparing(Path::toString)).toList();
                if (!unknown.isEmpty()) {
                    throw new MigrationException("Unknown Quarantine Sidecar: " + unknown.getFirst());
                }
            }
        }
        for (JournalArtifact artifact : journal.artifacts()) {
            requireDestinationArtifact(target(destination, artifact), artifact.type());
        }
    }

    private static void validateJournal(Journal journal, Path root, String category, boolean allowVerified) throws IOException {
        Path normalizedRoot = MigrationPaths.requirePath(root, "stagingRoot");
        if (!journal.category().equals(category) || !journal.sourceRoot().equals(normalizedRoot)) {
            throw new MigrationException("Quarantine Journal Does Not Match Its Source");
        }
        if (journal.snapshotState() == SnapshotState.VERIFIED && !allowVerified) {
            throw new MigrationException("Verified Snapshot Cannot Be Recovered As Failed Evidence: " + root);
        }
        if (journal.snapshotState() == null && !category.equals(RESTORE_STAGING_DIRECTORY)) {
            throw new MigrationException("Snapshot Quarantine Journal Has No State: " + root);
        }
        if (journal.snapshotState() != null && category.equals(RESTORE_STAGING_DIRECTORY)) {
            throw new MigrationException("Restore Quarantine Journal Has Snapshot State: " + root);
        }
        Set<ArtifactKind> kinds = new HashSet<>();
        for (JournalArtifact artifact : journal.artifacts()) {
            if (!kinds.add(artifact.kind()) || !artifact.source().equals(source(normalizedRoot, artifact.kind()))
                || !artifact.target().equals(expectedTarget(artifact.kind()))) {
                throw new MigrationException("Quarantine Journal Artifact Binding Is Invalid: " + root);
            }
            ArtifactType expectedType = artifact.kind() == ArtifactKind.ROOT ? ArtifactType.DIRECTORY : ArtifactType.REGULAR;
            if (artifact.type() != expectedType) {
                throw new MigrationException("Quarantine Journal Artifact Type Is Invalid: " + root);
            }
        }
        if (journal.artifacts().isEmpty()) {
            throw new MigrationException("Quarantine Journal Has No Artifacts: " + root);
        }
    }

    private static Journal readJournal(Path path) throws IOException {
        validateJournalPath(path);
        String content;
        try {
            content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(Files.readAllBytes(path))).toString();
        } catch (CharacterCodingException exception) {
            throw new MigrationException("Quarantine Journal Is Not Valid UTF-8: " + path, exception);
        }
        if (!content.endsWith("\n")) {
            throw new MigrationException("Quarantine Journal Does Not End With A Newline: " + path);
        }
        String[] rows = content.split("\n", -1);
        if (rows.length < 8 || !rows[rows.length - 1].isEmpty()) {
            throw new MigrationException("Quarantine Journal Is Incomplete: " + path);
        }
        List<String> rowList = Arrays.asList(rows);
        String hashRow = rows[rows.length - 2];
        if (!hashRow.startsWith("journal-hash=")) {
            throw new MigrationException("Quarantine Journal Hash Is Missing: " + path);
        }
        String body = String.join("\n", rowList.subList(0, rows.length - 2)) + "\n";
        String hash = MigrationCanonical.requireDigest(hashRow.substring("journal-hash=".length()), "journalHash");
        if (!MigrationCanonical.sha256(body).equals(hash)) {
            throw new MigrationException("Quarantine Journal Hash Does Not Match Content: " + path);
        }
        JournalCursor cursor = new JournalCursor(rowList.subList(0, rows.length - 2));
        if (!cursor.required("format=").equals(Integer.toString(JOURNAL_FORMAT))) {
            throw new MigrationException("Unsupported Quarantine Journal Format: " + path);
        }
        String category = MigrationCanonical.decode(cursor.raw("category="));
        Path sourceRoot = MigrationPaths.requirePath(Path.of(MigrationCanonical.decode(cursor.raw("source-root="))), "journalSourceRoot");
        String stateText = cursor.raw("snapshot-state=");
        SnapshotState snapshotState;
        if (stateText.equals("NONE")) {
            snapshotState = null;
        } else {
            try {
                snapshotState = SnapshotState.valueOf(stateText);
            } catch (IllegalArgumentException exception) {
                throw new MigrationException("Quarantine Journal Snapshot State Is Invalid: " + path, exception);
            }
        }
        int count = cursor.integer("artifacts=");
        if (count < 1 || count > ArtifactKind.values().length) {
            throw new MigrationException("Quarantine Journal Artifact Count Is Invalid: " + path);
        }
        List<JournalArtifact> artifacts = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String[] fields = cursor.raw("artifact=").split("\\|", -1);
            if (fields.length != 5) {
                throw new MigrationException("Quarantine Journal Artifact Row Is Invalid: " + path);
            }
            ArtifactKind kind;
            ArtifactType type;
            ArtifactStatus status;
            try {
                kind = ArtifactKind.valueOf(fields[0]);
                type = ArtifactType.valueOf(fields[3]);
                status = ArtifactStatus.valueOf(fields[4]);
            } catch (IllegalArgumentException exception) {
                throw new MigrationException("Quarantine Journal Artifact Row Is Invalid: " + path, exception);
            }
            artifacts.add(new JournalArtifact(kind,
                MigrationPaths.requirePath(Path.of(MigrationCanonical.decode(fields[1])), "journalArtifactSource"),
                MigrationCanonical.decode(fields[2]), type, status));
        }
        String transactionState = cursor.raw("transaction=");
        JournalState journalState;
        try {
            journalState = JournalState.valueOf(transactionState);
        } catch (IllegalArgumentException exception) {
            throw new MigrationException("Quarantine Journal Transaction State Is Invalid: " + path, exception);
        }
        cursor.requireEnd();
        return new Journal(category, sourceRoot, snapshotState, artifacts, journalState);
    }

    private static void writeJournal(Path path, Journal journal) throws IOException {
        StringBuilder body = new StringBuilder();
        body.append("format=").append(JOURNAL_FORMAT).append('\n');
        body.append("category=").append(MigrationCanonical.encode(journal.category())).append('\n');
        body.append("source-root=").append(MigrationCanonical.encode(journal.sourceRoot().toString())).append('\n');
        body.append("snapshot-state=").append(journal.snapshotState() == null ? "NONE" : journal.snapshotState().name()).append('\n');
        body.append("artifacts=").append(journal.artifacts().size()).append('\n');
        for (JournalArtifact artifact : journal.artifacts()) {
            body.append("artifact=").append(artifact.kind().name()).append('|')
                .append(MigrationCanonical.encode(artifact.source().toString())).append('|')
                .append(MigrationCanonical.encode(artifact.target())).append('|')
                .append(artifact.type().name()).append('|').append(artifact.status().name()).append('\n');
        }
        body.append("transaction=").append(journal.state().name()).append('\n');
        String canonical = body.toString();
        String content = canonical + "journal-hash=" + MigrationCanonical.sha256(canonical) + "\n";
        AtomicFiles.write(path, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void validateJournalPath(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new MigrationException("Quarantine Journal Is Not A Regular File: " + path);
        }
    }

    private static void requireSourceArtifact(Path path, ArtifactType type) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new MigrationException("Quarantine Source Cannot Be A Symbolic Link: " + path);
        }
        if (type == ArtifactType.DIRECTORY) {
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("Quarantine Source Must Be A Directory: " + path);
            }
            MigrationPaths.requireNoSymlinkTree(path);
            requireReservedDirectoryAvailable(path);
        } else if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Quarantine Source Must Be A Regular File: " + path);
        }
    }

    private static void requireDestinationArtifact(Path path, ArtifactType type) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new MigrationException("Quarantine Destination Cannot Be A Symbolic Link: " + path);
        }
        if (type == ArtifactType.DIRECTORY) {
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("Quarantine Destination Must Be A Directory: " + path);
            }
            MigrationPaths.requireNoSymlinkTree(path);
        } else if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Quarantine Destination Must Be A Regular File: " + path);
        }
    }

    private static void ensureSidecarDirectory(Path destination) throws IOException {
        Path sidecars = destination.resolve(SIDECAR_DIRECTORY);
        if (present(sidecars)) {
            if (Files.isSymbolicLink(sidecars) || !Files.isDirectory(sidecars, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("Quarantine Sidecar Directory Is Invalid: " + sidecars);
            }
            return;
        }
        Files.createDirectory(sidecars);
    }

    private static void requireReservedDirectoryAvailable(Path root) throws IOException {
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)
            && Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
            && present(root.resolve(SIDECAR_DIRECTORY))) {
            throw new MigrationException("Staging Root Uses A Reserved Quarantine Name: " + root.resolve(SIDECAR_DIRECTORY));
        }
    }

    private static ArtifactKind kindFor(Path sidecar, Path root) {
        for (ArtifactKind kind : ArtifactKind.values()) {
            if (kind != ArtifactKind.ROOT && source(root, kind).equals(sidecar)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown Snapshot Sidecar: " + sidecar);
    }

    private static Path target(Path destination, JournalArtifact artifact) {
        return artifact.kind() == ArtifactKind.ROOT ? destination
            : destination.resolve(artifact.target()).normalize();
    }

    private static Path source(Path root, ArtifactKind kind) {
        return kind == ArtifactKind.ROOT ? root : sidecar(root, kind.suffix());
    }

    private static String expectedTarget(ArtifactKind kind) {
        return kind == ArtifactKind.ROOT ? "." : SIDECAR_DIRECTORY + "/" + kind.suffix();
    }

    private static Path journalPath(Path destination) {
        return destination.resolveSibling(destination.getFileName() + JOURNAL_SUFFIX);
    }

    private static boolean present(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path);
    }

    private static void requireQuarantineParent(Path destination) throws IOException {
        Path parent = destination.getParent();
        if (parent == null) {
            throw new MigrationException("Quarantine Destination Has No Parent");
        }
        MigrationPaths.requireWritableParent(parent);
        Files.createDirectories(parent);
        if (Files.isSymbolicLink(parent) || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Quarantine Parent Is Invalid: " + parent);
        }
        Path ancestor = parent;
        while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            ancestor = ancestor.getParent();
            if (ancestor == null) {
                throw new MigrationException("Quarantine Parent Has No Existing Ancestor: " + parent);
            }
        }
        MigrationPaths.requireNoSymlinkTraversal(ancestor, parent);
    }

    private static Path requireExistingDirectory(Path value, String field) throws IOException {
        Path root = MigrationPaths.requirePath(value, field);
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException(field + " Must Be A Non-Symbolic-Link Directory: " + root);
        }
        return root;
    }

    private static Path requireParent(Path root, String field) throws IOException {
        Path parent = root.getParent();
        if (parent == null) {
            throw new MigrationException(field + " Has No Parent");
        }
        MigrationPaths.requireWritableParent(root);
        return parent;
    }

    private static void requireNoUnknownSiblings(Path root, List<Path> known) throws IOException {
        requireParent(root, "stagingRoot");
        rejectUnknownSiblings(root, known);
    }

    private static void rejectUnknownSiblings(Path root, List<Path> known) throws IOException {
        Path parent = root.getParent();
        if (parent == null) {
            throw new MigrationException("stagingRoot Has No Parent");
        }
        if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(parent) || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Staging Parent Is Invalid: " + parent);
        }
        String prefix = root.getFileName() + ".";
        try (var entries = Files.list(parent)) {
            List<Path> unknown = entries.filter(path -> path.getFileName().toString().startsWith(prefix)
                && known.stream().noneMatch(path::equals)).sorted(Comparator.comparing(Path::toString)).toList();
            if (!unknown.isEmpty()) {
                throw new MigrationException("Unknown Staging Artifact: " + unknown.getFirst());
            }
        }
    }

    private static Path sidecar(Path root, String suffix) {
        Path parent = root.getParent();
        Path name = root.getFileName();
        if (parent == null || name == null) {
            throw new IllegalArgumentException("Staging Root Must Have A Parent");
        }
        return parent.resolve(name + suffix).toAbsolutePath().normalize();
    }

    private static void moveAtomically(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new MigrationException("Staging Quarantine Requires Atomic Moves", exception);
        }
    }

    private record SnapshotArtifacts(Path root, SnapshotState state, List<Path> sidecars) {
        private SnapshotArtifacts {
            root = Objects.requireNonNull(root, "root");
            sidecars = List.copyOf(sidecars);
        }

        private boolean present() {
            return state != null || StagingArtifactRecovery.present(root) || !sidecars.isEmpty();
        }
    }

    private record Journal(String category, Path sourceRoot, SnapshotState snapshotState,
                           List<JournalArtifact> artifacts, JournalState state) {
        private Journal {
            category = MigrationCanonical.requireText(category, "category");
            sourceRoot = MigrationPaths.requirePath(sourceRoot, "sourceRoot");
            artifacts = List.copyOf(artifacts);
            state = Objects.requireNonNull(state, "state");
        }
    }

    private record JournalArtifact(ArtifactKind kind, Path source, String target,
                                   ArtifactType type, ArtifactStatus status) {
        private JournalArtifact {
            kind = Objects.requireNonNull(kind, "kind");
            source = MigrationPaths.requirePath(source, "source");
            target = MigrationCanonical.requireText(target, "target");
            type = Objects.requireNonNull(type, "type");
            status = Objects.requireNonNull(status, "status");
        }

        private static JournalArtifact root(Path source) {
            return new JournalArtifact(ArtifactKind.ROOT, source, ".", ArtifactType.DIRECTORY, ArtifactStatus.PENDING);
        }

        private static JournalArtifact sidecar(Path source, ArtifactKind kind) {
            return new JournalArtifact(kind, source, expectedTarget(kind), ArtifactType.REGULAR, ArtifactStatus.PENDING);
        }

        private JournalArtifact moved() {
            return new JournalArtifact(kind, source, target, type, ArtifactStatus.MOVED);
        }
    }

    private enum ArtifactKind {
        ROOT(null),
        MANIFEST(".manifest"),
        STATE(".state"),
        METADATA(".metadata"),
        COPYING(COPY_MARKER_SUFFIX);

        private final String suffix;

        ArtifactKind(String suffix) {
            this.suffix = suffix;
        }

        private String suffix() {
            if (suffix == null) {
                throw new IllegalStateException("Root Has No Sidecar Suffix");
            }
            return suffix;
        }
    }

    private enum ArtifactType {
        DIRECTORY,
        REGULAR
    }

    private enum ArtifactStatus {
        PENDING,
        MOVED
    }

    private enum JournalState {
        OPEN,
        COMPLETE
    }

    private static final class JournalCursor {
        private final List<String> rows;
        private int index;

        private JournalCursor(List<String> rows) {
            this.rows = rows;
        }

        private String required(String prefix) throws MigrationException {
            return raw(prefix);
        }

        private String raw(String prefix) throws MigrationException {
            if (index >= rows.size() || !rows.get(index).startsWith(prefix)) {
                throw new MigrationException("Missing Quarantine Journal Line: " + prefix);
            }
            return rows.get(index++).substring(prefix.length());
        }

        private int integer(String prefix) throws MigrationException {
            try {
                return Integer.parseInt(raw(prefix));
            } catch (NumberFormatException exception) {
                throw new MigrationException("Invalid Quarantine Journal Integer: " + prefix, exception);
            }
        }

        private void requireEnd() throws MigrationException {
            if (index != rows.size()) {
                throw new MigrationException("Unexpected Quarantine Journal Content");
            }
        }
    }
}
