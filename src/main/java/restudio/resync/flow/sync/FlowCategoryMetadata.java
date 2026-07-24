package restudio.resync.flow.sync;

public class FlowCategoryMetadata extends restudio.resync.flow.contract.FlowCategoryMetadata {
    public FlowCategoryMetadata() {
    }

    public FlowCategoryMetadata(String id, String displayName, int color, int priority) {
        super(id, displayName, color, priority);
    }

    public FlowCategoryMetadata(String id, String displayName, int color, int priority, String groupId, String groupName, int groupColor, int groupPriority) {
        super(id, displayName, color, priority, groupId, groupName, groupColor, groupPriority);
    }
}
