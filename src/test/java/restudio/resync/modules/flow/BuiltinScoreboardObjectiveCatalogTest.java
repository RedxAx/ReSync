package restudio.resync.modules.flow;

import org.bukkit.Bukkit;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import restudio.flow.data.FlowTypeRef;
import restudio.resync.api.OptionCatalogCapture;
import restudio.resync.api.OptionCatalogItem;
import restudio.resync.api.OptionCatalogProvider;
import restudio.resync.api.OptionCatalogQuery;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.customcontent.ItemAttributeSchemaService;
import restudio.resync.server.OptionCatalogCaptureExecutor;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinScoreboardObjectiveCatalogTest {
    @Test
    void capturesExactMainObjectiveNamesAndPreservesSavedTemplateChoices() {
        ServerMock server = MockBukkit.mock();
        try {
            Scoreboard main = server.getScoreboardManager().getMainScoreboard();
            Objective upper = main.registerNewObjective("Quest_Total", "dummy", "Quest Total");
            main.registerNewObjective("quest_total", "dummy", "Other Quest Total");
            server.getScoreboardManager().getNewScoreboard().registerNewObjective("private_only", "dummy", "Private");
            OptionCatalogRegistry registry = new OptionCatalogRegistry();
            registry.register(templateProvider());
            new BuiltinOptionCatalogService(() -> null, new ItemAttributeSchemaService()).registerProviders(registry);
            OptionCatalogProvider objectives = registry.provider("server:minecraft:scoreboard_objective");
            OptionCatalogQuery query = new OptionCatalogQuery(objectives.sourceId(), Map.of());

            assertEquals(OptionCatalogProvider.CaptureAffinity.SERVER_MAIN, objectives.captureAffinity(query));
            assertEquals(FlowTypeRef.simple("string"), objectives.runtimeDataType());
            assertEquals(String.class, objectives.runtimeDataClass());
            assertEquals("Quest_Total", objectives.resolveRuntimeData("Quest_Total"));
            OptionCatalogCapture first = objectives.capture(query);
            assertEquals(List.of("Quest_Total", "quest_total"), first.values());
            assertEquals("available", first.status());
            assertEquals(first.revision(), objectives.capture(query).revision());
            assertEquals(first.values(), objectives.values(query));
            assertEquals(first.items(), objectives.items(query));
            assertTrue(first.items().stream().allMatch(item -> objectives.sourceId().equals(item.metadata().get("source"))));
            assertThrows(UnsupportedOperationException.class, () -> first.items().clear());
            assertThrows(UnsupportedOperationException.class, () -> first.items().getFirst().metadata().clear());
            assertEquals(List.of("saved_only"), registry.provider("server:resync:scoreboard").values());

            upper.unregister();
            main.registerNewObjective("new_total", "dummy", "New Total");
            OptionCatalogCapture changed = objectives.capture(query);
            assertEquals(List.of("new_total", "quest_total"), changed.values());
            assertNotEquals(first.revision(), changed.revision());
            assertEquals(List.of("Quest_Total", "quest_total"), first.values());
            assertFalse(changed.values().contains("saved_only"));
            assertFalse(changed.values().contains("private_only"));
            assertEquals(List.of("saved_only"), registry.provider("server:resync:scoreboard").values());

            CompletionException failure = assertThrows(CompletionException.class,
                () -> CompletableFuture.supplyAsync(() -> objectives.capture(query)).join());
            assertInstanceOf(IllegalStateException.class, failure.getCause());
        } finally {
            MockBukkit.unmock();
        }
    }

    @Test
    void differentLiveNamesAlwaysAdvanceTheCaptureRevision() {
        ServerMock server = MockBukkit.mock();
        try {
            Scoreboard main = server.getScoreboardManager().getMainScoreboard();
            Objective firstObjective = main.registerNewObjective("Aa", "dummy", "First");
            OptionCatalogRegistry registry = new OptionCatalogRegistry();
            new BuiltinOptionCatalogService(() -> null, new ItemAttributeSchemaService()).registerProviders(registry);
            OptionCatalogProvider objectives = registry.provider("server:minecraft:scoreboard_objective");
            OptionCatalogQuery query = new OptionCatalogQuery(objectives.sourceId(), Map.of());
            OptionCatalogCapture first = objectives.capture(query);

            firstObjective.unregister();
            Objective replacement = main.registerNewObjective("BB", "dummy", "Replacement");
            OptionCatalogCapture second = objectives.capture(query);
            assertEquals(List.of("Aa"), first.values());
            assertEquals(List.of("BB"), second.values());
            assertNotEquals(first.revision(), second.revision());

            replacement.unregister();
            OptionCatalogCapture empty = objectives.capture(query);
            assertTrue(empty.values().isEmpty());
            assertEquals("available", empty.status());
            assertNotEquals(second.revision(), empty.revision());
        } finally {
            MockBukkit.unmock();
        }
    }

    @Test
    void existingBoundedCaptureReadsLiveObjectivesOnTheServerThread() throws Exception {
        ServerMock server = MockBukkit.mock();
        try {
            var plugin = MockBukkit.createMockPlugin();
            Scoreboard main = server.getScoreboardManager().getMainScoreboard();
            OptionCatalogRegistry registry = new OptionCatalogRegistry();
            new BuiltinOptionCatalogService(() -> null, new ItemAttributeSchemaService()).registerProviders(registry);
            OptionCatalogProvider objectives = registry.provider("server:minecraft:scoreboard_objective");
            OptionCatalogQuery query = new OptionCatalogQuery(objectives.sourceId(), Map.of());
            CountDownLatch queued = new CountDownLatch(1);
            try (OptionCatalogCaptureExecutor captures = OptionCatalogCaptureExecutor.bounded(4, Duration.ofSeconds(5),
                Bukkit::isPrimaryThread, action -> {
                    server.getScheduler().runTask(plugin, action);
                    queued.countDown();
                }, action -> Thread.ofPlatform().daemon().name("objective-capture-test").unstarted(action))) {
                CompletableFuture<OptionCatalogCapture> pending = CompletableFuture.supplyAsync(() -> captures.capture(objectives, query));
                assertTrue(queued.await(5, TimeUnit.SECONDS));
                assertFalse(pending.isDone());
                main.registerNewObjective("created_before_capture", "dummy", "Current");
                server.getScheduler().performOneTick();

                assertEquals(List.of("created_before_capture"), pending.get(5, TimeUnit.SECONDS).values());
            }
        } finally {
            MockBukkit.unmock();
        }
    }

    private static OptionCatalogProvider templateProvider() {
        return new OptionCatalogProvider() {
            @Override
            public String sourceId() {
                return "server:resync:scoreboard";
            }

            @Override
            public String revision() {
                return "templates-1";
            }

            @Override
            public List<String> values() {
                return List.of("saved_only");
            }

            @Override
            public List<OptionCatalogItem> items() {
                return List.of(new OptionCatalogItem("saved_only"));
            }
        };
    }
}
