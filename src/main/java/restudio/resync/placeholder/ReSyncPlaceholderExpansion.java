package restudio.resync.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import restudio.resync.ReSync;
import restudio.resync.flow.util.ReSyncPlaceholderUtil;

public class ReSyncPlaceholderExpansion extends PlaceholderExpansion {
    private final ReSync plugin;

    public ReSyncPlaceholderExpansion(ReSync plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "resync";
    }

    @Override
    public String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (params == null) {
            return "";
        }
        if (params.isBlank()) {
            return "";
        }
        Player onlinePlayer = player != null ? player.getPlayer() : null;
        String template = "%resync_" + params + "%";
        String resolved = ReSyncPlaceholderUtil.apply(onlinePlayer, template, false);
        return template.equals(resolved) ? "" : resolved;
    }
}
