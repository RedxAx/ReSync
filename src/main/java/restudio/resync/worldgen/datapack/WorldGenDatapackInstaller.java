package restudio.resync.worldgen.datapack;

import io.papermc.paper.datapack.Datapack;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public class WorldGenDatapackInstaller {
    public InstallResult install(WorldGenDatapackBuild build, String worldName) {
        if (build == null || build.getFolder() == null) {
            return new InstallResult(false, false, "Datapack Build Missing");
        }
        try {
            Path serverPack = serverDatapackFolder(build.getPackName());
            copyDirectory(build.getFolder(), serverPack);
            if (worldName != null && !worldName.isBlank()) {
                Path worldPack = worldDatapackFolder(worldName, build.getPackName());
                copyDirectory(build.getFolder(), worldPack);
            }
            boolean enabled = enableKnownPack(build.getPackName());
            return new InstallResult(true, enabled, enabled ? "Datapack Enabled" : "Datapack Installed");
        } catch (IOException exception) {
            return new InstallResult(false, false, exception.getMessage());
        }
    }

    private Path serverDatapackFolder(String packName) {
        return worldDatapackFolder(Bukkit.getUnsafe().getMainLevelName(), packName);
    }

    private Path worldDatapackFolder(String worldName, String packName) {
        if (worldName == null || worldName.isBlank() || worldName.contains("/") || worldName.contains("\\") || worldName.contains("..")
            || packName == null || packName.isBlank() || packName.contains("/") || packName.contains("\\") || packName.contains("..")) {
            throw new IllegalArgumentException("Invalid WorldGen datapack destination");
        }
        Path root = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        Path world = root.resolve(worldName).normalize();
        Path datapacks = world.resolve("datapacks").normalize();
        Path pack = datapacks.resolve(packName).normalize();
        if (world.equals(root) || !world.startsWith(root) || pack.equals(datapacks) || !pack.startsWith(datapacks)) {
            throw new IllegalArgumentException("Invalid WorldGen datapack destination");
        }
        return pack;
    }

    private boolean enableKnownPack(String packName) {
        try {
            for (Datapack pack : Bukkit.getDatapackManager().getPacks()) {
                if (pack.getName().equals(packName) || pack.getName().equals("file/" + packName)) {
                    pack.setEnabled(true);
                    return pack.isEnabled();
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        if (source == null || target == null || !Files.exists(source)) {
            return;
        }
        deleteDirectory(target);
        try (var stream = Files.walk(source)) {
            for (Path path : stream.toList()) {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative).normalize();
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            }
        }
    }

    private void deleteDirectory(Path folder) throws IOException {
        if (folder == null || !Files.exists(folder)) {
            return;
        }
        try (var stream = Files.walk(folder)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    public record InstallResult(boolean installed, boolean enabled, String message) {
    }
}
