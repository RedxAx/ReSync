package restudio.resync.world;

import java.util.LinkedHashMap;
import java.util.Map;

public class WorldChannelMessage {
    private String type;
    private String action;
    private boolean success;
    private String message;
    private Object data;
    private long timestamp;

    public static WorldChannelMessage response(String action, boolean success, String message, Object data) {
        WorldChannelMessage output = new WorldChannelMessage();
        output.type = "response";
        output.action = action;
        output.success = success;
        output.message = message;
        output.data = data;
        output.timestamp = System.currentTimeMillis();
        return output;
    }

    public static WorldChannelMessage event(String action, Object data) {
        WorldChannelMessage output = new WorldChannelMessage();
        output.type = "event";
        output.action = action;
        output.success = true;
        output.message = "Ok";
        output.data = data;
        output.timestamp = System.currentTimeMillis();
        return output;
    }

    public static WorldChannelMessage job(String action, Object data) {
        WorldChannelMessage output = new WorldChannelMessage();
        output.type = "job";
        output.action = action;
        output.success = false;
        output.message = action;
        output.data = data;
        output.timestamp = System.currentTimeMillis();
        return output;
    }

    public static WorldChannelMessage error(String action, String message) {
        WorldChannelMessage output = new WorldChannelMessage();
        output.type = "error";
        output.action = action;
        output.success = false;
        output.message = message;
        output.data = new LinkedHashMap<String, Object>();
        output.timestamp = System.currentTimeMillis();
        return output;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = data;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
