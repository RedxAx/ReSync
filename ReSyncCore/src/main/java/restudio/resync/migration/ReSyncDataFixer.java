package restudio.resync.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.regex.Pattern;

public final class ReSyncDataFixer {
    public static final String VERSION_DIRECTORY = ".migrations";
    public static final String VERSION_FILE = "data-version";
    private static final String FORMAT = "resync-data-version-v1";
    private static final String REPORT_FORMAT = "resync-data-fix-report-v1";
    private static final Pattern FIX_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");
    private static final Pattern REPORT_NAME = Pattern.compile(
        "data-fix-([1-9][0-9]*)-to-([1-9][0-9]*)-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.txt");
    private static final long MAX_VERSION_BYTES = 4096L;
    private static final long MAX_REPORT_BYTES = 64L * 1024L;
    private final int currentVersion;
    private final Map<Integer, ReSyncDataFix> fixes;
    private final String registryHash;

    public ReSyncDataFixer(int currentVersion, Collection<? extends ReSyncDataFix> fixes) {
        if (currentVersion < 1) {
            throw new IllegalArgumentException("Current ReSync Data Version Must Be Positive");
        }
        this.currentVersion = currentVersion;
        List<ReSyncDataFix> ordered = new ArrayList<>(fixes == null ? List.of() : fixes);
        ordered.sort(Comparator.comparingInt(ReSyncDataFix::sourceVersion));
        Map<Integer, ReSyncDataFix> indexed = new HashMap<>();
        HashSet<String> ids = new HashSet<>();
        StringBuilder registry = new StringBuilder("current=").append(currentVersion).append('\n');
        for (ReSyncDataFix fix : ordered) {
            ReSyncDataFix value = Objects.requireNonNull(fix, "fix");
            String id = MigrationCanonical.requireText(value.id(), "dataFixId");
            if (!FIX_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("ReSync Data Fix Id Is Invalid: " + id);
            }
            if (value.sourceVersion() < 1 || value.targetVersion() != value.sourceVersion() + 1
                || value.targetVersion() > currentVersion) {
                throw new IllegalArgumentException("ReSync Data Fix Must Advance Exactly One Supported Version: " + id);
            }
            if (indexed.putIfAbsent(value.sourceVersion(), value) != null) {
                throw new IllegalArgumentException("Duplicate ReSync Data Fix Source Version: " + value.sourceVersion());
            }
            if (!ids.add(id)) {
                throw new IllegalArgumentException("Duplicate ReSync Data Fix Id: " + id);
            }
            registry.append(value.sourceVersion()).append('>').append(value.targetVersion()).append(':').append(id).append('\n');
        }
        for (int version = 1; version < currentVersion; version++) {
            if (!indexed.containsKey(version)) {
                throw new IllegalArgumentException("ReSync Data Fix Registry Has No " + version + " To " + (version + 1) + " Step");
            }
        }
        this.fixes = Map.copyOf(indexed);
        this.registryHash = MigrationCanonical.sha256(registry.toString());
    }

    public int currentVersion() {
        return currentVersion;
    }

    public String registryHash() {
        return registryHash;
    }

