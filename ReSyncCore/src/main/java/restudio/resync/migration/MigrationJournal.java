package restudio.resync.migration;

import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.SnapshotId;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class MigrationJournal {
    public static final int FORMAT_VERSION = 2;
    public static final int AUTHORITY_FORMAT_VERSION = 4;
    public static final int AUTHORITY_GRANT_FORMAT_VERSION = 5;
    public static final int PUBLICATION_FORMAT_VERSION = 6;
    public static final int PUBLICATION_AUTHORITY_FORMAT_VERSION = 7;
    public static final int PUBLICATION_AUTHORITY_GRANT_FORMAT_VERSION = 8;
    public static final int ACTIVATION_MARKER_FORMAT_VERSION = 2;
    public static final int AUTHORITY_ACTIVATION_MARKER_FORMAT_VERSION = 4;
    private static final String TERMINAL_CONFLICT_PREFIX = "Terminal Publication Conflict: ";

    public record Binding(
        String sourceSnapshotId,
        String sourceManifestHash,
        String quarantineReportHash,
        String acceptanceHash,
        String stagedReplacementDigest,
        String archivedSourceDigest,
        String replacementCatalogHash,
        String runtimeBindingManifestHash,
        int runtimeBindingManifestVersion,
        String participantReadinessHash,
        int participantReadinessVersion,
        ProductionAuthorityBundle authorityBundle,
        String authorityTrustAnchorHash,
        AuthorityUseGrant authorityUseGrant,
        PublicationBinding publicationBinding
    ) {
        public Binding {
            sourceSnapshotId = MigrationCanonical.requireText(sourceSnapshotId, "sourceSnapshotId");
            sourceManifestHash = MigrationCanonical.requireDigest(sourceManifestHash, "sourceManifestHash");
            quarantineReportHash = MigrationCanonical.requireDigest(quarantineReportHash, "quarantineReportHash");
            acceptanceHash = MigrationCanonical.requireDigest(acceptanceHash, "acceptanceHash");
            stagedReplacementDigest = stagedReplacementDigest == null || stagedReplacementDigest.isBlank()
                ? "" : MigrationCanonical.requireDigest(stagedReplacementDigest, "stagedReplacementDigest");
            archivedSourceDigest = archivedSourceDigest == null || archivedSourceDigest.isBlank()
                ? "" : MigrationCanonical.requireDigest(archivedSourceDigest, "archivedSourceDigest");
            replacementCatalogHash = MigrationCanonical.requireDigest(replacementCatalogHash, "replacementCatalogHash");
            runtimeBindingManifestHash = MigrationCanonical.requireDigest(runtimeBindingManifestHash, "runtimeBindingManifestHash");
            if (runtimeBindingManifestVersion < 1) {
                throw new IllegalArgumentException("runtimeBindingManifestVersion Must Be Positive");
            }
            participantReadinessHash = MigrationCanonical.requireDigest(participantReadinessHash, "participantReadinessHash");
            if (participantReadinessVersion < 1) {
                throw new IllegalArgumentException("participantReadinessVersion Must Be Positive");
            }
            if (authorityBundle != null && (!authorityBundle.catalogContentChecksum().canonicalText().equals(replacementCatalogHash)
                || !authorityBundle.runtimeBindingManifestHash().canonicalText().equals(runtimeBindingManifestHash)
                || authorityBundle.runtimeBindingManifestVersion() != runtimeBindingManifestVersion
                || !authorityBundle.readinessReportHash().equals(participantReadinessHash)
                || authorityBundle.readinessReportVersion() != participantReadinessVersion)) {
                throw new IllegalArgumentException("Authority Bundle Does Not Match Journal Binding");
            }
            authorityTrustAnchorHash = authorityTrustAnchorHash == null || authorityTrustAnchorHash.isBlank()
                ? "" : MigrationCanonical.requireDigest(authorityTrustAnchorHash, "authorityTrustAnchorHash");
            if (authorityUseGrant != null && (authorityBundle == null || authorityTrustAnchorHash.isEmpty())) {
                throw new IllegalArgumentException("Journal Authority Use Grant Fields Are Incomplete");
            }
            if (authorityUseGrant != null && (authorityBundle == null
                || !authorityUseGrant.bundleHash().equals(authorityBundle.bundleHash())
                || !authorityUseGrant.serverId().equals(authorityBundle.serverId())
                || !authorityUseGrant.installationId().equals(authorityBundle.installationId())
                || !authorityUseGrant.installAuthorityHash().equals(authorityBundle.installAuthorityHash())
                || !authorityUseGrant.snapshotId().equals(authorityBundle.snapshotId()))) {
                throw new IllegalArgumentException("Authority Use Grant Does Not Match Journal Authority Bundle");
            }
            if (publicationBinding != null
                && !publicationBinding.sourceManifestHash().equals(sourceManifestHash)) {
                throw new IllegalArgumentException("Publication Binding Source Manifest Does Not Match Journal Binding");
            }
        }

        public Binding(String sourceSnapshotId, String sourceManifestHash, String quarantineReportHash,
                       String acceptanceHash, String stagedReplacementDigest, String archivedSourceDigest,
                       String replacementCatalogHash, String runtimeBindingManifestHash,
                       int runtimeBindingManifestVersion, String participantReadinessHash,
                       int participantReadinessVersion, ProductionAuthorityBundle authorityBundle,
                       String authorityTrustAnchorHash, AuthorityUseGrant authorityUseGrant) {
            this(sourceSnapshotId, sourceManifestHash, quarantineReportHash, acceptanceHash, stagedReplacementDigest,
                archivedSourceDigest, replacementCatalogHash, runtimeBindingManifestHash, runtimeBindingManifestVersion,
                participantReadinessHash, participantReadinessVersion, authorityBundle, authorityTrustAnchorHash,
                authorityUseGrant, null);
        }

        public Binding(String sourceSnapshotId, String sourceManifestHash, String quarantineReportHash,
                       String acceptanceHash, String stagedReplacementDigest, String replacementCatalogHash,
                       String runtimeBindingManifestHash, int runtimeBindingManifestVersion,
                       String participantReadinessHash, int participantReadinessVersion) {
            this(sourceSnapshotId, sourceManifestHash, quarantineReportHash, acceptanceHash, stagedReplacementDigest,
                "", replacementCatalogHash, runtimeBindingManifestHash, runtimeBindingManifestVersion,
                participantReadinessHash, participantReadinessVersion, null, "", null, null);
        }

        public Binding(String sourceSnapshotId, String sourceManifestHash, String quarantineReportHash,
                       String acceptanceHash, String stagedReplacementDigest, String archivedSourceDigest,
                       String replacementCatalogHash, String runtimeBindingManifestHash,
                       int runtimeBindingManifestVersion, String participantReadinessHash,
                       int participantReadinessVersion) {
            this(sourceSnapshotId, sourceManifestHash, quarantineReportHash, acceptanceHash, stagedReplacementDigest,
                archivedSourceDigest, replacementCatalogHash, runtimeBindingManifestHash, runtimeBindingManifestVersion,
                participantReadinessHash, participantReadinessVersion, null, "", null, null);
        }

        public Binding(String sourceSnapshotId, String sourceManifestHash, String quarantineReportHash,
                       String acceptanceHash, String stagedReplacementDigest, String archivedSourceDigest,
                       String replacementCatalogHash, String runtimeBindingManifestHash,
                       int runtimeBindingManifestVersion, String participantReadinessHash,
                       int participantReadinessVersion, ProductionAuthorityBundle authorityBundle) {
            this(sourceSnapshotId, sourceManifestHash, quarantineReportHash, acceptanceHash, stagedReplacementDigest,
                archivedSourceDigest, replacementCatalogHash, runtimeBindingManifestHash, runtimeBindingManifestVersion,
                participantReadinessHash, participantReadinessVersion, authorityBundle, "", null, null);
        }

        public Binding withStagedReplacementDigest(String digest) {
            return new Binding(
                sourceSnapshotId,
                sourceManifestHash,
                quarantineReportHash,
                acceptanceHash,
                digest,
                archivedSourceDigest,
                replacementCatalogHash,
                runtimeBindingManifestHash,
                runtimeBindingManifestVersion,
                participantReadinessHash,
                participantReadinessVersion,
                authorityBundle,
                authorityTrustAnchorHash,
                authorityUseGrant,
                publicationBinding);
        }

        public Binding withArchivedSourceDigest(String digest) {
            return new Binding(
                sourceSnapshotId,
                sourceManifestHash,
                quarantineReportHash,
                acceptanceHash,
                stagedReplacementDigest,
                digest,
                replacementCatalogHash,
                runtimeBindingManifestHash,
                runtimeBindingManifestVersion,
                participantReadinessHash,
                participantReadinessVersion,
                authorityBundle,
                authorityTrustAnchorHash,
                authorityUseGrant,
                publicationBinding);
        }

        public Binding withPublicationBinding(PublicationBinding next) {
            return new Binding(
                sourceSnapshotId,
                sourceManifestHash,
                quarantineReportHash,
                acceptanceHash,
                stagedReplacementDigest,
                archivedSourceDigest,
                replacementCatalogHash,
                runtimeBindingManifestHash,
                runtimeBindingManifestVersion,
                participantReadinessHash,
                participantReadinessVersion,
                authorityBundle,
                authorityTrustAnchorHash,
                authorityUseGrant,
                Objects.requireNonNull(next, "publicationBinding"));
        }

        public void requireStagedReplacementDigest() throws MigrationException {
            if (stagedReplacementDigest.isEmpty()) {
                throw new MigrationException("Migration Journal Staged Replacement Digest Is Missing");
            }
        }

        public void requireArchivedSourceDigest() throws MigrationException {
            if (archivedSourceDigest.isEmpty()) {
                throw new MigrationException("Migration Journal Archived Source Digest Is Missing");
            }
        }

        public PublicationBinding requirePublicationBinding() throws MigrationException {
            if (publicationBinding == null) {
                throw new MigrationException("Migration Journal Publication Binding Is Missing");
            }
            return publicationBinding;
        }
    }

    public record PublicationBinding(
        String artifactHash,
        String publisherContractIdentity,
        String sourceManifestHash,
        String postStageManifestHash
    ) {
        public PublicationBinding {
            artifactHash = MigrationCanonical.requireDigest(artifactHash, "publicationArtifactHash");
            publisherContractIdentity = MigrationCanonical.requireText(publisherContractIdentity, "publisherContractIdentity");
            if (!publisherContractIdentity.equals(publisherContractIdentity.strip())) {
                throw new IllegalArgumentException("publisherContractIdentity Must Not Have Surrounding Whitespace");
            }
            sourceManifestHash = MigrationCanonical.requireDigest(sourceManifestHash, "publicationSourceManifestHash");
            postStageManifestHash = MigrationCanonical.requireDigest(postStageManifestHash, "publicationPostStageManifestHash");
        }
    }

    public record Entry(MigrationJournalState state, long timestamp, String detail) {
        public Entry {
            state = Objects.requireNonNull(state, "state");
            if (timestamp < 0) {
                throw new IllegalArgumentException("Journal Timestamp Must Be Non-Negative");
            }
            detail = MigrationCanonical.requireText(detail, "journal detail");
        }
    }

    private static final Map<MigrationJournalState, Set<MigrationJournalState>> TRANSITIONS = Map.of(
            MigrationJournalState.PREPARED, EnumSet.of(MigrationJournalState.TRANSFORMING, MigrationJournalState.FAILED, MigrationJournalState.ROLLED_BACK),
            MigrationJournalState.TRANSFORMING, EnumSet.of(MigrationJournalState.VALIDATING, MigrationJournalState.FAILED, MigrationJournalState.ROLLED_BACK),
            MigrationJournalState.VALIDATING, EnumSet.of(MigrationJournalState.STAGED, MigrationJournalState.FAILED, MigrationJournalState.ROLLED_BACK),
            MigrationJournalState.STAGED, EnumSet.of(MigrationJournalState.ACTIVATED, MigrationJournalState.FAILED, MigrationJournalState.ROLLED_BACK),
            MigrationJournalState.ACTIVATED, EnumSet.of(MigrationJournalState.COMMITTED, MigrationJournalState.FAILED, MigrationJournalState.ROLLED_BACK),
            MigrationJournalState.FAILED, EnumSet.of(MigrationJournalState.ROLLED_BACK),
            MigrationJournalState.ROLLED_BACK, EnumSet.noneOf(MigrationJournalState.class),
            MigrationJournalState.COMMITTED, EnumSet.noneOf(MigrationJournalState.class));

    private final Path path;
    private final String migrationId;
    private final String planHash;
    private final Clock clock;
    private final List<Entry> entries;
    private Binding binding;

    private MigrationJournal(Path path, String migrationId, String planHash, Clock clock, List<Entry> entries, Binding binding) {
        this.path = MigrationPaths.requirePath(path, "journalPath");
        this.migrationId = MigrationCanonical.requireText(migrationId, "migrationId");
        this.planHash = MigrationCanonical.requireDigest(planHash, "planHash");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.entries = new ArrayList<>(entries);
        this.binding = binding;
    }

    public static MigrationJournal create(Path path, String migrationId, String planHash) throws IOException {
        return create(path, migrationId, planHash, Clock.systemUTC());
    }

    public static MigrationJournal create(Path path, String migrationId, String planHash, Clock clock) throws IOException {
        Path normalized = MigrationPaths.requirePath(path, "journalPath");
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Migration Journal Already Exists: " + normalized);
        }
        MigrationJournal journal = new MigrationJournal(normalized, migrationId, planHash, clock, List.of(), null);
        journal.persist();
        return journal;
    }

    public static MigrationJournal create(Path path, String migrationId, String planHash, Binding binding) throws IOException {
        return create(path, migrationId, planHash, Clock.systemUTC(), binding);
    }

    public static MigrationJournal create(Path path, String migrationId, String planHash, Clock clock, Binding binding) throws IOException {
        Path normalized = MigrationPaths.requirePath(path, "journalPath");
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Migration Journal Already Exists: " + normalized);
        }
        MigrationJournal journal = new MigrationJournal(normalized, migrationId, planHash, clock, List.of(), Objects.requireNonNull(binding, "binding"));
        journal.persist();
        return journal;
    }

    public static MigrationJournal open(Path path) throws IOException {
        return open(path, Clock.systemUTC());
    }

    public static MigrationJournal open(Path path, Clock clock) throws IOException {
        Path normalized = MigrationPaths.requirePath(path, "journalPath");
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) {
            throw new MigrationException("Migration Journal Is Not A Regular File: " + normalized);
        }
        String content = decodeUtf8(Files.readAllBytes(normalized));
        List<String> lines = new ArrayList<>(Arrays.asList(content.split("\n", -1)));
        if (lines.isEmpty() || !lines.getLast().isEmpty()) {
            throw new MigrationException("Migration Journal Must End With A Newline");
        }
        lines.removeLast();
        if (lines.size() < 4 || (!"format=1".equals(lines.getFirst())
            && !("format=" + FORMAT_VERSION).equals(lines.getFirst())
            && !("format=" + AUTHORITY_FORMAT_VERSION).equals(lines.getFirst())
            && !("format=" + AUTHORITY_GRANT_FORMAT_VERSION).equals(lines.getFirst())
            && !("format=" + PUBLICATION_FORMAT_VERSION).equals(lines.getFirst())
            && !("format=" + PUBLICATION_AUTHORITY_FORMAT_VERSION).equals(lines.getFirst())
            && !("format=" + PUBLICATION_AUTHORITY_GRANT_FORMAT_VERSION).equals(lines.getFirst()))) {
            throw new MigrationException("Migration Journal Header Is Invalid");
        }
        boolean bound = ("format=" + FORMAT_VERSION).equals(lines.getFirst())
            || ("format=" + AUTHORITY_FORMAT_VERSION).equals(lines.getFirst())
            || ("format=" + AUTHORITY_GRANT_FORMAT_VERSION).equals(lines.getFirst())
            || ("format=" + PUBLICATION_FORMAT_VERSION).equals(lines.getFirst())
            || ("format=" + PUBLICATION_AUTHORITY_FORMAT_VERSION).equals(lines.getFirst())
            || ("format=" + PUBLICATION_AUTHORITY_GRANT_FORMAT_VERSION).equals(lines.getFirst());
        boolean authorityBound = ("format=" + AUTHORITY_FORMAT_VERSION).equals(lines.getFirst())
            || ("format=" + AUTHORITY_GRANT_FORMAT_VERSION).equals(lines.getFirst())
            || ("format=" + PUBLICATION_AUTHORITY_FORMAT_VERSION).equals(lines.getFirst())
            || ("format=" + PUBLICATION_AUTHORITY_GRANT_FORMAT_VERSION).equals(lines.getFirst());
        boolean authorityGrantBound = ("format=" + AUTHORITY_GRANT_FORMAT_VERSION).equals(lines.getFirst())
            || ("format=" + PUBLICATION_AUTHORITY_GRANT_FORMAT_VERSION).equals(lines.getFirst());
        boolean publicationBound = ("format=" + PUBLICATION_FORMAT_VERSION).equals(lines.getFirst())
            || ("format=" + PUBLICATION_AUTHORITY_FORMAT_VERSION).equals(lines.getFirst())
            || ("format=" + PUBLICATION_AUTHORITY_GRANT_FORMAT_VERSION).equals(lines.getFirst());
        String storedJournalHash = null;
        if (bound) {
            if (lines.size() < 2 || !lines.getLast().startsWith("journal-hash=")) {
                throw new MigrationException("Migration Journal Hash Is Missing");
            }
            storedJournalHash = MigrationCanonical.requireDigest(raw(lines, lines.size() - 1, "journal-hash="), "journalHash");
            lines.removeLast();
        }
        String migrationId = raw(lines, 1, "migration-id=");
        String planHash = raw(lines, 2, "plan-hash=");
        int cursor = 3;
        Binding binding = null;
        if (bound) {
            String sourceSnapshotId = MigrationCanonical.decode(raw(lines, cursor++, "source-snapshot-id="));
            String sourceManifestHash = raw(lines, cursor++, "source-manifest-hash=");
            String quarantineReportHash = raw(lines, cursor++, "quarantine-report-hash=");
            String acceptanceHash = raw(lines, cursor++, "acceptance-hash=");
            String stagedReplacementDigest = raw(lines, cursor++, "staged-replacement-digest=");
            String archivedSourceDigest = raw(lines, cursor++, "archived-source-digest=");
            PublicationBinding publicationBinding = null;
            if (publicationBound) {
                publicationBinding = new PublicationBinding(
                    raw(lines, cursor++, "publication-artifact-hash="),
                    MigrationCanonical.decode(raw(lines, cursor++, "publication-publisher-contract-identity=")),
                    raw(lines, cursor++, "publication-source-manifest-hash="),
                    raw(lines, cursor++, "publication-post-stage-manifest-hash="));
            }
            String replacementCatalogHash = raw(lines, cursor++, "replacement-catalog-hash=");
            int runtimeBindingManifestVersion = integer(raw(lines, cursor++, "runtime-binding-manifest-version="));
            String runtimeBindingManifestHash = raw(lines, cursor++, "runtime-binding-manifest-hash=");
            int participantReadinessVersion = integer(raw(lines, cursor++, "participant-readiness-version="));
            String participantReadinessHash = raw(lines, cursor++, "participant-readiness-hash=");
            int markerFormat = integer(raw(lines, cursor++, "activation-marker-format="));
            if (markerFormat != (authorityGrantBound ? MigrationActivationMarker.AUTHORITY_GRANT_FORMAT_VERSION
                : authorityBound ? AUTHORITY_ACTIVATION_MARKER_FORMAT_VERSION : ACTIVATION_MARKER_FORMAT_VERSION)) {
                throw new MigrationException("Migration Journal Activation Marker Format Is Unsupported");
            }
            ProductionAuthorityBundle authority = authorityBound ? authority(lines, cursor, authorityGrantBound) : null;
            if (authorityBound) {
                cursor += authorityGrantBound ? 18 : 15;
            }
            String authorityTrustAnchorHash = "";
            AuthorityUseGrant authorityUseGrant = null;
            if (authorityGrantBound) {
                authorityTrustAnchorHash = MigrationCanonical.requireDigest(raw(lines, cursor++, "authority-trust-anchor-hash="),
                    "authorityTrustAnchorHash");
                try {
                    authorityUseGrant = AuthorityUseGrant.fromCanonical(
                        MigrationCanonical.decode(raw(lines, cursor++, "authority-use-grant=")));
                } catch (RuntimeException exception) {
                    throw new MigrationException("Migration Journal Authority Use Grant Is Invalid", exception);
                }
            }
            binding = new Binding(sourceSnapshotId, sourceManifestHash, quarantineReportHash, acceptanceHash,
                stagedReplacementDigest, archivedSourceDigest, replacementCatalogHash, runtimeBindingManifestHash, runtimeBindingManifestVersion,
                participantReadinessHash, participantReadinessVersion, authority, authorityTrustAnchorHash,
                authorityUseGrant, publicationBinding);
        }
        int count = integer(raw(lines, cursor++, "entries="));
        if (count < 0 || lines.size() != count + cursor) {
            throw new MigrationException("Migration Journal Entry Count Is Invalid");
        }
        List<Entry> entries = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String[] fields = raw(lines, index + cursor, "entry=").split("\\|", -1);
            if (fields.length != 3) {
                throw new MigrationException("Migration Journal Entry Is Invalid");
            }
            MigrationJournalState state;
            long timestamp;
            try {
                state = MigrationJournalState.valueOf(fields[0]);
                timestamp = Long.parseLong(fields[1]);
            } catch (IllegalArgumentException exception) {
                throw new MigrationException("Migration Journal Entry Is Invalid", exception);
            }
            entries.add(new Entry(state, timestamp, MigrationCanonical.decode(fields[2])));
        }
        MigrationJournal journal = new MigrationJournal(normalized, MigrationCanonical.decode(migrationId), planHash, clock, entries, binding);
        journal.validateHistory();
        if (bound) {
            String canonical = journal.canonicalContent();
            if (!MigrationCanonical.sha256(canonical).equals(storedJournalHash)
                || !(canonical + "journal-hash=" + storedJournalHash + "\n").equals(content)) {
                throw new MigrationException("Migration Journal Hash Does Not Match Content");
            }
        }
        return journal;
    }

    public synchronized MigrationJournalState transition(MigrationJournalState next, String detail) throws IOException {
        Objects.requireNonNull(next, "next");
        MigrationJournalState current = currentState().orElse(null);
        if (current == null) {
            if (next != MigrationJournalState.PREPARED) {
                throw new IllegalStateException("Migration Journal Must Start At PREPARED");
            }
        } else if (current == next) {
            return current;
        } else if (!TRANSITIONS.get(current).contains(next)) {
            throw new IllegalStateException("Illegal Migration Journal Transition: " + current + " To " + next);
        }
        String normalizedDetail = detail == null || detail.isBlank() ? "No Detail" : MigrationCanonical.requireText(detail, "journal detail");
        Entry entry = new Entry(next, clock.millis(), normalizedDetail);
        entries.add(entry);
        try {
            persist();
        } catch (IOException exception) {
            entries.removeLast();
            throw exception;
        }
        return next;
    }

    public synchronized Optional<MigrationJournalState> currentState() {
        return entries.isEmpty() ? Optional.empty() : Optional.of(entries.getLast().state());
    }

    public synchronized Optional<Entry> lastEntry() {
        return entries.isEmpty() ? Optional.empty() : Optional.of(entries.getLast());
    }

    public synchronized List<Entry> entries() {
        return List.copyOf(entries);
    }

    public synchronized Optional<Binding> binding() {
        return Optional.ofNullable(binding);
    }

    public synchronized void bind(Binding next) throws IOException {
        Objects.requireNonNull(next, "binding");
        if (binding != null && !binding.equals(next)) {
            throw new MigrationException("Migration Journal Binding Does Not Match Existing Binding");
        }
        if (binding == null) {
            replaceBinding(next);
        }
    }

    public synchronized void bindStagedReplacementDigest(String digest) throws IOException {
        if (binding == null) {
            throw new MigrationException("Migration Journal Binding Is Required Before Staged Digest");
        }
        Binding next = binding.withStagedReplacementDigest(digest);
        if (!next.equals(binding)) {
            replaceBinding(next);
        }
    }

    public synchronized void bindArchivedSourceDigest(String digest) throws IOException {
        if (binding == null) {
            throw new MigrationException("Migration Journal Binding Is Required Before Archived Source Digest");
        }
        String normalized = MigrationCanonical.requireDigest(digest, "archivedSourceDigest");
        if (!binding.archivedSourceDigest().isEmpty() && !binding.archivedSourceDigest().equals(normalized)) {
            throw new MigrationException("Migration Journal Archived Source Digest Does Not Match Existing Binding");
        }
        Binding next = binding.withArchivedSourceDigest(normalized);
        if (!next.equals(binding)) {
            replaceBinding(next);
        }
    }

    public synchronized void bindPublication(PublicationBinding next) throws IOException {
        Objects.requireNonNull(next, "publicationBinding");
        if (binding == null) {
            throw new MigrationException("Migration Journal Binding Is Required Before Publication Binding");
        }
        if (binding.publicationBinding() != null && !binding.publicationBinding().equals(next)) {
            throw new MigrationException("Migration Journal Publication Binding Does Not Match Existing Binding");
        }
        Binding updated = binding.withPublicationBinding(next);
        if (!updated.equals(binding)) {
            replaceBinding(updated);
        }
    }

    public synchronized void markTerminalConflict(String detail) throws IOException {
        MigrationJournalState state = currentState().orElse(null);
        if (state == MigrationJournalState.COMMITTED || state == MigrationJournalState.ROLLED_BACK) {
            throw new IllegalStateException("Migration Journal Is Already Terminal: " + state);
        }
        String normalized = detail == null || detail.isBlank() ? "No Detail" : MigrationCanonical.requireText(detail, "terminal conflict detail");
        if (normalized.startsWith(TERMINAL_CONFLICT_PREFIX)) {
            normalized = normalized.substring(TERMINAL_CONFLICT_PREFIX.length());
        }
        transition(MigrationJournalState.FAILED, TERMINAL_CONFLICT_PREFIX + normalized);
    }

    public synchronized boolean terminalConflict() {
        return entries.stream().anyMatch(entry -> entry.state() == MigrationJournalState.FAILED
            && entry.detail().startsWith(TERMINAL_CONFLICT_PREFIX));
    }

    public String migrationId() {
        return migrationId;
    }

    public String planHash() {
        return planHash;
    }

    public Path path() {
        return path;
    }

    public synchronized JournalRecovery recovery() {
        Optional<MigrationJournalState> state = currentState();
        if (state.isEmpty()) {
            return new JournalRecovery(Optional.empty(), JournalRecoveryAction.START);
        }
        return switch (state.get()) {
            case PREPARED, TRANSFORMING, VALIDATING -> new JournalRecovery(state, JournalRecoveryAction.RESUME);
            case STAGED, ACTIVATED -> new JournalRecovery(state, JournalRecoveryAction.VERIFY_OR_ROLLBACK);
            case FAILED -> new JournalRecovery(state,
                terminalConflict() ? JournalRecoveryAction.COMPLETE : JournalRecoveryAction.ROLLBACK);
            case ROLLED_BACK, COMMITTED -> new JournalRecovery(state, JournalRecoveryAction.COMPLETE);
        };
    }

    public synchronized void recoverToRollback(String detail) throws IOException {
        MigrationJournalState state = currentState().orElseThrow(() -> new IllegalStateException("Migration Journal Has No Recoverable State"));
        if (state == MigrationJournalState.COMMITTED || state == MigrationJournalState.ROLLED_BACK) {
            throw new IllegalStateException("Migration Journal Is Already Terminal: " + state);
        }
        transition(MigrationJournalState.ROLLED_BACK, detail);
    }

    private synchronized void validateHistory() throws IOException {
        MigrationJournalState previous = null;
        for (Entry entry : entries) {
            if (previous == null) {
                if (entry.state() != MigrationJournalState.PREPARED) {
                    throw new MigrationException("Migration Journal History Must Start At PREPARED");
                }
            } else if (!TRANSITIONS.get(previous).contains(entry.state())) {
                throw new MigrationException("Migration Journal History Contains An Illegal Transition");
            }
            previous = entry.state();
        }
    }

    private synchronized void persist() throws IOException {
        String canonical = canonicalContent();
        String content = binding == null
            ? canonical
            : canonical + "journal-hash=" + MigrationCanonical.sha256(canonical) + "\n";
        AtomicFiles.write(path, content.getBytes(StandardCharsets.UTF_8));
    }

    private void replaceBinding(Binding next) throws IOException {
        Binding previous = binding;
        binding = next;
        try {
            persist();
        } catch (IOException | RuntimeException exception) {
            binding = previous;
            throw exception;
        }
    }

    private synchronized String canonicalContent() {
        StringBuilder content = new StringBuilder();
        content.append(binding == null ? "format=1\n" : binding.publicationBinding() != null
            ? binding.authorityUseGrant() != null
                ? "format=" + PUBLICATION_AUTHORITY_GRANT_FORMAT_VERSION + "\n" : binding.authorityBundle() == null
                    ? "format=" + PUBLICATION_FORMAT_VERSION + "\n" : "format=" + PUBLICATION_AUTHORITY_FORMAT_VERSION + "\n"
            : binding.authorityUseGrant() != null
                ? "format=" + AUTHORITY_GRANT_FORMAT_VERSION + "\n" : binding.authorityBundle() == null
                    ? "format=" + FORMAT_VERSION + "\n" : "format=" + AUTHORITY_FORMAT_VERSION + "\n");
        content.append("migration-id=").append(MigrationCanonical.encode(migrationId)).append('\n');
        content.append("plan-hash=").append(planHash).append('\n');
        if (binding != null) {
            content.append("source-snapshot-id=").append(MigrationCanonical.encode(binding.sourceSnapshotId())).append('\n');
            content.append("source-manifest-hash=").append(binding.sourceManifestHash()).append('\n');
            content.append("quarantine-report-hash=").append(binding.quarantineReportHash()).append('\n');
            content.append("acceptance-hash=").append(binding.acceptanceHash()).append('\n');
            content.append("staged-replacement-digest=").append(binding.stagedReplacementDigest()).append('\n');
            content.append("archived-source-digest=").append(binding.archivedSourceDigest()).append('\n');
            if (binding.publicationBinding() != null) {
                PublicationBinding publication = binding.publicationBinding();
                content.append("publication-artifact-hash=").append(publication.artifactHash()).append('\n')
                    .append("publication-publisher-contract-identity=")
                    .append(MigrationCanonical.encode(publication.publisherContractIdentity())).append('\n')
                    .append("publication-source-manifest-hash=").append(publication.sourceManifestHash()).append('\n')
                    .append("publication-post-stage-manifest-hash=").append(publication.postStageManifestHash()).append('\n');
            }
            content.append("replacement-catalog-hash=").append(binding.replacementCatalogHash()).append('\n');
            content.append("runtime-binding-manifest-version=").append(binding.runtimeBindingManifestVersion()).append('\n');
            content.append("runtime-binding-manifest-hash=").append(binding.runtimeBindingManifestHash()).append('\n');
            content.append("participant-readiness-version=").append(binding.participantReadinessVersion()).append('\n');
            content.append("participant-readiness-hash=").append(binding.participantReadinessHash()).append('\n');
            content.append("activation-marker-format=").append(binding.authorityUseGrant() != null
                ? MigrationActivationMarker.AUTHORITY_GRANT_FORMAT_VERSION : binding.authorityBundle() == null
                    ? ACTIVATION_MARKER_FORMAT_VERSION : AUTHORITY_ACTIVATION_MARKER_FORMAT_VERSION).append('\n');
            if (binding.authorityBundle() != null) {
                ProductionAuthorityBundle authority = binding.authorityBundle();
                content.append("authority-server-id=").append(authority.serverId().canonicalText()).append('\n')
                    .append("authority-snapshot-id=").append(authority.snapshotId().canonicalText()).append('\n')
                    .append("authority-timestamp=").append(authority.timestamp()).append('\n')
                    .append("authority-catalog-content-checksum=").append(authority.catalogContentChecksum().canonicalText()).append('\n')
                    .append("authority-catalog-generation=").append(authority.catalogGeneration()).append('\n')
                    .append("authority-catalog-contract-generation=").append(authority.catalogContractVersion().generation()).append('\n')
                    .append("authority-catalog-contract-minor=").append(authority.catalogContractVersion().minor()).append('\n')
                    .append("authority-runtime-binding-manifest-hash=").append(authority.runtimeBindingManifestHash().canonicalText()).append('\n')
                    .append("authority-runtime-binding-manifest-version=").append(authority.runtimeBindingManifestVersion()).append('\n')
                    .append("authority-readiness-report-hash=").append(authority.readinessReportHash()).append('\n')
                    .append("authority-readiness-report-version=").append(authority.readinessReportVersion()).append('\n')
                    .append("authority-install-authority-hash=").append(authority.installAuthorityHash()).append('\n')
                    .append("authority-signing-public-key=").append(authority.signingPublicKey()).append('\n')
                    .append("authority-signature=").append(authority.signature()).append('\n')
                    .append("authority-bundle-hash=").append(authority.bundleHash()).append('\n');
                if (binding.authorityUseGrant() != null) {
                    content.append("authority-installation-id=").append(MigrationCanonical.encode(authority.installationId())).append('\n')
                        .append("authority-key-id=").append(MigrationCanonical.encode(authority.authorityKeyId())).append('\n')
                        .append("authority-public-key-fingerprint=").append(authority.publicKeyFingerprint()).append('\n');
                    content.append("authority-trust-anchor-hash=").append(binding.authorityTrustAnchorHash()).append('\n')
                        .append("authority-use-grant=").append(MigrationCanonical.encode(binding.authorityUseGrant().canonical())).append('\n');
                }
            }
        }
        content.append("entries=").append(entries.size()).append('\n');
        entries.forEach(entry -> content.append("entry=").append(entry.state()).append('|').append(entry.timestamp()).append('|').append(MigrationCanonical.encode(entry.detail())).append('\n'));
        return content.toString();
    }

    private static String decodeUtf8(byte[] bytes) throws MigrationException {
        try {
            String value = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString();
            if (!java.util.Arrays.equals(value.getBytes(StandardCharsets.UTF_8), bytes)
                || value.indexOf('\r') >= 0
                || !value.endsWith("\n")) {
                throw new MigrationException("Migration Journal Encoding Is Not Canonical");
            }
            return value;
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw new MigrationException("Migration Journal Encoding Is Invalid", exception);
        }
    }

    private static ProductionAuthorityBundle authority(List<String> lines, int index, boolean grantFormat) throws MigrationException {
        try {
            ServerId serverId = ServerId.parseCanonicalText(
                raw(lines, index++, "authority-server-id="));
            SnapshotId snapshotId = SnapshotId.parseCanonicalText(
                raw(lines, index++, "authority-snapshot-id="));
            String timestampText = raw(lines, index++, "authority-timestamp=");
            Instant timestamp = Instant.parse(timestampText);
            if (!timestamp.toString().equals(timestampText)) {
                throw new MigrationException("Migration Journal Authority Timestamp Is Not Canonical");
            }
            ContentHash catalogChecksum = ContentHash.parseCanonicalText(
                raw(lines, index++, "authority-catalog-content-checksum="));
            long catalogGeneration = Long.parseLong(raw(lines, index++, "authority-catalog-generation="));
            int contractGeneration = Integer.parseInt(raw(lines, index++, "authority-catalog-contract-generation="));
            int contractMinor = Integer.parseInt(raw(lines, index++, "authority-catalog-contract-minor="));
            ContentHash runtimeHash = ContentHash.parseCanonicalText(
                raw(lines, index++, "authority-runtime-binding-manifest-hash="));
            int runtimeVersion = Integer.parseInt(raw(lines, index++, "authority-runtime-binding-manifest-version="));
            String readinessHash = MigrationCanonical.requireDigest(raw(lines, index++, "authority-readiness-report-hash="),
                "authorityReadinessReportHash");
            int readinessVersion = Integer.parseInt(raw(lines, index++, "authority-readiness-report-version="));
            String installAuthorityHash = MigrationCanonical.requireDigest(raw(lines, index++, "authority-install-authority-hash="),
                "installAuthorityHash");
            String signingPublicKey = raw(lines, index++, "authority-signing-public-key=");
            String signature = raw(lines, index++, "authority-signature=");
            String bundleHash = MigrationCanonical.requireDigest(raw(lines, index++, "authority-bundle-hash="), "authorityBundleHash");
            String installationId = serverId.canonicalText();
            String authorityKeyId = ProductionAuthorityTrustAnchor.fingerprint(signingPublicKey);
            String publicKeyFingerprint = authorityKeyId;
            if (grantFormat) {
                installationId = MigrationCanonical.decode(raw(lines, index++, "authority-installation-id="));
                authorityKeyId = MigrationCanonical.decode(raw(lines, index++, "authority-key-id="));
                publicKeyFingerprint = MigrationCanonical.requireDigest(raw(lines, index++, "authority-public-key-fingerprint="),
                    "authorityPublicKeyFingerprint");
            }
            ProductionAuthorityBundle expected = ProductionAuthorityBundle.create(serverId, installationId, authorityKeyId,
                publicKeyFingerprint, snapshotId, timestamp, catalogChecksum, catalogGeneration,
                new CatalogVersion(contractGeneration, contractMinor), runtimeHash,
                runtimeVersion, readinessHash, readinessVersion, installAuthorityHash, signingPublicKey, signature);
            if (!expected.bundleHash().equals(bundleHash)) {
                throw new MigrationException("Migration Journal Authority Bundle Hash Does Not Match");
            }
            return expected;
        } catch (MigrationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MigrationException("Migration Journal Authority Bundle Is Invalid", exception);
        }
    }

    private static String raw(List<String> lines, int index, String prefix) throws MigrationException {
        if (index >= lines.size() || !lines.get(index).startsWith(prefix)) {
            throw new MigrationException("Missing Migration Journal Line: " + prefix);
        }
        return lines.get(index).substring(prefix.length());
    }

    private static int integer(String value) throws MigrationException {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new MigrationException("Invalid Migration Journal Entry Count", exception);
        }
    }
}
