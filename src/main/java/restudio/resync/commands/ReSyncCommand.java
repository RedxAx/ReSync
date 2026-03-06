package restudio.resync.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import restudio.flow.data.ScoreboardDefinition;
import restudio.flow.data.TabDefinition;
import restudio.resync.ReSync;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.TabListService;
import restudio.resync.flow.nodes.ScoreboardNodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ReSyncCommand implements TabExecutor {
    private final ReSync plugin;

    public ReSyncCommand(ReSync plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("resync.command")) {
            sender.sendMessage("No permission.");
            return true;
        }
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }
        String group = args[0].toLowerCase(Locale.ROOT);
        return switch (group) {
            case "scoreboard" -> handleScoreboard(sender, args);
            case "tab" -> handleTab(sender, args);
            default -> {
                sendUsage(sender);
                yield true;
            }
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("scoreboard", "tab"), args[0]);
        }
        String group = args[0].toLowerCase(Locale.ROOT);
        if ("scoreboard".equals(group)) {
            if (args.length == 2) {
                return filter(List.of("show", "hide", "list", "default"), args[1]);
            }
            if (args.length == 3 && "default".equalsIgnoreCase(args[1])) {
                FlowStorage storage = getStorage();
                if (storage == null) {
                    return List.of();
                }
                List<String> options = new ArrayList<>(storage.listScoreboardIds());
                options.add("none");
                return filter(options, args[2]);
            }
            if (args.length == 4 && "default".equalsIgnoreCase(args[1])) {
                return filter(List.of("true", "false"), args[3]);
            }
            if (args.length == 3) {
                return filter(onlinePlayers(), args[2]);
            }
            if (args.length == 4 && "show".equalsIgnoreCase(args[1])) {
                FlowStorage storage = getStorage();
                if (storage == null) {
                    return List.of();
                }
                return filter(storage.listScoreboardIds(), args[3]);
            }
            if (args.length == 5 && "show".equalsIgnoreCase(args[1])) {
                return filter(List.of("true", "false"), args[4]);
            }
            return List.of();
        }
        if ("tab".equals(group)) {
            if (args.length == 2) {
                return filter(List.of("list", "apply", "clear", "default", "interval"), args[1]);
            }
            if (args.length == 3 && "apply".equalsIgnoreCase(args[1])) {
                FlowStorage storage = getStorage();
                if (storage == null) {
                    return List.of();
                }
                return filter(storage.listTabIds(), args[2]);
            }
            if (args.length == 4 && "apply".equalsIgnoreCase(args[1])) {
                List<String> options = new ArrayList<>(onlinePlayers());
                options.add("true");
                options.add("false");
                return filter(options, args[3]);
            }
            if (args.length == 5 && "apply".equalsIgnoreCase(args[1])) {
                return filter(List.of("true", "false"), args[4]);
            }
            if (args.length == 3 && "clear".equalsIgnoreCase(args[1])) {
                return filter(onlinePlayers(), args[2]);
            }
            if (args.length == 3 && "default".equalsIgnoreCase(args[1])) {
                FlowStorage storage = getStorage();
                if (storage == null) {
                    return List.of();
                }
                List<String> options = new ArrayList<>(storage.listTabIds());
                options.add("none");
                return filter(options, args[2]);
            }
            if (args.length == 4 && "default".equalsIgnoreCase(args[1])) {
                return filter(List.of("true", "false"), args[3]);
            }
            if (args.length == 3 && "interval".equalsIgnoreCase(args[1])) {
                return filter(List.of("10", "20", "40", "100"), args[2]);
            }
            return List.of();
        }
        return List.of();
    }

    private boolean handleScoreboard(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendUsage(sender);
            return true;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if ("list".equals(action)) {
            FlowStorage storage = getStorage();
            if (storage == null) {
                sender.sendMessage("Flow storage unavailable.");
                return true;
            }
            List<String> ids = storage.listScoreboardIds();
            sender.sendMessage(ids.isEmpty() ? "No scoreboards found." : "Scoreboards: " + String.join(", ", ids));
            return true;
        }
        if ("default".equals(action)) {
            if (args.length == 2) {
                String defaultId = ScoreboardNodes.getDefaultScoreboardId();
                if (defaultId == null || defaultId.isBlank()) {
                    sender.sendMessage("Default scoreboard: none");
                } else {
                    sender.sendMessage("Default scoreboard: " + defaultId + " (usePapi=" + ScoreboardNodes.isDefaultScoreboardUsePapi() + ")");
                }
                return true;
            }
            String id = args[2];
            if ("none".equalsIgnoreCase(id)) {
                boolean cleared = ScoreboardNodes.clearDefaultScoreboard();
                sender.sendMessage(cleared ? "Default scoreboard cleared." : "Failed to clear default scoreboard.");
                return true;
            }
            boolean usePapi = args.length < 4 || Boolean.parseBoolean(args[3]);
            boolean changed = ScoreboardNodes.setDefaultScoreboard(id, usePapi);
            sender.sendMessage(changed
                ? "Default scoreboard set to '" + id + "' (usePapi=" + usePapi + ")"
                : "Failed to set default scoreboard. Check ID.");
            return true;
        }
        if (args.length < 3) {
            sendUsage(sender);
            return true;
        }
        Player target = findPlayer(args[2]);
        if (target == null) {
            sender.sendMessage("Player not found: " + args[2]);
            return true;
        }
        if ("hide".equals(action)) {
            ScoreboardNodes.hideActive(target);
            sender.sendMessage("Hid scoreboard for " + target.getName());
            return true;
        }
        if (!"show".equals(action) || args.length < 4) {
            sendUsage(sender);
            return true;
        }
        FlowStorage storage = getStorage();
        if (storage == null) {
            sender.sendMessage("Flow storage unavailable.");
            return true;
        }
        String scoreboardId = args[3];
        ScoreboardDefinition definition = storage.getScoreboard(scoreboardId);
        if (definition == null) {
            sender.sendMessage("Scoreboard not found: " + scoreboardId);
            return true;
        }
        boolean usePapi = args.length < 5 || Boolean.parseBoolean(args[4]);
        boolean applied = ScoreboardNodes.showTemplate(target, definition, usePapi);
        sender.sendMessage(applied
            ? "Applied scoreboard '" + scoreboardId + "' to " + target.getName()
            : "Failed to apply scoreboard '" + scoreboardId + "'.");
        return true;
    }

    private boolean handleTab(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendUsage(sender);
            return true;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        FlowStorage storage = getStorage();
        if (storage == null) {
            sender.sendMessage("Flow storage unavailable.");
            return true;
        }
        if ("list".equals(action)) {
            List<String> ids = storage.listTabIds();
            sender.sendMessage(ids.isEmpty() ? "No tabs found." : "Tabs: " + String.join(", ", ids));
            return true;
        }
        if ("default".equals(action)) {
            if (args.length == 2) {
                String defaultId = TabListService.getDefaultTabId();
                if (defaultId == null || defaultId.isBlank()) {
                    sender.sendMessage("Default tab: none");
                } else {
                    sender.sendMessage("Default tab: " + defaultId + " (usePapi=" + TabListService.isDefaultTabUsePapi() + ")");
                }
                return true;
            }
            String id = args[2];
            if ("none".equalsIgnoreCase(id)) {
                boolean cleared = TabListService.clearDefaultTab();
                sender.sendMessage(cleared ? "Default tab cleared." : "Failed to clear default tab.");
                return true;
            }
            boolean usePapi = args.length < 4 || Boolean.parseBoolean(args[3]);
            boolean changed = TabListService.setDefaultTab(id, usePapi);
            sender.sendMessage(changed
                ? "Default tab set to '" + id + "' (usePapi=" + usePapi + ")"
                : "Failed to set default tab. Check ID.");
            return true;
        }
        if ("clear".equals(action)) {
            if (args.length >= 3) {
                Player target = findPlayer(args[2]);
                if (target == null) {
                    sender.sendMessage("Player not found: " + args[2]);
                    return true;
                }
                TabListService.clearForPlayer(target);
                sender.sendMessage("Cleared tab for " + target.getName());
                return true;
            }
            for (Player player : Bukkit.getOnlinePlayers()) {
                TabListService.clearForPlayer(player);
            }
            TabListService.resetEntryNames();
            sender.sendMessage("Cleared tab for all players.");
            return true;
        }
        if ("apply".equals(action)) {
            if (args.length < 3) {
                sendUsage(sender);
                return true;
            }
            String tabId = args[2];
            TabDefinition tab = storage.getTab(tabId);
            if (tab == null) {
                sender.sendMessage("Tab not found: " + tabId);
                return true;
            }
            Player target = null;
            boolean usePapi = true;
            if (args.length >= 4) {
                Player maybePlayer = findPlayer(args[3]);
                if (maybePlayer != null) {
                    target = maybePlayer;
                    if (args.length >= 5) {
                        usePapi = Boolean.parseBoolean(args[4]);
                    }
                } else {
                    usePapi = Boolean.parseBoolean(args[3]);
                }
            }
            if (target != null) {
                boolean applied = TabListService.applyTemplate(target, tab, usePapi);
                sender.sendMessage(applied
                    ? "Applied tab '" + tabId + "' to " + target.getName()
                    : "Failed to apply tab '" + tabId + "'.");
            } else {
                TabListService.applyTemplateToAll(tab, usePapi);
                sender.sendMessage("Applied tab '" + tabId + "' to all players.");
            }
            return true;
        }
        if ("interval".equals(action)) {
            if (args.length == 2) {
                sender.sendMessage("Hud refresh interval: " + TabListService.getRefreshIntervalTicks() + " ticks");
                return true;
            }
            try {
                int ticks = Integer.parseInt(args[2]);
                if (ticks < 1) {
                    sender.sendMessage("Interval must be >= 1 tick.");
                    return true;
                }
                boolean changed = TabListService.setRefreshIntervalTicks(ticks);
                sender.sendMessage(changed ? "Hud refresh interval set to " + ticks + " ticks." : "Failed to set interval.");
            } catch (NumberFormatException e) {
                sender.sendMessage("Invalid interval: " + args[2]);
            }
            return true;
        }
        sendUsage(sender);
        return true;
    }

    private Player findPlayer(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Player exact = Bukkit.getPlayerExact(name);
        return exact != null ? exact : Bukkit.getPlayer(name);
    }

    private FlowStorage getStorage() {
        if (plugin.getV2Server() == null || plugin.getV2Server().getFlowModule() == null) {
            return null;
        }
        return plugin.getV2Server().getFlowModule().getStorage();
    }

    private List<String> onlinePlayers() {
        List<String> names = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            names.add(online.getName());
        }
        return names;
    }

    private List<String> filter(List<String> values, String input) {
        String needle = input == null ? "" : input.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String value : values) {
            if (value != null && value.toLowerCase(Locale.ROOT).startsWith(needle)) {
                out.add(value);
            }
        }
        return out;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage("/resync scoreboard list");
        sender.sendMessage("/resync scoreboard show <player> <scoreboardId> [usePapi]");
        sender.sendMessage("/resync scoreboard hide <player>");
        sender.sendMessage("/resync scoreboard default [<scoreboardId|none> [usePapi]]");
        sender.sendMessage("/resync tab list");
        sender.sendMessage("/resync tab apply <tabId> [player] [usePapi]");
        sender.sendMessage("/resync tab clear [player]");
        sender.sendMessage("/resync tab default [<tabId|none> [usePapi]]");
        sender.sendMessage("/resync tab interval [ticks]");
    }
}