    Result prepare(Path activeRoot, Path coordinationRoot, boolean allowCurrentBaseline,
                   MigrationActivator activation) throws IOException {
        Path root = MigrationPaths.requireDirectory(activeRoot, "activeRoot");
        Path coordination = MigrationPaths.requireDirectory(coordinationRoot, "coordinationRoot");
        MigrationActivator publisher = Objects.requireNonNull(activation, "activation");
        Path versionFile = versionPath(root);
        if (!Files.exists(versionFile, LinkOption.NOFOLLOW_LINKS)) {
            if (!allowCurrentBaseline) {
                throw new MigrationException("ReSync Data Has No Current Format Version At " + versionFile
                    + ". Preserve This Root And Restore A Verified Backup Before Restarting");
            }
            writeVersion(versionFile, currentVersion);
            return new Result(root, currentVersion, currentVersion, List.of(), false);
        }
        int sourceVersion = readVersion(versionFile);
        if (sourceVersion > currentVersion) {
            throw new MigrationException("ReSync Data Version " + sourceVersion
                + " Is Newer Than Supported Version " + currentVersion);
        }
        if (sourceVersion == currentVersion) {
            return new Result(root, sourceVersion, currentVersion, List.of(), false);
        }
        List<ReSyncDataFix> chain = chain(sourceVersion);
        Path staging = coordination.resolve("active-roots").resolve("data-fix-" + UUID.randomUUID())
            .toAbsolutePath().normalize();
        copy(root, staging);
        List<String> applied = new ArrayList<>();
        boolean activationStarted = false;
        try {
            for (ReSyncDataFix fix : chain) {
                fix.apply(new ReSyncDataFix.Context(staging, fix.sourceVersion(), fix.targetVersion()));
                applied.add(fix.id());
            }
            MigrationPaths.requireNoSymlinkTree(staging);
            writeReport(staging, sourceVersion, applied, root, staging);
            writeVersion(versionPath(staging), currentVersion);
            String planHash = MigrationCanonical.sha256(
                sourceVersion + ">" + currentVersion + "\n" + String.join("\n", applied));
            StagedMigration staged = new StagedMigration(staging, Optional.of(root), planHash, TreeDigest.of(staging));
            activationStarted = true;
            publisher.activate(staged);
            return new Result(staging, sourceVersion, currentVersion, applied, true);
        } catch (IOException | RuntimeException exception) {
            if (!activationStarted) {
                discard(staging, exception);
            }
            throw exception;
        }
    }

    public static OptionalInt installedVersion(Path activeRoot) throws IOException {
        Path root = MigrationPaths.requireDirectory(activeRoot, "activeRoot");
        Path versionFile = versionPath(root);
        if (!Files.exists(versionFile, LinkOption.NOFOLLOW_LINKS)) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(readVersion(versionFile));
    }

    public static Path versionPath(Path activeRoot) {
        Path root = MigrationPaths.requirePath(activeRoot, "activeRoot");
        return root.resolve(VERSION_DIRECTORY).resolve(VERSION_FILE).toAbsolutePath().normalize();
    }

    public static boolean isDataFixReportName(String name) {
        return name != null && REPORT_NAME.matcher(name).matches();
    }

    public static void validateReport(Path path) throws IOException {
        Path report = MigrationPaths.requirePath(path, "dataFixReport");
        if (Files.isSymbolicLink(report) || !Files.isRegularFile(report, LinkOption.NOFOLLOW_LINKS)
            || Files.size(report) > MAX_REPORT_BYTES) {
            throw new MigrationException("ReSync Data Fix Report Is Invalid: " + report);
        }
        List<String> lines = Files.readAllLines(report, StandardCharsets.UTF_8);
        if (lines.size() != 9 || !lines.get(0).equals("format=" + REPORT_FORMAT)
            || !lines.get(1).startsWith("source-version=") || !lines.get(2).startsWith("target-version=")
            || !lines.get(3).startsWith("registry-hash=") || !lines.get(4).startsWith("applied=")
            || !lines.get(5).startsWith("previous-root=") || !lines.get(6).startsWith("active-root=")
            || !lines.get(7).startsWith("completed-at=") || !lines.get(8).startsWith("hash=")) {
            throw new MigrationException("ReSync Data Fix Report Format Is Invalid: " + report);
        }
        String body = String.join("\n", lines.subList(0, 8)) + "\n";
        if (!MigrationCanonical.sha256(body).equals(lines.get(8).substring("hash=".length()))) {
            throw new MigrationException("ReSync Data Fix Report Hash Does Not Match: " + report);
        }
        try {
            int source = Integer.parseInt(lines.get(1).substring("source-version=".length()));
            int target = Integer.parseInt(lines.get(2).substring("target-version=".length()));
            MigrationCanonical.requireDigest(lines.get(3).substring("registry-hash=".length()), "registryHash");
            List<String> applied = List.of(lines.get(4).substring("applied=".length()).split(",", -1));
            MigrationCanonical.decode(lines.get(5).substring("previous-root=".length()));
            MigrationCanonical.decode(lines.get(6).substring("active-root=".length()));
            Instant.parse(lines.get(7).substring("completed-at=".length()));
            var name = REPORT_NAME.matcher(report.getFileName().toString());
            if (!name.matches() || source < 1 || target <= source
                || source != Integer.parseInt(name.group(1)) || target != Integer.parseInt(name.group(2))
                || !UUID.fromString(name.group(3)).toString().equals(name.group(3))
                || applied.size() != target - source || applied.stream().anyMatch(id -> !FIX_ID.matcher(id).matches())
                || new HashSet<>(applied).size() != applied.size()) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException exception) {
            throw new MigrationException("ReSync Data Fix Report Values Are Invalid: " + report, exception);
        }
    }

