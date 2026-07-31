package restudio.resync.velocity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.util.Favicon;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import restudio.resync.network.NetworkResource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

record VelocityMotdProfile(Component description, String playerCountMode, Integer onlinePlayers, Integer maxPlayers, Favicon favicon) {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    static Optional<VelocityMotdProfile> select(List<NetworkResource> resources) {
        return resources.stream()
            .filter(resource -> resource != null && !resource.deleted())
            .map(VelocityMotdProfile::json)
            .flatMap(Optional::stream)
            .filter(profile -> bool(profile, "enabled", true))
            .max(Comparator.comparingInt(profile -> integer(profile, "priority", 0)))
            .map(VelocityMotdProfile::from);
    }

    private static VelocityMotdProfile from(JsonObject profile) {
        String firstLine = line(profile, "line1");
        String secondLine = line(profile, "line2");
        String value = secondLine.isBlank() ? firstLine : firstLine + "\n" + secondLine;
        String countMode = switch (text(profile, "playerCountMode").toLowerCase(Locale.ROOT)) {
            case "hidden" -> "hidden";
            case "fixed" -> "fixed";
            default -> "real";
        };
        Integer onlinePlayers = nonNegativeInteger(profile, "onlinePlayers");
        Integer maxPlayers = nonNegativeInteger(profile, "maxPlayers");
        return new VelocityMotdProfile(MINI_MESSAGE.deserialize(value), countMode, onlinePlayers, maxPlayers, favicon(profile));
    }

    private static Optional<JsonObject> json(NetworkResource resource) {
        try {
            return Optional.of(JsonParser.parseString(new String(resource.payload(), StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static String text(JsonObject value, String key) {
        return value.has(key) && value.get(key).isJsonPrimitive() ? value.get(key).getAsString() : "";
    }

    private static String line(JsonObject value, String key) {
        String[] lines = text(value, key).split("\\R", -1);
        return lines.length == 0 ? "" : lines[0];
    }

    private static Integer nonNegativeInteger(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive()) {
            return null;
        }
        try {
            return Math.max(0, value.get(key).getAsInt());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static int integer(JsonObject value, String key, int fallback) {
        try {
            return value.has(key) && value.get(key).isJsonPrimitive() ? value.get(key).getAsInt() : fallback;
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static Favicon favicon(JsonObject profile) {
        String data = text(profile, "iconData");
        if (data.isBlank()) {
            return null;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(stripImageDataPrefix(data));
            if (!hasPngSignature(bytes)) {
                return null;
            }
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            return image != null && image.getWidth() == 64 && image.getHeight() == 64 ? Favicon.create(image) : null;
        } catch (RuntimeException | IOException exception) {
            return null;
        }
    }

    private static String stripImageDataPrefix(String data) {
        int comma = data.indexOf(',');
        return data.startsWith("data:image/") && comma >= 0 ? data.substring(comma + 1) : data;
    }

    private static boolean hasPngSignature(byte[] bytes) {
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

    private static boolean bool(JsonObject value, String key, boolean fallback) {
        try {
            return value.has(key) && value.get(key).isJsonPrimitive() ? value.get(key).getAsBoolean() : fallback;
        } catch (RuntimeException exception) {
            return fallback;
        }
    }
}
