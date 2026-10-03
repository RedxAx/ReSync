package restudio.resync.flow.handler.generic;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class HttpHandlerRequestTest {
    private HttpServer server;
    private HttpHandler handler;
    private ExecutorService serverWorkers;
    private String url;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverWorkers = Executors.newFixedThreadPool(16);
        server.setExecutor(serverWorkers);
        handler = new HttpHandler();
        url = "http://127.0.0.1:" + server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void tearDown() {
        handler.shutdown();
        server.stop(0);
        serverWorkers.shutdownNow();
    }

    @Test
    void queryParametersRemainBeforeTheFragmentAndPreserveExistingQuery() {
        AtomicReference<String> request = new AtomicReference<>();
        server.createContext("/echo", exchange -> {
            request.set(exchange.getRequestURI().toString());
            byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        Context context = execute("http_request", Map.of("url", url + "/echo?before=1#section", "query_params", Map.of("a", "hello world")));

        assertEquals("/echo?before=1&a=hello+world", request.get());
        assertEquals(true, context.outputs.get("success"));
        assertEquals("flow", context.branch);
    }

    @Test
    void anExplicitContentTypeIsSentOnce() {
        AtomicReference<List<String>> contentTypes = new AtomicReference<>();
        server.createContext("/post", exchange -> {
            contentTypes.set(exchange.getRequestHeaders().get("Content-Type"));
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });

        Context context = execute("http_post", Map.of("url", url + "/post", "headers", Map.of("content-type", "application/problem+json"), "body", Map.of("ok", true)));

        assertEquals(List.of("application/problem+json"), contentTypes.get());
        assertEquals(true, context.outputs.get("success"));
    }

    @Test
    void oversizedChunkedResponsesFailAndTheNextRequestStillSucceeds() {
        server.createContext("/large", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            byte[] block = new byte[16 * 1024];
            try {
                for (int i = 0; i < 300; i++) {
                    exchange.getResponseBody().write(block);
                }
            } catch (IOException ignored) {
            } finally {
                exchange.close();
            }
        });
        server.createContext("/small", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });

        Context failed = execute("http_get", Map.of("url", url + "/large"));
        Context next = execute("http_get", Map.of("url", url + "/small"));

        assertEquals(false, failed.outputs.get("success"));
        assertEquals("HTTP_RESPONSE_TOO_LARGE", failed.outputs.get("error_code"));
        assertEquals("failed", failed.branch);
        assertEquals(true, next.outputs.get("success"));
        assertEquals("flow", next.branch);
    }

    @Test
    void oversizedRequestBodiesHaveAnExplicitFailure() {
        Context context = execute("http_post", Map.of("url", url + "/unused", "body", Map.of("text", "x".repeat(4 * 1024 * 1024))));

        assertEquals(false, context.outputs.get("success"));
        assertEquals("HTTP_REQUEST_TOO_LARGE", context.outputs.get("error_code"));
        assertEquals("failed", context.branch);
    }

    @Test
    void shutdownPreventsFurtherRequestsAndDoesNotRecreateTheClient() {
        handler.shutdown();

        Context context = execute("http_get", Map.of("url", url + "/unused"));

        assertEquals(false, context.outputs.get("success"));
        assertEquals("failed", context.branch);
        assertTrue(context.outputs.get("message").toString().contains("shut down"));
    }

    @Test
    void methodsAreStableAcrossLocalesAndUrlBuildingKeepsFragmentsLast() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("DELETE", handler.validateMethod("delete"));
            Context context = execute("http_build_url", Map.of("base_url", url + "/echo?before=1#part", "path", "child", "query", "a=2"));
            assertEquals(url + "/echo/child?before=1&a=2#part", context.outputs.get("url"));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void aBodyThatStallsAfterHeadersStillHonorsTheRequestDeadline() {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        warmClient();
        server.createContext("/stall", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('x');
            exchange.getResponseBody().flush();
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        try {
            Context context = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> execute("http_get", Map.of("url", url + "/stall", "timeout_ms", 200)));
            assertEquals(false, context.outputs.get("success"));
            assertEquals(0L, started.getCount());
            assertEquals("HTTP_TIMEOUT", context.outputs.get("error_code"));
            assertEquals("failed", context.branch);
        } finally {
            release.countDown();
        }
    }

    @Test
    void concurrentRequestsHaveBoundedAdmissionAndRecoverAfterCompletion() throws Exception {
        CountDownLatch started = new CountDownLatch(16);
        CountDownLatch release = new CountDownLatch(1);
        warmClient();
        server.createContext("/concurrent", exchange -> {
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
                exchange.sendResponseHeaders(204, -1);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Context>> active = IntStream.range(0, 16)
                .mapToObj(index -> CompletableFuture.supplyAsync(() -> execute("http_get", Map.of("url", url + "/concurrent", "timeout_ms", 10000)), callers)).toList();
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                Context busy = execute("http_get", Map.of("url", url + "/concurrent"));
                assertEquals("HTTP_BUSY", busy.outputs.get("error_code"));
                assertEquals("failed", busy.branch);
            } finally {
                release.countDown();
            }
            for (CompletableFuture<Context> operation : active) {
                assertEquals(true, operation.get(5, TimeUnit.SECONDS).outputs.get("success"));
            }
        }
        assertEquals(true, execute("http_get", Map.of("url", url + "/warm")).outputs.get("success"));
    }

    @Test
    void shutdownSettlesAnActiveStalledBody() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        warmClient();
        server.createContext("/shutdown", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('x');
            exchange.getResponseBody().flush();
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<Context> active = CompletableFuture.supplyAsync(() -> execute("http_get", Map.of("url", url + "/shutdown", "timeout_ms", 10000)), callers);
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                handler.shutdown();
                Context context = active.get(2, TimeUnit.SECONDS);
                assertEquals(false, context.outputs.get("success"));
                assertEquals("failed", context.branch);
            } finally {
                release.countDown();
            }
        }
    }

    private void warmClient() {
        server.createContext("/warm", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        assertEquals(true, execute("http_get", Map.of("url", url + "/warm")).outputs.get("success"));
    }

    private Context execute(String operation, Map<String, Object> inputs) {
        FlowNode node = new FlowNode(operation, 0, 0, Map.of());
        node.setHandlerConfig(Map.of("operation", operation));
        Context context = new Context(inputs);
        handler.execute(context, node);
        return context;
    }

    private static final class Context extends FlowContext {
        private final Map<String, Object> inputs;
        private final Map<String, Object> outputs = new HashMap<>();
        private String branch;

        private Context(Map<String, Object> inputs) {
            super(null, null, null);
            this.inputs = inputs;
        }

        @Override
        public <T> T getInputValue(FlowNode node, String name, Class<T> type, T fallback) {
            Object value = inputs.get(name);
            return value == null ? fallback : type.cast(value);
        }

        @Override
        public void setOutput(FlowNode node, String name, Object value) {
            outputs.put(name, value);
        }

        @Override
        public void triggerOutput(String name) {
            branch = name;
        }

        @Override
        public CompletableFuture<Void> runAsync(Runnable action) {
            action.run();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> runSync(Runnable action) {
            return runAsync(action);
        }
    }
}
