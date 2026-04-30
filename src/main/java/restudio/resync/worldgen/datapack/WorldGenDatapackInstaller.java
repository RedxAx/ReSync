package restudio.resync.worldgen.datapack;

import io.papermc.paper.datapack.Datapack;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class WorldGenDatapackInstaller {
    private final Plugin plugin;

    public WorldGenDatapackInstaller(Plugin plugin) {
        this.plugin = plugin;
    }

    public InstallResult install(WorldGenDatapackBuild build, String worldName) {
        if (build == null || build.getFolder() == null) {
            return new InstallResult(false, false, "Datapack Build Missing");
        }
        try {
            cleanupInstalledPacks();
            Path serverPack = serverDatapackFolder(build.getPackName());
            copyDirectory(build.getFolder(), serverPack);
            if (worldName != null && !worldName.isBlank()) {
                Path worldPack = Bukkit.getWorldContainer().toPath().resolve(worldName).resolve("datapacks").resolve(build.getPackName()).toAbsolutePath().normalize();
                copyDirectory(build.getFolder(), worldPack);
            }
            boolean enabled = enableKnownPack(build.getPackName());
            return new InstallResult(true, enabled, enabled ? "Datapack Enabled" : "Datapack Installed");
        } catch (IOException exception) {
            return new InstallResult(false, false, exception.getMessage());
        }
    }

    public void cleanupInstalledPacks() {
        Path root = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        List<Path> datapackFolders = new ArrayList<>();
        try (var stream = Files.list(root)) {
            stream
                .filter(Files::isDirectory)
                .map(path -> path.resolve("datapacks").toAbsolutePath().normalize())
                .filter(Files::isDirectory)
                .forEach(datapackFolders::add);
        } catch (IOException ignored) {
        }
        for (Path datapackFolder : datapackFolders) {
            try (var stream = Files.list(datapackFolder)) {
                stream
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("resync_worldgen_"))
                    .forEach(path -> {
                        try {
                            deleteDirectory(path);
                        } catch (IOException ignored) {
                        }
                    });
            } catch (IOException ignored) {
            }
        }
    }

    private Path serverDatapackFolder(String packName) {
        String mainWorld = Bukkit.getUnsafe().getMainLevelName();
        return Bukkit.getWorldContainer().toPath().resolve(mainWorld).resolve("datapacks").resolve(packName).toAbsolutePath().normalize();
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
