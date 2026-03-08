package restudio.resync.world;

import java.util.LinkedHashMap;
import java.util.Map;

public class WorldMapQuery {
    private String worldName;
    private double centerX;
    private double centerZ;
    private int zoom;
    private Map<String, Object> options = new LinkedHashMap<>();

    public WorldMapQuery copy() {
        WorldMapQuery copy = new WorldMapQuery();
        copy.worldName = worldName;
        copy.centerX = centerX;
        copy.centerZ = centerZ;
        copy.zoom = zoom;
        copy.options = options == null ? new LinkedHashMap<>() : new LinkedHashMap<>(options);
        return copy;
    }

    public String getWorldName() {
        return worldName;
    }

    public void setWorldName(String worldName) {
        this.worldName = worldName;
    }

    public double getCenterX() {
        return centerX;
    }

    public void setCenterX(double centerX) {
        this.centerX = centerX;
    }

    public double getCenterZ() {
        return centerZ;
    }

    public void setCenterZ(double centerZ) {
        this.centerZ = centerZ;
    }

    public int getZoom() {
        return zoom;
    }

    public void setZoom(int zoom) {
        this.zoom = zoom;
    }

    public Map<String, Object> getOptions() {
        return options;
    }

    public void setOptions(Map<String, Object> options) {
        this.options = options == null ? new LinkedHashMap<>() : new LinkedHashMap<>(options);
    }
}
