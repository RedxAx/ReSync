package restudio.resync.flow.handler.generic;

import com.google.gson.Gson;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowOperationResult;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class HttpHandler implements NodeHandler {
    private static final Gson GSON = new Gson();
    private static final int DEFAULT_TIMEOUT_MS = 10000;
    private static final Set<String> SUPPORTED_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();
    private volatile int legacyTimeoutMs = DEFAULT_TIMEOUT_MS;

    public HttpHandler() {
        operations.put("http_get", (ctx, node) -> executeRequest(ctx, node, "GET", null, null, false));
        operations.put("http_post", (ctx, node) -> executeRequest(ctx, node, "POST", null, null, false));
        operations.put("http_put", (ctx, node) -> executeRequest(ctx, node, "PUT", null, null, false));
        operations.put("http_delete", (ctx, node) -> executeRequest(ctx, node, "DELETE", null, null, false));
        operations.put("http_patch", (ctx, node) -> executeRequest(ctx, node, "PATCH", null, null, false));
        operations.put("http_request", (ctx, node) -> executeRequest(ctx, node, null, null, null, true));

        operations.put("http_get_status", (ctx, node) -> {
            Map<String, Object> response = ctx.getInputValue(node, "response", Map.class, new HashMap<>());
            Object status = response.get("status_code");
            ctx.setOutput(node, "status_code", status instanceof Number n ? n.intValue() : -1);
            ctx.triggerOutput("flow");
        });
        operations.put("http_get_body", (ctx, node) -> {
            Map<String, Object> response = ctx.getInputValue(node, "response", Map.class, new HashMap<>());
            ctx.setOutput(node, "body", String.valueOf(response.getOrDefault("body", "")));
            ctx.triggerOutput("flow");
        });
        operations.put("http_get_header", (ctx, node) -> {
            Map<String, Object> response = ctx.getInputValue(node, "response", Map.class, new HashMap<>());
            String headerName = ctx.getInputValue(node, "header_name", String.class, "");
            Map<String, String> headers = response.get("headers") instanceof Map<?, ?> map ? castStringMap(map) : new HashMap<>();
            String value = "";
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(headerName)) {
                    value = entry.getValue();
                    break;
                }
            }
            ctx.setOutput(node, "header_value", value);
            ctx.triggerOutput("flow");
        });
        operations.put("http_get_headers", (ctx, node) -> {
            Map<String, Object> response = ctx.getInputValue(node, "response", Map.class, new HashMap<>());
            Object headers = response.get("headers");
            ctx.setOutput(node, "headers", headers instanceof Map<?, ?> map ? castStringMap(map) : new HashMap<>());
            ctx.triggerOutput("flow");
        });
        operations.put("http_is_success", (ctx, node) -> {
            Map<String, Object> response = ctx.getInputValue(node, "response", Map.class, new HashMap<>());
            Object status = response.get("status_code");
            int code = status instanceof Number n ? n.intValue() : -1;
            ctx.setOutput(node, "success", code >= 200 && code < 300);
            ctx.triggerOutput("flow");
        });
        operations.put("http_parse_json", (ctx, node) -> {
            Map<String, Object> response = ctx.getInputValue(node, "response", Map.class, new HashMap<>());
            String body = String.valueOf(response.getOrDefault("body", ""));
            Object json = new HashMap<>();
            try {
                if (!body.isBlank()) {
                    json = GSON.fromJson(body, Object.class);
                }
                ctx.setOutput(node, "valid", true);
                ctx.setOutput(node, "error", "");
            } catch (RuntimeException exception) {
                ctx.setOutput(node, "valid", false);
                ctx.setOutput(node, "error", message(exception, "Invalid JSON response"));
            }
            ctx.setOutput(node, "json", json);
            ctx.triggerOutput("flow");
        });
        operations.put("http_build_query", (ctx, node) -> {
            Map<String, Object> params = ctx.getInputValue(node, "params", Map.class, new HashMap<>());
            ctx.setOutput(node, "query_string", buildQueryStringFromParams(params));
            ctx.triggerOutput("flow");
        });
        operations.put("http_build_url", (ctx, node) -> {
            String baseUrl = ctx.getInputValue(node, "base_url", String.class, "");
            String path = ctx.getInputValue(node, "path", String.class, "");
            String query = ctx.getInputValue(node, "query", String.class, "");
            String builtUrl = baseUrl;
            if (!path.isEmpty()) {
                if (!builtUrl.endsWith("/") && !path.startsWith("/")) {
                    builtUrl += "/";
                }
                builtUrl += path;
            }
            if (!query.isEmpty()) {
                builtUrl += (builtUrl.contains("?") ? "&" : "?") + query;
            }
            ctx.setOutput(node, "url", builtUrl);
            ctx.triggerOutput("flow");
        });
        operations.put("http_set_timeout", (ctx, node) -> {
            int timeout = ctx.getInputValue(node, "timeout_ms", Integer.class, DEFAULT_TIMEOUT_MS);
            boolean valid = timeout >= 100 && timeout <= 120_000;
            if (valid) {
                legacyTimeoutMs = timeout;
            }
            ctx.setOutput(node, "success", valid);
            ctx.setOutput(node, "error_code", valid ? "" : "HTTP_TIMEOUT_INVALID");
            ctx.setOutput(node, "message", valid ? "" : "HTTP timeout must be between 100 and 120000 milliseconds");
            ctx.triggerOutput(valid ? "flow" : "failed");
        });
        operations.put("http_encode_url", (ctx, node) -> {
            String url = ctx.getInputValue(node, "url", String.class, "");
            ctx.setOutput(node, "encoded_url", URLEncoder.encode(url, StandardCharsets.UTF_8));
            ctx.triggerOutput("flow");
        });
        operations.put("http_decode_url", (ctx, node) -> {
            String url = ctx.getInputValue(node, "url", String.class, "");
            String decoded = url;
            try {
                decoded = URLDecoder.decode(url, StandardCharsets.UTF_8);
                ctx.setOutput(node, "valid", true);
                ctx.setOutput(node, "error", "");
            } catch (IllegalArgumentException exception) {
                ctx.setOutput(node, "valid", false);
                ctx.setOutput(node, "error", message(exception, "Invalid URL encoding"));
            }
            ctx.setOutput(node, "decoded_url", decoded);
            ctx.triggerOutput("flow");
        });
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("HttpHandler", this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op == null) {
            throw new IllegalArgumentException("Unknown HTTP operation: " + operation);
        }
        op.accept(ctx, node);
    }

    private void executeRequest(FlowContext ctx, FlowNode node, String fixedMethod, Map<String, Object> fixedBody, Map<String, Object> fixedHeaders, boolean dynamic) {
        String method = dynamic ? ctx.getInputValue(node, "method", String.class, "GET") : fixedMethod;
        String url = ctx.getInputValue(node, "url", String.class, "");
        Map<String, Object> body = fixedBody != null ? fixedBody : ctx.getInputValue(node, "body", Map.class, null);
        Map<String, Object> headers = fixedHeaders != null ? fixedHeaders : ctx.getInputValue(node, "headers", Map.class, new HashMap<>());
        Map<String, Object> queryParams = dynamic ? ctx.getInputValue(node, "query_params", Map.class, null) : null;
        int timeout = ctx.getInputValue(node, "timeout_ms", Integer.class, legacyTimeoutMs);

        ctx.runAsync(() -> {
            FlowOperationResult<Map<String, Object>> result;
            Map<String, Object> response = Map.of();
            String normalizedMethod = method != null ? method.toUpperCase() : "";
            String finalUrl = url != null ? url : "";
            try {
                normalizedMethod = validateMethod(method);
                finalUrl = queryParams != null && !queryParams.isEmpty() ? buildQueryString(url, queryParams) : url;
                response = makeHttpRequest(normalizedMethod, finalUrl, body, headers, validateTimeout(timeout));
                int status = ((Number) response.getOrDefault("status_code", -1)).intValue();
                result = status >= 200 && status < 300
                    ? new FlowOperationResult<>(true, response, "", "", Map.of("method", normalizedMethod, "url", finalUrl, "status", status))
                    : FlowOperationResult.failure("HTTP_STATUS_ERROR", "HTTP request returned status " + status,
                        Map.of("method", normalizedMethod, "url", finalUrl, "status", status));
            } catch (Exception exception) {
                String error = message(exception, "HTTP request failed");
                response = createErrorResponse(error);
                result = FlowOperationResult.failure(httpErrorCode(exception), error, Map.of("method", normalizedMethod, "url", finalUrl));
            }
            Map<String, Object> finalResponse = response;
            FlowOperationResult<Map<String, Object>> finalResult = result;
            ctx.runSync(() -> {
                ctx.setOutput(node, "response", finalResponse);
                setResult(ctx, node, finalResult);
                ctx.triggerOutput(finalResult.success() ? "flow" : "failed");
            });
        });
    }

    private Map<String, Object> makeHttpRequest(String method, String url, Map<String, Object> body, Map<String, Object> headers, int timeoutMs) throws Exception {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("HTTP URL is required");
        }

        URI uri = validateUri(url);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build();
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder().uri(uri).timeout(Duration.ofMillis(timeoutMs));

        if (headers != null) {
            for (Map.Entry<String, Object> entry : headers.entrySet()) {
                if (entry.getValue() != null) {
                    requestBuilder.header(entry.getKey(), entry.getValue().toString());
                }
            }
        }

        if (!"GET".equalsIgnoreCase(method) && !"DELETE".equalsIgnoreCase(method) && body != null) {
            String jsonBody = GSON.toJson(body);
            requestBuilder.header("Content-Type", "application/json");
            requestBuilder.method(method.toUpperCase(), HttpRequest.BodyPublishers.ofString(jsonBody));
        } else {
            requestBuilder.method(method.toUpperCase(), HttpRequest.BodyPublishers.noBody());
        }

        HttpResponse<String> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
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
        responseMap.put("success", response.statusCode() >= HttpURLConnection.HTTP_OK && response.statusCode() < 300);
        return responseMap;
    }

    URI validateUri(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("HTTP URL is required");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("HTTP URL is invalid", exception);
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("HTTP URL must use http or https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("HTTP URL host is required");
        }
        return uri;
    }

    String validateMethod(String method) {
        String normalized = method != null ? method.trim().toUpperCase() : "";
        if (!SUPPORTED_METHODS.contains(normalized)) {
            throw new IllegalArgumentException("HTTP method must be GET, POST, PUT, PATCH, or DELETE");
        }
        return normalized;
    }

    int validateTimeout(int timeout) {
        if (timeout < 100 || timeout > 120_000) {
            throw new IllegalArgumentException("HTTP timeout must be between 100 and 120000 milliseconds");
        }
        return timeout;
    }

    private void setResult(FlowContext context, FlowNode node, FlowOperationResult<Map<String, Object>> result) {
        context.setOutput(node, "result", result);
        context.setOutput(node, "success", result.success());
        context.setOutput(node, "error_code", result.errorCode());
        context.setOutput(node, "message", result.message());
    }

    private String buildQueryString(String url, Map<String, Object> params) {
        String query = buildQueryStringFromParams(params);
        if (query.isEmpty()) {
            return url;
        }
        return url.contains("?") ? url + "&" + query : url + "?" + query;
    }

    private String buildQueryStringFromParams(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (!first) {
                builder.append('&');
            }
            builder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            builder.append('=');
            builder.append(URLEncoder.encode(entry.getValue() != null ? entry.getValue().toString() : "", StandardCharsets.UTF_8));
            first = false;
        }
        return builder.toString();
    }

    private Map<String, Object> createErrorResponse(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("status_code", -1);
        response.put("body", GSON.toJson(Map.of("error", message != null ? message : "")));
        response.put("headers", new HashMap<>());
        response.put("success", false);
        response.put("error", message);
        return response;
    }

    private Map<String, String> castStringMap(Map<?, ?> source) {
        Map<String, String> target = new HashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            target.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }
        return target;
    }

    private String message(Exception exception, String fallback) {
        return exception.getMessage() != null && !exception.getMessage().isBlank() ? exception.getMessage() : fallback;
    }

    private String httpErrorCode(Exception exception) {
        String error = message(exception, "");
        if (error.startsWith("HTTP URL")) {
            return "HTTP_URL_INVALID";
        }
        if (error.startsWith("HTTP method")) {
            return "HTTP_METHOD_INVALID";
        }
        if (error.startsWith("HTTP timeout")) {
            return "HTTP_TIMEOUT_INVALID";
        }
        return "HTTP_REQUEST_FAILED";
    }
}
