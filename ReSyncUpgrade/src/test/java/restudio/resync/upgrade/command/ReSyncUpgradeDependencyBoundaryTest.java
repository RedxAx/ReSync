package restudio.resync.upgrade.command;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ReSyncUpgradeDependencyBoundaryTest {
    @Test
    void moduleDeclaresOnlyCoreAsItsProductionProjectDependency() throws Exception {
        Path moduleBuild = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().resolve("build.gradle.kts");
        assertTrue(Files.isRegularFile(moduleBuild), "ReSyncUpgrade build file is required");
        String build = Files.readString(moduleBuild).toLowerCase(Locale.ROOT);

        assertTrue(build.contains("api(project(\":resynccore\"))"));
        assertFalse(build.contains("implementation(project(\":resynccore\"))"));
        assertFalse(build.contains("resyncvelocity"));
        assertFalse(build.contains("paper"));
        assertFalse(build.contains("bukkit"));
        assertFalse(build.contains("gson"));
        assertFalse(build.contains("sqlite"));
        assertFalse(build.contains("rescreen"));
        assertFalse(build.contains("remotely"));
        assertTrue(build.contains("resync-offline-upgrader"));
    }
}
