package restudio.request;

import java.util.Map;

public record QuestResult(boolean changed, boolean allowed, String status, String reason, Quest quest, QuestState state, Map<String, Object> eventData) {
    public static QuestResult blocked(String status, String reason, Quest quest, QuestState state) {
        return new QuestResult(false, false, status, reason, quest, state, Map.of());
    }

    public static QuestResult changed(String status, String reason, Quest quest, QuestState state, Map<String, Object> eventData) {
        return new QuestResult(true, true, status, reason, quest, state, eventData != null ? eventData : Map.of());
    }

    public static QuestResult unchanged(String status, String reason, Quest quest, QuestState state) {
        return new QuestResult(false, true, status, reason, quest, state, Map.of());
    }
}
