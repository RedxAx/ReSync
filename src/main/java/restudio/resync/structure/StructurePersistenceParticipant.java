package restudio.resync.structure;

import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.PersistenceOwnershipContext;
import restudio.resync.migration.PersistenceOwnershipIndex;
import restudio.resync.migration.PersistenceOwnershipProvider;
import restudio.resync.migration.RebindablePersistenceParticipant;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

public final class StructurePersistenceParticipant
    implements RebindablePersistenceParticipant, PersistenceOwnershipProvider {
    public static final String OWNER = "resync.structures";
    private final Path scopeRoot;
    private final StructureLibrary library;

    public StructurePersistenceParticipant(Path scopeRoot, StructureLibrary library) {
        this.scopeRoot = MigrationPaths.requirePath(scopeRoot, "scopeRoot");
        this.library = Objects.requireNonNull(library, "library");
        Path expected = this.scopeRoot.resolve(StructureLibrary.ROOT_DIRECTORY).toAbsolutePath().normalize();
        if (!expected.equals(library.getStructuresDir())) {
            throw new IllegalArgumentException("Structure Participant Root Does Not Match Library Root");
        }
    }

    @Override
    public String owner() {
        return OWNER;
    }

    @Override
    public Path root() {
        return library.getStructuresDir();
    }

    @Override
    public Path rebindScope() {
        return scopeRoot;
    }

    @Override
    public PersistenceOwnershipIndex ownershipIndex(PersistenceOwnershipContext context) {
        return PersistenceOwnershipIndex.builder(context).subtreeRoot().build();
    }

    @Override
    public void flush() throws IOException {
        library.flushPersistence();
    }

    @Override
    public void quiesce() throws IOException {
        library.quiescePersistence();
    }

    @Override
    public void resume() throws IOException {
        library.resumePersistence();
    }

    @Override
    public void rebind(Path activeRoot) throws IOException {
        Path scope = MigrationPaths.requireDirectory(activeRoot, "activeRoot");
        Path candidate = scope.resolve(StructureLibrary.ROOT_DIRECTORY).toAbsolutePath().normalize();
        if (!candidate.startsWith(scope) || candidate.equals(scope)) {
            throw new IOException("Structure Rebind Escaped Active Root");
        }
        library.rebindPersistence(candidate);
    }

    @Override
    public void healthCheck() throws IOException {
        library.healthCheckPersistence();
    }
}
