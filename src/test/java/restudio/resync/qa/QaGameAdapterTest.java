package restudio.resync.qa;

import com.google.gson.Gson;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.flow.data.CustomContentDefinition;
import restudio.resync.customcontent.CustomContentService;
import restudio.resync.customcontent.CustomContentStorage;
import restudio.resync.customcontent.ItemAttributeSchemaService;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.network.paper.PaperPlayerDataMutationAdmission;
import restudio.resync.runtime.PlayerNpcRuntime;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.CanonicalProjectMetadataFixture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QaGameAdapterTest {
    @TempDir Path temporary;
    private JavaPlugin plugin;
    private World world;
    private Player player;
    private CommandSender actor;
    private AssetPersistenceGate gate;
    private AssetTransactionCoordinator transactions;
    private CustomContentStorage storage;
    private CustomContentService content;
    private PaperPlayerDataMutationAdmission playerData;
    private PaperPlayerDataMutationAdmission.Installation installation;
    private QaGameAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        world = MockBukkit.getMock().addSimpleWorld("qa-game");
        world.loadChunk(0, 0);
        player = MockBukkit.getMock().addPlayer();
        player.teleport(new Location(world, 2, 64, 2));
        actor = MockBukkit.getMock().getConsoleSender();
        actor.addAttachment(plugin, "resync.qa", true);
        Path worldRoot = world.getWorldFolder().toPath().toAbsolutePath().normalize();
        Files.createDirectories(worldRoot.resolve("playerdata"));
        playerData = new PaperPlayerDataMutationAdmission(List.of(worldRoot), worldRoot.getParent());
        installation = PaperPlayerDataMutationAdmission.installShared(playerData);
        gate = new AssetPersistenceGate(temporary);
        transactions = AssetTransactionCoordinator.open(temporary.resolve("assets"), new Gson());
        CanonicalProjectMetadataFixture.seed(transactions);
        storage = new CustomContentStorage(plugin, temporary, new ItemAttributeSchemaService(),
            LegacyRuntimeActivationGate.runtime(temporary), gate, transactions);
        content = new CustomContentService(storage, null, null);
        save("qa-item", "item", "STICK");
        save("qa-block", "block", "GOLD_BLOCK");
        adapter = new QaGameAdapter(plugin, () -> storage, () -> content, () -> null,
            () -> PlayerNpcRuntime.disabled("No Packet Runtime In This Fixture"));
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (content != null) content.getVanillaProvider().quiescePersistence();
            if (gate != null) gate.quiesce();
            if (storage != null) storage.close();
            if (transactions != null) transactions.close();
            if (playerData != null) playerData.quiesce();
        } finally {
            if (installation != null) PaperPlayerDataMutationAdmission.clearSharedInstallation(installation);
            MockBukkit.unmock();
        }
    }

    @Test
    void actualCustomItemsAndPlacedBlocksRetainTheirIdentityUntilExplicitRemoval() {
        Map<String, Object> item = itemInput();
        adapter.invoke(actor, "custom.item.give", item).toCompletableFuture().join();
        assertEquals("qa-item", content.identifyItem(player.getInventory().getItem(0)));
        assertEquals(2, player.getInventory().getItem(0).getAmount());
        Map<String, Object> inspected = adapter.invoke(actor, "custom.item.inspect", item).toCompletableFuture().join();
        assertEquals("qa-item", ((Map<?, ?>) inspected.get("item")).get("contentId"));

        Map<String, Object> block = blockInput();
        adapter.invoke(actor, "custom.block.place", block).toCompletableFuture().join();
        assertEquals(Material.GOLD_BLOCK, world.getBlockAt(3, 64, 3).getType());
        assertEquals("qa-block", content.identifyBlock(new Location(world, 3, 64, 3)));
        assertTrue(content.getVanillaProvider().getPlacedBlocks().containsValue("qa-block"));

        adapter.invoke(actor, "custom.item.remove", item).toCompletableFuture().join();
        adapter.invoke(actor, "custom.block.remove", block).toCompletableFuture().join();
        assertTrue(player.getInventory().getItem(0) == null || player.getInventory().getItem(0).getType().isAir());
        assertTrue(world.getBlockAt(3, 64, 3).getType().isAir());
        assertNull(content.identifyBlock(new Location(world, 3, 64, 3)));
    }

    @Test
    void deniedConsoleAndOffThreadAdmissionCannotChangeTheFixture() {
        actor.addAttachment(plugin, "resync.qa", false);
        CompletionException denied = assertThrows(CompletionException.class,
            () -> adapter.invoke(actor, "custom.block.place", blockInput()).toCompletableFuture().join());
        assertTrue(denied.getCause() instanceof SecurityException);
        assertTrue(world.getBlockAt(3, 64, 3).getType().isAir());
        actor.addAttachment(plugin, "resync.qa", true);

        CompletionException offThread = assertThrows(CompletionException.class, () -> CompletableFuture.runAsync(() ->
            adapter.invoke(actor, "custom.block.place", blockInput()).toCompletableFuture().join()).join());
        assertTrue(offThread.getCause() instanceof SecurityException);
        assertTrue(world.getBlockAt(3, 64, 3).getType().isAir());
        assertNull(content.identifyBlock(new Location(world, 3, 64, 3)));
    }

    @Test
    void occupiedSlotsAndMismatchedCustomIdentitiesArePreserved() {
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 3));
        assertThrows(CompletionException.class,
            () -> adapter.invoke(actor, "custom.item.give", itemInput()).toCompletableFuture().join());
        assertThrows(CompletionException.class,
            () -> adapter.invoke(actor, "custom.item.remove", itemInput()).toCompletableFuture().join());
        assertEquals(Material.DIAMOND, player.getInventory().getItem(0).getType());
        assertEquals(3, player.getInventory().getItem(0).getAmount());
        world.getBlockAt(3, 64, 3).setType(Material.DIAMOND_BLOCK);
        assertThrows(CompletionException.class,
            () -> adapter.invoke(actor, "custom.block.place", blockInput()).toCompletableFuture().join());
        assertThrows(CompletionException.class,
            () -> adapter.invoke(actor, "custom.block.remove", blockInput()).toCompletableFuture().join());
        assertEquals(Material.DIAMOND_BLOCK, world.getBlockAt(3, 64, 3).getType());
        assertNull(content.identifyBlock(new Location(world, 3, 64, 3)));
    }

    @Test
    void quiescedOwnersRejectMutationsAndBlockPlacementRollsBackItsMaterial() throws Exception {
        content.getVanillaProvider().quiescePersistence();
        assertThrows(CompletionException.class,
            () -> adapter.invoke(actor, "custom.block.place", blockInput()).toCompletableFuture().join());
        assertTrue(world.getBlockAt(3, 64, 3).getType().isAir());
        assertNull(content.identifyBlock(new Location(world, 3, 64, 3)));
        playerData.quiesce();
        assertThrows(CompletionException.class,
            () -> adapter.invoke(actor, "custom.item.give", itemInput()).toCompletableFuture().join());
        assertTrue(player.getInventory().getItem(0) == null || player.getInventory().getItem(0).getType().isAir());
    }

    @Test
    void malformedCoordinatesCannotLoadOrChangeBlocks() {
        Map<String, Object> nonfinite = new LinkedHashMap<>(blockInput());
        nonfinite.put("x", Double.NaN);
        assertThrows(CompletionException.class,
            () -> adapter.invoke(actor, "custom.block.place", nonfinite).toCompletableFuture().join());
        Map<String, Object> fractional = new LinkedHashMap<>(blockInput());
        fractional.put("x", 3.5);
        assertThrows(CompletionException.class,
            () -> adapter.invoke(actor, "custom.block.place", fractional).toCompletableFuture().join());
        Map<String, Object> unloaded = new LinkedHashMap<>(blockInput());
        unloaded.put("x", 16000);
        assertThrows(CompletionException.class,
            () -> adapter.invoke(actor, "custom.block.place", unloaded).toCompletableFuture().join());
        assertTrue(world.getBlockAt(3, 64, 3).getType().isAir());
        assertTrue(content.getVanillaProvider().getPlacedBlocks().isEmpty());
    }

    private void save(String id, String type, String material) {
        CustomContentDefinition definition = new CustomContentDefinition();
        definition.setId(id);
        definition.setType(type);
        definition.setDisplayName(id);
        definition.setMaterial(material);
        storage.save(definition, UUID.randomUUID(), 0);
    }

    private Map<String, Object> itemInput() {
        return Map.of("contentId", "qa-item", "playerId", player.getUniqueId().toString(), "slot", 0, "amount", 2);
    }

    private Map<String, Object> blockInput() {
        return Map.of("contentId", "qa-block", "worldId", world.getUID().toString(), "x", 3, "y", 64, "z", 3);
    }
}
