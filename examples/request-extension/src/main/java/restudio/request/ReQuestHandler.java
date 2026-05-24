package restudio.request;

import org.bukkit.entity.Player;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.CustomEventManager;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.NodeHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class ReQuestHandler implements NodeHandler {
    private final ReQuestService service;
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public ReQuestHandler(ReQuestService service) {
        this.service = service;
        operations.put("info", this::info);
        operations.put("find", this::find);
        operations.put("list", this::list);
        operations.put("profile", this::profile);
        operations.put("create", this::create);
        operations.put("action", this::action);
        operations.put("delete", this::delete);
        operations.put("can_start", this::canStart);
        operations.put("start", this::start);
        operations.put("progress", this::progress);
        operations.put("progress_family", this::progressFamily);
        operations.put("set_progress", this::setProgress);
        operations.put("complete", this::complete);
        operations.put("quit", this::quit);
        operations.put("reset", this::reset);
        operations.put("xp", this::xp);
        operations.put("listen", this::listen);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation", "info");
        BiConsumer<FlowContext, FlowNode> handler = operations.get(operation);
        if (handler != null) {
            handler.accept(ctx, node);
        }
        if (!"listen".equals(operation)) {
            ctx.triggerOutput("flow");
        }
    }

    private void info(FlowContext ctx, FlowNode node) {
        Player player = player(ctx, node);
        String questId = questId(ctx, node);
        Quest quest = service.quest(player, questId);
        QuestState state = service.state(player, questId);
        publish(ctx, node, QuestResult.unchanged(stateName(state), "Quest Info", quest, state));
    }

    private void find(FlowContext ctx, FlowNode node) {
        Player player = player(ctx, node);
        String query = text(ctx, node, "query", "");
        String mode = text(ctx, node, "mode", "id_title");
        Quest quest = service.findQuest(player, query, mode);
        QuestState state = quest != null ? service.state(player, quest) : null;
        publish(ctx, node, QuestResult.unchanged(quest != null ? "found" : "missing", quest != null ? "Quest Found" : "Quest Missing", quest, state));
    }

    private void list(FlowContext ctx, FlowNode node) {
        Player player = player(ctx, node);
        String scope = text(ctx, node, "scope", "visible");
        String status = text(ctx, node, "status", "all");
        var quests = service.questList(player, scope, status);
        var ids = service.questIdList(player, scope, status);
        ctx.setOutput(node, "quests", quests);
        ctx.setOutput(node, "quest_ids", ids);
        ctx.setOutput(node, "count", ids.size());
    }

    private void profile(FlowContext ctx, FlowNode node) {
        publishProfile(ctx, node, service.profileData(player(ctx, node)));
    }

    private void create(FlowContext ctx, FlowNode node) {
        Quest quest = new Quest(
            text(ctx, node, "id", ""),
            text(ctx, node, "title", ""),
            text(ctx, node, "description", ""),
            text(ctx, node, "reward", ""),
            number(ctx, node, "target", 1),
            text(ctx, node, "permission", ""),
            text(ctx, node, "world", ""),
            text(ctx, node, "required_quest", ""),
            number(ctx, node, "max_active", 3),
            number(ctx, node, "cooldown_seconds", 0),
            number(ctx, node, "required_level", 1),
            number(ctx, node, "xp_reward", 50),
            text(ctx, node, "scope", "global"),
            ""
        );
        publish(ctx, node, service.create(player(ctx, node), quest));
    }

    private void action(FlowContext ctx, FlowNode node) {
        String action = text(ctx, node, "action", "start");
        Player player = player(ctx, node);
        String questId = questId(ctx, node);
        QuestResult result = switch (action) {
            case "complete" -> service.complete(player, questId);
            case "quit" -> service.quit(player, questId);
            case "reset" -> service.reset(player, questId);
            case "delete" -> service.delete(player, questId);
            case "can_start" -> service.canStart(player, questId);
            default -> service.start(player, questId);
        };
        publish(ctx, node, result);
    }

    private void delete(FlowContext ctx, FlowNode node) {
        publish(ctx, node, service.delete(player(ctx, node), questId(ctx, node)));
    }

    private void canStart(FlowContext ctx, FlowNode node) {
        publish(ctx, node, service.canStart(player(ctx, node), questId(ctx, node)));
    }

    private void start(FlowContext ctx, FlowNode node) {
        publish(ctx, node, service.start(player(ctx, node), questId(ctx, node)));
    }

    private void progress(FlowContext ctx, FlowNode node) {
        publish(ctx, node, service.progress(player(ctx, node), questId(ctx, node), number(ctx, node, "amount", 1)));
    }

    private void progressFamily(FlowContext ctx, FlowNode node) {
        String mode = text(ctx, node, "mode", "add");
        QuestResult result = "set".equalsIgnoreCase(mode)
            ? service.setProgress(player(ctx, node), questId(ctx, node), number(ctx, node, "progress", 0))
            : service.progress(player(ctx, node), questId(ctx, node), number(ctx, node, "amount", 1));
        publish(ctx, node, result);
    }

    private void setProgress(FlowContext ctx, FlowNode node) {
        publish(ctx, node, service.setProgress(player(ctx, node), questId(ctx, node), number(ctx, node, "progress", 0)));
    }

    private void complete(FlowContext ctx, FlowNode node) {
        publish(ctx, node, service.complete(player(ctx, node), questId(ctx, node)));
    }

    private void quit(FlowContext ctx, FlowNode node) {
        publish(ctx, node, service.quit(player(ctx, node), questId(ctx, node)));
    }

    private void reset(FlowContext ctx, FlowNode node) {
        publish(ctx, node, service.reset(player(ctx, node), questId(ctx, node)));
    }

    private void xp(FlowContext ctx, FlowNode node) {
        publish(ctx, node, service.addXp(player(ctx, node), number(ctx, node, "amount", 0)));
    }

    private void listen(FlowContext ctx, FlowNode node) {
        String eventId = node.getHandlerConfig().getString("event", "");
        if (eventId.isBlank()) {
            eventId = text(ctx, node, "event", "request:completed");
        }
        String nodeId = ctx.resolveNodeId(node);
        if (nodeId == null || eventId.isBlank()) {
            ctx.setOutput(node, "listening", false);
            return;
        }
        CustomEventManager.getInstance().listen(eventId, new CustomEventManager.Listener(ctx, nodeId, 0));
        ctx.setOutput(node, "listening", true);
    }

    private void publish(FlowContext ctx, FlowNode node, QuestResult result) {
        Quest quest = result.quest();
        QuestState state = result.state();
        ctx.setOutput(node, "success", result.allowed() && (result.changed() || "found".equals(result.status()) || "allowed".equals(result.status()) || "active".equals(result.status()) || "completed".equals(result.status())));
        ctx.setOutput(node, "found", quest != null);
        ctx.setOutput(node, "changed", result.changed());
        ctx.setOutput(node, "status", result.status());
        ctx.setOutput(node, "reason", result.reason());
        ctx.setOutput(node, "quest", quest != null ? quest.id() : "");
        ctx.setOutput(node, "quest_id", quest != null ? quest.id() : "");
        ctx.setOutput(node, "title", quest != null ? quest.title() : "");
        ctx.setOutput(node, "description", quest != null ? quest.description() : "");
        ctx.setOutput(node, "reward", quest != null ? quest.reward() : "");
        ctx.setOutput(node, "target", quest != null ? quest.target() : 0);
        ctx.setOutput(node, "progress", state != null ? state.progress() : 0);
        ctx.setOutput(node, "previous_progress", number(result.eventData().get("previous_progress")));
        ctx.setOutput(node, "progress_added", number(result.eventData().get("progress_added")));
        ctx.setOutput(node, "progress_percent", number(result.eventData().get("progress_percent")));
        ctx.setOutput(node, "required_level", quest != null ? quest.requiredLevel() : 1);
        ctx.setOutput(node, "xp_reward", quest != null ? quest.xpReward() : 0);
        ctx.setOutput(node, "permission", quest != null ? quest.permission() : "");
        ctx.setOutput(node, "world", quest != null ? quest.world() : "");
        ctx.setOutput(node, "required_quest", quest != null ? quest.requiredQuest() : "");
        ctx.setOutput(node, "max_active", quest != null ? quest.maxActive() : 0);
        ctx.setOutput(node, "cooldown_seconds", quest != null ? quest.cooldownSeconds() : 0);
        ctx.setOutput(node, "scope", quest != null ? quest.scope() : "");
        ctx.setOutput(node, "owner", quest != null ? quest.owner() : "");
        ctx.setOutput(node, "created_at", quest != null ? quest.createdAt() : 0L);
        ctx.setOutput(node, "level", number(result.eventData().get("level")));
        ctx.setOutput(node, "xp", number(result.eventData().get("xp")));
        ctx.setOutput(node, "xp_to_next_level", number(result.eventData().get("xp_to_next_level")));
        ctx.setOutput(node, "previous_level", number(result.eventData().get("previous_level")));
        ctx.setOutput(node, "previous_xp", number(result.eventData().get("previous_xp")));
        ctx.setOutput(node, "active", state != null && state.active());
        ctx.setOutput(node, "completed", state != null && state.completed());
        ctx.setOutput(node, "quit", state != null && state.abandoned());
        ctx.setOutput(node, "started_at", state != null ? state.startedAt() : 0L);
        ctx.setOutput(node, "completed_at", state != null ? state.completedAt() : 0L);
        ctx.setOutput(node, "quit_at", state != null ? state.quitAt() : 0L);
        ctx.setOutput(node, "last_progress_at", state != null ? state.lastProgressAt() : 0L);
        ctx.setOutput(node, "time_to_complete_ms", state != null ? state.timeToComplete() : 0L);
        ctx.setOutput(node, "active_time_ms", state != null ? state.activeTime() : 0L);
    }

    private void publishProfile(FlowContext ctx, FlowNode node, Map<String, Object> profile) {
        ctx.setOutput(node, "profile", profile);
        ctx.setOutput(node, "level", number(profile.get("level")));
        ctx.setOutput(node, "xp", number(profile.get("xp")));
        ctx.setOutput(node, "xp_to_next_level", number(profile.get("xp_to_next_level")));
        ctx.setOutput(node, "active_count", number(profile.get("active_count")));
        ctx.setOutput(node, "completed_count", number(profile.get("completed_count")));
        ctx.setOutput(node, "quit_count", number(profile.get("quit_count")));
        ctx.setOutput(node, "available_count", number(profile.get("available_count")));
        ctx.setOutput(node, "quest_count", number(profile.get("quest_count")));
    }

    private Player player(FlowContext ctx, FlowNode node) {
        return ctx.getPlayerInput(node, "player");
    }

    private String questId(FlowContext ctx, FlowNode node) {
        return text(ctx, node, "quest", "gather_logs");
    }

    private String text(FlowContext ctx, FlowNode node, String pin, String fallback) {
        return ctx.getInputValue(node, pin, String.class, fallback);
    }

    private int number(FlowContext ctx, FlowNode node, String pin, int fallback) {
        Object value = ctx.getInputValue(node, pin);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private int number(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private String stateName(QuestState state) {
        if (state == null) {
            return "missing";
        }
        if (state.completed()) {
            return "completed";
        }
        if (state.active()) {
            return "active";
        }
        if (state.abandoned()) {
            return "quit";
        }
        return "inactive";
    }
}
