package restudio.request;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import restudio.flow.data.FlowDataType;
import restudio.resync.api.OptionCatalogItem;
import restudio.resync.api.OptionCatalogProvider;
import restudio.resync.api.ReSyncExtension;
import restudio.resync.api.ReSyncExtensionContext;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.sync.FlowCategoryMetadata;
import restudio.resync.flow.sync.FlowTypeMetadata;
import restudio.resync.player.PlayerTrackingService;

import java.util.List;
import java.util.Map;

public class ReQuestExtension implements ReSyncExtension {
    static final String PLUGIN_ID = "request";
    static final String CHANNEL_ID = "request:quests";
    static final String MODULE_ID = "request:quests";
    static final String CATEGORY_ID = "request:quests";
    static final String TYPE_ID = "request:quest";
    static final String QUEST_OPTION_SOURCE_ID = "request:quest_ids";
    static final String EVENT_OPTION_SOURCE_ID = "request:event_ids";
    static final String HANDLER_ID = "request:handler";
    static final int COLOR = 0xFF46B48A;
    static final FlowDataType QUEST_TYPE = new FlowDataType(TYPE_ID, FlowDataType.STRING, String.class, null, COLOR);

    private ReQuestService service;

    @Override
    public String getPluginId() {
        return PLUGIN_ID;
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "ReQuest extension example";
    }

    @Override
    public void initialize(ReSyncExtensionContext context) {
        service = new ReQuestService();
        service.storage(context.storage().directory());
        service.tracking(context.service(PlayerTrackingService.class));
        context.flow().registerCategory(new FlowCategoryMetadata(CATEGORY_ID, "ReQuest", COLOR, 1650));
        context.flow().registerType(QUEST_TYPE, new FlowTypeMetadata(TYPE_ID, "Quest", COLOR, "string", true, true, false));
        context.optionCatalogs().register(new QuestCatalog(service));
        context.optionCatalogs().register(new EventCatalog(service));
        context.flow().registerResource(new ReQuestResourceAdapter(service));
        context.flow().registerHandler(HANDLER_ID, new ReQuestHandler(service));
        context.flow().registerNodes("request/nodes");
        context.modules().register(new ReQuestModule(service));
        Bukkit.getPluginManager().registerEvents(new JoinListener(service), context.owner());
        Bukkit.getPluginManager().registerEvents(new ReQuestCommandListener(service), context.owner());
        Bukkit.getOnlinePlayers().forEach(service::publish);
    }

    @Override
    public void stop() {
        if (service != null) {
            service.save();
        }
    }

    private List<NodeDefinition> nodes() {
        return List.of(
            infoNode(),
            listNode(),
            profileNode(),
            createNode(),
            questActionNode(),
            questProgressNode(),
            actionNode("request:delete_quest", "Delete Quest", "delete"),
            checkNode(),
            actionNode("request:start_quest", "Start Quest", "start"),
            progressNode(),
            setProgressNode(),
            actionNode("request:complete_quest", "Complete Quest", "complete"),
            actionNode("request:quit_quest", "Quit Quest", "quit"),
            actionNode("request:reset_quest", "Reset Quest", "reset"),
            xpNode(),
            eventListenNode()
        );
    }

