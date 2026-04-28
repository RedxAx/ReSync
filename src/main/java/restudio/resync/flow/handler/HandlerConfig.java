package restudio.resync.flow.handler;

import java.util.Map;

public class HandlerConfig {
    private final Map<String, Object> config;

    public HandlerConfig(Map<String, Object> config) {
        this.config = config != null ? config : Map.of();
    }

    public String getString(String key) {
        Object value = config.get(key);
        return value != null ? value.toString() : null;
    }

    public String getString(String key, String defaultValue) {
        Object value = config.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    public int getInt(String key, int defaultValue) {
        Object value = config.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value != null ? Integer.parseInt(value.toString()) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public double getDouble(String key, double defaultValue) {
        Object value = config.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value != null ? Double.parseDouble(value.toString()) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        Object value = config.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        return value != null ? Boolean.parseBoolean(value.toString()) : defaultValue;
    }
}
