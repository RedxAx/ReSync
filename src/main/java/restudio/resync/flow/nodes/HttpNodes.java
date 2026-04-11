package restudio.resync.flow.nodes;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import restudio.resync.Log;
import org.bukkit.Bukkit;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class HttpNodes implements NodeCategory {
    
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();
    private static final int DEFAULT_TIMEOUT_MS = 10000;
    private int timeoutMs = DEFAULT_TIMEOUT_MS;
    
    @Override
    public void registerNodes(FlowRegistry registry) {
        registry.register("http_get", (ctx, node) -> {
            String url = ctx.getInputValue(node, "url", String.class, "");
            Map<String, Object> headers = ctx.getInputValue(node, "headers", Map.class, new HashMap<>());
            
            ctx.runAsync(() -> {
                try {
                    Map<String, Object> response = makeHttpRequest("GET", url, null, headers);
                    String nodeId = findNodeId(ctx, node);
                    ctx.runSync(() -> {
                        ctx.setNodeOutput(nodeId, "response", response);
                        ctx.triggerOutput("flow");
                    });
                } catch (Exception e) {
                    Log.error("[Flow] HTTP GET error: " + e.getMessage());
                    String nodeId = findNodeId(ctx, node);
                    ctx.runSync(() -> {
                        ctx.setNodeOutput(nodeId, "response", createErrorResponse(e.getMessage()));
                        ctx.triggerOutput("flow");
                    });
                }
            });
        });
        
        registry.register("http_post", (ctx, node) -> {
            String url = ctx.getInputValue(node, "url", String.class, "");
            Map<String, Object> body = ctx.getInputValue(node, "body", Map.class, new HashMap<>());
            Map<String, Object> headers = ctx.getInputValue(node, "headers", Map.class, new HashMap<>());
            
            ctx.runAsync(() -> {
                try {
                    Map<String, Object> response = makeHttpRequest("POST", url, body, headers);
                    String nodeId = findNodeId(ctx, node);
                    ctx.runSync(() -> {
                        ctx.setNodeOutput(nodeId, "response", response);
                        ctx.triggerOutput("flow");
                    });
                } catch (Exception e) {
                    Log.error("[Flow] HTTP POST error: " + e.getMessage());
                    String nodeId = findNodeId(ctx, node);
                    ctx.runSync(() -> {
                        ctx.setNodeOutput(nodeId, "response", createErrorResponse(e.getMessage()));
                        ctx.triggerOutput("flow");
                    });
                }
            });
        });
        
        registry.register("http_put", (ctx, node) -> {
            String url = ctx.getInputValue(node, "url", String.class, "");
            Map<String, Object> body = ctx.getInputValue(node, "body", Map.class, new HashMap<>());
            Map<String, Object> headers = ctx.getInputValue(node, "headers", Map.class, new HashMap<>());
            
            ctx.runAsync(() -> {
                try {
                    Map<String, Object> response = makeHttpRequest("PUT", url, body, headers);
                    String nodeId = findNodeId(ctx, node);
                    ctx.runSync(() -> {
                        ctx.setNodeOutput(nodeId, "response", response);
                        ctx.triggerOutput("flow");
                    });
                } catch (Exception e) {
                    Log.error("[Flow] HTTP PUT error: " + e.getMessage());
                    String nodeId = findNodeId(ctx, node);
                    ctx.runSync(() -> {
                        ctx.setNodeOutput(nodeId, "response", createErrorResponse(e.getMessage()));
                        ctx.triggerOutput("flow");
                    });
                }
            });
        });
        
        registry.register("http_delete", (ctx, node) -> {
            String url = ctx.getInputValue(node, "url", String.class, "");
            Map<String, Object> headers = ctx.getInputValue(node, "headers", Map.class, new HashMap<>());
            
            ctx.runAsync(() -> {
                try {
                    Map<String, Object> response = makeHttpRequest("DELETE", url, null, headers);
                    String nodeId = findNodeId(ctx, node);
                    ctx.runSync(() -> {
                        ctx.setNodeOutput(nodeId, "response", response);
                        ctx.triggerOutput("flow");
                    });
                } catch (Exception e) {
                    Log.error("[Flow] HTTP DELETE error: " + e.getMessage());
                    String nodeId = findNodeId(ctx, node);
                    ctx.runSync(() -> {
                        ctx.setNodeOutput(nodeId, "response", createErrorResponse(e.getMessage()));
                        ctx.triggerOutput("flow");
                    });
                }
            });
        });
        
        registry.register("http_patch", (ctx, node) -> {
            String url = ctx.getInputValue(node, "url", String.class, "");
            Map<String, Object> body = ctx.getInputValue(node, "body", Map.class, new HashMap<>());
            Map<String, Object> headers = ctx.getInputValue(node, "headers", Map.class, new HashMap<>());
            
            ctx.runAsync(() -> {
                try {
                    Map<String, Object> response = makeHttpRequest("PATCH", url, body, headers);
                    String nodeId = findNodeId(ctx, node);
                    ctx.runSync(() -> {
                        ctx.setNodeOutput(nodeId, "response", response);
                        ctx.triggerOutput("flow");
                    });
                } catch (Exception e) {
                    Log.error("[Flow] HTTP PATCH error: " + e.getMessage());
                    String nodeId = findNodeId(ctx, node);
                    ctx.runSync(() -> {
                        ctx.setNodeOutput(nodeId, "response", createErrorResponse(e.getMessage()));
                        ctx.triggerOutput("flow");
                    });
                }
            });
        });
        
        registry.register("http_request", (ctx, node) -> {
            String method = ctx.getInputValue(node, "method", String.class, "GET").toUpperCase();
            String url = ctx.getInputValue(node, "url", String.class, "");
            Map<String, Object> body = ctx.getInputValue(node, "body", Map.class, null);
            Map<String, Object> headers = ctx.getInputValue(node, "headers", Map.class, new HashMap<>());
            Map<String, Object> queryParams = ctx.getInputValue(node, "query_params", Map.class, null);
            
            String finalUrl;
            if (queryParams != null && !queryParams.isEmpty()) {
                finalUrl = buildQueryString(url, queryParams);
            } else {
                finalUrl = url;
            }
            
            ctx.runAsync(() -> {
                try {
                    Map<String, Object> response = makeHttpRequest(method, finalUrl, body, headers);
                    String nodeId = findNodeId(ctx, node);
                    ctx.runSync(() -> {
                        ctx.setNodeOutput(nodeId, "response", response);
                        ctx.triggerOutput("flow");
                    });
                } catch (Exception e) {
                    Log.error("[Flow] HTTP REQUEST error: " + e.getMessage());
                    String nodeId = findNodeId(ctx, node);
                    ctx.runSync(() -> {
                        ctx.setNodeOutput(nodeId, "response", createErrorResponse(e.getMessage()));
                        ctx.triggerOutput("flow");
                    });
                }
            });
        });
        
        registry.register("http_get_status", (ctx, node) -> {
            Map<String, Object> response = ctx.getInputValue(node, "response", Map.class, new HashMap<>());
            Integer status = (Integer) response.get("status_code");
            if (status == null) status = -1;
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "status_code", status);
            ctx.triggerOutput("flow");
        });
        
        registry.register("http_get_body", (ctx, node) -> {
            Map<String, Object> response = ctx.getInputValue(node, "response", Map.class, new HashMap<>());
            String body = (String) response.get("body");
            if (body == null) body = "";
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "body", body);
            ctx.triggerOutput("flow");
        });
        
        registry.register("http_get_header", (ctx, node) -> {
            Map<String, Object> response = ctx.getInputValue(node, "response", Map.class, new HashMap<>());
            String headerName = ctx.getInputValue(node, "header_name", String.class, "");
            Map<String, String> headers = (Map<String, String>) response.get("headers");
            
            String headerValue = "";
            if (headers != null && headerName != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    if (entry.getKey().equalsIgnoreCase(headerName)) {
                        headerValue = entry.getValue();
                        break;
                    }
                }
            }
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "header_value", headerValue);
            ctx.triggerOutput("flow");
        });
        
        registry.register("http_get_headers", (ctx, node) -> {
            Map<String, Object> response = ctx.getInputValue(node, "response", Map.class, new HashMap<>());
            Map<String, String> headers = (Map<String, String>) response.get("headers");
            if (headers == null) headers = new HashMap<>();
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "headers", headers);
            ctx.triggerOutput("flow");
        });
        
        registry.register("http_is_success", (ctx, node) -> {
            Map<String, Object> response = ctx.getInputValue(node, "response", Map.class, new HashMap<>());
            Integer status = (Integer) response.get("status_code");
            boolean success = status != null && status >= 200 && status < 300;
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "success", success);
            ctx.triggerOutput("flow");
        });
        
        registry.register("http_parse_json", (ctx, node) -> {
            Map<String, Object> response = ctx.getInputValue(node, "response", Map.class, new HashMap<>());
            String body = (String) response.get("body");
            
            Object json = null;
            try {
                if (body != null && !body.isBlank()) {
                    json = GSON.fromJson(body, Object.class);
                }
            } catch (Exception e) {
                json = new HashMap<>();
            }
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "json", json);
            ctx.triggerOutput("flow");
        });
        
        registry.register("http_build_query", (ctx, node) -> {
            Map<String, Object> params = ctx.getInputValue(node, "params", Map.class, new HashMap<>());
            String queryString = buildQueryStringFromParams(params);
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "query_string", queryString);
            ctx.triggerOutput("flow");
        });
        
        registry.register("http_build_url", (ctx, node) -> {
            String baseUrl = ctx.getInputValue(node, "base_url", String.class, "");
            String path = ctx.getInputValue(node, "path", String.class, "");
            String query = ctx.getInputValue(node, "query", String.class, "");
            
            String builtUrl = baseUrl;
            if (path != null && !path.isEmpty()) {
                if (!builtUrl.endsWith("/") && !path.startsWith("/")) {
                    builtUrl += "/";
                }
                builtUrl += path;
            }
            if (query != null && !query.isEmpty()) {
                builtUrl += "?" + query;
            }
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "url", builtUrl);
            ctx.triggerOutput("flow");
        });
        
        registry.register("http_set_timeout", (ctx, node) -> {
            Integer timeout = ctx.getInputValue(node, "timeout_ms", Integer.class, DEFAULT_TIMEOUT_MS);
            timeoutMs = timeout;
            ctx.triggerOutput("flow");
        });
        
        registry.register("http_encode_url", (ctx, node) -> {
            String url = ctx.getInputValue(node, "url", String.class, "");
            String encoded = URLEncoder.encode(url, StandardCharsets.UTF_8);
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "encoded_url", encoded);
            ctx.triggerOutput("flow");
        });
        
        registry.register("http_decode_url", (ctx, node) -> {
            String url = ctx.getInputValue(node, "url", String.class, "");
            String decoded;
            try {
                decoded = java.net.URLDecoder.decode(url, StandardCharsets.UTF_8);
            } catch (Exception e) {
                decoded = url;
            }
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "decoded_url", decoded);
            ctx.triggerOutput("flow");
        });
    }
    
    private Map<String, Object> makeHttpRequest(String method, String url, Map<String, Object> body, Map<String, Object> headers) throws Exception {
        if (url == null || url.isEmpty()) {
            return createErrorResponse("Empty URL");
        }
        
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(timeoutMs))
            .build();
        
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(new URL(url).toURI())
            .timeout(Duration.ofMillis(timeoutMs));
        
        for (Map.Entry<String, Object> entry : headers.entrySet()) {
            if (entry.getValue() != null) {
                requestBuilder.header(entry.getKey(), entry.getValue().toString());
            }
        }
        
        if (!"GET".equalsIgnoreCase(method) && !"DELETE".equalsIgnoreCase(method) && body != null) {
            String jsonBody = GSON.toJson(body);
            requestBuilder.header("Content-Type", "application/json");
            requestBuilder.method(method.toUpperCase(), HttpRequest.BodyPublishers.ofString(jsonBody));
        } else {
            requestBuilder.method(method.toUpperCase(), HttpRequest.BodyPublishers.noBody());
        }
        
        HttpRequest request = requestBuilder.build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        
        Map<String, Object> responseMap = new HashMap<>();
        responseMap.put("status_code", response.statusCode());
        responseMap.put("body", response.body());
        
        Map<String, String> responseHeaders = new HashMap<>();
        response.headers().map().forEach((key, values) -> {
            if (!values.isEmpty()) {
                responseHeaders.put(key, values.get(0));
            }
        });
        responseMap.put("headers", responseHeaders);
        responseMap.put("success", response.statusCode() >= 200 && response.statusCode() < 300);
        
        return responseMap;
    }
    
    private String buildQueryString(String url, Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return url;
        }
        String query = buildQueryStringFromParams(params);
        if (url.contains("?")) {
            return url + "&" + query;
        }
        return url + "?" + query;
    }
    
    private String buildQueryStringFromParams(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (!first) {
                sb.append("&");
            }
            try {
                sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
                sb.append("=");
                sb.append(URLEncoder.encode(entry.getValue() != null ? entry.getValue().toString() : "", StandardCharsets.UTF_8));
            } catch (Exception e) {
                Log.warn("[Flow] Failed to encode query parameter: " + e.getMessage());
            }
            first = false;
        }
        return sb.toString();
    }
    
    private Map<String, Object> createErrorResponse(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("status_code", -1);
        response.put("body", "{\"error\": \"" + message + "\"}");
        response.put("headers", new HashMap<>());
        response.put("success", false);
        response.put("error", message);
        return response;
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
