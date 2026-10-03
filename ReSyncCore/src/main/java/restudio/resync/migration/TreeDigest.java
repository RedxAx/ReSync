package restudio.resync.migration;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

public final class TreeDigest {
    private static final String ASSET_ROOT_COORDINATOR_LOCK = ".asset-coordinator/root.lock";
    private static final String DATA_ROOT_COORDINATOR_LOCK = "assets/.asset-coordinator/root.lock";

    private TreeDigest() {
    }

    public static String of(Path root) throws IOException {
        return digest(root, false, false);
    }

    public static String migratableOf(Path root) throws IOException {
        return digest(root, true, false);
    }

    static String freshInputsOf(Path root) throws IOException {
        return digest(root, false, true);
    }

    private static String digest(Path root, boolean skipRootQuarantine, boolean skipMigrations) throws IOException {
        Path normalizedRoot = MigrationPaths.requireDirectory(root, "root");
        List<String> rows = new ArrayList<>();
        Files.walkFileTree(normalizedRoot, Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(directory)) {
                    throw new MigrationException("Symbolic Link Directory Is Not Allowed: " + directory);
                }
                if (!directory.equals(normalizedRoot)) {
                    String relative = MigrationPaths.relative(normalizedRoot, directory);
                    if (skipMigrations && relative.equals(ReSyncDataFixer.VERSION_DIRECTORY)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (skipRootQuarantine && isRootQuarantinePath(relative)) {
                        requireReservedQuarantine(directory);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!relative.equals("assets/.migrations") && (!relative.equals("assets") || hasMeaningfulAssets(directory))) {
                        rows.add("directory|" + MigrationCanonical.encode(relative));
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(file) || !attributes.isRegularFile()) {
                    throw new MigrationException("Only Regular Files Are Allowed In A Staged Root: " + file);
                }
                String relative = MigrationPaths.relative(normalizedRoot, file);
                if (skipRootQuarantine && isRootQuarantinePath(relative)) {
                    throw new MigrationException("The Reserved Quarantine Path Must Be A Directory: " + relative);
                }
                if (isAuthorityMetadata(relative) || isEphemeralAssetCoordinatorLock(relative)) {
                    return FileVisitResult.CONTINUE;
                }
                rows.add("file|" + MigrationCanonical.encode(relative) + '|' + attributes.size() + '|' + sha256(file));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                throw new MigrationException("Cannot Read Staged Root: " + file, exception);
            }
        });
        rows.sort(Comparator.naturalOrder());
        return MigrationCanonical.sha256(String.join("\n", rows).getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path); var stream = new DigestInputStream(input, digest)) {
                stream.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new MigrationException("SHA-256 Is Unavailable", exception);
        }
    }

    private static boolean hasMeaningfulAssets(Path directory) throws IOException {
        try (var children = Files.list(directory)) {
            return children.anyMatch(child -> !child.getFileName().toString().equals(".migrations"));
        }
    }

    private static boolean isAuthorityMetadata(String relative) {
        return relative.equals(MigrationActivationMarker.MARKER_FILE)
            || relative.equals(ReplacementActivationRecord.RECORD_FILE);
    }

    static boolean isEphemeralAssetCoordinatorLock(String relative) {
        return relative.equals(ASSET_ROOT_COORDINATOR_LOCK) || relative.equals(DATA_ROOT_COORDINATOR_LOCK);
    }

    private static boolean isRootQuarantinePath(String relative) {
        return relative.equals(".quarantine") || relative.startsWith(".quarantine/");
    }

    private static void requireReservedQuarantine(Path quarantine) throws IOException {
        Files.walkFileTree(quarantine, Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(directory)) {
                    throw new MigrationException("Symbolic Link Directory Is Not Allowed: " + directory);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(file) || !attributes.isRegularFile()) {
                    throw new MigrationException("Only Regular Files Are Allowed In Reserved Quarantine: " + file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                throw new MigrationException("Cannot Inspect Reserved Quarantine: " + file, exception);
            }
        });
    }
}
