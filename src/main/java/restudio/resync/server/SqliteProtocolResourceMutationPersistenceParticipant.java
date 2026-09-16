package restudio.resync.server;

import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.PersistenceOwnershipContext;
import restudio.resync.migration.PersistenceOwnershipIndex;
import restudio.resync.migration.PersistenceOwnershipProvider;
import restudio.resync.migration.ProductionPersistenceOwners;
import restudio.resync.migration.RebindablePersistenceParticipant;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class SqliteProtocolResourceMutationPersistenceParticipant implements RebindablePersistenceParticipant,
    PersistenceOwnershipProvider {
    public static final String OWNER = "resync.runtime.resource-mutations";
    private static final String RUNTIME_DIRECTORY = "runtime";
    private static final String DATABASE_NAME = "resource-mutations.db";
    private static final String WAL_SUFFIX = "-wal";
    private static final String SHM_SUFFIX = "-shm";
    private final Path scopeRoot;
    private final SqliteProtocolResourceMutationAuthority authority;

    public SqliteProtocolResourceMutationPersistenceParticipant(Path scopeRoot,
                                                                 SqliteProtocolResourceMutationAuthority authority) {
        this.scopeRoot = MigrationPaths.requirePath(scopeRoot, "scopeRoot");
        this.authority = Objects.requireNonNull(authority, "authority");
        Path expected = this.scopeRoot.resolve(RUNTIME_DIRECTORY).resolve(DATABASE_NAME).toAbsolutePath().normalize();
        if (!expected.equals(authority.databasePath())) {
            throw new IllegalArgumentException("Resource mutation authority is not bound to the runtime database");
        }
    }

    @Override
    public String owner() {
        return OWNER;
    }

    @Override
    public Path root() {
        return authority.databasePath();
    }

    @Override
    public Set<String> resumeDependencies() {
        return Set.of(ProductionPersistenceOwners.FLOW_ASSETS);
    }

    @Override
    public boolean owns(Path file) {
        Path database = root();
        Path candidate = MigrationPaths.requirePath(file, "file");
        return candidate.equals(database)
            || candidate.equals(sidecar(database, WAL_SUFFIX))
            || candidate.equals(sidecar(database, SHM_SUFFIX));
    }

    @Override
    public PersistenceOwnershipIndex ownershipIndex(PersistenceOwnershipContext context) {
        Path database = context.participantRoot();
        return PersistenceOwnershipIndex.builder()
            .exact(context.participantRootRelative())
            .exact(context.relativeToSource(sidecar(database, WAL_SUFFIX)))
            .exact(context.relativeToSource(sidecar(database, SHM_SUFFIX)))
            .build();
    }

    @Override
    public Path rebindScope() {
        return scopeRoot;
    }

    @Override
    public void flush() throws IOException {
        authority.flushPersistence();
    }

    @Override
    public void quiesce() throws IOException {
        authority.quiescePersistence();
    }

    @Override
    public void resume() throws IOException {
        long started = TemporaryLifecycleDiagnostics.start();
        try {
            authority.resumePersistence();
        } catch (IOException | RuntimeException exception) {
            TemporaryLifecycleDiagnostics.terminal("resource_mutation_persistence_resume", started,
                TemporaryLifecycleDiagnostics.identity(null, OWNER, null, null, null, null, null, null),
                "failed", "RESOURCE_MUTATION_PERSISTENCE_RESUME_FAILED", diagnosticReason(exception));
            throw exception;
        }
    }

    @Override
    public void rebind(Path activeRoot) throws IOException {
        authority.rebindPersistence(activeRoot);
    }

    @Override
    public void healthCheck() throws IOException {
        authority.healthCheckPersistence();
    }

    private static Path sidecar(Path database, String suffix) {
        return database.resolveSibling(database.getFileName() + suffix);
    }

    private static String diagnosticReason(Throwable failure) {
        Throwable detail = failure;
        int depth = 0;
        while (detail.getCause() != null && detail.getCause() != detail && depth++ < 8) {
            detail = detail.getCause();
        }
        String message = detail.getMessage();
        if (message == null || message.isBlank()) {
            return detail.getClass().getSimpleName();
        }
        String normalized = message.strip();
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.contains("payload") || lower.contains("authorization") || lower.contains("token")
            || lower.contains("secret") || lower.contains("password") || normalized.contains("\\")
            || normalized.contains("/")) {
            return detail.getClass().getSimpleName();
        }
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200);
    }
}
