package restudio.resync.modules;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import restudio.flow.data.FlowGraph;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.customization.ResourceJson;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.FlowPredicateSupport;
import restudio.resync.flow.FlowStorage;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.text.ReTextService;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MessageRewriteModule implements Module, Listener {
    private static final ModuleMetadata METADATA = ModuleMetadata.of("messageRewrite", "Messages").withDependencies("flow");
    private final Deque<JsonObject> traces = new ArrayDeque<>();
    private ReSyncJsonResourceStorage storage;
    private ReTextService text;
    private FlowStorage flowStorage;
    private FlowExecutor flowExecutor;
    private boolean protocolLibAvailable;
    private Object protocolBridge;
    private final PlainTextComponentSerializer plainText = PlainTextComponentSerializer.plainText();
    private final GsonComponentSerializer gsonComponent = GsonComponentSerializer.gson();

    @Override
    public ModuleMetadata getMetadata() {
        return METADATA;
    }

    @Override
    public void initialize(ModuleContext context) {
        storage = context.getRequiredService(ReSyncJsonResourceStorage.class);
        text = context.getRequiredService(ReTextService.class);
        flowStorage = context.getService(FlowStorage.class);
        flowExecutor = context.getService(FlowExecutor.class);
        protocolLibAvailable = Bukkit.getPluginManager().isPluginEnabled("ProtocolLib");
        context.registerService(MessageRewriteModule.class, this);
    }

    @Override
    public void start(ModuleContext context) {
        Bukkit.getPluginManager().registerEvents(this, context.getPlugin());
        registerProtocolListener(context);
    }

    @Override
    public void stop(ModuleContext context) {
        HandlerList.unregisterAll(this);
        if (protocolBridge != null) {
            try {
                protocolBridge.getClass().getMethod("close").invoke(protocolBridge);
            } catch (Exception ignored) {
            }
            protocolBridge = null;
        }
    }

    public boolean protocolLibAvailable() {
        return protocolLibAvailable;
    }

    public List<JsonObject> traces() {
        return List.copyOf(traces);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        String replacement = rewrite("join", event.getPlayer(), event.joinMessage() != null ? plainText.serialize(event.joinMessage()) : "", "", "native");
        if (replacement != null) {
            event.joinMessage(text.render(replacement, event.getPlayer(), event.getPlayer()));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        String replacement = rewrite("quit", event.getPlayer(), event.quitMessage() != null ? plainText.serialize(event.quitMessage()) : "", "", "native");
        if (replacement != null) {
            event.quitMessage(text.render(replacement, event.getPlayer(), event.getPlayer()));
        }
    }

    @EventHandler
    public void onKick(PlayerKickEvent event) {
        String replacement = rewrite("kick", event.getPlayer(), event.reason() != null ? plainText.serialize(event.reason()) : "", "", "native");
        if (replacement != null) {
            event.reason(text.render(replacement, event.getPlayer(), event.getPlayer()));
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        String original = event.deathMessage() != null ? plainText.serialize(event.deathMessage()) : "";
        String replacement = rewrite("death", event.getPlayer(), original, "", "native");
        if (replacement != null) {
            event.deathMessage(text.render(replacement, event.getPlayer(), event.getPlayer()));
        }
    }

    private void registerProtocolListener(ModuleContext context) {
        if (!protocolLibAvailable || protocolBridge != null) {
            return;
        }
        try {
            Class<?> bridgeClass = Class.forName("restudio.resync.modules.ProtocolLibMessageRewriteBridge");
            protocolBridge = bridgeClass.getConstructor(MessageRewriteModule.class, ModuleContext.class).newInstance(this, context);
        } catch (Exception exception) {
            protocolLibAvailable = false;
            protocolBridge = null;
        }
    }

    Component rewritePacketComponent(String source, Player target, Component original, String originalJson) {
        String originalPlain = plainText.serialize(original);
        String replacement = rewrite(source, target, originalPlain, originalJson, "packet");
        if (replacement == null) {
            return null;
        }
        return text.render(replacement, target, target);
    }

    private String rewrite(String source, Player target, String original, String originalJson, String hook) {
        JsonObject rule = storage.listIds(ReSyncResourceCatalog.MESSAGE_RULE).stream()
            .map(id -> storage.get(ReSyncResourceCatalog.MESSAGE_RULE, id))
            .filter(candidate -> ResourceJson.bool(candidate, "enabled", true))
            .filter(candidate -> sourceMatches(candidate, source))
            .filter(candidate -> audienceMatches(candidate, target))
            .filter(candidate -> textMatches(candidate, original))
            .filter(candidate -> flowPredicate(candidate, target, source, original))
            .max(Comparator.comparingInt(candidate -> ResourceJson.integer(candidate, "priority", 0)))
            .orElse(null);
        if (rule == null) {
            return null;
        }
        String playerName = target != null ? target.getName() : "";
        String replacement = applyRule(rule, playerName, original);
        trace(source, playerName, original, originalJson, replacement, ResourceJson.string(rule, "id", ""), hook);
        dispatchFlow(rule, target, source, original, replacement);
        return replacement;
    }

    private String applyRule(JsonObject rule, String playerName, String original) {
        String action = ResourceJson.string(rule, "action", "replace").toLowerCase(Locale.ROOT);
        String replacement = ResourceJson.string(rule, "replacement", original).replace("{player}", playerName).replace("{message}", original);
        return switch (action) {
            case "remove", "clear", "hide" -> "";
            case "flow" -> replacement;
            case "append" -> original + replacement;
            case "prepend" -> replacement + original;
            case "replace_section", "section" -> replaceSection(rule, original, replacement);
            default -> replacement;
        };
    }

    private String replaceSection(JsonObject rule, String original, String replacement) {
        String contains = ResourceJson.string(rule, "contains", "");
        return contains.isBlank() ? replacement : original.replace(contains, replacement);
    }

    private boolean sourceMatches(JsonObject rule, String source) {
        String filter = ResourceJson.string(rule, "source", "");
        return filter.isBlank() || filter.equalsIgnoreCase(source) || ResourceJson.strings(rule, "sources").stream().anyMatch(source::equalsIgnoreCase);
    }

    private boolean audienceMatches(JsonObject rule, Player target) {
        String permission = ResourceJson.string(rule, "permission", ResourceJson.string(rule, "audiencePermission", ""));
        if (!permission.isBlank() && (target == null || !target.hasPermission(permission))) {
            return false;
        }
        List<String> players = ResourceJson.strings(rule, "players");
        if (!players.isEmpty() && (target == null || players.stream().noneMatch(target.getName()::equalsIgnoreCase))) {
            return false;
        }
        return true;
    }

    private boolean textMatches(JsonObject rule, String original) {
        String contains = ResourceJson.string(rule, "contains", "");
        return contains.isBlank() || original.contains(contains);
    }

    private boolean flowPredicate(JsonObject rule, Player target, String source, String original) {
        String flowId = ResourceJson.string(rule, "flowPredicate", "");
        if (flowId.isBlank()) {
            return true;
        }
        return FlowPredicateSupport.evaluate(flowStorage, flowExecutor, flowId, target, null, vars(source, original, original));
    }

    private void dispatchFlow(JsonObject rule, Player target, String source, String original, String replacement) {
        String flowId = ResourceJson.string(rule, "flowId", "");
        if (flowId.isBlank() || flowStorage == null || flowExecutor == null) {
            return;
        }
        FlowGraph graph = flowStorage.getGraph(flowId);
        if (graph == null) {
            return;
        }
        flowExecutor.execute(graph, findStartNode(graph), target, null, vars(source, original, replacement));
    }

    private Map<String, Object> vars(String source, String original, String replacement) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("event.source", source);
        vars.put("event.original", original);
        vars.put("event.replacement", replacement);
        return vars;
    }

    private String findStartNode(FlowGraph graph) {
        return graph != null && graph.getNodes() != null ? graph.getNodes().keySet().stream().sorted(String.CASE_INSENSITIVE_ORDER).findFirst().orElse(null) : null;
    }

    private void trace(String source, String playerName, String original, String originalJson, String replacement, String ruleId, String hook) {
        JsonObject trace = new JsonObject();
        trace.addProperty("source", source);
        trace.addProperty("targetPlayer", playerName);
        trace.addProperty("originalPlainText", original);
        trace.addProperty("originalComponentJson", originalJson);
        trace.addProperty("rewrittenPlainText", replacement);
        trace.addProperty("rewrittenComponentJson", gsonComponent.serialize(text.render(replacement, null, null)));
        trace.addProperty("ruleId", ruleId);
        trace.addProperty("hook", hook);
        traces.addFirst(trace);
        while (traces.size() > 200) {
            traces.removeLast();
        }
    }

    public JsonObject capabilityPayload() {
        JsonObject payload = new JsonObject();
        payload.addProperty("protocolLib", protocolLibAvailable);
        JsonArray unavailable = new JsonArray();
        if (!protocolLibAvailable) {
            unavailable.add("packetText");
            unavailable.add("title");
            unavailable.add("actionbar");
            unavailable.add("bossbar");
            unavailable.add("openScreen");
        }
        payload.add("unavailablePacketHooks", unavailable);
        return payload;
    }

}
