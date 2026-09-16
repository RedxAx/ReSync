package restudio.resync.worldgen;

import restudio.resync.migration.MigrationPaths;

import java.nio.file.Path;

public final class WorldGenGeneratedOutputPolicy {
    public static final String OWNER = "resync.worldgen.generated";
    public static final String RELATIVE_ROOT = "worldgen/generated";
    private WorldGenGeneratedOutputPolicy() {
    }

    public static Path root(Path scopeRoot) {
        Path scope = MigrationPaths.requirePath(scopeRoot, "scopeRoot");
        return MigrationPaths.resolveInside(scope, RELATIVE_ROOT);
    }

}
