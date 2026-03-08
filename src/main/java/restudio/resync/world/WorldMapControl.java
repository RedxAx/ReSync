package restudio.resync.world;

import java.util.LinkedHashMap;
import java.util.Map;

public class WorldMapControl {
    private String extensionId;
    private String controlId;
    private String label;
    private String kind;
    private Map<String, Object> data = new LinkedHashMap<>();

    public WorldMapControl copy() {
        WorldMapControl copy = new WorldMapControl();
        copy.extensionId = extensionId;
        copy.controlId = controlId;
        copy.label = label;
        copy.kind = kind;
        copy.data = data == null ? new LinkedHashMap<>() : new LinkedHashMap<>(data);
        return copy;
    }

    public String getExtensionId() {
        return extensionId;
    }

    public void setExtensionId(String extensionId) {
        this.extensionId = extensionId;
    }

    public String getControlId() {
        return controlId;
    }

    public void setControlId(String controlId) {
        this.controlId = controlId;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public void setData(Map<String, Object> data) {
        this.data = data == null ? new LinkedHashMap<>() : new LinkedHashMap<>(data);
    }
}
