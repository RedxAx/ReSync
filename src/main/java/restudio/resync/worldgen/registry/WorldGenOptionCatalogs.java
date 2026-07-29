package restudio.resync.worldgen.registry;

import restudio.resync.ReSync;
import restudio.resync.api.OptionCatalogProvider;
import restudio.resync.api.OptionCatalogQuery;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.structure.StructureLibrary;
import restudio.resync.structure.StructureSummary;
import restudio.resync.worldgen.contract.WorldGenTargetVersion;
import restudio.resync.worldgen.datapack.WorldGenVanillaCatalog;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

public final class WorldGenOptionCatalogs {
    private WorldGenOptionCatalogs() {
    }

    public static void register(OptionCatalogRegistry registry) {
        registry.register(provider("worldgen:blocks", WorldGenVanillaCatalog::blocks));
        registry.register(provider("worldgen:biomes", WorldGenVanillaCatalog::biomes));
        registry.register(provider("worldgen:entity_types", WorldGenVanillaCatalog::entities));
        registry.register(provider("worldgen:structures", WorldGenOptionCatalogs::structures));
        registry.register(provider("worldgen:tree_features", WorldGenVanillaCatalog::treeTypes));
        registry.register(provider("worldgen:features", WorldGenVanillaCatalog::placedFeatures));
    }

    private static OptionCatalogProvider provider(String sourceId, Function<WorldGenVanillaCatalog, List<String>> values) {
        return new OptionCatalogProvider() {
            @Override
            public String sourceId() {
                return sourceId;
            }

            @Override
            public Set<String> contextKeys() {
                return Set.of(WorldGenTargetVersion.OPTION_CONTEXT_KEY);
            }

            @Override
            public String revision() {
                return revision(null);
            }

            @Override
            public String revision(OptionCatalogQuery query) {
                WorldGenTargetVersion target = target(query);
                return target.id() + ":" + WorldGenVanillaCatalog.load(target).serverSha1();
            }

            @Override
            public List<String> values() {
                return values(null);
            }

            @Override
            public List<String> values(OptionCatalogQuery query) {
                return values.apply(WorldGenVanillaCatalog.load(target(query)));
            }
        };
    }

    private static WorldGenTargetVersion target(OptionCatalogQuery query) {
        return WorldGenTargetVersion.resolve(query != null ? query.text(WorldGenTargetVersion.OPTION_CONTEXT_KEY) : null);
    }

    private static List<String> structures(WorldGenVanillaCatalog catalog) {
        List<String> custom = ReSync.getInstance() == null ? List.of() : StructureLibrary.get(ReSync.getInstance()).list().stream().map(StructureSummary::id).toList();
        return Stream.concat(catalog.structures().stream(), custom.stream()).distinct().sorted().toList();
    }
}
