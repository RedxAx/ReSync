package restudio.resync.resources;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

public final class JsonAssetInventory {
    private static final int MAX_DEPTH = 64;
    private static final int MAX_ENTRIES = 100_000;
    private static final Set<String> INTERNAL_ROOTS = Set.of(".transactions", ".snapshots", ".asset-coordinator",
        ".mutation-intents", ".quarantine", ".durability", ".tombstones", ".migrations", "migration-backups");
    private static final Set<String> COORDINATOR_ROOTS = Set.of(".transactions", ".snapshots", ".asset-coordinator",
        ".mutation-intents", ".quarantine", ".durability", ".migrations", "migration-backups");

    record Entry(Path path, String fileName, String type, String id, boolean internal) {
    }

    private final Path root;
    private final Map<String, List<Entry>> filesByType;
    private final Map<Path, List<Entry>> jsonFilesByAncestor;
    private final Map<Path, List<Entry>> filesByParent;
    private final Map<Path, List<Path>> directoriesByParent;
    private final int visitedPathCount;

    private JsonAssetInventory(Path root, List<Entry> files, List<Path> directories, int visitedPathCount) {
        this.root = root;
        Map<String, List<Entry>> byType = new LinkedHashMap<>();
        Map<Path, List<Entry>> byAncestor = new LinkedHashMap<>();
        Map<Path, List<Entry>> byParent = new LinkedHashMap<>();
        for (Entry entry : files) {
            byParent.computeIfAbsent(entry.path().getParent(), ignored -> new ArrayList<>()).add(entry);
            if (entry.internal() || !entry.fileName().endsWith(".json")) {
                continue;
            }
            byType.computeIfAbsent(entry.type(), ignored -> new ArrayList<>()).add(entry);
            Path ancestor = entry.path().getParent();
            while (ancestor != null && ancestor.startsWith(root)) {
                byAncestor.computeIfAbsent(ancestor, ignored -> new ArrayList<>()).add(entry);
                if (ancestor.equals(root)) {
                    break;
                }
                ancestor = ancestor.getParent();
            }
        }
        this.filesByType = immutableLists(byType);
        this.jsonFilesByAncestor = immutableLists(byAncestor);
        this.filesByParent = immutableLists(byParent);
        Map<Path, List<Path>> directoryChildren = new LinkedHashMap<>();
        for (Path directory : directories) {
            directoryChildren.computeIfAbsent(directory.getParent(), ignored -> new ArrayList<>()).add(directory);
        }
        this.directoriesByParent = immutablePaths(directoryChildren);
        this.visitedPathCount = visitedPathCount;
    }

    public static JsonAssetInventory scan(Path assetsRoot) throws IOException {
        return scan(assetsRoot, ignored -> {
        });
    }

    static JsonAssetInventory scan(Path assetsRoot, Consumer<Path> observer) throws IOException {
        Path root = Objects.requireNonNull(assetsRoot, "assetsRoot").toAbsolutePath().normalize();
        Consumer<Path> requiredObserver = Objects.requireNonNull(observer, "observer");
        BasicFileAttributes rootAttributes = Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (rootAttributes.isSymbolicLink() || !rootAttributes.isDirectory()) {
            throw new IOException("JSON Asset Root Is Not An Existing Directory: " + root);
        }
        List<Entry> files = new ArrayList<>();
        List<Path> directories = new ArrayList<>();
        int[] visited = {0};
        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), MAX_DEPTH, new FileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                observe(directory, attributes);
                Path normalized = directory.toAbsolutePath().normalize();
                Path relative = root.relativize(normalized);
                if (relative.getNameCount() == 1 && COORDINATOR_ROOTS.contains(relative.getName(0).toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!normalized.equals(root)) {
                    directories.add(normalized);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                observe(file, attributes);
                if (attributes.isDirectory()) {
                    throw new IOException("JSON Asset Inventory Exceeded Maximum Depth: " + file);
                }
                if (attributes.isRegularFile()) {
                    files.add(entry(root, file));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
                throw failure;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                return FileVisitResult.CONTINUE;
            }

            private void observe(Path path, BasicFileAttributes attributes) throws IOException {
                Path normalized = path.toAbsolutePath().normalize();
                if (!normalized.startsWith(root)) {
                    throw new IOException("JSON Asset Inventory Escaped Root: " + normalized);
                }
                if (attributes.isSymbolicLink() || Files.isSymbolicLink(normalized)) {
                    throw new IOException("JSON Asset Inventory Rejects Symbolic Links: " + normalized);
                }
                visited[0]++;
                if (visited[0] > MAX_ENTRIES) {
                    throw new IOException("JSON Asset Inventory Exceeded Maximum Entries: " + root);
                }
                requiredObserver.accept(normalized);
            }
        });
        return new JsonAssetInventory(root, files, directories, visited[0]);
    }

    Path root() {
        return root;
    }

    List<Entry> filesForType(String type) {
        return filesByType.getOrDefault(type, List.of());
    }

    List<Entry> jsonFilesUnder(Path folder) {
        return jsonFilesByAncestor.getOrDefault(folder.toAbsolutePath().normalize(), List.of());
    }

    List<Entry> filesDirectlyUnder(Path folder) {
        return filesByParent.getOrDefault(folder.toAbsolutePath().normalize(), List.of());
    }

    List<Path> directoriesDirectlyUnder(Path folder) {
        return directoriesByParent.getOrDefault(folder.toAbsolutePath().normalize(), List.of());
    }

    int visitedPathCount() {
        return visitedPathCount;
    }

    private static Entry entry(Path root, Path file) throws IOException {
        Path normalized = file.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized) || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("JSON Asset Inventory File Changed During Traversal: " + normalized);
        }
        Path relative = root.relativize(normalized);
        String fileName = normalized.getFileName() != null ? normalized.getFileName().toString() : "";
        boolean internal = relative.getNameCount() > 0 && INTERNAL_ROOTS.contains(relative.getName(0).toString());
        if (internal || !fileName.endsWith(".json")) {
            return new Entry(normalized, fileName, "", "", internal);
        }
        String declaredType = AssetFileFormat.readResourceType(normalized);
        int separator = fileName.indexOf("__");
        String type;
        String id;
        if (!declaredType.isBlank()) {
            type = declaredType;
            id = fileName.substring(0, fileName.length() - 5);
        } else if (separator > 0) {
            type = fileName.substring(0, separator);
            id = fileName.substring(separator + 2, fileName.length() - 5);
        } else {
            type = "";
            id = AssetFileFormat.idFromIdOnlyFileName(fileName);
        }
        if ("chat_channel".equals(type)) {
            type = "chat";
        }
        return new Entry(normalized, fileName, type, id, false);
    }

    private static <K> Map<K, List<Entry>> immutableLists(Map<K, List<Entry>> source) {
        Map<K, List<Entry>> immutable = new LinkedHashMap<>();
        source.forEach((key, value) -> immutable.put(key, List.copyOf(value)));
        return Map.copyOf(immutable);
    }

    private static <K> Map<K, List<Path>> immutablePaths(Map<K, List<Path>> source) {
        Map<K, List<Path>> immutable = new LinkedHashMap<>();
        source.forEach((key, value) -> immutable.put(key, List.copyOf(value)));
        return Map.copyOf(immutable);
    }
}
