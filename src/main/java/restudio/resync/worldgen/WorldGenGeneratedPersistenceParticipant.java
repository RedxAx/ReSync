package restudio.resync.worldgen;

import restudio.resync.migration.CloseablePersistenceParticipant;
import restudio.resync.migration.MigrationFence;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.PersistenceParticipantClassification;
import restudio.resync.migration.ResolvedPersistenceOwnership;
import restudio.resync.server.TemporaryLifecycleDiagnostics;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class WorldGenGeneratedPersistenceParticipant implements CloseablePersistenceParticipant, ResolvedPersistenceOwnership {
    public static final String OWNER = WorldGenGeneratedOutputPolicy.OWNER;
    public static final String ROOT_DIRECTORY = WorldGenGeneratedOutputPolicy.RELATIVE_ROOT;

    private final Path scopeRoot;
    private final WorldGenGeneratedOutputController controller;

    public WorldGenGeneratedPersistenceParticipant(Path scopeRoot,
                                                   WorldGenGeneratedOutputController controller) throws IOException {
        this.scopeRoot = MigrationPaths.requirePath(scopeRoot, "scopeRoot");
        this.controller = Objects.requireNonNull(controller, "controller");
        Path expected = WorldGenGeneratedOutputPolicy.root(this.scopeRoot);
        if (!expected.equals(MigrationPaths.requirePath(controller.generatedRoot(), "generatedRoot"))) {
            throw new IllegalArgumentException("WorldGen Generated Participant Root Does Not Match Controller Root");
        }
    }

    public WorldGenGeneratedPersistenceParticipant(Path scopeRoot,
                                                   WorldGenGeneratedOutputController.Rebuilder rebuilder) throws IOException {
        this(scopeRoot, new WorldGenGeneratedOutputController(scopeRoot, rebuilder));
    }

    public WorldGenGeneratedPersistenceParticipant(Path scopeRoot,
                                                   MigrationFence fence,
                                                   Duration drainTimeout,
                                                   WorldGenGeneratedOutputController.Rebuilder rebuilder) throws IOException {
        this(scopeRoot, new WorldGenGeneratedOutputController(scopeRoot, fence, drainTimeout, rebuilder));
    }

    @Override
    public String owner() {
        return OWNER;
    }

    @Override
    public PersistenceParticipantClassification classification() {
        return PersistenceParticipantClassification.DERIVED_CACHE;
    }

    @Override
    public Path root() {
        return controller.generatedRoot();
    }

    @Override
    public Path rebindScope() {
        return scopeRoot;
    }

    @Override
    public boolean owns(Path file) {
        Path generated = MigrationPaths.requirePath(root(), "generatedRoot");
        Path candidate = MigrationPaths.requirePath(file, "file");
        return ownsResolved(generated, candidate);
    }

    @Override
    public boolean ownsResolved(Path file) {
        return ownsResolved(root().toAbsolutePath().normalize(), file);
    }

    private static boolean ownsResolved(Path generated, Path candidate) {
        if (candidate.startsWith(generated) && !candidate.equals(generated)) {
            return true;
        }
        Path parent = generated.getParent();
        if (parent == null) {
            return false;
        }
        if (candidate.equals(parent.resolve("generated.transaction").toAbsolutePath().normalize())
            || WorldGenGeneratedOutputController.ownsRecoveryArtifactResolved(parent, candidate)) {
            return true;
        }
        return WorldGenGeneratedOutputController.ownsRecoveryQuarantineResolved(
            WorldGenGeneratedOutputController.recoveryQuarantineResolved(parent), candidate);
    }

    @Override
    public void flush() throws IOException {
        run("flush", controller::flush);
    }

    @Override
    public void quiesce() throws IOException {
        run("quiesce", controller::quiesce);
    }

    @Override
    public void resume() throws IOException {
        run("resume", controller::resume);
    }

    @Override
    public void rebind(Path activeRoot) throws IOException {
        controller.rebind(activeRoot);
    }

    @Override
    public void healthCheck() throws IOException {
        run("healthCheck", controller::healthCheck);
    }

    @Override
    public void readinessCheck() throws IOException {
        run("readinessCheck", () -> {
            WorldGenGeneratedOutputController.Health health = controller.health();
            if (!health.available()) {
                throw new IOException("WorldGen Generated Output Is Unavailable: " + health.failureReason());
            }
        });
    }

    @Override
    public void close() throws IOException {
        controller.close();
    }

    public WorldGenGeneratedOutputController controller() {
        return controller;
    }

    private void run(String operation, IoAction action) throws IOException {
        long started = TemporaryLifecycleDiagnostics.start();
        String outcome = "complete";
        String reason = "";
        try {
            action.run();
        } catch (IOException | RuntimeException failure) {
            outcome = "failed";
            reason = diagnosticReason(failure);
            throw failure;
        } catch (Error failure) {
            outcome = "failed";
            reason = diagnosticReason(failure);
            throw failure;
        } finally {
            if (TemporaryLifecycleDiagnostics.enabled()) {
                TemporaryLifecycleDiagnostics.event("worldgen_generated_persistence", started,
                    Map.of("operation", operation, "state", controller.state().name(), "outcome", outcome, "reason", reason));
            }
        }
    }

    static String diagnosticReason(Throwable failure) {
        String reason = failure.getMessage();
        if (reason == null || reason.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        String normalized = reason.strip();
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.contains("payload") || lower.contains("authorization") || lower.contains("token")
            || lower.contains("secret") || lower.contains("password")) {
            return failure.getClass().getSimpleName();
        }
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240);
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }

}
