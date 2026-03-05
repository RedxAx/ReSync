package restudio.resync.flow.definitions;

import restudio.flow.data.FlowType;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

public class DiscordNodes implements NodeDefinitionCategory {

    @Override
    public void registerNodes(NodeDefinitionRegistry registry) {
        registry.register(new NodeDefinition.Builder("discord_webhook_send", "Discord: Send Simple Webhook", NodeDefinition.NodeCategory.DISCORD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("webhook_url", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("content", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("username", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("avatar_url", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("tts", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("success", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());

        registry.register(new NodeDefinition.Builder("discord_webhook_send_embed", "Discord: Send Embed Webhook", NodeDefinition.NodeCategory.DISCORD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("webhook_url", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("embed", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("success", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());

        registry.register(new NodeDefinition.Builder("discord_webhook_send_multiple", "Discord: Send Multiple Messages", NodeDefinition.NodeCategory.DISCORD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("webhook_url", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("messages", NodeDefinition.PinType.DATA, FlowType.LIST)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("success", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());

        registry.register(new NodeDefinition.Builder("discord_create_embed", "Discord: Create Rich Embed", NodeDefinition.NodeCategory.DISCORD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("title", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("description", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("color", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("url", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("thumbnail", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("image", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("author_name", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("author_url", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("author_icon", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("footer_text", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("footer_icon", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("timestamp", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("embed", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .build());

        registry.register(new NodeDefinition.Builder("discord_add_field", "Discord: Add Embed Field", NodeDefinition.NodeCategory.DISCORD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("embed", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .input("name", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("value", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("inline", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("embed", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .build());

        registry.register(new NodeDefinition.Builder("discord_add_embed", "Discord: Append Embed to Webhook", NodeDefinition.NodeCategory.DISCORD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("webhook_data", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .input("embed", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("webhook_data", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .build());

        registry.register(new NodeDefinition.Builder("discord_set_color", "Discord: Set Embed Color", NodeDefinition.NodeCategory.DISCORD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("embed", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .input("color", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("embed", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .build());

        registry.register(new NodeDefinition.Builder("discord_color_from_hex", "Discord: Hex to Embed Color", NodeDefinition.NodeCategory.DISCORD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("hex_color", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("color", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("discord_color_from_rgb", "Discord: RGB to Embed Color", NodeDefinition.NodeCategory.DISCORD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("red", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("green", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("blue", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("color", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("discord_webhook_edit", "Discord: Edit Webhook Message", NodeDefinition.NodeCategory.DISCORD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("webhook_url", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("message_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("content", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("embeds", NodeDefinition.PinType.DATA, FlowType.LIST)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("success", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());

        registry.register(new NodeDefinition.Builder("discord_webhook_delete", "Discord: Delete Webhook Message", NodeDefinition.NodeCategory.DISCORD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("webhook_url", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("message_id", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("success", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());

        registry.register(new NodeDefinition.Builder("discord_set_thumbnail", "Discord: Set Embed Thumbnail", NodeDefinition.NodeCategory.DISCORD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("embed", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .input("url", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("embed", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .build());

        registry.register(new NodeDefinition.Builder("discord_set_image", "Discord: Set Embed Image", NodeDefinition.NodeCategory.DISCORD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("embed", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .input("url", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("embed", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .build());

        registry.register(new NodeDefinition.Builder("discord_set_author", "Discord: Set Embed Author", NodeDefinition.NodeCategory.DISCORD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("embed", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .input("name", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("url", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("icon_url", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("embed", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .build());

        registry.register(new NodeDefinition.Builder("discord_set_footer", "Discord: Set Embed Footer", NodeDefinition.NodeCategory.DISCORD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("embed", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .input("text", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("icon_url", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("embed", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .build());

        registry.register(new NodeDefinition.Builder("discord_set_timestamp", "Discord: Set Embed Timestamp", NodeDefinition.NodeCategory.DISCORD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("embed", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .input("timestamp", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("embed", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .build());

        registry.register(new NodeDefinition.Builder("discord_create_webhook_data", "Discord: Create Webhook Data", NodeDefinition.NodeCategory.DISCORD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("content", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("username", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("avatar_url", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("tts", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("webhook_data", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .build());

        registry.register(new NodeDefinition.Builder("discord_send_webhook_data", "Discord: Send Webhook Data", NodeDefinition.NodeCategory.DISCORD)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("webhook_url", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("webhook_data", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("success", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());
    }
}
