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
    public static final String CHAT_CHANNEL = "chat_channel";
    public static final String CHAT_FORMAT = "chat_format";
    public static final String CHAT_RULE = "chat_rule";
    public static final String PRIVATE_MESSAGE_FORMAT = "private_message_format";
    public static final String MENTION_STYLE = "mention_style";
    public static final String IGNORE_LIST = "ignore_list";
    public static final String MOTD_PROFILE = "motd_profile";
    public static final String MESSAGE_RULE = "message_rule";
    public static final String RECIPE_DEFINITION = "recipe_definition";
    public static final String TEXT_TEMPLATE = "text_template";
    public static final String ADVANCEMENT_TREE = "advancement_tree";
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
        register(new ReSyncManagedResource(CHAT_CHANNEL, "Chat Channel", "Customization/Chat", new ReSyncManagedResource.FlowPackets((byte) 0x67, (byte) 0x68, (byte) 0x69, (byte) 0x6A, (byte) 0x6B, (byte) 0x6C, (byte) 0x6D), true));
        register(new ReSyncManagedResource(CHAT_FORMAT, "Chat Format", "Customization/Chat", new ReSyncManagedResource.FlowPackets((byte) 0x6E, (byte) 0x6F, (byte) 0x70, (byte) 0x71, (byte) 0x72, (byte) 0x73, (byte) 0x74), true));
        register(new ReSyncManagedResource(CHAT_RULE, "Chat Rule", "Customization/Chat", new ReSyncManagedResource.FlowPackets((byte) 0x75, (byte) 0x76, (byte) 0x77, (byte) 0x78, (byte) 0x79, (byte) 0x7A, (byte) 0x7B), true));
        register(new ReSyncManagedResource(PRIVATE_MESSAGE_FORMAT, "Private Message Format", "Customization/Chat", new ReSyncManagedResource.FlowPackets((byte) 0x7C, (byte) 0x7D, (byte) 0x7E, (byte) 0x7F, (byte) 0x80, (byte) 0x81, (byte) 0x82), true));
        register(new ReSyncManagedResource(MENTION_STYLE, "Mention Style", "Customization/Chat", new ReSyncManagedResource.FlowPackets((byte) 0x83, (byte) 0x84, (byte) 0x85, (byte) 0x86, (byte) 0x87, (byte) 0x88, (byte) 0x89), true));
        register(new ReSyncManagedResource(IGNORE_LIST, "Ignore List", "Customization/Chat", new ReSyncManagedResource.FlowPackets((byte) 0x8A, (byte) 0x8B, (byte) 0x8C, (byte) 0x8D, (byte) 0x8E, (byte) 0x8F, (byte) 0x90), true));
        register(new ReSyncManagedResource(MOTD_PROFILE, "MOTD Profile", "Customization/MOTDs", new ReSyncManagedResource.FlowPackets((byte) 0x91, (byte) 0x92, (byte) 0x93, (byte) 0x94, (byte) 0x95, (byte) 0x96, (byte) 0x97), true));
        register(new ReSyncManagedResource(MESSAGE_RULE, "Message Rule", "Customization/Messages", new ReSyncManagedResource.FlowPackets((byte) 0x98, (byte) 0x99, (byte) 0x9A, (byte) 0x9B, (byte) 0x9C, (byte) 0x9D, (byte) 0x9E), true));
        register(new ReSyncManagedResource(RECIPE_DEFINITION, "Recipe Definition", "Content/Recipes", new ReSyncManagedResource.FlowPackets((byte) 0x9F, (byte) 0xA0, (byte) 0xA1, (byte) 0xA2, (byte) 0xA3, (byte) 0xA4, (byte) 0xA5), true));
        register(new ReSyncManagedResource(TEXT_TEMPLATE, "Text Template", "Text/Templates", new ReSyncManagedResource.FlowPackets((byte) 0xA6, (byte) 0xA7, (byte) 0xA8, (byte) 0xA9, (byte) 0xAA, (byte) 0xAB, (byte) 0xAC), true));
        register(new ReSyncManagedResource(ADVANCEMENT_TREE, "Advancement Tree", "Content/Advancements", new ReSyncManagedResource.FlowPackets((byte) 0xAD, (byte) 0xAE, (byte) 0xAF, (byte) 0xB0, (byte) 0xB1, (byte) 0xB2, (byte) 0xB3), true));
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
            BY_FLOW_PACKET.put(resource.flowPackets().data(), resource);
            BY_FLOW_PACKET.put(resource.flowPackets().list(), resource);
            BY_FLOW_PACKET.put(resource.flowPackets().save(), resource);
            BY_FLOW_PACKET.put(resource.flowPackets().delete(), resource);
            BY_FLOW_PACKET.put(resource.flowPackets().saveAck(), resource);
        }
    }
}
