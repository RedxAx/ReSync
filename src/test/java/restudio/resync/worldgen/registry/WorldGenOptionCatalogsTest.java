package restudio.resync.worldgen.registry;

import org.junit.jupiter.api.Test;
import restudio.resync.api.OptionCatalogProvider;
import restudio.resync.api.OptionCatalogQuery;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.server.OptionCatalogCaptureExecutor;
import restudio.resync.worldgen.contract.WorldGenTargetVersion;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGenOptionCatalogsTest {
    @Test
    void resolvesCatalogsFromTheProjectTargetVersion() {
        OptionCatalogRegistry registry = new OptionCatalogRegistry();
        WorldGenOptionCatalogs.register(registry);
        registry.bindCapture((provider, query) -> {
            throw new AssertionError("Prepared WorldGen catalogs must serve synchronous consumers");
        });
        assertThrows(OptionCatalogRegistry.CaptureUnavailable.class,
            () -> registry.values("worldgen:blocks", query("worldgen:blocks", "26.2")));
        AtomicInteger captures = new AtomicInteger();
        OptionCatalogCaptureExecutor executor = (provider, query) -> {
            assertEquals(OptionCatalogProvider.CaptureAffinity.IO, provider.captureAffinity());
            captures.incrementAndGet();
            return provider.capture(query);
        };
        WorldGenOptionCatalogs.prewarm(registry, executor).toCompletableFuture().join();
        OptionCatalogQuery beforeSulfur = query("worldgen:blocks", "26.1.2");
        OptionCatalogQuery withSulfur = query("worldgen:blocks", "26.2");

        assertFalse(registry.values("worldgen:blocks", beforeSulfur).contains("minecraft:sulfur"));
        assertTrue(registry.values("worldgen:blocks", withSulfur).contains("minecraft:sulfur"));
        assertFalse(registry.values("worldgen:biomes", query("worldgen:biomes", "26.1.2")).contains("minecraft:sulfur_caves"));
        assertTrue(registry.values("worldgen:biomes", query("worldgen:biomes", "26.2")).contains("minecraft:sulfur_caves"));
        assertFalse(registry.values("worldgen:tree_features", query("worldgen:tree_features", "1.21")).contains("PALE_OAK"));
        assertTrue(registry.values("worldgen:tree_features", query("worldgen:tree_features", "1.21.4")).contains("PALE_OAK"));
        assertNotEquals(registry.provider("worldgen:blocks").revision(beforeSulfur), registry.provider("worldgen:blocks").revision(withSulfur));
        assertEquals(WorldGenTargetVersion.values().length * 6, captures.get());
    }

    @Test
    void prewarmRejectsProviderReplacementDuringCapture() {
        OptionCatalogRegistry registry = new OptionCatalogRegistry();
        WorldGenOptionCatalogs.register(registry);
        OptionCatalogCaptureExecutor replacingExecutor = (provider, query) -> {
            var capture = provider.capture(query);
            registry.unregister(provider.sourceId());
            registry.register(replacement(provider.sourceId()));
            return capture;
        };

        CompletionException failure = assertThrows(CompletionException.class,
            () -> WorldGenOptionCatalogs.prewarm(registry, replacingExecutor).toCompletableFuture().join());

        assertTrue(failure.getCause() instanceof IllegalStateException);
        assertTrue(failure.getCause().getMessage().contains("changed during prewarm"));
    }

    private OptionCatalogQuery query(String source, String version) {
        return new OptionCatalogQuery(source, Map.of(WorldGenTargetVersion.OPTION_CONTEXT_KEY, version));
    }

    private OptionCatalogProvider replacement(String sourceId) {
        return new OptionCatalogProvider() {
            @Override
            public String sourceId() {
                return sourceId;
            }

            @Override
            public String revision() {
                return "replacement";
            }

            @Override
            public List<String> values() {
                return List.of();
            }
        };
    }
}
