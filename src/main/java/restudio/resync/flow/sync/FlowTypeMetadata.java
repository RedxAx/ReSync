package restudio.resync.flow.sync;

public class FlowTypeMetadata extends restudio.resync.flow.contract.FlowTypeMetadata {
    public FlowTypeMetadata() {
    }

    public FlowTypeMetadata(String id, String displayName, int color, String parentId, boolean canStringify, boolean literalInput, boolean objectPin) {
        super(id, displayName, color, parentId, canStringify, literalInput, objectPin);
    }
}
