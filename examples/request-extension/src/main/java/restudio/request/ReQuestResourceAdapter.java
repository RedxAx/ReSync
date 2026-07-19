package restudio.request;

import com.google.gson.Gson;
import restudio.resync.modules.flow.FlowResourceAdapter;
import restudio.resync.resources.ReSyncManagedResource;

import java.util.List;
import java.util.Set;

final class ReQuestResourceAdapter implements FlowResourceAdapter<Quest> {
    private static final Gson GSON = new Gson();
    private static final ReSyncManagedResource DESCRIPTOR = new ReSyncManagedResource(
        ReQuestExtension.TYPE_ID,
        "Quest",
        "extensions/request/quests",
        null,
        true
    );
    private final ReQuestService service;

    ReQuestResourceAdapter(ReQuestService service) {
        this.service = service;
    }

    @Override
    public ReSyncManagedResource descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public Quest get(String id) {
        return service.quest(id);
    }

    @Override
    public List<String> listIds() {
        return service.questIds();
    }

    @Override
    public Quest deserialize(String json) {
        return GSON.fromJson(json, Quest.class);
    }

    @Override
    public String id(Quest value) {
        return value != null ? value.id() : "";
    }

    @Override
    public void validate(Quest value) {
        if (value == null || value.normalize().id().isBlank()) {
            throw new IllegalArgumentException("Quest ID is required");
        }
        if (!"global".equals(value.normalize().scope())) {
            throw new IllegalArgumentException("Generic quest resources must use global scope");
        }
    }

    @Override
    public void save(Quest value) {
        QuestResult result = service.create(value);
        if (!result.allowed()) {
            throw new IllegalArgumentException(result.reason());
        }
    }

    @Override
    public void delete(String id) {
        QuestResult result = service.delete(null, id);
        if (!result.allowed()) {
            throw new IllegalArgumentException(result.reason());
        }
    }

    @Override
    public Quest duplicate(Quest value, String targetId) {
        return new Quest(targetId, value.title(), value.description(), value.reward(), value.target(), value.permission(), value.world(),
            value.requiredQuest(), value.maxActive(), value.cooldownSeconds(), value.requiredLevel(), value.xpReward(), value.scope(), value.owner());
    }

    @Override
    public Set<String> supportedOperations() {
        return Set.of("discover", "query", "get", "create", "validate", "save", "update", "duplicate", "delete");
    }

    @Override
    public String catalogSource() {
        return ReQuestExtension.QUEST_OPTION_SOURCE_ID;
    }

    @Override
    public String authoritativeService() {
        return ReQuestService.class.getName();
    }

    @Override
    public boolean changeEvents() {
        return true;
    }

    @Override
    public boolean activeRefresh() {
        return true;
    }
}
