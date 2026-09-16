package restudio.resync.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ProductionSnapshotMetadataManifest {
    public static final int FORMAT_VERSION = 1;
    public static final String PRODUCER = "resync.snapshot-service";
    public static final String SCHEMA = "snapshot-metadata-v1";
    public static final String FILE_SUFFIX = ".metadata";

    private ProductionSnapshotMetadataManifest() {
    }

    public static Path pathFor(Path snapshotRoot) {
        Path root = MigrationPaths.requirePath(snapshotRoot, "snapshotRoot");
        Path name = root.getFileName();
        Path parent = root.getParent();
        if (name == null || parent == null) {
            throw new IllegalArgumentException("Snapshot Root Must Have A Parent");
        }
        return parent.resolve(name + FILE_SUFFIX).toAbsolutePath().normalize();
    }

    public static void write(Path snapshotRoot, SnapshotManifest manifest) throws IOException {
        if (manifest == null) {
            throw new IllegalArgumentException("Snapshot Manifest Is Required");
        }
        SnapshotMetadata metadata = manifest.metadata();
        String canonical = canonicalText(metadata, manifest.manifestHash());
        Path target = pathFor(snapshotRoot);
        MigrationPaths.requireWritableParent(target);
        AtomicFiles.write(target, (canonical + "metadata-hash=" + MigrationCanonical.sha256(canonical) + "\n")
            .getBytes(StandardCharsets.UTF_8));
    }

    public static Values read(Path snapshotRoot) throws IOException {
        Path target = pathFor(snapshotRoot);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
            throw new MigrationException("Production Snapshot Metadata Manifest Is Missing");
        }
        List<String> lines = new ArrayList<>(Files.readAllLines(target, StandardCharsets.UTF_8));
        if (lines.isEmpty() || !lines.getLast().startsWith("metadata-hash=")) {
            throw new MigrationException("Production Snapshot Metadata Manifest Hash Is Missing");
        }
        String storedHash = MigrationCanonical.requireDigest(lines.removeLast().substring("metadata-hash=".length()), "metadataHash");
        String canonical = String.join("\n", lines) + "\n";
        if (!MigrationCanonical.sha256(canonical).equals(storedHash)
            || !(canonical + "metadata-hash=" + storedHash + "\n").equals(Files.readString(target, StandardCharsets.UTF_8))) {
            throw new MigrationException("Production Snapshot Metadata Manifest Hash Does Not Match Content");
        }
        Cursor cursor = new Cursor(lines);
        int format = cursor.integer("format=");
        if (format != FORMAT_VERSION) {
            throw new MigrationException("Unsupported Production Snapshot Metadata Manifest Format");
        }
        String producer = cursor.decoded("producer=");
        if (!PRODUCER.equals(producer)) {
            throw new MigrationException("Production Snapshot Metadata Manifest Producer Is Invalid");
        }
        String schema = cursor.decoded("schema=");
        if (!SCHEMA.equals(schema)) {
            throw new MigrationException("Production Snapshot Metadata Manifest Schema Is Invalid");
        }
        int metadataFormat = cursor.integer("metadata-format=");
        String snapshotId = cursor.decoded("snapshot-id=");
        Instant createdAt = Instant.ofEpochMilli(cursor.longValue("created-at="));
        String build = cursor.decoded("build=");
        String catalog = cursor.raw("catalog=");
        int extensionCount = cursor.integer("extensions=");
        Map<String, String> extensions = new HashMap<>();
        for (int index = 0; index < extensionCount; index++) {
            String row = cursor.required("extension=").substring("extension=".length());
            String[] fields = row.split("\\|", -1);
            if (fields.length != 2) {
                throw new MigrationException("Production Snapshot Metadata Extension Row Is Invalid");
            }
            String owner = MigrationCanonical.decode(fields[0]);
            String version = MigrationCanonical.decode(fields[1]);
            if (extensions.putIfAbsent(owner, version) != null) {
                throw new MigrationException("Duplicate Production Snapshot Metadata Extension");
            }
        }
        String manifestHash = MigrationCanonical.requireDigest(cursor.raw("manifest-hash="), "manifestHash");
        cursor.requireEnd();
        SnapshotMetadata metadata = new SnapshotMetadata(metadataFormat, snapshotId, createdAt, build, catalog, extensions);
        Path manifestPath = target.resolveSibling(snapshotRoot.getFileName() + ".manifest");
        SnapshotManifest manifest = SnapshotManifest.read(manifestPath);
        if (!manifest.metadata().equals(metadata) || !manifest.manifestHash().equals(manifestHash)) {
            throw new MigrationException("Production Snapshot Metadata Does Not Match Snapshot Manifest");
        }
        return new Values(format, producer, schema, metadata, manifestHash);
    }

    private static String canonicalText(SnapshotMetadata metadata, String manifestHash) {
        StringBuilder value = new StringBuilder();
        value.append("format=").append(FORMAT_VERSION).append('\n');
        value.append("producer=").append(MigrationCanonical.encode(PRODUCER)).append('\n');
        value.append("schema=").append(MigrationCanonical.encode(SCHEMA)).append('\n');
        value.append("metadata-format=").append(metadata.formatVersion()).append('\n');
        value.append("snapshot-id=").append(MigrationCanonical.encode(metadata.snapshotId())).append('\n');
        value.append("created-at=").append(metadata.createdAt().toEpochMilli()).append('\n');
        value.append("build=").append(MigrationCanonical.encode(metadata.build())).append('\n');
        value.append("catalog=").append(metadata.catalogChecksum()).append('\n');
        value.append("extensions=").append(metadata.extensionVersions().size()).append('\n');
        metadata.extensionVersions().entrySet().stream().sorted(Map.Entry.comparingByKey())
            .forEach(entry -> value.append("extension=").append(MigrationCanonical.encode(entry.getKey())).append('|')
                .append(MigrationCanonical.encode(entry.getValue())).append('\n'));
        value.append("manifest-hash=").append(MigrationCanonical.requireDigest(manifestHash, "manifestHash")).append('\n');
        return value.toString();
    }

    public record Values(int formatVersion, String producer, String schema, SnapshotMetadata metadata,
                         String manifestHash) {
        public Values {
            if (formatVersion != FORMAT_VERSION) {
                throw new IllegalArgumentException("Unsupported Production Snapshot Metadata Manifest Format");
            }
            producer = MigrationCanonical.requireText(producer, "producer");
            schema = MigrationCanonical.requireText(schema, "schema");
            metadata = Objects.requireNonNull(metadata, "metadata");
            manifestHash = MigrationCanonical.requireDigest(manifestHash, "manifestHash");
        }
    }

    private static final class Cursor {
        private final List<String> lines;
        private int index;

        private Cursor(List<String> lines) {
            this.lines = lines;
        }

        private String required(String prefix) throws MigrationException {
            if (index >= lines.size() || !lines.get(index).startsWith(prefix)) {
                throw new MigrationException("Missing Production Snapshot Metadata Line: " + prefix);
            }
            return lines.get(index++);
        }

        private String raw(String prefix) throws MigrationException {
            return required(prefix).substring(prefix.length());
        }

        private String decoded(String prefix) throws MigrationException {
            return MigrationCanonical.decode(raw(prefix));
        }

        private int integer(String prefix) throws MigrationException {
            try {
                return Integer.parseInt(raw(prefix));
            } catch (NumberFormatException exception) {
                throw new MigrationException("Invalid Production Snapshot Metadata Integer: " + prefix, exception);
            }
        }

        private long longValue(String prefix) throws MigrationException {
            try {
                return Long.parseLong(raw(prefix));
            } catch (NumberFormatException exception) {
                throw new MigrationException("Invalid Production Snapshot Metadata Long: " + prefix, exception);
            }
        }

        private void requireEnd() throws MigrationException {
            if (index != lines.size()) {
                throw new MigrationException("Unexpected Production Snapshot Metadata Content");
            }
        }
    }
}
