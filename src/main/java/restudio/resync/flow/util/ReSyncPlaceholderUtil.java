package restudio.resync.flow.util;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import restudio.resync.flow.FlowRuntimeAccess;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ReSyncPlaceholderUtil {
    private static final Pattern RESYNC_PATTERN = Pattern.compile("%resync_([^%]+)%", Pattern.CASE_INSENSITIVE);

    private ReSyncPlaceholderUtil() {
    }

    public static String apply(Player player, String text, boolean usePapi) {
        String value = text != null ? text : "";
        value = applyReSyncVariables(player, value);
        if (usePapi) {
            value = applyPlaceholderApi(player, value);
        }
        return applyReSyncVariables(player, value);
    }

    private static String applyReSyncVariables(Player contextPlayer, String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        Map<String, Object> globals = getGlobalVariables();
        if (globals == null || globals.isEmpty()) {
            return input;
        }
        Matcher matcher = RESYNC_PATTERN.matcher(input);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String token = matcher.group(1);
            String replacement = resolveToken(globals, contextPlayer, token);
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String resolveToken(Map<String, Object> globals, Player contextPlayer, String token) {
        if (token == null || token.isBlank()) {
            return "";
        }
        String direct = stringify(globals.get(token));
        if (direct != null) {
            return direct;
        }
        if (contextPlayer != null) {
            Object playerVarsObj = globals.get("player_vars_" + contextPlayer.getUniqueId());
            if (playerVarsObj instanceof Map<?, ?> playerVars) {
                String ownValue = stringify(playerVars.get(token));
                if (ownValue != null) {
                    return ownValue;
                }
            }
            String scoped = stringify(globals.get(token + "_" + contextPlayer.getName()));
            if (scoped != null) {
                return scoped;
            }
        }

        Player matchedPlayer = null;
        String varName = token;
        String tokenLower = token.toLowerCase(Locale.ROOT);
        for (Player player : Bukkit.getOnlinePlayers()) {
            String playerName = player.getName();
            String playerLower = playerName.toLowerCase(Locale.ROOT);
            String suffix = "_" + playerLower;
            if (tokenLower.endsWith(suffix) && token.length() > suffix.length()) {
                matchedPlayer = player;
                varName = token.substring(0, token.length() - suffix.length());
                break;
            }
        }

        if (matchedPlayer != null) {
            Object playerVarsObj = globals.get("player_vars_" + matchedPlayer.getUniqueId());
            if (playerVarsObj instanceof Map<?, ?> playerVars) {
                Object value = playerVars.get(varName);
                String textValue = stringify(value);
                if (textValue != null) {
                    return textValue;
                }
            }
            String combined = stringify(globals.get(varName + "_" + matchedPlayer.getName()));
            if (combined != null) {
                return combined;
            }
        }

        return "";
    }

    private static String applyPlaceholderApi(Player player, String text) {
        if (player == null || text == null || text.isEmpty() || !Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return text;
        }
        return PlaceholderAPI.setPlaceholders(player, text);
    }

    private static Map<String, Object> getGlobalVariables() {
        return FlowRuntimeAccess.getGlobalVariables();
    }

    private static String stringify(Object value) {
        return value != null ? String.valueOf(value) : null;
    }
}
