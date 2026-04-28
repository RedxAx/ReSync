package restudio.resync.flow.handler.generic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import restudio.flow.data.FlowNode;
import restudio.resync.Log;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class DiscordHandler implements NodeHandler {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public DiscordHandler() {
        operations.put("discord_webhook_send", (ctx, node) -> {
            String webhookUrl = ctx.getInputValue(node, "webhook_url", String.class, "");
            String content = ctx.getInputValue(node, "content", String.class, "");
            String username = ctx.getInputValue(node, "username", String.class, null);
            String avatarUrl = ctx.getInputValue(node, "avatar_url", String.class, null);
            Boolean tts = ctx.getInputValue(node, "tts", Boolean.class, false);
            sendAsync(ctx, node, () -> sendWebhook(webhookUrl, content, null, username, avatarUrl, tts, null));
        });
        operations.put("discord_webhook_send_embed", (ctx, node) -> {
            String webhookUrl = ctx.getInputValue(node, "webhook_url", String.class, "");
            Map<String, Object> embed = ctx.getInputValue(node, "embed", Map.class, null);
            sendAsync(ctx, node, () -> sendWebhook(webhookUrl, null, embed, null, null, false, null));
        });
        operations.put("discord_webhook_send_multiple", (ctx, node) -> {
            String webhookUrl = ctx.getInputValue(node, "webhook_url", String.class, "");
            List<Map<String, Object>> messages = ctx.getInputValue(node, "messages", List.class, new ArrayList<>());
            sendAsync(ctx, node, () -> {
                for (Map<String, Object> message : messages) {
                    String content = message.get("content") instanceof String s ? s : null;
                    Object embed = message.get("embed");
                    if (!sendWebhook(webhookUrl, content, embed, null, null, false, null)) {
                        return false;
                    }
                }
                return true;
            });
        });
        operations.put("discord_create_embed", (ctx, node) -> {
            Map<String, Object> embed = new HashMap<>();
            putIfNotBlank(embed, "title", ctx.getInputValue(node, "title", String.class, null));
            putIfNotBlank(embed, "description", ctx.getInputValue(node, "description", String.class, null));
            Integer color = ctx.getInputValue(node, "color", Integer.class, null);
            if (color != null) {
                embed.put("color", color);
            }
            putIfNotBlank(embed, "url", ctx.getInputValue(node, "url", String.class, null));
            setUrlObject(embed, "thumbnail", ctx.getInputValue(node, "thumbnail", String.class, null));
            setUrlObject(embed, "image", ctx.getInputValue(node, "image", String.class, null));
            Map<String, String> author = new HashMap<>();
            putIfNotBlank(author, "name", ctx.getInputValue(node, "author_name", String.class, null));
            putIfNotBlank(author, "url", ctx.getInputValue(node, "author_url", String.class, null));
            putIfNotBlank(author, "icon_url", ctx.getInputValue(node, "author_icon", String.class, null));
            if (!author.isEmpty()) {
                embed.put("author", author);
            }
            Map<String, String> footer = new HashMap<>();
            putIfNotBlank(footer, "text", ctx.getInputValue(node, "footer_text", String.class, null));
            putIfNotBlank(footer, "icon_url", ctx.getInputValue(node, "footer_icon", String.class, null));
            if (!footer.isEmpty()) {
                embed.put("footer", footer);
            }
            putIfNotBlank(embed, "timestamp", ctx.getInputValue(node, "timestamp", String.class, null));
            embed.put("fields", new ArrayList<>());
            ctx.setOutput(node, "embed", embed);
            ctx.triggerOutput("flow");
        });
        operations.put("discord_add_field", (ctx, node) -> {
            Map<String, Object> embed = ctx.getInputValue(node, "embed", Map.class, new HashMap<>());
            Map<String, Object> field = new HashMap<>();
            field.put("name", ctx.getInputValue(node, "name", String.class, ""));
            field.put("value", ctx.getInputValue(node, "value", String.class, ""));
            field.put("inline", ctx.getInputValue(node, "inline", Boolean.class, false));
            List<Map<String, Object>> fields = embed.get("fields") instanceof List<?> list ? (List<Map<String, Object>>) list : new ArrayList<>();
            if (!(embed.get("fields") instanceof List<?>)) {
                embed.put("fields", fields);
            }
            fields.add(field);
            ctx.setOutput(node, "embed", embed);
            ctx.triggerOutput("flow");
        });
        operations.put("discord_add_embed", (ctx, node) -> {
            Map<String, Object> webhookData = ctx.getInputValue(node, "webhook_data", Map.class, new HashMap<>());
            Map<String, Object> embed = ctx.getInputValue(node, "embed", Map.class, null);
            List<Map<String, Object>> embeds = webhookData.get("embeds") instanceof List<?> list ? (List<Map<String, Object>>) list : new ArrayList<>();
            if (!(webhookData.get("embeds") instanceof List<?>)) {
                webhookData.put("embeds", embeds);
            }
            if (embed != null) {
                embeds.add(embed);
            }
            ctx.setOutput(node, "webhook_data", webhookData);
            ctx.triggerOutput("flow");
        });
        operations.put("discord_set_color", (ctx, node) -> {
            Map<String, Object> embed = ctx.getInputValue(node, "embed", Map.class, new HashMap<>());
            Integer color = ctx.getInputValue(node, "color", Integer.class, null);
            if (color != null) {
                embed.put("color", color);
            }
            ctx.setOutput(node, "embed", embed);
            ctx.triggerOutput("flow");
        });
        operations.put("discord_color_from_hex", (ctx, node) -> {
            String hexColor = ctx.getInputValue(node, "hex_color", String.class, "#000000");
            int color = 0;
            try {
                String hex = hexColor.replace("#", "");
                if (hex.length() == 6) {
                    color = Integer.parseInt(hex, 16);
                }
            } catch (Exception ignored) {
            }
            ctx.setOutput(node, "color", color);
            ctx.triggerOutput("flow");
        });
        operations.put("discord_color_from_rgb", (ctx, node) -> {
            int red = ctx.getInputValue(node, "red", Integer.class, 0);
            int green = ctx.getInputValue(node, "green", Integer.class, 0);
            int blue = ctx.getInputValue(node, "blue", Integer.class, 0);
            ctx.setOutput(node, "color", ((red & 0xFF) << 16) | ((green & 0xFF) << 8) | (blue & 0xFF));
            ctx.triggerOutput("flow");
        });
        operations.put("discord_webhook_edit", (ctx, node) -> {
            String webhookUrl = ctx.getInputValue(node, "webhook_url", String.class, "");
            String messageId = ctx.getInputValue(node, "message_id", String.class, "");
            String content = ctx.getInputValue(node, "content", String.class, null);
            List<Map<String, Object>> embeds = ctx.getInputValue(node, "embeds", List.class, null);
            sendAsync(ctx, node, () -> editWebhookMessage(webhookUrl, messageId, content, embeds));
        });
        operations.put("discord_webhook_delete", (ctx, node) -> {
            String webhookUrl = ctx.getInputValue(node, "webhook_url", String.class, "");
            String messageId = ctx.getInputValue(node, "message_id", String.class, "");
            sendAsync(ctx, node, () -> deleteWebhookMessage(webhookUrl, messageId));
        });
        operations.put("discord_set_thumbnail", (ctx, node) -> {
            Map<String, Object> embed = ctx.getInputValue(node, "embed", Map.class, new HashMap<>());
            setUrlObject(embed, "thumbnail", ctx.getInputValue(node, "url", String.class, null));
            ctx.setOutput(node, "embed", embed);
            ctx.triggerOutput("flow");
        });
        operations.put("discord_set_image", (ctx, node) -> {
            Map<String, Object> embed = ctx.getInputValue(node, "embed", Map.class, new HashMap<>());
            setUrlObject(embed, "image", ctx.getInputValue(node, "url", String.class, null));
            ctx.setOutput(node, "embed", embed);
            ctx.triggerOutput("flow");
        });
        operations.put("discord_set_author", (ctx, node) -> {
            Map<String, Object> embed = ctx.getInputValue(node, "embed", Map.class, new HashMap<>());
            Map<String, String> author = new HashMap<>();
            putIfNotBlank(author, "name", ctx.getInputValue(node, "name", String.class, null));
            putIfNotBlank(author, "url", ctx.getInputValue(node, "url", String.class, null));
            putIfNotBlank(author, "icon_url", ctx.getInputValue(node, "icon_url", String.class, null));
            if (author.isEmpty()) {
                embed.remove("author");
            } else {
                embed.put("author", author);
            }
            ctx.setOutput(node, "embed", embed);
            ctx.triggerOutput("flow");
        });
        operations.put("discord_set_footer", (ctx, node) -> {
            Map<String, Object> embed = ctx.getInputValue(node, "embed", Map.class, new HashMap<>());
            Map<String, String> footer = new HashMap<>();
            putIfNotBlank(footer, "text", ctx.getInputValue(node, "text", String.class, null));
            putIfNotBlank(footer, "icon_url", ctx.getInputValue(node, "icon_url", String.class, null));
            if (footer.isEmpty()) {
                embed.remove("footer");
            } else {
                embed.put("footer", footer);
            }
            ctx.setOutput(node, "embed", embed);
            ctx.triggerOutput("flow");
        });
        operations.put("discord_set_timestamp", (ctx, node) -> {
            Map<String, Object> embed = ctx.getInputValue(node, "embed", Map.class, new HashMap<>());
            String timestamp = ctx.getInputValue(node, "timestamp", String.class, null);
            if (timestamp == null || timestamp.isBlank()) {
                embed.remove("timestamp");
            } else {
                embed.put("timestamp", timestamp);
            }
            ctx.setOutput(node, "embed", embed);
            ctx.triggerOutput("flow");
        });
        operations.put("discord_create_webhook_data", (ctx, node) -> {
            Map<String, Object> webhookData = new HashMap<>();
            putIfNotBlank(webhookData, "content", ctx.getInputValue(node, "content", String.class, null));
            putIfNotBlank(webhookData, "username", ctx.getInputValue(node, "username", String.class, null));
            putIfNotBlank(webhookData, "avatar_url", ctx.getInputValue(node, "avatar_url", String.class, null));
            webhookData.put("tts", ctx.getInputValue(node, "tts", Boolean.class, false));
            webhookData.put("embeds", new ArrayList<>());
            ctx.setOutput(node, "webhook_data", webhookData);
            ctx.triggerOutput("flow");
        });
        operations.put("discord_send_webhook_data", (ctx, node) -> {
            String webhookUrl = ctx.getInputValue(node, "webhook_url", String.class, "");
            Map<String, Object> webhookData = ctx.getInputValue(node, "webhook_data", Map.class, new HashMap<>());
            sendAsync(ctx, node, () -> sendWebhook(
                webhookUrl,
                webhookData.get("content") instanceof String s ? s : null,
                webhookData.get("embeds"),
                webhookData.get("username") instanceof String s ? s : null,
                webhookData.get("avatar_url") instanceof String s ? s : null,
                webhookData.get("tts") instanceof Boolean b && b,
                null
            ));
        });
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("DiscordHandler", this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op != null) {
            op.accept(ctx, node);
        }
    }

    private void sendAsync(FlowContext ctx, FlowNode node, java.util.function.Supplier<Boolean> call) {
        ctx.runAsync(() -> {
            boolean success = call.get();
            ctx.runSync(() -> {
                ctx.setOutput(node, "success", success);
                ctx.triggerOutput("flow");
            });
        });
    }

    private boolean sendWebhook(String webhookUrl, String content, Object embedOrEmbeds, String username, String avatarUrl, boolean tts, String messageId) {
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
            JsonArray embeds = toEmbeds(embedOrEmbeds);
            if (embeds != null && embeds.size() > 0) {
                payload.add("embeds", embeds);
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

    private JsonArray toEmbeds(Object embedOrEmbeds) {
        if (embedOrEmbeds == null) {
            return null;
        }
        JsonArray embeds = new JsonArray();
        if (embedOrEmbeds instanceof List<?> list) {
            for (Object entry : list) {
                embeds.add(GSON.toJsonTree(entry));
            }
        } else {
            embeds.add(GSON.toJsonTree(embedOrEmbeds));
        }
        return embeds;
    }

    private void setUrlObject(Map<String, Object> embed, String key, String url) {
        if (url == null || url.isBlank()) {
            embed.remove(key);
            return;
        }
        Map<String, String> obj = new HashMap<>();
        obj.put("url", url);
        embed.put(key, obj);
    }

    private void putIfNotBlank(Map<String, ?> map, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        ((Map<String, Object>) map).put(key, value);
    }
}
