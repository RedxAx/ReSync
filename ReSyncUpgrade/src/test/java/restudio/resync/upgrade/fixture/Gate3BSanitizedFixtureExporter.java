package restudio.resync.upgrade.fixture;

import restudio.resync.contract.canonical.CanonicalHash;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.migration.MigrationException;
import restudio.resync.migration.MigrationFence;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.PersistenceParticipant;
import restudio.resync.migration.PersistenceParticipantRegistry;
import restudio.resync.migration.ProductionSnapshotMetadataManifest;
import restudio.resync.migration.Snapshot;
import restudio.resync.migration.SnapshotManifest;
import restudio.resync.migration.SnapshotMetadata;
import restudio.resync.migration.SnapshotService;
import restudio.resync.migration.SnapshotVerification;
import restudio.resync.upgrade.LegacySnapshotWindow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class Gate3BSanitizedFixtureExporter {
    public static final String FIXTURE_ID = "FX-013";
    public static final String DEFAULT_SNAPSHOT_ID = "gate3b-sanitized-folder";
    public static final String DEFAULT_BUILD = LegacySnapshotWindow.SOURCE_BUILD;
    public static final String DEFAULT_EXTENSION_OWNER = "fixture-extension";
    public static final String DEFAULT_EXTENSION_VERSION = "1.0.0";
    private static final String MANIFEST_FILE = "fixture-manifest.json";
    private static final String MANIFEST_PIN = "manifest.sha256";
    private static final String SANITIZED_ROOT = "sanitized-real";
    private static final String POPULATED_ROOT = "populated";
    private static final Pattern SECRET_MARKER = Pattern.compile(
        "(?i)(?:password|passwd|secret|access[_-]?token|bearer|private\\s+key)\\s*[=:]|https?://");

    private Gate3BSanitizedFixtureExporter() {
    }

    public static ExportResult export(Path fixtureRoot, Path outputRoot) throws IOException {
        Path fixture = MigrationPaths.requireDirectory(fixtureRoot, "fixtureRoot");
        FixtureManifest manifest = FixtureManifest.read(fixture);
        SnapshotMetadata metadata = new SnapshotMetadata(
            1,
            DEFAULT_SNAPSHOT_ID,
            Instant.EPOCH,
            DEFAULT_BUILD,
            manifest.catalogChecksum(),
            Map.of(DEFAULT_EXTENSION_OWNER, DEFAULT_EXTENSION_VERSION));
        return export(fixture, outputRoot, metadata, manifest);
    }

    public static ExportResult export(Path fixtureRoot, Path outputRoot, SnapshotMetadata metadata) throws IOException {
        Path fixture = MigrationPaths.requireDirectory(fixtureRoot, "fixtureRoot");
        return export(fixture, outputRoot, metadata, FixtureManifest.read(fixture));
    }

    public static Path locateFixtureRoot(Path workingDirectory) throws IOException {
        Path working = MigrationPaths.requireDirectory(workingDirectory, "workingDirectory");
        List<Path> candidates = List.of(
            working.resolve("src/test/resources/fixtures/node-replacement/full-folder"),
            working.resolve("ReSyncUpgrade/src/test/resources/fixtures/node-replacement/full-folder"),
            working.resolve("../src/test/resources/fixtures/node-replacement/full-folder").normalize());
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return MigrationPaths.requireDirectory(candidate, "fixtureRoot");
            }
        }
        throw new IOException("Gate 3B Sanitized Fixture Root Is Missing");
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        ExportResult result = export(options.fixtureRoot(), options.outputRoot());
        System.out.println("fixture-id=" + FIXTURE_ID);
        System.out.println("root=" + result.snapshot().root());
        System.out.println("manifest=" + result.snapshot().manifest().manifestHash());
        System.out.println("files=" + result.snapshot().manifest().entries().size());
    }

    private static ExportResult export(Path fixture, Path output, SnapshotMetadata metadata,
                                       FixtureManifest manifest) throws IOException {
        Objects.requireNonNull(metadata, "metadata");
        Path normalizedFixture = MigrationPaths.requireDirectory(fixture, "fixtureRoot");
        Path normalizedOutput = MigrationPaths.requirePath(output, "outputRoot");
        MigrationPaths.requireDistinctRoots(normalizedFixture, normalizedOutput);
        MigrationPaths.requireWritableParent(normalizedOutput);
        requireOutputAvailable(normalizedOutput);
        Map<Path, String> fixtureDigest = treeDigest(normalizedFixture);
        Path assembly = Files.createTempDirectory(normalizedOutput.getParent(), ".gate3b-assembly-");
        List<String> skippedConflicts = new ArrayList<>();
        try {
            List<SourceFile> selected = compose(manifest, normalizedFixture, assembly, skippedConflicts);
            PersistenceParticipantRegistry participants = participants(assembly, manifest, selected);
            Snapshot snapshot = new SnapshotService(new MigrationFence()).create(
                assembly, normalizedOutput, metadata, participants);
            SnapshotVerification verification = snapshot.verification();
            verification.requireVerified();
            SnapshotManifest persistedManifest = SnapshotManifest.read(snapshot.manifestPath());
            if (!persistedManifest.metadata().equals(metadata)
                || !persistedManifest.manifestHash().equals(snapshot.manifest().manifestHash())) {
                throw new MigrationException("Exported Snapshot Manifest Does Not Match Snapshot Metadata");
            }
            ProductionSnapshotMetadataManifest.read(normalizedOutput);
            verifySelected(snapshot, selected);
            requireSameTree(fixtureDigest, treeDigest(normalizedFixture));
            return new ExportResult(snapshot, skippedConflicts);
        } finally {
            deleteTree(assembly);
        }
    }

    private static void requireOutputAvailable(Path output) throws IOException {
        Path manifest = output.resolveSibling(output.getFileName() + ".manifest");
        Path metadata = output.resolveSibling(output.getFileName() + ".metadata");
        Path state = output.resolveSibling(output.getFileName() + ".state");
        for (Path sidecar : List.of(manifest, metadata, state)) {
            if (Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("Export Sidecar Already Exists: " + sidecar.getFileName());
            }
        }
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(output) || !Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("outputRoot Must Be A Non-Symbolic-Link Directory");
            }
            try (var files = Files.list(output)) {
                if (files.findAny().isPresent()) {
                    throw new MigrationException("outputRoot Must Be Empty");
                }
            }
        }
    }

    private static List<SourceFile> compose(FixtureManifest manifest, Path fixture, Path assembly,
                                            List<String> skippedConflicts) throws IOException {
        Path populated = fixture.resolve(POPULATED_ROOT);
        Path sanitized = fixture.resolve(SANITIZED_ROOT);
        MigrationPaths.requireDirectory(populated, "populatedFixtureRoot");
        MigrationPaths.requireDirectory(sanitized, "sanitizedFixtureRoot");
        List<SourceFile> selected = new ArrayList<>();
        Set<String> selectedPaths = new HashSet<>();
        for (Path source : files(populated)) {
            String relative = relative(populated, source);
            ParticipantSpec participant = manifest.participantFor(relative);
            if (participant == null) {
                throw new MigrationException("Populated Fixture File Has No Declared Owner: " + relative);
            }
            Path target = MigrationPaths.resolveInside(assembly, relative);
            copy(source, target);
            selectedPaths.add(relative);
            selected.add(new SourceFile(relative, source, participant.owner()));
        }
        for (Path source : files(sanitized)) {
            String relative = relative(sanitized, source);
            Path target = MigrationPaths.resolveInside(assembly, relative);
            if (selectedPaths.contains(relative)) {
                ParticipantSpec participant = manifest.participantFor(relative);
                if (participant == null) {
                    throw new MigrationException("Conflicting Sanitized Fixture File Has No Explicit Participant Authority: " + relative);
                }
                skippedConflicts.add(relative);
                continue;
            }
            ParticipantSpec participant = manifest.supplementalParticipant(relative);
            if (participant == null) {
                throw new MigrationException("Sanitized Fixture File Has No Declared Owner: " + relative);
            }
            copy(source, target);
            selectedPaths.add(relative);
            selected.add(new SourceFile(relative, source, participant.owner()));
        }
        selected.sort(Comparator.comparing(SourceFile::relativePath));
        return List.copyOf(selected);
    }

    private static PersistenceParticipantRegistry participants(Path assembly, FixtureManifest manifest,
                                                               List<SourceFile> selected) throws IOException {
        Map<String, Set<String>> selectedByOwner = new TreeMap<>();
        for (SourceFile source : selected) {
            selectedByOwner.computeIfAbsent(source.owner(), ignored -> new TreeSet<>()).add(source.relativePath());
        }
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry();
        Map<String, List<ParticipantSpec>> specsByOwner = manifest.participants().stream()
            .collect(Collectors.groupingBy(ParticipantSpec::owner, TreeMap::new, Collectors.toList()));
        for (Map.Entry<String, List<ParticipantSpec>> owner : specsByOwner.entrySet()) {
            List<ParticipantSpec> specs = owner.getValue();
            specs.sort(Comparator.comparing(ParticipantSpec::relativePath));
            Path root = MigrationPaths.resolveInside(assembly, specs.getFirst().relativePath());
            Set<String> owned = selectedByOwner.getOrDefault(owner.getKey(), Set.of());
            if (owned.isEmpty()) {
                throw new MigrationException("Persistence Participant Has No Exported Files: " + owner.getKey());
            }
            Set<Path> prefixes = new HashSet<>();
            for (ParticipantSpec spec : specs) {
                prefixes.add(MigrationPaths.resolveInside(assembly, spec.relativePath()));
                String topLevel = topLevel(spec.relativePath());
                for (String relative : owned) {
                    if (topLevel(relative).equals(topLevel)) {
                        prefixes.add(MigrationPaths.resolveInside(assembly, topLevel));
                    }
                }
            }
            registry.register(new FixtureParticipant(owner.getKey(), root, prefixes));
        }
        registry.validateForRoot(assembly);
        return registry;
    }

    private static void verifySelected(Snapshot snapshot, List<SourceFile> selected) throws IOException {
        Map<String, String> expectedOwners = selected.stream()
            .collect(Collectors.toMap(SourceFile::relativePath, SourceFile::owner, (first, second) -> {
                throw new IllegalArgumentException("Duplicate Exported File: " + first);
            }, TreeMap::new));
        Map<String, SnapshotManifest.Entry> actual = snapshot.manifest().entries().stream()
            .collect(Collectors.toMap(SnapshotManifest.Entry::relativePath, entry -> entry, (first, second) -> {
                throw new IllegalArgumentException("Duplicate Exported Manifest File: " + first.relativePath());
            }, TreeMap::new));
        if (!expectedOwners.keySet().equals(actual.keySet())) {
            throw new MigrationException("Exported Snapshot File Set Does Not Match Selected Fixture Files");
        }
        for (Map.Entry<String, String> expected : expectedOwners.entrySet()) {
            SnapshotManifest.Entry entry = actual.get(expected.getKey());
            if (!expected.getValue().equals(entry.owner())) {
                throw new MigrationException("Exported Snapshot Owner Does Not Match Fixture: " + expected.getKey());
            }
            Path file = MigrationPaths.resolveInside(snapshot.root(), expected.getKey());
            if (!entry.sha256().equals(hash(file)) || entry.size() != Files.size(file)) {
                throw new MigrationException("Exported Snapshot File Hash Does Not Match Fixture: " + expected.getKey());
            }
        }
    }

    private static Map<Path, String> treeDigest(Path root) throws IOException {
        MigrationPaths.requireNoSymlinkTree(root);
        Map<Path, String> digest = new TreeMap<>(Comparator.comparing(Path::toString));
        for (Path file : files(root)) {
            digest.put(root.relativize(file), hash(file));
        }
        return Map.copyOf(digest);
    }

    private static void requireSameTree(Map<Path, String> expected, Map<Path, String> actual) throws IOException {
        if (!expected.equals(actual)) {
            throw new MigrationException("Approved Fixture Sources Changed During Export");
        }
    }

    private static List<Path> files(Path root) throws IOException {
        MigrationPaths.requireNoSymlinkTree(root);
        try (var stream = Files.walk(root)) {
            List<Path> files = stream.filter(path -> !path.equals(root))
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .sorted(Comparator.comparing(path -> relative(root, path)))
                .toList();
            for (Path file : files) {
                requireSanitizedContent(file);
            }
            return files;
        }
    }

    private static void requireSanitizedContent(Path file) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        if (SECRET_MARKER.matcher(content).find()) {
            throw new MigrationException("Sanitized Fixture Contains A Secret Marker: " + file.getFileName());
        }
    }

    private static void copy(Path source, Path target) throws IOException {
        Path parent = target.getParent();
        if (parent == null) {
            throw new MigrationException("Fixture Target Has No Parent: " + target);
        }
        Files.createDirectories(parent);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Fixture Target Is Already Present: " + target);
        }
        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static String topLevel(String relative) {
        int separator = relative.indexOf('/');
        return separator < 0 ? relative : relative.substring(0, separator);
    }

    private static String hash(Path file) throws IOException {
        return CanonicalHash.rawSha256(Files.readAllBytes(file));
    }

    public record ExportResult(Snapshot snapshot, List<String> skippedSanitizedConflicts) {
        public ExportResult {
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
            skippedSanitizedConflicts = List.copyOf(skippedSanitizedConflicts == null ? List.of() : skippedSanitizedConflicts);
        }

        public Path metadataPath() {
            return ProductionSnapshotMetadataManifest.pathFor(snapshot.root());
        }
    }

    private record SourceFile(String relativePath, Path source, String owner) {
        private SourceFile {
            relativePath = MigrationPaths.requireRelative(relativePath);
            source = Objects.requireNonNull(source, "source");
            owner = Objects.requireNonNull(owner, "owner");
        }
    }

    private static final class FixtureParticipant implements PersistenceParticipant {
        private final String owner;
        private final Path root;
        private final Set<Path> prefixes;

        private FixtureParticipant(String owner, Path root, Set<Path> prefixes) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.root = MigrationPaths.requirePath(root, "participantRoot");
            this.prefixes = Set.copyOf(prefixes);
        }

        @Override
        public String owner() {
            return owner;
        }

        @Override
        public Path root() {
            return root;
        }

        @Override
        public boolean owns(Path file) {
            Path candidate = MigrationPaths.requirePath(file, "file");
            return prefixes.stream().anyMatch(prefix -> candidate.equals(prefix) || candidate.startsWith(prefix));
        }
    }

    private record ParticipantSpec(String owner, String relativePath, long size, String sha256) {
        private ParticipantSpec {
            owner = requireText(owner, "participant owner");
            relativePath = MigrationPaths.requireRelative(relativePath);
            if (size < 0) {
                throw new IllegalArgumentException("Participant Size Must Be Non-Negative");
            }
            sha256 = requireDigest(sha256, "participant sha256");
        }

        private static String requireText(String value, String field) {
            if (value == null || value.isBlank() || value.indexOf('\u0000') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                throw new IllegalArgumentException(field + " Is Invalid");
            }
            return value;
        }

        private static String requireDigest(String value, String field) {
            if (value == null || !value.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException(field + " Must Be A SHA-256 Digest");
            }
            return value.toLowerCase(Locale.ROOT);
        }
    }

    private static final class FixtureManifest {
        private final Path fixtureRoot;
        private final String catalogChecksum;
        private final List<ParticipantSpec> participants;
        private final Map<String, ParticipantSpec> participantRoots;
        private final Map<String, ParticipantSpec> topLevelOwners;

        private FixtureManifest(Path fixtureRoot, String catalogChecksum, List<ParticipantSpec> participants) throws IOException {
            this.fixtureRoot = fixtureRoot;
            this.catalogChecksum = catalogChecksum;
            this.participants = List.copyOf(participants);
            this.participantRoots = new TreeMap<>();
            this.topLevelOwners = new TreeMap<>();
            for (ParticipantSpec participant : participants) {
                if (participantRoots.putIfAbsent(participant.relativePath(), participant) != null) {
                    throw new MigrationException("Duplicate Fixture Participant Root: " + participant.relativePath());
                }
                ParticipantSpec topLevel = topLevelOwners.putIfAbsent(topLevel(participant.relativePath()), participant);
                if (topLevel != null && !topLevel.owner().equals(participant.owner())) {
                    throw new MigrationException("Conflicting Fixture Participant Roots: " + participant.relativePath());
                }
            }
            List<String> roots = new ArrayList<>(participantRoots.keySet());
            roots.sort(String::compareTo);
            for (int index = 1; index < roots.size(); index++) {
                String previous = roots.get(index - 1);
                String current = roots.get(index);
                if (current.startsWith(previous + "/") || previous.startsWith(current + "/")) {
                    throw new MigrationException("Overlapping Fixture Participant Roots: " + previous + " And " + current);
                }
            }
        }

        private static FixtureManifest read(Path fixtureRoot) throws IOException {
            Path manifestPath = fixtureRoot.resolve(MANIFEST_FILE);
            Path pinPath = fixtureRoot.resolve(MANIFEST_PIN);
            byte[] bytes = readRegular(manifestPath, "fixture manifest");
            String pin = Files.readString(pinPath, StandardCharsets.UTF_8).stripTrailing();
            if (!pin.matches("[0-9a-f]{64}  fixture-manifest\\.json")
                || !pin.substring(0, 64).equals(CanonicalHash.rawSha256(bytes))) {
                throw new MigrationException("Fixture Manifest Hash Does Not Match Pin");
            }
            Map<?, ?> document = object(CanonicalJson.parse(bytes), "fixture manifest");
            if (!FIXTURE_ID.equals(text(document, "fixtureId"))) {
                throw new MigrationException("Unexpected Gate 3B Fixture ID");
            }
            Map<?, ?> compatibility = object(document.get("compatibility"), "compatibility");
            String catalog = digest(text(compatibility, "catalogHash"), "catalogHash");
            List<ParticipantSpec> participants = new ArrayList<>();
            for (Object value : list(document, "participants")) {
                Map<?, ?> participant = object(value, "participant");
                String path = text(participant, "path");
                if (!path.startsWith(POPULATED_ROOT + "/")) {
                    throw new MigrationException("Fixture Participant Path Must Be Under Populated Root: " + path);
                }
                ParticipantSpec spec = new ParticipantSpec(text(participant, "owner"), path.substring(POPULATED_ROOT.length() + 1),
                    number(participant, "size"), text(participant, "sha256"));
                participants.add(spec);
            }
            FixtureManifest result = new FixtureManifest(fixtureRoot, catalog, participants);
            result.verifySources(document);
            return result;
        }

        private void verifySources(Map<?, ?> document) throws IOException {
            Path sanitized = fixtureRoot.resolve(SANITIZED_ROOT);
            Path populated = fixtureRoot.resolve(POPULATED_ROOT);
            MigrationPaths.requireDirectory(sanitized, "sanitizedFixtureRoot");
            MigrationPaths.requireDirectory(populated, "populatedFixtureRoot");
            Set<String> declaredSanitized = new HashSet<>();
            for (Object value : list(document, "sanitizedFiles")) {
                Map<?, ?> entry = object(value, "sanitized file");
                String relative = MigrationPaths.requireRelative(text(entry, "path"));
                if (!relative.startsWith(SANITIZED_ROOT + "/") || !declaredSanitized.add(relative)) {
                    throw new MigrationException("Duplicate Or Invalid Sanitized Fixture Path: " + relative);
                }
                Path file = MigrationPaths.resolveInside(fixtureRoot, relative);
                requireRegular(file, "sanitized fixture file");
                if (number(entry, "size") != Files.size(file) || !text(entry, "sha256").equals(hash(file))) {
                    throw new MigrationException("Sanitized Fixture Hash Does Not Match Manifest: " + relative);
                }
            }
            Set<String> actualSanitized = files(sanitized).stream()
                .map(path -> SANITIZED_ROOT + "/" + relative(sanitized, path)).collect(java.util.stream.Collectors.toSet());
            if (!actualSanitized.equals(declaredSanitized)) {
                throw new MigrationException("Sanitized Fixture File Inventory Does Not Match Manifest");
            }
            Set<String> covered = new HashSet<>();
            for (ParticipantSpec participant : participants) {
                Path root = MigrationPaths.resolveInside(fixtureRoot, POPULATED_ROOT + "/" + participant.relativePath());
                requireRegularOrDirectory(root, "participant root");
                ParticipantDigest digest = participantDigest(root, populated);
                if (digest.size() != participant.size() || !digest.hash().equals(participant.sha256())) {
                    throw new MigrationException("Participant Hash Does Not Match Manifest: " + participant.owner());
                }
                for (String relative : digest.files()) {
                    if (!covered.add(relative)) {
                        throw new MigrationException("Participant Roots Overlap: " + relative);
                    }
                }
            }
            Set<String> actualPopulated = files(populated).stream()
                .map(path -> relative(populated, path)).collect(java.util.stream.Collectors.toSet());
            if (!actualPopulated.equals(covered)) {
                throw new MigrationException("Populated Fixture File Inventory Is Not Fully Owned");
            }
        }

        private ParticipantSpec participantFor(String relative) throws IOException {
            ParticipantSpec best = null;
            for (ParticipantSpec participant : participants) {
                if (relative.equals(participant.relativePath()) || relative.startsWith(participant.relativePath() + "/")) {
                    if (best != null) {
                        throw new MigrationException("Multiple Fixture Owners For File: " + relative);
                    }
                    best = participant;
                }
            }
            return best;
        }

        private ParticipantSpec participantRoot(String relative) {
            return participantRoots.get(relative);
        }

        private ParticipantSpec supplementalParticipant(String relative) {
            return topLevelOwners.get(topLevel(relative));
        }

        private String catalogChecksum() {
            return catalogChecksum;
        }

        private List<ParticipantSpec> participants() {
            return participants;
        }

        private static ParticipantDigest participantDigest(Path root, Path populated) throws IOException {
            List<Path> files = Files.isRegularFile(root, LinkOption.NOFOLLOW_LINKS)
                ? List.of(root)
                : files(root);
            List<String> records = new ArrayList<>();
            Set<String> relativeFiles = new HashSet<>();
            long size = 0;
            for (Path file : files) {
                String relative = relative(populated, file);
                relativeFiles.add(relative);
                size += Files.size(file);
                records.add(hash(file) + "  " + relative);
            }
            records.sort(String::compareTo);
            return new ParticipantDigest(size, CanonicalHash.rawSha256((String.join("\n", records) + "\n")
                .getBytes(StandardCharsets.UTF_8)), relativeFiles);
        }

        private static byte[] readRegular(Path path, String field) throws IOException {
            requireRegular(path, field);
            return Files.readAllBytes(path);
        }

        private static void requireRegular(Path path, String field) throws IOException {
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException(field + " Must Be A Regular Non-Symbolic-Link File: " + path);
            }
        }

        private static void requireRegularOrDirectory(Path path, String field) throws IOException {
            if (Files.isSymbolicLink(path) || (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))) {
                throw new MigrationException(field + " Must Be A Regular File Or Directory: " + path);
            }
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                MigrationPaths.requireNoSymlinkTree(path);
            }
        }

        private static Map<?, ?> object(Object value, String field) throws IOException {
            if (!(value instanceof Map<?, ?> map)) {
                throw new MigrationException("Fixture Manifest " + field + " Must Be An Object");
            }
            return map;
        }

        private static List<?> list(Map<?, ?> value, String field) throws IOException {
            if (!(value.get(field) instanceof List<?> list)) {
                throw new MigrationException("Fixture Manifest " + field + " Must Be An Array");
            }
            return list;
        }

        private static String text(Map<?, ?> value, String field) throws IOException {
            Object raw = value.get(field);
            if (!(raw instanceof String text) || text.isBlank()) {
                throw new MigrationException("Fixture Manifest " + field + " Must Be Text");
            }
            return text;
        }

        private static long number(Map<?, ?> value, String field) throws IOException {
            Object raw = value.get(field);
            if (!(raw instanceof Number number)) {
                throw new MigrationException("Fixture Manifest " + field + " Must Be A Number");
            }
            return number.longValue();
        }

        private static String digest(String value, String field) throws IOException {
            if (!value.matches("[0-9a-fA-F]{64}")) {
                throw new MigrationException("Fixture Manifest " + field + " Must Be A SHA-256 Digest");
            }
            return value.toLowerCase(Locale.ROOT);
        }
    }

    private record ParticipantDigest(long size, String hash, Set<String> files) {
    }

    private record Options(Path fixtureRoot, Path outputRoot) {
        private static Options parse(String[] args) throws IOException {
            Map<String, String> values = new HashMap<>();
            String[] source = args == null ? new String[0] : args;
            for (int index = 0; index < source.length; index++) {
                String option = source[index];
                if (!option.startsWith("--") || index + 1 >= source.length) {
                    throw new IllegalArgumentException("Usage: --fixture <directory> --output <directory>");
                }
                String name = option.substring(2);
                if (!name.equals("fixture") && !name.equals("output") || values.putIfAbsent(name, source[++index]) != null) {
                    throw new IllegalArgumentException("Usage: --fixture <directory> --output <directory>");
                }
            }
            Path working = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
            Path fixture = values.containsKey("fixture")
                ? MigrationPaths.requirePath(Path.of(values.get("fixture")), "fixtureRoot")
                : locateFixtureRoot(working);
            Path output = values.containsKey("output")
                ? MigrationPaths.requirePath(Path.of(values.get("output")), "outputRoot")
                : working.resolve("build/gate3b/sanitized-folder");
            return new Options(fixture, output);
        }
    }
}
