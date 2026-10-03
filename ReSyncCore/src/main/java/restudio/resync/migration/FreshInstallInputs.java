package restudio.resync.migration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;

public final class FreshInstallInputs {
    private static final Set<String> CONFIGURATION = Set.of("config.properties", "resync.properties", "config.yml", "config.yaml");
    private static final int MAX_ENTRIES = 128;
    private static final long MAX_CONFIG_BYTES = 1_048_576L;
    public static final String DIAGNOSTICS_DIRECTORY = "diagnostic-channel";
    private static final long MAX_DIAGNOSTIC_BYTES = 16L * 1024L * 1024L;
    private static final long MAX_DIAGNOSTIC_TOTAL = 128L * 1024L * 1024L;
    private static final Pattern ROTATED_DIAGNOSTIC = Pattern.compile("resync-lifecycle\\.[0-9]{20}\\.[0-9]{20}\\.[0-9a-f-]{36}\\.jsonl");

    private FreshInstallInputs() {
    }

    public static boolean accepts(Path root) throws IOException {
        Path directory = MigrationPaths.requireDirectory(root, "fresh install inputs");
        try (var entries = Files.list(directory)) {
            var children = entries.limit(MAX_ENTRIES + 1L).toList();
            if (children.size() > MAX_ENTRIES) return false;
            for (Path child : children) {
                if (!acceptsEntry(child)) return false;
            }
            return true;
        }
    }

    public static boolean acceptsEntry(Path entry) throws IOException {
        if (Files.isSymbolicLink(entry)) return false;
        if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
            return CONFIGURATION.contains(entry.getFileName().toString()) && Files.size(entry) <= MAX_CONFIG_BYTES;
        }
        if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) || entry.getFileName().toString().startsWith(".")) return false;
        if (entry.getFileName().toString().equals(DIAGNOSTICS_DIRECTORY)) return acceptsDiagnostics(entry);
        try (var children = Files.list(entry)) {
            return children.findAny().isEmpty();
        }
    }

    private static boolean acceptsDiagnostics(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            var files = entries.limit(MAX_ENTRIES + 1L).toList();
            if (files.size() > MAX_ENTRIES) return false;
            long total = 0L;
            for (Path file : files) {
                String name = file.getFileName().toString();
                if ((!name.equals("resync-lifecycle.jsonl")
                    && !ROTATED_DIAGNOSTIC.matcher(name).matches())
                    || Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false;
                long size = Files.size(file);
                if (size > MAX_DIAGNOSTIC_BYTES) return false;
                total += size;
                if (total > MAX_DIAGNOSTIC_TOTAL) return false;
            }
            return true;
        }
    }
}
