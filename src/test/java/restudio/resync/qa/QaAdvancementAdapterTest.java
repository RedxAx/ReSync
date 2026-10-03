package restudio.resync.qa;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;
import org.mockbukkit.mockbukkit.util.UnsafeValuesMock;
import restudio.resync.ReSync;
import restudio.resync.advancement.AdvancementRuntimeBridge;
import restudio.resync.advancement.PaperAdvancementRuntimeBridge;
import restudio.resync.advancement.PaperUnsafe;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.modules.AdvancementModule;
import restudio.resync.modules.ModuleContext;
import restudio.resync.modules.flow.FlowResourceMutationStamp;
import restudio.resync.network.paper.PaperPlayerDataMutationAdmission;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.CanonicalProjectMetadataFixture;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QaAdvancementAdapterTest {
    @TempDir Path temporary;
    private Server server;
    private TestPlugin plugin;
    private OnlinePlayer player;
    private CommandSender actor;
    private AdvancementModule module;
    private ModuleContext context;
    private ReSyncJsonResourceStorage storage;
    private AssetTransactionCoordinator transactions;
    private RuntimeBridge bridge;
    private QaAdvancementAdapter adapter;
    private boolean blockProjection;
    private final Map<Field, Object> nativeMethods = new LinkedHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        for (Field field : PaperUnsafe.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())) {
                field.setAccessible(true);
                nativeMethods.put(field, field.get(null));
            }
        }
        server = new Server(temporary);
        MockBukkit.mock(server);
        plugin = MockBukkit.loadSimple(TestPlugin.class);
        Path worldRoot = Files.createDirectories(temporary.resolve("world"));
        for (String directory : List.of("playerdata", "stats", "advancements")) {
            Files.createDirectories(worldRoot.resolve(directory));
        }
        World world = new World(worldRoot);
        server.addWorld(world);
        player = new OnlinePlayer(server);
        server.addPlayer(player);
        player.teleport(new Location(world, 0, 64, 0));
        actor = server.getConsoleSender();
        actor.addAttachment(plugin, "resync.qa", true);
        transactions = AssetTransactionCoordinator.open(temporary.resolve("assets"), new Gson());
        CanonicalProjectMetadataFixture.seed(transactions);
        storage = new ReSyncJsonResourceStorage(plugin, LegacyRuntimeActivationGate.runtime(temporary),
            new AssetPersistenceGate(temporary), transactions);
        storage.save(ReSyncResourceCatalog.ADVANCEMENT_TREE, tree());
        storage.addInterceptor(new ReSyncJsonResourceStorage.ResourceMutationInterceptor() {
            @Override
            public void afterCommit(String type, String id, JsonObject value, FlowResourceMutationStamp stamp) {
                if (blockProjection && ReSyncResourceCatalog.ADVANCEMENT_TREE.equals(type)) {
                    throw new IllegalStateException("Projection callback is unavailable");
                }
            }
        });
        PaperPlayerDataMutationAdmission admission = new PaperPlayerDataMutationAdmission(List.of(worldRoot), temporary);
        module = new AdvancementModule(admission);
        context = new ModuleContext(plugin, null, null, null, null, null, null, null, null, null, null, null, null);
        context.registerService(ReSyncJsonResourceStorage.class, storage);
        context.registerService(PaperPlayerDataMutationAdmission.class, admission);
        module.initialize(context);
        bridge = new RuntimeBridge();
        Field field = AdvancementModule.class.getDeclaredField("bridge");
        field.setAccessible(true);
        field.set(module, bridge);
        module.start(context);
        adapter = new QaAdvancementAdapter(() -> module);
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (module != null) module.stop(context);
            if (storage != null) storage.closePersistence();
            if (transactions != null) transactions.close();
        } finally {
            try {
                for (Map.Entry<Field, Object> entry : nativeMethods.entrySet()) {
                    entry.getKey().set(null, entry.getValue());
                }
            } finally {
                MockBukkit.unmock();
            }
        }
    }

    @Test
    void permissionAndServerThreadAdmissionProtectNativeProgress() {
        actor.addAttachment(plugin, "resync.qa", false);
        CompletionException denied = assertThrows(CompletionException.class,
            () -> adapter.invoke(actor, "advancement.inspect", Map.of()).toCompletableFuture().join());
        assertTrue(denied.getCause() instanceof SecurityException);
        actor.addAttachment(plugin, "resync.qa", true);
        CompletionException asynchronous = assertThrows(CompletionException.class, () -> CompletableFuture.supplyAsync(
            () -> adapter.invoke(actor, "advancement.inspect", Map.of()).toCompletableFuture().join()).join());
        assertTrue(asynchronous.getCause() instanceof SecurityException);
        assertFalse(module.advancementService().has(player, "qa_tree", "held", "stone"));
    }

    @Test
    void evaluatesActualPlayerConditionsWithoutAwardingOrChangingTheCommittedTree() {
        Map<String, Object> input = criterion("held");
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIRT));
        assertEquals(false, invoke("advancement.evaluate", input).get("conditionsMatch"));
        player.getInventory().setItemInMainHand(new ItemStack(Material.STONE));
        assertEquals(true, invoke("advancement.evaluate", input).get("conditionsMatch"));
        assertFalse(module.advancementService().has(player, "qa_tree", "held", "stone"));
        assertEquals(1L, module.treeState("qa_tree").stamp().revision());
        assertEquals(input.get("checksum"), module.treeState("qa_tree").stamp().payloadHash());
    }

    @Test
    void duplicateReceiptsDoNotRepeatAnAwardAndRevokeUsesTheSameNativeProgress() {
        ServerQaService service = new ServerQaService(() -> 1L);
        service.register(adapter.describe(), adapter::invoke);
        Map<String, Object> input = new LinkedHashMap<>(criterion("held"));
        input.put("requestId", UUID.randomUUID().toString());
        Map<String, Object> first = service.submit(actor, "advancement.grant", input);
        assertEquals("completed", first.get("status"));
        assertEquals(first, service.submit(actor, "advancement.grant", input));
        assertEquals(1, player.progress("held").awards);
        assertEquals(true, invoke("advancement.revoke", criterion("held")).get("changed"));
        assertFalse(module.advancementService().has(player, "qa_tree", "held", "stone"));
    }

    @Test
    void rejectsAStaleCommittedIdentityBeforeAwarding() {
        Map<String, Object> input = new LinkedHashMap<>(criterion("held"));
        input.put("checksum", "0".repeat(64));
        assertThrows(CompletionException.class, () -> invoke("advancement.grant", input));
        assertFalse(module.advancementService().has(player, "qa_tree", "held", "stone"));
    }

    @Test
    void cancelledBreaksCannotCompleteAndSuccessfulMatchingEventsRunActionsOnce() {
        player.getWorld().getBlockAt(0, 64, 0).setType(Material.STONE);
        BlockBreakEvent cancelled = new BlockBreakEvent(player.getWorld().getBlockAt(0, 64, 0), player);
        cancelled.setCancelled(true);
        Bukkit.getPluginManager().callEvent(cancelled);
        assertFalse(module.advancementService().has(player, "qa_tree", "broken", "stone"));
        assertEquals(List.of(), server.commands);
        Bukkit.getPluginManager().callEvent(new BlockBreakEvent(player.getWorld().getBlockAt(0, 64, 0), player));
        Bukkit.getPluginManager().callEvent(new BlockBreakEvent(player.getWorld().getBlockAt(0, 64, 0), player));
        assertTrue(module.advancementService().complete(player, "qa_tree", "broken"));
        assertEquals(List.of("say qa-complete"), server.commands);
    }

    @Test
    void rejectedSavesNeverExposeCandidateCriteriaOrRevokeNativeProgress() {
        assertTrue(module.advancementService().grant(player, "qa_tree", "held", "stone"));
        Map<String, Object> current = criterion("held");
        int awards = player.progress("held").awards;
        JsonObject candidate = tree();
        candidate.getAsJsonObject("nodes").getAsJsonObject("held").getAsJsonObject("criteria")
            .getAsJsonObject("stone").getAsJsonObject("conditions").addProperty("heldItem", "minecraft:dirt");
        module.beforeSave(ReSyncResourceCatalog.ADVANCEMENT_TREE, candidate);
        assertTrue(module.advancementService().has(player, "qa_tree", "held", "stone"));
        assertThrows(RuntimeException.class, () -> storage.save(ReSyncResourceCatalog.ADVANCEMENT_TREE, candidate,
            UUID.randomUUID(), 0L));
        assertEquals(current.get("checksum"), module.treeState("qa_tree").stamp().payloadHash());
        assertEquals(1L, module.treeState("qa_tree").stamp().revision());
        assertTrue(module.advancementService().has(player, "qa_tree", "held", "stone"));
        assertEquals(awards, player.progress("held").awards);
        assertEquals(List.of(), server.commands);
    }

    @Test
    void aDurableCommitCannotAwardAgainstTheOldNativeProjectionBeforeItsCallback() {
        Map<String, Object> old = criterion("broken");
        blockProjection = true;
        UUID mutation = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> storage.save(ReSyncResourceCatalog.ADVANCEMENT_TREE,
            changedTree(), mutation, 1L));
        assertEquals(2L, storage.readMutationStamp(ReSyncResourceCatalog.ADVANCEMENT_TREE, "qa_tree").revision());
        assertThrows(CompletionException.class, () -> invoke("advancement.grant", old));
        fireBreak(Material.DIRT);
        assertFalse(module.advancementService().has(player, "qa_tree", "broken", "stone"));
        assertEquals(List.of(), server.commands);
        blockProjection = false;
        storage.completePostCommitRecovery(ReSyncResourceCatalog.ADVANCEMENT_TREE, "qa_tree", mutation, 2L, false);
        fireBreak(Material.DIRT);
        assertTrue(module.advancementService().complete(player, "qa_tree", "broken"));
        assertEquals(List.of("say qa-new"), server.commands);
    }

    @Test
    void failedNativeActivationPreservesProgressAndExactCommittedRetryRecoversOnce() {
        assertTrue(module.advancementService().grant(player, "qa_tree", "held", "stone"));
        bridge.failure = new IllegalStateException("Native replacement failed");
        UUID mutation = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> storage.save(ReSyncResourceCatalog.ADVANCEMENT_TREE,
            changedTree(), mutation, 1L));
        assertTrue(module.advancementService().has(player, "qa_tree", "held", "stone"));
        assertThrows(CompletionException.class, () -> invoke("advancement.progress", criterionWithoutStamp("held")));
        fireBreak(Material.DIRT);
        assertFalse(module.advancementService().has(player, "qa_tree", "broken", "stone"));
        assertEquals(List.of(), server.commands);
        storage.completePostCommitRecovery(ReSyncResourceCatalog.ADVANCEMENT_TREE, "qa_tree", mutation, 2L, false);
        assertEquals(2L, module.treeState("qa_tree").stamp().revision());
        fireBreak(Material.DIRT);
        assertEquals(List.of("say qa-new"), server.commands);
        int awards = player.progress("broken").awards;
        storage.completePostCommitRecovery(ReSyncResourceCatalog.ADVANCEMENT_TREE, "qa_tree", mutation, 2L, false);
        fireBreak(Material.DIRT);
        assertEquals(awards, player.progress("broken").awards);
        assertEquals(List.of("say qa-new"), server.commands);
        assertThrows(IllegalStateException.class, () -> storage.completePostCommitRecovery(
            ReSyncResourceCatalog.ADVANCEMENT_TREE, "qa_tree", UUID.randomUUID(), 2L, false));
    }

    @Test
    void nativeActivationErrorsFenceGameplayAndKeepTheCommittedRevisionRecoverable() {
        bridge.failure = new AssertionError("Native replacement error");
        UUID mutation = UUID.randomUUID();
        assertThrows(AssertionError.class, () -> storage.save(ReSyncResourceCatalog.ADVANCEMENT_TREE,
            changedTree(), mutation, 1L));
        assertEquals(mutation, storage.readMutationStamp(ReSyncResourceCatalog.ADVANCEMENT_TREE, "qa_tree").mutationId());
        assertThrows(CompletionException.class, () -> invoke("advancement.inspect", Map.of()));
        fireBreak(Material.DIRT);
        assertEquals(List.of(), server.commands);
        storage.completePostCommitRecovery(ReSyncResourceCatalog.ADVANCEMENT_TREE, "qa_tree", mutation, 2L, false);
        fireBreak(Material.DIRT);
        assertEquals(List.of("say qa-new"), server.commands);
    }

    @Test
    void failedRollbackKeepsPartiallyLoadedKeysOwnedUntilRetryRemovesThem() {
        PaperAdvancementRuntimeBridge nativeBridge = new PaperAdvancementRuntimeBridge(null);
        JsonObject node = tree().getAsJsonObject("nodes").getAsJsonObject("root");
        JsonObject definition = new JsonObject();
        JsonObject nodes = new JsonObject();
        nodes.add("root", node);
        definition.add("nodes", nodes);
        NamespacedKey previous = new NamespacedKey("resync", "qa_previous/root");
        NamespacedKey candidate = new NamespacedKey("resync", "qa_candidate/root");
        NamespacedKey replacement = new NamespacedKey("resync", "qa_replacement/root");
        nativeBridge.replace(Map.of("qa_previous", definition));
        assertNotNull(Bukkit.getAdvancement(previous));
        server.unsafe.failedLoad = candidate;
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> nativeBridge.replace(Map.of("qa_candidate", definition)));
        assertEquals(1, failure.getSuppressed().length);
        assertNotNull(Bukkit.getAdvancement(candidate));
        server.unsafe.rejectRemove = false;
        nativeBridge.replace(Map.of("qa_replacement", definition));
        assertNull(Bukkit.getAdvancement(previous));
        assertNull(Bukkit.getAdvancement(candidate));
        assertNotNull(Bukkit.getAdvancement(replacement));
        nativeBridge.replace(Map.of());
        assertNull(Bukkit.getAdvancement(replacement));
    }

    @Test
    void stoppedModuleCannotReloadOrAwardFromQueuedStartupWork() {
        module.stop(context);
        int replacements = bridge.replacements;
        server.getScheduler().performTicks(45);
        assertEquals(replacements, bridge.replacements);
        assertTrue(server.advancements.isEmpty());
        assertThrows(CompletionException.class, () -> invoke("advancement.grant", criterionWithoutStamp("held")));
        module = null;
    }

    private Map<String, Object> invoke(String operation, Map<String, Object> input) {
        return adapter.invoke(actor, operation, input).toCompletableFuture().join();
    }

    private Map<String, Object> criterion(String nodeId) {
        AdvancementModule.TreeState state = module.treeState("qa_tree");
        Map<String, Object> input = new LinkedHashMap<>(criterionWithoutStamp(nodeId));
        input.put("revision", state.stamp().revision());
        input.put("checksum", state.stamp().payloadHash());
        return input;
    }

    private Map<String, Object> criterionWithoutStamp(String nodeId) {
        return Map.of("treeId", "qa_tree", "nodeId", nodeId, "criterion", "stone", "playerId", player.getUniqueId().toString());
    }

    private void fireBreak(Material material) {
        player.getWorld().getBlockAt(0, 64, 0).setType(material);
        Bukkit.getPluginManager().callEvent(new BlockBreakEvent(player.getWorld().getBlockAt(0, 64, 0), player));
    }

    private JsonObject changedTree() {
        JsonObject tree = tree();
        JsonObject broken = tree.getAsJsonObject("nodes").getAsJsonObject("broken");
        broken.getAsJsonObject("criteria").getAsJsonObject("stone").getAsJsonObject("conditions")
            .addProperty("block", "minecraft:dirt");
        broken.getAsJsonObject("onComplete").add("commands", JsonParser.parseString("[\"say qa-new\"]"));
        return tree;
    }

    private JsonObject tree() {
        return JsonParser.parseString("""
            {"id":"qa_tree","enabled":true,"nodes":{
              "root":{"display":{"title":"QA","description":"QA Root","icon":"minecraft:stone"},"criteria":{}},
              "held":{"parent":"root","display":{"title":"Hold Stone","description":"Hold Stone","icon":"minecraft:stone"},"criteria":{"stone":{"trigger":"held_item","conditions":{"heldItem":"minecraft:stone"}}}},
              "broken":{"parent":"held","display":{"title":"Break Stone","description":"Break Stone","icon":"minecraft:stone"},"criteria":{"stone":{"trigger":"break_block","conditions":{"block":"minecraft:stone"}}},"onComplete":{"commands":["say qa-complete"]}}
            }}
            """).getAsJsonObject();
    }

    public static class TestPlugin extends ReSync {
        @Override public void onEnable() {}
        @Override public void onDisable() {}
    }

    private static final class World extends WorldMock {
        private final File folder;
        private World(Path folder) { this.folder = folder.toFile(); }
        @Override public File getWorldFolder() { return folder; }
    }

    private static final class Server extends ServerMock {
        private final File folder;
        private final Map<NamespacedKey, Advancement> advancements = new LinkedHashMap<>();
        private final List<String> commands = new ArrayList<>();
        private final NativeUnsafe unsafe = new NativeUnsafe(this);
        private Server(Path folder) { this.folder = folder.toFile(); }
        @Override public File getWorldContainer() { return folder; }
        @Override public Advancement getAdvancement(NamespacedKey key) { return advancements.get(key); }
        @Override public NativeUnsafe getUnsafe() { return unsafe; }
        @Override public void reloadData() {}
        @Override public Iterator<Advancement> advancementIterator() { return advancements.values().iterator(); }
        @Override public boolean dispatchCommand(CommandSender sender, String command) { commands.add(command); return true; }
    }

    public static final class NativeUnsafe extends UnsafeValuesMock {
        private final Server server;
        private NamespacedKey failedLoad;
        private boolean rejectRemove;
        private NativeUnsafe(Server server) { this.server = server; }

        @Override
        public Advancement loadAdvancement(NamespacedKey key, String value) {
            JsonObject definition = JsonParser.parseString(value).getAsJsonObject();
            Set<String> criteria = Set.copyOf(definition.getAsJsonObject("criteria").keySet());
            Advancement advancement = (Advancement) Proxy.newProxyInstance(Advancement.class.getClassLoader(),
                new Class<?>[]{Advancement.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "getKey" -> key;
                    case "getCriteria" -> List.copyOf(criteria);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
            server.advancements.put(key, advancement);
            if (key.equals(failedLoad)) {
                failedLoad = null;
                rejectRemove = true;
                throw new IllegalStateException("Native load failed after registration");
            }
            return advancement;
        }

        @Override
        public boolean removeAdvancement(NamespacedKey key) {
            if (rejectRemove) throw new IllegalStateException("Native rollback removal failed");
            return server.advancements.remove(key) != null;
        }
    }

    private static final class OnlinePlayer extends PlayerMock {
        private final Map<NamespacedKey, Progress> states = new LinkedHashMap<>();
        private OnlinePlayer(Server server) { super(server, "qa-player"); }
        @Override public AdvancementProgress getAdvancementProgress(Advancement advancement) {
            return states.computeIfAbsent(advancement.getKey(), ignored -> new Progress(advancement));
        }
        private Progress progress(String nodeId) { return states.get(new NamespacedKey("resync", "qa_tree/" + nodeId)); }
    }

    private static final class Progress implements AdvancementProgress {
        private final Advancement advancement;
        private final Set<String> awarded = new LinkedHashSet<>();
        private int awards;
        private Progress(Advancement advancement) { this.advancement = advancement; }
        @Override public Advancement getAdvancement() { return advancement; }
        @Override public boolean isDone() { return awarded.containsAll(advancement.getCriteria()); }
        @Override public boolean awardCriteria(String criterion) {
            if (!advancement.getCriteria().contains(criterion) || !awarded.add(criterion)) return false;
            awards++;
            return true;
        }
        @Override public boolean revokeCriteria(String criterion) { return awarded.remove(criterion); }
        @Override public Date getDateAwarded(String criterion) { return awarded.contains(criterion) ? new Date(0) : null; }
        @Override public Collection<String> getRemainingCriteria() {
            Set<String> remaining = new LinkedHashSet<>(advancement.getCriteria());
            remaining.removeAll(awarded);
            return remaining;
        }
        @Override public Collection<String> getAwardedCriteria() { return Set.copyOf(awarded); }
    }

    private final class RuntimeBridge implements AdvancementRuntimeBridge {
        private int replacements;
        private Throwable failure;
        @Override public boolean supported() { return true; }
        @Override public String unsupportedReason() { return ""; }
        @Override public void replace(Map<String, JsonObject> trees) {
            replacements++;
            Throwable injected = failure;
            failure = null;
            if (injected instanceof RuntimeException runtime) throw runtime;
            if (injected instanceof Error error) throw error;
            server.advancements.clear();
            trees.forEach((treeId, tree) -> tree.getAsJsonObject("nodes").entrySet().forEach(entry -> {
                JsonObject node = entry.getValue().getAsJsonObject();
                Set<String> criteria = new LinkedHashSet<>(node.getAsJsonObject("criteria").keySet());
                if (!node.has("parent")) criteria.add("__resync_root");
                NamespacedKey key = new NamespacedKey("resync", treeId + "/" + entry.getKey());
                Advancement advancement = (Advancement) Proxy.newProxyInstance(Advancement.class.getClassLoader(),
                    new Class<?>[]{Advancement.class}, (proxy, method, arguments) -> switch (method.getName()) {
                        case "getKey" -> key;
                        case "getCriteria" -> List.copyOf(criteria);
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
                server.advancements.put(key, advancement);
            }));
        }
        @Override public void sync(Player target) {
            server.advancements.values().stream().filter(advancement -> advancement.getCriteria().contains("__resync_root"))
                .forEach(advancement -> target.getAdvancementProgress(advancement).awardCriteria("__resync_root"));
        }
    }
}
