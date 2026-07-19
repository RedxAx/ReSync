package restudio.flow.data;

public record FlowNpcHandle(String definitionId, String entityUuid, boolean packetBacked, boolean active, String world,
                            double x, double y, double z, float yaw, float pitch) {
    public FlowNpcHandle {
        definitionId = definitionId != null ? definitionId.strip() : "";
        entityUuid = entityUuid != null ? entityUuid.strip() : "";
        world = world != null ? world.strip() : "";
    }
}
