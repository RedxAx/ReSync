package restudio.flow.data;

import java.util.ArrayList;
import java.util.List;

public class ScoreboardDefinition {
    public static final String SLOT_SIDEBAR = "sidebar";
    public static final String SLOT_BELOW_NAME = "below_name";

    private String id;
    private String title;
    private String objectiveId;
    private String displaySlot;
    private List<String> lines;

    public ScoreboardDefinition() {
        this.lines = new ArrayList<>();
        this.displaySlot = SLOT_SIDEBAR;
    }

    public ScoreboardDefinition(String id, String title) {
        this.id = id;
        this.title = title;
        this.objectiveId = id;
        this.displaySlot = SLOT_SIDEBAR;
        this.lines = new ArrayList<>();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getObjectiveId() {
        return objectiveId;
    }

    public void setObjectiveId(String objectiveId) {
        this.objectiveId = objectiveId;
    }

    public String getDisplaySlot() {
        return displaySlot;
    }

    public void setDisplaySlot(String displaySlot) {
        String normalized = displaySlot != null ? displaySlot.trim().toLowerCase() : "";
        if (SLOT_BELOW_NAME.equals(normalized)) {
            this.displaySlot = SLOT_BELOW_NAME;
            return;
        }
        this.displaySlot = SLOT_SIDEBAR;
    }

    public List<String> getLines() {
        return lines;
    }

    public void setLines(List<String> lines) {
        this.lines = lines != null ? lines : new ArrayList<>();
    }
}
