package restudio.resync.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import restudio.resync.ReSync;
import restudio.resync.flow.util.ReSyncPlaceholderUtil;
import restudio.resync.text.ReTextService;

import java.util.Locale;

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
        String animationTemplateId = animationTemplateId(params);
        if (!animationTemplateId.isBlank()) {
            ReTextService text = plugin.getReSyncServer().getModuleContext().getService(ReTextService.class);
            if (text == null) {
                return "";
            }
            ReTextService.ReTextTemplate template = text.template(animationTemplateId);
            if (template == null) {
                return "%resync_" + params + "%";
            }
            String frame = template.frame(onlinePlayer, onlinePlayer, System.currentTimeMillis());
            return ReSyncPlaceholderUtil.apply(onlinePlayer, frame, true);
        }
        String template = "%resync_" + params + "%";
        String resolved = ReSyncPlaceholderUtil.apply(onlinePlayer, template, false);
        return template.equals(resolved) ? "" : resolved;
    }

    private String animationTemplateId(String params) {
        String normalized = params.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("animation:")) {
            return params.substring("animation:".length()).trim();
        }
        if (normalized.startsWith("animation_")) {
            return params.substring("animation_".length()).trim();
        }
        return "";
    }
}
