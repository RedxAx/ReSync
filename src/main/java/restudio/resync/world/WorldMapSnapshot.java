package restudio.resync.world;

import java.util.ArrayList;
import java.util.List;

public class WorldMapSnapshot {
    private List<WorldMapControl> controls = new ArrayList<>();
    private List<WorldMapDrawing> drawings = new ArrayList<>();
    private long generatedAt;

    public WorldMapSnapshot copy() {
        WorldMapSnapshot copy = new WorldMapSnapshot();
        copy.controls = new ArrayList<>();
        for (WorldMapControl control : controls) {
            copy.controls.add(control == null ? null : control.copy());
        }
        copy.drawings = new ArrayList<>();
        for (WorldMapDrawing drawing : drawings) {
            copy.drawings.add(drawing == null ? null : drawing.copy());
        }
        copy.generatedAt = generatedAt;
        return copy;
    }

    public List<WorldMapControl> getControls() {
        return controls;
    }

    public void setControls(List<WorldMapControl> controls) {
        this.controls = controls == null ? new ArrayList<>() : controls;
    }

    public List<WorldMapDrawing> getDrawings() {
        return drawings;
    }

    public void setDrawings(List<WorldMapDrawing> drawings) {
        this.drawings = drawings == null ? new ArrayList<>() : drawings;
    }

    public long getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(long generatedAt) {
        this.generatedAt = generatedAt;
    }
}
