package restudio.resync.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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
import restudio.resync.selection.InteractiveSelectionManager;
import restudio.resync.world.WorldGameRuleDescriptor;
import restudio.resync.world.WorldManagementService;
import restudio.resync.world.WorldOperationResult;
import restudio.resync.world.PortalLinkCreationSession;
import restudio.resync.world.WorldPortal;
import restudio.resync.world.WorldRegistryEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ReSyncCommand implements TabExecutor {
    private final ReSync plugin;

    public ReSyncCommand(ReSync plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("resync.command")) {
            sendError(sender, "No permission");
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
            case "world" -> handleWorld(sender, args);
            case "portal" -> handlePortal(sender, args);
            default -> {
                sendUsage(sender);
                yield true;
            }
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("scoreboard", "tab", "world", "portal"), args[0]);
        }
        String group = args[0].toLowerCase(Locale.ROOT);
        return switch (group) {
            case "scoreboard" -> tabCompleteScoreboard(args);
            case "tab" -> tabCompleteTab(args);
            case "world" -> tabCompleteWorld(args);
            case "portal" -> tabCompletePortal(args);
            default -> List.of();
        };
    }

    private List<String> tabCompleteScoreboard(String[] args) {
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
            return filter(booleanOptions(), args[3]);
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
            return filter(booleanOptions(), args[4]);
        }
        return List.of();
    }

    private List<String> tabCompleteTab(String[] args) {
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
            options.addAll(booleanOptions());
            return filter(options, args[3]);
        }
        if (args.length == 5 && "apply".equalsIgnoreCase(args[1])) {
            return filter(booleanOptions(), args[4]);
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
            return filter(booleanOptions(), args[3]);
        }
        if (args.length == 3 && "interval".equalsIgnoreCase(args[1])) {
            return filter(List.of("10", "20", "40", "100"), args[2]);
        }
        return List.of();
    }

    private List<String> tabCompleteWorld(String[] args) {
        if (args.length == 2) {
            return filter(List.of("list", "info", "generators", "scan", "import", "create", "clone", "load", "unload", "delete", "difficulty", "rules", "rule", "isolated", "timelock", "weatherlock", "tp", "tpspawn"), args[1]);
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        return switch (action) {
            case "info", "load", "unload", "delete", "difficulty", "rules", "rule", "isolated", "timelock", "weatherlock" ->
                args.length == 3 ? filter(worldNames(), args[2]) : tabCompleteWorldAction(action, args);
            case "clone" -> tabCompleteClone(args);
            case "create" -> args.length == 5 ? filter(environmentOptions(), args[4]) : args.length == 6 ? filter(generatorHints(), args[5]) : List.of();
            case "tp" -> tabCompleteWorldTeleport(args);
            case "tpspawn" -> {
                if (args.length == 3) {
                    yield filter(onlinePlayers(), args[2]);
                }
                if (args.length == 4) {
                    yield filter(worldNames(), args[3]);
                }
                yield List.of();
            }
            default -> List.of();
        };
    }

    private List<String> tabCompleteWorldAction(String action, String[] args) {
        return switch (action) {
            case "unload", "delete" -> args.length == 4 ? filter(worldNames(), args[3]) : args.length == 5 && "delete".equals(action) ? filter(worldNames(), args[4]) : List.of();
            case "difficulty" -> args.length == 4 ? filter(difficultyOptions(), args[3]) : List.of();
            case "rule" -> args.length == 4 ? filter(gameRuleNames(), args[3]) : args.length == 5 ? filter(gameRuleValueOptions(args[3]), args[4]) : List.of();
            case "isolated" -> args.length == 4 ? filter(booleanOptions(), args[3]) : List.of();
            case "timelock" -> args.length == 4 ? filter(booleanOptions(), args[3]) : List.of();
            case "weatherlock" -> args.length == 4 ? filter(booleanOptions(), args[3]) : args.length == 5 ? filter(booleanOptions(), args[4]) : args.length == 6 ? filter(booleanOptions(), args[5]) : List.of();
            default -> List.of();
        };
    }

    private List<String> tabCompleteClone(String[] args) {
        if (args.length == 3) {
            return filter(worldNames(), args[2]);
        }
        if (args.length == 5) {
            return filter(booleanOptions(), args[4]);
        }
        return List.of();
    }

    private List<String> tabCompleteWorldTeleport(String[] args) {
        if (args.length == 3) {
            return filter(onlinePlayers(), args[2]);
        }
        if (args.length == 4) {
            return filter(worldNames(), args[3]);
        }
        return List.of();
    }

    private List<String> tabCompletePortal(String[] args) {
        if (args.length == 2) {
            return filter(List.of("create", "cancel", "list", "info", "delete", "enable", "rename", "bounds", "dest", "tp"), args[1]);
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        return switch (action) {
            case "create" -> args.length == 3 ? filter(worldNames(), args[2]) : List.of();
            case "list" -> args.length == 3 ? filter(worldNames(), args[2]) : List.of();
            case "info", "delete", "rename", "bounds", "dest", "enable" -> args.length == 3 ? filter(portalIdentifiers(), args[2]) : tabCompletePortalMutation(action, args);
            case "tp" -> args.length == 3 ? filter(onlinePlayers(), args[2]) : args.length == 4 ? filter(portalIdentifiers(), args[3]) : List.of();
            default -> List.of();
        };
    }

    private List<String> tabCompletePortalMutation(String action, String[] args) {
        return switch (action) {
            case "enable" -> args.length == 4 ? filter(booleanOptions(), args[3]) : List.of();
            case "bounds" -> args.length == 4 ? filter(worldNames(), args[3]) : List.of();
            case "dest" -> args.length == 4 ? filter(worldNames(), args[3]) : List.of();
            case "rename" -> List.of();
            default -> List.of();
        };
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
            Integer ticks = parseInt(args[2]);
            if (ticks == null) {
                sender.sendMessage("Invalid interval: " + args[2]);
                return true;
            }
            if (ticks < 1) {
                sender.sendMessage("Interval must be >= 1 tick.");
                return true;
            }
            boolean changed = TabListService.setRefreshIntervalTicks(ticks);
            sender.sendMessage(changed ? "Hud refresh interval set to " + ticks + " ticks." : "Failed to set interval.");
            return true;
        }
        sendUsage(sender);
        return true;
    }

    private boolean handleWorld(CommandSender sender, String[] args) {
        WorldManagementService service = getWorldService();
        if (service == null) {
            sender.sendMessage("World management unavailable.");
            return true;
        }
        if (args.length < 2) {
            sendUsage(sender);
            return true;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "list" -> sendWorldList(sender, service);
            case "info" -> sendWorldInfo(sender, service, args);
            case "generators" -> sendGeneratorHints(sender, service);
            case "scan" -> sendResult(sender, service.scanUnregisteredWorlds());
            case "import" -> sendResult(sender, service.importUnregisteredWorlds());
            case "create" -> handleWorldCreate(sender, service, args);
            case "clone" -> handleWorldClone(sender, service, args);
            case "load" -> handleWorldLoad(sender, service, args);
            case "unload" -> handleWorldUnload(sender, service, args);
            case "delete" -> handleWorldDelete(sender, service, args);
            case "difficulty" -> handleWorldDifficulty(sender, service, args);
            case "rules" -> sendWorldRules(sender, service, args);
            case "rule" -> handleWorldRule(sender, service, args);
            case "isolated" -> handleWorldIsolated(sender, service, args);
            case "timelock" -> handleWorldTimeLock(sender, service, args);
            case "weatherlock" -> handleWorldWeatherLock(sender, service, args);
            case "tp" -> handleWorldTeleport(sender, service, args);
            case "tpspawn" -> handleWorldTeleportSpawn(sender, service, args);
            default -> sendUsage(sender);
        }
        return true;
    }

    private boolean handleCreateInteractive(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sendError(sender, "Players Only");
            return true;
        }
        WorldManagementService service = getWorldService();
        if (service == null) {
            sendError(sender, "World Management Unavailable");
            return true;
        }
        String targetWorld = requiredArg(sender, args, 2, "/resync portal create <target> <name>");
        if (targetWorld == null || args.length < 4) {
            sendError(sender, "Usage", "/resync portal create <target> <name>");
            return true;
        }
        if (findWorldEntry(service, targetWorld) == null) {
            sendError(sender, "World Not Found", targetWorld);
            return true;
        }
        String portalName = joinArgs(args, 3).trim();
        if (portalName.isBlank()) {
            sendError(sender, "Portal Name Required");
            return true;
        }
        InteractiveSelectionManager selectionManager = plugin.getInteractiveSelectionManager();
        if (selectionManager == null) {
            sendError(sender, "Selection Manager Unavailable");
            return true;
        }
        boolean started = selectionManager.beginSession(new PortalLinkCreationSession(
            player.getUniqueId(),
            service,
            player.getWorld().getName(),
            targetWorld,
            portalName
        ));
        if (started) {
            sendSuccess(sender, "Portal Create Started", portalName);
        } else {
            sendError(sender, "Portal Create Start Failed");
        }
        return true;
    }

    private boolean handleCancelSelection(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sendError(sender, "Players Only");
            return true;
        }
        InteractiveSelectionManager selectionManager = plugin.getInteractiveSelectionManager();
        if (selectionManager == null) {
            sendError(sender, "Selection Manager Unavailable");
            return true;
        }
        if (selectionManager.cancelSession(player.getUniqueId(), "Cancelled")) {
            sendInfo(sender, "Portal Create Cancelled");
        } else {
            sendInfo(sender, "No Active Selection");
        }
        return true;
    }

    private boolean handlePortal(CommandSender sender, String[] args) {
        WorldManagementService service = getWorldService();
        if (service == null) {
            sendError(sender, "World Management Unavailable");
            return true;
        }
        if (args.length < 2) {
            sendUsage(sender);
            return true;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "create" -> handleCreateInteractive(sender, args);
            case "cancel" -> handleCancelSelection(sender);
            case "list" -> sendPortalList(sender, service, optionalArg(args, 2));
            case "info" -> sendPortalInfo(sender, service, args);
            case "delete" -> handlePortalDelete(sender, service, args);
            case "enable" -> handlePortalEnable(sender, service, args);
            case "rename" -> handlePortalRename(sender, service, args);
            case "bounds" -> handlePortalBounds(sender, service, args);
            case "dest" -> handlePortalDestination(sender, service, args);
            case "tp" -> handlePortalTeleport(sender, service, args);
            default -> sendUsage(sender);
        }
        return true;
    }

    private void handleWorldCreate(CommandSender sender, WorldManagementService service, String[] args) {
        String worldName = requiredArg(sender, args, 2, "/resync world create <world> [seed] [environment] [generator]");
        if (worldName == null) {
            return;
        }
        sendResult(sender, service.createWorld(worldName, optionalArg(args, 3), optionalArg(args, 4), optionalArg(args, 5)));
    }

    private void handleWorldLoad(CommandSender sender, WorldManagementService service, String[] args) {
        String worldName = requiredArg(sender, args, 2, "/resync world load <world>");
        if (worldName == null) {
            return;
        }
        sendResult(sender, service.loadWorld(worldName));
    }

    private void handleWorldUnload(CommandSender sender, WorldManagementService service, String[] args) {
        String worldName = requiredArg(sender, args, 2, "/resync world unload <world> [fallbackWorld]");
        if (worldName == null) {
            return;
        }
        sendResult(sender, service.unloadWorld(worldName, optionalArg(args, 3)));
    }

    private void handleWorldClone(CommandSender sender, WorldManagementService service, String[] args) {
        String source = requiredArg(sender, args, 2, "/resync world clone <source> <target> [loadAfterClone]");
        String target = requiredArg(sender, args, 3, "/resync world clone <source> <target> [loadAfterClone]");
        if (source == null || target == null) {
            return;
        }
        boolean loadAfterClone = args.length >= 5 && Boolean.parseBoolean(args[4]);
        sendResult(sender, service.cloneWorldAsync(source, target, loadAfterClone));
    }

    private void handleWorldDelete(CommandSender sender, WorldManagementService service, String[] args) {
        String worldName = requiredArg(sender, args, 2, "/resync world delete <world> <deleteFiles> [fallbackWorld]");
        String deleteFilesRaw = requiredArg(sender, args, 3, "/resync world delete <world> <deleteFiles> [fallbackWorld]");
        if (worldName == null || deleteFilesRaw == null) {
            return;
        }
        sendResult(sender, service.deleteWorld(worldName, Boolean.parseBoolean(deleteFilesRaw), optionalArg(args, 4)));
    }

    private void handleWorldDifficulty(CommandSender sender, WorldManagementService service, String[] args) {
        String worldName = requiredArg(sender, args, 2, "/resync world difficulty <world> [difficulty]");
        if (worldName == null) {
            return;
        }
        WorldRegistryEntry entry = findWorldEntry(service, worldName);
        if (args.length == 3) {
            if (entry == null) {
                sendError(sender, "World Not Found", worldName);
            } else {
                sendInfo(sender, "Difficulty", safeText(entry.getDifficulty()));
            }
            return;
        }
        sendResult(sender, service.setDifficulty(worldName, args[3]));
    }

    private void handleWorldRule(CommandSender sender, WorldManagementService service, String[] args) {
        String worldName = requiredArg(sender, args, 2, "/resync world rule <world> <rule> [value]");
        String ruleName = requiredArg(sender, args, 3, "/resync world rule <world> <rule> [value]");
        if (worldName == null || ruleName == null) {
            return;
        }
        if (args.length == 4) {
            WorldRegistryEntry entry = findWorldEntry(service, worldName);
            if (entry == null) {
                sendError(sender, "World Not Found", worldName);
                return;
            }
            sendInfo(sender, ruleName, safeText(entry.getGameRules().get(ruleName)));
            return;
        }
        sendResult(sender, service.setGameRule(worldName, ruleName, args[4]));
    }

    private void handleWorldIsolated(CommandSender sender, WorldManagementService service, String[] args) {
        String worldName = requiredArg(sender, args, 2, "/resync world isolated <world> [enabled]");
        if (worldName == null) {
            return;
        }
        WorldRegistryEntry entry = findWorldEntry(service, worldName);
        if (args.length == 3) {
            if (entry == null) {
                sendError(sender, "World Not Found", worldName);
            } else {
                sendInfo(sender, "Isolated State", String.valueOf(entry.isIsolatedPlayerState()));
            }
            return;
        }
        sendResult(sender, service.setIsolatedPlayerState(worldName, Boolean.parseBoolean(args[3])));
    }

    private void handleWorldTimeLock(CommandSender sender, WorldManagementService service, String[] args) {
        String worldName = requiredArg(sender, args, 2, "/resync world timelock <world> [enabled] [lockedTime]");
        if (worldName == null) {
            return;
        }
        WorldRegistryEntry entry = findWorldEntry(service, worldName);
        if (args.length == 3) {
            if (entry == null) {
                sendError(sender, "World Not Found", worldName);
            } else {
                sendInfo(sender, "Time Lock", entry.isTimeLockEnabled() + " · " + entry.getLockedTime());
            }
            return;
        }
        boolean enabled = Boolean.parseBoolean(args[3]);
        long lockedTime = entry == null ? 0L : entry.getLockedTime();
        if (args.length >= 5) {
            Long parsed = parseLong(args[4]);
            if (parsed == null) {
                sendError(sender, "Invalid Locked Time", args[4]);
                return;
            }
            lockedTime = parsed;
        }
        sendResult(sender, service.setTimeLock(worldName, enabled, lockedTime));
    }

    private void handleWorldWeatherLock(CommandSender sender, WorldManagementService service, String[] args) {
        String worldName = requiredArg(sender, args, 2, "/resync world weatherlock <world> [enabled] [storm] [thundering]");
        if (worldName == null) {
            return;
        }
        WorldRegistryEntry entry = findWorldEntry(service, worldName);
        if (args.length == 3) {
            if (entry == null) {
                sendError(sender, "World Not Found", worldName);
            } else {
                sendInfo(sender, "Weather Lock", entry.isWeatherLockEnabled() + " · Storm " + entry.isLockedStorm() + " · Thunder " + entry.isLockedThundering());
            }
            return;
        }
        boolean enabled = Boolean.parseBoolean(args[3]);
        boolean storm = args.length >= 5 ? Boolean.parseBoolean(args[4]) : entry != null && entry.isLockedStorm();
        boolean thundering = args.length >= 6 ? Boolean.parseBoolean(args[5]) : entry != null && entry.isLockedThundering();
        sendResult(sender, service.setWeatherLock(worldName, enabled, storm, thundering));
    }

    private void handleWorldTeleport(CommandSender sender, WorldManagementService service, String[] args) {
        String playerName = requiredArg(sender, args, 2, "/resync world tp <player> <world> [x] [y] [z] [yaw] [pitch]");
        String worldName = requiredArg(sender, args, 3, "/resync world tp <player> <world> [x] [y] [z] [yaw] [pitch]");
        if (playerName == null || worldName == null) {
            return;
        }
        if (args.length == 4) {
            sendResult(sender, service.teleportPlayerToWorldSpawn(playerName, worldName));
            return;
        }
        if (args.length < 7) {
            sendError(sender, "Usage", "/resync world tp <player> <world> [x] [y] [z] [yaw] [pitch]");
            return;
        }
        Double x = parseDouble(args[4]);
        Double y = parseDouble(args[5]);
        Double z = parseDouble(args[6]);
        Float yaw = args.length >= 8 ? parseFloat(args[7]) : null;
        Float pitch = args.length >= 9 ? parseFloat(args[8]) : null;
        if (x == null || y == null || z == null || (args.length >= 8 && yaw == null) || (args.length >= 9 && pitch == null)) {
            sendError(sender, "Invalid Teleport Coordinates");
            return;
        }
        sendResult(sender, service.teleportPlayerToWorld(playerName, worldName, x, y, z, yaw, pitch));
    }

    private void handleWorldTeleportSpawn(CommandSender sender, WorldManagementService service, String[] args) {
        String playerName = requiredArg(sender, args, 2, "/resync world tpspawn <player> <world>");
        String worldName = requiredArg(sender, args, 3, "/resync world tpspawn <player> <world>");
        if (playerName == null || worldName == null) {
            return;
        }
        sendResult(sender, service.teleportPlayerToWorldSpawn(playerName, worldName));
    }

    private void handlePortalEnable(CommandSender sender, WorldManagementService service, String[] args) {
        String portalId = requiredArg(sender, args, 2, "/resync portal enable <portal> <enabled>");
        String enabledRaw = requiredArg(sender, args, 3, "/resync portal enable <portal> <enabled>");
        if (portalId == null || enabledRaw == null) {
            return;
        }
        sendResult(sender, service.setPortalEnabled(portalId, Boolean.parseBoolean(enabledRaw)));
    }

    private void handlePortalDelete(CommandSender sender, WorldManagementService service, String[] args) {
        String portalId = requiredArg(sender, args, 2, "/resync portal delete <portal>");
        if (portalId == null) {
            return;
        }
        sendResult(sender, service.deletePortal(portalId));
    }

    private void handlePortalRename(CommandSender sender, WorldManagementService service, String[] args) {
        String portalId = requiredArg(sender, args, 2, "/resync portal rename <portal> <newName>");
        if (portalId == null || args.length < 4) {
            sendError(sender, "Usage", "/resync portal rename <portal> <newName>");
            return;
        }
        WorldPortal portal = service.getPortal(portalId);
        if (portal == null) {
            sendError(sender, "Portal Not Found", portalId);
            return;
        }
        portal.setPortalName(joinArgs(args, 3));
        sendResult(sender, service.resizePortal(portal));
    }

    private void handlePortalBounds(CommandSender sender, WorldManagementService service, String[] args) {
        String portalId = requiredArg(sender, args, 2, "/resync portal bounds <portal> [sourceWorld minX minY minZ maxX maxY maxZ]");
        if (portalId == null) {
            return;
        }
        if (args.length < 10) {
            sendError(sender, "Usage", "/resync portal bounds <portal> [sourceWorld minX minY minZ maxX maxY maxZ]");
            return;
        }
        Double minX = parseDouble(args[4]);
        Double minY = parseDouble(args[5]);
        Double minZ = parseDouble(args[6]);
        Double maxX = parseDouble(args[7]);
        Double maxY = parseDouble(args[8]);
        Double maxZ = parseDouble(args[9]);
        if (minX == null || minY == null || minZ == null || maxX == null || maxY == null || maxZ == null) {
            sendError(sender, "Invalid Portal Bounds");
            return;
        }
        sendResult(sender, service.setPortalBounds(portalId, args[3], minX, minY, minZ, maxX, maxY, maxZ));
    }

    private void handlePortalDestination(CommandSender sender, WorldManagementService service, String[] args) {
        String portalId = requiredArg(sender, args, 2, "/resync portal dest <portal> [world x y z yaw pitch]");
        if (portalId == null) {
            return;
        }
        if (args.length == 3) {
            if (!(sender instanceof Player player)) {
                sendError(sender, "Players Only");
                return;
            }
            Location location = player.getLocation();
            sendResult(sender, service.setPortalDestination(portalId, player.getWorld().getName(), location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch()));
            return;
        }
        if (args.length < 7) {
            sendError(sender, "Usage", "/resync portal dest <portal> [world x y z yaw pitch]");
            return;
        }
        Double x = parseDouble(args[4]);
        Double y = parseDouble(args[5]);
        Double z = parseDouble(args[6]);
        Float yaw = args.length >= 8 ? parseFloat(args[7]) : 0f;
        Float pitch = args.length >= 9 ? parseFloat(args[8]) : 0f;
        if (x == null || y == null || z == null || yaw == null || pitch == null) {
            sendError(sender, "Invalid Destination");
            return;
        }
        sendResult(sender, service.setPortalDestination(portalId, args[3], x, y, z, yaw, pitch));
    }

    private void handlePortalTeleport(CommandSender sender, WorldManagementService service, String[] args) {
        String playerName = requiredArg(sender, args, 2, "/resync portal tp <player> <portal>");
        String portalId = requiredArg(sender, args, 3, "/resync portal tp <player> <portal>");
        if (playerName == null || portalId == null) {
            return;
        }
        sendResult(sender, service.teleportPlayerToPortal(playerName, portalId));
    }

    private void sendWorldList(CommandSender sender, WorldManagementService service) {
        List<WorldRegistryEntry> worlds = new ArrayList<>(service.createSnapshot().getWorlds());
        worlds.sort(Comparator.comparing(WorldRegistryEntry::getWorldName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        if (worlds.isEmpty()) {
            sendInfo(sender, "No Worlds Found");
            return;
        }
        for (WorldRegistryEntry world : worlds) {
            if (world == null) {
                continue;
            }
            sendInfo(sender, safeText(world.getWorldName()) + " · " + (world.isLoaded() ? "Loaded" : "Unloaded") + " · " + safeText(world.getEnvironment()) + " · " + safeText(world.getDifficulty()));
        }
    }

    private void sendGeneratorHints(CommandSender sender, WorldManagementService service) {
        List<String> hints = service.createSnapshot().getGeneratorHints();
        if (hints.isEmpty()) {
            sendInfo(sender, "No Generator Hints Found");
            return;
        }
        sendInfo(sender, "Generator Hints");
        for (String hint : hints) {
            sendInfo(sender, "- " + hint);
        }
    }

    private void sendWorldInfo(CommandSender sender, WorldManagementService service, String[] args) {
        String worldName = requiredArg(sender, args, 2, "/resync world info <world>");
        if (worldName == null) {
            return;
        }
        WorldRegistryEntry world = findWorldEntry(service, worldName);
        if (world == null) {
            sendError(sender, "World Not Found", worldName);
            return;
        }
        sendInfo(sender, "World", world.getWorldName());
        sendInfo(sender, "Environment", safeText(world.getEnvironment()));
        sendInfo(sender, "Generator", safeText(world.getGenerator()).isBlank() ? "Default" : safeText(world.getGenerator()));
        sendInfo(sender, "Difficulty", safeText(world.getDifficulty()));
        sendInfo(sender, "State", world.isLoaded() ? "Loaded" : "Unloaded");
        sendInfo(sender, "Isolated State", String.valueOf(world.isIsolatedPlayerState()));
        sendInfo(sender, "Time Lock", world.isTimeLockEnabled() + " · " + world.getLockedTime());
        sendInfo(sender, "Weather Lock", world.isWeatherLockEnabled() + " · Storm " + world.isLockedStorm() + " · Thunder " + world.isLockedThundering());
        sendInfo(sender, "Game Rules", String.valueOf(world.getGameRules().size()));
    }

    private void sendWorldRules(CommandSender sender, WorldManagementService service, String[] args) {
        String worldName = requiredArg(sender, args, 2, "/resync world rules <world>");
        if (worldName == null) {
            return;
        }
        WorldRegistryEntry world = findWorldEntry(service, worldName);
        if (world == null) {
            sendError(sender, "World Not Found", worldName);
            return;
        }
        if (world.getGameRules().isEmpty()) {
            sendInfo(sender, "No Game Rules Found");
            return;
        }
        List<Map.Entry<String, String>> entries = new ArrayList<>(world.getGameRules().entrySet());
        entries.sort(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER));
        for (Map.Entry<String, String> entry : entries) {
            sendInfo(sender, entry.getKey(), safeText(entry.getValue()));
        }
    }

    private void sendPortalList(CommandSender sender, WorldManagementService service, String worldName) {
        List<WorldPortal> portals = worldName == null || worldName.isBlank() ? service.getPortals() : service.getPortalsByWorld(worldName);
        if (portals.isEmpty()) {
            sendInfo(sender, "No Portals Found");
            return;
        }
        for (WorldPortal portal : portals) {
            if (portal == null) {
                continue;
            }
            sendInfo(sender, safeText(portal.getPortalName()) + " · " + safeText(portal.getSourceWorld()) + " -> " + safeText(portal.getDestinationWorld()) + " · " + (portal.isEnabled() ? "Enabled" : "Disabled"));
        }
    }

    private void sendPortalInfo(CommandSender sender, WorldManagementService service, String[] args) {
        String portalId = requiredArg(sender, args, 2, "/resync portal info <portal>");
        if (portalId == null) {
            return;
        }
        WorldPortal portal = service.getPortal(portalId);
        if (portal == null) {
            sendError(sender, "Portal Not Found", portalId);
            return;
        }
        sendInfo(sender, "Portal", safeText(portal.getPortalName()));
        sendInfo(sender, "Source", safeText(portal.getSourceWorld()));
        sendInfo(sender, "Bounds", formatNumber(portal.getMinX()) + "," + formatNumber(portal.getMinY()) + "," + formatNumber(portal.getMinZ()) + " -> " + formatNumber(portal.getMaxX()) + "," + formatNumber(portal.getMaxY()) + "," + formatNumber(portal.getMaxZ()));
        sendInfo(sender, "Destination", safeText(portal.getDestinationWorld()) + " · " + formatNumber(portal.getDestinationX()) + "," + formatNumber(portal.getDestinationY()) + "," + formatNumber(portal.getDestinationZ()));
        sendInfo(sender, "Rotation", formatNumber(portal.getDestinationYaw()) + " · " + formatNumber(portal.getDestinationPitch()));
        sendInfo(sender, "State", portal.isEnabled() ? "Enabled" : "Disabled");
    }

    private void sendResult(CommandSender sender, WorldOperationResult result) {
        if (result == null) {
            sendError(sender, "Operation Returned No Result");
            return;
        }
        if (result.isSuccess()) {
            sendSuccess(sender, prettyAction(result.getAction()), prettyMessage(result.getMessage()));
        } else {
            sendError(sender, prettyAction(result.getAction()), prettyMessage(result.getMessage()));
        }
        Map<String, Object> data = new LinkedHashMap<>(result.getData());
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            if (entry.getValue() instanceof WorldPortal portal) {
                sendInfo(sender, "Portal", safeText(portal.getPortalName()));
                continue;
            }
            if (entry.getValue() instanceof WorldRegistryEntry world) {
                sendInfo(sender, prettyKey(entry.getKey()), safeText(world.getWorldName()));
                continue;
            }
            if ("portalId".equalsIgnoreCase(entry.getKey()) || "playerId".equalsIgnoreCase(entry.getKey())) {
                continue;
            }
            sendInfo(sender, prettyKey(entry.getKey()), String.valueOf(entry.getValue()));
        }
    }

    private Player findPlayer(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Player exact = Bukkit.getPlayerExact(name);
        return exact != null ? exact : Bukkit.getPlayer(name);
    }

    private WorldManagementService getWorldService() {
        if (plugin.getV2Server() == null) {
            return null;
        }
        return plugin.getV2Server().getWorldManagementService();
    }

    private FlowStorage getStorage() {
        if (plugin.getV2Server() == null) {
            return null;
        }
        return plugin.getV2Server().getFlowStorage();
    }

    private WorldRegistryEntry findWorldEntry(WorldManagementService service, String worldName) {
        if (service == null || worldName == null) {
            return null;
        }
        for (WorldRegistryEntry entry : service.createSnapshot().getWorlds()) {
            if (entry != null && entry.getWorldName() != null && entry.getWorldName().equalsIgnoreCase(worldName)) {
                return entry;
            }
        }
        return null;
    }

    private List<String> worldNames() {
        WorldManagementService service = getWorldService();
        if (service == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (WorldRegistryEntry entry : service.createSnapshot().getWorlds()) {
            if (entry != null && entry.getWorldName() != null) {
                values.add(entry.getWorldName());
            }
        }
        values.sort(String.CASE_INSENSITIVE_ORDER);
        return values;
    }

    private List<String> portalIdentifiers() {
        WorldManagementService service = getWorldService();
        if (service == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (WorldPortal portal : service.getPortals()) {
            if (portal == null) {
                continue;
            }
            if (portal.getPortalName() != null && !portal.getPortalName().isBlank()) {
                values.add(portal.getPortalName());
            }
            if (portal.getPortalId() != null && !portal.getPortalId().isBlank()) {
                values.add(portal.getPortalId());
            }
        }
        values.sort(String.CASE_INSENSITIVE_ORDER);
        return values;
    }

    private List<String> gameRuleNames() {
        WorldManagementService service = getWorldService();
        if (service == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (WorldGameRuleDescriptor descriptor : service.getGameRuleDescriptors()) {
            if (descriptor != null && descriptor.getName() != null) {
                values.add(descriptor.getName());
            }
        }
        values.sort(String.CASE_INSENSITIVE_ORDER);
        return values;
    }

    private List<String> gameRuleValueOptions(String ruleName) {
        WorldManagementService service = getWorldService();
        if (service == null || ruleName == null) {
            return List.of();
        }
        for (WorldGameRuleDescriptor descriptor : service.getGameRuleDescriptors()) {
            if (descriptor != null && ruleName.equalsIgnoreCase(descriptor.getName())) {
                if ("boolean".equalsIgnoreCase(descriptor.getType())) {
                    return booleanOptions();
                }
                if ("integer".equalsIgnoreCase(descriptor.getType())) {
                    return List.of("0", "1", "10", "100");
                }
                return List.of();
            }
        }
        return List.of();
    }

    private List<String> difficultyOptions() {
        return List.of("PEACEFUL", "EASY", "NORMAL", "HARD");
    }

    private List<String> environmentOptions() {
        return List.of("NORMAL", "NETHER", "THE_END");
    }

    private List<String> booleanOptions() {
        return List.of("true", "false");
    }

    private List<String> onlinePlayers() {
        List<String> names = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            names.add(online.getName());
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private List<String> filter(List<String> values, String input) {
        String needle = input == null ? "" : input.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String value : values) {
            if (value != null && value.toLowerCase(Locale.ROOT).startsWith(needle) && !out.contains(value)) {
                out.add(value);
            }
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private String requiredArg(CommandSender sender, String[] args, int index, String usage) {
        if (index >= args.length) {
            sendError(sender, "Usage", usage);
            return null;
        }
        return args[index];
    }

    private String optionalArg(String[] args, int index) {
        return index < args.length ? args[index] : null;
    }

    private String joinArgs(String[] args, int startIndex) {
        StringBuilder builder = new StringBuilder();
        for (int index = startIndex; index < args.length; index++) {
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(args[index]);
        }
        return builder.toString();
    }

    private Integer parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Double parseDouble(String value) {
        try {
            return Double.parseDouble(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Float parseFloat(String value) {
        try {
            return Float.parseFloat(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String formatNumber(double value) {
        if (Math.rint(value) == value) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private List<String> generatorHints() {
        WorldManagementService service = getWorldService();
        return service == null ? List.of() : service.createSnapshot().getGeneratorHints();
    }

    private void sendInfo(CommandSender sender, String message) {
        sender.sendMessage(Component.text("[ReSync] ", NamedTextColor.DARK_GRAY).append(Component.text(message, NamedTextColor.GRAY)));
    }

    private void sendInfo(CommandSender sender, String label, String value) {
        sender.sendMessage(Component.text("[ReSync] ", NamedTextColor.DARK_GRAY)
            .append(Component.text(label + " ", NamedTextColor.GRAY))
            .append(Component.text(value, NamedTextColor.WHITE)));
    }

    private void sendSuccess(CommandSender sender, String message) {
        sender.sendMessage(Component.text("[ReSync] ", NamedTextColor.DARK_GRAY).append(Component.text(message, NamedTextColor.GREEN)));
    }

    private void sendSuccess(CommandSender sender, String label, String value) {
        sender.sendMessage(Component.text("[ReSync] ", NamedTextColor.DARK_GRAY)
            .append(Component.text(label + " ", NamedTextColor.GREEN))
            .append(Component.text(value, NamedTextColor.WHITE)));
    }

    private void sendError(CommandSender sender, String message) {
        sender.sendMessage(Component.text("[ReSync] ", NamedTextColor.DARK_GRAY).append(Component.text(message, NamedTextColor.RED)));
    }

    private void sendError(CommandSender sender, String label, String value) {
        sender.sendMessage(Component.text("[ReSync] ", NamedTextColor.DARK_GRAY)
            .append(Component.text(label + " ", NamedTextColor.RED))
            .append(Component.text(value, NamedTextColor.WHITE)));
    }

    private String prettyAction(String action) {
        return prettyKey(action);
    }

    private String prettyMessage(String message) {
        return prettyKey(message);
    }

    private String prettyKey(String key) {
        if (key == null || key.isBlank()) {
            return "Value";
        }
        String normalized = key.replaceAll("([a-z])([A-Z])", "$1 $2").replace('_', ' ').trim();
        String[] parts = normalized.split("\\s+");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
            if (part.length() > 1) {
                builder.append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return builder.isEmpty() ? "Value" : builder.toString();
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(Component.text("/resync portal create <target> <name>", NamedTextColor.WHITE));
        sender.sendMessage(Component.text("/resync portal cancel", NamedTextColor.WHITE));
        sender.sendMessage("/resync scoreboard list");
        sender.sendMessage("/resync scoreboard show <player> <scoreboardId> [usePapi]");
        sender.sendMessage("/resync scoreboard hide <player>");
        sender.sendMessage("/resync scoreboard default [<scoreboardId|none> [usePapi]]");
        sender.sendMessage("/resync tab list");
        sender.sendMessage("/resync tab apply <tabId> [player] [usePapi]");
        sender.sendMessage("/resync tab clear [player]");
        sender.sendMessage("/resync tab default [<tabId|none> [usePapi]]");
        sender.sendMessage("/resync tab interval [ticks]");
        sender.sendMessage("/resync world list");
        sender.sendMessage("/resync world info <world>");
        sender.sendMessage("/resync world create <world> [seed] [environment] [generator]");
        sender.sendMessage("/resync world clone <source> <target> [loadAfterClone]");
        sender.sendMessage("/resync world load <world>");
        sender.sendMessage("/resync world unload <world> [fallbackWorld]");
        sender.sendMessage("/resync world delete <world> <deleteFiles> [fallbackWorld]");
        sender.sendMessage("/resync world difficulty <world> [difficulty]");
        sender.sendMessage("/resync world rules <world>");
        sender.sendMessage("/resync world rule <world> <rule> [value]");
        sender.sendMessage("/resync world isolated <world> [enabled]");
        sender.sendMessage("/resync world timelock <world> [enabled] [lockedTime]");
        sender.sendMessage("/resync world weatherlock <world> [enabled] [storm] [thundering]");
        sender.sendMessage("/resync world tp <player> <world> [x] [y] [z] [yaw] [pitch]");
        sender.sendMessage("/resync world tpspawn <player> <world>");
        sender.sendMessage("/resync world generators");
        sender.sendMessage("/resync portal create <target> <name>");
        sender.sendMessage("/resync portal cancel");
        sender.sendMessage("/resync portal list [world]");
        sender.sendMessage("/resync portal info <portal>");
        sender.sendMessage("/resync portal enable <portal> <enabled>");
        sender.sendMessage("/resync portal rename <portal> <newName>");
        sender.sendMessage("/resync portal bounds <portal> [sourceWorld minX minY minZ maxX maxY maxZ]");
        sender.sendMessage("/resync portal dest <portal> [world x y z yaw pitch]");
        sender.sendMessage("/resync portal tp <player> <portal>");
        sender.sendMessage("/resync portal delete <portal>");
    }
}
