package restudio.resync.flow.handler.generic;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.world.WorldExternalPersistenceCapability;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbilityEffectHandlerTemporaryBlockTest {
    private World world;
    private Plugin plugin;
    private Block block;
    private WorldExternalPersistenceCapability capability;
    private AbilityEffectHandler handler;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        world = MockBukkit.getMock().addSimpleWorld("temporary-block-world");
        block = world.getBlockAt(2, 64, 4);
        block.setType(Material.STONE);
        capability = WorldExternalPersistenceCapability.unavailable();
        handler = new AbilityEffectHandler(null, () -> capability);
    }

    @AfterEach
    void tearDown() {
        handler.shutdown();
        MockBukkit.unmock();
    }

    @Test
    void expiryRestoresBlockPropertiesInventoryAndPersistentTileState() {
        block.setType(Material.CHEST);
        Chest chest = (Chest) block.getState();
        BlockData data = chest.getBlockData();
        ((Directional) data).setFacing(BlockFace.WEST);
        ((Waterlogged) data).setWaterlogged(true);
        chest.setBlockData(data);
        chest.getSnapshotInventory().setItem(0, new ItemStack(Material.DIAMOND, 7));
        NamespacedKey key = new NamespacedKey("resync", "temporary-test");
        chest.getPersistentDataContainer().set(key, PersistentDataType.STRING, "retained");
        assertTrue(chest.update(true, false));
        String originalData = block.getBlockData().getAsString();

        Context context = place(Material.ICE, 3);
        assertEquals("flow", context.branch);
        assertEquals(Material.ICE, block.getType());
        MockBukkit.getMock().getScheduler().performTicks(3);

        Chest restored = (Chest) block.getState();
        assertEquals(originalData, block.getBlockData().getAsString());
        assertEquals(new ItemStack(Material.DIAMOND, 7), restored.getBlockInventory().getItem(0));
        assertEquals("retained", restored.getPersistentDataContainer().get(key, PersistentDataType.STRING));
        assertTrue(context.recovery().isDone());
        assertFalse(context.recovery().isCompletedExceptionally());
        assertEquals(0, capability.activeOperationCount());
    }

    @Test
    void overlappingPlacementsKeepTheOriginalAndFenceOlderExpiry() {
        Context first = place(Material.ICE, 3);
        Context second = place(Material.GLASS, 8);

        MockBukkit.getMock().getScheduler().performTicks(3);
        assertEquals(Material.GLASS, block.getType());
        assertTrue(first.recovery().isDone());
        assertFalse(second.recovery().isDone());
        assertEquals(1, capability.activeOperationCount());

        MockBukkit.getMock().getScheduler().performTicks(5);
        assertEquals(Material.STONE, block.getType());
        assertTrue(second.recovery().isDone());
        assertEquals(0, capability.activeOperationCount());
    }

    @Test
    void interveningBlockPropertiesAreNeverOverwritten() {
        Context context = place(Material.OAK_STAIRS, 3);
        BlockData changed = block.getBlockData();
        ((Directional) changed).setFacing(BlockFace.WEST);
        block.setBlockData(changed, false);
        String changedData = block.getBlockData().getAsString();

        MockBukkit.getMock().getScheduler().performTicks(3);

        assertEquals(changedData, block.getBlockData().getAsString());
        assertEquals(Material.OAK_STAIRS, block.getType());
        assertTrue(context.recovery().isDone());
        assertEquals(0, capability.activeOperationCount());
    }

    @Test
    void cancellingTheQueuedTimerRestoresImmediatelyOnTheServerThread() {
        Context context = place(Material.ICE, 100);

        context.timers.getFirst().cancel(false);

        assertEquals(Material.STONE, block.getType());
        assertTrue(context.recovery().isDone());
        assertEquals(0, capability.activeOperationCount());
    }

    @Test
    void asynchronousCancellationRetainsRecoveryUntilTheServerThreadRuns() throws InterruptedException {
        Context context = place(Material.ICE, 100);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread cancelling = new Thread(() -> {
            try {
                context.timers.getFirst().cancel(false);
            } catch (Throwable exception) {
                failure.set(exception);
            }
        });

        cancelling.start();
        cancelling.join(2000);
        assertFalse(cancelling.isAlive());
        assertEquals(null, failure.get());
        assertEquals(Material.ICE, block.getType());
        assertFalse(context.recovery().isDone());
        assertEquals(1, capability.activeOperationCount());

        MockBukkit.getMock().getScheduler().performOneTick();

        assertEquals(Material.STONE, block.getType());
        assertTrue(context.recovery().isDone());
        assertEquals(0, capability.activeOperationCount());
    }

    @Test
    void shutdownRestoresOwnedBlocksAndPreventsNewPlacements() {
        Context context = place(Material.ICE, 100);

        handler.shutdown();

        assertEquals(Material.STONE, block.getType());
        assertTrue(context.recovery().isDone());
        assertEquals(0, capability.activeOperationCount());
        assertThrows(IllegalStateException.class, () -> place(Material.GLASS, 3));
        MockBukkit.getMock().getScheduler().performTicks(100);
        assertEquals(Material.STONE, block.getType());
    }

    @Test
    void unloadingTheWorldRestoresItsBlocksBeforeOwnershipEnds() {
        Context context = place(Material.ICE, 100);
        WorldUnloadEvent event = new WorldUnloadEvent(world);

        handler.onWorldUnload(event);

        assertEquals(Material.STONE, block.getType());
        assertTrue(context.recovery().isDone());
        assertEquals(0, capability.activeOperationCount());
        assertFalse(event.isCancelled());
    }

    @Test
    void unavailableOwnershipAndRejectedSchedulingLeaveTheWorldUntouched() {
        AbilityEffectHandler unavailable = new AbilityEffectHandler(null, () -> null);
        Context context = context(Material.ICE, 3);
        assertThrows(IllegalStateException.class, () -> unavailable.execute(context, node()));
        assertEquals(Material.STONE, block.getType());

        context.schedulingRejected = true;
        assertThrows(IllegalStateException.class, () -> handler.execute(context, node()));
        assertEquals(Material.STONE, block.getType());
        assertEquals(0, capability.activeOperationCount());
        assertTrue(context.recovery().isCompletedExceptionally());
    }

    @Test
    void schedulingErrorsRestoreTheWorldAndSettleCompensation() {
        Context context = context(Material.ICE, 3);
        context.schedulingError = new AssertionError("Scheduler failed");

        AssertionError failure = assertThrows(AssertionError.class, () -> handler.execute(context, node()));

        assertEquals(context.schedulingError, failure);
        assertEquals(Material.STONE, block.getType());
        assertEquals(0, capability.activeOperationCount());
        assertTrue(context.recovery().isCompletedExceptionally());
    }

    private Context place(Material material, int duration) {
        Context context = context(material, duration);
        handler.execute(context, node());
        return context;
    }

    private Context context(Material material, int duration) {
        return new Context(Map.of("location", new Location(world, 2, 64, 4), "material", material.name(), "duration_ticks", duration));
    }

    private FlowNode node() {
        FlowNode node = new FlowNode("ability_temporary_block", 0, 0, Map.of());
        node.setHandlerConfig(Map.of("operation", "temporary_block"));
        return node;
    }

    private final class Context extends FlowContext {
        private final Map<String, Object> inputs;
        private final List<CompletableFuture<Void>> timers = new ArrayList<>();
        private CompletableFuture<Void> recovery;
        private String branch;
        private boolean schedulingRejected;
        private Error schedulingError;

        private Context(Map<String, Object> inputs) {
            super(null, null, null);
            this.inputs = inputs;
        }

        @Override
        public <T> T getInputValue(FlowNode node, String name, Class<T> type, T fallback) {
            Object value = inputs.get(name);
            return value == null ? fallback : type.cast(value);
        }

        @Override
        public CompletableFuture<Void> trackOperation(CompletableFuture<Void> operation) {
            if (recovery == null) recovery = operation;
            return super.trackOperation(operation);
        }

        private CompletableFuture<Void> recovery() {
            return recovery;
        }

        @Override
        public void triggerOutput(String name) {
            branch = name;
        }

        @Override
        public CompletableFuture<Void> runLater(Runnable action, long delayTicks) {
            if (schedulingRejected) throw new IllegalStateException("Scheduling was rejected");
            if (schedulingError != null) throw schedulingError;
            CompletableFuture<Void> completion = scheduled(action, delayTicks);
            timers.add(completion);
            return completion;
        }

        @Override
        public CompletableFuture<Void> runSync(Runnable action) {
            return scheduled(action, 1);
        }

        private CompletableFuture<Void> scheduled(Runnable action, long delayTicks) {
            CompletableFuture<Void> completion = super.trackOperation(new CompletableFuture<>());
            BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (completion.isDone()) return;
                try {
                    action.run();
                    completion.complete(null);
                } catch (Throwable failure) {
                    completion.completeExceptionally(failure);
                }
            }, delayTicks);
            completion.whenComplete((ignored, failure) -> {
                if (completion.isCancelled()) task.cancel();
            });
            return completion;
        }
    }
}