    private List<ReSyncDataFix> chain(int sourceVersion) throws MigrationException {
        List<ReSyncDataFix> chain = new ArrayList<>();
        int version = sourceVersion;
        while (version < currentVersion) {
            ReSyncDataFix fix = fixes.get(version);
            if (fix == null) {
                throw new MigrationException("ReSync Data Fix Chain Has No " + version + " To " + (version + 1) + " Step");
            }
            chain.add(fix);
            version = fix.targetVersion();
        }
        return List.copyOf(chain);
    }

    private static void copy(Path source, Path target) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("ReSync Data Fix Staging Root Already Exists");
        }
        Files.createDirectories(target);
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(directory)) {
                    throw new MigrationException("ReSync Data Fix Source Contains A Symbolic Link");
                }
                if (!directory.equals(source)) {
                    Files.createDirectory(target.resolve(source.relativize(directory)));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(file) || !attributes.isRegularFile()) {
                    throw new MigrationException("ReSync Data Fix Source Contains A Non-Regular File");
                }
                Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void discard(Path root, Throwable failure) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                    if (exception != null) {
                        throw exception;
                    }
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private static int readVersion(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("ReSync Data Version Is Not A Regular File");
        }
        if (Files.size(path) > MAX_VERSION_BYTES) {
            throw new MigrationException("ReSync Data Version File Is Too Large");
        }
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.size() != 3 || !lines.get(0).equals("format=" + FORMAT) || !lines.get(1).startsWith("version=")
            || !lines.get(2).startsWith("hash=")) {
            throw new MigrationException("ReSync Data Version File Is Invalid");
        }
        String body = lines.get(0) + "\n" + lines.get(1) + "\n";
        if (!MigrationCanonical.sha256(body).equals(lines.get(2).substring("hash=".length()))) {
            throw new MigrationException("ReSync Data Version Hash Does Not Match");
        }
        try {
            int version = Integer.parseInt(lines.get(1).substring("version=".length()));
            if (version < 1) {
                throw new NumberFormatException();
            }
            return version;
        } catch (NumberFormatException exception) {
            throw new MigrationException("ReSync Data Version Is Invalid", exception);
        }
    }

    private static void writeVersion(Path path, int version) throws IOException {
        String body = "format=" + FORMAT + "\nversion=" + version + "\n";
        AtomicFiles.write(path, (body + "hash=" + MigrationCanonical.sha256(body) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private void writeReport(Path activeRoot, int sourceVersion, List<String> applied, Path previous, Path active)
        throws IOException {
        Path reports = activeRoot.resolve(VERSION_DIRECTORY);
        Files.createDirectories(reports);
        String body = "format=" + REPORT_FORMAT + "\n"
            + "source-version=" + sourceVersion + "\n"
            + "target-version=" + currentVersion + "\n"
            + "registry-hash=" + registryHash + "\n"
            + "applied=" + String.join(",", applied) + "\n"
            + "previous-root=" + MigrationCanonical.encode(previous.toString()) + "\n"
            + "active-root=" + MigrationCanonical.encode(active.toString()) + "\n"
            + "completed-at=" + Instant.now() + "\n";
        String name = "data-fix-" + sourceVersion + "-to-" + currentVersion + "-" + UUID.randomUUID() + ".txt";
        AtomicFiles.write(reports.resolve(name),
            (body + "hash=" + MigrationCanonical.sha256(body) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    public record Result(Path activeRoot, int sourceVersion, int targetVersion, List<String> appliedFixes, boolean changed) {
        public Result {
            activeRoot = Objects.requireNonNull(activeRoot, "activeRoot").toAbsolutePath().normalize();
            appliedFixes = List.copyOf(appliedFixes);
        }
    }
}
