package restudio.resync.restore;

import restudio.resync.migration.AtomicFiles;
import restudio.resync.migration.MigrationException;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.MigrationPlan;
import restudio.resync.migration.QuarantineReport;
import restudio.resync.migration.StagedMigration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class RestoreActivationRecovery {
    enum Phase {
        PREPARING,
        READY,
        ACTIVATED,
        ROLLED_BACK,
        BOOTSTRAP_COMPLETE
    }

    record Intent(Phase phase, Path evidenceRoot, StagedMigration staged) {
        Intent {
            phase = Objects.requireNonNull(phase, "phase");
            evidenceRoot = MigrationPaths.requirePath(evidenceRoot, "evidenceRoot");
            staged = Objects.requireNonNull(staged, "staged");
            if (phase == Phase.PREPARING && !staged.contentHash().equals(zeroHash())) {
                throw new IllegalArgumentException("Preparing Restore Activation Intent Cannot Declare Content");
            }
            if (phase != Phase.PREPARING && staged.contentHash().equals(zeroHash())) {
                throw new IllegalArgumentException("Prepared Restore Activation Intent Must Declare Content");
            }
            if (phase == Phase.BOOTSTRAP_COMPLETE && (staged.previousRoot().isPresent()
                || evidenceRoot.equals(staged.root())
                || !bootstrapPlan(staged.contentHash()).planHash().equals(staged.planHash()))) {
                throw new IllegalArgumentException("Completed Bootstrap Intent Must Preserve Its Original Copy Proof");
            }
        }
    }

    private static final String FORMAT = "format=2";
    private final Path path;

    RestoreActivationRecovery(Path path) {
        this.path = MigrationPaths.requirePath(path, "activationIntentPath");
    }

    synchronized Optional<Intent> read() throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Restore Activation Intent Is Not A Regular File");
        }
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.size() != 9 || !FORMAT.equals(lines.getFirst())) {
            throw new MigrationException("Restore Activation Intent Is Invalid");
        }
        String canonical = String.join("\n", lines.subList(0, 8)) + '\n';
        String expectedHash = value(lines.get(8), "hash=");
        if (!expectedHash.equals(sha256(canonical))) {
            throw new MigrationException("Restore Activation Intent Hash Is Invalid");
        }
        Phase phase;
        try {
            phase = Phase.valueOf(value(lines.get(1), "phase="));
        } catch (IllegalArgumentException exception) {
            throw new MigrationException("Restore Activation Intent Phase Is Invalid", exception);
        }
        Path evidence = decodePath(value(lines.get(2), "evidence="));
        Path target = decodePath(value(lines.get(3), "target="));
        String previousValue = value(lines.get(4), "previous=");
        Optional<Path> previous = previousValue.isEmpty() ? Optional.empty() : Optional.of(decodePath(previousValue));
        String planHash = digest(value(lines.get(5), "plan-hash="), "planHash");
        String contentHash = digest(value(lines.get(6), "content-hash="), "contentHash");
        if (!value(lines.get(7), "identity=").equals(identity(phase, evidence, target, previous, planHash, contentHash))) {
            throw new MigrationException("Restore Activation Intent Identity Is Invalid");
        }
        return Optional.of(new Intent(phase, evidence, new StagedMigration(target, previous, planHash, contentHash)));
    }

    synchronized void preparing(Path evidenceRoot, Path targetRoot, Optional<Path> previousRoot, String planHash) throws IOException {
        write(new Intent(Phase.PREPARING, evidenceRoot,
            new StagedMigration(targetRoot, previousRoot, planHash, zeroHash())));
    }

    synchronized void ready(Path evidenceRoot, StagedMigration staged) throws IOException {
        write(new Intent(Phase.READY, evidenceRoot, staged));
    }

    synchronized void activated(Path evidenceRoot, StagedMigration staged) throws IOException {
        write(new Intent(Phase.ACTIVATED, evidenceRoot, staged));
    }

    synchronized void rolledBack(Path evidenceRoot, StagedMigration staged) throws IOException {
        write(new Intent(Phase.ROLLED_BACK, evidenceRoot, staged));
    }

    synchronized void bootstrapComplete(Path evidenceRoot, StagedMigration staged) throws IOException {
        Intent current = read().orElseThrow(() -> new MigrationException("Bootstrap Activation Intent Is Missing"));
        if (current.phase() != Phase.ACTIVATED || !current.evidenceRoot().equals(evidenceRoot)
            || !current.staged().equals(staged)) {
            throw new MigrationException("Bootstrap Completion Does Not Match The Activated Copy");
        }
        write(new Intent(Phase.BOOTSTRAP_COMPLETE, evidenceRoot, staged));
    }

    synchronized boolean pending() throws IOException {
        return read().filter(intent -> intent.phase() != Phase.BOOTSTRAP_COMPLETE).isPresent();
    }

    synchronized void clear() throws IOException {
        if (read().filter(intent -> intent.phase() == Phase.BOOTSTRAP_COMPLETE).isPresent()) {
            throw new MigrationException("Completed Bootstrap Copy Proof Must Be Retained");
        }
        Files.deleteIfExists(path);
    }

    static MigrationPlan bootstrapPlan(String sourceHash) {
        return new MigrationPlan("restore-bootstrap", sourceHash, 1, 1, QuarantineReport.empty().reportHash(), List.of());
    }

    private void write(Intent intent) throws IOException {
        StagedMigration staged = intent.staged();
        String identity = identity(intent.phase(), intent.evidenceRoot(), staged.root(), staged.previousRoot(), staged.planHash(), staged.contentHash());
        String canonical = FORMAT + '\n'
            + "phase=" + intent.phase().name() + '\n'
            + "evidence=" + encodePath(intent.evidenceRoot()) + '\n'
            + "target=" + encodePath(staged.root()) + '\n'
            + "previous=" + staged.previousRoot().map(RestoreActivationRecovery::encodePath).orElse("") + '\n'
            + "plan-hash=" + staged.planHash() + '\n'
            + "content-hash=" + staged.contentHash() + '\n'
            + "identity=" + identity + '\n';
        AtomicFiles.write(path, (canonical + "hash=" + sha256(canonical) + '\n').getBytes(StandardCharsets.UTF_8));
    }

    private static String identity(Phase phase, Path evidence, Path target, Optional<Path> previous, String planHash, String contentHash) {
        return sha256(phase.name() + '\n' + evidence + '\n' + target + '\n' + previous.map(Path::toString).orElse("") + '\n'
            + planHash + '\n' + contentHash);
    }

    private static String encodePath(Path path) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(path.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Path decodePath(String value) throws MigrationException {
        try {
            return MigrationPaths.requirePath(Path.of(new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)), "intentPath");
        } catch (IllegalArgumentException exception) {
            throw new MigrationException("Restore Activation Intent Path Is Invalid", exception);
        }
    }

    private static String value(String line, String prefix) throws MigrationException {
        if (!line.startsWith(prefix)) {
            throw new MigrationException("Restore Activation Intent Field Is Missing: " + prefix);
        }
        return line.substring(prefix.length());
    }

    private static String digest(String value, String field) throws MigrationException {
        if (!value.matches("[0-9a-f]{64}")) {
            throw new MigrationException("Restore Activation Intent " + field + " Is Invalid");
        }
        return value;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 Is Unavailable", exception);
        }
    }

    private static String zeroHash() {
        return "0".repeat(64);
    }
}
