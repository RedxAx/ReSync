package restudio.resync.migration;

import restudio.resync.contract.install.ReSyncInstallationStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public final class LegacyInstallBoundary {
    public static final String RESET_MARKER = ".resync-archive-legacy-and-start-fresh";
    public static final String STATUS_FILE = ".resync-installation-status.json";
    private static final long MAX_MARKER_BYTES = 128L;

    private LegacyInstallBoundary() {
    }

    public static Result prepare(Path dataRoot, Path coordinationRoot) throws IOException {
        Path data = MigrationPaths.requirePath(dataRoot, "dataRoot");
        Path coordination = MigrationPaths.requirePath(coordinationRoot, "coordinationRoot");
        Path pluginRoot = data.getParent();
        if (pluginRoot == null || !pluginRoot.equals(coordination.getParent())) {
            throw new MigrationException("ReSync Data And Coordination Roots Must Share The Plugin Directory");
        }
        Path marker = pluginRoot.resolve(RESET_MARKER);
        boolean markerExists = Files.exists(marker, LinkOption.NOFOLLOW_LINKS);
        InstallState state;
        try {
            state = inspect(data, coordination);
        } catch (IOException exception) {
            if (!markerExists) {
                throw exception;
            }
            state = new InstallState(false, true);
        }
        if (!markerExists) {
            if (!state.occupied() || state.current()) {
                Files.deleteIfExists(pluginRoot.resolve(STATUS_FILE));
                return Result.current();
            }
            writeStatus(pluginRoot, ReSyncInstallationStatus.legacyDataBlocked());
            throw new MigrationException("Pre-Rewrite ReSync Data Cannot Be Migrated. To Preserve It And Start Fresh, Create "
                + marker + " And Restart The Server");
        }
        String token = token(marker);
        Path dataBackup = pluginRoot.resolve("ReSync Legacy Backup " + token);
        Path coordinationBackup = pluginRoot.resolve(".resync-coordination Legacy Backup " + token);
        if (state.current() && !Files.exists(dataBackup, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("ReSync Legacy Archive Marker Cannot Be Applied To Current ReSync Data");
        }
        moveOnce(data, dataBackup);
        moveOnce(coordination, coordinationBackup);
        if (!Files.exists(dataBackup, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(dataBackup);
        } else if (Files.isSymbolicLink(dataBackup) || !Files.isDirectory(dataBackup, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("ReSync Legacy Data Archive Is Invalid: " + dataBackup);
        }
        String report = "format=resync-legacy-archive-v1\n"
            + "archived-at=" + Instant.now() + "\n"
            + "data-backup=" + dataBackup + "\n"
            + "coordination-backup=" + coordinationBackup + "\n"
            + "reason=Pre-rewrite migration is unsupported\n";
        AtomicFiles.write(dataBackup.resolve("ReSync Legacy Archive.txt"), report.getBytes(StandardCharsets.UTF_8));
        Files.delete(marker);
        writeStatus(pluginRoot, ReSyncInstallationStatus.legacyDataArchived(dataBackup.getFileName().toString()));
        return new Result(true, dataBackup, Files.exists(coordinationBackup) ? coordinationBackup : null);
    }

    private static void writeStatus(Path pluginRoot, ReSyncInstallationStatus status) throws IOException {
        AtomicFiles.write(pluginRoot.resolve(STATUS_FILE), (status.encode() + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static InstallState inspect(Path data, Path coordination) throws IOException {
        boolean occupied = nonEmpty(data);
        if (!Files.exists(coordination, LinkOption.NOFOLLOW_LINKS)) {
            return new InstallState(false, occupied);
        }
        if (Files.isSymbolicLink(coordination) || !Files.isDirectory(coordination, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("ReSync Coordination Root Is Not A Regular Directory");
        }
        Path restoreControl = coordination.resolve("restore-control");
        if (!Files.exists(restoreControl, LinkOption.NOFOLLOW_LINKS)) {
            return new InstallState(false, occupied);
        }
        AtomicDirectoryRootStore roots = new AtomicDirectoryRootStore(restoreControl);
        Optional<Path> active = roots.activeRoot();
        if (active.isEmpty()) {
            return new InstallState(false, occupied);
        }
        Path activeRoot = active.orElseThrow();
        boolean activeOccupied = nonEmpty(activeRoot);
        boolean current = ReSyncDataFixer.installedVersion(activeRoot).isPresent()
            || FreshRootProvenance.recordsCurrentInstallation(coordination, data);
        return new InstallState(current, occupied || activeOccupied);
    }

    private static boolean nonEmpty(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("ReSync Data Root Is Not A Regular Directory");
        }
        try (var entries = Files.list(root)) {
            return entries.findAny().isPresent();
        }
    }

    private static String token(Path marker) throws IOException {
        if (Files.isSymbolicLink(marker) || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("ReSync Legacy Archive Marker Must Be A Regular File");
        }
        if (Files.size(marker) > MAX_MARKER_BYTES) {
            throw new MigrationException("ReSync Legacy Archive Marker Is Too Large");
        }
        String value = Files.readString(marker, StandardCharsets.UTF_8).strip();
        if (value.isEmpty()) {
            value = UUID.randomUUID().toString();
            AtomicFiles.write(marker, (value + "\n").getBytes(StandardCharsets.UTF_8));
        }
        try {
            UUID.fromString(value);
            return value;
        } catch (IllegalArgumentException exception) {
            throw new MigrationException("ReSync Legacy Archive Marker Is Invalid", exception);
        }
    }

    private static void moveOnce(Path source, Path target) throws IOException {
        boolean sourceExists = Files.exists(source, LinkOption.NOFOLLOW_LINKS);
        boolean targetExists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (!sourceExists) {
            return;
        }
        if (targetExists) {
            throw new MigrationException("ReSync Legacy Archive Target Already Exists: " + target);
        }
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    public record Result(boolean archived, Path dataBackup, Path coordinationBackup) {
        private static Result current() {
            return new Result(false, null, null);
        }
    }

    private record InstallState(boolean current, boolean occupied) {
    }
}
