package restudio.resync.modules.flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.compression.CompressionPool;
import restudio.resync.core.ConnectionInfo;
import restudio.resync.core.Session;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCacheEntry;
import restudio.resync.flow.cache.CatalogCacheDefinition;
import restudio.resync.flow.cache.CatalogCacheOpaque;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCachePublicationCodec;
import restudio.resync.flow.cache.CatalogCacheSnapshot;
import restudio.resync.flow.cache.CatalogCachePublicationTransport;
import restudio.resync.flow.catalog.CatalogBindingProof;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogCompiler;
import restudio.resync.flow.catalog.CatalogRuntimeActivation;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.cache.CatalogPublicationReceiptStore;
import restudio.resync.protocol.ReSyncProtocolContract;
import restudio.resync.protocol.FrameSender;
import restudio.resync.protocol.Codec;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.runtime.RuntimeBindingRegistry;
import restudio.resync.flow.runtime.RuntimeRegistrySnapshot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowCatalogPublicationPacketHandlerTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));

    @TempDir
    Path temporary;

    @Test
    void requestEnqueuesCanonicalCorePublication() throws Exception {
        CatalogSnapshot active = CatalogSnapshot.empty(new CatalogVersion(1, 0));
        RecordingSender sender = new RecordingSender();
        FlowCatalogPublicationPacketHandler handler = new FlowCatalogPublicationPacketHandler(sender,
            new CatalogCachePublicationTransport(SERVER, () -> active, Set.of()), receiptStore("request"));

        try {
            handler.handleRequest(new Session("session", "client", null), ByteBuffer.allocate(0));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (sender.payload.length == 0 && System.nanoTime() < deadline) {
                handler.drainReady(System.currentTimeMillis());
                Thread.yield();
            }

            assertEquals(ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION, sender.payload[0],
                () -> handler.lastFailureCode().orElse("No catalog publication failure was recorded"));
            CatalogCachePublication decoded = new CatalogCachePublicationCodec().decodeBytes(bytesAfterPacket(sender.payload));
            assertEquals(active.generation(), decoded.catalogGeneration());
            assertEquals(active.contentChecksum(), decoded.snapshotChecksum());
            assertEquals(decoded.key(), handler.activePublicationKey().orElseThrow());
            assertArrayEquals(new CatalogCachePublicationCodec().encodeBytes(decoded), bytesAfterPacket(sender.payload));
        } finally {
            handler.shutdown();
        }
    }

    @Test
    void requestCoalescesWithAnInFlightInitialTransfer() throws Exception {
        Session session = new Session("session", "client", null);
        CatalogSnapshot active = CatalogSnapshot.empty(new CatalogVersion(1, 0));
        List<Integer> frameIndexes = new ArrayList<>();
        AtomicReference<Integer> preflightCalls = new AtomicReference<>(0);
        FlowPacketSender sender = new FlowPacketSender(null, 0, Set.of(session)) {
            @Override
            public CompletableFuture<Optional<CatalogPublicationSendPlan>> preflightCatalogCachePublicationAsync(
                Session target, CatalogCachePublication publication) {
                preflightCalls.updateAndGet(value -> value + 1);
                List<byte[]> payloads = new ArrayList<>();
                for (int index = 0; index < 20; index++) {
                    payloads.add(new byte[40_000]);
                }
                return CompletableFuture.completedFuture(Optional.of(new CatalogPublicationSendPlan(target, publication, payloads)));
            }

            @Override
            public CatalogFrameSendResult sendCatalogCachePublicationFrameResult(Session target,
                                                                                  CatalogPublicationSendPlan plan,
                                                                                  int frameIndex) {
                frameIndexes.add(frameIndex);
                return CatalogFrameSendResult.success();
            }
        };
        FlowCatalogPublicationPacketHandler handler = new FlowCatalogPublicationPacketHandler(sender,
            new CatalogCachePublicationTransport(SERVER, () -> active, Set.of()), receiptStore("request-coalesce"));
        try {
            assertTrue(handler.enqueueInitial(session));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            int firstDrain = 0;
            while (firstDrain == 0 && System.nanoTime() < deadline) {
                firstDrain = handler.drainReady(System.currentTimeMillis());
                if (firstDrain == 0) {
                    Thread.yield();
                }
            }
            assertEquals(13, firstDrain);
            CatalogCacheKey key = handler.activePublicationKey().orElseThrow();

            handler.handleRequest(session, ByteBuffer.wrap(key.canonicalText().getBytes(StandardCharsets.UTF_8)));
            assertEquals(1, preflightCalls.get());
            assertEquals(7, handler.drainReady(System.currentTimeMillis()));
            assertEquals(20, frameIndexes.size());
            assertTrue(frameIndexes.stream().allMatch(index -> index >= 0 && index < 20));
            assertEquals(frameIndexes.stream().sorted().toList(), frameIndexes);
        } finally {
            handler.shutdown();
        }
    }

    @Test
    void failedFullPublicationSurfacesTheStableTransportDiagnostic() {
        RecordingSender sender = new RecordingSender();
        FlowCatalogPublicationPacketHandler handler = new FlowCatalogPublicationPacketHandler(sender,
            new CatalogCachePublicationTransport(SERVER, () -> null, Set.of()), receiptStore("failed-full"));

        assertFalse(handler.sendFull(new Session("session", "client", null)));

        ByteBuffer payload = ByteBuffer.wrap(sender.payload);
        assertEquals(ReSyncProtocolContract.FLOW_PACKET_ERROR, payload.get());
        byte[] message = new byte[payload.getInt()];
        payload.get(message);
        assertEquals("CATALOG_PUBLICATION.NO_ACTIVE_SNAPSHOT", new String(message, StandardCharsets.UTF_8));
    }

    @Test
    void expectedKeyMismatchDoesNotReplaceLastValidPublication() {
        CatalogSnapshot active = CatalogSnapshot.empty(new CatalogVersion(1, 0));
        RecordingSender sender = new RecordingSender();
        FlowCatalogPublicationPacketHandler handler = new FlowCatalogPublicationPacketHandler(sender,
            new CatalogCachePublicationTransport(SERVER, () -> active, Set.of()), receiptStore("mismatch"));
        handler.sendFull(null);
        CatalogCachePublication previous = handler.lastValidPublication().orElseThrow();
        CatalogCacheKey mismatch = new CatalogCacheKey(SERVER, active.generation() + 1, active.contentChecksum());

        sender.payload = new byte[0];
        handler.handleRequest(null, ByteBuffer.wrap(mismatch.canonicalText().getBytes(StandardCharsets.UTF_8)));

        assertEquals(0, sender.payload.length);
        assertEquals(previous, handler.lastValidPublication().orElseThrow());
    }

    @Test
    void outboundPreflightFailureDiscardsBeforeActivationCommit() {
        RuntimeRegistrySnapshot runtime = RuntimeRegistrySnapshot.empty();
        CatalogSnapshot active = new CatalogCompiler(new CatalogVersion(1, 0), CatalogBindingProof.snapshot(runtime))
            .compile(List.of(), 1).snapshot().orElseThrow();
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(active, runtime);
        FlowPacketSender sender = new FlowPacketSender(null, 0, Set.of()) {
            @Override
            public Optional<FlowPacketSender.CatalogPublicationSendPlan> preflightCatalogCachePublication(
                Session session, CatalogCachePublication publication) {
                return Optional.empty();
            }
        };
        CatalogCachePublicationTransport transport = new CatalogCachePublicationTransport(SERVER, activation, Set.of());
        FlowCatalogPublicationPacketHandler handler = new FlowCatalogPublicationPacketHandler(sender, transport,
            receiptStore("preflight"));

        assertFalse(handler.sendFull(null));
        assertTrue(activation.activePublicationKey().isEmpty());
        assertTrue(transport.lastValidPublication().isEmpty());
    }

    @Test
    void unboundBroadcastEnqueuesOnlyAfterTheActivationCommits() {
        RuntimeRegistrySnapshot runtime = RuntimeRegistrySnapshot.empty();
        CatalogSnapshot active = new CatalogCompiler(new CatalogVersion(1, 0), CatalogBindingProof.snapshot(runtime))
            .compile(List.of(), 1).snapshot().orElseThrow();
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(active, runtime);
        Session session = new Session("session", "client", null);
        List<Boolean> publicationKeyPresentAtSend = new ArrayList<>();
        FlowPacketSender sender = new FlowPacketSender(null, 0, Set.of(session)) {
            @Override
            protected CatalogFrameSendResult sendCatalogFrameResult(Session target, byte[] payload) {
                publicationKeyPresentAtSend.add(activation.activePublicationKey().isPresent());
                return CatalogFrameSendResult.success();
            }
        };
        CatalogCachePublicationTransport transport = new CatalogCachePublicationTransport(SERVER, activation, Set.of());
        FlowCatalogPublicationPacketHandler handler = new FlowCatalogPublicationPacketHandler(sender, transport,
            receiptStore("unbound-broadcast"));

        assertTrue(handler.broadcastFull());
        assertEquals(List.of(true), publicationKeyPresentAtSend);
    }

    @Test
    void deltaRequestPathCarriesTombstonesThroughTheSamePacket() {
        CatalogSnapshot active = CatalogSnapshot.empty(new CatalogVersion(1, 0));
        CatalogBinding binding = new CatalogBinding(active.generation(), active.contentChecksum(),
            active.bindingManifestHash());
        CatalogCacheKey key = new CatalogCacheKey(SERVER, binding, CatalogProjectionVersion.current());
        ContractRef<NodeId> node = ContractRef.of(new OwnerId("resync.packet"), new NodeId("removed"));
        CatalogCacheDefinition definition = CatalogCacheDefinition.opaqueUnavailable(node,
            CatalogCacheOpaque.of("{\"id\":\"removed\"}".getBytes(StandardCharsets.UTF_8)));
        CatalogCacheSnapshot previous = new CatalogCacheSnapshot(key, 1,
            List.of(CatalogCacheEntry.present(node, 1, definition)));
        CatalogCacheSnapshot current = new CatalogCacheSnapshot(key, 2,
            List.of(CatalogCacheEntry.tombstone(node, 2)));
        RecordingSender sender = new RecordingSender();
        FlowCatalogPublicationPacketHandler handler = new FlowCatalogPublicationPacketHandler(sender,
            new CatalogCachePublicationTransport(SERVER, () -> active, Set.of()), receiptStore("delta"));

        assertTrue(handler.sendDelta(null, previous, current));

        CatalogCachePublication decoded = new CatalogCachePublicationCodec().decodeBytes(bytesAfterPacket(sender.payload));
        assertEquals(CatalogCachePublication.Kind.DELTA, decoded.kind());
        assertTrue(decoded.entries().getFirst().tombstone());
    }

    @Test
    void refreshUsesDeltaForAnUnchangedTypedPublicationKey() {
        AtomicReference<CatalogSnapshot> active = new AtomicReference<>(CatalogSnapshot.empty(new CatalogVersion(1, 0)));
        RecordingSender sender = new RecordingSender();
        FlowCatalogPublicationPacketHandler handler = new FlowCatalogPublicationPacketHandler(sender,
            new CatalogCachePublicationTransport(SERVER, active::get, Set.of()), receiptStore("refresh"));

        assertTrue(handler.sendFull(null));
        long previousRevision = handler.lastValidPublication().orElseThrow().revision();
        sender.payload = new byte[0];

        assertTrue(handler.sendRefresh(null));

        CatalogCachePublication decoded = new CatalogCachePublicationCodec().decodeBytes(bytesAfterPacket(sender.payload));
        assertEquals(CatalogCachePublication.Kind.DELTA, decoded.kind());
        assertTrue(decoded.revision() > previousRevision);
        assertEquals(active.get().generation(), decoded.catalogGeneration());
    }

    @Test
    void refreshUsesFullWhenCatalogGenerationChangesAndAuthoringBindingMustFollow() {
        AtomicReference<CatalogSnapshot> active = new AtomicReference<>(CatalogSnapshot.empty(new CatalogVersion(1, 0)));
        RecordingSender sender = new RecordingSender();
        FlowCatalogPublicationPacketHandler handler = new FlowCatalogPublicationPacketHandler(sender,
            new CatalogCachePublicationTransport(SERVER, active::get, Set.of()), receiptStore("activation"));

        assertTrue(handler.sendFull(null));
        active.set(new CatalogCompiler(new CatalogVersion(1, 0)).compile(List.of(), 2).snapshot().orElseThrow());
        sender.payload = new byte[0];

        assertTrue(handler.sendRefresh(null));

        CatalogCachePublication decoded = new CatalogCachePublicationCodec().decodeBytes(bytesAfterPacket(sender.payload));
        assertEquals(CatalogCachePublication.Kind.FULL, decoded.kind());
        assertEquals(2, decoded.catalogGeneration());
        assertEquals(active.get().contentChecksum(), decoded.snapshotChecksum());
    }

    @Test
    void productionRefreshActivationUsesFullWhenAuthoringBindingGenerationChanges() {
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry();
        RuntimeRegistrySnapshot runtime = registry.snapshot();
        CatalogCompiler compiler = new CatalogCompiler(new CatalogVersion(1, 0), CatalogBindingProof.snapshot(runtime));
        CatalogSnapshot initialSnapshot = compiler.compile(List.of(), 1).snapshot().orElseThrow();
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(initialSnapshot, runtime);
        RecordingSender sender = new RecordingSender();
        FlowCatalogPublicationPacketHandler handler = new FlowCatalogPublicationPacketHandler(sender,
            new CatalogCachePublicationTransport(SERVER, activation::catalog, Set.of()), receiptStore("production-refresh"));

        assertTrue(handler.sendFull(null));
        CatalogSnapshot candidate = compiler.compile(List.of(), 2).snapshot().orElseThrow();
        var replacement = registry.prepareReplacement(List.of(), List.of());
        assertTrue(activation.activate(candidate, replacement, null).committed());
        sender.payload = new byte[0];

        assertTrue(handler.sendRefresh(null));

        CatalogCachePublication decoded = new CatalogCachePublicationCodec().decodeBytes(bytesAfterPacket(sender.payload));
        assertEquals(CatalogCachePublication.Kind.FULL, decoded.kind());
        assertEquals(2, decoded.catalogGeneration());
        assertEquals(activation.catalog().bindingManifestHash(), decoded.catalogBinding().bindingManifestHash());
        assertEquals(2, activation.catalog().generation());
        assertEquals(activation.catalog().contentChecksum(), decoded.snapshotChecksum());
    }

    @Test
    void refreshFallsBackToFullWhenTheSnapshotChecksumChanges() {
        AtomicReference<CatalogSnapshot> active = new AtomicReference<>(CatalogSnapshot.empty(new CatalogVersion(1, 0)));
        RecordingSender sender = new RecordingSender();
        FlowCatalogPublicationPacketHandler handler = new FlowCatalogPublicationPacketHandler(sender,
            new CatalogCachePublicationTransport(SERVER, active::get, Set.of()), receiptStore("checksum"));

        assertTrue(handler.sendFull(null));
        active.set(new CatalogCompiler(new CatalogVersion(2, 0)).compile(List.of(), 2).snapshot().orElseThrow());
        sender.payload = new byte[0];

        assertTrue(handler.sendRefresh(null));

        CatalogCachePublication decoded = new CatalogCachePublicationCodec().decodeBytes(bytesAfterPacket(sender.payload));
        assertEquals(CatalogCachePublication.Kind.FULL, decoded.kind());
        assertEquals(2, decoded.catalogGeneration());
        assertEquals(active.get().contentChecksum(), decoded.snapshotChecksum());
    }

    @Test
    void activePublicationKeyNeverReturnsAStaleCommittedPublication() {
        AtomicReference<CatalogSnapshot> active = new AtomicReference<>(CatalogSnapshot.empty(new CatalogVersion(1, 0)));
        FlowCatalogPublicationPacketHandler handler = new FlowCatalogPublicationPacketHandler(new RecordingSender(),
            new CatalogCachePublicationTransport(SERVER, active::get, Set.of()), receiptStore("active-key"));

        assertTrue(handler.sendFull(null));
        CatalogCacheKey previous = handler.activePublicationKey().orElseThrow();
        CatalogSnapshot replacement = new CatalogCompiler(new CatalogVersion(2, 0)).compile(List.of(), 2).snapshot().orElseThrow();
        active.set(replacement);

        CatalogCacheKey current = handler.activePublicationKey().orElseThrow();
        assertFalse(previous.equals(current));
        assertEquals(replacement.generation(), current.catalogGeneration());
        assertEquals(replacement.contentChecksum(), current.snapshotChecksum());
    }

    @Test
    void broadcastDoesNotReportConvergenceWhenOneTargetCannotReceive() {
        CompressionPool compressionPool = new CompressionPool(6, 1);
        try {
            List<byte[]> frames = new ArrayList<>();
            Session unavailable = new Session("unavailable", "client-a", null);
            Session available = new Session("available", "client-b", new ConnectionInfo(null, new FrameSender() {
                @Override
                public void send(byte[] frame) {
                    frames.add(frame);
                }

                @Override
                public void close(int code, String reason) {
                }
            }, 2));
            FlowPacketSender sender = new FlowPacketSender(new Codec(compressionPool), 0, Set.of(unavailable, available));
            CatalogSnapshot active = CatalogSnapshot.empty(new CatalogVersion(1, 0));
            FlowCatalogPublicationPacketHandler handler = new FlowCatalogPublicationPacketHandler(sender,
                new CatalogCachePublicationTransport(SERVER, () -> active, Set.of()), receiptStore("broadcast"));

            assertFalse(handler.broadcastFull());
            assertEquals("CATALOG_PUBLICATION.SEND_UNAVAILABLE", handler.lastFailureCode().orElseThrow());
            assertEquals(1, frames.size());
            assertTrue(handler.lastValidPublication().isPresent());
            assertFalse(handler.broadcastRefresh());
            assertEquals("CATALOG_PUBLICATION.SEND_UNAVAILABLE", handler.lastFailureCode().orElseThrow());
        } finally {
            compressionPool.close();
        }
    }

    @Test
    void failedDeltaRetainsTheCommittedCandidateForReplay() {
        CompressionPool compressionPool = new CompressionPool(6, 1);
        try {
            CatalogSnapshot active = CatalogSnapshot.empty(new CatalogVersion(1, 0));
            CatalogCachePublicationTransport transport = new CatalogCachePublicationTransport(SERVER, () -> active, Set.of());
            CatalogCachePublication committed = transport.publishFull().orElseThrow();
            CatalogCacheSnapshot previous = transport.lastValidProjection().orElseThrow();
            CatalogCacheSnapshot current = new CatalogCacheSnapshot(previous.key(), previous.revision() + 1,
                previous.entries().values());
            Session session = new Session("session", "client", new ConnectionInfo(null, new FrameSender() {
                @Override
                public void send(byte[] frame) {
                    throw new IllegalStateException("closed");
                }

                @Override
                public void close(int code, String reason) {
                }
            }, 1));
            FlowPacketSender sender = new FlowPacketSender(new Codec(compressionPool), 0, Set.of());
            FlowCatalogPublicationPacketHandler handler = new FlowCatalogPublicationPacketHandler(sender, transport,
                receiptStore("failed-delta"));

            assertFalse(handler.sendDelta(session, previous, current));
            assertEquals("CATALOG_PUBLICATION.SEND_FAILED", handler.lastFailureCode().orElseThrow());
            CatalogCachePublication replay = transport.lastValidPublication().orElseThrow();
            assertEquals(CatalogCachePublication.Kind.DELTA, replay.kind());
            assertEquals(committed.revision() + 1, replay.revision());
        } finally {
            compressionPool.close();
        }
    }

    @Test
    void codecFailureDoesNotReportTargetedPublicationAsSent() {
        CompressionPool compressionPool = new CompressionPool(6, 1);
        try {
            Session session = new Session("session", "client", new ConnectionInfo(null, new FrameSender() {
                @Override
                public void send(byte[] frame) {
                    throw new IllegalStateException("closed");
                }

                @Override
                public void close(int code, String reason) {
                }
            }, 1));
            FlowPacketSender sender = new FlowPacketSender(new Codec(compressionPool), 0, Set.of());
            CatalogSnapshot active = CatalogSnapshot.empty(new CatalogVersion(1, 0));
            FlowCatalogPublicationPacketHandler handler = new FlowCatalogPublicationPacketHandler(sender,
                new CatalogCachePublicationTransport(SERVER, () -> active, Set.of()), receiptStore("codec"));

            assertFalse(handler.sendFull(session));
            assertEquals("CATALOG_PUBLICATION.SEND_FAILED", handler.lastFailureCode().orElseThrow());
        } finally {
            compressionPool.close();
        }
    }

    @Test
    void readyDrainRunsOnCallerThreadAndHonorsFrameBudget() throws Exception {
        Set<Session> sessions = Set.of();
        Session session = new Session("session", "client", null);
        CatalogSnapshot active = CatalogSnapshot.empty(new CatalogVersion(1, 0));
        List<Thread> sendingThreads = new CopyOnWriteArrayList<>();
        FlowPacketSender sender = new FlowPacketSender(null, 0, sessions) {
            @Override
            public CompletableFuture<Optional<CatalogPublicationSendPlan>> preflightCatalogCachePublicationAsync(
                Session target, CatalogCachePublication publication) {
                List<byte[]> payloads = new ArrayList<>();
                for (int index = 0; index < 20; index++) {
                    payloads.add(new byte[40_000]);
                }
                return CompletableFuture.completedFuture(Optional.of(new CatalogPublicationSendPlan(target, publication, payloads)));
            }

            @Override
            public CatalogFrameSendResult sendCatalogCachePublicationFrameResult(Session target,
                                                                                  CatalogPublicationSendPlan plan,
                                                                                  int frameIndex) {
                sendingThreads.add(Thread.currentThread());
                return CatalogFrameSendResult.success();
            }
        };
        FlowCatalogPublicationPacketHandler handler = new FlowCatalogPublicationPacketHandler(sender,
            new CatalogCachePublicationTransport(SERVER, () -> active, Set.of()), receiptStore("thread-budget"));
        try {
            assertTrue(handler.enqueueInitial(session));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            int firstDrain = 0;
            while (firstDrain == 0 && System.nanoTime() < deadline) {
                firstDrain = handler.drainReady(0L);
                if (firstDrain == 0) {
                    Thread.yield();
                }
            }
            assertEquals(13, firstDrain);
            assertEquals(7, handler.drainReady(0L));
            assertEquals(20, sendingThreads.size());
            assertTrue(sendingThreads.stream().allMatch(thread -> thread == Thread.currentThread()));
        } finally {
            handler.shutdown();
        }
    }

    @Test
    void readyDrainRetainsExactFrameWhenPreflightOverwritesBackpressureDiagnostic() {
        CompressionPool compressionPool = new CompressionPool(6, 1);
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        List<byte[]> attemptedFrames = new ArrayList<>();
        try {
            FrameSender frameSender = new FrameSender() {
                @Override
                public void send(byte[] frame) {
                    throw new AssertionError("Catalog publication must use admitted transport sends");
                }

                @Override
                public FrameSender.SendResult trySend(byte[] frame) {
                    attemptedFrames.add(frame.clone());
                    return attempts.getAndIncrement() == 0
                        ? FrameSender.SendResult.BACKPRESSURED : FrameSender.SendResult.ACCEPTED;
                }

                @Override
                public void close(int code, String reason) {
                    closes.incrementAndGet();
                }
            };
            Session session = new Session("session", "client", new ConnectionInfo(null, frameSender, 1));
            FlowPacketSender sender = new FlowPacketSender(new Codec(compressionPool), 0, Set.of(session)) {
                @Override
                public CatalogFrameSendResult sendCatalogCachePublicationFrameResult(
                    Session target, CatalogPublicationSendPlan plan, int frameIndex) {
                    CatalogFrameSendResult result = super.sendCatalogCachePublicationFrameResult(target, plan, frameIndex);
                    if (!result.accepted()) {
                        preflightCatalogCachePublicationAsync(target, null).join();
                        assertEquals("CATALOG_PUBLICATION.SEND_INVALID", lastCatalogPublicationSendFailureCode().orElseThrow());
                    }
                    return result;
                }
            };
            CatalogSnapshot active = CatalogSnapshot.empty(new CatalogVersion(1, 0));
            FlowCatalogPublicationPacketHandler handler = new FlowCatalogPublicationPacketHandler(sender,
                new CatalogCachePublicationTransport(SERVER, () -> active, Set.of()), receiptStore("backpressure-retry"));
            try {
                assertTrue(handler.enqueueInitial(session));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                long now = 1_000L;
                while (attempts.get() == 0 && System.nanoTime() < deadline) {
                    handler.drainReady(now);
                    Thread.yield();
                }

                assertEquals(1, attempts.get());
                assertEquals(1, handler.diagnosticSnapshot().outboxEntries());
                assertEquals(0, handler.drainReady(now + 49L));
                assertEquals(1, handler.drainReady(now + 50L));
                assertEquals(2, attempts.get());
                assertEquals(2, attemptedFrames.size());
                assertArrayEquals(attemptedFrames.get(0), attemptedFrames.get(1));
                assertEquals(0, handler.diagnosticSnapshot().outboxEntries());
                assertEquals(0, closes.get());
                assertTrue(handler.lastFailureCode().isEmpty());
            } finally {
                handler.shutdown();
            }
        } finally {
            compressionPool.close();
        }
    }

    private static byte[] bytesAfterPacket(byte[] payload) {
        return Arrays.copyOfRange(payload, 1, payload.length);
    }

    private CatalogPublicationReceiptStore receiptStore(String name) {
        Path root = temporary.resolve(name);
        try {
            Files.createDirectory(root);
        } catch (IOException exception) {
            throw new IllegalStateException("Receipt test root could not be created", exception);
        }
        return new CatalogPublicationReceiptStore(root, root.resolve(CatalogPublicationReceiptStore.FILE_NAME));
    }

    private static final class RecordingSender extends FlowPacketSender {
        private byte[] payload = new byte[0];

        private RecordingSender() {
            super(null, 0, Set.of());
        }

        @Override
        protected CatalogFrameSendResult sendCatalogFrameResult(Session session, byte[] payload) {
            this.payload = payload;
            return CatalogFrameSendResult.success();
        }

        @Override
        public CatalogFrameSendResult sendCatalogCachePublicationFrameResult(Session session,
                                                                              CatalogPublicationSendPlan plan,
                                                                              int frameIndex) {
            this.payload = plan.payload(frameIndex);
            return CatalogFrameSendResult.success();
        }

        @Override
        public boolean sendRawAcknowledged(Session session, byte[] payload, boolean compress) {
            this.payload = payload;
            return true;
        }
    }
}
