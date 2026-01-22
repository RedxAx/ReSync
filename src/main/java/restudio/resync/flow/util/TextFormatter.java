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
