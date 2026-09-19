package restudio.resync.metadata;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public record ServerSettingsBundle(int formatVersion, String createdAt, List<Source> sources) {
    public static final int CURRENT_FORMAT_VERSION = 1;
    public static final MetadataArtifactFamily ARTIFACT_FAMILY = MetadataArtifactFamily.of("server_settings");
    private static final int MAXIMUM_SOURCE_BYTES = 1_048_576;

    public ServerSettingsBundle {
        if (formatVersion != CURRENT_FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported server settings bundle format version: " + formatVersion);
        }
        createdAt = MetadataValidation.text(createdAt, "Server settings bundle creation time", 128);
        Objects.requireNonNull(sources, "Server settings sources are required");
        ArrayList<Source> sorted = new ArrayList<>(sources.size());
        Set<String> identities = new HashSet<>();
        for (Source source : sources) {
            Source checked = Objects.requireNonNull(source, "Server settings source is required");
            if (!identities.add(checked.id())) {
                throw new IllegalArgumentException("Server settings sources contain a duplicate identity: " + checked.id());
            }
            sorted.add(checked);
        }
        if (sorted.isEmpty()) {
            throw new IllegalArgumentException("Server settings sources must not be empty");
        }
        sorted.sort(Comparator.comparing(Source::id));
        sources = List.copyOf(sorted);
    }

    public ServerSettingsBundle(String createdAt, List<Source> sources) {
        this(CURRENT_FORMAT_VERSION, createdAt, sources);
    }

    public record Source(String id, int schemaVersion, String mediaType, String contentSha256, String content) {
        private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

        public Source {
            id = MetadataValidation.id(id, "Server settings source ID");
            if (schemaVersion < 1) {
                throw new IllegalArgumentException("Server settings source schema version must be positive");
            }
            mediaType = MetadataValidation.text(mediaType, "Server settings source media type", 128);
            if (!"application/yaml".equals(mediaType)) {
                throw new IllegalArgumentException("Unsupported server settings source media type: " + mediaType);
            }
            contentSha256 = MetadataValidation.text(contentSha256, "Server settings source SHA-256", 64);
            if (!SHA256.matcher(contentSha256).matches()) {
                throw new IllegalArgumentException("Server settings source SHA-256 must be lowercase hexadecimal");
            }
            Objects.requireNonNull(content, "Server settings source content is required");
            if (content.isEmpty() || content.indexOf('\0') >= 0 || content.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("Server settings source content must be nonempty canonical text");
            }
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAXIMUM_SOURCE_BYTES) {
                throw new IllegalArgumentException("Server settings source content is too large");
            }
            if (!contentSha256.equals(sha256(bytes))) {
                throw new IllegalArgumentException("Server settings source SHA-256 does not match its content");
            }
        }

        public static Source yaml(String id, int schemaVersion, String content) {
            String normalized = normalize(content);
            return new Source(id, schemaVersion, "application/yaml",
                    sha256(normalized.getBytes(StandardCharsets.UTF_8)), normalized);
        }

        private static String normalize(String value) {
            Objects.requireNonNull(value, "Server settings source content is required");
            String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
            return normalized.endsWith("\n") ? normalized : normalized + '\n';
        }
    }

    private static String sha256(byte[] value) {
        return PortableSha256.hex(value);
    }
}
