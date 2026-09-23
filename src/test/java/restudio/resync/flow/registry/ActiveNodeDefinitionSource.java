package restudio.resync.flow.registry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ActiveNodeDefinitionSource {
    private static final Path ROOT = Path.of("src", "main", "resources", "nodes");

    private ActiveNodeDefinitionSource() {
    }

    public static Path root() {
        return ROOT;
    }

    public static String read(String fileName) throws IOException {
        return Files.readString(ROOT.resolve(fileName));
    }
}
