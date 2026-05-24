package restudio.request;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import restudio.resync.flow.CustomEventManager;
import restudio.resync.player.PlayerFacetMetadata;
import restudio.resync.player.PlayerTrackingService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class ReQuestService {
    private final Map<String, Quest> quests = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, QuestState>> playerStates = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerQuestProfile> profiles = new ConcurrentHashMap<>();
    private final AtomicLong revision = new AtomicLong(1);
    private PlayerTrackingService trackingService;
    private Path storageFile;

    public ReQuestService() {
        put(new Quest("gather_logs", "Gather Logs", "Collect logs for the spawn builder", "Builder Crate", 32, "", "", "", 3, 30, 1, 70));
        put(new Quest("light_caves", "Light Caves", "Place torches in nearby caves", "Miner Token", 16, "", "", "gather_logs", 3, 60, 2, 90));
        put(new Quest("map_spawn", "Map Spawn", "Visit and map the spawn district", "Scout Badge", 1, "", "world", "", 3, 0, 1, 40));
        put(new Quest("feed_team", "Feed Team", "Bring food for online players", "Cook Token", 8, "request.feed", "", "", 3, 20, 1, 60));
    }

    public QuestResult create(Quest quest) {
        return create(null, quest);
    }

    public QuestResult create(Player player, Quest quest) {
        Quest normalized = quest != null ? quest.normalize() : null;
        if (normalized == null || normalized.id().isBlank()) {
            return QuestResult.blocked("missing", "Missing Quest Id", null, null);
        }
        if ("player".equals(normalized.scope())) {
            if (player == null) {
                return QuestResult.blocked("blocked", "Missing Player", normalized, null);
            }
            normalized = normalized.withOwner(player.getUniqueId().toString());
        }
        if ("permission".equals(normalized.scope()) && normalized.permission().isBlank()) {
            return QuestResult.blocked("blocked", "Missing Permission", normalized, null);
        }
        quests.put(definitionKey(normalized), normalized);
        revision.incrementAndGet();
        Map<String, Object> eventData = definitionEventData(player, normalized, "created", "Quest Created");
        emit("created", eventData);
        record(player, "created", eventData);
        Bukkit.getOnlinePlayers().forEach(this::publishFacet);
        save();
        return QuestResult.changed("created", "Quest Created", normalized, null, eventData);
    }

    public void tracking(PlayerTrackingService trackingService) {
        this.trackingService = trackingService;
    }

    public void storage(Path directory) {
        if (directory == null) {
            return;
        }
        try {
            Files.createDirectories(directory);
            storageFile = directory.resolve("quests.yml");
            load();
            save();
        } catch (IOException exception) {
            Bukkit.getLogger().warning("[ReQuest] Failed to initialize storage: " + exception.getMessage());
        }
    }

    public Quest quest(String questId) {
        return quest(null, questId);
    }

    public Quest quest(Player player, String questId) {
        return findQuest(player, questId, "id");
    }

    public Quest findQuest(Player player, String query, String mode) {
        String id = clean(query);
        if (id.isBlank()) {
            return null;
        }
        String normalizedMode = clean(mode);
        if ("title".equals(normalizedMode)) {
            return findByTitle(player, id, false);
        }
        if ("contains".equals(normalizedMode)) {
            return findByContains(player, id);
        }
        if ("id_title".equals(normalizedMode) || "title_id".equals(normalizedMode) || "any".equals(normalizedMode)) {
            Quest byId = findById(player, id);
            return byId != null ? byId : findByTitle(player, id, false);
        }
        return findById(player, id);
    }

    private Quest findById(Player player, String id) {
        if (player != null) {
            Quest playerQuest = quests.get("player:" + player.getUniqueId() + ":" + id);
            if (playerQuest != null) {
                return playerQuest;
            }
        }
        Quest global = quests.get("global:" + id);
        if (global != null) {
            return global;
        }
        return quests.values().stream()
            .filter(quest -> quest.id().equalsIgnoreCase(id))
            .filter(quest -> player == null || visibleTo(player, quest))
            .findFirst()
            .orElse(null);
    }

    private Quest findByTitle(Player player, String query, boolean contains) {
        return quests.values().stream()
            .filter(quest -> player == null || visibleTo(player, quest))
            .filter(quest -> {
                String title = clean(quest.title());
                return contains ? title.contains(query) : title.equals(query);
            })
            .sorted(Comparator.comparing(Quest::id, String.CASE_INSENSITIVE_ORDER))
            .findFirst()
            .orElse(null);
    }

    private Quest findByContains(Player player, String query) {
        Quest byId = quests.values().stream()
            .filter(quest -> player == null || visibleTo(player, quest))
            .filter(quest -> clean(quest.id()).contains(query))
            .sorted(Comparator.comparing(Quest::id, String.CASE_INSENSITIVE_ORDER))
            .findFirst()
            .orElse(null);
        return byId != null ? byId : findByTitle(player, query, true);
    }

    public List<String> questIds() {
        return quests.values().stream().map(Quest::id).distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public List<String> questIds(Player player) {
        return quests.values().stream()
            .filter(quest -> visibleTo(player, quest))
            .map(Quest::id)
            .distinct()
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
    }

    public List<String> activeQuestIds(Player player) {
        if (player == null) {
            return List.of();
        }
        return states(player).values().stream()
            .filter(QuestState::active)
            .map(QuestState::questId)
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
    }

    public List<String> completedQuestIds(Player player) {
        if (player == null) {
            return List.of();
        }
        return states(player).values().stream()
            .filter(QuestState::completed)
            .map(QuestState::questId)
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
    }

    public QuestState state(Player player, String questId) {
        if (player == null) {
            return null;
        }
        Quest quest = quest(player, questId);
        return quest != null ? state(player, quest) : null;
    }

    public QuestState state(Player player, Quest quest) {
        if (player == null || quest == null) {
            return null;
        }
        return states(player).get(stateKey(quest));
    }

    public QuestResult start(Player player, String questId) {
        Quest quest = quest(player, questId);
        if (player == null) {
            return QuestResult.blocked("blocked", "Missing Player", quest, null);
        }
        if (quest == null) {
            return QuestResult.blocked("missing", "Quest Missing", null, null);
        }
        QuestResult restriction = checkStartRestrictions(player, quest);
        if (!restriction.allowed()) {
            return restriction;
        }
        Map<String, QuestState> states = states(player);
        QuestState existing = states.get(stateKey(quest));
        if (existing != null && existing.active()) {
            return QuestResult.unchanged("active", "Quest Already Active", quest, existing);
        }
        if (existing != null && existing.completed()) {
            return QuestResult.unchanged("completed", "Quest Already Complete", quest, existing);
        }
        QuestState state = new QuestState(quest.id());
        states.put(stateKey(quest), state);
        revision.incrementAndGet();
        Map<String, Object> eventData = eventData(player, quest, state, "started", "Quest Started");
        emit("started", eventData);
        record(player, "started", eventData);
        publishFacet(player);
        save();
        return QuestResult.changed("started", "Quest Started", quest, state, eventData);
    }

    public QuestResult progress(Player player, String questId, int amount) {
        QuestResult prepared = ensureActive(player, questId);
        if (!prepared.allowed()) {
            return prepared;
        }
        Quest quest = prepared.quest();
        QuestState state = prepared.state();
        int previousProgress = state.progress();
        int added = Math.max(1, amount);
        state.progress(Math.min(quest.target(), previousProgress + added));
        revision.incrementAndGet();
        Map<String, Object> eventData = eventData(player, quest, state, "progress", "Quest Progress");
        eventData.put("previous_progress", previousProgress);
        eventData.put("progress_added", state.progress() - previousProgress);
        eventData.put("progress_percent", progressPercent(state, quest));
        emit("progress", eventData);
        record(player, "progress", eventData);
        publishFacet(player);
        save();
        if (state.progress() >= quest.target()) {
            return completePrepared(player, quest, state);
        }
        return QuestResult.changed("progress", "Quest Progress", quest, state, eventData);
    }

    public QuestResult complete(Player player, String questId) {
        QuestResult prepared = ensureActive(player, questId);
        if (!prepared.allowed()) {
            return prepared;
        }
        return completePrepared(player, prepared.quest(), prepared.state());
    }

    public QuestResult setProgress(Player player, String questId, int amount) {
        QuestResult prepared = ensureActive(player, questId);
        if (!prepared.allowed()) {
            return prepared;
        }
        Quest quest = prepared.quest();
        QuestState state = prepared.state();
        int previousProgress = state.progress();
        state.progress(Math.min(quest.target(), Math.max(0, amount)));
        revision.incrementAndGet();
        Map<String, Object> eventData = eventData(player, quest, state, "progress", "Quest Progress");
        eventData.put("previous_progress", previousProgress);
        eventData.put("progress_added", state.progress() - previousProgress);
        eventData.put("progress_percent", progressPercent(state, quest));
        emit("progress", eventData);
        record(player, "progress", eventData);
        publishFacet(player);
        save();
        if (state.progress() >= quest.target()) {
            return completePrepared(player, quest, state);
        }
        return QuestResult.changed("progress", "Quest Progress", quest, state, eventData);
    }

    public QuestResult quit(Player player, String questId) {
        QuestResult prepared = ensureActive(player, questId);
        if (!prepared.allowed()) {
            return prepared;
        }
        Quest quest = prepared.quest();
        QuestState state = prepared.state();
        state.quit();
        revision.incrementAndGet();
        Map<String, Object> eventData = eventData(player, quest, state, "quit", "Quest Quit");
        emit("quit", eventData);
        record(player, "quit", eventData);
        publishFacet(player);
        save();
        return QuestResult.changed("quit", "Quest Quit", quest, state, eventData);
    }

    public QuestResult reset(Player player, String questId) {
        Quest quest = quest(player, questId);
        if (player == null) {
            return QuestResult.blocked("blocked", "Missing Player", quest, null);
        }
        if (quest == null) {
            return QuestResult.blocked("missing", "Quest Missing", null, null);
        }
        QuestState removed = states(player).remove(stateKey(quest));
        revision.incrementAndGet();
        Map<String, Object> eventData = definitionEventData(player, quest, "reset", "Quest Reset");
        emit("reset", eventData);
        record(player, "reset", eventData);
        publishFacet(player);
        save();
        if (removed == null) {
            return QuestResult.unchanged("missing", "Quest State Missing", quest, null);
        }
        return QuestResult.changed("reset", "Quest Reset", quest, removed, eventData);
    }

    public QuestResult delete(Player player, String questId) {
        Quest quest = quest(player, questId);
        if (quest == null) {
            return QuestResult.blocked("missing", "Quest Missing", null, null);
        }
        if ("player".equals(quest.scope()) && !ownedBy(player, quest)) {
            return QuestResult.blocked("blocked", "Wrong Owner", quest, null);
        }
        quests.remove(definitionKey(quest));
        playerStates.values().forEach(states -> states.remove(stateKey(quest)));
        revision.incrementAndGet();
        Map<String, Object> eventData = definitionEventData(player, quest, "deleted", "Quest Deleted");
        emit("deleted", eventData);
        record(player, "deleted", eventData);
        Bukkit.getOnlinePlayers().forEach(this::publishFacet);
        save();
        return QuestResult.changed("deleted", "Quest Deleted", quest, null, eventData);
    }

    public QuestResult addXp(Player player, int amount) {
        if (player == null) {
            return QuestResult.blocked("blocked", "Missing Player", null, null);
        }
        PlayerQuestProfile profile = profile(player);
        int previousLevel = profile.level();
        int previousXp = profile.xp();
        profile.addXp(amount);
        revision.incrementAndGet();
        Map<String, Object> eventData = new LinkedHashMap<>();
        eventData.put("type", "xp");
        eventData.put("reason", "Quest XP");
        eventData.put("previous_level", previousLevel);
        eventData.put("previous_xp", previousXp);
        eventData.put("level", profile.level());
        eventData.put("xp", profile.xp());
        eventData.put("xp_to_next_level", profile.xpToNextLevel());
        eventData.put("player_uuid", player.getUniqueId().toString());
        eventData.put("player_name", player.getName());
        emit("xp", eventData);
        record(player, "xp", eventData);
        publishFacet(player);
        save();
        return QuestResult.changed("xp", "Quest XP", null, null, eventData);
    }

    public QuestResult canStart(Player player, String questId) {
        Quest quest = quest(player, questId);
        if (quest == null) {
            return QuestResult.blocked("missing", "Quest Missing", null, null);
        }
        return checkStartRestrictions(player, quest);
    }

    public String snapshotJson(Player player) {
        String client = player != null ? player.getUniqueId().toString() : "server";
        List<Quest> sorted = quests.values().stream().filter(quest -> player == null || visibleTo(player, quest)).collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        sorted.sort(Comparator.comparing(Quest::id, String.CASE_INSENSITIVE_ORDER));
        StringBuilder builder = new StringBuilder();
        builder.append('{');
        appendField(builder, "type", "snapshot").append(',');
        appendField(builder, "player", client).append(',');
        appendNumber(builder, "active", player != null ? activeQuestIds(player).size() : 0).append(',');
        appendNumber(builder, "completed", player != null ? completedQuestIds(player).size() : 0).append(',');
        appendNumber(builder, "level", player != null ? profile(player).level() : 1).append(',');
        appendNumber(builder, "xp", player != null ? profile(player).xp() : 0).append(',');
        appendNumber(builder, "xpToNextLevel", player != null ? profile(player).xpToNextLevel() : 100).append(',');
        builder.append("\"quests\":[");
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            appendQuest(builder, sorted.get(i), player);
        }
        builder.append("]}");
        return builder.toString();
    }

    public String resultJson(QuestResult result, Player player) {
        QuestResult value = result != null ? result : QuestResult.blocked("error", "No Result", null, null);
        StringBuilder builder = new StringBuilder();
        builder.append('{');
        appendField(builder, "type", value.status()).append(',');
        appendField(builder, "reason", value.reason()).append(',');
        builder.append("\"changed\":").append(value.changed()).append(',');
        builder.append("\"allowed\":").append(value.allowed()).append(',');
        appendField(builder, "player", player != null ? player.getUniqueId().toString() : "server");
        if (value.quest() != null) {
            builder.append(',');
            builder.append("\"quest\":");
            appendQuest(builder, value.quest(), player);
        }
        builder.append('}');
        return builder.toString();
    }

    public long revision() {
        return revision.get();
    }

    public List<Map<String, Object>> questList(Player player, String scope, String status) {
        return quests.values().stream()
            .filter(quest -> matchesScope(player, quest, scope))
            .filter(quest -> visibleTo(player, quest))
            .sorted(Comparator.comparing(Quest::id, String.CASE_INSENSITIVE_ORDER))
            .map(quest -> questData(quest, state(player, quest)))
            .filter(data -> matchesStatus(data, status))
            .toList();
    }

    public List<String> questIdList(Player player, String scope, String status) {
        return questList(player, scope, status).stream()
            .map(data -> String.valueOf(data.get("id")))
            .distinct()
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
    }

    public Map<String, Object> profileData(Player player) {
        if (player == null) {
            return Map.of(
                "level", 1,
                "xp", 0,
                "xp_to_next_level", 100,
                "active_count", 0,
                "completed_count", 0,
                "quit_count", 0,
                "available_count", 0,
                "quest_count", quests.size()
            );
        }
        PlayerQuestProfile profile = profile(player);
        List<Map<String, Object>> active = questList(player, "visible", "active");
        List<Map<String, Object>> completed = questList(player, "visible", "completed");
        List<Map<String, Object>> quit = questList(player, "visible", "quit");
        List<Map<String, Object>> available = questList(player, "visible", "available");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("level", profile.level());
        data.put("xp", profile.xp());
        data.put("xp_to_next_level", profile.xpToNextLevel());
        data.put("active_count", active.size());
        data.put("completed_count", completed.size());
        data.put("quit_count", quit.size());
        data.put("available_count", available.size());
        data.put("quest_count", active.size() + completed.size() + quit.size() + available.size());
        data.put("active", active);
        data.put("completed", completed);
        data.put("quit", quit);
        data.put("available", available);
        return data;
    }

    public void publish(Player player) {
        publishFacet(player);
    }

    public void clear() {
        playerStates.clear();
        profiles.clear();
        revision.incrementAndGet();
        save();
    }

    public void clearPlayer(Player player) {
        if (player == null) {
            return;
        }
        clearPlayer(player.getUniqueId());
        publishFacet(player);
    }

    public void clearPlayer(UUID playerId) {
        if (playerId == null) {
            return;
        }
        playerStates.remove(playerId);
        profiles.remove(playerId);
        revision.incrementAndGet();
        save();
    }

    public void seedDefaults() {
        put(new Quest("gather_logs", "Gather Logs", "Collect logs for the spawn builder", "Builder Crate", 32, "", "", "", 3, 30, 1, 70));
        put(new Quest("light_caves", "Light Caves", "Place torches in nearby caves", "Miner Token", 16, "", "", "gather_logs", 3, 60, 2, 90));
        put(new Quest("map_spawn", "Map Spawn", "Visit and map the spawn district", "Scout Badge", 1, "", "world", "", 3, 0, 1, 40));
        put(new Quest("feed_team", "Feed Team", "Bring food for online players", "Cook Token", 8, "request.feed", "", "", 3, 20, 1, 60));
        revision.incrementAndGet();
        Bukkit.getOnlinePlayers().forEach(this::publishFacet);
        save();
    }

    public void reload() {
        load();
        Bukkit.getOnlinePlayers().forEach(this::publishFacet);
    }

    private QuestResult completePrepared(Player player, Quest quest, QuestState state) {
        state.progress(quest.target());
        state.complete();
        profile(player).addXp(quest.xpReward());
        revision.incrementAndGet();
        Map<String, Object> eventData = eventData(player, quest, state, "completed", "Quest Complete");
        emit("completed", eventData);
        record(player, "completed", eventData);
        publishFacet(player);
        save();
        return QuestResult.changed("completed", "Quest Complete", quest, state, eventData);
    }

    public void save() {
        if (storageFile == null) {
            return;
        }
        try {
            Files.createDirectories(storageFile.getParent());
            YamlConfiguration config = new YamlConfiguration();
            for (Map.Entry<String, Quest> entry : quests.entrySet()) {
                String path = "quests." + key(entry.getKey());
                Quest quest = entry.getValue();
                config.set(path + ".id", quest.id());
                config.set(path + ".title", quest.title());
                config.set(path + ".description", quest.description());
                config.set(path + ".reward", quest.reward());
                config.set(path + ".target", quest.target());
                config.set(path + ".permission", quest.permission());
                config.set(path + ".world", quest.world());
                config.set(path + ".requiredQuest", quest.requiredQuest());
                config.set(path + ".maxActive", quest.maxActive());
                config.set(path + ".cooldownSeconds", quest.cooldownSeconds());
                config.set(path + ".requiredLevel", quest.requiredLevel());
                config.set(path + ".xpReward", quest.xpReward());
                config.set(path + ".scope", quest.scope());
                config.set(path + ".owner", quest.owner());
                config.set(path + ".createdAt", quest.createdAt());
            }
            for (Map.Entry<UUID, Map<String, QuestState>> playerEntry : playerStates.entrySet()) {
                for (Map.Entry<String, QuestState> stateEntry : playerEntry.getValue().entrySet()) {
                    String path = "states." + playerEntry.getKey() + "." + key(stateEntry.getKey());
                    QuestState state = stateEntry.getValue();
                    config.set(path + ".questId", state.questId());
                    config.set(path + ".progress", state.progress());
                    config.set(path + ".startedAt", state.startedAt());
                    config.set(path + ".completedAt", state.completedAt());
                    config.set(path + ".quitAt", state.quitAt());
                    config.set(path + ".lastProgressAt", state.lastProgressAt());
                }
            }
            for (Map.Entry<UUID, PlayerQuestProfile> entry : profiles.entrySet()) {
                config.set("profiles." + entry.getKey() + ".xp", entry.getValue().xp());
            }
            config.save(storageFile.toFile());
        } catch (Exception exception) {
            Bukkit.getLogger().warning("[ReQuest] Failed to save quests: " + exception.getMessage());
        }
    }

    private void load() {
        if (storageFile == null || !Files.isRegularFile(storageFile)) {
            return;
        }
        try {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(storageFile.toFile());
            quests.clear();
            playerStates.clear();
            profiles.clear();
            ConfigurationSection questSection = config.getConfigurationSection("quests");
            if (questSection != null) {
                for (String key : questSection.getKeys(false)) {
                    String path = "quests." + key + ".";
                    put(new Quest(
                        config.getString(path + "id", ""),
                        config.getString(path + "title", ""),
                        config.getString(path + "description", ""),
                        config.getString(path + "reward", ""),
                        config.getInt(path + "target", 1),
                        config.getString(path + "permission", ""),
                        config.getString(path + "world", ""),
                        config.getString(path + "requiredQuest", ""),
                        config.getInt(path + "maxActive", 3),
                        config.getInt(path + "cooldownSeconds", 0),
                        config.getInt(path + "requiredLevel", 1),
                        config.getInt(path + "xpReward", 50),
                        config.getString(path + "scope", "global"),
                        config.getString(path + "owner", ""),
                        config.getLong(path + "createdAt", System.currentTimeMillis())
                    ));
                }
            }
            ConfigurationSection statesSection = config.getConfigurationSection("states");
            if (statesSection != null) {
                for (String playerId : statesSection.getKeys(false)) {
                    UUID uuid = UUID.fromString(playerId);
                    Map<String, QuestState> states = playerStates.computeIfAbsent(uuid, ignored -> new ConcurrentHashMap<>());
                    ConfigurationSection playerSection = statesSection.getConfigurationSection(playerId);
                    if (playerSection == null) {
                        continue;
                    }
                    for (String stateId : playerSection.getKeys(false)) {
                        String path = "states." + playerId + "." + stateId + ".";
                        states.put(unkey(stateId), QuestState.restore(
                            config.getString(path + "questId", ""),
                            config.getInt(path + "progress", 0),
                            config.getLong(path + "startedAt", 0L),
                            config.getLong(path + "completedAt", 0L),
                            config.getLong(path + "quitAt", 0L),
                            config.getLong(path + "lastProgressAt", config.getLong(path + "startedAt", 0L))
                        ));
                    }
                }
            }
            ConfigurationSection profileSection = config.getConfigurationSection("profiles");
            if (profileSection != null) {
                for (String playerId : profileSection.getKeys(false)) {
                    PlayerQuestProfile profile = new PlayerQuestProfile();
                    profile.xp(config.getInt("profiles." + playerId + ".xp", 0));
                    profiles.put(UUID.fromString(playerId), profile);
                }
            }
            revision.incrementAndGet();
        } catch (Exception exception) {
            Bukkit.getLogger().warning("[ReQuest] Failed to load quests: " + exception.getMessage());
        }
    }

    private QuestResult ensureActive(Player player, String questId) {
        Quest quest = quest(player, questId);
        if (player == null) {
            return QuestResult.blocked("blocked", "Missing Player", quest, null);
        }
        if (quest == null) {
            return QuestResult.blocked("missing", "Quest Missing", null, null);
        }
        QuestState state = state(player, quest);
        if (state == null || !state.active()) {
            return QuestResult.blocked("inactive", "Quest Not Active", quest, state);
        }
        return QuestResult.unchanged("active", "Quest Active", quest, state);
    }

    private QuestResult checkStartRestrictions(Player player, Quest quest) {
        if (player == null) {
            return QuestResult.blocked("blocked", "Missing Player", quest, null);
        }
        if (!visibleTo(player, quest)) {
            return QuestResult.blocked("blocked", "Quest Not Visible", quest, null);
        }
        if (!quest.permission().isBlank() && !player.hasPermission(quest.permission())) {
            return QuestResult.blocked("blocked", "Missing Permission", quest, null);
        }
        if (!quest.world().isBlank() && (player.getWorld() == null || !player.getWorld().getName().equalsIgnoreCase(quest.world()))) {
            return QuestResult.blocked("blocked", "Wrong World", quest, null);
        }
        if (!quest.requiredQuest().isBlank()) {
            QuestState required = state(player, quest.requiredQuest());
            if (required == null || !required.completed()) {
                return QuestResult.blocked("blocked", "Required Quest Missing", quest, null);
            }
        }
        if (profile(player).level() < quest.requiredLevel()) {
            return QuestResult.blocked("blocked", "Quest Level Too Low", quest, null);
        }
        if (activeQuestIds(player).size() >= quest.maxActive()) {
            return QuestResult.blocked("blocked", "Too Many Active Quests", quest, null);
        }
        QuestState existing = state(player, quest);
        if (existing != null && existing.quitAt() > 0 && quest.cooldownSeconds() > 0) {
            long elapsed = (System.currentTimeMillis() - existing.quitAt()) / 1000L;
            if (elapsed < quest.cooldownSeconds()) {
                return QuestResult.blocked("blocked", "Quest Cooldown", quest, existing);
            }
        }
        return QuestResult.unchanged("allowed", "Allowed", quest, null);
    }

    private Map<String, QuestState> states(Player player) {
        return playerStates.computeIfAbsent(player.getUniqueId(), ignored -> new ConcurrentHashMap<>());
    }

    private PlayerQuestProfile profile(Player player) {
        return profiles.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerQuestProfile());
    }

    private void put(Quest quest) {
        Quest normalized = quest.normalize();
        quests.put(definitionKey(normalized), normalized);
    }

    private String definitionKey(Quest quest) {
        if ("player".equals(quest.scope())) {
            return "player:" + quest.owner() + ":" + quest.id();
        }
        if ("permission".equals(quest.scope())) {
            return "permission:" + quest.permission().toLowerCase() + ":" + quest.id();
        }
        return "global:" + quest.id();
    }

    private String stateKey(Quest quest) {
        return definitionKey(quest);
    }

    private boolean visibleTo(Player player, Quest quest) {
        if (quest == null) {
            return false;
        }
        if ("global".equals(quest.scope())) {
            return true;
        }
        if (player == null) {
            return false;
        }
        if ("player".equals(quest.scope())) {
            return ownedBy(player, quest);
        }
        return quest.permission().isBlank() || player.hasPermission(quest.permission());
    }

    private boolean ownedBy(Player player, Quest quest) {
        return player != null && quest != null && player.getUniqueId().toString().equalsIgnoreCase(quest.owner());
    }

    private boolean matchesScope(Player player, Quest quest, String scope) {
        String normalized = clean(scope);
        if (normalized.isBlank() || "all".equals(normalized)) {
            return true;
        }
        if ("visible".equals(normalized)) {
            return visibleTo(player, quest);
        }
        if ("owned".equals(normalized)) {
            return ownedBy(player, quest);
        }
        return quest.scope().equalsIgnoreCase(normalized);
    }

    private boolean matchesStatus(Map<String, Object> data, String status) {
        String normalized = clean(status);
        if (normalized.isBlank() || "all".equals(normalized)) {
            return true;
        }
        if ("available".equals(normalized)) {
            return !Boolean.TRUE.equals(data.get("active")) && !Boolean.TRUE.equals(data.get("completed")) && !Boolean.TRUE.equals(data.get("quit"));
        }
        return Boolean.TRUE.equals(data.get(normalized));
    }

    private void emit(String type, Map<String, Object> eventData) {
        CustomEventManager.getInstance().emit("request:" + type, eventData);
    }

    private void record(Player player, String type, Map<String, Object> eventData) {
        if (trackingService == null || player == null) {
            return;
        }
        trackingService.recordEvent(player.getUniqueId(), player.getName(), ReQuestExtension.MODULE_ID, "quest", type, eventData);
    }

    private void publishFacet(Player player) {
        if (trackingService == null || player == null) {
            return;
        }
        PlayerQuestProfile profile = profile(player);
        Map<String, QuestState> states = states(player);
        List<Map<String, Object>> active = new ArrayList<>();
        List<Map<String, Object>> completed = new ArrayList<>();
        List<Map<String, Object>> quit = new ArrayList<>();
        List<Map<String, Object>> available = new ArrayList<>();
        List<Quest> sorted = quests.values().stream()
            .filter(quest -> visibleTo(player, quest))
            .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        sorted.sort(Comparator.comparing(Quest::id, String.CASE_INSENSITIVE_ORDER));
        for (Quest quest : sorted) {
            QuestState state = states.get(stateKey(quest));
            Map<String, Object> row = questData(quest, state);
            if (state != null && state.active()) {
                active.add(row);
            } else if (state != null && state.completed()) {
                completed.add(row);
            } else if (state != null && state.abandoned()) {
                quit.add(row);
            } else {
                available.add(row);
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("level", profile.level());
        data.put("xp", profile.xp());
        data.put("xp_to_next_level", profile.xpToNextLevel());
        data.put("active_count", active.size());
        data.put("completed_count", completed.size());
        data.put("quit_count", quit.size());
        data.put("available_count", available.size());
        data.put("quest_count", sorted.size());
        data.put("active", active);
        data.put("completed", completed);
        data.put("quit", quit);
        data.put("available", available);
        data.put("summary", List.of(
            row("Level", "Lv " + profile.level(), profile.xp() + " XP"),
            row("Active", String.valueOf(active.size()), available.size() + " Available"),
            row("Completed", String.valueOf(completed.size()), quit.size() + " Quit")
        ));
        data.put("sections", List.of(
            section("Active", active),
            section("Available", available),
            section("Completed", completed),
            section("Quit", quit)
        ));
        trackingService.upsertFacet(player.getUniqueId(), player.getName(), "request:quests", ReQuestExtension.MODULE_ID, PlayerFacetMetadata.tab("Quests", "Quests", 120), data);
    }

    private Map<String, Object> questData(Quest quest, QuestState state) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", quest.id());
        data.put("title", quest.title());
        data.put("description", quest.description());
        data.put("reward", quest.reward());
        data.put("target", quest.target());
        data.put("progress", state != null ? state.progress() : 0);
        data.put("progress_percent", progressPercent(state, quest));
        data.put("required_level", quest.requiredLevel());
        data.put("xp_reward", quest.xpReward());
        data.put("permission", quest.permission());
        data.put("world", quest.world());
        data.put("required_quest", quest.requiredQuest());
        data.put("max_active", quest.maxActive());
        data.put("cooldown_seconds", quest.cooldownSeconds());
        data.put("scope", quest.scope());
        data.put("owner", quest.owner());
        data.put("created_at", quest.createdAt());
        data.put("active", state != null && state.active());
        data.put("completed", state != null && state.completed());
        data.put("quit", state != null && state.abandoned());
        data.put("started_at", state != null ? state.startedAt() : 0L);
        data.put("completed_at", state != null ? state.completedAt() : 0L);
        data.put("quit_at", state != null ? state.quitAt() : 0L);
        data.put("last_progress_at", state != null ? state.lastProgressAt() : 0L);
        data.put("time_to_complete_ms", state != null ? state.timeToComplete() : 0L);
        data.put("active_time_ms", state != null ? state.activeTime() : 0L);
        data.put("value", (state != null ? state.progress() : 0) + "/" + quest.target());
        data.put("meta", progressPercent(state, quest) + "% | Lv " + quest.requiredLevel() + " | " + quest.xpReward() + " XP");
        return data;
    }

    private Map<String, Object> section(String title, List<Map<String, Object>> rows) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("title", title);
        section.put("rows", rows);
        return section;
    }

    private Map<String, Object> row(String title, String value, String meta) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("title", title);
        row.put("value", value);
        row.put("meta", meta);
        return row;
    }

    private Map<String, Object> eventData(Player player, Quest quest, QuestState state, String type, String reason) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", type);
        data.put("reason", reason);
        data.put("quest_id", quest.id());
        data.put("quest", quest.id());
        data.put("title", quest.title());
        data.put("description", quest.description());
        data.put("reward", quest.reward());
        data.put("target", quest.target());
        data.put("progress", state.progress());
        data.put("progress_percent", progressPercent(state, quest));
        data.put("required_level", quest.requiredLevel());
        data.put("xp_reward", quest.xpReward());
        data.put("permission", quest.permission());
        data.put("world", quest.world());
        data.put("required_quest", quest.requiredQuest());
        data.put("max_active", quest.maxActive());
        data.put("cooldown_seconds", quest.cooldownSeconds());
        data.put("scope", quest.scope());
        data.put("owner", quest.owner());
        data.put("created_at", quest.createdAt());
        data.put("level", player != null ? profile(player).level() : 1);
        data.put("xp", player != null ? profile(player).xp() : 0);
        data.put("xp_to_next_level", player != null ? profile(player).xpToNextLevel() : 100);
        data.put("active", state.active());
        data.put("completed", state.completed());
        data.put("quit", state.abandoned());
        data.put("started_at", state.startedAt());
        data.put("completed_at", state.completedAt());
        data.put("quit_at", state.quitAt());
        data.put("last_progress_at", state.lastProgressAt());
        data.put("time_to_complete_ms", state.timeToComplete());
        data.put("active_time_ms", state.activeTime());
        data.put("player_uuid", player != null ? player.getUniqueId().toString() : "");
        data.put("player_name", player != null ? player.getName() : "");
        return data;
    }

    private Map<String, Object> definitionEventData(Player player, Quest quest, String type, String reason) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", type);
        data.put("reason", reason);
        data.put("quest_id", quest.id());
        data.put("quest", quest.id());
        data.put("title", quest.title());
        data.put("description", quest.description());
        data.put("reward", quest.reward());
        data.put("target", quest.target());
        data.put("required_level", quest.requiredLevel());
        data.put("xp_reward", quest.xpReward());
        data.put("permission", quest.permission());
        data.put("world", quest.world());
        data.put("required_quest", quest.requiredQuest());
        data.put("max_active", quest.maxActive());
        data.put("cooldown_seconds", quest.cooldownSeconds());
        data.put("scope", quest.scope());
        data.put("owner", quest.owner());
        data.put("created_at", quest.createdAt());
        data.put("progress", 0);
        data.put("progress_percent", 0);
        data.put("active", false);
        data.put("completed", false);
        data.put("quit", false);
        data.put("started_at", 0L);
        data.put("completed_at", 0L);
        data.put("quit_at", 0L);
        data.put("last_progress_at", 0L);
        data.put("time_to_complete_ms", 0L);
        data.put("active_time_ms", 0L);
        data.put("player_uuid", player != null ? player.getUniqueId().toString() : "");
        data.put("player_name", player != null ? player.getName() : "");
        return data;
    }

    private void appendQuest(StringBuilder builder, Quest quest, Player player) {
        QuestState state = player != null ? state(player, quest) : null;
        builder.append('{');
        appendField(builder, "id", quest.id()).append(',');
        appendField(builder, "title", quest.title()).append(',');
        appendField(builder, "description", quest.description()).append(',');
        appendField(builder, "reward", quest.reward()).append(',');
        appendNumber(builder, "target", quest.target()).append(',');
        appendNumber(builder, "progress", state != null ? state.progress() : 0).append(',');
        appendNumber(builder, "requiredLevel", quest.requiredLevel()).append(',');
        appendNumber(builder, "xpReward", quest.xpReward()).append(',');
        appendNumber(builder, "createdAt", quest.createdAt()).append(',');
        appendNumber(builder, "startedAt", state != null ? state.startedAt() : 0L).append(',');
        appendNumber(builder, "completedAt", state != null ? state.completedAt() : 0L).append(',');
        appendNumber(builder, "quitAt", state != null ? state.quitAt() : 0L).append(',');
        appendNumber(builder, "lastProgressAt", state != null ? state.lastProgressAt() : 0L).append(',');
        appendNumber(builder, "timeToCompleteMs", state != null ? state.timeToComplete() : 0L).append(',');
        appendNumber(builder, "activeTimeMs", state != null ? state.activeTime() : 0L).append(',');
        appendField(builder, "scope", quest.scope()).append(',');
        builder.append("\"active\":").append(state != null && state.active()).append(',');
        builder.append("\"completed\":").append(state != null && state.completed()).append(',');
        builder.append("\"quit\":").append(state != null && state.abandoned());
        builder.append('}');
    }

    private int progressPercent(QuestState state, Quest quest) {
        if (state == null || quest == null || quest.target() <= 0) {
            return 0;
        }
        return Math.min(100, Math.max(0, (int) Math.round(state.progress() * 100.0 / quest.target())));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private static String key(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((value != null ? value : "").getBytes(StandardCharsets.UTF_8));
    }

    private static String unkey(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static StringBuilder appendField(StringBuilder builder, String name, String value) {
        builder.append('"').append(escape(name)).append("\":\"").append(escape(value)).append('"');
        return builder;
    }

    private static StringBuilder appendNumber(StringBuilder builder, String name, int value) {
        builder.append('"').append(escape(name)).append("\":").append(value);
        return builder;
    }

    private static StringBuilder appendNumber(StringBuilder builder, String name, long value) {
        builder.append('"').append(escape(name)).append("\":").append(value);
        return builder;
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
