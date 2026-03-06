package restudio.resync.flow;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import restudio.resync.flow.nodes.ScoreboardNodes;

public class ScoreboardRuntimeListener implements Listener {
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (event.getPlayer() != null) {
            ScoreboardNodes.applyDefaultOnJoin(event.getPlayer());
            TabListService.applyDefaultOnJoin(event.getPlayer());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (event.getPlayer() != null) {
            ScoreboardNodes.clearTrackedPlayer(event.getPlayer());
            TabListService.clearTrackedPlayer(event.getPlayer());
        }
    }
}
