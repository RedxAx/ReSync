package restudio.resync.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import restudio.flow.data.CustomContentDefinition;
import restudio.flow.data.ScoreboardDefinition;
import restudio.flow.data.TabDefinition;
import restudio.resync.ReSync;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.customcontent.CustomContentService;
import restudio.resync.customcontent.CustomContentStorage;
import restudio.resync.dialog.DialogService;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.TabListService;
import restudio.resync.flow.ScoreboardTemplateManager;
import restudio.resync.flow.diagnostics.ProgrammabilityAcceptanceSnapshot;
import restudio.resync.modules.FlowRuntimeModule;
import restudio.resync.modules.FlowModule;
import restudio.resync.runtime.LootTableService;
import restudio.resync.runtime.NpcService;
import restudio.resync.runtime.TradeProfileService;
import restudio.resync.selection.InteractiveSelectionManager;
import restudio.resync.server.ReSyncServer;
import restudio.resync.world.WorldGameRuleDescriptor;
import restudio.resync.world.WorldGeneratorDescriptor;
import restudio.resync.world.WorldInventoryGroup;
import restudio.resync.world.WorldManagementService;
import restudio.resync.world.WorldOperationResult;
import restudio.resync.world.PortalLinkCreationSession;
import restudio.resync.world.WorldPortal;
import restudio.resync.world.WorldProfileSettings;
import restudio.resync.world.WorldRegistryEntry;
import restudio.resync.world.WorldSignPortal;
import restudio.resync.resources.ReSyncResourceCatalog;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

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
            case "status" -> handleStatus(sender);
            case "network" -> handleNetwork(sender, args);
            case "scoreboard" -> handleScoreboard(sender, args);
            case "tab" -> handleTab(sender, args);
            case "world" -> handleWorld(sender, args);
            case "portal" -> handlePortal(sender, args);
            case "flow" -> handleFlow(sender, args);
            case "quickedit" -> handleQuickEdit(sender);
            case "item" -> handleItem(sender, args);
            case "npc" -> handleNpc(sender, args);
            case "trade" -> handleTrade(sender, args);
            case "resource" -> handleResource(sender, args);
            default -> {
                sendUsage(sender);
                yield true;
            }
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("status", "network", "scoreboard", "tab", "world", "portal", "flow", "quickedit", "item", "npc", "trade", "resource"), args[0]);
        }
        String group = args[0].toLowerCase(Locale.ROOT);
        return switch (group) {
            case "scoreboard" -> tabCompleteScoreboard(args);
            case "tab" -> tabCompleteTab(args);
            case "world" -> tabCompleteWorld(args);
            case "portal" -> tabCompletePortal(args);
            case "flow" -> tabCompleteFlow(args);
            case "quickedit" -> List.of();
            case "item" -> tabCompleteItem(args);
            case "npc" -> tabCompleteNpc(args);
            case "trade" -> tabCompleteTrade(args);
            case "resource" -> tabCompleteResource(args);
            case "network" -> args.length == 2 ? filter(List.of("reload"), args[1]) : List.of();
            default -> List.of();
        };
    }

    private boolean handleNetwork(CommandSender sender, String[] args) {
        if (args.length != 2 || !"reload".equalsIgnoreCase(args[1])) {
            sendError(sender, "Usage", "/resync network reload");
            return true;
        }
        try {
            plugin.reloadNetworkState();
            sendSuccess(sender, "Network Sync Reloaded");
        } catch (Exception exception) {
            sendError(sender, "Network Sync Reload Failed", exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
        }
        return true;
    }

    private boolean handleStatus(CommandSender sender) {
        ReSyncServer server = plugin.getReSyncServer();
        if (server == null) {
            sendError(sender, "Server Not Initialized");
            return true;
        }
        Map<String, Object> status = server.readinessSnapshot();
        sendInfo(sender, "ReSync Status");
        sendInfo(sender, "API", Boolean.TRUE.equals(status.get("apiEnabled")) ? "Enabled" : "Disabled");
        sendInfo(sender, "Bind", String.valueOf(status.get("bindHost")));
        sendInfo(sender, "Clients", status.get("connectedClients") + "/" + status.get("maxConnections"));
        sendInfo(sender, "Auth", String.valueOf(status.get("authMode")));
        sendInfo(sender, "Open Connections", String.valueOf(status.get("openConnections")));
        sendInfo(sender, "Queue", status.get("queueMaxGlobalRequests") + " global / " + status.get("queueMaxRequestsPerClient") + " per client");
        sendInfo(sender, "Memory", status.get("sessionMemoryBytes") + "/" + status.get("sessionMemoryLimitBytes"));
        return true;
    }

    private boolean handleQuickEdit(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sendError(sender, "Players Only");
            return true;
        }
        FlowModule module = flowModule();
        if (module == null) {
            sendError(sender, "Flow Module Not Initialized");
            return true;
        }
        FlowModule.QuickEditResult result = module.startQuickEdit(player);
        if (result.success()) {
            sendSuccess(sender, result.message());
        } else {
            sendError(sender, result.message());
        }
        return true;
    }

    private boolean handleItem(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendUsageLine(sender, "/resync item list");
            sendUsageLine(sender, "/resync item give <player> <itemId> [amount]");
            return true;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if ("list".equals(action)) {
            CustomContentStorage storage = getCustomContentStorage();
            if (storage == null) {
                sendError(sender, "Content Storage Unavailable");
                return true;
            }
            List<CustomContentDefinition> items = storage.getAll().stream()
                .filter(definition -> definition != null && List.of("item", "armor", "block").contains(safeText(definition.getType()).toLowerCase(Locale.ROOT)))
                .sorted(Comparator.comparing(CustomContentDefinition::getId, String.CASE_INSENSITIVE_ORDER))
                .toList();
            if (items.isEmpty()) {
                sendInfo(sender, "No Custom Items Found");
                return true;
            }
            for (CustomContentDefinition item : items) {
                sendInfo(sender, item.getId(), safeText(item.getType()) + " · " + safeText(item.getMaterial()) + " · " + safeText(item.getDisplayName()));
            }
            return true;
        }
        if (!"give".equals(action) || args.length < 4) {
            sendUsageLine(sender, "/resync item give <player> <itemId> [amount]");
            return true;
        }
        Player target = findPlayer(args[2]);
        if (target == null) {
            sendError(sender, "Player Not Found", args[2]);
            return true;
        }
        String itemId = args[3];
        int amount = 1;
        if (args.length >= 5) {
            Integer parsed = parseInt(args[4]);
            if (parsed == null || parsed < 1) {
                sendError(sender, "Invalid Amount", args[4]);
                return true;
            }
            amount = parsed;
        }
        CustomContentStorage storage = getCustomContentStorage();
        CustomContentService service = getCustomContentService();
        if (storage == null || service == null) {
            sendError(sender, "Content System Unavailable");
            return true;
        }
        CustomContentDefinition definition = storage.get(itemId);
        if (definition == null) {
            sendError(sender, "Item Not Found", itemId);
            return true;
        }
        ItemStack item = service.createItem(itemId, amount);
        if (item == null) {
            sendError(sender, "Item Create Failed", itemId);
            return true;
        }
        target.getInventory().addItem(item);
        sendSuccess(sender, "Item Given", itemId + " -> " + target.getName() + " x" + amount);
        return true;
    }

    private List<String> tabCompleteItem(String[] args) {
        if (args.length == 2) {
            return filter(List.of("list", "give"), args[1]);
        }
        if (args.length == 3 && "give".equalsIgnoreCase(args[1])) {
            return filter(onlinePlayers(), args[2]);
        }
        if (args.length == 4 && "give".equalsIgnoreCase(args[1])) {
            return filter(customContentIds(), args[3]);
        }
        if (args.length == 5 && "give".equalsIgnoreCase(args[1])) {
            return filter(List.of("1", "8", "16", "32", "64"), args[4]);
        }
        return List.of();
    }

    private boolean handleNpc(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendUsageLine(sender, "/resync npc list");
            sendUsageLine(sender, "/resync npc spawn <npcId> [player]");
            sendUsageLine(sender, "/resync npc despawn <npcId>");
            sendUsageLine(sender, "/resync npc open <npcId> [player]");
            sendUsageLine(sender, "/resync npc trade <npcId> <profileId>");
            return true;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        ReSyncJsonResourceStorage storage = getJsonResourceStorage();
        NpcService service = plugin.getReSyncServer().getModuleContext().getService(NpcService.class);
        if (storage == null || service == null) {
            sendError(sender, "NPC System Unavailable");
            return true;
        }
        if ("list".equals(action)) {
            List<String> ids = storage.listIds(ReSyncResourceCatalog.NPC_DEFINITION).stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
            sendInfo(sender, "NPCs", ids.isEmpty() ? "None" : String.join(", ", ids));
            return true;
        }
        String id = requiredArg(sender, args, 2, "/resync npc " + action + " <npcId>");
        if (id == null) {
            return true;
        }
        if (storage.get(ReSyncResourceCatalog.NPC_DEFINITION, id) == null) {
            sendError(sender, "NPC Not Found", id);
            return true;
        }
        return switch (action) {
            case "spawn" -> {
                Player target = targetPlayerArg(sender, args, 3, "/resync npc spawn " + id + " [player]");
                if (target != null) {
                    service.spawn(id, target.getLocation());
                }
                if (target != null && service.isActive(id)) {
                    sendSuccess(sender, "NPC Spawned", id);
                } else if (target != null) {
                    sendError(sender, "NPC Spawn Failed", service.spawnFailureReason(id));
                }
                yield true;
            }
            case "despawn" -> {
                if (service.despawn(id)) {
                    sendSuccess(sender, "NPC Despawned", id);
                } else {
                    sendError(sender, "NPC Not Active", id);
                }
                yield true;
            }
            case "open" -> {
                Player target = targetPlayerArg(sender, args, 3, "/resync npc open " + id + " [player]");
                if (target != null && service.open(target, id)) {
                    sendSuccess(sender, "NPC Opened", id);
                } else if (target != null) {
                    sendError(sender, "NPC Open Failed", id);
                }
                yield true;
            }
            case "trade", "profile" -> {
                String profileId = requiredArg(sender, args, 3, "/resync npc trade " + id + " <profileId>");
                if (profileId == null) {
                    yield true;
                }
                if (storage.get(ReSyncResourceCatalog.TRADE_PROFILE, profileId) == null) {
                    sendError(sender, "Trade Profile Not Found", profileId);
                    yield true;
                }
                if (service.setProfile(id, profileId)) {
                    sendSuccess(sender, "NPC Trade Set", id + " -> " + profileId);
                } else {
                    sendError(sender, "NPC Trade Failed", id);
                }
                yield true;
            }
            default -> {
                sendUsageLine(sender, "/resync npc <list|spawn|despawn|open|trade> ...");
                yield true;
            }
        };
    }

    private List<String> tabCompleteNpc(String[] args) {
        if (args.length == 2) {
            return filter(List.of("list", "spawn", "despawn", "open", "trade"), args[1]);
        }
        ReSyncJsonResourceStorage storage = getJsonResourceStorage();
        if (storage == null) {
            return List.of();
        }
        if (args.length == 3 && List.of("spawn", "despawn", "open", "trade").contains(args[1].toLowerCase(Locale.ROOT))) {
            return filter(storage.listIds(ReSyncResourceCatalog.NPC_DEFINITION), args[2]);
        }
        if (args.length == 4 && List.of("spawn", "open").contains(args[1].toLowerCase(Locale.ROOT))) {
            return filter(onlinePlayers(), args[3]);
        }
        if (args.length == 4 && "trade".equalsIgnoreCase(args[1])) {
            return filter(storage.listIds(ReSyncResourceCatalog.TRADE_PROFILE), args[3]);
        }
        return List.of();
    }

    private Player targetPlayerArg(CommandSender sender, String[] args, int index, String usage) {
        if (args.length > index && args[index] != null && !args[index].isBlank()) {
            Player target = Bukkit.getPlayerExact(args[index]);
            if (target == null) {
                sendError(sender, "Player Not Found", args[index]);
                return null;
            }
            return target;
        }
        if (sender instanceof Player player) {
            return player;
        }
        sendError(sender, "Player Required", usage);
        return null;
    }

    private boolean handleTrade(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendUsageLine(sender, "/resync trade list");
            sendUsageLine(sender, "/resync trade apply <profileId>");
            sendUsageLine(sender, "/resync trade spawn <profileId> [player]");
            sendUsageLine(sender, "/resync trade open <profileId> [player]");
            return true;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        ReSyncJsonResourceStorage storage = getJsonResourceStorage();
        if (storage == null) {
            sendError(sender, "Trade System Unavailable");
            return true;
        }
        if ("list".equals(action)) {
            List<String> ids = storage.listIds(ReSyncResourceCatalog.TRADE_PROFILE).stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
            sendInfo(sender, "Trade Profiles", ids.isEmpty() ? "None" : String.join(", ", ids));
            return true;
        }
        if (!List.of("apply", "spawn", "open").contains(action)) {
            sendUsageLine(sender, "/resync trade <list|apply|spawn|open> ...");
            return true;
        }
        String id = requiredArg(sender, args, 2, "/resync trade " + action + " <profileId>");
        if (id == null) {
            return true;
        }
        if (storage.get(ReSyncResourceCatalog.TRADE_PROFILE, id) == null) {
            sendError(sender, "Trade Profile Not Found", id);
            return true;
        }
        return switch (action) {
            case "spawn" -> spawnTradeResource(sender, id, args);
            case "open" -> openTradeResource(sender, id, args);
            default -> applyTradeResource(sender, id);
        };
    }

    private List<String> tabCompleteTrade(String[] args) {
        if (args.length == 2) {
            return filter(List.of("list", "apply", "spawn", "open"), args[1]);
        }
        ReSyncJsonResourceStorage storage = getJsonResourceStorage();
        if (storage == null) {
            return List.of();
        }
        if (args.length == 3 && List.of("apply", "spawn", "open").contains(args[1].toLowerCase(Locale.ROOT))) {
            return filter(storage.listIds(ReSyncResourceCatalog.TRADE_PROFILE), args[2]);
        }
        if (args.length == 4 && List.of("spawn", "open").contains(args[1].toLowerCase(Locale.ROOT))) {
            return filter(onlinePlayers(), args[3]);
        }
        return List.of();
    }

    private boolean handleResource(CommandSender sender, String[] args) {
        ReSyncJsonResourceStorage storage = getJsonResourceStorage();
        if (storage == null) {
            sendError(sender, "Resource Storage Unavailable");
            return true;
        }
        if (args.length < 2) {
            sendUsageLine(sender, "/resync resource types");
            sendUsageLine(sender, "/resync resource list <type>");
            sendUsageLine(sender, "/resync resource info <type> <id>");
            sendUsageLine(sender, "/resync resource create <type> <id>");
            sendUsageLine(sender, "/resync resource set <type> <id> <field> <value>");
            sendUsageLine(sender, "/resync resource delete <type> <id>");
            return true;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        return switch (action) {
            case "types" -> {
                sendInfo(sender, "Resource Types", String.join(", ", storage.resourceTypes()));
                yield true;
            }
            case "list" -> handleResourceList(sender, storage, args);
            case "info" -> handleResourceInfo(sender, storage, args);
            case "create" -> handleResourceCreate(sender, storage, args);
            case "set" -> handleResourceSet(sender, storage, args);
            case "apply" -> handleResourceApply(sender, storage, args);
            case "delete" -> handleResourceDelete(sender, storage, args);
            default -> {
                sendError(sender, "Unknown resource command: " + action);
                yield true;
            }
        };
    }

    private boolean handleResourceList(CommandSender sender, ReSyncJsonResourceStorage storage, String[] args) {
        String type = resourceTypeArg(sender, storage, args, 2, "/resync resource list <type>");
        if (type == null) {
            return true;
        }
        List<String> ids = storage.listIds(type).stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        if (ids.isEmpty()) {
            sendInfo(sender, "No Resources", type);
            return true;
        }
        sendInfo(sender, prettyResourceType(type), String.join(", ", ids));
        return true;
    }

    private boolean handleResourceInfo(CommandSender sender, ReSyncJsonResourceStorage storage, String[] args) {
        String type = resourceTypeArg(sender, storage, args, 2, "/resync resource info <type> <id>");
        if (type == null) {
            return true;
        }
        String id = requiredArg(sender, args, 3, "/resync resource info <type> <id>");
        if (id == null) {
            return true;
        }
        JsonObject resource = storage.get(type, id);
        if (resource == null) {
            sendError(sender, "Resource Not Found", id);
            return true;
        }
        sendInfo(sender, prettyResourceType(type), id);
        for (Map.Entry<String, JsonElement> entry : resource.entrySet()) {
            sendInfo(sender, entry.getKey(), shortJsonValue(entry.getValue()));
        }
        return true;
    }

    private boolean handleResourceCreate(CommandSender sender, ReSyncJsonResourceStorage storage, String[] args) {
        String type = resourceTypeArg(sender, storage, args, 2, "/resync resource create <type> <id>");
        if (type == null) {
            return true;
        }
        String id = requiredArg(sender, args, 3, "/resync resource create <type> <id>");
        if (id == null) {
            return true;
        }
        if (storage.get(type, id) != null) {
            sendError(sender, "Resource Already Exists", id);
            return true;
        }
        JsonObject resource = defaultResource(type, id);
        storage.save(type, resource);
        sendSuccess(sender, "Resource Created", prettyResourceType(type) + " " + id);
        return true;
    }

    private boolean handleResourceSet(CommandSender sender, ReSyncJsonResourceStorage storage, String[] args) {
        String type = resourceTypeArg(sender, storage, args, 2, "/resync resource set <type> <id> <field> <value>");
        if (type == null) {
            return true;
        }
        String id = requiredArg(sender, args, 3, "/resync resource set <type> <id> <field> <value>");
        String field = requiredArg(sender, args, 4, "/resync resource set <type> <id> <field> <value>");
        if (id == null || field == null || args.length < 6) {
            if (args.length < 6) {
                sendError(sender, "Usage", "/resync resource set <type> <id> <field> <value>");
            }
            return true;
        }
        JsonObject resource = storage.get(type, id);
        if (resource == null) {
            sendError(sender, "Resource Not Found", id);
            return true;
        }
        String value = joinArgs(args, 5);
        putResourceValue(resource, field, value);
        storage.save(type, resource);
        sendSuccess(sender, "Resource Updated", id + " " + field);
        return true;
    }

    private boolean handleResourceDelete(CommandSender sender, ReSyncJsonResourceStorage storage, String[] args) {
        String type = resourceTypeArg(sender, storage, args, 2, "/resync resource delete <type> <id>");
        if (type == null) {
            return true;
        }
        String id = requiredArg(sender, args, 3, "/resync resource delete <type> <id>");
        if (id == null) {
            return true;
        }
        if (storage.get(type, id) == null) {
            sendError(sender, "Resource Not Found", id);
            return true;
        }
        storage.delete(type, id);
        sendSuccess(sender, "Resource Deleted", id);
        return true;
    }

    private boolean handleResourceApply(CommandSender sender, ReSyncJsonResourceStorage storage, String[] args) {
        String type = resourceTypeArg(sender, storage, args, 2, "/resync resource apply <type> <id>");
        if (type == null) {
            return true;
        }
        String id = requiredArg(sender, args, 3, "/resync resource apply <type> <id>");
        if (id == null) {
            return true;
        }
        if (storage.get(type, id) == null) {
            sendError(sender, "Resource Not Found", id);
            return true;
        }
        if (ReSyncResourceCatalog.DIALOG.equals(type)) {
            return applyDialogResource(sender, storage, id, args);
        }
        if (ReSyncResourceCatalog.LOOT_TABLE.equals(type)) {
            return applyLootResource(sender, id, args);
        }
        if (ReSyncResourceCatalog.NPC_DEFINITION.equals(type)) {
            return applyNpcResource(sender, id, args);
        }
        if (ReSyncResourceCatalog.TRADE_PROFILE.equals(type)) {
            return applyTradeResource(sender, id);
        }
        sendError(sender, "Unsupported Resource Apply", prettyResourceType(type));
        return true;
    }

    private boolean applyLootResource(CommandSender sender, String id, String[] args) {
        Player target = resourceTargetPlayer(sender, args, "/resync resource apply loot " + id + " <player>");
        if (target == null) {
            return true;
        }
        LootTableService service = plugin.getReSyncServer().getModuleContext().getService(LootTableService.class);
        if (service == null) {
            sendError(sender, "Loot Service Unavailable", id);
            return true;
        }
        service.give(target, id);
        sendSuccess(sender, "Loot Given", id);
        return true;
    }

    private boolean applyNpcResource(CommandSender sender, String id, String[] args) {
        Player target = resourceTargetPlayer(sender, args, "/resync resource apply npc " + id + " <player>");
        if (target == null) {
            return true;
        }
        NpcService service = plugin.getReSyncServer().getModuleContext().getService(NpcService.class);
        if (service == null) {
            sendError(sender, "NPC Service Unavailable", id);
            return true;
        }
        service.spawn(id, target.getLocation());
        if (service.isActive(id)) {
            sendSuccess(sender, "NPC Spawned", id);
        } else {
            sendError(sender, "NPC Spawn Failed", service.spawnFailureReason(id));
        }
        return true;
    }

    private boolean applyTradeResource(CommandSender sender, String id) {
        if (!(sender instanceof Player player)) {
            sendError(sender, "Villager Target Required", "/resync resource apply trade " + id);
            return true;
        }
        Villager villager = player.getNearbyEntities(8, 8, 8).stream()
            .filter(Villager.class::isInstance)
            .map(Villager.class::cast)
            .min((first, second) -> Double.compare(first.getLocation().distanceSquared(player.getLocation()), second.getLocation().distanceSquared(player.getLocation())))
            .orElse(null);
        if (villager == null) {
            sendError(sender, "Villager Target Required", "/resync resource apply trade " + id);
            return true;
        }
        TradeProfileService service = plugin.getReSyncServer().getModuleContext().getService(TradeProfileService.class);
        if (service == null || !service.apply(villager, id)) {
            sendError(sender, "Trade Apply Failed", id);
            return true;
        }
        sendSuccess(sender, "Trade Applied", id);
        return true;
    }

    private boolean spawnTradeResource(CommandSender sender, String id, String[] args) {
        Player target = targetPlayerArg(sender, args, 3, "/resync trade spawn " + id + " [player]");
        if (target == null) {
            return true;
        }
        TradeProfileService service = plugin.getReSyncServer().getModuleContext().getService(TradeProfileService.class);
        if (service == null) {
            sendError(sender, "Trade Service Unavailable", id);
            return true;
        }
        Villager villager = service.spawn(target.getLocation(), id);
        if (villager == null) {
            sendError(sender, "Trade Spawn Failed", id);
            return true;
        }
        sendSuccess(sender, "Trade Spawned", id);
        return true;
    }

    private boolean openTradeResource(CommandSender sender, String id, String[] args) {
        Player target = targetPlayerArg(sender, args, 3, "/resync trade open " + id + " [player]");
        if (target == null) {
            return true;
        }
        TradeProfileService service = plugin.getReSyncServer().getModuleContext().getService(TradeProfileService.class);
        if (service == null) {
            sendError(sender, "Trade Service Unavailable", id);
            return true;
        }
        if (!service.openVirtualTrades(target, id)) {
            sendError(sender, "Trade Open Failed", id);
            return true;
        }
        sendSuccess(sender, "Trade Opened", id);
        return true;
    }

    private Player resourceTargetPlayer(CommandSender sender, String[] args, String usage) {
        if (args.length >= 5 && args[4] != null && !args[4].isBlank()) {
            Player target = Bukkit.getPlayerExact(args[4]);
            if (target == null) {
                sendError(sender, "Player Not Found", args[4]);
                return null;
            }
            return target;
        }
        if (sender instanceof Player player) {
            return player;
        }
        sendError(sender, "Player Required", usage);
        return null;
    }

    private boolean applyDialogResource(CommandSender sender, ReSyncJsonResourceStorage storage, String id, String[] args) {
        Player target;
        if (args.length >= 5 && args[4] != null && !args[4].isBlank()) {
            target = Bukkit.getPlayerExact(args[4]);
            if (target == null) {
                sendError(sender, "Player Not Found", args[4]);
                return true;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sendError(sender, "Player Required", "/resync resource apply dialog " + id + " <player>");
            return true;
        }
        DialogService service = plugin.getReSyncServer().getModuleContext().getService(DialogService.class);
        if (service == null) {
            service = new DialogService(plugin, storage, getStorage(), plugin.getReSyncServer().getFlowExecutor());
        }
        if (!service.supported()) {
            sendError(sender, "Dialog API Unavailable", "Paper 1.21.7+ is required");
            return true;
        }
        if (!service.show(target, id)) {
            String reason = service.lastError();
            sendError(sender, "Dialog Failed", reason == null || reason.isBlank() ? id : reason);
            return true;
        }
        sendSuccess(sender, "Dialog Shown", id);
        return true;
    }

    private List<String> tabCompleteResource(String[] args) {
        ReSyncJsonResourceStorage storage = getJsonResourceStorage();
        if (storage == null) {
            return List.of();
        }
        if (args.length == 2) {
            return filter(List.of("types", "list", "info", "create", "set", "apply", "delete"), args[1]);
        }
        if (args.length == 3 && !List.of("types").contains(args[1].toLowerCase(Locale.ROOT))) {
            return filter(resourceTypeOptions(storage), args[2]);
        }
        if (args.length == 4 && List.of("info", "set", "apply", "delete").contains(args[1].toLowerCase(Locale.ROOT))) {
            return filter(resourceIds(storage, resourceTypeAlias(args[2])), args[3]);
        }
        if (args.length == 5 && "set".equalsIgnoreCase(args[1])) {
            return filter(resourceFieldOptions(resourceTypeAlias(args[2])), args[4]);
        }
        if (args.length == 6 && "set".equalsIgnoreCase(args[1])) {
            return filter(resourceValueOptions(resourceTypeAlias(args[2]), args[4]), args[5]);
        }
        if (args.length == 5 && "apply".equalsIgnoreCase(args[1]) && List.of(ReSyncResourceCatalog.DIALOG, ReSyncResourceCatalog.LOOT_TABLE, ReSyncResourceCatalog.NPC_DEFINITION).contains(resourceTypeAlias(args[2]))) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[4]);
        }
        return List.of();
    }

    private String resourceTypeArg(CommandSender sender, ReSyncJsonResourceStorage storage, String[] args, int index, String usage) {
        String type = requiredArg(sender, args, index, usage);
        if (type == null) {
            return null;
        }
        type = resourceTypeAlias(type);
        if (!storage.resourceTypes().contains(type)) {
            sendError(sender, "Unknown Resource Type", type);
            return null;
        }
        return type;
    }

    private String resourceTypeAlias(String type) {
        if (type == null) {
            return "";
        }
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "chat" -> ReSyncResourceCatalog.CHAT;
            case "motd", "motd_profile" -> ReSyncResourceCatalog.MOTD_PROFILE;
            case "message", "message_rule" -> ReSyncResourceCatalog.MESSAGE_RULE;
            case "recipe", "recipe_definition" -> ReSyncResourceCatalog.RECIPE_DEFINITION;
            case "text", "template", "text_template" -> ReSyncResourceCatalog.TEXT_TEMPLATE;
            case "advancement", "advancements", "advancement_tree" -> ReSyncResourceCatalog.ADVANCEMENT_TREE;
            case "dialog", "dialogs" -> ReSyncResourceCatalog.DIALOG;
            case "trade", "trade_profile" -> ReSyncResourceCatalog.TRADE_PROFILE;
            case "npc", "npcs", "npc_definition" -> ReSyncResourceCatalog.NPC_DEFINITION;
            case "loot", "loot_table" -> ReSyncResourceCatalog.LOOT_TABLE;
            default -> type.toLowerCase(Locale.ROOT);
        };
    }

    private List<String> resourceTypeOptions(ReSyncJsonResourceStorage storage) {
        List<String> values = new ArrayList<>(storage.resourceTypes());
        values.addAll(List.of("motd", "message", "recipe", "text", "chat", "advancement", "dialog", "trade", "npc", "loot"));
        return values;
    }

    private List<String> resourceIds(ReSyncJsonResourceStorage storage, String type) {
        return storage.resourceTypes().contains(type) ? storage.listIds(type) : List.of();
    }

    private List<String> resourceFieldOptions(String type) {
        return switch (type) {
            case ReSyncResourceCatalog.MOTD_PROFILE -> List.of("line1", "line2", "priority", "playerCountMode", "onlinePlayers", "maxPlayers");
            case ReSyncResourceCatalog.CHAT -> List.of("displayName", "channel.prefix", "format.template", "channel.range", "channel.speakPermission", "channel.readPermission", "channel.allowMiniMessage", "channel.miniMessagePermission", "rule.contains", "rule.action", "rule.replacement", "rule.channel", "rule.flowId", "privateMessages.sender", "privateMessages.receiver", "privateMessages.spy", "privateMessages.privateMessageFlow", "mention.template", "mention.mentionFlow", "ignore.players", "enabled");
            case ReSyncResourceCatalog.MESSAGE_RULE -> List.of("source", "sources", "contains", "replacement", "action", "priority", "enabled", "permission", "players", "flowPredicate", "flowId");
            case ReSyncResourceCatalog.TEXT_TEMPLATE -> List.of("text", "mode", "frameMillis", "width", "visibleCharacters", "frames", "colors", "color", "secondaryColor");
            case ReSyncResourceCatalog.RECIPE_DEFINITION -> List.of("type", "output.material", "output.amount", "shape", "ingredients", "experience", "cookingTime", "craftedFlow", "cookedFlow", "deniedFlow", "conditions.permission", "conditions.world", "enabled");
            case ReSyncResourceCatalog.ADVANCEMENT_TREE -> List.of("displayName", "enabled", "nodes");
            case ReSyncResourceCatalog.DIALOG -> List.of("displayName", "enabled", "type", "title", "external_title", "pause", "can_close_with_escape", "after_action", "columns", "body", "inputs", "actions");
            case ReSyncResourceCatalog.TRADE_PROFILE -> List.of("displayName", "enabled", "profession", "villagerType", "level", "maxUses", "restockTicks", "lootTable", "offers", "hooks.openAction", "hooks.completeAction", "hooks.deniedAction");
            case ReSyncResourceCatalog.NPC_DEFINITION -> List.of("displayName", "enabled", "entityType", "spawnMode", "location.world", "location.x", "location.y", "location.z", "location.yaw", "location.pitch", "skin.username", "skin.uuid", "skin.texture", "skin.signature", "ai", "gravity", "invulnerable", "followPlayer", "followRange", "dialog", "tradeProfile", "lootTable", "equipment.mainHand", "equipment.offHand", "equipment.helmet", "equipment.chestplate", "equipment.leggings", "equipment.boots", "hooks.spawnAction", "hooks.interactAction", "hooks.rightClickAction", "hooks.leftClickAction", "hooks.damageAction", "hooks.deathAction", "hooks.despawnAction");
            case ReSyncResourceCatalog.LOOT_TABLE -> List.of("displayName", "enabled", "trigger", "trigger.event", "trigger.target", "trigger.entity", "trigger.tool", "trigger.overrideDrops", "pools", "pools.0.rolls", "pools.0.entries.0.item", "pools.0.entries.0.minAmount", "pools.0.entries.0.maxAmount", "pools.0.entries.0.weight", "pools.0.entries.0.chance", "hooks.beforeRollFlow", "hooks.afterRollFlow", "hooks.deniedRollFlow");
            default -> List.of("enabled", "priority", "displayName", "template", "prefix", "format");
        };
    }

    private List<String> resourceValueOptions(String type, String field) {
        if ("enabled".equalsIgnoreCase(field)) {
            return booleanOptions();
        }
        if (ReSyncResourceCatalog.MOTD_PROFILE.equals(type) && "playerCountMode".equalsIgnoreCase(field)) {
            return List.of("real", "hidden", "fixed");
        }
        if (ReSyncResourceCatalog.MESSAGE_RULE.equals(type) && "action".equalsIgnoreCase(field)) {
            return List.of("replace_section", "replace", "append", "prepend", "remove", "flow");
        }
        if (ReSyncResourceCatalog.CHAT.equals(type) && "rule.action".equalsIgnoreCase(field)) {
            return List.of("block", "replace", "flow", "channel");
        }
        if (ReSyncResourceCatalog.MESSAGE_RULE.equals(type) && "source".equalsIgnoreCase(field)) {
            return List.of("chat", "join", "quit", "kick", "death", "title", "actionbar", "bossbar", "openScreen", "packetText", "system");
        }
        if (ReSyncResourceCatalog.TEXT_TEMPLATE.equals(type) && "mode".equalsIgnoreCase(field)) {
            return List.of("frames", "typing", "scroll", "bounce", "blink", "pulse", "rainbow", "wave", "wipe", "sparkle");
        }
        if (ReSyncResourceCatalog.DIALOG.equals(type) && "type".equalsIgnoreCase(field)) {
            return List.of("minecraft:notice", "minecraft:confirmation", "minecraft:multi_action", "minecraft:dialog_list");
        }
        if (ReSyncResourceCatalog.DIALOG.equals(type) && "after_action".equalsIgnoreCase(field)) {
            return List.of("close", "none", "wait_for_response");
        }
        if (ReSyncResourceCatalog.DIALOG.equals(type) && ("pause".equalsIgnoreCase(field) || "can_close_with_escape".equalsIgnoreCase(field))) {
            return booleanOptions();
        }
        if (ReSyncResourceCatalog.TRADE_PROFILE.equals(type) && "profession".equalsIgnoreCase(field)) {
            return List.of("none", "armorer", "butcher", "cartographer", "cleric", "farmer", "fisherman", "fletcher", "leatherworker", "librarian", "mason", "nitwit", "shepherd", "toolsmith", "weaponsmith");
        }
        if (ReSyncResourceCatalog.TRADE_PROFILE.equals(type) && "villagerType".equalsIgnoreCase(field)) {
            return List.of("plains", "desert", "jungle", "savanna", "snow", "swamp", "taiga");
        }
        if (ReSyncResourceCatalog.NPC_DEFINITION.equals(type) && "spawnMode".equalsIgnoreCase(field)) {
            return List.of("manual", "startup");
        }
        if (ReSyncResourceCatalog.NPC_DEFINITION.equals(type) && ("ai".equalsIgnoreCase(field) || "gravity".equalsIgnoreCase(field) || "invulnerable".equalsIgnoreCase(field) || "followPlayer".equalsIgnoreCase(field))) {
            return booleanOptions();
        }
        return List.of();
    }

    private JsonObject defaultResource(String type, String id) {
        JsonObject resource = new JsonObject();
        resource.addProperty("id", id);
        resource.addProperty("enabled", true);
        resource.addProperty("folder", ReSyncResourceCatalog.defaultFolder(type));
        switch (type) {
            case ReSyncResourceCatalog.CHAT -> {
                resource.addProperty("displayName", id);
                JsonObject channel = new JsonObject();
                channel.addProperty("priority", 0);
                channel.addProperty("defaultChannel", true);
                channel.addProperty("autojoin", true);
                channel.addProperty("prefix", "<gray>[Chat]</gray> ");
                channel.addProperty("format", "");
                channel.addProperty("range", -1);
                channel.addProperty("allowMiniMessage", false);
                resource.add("channel", channel);
                JsonObject format = new JsonObject();
                format.addProperty("template", "{prefix}{sender}: {message}");
                resource.add("format", format);
                JsonObject rule = new JsonObject();
                rule.addProperty("contains", "");
                rule.addProperty("action", "replace");
                rule.addProperty("replacement", "{message}");
                resource.add("rule", rule);
                JsonObject privateMessages = new JsonObject();
                privateMessages.addProperty("sender", "<gray>To <white>{receiver}</white>: <message>");
                privateMessages.addProperty("receiver", "<gray>From <white>{sender}</white>: <message>");
                privateMessages.addProperty("spy", "<gray>Spy <white>{sender}</white> -> <white>{receiver}</white>: <message>");
                resource.add("privateMessages", privateMessages);
                JsonObject mention = new JsonObject();
                mention.addProperty("template", "<yellow>@{player}</yellow>");
                resource.add("mention", mention);
                JsonObject ignore = new JsonObject();
                ignore.add("players", new JsonArray());
                resource.add("ignore", ignore);
            }
            case ReSyncResourceCatalog.MOTD_PROFILE -> {
                resource.addProperty("priority", 0);
                resource.addProperty("line1", "<green>ReSync Server");
                resource.addProperty("line2", "<gray>Powered By ReStudio");
                resource.addProperty("playerCountMode", "real");
            }
            case ReSyncResourceCatalog.MESSAGE_RULE -> {
                resource.addProperty("source", "join");
                resource.addProperty("priority", 0);
                resource.addProperty("action", "replace_section");
                resource.addProperty("contains", "");
                resource.addProperty("replacement", "{message}");
            }
            case ReSyncResourceCatalog.TEXT_TEMPLATE -> {
                resource.addProperty("text", id);
                JsonArray frames = new JsonArray();
                frames.add(id);
                resource.add("frames", frames);
                resource.addProperty("mode", "frames");
                resource.addProperty("frameMillis", 250);
            }
            case ReSyncResourceCatalog.ADVANCEMENT_TREE -> {
                resource.addProperty("displayName", id);
                JsonObject nodes = new JsonObject();
                JsonObject root = new JsonObject();
                root.addProperty("enabled", true);
                root.addProperty("parent", "");
                JsonObject position = new JsonObject();
                position.addProperty("x", 0);
                position.addProperty("y", 0);
                root.add("position", position);
                JsonObject display = new JsonObject();
                display.addProperty("title", id);
                display.addProperty("description", "Server Progress");
                display.addProperty("icon", "minecraft:nether_star");
                display.addProperty("frame", "task");
                display.addProperty("background", "minecraft:gui/advancements/backgrounds/adventure");
                display.addProperty("showToast", false);
                display.addProperty("announceToChat", false);
                display.addProperty("hidden", false);
                root.add("display", display);
                root.add("criteria", new JsonObject());
                root.add("requirements", new JsonArray());
                nodes.add("root", root);
                resource.add("nodes", nodes);
            }
            case ReSyncResourceCatalog.DIALOG -> {
                resource.addProperty("displayName", id);
                resource.addProperty("type", "minecraft:multi_action");
                resource.addProperty("title", id);
                resource.addProperty("external_title", id);
                resource.add("body", new JsonArray());
                resource.add("inputs", new JsonArray());
                resource.addProperty("can_close_with_escape", true);
                resource.addProperty("pause", true);
                resource.addProperty("after_action", "close");
                resource.addProperty("columns", 1);
                JsonArray actions = new JsonArray();
                JsonObject button = new JsonObject();
                button.addProperty("label", "Button");
                button.addProperty("width", 150);
                JsonObject resync = new JsonObject();
                resync.addProperty("actionMode", "None");
                resync.addProperty("predicateMode", "None");
                button.add("resync", resync);
                actions.add(button);
                resource.add("actions", actions);
            }
            case ReSyncResourceCatalog.TRADE_PROFILE -> {
                resource.addProperty("displayName", id);
                resource.addProperty("profession", "librarian");
                resource.addProperty("villagerType", "plains");
                resource.addProperty("level", 1);
                resource.addProperty("maxUses", 12);
                resource.addProperty("restockTicks", 24000);
                resource.addProperty("lootTable", "");
                resource.add("offers", new JsonArray());
                resource.add("hooks", new JsonObject());
            }
            case ReSyncResourceCatalog.NPC_DEFINITION -> {
                resource.addProperty("displayName", id);
                resource.addProperty("entityType", "villager");
                resource.addProperty("spawnMode", "manual");
                resource.addProperty("ai", false);
                resource.addProperty("gravity", true);
                resource.addProperty("invulnerable", true);
                resource.addProperty("followPlayer", false);
                resource.addProperty("followRange", 12);
                resource.addProperty("dialog", "");
                resource.addProperty("tradeProfile", "");
                resource.addProperty("lootTable", "");
                JsonObject skin = new JsonObject();
                skin.addProperty("username", "");
                resource.add("skin", skin);
                resource.add("equipment", new JsonObject());
                resource.add("hooks", new JsonObject());
            }
            case ReSyncResourceCatalog.LOOT_TABLE -> {
                resource.addProperty("displayName", id);
                resource.addProperty("enabled", true);
                JsonObject trigger = new JsonObject();
                trigger.addProperty("event", "none");
                trigger.addProperty("target", "");
                trigger.addProperty("entity", "");
                trigger.addProperty("tool", "");
                trigger.addProperty("overrideDrops", true);
                resource.add("trigger", trigger);
                JsonArray pools = new JsonArray();
                JsonObject pool = new JsonObject();
                pool.addProperty("rolls", 1);
                JsonArray entries = new JsonArray();
                JsonObject entry = new JsonObject();
                entry.addProperty("item", "minecraft:stone");
                entry.addProperty("minAmount", 1);
                entry.addProperty("maxAmount", 1);
                entry.addProperty("weight", 1);
                entry.addProperty("chance", 100);
                entries.add(entry);
                pool.add("entries", entries);
                pools.add(pool);
                resource.add("pools", pools);
                JsonObject hooks = new JsonObject();
                hooks.addProperty("beforeRollFlow", "");
                hooks.addProperty("afterRollFlow", "");
                hooks.addProperty("deniedRollFlow", "");
                resource.add("hooks", hooks);
            }
            default -> resource.addProperty("displayName", id);
        }
        return resource;
    }

    private void putResourceValue(JsonObject resource, String field, String value) {
        if (arrayResourceField(field)) {
            JsonArray array = new JsonArray();
            for (String part : value.split("\\|")) {
                String trimmed = part.trim();
                if (!trimmed.isBlank()) {
                    array.add(resourceArrayValue(field, trimmed));
                }
            }
            resource.add(field, array);
            return;
        }
        JsonPathParent parent = jsonPathParent(resource, field, true);
        if (parent == null) {
            return;
        }
        if (value == null || value.isBlank() || "none".equalsIgnoreCase(value)) {
            removeJsonPath(resource, field);
            return;
        }
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            setJsonPathValue(parent, new JsonPrimitive(Boolean.parseBoolean(value)));
            return;
        }
        Integer integer = parseInt(value);
        if (integer != null) {
            setJsonPathValue(parent, new JsonPrimitive(integer));
            return;
        }
        setJsonPathValue(parent, new JsonPrimitive(value));
    }

    private JsonPathParent jsonPathParent(JsonObject resource, String field, boolean create) {
        if (resource == null || field == null || field.isBlank()) {
            return null;
        }
        String[] parts = field.split("\\.");
        JsonElement current = resource;
        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            boolean nextArray = isIndex(parts[i + 1]);
            if (current.isJsonObject()) {
                JsonObject object = current.getAsJsonObject();
                if (!object.has(part) || object.get(part).isJsonNull() || (!object.get(part).isJsonObject() && !object.get(part).isJsonArray())) {
                    if (!create) {
                        return null;
                    }
                    object.add(part, nextArray ? new JsonArray() : new JsonObject());
                }
                current = object.get(part);
            } else if (current.isJsonArray() && isIndex(part)) {
                JsonArray array = current.getAsJsonArray();
                int index = Integer.parseInt(part);
                while (create && array.size() <= index) {
                    array.add(nextArray ? new JsonArray() : new JsonObject());
                }
                if (index >= array.size()) {
                    return null;
                }
                JsonElement child = array.get(index);
                if (child == null || child.isJsonNull() || (!child.isJsonObject() && !child.isJsonArray())) {
                    if (!create) {
                        return null;
                    }
                    child = nextArray ? new JsonArray() : new JsonObject();
                    array.set(index, child);
                }
                current = child;
            } else {
                return null;
            }
        }
        return current.isJsonObject() ? new JsonPathParent(current.getAsJsonObject(), null, parts[parts.length - 1])
            : current.isJsonArray() ? new JsonPathParent(null, current.getAsJsonArray(), parts[parts.length - 1])
            : null;
    }

    private void setJsonPathValue(JsonPathParent parent, JsonElement value) {
        if (parent.object() != null) {
            parent.object().add(parent.key(), value);
            return;
        }
        if (parent.array() != null && isIndex(parent.key())) {
            int index = Integer.parseInt(parent.key());
            while (parent.array().size() <= index) {
                parent.array().add(new JsonObject());
            }
            parent.array().set(index, value);
        }
    }

    private void removeJsonPath(JsonObject resource, String field) {
        JsonPathParent parent = jsonPathParent(resource, field, false);
        if (parent == null) {
            return;
        }
        if (parent.object() != null) {
            parent.object().remove(parent.key());
        } else if (parent.array() != null && isIndex(parent.key())) {
            int index = Integer.parseInt(parent.key());
            if (index < parent.array().size()) {
                parent.array().remove(index);
            }
        }
    }

    private boolean isIndex(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private record JsonPathParent(JsonObject object, JsonArray array, String key) {
    }

    private boolean arrayResourceField(String field) {
        return switch (field) {
            case "frames", "colors", "shape", "ingredients", "nodes", "temporaryNodes", "parents", "tracks", "prefixes", "suffixes", "players", "sources", "offers", "pools" -> true;
            default -> false;
        };
    }

    private JsonElement resourceArrayValue(String field, String value) {
        return switch (field) {
            case "temporaryNodes" -> {
                JsonObject object = new JsonObject();
                String[] parts = splitResourceValue(value, 2);
                object.addProperty("node", parts[0]);
                Integer seconds = parts[1].isBlank() ? null : parseInt(parts[1]);
                if (seconds != null) {
                    object.addProperty("seconds", seconds);
                }
                yield object;
            }
            case "prefixes", "suffixes" -> {
                JsonObject object = new JsonObject();
                String[] parts = splitResourceValue(value, 2);
                Integer priority = parseInt(parts[1]);
                object.addProperty("value", parts[0]);
                object.addProperty("priority", priority != null ? priority : 0);
                yield object;
            }
            case "tracks" -> {
                String[] parts = splitResourceValue(value, 2);
                if (parts[1].isBlank()) {
                    yield new JsonPrimitive(parts[0]);
                }
                JsonObject object = new JsonObject();
                object.addProperty("name", parts[0]);
                JsonArray groups = new JsonArray();
                for (String group : parts[1].split(",")) {
                    String trimmed = group.trim();
                    if (!trimmed.isBlank()) {
                        groups.add(trimmed);
                    }
                }
                object.add("groups", groups);
                yield object;
            }
            default -> new JsonPrimitive(value);
        };
    }

    private String[] splitResourceValue(String value, int size) {
        String[] raw = value == null ? new String[0] : value.split(":", size);
        String[] parts = new String[size];
        for (int i = 0; i < size; i++) {
            parts[i] = i < raw.length ? raw[i].trim() : "";
        }
        return parts;
    }

    private String prettyResourceType(String type) {
        String text = type == null ? "" : type.replace('_', ' ');
        StringBuilder builder = new StringBuilder();
        for (String part : text.split("\\s+")) {
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
        return builder.isEmpty() ? "Resource" : builder.toString();
    }

    private String shortJsonValue(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "None";
        }
        String value = element.isJsonPrimitive() ? element.getAsString() : element.toString();
        return value.length() <= 96 ? value : value.substring(0, 93) + "...";
    }

    private boolean handleFlow(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendUsageLine(sender, "/resync flow reload nodes");
            sendUsageLine(sender, "/resync flow registry");
            sendUsageLine(sender, "/resync flow registry export");
            sendUsageLine(sender, "/resync flow clients reconnect");
            return true;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        if ("clients".equals(sub)) {
            if (args.length != 3 || !"reconnect".equalsIgnoreCase(args[2])) {
                sendUsageLine(sender, "/resync flow clients reconnect");
                return true;
            }
            ReSyncServer server = plugin.getReSyncServer();
            if (server == null) {
                sendError(sender, "Server Not Initialized");
                return true;
            }
            int clients = server.getConnectionManager().reconnectWebSocketClients("Server reconnect requested");
            sendSuccess(sender, "Flow Clients Reconnecting", String.valueOf(clients));
            return true;
        }
        if ("registry".equals(sub)) {
            FlowRuntimeModule module = flowRuntimeModule();
            if (module == null) {
                sendError(sender, "Flow Module Not Initialized");
                return true;
            }
            Map<String, Object> diagnostics = module.nodeRegistryDiagnostics();
            if (args.length >= 3) {
                if (!"export".equalsIgnoreCase(args[2])) {
                    sendError(sender, "Unknown registry action: " + args[2]);
                    return true;
                }
                try {
                    ReSyncServer server = plugin.getReSyncServer();
                    Map<String, Object> readiness = server != null ? server.readinessSnapshot() : Map.of();
                    var output = ProgrammabilityAcceptanceSnapshot.write(plugin.getDataFolder().toPath().resolve("diagnostics"), diagnostics, readiness,
                        Bukkit.getVersion(), plugin.getDescription().getVersion(), Instant.now());
                    sendSuccess(sender, "Registry Snapshot Exported", output.toAbsolutePath().toString());
                } catch (Exception exception) {
                    sendError(sender, "Registry Snapshot Export Failed", exception.getMessage() != null ? exception.getMessage() : exception.getClass().getSimpleName());
                }
                return true;
            }
            String definitions = String.valueOf(diagnostics.getOrDefault("definitions", 0));
            String definitionSets = String.valueOf(diagnostics.getOrDefault("definitionSets", 0));
            String externalNodePlugins = String.valueOf(diagnostics.getOrDefault("externalNodePlugins", 0));
            String flowClients = String.valueOf(diagnostics.getOrDefault("flowClients", 0));
            sendInfo(sender, "Node Registry", definitions + " definitions · " + definitionSets + " definition sets · " + externalNodePlugins + " external node plugins · " + flowClients + " flow clients");
            sendInfo(sender, "Definitions", definitions);
            sendInfo(sender, "Definition Sets", definitionSets);
            sendInfo(sender, "Definition Set Ids", joinedDiagnosticList(diagnostics.get("definitionSetIds")));
            sendInfo(sender, "External Node Plugins", externalNodePlugins);
            sendInfo(sender, "External Node Plugin Ids", joinedDiagnosticList(diagnostics.get("externalNodePluginIds")));
            sendInfo(sender, "Flow Clients", flowClients);
            sendInfo(sender, "Registry Checksum", shortChecksum(String.valueOf(diagnostics.get("checksum"))));
            sendInfo(sender, "Registry Parity", Boolean.TRUE.equals(diagnostics.get("parity")) ? "Ready" : "Mismatch");
            sendInfo(sender, "Rejected Definitions", String.valueOf(diagnostics.getOrDefault("rejectedDefinitions", 0)));
            sendInfo(sender, "Missing Handlers", joinedDiagnosticList(diagnostics.get("missingHandlers")));
            sendInfo(sender, "Missing Operations", joinedDiagnosticList(diagnostics.get("missingOperations")));
            sendInfo(sender, "Missing Catalogs", joinedDiagnosticList(diagnostics.get("missingCatalogs")));
            if (diagnostics.get("inventoryCounts") instanceof Map<?, ?> counts) {
                sendInfo(sender, "Platform Inventory", String.valueOf(counts.get("nodes")) + " nodes · " + counts.get("types") + " types · "
                    + counts.get("catalogs") + " catalogs · " + counts.get("resources") + " resources");
            }
            sendInfo(sender, "Inventory Completion", Boolean.TRUE.equals(diagnostics.get("inventoryComplete")) ? "Ready" : "Incomplete");
            sendInfo(sender, "Node Dispositions", diagnosticMap(diagnostics.get("nodeDispositions")));
            sendInfo(sender, "Resource Dispositions", diagnosticMap(diagnostics.get("resourceDispositions")));
            return true;
        }
        if (!"reload".equals(sub)) {
            sendError(sender, "Unknown flow subcommand: " + sub);
            return true;
        }
        if (args.length < 3 || !"nodes".equalsIgnoreCase(args[2])) {
            sendUsageLine(sender, "/resync flow reload nodes");
            return true;
        }
        FlowRuntimeModule module = flowRuntimeModule();
        if (module == null) {
            sendError(sender, "Flow module not initialized");
            return true;
        }
        try {
            module.reloadNodeDefinitions();
            sendSuccess(sender, "Node definitions reloaded.");
        } catch (Exception e) {
            sendError(sender, "Failed to reload nodes: " + e.getMessage());
        }
        return true;
    }

    private String diagnosticMap(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return "None";
        }
        return map.entrySet().stream().map(entry -> entry.getKey() + ": " + entry.getValue()).collect(Collectors.joining(" · "));
    }

    private List<String> tabCompleteFlow(String[] args) {
        if (args.length == 2) {
            return filter(List.of("reload", "registry", "clients"), args[1]);
        }
        if (args.length == 3 && "clients".equalsIgnoreCase(args[1])) {
            return filter(List.of("reconnect"), args[2]);
        }
        if (args.length == 3 && "registry".equalsIgnoreCase(args[1])) {
            return filter(List.of("export"), args[2]);
        }
        if (args.length == 3 && "reload".equalsIgnoreCase(args[1])) {
            return filter(List.of("nodes"), args[2]);
        }
        return List.of();
    }

    private FlowRuntimeModule flowRuntimeModule() {
        ReSyncServer server = plugin.getReSyncServer();
        if (server == null || server.getModuleContext() == null) {
            return null;
        }
        return server.getModuleContext().getService(FlowRuntimeModule.class);
    }

    private FlowModule flowModule() {
        ReSyncServer server = plugin.getReSyncServer();
        if (server == null || server.getModuleContext() == null) {
            return null;
        }
        return server.getModuleContext().getService(FlowModule.class);
    }

    private String shortChecksum(String checksum) {
        if (checksum == null || checksum.isBlank()) {
            return "None";
        }
        return checksum.length() <= 12 ? checksum : checksum.substring(0, 12);
    }

    private String joinedDiagnosticList(Object value) {
        if (value instanceof List<?> list && !list.isEmpty()) {
            return list.stream().map(String::valueOf).collect(Collectors.joining(", "));
        }
        return "None";
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
            return filter(List.of("list", "info", "generators", "scan", "import", "create", "clone", "load", "unload", "delete", "difficulty", "rules", "rule", "isolated", "timelock", "weatherlock", "profile", "who", "purge", "group", "sign", "tp", "tpspawn"), args[1]);
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        return switch (action) {
            case "info", "load", "unload", "delete", "difficulty", "rules", "rule", "isolated", "timelock", "weatherlock", "profile", "who", "purge" ->
                args.length == 3 ? filter(worldNames(), args[2]) : tabCompleteWorldAction(action, args);
            case "clone" -> tabCompleteClone(args);
            case "create" -> args.length == 5 ? filter(environmentOptions(), args[4]) : args.length == 6 ? filter(generatorIds(), args[5]) : List.of();
            case "group" -> tabCompleteWorldGroup(args);
            case "sign" -> tabCompleteWorldSign(args);
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
        if (args.length == 3) {
            return filter(worldNames(), args[2]);
        }
        return switch (action) {
            case "unload" -> args.length == 4 ? filter(worldNames(), args[3]) : List.of();
            case "delete" -> args.length == 4 ? filter(booleanOptions(), args[3]) : args.length == 5 ? filter(worldNames(), args[4]) : List.of();
            case "difficulty" -> args.length == 4 ? filter(difficultyOptions(), args[3]) : List.of();
            case "rule" -> args.length == 4 ? filter(gameRuleNames(), args[3]) : args.length == 5 ? filter(gameRuleValueOptions(args[3]), args[4]) : List.of();
            case "isolated", "timelock" -> args.length == 4 ? filter(booleanOptions(), args[3]) : List.of();
            case "weatherlock" -> args.length == 4 ? filter(booleanOptions(), args[3]) : args.length == 5 ? filter(booleanOptions(), args[4]) : args.length == 6 ? filter(booleanOptions(), args[5]) : List.of();
            case "profile" -> tabCompleteWorldProfile(args);
            case "purge" -> args.length >= 4 && args.length <= 9 ? filter(booleanOptions(), args[args.length - 1]) : List.of();
            default -> List.of();
        };
    }

    private List<String> tabCompleteWorldProfile(String[] args) {
        if (args.length == 4) {
            return filter(worldProfileActions(), args[3]);
        }
        if (args.length != 5) {
            return List.of();
        }
        String action = args[3].toLowerCase(Locale.ROOT);
        return switch (action) {
            case "gamemode" -> filter(gamemodeOptions(), args[4]);
            case "pvp", "keepspawn", "autosave", "animals", "monsters", "misc", "hunger", "autoheal", "bedrespawn", "anchorrespawn", "autonether", "autoend" -> filter(booleanOptions(), args[4]);
            case "respawn", "linknether", "linkend", "linkoverworld" -> filter(appendOption(worldNames(), "none"), args[4]);
            case "group" -> filter(appendOption(groupIds(), "none"), args[4]);
            default -> List.of();
        };
    }

    private List<String> tabCompleteWorldGroup(String[] args) {
        if (args.length == 3) {
            return filter(List.of("list", "info", "create", "delete", "display", "worlds", "addworld", "removeworld", "share"), args[2]);
        }
        String action = args[2].toLowerCase(Locale.ROOT);
        return switch (action) {
            case "info", "delete", "display", "worlds", "addworld", "removeworld", "share" -> args.length == 4 ? filter(groupIds(), args[3]) : tabCompleteWorldGroupAction(action, args);
            default -> List.of();
        };
    }

    private List<String> tabCompleteWorldGroupAction(String action, String[] args) {
        return switch (action) {
            case "addworld", "removeworld" -> args.length == 5 ? filter(worldNames(), args[4]) : List.of();
            case "share" -> args.length == 5 ? filter(inventoryGroupShareOptions(), args[4]) : args.length == 6 ? filter(booleanOptions(), args[5]) : List.of();
            default -> List.of();
        };
    }

    private List<String> tabCompleteWorldSign(String[] args) {
        if (args.length == 3) {
            return filter(List.of("list", "info", "create", "enable", "target", "delete"), args[2]);
        }
        String action = args[2].toLowerCase(Locale.ROOT);
        return switch (action) {
            case "list" -> args.length == 4 ? filter(worldNames(), args[3]) : List.of();
            case "info", "enable", "target", "delete" -> args.length == 4 ? filter(signIds(), args[3]) : tabCompleteWorldSignAction(action, args);
            case "create" -> args.length == 4 ? filter(worldNames(), args[3]) : args.length == 8 ? filter(portalIdentifiers(), args[7]) : List.of();
            default -> List.of();
        };
    }

    private List<String> tabCompleteWorldSignAction(String action, String[] args) {
        return switch (action) {
            case "enable" -> args.length == 5 ? filter(booleanOptions(), args[4]) : List.of();
            case "target" -> args.length == 5 ? filter(portalIdentifiers(), args[4]) : List.of();
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
            return filter(List.of("create", "cancel", "list", "info", "delete", "enable", "rename", "bounds", "dest", "access", "bypass", "fee", "cooldown", "priority", "safe", "velocity", "message", "vehiclepass", "entitypass", "mode", "cannon", "tp"), args[1]);
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        return switch (action) {
            case "create" -> args.length == 3 ? filter(worldNames(), args[2]) : List.of();
            case "list" -> args.length == 3 ? filter(worldNames(), args[2]) : List.of();
            case "info", "delete", "rename", "bounds", "dest", "enable", "access", "bypass", "fee", "cooldown", "priority", "safe", "velocity", "message", "vehiclepass", "entitypass", "mode", "cannon" -> args.length == 3 ? filter(portalIdentifiers(), args[2]) : tabCompletePortalMutation(action, args);
            case "tp" -> args.length == 3 ? filter(onlinePlayers(), args[2]) : args.length == 4 ? filter(portalIdentifiers(), args[3]) : List.of();
            default -> List.of();
        };
    }

    private List<String> tabCompletePortalMutation(String action, String[] args) {
        return switch (action) {
            case "enable", "safe", "velocity", "vehiclepass", "entitypass" -> args.length == 4 ? filter(booleanOptions(), args[3]) : List.of();
            case "bounds" -> args.length == 4 ? filter(worldNames(), args[3]) : List.of();
            case "dest" -> args.length == 4 ? filter(worldNames(), args[3]) : List.of();
            case "mode" -> args.length == 4 ? filter(portalModeOptions(), args[3]) : List.of();
            case "fee" -> args.length == 4 ? filter(List.of("off", "0", "1", "10", "100"), args[3]) : List.of();
            case "cooldown" -> args.length == 4 ? filter(List.of("0", "1500", "3000", "5000"), args[3]) : List.of();
            case "priority" -> args.length == 4 ? filter(List.of("0", "1", "5", "10"), args[3]) : List.of();
            case "cannon" -> args.length == 4 ? filter(List.of("1.8", "2.5", "4.0"), args[3]) : List.of();
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
                sendError(sender, "Flow Storage Unavailable");
                return true;
            }
            List<String> ids = storage.listScoreboardIds();
            if (ids.isEmpty()) {
                sendInfo(sender, "No Scoreboards Found");
            } else {
                sendInfo(sender, "Scoreboards", String.join(", ", ids));
            }
            return true;
        }
        if ("default".equals(action)) {
            if (args.length == 2) {
                String defaultId = ScoreboardTemplateManager.getDefaultScoreboardId();
                if (defaultId == null || defaultId.isBlank()) {
                    sendInfo(sender, "Default Scoreboard", "None");
                } else {
                    sendInfo(sender, "Default Scoreboard", defaultId + " · Papi " + ScoreboardTemplateManager.isDefaultScoreboardUsePapi());
                }
                return true;
            }
            String id = args[2];
            if ("none".equalsIgnoreCase(id)) {
                boolean cleared = ScoreboardTemplateManager.clearDefaultScoreboard();
                if (cleared) {
                    sendSuccess(sender, "Default Scoreboard Cleared");
                } else {
                    sendError(sender, "Default Scoreboard Clear Failed");
                }
                return true;
            }
            boolean usePapi = args.length < 4 || Boolean.parseBoolean(args[3]);
            boolean changed = ScoreboardTemplateManager.setDefaultScoreboard(id, usePapi);
            if (changed) {
                sendSuccess(sender, "Default Scoreboard", id + " · Papi " + usePapi);
            } else {
                sendError(sender, "Default Scoreboard Set Failed", id);
            }
            return true;
        }
        if (args.length < 3) {
            sendUsage(sender);
            return true;
        }
        Player target = findPlayer(args[2]);
        if (target == null) {
            sendError(sender, "Player Not Found", args[2]);
            return true;
        }
        if ("hide".equals(action)) {
            ScoreboardTemplateManager.hideActive(target);
            sendSuccess(sender, "Scoreboard Hidden", target.getName());
            return true;
        }
        if (!"show".equals(action) || args.length < 4) {
            sendUsage(sender);
            return true;
        }
        FlowStorage storage = getStorage();
        if (storage == null) {
            sendError(sender, "Flow Storage Unavailable");
            return true;
        }
        String scoreboardId = args[3];
        ScoreboardDefinition definition = storage.getScoreboard(scoreboardId);
        if (definition == null) {
            sendError(sender, "Scoreboard Not Found", scoreboardId);
            return true;
        }
        boolean usePapi = args.length < 5 || Boolean.parseBoolean(args[4]);
        boolean applied = ScoreboardTemplateManager.showTemplate(target, definition, usePapi);
        if (applied) {
            sendSuccess(sender, "Scoreboard Applied", scoreboardId + " -> " + target.getName());
        } else {
            sendError(sender, "Scoreboard Apply Failed", scoreboardId);
        }
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
            sendError(sender, "Flow Storage Unavailable");
            return true;
        }
        if ("list".equals(action)) {
            List<String> ids = storage.listTabIds();
            if (ids.isEmpty()) {
                sendInfo(sender, "No Tabs Found");
            } else {
                sendInfo(sender, "Tabs", String.join(", ", ids));
            }
            return true;
        }
        if ("default".equals(action)) {
            if (args.length == 2) {
                String defaultId = TabListService.getDefaultTabId();
                if (defaultId == null || defaultId.isBlank()) {
                    sendInfo(sender, "Default Tab", "None");
                } else {
                    sendInfo(sender, "Default Tab", defaultId + " · Papi " + TabListService.isDefaultTabUsePapi());
                }
                return true;
            }
            String id = args[2];
            if ("none".equalsIgnoreCase(id)) {
                boolean cleared = TabListService.clearDefaultTab();
                if (cleared) {
                    sendSuccess(sender, "Default Tab Cleared");
                } else {
                    sendError(sender, "Default Tab Clear Failed");
                }
                return true;
            }
            boolean usePapi = args.length < 4 || Boolean.parseBoolean(args[3]);
            boolean changed = TabListService.setDefaultTab(id, usePapi);
            if (changed) {
                sendSuccess(sender, "Default Tab", id + " · Papi " + usePapi);
            } else {
                sendError(sender, "Default Tab Set Failed", id);
            }
            return true;
        }
        if ("clear".equals(action)) {
            if (args.length >= 3) {
                Player target = findPlayer(args[2]);
                if (target == null) {
                    sendError(sender, "Player Not Found", args[2]);
                    return true;
                }
                TabListService.clearForPlayer(target);
                sendSuccess(sender, "Tab Cleared", target.getName());
                return true;
            }
            for (Player player : Bukkit.getOnlinePlayers()) {
                TabListService.clearForPlayer(player);
            }
            TabListService.resetEntryNames();
            sendSuccess(sender, "Tab Cleared", "All Players");
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
                sendError(sender, "Tab Not Found", tabId);
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
                if (applied) {
                    sendSuccess(sender, "Tab Applied", tabId + " -> " + target.getName());
                } else {
                    sendError(sender, "Tab Apply Failed", tabId);
                }
            } else {
                TabListService.applyTemplateToAll(tab, usePapi);
                sendSuccess(sender, "Tab Applied", tabId + " -> All Players");
            }
            return true;
        }
        if ("interval".equals(action)) {
            if (args.length == 2) {
                sendInfo(sender, "Hud Refresh Interval", TabListService.getRefreshIntervalTicks() + " Ticks");
                return true;
            }
            Integer ticks = parseInt(args[2]);
            if (ticks == null) {
                sendError(sender, "Invalid Interval", args[2]);
                return true;
            }
            if (ticks < 1) {
                sendError(sender, "Interval Must Be At Least 1 Tick");
                return true;
            }
            boolean changed = TabListService.setRefreshIntervalTicks(ticks);
            if (changed) {
                sendSuccess(sender, "Hud Refresh Interval", ticks + " Ticks");
            } else {
                sendError(sender, "Hud Refresh Interval Set Failed");
            }
            return true;
        }
        sendUsage(sender);
        return true;
    }

    private boolean handleWorld(CommandSender sender, String[] args) {
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
            case "profile" -> handleWorldProfile(sender, service, args);
            case "who" -> handleWorldWho(sender, service, args);
            case "purge" -> handleWorldPurge(sender, service, args);
            case "group" -> handleWorldGroup(sender, service, args);
            case "sign" -> handleWorldSign(sender, service, args);
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
            case "access" -> handlePortalAccess(sender, service, args);
            case "bypass" -> handlePortalBypass(sender, service, args);
            case "fee" -> handlePortalFee(sender, service, args);
            case "cooldown" -> handlePortalCooldown(sender, service, args);
            case "priority" -> handlePortalPriority(sender, service, args);
            case "safe" -> handlePortalSafe(sender, service, args);
            case "velocity" -> handlePortalVelocity(sender, service, args);
            case "message" -> handlePortalMessage(sender, service, args);
            case "vehiclepass" -> handlePortalVehiclePass(sender, service, args);
            case "entitypass" -> handlePortalEntityPass(sender, service, args);
            case "mode" -> handlePortalMode(sender, service, args);
            case "cannon" -> handlePortalCannon(sender, service, args);
            case "tp" -> handlePortalTeleport(sender, service, args);
            default -> sendUsage(sender);
        }
        return true;
    }

    private void handleWorldCreate(CommandSender sender, WorldManagementService service, String[] args) {
        String worldName = requiredArg(sender, args, 2, "/resync world create <world> [seed] [environment] [generator] [generatorConfig]");
        if (worldName == null) {
            return;
        }
        sendResult(sender, service.createWorld(worldName, optionalArg(args, 3), optionalArg(args, 4), optionalArg(args, 5), optionalArg(args, 6)));
    }

    private void handleWorldProfile(CommandSender sender, WorldManagementService service, String[] args) {
        String worldName = requiredArg(sender, args, 2, "/resync world profile <world> <action> ...");
        String profileAction = requiredArg(sender, args, 3, "/resync world profile <world> <action> ...");
        if (worldName == null || profileAction == null) {
            return;
        }
        WorldRegistryEntry entry = findWorldEntry(service, worldName);
        if (entry == null) {
            sendError(sender, "World Not Found", worldName);
            return;
        }
        WorldProfileSettings profile = entry.getProfileSettings();
        switch (profileAction.toLowerCase(Locale.ROOT)) {
            case "show" -> sendWorldProfileInfo(sender, worldName, profile);
            case "showall" -> {
                sendWorldInfo(sender, service, new String[] {"world", "info", worldName});
                sendWorldProfileInfo(sender, worldName, profile);
            }
            case "hide" -> applyWorldProfile(sender, service, worldName, profile, updated -> updated.setHidden(true));
            case "unhide" -> applyWorldProfile(sender, service, worldName, profile, updated -> updated.setHidden(false));
            case "alias" -> applyWorldProfile(sender, service, worldName, profile, updated -> updated.setAlias(normalizeOptionalText(joinArgs(args, 4))));
            case "access" -> applyWorldProfile(sender, service, worldName, profile, updated -> updated.setAccessPermission(normalizeOptionalText(joinArgs(args, 4))));
            case "bypass" -> applyWorldProfile(sender, service, worldName, profile, updated -> updated.setBypassPermission(normalizeOptionalText(joinArgs(args, 4))));
            case "respawn" -> applyWorldProfile(sender, service, worldName, profile, updated -> updated.setRespawnWorld(normalizeOptionalText(optionalArg(args, 4))));
            case "gamemode" -> {
                String gameMode = requiredArg(sender, args, 4, "/resync world profile <world> gamemode <mode>");
                if (gameMode == null) {
                    return;
                }
                applyWorldProfile(sender, service, worldName, profile, updated -> {
                    updated.setForceGameMode(true);
                    updated.setGameMode(gameMode);
                });
            }
            case "gamemodeoff" -> applyWorldProfile(sender, service, worldName, profile, updated -> {
                updated.setForceGameMode(false);
                updated.setGameMode("");
            });
            case "pvp" -> handleWorldProfileBoolean(sender, service, worldName, profile, args, 4, "/resync world profile <world> pvp <enabled>", WorldProfileSettings::setPvpEnabled);
            case "keepspawn" -> handleWorldProfileBoolean(sender, service, worldName, profile, args, 4, "/resync world profile <world> keepspawn <enabled>", WorldProfileSettings::setKeepSpawnLoaded);
            case "autosave" -> handleWorldProfileBoolean(sender, service, worldName, profile, args, 4, "/resync world profile <world> autosave <enabled>", WorldProfileSettings::setAutoSaveEnabled);
            case "animals" -> handleWorldProfileBoolean(sender, service, worldName, profile, args, 4, "/resync world profile <world> animals <enabled>", WorldProfileSettings::setAnimalSpawnsEnabled);
            case "monsters" -> handleWorldProfileBoolean(sender, service, worldName, profile, args, 4, "/resync world profile <world> monsters <enabled>", WorldProfileSettings::setMonsterSpawnsEnabled);
            case "misc" -> handleWorldProfileBoolean(sender, service, worldName, profile, args, 4, "/resync world profile <world> misc <enabled>", WorldProfileSettings::setNonLivingEntitySpawnsEnabled);
            case "hunger" -> handleWorldProfileBoolean(sender, service, worldName, profile, args, 4, "/resync world profile <world> hunger <enabled>", WorldProfileSettings::setHungerEnabled);
            case "autoheal" -> handleWorldProfileBoolean(sender, service, worldName, profile, args, 4, "/resync world profile <world> autoheal <enabled>", WorldProfileSettings::setAutoHealEnabled);
            case "bedrespawn" -> handleWorldProfileBoolean(sender, service, worldName, profile, args, 4, "/resync world profile <world> bedrespawn <enabled>", WorldProfileSettings::setBedRespawnEnabled);
            case "anchorrespawn" -> handleWorldProfileBoolean(sender, service, worldName, profile, args, 4, "/resync world profile <world> anchorrespawn <enabled>", WorldProfileSettings::setAnchorRespawnEnabled);
            case "arrival" -> applyWorldProfile(sender, service, worldName, profile, updated -> updated.setArrivalMessage(normalizeOptionalText(joinArgs(args, 4))));
            case "deny" -> applyWorldProfile(sender, service, worldName, profile, updated -> updated.setDenyMessage(normalizeOptionalText(joinArgs(args, 4))));
            case "group" -> applyWorldProfile(sender, service, worldName, profile, updated -> updated.setInventoryGroupId(normalizeOptionalText(optionalArg(args, 4))));
            case "linknether" -> applyWorldProfile(sender, service, worldName, profile, updated -> updated.setLinkedNetherWorld(normalizeOptionalText(optionalArg(args, 4))));
            case "linkend" -> applyWorldProfile(sender, service, worldName, profile, updated -> updated.setLinkedEndWorld(normalizeOptionalText(optionalArg(args, 4))));
            case "linkoverworld" -> applyWorldProfile(sender, service, worldName, profile, updated -> updated.setLinkedOverworld(normalizeOptionalText(optionalArg(args, 4))));
            case "netherscale" -> {
                Double scale = args.length >= 5 ? parseDouble(args[4]) : null;
                if (scale == null || scale <= 0.0) {
                    sendError(sender, "Usage", "/resync world profile <world> netherscale <value>");
                    return;
                }
                applyWorldProfile(sender, service, worldName, profile, updated -> updated.setNetherScale(scale));
            }
            case "endscale" -> {
                Double scale = args.length >= 5 ? parseDouble(args[4]) : null;
                if (scale == null || scale <= 0.0) {
                    sendError(sender, "Usage", "/resync world profile <world> endscale <value>");
                    return;
                }
                applyWorldProfile(sender, service, worldName, profile, updated -> updated.setEndScale(scale));
            }
            case "autonether" -> handleWorldProfileBoolean(sender, service, worldName, profile, args, 4, "/resync world profile <world> autonether <enabled>", WorldProfileSettings::setAutoLinkNetherPortal);
            case "autoend" -> handleWorldProfileBoolean(sender, service, worldName, profile, args, 4, "/resync world profile <world> autoend <enabled>", WorldProfileSettings::setAutoLinkEndPortal);
            case "spawn" -> {
                if (args.length < 7) {
                    sendError(sender, "Usage", "/resync world profile <world> spawn <x> <y> <z> [yaw] [pitch]");
                    return;
                }
                Double x = parseDouble(args[4]);
                Double y = parseDouble(args[5]);
                Double z = parseDouble(args[6]);
                Float yaw = args.length >= 8 ? parseFloat(args[7]) : 0f;
                Float pitch = args.length >= 9 ? parseFloat(args[8]) : 0f;
                if (x == null || y == null || z == null || yaw == null || pitch == null) {
                    sendError(sender, "Invalid Spawn Coordinates");
                    return;
                }
                applyWorldProfile(sender, service, worldName, profile, updated -> {
                    updated.setCustomSpawnEnabled(true);
                    updated.setSpawnX(x);
                    updated.setSpawnY(y);
                    updated.setSpawnZ(z);
                    updated.setSpawnYaw(yaw);
                    updated.setSpawnPitch(pitch);
                });
            }
            case "spawnoff" -> applyWorldProfile(sender, service, worldName, profile, updated -> updated.setCustomSpawnEnabled(false));
            case "entryfee" -> {
                Double amount = args.length >= 5 ? parseDouble(args[4]) : null;
                if (amount == null) {
                    sendError(sender, "Usage", "/resync world profile <world> entryfee <amount>");
                    return;
                }
                applyWorldProfile(sender, service, worldName, profile, updated -> {
                    updated.setEntryFeeEnabled(amount > 0.0);
                    updated.setEntryFee(amount);
                });
            }
            default -> sendError(sender, "Unknown Profile Action", profileAction);
        }
    }

    private void applyWorldProfile(CommandSender sender, WorldManagementService service, String worldName, WorldProfileSettings current, Consumer<WorldProfileSettings> mutator) {
        WorldProfileSettings updated = current == null ? new WorldProfileSettings() : current.copy();
        mutator.accept(updated);
        sendResult(sender, service.setWorldProfile(worldName, updated));
    }

    private void sendWorldProfileInfo(CommandSender sender, String worldName, WorldProfileSettings profile) {
        if (profile == null) {
            sendInfo(sender, "No World Profile", worldName);
            return;
        }
        sendInfo(sender, "Profile", worldName);
        sendInfo(sender, "Alias", safeText(profile.getAlias()).isBlank() ? "None" : safeText(profile.getAlias()));
        sendInfo(sender, "Hidden", String.valueOf(profile.isHidden()));
        sendInfo(sender, "Access", safeText(profile.getAccessPermission()).isBlank() ? "Open" : safeText(profile.getAccessPermission()));
        sendInfo(sender, "Bypass", safeText(profile.getBypassPermission()).isBlank() ? "None" : safeText(profile.getBypassPermission()));
        sendInfo(sender, "Respawn", safeText(profile.getRespawnWorld()).isBlank() ? "Default" : safeText(profile.getRespawnWorld()));
        sendInfo(sender, "Force Gamemode", profile.isForceGameMode() ? safeText(profile.getGameMode()) : "Disabled");
        sendInfo(sender, "Custom Spawn", profile.isCustomSpawnEnabled() ? formatNumber(profile.getSpawnX()) + "," + formatNumber(profile.getSpawnY()) + "," + formatNumber(profile.getSpawnZ()) + " · " + formatNumber(profile.getSpawnYaw()) + "," + formatNumber(profile.getSpawnPitch()) : "Disabled");
        sendInfo(sender, "Entry Fee", profile.isEntryFeeEnabled() ? formatNumber(profile.getEntryFee()) : "Disabled");
        sendInfo(sender, "Pvp", String.valueOf(profile.isPvpEnabled()));
        sendInfo(sender, "Keep Spawn", String.valueOf(profile.isKeepSpawnLoaded()));
        sendInfo(sender, "Autosave", String.valueOf(profile.isAutoSaveEnabled()));
        sendInfo(sender, "Animal Spawns", String.valueOf(profile.isAnimalSpawnsEnabled()));
        sendInfo(sender, "Monster Spawns", String.valueOf(profile.isMonsterSpawnsEnabled()));
        sendInfo(sender, "Misc Spawns", String.valueOf(profile.isNonLivingEntitySpawnsEnabled()));
        sendInfo(sender, "Hunger", String.valueOf(profile.isHungerEnabled()));
        sendInfo(sender, "Auto Heal", String.valueOf(profile.isAutoHealEnabled()));
        sendInfo(sender, "Bed Respawn", String.valueOf(profile.isBedRespawnEnabled()));
        sendInfo(sender, "Anchor Respawn", String.valueOf(profile.isAnchorRespawnEnabled()));
        sendInfo(sender, "Arrival", safeText(profile.getArrivalMessage()).isBlank() ? "None" : safeText(profile.getArrivalMessage()));
        sendInfo(sender, "Deny", safeText(profile.getDenyMessage()).isBlank() ? "None" : safeText(profile.getDenyMessage()));
        sendInfo(sender, "Inventory Group", safeText(profile.getInventoryGroupId()).isBlank() ? "None" : safeText(profile.getInventoryGroupId()));
        sendInfo(sender, "Linked Nether", safeText(profile.getLinkedNetherWorld()).isBlank() ? "Default" : safeText(profile.getLinkedNetherWorld()));
        sendInfo(sender, "Linked End", safeText(profile.getLinkedEndWorld()).isBlank() ? "Default" : safeText(profile.getLinkedEndWorld()));
        sendInfo(sender, "Linked Overworld", safeText(profile.getLinkedOverworld()).isBlank() ? "Default" : safeText(profile.getLinkedOverworld()));
        sendInfo(sender, "Nether Scale", formatNumber(profile.getNetherScale()));
        sendInfo(sender, "End Scale", formatNumber(profile.getEndScale()));
        sendInfo(sender, "Auto Nether Link", String.valueOf(profile.isAutoLinkNetherPortal()));
        sendInfo(sender, "Auto End Link", String.valueOf(profile.isAutoLinkEndPortal()));
    }

    private void handleWorldProfileBoolean(CommandSender sender, WorldManagementService service, String worldName, WorldProfileSettings profile, String[] args,
                                           int valueIndex, String usage, BiConsumer<WorldProfileSettings, Boolean> mutator) {
        String raw = requiredArg(sender, args, valueIndex, usage);
        if (raw == null) {
            return;
        }
        Boolean enabled = parseBooleanValue(raw);
        if (enabled == null) {
            sendError(sender, "Invalid Boolean", raw);
            return;
        }
        applyWorldProfile(sender, service, worldName, profile, updated -> mutator.accept(updated, enabled));
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
        boolean loadAfterClone = false;
        if (args.length >= 5) {
            Boolean parsed = parseBooleanValue(args[4]);
            if (parsed == null) {
                sendError(sender, "Invalid Boolean", args[4]);
                return;
            }
            loadAfterClone = parsed;
        }
        sendResult(sender, service.cloneWorldAsync(source, target, loadAfterClone));
    }

    private void handleWorldDelete(CommandSender sender, WorldManagementService service, String[] args) {
        String worldName = requiredArg(sender, args, 2, "/resync world delete <world> <deleteFiles> [fallbackWorld]");
        String deleteFilesRaw = requiredArg(sender, args, 3, "/resync world delete <world> <deleteFiles> [fallbackWorld]");
        if (worldName == null || deleteFilesRaw == null) {
            return;
        }
        Boolean deleteFiles = parseBooleanValue(deleteFilesRaw);
        if (deleteFiles == null) {
            sendError(sender, "Invalid Boolean", deleteFilesRaw);
            return;
        }
        sendResult(sender, service.deleteWorld(worldName, deleteFiles, optionalArg(args, 4)));
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
        Boolean enabled = parseBooleanValue(args[3]);
        if (enabled == null) {
            sendError(sender, "Invalid Boolean", args[3]);
            return;
        }
        sendResult(sender, service.setIsolatedPlayerState(worldName, enabled));
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
        Boolean enabled = parseBooleanValue(args[3]);
        if (enabled == null) {
            sendError(sender, "Invalid Boolean", args[3]);
            return;
        }
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
        Boolean enabled = parseBooleanValue(args[3]);
        if (enabled == null) {
            sendError(sender, "Invalid Boolean", args[3]);
            return;
        }
        boolean storm = entry != null && entry.isLockedStorm();
        if (args.length >= 5) {
            Boolean parsedStorm = parseBooleanValue(args[4]);
            if (parsedStorm == null) {
                sendError(sender, "Invalid Boolean", args[4]);
                return;
            }
            storm = parsedStorm;
        }
        boolean thundering = entry != null && entry.isLockedThundering();
        if (args.length >= 6) {
            Boolean parsedThundering = parseBooleanValue(args[5]);
            if (parsedThundering == null) {
                sendError(sender, "Invalid Boolean", args[5]);
                return;
            }
            thundering = parsedThundering;
        }
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

    private void handleWorldWho(CommandSender sender, WorldManagementService service, String[] args) {
        String worldName = requiredArg(sender, args, 2, "/resync world who <world>");
        if (worldName == null) {
            return;
        }
        sendResult(sender, service.whoWorld(worldName));
    }

    private void handleWorldPurge(CommandSender sender, WorldManagementService service, String[] args) {
        String worldName = requiredArg(sender, args, 2, "/resync world purge <world> <monsters> [animals] [ambient] [misc] [vehicles] [items]");
        if (worldName == null) {
            return;
        }
        if (args.length < 4) {
            sendError(sender, "Usage", "/resync world purge <world> <monsters> [animals] [ambient] [misc] [vehicles] [items]");
            return;
        }
        Boolean monsters = parseBooleanValue(args[3]);
        Boolean animals = args.length >= 5 ? parseBooleanValue(args[4]) : Boolean.FALSE;
        Boolean ambient = args.length >= 6 ? parseBooleanValue(args[5]) : Boolean.FALSE;
        Boolean misc = args.length >= 7 ? parseBooleanValue(args[6]) : Boolean.FALSE;
        Boolean vehicles = args.length >= 8 ? parseBooleanValue(args[7]) : Boolean.FALSE;
        Boolean items = args.length >= 9 ? parseBooleanValue(args[8]) : Boolean.FALSE;
        if (monsters == null || animals == null || ambient == null || misc == null || vehicles == null || items == null) {
            sendError(sender, "Invalid Boolean", "Use true or false");
            return;
        }
        if (!monsters && !animals && !ambient && !misc && !vehicles && !items) {
            sendError(sender, "No Purge Categories Selected");
            return;
        }
        sendResult(sender, service.purgeWorld(worldName, monsters, animals, ambient, misc, vehicles, items));
    }

    private void handleWorldGroup(CommandSender sender, WorldManagementService service, String[] args) {
        String groupAction = requiredArg(sender, args, 2, "/resync world group <list|info|create|delete|display|worlds|addworld|removeworld|share> ...");
        if (groupAction == null) {
            return;
        }
        switch (groupAction.toLowerCase(Locale.ROOT)) {
            case "list" -> sendInventoryGroupList(sender, service);
            case "info" -> sendInventoryGroupInfo(sender, service, args);
            case "create" -> handleInventoryGroupCreate(sender, service, args);
            case "delete" -> handleInventoryGroupDelete(sender, service, args);
            case "display" -> handleInventoryGroupDisplay(sender, service, args);
            case "worlds" -> handleInventoryGroupWorlds(sender, service, args);
            case "addworld" -> handleInventoryGroupAddWorld(sender, service, args);
            case "removeworld" -> handleInventoryGroupRemoveWorld(sender, service, args);
            case "share" -> handleInventoryGroupShare(sender, service, args);
            default -> sendError(sender, "Unknown Group Action", groupAction);
        }
    }

    private void handleWorldSign(CommandSender sender, WorldManagementService service, String[] args) {
        String signAction = requiredArg(sender, args, 2, "/resync world sign <list|info|create|enable|target|delete> ...");
        if (signAction == null) {
            return;
        }
        switch (signAction.toLowerCase(Locale.ROOT)) {
            case "list" -> sendSignPortalList(sender, service, optionalArg(args, 3));
            case "info" -> sendSignPortalInfo(sender, service, args);
            case "create" -> handleSignPortalCreate(sender, service, args);
            case "enable" -> handleSignPortalEnable(sender, service, args);
            case "target" -> handleSignPortalTarget(sender, service, args);
            case "delete" -> handleSignPortalDelete(sender, service, args);
            default -> sendError(sender, "Unknown Sign Action", signAction);
        }
    }

    private void handlePortalEnable(CommandSender sender, WorldManagementService service, String[] args) {
        String portalId = requiredArg(sender, args, 2, "/resync portal enable <portal> <enabled>");
        String enabledRaw = requiredArg(sender, args, 3, "/resync portal enable <portal> <enabled>");
        if (portalId == null || enabledRaw == null) {
            return;
        }
        Boolean enabled = parseBooleanValue(enabledRaw);
        if (enabled == null) {
            sendError(sender, "Invalid Boolean", enabledRaw);
            return;
        }
        sendResult(sender, service.setPortalEnabled(portalId, enabled));
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

    private void handlePortalAccess(CommandSender sender, WorldManagementService service, String[] args) {
        String portalId = requiredArg(sender, args, 2, "/resync portal access <portal> [permission|none]");
        if (portalId == null) {
            return;
        }
        applyPortalMutation(sender, service, portalId, portal -> portal.setAccessPermission(normalizeOptionalText(joinArgs(args, 3))));
    }

    private void handlePortalBypass(CommandSender sender, WorldManagementService service, String[] args) {
        String portalId = requiredArg(sender, args, 2, "/resync portal bypass <portal> [permission|none]");
        if (portalId == null) {
            return;
        }
        applyPortalMutation(sender, service, portalId, portal -> portal.setBypassPermission(normalizeOptionalText(joinArgs(args, 3))));
    }

    private void handlePortalFee(CommandSender sender, WorldManagementService service, String[] args) {
        String portalId = requiredArg(sender, args, 2, "/resync portal fee <portal> <amount|off>");
        String raw = requiredArg(sender, args, 3, "/resync portal fee <portal> <amount|off>");
        if (portalId == null || raw == null) {
            return;
        }
        if (isResetToken(raw)) {
            applyPortalMutation(sender, service, portalId, portal -> {
                portal.setUsageFeeEnabled(false);
                portal.setUsageFee(0.0);
            });
            return;
        }
        Double amount = parseDouble(raw);
        if (amount == null) {
            sendError(sender, "Invalid Fee", raw);
            return;
        }
        applyPortalMutation(sender, service, portalId, portal -> {
            portal.setUsageFeeEnabled(amount > 0.0);
            portal.setUsageFee(Math.max(0.0, amount));
        });
    }

    private void handlePortalCooldown(CommandSender sender, WorldManagementService service, String[] args) {
        String portalId = requiredArg(sender, args, 2, "/resync portal cooldown <portal> <millis>");
        String raw = requiredArg(sender, args, 3, "/resync portal cooldown <portal> <millis>");
        if (portalId == null || raw == null) {
            return;
        }
        Long cooldown = parseLong(raw);
        if (cooldown == null || cooldown < 0L) {
            sendError(sender, "Invalid Cooldown", raw);
            return;
        }
        applyPortalMutation(sender, service, portalId, portal -> portal.setCooldownMillis(cooldown));
    }

    private void handlePortalPriority(CommandSender sender, WorldManagementService service, String[] args) {
        String portalId = requiredArg(sender, args, 2, "/resync portal priority <portal> <value>");
        String raw = requiredArg(sender, args, 3, "/resync portal priority <portal> <value>");
        if (portalId == null || raw == null) {
            return;
        }
        Integer priority = parseInt(raw);
        if (priority == null) {
            sendError(sender, "Invalid Priority", raw);
            return;
        }
        applyPortalMutation(sender, service, portalId, portal -> portal.setPriority(priority));
    }

    private void handlePortalSafe(CommandSender sender, WorldManagementService service, String[] args) {
        handlePortalBooleanMutation(sender, service, args, "/resync portal safe <portal> <enabled>", WorldPortal::setSafeTeleport);
    }

    private void handlePortalVelocity(CommandSender sender, WorldManagementService service, String[] args) {
        handlePortalBooleanMutation(sender, service, args, "/resync portal velocity <portal> <enabled>", WorldPortal::setPreserveVelocity);
    }

    private void handlePortalMessage(CommandSender sender, WorldManagementService service, String[] args) {
        String portalId = requiredArg(sender, args, 2, "/resync portal message <portal> [message|none]");
        if (portalId == null) {
            return;
        }
        applyPortalMutation(sender, service, portalId, portal -> portal.setEnterMessage(normalizeOptionalText(joinArgs(args, 3))));
    }

    private void handlePortalVehiclePass(CommandSender sender, WorldManagementService service, String[] args) {
        handlePortalBooleanMutation(sender, service, args, "/resync portal vehiclepass <portal> <enabled>", WorldPortal::setVehiclePassthroughEnabled);
    }

    private void handlePortalEntityPass(CommandSender sender, WorldManagementService service, String[] args) {
        handlePortalBooleanMutation(sender, service, args, "/resync portal entitypass <portal> <enabled>", WorldPortal::setEntityPassthroughEnabled);
    }

    private void handlePortalMode(CommandSender sender, WorldManagementService service, String[] args) {
        String portalId = requiredArg(sender, args, 2, "/resync portal mode <portal> <WORLD|CANNON>");
        String raw = requiredArg(sender, args, 3, "/resync portal mode <portal> <WORLD|CANNON>");
        if (portalId == null || raw == null) {
            return;
        }
        String mode = raw.trim().toUpperCase(Locale.ROOT);
        if (!portalModeOptions().contains(mode)) {
            sendError(sender, "Invalid Portal Mode", raw);
            return;
        }
        applyPortalMutation(sender, service, portalId, portal -> portal.setDestinationMode(mode));
    }

    private void handlePortalCannon(CommandSender sender, WorldManagementService service, String[] args) {
        String portalId = requiredArg(sender, args, 2, "/resync portal cannon <portal> <power>");
        String raw = requiredArg(sender, args, 3, "/resync portal cannon <portal> <power>");
        if (portalId == null || raw == null) {
            return;
        }
        Double power = parseDouble(raw);
        if (power == null || power <= 0.0) {
            sendError(sender, "Invalid Cannon Power", raw);
            return;
        }
        applyPortalMutation(sender, service, portalId, portal -> portal.setCannonPower(power));
    }

    private void handlePortalBooleanMutation(CommandSender sender, WorldManagementService service, String[] args, String usage,
                                             BiConsumer<WorldPortal, Boolean> mutator) {
        String portalId = requiredArg(sender, args, 2, usage);
        String raw = requiredArg(sender, args, 3, usage);
        if (portalId == null || raw == null) {
            return;
        }
        Boolean enabled = parseBooleanValue(raw);
        if (enabled == null) {
            sendError(sender, "Invalid Boolean", raw);
            return;
        }
        applyPortalMutation(sender, service, portalId, portal -> mutator.accept(portal, enabled));
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
        sendInfo(sender, "Generator Hints", "Use Exact Value");
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
        sendInfo(sender, "Portal", safeText(portal.getPortalName()).isBlank() ? safeText(portal.getPortalId()) : safeText(portal.getPortalName()));
        sendInfo(sender, "Portal Id", safeText(portal.getPortalId()));
        sendInfo(sender, "Source", safeText(portal.getSourceWorld()));
        sendInfo(sender, "Bounds", formatNumber(portal.getMinX()) + "," + formatNumber(portal.getMinY()) + "," + formatNumber(portal.getMinZ()) + " -> " + formatNumber(portal.getMaxX()) + "," + formatNumber(portal.getMaxY()) + "," + formatNumber(portal.getMaxZ()));
        sendInfo(sender, "Destination", safeText(portal.getDestinationWorld()) + " · " + formatNumber(portal.getDestinationX()) + "," + formatNumber(portal.getDestinationY()) + "," + formatNumber(portal.getDestinationZ()));
        sendInfo(sender, "Rotation", formatNumber(portal.getDestinationYaw()) + " · " + formatNumber(portal.getDestinationPitch()));
        sendInfo(sender, "State", portal.isEnabled() ? "Enabled" : "Disabled");
        sendInfo(sender, "Access", safeText(portal.getAccessPermission()).isBlank() ? "Open" : safeText(portal.getAccessPermission()));
        sendInfo(sender, "Bypass", safeText(portal.getBypassPermission()).isBlank() ? "None" : safeText(portal.getBypassPermission()));
        sendInfo(sender, "Usage Fee", portal.isUsageFeeEnabled() ? formatNumber(portal.getUsageFee()) : "Disabled");
        sendInfo(sender, "Cooldown", portal.getCooldownMillis() + "ms");
        sendInfo(sender, "Priority", String.valueOf(portal.getPriority()));
        sendInfo(sender, "Safe Teleport", String.valueOf(portal.isSafeTeleport()));
        sendInfo(sender, "Keep Velocity", String.valueOf(portal.isPreserveVelocity()));
        sendInfo(sender, "Vehicle Pass", String.valueOf(portal.isVehiclePassthroughEnabled()));
        sendInfo(sender, "Entity Pass", String.valueOf(portal.isEntityPassthroughEnabled()));
        sendInfo(sender, "Mode", portal.getDestinationMode());
        sendInfo(sender, "Cannon Power", formatNumber(portal.getCannonPower()));
        sendInfo(sender, "Enter Message", safeText(portal.getEnterMessage()).isBlank() ? "None" : safeText(portal.getEnterMessage()));
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
            if (entry.getValue() instanceof WorldInventoryGroup group) {
                sendInfo(sender, prettyKey(entry.getKey()), safeText(group.getGroupId()));
                continue;
            }
            if (entry.getValue() instanceof WorldSignPortal signPortal) {
                sendInfo(sender, prettyKey(entry.getKey()), safeText(signPortal.getSignId()));
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
        if (plugin.getReSyncServer() == null) {
            return null;
        }
        return plugin.getReSyncServer().getWorldManagementService();
    }

    private FlowStorage getStorage() {
        if (plugin.getReSyncServer() == null) {
            return null;
        }
        return plugin.getReSyncServer().getFlowStorage();
    }

    private CustomContentStorage getCustomContentStorage() {
        if (plugin.getReSyncServer() == null || plugin.getReSyncServer().getModuleContext() == null) {
            return null;
        }
        return plugin.getReSyncServer().getModuleContext().getService(CustomContentStorage.class);
    }

    private CustomContentService getCustomContentService() {
        if (plugin.getReSyncServer() == null || plugin.getReSyncServer().getModuleContext() == null) {
            return null;
        }
        return plugin.getReSyncServer().getModuleContext().getService(CustomContentService.class);
    }

    private ReSyncJsonResourceStorage getJsonResourceStorage() {
        if (plugin.getReSyncServer() == null || plugin.getReSyncServer().getModuleContext() == null) {
            return null;
        }
        return plugin.getReSyncServer().getModuleContext().getService(ReSyncJsonResourceStorage.class);
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

    private List<String> groupIds() {
        WorldManagementService service = getWorldService();
        if (service == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (WorldInventoryGroup group : service.getInventoryGroups()) {
            if (group != null && group.getGroupId() != null && !group.getGroupId().isBlank()) {
                values.add(group.getGroupId());
            }
        }
        values.sort(String.CASE_INSENSITIVE_ORDER);
        return values;
    }

    private List<String> signIds() {
        WorldManagementService service = getWorldService();
        if (service == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (WorldSignPortal signPortal : service.getSignPortals()) {
            if (signPortal != null && signPortal.getSignId() != null && !signPortal.getSignId().isBlank()) {
                values.add(signPortal.getSignId());
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

    private List<String> gamemodeOptions() {
        return List.of("SURVIVAL", "CREATIVE", "ADVENTURE", "SPECTATOR");
    }

    private List<String> environmentOptions() {
        return List.of("NORMAL", "NETHER", "THE_END");
    }

    private List<String> booleanOptions() {
        return List.of("true", "false");
    }

    private List<String> portalModeOptions() {
        return List.of("WORLD", "CANNON");
    }

    private List<String> worldProfileActions() {
        return List.of("show", "showall", "hide", "unhide", "alias", "access", "bypass", "respawn", "gamemode", "gamemodeoff", "spawn", "spawnoff", "entryfee", "pvp", "keepspawn", "autosave", "animals", "monsters", "misc", "hunger", "autoheal", "bedrespawn", "anchorrespawn", "arrival", "deny", "group", "linknether", "linkend", "linkoverworld", "netherscale", "endscale", "autonether", "autoend");
    }

    private List<String> inventoryGroupShareOptions() {
        return List.of("inventory", "armor", "offhand", "enderchest", "health", "hunger", "experience", "gamemode", "potions", "lastlocation", "bedspawn");
    }

    private List<String> onlinePlayers() {
        List<String> names = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            names.add(online.getName());
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private List<String> customContentIds() {
        CustomContentStorage storage = getCustomContentStorage();
        if (storage == null) {
            return List.of();
        }
        return storage.listIds();
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

    private List<String> appendOption(List<String> values, String option) {
        List<String> out = new ArrayList<>(values);
        if (option != null && !option.isBlank() && !out.contains(option)) {
            out.add(option);
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

    private String normalizeOptionalText(String value) {
        String trimmed = safeText(value).trim();
        return isResetToken(trimmed) ? "" : trimmed;
    }

    private boolean isResetToken(String value) {
        String normalized = safeText(value).trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() || normalized.equals("none") || normalized.equals("off") || normalized.equals("clear") || normalized.equals("default") || normalized.equals("disabled");
    }

    private Boolean parseBooleanValue(String value) {
        String normalized = safeText(value).trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "true", "on", "yes", "enabled" -> true;
            case "false", "off", "no", "disabled" -> false;
            default -> null;
        };
    }

    private List<String> parseTokenList(String value) {
        List<String> values = new ArrayList<>();
        for (String token : safeText(value).trim().split("[,\\s]+")) {
            if (!token.isBlank() && values.stream().noneMatch(existing -> existing.equalsIgnoreCase(token))) {
                values.add(token);
            }
        }
        values.sort(String.CASE_INSENSITIVE_ORDER);
        return values;
    }

    private boolean validGroupId(String value) {
        return value != null && value.matches("^[a-zA-Z0-9_\\-]+$");
    }

    private void applyPortalMutation(CommandSender sender, WorldManagementService service, String portalId,
                                     Consumer<WorldPortal> mutator) {
        WorldPortal portal = service.getPortal(portalId);
        if (portal == null) {
            sendError(sender, "Portal Not Found", portalId);
            return;
        }
        WorldPortal updated = portal.copy();
        mutator.accept(updated);
        sendResult(sender, service.resizePortal(updated));
    }

    private void applyInventoryGroupMutation(CommandSender sender, WorldManagementService service, String groupId,
                                             Consumer<WorldInventoryGroup> mutator) {
        WorldInventoryGroup group = findInventoryGroup(service, groupId);
        if (group == null) {
            sendError(sender, "Inventory Group Not Found", groupId);
            return;
        }
        WorldInventoryGroup updated = group.copy();
        mutator.accept(updated);
        sendResult(sender, service.updateInventoryGroup(updated));
    }

    private void applySignPortalMutation(CommandSender sender, WorldManagementService service, String signId,
                                         Consumer<WorldSignPortal> mutator) {
        WorldSignPortal signPortal = findSignPortal(service, signId);
        if (signPortal == null) {
            sendError(sender, "Sign Portal Not Found", signId);
            return;
        }
        WorldSignPortal updated = signPortal.copy();
        mutator.accept(updated);
        sendResult(sender, service.createSignPortal(updated));
    }

    private WorldInventoryGroup findInventoryGroup(WorldManagementService service, String groupId) {
        if (service == null || groupId == null) {
            return null;
        }
        for (WorldInventoryGroup group : service.getInventoryGroups()) {
            if (group != null && group.getGroupId() != null && group.getGroupId().equalsIgnoreCase(groupId)) {
                return group;
            }
        }
        return null;
    }

    private WorldSignPortal findSignPortal(WorldManagementService service, String signId) {
        if (service == null || signId == null) {
            return null;
        }
        for (WorldSignPortal signPortal : service.getSignPortals()) {
            if (signPortal != null && signPortal.getSignId() != null && signPortal.getSignId().equalsIgnoreCase(signId)) {
                return signPortal;
            }
        }
        return null;
    }

    private void resolveSignPortalTarget(WorldManagementService service, WorldSignPortal signPortal, String portalTarget) {
        WorldPortal portal = service.getPortal(portalTarget);
        if (portal != null) {
            signPortal.setPortalId(safeText(portal.getPortalId()));
            signPortal.setPortalName(safeText(portal.getPortalName()));
            return;
        }
        signPortal.setPortalId("");
        signPortal.setPortalName(portalTarget);
    }

    private boolean applyInventoryGroupShare(WorldInventoryGroup group, String shareType, boolean enabled) {
        return switch (safeText(shareType).toLowerCase(Locale.ROOT)) {
            case "inventory" -> {
                group.setShareInventory(enabled);
                yield true;
            }
            case "armor" -> {
                group.setShareArmor(enabled);
                yield true;
            }
            case "offhand" -> {
                group.setShareOffhand(enabled);
                yield true;
            }
            case "enderchest" -> {
                group.setShareEnderChest(enabled);
                yield true;
            }
            case "health" -> {
                group.setShareHealth(enabled);
                yield true;
            }
            case "hunger" -> {
                group.setShareHunger(enabled);
                yield true;
            }
            case "experience" -> {
                group.setShareExperience(enabled);
                yield true;
            }
            case "gamemode" -> {
                group.setShareGameMode(enabled);
                yield true;
            }
            case "potions" -> {
                group.setSharePotionEffects(enabled);
                yield true;
            }
            case "lastlocation" -> {
                group.setShareLastLocation(enabled);
                yield true;
            }
            case "bedspawn" -> {
                group.setShareBedSpawn(enabled);
                yield true;
            }
            default -> false;
        };
    }

    private String describeInventoryGroupShares(WorldInventoryGroup group) {
        List<String> values = new ArrayList<>();
        if (group.isShareInventory()) {
            values.add("Inventory");
        }
        if (group.isShareArmor()) {
            values.add("Armor");
        }
        if (group.isShareOffhand()) {
            values.add("Offhand");
        }
        if (group.isShareEnderChest()) {
            values.add("EnderChest");
        }
        if (group.isShareHealth()) {
            values.add("Health");
        }
        if (group.isShareHunger()) {
            values.add("Hunger");
        }
        if (group.isShareExperience()) {
            values.add("Experience");
        }
        if (group.isShareGameMode()) {
            values.add("GameMode");
        }
        if (group.isSharePotionEffects()) {
            values.add("Potions");
        }
        if (group.isShareLastLocation()) {
            values.add("LastLocation");
        }
        if (group.isShareBedSpawn()) {
            values.add("BedSpawn");
        }
        return values.isEmpty() ? "None" : String.join(", ", values);
    }

    private void sendInventoryGroupList(CommandSender sender, WorldManagementService service) {
        List<WorldInventoryGroup> groups = new ArrayList<>(service.getInventoryGroups());
        groups.sort(Comparator.comparing(WorldInventoryGroup::getGroupId, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        if (groups.isEmpty()) {
            sendInfo(sender, "No Inventory Groups Found");
            return;
        }
        for (WorldInventoryGroup group : groups) {
            if (group == null) {
                continue;
            }
            sendInfo(sender, safeText(group.getGroupId()) + " · Worlds " + group.getWorlds().size() + " · Shares " + describeInventoryGroupShares(group));
        }
    }

    private void sendInventoryGroupInfo(CommandSender sender, WorldManagementService service, String[] args) {
        String groupId = requiredArg(sender, args, 3, "/resync world group info <group>");
        if (groupId == null) {
            return;
        }
        WorldInventoryGroup group = findInventoryGroup(service, groupId);
        if (group == null) {
            sendError(sender, "Inventory Group Not Found", groupId);
            return;
        }
        sendInfo(sender, "Inventory Group", safeText(group.getGroupId()));
        sendInfo(sender, "Display", safeText(group.getDisplayName()).isBlank() ? "None" : safeText(group.getDisplayName()));
        sendInfo(sender, "Worlds", group.getWorlds().isEmpty() ? "None" : String.join(", ", group.getWorlds()));
        sendInfo(sender, "Shares", describeInventoryGroupShares(group));
    }

    private void handleInventoryGroupCreate(CommandSender sender, WorldManagementService service, String[] args) {
        String groupId = requiredArg(sender, args, 3, "/resync world group create <groupId> [displayName]");
        if (groupId == null) {
            return;
        }
        if (!validGroupId(groupId)) {
            sendError(sender, "Invalid Group Id", groupId);
            return;
        }
        WorldInventoryGroup existing = findInventoryGroup(service, groupId);
        if (existing != null) {
            sendError(sender, "Inventory Group Already Exists", groupId);
            return;
        }
        WorldInventoryGroup group = new WorldInventoryGroup();
        group.setGroupId(groupId);
        group.setDisplayName(joinArgs(args, 4).trim());
        sendResult(sender, service.createInventoryGroup(group));
    }

    private void handleInventoryGroupDelete(CommandSender sender, WorldManagementService service, String[] args) {
        String groupId = requiredArg(sender, args, 3, "/resync world group delete <group>");
        if (groupId == null) {
            return;
        }
        sendResult(sender, service.deleteInventoryGroup(groupId));
    }

    private void handleInventoryGroupDisplay(CommandSender sender, WorldManagementService service, String[] args) {
        String groupId = requiredArg(sender, args, 3, "/resync world group display <group> [displayName]");
        if (groupId == null) {
            return;
        }
        applyInventoryGroupMutation(sender, service, groupId, group -> group.setDisplayName(normalizeOptionalText(joinArgs(args, 4))));
    }

    private void handleInventoryGroupWorlds(CommandSender sender, WorldManagementService service, String[] args) {
        String groupId = requiredArg(sender, args, 3, "/resync world group worlds <group> <worldA,worldB,...|none>");
        if (groupId == null) {
            return;
        }
        String raw = joinArgs(args, 4).trim();
        List<String> worlds = isResetToken(raw) ? List.of() : parseTokenList(raw);
        for (String worldName : worlds) {
            if (findWorldEntry(service, worldName) == null) {
                sendError(sender, "World Not Found", worldName);
                return;
            }
        }
        applyInventoryGroupMutation(sender, service, groupId, group -> group.setWorlds(worlds));
    }

    private void handleInventoryGroupAddWorld(CommandSender sender, WorldManagementService service, String[] args) {
        String groupId = requiredArg(sender, args, 3, "/resync world group addworld <group> <world>");
        String worldName = requiredArg(sender, args, 4, "/resync world group addworld <group> <world>");
        if (groupId == null || worldName == null) {
            return;
        }
        if (findWorldEntry(service, worldName) == null) {
            sendError(sender, "World Not Found", worldName);
            return;
        }
        applyInventoryGroupMutation(sender, service, groupId, group -> {
            List<String> worlds = new ArrayList<>(group.getWorlds());
            if (worlds.stream().noneMatch(existing -> existing.equalsIgnoreCase(worldName))) {
                worlds.add(worldName);
            }
            worlds.sort(String.CASE_INSENSITIVE_ORDER);
            group.setWorlds(worlds);
        });
    }

    private void handleInventoryGroupRemoveWorld(CommandSender sender, WorldManagementService service, String[] args) {
        String groupId = requiredArg(sender, args, 3, "/resync world group removeworld <group> <world>");
        String worldName = requiredArg(sender, args, 4, "/resync world group removeworld <group> <world>");
        if (groupId == null || worldName == null) {
            return;
        }
        applyInventoryGroupMutation(sender, service, groupId, group -> {
            List<String> worlds = new ArrayList<>(group.getWorlds());
            worlds.removeIf(existing -> existing != null && existing.equalsIgnoreCase(worldName));
            group.setWorlds(worlds);
        });
    }

    private void handleInventoryGroupShare(CommandSender sender, WorldManagementService service, String[] args) {
        String groupId = requiredArg(sender, args, 3, "/resync world group share <group> <shareType> <enabled>");
        String shareType = requiredArg(sender, args, 4, "/resync world group share <group> <shareType> <enabled>");
        String enabledRaw = requiredArg(sender, args, 5, "/resync world group share <group> <shareType> <enabled>");
        if (groupId == null || shareType == null || enabledRaw == null) {
            return;
        }
        Boolean enabled = parseBooleanValue(enabledRaw);
        if (enabled == null) {
            sendError(sender, "Invalid Boolean", enabledRaw);
            return;
        }
        if (!inventoryGroupShareOptions().stream().anyMatch(option -> option.equalsIgnoreCase(shareType))) {
            sendError(sender, "Invalid Share Type", shareType);
            return;
        }
        applyInventoryGroupMutation(sender, service, groupId, group -> {
            applyInventoryGroupShare(group, shareType, enabled);
        });
    }

    private void sendSignPortalList(CommandSender sender, WorldManagementService service, String worldName) {
        List<WorldSignPortal> signPortals = new ArrayList<>(service.getSignPortals());
        signPortals.sort(Comparator.comparing(WorldSignPortal::getSignId, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        boolean found = false;
        for (WorldSignPortal signPortal : signPortals) {
            if (signPortal == null) {
                continue;
            }
            if (worldName != null && !worldName.isBlank() && !safeText(signPortal.getWorldName()).equalsIgnoreCase(worldName)) {
                continue;
            }
            found = true;
            String target = safeText(signPortal.getPortalName()).isBlank() ? safeText(signPortal.getPortalId()) : safeText(signPortal.getPortalName());
            sendInfo(sender, safeText(signPortal.getSignId()) + " · " + safeText(signPortal.getWorldName()) + " · " + signPortal.getX() + "," + signPortal.getY() + "," + signPortal.getZ() + " · " + target + " · " + (signPortal.isEnabled() ? "Enabled" : "Disabled"));
        }
        if (!found) {
            sendInfo(sender, "No Sign Portals Found");
        }
    }

    private void sendSignPortalInfo(CommandSender sender, WorldManagementService service, String[] args) {
        String signId = requiredArg(sender, args, 3, "/resync world sign info <signId>");
        if (signId == null) {
            return;
        }
        WorldSignPortal signPortal = findSignPortal(service, signId);
        if (signPortal == null) {
            sendError(sender, "Sign Portal Not Found", signId);
            return;
        }
        sendInfo(sender, "Sign Portal", safeText(signPortal.getSignId()));
        sendInfo(sender, "World", safeText(signPortal.getWorldName()));
        sendInfo(sender, "Position", signPortal.getX() + "," + signPortal.getY() + "," + signPortal.getZ());
        sendInfo(sender, "Portal", safeText(signPortal.getPortalName()).isBlank() ? safeText(signPortal.getPortalId()) : safeText(signPortal.getPortalName()));
        sendInfo(sender, "Portal Id", safeText(signPortal.getPortalId()).isBlank() ? "None" : safeText(signPortal.getPortalId()));
        sendInfo(sender, "State", signPortal.isEnabled() ? "Enabled" : "Disabled");
    }

    private void handleSignPortalCreate(CommandSender sender, WorldManagementService service, String[] args) {
        String worldName = requiredArg(sender, args, 3, "/resync world sign create <world> <x> <y> <z> <portal>");
        String xRaw = requiredArg(sender, args, 4, "/resync world sign create <world> <x> <y> <z> <portal>");
        String yRaw = requiredArg(sender, args, 5, "/resync world sign create <world> <x> <y> <z> <portal>");
        String zRaw = requiredArg(sender, args, 6, "/resync world sign create <world> <x> <y> <z> <portal>");
        String portalTarget = requiredArg(sender, args, 7, "/resync world sign create <world> <x> <y> <z> <portal>");
        if (worldName == null || xRaw == null || yRaw == null || zRaw == null || portalTarget == null) {
            return;
        }
        Integer x = parseInt(xRaw);
        Integer y = parseInt(yRaw);
        Integer z = parseInt(zRaw);
        if (x == null || y == null || z == null) {
            sendError(sender, "Invalid Sign Coordinates");
            return;
        }
        WorldSignPortal signPortal = new WorldSignPortal();
        signPortal.setWorldName(worldName);
        signPortal.setX(x);
        signPortal.setY(y);
        signPortal.setZ(z);
        signPortal.setEnabled(true);
        resolveSignPortalTarget(service, signPortal, portalTarget);
        sendResult(sender, service.createSignPortal(signPortal));
    }

    private void handleSignPortalEnable(CommandSender sender, WorldManagementService service, String[] args) {
        String signId = requiredArg(sender, args, 3, "/resync world sign enable <signId> <enabled>");
        String raw = requiredArg(sender, args, 4, "/resync world sign enable <signId> <enabled>");
        if (signId == null || raw == null) {
            return;
        }
        Boolean enabled = parseBooleanValue(raw);
        if (enabled == null) {
            sendError(sender, "Invalid Boolean", raw);
            return;
        }
        applySignPortalMutation(sender, service, signId, signPortal -> signPortal.setEnabled(enabled));
    }

    private void handleSignPortalTarget(CommandSender sender, WorldManagementService service, String[] args) {
        String signId = requiredArg(sender, args, 3, "/resync world sign target <signId> <portal>");
        String portalTarget = requiredArg(sender, args, 4, "/resync world sign target <signId> <portal>");
        if (signId == null || portalTarget == null) {
            return;
        }
        applySignPortalMutation(sender, service, signId, signPortal -> resolveSignPortalTarget(service, signPortal, portalTarget));
    }

    private void handleSignPortalDelete(CommandSender sender, WorldManagementService service, String[] args) {
        String signId = requiredArg(sender, args, 3, "/resync world sign delete <signId>");
        if (signId == null) {
            return;
        }
        sendResult(sender, service.deleteSignPortal(signId));
    }

    private List<String> generatorHints() {
        WorldManagementService service = getWorldService();
        return service == null ? List.of() : service.createSnapshot().getGeneratorHints();
    }

    private List<String> generatorIds() {
        WorldManagementService service = getWorldService();
        if (service == null) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (WorldGeneratorDescriptor descriptor : service.getGeneratorDescriptors()) {
            if (descriptor != null && descriptor.getId() != null && !descriptor.getId().isBlank()) {
                ids.add(descriptor.getId());
            }
        }
        return ids;
    }

    private void sendInfo(CommandSender sender, String message) {
        sender.sendMessage("§8[ReSync] §7" + message);
    }

    private void sendInfo(CommandSender sender, String label, String value) {
        sender.sendMessage("§8[ReSync] §7" + label + " §f" + value);
    }

    private void sendSuccess(CommandSender sender, String message) {
        sender.sendMessage("§8[ReSync] §a" + message);
    }

    private void sendSuccess(CommandSender sender, String label, String value) {
        sender.sendMessage("§8[ReSync] §a" + label + " §f" + value);
    }

    private void sendError(CommandSender sender, String message) {
        sender.sendMessage("§8[ReSync] §c" + message);
    }

    private void sendError(CommandSender sender, String label, String value) {
        sender.sendMessage("§8[ReSync] §c" + label + " §f" + value);
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
        sendInfo(sender, "Commands");
        sendUsageLine(sender, "/resync status");
        sendUsageLine(sender, "/resync network reload");
        sendUsageLine(sender, "/resync portal create <target> <name>");
        sendUsageLine(sender, "/resync portal cancel");
        sendUsageLine(sender, "/resync scoreboard list");
        sendUsageLine(sender, "/resync scoreboard show <player> <scoreboardId> [usePapi]");
        sendUsageLine(sender, "/resync scoreboard hide <player>");
        sendUsageLine(sender, "/resync scoreboard default [<scoreboardId|none> [usePapi]]");
        sendUsageLine(sender, "/resync tab list");
        sendUsageLine(sender, "/resync tab apply <tabId> [player] [usePapi]");
        sendUsageLine(sender, "/resync tab clear [player]");
        sendUsageLine(sender, "/resync tab default [<tabId|none> [usePapi]]");
        sendUsageLine(sender, "/resync tab interval [ticks]");
        sendUsageLine(sender, "/resync item list");
        sendUsageLine(sender, "/resync item give <player> <itemId> [amount]");
        sendUsageLine(sender, "/resync quickedit");
        sendUsageLine(sender, "/resync resource types");
        sendUsageLine(sender, "/resync resource list <type>");
        sendUsageLine(sender, "/resync resource create <type> <id>");
        sendUsageLine(sender, "/resync resource set <type> <id> <field> <value>");
        sendUsageLine(sender, "/resync resource apply permission <id>");
        sendUsageLine(sender, "/resync resource delete <type> <id>");
        sendUsageLine(sender, "/resync world list");
        sendUsageLine(sender, "/resync world info <world>");
        sendUsageLine(sender, "/resync world create <world> [seed] [environment] [generator]");
        sendUsageLine(sender, "/resync world clone <source> <target> [loadAfterClone]");
        sendUsageLine(sender, "/resync world load <world>");
        sendUsageLine(sender, "/resync world unload <world> [fallbackWorld]");
        sendUsageLine(sender, "/resync world delete <world> <deleteFiles> [fallbackWorld]");
        sendUsageLine(sender, "/resync world difficulty <world> [difficulty]");
        sendUsageLine(sender, "/resync world rules <world>");
        sendUsageLine(sender, "/resync world rule <world> <rule> [value]");
        sendUsageLine(sender, "/resync world isolated <world> [enabled]");
        sendUsageLine(sender, "/resync world timelock <world> [enabled] [lockedTime]");
        sendUsageLine(sender, "/resync world weatherlock <world> [enabled] [storm] [thundering]");
        sendUsageLine(sender, "/resync world profile <world> <show|showall|hide|unhide|alias|access|bypass|respawn|gamemode|gamemodeoff|spawn|spawnoff|entryfee|pvp|keepspawn|autosave|animals|monsters|misc|hunger|autoheal|bedrespawn|anchorrespawn|arrival|deny|group|linknether|linkend|linkoverworld|netherscale|endscale|autonether|autoend> ...");
        sendUsageLine(sender, "/resync world who <world>");
        sendUsageLine(sender, "/resync world purge <world> <monsters> [animals] [ambient] [misc] [vehicles] [items]");
        sendUsageLine(sender, "/resync world group list");
        sendUsageLine(sender, "/resync world group info <group>");
        sendUsageLine(sender, "/resync world group create <groupId> [displayName]");
        sendUsageLine(sender, "/resync world group display <group> [displayName]");
        sendUsageLine(sender, "/resync world group worlds <group> <worldA,worldB,...|none>");
        sendUsageLine(sender, "/resync world group addworld <group> <world>");
        sendUsageLine(sender, "/resync world group removeworld <group> <world>");
        sendUsageLine(sender, "/resync world group share <group> <shareType> <enabled>");
        sendUsageLine(sender, "/resync world group delete <group>");
        sendUsageLine(sender, "/resync world sign list [world]");
        sendUsageLine(sender, "/resync world sign info <signId>");
        sendUsageLine(sender, "/resync world sign create <world> <x> <y> <z> <portal>");
        sendUsageLine(sender, "/resync world sign enable <signId> <enabled>");
        sendUsageLine(sender, "/resync world sign target <signId> <portal>");
        sendUsageLine(sender, "/resync world sign delete <signId>");
        sendUsageLine(sender, "/resync world tp <player> <world> [x] [y] [z] [yaw] [pitch]");
        sendUsageLine(sender, "/resync world tpspawn <player> <world>");
        sendUsageLine(sender, "/resync world generators");
        sendUsageLine(sender, "/resync portal list [world]");
        sendUsageLine(sender, "/resync portal info <portal>");
        sendUsageLine(sender, "/resync portal enable <portal> <enabled>");
        sendUsageLine(sender, "/resync portal rename <portal> <newName>");
        sendUsageLine(sender, "/resync portal bounds <portal> [sourceWorld minX minY minZ maxX maxY maxZ]");
        sendUsageLine(sender, "/resync portal dest <portal> [world x y z yaw pitch]");
        sendUsageLine(sender, "/resync portal access <portal> [permission|none]");
        sendUsageLine(sender, "/resync portal bypass <portal> [permission|none]");
        sendUsageLine(sender, "/resync portal fee <portal> <amount|off>");
        sendUsageLine(sender, "/resync portal cooldown <portal> <millis>");
        sendUsageLine(sender, "/resync portal priority <portal> <value>");
        sendUsageLine(sender, "/resync portal safe <portal> <enabled>");
        sendUsageLine(sender, "/resync portal velocity <portal> <enabled>");
        sendUsageLine(sender, "/resync portal message <portal> [message|none]");
        sendUsageLine(sender, "/resync portal vehiclepass <portal> <enabled>");
        sendUsageLine(sender, "/resync portal entitypass <portal> <enabled>");
        sendUsageLine(sender, "/resync portal mode <portal> <WORLD|CANNON>");
        sendUsageLine(sender, "/resync portal cannon <portal> <power>");
        sendUsageLine(sender, "/resync portal tp <player> <portal>");
        sendUsageLine(sender, "/resync portal delete <portal>");
        sendUsageLine(sender, "/resync flow reload nodes");
        sendUsageLine(sender, "/resync flow registry");
        sendUsageLine(sender, "/resync flow registry export");
        sendUsageLine(sender, "/resync flow clients reconnect");
    }

    private void sendUsageLine(CommandSender sender, String usage) {
        sender.sendMessage("§f  " + usage);
    }

    private String text(JsonObject object, String key, String fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            String value = object.get(key).getAsString();
            return value == null || value.isBlank() ? fallback : value;
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
