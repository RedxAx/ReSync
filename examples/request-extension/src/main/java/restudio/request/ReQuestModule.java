package restudio.request;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.core.ChannelMuxer;
import restudio.resync.core.Session;
import restudio.resync.flow.validation.FlowGraphDiagnostic;
import restudio.resync.flow.validation.FlowGraphValidationRegistry;
import restudio.resync.modules.Module;
import restudio.resync.modules.ModuleContext;
import restudio.resync.modules.ModuleMetadata;
import restudio.resync.protocol.messages.DataMessage;
import restudio.resync.protocol.messages.SubscribeRequest;
import restudio.resync.protocol.messages.UnsubscribeRequest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ReQuestModule implements Module {
    private static final ModuleMetadata METADATA = new ModuleMetadata(
        ReQuestExtension.MODULE_ID,
        "ReQuest",
        List.of(),
        Set.of(ReQuestExtension.CHANNEL_ID)
    );

    private final ReQuestService service;
    private final Set<Session> sessions = ConcurrentHashMap.newKeySet();
    private ModuleContext context;
    private FlowGraphValidationRegistry validationRegistry;

    public ReQuestModule(ReQuestService service) {
        this.service = service;
    }

    @Override
    public ModuleMetadata getMetadata() {
        return METADATA;
    }

    @Override
    public void initialize(ModuleContext context) {
        this.context = context;
        validationRegistry = context.getRequiredService(FlowGraphValidationRegistry.class);
        validationRegistry.register(ReQuestExtension.PLUGIN_ID, "request:quest_references", this::validateGraph);
    }

    @Override
    public void stop(ModuleContext context) {
        if (validationRegistry != null) {
            validationRegistry.unregister(ReQuestExtension.PLUGIN_ID, "request:quest_references");
            validationRegistry = null;
        }
        sessions.clear();
    }

    List<FlowGraphDiagnostic> validateGraph(FlowGraph graph) {
        if (graph == null) {
            return List.of();
        }
        List<FlowGraphDiagnostic> diagnostics = new ArrayList<>();
        for (var entry : graph.getNodes().entrySet()) {
            FlowNode node = entry.getValue();
            if (node == null || node.getType() == null || !node.getType().startsWith(ReQuestExtension.PLUGIN_ID + ":") || node.getInputValues() == null) {
                continue;
            }
            Object value = node.getInputValues().get("quest");
            if (!(value instanceof String questId) || questId.isBlank() || graph.getConnectionsToTarget(entry.getKey()).stream().anyMatch(connection -> "quest".equals(connection.getTargetPin()))) {
                continue;
            }
            if (service.quest(questId) == null) {
                diagnostics.add(new FlowGraphDiagnostic(FlowGraphDiagnostic.Severity.ERROR, "REQUEST_QUEST_UNRESOLVED", graph.getId(), entry.getKey(), "quest",
                    "Quest does not exist: " + questId, "Create the quest or select an existing quest ID"));
            }
        }
        return List.copyOf(diagnostics);
    }

    @Override
    public void onSubscribe(Session session, SubscribeRequest req) {
        sessions.add(session);
        Player player = player(session);
        service.publish(player);
        send(session, service.snapshotJson(player));
    }

    @Override
    public void onUnsubscribe(Session session, UnsubscribeRequest req) {
        sessions.remove(session);
    }

    @Override
    public void cleanup(Session session) {
        sessions.remove(session);
    }

    @Override
    public void onData(Session session, DataMessage req) {
        String text = new String(req.getPayload(), StandardCharsets.UTF_8).trim();
        Player player = player(session);
        if (text.equalsIgnoreCase("snapshot") || text.equalsIgnoreCase("list")) {
            send(session, service.snapshotJson(player));
            return;
        }
        String[] parts = text.split(":", 5);
        String command = parts.length > 0 ? parts[0].trim().toLowerCase() : "";
        String questId = parts.length > 1 ? parts[1].trim() : "";
        switch (command) {
            case "start" -> send(session, service.resultJson(service.start(player, questId), player));
            case "progress" -> send(session, service.resultJson(service.progress(player, questId, amount(parts)), player));
            case "set_progress" -> send(session, service.resultJson(service.setProgress(player, questId, amount(parts)), player));
            case "complete" -> send(session, service.resultJson(service.complete(player, questId), player));
            case "quit" -> send(session, service.resultJson(service.quit(player, questId), player));
            case "reset" -> send(session, service.resultJson(service.reset(player, questId), player));
            case "delete" -> send(session, service.resultJson(service.delete(player, questId), player));
            case "status" -> send(session, service.resultJson(QuestResult.unchanged("status", "Quest Status", service.quest(player, questId), service.state(player, questId)), player));
            case "create" -> {
                QuestResult result = service.create(player, createQuest(parts));
                send(session, service.resultJson(result, player));
            }
            default -> send(session, "{\"type\":\"error\",\"reason\":\"Unknown Command\"}");
        }
    }

    private Quest createQuest(String[] parts) {
        String id = parts.length > 1 ? parts[1] : "";
        String title = parts.length > 2 ? parts[2] : id;
        int target = parts.length > 3 ? parseInt(parts[3], 1) : 1;
        String reward = parts.length > 4 ? parts[4] : "";
        return new Quest(id, title, "", reward, target, "", "", "", 3, 0, 1, 50);
    }

    private int amount(String[] parts) {
        return parts.length > 2 ? parseInt(parts[2], 1) : 1;
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private Player player(Session session) {
        if (session == null || session.getClientId() == null) {
            return null;
        }
        try {
            return Bukkit.getPlayer(UUID.fromString(session.getClientId()));
        } catch (IllegalArgumentException ignored) {
            return Bukkit.getPlayerExact(session.getClientId());
        }
    }

    private void send(Session session, String json) {
        if (context == null || session == null || session.getConnection() == null || !session.getConnection().isOpen() || json == null) {
            return;
        }
        ChannelMuxer.Channel channel = context.getChannelMuxer().getChannel(ReQuestExtension.CHANNEL_ID);
        if (channel == null) {
            return;
        }
        DataMessage message = new DataMessage();
        message.setPayload(json.getBytes(StandardCharsets.UTF_8));
        context.getCodec().sendMessage(session.getConnection().getFrameSender(), message, channel.getNumericId(), false);
    }
}
