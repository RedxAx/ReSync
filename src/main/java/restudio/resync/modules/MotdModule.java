package restudio.resync.modules;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.util.CachedServerIcon;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.customization.ResourceJson;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.text.ReTextService;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class MotdModule implements Module, Listener {
    private static final ModuleMetadata METADATA = ModuleMetadata.of("motd", "MOTDs");
    private ModuleContext context;
    private ReSyncJsonResourceStorage storage;
    private ReTextService text;
    private final Map<String, CachedServerIcon> iconCache = new ConcurrentHashMap<>();

    @Override
    public ModuleMetadata getMetadata() {
        return METADATA;
    }

    @Override
    public void initialize(ModuleContext context) {
        this.context = context;
        storage = context.getRequiredService(ReSyncJsonResourceStorage.class);
        text = context.getRequiredService(ReTextService.class);
        context.registerService(MotdModule.class, this);
    }

    @Override
    public void start(ModuleContext context) {
        Bukkit.getPluginManager().registerEvents(this, context.getPlugin());
    }

    @Override
    public void stop(ModuleContext context) {
        HandlerList.unregisterAll(this);
    }

    @EventHandler
    public void onPing(ServerListPingEvent event) {
        JsonObject profile = selectProfile();
        if (profile == null) {
            return;
        }
        String line1 = text.renderLegacy(motdLine(profile, "line1"), null, null);
        String line2 = text.renderLegacy(motdLine(profile, "line2"), null, null);
        event.setMotd(line2.isBlank() ? line1 : line1 + "\n" + line2);
        if (event instanceof PaperServerListPingEvent paperEvent) {
            applyPaperOptions(paperEvent, profile);
        }
        String mode = ResourceJson.string(profile, "playerCountMode", "real").toLowerCase(Locale.ROOT);
        if ("fixed".equals(mode) && profile.has("maxPlayers")) {
            event.setMaxPlayers(ResourceJson.integer(profile, "maxPlayers", event.getMaxPlayers()));
        }
    }

    private void applyPaperOptions(PaperServerListPingEvent event, JsonObject profile) {
        String mode = ResourceJson.string(profile, "playerCountMode", "real").toLowerCase(Locale.ROOT);
        if ("hidden".equals(mode)) {
            event.setHidePlayers(true);
        } else if ("fixed".equals(mode)) {
            event.setNumPlayers(ResourceJson.integer(profile, "onlinePlayers", event.getNumPlayers()));
        }
        applyIcon(event, profile);
    }

    private void applyIcon(PaperServerListPingEvent event, JsonObject profile) {
        CachedServerIcon inlineIcon = inlineIcon(profile);
        if (inlineIcon != null) {
            event.setServerIcon(inlineIcon);
            return;
        }
        String iconPath = ResourceJson.string(profile, "icon", "");
        if (iconPath.isBlank()) {
            return;
        }
        File iconFile = new File(iconPath);
        if (!iconFile.isAbsolute()) {
            iconFile = new File(context.getPlugin().getDataFolder(), iconPath);
        }
        if (!iconFile.isFile()) {
            return;
        }
        try {
            CachedServerIcon icon = fileIcon(iconFile);
            if (icon == null) {
                return;
            }
            event.setServerIcon(icon);
        } catch (Exception ignored) {
        }
    }

    private CachedServerIcon fileIcon(File iconFile) {
        String key = iconFile.getAbsolutePath() + ":" + iconFile.lastModified() + ":" + iconFile.length();
        CachedServerIcon cached = iconCache.get(key);
        if (cached != null) {
            return cached;
        }
        if (!isValidIconFile(iconFile)) {
            return null;
        }
        try {
            CachedServerIcon icon = Bukkit.loadServerIcon(iconFile);
            iconCache.put(key, icon);
            return icon;
        } catch (Exception e) {
            return null;
        }
    }

    private CachedServerIcon inlineIcon(JsonObject profile) {
        String data = ResourceJson.string(profile, "iconData", "");
        if (data.isBlank()) {
            return null;
        }
        String hash = ResourceJson.string(profile, "iconHash", "");
        String key = hash.isBlank() ? data : hash;
        return iconCache.computeIfAbsent(key, ignored -> {
            try {
                byte[] bytes = Base64.getDecoder().decode(stripImageDataPrefix(data));
                BufferedImage image = validPngIcon(bytes);
                return image != null ? Bukkit.loadServerIcon(image) : null;
            } catch (Exception e) {
                return null;
            }
        });
    }

    private boolean isValidIconFile(File file) {
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            return validPngIcon(bytes) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private BufferedImage validPngIcon(byte[] bytes) {
        if (!hasPngSignature(bytes)) {
            return null;
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            return image != null && image.getWidth() == 64 && image.getHeight() == 64 ? image : null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean hasPngSignature(byte[] bytes) {
        return bytes != null && bytes.length >= 8
            && bytes[0] == (byte) 0x89
            && bytes[1] == 0x50
            && bytes[2] == 0x4E
            && bytes[3] == 0x47
            && bytes[4] == 0x0D
            && bytes[5] == 0x0A
            && bytes[6] == 0x1A
            && bytes[7] == 0x0A;
    }

    private String stripImageDataPrefix(String data) {
        int comma = data.indexOf(',');
        return data.startsWith("data:image/") && comma >= 0 ? data.substring(comma + 1) : data;
    }

    private String motdLine(JsonObject profile, String key) {
        String[] lines = ResourceJson.string(profile, key, "").split("\\R", -1);
        return lines.length > 0 ? lines[0] : "";
    }

    private JsonObject selectProfile() {
        List<JsonObject> matches = storage.listIds(ReSyncResourceCatalog.MOTD_PROFILE).stream()
            .map(id -> storage.get(ReSyncResourceCatalog.MOTD_PROFILE, id))
            .filter(Objects::nonNull)
            .filter(profile -> ResourceJson.bool(profile, "enabled", true))
            .sorted(Comparator.comparingInt((JsonObject profile) -> ResourceJson.integer(profile, "priority", 0)).reversed())
            .toList();
        if (matches.isEmpty()) {
            return null;
        }
        return matches.getFirst();
    }
}
