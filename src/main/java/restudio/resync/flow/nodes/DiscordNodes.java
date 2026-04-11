package restudio.resync.flow.nodes;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import restudio.resync.Log;
import org.bukkit.Bukkit;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DiscordNodes implements NodeCategory {
    
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
    @Override
    public void registerNodes(FlowRegistry registry) {
        registry.register("discord_webhook_send", (ctx, node) -> {
            String webhookUrl = ctx.getInputValue(node, "webhook_url", String.class, "");
            String content = ctx.getInputValue(node, "content", String.class, "");
            String username = ctx.getInputValue(node, "username", String.class, null);
            String avatarUrl = ctx.getInputValue(node, "avatar_url", String.class, null);
            Boolean tts = ctx.getInputValue(node, "tts", Boolean.class, false);
            
            ctx.runAsync(() -> {
                boolean success = sendWebhook(webhookUrl, content, null, username, avatarUrl, tts, null);
                String nodeId = findNodeId(ctx, node);
                ctx.runSync(() -> {
                    ctx.setNodeOutput(nodeId, "success", success);
                    ctx.triggerOutput("flow");
                });
            });
        });
        
        registry.register("discord_webhook_send_embed", (ctx, node) -> {
            String webhookUrl = ctx.getInputValue(node, "webhook_url", String.class, "");
            Map<String, Object> embed = ctx.getInputValue(node, "embed", Map.class, null);
            
            ctx.runAsync(() -> {
                boolean success = sendWebhook(webhookUrl, null, embed, null, null, false, null);
                String nodeId = findNodeId(ctx, node);
                ctx.runSync(() -> {
                    ctx.setNodeOutput(nodeId, "success", success);
                    ctx.triggerOutput("flow");
                });
            });
        });
        
        registry.register("discord_webhook_send_multiple", (ctx, node) -> {
            String webhookUrl = ctx.getInputValue(node, "webhook_url", String.class, "");
            List<Map<String, Object>> messages = ctx.getInputValue(node, "messages", List.class, new ArrayList<>());
            
            ctx.runAsync(() -> {
                boolean allSuccess = true;
                for (Map<String, Object> message : messages) {
                    String content = (String) message.get("content");
                    Map<String, Object> embed = (Map<String, Object>) message.get("embed");
                    if (!sendWebhook(webhookUrl, content, embed, null, null, false, null)) {
                        allSuccess = false;
                        break;
                    }
                }
                
                final boolean finalSuccess = allSuccess;
                String nodeId = findNodeId(ctx, node);
                ctx.runSync(() -> {
                    ctx.setNodeOutput(nodeId, "success", finalSuccess);
                    ctx.triggerOutput("flow");
                });
            });
        });
        
        registry.register("discord_create_embed", (ctx, node) -> {
            String title = ctx.getInputValue(node, "title", String.class, null);
            String description = ctx.getInputValue(node, "description", String.class, null);
            Integer color = ctx.getInputValue(node, "color", Integer.class, null);
            String url = ctx.getInputValue(node, "url", String.class, null);
            String thumbnail = ctx.getInputValue(node, "thumbnail", String.class, null);
            String image = ctx.getInputValue(node, "image", String.class, null);
            String authorName = ctx.getInputValue(node, "author_name", String.class, null);
            String authorUrl = ctx.getInputValue(node, "author_url", String.class, null);
            String authorIcon = ctx.getInputValue(node, "author_icon", String.class, null);
            String footerText = ctx.getInputValue(node, "footer_text", String.class, null);
            String footerIcon = ctx.getInputValue(node, "footer_icon", String.class, null);
            String timestamp = ctx.getInputValue(node, "timestamp", String.class, null);
            
            Map<String, Object> embed = new HashMap<>();
            
            if (title != null && !title.isEmpty()) embed.put("title", title);
            if (description != null && !description.isEmpty()) embed.put("description", description);
            if (color != null) embed.put("color", color);
            if (url != null && !url.isEmpty()) embed.put("url", url);
            
            if (thumbnail != null && !thumbnail.isEmpty()) {
                Map<String, String> thumbnailObj = new HashMap<>();
                thumbnailObj.put("url", thumbnail);
                embed.put("thumbnail", thumbnailObj);
            }
            
            if (image != null && !image.isEmpty()) {
                Map<String, String> imageObj = new HashMap<>();
                imageObj.put("url", image);
                embed.put("image", imageObj);
            }
            
            if (authorName != null || authorUrl != null || authorIcon != null) {
                Map<String, String> authorObj = new HashMap<>();
                if (authorName != null) authorObj.put("name", authorName);
                if (authorUrl != null) authorObj.put("url", authorUrl);
                if (authorIcon != null) authorObj.put("icon_url", authorIcon);
                embed.put("author", authorObj);
            }
            
            if (footerText != null || footerIcon != null) {
                Map<String, String> footerObj = new HashMap<>();
                if (footerText != null) footerObj.put("text", footerText);
                if (footerIcon != null) footerObj.put("icon_url", footerIcon);
                embed.put("footer", footerObj);
            }
            
            if (timestamp != null && !timestamp.isEmpty()) {
                embed.put("timestamp", timestamp);
            }
            
            embed.put("fields", new ArrayList<>());
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "embed", embed);
            ctx.triggerOutput("flow");
        });
        
        registry.register("discord_add_field", (ctx, node) -> {
            Map<String, Object> embed = ctx.getInputValue(node, "embed", Map.class, new HashMap<>());
            String name = ctx.getInputValue(node, "name", String.class, "");
            String value = ctx.getInputValue(node, "value", String.class, "");
            Boolean inline = ctx.getInputValue(node, "inline", Boolean.class, false);
            
            Map<String, Object> field = new HashMap<>();
            field.put("name", name);
            field.put("value", value);
            field.put("inline", inline);
            
            List<Map<String, Object>> fields = (List<Map<String, Object>>) embed.get("fields");
            if (fields == null) {
                fields = new ArrayList<>();
                embed.put("fields", fields);
            }
            fields.add(field);
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "embed", embed);
            ctx.triggerOutput("flow");
        });
        
        registry.register("discord_add_embed", (ctx, node) -> {
            Map<String, Object> webhookData = ctx.getInputValue(node, "webhook_data", Map.class, new HashMap<>());
            Map<String, Object> embed = ctx.getInputValue(node, "embed", Map.class, null);
            
            List<Map<String, Object>> embeds = (List<Map<String, Object>>) webhookData.get("embeds");
            if (embeds == null) {
                embeds = new ArrayList<>();
                webhookData.put("embeds", embeds);
            }
            
            if (embed != null) {
                embeds.add(embed);
            }
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "webhook_data", webhookData);
            ctx.triggerOutput("flow");
        });
        
        registry.register("discord_set_color", (ctx, node) -> {
            Map<String, Object> embed = ctx.getInputValue(node, "embed", Map.class, new HashMap<>());
            Integer color = ctx.getInputValue(node, "color", Integer.class, null);
            
            if (color != null) {
                embed.put("color", color);
            }
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "embed", embed);
            ctx.triggerOutput("flow");
        });
        
        registry.register("discord_color_from_hex", (ctx, node) -> {
            String hexColor = ctx.getInputValue(node, "hex_color", String.class, "#000000");
            
            int color = 0;
            try {
                String hex = hexColor.replace("#", "");
                if (hex.length() == 6) {
                    color = Integer.parseInt(hex, 16);
                }
            } catch (Exception e) {
                Log.warn("[Flow] Invalid hex color: " + hexColor);
            }
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "color", color);
            ctx.triggerOutput("flow");
        });
        
        registry.register("discord_color_from_rgb", (ctx, node) -> {
            Integer red = ctx.getInputValue(node, "red", Integer.class, 0);
            Integer green = ctx.getInputValue(node, "green", Integer.class, 0);
            Integer blue = ctx.getInputValue(node, "blue", Integer.class, 0);
            
            int color = ((red & 0xFF) << 16) | ((green & 0xFF) << 8) | (blue & 0xFF);
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "color", color);
            ctx.triggerOutput("flow");
        });
        
        registry.register("discord_webhook_edit", (ctx, node) -> {
            String webhookUrl = ctx.getInputValue(node, "webhook_url", String.class, "");
            String messageId = ctx.getInputValue(node, "message_id", String.class, "");
            String content = ctx.getInputValue(node, "content", String.class, null);
            List<Map<String, Object>> embeds = ctx.getInputValue(node, "embeds", List.class, null);
            
            ctx.runAsync(() -> {
                boolean success = editWebhookMessage(webhookUrl, messageId, content, embeds);
                String nodeId = findNodeId(ctx, node);
                ctx.runSync(() -> {
                    ctx.setNodeOutput(nodeId, "success", success);
                    ctx.triggerOutput("flow");
                });
            });
        });
        
        registry.register("discord_webhook_delete", (ctx, node) -> {
            String webhookUrl = ctx.getInputValue(node, "webhook_url", String.class, "");
            String messageId = ctx.getInputValue(node, "message_id", String.class, "");
            
            ctx.runAsync(() -> {
                boolean success = deleteWebhookMessage(webhookUrl, messageId);
                String nodeId = findNodeId(ctx, node);
                ctx.runSync(() -> {
                    ctx.setNodeOutput(nodeId, "success", success);
                    ctx.triggerOutput("flow");
                });
            });
        });
        
        registry.register("discord_set_thumbnail", (ctx, node) -> {
            Map<String, Object> embed = ctx.getInputValue(node, "embed", Map.class, new HashMap<>());
            String url = ctx.getInputValue(node, "url", String.class, null);
            
            if (url != null && !url.isEmpty()) {
                Map<String, String> thumbnail = new HashMap<>();
                thumbnail.put("url", url);
                embed.put("thumbnail", thumbnail);
            } else {
                embed.remove("thumbnail");
            }
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "embed", embed);
            ctx.triggerOutput("flow");
        });
        
        registry.register("discord_set_image", (ctx, node) -> {
            Map<String, Object> embed = ctx.getInputValue(node, "embed", Map.class, new HashMap<>());
            String url = ctx.getInputValue(node, "url", String.class, null);
            
            if (url != null && !url.isEmpty()) {
                Map<String, String> image = new HashMap<>();
                image.put("url", url);
                embed.put("image", image);
            } else {
                embed.remove("image");
            }
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "embed", embed);
            ctx.triggerOutput("flow");
        });
        
        registry.register("discord_set_author", (ctx, node) -> {
            Map<String, Object> embed = ctx.getInputValue(node, "embed", Map.class, new HashMap<>());
            String name = ctx.getInputValue(node, "name", String.class, null);
            String url = ctx.getInputValue(node, "url", String.class, null);
            String iconUrl = ctx.getInputValue(node, "icon_url", String.class, null);
            
            if (name != null || url != null || iconUrl != null) {
                Map<String, String> author = new HashMap<>();
                if (name != null) author.put("name", name);
                if (url != null) author.put("url", url);
                if (iconUrl != null) author.put("icon_url", iconUrl);
                embed.put("author", author);
            } else {
                embed.remove("author");
            }
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "embed", embed);
            ctx.triggerOutput("flow");
        });
        
        registry.register("discord_set_footer", (ctx, node) -> {
            Map<String, Object> embed = ctx.getInputValue(node, "embed", Map.class, new HashMap<>());
            String text = ctx.getInputValue(node, "text", String.class, null);
            String iconUrl = ctx.getInputValue(node, "icon_url", String.class, null);
            
            if (text != null || iconUrl != null) {
                Map<String, String> footer = new HashMap<>();
                if (text != null) footer.put("text", text);
                if (iconUrl != null) footer.put("icon_url", iconUrl);
                embed.put("footer", footer);
            } else {
                embed.remove("footer");
            }
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "embed", embed);
            ctx.triggerOutput("flow");
        });
        
        registry.register("discord_set_timestamp", (ctx, node) -> {
            Map<String, Object> embed = ctx.getInputValue(node, "embed", Map.class, new HashMap<>());
            String timestamp = ctx.getInputValue(node, "timestamp", String.class, null);
            
            if (timestamp != null && !timestamp.isEmpty()) {
                embed.put("timestamp", timestamp);
            } else {
                embed.remove("timestamp");
            }
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "embed", embed);
            ctx.triggerOutput("flow");
        });
        
        registry.register("discord_create_webhook_data", (ctx, node) -> {
            String content = ctx.getInputValue(node, "content", String.class, null);
            String username = ctx.getInputValue(node, "username", String.class, null);
            String avatarUrl = ctx.getInputValue(node, "avatar_url", String.class, null);
            Boolean tts = ctx.getInputValue(node, "tts", Boolean.class, false);
            
            Map<String, Object> webhookData = new HashMap<>();
            
            if (content != null) webhookData.put("content", content);
            if (username != null) webhookData.put("username", username);
            if (avatarUrl != null) webhookData.put("avatar_url", avatarUrl);
            webhookData.put("tts", tts);
            webhookData.put("embeds", new ArrayList<>());
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "webhook_data", webhookData);
            ctx.triggerOutput("flow");
        });
        
        registry.register("discord_send_webhook_data", (ctx, node) -> {
            String webhookUrl = ctx.getInputValue(node, "webhook_url", String.class, "");
            Map<String, Object> webhookData = ctx.getInputValue(node, "webhook_data", Map.class, new HashMap<>());
            
            ctx.runAsync(() -> {
                String content = (String) webhookData.get("content");
                List<Map<String, Object>> embeds = (List<Map<String, Object>>) webhookData.get("embeds");
                String username = (String) webhookData.get("username");
                String avatarUrl = (String) webhookData.get("avatar_url");
                Boolean tts = (Boolean) webhookData.get("tts");
                
                boolean success = sendWebhook(webhookUrl, content, embeds, username, avatarUrl, tts != null ? tts : false, null);
                
                String nodeId = findNodeId(ctx, node);
                ctx.runSync(() -> {
                    ctx.setNodeOutput(nodeId, "success", success);
                    ctx.triggerOutput("flow");
                });
            });
        });
    }
    
    private boolean sendWebhook(String webhookUrl, String content, Object embed, String username, String avatarUrl, boolean tts, String messageId) {
        try {
            URL url = new URL(webhookUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setDoOutput(true);
            
            JsonObject payload = new JsonObject();
            
            if (content != null && !content.isEmpty()) {
                payload.addProperty("content", content);
            }
            if (embed != null) {
                payload.add("embeds", GSON.toJsonTree(embed));
            }
            if (username != null && !username.isEmpty()) {
                payload.addProperty("username", username);
            }
            if (avatarUrl != null && !avatarUrl.isEmpty()) {
                payload.addProperty("avatar_url", avatarUrl);
            }
            payload.addProperty("tts", tts);
            
            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = payload.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }
            
            int responseCode = connection.getResponseCode();
            return responseCode >= 200 && responseCode < 300;
        } catch (Exception e) {
            Log.error("[Flow] Discord webhook error: " + e.getMessage());
            return false;
        }
    }
    
    private boolean editWebhookMessage(String webhookUrl, String messageId, String content, List<Map<String, Object>> embeds) {
        try {
            URL url = new URL(webhookUrl + "/messages/" + messageId);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("PATCH");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setDoOutput(true);
            
            JsonObject payload = new JsonObject();
            
            if (content != null && !content.isEmpty()) {
                payload.addProperty("content", content);
            }
            if (embeds != null && !embeds.isEmpty()) {
                payload.add("embeds", GSON.toJsonTree(embeds));
            }
            
            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = payload.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }
            
            int responseCode = connection.getResponseCode();
            return responseCode >= 200 && responseCode < 300;
        } catch (Exception e) {
            Log.error("[Flow] Discord webhook edit error: " + e.getMessage());
            return false;
        }
    }
    
    private boolean deleteWebhookMessage(String webhookUrl, String messageId) {
        try {
            URL url = new URL(webhookUrl + "/messages/" + messageId);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("DELETE");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            
            int responseCode = connection.getResponseCode();
            return responseCode >= 200 && responseCode < 300;
        } catch (Exception e) {
            Log.error("[Flow] Discord webhook delete error: " + e.getMessage());
            return false;
        }
    }
    
    private static String findNodeId(FlowContext ctx, FlowNode node) {
        for (var entry : ctx.getRuntime().getGraph().getNodes().entrySet()) {
            if (entry.getValue() == node) {
                return entry.getKey();
            }
        }
        return null;
    }
}
