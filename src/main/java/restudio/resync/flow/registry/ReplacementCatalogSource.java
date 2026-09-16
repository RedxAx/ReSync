package restudio.resync.flow.registry;

import restudio.resync.migration.MigrationException;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class ReplacementCatalogSource {
    public static final String RESOURCE_PATH = "nodes";

    private ReplacementCatalogSource() {
    }

    public static Snapshot load(NodeDefinitionLoader loader, Path localRoot) {
        Objects.requireNonNull(loader, "Replacement catalog loader is required");
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = ReplacementCatalogSource.class.getClassLoader();
        }
        return load(loader, classLoader, localRoot, true);
    }

    public static Snapshot load(NodeDefinitionLoader loader, ClassLoader classLoader, Path localRoot) {
        Objects.requireNonNull(loader, "Replacement catalog loader is required");
        return load(loader, classLoader, localRoot, false);
    }

    public static SourceInventory inventory(ClassLoader classLoader, Path localRoot) {
        ClassLoader selectedClassLoader = classLoader != null ? classLoader : ReplacementCatalogSource.class.getClassLoader();
        return buildInventory(selectedClassLoader, localRoot, true);
    }

    private static Snapshot load(NodeDefinitionLoader loader, ClassLoader classLoader, Path localRoot,
                                 boolean preferCodeSource) {
        SourceInventory sourceInventory = buildInventory(classLoader, localRoot, preferCodeSource);
        List<NodeDefinition> classpathDefinitions = loader.loadReplacementFromSources(
            sourceInventory.replacementClasspathFiles());
        LocalRootValidation validation = sourceInventory.localRoot();
        if (!validation.valid()) {
            loader.recordFailure("CATALOG.LOCAL_ROOT_UNAVAILABLE", validation.root().toString(), validation.reason());
            return snapshot(sourceInventory, classpathDefinitions, List.of(), loader.getDiagnostics());
        }
        List<NodeDefinition> localDefinitions = loader.loadReplacementFromSources(sourceInventory.localFiles());
        return snapshot(sourceInventory, classpathDefinitions, localDefinitions, loader.getDiagnostics());
    }

    public static LocalRootValidation validateLocalRoot(Path localRoot) {
        return inspectLocalRoot(localRoot, false).validation();
    }

    private static SourceInventory buildInventory(ClassLoader classLoader, Path localRoot, boolean preferCodeSource) {
        ClasspathInventory classpath = inspectClasspath(classLoader, preferCodeSource);
        LocalInventory local = inspectLocalRoot(localRoot, true);
        return new SourceInventory(classpath.files(), classpath.replacementFiles(), local.files(), local.validation());
    }

    private static ClasspathInventory inspectClasspath(ClassLoader classLoader, boolean preferCodeSource) {
        Map<String, ClasspathRoot> roots = new LinkedHashMap<>();
        String codeSourceKey = null;
        try {
            var protectionDomain = NodeDefinitionLoader.class.getProtectionDomain();
            var codeSource = protectionDomain != null ? protectionDomain.getCodeSource() : null;
            URL location = codeSource != null ? codeSource.getLocation() : null;
            if (location != null) {
                Path locationPath = Paths.get(location.toURI()).toAbsolutePath().normalize();
                if (Files.isDirectory(locationPath)) {
                    Path root = locationPath.resolve(RESOURCE_PATH).normalize();
                    if (Files.isDirectory(root)) {
                        codeSourceKey = fileRootKey(root);
                        roots.put(codeSourceKey, new ClasspathRoot(codeSourceKey, root, null, false, true));
                    }
                } else if (Files.isRegularFile(locationPath) && locationPath.toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    codeSourceKey = jarRootKey(locationPath, RESOURCE_PATH);
                    roots.put(codeSourceKey, new ClasspathRoot(codeSourceKey, locationPath, RESOURCE_PATH + "/", true, true));
                }
            }
        } catch (URISyntaxException | RuntimeException exception) {
            throw new IllegalStateException("Replacement catalog code source could not be inspected", exception);
        }

        if (classLoader != null) {
            try {
                Enumeration<URL> resources = classLoader.getResources(RESOURCE_PATH);
                while (resources.hasMoreElements()) {
                    addClasspathRoot(roots, resources.nextElement(), codeSourceKey);
                }
            } catch (IOException | RuntimeException exception) {
                throw new IllegalStateException("Replacement catalog classpath could not be inspected", exception);
            }
        }

        List<ClasspathRoot> orderedRoots = roots.values().stream()
            .sorted(Comparator.comparing(ClasspathRoot::key, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ClasspathRoot::key))
            .toList();
        List<NodeDefinitionLoader.SourceFile> files = new ArrayList<>();
        List<NodeDefinitionLoader.SourceFile> replacementFiles = new ArrayList<>();
        for (ClasspathRoot root : orderedRoots) {
            List<NodeDefinitionLoader.SourceFile> rootFiles = scanClasspathRoot(root);
            files.addAll(rootFiles);
            if (!preferCodeSource || codeSourceKey == null || root.codeSource()) {
                replacementFiles.addAll(rootFiles);
            }
        }
        return new ClasspathInventory(files, replacementFiles);
    }

    private static void addClasspathRoot(Map<String, ClasspathRoot> roots, URL resource, String codeSourceKey) {
        if (resource == null) {
            return;
        }
        try {
            URI uri = resource.toURI();
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                Path root = Path.of(uri).toAbsolutePath().normalize();
                if (Files.isDirectory(root)) {
                    String key = fileRootKey(root);
                    roots.putIfAbsent(key, new ClasspathRoot(key, root, null, false, key.equals(codeSourceKey)));
                }
                return;
            }
            if (!"jar".equalsIgnoreCase(uri.getScheme())) {
                return;
            }
            String value = uri.getSchemeSpecificPart();
            int separator = value.indexOf("!/");
            if (separator <= 0) {
                return;
            }
            URI jarUri = URI.create(value.substring(0, separator));
            Path jarPath = Path.of(jarUri).toAbsolutePath().normalize();
            String prefix = value.substring(separator + 2);
            String key = jarRootKey(jarPath, prefix);
            if (!prefix.endsWith("/")) {
                prefix += "/";
            }
            roots.putIfAbsent(key, new ClasspathRoot(key, jarPath, prefix, true, key.equals(codeSourceKey)));
        } catch (URISyntaxException | RuntimeException exception) {
            throw new IllegalStateException("Replacement catalog classpath resource could not be inspected: " + resource, exception);
        }
    }

    private static List<NodeDefinitionLoader.SourceFile> scanClasspathRoot(ClasspathRoot root) {
        try {
            return root.jar() ? scanJar(root) : scanDirectory(root);
        } catch (IOException exception) {
            throw new IllegalStateException("Replacement catalog source could not be read: " + root.key(), exception);
        }
    }

    private static List<NodeDefinitionLoader.SourceFile> scanDirectory(ClasspathRoot root) throws IOException {
        List<Path> paths;
        try (var stream = Files.walk(root.path())) {
            paths = stream.filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".json"))
                .filter(path -> !path.getFileName().toString().startsWith("_"))
                .map(path -> root.path().relativize(path))
                .filter(path -> !isMigratedRelativePath(path.toString()))
                .sorted(relativePathComparator())
                .toList();
        }
        List<NodeDefinitionLoader.SourceFile> files = new ArrayList<>(paths.size());
        for (Path relativePath : paths) {
            Path path = root.path().resolve(relativePath);
            String relative = normalizeRelativePath(relativePath.toString());
            files.add(new NodeDefinitionLoader.SourceFile(path.toString(), "classpath:/nodes/" + relative, relative,
                NodeDefinitionLoader.SourceOrigin.CLASSPATH, Files.readAllBytes(path)));
        }
        return files;
    }

    private static List<NodeDefinitionLoader.SourceFile> scanJar(ClasspathRoot root) throws IOException {
        try (JarFile jar = new JarFile(root.path().toFile())) {
            List<JarEntry> entries = jar.stream()
                .filter(entry -> !entry.isDirectory() && entry.getName().startsWith(root.prefix())
                    && entry.getName().endsWith(".json")
                    && !entry.getName().substring(entry.getName().lastIndexOf('/') + 1).startsWith("_"))
                .filter(entry -> !isMigratedRelativePath(entry.getName().substring(root.prefix().length())))
                .sorted(Comparator.comparing((JarEntry entry) -> normalizeRelativePath(
                        entry.getName().substring(root.prefix().length())), String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(entry -> normalizeRelativePath(entry.getName().substring(root.prefix().length()))))
                .toList();
            List<NodeDefinitionLoader.SourceFile> files = new ArrayList<>(entries.size());
            for (JarEntry entry : entries) {
                String relative = normalizeRelativePath(entry.getName().substring(root.prefix().length()));
                byte[] bytes;
                try (var input = jar.getInputStream(entry)) {
                    bytes = input.readAllBytes();
                }
                files.add(new NodeDefinitionLoader.SourceFile(entry.getName(), "classpath:/nodes/" + relative, relative,
                    NodeDefinitionLoader.SourceOrigin.CLASSPATH, bytes));
            }
            return files;
        }
    }

    private static LocalInventory inspectLocalRoot(Path localRoot, boolean collectFiles) {
        Path root = localRoot == null ? Path.of(RESOURCE_PATH).toAbsolutePath().normalize()
            : localRoot.toAbsolutePath().normalize();
        List<NodeDefinitionLoader.SourceFile> files = new ArrayList<>();
        BasicFileAttributes rootAttributes;
        try {
            rootAttributes = Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException exception) {
            return new LocalInventory(files, new LocalRootValidation(root, false, true, ""));
        } catch (IOException | SecurityException exception) {
            return new LocalInventory(files, new LocalRootValidation(root, true, false, reason(exception)));
        }
        if (rootAttributes.isSymbolicLink() || !rootAttributes.isDirectory()) {
            return new LocalInventory(files, new LocalRootValidation(root, true, false,
                "Local replacement catalog root must be a regular non-symbolic-link directory"));
        }
        try {
            Files.walkFileTree(root, Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                    if (Files.isSymbolicLink(directory)) {
                        throw new MigrationException("Symbolic link directory is not allowed: " + directory);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (Files.isSymbolicLink(file) || !attributes.isRegularFile()) {
                        throw new MigrationException("Local replacement catalog contains a non-regular file: " + file);
                    }
                    if (collectFiles && file.toString().endsWith(".json") && !file.getFileName().toString().startsWith("_")) {
                        Path relativePath = root.relativize(file);
                        if (!isMigratedRelativePath(relativePath.toString())) {
                            String relative = normalizeRelativePath(relativePath.toString());
                            files.add(new NodeDefinitionLoader.SourceFile(file.toString(), "local:/nodes/" + relative,
                                relative, NodeDefinitionLoader.SourceOrigin.LOCAL, Files.readAllBytes(file)));
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                    throw new MigrationException("Local replacement catalog cannot be inspected: " + file, exception);
                }
            });
            files.sort(Comparator.comparing(NodeDefinitionLoader.SourceFile::relativePath, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(NodeDefinitionLoader.SourceFile::relativePath));
            return new LocalInventory(files, new LocalRootValidation(root, true, true, ""));
        } catch (IOException | RuntimeException exception) {
            return new LocalInventory(List.of(), new LocalRootValidation(root, true, false, reason(exception)));
        }
    }

    private static Comparator<Path> relativePathComparator() {
        return Comparator.comparing((Path path) -> normalizeRelativePath(path.toString()), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(path -> normalizeRelativePath(path.toString()));
    }

    private static String fileRootKey(Path root) {
        return "file:" + root.toAbsolutePath().normalize();
    }

    private static String jarRootKey(Path jar, String prefix) {
        return "jar:" + jar.toAbsolutePath().normalize() + "!/" + normalizeRelativePath(prefix);
    }

    private static String reason(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static String normalizeRelativePath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String[] segments = path.replace('\\', '/').split("/+", -1);
        List<String> normalized = new ArrayList<>();
        for (String segment : segments) {
            if (!segment.isBlank() && !".".equals(segment)) {
                normalized.add(segment);
            }
        }
        return String.join("/", normalized);
    }

    private static boolean isMigratedRelativePath(String path) {
        String normalized = normalizeRelativePath(path).toLowerCase(Locale.ROOT);
        return "migrated".equals(normalized) || normalized.startsWith("migrated/");
    }

    private static Snapshot snapshot(SourceInventory sourceInventory, List<NodeDefinition> classpathDefinitions,
                                     List<NodeDefinition> localDefinitions, List<NodeDefinitionDiagnostic> diagnostics) {
        List<NodeDefinition> definitions = new ArrayList<>();
        if (classpathDefinitions != null) {
            definitions.addAll(classpathDefinitions);
        }
        if (localDefinitions != null) {
            definitions.addAll(localDefinitions);
        }
        return new Snapshot(classpathDefinitions, localDefinitions, definitions, diagnostics, sourceInventory);
    }

    public record SourceInventory(List<NodeDefinitionLoader.SourceFile> classpathFiles,
                                  List<NodeDefinitionLoader.SourceFile> replacementClasspathFiles,
                                  List<NodeDefinitionLoader.SourceFile> localFiles,
                                  LocalRootValidation localRoot) {
        public SourceInventory {
            classpathFiles = classpathFiles == null ? List.of() : List.copyOf(classpathFiles);
            replacementClasspathFiles = replacementClasspathFiles == null ? List.of() : List.copyOf(replacementClasspathFiles);
            localFiles = localFiles == null ? List.of() : List.copyOf(localFiles);
            localRoot = Objects.requireNonNull(localRoot, "localRoot");
        }

        public List<NodeDefinitionLoader.SourceFile> authoredFiles() {
            List<NodeDefinitionLoader.SourceFile> values = new ArrayList<>(classpathFiles.size() + localFiles.size());
            values.addAll(classpathFiles);
            values.addAll(localFiles);
            return List.copyOf(values);
        }
    }

    public record Snapshot(List<NodeDefinition> classpathDefinitions, List<NodeDefinition> localDefinitions,
                           List<NodeDefinition> definitions, List<NodeDefinitionDiagnostic> diagnostics,
                           SourceInventory sourceInventory) {
        public Snapshot(List<NodeDefinition> classpathDefinitions, List<NodeDefinition> localDefinitions,
                        List<NodeDefinition> definitions, List<NodeDefinitionDiagnostic> diagnostics) {
            this(classpathDefinitions, localDefinitions, definitions, diagnostics, null);
        }

        public Snapshot {
            classpathDefinitions = classpathDefinitions != null ? List.copyOf(classpathDefinitions) : List.of();
            localDefinitions = localDefinitions != null ? List.copyOf(localDefinitions) : List.of();
            definitions = definitions != null ? List.copyOf(definitions) : List.of();
            diagnostics = diagnostics != null ? List.copyOf(diagnostics) : List.of();
        }

        public boolean available() {
            return !definitions.isEmpty();
        }

        public boolean hasErrors() {
            return diagnostics.stream().anyMatch(value -> value.severity() == NodeDefinitionDiagnostic.Severity.ERROR);
        }
    }

    public record LocalRootValidation(Path root, boolean present, boolean valid, String reason) {
        public LocalRootValidation {
            root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
            reason = reason == null ? "" : reason.trim();
            if (!valid && reason.isBlank()) {
                throw new IllegalArgumentException("Invalid local catalog roots must explain their failure");
            }
            if (valid && !reason.isBlank()) {
                throw new IllegalArgumentException("Valid local catalog roots cannot carry a failure reason");
            }
        }
    }

    private record ClasspathRoot(String key, Path path, String prefix, boolean jar, boolean codeSource) {
    }

    private record ClasspathInventory(List<NodeDefinitionLoader.SourceFile> files,
                                      List<NodeDefinitionLoader.SourceFile> replacementFiles) {
    }

    private record LocalInventory(List<NodeDefinitionLoader.SourceFile> files, LocalRootValidation validation) {
    }
}
