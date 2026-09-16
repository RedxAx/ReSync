package restudio.resync.upgrade;

import restudio.resync.flow.validation.NodeDefinitionBuildValidator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class UpgradeNodeDefinitionSource {
    private UpgradeNodeDefinitionSource() {
    }

    public static Path root() {
        String configured = System.getProperty(NodeDefinitionBuildValidator.NODE_DEFINITIONS_PROPERTY);
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("The Upgrade node definition directory must be configured through -D" + NodeDefinitionBuildValidator.NODE_DEFINITIONS_PROPERTY);
        }
        Path root = Path.of(configured).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("The configured Upgrade node definition directory is missing: " + root);
        }
        return root;
    }

    public static String read(String relativePath) throws IOException {
        Path root = root();
        Path file = root.resolve(Objects.requireNonNull(relativePath, "relativePath")).normalize();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("Node definition path escapes the configured Upgrade source: " + relativePath);
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
