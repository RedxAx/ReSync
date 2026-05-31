package restudio.resync.text;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.flow.util.ReSyncPlaceholderUtil;
import restudio.resync.resources.ReSyncResourceCatalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ReTextService {
    private static final Pattern ANIMATION_PATTERN = Pattern.compile("%resync_animation:([^%]+)%", Pattern.CASE_INSENSITIVE);
    private static final Pattern MINIMESSAGE_PATTERN = Pattern.compile("<[^>]+>");
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final LegacyComponentSerializer legacySerializer = LegacyComponentSerializer.builder()
        .character('&')
        .hexColors()
        .useUnusualXRepeatedCharacterHexFormat()
        .build();
    private final LegacyComponentSerializer sectionSerializer = LegacyComponentSerializer.legacySection();
    private final ReSyncJsonResourceStorage storage;
    private final Map<String, ReTextTemplate> templateCache = new ConcurrentHashMap<>();

    public ReTextService(ReSyncJsonResourceStorage storage) {
        this.storage = storage;
    }

    public Component render(String input, Player subject, Player viewer) {
        String text = renderPlain(input, subject, viewer, System.currentTimeMillis());
        return parse(text, false);
    }

    public Component render(String input, Player subject, Player viewer, TagResolver... resolvers) {
        String text = renderPlain(input, subject, viewer, System.currentTimeMillis());
        return parse(text, false, resolvers);
    }

    public Component renderStrict(String input, Player subject, Player viewer) {
        String text = renderPlain(input, subject, viewer, System.currentTimeMillis());
        return parse(text, true);
    }

    public String renderLegacy(String input, Player subject, Player viewer) {
        return sectionSerializer.serialize(render(input, subject, viewer));
    }

    public String legacy(Component component) {
        return component == null ? "" : sectionSerializer.serialize(component);
    }

    public String renderPlain(String input, Player subject, Player viewer, long timeMillis) {
        String text = normalize(input);
        text = applyTemplate(text, subject, viewer, timeMillis);
        text = ReSyncPlaceholderUtil.apply(subject, text, true);
        text = applyLuckPermsMeta(subject, text);
        text = applyAnimations(text, subject, viewer, timeMillis);
        return ReSyncPlaceholderUtil.apply(viewer != null ? viewer : subject, text, true);
    }

    public Component parse(String input, boolean strict) {
        return parse(input, strict, new TagResolver[0]);
    }

    public Component parse(String input, boolean strict, TagResolver... resolvers) {
        String text = normalize(input);
        if (MINIMESSAGE_PATTERN.matcher(text).find()) {
            if (strict) {
                return miniMessage.deserialize(text, resolvers);
            }
            try {
                return miniMessage.deserialize(text, resolvers);
            } catch (Exception ignored) {
            }
        }
        return legacySerializer.deserialize(text);
    }

    public String escapeMiniMessage(String input) {
        return miniMessage.escapeTags(input == null ? "" : input);
    }

    public List<String> timeline(String templateId, Player subject, Player viewer, int frames, long frameMillis) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < Math.max(1, frames); i++) {
            result.add(renderPlain("%resync_animation:" + templateId + "%", subject, viewer, i * Math.max(1L, frameMillis)));
        }
        return result;
    }

    public ReTextTemplate template(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        ReTextTemplate cached = templateCache.get(id);
        if (cached != null) {
            return cached;
        }
        JsonObject json = storage.get(ReSyncResourceCatalog.TEXT_TEMPLATE, id);
        ReTextTemplate template = ReTextTemplate.fromJson(json);
        if (template != null) {
            templateCache.put(id, template);
        }
        return template;
    }

    public void clearTemplateCache() {
        templateCache.clear();
    }

    private String applyTemplate(String input, Player subject, Player viewer, long timeMillis) {
        if (!input.startsWith("@")) {
            return input;
        }
        String id = input.substring(1);
        ReTextTemplate template = template(id);
        return template != null ? template.frame(subject, viewer, timeMillis) : input;
    }

    private String applyAnimations(String input, Player subject, Player viewer, long timeMillis) {
        Matcher matcher = ANIMATION_PATTERN.matcher(input);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            ReTextTemplate template = template(matcher.group(1));
            String replacement = template != null ? template.frame(subject, viewer, timeMillis) : "";
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private String applyLuckPermsMeta(Player player, String input) {
        if (player == null || input == null || input.isBlank() || !Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
            return input;
        }
        if (!input.toLowerCase(Locale.ROOT).contains("%luckperms_")) {
            return input;
        }
        try {
            Class<?> luckPermsClass = Class.forName("net.luckperms.api.LuckPerms");
            RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration(luckPermsClass);
            Object luckPerms = registration != null ? registration.getProvider() : null;
            Object userManager = luckPerms != null ? luckPerms.getClass().getMethod("getUserManager").invoke(luckPerms) : null;
            Object user = userManager != null ? userManager.getClass().getMethod("getUser", UUID.class).invoke(userManager, player.getUniqueId()) : null;
            if (user == null) {
                return input;
            }
            Object cachedData = user.getClass().getMethod("getCachedData").invoke(user);
            Object metaData = cachedData.getClass().getMethod("getMetaData").invoke(cachedData);
            String prefix = string(metaData.getClass().getMethod("getPrefix").invoke(metaData));
            String suffix = string(metaData.getClass().getMethod("getSuffix").invoke(metaData));
            String primaryGroup = string(user.getClass().getMethod("getPrimaryGroup").invoke(user));
            return input
                .replace("%luckperms_prefix%", prefix != null ? prefix : "")
                .replace("%luckperms_suffix%", suffix != null ? suffix : "")
                .replace("%luckperms_primary_group%", primaryGroup != null ? primaryGroup : "");
        } catch (Exception exception) {
            return input;
        }
    }

    private String normalize(String input) {
        return input == null ? "" : input.replace('§', '&');
    }

    private String string(Object value) {
        return value != null ? value.toString() : "";
    }

    public static class ReTextTemplate {
        private final String id;
        private final List<String> frames;
        private final long frameMillis;
        private final String mode;
        private final int width;
        private final int visibleCharacters;
        private final List<String> colors;
        private final String text;

        public ReTextTemplate(String id, List<String> frames, long frameMillis, String mode) {
            this(id, frames, frameMillis, mode, 16, 0, List.of(), frames == null || frames.isEmpty() ? "" : frames.getFirst());
        }

        public ReTextTemplate(String id, List<String> frames, long frameMillis, String mode, int width, int visibleCharacters, List<String> colors, String text) {
            this.id = id;
            this.frames = frames == null || frames.isEmpty() ? List.of("") : List.copyOf(frames);
            this.frameMillis = Math.max(1L, frameMillis);
            this.mode = mode == null || mode.isBlank() ? "frames" : mode;
            this.width = Math.max(1, width);
            this.visibleCharacters = Math.max(0, visibleCharacters);
            this.colors = colors == null ? List.of() : List.copyOf(colors);
            this.text = text == null ? "" : text;
        }

        public String id() {
            return id;
        }

        public String frame(Player subject, Player viewer, long timeMillis) {
            String normalizedMode = mode.toLowerCase(Locale.ROOT);
            return switch (normalizedMode) {
                case "random", "choice" -> randomFrame(subject, timeMillis);
                case "blink" -> blinkFrame(timeMillis);
                case "typing" -> typingFrame(timeMillis);
                case "scroll", "scrolling" -> scrollingFrame(timeMillis);
                case "gradient" -> gradientFrame(timeMillis);
                case "conditional" -> conditionalFrame(subject, viewer, timeMillis);
                default -> sequenceFrame(timeMillis);
            };
        }

        private String sequenceFrame(long timeMillis) {
            int index = (int) Math.floorMod(timeMillis / frameMillis, frames.size());
            return frames.get(index);
        }

        private String randomFrame(Player subject, long timeMillis) {
            int seed = subject != null ? subject.getUniqueId().hashCode() : Long.hashCode(timeMillis / frameMillis);
            return frames.get(Math.floorMod(seed, frames.size()));
        }

        private String blinkFrame(long timeMillis) {
            return Math.floorMod(timeMillis / frameMillis, 2) == 0 ? frames.getFirst() : "";
        }

        private String typingFrame(long timeMillis) {
            String value = !text.isBlank() ? text : frames.getFirst();
            int length = value.length();
            int visible = (int) Math.floorMod(timeMillis / frameMillis, length + 1);
            int maxVisible = visibleCharacters > 0 ? Math.min(visibleCharacters, visible) : visible;
            return value.substring(0, Math.min(length, maxVisible));
        }

        private String scrollingFrame(long timeMillis) {
            String value = !text.isBlank() ? text : frames.getFirst();
            String padded = value + " ".repeat(width);
            int start = (int) Math.floorMod(timeMillis / frameMillis, padded.length());
            String doubled = padded + padded;
            return doubled.substring(start, Math.min(start + width, doubled.length()));
        }

        private String gradientFrame(long timeMillis) {
            String value = !text.isBlank() ? text : frames.getFirst();
            if (colors.size() < 2) {
                return value;
            }
            int phase = (int) Math.floorMod(timeMillis / frameMillis, colors.size());
            String first = colors.get(phase);
            String second = colors.get((phase + 1) % colors.size());
            return "<gradient:" + first + ":" + second + ">" + value + "</gradient>";
        }

        private String conditionalFrame(Player subject, Player viewer, long timeMillis) {
            if (frames.size() < 2) {
                return sequenceFrame(timeMillis);
            }
            return subject != null && viewer != null && subject.getUniqueId().equals(viewer.getUniqueId()) ? frames.getFirst() : frames.get(1);
        }

        public static ReTextTemplate fromJson(JsonObject json) {
            if (json == null || !json.has("id") || json.get("id").isJsonNull()) {
                return null;
            }
            List<String> frames = new ArrayList<>();
            JsonElement framesElement = json.get("frames");
            if (framesElement != null && framesElement.isJsonArray()) {
                JsonArray array = framesElement.getAsJsonArray();
                for (JsonElement element : array) {
                    if (element != null && !element.isJsonNull()) {
                        frames.add(element.getAsString());
                    }
                }
            }
            if (frames.isEmpty() && json.has("text") && !json.get("text").isJsonNull()) {
                frames.add(json.get("text").getAsString());
            }
            if (frames.isEmpty()) {
                frames.add("");
            }
            long frameMillis = json.has("frameMillis") && !json.get("frameMillis").isJsonNull() ? json.get("frameMillis").getAsLong() : 250L;
            String mode = json.has("mode") && !json.get("mode").isJsonNull() ? json.get("mode").getAsString() : "frames";
            int width = json.has("width") && !json.get("width").isJsonNull() ? json.get("width").getAsInt() : 16;
            int visibleCharacters = json.has("visibleCharacters") && !json.get("visibleCharacters").isJsonNull() ? json.get("visibleCharacters").getAsInt() : 0;
            List<String> colors = new ArrayList<>();
            JsonElement colorsElement = json.get("colors");
            if (colorsElement != null && colorsElement.isJsonArray()) {
                for (JsonElement color : colorsElement.getAsJsonArray()) {
                    if (color != null && !color.isJsonNull()) {
                        colors.add(color.getAsString());
                    }
                }
            }
            String text = json.has("text") && !json.get("text").isJsonNull() ? json.get("text").getAsString() : frames.getFirst();
            return new ReTextTemplate(json.get("id").getAsString(), frames, frameMillis, mode, width, visibleCharacters, colors, text);
        }
    }
}
