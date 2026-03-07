package restudio.resync.player;

import java.util.LinkedHashMap;
import java.util.Map;

public class PlayerEventRecord {
    private String eventId;
    private long timestamp;
    private String moduleId;
    private String category;
    private String type;
    private Map<String, Object> data = new LinkedHashMap<>();

    public PlayerEventRecord copy() {
        PlayerEventRecord copy = new PlayerEventRecord();
        copy.eventId = eventId;
        copy.timestamp = timestamp;
        copy.moduleId = moduleId;
        copy.category = category;
        copy.type = type;
        copy.data = data == null ? new LinkedHashMap<>() : new LinkedHashMap<>(data);
        return copy;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public String getModuleId() {
        return moduleId;
    }

    public void setModuleId(String moduleId) {
        this.moduleId = moduleId;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public void setData(Map<String, Object> data) {
        this.data = data == null ? new LinkedHashMap<>() : new LinkedHashMap<>(data);
    }
}
