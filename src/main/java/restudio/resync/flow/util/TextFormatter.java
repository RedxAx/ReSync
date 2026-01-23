package restudio.resync.flow.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class TextFormatter {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.builder()
        .character('&')
        .hexColors()
        .useUnusualXRepeatedCharacterHexFormat()
        .build();
    private static final LegacyComponentSerializer LEGACY_SECTION_SERIALIZER = LegacyComponentSerializer.legacySection();
    private static final Pattern MINIMESSAGE_PATTERN = Pattern.compile("<[^>]+>");
    private static final String DEFAULT_NAME_LEGACY_PREFIX = "&r";
    private static final String DEFAULT_LORE_LEGACY_PREFIX = "&r&8";
    private static final String DEFAULT_NAME_MINI_PREFIX = "<white><!italic>";
    private static final String DEFAULT_LORE_MINI_PREFIX = "<dark_gray><!italic>";

    private TextFormatter() {}

    public static Component parse(String text) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        String normalized = text.replace('§', '&');
        if (MINIMESSAGE_PATTERN.matcher(normalized).find()) {
            try {
                return MINI_MESSAGE.deserialize(normalized);
            } catch (Exception ignored) {
            }
        }
        return LEGACY_SERIALIZER.deserialize(normalized);
    }

    public static Component parseItemName(String text) {
        return parseWithDefaults(text, DEFAULT_NAME_LEGACY_PREFIX, DEFAULT_NAME_MINI_PREFIX);
    }

    public static Component parseItemLore(String text) {
        return parseWithDefaults(text, DEFAULT_LORE_LEGACY_PREFIX, DEFAULT_LORE_MINI_PREFIX);
    }

    public static List<Component> parseLines(String text) {
        List<Component> components = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return components;
        }
        String[] lines = text.split("\n");
        for (String line : lines) {
            components.add(parse(line));
        }
        return components;
    }

    private static Component parseWithDefaults(String text, String legacyPrefix, String miniPrefix) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        String normalized = text.replace('§', '&');
        if (MINIMESSAGE_PATTERN.matcher(normalized).find()) {
            try {
                return MINI_MESSAGE.deserialize(miniPrefix + normalized);
            } catch (Exception ignored) {
            }
        }
        return LEGACY_SERIALIZER.deserialize(legacyPrefix + normalized);
    }

    public static String formatLegacy(String text) {
        return LEGACY_SECTION_SERIALIZER.serialize(parse(text));
    }

    public static String formatLegacy(Component component) {
        if (component == null) {
            return "";
        }
        return LEGACY_SECTION_SERIALIZER.serialize(component);
    }
}
