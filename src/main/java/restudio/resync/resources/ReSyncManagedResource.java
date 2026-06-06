package restudio.resync.resources;

public final class ReSyncManagedResource {
    private final String typeId;
    private final String displayName;
    private final String defaultFolder;
    private final FlowPackets flowPackets;
    private final boolean enabled;
    private final boolean jsonStorageSupported;

    public ReSyncManagedResource(String typeId, String displayName, String defaultFolder, FlowPackets flowPackets, boolean enabled) {
        this(typeId, displayName, defaultFolder, flowPackets, enabled, false);
    }

    public ReSyncManagedResource(String typeId, String displayName, String defaultFolder, FlowPackets flowPackets, boolean enabled, boolean jsonStorageSupported) {
        this.typeId = typeId;
        this.displayName = displayName;
        this.defaultFolder = defaultFolder;
        this.flowPackets = flowPackets;
        this.enabled = enabled;
        this.jsonStorageSupported = jsonStorageSupported;
    }

    public String typeId() {
        return typeId;
    }

    public String displayName() {
        return displayName;
    }

    public String defaultFolder() {
        return defaultFolder;
    }

    public FlowPackets flowPackets() {
        return flowPackets;
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean jsonStorageSupported() {
        return jsonStorageSupported;
    }

    public boolean hasFlowPackets() {
        return flowPackets != null;
    }

    public record FlowPackets(byte request, byte listRequest, byte data, byte list, byte save, byte delete, byte saveAck) {
        public boolean matches(byte packetId) {
            return packetId == request || packetId == listRequest || packetId == save || packetId == delete;
        }
    }
}
