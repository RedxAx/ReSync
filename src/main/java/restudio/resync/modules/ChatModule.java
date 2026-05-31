package restudio.resync.modules;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.chat.ComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.Plugin;
import restudio.flow.data.FlowGraph;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.customization.ResourceJson;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.FlowStorage;
import restudio.resync.modules.chat.event.ReSyncChannelJoinEvent;
import restudio.resync.modules.chat.event.ReSyncChannelLeaveEvent;
import restudio.resync.modules.chat.event.ReSyncChannelSendEvent;
import restudio.resync.modules.chat.event.ReSyncMentionEvent;
import restudio.resync.modules.chat.event.ReSyncPrivateMessageEvent;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.text.ReTextService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class ChatModule implements Module, Listener {
    private static final ModuleMetadata METADATA = ModuleMetadata.of("chat", "Chat").withDependencies("flow");
    private ReSyncJsonResourceStorage storage;
    private ReTextService text;
    private FlowStorage flowStorage;
    private FlowExecutor flowExecutor;
    private Plugin plugin;
    private final Map<UUID, UUID> conversations = new HashMap<>();
    private final Map<UUID, Set<String>> channelMembership = new HashMap<>();
    private final Map<UUID, Map<String, Long>> channelCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> spyTargets = new ConcurrentHashMap<>();
    private final Set<UUID> spies = new HashSet<>();
    private final GsonComponentSerializer gsonComponent = GsonComponentSerializer.gson();
    private final PlainTextComponentSerializer plainText = PlainTextComponentSerializer.plainText();

    @Override
    public ModuleMetadata getMetadata() {
        return METADATA;
    }

    @Override
    public void initialize(ModuleContext context) {
        plugin = context.getPlugin();
        storage = context.getRequiredService(ReSyncJsonResourceStorage.class);
        text = context.getRequiredService(ReTextService.class);
        flowStorage = context.getService(FlowStorage.class);
        flowExecutor = context.getService(FlowExecutor.class);
        context.registerService(ChatModule.class, this);
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
    public void onChat(AsyncChatEvent event) {
        Player sender = event.getPlayer();
        JsonObject channel = selectChannel(sender);
        if (channel == null) {
            return;
        }
        String permission = ResourceJson.string(channel, "speakPermission", "");
        if (!permission.isBlank() && !sender.hasPermission(permission)) {
            event.setCancelled(true);
            sender.sendMessage(text.render(ResourceJson.string(channel, "denyMessage", "<red>No Permission"), sender, sender));
            return;
        }
        if (isMuted(sender, channel)) {
            event.setCancelled(true);
            sender.sendMessage(text.render(ResourceJson.string(channel, "muteMessage", "<red>Muted"), sender, sender));
            return;
        }
        String rawMessage = plainText.serialize(event.message());
        dispatchFlow(channel, "receivedFlow", sender, event, Map.of("event.message", rawMessage, "event.channel", resourceId(channel)));
        rawMessage = plainText.serialize(event.message());
        ChatRuleResult ruleResult = applyChatRules(sender, rawMessage, event, channel);
        if (ruleResult.cancelled()) {
            event.setCancelled(true);
            sender.sendMessage(text.render(ruleResult.message(), sender, sender));
            dispatchFlow(channel, "cancelledFlow", sender, event, Map.of("event.message", rawMessage, "event.channel", resourceId(channel)));
            return;
        }
        if (!ruleResult.redirectChannel().isBlank()) {
            JsonObject redirected = storage.get(ReSyncResourceCatalog.CHAT_CHANNEL, ruleResult.redirectChannel());
            if (redirected != null) {
                channel = redirected;
            }
        }
        if (!markCooldown(sender, channel)) {
            event.setCancelled(true);
            sender.sendMessage(text.render(ResourceJson.string(channel, "cooldownMessage", "<red>Slow Down"), sender, sender));
            return;
        }
        event.message(Component.text(ruleResult.message()));
        applyViewers(event, sender, channel);
        mutateViewers(event, ruleResult.addViewers(), true);
        mutateViewers(event, ruleResult.removeViewers(), false);
        JsonObject format = formatFor(channel);
        event.setCancelled(true);
        List<ChatDelivery> deliveries = new ArrayList<>();
        for (Audience viewer : event.viewers()) {
            deliveries.add(new ChatDelivery(viewer, render(format, channel, sender, sender.displayName(), event.message(), viewer)));
        }
        Bukkit.getScheduler().runTask(plugin, () -> deliveries.forEach(this::deliver));
        dispatchFlow(channel, "routedFlow", sender, event, Map.of("event.message", ruleResult.message(), "event.channel", resourceId(channel), "event.viewers", event.viewers().size()));
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage();
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.startsWith("/msg ") || lower.startsWith("/tell ") || lower.startsWith("/w ")) {
            event.setCancelled(true);
            handleMessage(event.getPlayer(), message.substring(message.indexOf(' ') + 1));
        } else if (lower.startsWith("/reply ") || lower.startsWith("/r ")) {
            event.setCancelled(true);
            handleReply(event.getPlayer(), message.substring(message.indexOf(' ') + 1));
        } else if (lower.startsWith("/socialspy")) {
            event.setCancelled(true);
            String payload = message.length() > "/socialspy".length() ? message.substring("/socialspy".length()).trim() : "";
            if (payload.isBlank()) {
                toggleSpy(event.getPlayer());
            } else {
                toggleSpy(event.getPlayer(), payload);
            }
        } else if (lower.startsWith("/ignore ")) {
            event.setCancelled(true);
            toggleIgnore(event.getPlayer(), message.substring(message.indexOf(' ') + 1).trim());
        } else if (lower.startsWith("/channel join ")) {
            event.setCancelled(true);
            joinChannel(event.getPlayer(), message.substring("/channel join ".length()).trim());
        } else if (lower.startsWith("/channel leave ")) {
            event.setCancelled(true);
            leaveChannel(event.getPlayer(), message.substring("/channel leave ".length()).trim());
        } else {
            handleChannelAlias(event, message, lower);
        }
    }

    private void handleMessage(Player sender, String payload) {
        String[] parts = payload.split(" ", 2);
        if (parts.length < 2) {
            sender.sendMessage(text.render("<red>Usage /msg Player Message", sender, sender));
            return;
        }
        Player target = Bukkit.getPlayerExact(parts[0]);
        if (target == null) {
            sender.sendMessage(text.render("<red>Player Offline", sender, sender));
            return;
        }
        if (isIgnoring(target, sender.getUniqueId())) {
            sender.sendMessage(text.render("<red>Ignored", sender, sender));
            return;
        }
        sendPrivateMessage(sender, target, parts[1]);
    }

    private void handleReply(Player sender, String payload) {
        UUID targetId = conversations.get(sender.getUniqueId());
        Player target = targetId != null ? Bukkit.getPlayer(targetId) : null;
        if (target == null) {
            sender.sendMessage(text.render("<red>No Reply Target", sender, sender));
            return;
        }
        sendPrivateMessage(sender, target, payload);
    }

    private void sendPrivateMessage(Player sender, Player target, String rawMessage) {
        ChatRuleResult ruleResult = applyChatRules(sender, rawMessage, null, null);
        if (ruleResult.cancelled()) {
            sender.sendMessage(text.render(ruleResult.message(), sender, sender));
            return;
        }
        rawMessage = ruleResult.message();
        conversations.put(sender.getUniqueId(), target.getUniqueId());
        conversations.put(target.getUniqueId(), sender.getUniqueId());
        JsonObject format = firstEnabled(ReSyncResourceCatalog.PRIVATE_MESSAGE_FORMAT);
        String senderFormat = ResourceJson.string(format, "sender", "<gray>To <white>{receiver}</white>: <message>");
        String receiverFormat = ResourceJson.string(format, "receiver", "<gray>From <white>{sender}</white>: <message>");
        String spyFormat = ResourceJson.string(format, "spy", "<gray>Spy <white>{sender}</white> -> <white>{receiver}</white>: <message>");
        String mentionedMessage = applyMentions(text.escapeMiniMessage(rawMessage), sender, "");
        sender.sendMessage(text.render(senderFormat.replace("{receiver}", target.getName()).replace("{sender}", sender.getName()).replace("{message}", mentionedMessage), sender, sender));
        target.sendMessage(text.render(receiverFormat.replace("{receiver}", target.getName()).replace("{sender}", sender.getName()).replace("{message}", mentionedMessage), sender, target));
        Bukkit.getPluginManager().callEvent(new ReSyncPrivateMessageEvent(sender, target, rawMessage));
        JsonObject formatResource = firstEnabled(ReSyncResourceCatalog.PRIVATE_MESSAGE_FORMAT);
        dispatchFlow(formatResource, "privateMessageFlow", sender, null, Map.of("event.message", rawMessage, "event.receiver", target));
        for (UUID spyId : spies) {
            Player spy = Bukkit.getPlayer(spyId);
            if (spy != null && !spy.equals(sender) && !spy.equals(target) && shouldSpy(spyId, sender, target)) {
                spy.sendMessage(text.render(spyFormat.replace("{receiver}", target.getName()).replace("{sender}", sender.getName()).replace("{message}", mentionedMessage), sender, spy));
            }
        }
    }

    private void handleChannelAlias(PlayerCommandPreprocessEvent event, String message, String lower) {
        for (String id : storage.listIds(ReSyncResourceCatalog.CHAT_CHANNEL)) {
            JsonObject channel = storage.get(ReSyncResourceCatalog.CHAT_CHANNEL, id);
            for (String alias : ResourceJson.strings(channel, "quickAliases")) {
                String normalized = alias.startsWith("/") ? alias.toLowerCase(Locale.ROOT) : "/" + alias.toLowerCase(Locale.ROOT);
                if (lower.equals(normalized) || lower.startsWith(normalized + " ")) {
                    event.setCancelled(true);
                    String payload = message.length() > normalized.length() ? message.substring(normalized.length()).trim() : "";
                    sendChannelMessage(event.getPlayer(), channel, payload);
                    return;
                }
            }
        }
    }

    private boolean sendChannelMessage(Player sender, JsonObject channel, String rawMessage) {
        if (rawMessage.isBlank()) {
            sender.sendMessage(text.render("<red>Message Required", sender, sender));
            return false;
        }
        String permission = ResourceJson.string(channel, "speakPermission", "");
        if (!permission.isBlank() && !sender.hasPermission(permission)) {
            sender.sendMessage(text.render(ResourceJson.string(channel, "denyMessage", "<red>No Permission"), sender, sender));
            return false;
        }
        if (isMuted(sender, channel)) {
            sender.sendMessage(text.render(ResourceJson.string(channel, "muteMessage", "<red>Muted"), sender, sender));
            return false;
        }
        ChatRuleResult ruleResult = applyChatRules(sender, rawMessage, null, channel);
        if (ruleResult.cancelled()) {
            sender.sendMessage(text.render(ruleResult.message(), sender, sender));
            return false;
        }
        if (!ruleResult.redirectChannel().isBlank()) {
            JsonObject redirected = storage.get(ReSyncResourceCatalog.CHAT_CHANNEL, ruleResult.redirectChannel());
            if (redirected != null) {
                channel = redirected;
            }
        }
        if (!markCooldown(sender, channel)) {
            sender.sendMessage(text.render(ResourceJson.string(channel, "cooldownMessage", "<red>Slow Down"), sender, sender));
            return false;
        }
        JsonObject format = formatFor(channel);
        String message = ruleResult.message();
        applyMentions(message, sender, resourceId(channel));
        Bukkit.getPluginManager().callEvent(new ReSyncChannelSendEvent(sender, message, resourceId(channel)));
        boolean sameWorld = ResourceJson.bool(channel, "sameWorld", false);
        double range = ResourceJson.decimal(channel, "range", -1);
        String readPermission = ResourceJson.string(channel, "readPermission", "");
        List<String> allowedWorlds = ResourceJson.strings(channel, "worldAllowList");
        List<String> deniedWorlds = ResourceJson.strings(channel, "worldDenyList");
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!shouldHide(viewer, sender, channel, sameWorld, range, readPermission, allowedWorlds, deniedWorlds) && !isIgnoring(viewer, sender.getUniqueId())) {
                sendPlayerMessage(viewer, render(format, channel, sender, sender.displayName(), Component.text(message), viewer));
            }
        }
        dispatchFlow(channel, "sentFlow", sender, null, Map.of("event.message", message, "event.channel", resourceId(channel)));
        return true;
    }

    public boolean sendChannelMessage(Player sender, String channelId, String rawMessage) {
        JsonObject channel = storage.get(ReSyncResourceCatalog.CHAT_CHANNEL, channelId);
        if (sender == null || channel == null) {
            return false;
        }
        return sendChannelMessage(sender, channel, rawMessage);
    }

    public boolean setEventMessage(Event event, Player player, String message) {
        if (event instanceof AsyncChatEvent chatEvent) {
            chatEvent.message(text.render(message, player, player));
            return true;
        }
        return false;
    }

    public boolean addEventViewer(Event event, Player viewer) {
        if (event instanceof AsyncChatEvent chatEvent && viewer != null) {
            chatEvent.viewers().add(viewer);
            return true;
        }
        return false;
    }

    public boolean removeEventViewer(Event event, Player viewer) {
        if (event instanceof AsyncChatEvent chatEvent && viewer != null) {
            chatEvent.viewers().remove(viewer);
            return true;
        }
        return false;
    }

    private void toggleSpy(Player player) {
        if (spies.remove(player.getUniqueId())) {
            spyTargets.remove(player.getUniqueId());
            player.sendMessage(text.render("<gray>Spy Off", player, player));
        } else {
            spies.add(player.getUniqueId());
            player.sendMessage(text.render("<green>Spy On", player, player));
        }
    }

    private void toggleSpy(Player player, String targetName) {
        if (targetName.isBlank() || "all".equalsIgnoreCase(targetName)) {
            spies.add(player.getUniqueId());
            spyTargets.remove(player.getUniqueId());
            player.sendMessage(text.render("<green>Spy All", player, player));
            return;
        }
        if ("off".equalsIgnoreCase(targetName) || "none".equalsIgnoreCase(targetName)) {
            spies.remove(player.getUniqueId());
            spyTargets.remove(player.getUniqueId());
            player.sendMessage(text.render("<gray>Spy Off", player, player));
            return;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            player.sendMessage(text.render("<red>Player Offline", player, player));
            return;
        }
        spies.add(player.getUniqueId());
        Set<UUID> targets = spyTargets.computeIfAbsent(player.getUniqueId(), ignored -> ConcurrentHashMap.newKeySet());
        if (targets.remove(target.getUniqueId())) {
            player.sendMessage(text.render("<gray>Spy Target Removed", player, player));
        } else {
            targets.add(target.getUniqueId());
            player.sendMessage(text.render("<green>Spy Target Added", player, player));
        }
        if (targets.isEmpty()) {
            spyTargets.remove(player.getUniqueId());
        }
    }

    private void toggleIgnore(Player player, String targetName) {
        JsonObject ignoreList = storage.get(ReSyncResourceCatalog.IGNORE_LIST, player.getUniqueId().toString());
        if (ignoreList == null) {
            ignoreList = new JsonObject();
            ignoreList.addProperty("id", player.getUniqueId().toString());
        }
        Set<String> ignored = new HashSet<>(ResourceJson.strings(ignoreList, "players"));
        Player target = Bukkit.getPlayerExact(targetName);
        String value = target != null ? target.getUniqueId().toString() : targetName;
        if (ignored.remove(value)) {
            player.sendMessage(text.render("<gray>Ignore Removed", player, player));
        } else {
            ignored.add(value);
            player.sendMessage(text.render("<green>Ignored", player, player));
        }
        JsonArray array = new JsonArray();
        ignored.forEach(array::add);
        ignoreList.add("players", array);
        storage.save(ReSyncResourceCatalog.IGNORE_LIST, ignoreList);
    }

    private boolean shouldSpy(UUID spyId, Player sender, Player target) {
        Set<UUID> targets = spyTargets.get(spyId);
        return targets == null || targets.isEmpty() || targets.contains(sender.getUniqueId()) || targets.contains(target.getUniqueId());
    }

    private boolean isIgnoring(Player receiver, UUID senderId) {
        JsonObject ignoreList = storage.get(ReSyncResourceCatalog.IGNORE_LIST, receiver.getUniqueId().toString());
        return ResourceJson.strings(ignoreList, "players").contains(senderId.toString());
    }

    private boolean isMuted(Player sender, JsonObject channel) {
        List<String> muted = ResourceJson.strings(channel, "mutedPlayers");
        return muted.contains(sender.getUniqueId().toString()) || muted.stream().anyMatch(sender.getName()::equalsIgnoreCase);
    }

    private JsonObject selectChannel(Player sender) {
        JsonObject best = null;
        int bestPriority = Integer.MIN_VALUE;
        Set<String> joined = channelMembership.getOrDefault(sender.getUniqueId(), Set.of());
        for (String id : storage.listIds(ReSyncResourceCatalog.CHAT_CHANNEL)) {
            JsonObject channel = storage.get(ReSyncResourceCatalog.CHAT_CHANNEL, id);
            if (!ResourceJson.bool(channel, "enabled", true)) {
                continue;
            }
            boolean autoJoin = ResourceJson.bool(channel, "autojoin", ResourceJson.bool(channel, "default", true));
            if (!joined.isEmpty() && !joined.contains(id) || joined.isEmpty() && !autoJoin) {
                continue;
            }
            String joinPermission = ResourceJson.string(channel, "joinPermission", "");
            if (!joinPermission.isBlank() && !sender.hasPermission(joinPermission)) {
                continue;
            }
            int priority = ResourceJson.integer(channel, "priority", 0);
            if (best == null || priority > bestPriority) {
                best = channel;
                bestPriority = priority;
            }
        }
        return best;
    }

    private void joinChannel(Player player, String channelId) {
        JsonObject channel = storage.get(ReSyncResourceCatalog.CHAT_CHANNEL, channelId);
        if (channel == null) {
            player.sendMessage(text.render("<red>Channel Missing", player, player));
            return;
        }
        String permission = ResourceJson.string(channel, "joinPermission", "");
        if (!permission.isBlank() && !player.hasPermission(permission)) {
            player.sendMessage(text.render(ResourceJson.string(channel, "denyMessage", "<red>No Permission"), player, player));
            return;
        }
        channelMembership.computeIfAbsent(player.getUniqueId(), ignored -> new HashSet<>()).add(channelId);
        player.sendMessage(text.render(ResourceJson.string(channel, "joinMessage", "<green>Channel Joined"), player, player));
        Bukkit.getPluginManager().callEvent(new ReSyncChannelJoinEvent(player, channelId));
        dispatchFlow(channel, "joinFlow", player, null, Map.of("event.channel", channelId));
    }

    private void leaveChannel(Player player, String channelId) {
        Set<String> joined = channelMembership.get(player.getUniqueId());
        if (joined != null) {
            joined.remove(channelId);
            if (joined.isEmpty()) {
                channelMembership.remove(player.getUniqueId());
            }
        }
        JsonObject channel = storage.get(ReSyncResourceCatalog.CHAT_CHANNEL, channelId);
        player.sendMessage(text.render(ResourceJson.string(channel, "leaveMessage", "<gray>Channel Left"), player, player));
        Bukkit.getPluginManager().callEvent(new ReSyncChannelLeaveEvent(player, channelId));
        dispatchFlow(channel, "leaveFlow", player, null, Map.of("event.channel", channelId));
    }

    private void applyViewers(AsyncChatEvent event, Player sender, JsonObject channel) {
        boolean sameWorld = ResourceJson.bool(channel, "sameWorld", false);
        double range = ResourceJson.decimal(channel, "range", -1);
        String readPermission = ResourceJson.string(channel, "readPermission", "");
        List<String> allowedWorlds = ResourceJson.strings(channel, "worldAllowList");
        List<String> deniedWorlds = ResourceJson.strings(channel, "worldDenyList");
        event.viewers().removeIf(viewer -> {
            if (!(viewer instanceof Player player)) {
                return false;
            }
            String reason = hideReason(player, sender, channel, sameWorld, range, readPermission, allowedWorlds, deniedWorlds);
            if (reason.isBlank()) {
                return false;
            }
            return true;
        });
    }

    private boolean shouldHide(Player viewer, Player sender, JsonObject channel, boolean sameWorld, double range, String readPermission, List<String> allowedWorlds, List<String> deniedWorlds) {
        return !hideReason(viewer, sender, channel, sameWorld, range, readPermission, allowedWorlds, deniedWorlds).isBlank();
    }

    private String hideReason(Player viewer, Player sender, JsonObject channel, boolean sameWorld, double range, String readPermission, List<String> allowedWorlds, List<String> deniedWorlds) {
        if (isIgnoring(viewer, sender.getUniqueId())) {
            return "Ignored";
        }
        if (shouldAlwaysSee(viewer, channel)) {
            return "";
        }
        if (!readPermission.isBlank() && !viewer.hasPermission(readPermission)) {
            return "Missing Read Permission " + readPermission;
        }
        if (sameWorld && !viewer.getWorld().equals(sender.getWorld())) {
            return "Different World";
        }
        if (!allowedWorlds.isEmpty() && !allowedWorlds.contains(viewer.getWorld().getName())) {
            return "World Not Allowed";
        }
        if (!deniedWorlds.isEmpty() && deniedWorlds.contains(viewer.getWorld().getName())) {
            return "World Denied";
        }
        return range > 0 && (!viewer.getWorld().equals(sender.getWorld()) || viewer.getLocation().distanceSquared(sender.getLocation()) > range * range) ? "Out Of Range " + range : "";
    }

    private boolean shouldAlwaysSee(Player viewer, JsonObject channel) {
        if (ResourceJson.strings(channel, "alwaysVisiblePlayers").stream().anyMatch(value -> viewer.getUniqueId().toString().equals(value) || viewer.getName().equalsIgnoreCase(value))) {
            return true;
        }
        String alwaysPermission = ResourceJson.string(channel, "alwaysVisiblePermission", ResourceJson.string(channel, "staffPermission", ""));
        if (!alwaysPermission.isBlank() && viewer.hasPermission(alwaysPermission)) {
            return true;
        }
        for (String permission : ResourceJson.strings(channel, "alwaysVisiblePermissions")) {
            if (!permission.isBlank() && viewer.hasPermission(permission)) {
                return true;
            }
        }
        return false;
    }

    private boolean markCooldown(Player player, JsonObject channel) {
        int cooldownMillis = ResourceJson.integer(channel, "cooldownMillis", 0);
        int cooldownSeconds = ResourceJson.integer(channel, "cooldownSeconds", 0);
        long duration = cooldownMillis > 0 ? cooldownMillis : cooldownSeconds * 1000L;
        if (duration <= 0) {
            return true;
        }
        String bypassPermission = ResourceJson.string(channel, "cooldownBypassPermission", "");
        if (!bypassPermission.isBlank() && player.hasPermission(bypassPermission)) {
            return true;
        }
        String id = resourceId(channel);
        long now = System.currentTimeMillis();
        Map<String, Long> playerCooldowns = channelCooldowns.computeIfAbsent(player.getUniqueId(), ignored -> new ConcurrentHashMap<>());
        long readyAt = playerCooldowns.getOrDefault(id, 0L);
        if (readyAt > now) {
            return false;
        }
        playerCooldowns.put(id, now + duration);
        return true;
    }

    private Component render(JsonObject format, JsonObject channel, Player source, Component sourceDisplayName, Component message, Audience viewer) {
        Player viewerPlayer = viewer instanceof Player player ? player : null;
        String template = ResourceJson.string(format, "template", "{prefix}{sender}: {message}");
        String prefix = ResourceJson.string(channel, "prefix", "");
        String plainMessage = applyMentions(playerMessage(channel, source, plainText.serialize(message)), source, resourceId(channel), false);
        Component rendered = text.render(template
                .replace("{prefix}", "<resync_prefix/>")
                .replace("{sender}", "<resync_sender/>")
                .replace("{message}", "<resync_message/>"),
            source,
            viewerPlayer,
            Placeholder.component("resync_prefix", text.render(prefix, source, viewerPlayer)),
            Placeholder.component("resync_sender", sourceDisplayName),
            Placeholder.component("resync_message", text.render(plainMessage, source, viewerPlayer)));
        return rendered;
    }

    private String playerMessage(JsonObject channel, Player sender, String message) {
        if (!ResourceJson.bool(channel, "allowMiniMessage", false)) {
            return text.escapeMiniMessage(message);
        }
        String permission = ResourceJson.string(channel, "miniMessagePermission", "resync.chat.minimessage");
        return sender.isOp() || !permission.isBlank() && sender.hasPermission(permission) ? message : text.escapeMiniMessage(message);
    }

    private void deliver(ChatDelivery delivery) {
        if (delivery.viewer() instanceof Player player) {
            sendPlayerMessage(player, delivery.message());
            return;
        }
        delivery.viewer().sendMessage(delivery.message());
    }

    private void sendPlayerMessage(Player player, Component message) {
        player.spigot().sendMessage(ChatMessageType.SYSTEM, ComponentSerializer.parse(gsonComponent.serialize(message)));
    }

    private ChatRuleResult applyChatRules(Player sender, String message, Event event, JsonObject channel) {
        String result = message;
        String redirectChannel = "";
        for (JsonObject rule : storage.listIds(ReSyncResourceCatalog.CHAT_RULE).stream()
            .map(id -> storage.get(ReSyncResourceCatalog.CHAT_RULE, id))
            .filter(rule -> ResourceJson.bool(rule, "enabled", true))
            .sorted((left, right) -> Integer.compare(ResourceJson.integer(right, "priority", 0), ResourceJson.integer(left, "priority", 0)))
            .toList()) {
            if (!chatRuleMatches(rule, sender, result)) {
                continue;
            }
            String action = ResourceJson.string(rule, "action", "").toLowerCase(Locale.ROOT);
            if ("cancel".equals(action) || "deny".equals(action) || "block".equals(action)) {
                dispatchFlow(rule, "flowId", sender, event, ruleVars(result, channel));
                return new ChatRuleResult(true, ResourceJson.string(rule, "denyMessage", "<red>Message Blocked"), redirectChannel, List.of(), List.of());
            }
            if ("warn".equals(action)) {
                sender.sendMessage(text.render(ResourceJson.string(rule, "warning", ResourceJson.string(rule, "denyMessage", "<red>Message Blocked")), sender, sender));
            }
            if ("run_command".equals(action) || "command".equals(action)) {
                String command = ResourceJson.string(rule, "command", "");
                if (!command.isBlank()) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("{sender}", sender.getName()).replace("{message}", result));
                }
            }
            if ("redirect".equals(action)) {
                redirectChannel = ResourceJson.string(rule, "channel", ResourceJson.string(rule, "redirectChannel", ""));
            }
            List<String> addViewers = ResourceJson.strings(rule, "addViewers");
            List<String> removeViewers = ResourceJson.strings(rule, "removeViewers");
            if ("add_viewer".equals(action)) {
                addViewers = List.of(ResourceJson.string(rule, "viewer", ""));
            }
            if ("remove_viewer".equals(action)) {
                removeViewers = List.of(ResourceJson.string(rule, "viewer", ""));
            }
            if ("strip_section".equals(action) || "strip".equals(action)) {
                result = stripSection(rule, result);
            }
            String replacement = ResourceJson.string(rule, "replacement", "");
            if (!replacement.isBlank()) {
                result = replacement.replace("{message}", result).replace("{sender}", sender.getName());
            }
            dispatchFlow(rule, "flowId", sender, event, ruleVars(result, channel));
            if (!addViewers.isEmpty() || !removeViewers.isEmpty()) {
                return new ChatRuleResult(false, result, redirectChannel, addViewers, removeViewers);
            }
        }
        return new ChatRuleResult(false, result, redirectChannel, List.of(), List.of());
    }

    private boolean chatRuleMatches(JsonObject rule, Player sender, String message) {
        String permission = ResourceJson.string(rule, "permission", "");
        if (!permission.isBlank() && !sender.hasPermission(permission)) {
            return false;
        }
        String contains = ResourceJson.string(rule, "contains", "");
        if (!contains.isBlank() && !message.toLowerCase(Locale.ROOT).contains(contains.toLowerCase(Locale.ROOT))) {
            return false;
        }
        String regex = ResourceJson.string(rule, "regex", "");
        if (regex.isBlank()) {
            return true;
        }
        try {
            return Pattern.compile(regex).matcher(message).find();
        } catch (PatternSyntaxException ignored) {
            return false;
        }
    }

    private String applyMentions(String message, Player sender, String channelId) {
        return applyMentions(message, sender, channelId, true);
    }

    private String applyMentions(String message, Player sender, String channelId, boolean fireEvent) {
        JsonObject style = firstEnabled(ReSyncResourceCatalog.MENTION_STYLE);
        String template = ResourceJson.string(style, "template", "<yellow>@{player}</yellow>");
        String result = message;
        for (Player player : Bukkit.getOnlinePlayers()) {
            String mention = "@" + player.getName();
            if (result.contains(mention)) {
                result = result.replace(mention, template.replace("{player}", player.getName()));
                if (fireEvent) {
                    Bukkit.getPluginManager().callEvent(new ReSyncMentionEvent(sender, player, message, channelId));
                    dispatchFlow(style, "mentionFlow", player, null, Map.of("event.mentioned", player, "event.message", message));
                }
            }
        }
        return result;
    }

    private JsonObject formatFor(JsonObject channel) {
        String override = ResourceJson.string(channel, "format", "");
        if (!override.isBlank()) {
            JsonObject format = storage.get(ReSyncResourceCatalog.CHAT_FORMAT, override);
            if (format != null) {
                return format;
            }
        }
        return firstEnabled(ReSyncResourceCatalog.CHAT_FORMAT);
    }

    private JsonObject firstEnabled(String type) {
        for (String id : storage.listIds(type)) {
            JsonObject resource = storage.get(type, id);
            if (ResourceJson.bool(resource, "enabled", true)) {
                return resource;
            }
        }
        return null;
    }

    private String stripSection(JsonObject rule, String message) {
        String regex = ResourceJson.string(rule, "stripRegex", ResourceJson.string(rule, "regex", ""));
        if (!regex.isBlank()) {
            try {
                return Pattern.compile(regex).matcher(message).replaceAll("");
            } catch (PatternSyntaxException ignored) {
                return message;
            }
        }
        String contains = ResourceJson.string(rule, "contains", "");
        return contains.isBlank() ? message : message.replace(contains, "");
    }

    private Map<String, Object> ruleVars(String message, JsonObject channel) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("event.message", message);
        vars.put("event.channel", resourceId(channel));
        return vars;
    }

    private void dispatchFlow(JsonObject resource, String field, Player player, Event event, Map<String, Object> vars) {
        String flowId = ResourceJson.string(resource, field, "");
        if (flowId.isBlank() || flowStorage == null || flowExecutor == null) {
            return;
        }
        FlowGraph graph = flowStorage.getGraph(flowId);
        if (graph == null) {
            return;
        }
        Map<String, Object> eventVars = new HashMap<>();
        if (vars != null) {
            eventVars.putAll(vars);
        }
        flowExecutor.execute(graph, findStartNode(graph), player, event, eventVars);
    }

    private String findStartNode(FlowGraph graph) {
        return graph != null && graph.getNodes() != null ? graph.getNodes().keySet().stream().sorted(String.CASE_INSENSITIVE_ORDER).findFirst().orElse(null) : null;
    }

    private String resourceId(JsonObject resource) {
        return ResourceJson.string(resource, "id", "");
    }

    private void mutateViewers(AsyncChatEvent event, List<String> values, boolean add) {
        for (String value : values) {
            Player player = Bukkit.getPlayerExact(value);
            if (player != null) {
                if (add) {
                    event.viewers().add(player);
                } else {
                    event.viewers().remove(player);
                }
            }
        }
    }

    private record ChatRuleResult(boolean cancelled, String message, String redirectChannel, List<String> addViewers, List<String> removeViewers) {
    }

    private record ChatDelivery(Audience viewer, Component message) {
    }

}
