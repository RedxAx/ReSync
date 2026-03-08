package restudio.resync.world;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class WorldMapDrawing {
    private String extensionId;
    private String drawingId;
    private String label;
    private String kind;
    private String worldName;
    private List<WorldMapCoordinate> coordinates = new ArrayList<>();
    private Map<String, Object> data = new LinkedHashMap<>();

    public WorldMapDrawing copy() {
        WorldMapDrawing copy = new WorldMapDrawing();
        copy.extensionId = extensionId;
        copy.drawingId = drawingId;
        copy.label = label;
        copy.kind = kind;
        copy.worldName = worldName;
        copy.coordinates = new ArrayList<>();
        for (WorldMapCoordinate coordinate : coordinates) {
            copy.coordinates.add(coordinate == null ? null : coordinate.copy());
        }
        copy.data = data == null ? new LinkedHashMap<>() : new LinkedHashMap<>(data);
        return copy;
    }

    public String getExtensionId() {
        return extensionId;
    }

    public void setExtensionId(String extensionId) {
        this.extensionId = extensionId;
    }

    public String getDrawingId() {
        return drawingId;
    }

    public void setDrawingId(String drawingId) {
        this.drawingId = drawingId;
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

    public String getWorldName() {
        return worldName;
    }

    public void setWorldName(String worldName) {
        this.worldName = worldName;
    }

    public List<WorldMapCoordinate> getCoordinates() {
        return coordinates;
    }

    public void setCoordinates(List<WorldMapCoordinate> coordinates) {
        this.coordinates = coordinates == null ? new ArrayList<>() : coordinates;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public void setData(Map<String, Object> data) {
        this.data = data == null ? new LinkedHashMap<>() : new LinkedHashMap<>(data);
    }
}
