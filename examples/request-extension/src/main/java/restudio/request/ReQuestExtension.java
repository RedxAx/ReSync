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
