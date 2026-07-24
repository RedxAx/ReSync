package restudio.resync.flow.contract;

public class FlowCategoryMetadata {
    private String id;
    private String displayName;
    private int color;
    private int priority;
    private String groupId;
    private String groupName;
    private int groupColor;
    private int groupPriority;

    public FlowCategoryMetadata() {
    }

    public FlowCategoryMetadata(String id, String displayName, int color, int priority) {
        this(id, displayName, color, priority, "flow", "Flow", 0xFF55FFFF, 100);
    }

    public FlowCategoryMetadata(String id, String displayName, int color, int priority, String groupId, String groupName, int groupColor, int groupPriority) {
        this.id = id;
        this.displayName = displayName;
        this.color = color;
        this.priority = priority;
        this.groupId = groupId;
        this.groupName = groupName;
        this.groupColor = groupColor;
        this.groupPriority = groupPriority;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public int getColor() {
        return color;
    }

    public void setColor(int color) {
        this.color = color;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public int getGroupColor() {
        return groupColor;
    }

    public void setGroupColor(int groupColor) {
        this.groupColor = groupColor;
    }

    public int getGroupPriority() {
        return groupPriority;
    }

    public void setGroupPriority(int groupPriority) {
        this.groupPriority = groupPriority;
    }
}
