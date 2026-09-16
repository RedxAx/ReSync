package restudio.resync.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

public final class AuthorityEpochStore {
    public static final String FILE_NAME = "authority-epoch";
    private static final String FORMAT = "format=1";
    private final Path file;
    private long epoch;
    private Path boundRoot;

    public AuthorityEpochStore(Path coordinationRoot) throws IOException {
        Path root = MigrationPaths.requireDirectory(coordinationRoot, "coordinationRoot");
        file = root.resolve(FILE_NAME).toAbsolutePath().normalize();
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            read();
        }
    }

    public synchronized long bind(Path activeRoot) throws IOException {
        Path root = MigrationPaths.requireDirectory(activeRoot, "activeRoot");
        long candidateEpoch = epoch;
        Path candidateRoot = boundRoot;
        if (epoch == 0L) {
            candidateEpoch = 1L;
            candidateRoot = root;
        } else if (boundRoot == null) {
            throw new MigrationException("Authority Epoch Has No Bound Root");
        } else if (!boundRoot.equals(root)) {
            candidateEpoch = Math.addExact(epoch, 1L);
            candidateRoot = root;
        }

        if (candidateEpoch != epoch || !Objects.equals(candidateRoot, boundRoot)) {
            String candidateDocument = document(candidateEpoch, candidateRoot);
            write(candidateDocument);
            epoch = candidateEpoch;
            boundRoot = candidateRoot;
        }
        return epoch;
    }

    public synchronized long current() {
        if (epoch < 1L || boundRoot == null) {
            throw new IllegalStateException("Authority Epoch Is Not Bound To An Active Root");
        }
        return epoch;
    }

    public synchronized Path boundRoot() {
        return boundRoot;
    }

    public Path file() {
        return file;
    }

    private void read() throws IOException {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Authority Epoch File Is Not A Regular File");
        }
        String content = Files.readString(file, StandardCharsets.UTF_8);
        String[] lines = content.strip().split("\\R", -1);
        if (lines.length != 4 || !FORMAT.equals(lines[0]) || !lines[1].startsWith("epoch=")
            || !lines[2].startsWith("root=") || !lines[3].startsWith("hash=")) {
            throw new MigrationException("Authority Epoch File Is Invalid");
        }
        long parsedEpoch;
        try {
            parsedEpoch = Long.parseLong(lines[1].substring("epoch=".length()));
        } catch (NumberFormatException exception) {
            throw new MigrationException("Authority Epoch Is Invalid", exception);
        }
        if (parsedEpoch < 1L) {
            throw new MigrationException("Authority Epoch Must Be Positive");
        }
        String encodedRoot = lines[2].substring("root=".length());
        String decodedRoot = MigrationCanonical.decode(encodedRoot);
        Path parsedRoot;
        try {
            MigrationCanonical.requireText(decodedRoot, "boundRoot");
            parsedRoot = MigrationPaths.requirePath(Path.of(decodedRoot), "boundRoot");
        } catch (IllegalArgumentException exception) {
            throw new MigrationException("Authority Epoch Bound Root Is Invalid", exception);
        }
        if (!MigrationCanonical.encode(parsedRoot.toAbsolutePath().normalize().toString()).equals(encodedRoot)) {
            throw new MigrationException("Authority Epoch Bound Root Is Not Canonical");
        }
        String canonical = canonical(parsedEpoch, parsedRoot);
        String expectedHash = MigrationCanonical.requireDigest(lines[3].substring("hash=".length()), "authorityEpochHash");
        if (!expectedHash.equals(MigrationCanonical.sha256(canonical))) {
            throw new MigrationException("Authority Epoch Hash Is Invalid");
        }
        epoch = parsedEpoch;
        boundRoot = parsedRoot;
    }

    private void write(String document) throws IOException {
        AtomicFiles.write(file, document.getBytes(StandardCharsets.UTF_8));
    }

    private static String document(long epoch, Path root) {
        Objects.requireNonNull(root, "root");
        String canonical = canonical(epoch, root);
        return canonical + "hash=" + MigrationCanonical.sha256(canonical) + "\n";
    }

    private static String canonical(long epoch, Path root) {
        return FORMAT + "\nepoch=" + epoch + "\nroot=" + MigrationCanonical.encode(root.toAbsolutePath().normalize().toString()) + "\n";
    }
}
