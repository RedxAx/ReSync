package restudio.resync.resources;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ReSyncResourceCatalog {
    public static final String FLOW = "flow";
    public static final String FUNCTION = "function";
    public static final String COMMAND = "command";
    public static final String GUI = "gui";
    public static final String SCOREBOARD = "scoreboard";
    public static final String TAB = "tab";
    public static final String CUSTOM_CONTENT = "custom_content";
    public static final String PROJECT_METADATA = "project_metadata";
    public static final String WORLDGEN = "worldgen";
    public static final String WORLD = "world";
    private static final Map<String, ReSyncManagedResource> BY_TYPE = new LinkedHashMap<>();
    private static final Map<Byte, ReSyncManagedResource> BY_FLOW_PACKET = new LinkedHashMap<>();

    static {
        register(new ReSyncManagedResource(FLOW, "Flow", "Blueprints/Flows", new ReSyncManagedResource.FlowPackets((byte) 0x01, (byte) 0x09, (byte) 0x02, (byte) 0x0A, (byte) 0x03, (byte) 0x08, (byte) 0x07), true));
        register(new ReSyncManagedResource(FUNCTION, "Function", "Blueprints/Functions", null, true));
        register(new ReSyncManagedResource(COMMAND, "Command", "Blueprints/Commands", null, true));
        register(new ReSyncManagedResource(GUI, "GUI", "GUIs", new ReSyncManagedResource.FlowPackets((byte) 0x11, (byte) 0x14, (byte) 0x12, (byte) 0x15, (byte) 0x13, (byte) 0x16, (byte) 0x17), true));
        register(new ReSyncManagedResource(SCOREBOARD, "Scoreboard", "Customization/Scoreboards", new ReSyncManagedResource.FlowPackets((byte) 0x18, (byte) 0x1A, (byte) 0x1C, (byte) 0x1D, (byte) 0x19, (byte) 0x1B, (byte) 0x1E), true));
        register(new ReSyncManagedResource(TAB, "Tab", "Customization/Tabs", new ReSyncManagedResource.FlowPackets((byte) 0x20, (byte) 0x22, (byte) 0x24, (byte) 0x25, (byte) 0x21, (byte) 0x23, (byte) 0x26), true));
        register(new ReSyncManagedResource(CUSTOM_CONTENT, "Custom Content", "Content/Items", new ReSyncManagedResource.FlowPackets((byte) 0x30, (byte) 0x36, (byte) 0x32, (byte) 0x31, (byte) 0x33, (byte) 0x34, (byte) 0x35), true));
        register(new ReSyncManagedResource(PROJECT_METADATA, "Project Metadata", "", new ReSyncManagedResource.FlowPackets((byte) 0x50, (byte) 0x51, (byte) 0x52, (byte) 0x53, (byte) 0x54, (byte) 0x55, (byte) 0x56), true));
        register(new ReSyncManagedResource(WORLDGEN, "WorldGen", "WorldGen", null, true));
        register(new ReSyncManagedResource(WORLD, "World", "Worlds", null, true));
    }

    private ReSyncResourceCatalog() {
    }

    public static Collection<ReSyncManagedResource> all() {
        return List.copyOf(BY_TYPE.values());
    }

    public static ReSyncManagedResource byType(String typeId) {
        return BY_TYPE.get(typeId);
    }

    public static ReSyncManagedResource byFlowPacket(byte packetId) {
        return BY_FLOW_PACKET.get(packetId);
    }

    public static String defaultFolder(String typeId) {
        ReSyncManagedResource resource = byType(typeId);
        return resource != null ? resource.defaultFolder() : "Blueprints/Flows";
    }

    private static void register(ReSyncManagedResource resource) {
        BY_TYPE.put(resource.typeId(), resource);
        if (resource.flowPackets() != null) {
            BY_FLOW_PACKET.put(resource.flowPackets().request(), resource);
            BY_FLOW_PACKET.put(resource.flowPackets().listRequest(), resource);
            BY_FLOW_PACKET.put(resource.flowPackets().save(), resource);
            BY_FLOW_PACKET.put(resource.flowPackets().delete(), resource);
        }
    }
}
