package restudio.resync.selection;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import restudio.resync.ReSync;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InteractiveSelectionManager implements Listener {
    private final ReSync plugin;
    private final Map<UUID, InteractiveSelectionSession> sessions = new ConcurrentHashMap<>();
    private int taskId = -1;

    public InteractiveSelectionManager(ReSync plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::tickSessions, 10L, 10L);
    }

    public void shutdown() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
        for (UUID playerId : new ArrayList<>(sessions.keySet())) {
            endSession(playerId, "SelectionStopped", false);
        }
        sessions.clear();
        HandlerList.unregisterAll(this);
    }

    public boolean beginSession(InteractiveSelectionSession session) {
        if (session == null) {
            return false;
        }
        Player player = Bukkit.getPlayer(session.getPlayerId());
        if (player == null) {
            return false;
        }
        endSession(player.getUniqueId(), "SelectionReplaced", false);
        sessions.put(player.getUniqueId(), session);
        session.start(this, player);
        return true;
    }

    public boolean cancelSession(UUID playerId, String reason) {
        return endSession(playerId, reason, false);
    }

    public boolean completeSession(UUID playerId, String reason) {
        return endSession(playerId, reason, true);
    }

    public ReSync getPlugin() {
        return plugin;
    }

    public BossBar createBossBar(Player player, String title, BarColor color, double progress) {
        BossBar bossBar = Bukkit.createBossBar(title, color, BarStyle.SEGMENTED_10);
        bossBar.setProgress(clamp(progress));
        bossBar.addPlayer(player);
        return bossBar;
    }

    public void updateBossBar(BossBar bossBar, String title, BarColor color, double progress) {
        if (bossBar == null) {
            return;
        }
        bossBar.setTitle(title);
        bossBar.setColor(color);
        bossBar.setProgress(clamp(progress));
    }

    public void removeBossBar(BossBar bossBar) {
        if (bossBar != null) {
            bossBar.removeAll();
        }
    }

    public void sendAction(Player player, Component component) {
        if (player != null && component != null) {
            player.sendActionBar(component);
        }
    }

    public void pulseBlock(Block block, Color color) {
        if (block == null || block.getWorld() == null) {
            return;
        }
        Location location = block.getLocation().add(0.5, 0.5, 0.5);
        block.getWorld().spawnParticle(Particle.DUST, location, 8, 0.12, 0.12, 0.12, 0.0, new Particle.DustOptions(color, 1.2f));
    }

    public void pulseBox(Player player, String worldName, double minX, double minY, double minZ, double maxX, double maxY, double maxZ, Color color) {
        if (player == null || player.getWorld() == null || !player.getWorld().getName().equalsIgnoreCase(worldName)) {
            return;
        }
        double step = 0.5;
        Location origin = new Location(player.getWorld(), 0, 0, 0);
        for (double x = minX; x <= maxX + 0.001; x += step) {
            spawnDust(origin, x + 0.5, minY + 0.5, minZ + 0.5, color);
            spawnDust(origin, x + 0.5, minY + 0.5, maxZ + 0.5, color);
            spawnDust(origin, x + 0.5, maxY + 0.5, minZ + 0.5, color);
            spawnDust(origin, x + 0.5, maxY + 0.5, maxZ + 0.5, color);
        }
        for (double y = minY; y <= maxY + 0.001; y += step) {
            spawnDust(origin, minX + 0.5, y + 0.5, minZ + 0.5, color);
            spawnDust(origin, minX + 0.5, y + 0.5, maxZ + 0.5, color);
            spawnDust(origin, maxX + 0.5, y + 0.5, minZ + 0.5, color);
            spawnDust(origin, maxX + 0.5, y + 0.5, maxZ + 0.5, color);
        }
        for (double z = minZ; z <= maxZ + 0.001; z += step) {
            spawnDust(origin, minX + 0.5, minY + 0.5, z + 0.5, color);
            spawnDust(origin, minX + 0.5, maxY + 0.5, z + 0.5, color);
            spawnDust(origin, maxX + 0.5, minY + 0.5, z + 0.5, color);
            spawnDust(origin, maxX + 0.5, maxY + 0.5, z + 0.5, color);
        }
    }

    public void playStepSound(Player player, Sound sound, float pitch) {
        if (player != null && sound != null) {
            player.playSound(player.getLocation(), sound, SoundCategory.MASTER, 1.0f, pitch);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockBreak(BlockBreakEvent event) {
        InteractiveSelectionSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null) {
            event.setCancelled(true);
            session.handleBlockSelect(this, event.getPlayer(), event.getBlock());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        cancelSession(event.getPlayer().getUniqueId(), "PlayerLeft");
    }

    @EventHandler
    public void onPlayerKick(PlayerKickEvent event) {
        cancelSession(event.getPlayer().getUniqueId(), "PlayerLeft");
    }

    private void tickSessions() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, InteractiveSelectionSession> entry : List.copyOf(sessions.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) {
                endSession(entry.getKey(), "PlayerLeft", false);
                continue;
            }
            entry.getValue().tick(this, player, now);
        }
    }

    private boolean endSession(UUID playerId, String reason, boolean completed) {
        if (playerId == null) {
            return false;
        }
        InteractiveSelectionSession session = sessions.remove(playerId);
        if (session == null) {
            return false;
        }
        Player player = Bukkit.getPlayer(playerId);
        session.stop(this, player, reason, completed);
        return true;
    }

    private void spawnDust(Location origin, double x, double y, double z, Color color) {
        if (origin.getWorld() == null) {
            return;
        }
        origin.getWorld().spawnParticle(Particle.DUST, x, y, z, 1, 0.0, 0.0, 0.0, 0.0, new Particle.DustOptions(color, 0.9f));
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