    private NodeDefinition infoNode() {
        return base("request:quest_info", "Quest Info", "info")
            .input(playerPin())
            .input(questPin())
            .output("title", NodeDefinition.PinType.DATA, FlowDataType.STRING)
            .output("description", NodeDefinition.PinType.DATA, FlowDataType.STRING)
            .output("reward", NodeDefinition.PinType.DATA, FlowDataType.STRING)
            .output("target", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("progress", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("progress_percent", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("required_level", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("xp_reward", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("scope", NodeDefinition.PinType.DATA, FlowDataType.STRING)
            .output("owner", NodeDefinition.PinType.DATA, FlowDataType.STRING)
            .output("level", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("xp", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("xp_to_next_level", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("active", NodeDefinition.PinType.DATA, FlowDataType.BOOLEAN)
            .output("completed", NodeDefinition.PinType.DATA, FlowDataType.BOOLEAN)
            .description("Reads quest definition and player state")
            .tags(List.of("request", "quest"))
            .recommended(true)
            .build();
    }

    private NodeDefinition listNode() {
        return base("request:quest_list", "Quest List", "list")
            .input(playerPin())
            .input(selectPin("scope", "visible", List.of("visible", "global", "player", "permission", "owned", "all")))
            .input(selectPin("status", "all", List.of("all", "available", "active", "completed", "quit")))
            .output("quests", NodeDefinition.PinType.DATA, FlowDataType.LIST)
            .output("quest_ids", NodeDefinition.PinType.DATA, FlowDataType.LIST)
            .output("count", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .description("Lists quests for the current player")
            .tags(List.of("request", "quest", "list"))
            .family("request:quest")
            .recommended(true)
            .build();
    }

    private NodeDefinition profileNode() {
        return base("request:quest_profile", "Quest Profile", "profile")
            .input(playerPin())
            .output("profile", NodeDefinition.PinType.DATA, FlowDataType.MAP)
            .output("level", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("xp", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("xp_to_next_level", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("active_count", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("completed_count", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("quit_count", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("available_count", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("quest_count", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .description("Reads the current player's quest profile")
            .tags(List.of("request", "quest", "profile"))
            .family("request:profile")
            .recommended(true)
            .build();
    }

    private NodeDefinition createNode() {
        return base("request:create_quest", "Create Quest", "create")
            .input("flow", NodeDefinition.PinType.FLOW, FlowDataType.EXECUTION)
            .input(playerPin())
            .input(textPin("id", "new_quest").build())
            .input(textPin("title", "New Quest").build())
            .input(textPin("description", "").build())
            .input(textPin("reward", "").build())
            .input(numberPin("target", 1).build())
            .input(selectPin("scope", "global", List.of("global", "player", "permission")))
            .input(togglePin("restrictions", false).build())
            .input(togglePin("progression", false).build())
            .input(textPin("permission", "").visibleWhen("scope", "permission").build())
            .input(textPin("world", "").visibleWhen("restrictions", "true").build())
            .input(new NodeDefinition.PinBuilder("required_quest", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, QUEST_TYPE)
                .widget(NodeDefinition.WidgetType.SEARCHABLE_LIST)
                .optionsSource(QUEST_OPTION_SOURCE_ID)
                .defaultValue("")
                .visibleWhen("restrictions", "true")
                .build())
            .input(numberPin("max_active", 3).visibleWhen("restrictions", "true").build())
            .input(numberPin("cooldown_seconds", 0).visibleWhen("restrictions", "true").build())
            .input(numberPin("required_level", 1).visibleWhen("progression", "true").build())
            .input(numberPin("xp_reward", 50).visibleWhen("progression", "true").build())
            .output("flow", NodeDefinition.PinType.FLOW, FlowDataType.EXECUTION)
            .output("success", NodeDefinition.PinType.DATA, FlowDataType.BOOLEAN)
            .output("changed", NodeDefinition.PinType.DATA, FlowDataType.BOOLEAN)
            .output("status", NodeDefinition.PinType.DATA, FlowDataType.STRING)
            .output("reason", NodeDefinition.PinType.DATA, FlowDataType.STRING)
            .output("title", NodeDefinition.PinType.DATA, FlowDataType.STRING)
            .output("required_level", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("xp_reward", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("scope", NodeDefinition.PinType.DATA, FlowDataType.STRING)
            .output("owner", NodeDefinition.PinType.DATA, FlowDataType.STRING)
            .description("Creates or replaces a quest")
            .tags(List.of("request", "quest"))
            .build();
    }

    private NodeDefinition checkNode() {
        return base("request:can_start_quest", "Can Start Quest", "can_start")
            .input(playerPin())
            .input(questPin())
            .output("success", NodeDefinition.PinType.DATA, FlowDataType.BOOLEAN)
            .output("status", NodeDefinition.PinType.DATA, FlowDataType.STRING)
            .output("reason", NodeDefinition.PinType.DATA, FlowDataType.STRING)
            .output("title", NodeDefinition.PinType.DATA, FlowDataType.STRING)
            .output("active", NodeDefinition.PinType.DATA, FlowDataType.BOOLEAN)
            .output("completed", NodeDefinition.PinType.DATA, FlowDataType.BOOLEAN)
            .description("Checks quest start restrictions")
            .tags(List.of("request", "quest", "check"))
            .family("request:quest")
            .recommended(true)
            .build();
    }

    private NodeDefinition questActionNode() {
        return base("request:quest_action", "Quest Action", "action")
            .input("flow", NodeDefinition.PinType.FLOW, FlowDataType.EXECUTION)
            .input(playerPin())
            .input(questPin())
            .input(selectPin("action", "start", List.of("start", "complete", "quit", "reset", "delete", "can_start")))
            .input(togglePin("details", false).build())
            .output("flow", NodeDefinition.PinType.FLOW, FlowDataType.EXECUTION)
            .output("success", NodeDefinition.PinType.DATA, FlowDataType.BOOLEAN)
            .output("changed", NodeDefinition.PinType.DATA, FlowDataType.BOOLEAN)
            .output("status", NodeDefinition.PinType.DATA, FlowDataType.STRING)
            .output("reason", NodeDefinition.PinType.DATA, FlowDataType.STRING)
            .output("title", NodeDefinition.PinType.DATA, FlowDataType.STRING)
            .output("progress", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("active", NodeDefinition.PinType.DATA, FlowDataType.BOOLEAN)
            .output("completed", NodeDefinition.PinType.DATA, FlowDataType.BOOLEAN)
            .output(outputPin("reward", FlowDataType.STRING).visibleWhen("details", "true").build())
            .output(outputPin("target", FlowDataType.NUMBER).visibleWhen("details", "true").build())
            .output(outputPin("progress_percent", FlowDataType.NUMBER).visibleWhen("details", "true").build())
            .output(outputPin("level", FlowDataType.NUMBER).visibleWhen("details", "true").build())
            .output(outputPin("xp", FlowDataType.NUMBER).visibleWhen("details", "true").build())
            .output(outputPin("xp_to_next_level", FlowDataType.NUMBER).visibleWhen("details", "true").build())
            .description("Runs a quest action")
            .tags(List.of("request", "quest", "action"))
            .family("request:quest")
            .recommended(true)
            .build();
    }

    private NodeDefinition questProgressNode() {
        return base("request:quest_progress", "Quest Progress", "progress_family")
            .input("flow", NodeDefinition.PinType.FLOW, FlowDataType.EXECUTION)
            .input(playerPin())
            .input(questPin())
            .input(selectPin("mode", "add", List.of("add", "set")))
            .input(numberPin("amount", 1).visibleWhen("mode", "add").build())
            .input(numberPin("progress", 0).visibleWhen("mode", "set").build())
            .input(togglePin("details", false).build())
            .output("flow", NodeDefinition.PinType.FLOW, FlowDataType.EXECUTION)
            .output("success", NodeDefinition.PinType.DATA, FlowDataType.BOOLEAN)
            .output("changed", NodeDefinition.PinType.DATA, FlowDataType.BOOLEAN)
            .output("status", NodeDefinition.PinType.DATA, FlowDataType.STRING)
            .output("reason", NodeDefinition.PinType.DATA, FlowDataType.STRING)
            .output("progress", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("active", NodeDefinition.PinType.DATA, FlowDataType.BOOLEAN)
            .output("completed", NodeDefinition.PinType.DATA, FlowDataType.BOOLEAN)
            .output(outputPin("previous_progress", FlowDataType.NUMBER).visibleWhen("details", "true").build())
            .output(outputPin("progress_added", FlowDataType.NUMBER).visibleWhen("details", "true").build())
            .output(outputPin("progress_percent", FlowDataType.NUMBER).visibleWhen("details", "true").build())
            .output(outputPin("target", FlowDataType.NUMBER).visibleWhen("details", "true").build())
            .description("Adds or sets quest progress")
            .tags(List.of("request", "quest", "progress"))
            .family("request:quest")
            .recommended(true)
            .build();
    }

    private NodeDefinition actionNode(String id, String name, String operation) {
        return base(id, name, operation)
            .input("flow", NodeDefinition.PinType.FLOW, FlowDataType.EXECUTION)
            .input(playerPin())
            .input(questPin())
            .output("flow", NodeDefinition.PinType.FLOW, FlowDataType.EXECUTION)
            .output("success", NodeDefinition.PinType.DATA, FlowDataType.BOOLEAN)
            .output("changed", NodeDefinition.PinType.DATA, FlowDataType.BOOLEAN)
            .output("status", NodeDefinition.PinType.DATA, FlowDataType.STRING)
            .output("reason", NodeDefinition.PinType.DATA, FlowDataType.STRING)
            .output("title", NodeDefinition.PinType.DATA, FlowDataType.STRING)
            .output("reward", NodeDefinition.PinType.DATA, FlowDataType.STRING)
            .output("progress", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("target", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("progress_percent", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("required_level", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("xp_reward", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("scope", NodeDefinition.PinType.DATA, FlowDataType.STRING)
            .output("owner", NodeDefinition.PinType.DATA, FlowDataType.STRING)
            .output("level", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("xp", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("xp_to_next_level", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("active", NodeDefinition.PinType.DATA, FlowDataType.BOOLEAN)
            .output("completed", NodeDefinition.PinType.DATA, FlowDataType.BOOLEAN)
            .description(name)
            .tags(List.of("request", "quest"))
            .recommended("start".equals(operation) || "complete".equals(operation))
            .build();
    }

    private NodeDefinition progressNode() {
        return base("request:add_progress", "Add Quest Progress", "progress")
            .input("flow", NodeDefinition.PinType.FLOW, FlowDataType.EXECUTION)
            .input(playerPin())
            .input(questPin())
            .input(numberPin("amount", 1).build())
            .output("flow", NodeDefinition.PinType.FLOW, FlowDataType.EXECUTION)
            .output("success", NodeDefinition.PinType.DATA, FlowDataType.BOOLEAN)
            .output("changed", NodeDefinition.PinType.DATA, FlowDataType.BOOLEAN)
            .output("status", NodeDefinition.PinType.DATA, FlowDataType.STRING)
            .output("reason", NodeDefinition.PinType.DATA, FlowDataType.STRING)
            .output("previous_progress", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("progress_added", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("progress", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("progress_percent", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("target", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("required_level", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("xp_reward", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("level", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("xp", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("xp_to_next_level", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("active", NodeDefinition.PinType.DATA, FlowDataType.BOOLEAN)
            .output("completed", NodeDefinition.PinType.DATA, FlowDataType.BOOLEAN)
            .description("Adds progress and completes the quest at the target")
            .tags(List.of("request", "quest"))
            .recommended(true)
            .build();
    }

    private NodeDefinition setProgressNode() {
        return base("request:set_progress", "Set Quest Progress", "set_progress")
            .input("flow", NodeDefinition.PinType.FLOW, FlowDataType.EXECUTION)
            .input(playerPin())
            .input(questPin())
            .input(numberPin("progress", 0).build())
            .output("flow", NodeDefinition.PinType.FLOW, FlowDataType.EXECUTION)
            .output("success", NodeDefinition.PinType.DATA, FlowDataType.BOOLEAN)
            .output("changed", NodeDefinition.PinType.DATA, FlowDataType.BOOLEAN)
            .output("status", NodeDefinition.PinType.DATA, FlowDataType.STRING)
            .output("reason", NodeDefinition.PinType.DATA, FlowDataType.STRING)
            .output("previous_progress", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("progress_added", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("progress", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("progress_percent", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("target", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("active", NodeDefinition.PinType.DATA, FlowDataType.BOOLEAN)
            .output("completed", NodeDefinition.PinType.DATA, FlowDataType.BOOLEAN)
            .description("Sets progress and completes at the target")
            .tags(List.of("request", "quest", "progress"))
            .family("request:quest")
            .recommended(true)
            .build();
    }

    private NodeDefinition xpNode() {
        return base("request:add_quest_xp", "Add Quest XP", "xp")
            .input("flow", NodeDefinition.PinType.FLOW, FlowDataType.EXECUTION)
            .input(playerPin())
            .input(numberPin("amount", 50).build())
            .output("flow", NodeDefinition.PinType.FLOW, FlowDataType.EXECUTION)
            .output("success", NodeDefinition.PinType.DATA, FlowDataType.BOOLEAN)
            .output("changed", NodeDefinition.PinType.DATA, FlowDataType.BOOLEAN)
            .output("status", NodeDefinition.PinType.DATA, FlowDataType.STRING)
            .output("reason", NodeDefinition.PinType.DATA, FlowDataType.STRING)
            .output("level", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("xp", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("xp_to_next_level", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .description("Adds quest profile XP")
            .tags(List.of("request", "quest", "profile"))
            .family("request:profile")
            .build();
    }

    private NodeDefinition eventListenNode() {
        return base("request:quest_event_listen", "Quest Event Listen", "listen")
            .input("flow", NodeDefinition.PinType.FLOW, FlowDataType.EXECUTION)
            .input(new NodeDefinition.PinBuilder("event", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, FlowDataType.STRING)
                .widget(NodeDefinition.WidgetType.SEARCHABLE_LIST)
                .optionsSource(EVENT_OPTION_SOURCE_ID)
                .defaultValue("request:completed")
                .build())
            .output("next", NodeDefinition.PinType.FLOW, FlowDataType.EXECUTION)
            .output("listening", NodeDefinition.PinType.DATA, FlowDataType.BOOLEAN)
            .output("event_data", NodeDefinition.PinType.DATA, FlowDataType.MAP)
            .output("triggered", NodeDefinition.PinType.DATA, FlowDataType.BOOLEAN)
            .description("Continues when a ReQuest event is emitted")
            .tags(List.of("request", "event"))
            .recommended(true)
            .build();
    }

    private NodeDefinition.Builder base(String id, String name, String operation) {
        return new NodeDefinition.Builder(id, name, NodeDefinition.NodeCategory.UTILITY)
            .color(COLOR)
            .priority(10)
            .handler(HANDLER_ID)
            .handlerConfig(Map.of("operation", operation));
    }

    private NodeDefinition.PinDefinition questPin() {
        return new NodeDefinition.PinBuilder("quest", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, QUEST_TYPE)
            .widget(NodeDefinition.WidgetType.SEARCHABLE_LIST)
            .optionsSource(QUEST_OPTION_SOURCE_ID)
            .defaultValue("gather_logs")
            .build();
    }

    private NodeDefinition.PinDefinition playerPin() {
        return new NodeDefinition.PinBuilder("player", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, FlowDataType.PLAYER)
            .build();
    }

    private NodeDefinition.PinBuilder textPin(String name, String value) {
        return new NodeDefinition.PinBuilder(name, NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, FlowDataType.STRING)
            .defaultValue(value);
    }

    private NodeDefinition.PinDefinition selectPin(String name, String value, List<String> options) {
        return new NodeDefinition.PinBuilder(name, NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, FlowDataType.STRING)
            .widget(NodeDefinition.WidgetType.DROPDOWN)
            .options(options)
            .defaultValue(value)
            .build();
    }

    private NodeDefinition.PinBuilder togglePin(String name, boolean value) {
        return new NodeDefinition.PinBuilder(name, NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, FlowDataType.BOOLEAN)
            .widget(NodeDefinition.WidgetType.TOGGLE)
            .defaultValue(String.valueOf(value));
    }

    private NodeDefinition.PinBuilder numberPin(String name, int value) {
        return new NodeDefinition.PinBuilder(name, NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, FlowDataType.NUMBER)
            .defaultValue(String.valueOf(value));
    }

    private NodeDefinition.PinBuilder outputPin(String name, FlowDataType type) {
        return new NodeDefinition.PinBuilder(name, NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.OUTPUT, type);
    }

    private static class QuestCatalog implements OptionCatalogProvider {
        private final ReQuestService service;

        private QuestCatalog(ReQuestService service) {
            this.service = service;
        }

        @Override
        public String sourceId() {
            return QUEST_OPTION_SOURCE_ID;
        }

        @Override
        public String revision() {
            return "request:quests:" + service.revision();
        }

        @Override
        public List<String> values() {
            return service.questIds();
        }

        @Override
        public List<OptionCatalogItem> items() {
            return service.questIds().stream()
                .map(id -> {
                    Quest quest = service.quest(id);
                    return quest != null
                        ? new OptionCatalogItem(quest.id(), quest.title(), quest.description(), "", quest.scope(), Map.of("reward", quest.reward(), "target", quest.target()))
                        : new OptionCatalogItem(id);
                })
                .toList();
        }
    }

    private static class EventCatalog implements OptionCatalogProvider {
        private EventCatalog(ReQuestService service) {
        }

        @Override
        public String sourceId() {
            return EVENT_OPTION_SOURCE_ID;
        }

        @Override
        public String revision() {
            return "request:events:1";
        }

        @Override
        public List<String> values() {
            return List.of("request:created", "request:started", "request:progress", "request:completed", "request:quit", "request:reset", "request:deleted", "request:xp");
        }

        @Override
        public List<OptionCatalogItem> items() {
            return List.of(
                new OptionCatalogItem("request:created", "Quest Created", "A quest definition was created", "", "definition", Map.of()),
                new OptionCatalogItem("request:started", "Quest Started", "A player started a quest", "", "player", Map.of()),
                new OptionCatalogItem("request:progress", "Quest Progress", "A player's quest progress changed", "", "player", Map.of()),
                new OptionCatalogItem("request:completed", "Quest Completed", "A player completed a quest", "", "player", Map.of()),
                new OptionCatalogItem("request:quit", "Quest Quit", "A player quit a quest", "", "player", Map.of()),
                new OptionCatalogItem("request:reset", "Quest Reset", "A player's quest state was reset", "", "player", Map.of()),
                new OptionCatalogItem("request:deleted", "Quest Deleted", "A quest definition was deleted", "", "definition", Map.of()),
                new OptionCatalogItem("request:xp", "Quest XP", "A player's quest XP changed", "", "player", Map.of())
            );
        }
    }

    private static class JoinListener implements Listener {
        private final ReQuestService service;

        private JoinListener(ReQuestService service) {
            this.service = service;
        }

        @EventHandler
        public void onJoin(PlayerJoinEvent event) {
            service.publish(event.getPlayer());
        }
    }
}
