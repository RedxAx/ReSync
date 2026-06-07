package restudio.resync.resources;

import restudio.resync.contracts.ReSyncProtocolContract;

import java.util.ArrayList;
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
    public static final String CHAT = "chat";
    public static final String MOTD_PROFILE = "motd_profile";
    public static final String MESSAGE_RULE = "message_rule";
    public static final String RECIPE_DEFINITION = "recipe_definition";
    public static final String TEXT_TEMPLATE = "text_template";
    public static final String ADVANCEMENT_TREE = "advancement_tree";
    public static final String DIALOG = "dialog";
    public static final String WORLDGEN = "worldgen";
    public static final String WORLD = "world";
    private static final Map<String, ReSyncManagedResource> BY_TYPE = new LinkedHashMap<>();
    private static final Map<Byte, ReSyncManagedResource> BY_FLOW_PACKET = new LinkedHashMap<>();

    static {
        for (ReSyncProtocolContract.ResourceContract resource : ReSyncProtocolContract.RESOURCE_CONTRACTS) {
            register(new ReSyncManagedResource(
                resource.typeId(),
                resource.displayName(),
                resource.defaultFolder(),
                flowPackets(resource.flowPackets()),
                true,
                resource.jsonStorageSupported()
            ));
        }
    }

    private ReSyncResourceCatalog() {
    }

    public static Collection<ReSyncManagedResource> all() {
        return List.copyOf(BY_TYPE.values());
    }

    public static List<String> jsonStorageTypes() {
        List<String> types = new ArrayList<>();
        for (ReSyncManagedResource resource : BY_TYPE.values()) {
            if (resource.jsonStorageSupported()) {
                types.add(resource.typeId());
            }
        }
        return List.copyOf(types);
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
            BY_FLOW_PACKET.put(resource.flowPackets().data(), resource);
            BY_FLOW_PACKET.put(resource.flowPackets().list(), resource);
            BY_FLOW_PACKET.put(resource.flowPackets().save(), resource);
            BY_FLOW_PACKET.put(resource.flowPackets().delete(), resource);
            BY_FLOW_PACKET.put(resource.flowPackets().saveAck(), resource);
        }
    }

    private static ReSyncManagedResource.FlowPackets flowPackets(ReSyncProtocolContract.ResourceFlowPackets packets) {
        if (packets == null) {
            return null;
        }
        return new ReSyncManagedResource.FlowPackets(
            packets.request(),
            packets.listRequest(),
            packets.data(),
            packets.list(),
            packets.save(),
            packets.delete(),
            packets.saveAck()
        );
    }
}
