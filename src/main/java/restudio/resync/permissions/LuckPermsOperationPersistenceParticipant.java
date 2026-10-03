package restudio.resync.permissions;

import com.google.gson.JsonParser;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.AtomicFiles;
import restudio.resync.migration.PersistenceOwnershipContext;
import restudio.resync.migration.PersistenceOwnershipIndex;
import restudio.resync.migration.PersistenceOwnershipProvider;
import restudio.resync.migration.RebindablePersistenceParticipant;
import restudio.resync.storage.StorageSafety;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

public final class LuckPermsOperationPersistenceParticipant
    implements RebindablePersistenceParticipant, PersistenceOwnershipProvider {
    public static final String OWNER = "resync.runtime.luckperms-operations";
    public static final String DIRECTORY = "runtime";
    public static final String FILE_NAME = "luckperms-operations.json";
    private final Path scopeRoot;
    private final LuckPermsManagementService service;

    public static void prepareFreshJournal(Path scopeRoot) throws IOException {
        Path file = persistenceFile(MigrationPaths.requireDirectory(scopeRoot, "scopeRoot"));
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
            AtomicFiles.writeNew(file, "[]\n".getBytes(StandardCharsets.UTF_8));
        } else if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Fresh LuckPerms Operation Journal Must Be A Regular File: " + file);
        }
    }

    public static RebindablePersistenceParticipant dormant(Path scopeRoot) throws IOException {
        return new DormantJournal(scopeRoot);
    }

    private static final class DormantJournal implements RebindablePersistenceParticipant, PersistenceOwnershipProvider {
        private final Path scopeRoot;
        private Path file;
        private boolean quiesced;

        private DormantJournal(Path root) throws IOException {
            scopeRoot = MigrationPaths.requireDirectory(root, "scopeRoot");
            file = persistenceFile(scopeRoot);
            healthCheck();
        }

        @Override
        public String owner() {
            return OWNER;
        }

        @Override
        public synchronized Path root() {
            return file;
        }

        @Override
        public Path rebindScope() {
            return scopeRoot;
        }

        @Override
        public synchronized PersistenceOwnershipIndex ownershipIndex(PersistenceOwnershipContext context) {
            return PersistenceOwnershipIndex.builder(context).exactRoot().build();
        }

        @Override
        public synchronized void flush() throws IOException {
            healthCheck();
            AtomicFiles.force(file);
        }

        @Override
        public synchronized void quiesce() throws IOException {
            flush();
            quiesced = true;
        }

        @Override
        public synchronized void resume() throws IOException {
            healthCheck();
            quiesced = false;
        }

        @Override
        public synchronized void rebind(Path activeRoot) throws IOException {
            if (!quiesced) throw new IOException("Dormant LuckPerms Journal Must Be Quiesced Before Rebind");
            Path candidate = persistenceFile(MigrationPaths.requireDirectory(activeRoot, "activeRoot"));
            validate(candidate);
            file = candidate;
        }

        @Override
        public synchronized void healthCheck() throws IOException {
            validate(file);
        }

        private static void validate(Path candidate) throws IOException {
            Path journal = MigrationPaths.requirePath(candidate, "LuckPerms operation journal");
            if (!Files.isRegularFile(journal, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Dormant LuckPerms Operation Journal Is Missing Or Invalid: " + journal);
            }
            try {
                if (!JsonParser.parseString(StorageSafety.readUtf8(journal)).isJsonArray()) {
                    throw new IOException("Dormant LuckPerms Operation Journal Must Be An Array: " + journal);
                }
            } catch (RuntimeException failure) {
                throw new IOException("Dormant LuckPerms Operation Journal Is Invalid: " + journal, failure);
            }
        }
    }

    public LuckPermsOperationPersistenceParticipant(Path scopeRoot, LuckPermsManagementService service) {
        this.scopeRoot = MigrationPaths.requirePath(scopeRoot, "scopeRoot");
        this.service = Objects.requireNonNull(service, "service");
        Path expected = persistenceFile(this.scopeRoot);
        Path actual = MigrationPaths.requirePath(service.persistenceRoot(), "LuckPerms operation journal");
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("LuckPerms operation participant root does not match service journal");
        }
    }

    @Override
    public String owner() {
        return OWNER;
    }

    @Override
    public Path root() {
        return MigrationPaths.requirePath(service.persistenceRoot(), "LuckPerms operation journal");
    }

    @Override
    public PersistenceOwnershipIndex ownershipIndex(PersistenceOwnershipContext context) {
        return PersistenceOwnershipIndex.builder(context).exactRoot().build();
    }

    @Override
    public boolean owns(Path file) {
        return root().equals(MigrationPaths.requirePath(file, "file"));
    }

    @Override
    public Path rebindScope() {
        return scopeRoot;
    }

    @Override
    public void flush() throws IOException {
        service.flushPersistence();
    }

    @Override
    public void quiesce() throws IOException {
        service.quiescePersistence();
    }

    @Override
    public void resume() throws IOException {
        service.resumePersistence();
    }

    @Override
    public void rebind(Path activeRoot) throws IOException {
        Path scope = MigrationPaths.requireDirectory(activeRoot, "activeRoot");
        service.rebindPersistence(persistenceFile(scope));
    }

    @Override
    public void healthCheck() throws IOException {
        service.healthCheckPersistence();
    }

    private static Path persistenceFile(Path scopeRoot) {
        return MigrationPaths.resolveInside(scopeRoot, DIRECTORY + "/" + FILE_NAME);
    }
}
