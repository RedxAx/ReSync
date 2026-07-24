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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ReTextService {
    private static final Pattern ANIMATION_PATTERN = Pattern.compile("%resync_animation[:_]([^%]+)%", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESOURCE_PATTERN = Pattern.compile("%resync_text[:_]([^%]+)%", Pattern.CASE_INSENSITIVE);
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
    private final Map<String, ReTextResource> resourceCache = new ConcurrentHashMap<>();

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
        text = applyAnimations(text, subject, viewer, timeMillis);
        text = applyResources(text);
        text = ReSyncPlaceholderUtil.apply(subject, text, true);
        text = applyLuckPermsMeta(subject, text);
        return viewer != null && viewer != subject ? ReSyncPlaceholderUtil.apply(viewer, text, true) : text;
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
        ReTextResource resource = resource(id);
        if (resource == null || resource.kind() != ReTextKind.ANIMATION) {
            return null;
        }
        String templateId = resource.id();
        ReTextTemplate cached = templateCache.get(templateId);
        if (cached != null) {
            return cached;
        }
        ReTextTemplate template = ReTextTemplate.fromJson(resource.source());
        if (template != null) {
            templateCache.put(templateId, template);
        }
        return template;
    }

    public ReTextResource resource(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String resourceId = resolveTemplateId(id);
        ReTextResource cached = resourceCache.get(resourceId);
        if (cached != null) {
            return cached;
        }
        JsonObject json = storage.get(ReSyncResourceCatalog.TEXT_TEMPLATE, resourceId);
        ReTextResource resource = ReTextResource.fromJson(resourceId, json);
        if (resource != null) {
            resourceCache.put(resourceId, resource);
        }
        return resource;
    }

    public List<String> lines(String id) {
        ReTextResource resource = resource(id);
        return resource == null ? List.of() : resource.lines();
    }

    public Map<String, String> entries(String id) {
        ReTextResource resource = resource(id);
        return resource == null ? Map.of() : resource.entries();
    }

    public String lookup(String id, String key, String fallback) {
        ReTextResource resource = resource(id);
        return resource == null ? normalizeFallback(fallback) : resource.lookup(key, fallback);
    }

    private String resolveTemplateId(String id) {
        String clean = id == null ? "" : id.trim();
        if (clean.isBlank()) {
            return clean;
        }
        if (storage.get(ReSyncResourceCatalog.TEXT_TEMPLATE, clean) != null) {
            return clean;
        }
        for (String existing : storage.listIds(ReSyncResourceCatalog.TEXT_TEMPLATE)) {
            if (existing.equalsIgnoreCase(clean)) {
                return existing;
            }
        }
        return clean;
    }

    public void clearTemplateCache() {
        templateCache.clear();
        resourceCache.clear();
    }

    private String normalizeFallback(String fallback) {
        return fallback == null ? "" : fallback;
    }

    private String applyAnimations(String input, Player subject, Player viewer, long timeMillis) {
        Matcher matcher = ANIMATION_PATTERN.matcher(input);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            ReTextTemplate template = template(matcher.group(1));
            String replacement = template != null ? template.frame(subject, viewer, timeMillis) : matcher.group();
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private String applyResources(String input) {
        Matcher matcher = RESOURCE_PATTERN.matcher(input);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            ReTextResource resource = resource(matcher.group(1));
            String replacement = matcher.group();
            if (resource != null && resource.kind() == ReTextKind.LIST) {
                replacement = String.join("\n", resource.lines());
            } else if (resource != null && resource.kind() == ReTextKind.MAP) {
                replacement = String.join("\n", resource.entries().entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue()).toList());
            }
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

    public enum ReTextKind {
        ANIMATION,
        LIST,
        MAP
    }

    public record ReTextResource(String id, ReTextKind kind, List<String> lines, Map<String, String> entries, JsonObject source) {
        public ReTextResource {
            id = id == null ? "" : id;
            kind = kind == null ? ReTextKind.ANIMATION : kind;
            lines = lines == null ? List.of() : List.copyOf(lines);
            entries = entries == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(entries));
            source = source == null ? new JsonObject() : source.deepCopy();
        }

        public String lookup(String key, String fallback) {
            if (key == null) {
                return fallback == null ? "" : fallback;
            }
            return entries.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(key.trim())).map(Map.Entry::getValue).findFirst().orElse(fallback == null ? "" : fallback);
        }

        private static ReTextResource fromJson(String id, JsonObject json) {
            if (json == null) {
                return null;
            }
            ReTextKind kind = switch (text(json, "kind").toLowerCase(Locale.ROOT)) {
                case "list" -> ReTextKind.LIST;
                case "map" -> ReTextKind.MAP;
                default -> ReTextKind.ANIMATION;
            };
            List<String> lines = new ArrayList<>();
            Map<String, String> entries = new LinkedHashMap<>();
            if (kind == ReTextKind.LIST && json.has("values") && json.get("values").isJsonArray()) {
                json.getAsJsonArray("values").forEach(value -> {
                    if (value.isJsonPrimitive() && !value.getAsString().isBlank()) {
                        lines.add(value.getAsString());
                    }
                });
            }
            if (kind == ReTextKind.MAP && json.has("entries") && json.get("entries").isJsonArray()) {
                json.getAsJsonArray("entries").forEach(value -> {
                    if (!value.isJsonObject()) {
                        return;
                    }
                    JsonObject entry = value.getAsJsonObject();
                    String key = text(entry, "key").trim();
                    if (!key.isBlank()) {
                        entries.putIfAbsent(key, text(entry, "value"));
                    }
                });
                lines.addAll(entries.keySet());
            }
            return new ReTextResource(id, kind, lines, entries, json);
        }

        private static String text(JsonObject json, String key) {
            return json != null && json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : "";
        }
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
        private final String color;
        private final String secondaryColor;

        public ReTextTemplate(String id, List<String> frames, long frameMillis, String mode) {
            this(id, frames, frameMillis, mode, 16, 0, List.of(), frames == null || frames.isEmpty() ? "" : frames.getFirst(), "yellow", "white");
        }

        public ReTextTemplate(String id, List<String> frames, long frameMillis, String mode, int width, int visibleCharacters, List<String> colors, String text) {
            this(id, frames, frameMillis, mode, width, visibleCharacters, colors, text, "yellow", "white");
        }

        public ReTextTemplate(String id, List<String> frames, long frameMillis, String mode, int width, int visibleCharacters, List<String> colors, String text, String color, String secondaryColor) {
            this.id = id;
            this.frames = frames == null || frames.isEmpty() ? List.of("") : List.copyOf(frames);
            this.frameMillis = Math.max(1L, frameMillis);
            this.mode = mode == null || mode.isBlank() ? "frames" : mode;
            this.width = Math.max(1, width);
            this.visibleCharacters = Math.max(0, visibleCharacters);
            this.colors = colors == null ? List.of() : List.copyOf(colors);
            this.text = text == null ? "" : text;
            this.color = color == null || color.isBlank() ? "yellow" : color;
            this.secondaryColor = secondaryColor == null || secondaryColor.isBlank() ? "white" : secondaryColor;
        }

        public String id() {
            return id;
        }

        public String frame(Player subject, Player viewer, long timeMillis) {
            String normalizedMode = mode.toLowerCase(Locale.ROOT);
            return switch (normalizedMode) {
                case "blink" -> blinkFrame(timeMillis);
                case "typing" -> typingFrame(timeMillis);
                case "scroll", "scrolling" -> scrollingFrame(timeMillis);
                case "bounce" -> bounceFrame(timeMillis);
                case "pulse" -> pulseFrame(timeMillis);
                case "rainbow" -> rainbowFrame(timeMillis);
                case "wave" -> waveFrame(timeMillis);
                case "wipe" -> wipeFrame(timeMillis);
                case "sparkle" -> sparkleFrame(timeMillis);
                default -> sequenceFrame(timeMillis);
            };
        }

        private String sequenceFrame(long timeMillis) {
            int index = (int) Math.floorMod(timeMillis / frameMillis, frames.size());
            return frames.get(index);
        }

        private String blinkFrame(long timeMillis) {
            String value = !text.isBlank() ? text : frames.getFirst();
            return Math.floorMod(timeMillis / frameMillis, 2) == 0 ? value : "";
        }

        private String typingFrame(long timeMillis) {
            String value = !text.isBlank() ? text : frames.getFirst();
            int length = visibleLength(value);
            int visible = (int) Math.floorMod(timeMillis / frameMillis, length + 1);
            int maxVisible = visibleCharacters > 0 ? Math.min(visibleCharacters, visible) : visible;
            return formattedSlice(value, 0, Math.min(length, maxVisible));
        }

        private String scrollingFrame(long timeMillis) {
            String value = !text.isBlank() ? text : frames.getFirst();
            int length = visibleLength(value);
            if (length <= width) {
                return value;
            }
            int span = length + width;
            int start = (int) Math.floorMod(timeMillis / frameMillis, span);
            return formattedWindow(value, start, width);
        }

        private String bounceFrame(long timeMillis) {
            String value = !text.isBlank() ? text : frames.getFirst();
            int length = visibleLength(value);
            if (length <= width) {
                return value;
            }
            int maxStart = length - width;
            int cycle = Math.max(1, maxStart * 2);
            int step = (int) Math.floorMod(timeMillis / frameMillis, cycle);
            int start = step <= maxStart ? step : cycle - step;
            return formattedSlice(value, start, start + width);
        }

        private String pulseFrame(long timeMillis) {
            String value = !text.isBlank() ? text : frames.getFirst();
            String active = Math.floorMod(timeMillis / frameMillis, 2) == 0 ? color : secondaryColor;
            return tag(active, value);
        }

        private String rainbowFrame(long timeMillis) {
            String value = !text.isBlank() ? text : frames.getFirst();
            double phase = Math.floorMod(timeMillis / frameMillis, 32) / 32.0;
            return "<rainbow:" + String.format(Locale.ROOT, "%.3f", phase) + ">" + value + "</rainbow>";
        }

        private String waveFrame(long timeMillis) {
            String value = !text.isBlank() ? text : frames.getFirst();
            List<String> palette = colors.isEmpty() ? List.of(color, secondaryColor) : colors;
            int phase = (int) Math.floorMod(timeMillis / frameMillis, palette.size());
            StringBuilder out = new StringBuilder();
            for (VisibleChar visible : visibleChars(value)) {
                String active = palette.get(Math.floorMod(visible.index() + phase, palette.size()));
                out.append(tag(active, visible.text()));
            }
            return out.toString();
        }

        private String wipeFrame(long timeMillis) {
            String value = !text.isBlank() ? text : frames.getFirst();
            int length = visibleLength(value);
            int cap = visibleCharacters > 0 ? Math.min(visibleCharacters, length) : length;
            int visible = (int) Math.floorMod(timeMillis / frameMillis, cap + 1);
            return formattedSlice(value, 0, visible);
        }

        private String sparkleFrame(long timeMillis) {
            String value = !text.isBlank() ? text : frames.getFirst();
            List<VisibleChar> chars = visibleChars(value);
            if (chars.isEmpty()) {
                return value;
            }
            int active = (int) Math.floorMod(timeMillis / frameMillis, chars.size());
            StringBuilder out = new StringBuilder();
            for (VisibleChar visible : chars) {
                out.append(visible.index() == active ? tag(color, visible.text()) : tag(secondaryColor, visible.text()));
            }
            return out.toString();
        }

        private String formattedWindow(String value, int start, int size) {
            StringBuilder out = new StringBuilder();
            int textStart = Math.max(0, start);
            int textEnd = Math.min(visibleLength(value), start + size);
            int leftPad = Math.max(0, -start);
            int rightPad = Math.max(0, start + size - visibleLength(value));
            out.append(" ".repeat(leftPad));
            if (textEnd > textStart) {
                out.append(formattedSlice(value, textStart, textEnd));
            }
            out.append(" ".repeat(rightPad));
            return out.toString();
        }

        private String formattedSlice(String value, int start, int end) {
            if (value == null || value.isEmpty() || end <= start) {
                return "";
            }
            List<TextPart> parts = textParts(value);
            StringBuilder out = new StringBuilder();
            List<String> openTags = new ArrayList<>();
            int visible = 0;
            boolean startTagsEmitted = false;
            for (TextPart part : parts) {
                if (part.tag()) {
                    if (visible < start) {
                        applyTagState(openTags, part.text());
                    }
                    if (visible >= start && visible < end) {
                        if (visible == start && !startTagsEmitted) {
                            for (String tag : openTags) {
                                out.append(tag);
                            }
                            startTagsEmitted = true;
                        }
                        out.append(part.text());
                        applyTagState(openTags, part.text());
                    }
                    continue;
                }
                for (int offset = 0; offset < part.text().length();) {
                    int codePoint = part.text().codePointAt(offset);
                    if (visible == start && !startTagsEmitted) {
                        for (String tag : openTags) {
                            out.append(tag);
                        }
                        startTagsEmitted = true;
                    }
                    if (visible >= start && visible < end) {
                        out.appendCodePoint(codePoint);
                    }
                    offset += Character.charCount(codePoint);
                    visible++;
                    if (visible >= end) {
                        break;
                    }
                }
                if (visible >= end) {
                    break;
                }
            }
            for (int i = openTags.size() - 1; i >= 0; i--) {
                out.append(closeTag(openTags.get(i)));
            }
            return out.toString();
        }

        private int visibleLength(String value) {
            int length = 0;
            for (TextPart part : textParts(value)) {
                if (!part.tag()) {
                    length += part.text().codePointCount(0, part.text().length());
                }
            }
            return length;
        }

        private List<VisibleChar> visibleChars(String value) {
            List<VisibleChar> chars = new ArrayList<>();
            int index = 0;
            for (TextPart part : textParts(value)) {
                if (part.tag()) {
                    continue;
                }
                for (int offset = 0; offset < part.text().length();) {
                    int codePoint = part.text().codePointAt(offset);
                    chars.add(new VisibleChar(index, new String(Character.toChars(codePoint))));
                    offset += Character.charCount(codePoint);
                    index++;
                }
            }
            return chars;
        }

        private List<TextPart> textParts(String value) {
            List<TextPart> parts = new ArrayList<>();
            if (value == null || value.isEmpty()) {
                return parts;
            }
            Matcher matcher = MINIMESSAGE_PATTERN.matcher(value);
            int index = 0;
            while (matcher.find()) {
                if (matcher.start() > index) {
                    parts.add(new TextPart(false, value.substring(index, matcher.start())));
                }
                parts.add(new TextPart(true, matcher.group()));
                index = matcher.end();
            }
            if (index < value.length()) {
                parts.add(new TextPart(false, value.substring(index)));
            }
            return parts;
        }

        private void applyTagState(List<String> openTags, String tag) {
            String name = tagName(tag);
            if (name.isBlank() || tag.startsWith("</")) {
                openTags.removeIf(open -> tagName(open).equals(name));
                return;
            }
            if (tag.endsWith("/>") || name.startsWith("click") || name.startsWith("hover")) {
                return;
            }
            openTags.add(tag);
        }

        private String tagName(String tag) {
            String clean = tag == null ? "" : tag.replace("<", "").replace(">", "").replace("/", "").trim();
            int split = clean.indexOf(':');
            return split >= 0 ? clean.substring(0, split).toLowerCase(Locale.ROOT) : clean.toLowerCase(Locale.ROOT);
        }

        private String closeTag(String tag) {
            String name = tagName(tag);
            return name.isBlank() ? "" : "</" + name + ">";
        }

        private String tag(String tag, String value) {
            if (tag == null || tag.isBlank()) {
                return value;
            }
            String clean = tag.trim();
            if (clean.matches("(?i)#?[0-9a-f]{6}")) {
                clean = clean.startsWith("#") ? clean : "#" + clean;
            }
            if (clean.startsWith("<") && clean.endsWith(">")) {
                return clean + value + closeTag(clean);
            }
            return "<" + clean + ">" + value + "</" + tagName(clean) + ">";
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
            String color = json.has("color") && !json.get("color").isJsonNull() ? json.get("color").getAsString() : "yellow";
            String secondaryColor = json.has("secondaryColor") && !json.get("secondaryColor").isJsonNull() ? json.get("secondaryColor").getAsString() : "white";
            return new ReTextTemplate(json.get("id").getAsString(), frames, frameMillis, mode, width, visibleCharacters, colors, text, color, secondaryColor);
        }

        private record TextPart(boolean tag, String text) {
        }

        private record VisibleChar(int index, String text) {
        }
    }
}
