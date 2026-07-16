package restudio.resync.network.paper.state;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import restudio.resync.Log;
import restudio.resync.ReSync;
import restudio.resync.network.NetworkPayloads;
import restudio.resync.network.PlayerStateSnapshot;
import restudio.resync.network.PlayerLease;
import restudio.resync.network.PlayerTransfer;
import restudio.resync.network.paper.ReSyncNetworkAgent;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

public class NetworkPlayerStateCoordinator implements ReSyncNetworkAgent.TransferHandler, Listener {
    private final ReSync plugin;
    private final NetworkPlayerStateConfig config;
    private final Set<UUID> frozenPlayers = ConcurrentHashMap.newKeySet();
    private final Set<String> sourceTransfers = ConcurrentHashMap.newKeySet();
    private final Map<String, NetworkPlayerStateCodec.Captured> sourceStates = new ConcurrentHashMap<>();
    private final Map<String, NetworkPlayerStateCodec.Captured> targetStates = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerLease> ownership = new ConcurrentHashMap<>();
    private final NetworkSnapshotOutbox outbox;
    private final AtomicBoolean replayingOutbox = new AtomicBoolean();

    public NetworkPlayerStateCoordinator(ReSync plugin, NetworkPlayerStateConfig config) {
        this.plugin = plugin;
        this.config = config;
        if (!config.enabled()) {
            throw new IllegalArgumentException("Network Player State Coordinator Requires An Enabled Profile");
        }
        outbox = new NetworkSnapshotOutbox(Path.of(plugin.getDataFolder().getPath(), "network", "snapshot-outbox"));
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void shutdown() {
        HandlerList.unregisterAll(this);
        frozenPlayers.clear();
        sourceStates.clear();
        targetStates.clear();
        sourceTransfers.clear();
        ownership.clear();
    }

    @Override
    public CompletionStage<PlayerStateSnapshot> capture(PlayerTransfer transfer) {
        return onPlayer(transfer.playerId(), player -> {
            frozenPlayers.add(player.getUniqueId());
            sourceTransfers.add(transfer.transferId());
            player.closeInventory();
            NetworkPlayerStateCodec.Captured captured = NetworkPlayerStateCodec.capture(player, config);
            sourceStates.put(transfer.transferId(), captured);
            return captured;
        }).thenApplyAsync(captured -> {
            byte[] payload = NetworkPlayerStateCodec.encode(captured);
            long now = Instant.now().toEpochMilli();
            return new PlayerStateSnapshot(UUID.randomUUID().toString(), transfer.networkId(), transfer.playerId(), transfer.fenceEpoch(), config.family(), payload, NetworkPayloads.sha256(payload), NetworkPlayerStateCodec.SCHEMA_VERSION, captured.data().dataVersion(), transfer.sourceNodeId(), now, false);
        });
    }

    @Override
    public CompletionStage<Void> prepare(PlayerTransfer transfer, PlayerStateSnapshot snapshot) {
        return CompletableFuture.runAsync(() -> {
            if (!snapshot.family().equals(config.family()) || snapshot.schemaVersion() < 1 || snapshot.schemaVersion() > NetworkPlayerStateCodec.SCHEMA_VERSION || snapshot.dataVersion() > Bukkit.getUnsafe().getDataVersion()) {
                throw new IllegalArgumentException("Network Player State Is Not Compatible With The Target Realm");
            }
            NetworkPlayerStateCodec.Captured captured = NetworkPlayerStateCodec.decode(snapshot.payload());
            NetworkPlayerStateCodec.validate(captured, config);
            targetStates.put(transfer.transferId(), captured);
            frozenPlayers.add(transfer.playerId());
        });
    }

    @Override
    public CompletionStage<Void> apply(PlayerTransfer transfer, PlayerStateSnapshot snapshot) {
        NetworkPlayerStateCodec.Captured captured = targetStates.get(transfer.transferId());
        if (captured == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Prepared Network Player State Is Missing"));
        }
        return applyState(transfer.playerId(), captured);
    }

    @Override
    public void committed(PlayerTransfer transfer) {
        cleanup(transfer);
    }

    @Override
    public void recovering(PlayerTransfer transfer, boolean source) {
        frozenPlayers.add(transfer.playerId());
        if (source) {
            sourceTransfers.add(transfer.transferId());
        }
    }

    @Override
    public void ownershipChanged(PlayerLease lease) {
        ownership.put(lease.playerId(), lease);
    }

    @Override
    public void connected() {
        replayOutbox();
    }

    @Override
    public void aborted(PlayerTransfer transfer, PlayerStateSnapshot snapshot) {
        boolean sourceNode = sourceTransfers.contains(transfer.transferId());
        NetworkPlayerStateCodec.Captured source = sourceNode ? sourceStates.get(transfer.transferId()) : null;
        if (sourceNode && source == null && snapshot != null && snapshot.family().equals(config.family())) {
            try {
                source = NetworkPlayerStateCodec.decode(snapshot.payload());
                NetworkPlayerStateCodec.validate(source, config);
            } catch (RuntimeException exception) {
                Log.warn("ReSync could not decode aborted player transfer " + transfer.transferId() + ": " + rootMessage(exception));
            }
        }
        if (source != null) {
            applyState(transfer.playerId(), source).whenComplete((unused, throwable) -> {
                if (throwable != null) {
                    Log.warn("ReSync could not restore aborted player transfer " + transfer.transferId() + ": " + rootMessage(throwable));
                }
                cleanup(transfer);
            });
            return;
        }
        cleanup(transfer);
    }

    private CompletionStage<Void> applyState(UUID playerId, NetworkPlayerStateCodec.Captured captured) {
        return onPlayer(playerId, player -> new AppliedPlayer(player, NetworkPlayerStateCodec.apply(player, captured, config))).thenCompose(applied -> {
            Location destination = applied.destination();
            if (destination == null) {
                return CompletableFuture.completedFuture(null);
            }
            return applied.player().teleportAsync(destination).thenCompose(success -> success ? CompletableFuture.completedFuture(null) : CompletableFuture.failedFuture(new IllegalStateException("Network Player Location Could Not Be Restored")));
        });
    }

    private void cleanup(PlayerTransfer transfer) {
        frozenPlayers.remove(transfer.playerId());
        sourceStates.remove(transfer.transferId());
        targetStates.remove(transfer.transferId());
        sourceTransfers.remove(transfer.transferId());
    }

    private <T> CompletableFuture<T> onPlayer(UUID playerId, Function<Player, T> operation) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Network Player Is Not Online On This Server"));
        }
        boolean scheduled = player.getScheduler().execute(plugin, () -> {
            try {
                if (!player.isOnline()) {
                    throw new IllegalStateException("Network Player Left Before State Processing");
                }
                future.complete(operation.apply(player));
            } catch (RuntimeException exception) {
                future.completeExceptionally(exception);
            }
        }, () -> future.completeExceptionally(new IllegalStateException("Network Player Left Before State Processing")), 1);
        if (!scheduled) {
            future.completeExceptionally(new IllegalStateException("Network Player Scheduler Is Retired"));
        }
        return future;
    }

    private boolean frozen(Player player) {
        return player != null && frozenPlayers.contains(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMove(PlayerMoveEvent event) {
        if (event.getClass() == PlayerMoveEvent.class && frozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && frozen(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && frozen(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (frozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && frozen(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (frozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (frozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHeldItem(PlayerItemHeldEvent event) {
        if (frozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (frozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && frozen(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFood(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && frozen(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (frozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        String playerName = player.getName();
        if (frozen(player)) {
            return;
        }
        PlayerLease lease = ownership.get(player.getUniqueId());
        if (lease == null || !lease.pendingNodeId().isBlank()) {
            return;
        }
        NetworkPlayerStateCodec.Captured captured;
        try {
            captured = NetworkPlayerStateCodec.capture(player, config);
        } catch (RuntimeException exception) {
            Log.warn("ReSync could not capture disconnect state for " + playerName + ": " + rootMessage(exception));
            return;
        }
        CompletableFuture.supplyAsync(() -> NetworkPlayerStateCodec.encode(captured)).thenCompose(payload -> {
            long now = Instant.now().toEpochMilli();
            PlayerStateSnapshot snapshot = new PlayerStateSnapshot(UUID.randomUUID().toString(), lease.networkId(), lease.playerId(), lease.fenceEpoch(), config.family(), payload, NetworkPayloads.sha256(payload), NetworkPlayerStateCodec.SCHEMA_VERSION, captured.data().dataVersion(), lease.ownerNodeId(), now, false);
            outbox.save(snapshot);
            ReSyncNetworkAgent agent = plugin.getNetworkAgent();
            if (agent == null || !agent.connected()) {
                return CompletableFuture.completedFuture(lease);
            }
            return agent.saveOwnerSnapshot(snapshot).thenApply(saved -> {
                outbox.remove(snapshot.snapshotId());
                return saved;
            });
        }).whenComplete((saved, throwable) -> {
            if (throwable != null) {
                Log.warn("ReSync could not save disconnect state for " + playerName + ": " + rootMessage(throwable));
            }
        });
    }

    private void replayOutbox() {
        if (!replayingOutbox.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.supplyAsync(outbox::load).thenCompose(snapshots -> {
            CompletableFuture<Void> replay = CompletableFuture.completedFuture(null);
            for (PlayerStateSnapshot snapshot : snapshots) {
                replay = replay.thenCompose(ignored -> replaySnapshot(snapshot));
            }
            return replay;
        }).whenComplete((unused, throwable) -> {
            replayingOutbox.set(false);
            if (throwable != null) {
                Log.warn("ReSync could not replay disconnect snapshots: " + rootMessage(throwable));
            }
        });
    }

    private CompletableFuture<Void> replaySnapshot(PlayerStateSnapshot snapshot) {
        ReSyncNetworkAgent agent = plugin.getNetworkAgent();
        if (agent == null || !agent.connected()) {
            return CompletableFuture.completedFuture(null);
        }
        return agent.saveOwnerSnapshot(snapshot).thenAccept(saved -> outbox.remove(snapshot.snapshotId())).exceptionally(throwable -> {
            Log.warn("ReSync could not replay disconnect snapshot " + snapshot.snapshotId() + ": " + rootMessage(throwable));
            return null;
        });
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record AppliedPlayer(Player player, Location destination) {
    }
}
