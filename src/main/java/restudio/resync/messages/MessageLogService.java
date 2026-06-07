package restudio.resync.messages;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MessageLogService {
    private static final int MAX_ENTRIES = 1000;
    private static final int MAX_PAGE_SIZE = 100;
    private final Deque<MessageLogEntry> entries = new ArrayDeque<>();
    private long nextSequence;

    public synchronized void record(String source, Player target, String plainText, String componentJson, String hook) {
        String text = plainText != null ? plainText : "";
        if (text.isBlank()) {
            return;
        }
        entries.addFirst(new MessageLogEntry(
            ++nextSequence,
            System.currentTimeMillis(),
            clean(source),
            clean(hook),
            target != null ? target.getName() : "",
            target != null ? target.getUniqueId().toString() : "",
            target != null && target.getWorld() != null ? target.getWorld().getName() : "",
            text,
            componentJson != null ? componentJson : ""
        ));
        while (entries.size() > MAX_ENTRIES) {
            entries.removeLast();
        }
    }

    public synchronized JsonObject page(int page, int pageSize, String query, String source) {
        int normalizedSize = Math.clamp(pageSize <= 0 ? 20 : pageSize, 1, MAX_PAGE_SIZE);
        int normalizedPage = Math.max(0, page);
        String normalizedQuery = query != null ? query.strip().toLowerCase(Locale.ROOT) : "";
        String normalizedSource = source != null ? source.strip().toLowerCase(Locale.ROOT) : "";
        Map<String, MessageLogEntry> deduped = new LinkedHashMap<>();
        entries.stream()
            .filter(entry -> normalizedSource.isBlank() || entry.source().equalsIgnoreCase(normalizedSource))
            .filter(entry -> normalizedQuery.isBlank()
                || entry.plainText().toLowerCase(Locale.ROOT).contains(normalizedQuery)
                || entry.targetPlayer().toLowerCase(Locale.ROOT).contains(normalizedQuery))
            .forEach(entry -> deduped.putIfAbsent(entry.source().toLowerCase(Locale.ROOT) + "\u0000" + entry.plainText(), entry));
        List<MessageLogEntry> filtered = new ArrayList<>(deduped.values());
        int total = filtered.size();
        int from = Math.min(total, normalizedPage * normalizedSize);
        int to = Math.min(total, from + normalizedSize);
        JsonArray array = new JsonArray();
        for (MessageLogEntry entry : new ArrayList<>(filtered.subList(from, to))) {
            array.add(entry.toJson());
        }
        JsonObject result = new JsonObject();
        result.addProperty("page", normalizedPage);
        result.addProperty("pageSize", normalizedSize);
        result.addProperty("total", total);
        result.addProperty("query", query != null ? query : "");
        result.addProperty("source", source != null ? source : "");
        result.add("entries", array);
        return result;
    }

    private String clean(String value) {
        return value != null ? value.strip() : "";
    }

    private record MessageLogEntry(long sequence, long timestamp, String source, String hook, String targetPlayer, String targetUuid, String world, String plainText, String componentJson) {
        private JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("sequence", sequence);
            json.addProperty("timestamp", timestamp);
            json.addProperty("source", source);
            json.addProperty("hook", hook);
            json.addProperty("targetPlayer", targetPlayer);
            json.addProperty("targetUuid", targetUuid);
            json.addProperty("world", world);
            json.addProperty("plainText", plainText);
            json.addProperty("componentJson", componentJson);
            return json;
        }
    }
}
