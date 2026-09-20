package restudio.resync.customcontent;

import restudio.resync.metadata.MinecraftSchemaBundle;
import restudio.resync.metadata.MinecraftSchemaBundleCodec;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

public final class MinecraftSchemaExportFiles {
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final MinecraftSchemaBundleCodec CODEC = new MinecraftSchemaBundleCodec();

    private MinecraftSchemaExportFiles() {
    }

    public static Result write(Path dataRoot, MinecraftSchemaBundle bundle) throws IOException {
        Path root = Objects.requireNonNull(dataRoot, "ReSync data root is required").toAbsolutePath().normalize();
        MinecraftSchemaBundle checked = Objects.requireNonNull(bundle, "Minecraft schema bundle is required");
        byte[] content = CODEC.encodeBytes(checked);
        Path directory = root.resolve("exports").normalize();
        Files.createDirectories(directory);
        Path output = directory.resolve("minecraft-schema-" + checked.minecraftVersion() + ".json").normalize();
        if (!output.getParent().equals(directory)) {
            throw new IllegalArgumentException("Minecraft version cannot be used as an export name");
        }
        Path temporary = Files.createTempFile(directory, "minecraft-schema-", ".tmp");
        boolean installed = false;
        try {
            Files.write(temporary, content, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
            }
            installed = true;
        } finally {
            if (!installed) Files.deleteIfExists(temporary);
        }
        return new Result(output, sha256(content), content.length);
    }

    private static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            char[] result = new char[digest.length * 2];
            for (int index = 0; index < digest.length; index++) {
                int current = digest[index] & 0xff;
                result[index * 2] = HEX[current >>> 4];
                result[index * 2 + 1] = HEX[current & 0x0f];
            }
            return new String(result);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record Result(Path path, String sha256, long bytes) {
        public Result {
            path = Objects.requireNonNull(path, "Minecraft schema export path is required").toAbsolutePath().normalize();
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Minecraft schema export SHA-256 is invalid");
            }
            if (bytes < 1) throw new IllegalArgumentException("Minecraft schema export must contain bytes");
        }
    }
}
