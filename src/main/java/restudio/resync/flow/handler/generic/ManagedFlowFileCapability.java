package restudio.resync.flow.handler.generic;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import restudio.resync.migration.ManagedFlowFileStoreContract;

public interface ManagedFlowFileCapability extends AutoCloseable {
    String ROOT_DIRECTORY = ManagedFlowFileStoreContract.ROOT_DIRECTORY;
    String DATABASE_FILE = ManagedFlowFileStoreContract.DATABASE_FILE;

    Path root();

    Path databaseFile();

    Path persistenceRoot();

    List<Path> persistenceFiles();

    boolean available();

    String failureReason();

    IOException postCommitCleanupFailure();

    String normalize(String path) throws AccessException;

    String write(String path, String content) throws IOException;

    String append(String path, String content) throws IOException;

    String read(String path) throws IOException;

    List<String> readLines(String path) throws IOException;

    boolean delete(String path) throws IOException;

    boolean exists(String path) throws IOException;

    String copy(String sourcePath, String destinationPath) throws IOException;

    String move(String sourcePath, String destinationPath) throws IOException;

    List<String> list(String path) throws IOException;

    String createDirectory(String path) throws IOException;

    long size(String path) throws IOException;

    void quiesce() throws IOException;

    void resume() throws IOException;

    void rebind(Path persistenceRoot) throws IOException;

    void healthCheck() throws IOException;

    @Override
    void close() throws IOException;

    static ManagedFlowFileCapability unavailable(Path trustedDataRoot, Throwable cause) {
        return new Unavailable(trustedDataRoot, cause);
    }

    final class AccessException extends IOException {
        private final String code;

        public AccessException(String code, String message) {
            super(message);
            this.code = code;
        }

        public AccessException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    final class Unavailable implements ManagedFlowFileCapability {
        private final Path persistenceRoot;
        private final Path databaseFile;
        private final String message;

        private Unavailable(Path trustedDataRoot, Throwable cause) {
            Path dataRoot = trustedDataRoot.toAbsolutePath().normalize();
            this.persistenceRoot = dataRoot.resolve(ROOT_DIRECTORY).normalize();
            this.databaseFile = persistenceRoot.resolve(DATABASE_FILE).normalize();
            this.message = "Managed flow-file storage is unavailable";
        }

        @Override
        public Path root() {
            return persistenceRoot;
        }

        @Override
        public Path databaseFile() {
            return databaseFile;
        }

        @Override
        public Path persistenceRoot() {
            return persistenceRoot;
        }

        @Override
        public List<Path> persistenceFiles() {
            return List.of(databaseFile, databaseFile.resolveSibling(DATABASE_FILE + "-wal"),
                databaseFile.resolveSibling(DATABASE_FILE + "-shm"), databaseFile.resolveSibling(DATABASE_FILE + "-journal"));
        }

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public String failureReason() {
            return message;
        }

        @Override
        public IOException postCommitCleanupFailure() {
            return null;
        }

        @Override
        public String normalize(String path) throws AccessException {
            throw unavailable();
        }

        @Override
        public String write(String path, String content) throws IOException {
            throw unavailable();
        }

        @Override
        public String append(String path, String content) throws IOException {
            throw unavailable();
        }

        @Override
        public String read(String path) throws IOException {
            throw unavailable();
        }

        @Override
        public List<String> readLines(String path) throws IOException {
            throw unavailable();
        }

        @Override
        public boolean delete(String path) throws IOException {
            throw unavailable();
        }

        @Override
        public boolean exists(String path) throws IOException {
            throw unavailable();
        }

        @Override
        public String copy(String sourcePath, String destinationPath) throws IOException {
            throw unavailable();
        }

        @Override
        public String move(String sourcePath, String destinationPath) throws IOException {
            throw unavailable();
        }

        @Override
        public List<String> list(String path) throws IOException {
            throw unavailable();
        }

        @Override
        public String createDirectory(String path) throws IOException {
            throw unavailable();
        }

        @Override
        public long size(String path) throws IOException {
            throw unavailable();
        }

        @Override
        public void quiesce() throws IOException {
            throw unavailable();
        }

        @Override
        public void resume() throws IOException {
            throw unavailable();
        }

        @Override
        public void rebind(Path persistenceRoot) throws IOException {
            throw unavailable();
        }

        @Override
        public void healthCheck() throws IOException {
            throw unavailable();
        }

        @Override
        public void close() {
        }

        private AccessException unavailable() {
            return new AccessException("FILE_CAPABILITY_UNAVAILABLE", message);
        }
    }
}
