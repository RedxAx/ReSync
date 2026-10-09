package restudio.resync.flow.handler.generic;

import com.google.gson.Gson;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowOperationResult;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

public class HttpHandler implements NodeHandler {
    private static final Gson GSON = new Gson();
    private static final int DEFAULT_TIMEOUT_MS = 10000;
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;
    private static final Set<String> SUPPORTED_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();
    private final Semaphore requests = new Semaphore(16);
    private volatile int legacyTimeoutMs = DEFAULT_TIMEOUT_MS;
    private HttpClient client;
    private boolean stopped;

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
                builtUrl = appendPath(builtUrl, path);
            }
            if (!query.isEmpty()) {
                builtUrl = appendQuery(builtUrl, query);
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
            String normalizedMethod = method != null ? method.toUpperCase(Locale.ROOT) : "";
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
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
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

    Map<String, Object> makeHttpRequest(String method, String url, Object body, Map<String, Object> headers, int timeoutMs) throws Exception {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("HTTP URL is required");
        }

        URI uri = validateUri(url);
        if (!requests.tryAcquire()) {
            throw new IOException("HTTP request limit reached; retry after a running request finishes");
        }
        HttpResponse<String> response;
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder().uri(uri).timeout(Duration.ofMillis(timeoutMs));
            if (headers != null) {
                for (Map.Entry<String, Object> entry : headers.entrySet()) {
                    if (entry.getValue() != null) {
                        requestBuilder.header(entry.getKey(), entry.getValue().toString());
                    }
                }
            }
            if (!"GET".equalsIgnoreCase(method) && !"DELETE".equalsIgnoreCase(method) && body != null) {
                byte[] jsonBody = boundedBody(body);
                if (headers == null || headers.keySet().stream().noneMatch(key -> "Content-Type".equalsIgnoreCase(key))) {
                    requestBuilder.header("Content-Type", "application/json");
                }
                requestBuilder.method(method.toUpperCase(Locale.ROOT), HttpRequest.BodyPublishers.ofByteArray(jsonBody));
            } else {
                requestBuilder.method(method.toUpperCase(Locale.ROOT), HttpRequest.BodyPublishers.noBody());
            }
            HttpClient current = client();
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            AtomicReference<LimitedBody> receiving = new AtomicReference<>();
            try {
                response = current.send(requestBuilder.build(), info -> {
                    LimitedBody bodyReader = new LimitedBody(HttpResponse.BodyHandlers.ofString().apply(info), deadline);
                    receiving.set(bodyReader);
                    return bodyReader;
                });
            } catch (IOException failure) {
                LimitedBody bodyReader = receiving.get();
                IOException limitFailure = bodyReader != null ? bodyReader.limitFailure() : null;
                if (limitFailure != null) throw limitFailure;
                throw failure;
            }
        } finally {
            requests.release();
        }
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
        String normalized = method != null ? method.trim().toUpperCase(Locale.ROOT) : "";
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
        return appendQuery(url, query);
    }

    private String appendPath(String url, String path) {
        int query = url.indexOf('?');
        int fragment = url.indexOf('#');
        int split = query < 0 ? fragment : fragment < 0 ? query : Math.min(query, fragment);
        String base = split < 0 ? url : url.substring(0, split);
        String suffix = split < 0 ? "" : url.substring(split);
        if (base.endsWith("/") && path.startsWith("/")) {
            path = path.substring(1);
        } else if (!base.endsWith("/") && !path.startsWith("/")) {
            base += "/";
        }
        return base + path + suffix;
    }

    private byte[] boundedBody(Object body) throws IOException {
        LimitedOutput output = new LimitedOutput();
        try (Writer writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
            GSON.toJson(body, writer);
        }
        return output.bytes.toByteArray();
    }

    private String appendQuery(String url, String query) {
        int fragment = url.indexOf('#');
        String base = fragment < 0 ? url : url.substring(0, fragment);
        String suffix = fragment < 0 ? "" : url.substring(fragment);
        String separator = base.endsWith("?") || base.endsWith("&") ? "" : base.contains("?") ? "&" : "?";
        return base + separator + query + suffix;
    }

    private synchronized HttpClient client() {
        if (stopped) {
            throw new IllegalStateException("HTTP handler is shut down");
        }
        if (client == null) {
            client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(DEFAULT_TIMEOUT_MS)).build();
        }
        return client;
    }

    @Override
    public synchronized void shutdown() {
        stopped = true;
        if (client != null) {
            client.shutdownNow();
            client = null;
        }
    }

    private static final class BodyLimitException extends IOException {
        private BodyLimitException() {
            super("HTTP response body exceeds 4 MiB");
        }
    }

    private static final class RequestLimitException extends IOException {
        private RequestLimitException() {
            super("HTTP request body exceeds 4 MiB");
        }
    }

    private static final class LimitedOutput extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        @Override
        public void write(int value) throws IOException {
            if (bytes.size() == MAX_BODY_BYTES) {
                throw new RequestLimitException();
            }
            bytes.write(value);
        }

        @Override
        public void write(byte[] source, int offset, int length) throws IOException {
            if (length > MAX_BODY_BYTES - bytes.size()) {
                throw new RequestLimitException();
            }
            bytes.write(source, offset, length);
        }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<String> {
        private final HttpResponse.BodySubscriber<String> delegate;
        private final CompletableFuture<String> body = new CompletableFuture<>();
        private final CompletableFuture<Void> alarm = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private long received;
        private boolean finished;
        private IOException limitFailure;

        private LimitedBody(HttpResponse.BodySubscriber<String> delegate, long deadline) {
            this.delegate = delegate;
            delegate.getBody().whenComplete((value, failure) -> {
                if (failure == null) body.complete(value);
                else body.completeExceptionally(failure);
                alarm.complete(null);
            });
            alarm.orTimeout(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS).whenComplete((unused, failure) -> {
                if (failure != null) {
                    fail(new HttpTimeoutException("HTTP response body timed out"));
                }
            });
        }

        @Override
        public CompletionStage<String> getBody() {
            return body;
        }

        @Override
        public synchronized void onSubscribe(Flow.Subscription subscription) {
            if (finished) {
                subscription.cancel();
                return;
            }
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }

        @Override
        public synchronized void onNext(List<ByteBuffer> buffers) {
            if (finished) {
                return;
            }
            for (ByteBuffer buffer : buffers) {
                received += buffer.remaining();
                if (received > MAX_BODY_BYTES) {
                    fail(new BodyLimitException());
                    return;
                }
            }
            delegate.onNext(buffers);
        }

        private synchronized void fail(Throwable failure) {
            if (finished) {
                return;
            }
            finished = true;
            if (failure instanceof HttpTimeoutException timeout) limitFailure = timeout;
            else if (failure instanceof BodyLimitException limit) limitFailure = limit;
            body.completeExceptionally(failure);
            try {
                delegate.onError(failure);
            } finally {
                if (subscription != null) subscription.cancel();
            }
        }

        private synchronized IOException limitFailure() {
            return limitFailure;
        }

        @Override
        public void onError(Throwable failure) {
            fail(failure);
        }

        @Override
        public synchronized void onComplete() {
            if (!finished) {
                finished = true;
                delegate.onComplete();
            }
        }
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
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof BodyLimitException) {
                return "HTTP_RESPONSE_TOO_LARGE";
            }
            if (cause instanceof RequestLimitException) {
                return "HTTP_REQUEST_TOO_LARGE";
            }
            if (cause instanceof HttpTimeoutException) {
                return "HTTP_TIMEOUT";
            }
        }
        if (exception instanceof InterruptedException) {
            return "HTTP_REQUEST_CANCELLED";
        }
        String error = message(exception, "");
        if (error.startsWith("HTTP request body")) {
            return "HTTP_REQUEST_TOO_LARGE";
        }
        if (error.startsWith("HTTP request limit")) {
            return "HTTP_BUSY";
        }
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
