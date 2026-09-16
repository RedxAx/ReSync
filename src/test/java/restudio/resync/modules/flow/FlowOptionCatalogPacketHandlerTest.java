package restudio.resync.modules.flow;

import org.junit.jupiter.api.Test;
import restudio.resync.api.OptionCatalogCapture;
import restudio.resync.api.OptionCatalogItem;
import restudio.resync.api.OptionCatalogProvider;
import restudio.resync.api.OptionCatalogQuery;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.core.Session;
import restudio.resync.server.OptionCatalogCaptureExecutor;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowOptionCatalogPacketHandlerTest {
    @Test
    void requestBroadcastAndCustomSnapshotUseTheInjectedCaptureBoundary() {
        OptionCatalogRegistry registry = new OptionCatalogRegistry();
        registry.register(provider("server:test:main", "test", OptionCatalogProvider.CaptureAffinity.SERVER_MAIN));
        registry.register(provider("server:test:io", "test", OptionCatalogProvider.CaptureAffinity.IO));
        registry.register(provider("server:test:custom", "custom_content", OptionCatalogProvider.CaptureAffinity.SERVER_MAIN));
        List<String> captures = new ArrayList<>();
        OptionCatalogCaptureExecutor executor = new OptionCatalogCaptureExecutor() {
            @Override
            public OptionCatalogCapture capture(OptionCatalogProvider provider, OptionCatalogQuery query) {
                captures.add("sync:" + provider.sourceId() + ":" + provider.captureAffinity());
                return FlowOptionCatalogPacketHandlerTest.capture(provider.sourceId());
            }

            @Override
            public java.util.concurrent.CompletionStage<OptionCatalogCapture> captureAsync(OptionCatalogProvider provider,
                                                                                           OptionCatalogQuery query) {
                captures.add("async:" + provider.sourceId() + ":" + provider.captureAffinity());
                return CompletableFuture.completedFuture(FlowOptionCatalogPacketHandlerTest.capture(provider.sourceId()));
            }
        };
        RecordingSender sender = new RecordingSender();
        FlowOptionCatalogPacketHandler handler = new FlowOptionCatalogPacketHandler(sender, registry, null, executor);

        handler.handle(new Session("session", "client", null), request("server:test:main"));
        handler.broadcastCatalog("server:test:io");
        handler.broadcastCustomContentCatalogs();

        assertEquals(List.of(
            "sync:server:test:main:SERVER_MAIN",
            "async:server:test:io:IO",
            "sync:server:test:custom:SERVER_MAIN"
        ), captures);
        assertEquals(1, sender.direct);
        assertEquals(2, sender.broadcasts);
    }

    @Test
    void boundedExecutorCancelsTimedOutFuturesAndRejectsAFullQueue() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        OptionCatalogProvider provider = new OptionCatalogProvider() {
            @Override
            public String sourceId() {
                return "server:test:hung";
            }

            @Override
            public CaptureAffinity captureAffinity() {
                return CaptureAffinity.IO;
            }

            @Override
            public OptionCatalogCapture capture(OptionCatalogQuery query) {
                attempts.incrementAndGet();
                running.countDown();
                while (true) {
                    try {
                        release.await();
                        break;
                    } catch (InterruptedException ignored) {
                    }
                }
                return FlowOptionCatalogPacketHandlerTest.capture(sourceId());
            }

            @Override
            public String revision() {
                throw new AssertionError("Legacy revision must not be called");
            }

            @Override
            public List<String> values() {
                throw new AssertionError("Legacy values must not be called");
            }
        };
        OptionCatalogCaptureExecutor.Bounded executor = OptionCatalogCaptureExecutor.bounded(1, Duration.ofMillis(75),
            () -> false, Runnable::run, runnable -> {
                Thread thread = new Thread(runnable, "option-test");
                thread.setDaemon(true);
                return thread;
            });
        try {
            CompletableFuture<OptionCatalogCapture> first = executor.captureAsync(provider,
                new OptionCatalogQuery(provider.sourceId(), Map.of())).toCompletableFuture();
            assertTrue(running.await(1, TimeUnit.SECONDS));
            CompletableFuture<OptionCatalogCapture> queued = executor.captureAsync(provider,
                new OptionCatalogQuery(provider.sourceId(), Map.of())).toCompletableFuture();

            assertThrows(OptionCatalogCaptureExecutor.CaptureUnavailable.class,
                () -> executor.captureAsync(provider, new OptionCatalogQuery(provider.sourceId(), Map.of())));
            ExecutionException firstFailure = assertThrows(ExecutionException.class,
                () -> first.get(1, TimeUnit.SECONDS));
            ExecutionException queuedFailure = assertThrows(ExecutionException.class,
                () -> queued.get(1, TimeUnit.SECONDS));
            assertInstanceOf(OptionCatalogCaptureExecutor.CaptureTimeout.class, firstFailure.getCause());
            assertInstanceOf(OptionCatalogCaptureExecutor.CaptureTimeout.class, queuedFailure.getCause());
            assertEquals(1, attempts.get());
        } finally {
            release.countDown();
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    private static OptionCatalogProvider provider(String sourceId, String providerId,
                                                   OptionCatalogProvider.CaptureAffinity affinity) {
        return new OptionCatalogProvider() {
            @Override
            public String sourceId() {
                return sourceId;
            }

            @Override
            public String providerId() {
                return providerId;
            }

            @Override
            public CaptureAffinity captureAffinity() {
                return affinity;
            }

            @Override
            public OptionCatalogCapture capture(OptionCatalogQuery query) {
                throw new AssertionError("Provider capture must be routed through the injected boundary");
            }

            @Override
            public String revision() {
                throw new AssertionError("Legacy revision must not be called");
            }

            @Override
            public List<String> values() {
                throw new AssertionError("Legacy values must not be called");
            }
        };
    }

    private static OptionCatalogCapture capture(String sourceId) {
        return new OptionCatalogCapture(sourceId + ":revision", List.of(new OptionCatalogItem("value")), "available", "");
    }

    private static ByteBuffer request(String sourceId) {
        byte[] source = sourceId.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(Integer.BYTES + source.length + Integer.BYTES)
            .putInt(source.length).put(source).putInt(0).flip();
    }

    private static final class RecordingSender extends FlowPacketSender {
        private int direct;
        private int broadcasts;

        private RecordingSender() {
            super(null, 1, Set.of());
        }

        @Override
        public void sendOptionCatalog(Session session, String sourceId, String contextKey, List<String> values,
                                      List<OptionCatalogItem> items, String revision, long sequence,
                                      String status, String diagnostic) {
            direct++;
        }

        @Override
        public void broadcastOptionCatalog(String sourceId, List<String> values, List<OptionCatalogItem> items,
                                           String revision, long sequence, String status, String diagnostic) {
            broadcasts++;
        }
    }
}
