package restudio.resync.modules.flow;

import org.junit.jupiter.api.Test;
import restudio.resync.bridge.ReSyncBridgeChunker;
import restudio.resync.compression.CompressionPool;
import restudio.resync.core.ConnectionInfo;
import restudio.resync.core.ConnectionState;
import restudio.resync.core.Session;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCacheOpaque;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCachePublicationCodec;
import restudio.resync.flow.cache.CatalogCachePublicationTransport;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.cache.CatalogPublicationChunkPacket;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.jobs.FlowJobRegistry;
import restudio.resync.jobs.JobRecord;
import restudio.resync.protocol.Codec;
import restudio.resync.protocol.FrameSender;
import restudio.resync.protocol.ReSyncProtocolContract;
import restudio.resync.server.AuthorityEpoch;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowPacketSenderTest {
    @Test
    void graphSaveAcknowledgementsUseTheirResourcePacket() {
        RecordingSender sender = new RecordingSender();

        sender.sendGraphSaveAck(null, "function", "requestMessage", "save-1", 13L, "hash");

        ByteBuffer payload = ByteBuffer.wrap(sender.payload);
        assertEquals(ReSyncProtocolContract.resource("function").flowPackets().saveAck(), payload.get());
        assertEquals("requestMessage", readString(payload));
        assertEquals("save-1", readString(payload));
        assertEquals(13L, payload.getLong());
        assertEquals("hash", readString(payload));
        assertEquals(1L, payload.getLong());
        assertFalse(payload.hasRemaining());

        sender.sendGraphSaveAck(null, "command", "race", "save-2", 4L, "next");
        assertEquals(ReSyncProtocolContract.resource("command").flowPackets().saveAck(), sender.payload[0]);
    }

    @Test
    void successfulRawAcknowledgementRecordsTheFrame() {
        CompressionPool compressionPool = new CompressionPool(6, 1);
        try {
            List<byte[]> frames = new ArrayList<>();
            Session session = new Session("session", "client", new ConnectionInfo(null, new FrameSender() {
                @Override
                public void send(byte[] frame) {
                    frames.add(frame);
                }

                @Override
                public void close(int code, String reason) {
                }
            }, 1));
            FlowPacketSender sender = new FlowPacketSender(new Codec(compressionPool), 0, Set.of());

            assertTrue(sender.sendRawAcknowledged(session, new byte[]{1, 2, 3}, false));
            assertEquals(1, frames.size());
            assertTrue(sender.lastCatalogPublicationSendFailureCode().isEmpty());
        } finally {
            compressionPool.close();
        }
    }

    @Test
    void closedConnectionFailsRawAcknowledgementWithoutSending() {
        CompressionPool compressionPool = new CompressionPool(6, 1);
        try {
            List<byte[]> frames = new ArrayList<>();
            ConnectionInfo connection = new ConnectionInfo(null, new FrameSender() {
                @Override
                public void send(byte[] frame) {
                    frames.add(frame);
                }

                @Override
                public void close(int code, String reason) {
                }
            }, 1);
            connection.setState(ConnectionState.CLOSED);
            Session session = new Session("session", "client", connection);
            FlowPacketSender sender = new FlowPacketSender(new Codec(compressionPool), 0, Set.of());

            assertFalse(sender.sendRawAcknowledged(session, new byte[]{1}, false));
            assertEquals("CATALOG_PUBLICATION.SEND_UNAVAILABLE", sender.lastCatalogPublicationSendFailureCode().orElseThrow());
            assertTrue(frames.isEmpty());
        } finally {
            compressionPool.close();
        }
    }

    @Test
    void incompressibleLargeCatalogPublicationIsChunked() {
        RecordingSender sender = new RecordingSender();
        CatalogCachePublication publication = largePublication();
        int encodedLength = new CatalogCachePublicationCodec().encodeBytes(publication).length;

        assertTrue(encodedLength + 1 > CatalogPublicationChunkPacket.MAX_CHUNK_BYTES);
        assertTrue(encodedLength <= CatalogPublicationChunkPacket.MAX_PUBLICATION_BYTES);
        assertTrue(sender.sendCatalogCachePublicationAcknowledged(null, publication));
        assertTrue(sender.catalogPayloads.size() > 1);
        assertTrue(sender.catalogPayloads.stream()
            .allMatch(payload -> payload[0] == ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION_CHUNK));
        assertTrue(sender.lastCatalogPublicationSendFailureCode().isEmpty());
    }

    @Test
    void compressibleLargeCatalogPublicationIsChunked() {
        RecordingSender sender = new RecordingSender();
        CatalogCachePublication publication = compressibleLargePublication();
        int encodedLength = new CatalogCachePublicationCodec().encodeBytes(publication).length;

        assertTrue(encodedLength + 1 > CatalogPublicationChunkPacket.MAX_CHUNK_BYTES);
        assertTrue(sender.sendCatalogCachePublicationAcknowledged(null, publication));
        assertTrue(sender.catalogPayloads.size() > 1);
        assertTrue(sender.catalogPayloads.stream()
            .allMatch(payload -> payload[0] == ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION_CHUNK));
    }

    @Test
    void legacyClientProjectionRemovesTheWholeAuthoringPublication() {
        CompressionPool compressionPool = new CompressionPool(6, 1);
        try {
            CatalogCachePublication publication = new CatalogCachePublicationTransport(
                new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111")),
                () -> CatalogSnapshot.empty(new CatalogVersion(1, 0)), Set.of()).publishFull().orElseThrow();
            assertTrue(publication.hasAuthoringPublication());
            ConnectionInfo legacyConnection = new ConnectionInfo(null, recordingFrameSender(new ArrayList<>()), 1);
            Session legacy = new Session("legacy", "legacy", legacyConnection);
            ConnectionInfo currentConnection = new ConnectionInfo(null, recordingFrameSender(new ArrayList<>()), 2);
            currentConnection.setClientCapabilities(Set.of(FlowPacketSender.CATALOG_AUTHORING_CAPABILITY));
            Session current = new Session("current", "current", currentConnection);
            FlowPacketSender sender = new FlowPacketSender(new Codec(compressionPool), 0, Set.of(legacy, current));

            FlowPacketSender.CatalogPublicationSendPlan legacyPlan = sender
                .preflightCatalogCachePublication(legacy, publication).orElseThrow();
            FlowPacketSender.CatalogPublicationSendPlan currentPlan = sender
                .preflightCatalogCachePublication(current, publication).orElseThrow();

            assertFalse(legacyPlan.publication().hasAuthoringPublication());
            assertTrue(currentPlan.publication().hasAuthoringPublication());
            assertEquals(publication.key(), legacyPlan.publication().key());
            assertEquals(publication.entries(), legacyPlan.publication().entries());
        } finally {
            compressionPool.close();
        }
    }

    @Test
    void configuredFrameBudgetIsAppliedBeforeCatalogPublicationSend() {
        CompressionPool compressionPool = new CompressionPool(6, 1);
        try {
            List<byte[]> frames = new ArrayList<>();
            Session session = new Session("session", "client", new ConnectionInfo(null, new FrameSender() {
                @Override
                public void send(byte[] frame) {
                    frames.add(frame);
                }

                @Override
                public void close(int code, String reason) {
                }
            }, 1));
            int maxEncodedFrameBytes = 4096;
            FlowPacketSender sender = new FlowPacketSender(new Codec(compressionPool, maxEncodedFrameBytes, 1_000_000), 0, Set.of());
            CatalogCachePublication publication = largePublication();

            FlowPacketSender.CatalogPublicationSendPlan plan = sender
                .preflightCatalogCachePublication(session, publication).orElseThrow();

            assertTrue(plan.frameCount() > 1);
            for (int index = 0; index < plan.frameCount(); index++) {
                assertTrue(plan.frameBytes(index) <= maxEncodedFrameBytes);
            }
            assertTrue(sender.sendPreparedCatalogCachePublication(session, plan));
            assertEquals(plan.frameCount(), frames.size());
            assertTrue(frames.stream().allMatch(frame -> frame.length <= maxEncodedFrameBytes));
        } finally {
            compressionPool.close();
        }
    }

    @Test
    void catalogFrameReportsTransportBackpressureInsteadOfFalseSuccess() {
        CompressionPool compressionPool = new CompressionPool(6, 1);
        try {
            FrameSender frameSender = new FrameSender() {
                @Override
                public void send(byte[] frame) {
                    throw new AssertionError("Catalog publication must use admitted transport sends");
                }

                @Override
                public FrameSender.SendResult trySend(byte[] frame) {
                    return FrameSender.SendResult.BACKPRESSURED;
                }

                @Override
                public void close(int code, String reason) {
                }
            };
            Session session = new Session("session", "client", new ConnectionInfo(null, frameSender, 1));
            FlowPacketSender sender = new FlowPacketSender(new Codec(compressionPool), 0, Set.of(session));
            FlowPacketSender.CatalogPublicationSendPlan plan = sender
                .preflightCatalogCachePublication(session, largePublication()).orElseThrow();

            assertFalse(sender.sendCatalogCachePublicationFrame(session, plan, 0));
            assertEquals("CATALOG_PUBLICATION.SEND_BACKPRESSURE",
                sender.lastCatalogPublicationSendFailureCode().orElseThrow());
        } finally {
            compressionPool.close();
        }
    }

    @Test
    void catalogPreflightCachesExactAdaptiveFramesForSend() {
        CompressionPool compressionPool = new CompressionPool(6, 1);
        try {
            List<byte[]> frames = new ArrayList<>();
            Session session = new Session("session", "client", new ConnectionInfo(null, new FrameSender() {
                @Override
                public void send(byte[] frame) {
                    frames.add(frame);
                }

                @Override
                public void close(int code, String reason) {
                }
            }, 1));
            Codec codec = new Codec(compressionPool);
            FlowPacketSender sender = new FlowPacketSender(codec, 0, Set.of(session));
            FlowPacketSender.CatalogPublicationSendPlan plan = sender
                .preflightCatalogCachePublication(session, compressibleLargePublication()).orElseThrow();
            int sequenceAfterPreflight = codec.getNextSequence();
            byte[] preparedFrame = plan.frame(0);

            assertTrue(plan.frameCount() > 1);
            assertTrue(plan.frameBytes(0) < plan.payloadBytes());
            assertTrue(sender.sendCatalogCachePublicationFrame(session, plan, 0));

            assertEquals(sequenceAfterPreflight, codec.getNextSequence());
            assertEquals(1, frames.size());
            assertArrayEquals(preparedFrame, frames.getFirst());
            Codec.Frame decoded = codec.decodeFrame(frames.getFirst());
            assertTrue(decoded.header.isCompressed());
            assertArrayEquals(codec.decodeFrame(plan.frame(0)).payload, decoded.payload);
        } finally {
            compressionPool.close();
        }
    }

    @Test
    void frameBudgetWithoutCatalogPayloadCapacityFailsPreflight() {
        CompressionPool compressionPool = new CompressionPool(6, 1);
        try {
            FlowPacketSender sender = new FlowPacketSender(new Codec(compressionPool, 12, 1_000_000), 0, Set.of());

            assertTrue(sender.preflightCatalogCachePublication(largePublication()).isEmpty());
            assertEquals("CATALOG_PUBLICATION.SEND_TOO_LARGE", sender.lastCatalogPublicationSendFailureCode().orElseThrow());
        } finally {
            compressionPool.close();
        }
    }

    @Test
    void effectiveCatalogLimitAccountsForConfiguredFrameBudgetAndChunkCount() {
        CompressionPool compressionPool = new CompressionPool(6, 1);
        try {
            int maxEncodedFrameBytes = 64 * 1024;
            FlowPacketSender sender = new FlowPacketSender(
                new Codec(compressionPool, maxEncodedFrameBytes, 1_000_000), 0, Set.of());

            assertEquals(maxEncodedFrameBytes - 12 - CatalogPublicationChunkPacket.HEADER_BYTES,
                sender.effectiveCatalogChunkDataBytes());
            assertEquals(CatalogPublicationChunkPacket.MAX_PUBLICATION_BYTES,
                sender.effectiveCatalogPublicationBytes());
        } finally {
            compressionPool.close();
        }
    }

    @Test
    void sessionBridgeBudgetIsPreservedByCatalogPreflight() {
        CompressionPool compressionPool = new CompressionPool(6, 1);
        try {
            List<byte[]> frames = new ArrayList<>();
            FrameSender bridgeSender = new FrameSender() {
                @Override
                public void send(byte[] frame) {
                    frames.add(frame);
                }

                @Override
                public void close(int code, String reason) {
                }

                @Override
                public int getMaxEncodedFrameBytes() {
                    return ReSyncBridgeChunker.CHUNK_SIZE;
                }
            };
            Session session = new Session("session", "client", new ConnectionInfo(null, bridgeSender, 1));
            FlowPacketSender sender = new FlowPacketSender(new Codec(compressionPool), 0, Set.of(session));
            FlowPacketSender.CatalogPublicationSendPlan plan = sender
                .preflightCatalogCachePublication(session, largePublication()).orElseThrow();

            assertSame(session, plan.session());
            assertEquals(ReSyncBridgeChunker.CHUNK_SIZE - 12 - CatalogPublicationChunkPacket.HEADER_BYTES,
                sender.effectiveCatalogChunkDataBytes(session));
            for (int index = 0; index < plan.frameCount(); index++) {
                assertTrue(plan.frameBytes(index) <= ReSyncBridgeChunker.CHUNK_SIZE);
            }
            assertTrue(sender.sendPreparedCatalogCachePublication(session, plan));
            assertTrue(frames.stream().allMatch(frame -> frame.length <= ReSyncBridgeChunker.CHUNK_SIZE));
        } finally {
            compressionPool.close();
        }
    }

    @Test
    void globalCatalogCapabilityUsesTheSmallestSubscribedFrameBudget() {
        CompressionPool compressionPool = new CompressionPool(6, 1);
        try {
            FrameSender bridgeSender = new FrameSender() {
                @Override
                public void send(byte[] frame) {
                }

                @Override
                public void close(int code, String reason) {
                }

                @Override
                public int getMaxEncodedFrameBytes() {
                    return ReSyncBridgeChunker.CHUNK_SIZE;
                }
            };
            Session session = new Session("session", "client", new ConnectionInfo(null, bridgeSender, 1));
            FlowPacketSender sender = new FlowPacketSender(new Codec(compressionPool), 0, Set.of(session));

            assertEquals(ReSyncBridgeChunker.CHUNK_SIZE - 12 - CatalogPublicationChunkPacket.HEADER_BYTES,
                sender.effectiveMaxChunkDataBytes());
        } finally {
            compressionPool.close();
        }
    }

    @Test
    void concurrentCatalogPreflightsShareExactFramesUntilTransmissionStarts() {
        CompressionPool compressionPool = new CompressionPool(6, 1);
        try {
            List<byte[]> sent = new ArrayList<>();
            Session session = new Session("session", "client", new ConnectionInfo(null, recordingFrameSender(sent), 1));
            Codec codec = new Codec(compressionPool);
            FlowPacketSender sender = new FlowPacketSender(codec, 0, Set.of(session));
            CatalogCachePublication publication = largePublication();

            var firstFuture = sender.preflightCatalogCachePublicationAsync(session, publication);
            var secondFuture = sender.preflightCatalogCachePublicationAsync(session, publication);
            FlowPacketSender.CatalogPublicationSendPlan first = firstFuture.join().orElseThrow();
            FlowPacketSender.CatalogPublicationSendPlan second = secondFuture.join().orElseThrow();
            int sequenceAfterFirst = codec.getNextSequence();

            assertEquals(first.frameCount(), sequenceAfterFirst);
            assertEquals(1, sender.catalogPublicationPlanCacheEntryCount());
            long encodedBytes = 0L;
            for (int index = 0; index < first.frameCount(); index++) {
                encodedBytes += first.frameBytes(index);
            }
            assertTrue(sender.catalogPublicationPlanCacheBytes() >= encodedBytes);
            assertTrue(sender.catalogPublicationPlanCacheBytes()
                <= FlowPacketSender.MAX_CATALOG_PUBLICATION_PLAN_CACHE_BYTES);
            assertArrayEquals(first.frame(0), second.frame(0));
            assertTrue(sender.sendCatalogCachePublicationFrame(session, first, 0));
            assertFalse(sender.sendCatalogCachePublicationFrame(session, second, 0));
            assertEquals("CATALOG_PUBLICATION.SEND_PLAN_CONFLICT",
                sender.lastCatalogPublicationSendFailureCode().orElseThrow());
            assertEquals(0, sender.catalogPublicationPlanCacheEntryCount());

            FlowPacketSender.CatalogPublicationSendPlan third = sender
                .preflightCatalogCachePublication(session, publication).orElseThrow();
            assertTrue(codec.getNextSequence() > sequenceAfterFirst);
            assertFalse(Arrays.equals(first.frame(0), third.frame(0)));
        } finally {
            compressionPool.close();
        }
    }

    @Test
    void catalogPublicationEncodingCanRunOffThreadAndDrainOneFrame() {
        RecordingSender sender = new RecordingSender();
        CatalogCachePublication publication = largePublication();

        FlowPacketSender.CatalogPublicationSendPlan plan = sender
            .encodeCatalogCachePublicationAsync(publication).join().orElseThrow();

        assertTrue(plan.frameCount() > 1);
        assertTrue(sender.sendCatalogCachePublicationFrame(null, plan, 0));
        assertEquals(1, sender.catalogPayloads.size());
        assertArrayEquals(plan.payload(0), sender.catalogPayloads.getFirst());
    }

    @Test
    void catalogEncodingExecutorIsDedicatedBoundedAndRejectsSaturation() throws Exception {
        BlockingCompressionPool compressionPool = new BlockingCompressionPool();
        List<CompletableFuture<?>> accepted = new ArrayList<>();
        try {
            Session session = new Session("session", "client",
                new ConnectionInfo(null, recordingFrameSender(new ArrayList<>()), 1));
            FlowPacketSender sender = new FlowPacketSender(new Codec(compressionPool), 0, Set.of(session));
            CatalogCachePublication publication = compressibleLargePublication();

            accepted.add(sender.preflightCatalogCachePublicationAsync(session, publication));
            assertTrue(compressionPool.started.await(5, TimeUnit.SECONDS));
            for (int index = 0; index < FlowPacketSender.MAX_CATALOG_ENCODING_QUEUE + 1; index++) {
                accepted.add(sender.preflightCatalogCachePublicationAsync(session, publication));
            }
            var rejected = sender.preflightCatalogCachePublicationAsync(session, publication);

            assertTrue(rejected.join().isEmpty());
            assertTrue(compressionPool.threadName.get().startsWith("resync-catalog-encoding"));
        } finally {
            compressionPool.release.countDown();
            for (CompletableFuture<?> future : accepted) {
                future.join();
            }
            compressionPool.close();
        }
    }

    @Test
    void failedCatalogPublicationChunkKeepsStableFailureAndStopsTransfer() {
        CatalogCachePublication publication = largePublication();
        byte[] encoded = new CatalogCachePublicationCodec().encodeBytes(publication);
        int chunkCount = CatalogPublicationChunkPacket.encodeChunks(encoded).size();
        PartialFailureSender sender = new PartialFailureSender(2);

        assertTrue(chunkCount > 2);
        assertFalse(sender.sendCatalogCachePublicationAcknowledged(null, publication));
        assertEquals("CATALOG_PUBLICATION.SEND_FAILED", sender.lastCatalogPublicationSendFailureCode().orElseThrow());
        assertEquals(2, sender.catalogPayloads.size());
    }

    @Test
    void broadcastFailureWithoutTargetCodeUsesStableFallback() {
        CatalogCachePublication publication = new CatalogCachePublicationTransport(
            new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111")),
            () -> CatalogSnapshot.empty(new CatalogVersion(1, 0)), Set.of()).publishFull().orElseThrow();
        Session session = new Session("session", "client", null);
        FlowPacketSender sender = new FalsePublicationAcknowledgementSender(Set.of(session));

        assertFalse(sender.broadcastCatalogCachePublicationAcknowledged(publication));
        assertEquals("CATALOG_PUBLICATION.SEND_FAILED", sender.lastCatalogPublicationSendFailureCode().orElseThrow());
    }

    @Test
    void packetJobOperationsFailClosedWithoutTheCentralRegistry() {
        FlowPacketSender sender = new FlowPacketSender(null, 0, Set.of());

        assertThrows(IllegalStateException.class, () -> sender.beginJob(null, "saveFlow", "flow"));
    }

    @Test
    void packetJobLeaseKeepsQuiesceOpenUntilHandlerCompletes() {
        FlowJobRegistry registry = new FlowJobRegistry();
        FlowPacketSender sender = new FlowPacketSender(null, 0, Set.of(), registry);

        JobRecord<String> job = sender.beginJob(null, "saveFlow", "flow");
        assertNotNull(job);

        var drain = registry.quiesce(Duration.ofSeconds(1));

        assertFalse(drain.isDone());
        sender.completeJobExecution(job);
        drain.join();

        assertEquals(0, registry.physicalTaskCount());
        assertTrue(registry.health().available());
    }

    private static String readString(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.getInt()];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static FrameSender recordingFrameSender(List<byte[]> frames) {
        return new FrameSender() {
            @Override
            public void send(byte[] frame) {
                frames.add(frame);
            }

            @Override
            public void close(int code, String reason) {
            }
        };
    }

    private static CatalogCachePublication largePublication() {
        CatalogCacheKey key = new CatalogCacheKey(new ServerId(UUID.fromString(
            "11111111-1111-4111-8111-111111111111")), 1, new ContentHash(
            "0000000000000000000000000000000000000000000000000000000000000000"));
        ContractRef<NodeId> firstKey = ContractRef.of(new OwnerId("catalog.test"), new NodeId("first"));
        ContractRef<NodeId> secondKey = ContractRef.of(new OwnerId("catalog.test"), new NodeId("second"));
        ContractRef<NodeId> thirdKey = ContractRef.of(new OwnerId("catalog.test"), new NodeId("third"));
        ContractRef<NodeId> fourthKey = ContractRef.of(new OwnerId("catalog.test"), new NodeId("fourth"));
        return new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key, 1, List.of(
            CatalogCachePublication.Entry.present(firstKey, 1, CatalogCacheState.ACTIVE, Set.of(), false,
                CatalogCacheOpaque.of(deterministicCanonicalArray(50_000, 0x19A2B3C4D5E6F701L))),
            CatalogCachePublication.Entry.present(secondKey, 1, CatalogCacheState.ACTIVE, Set.of(), false,
                CatalogCacheOpaque.of(deterministicCanonicalArray(50_000, 0x27B4C6D8EAF10213L))),
            CatalogCachePublication.Entry.present(thirdKey, 1, CatalogCacheState.ACTIVE, Set.of(), false,
                CatalogCacheOpaque.of(deterministicCanonicalArray(50_000, 0x35C7D9EB0F123425L))),
            CatalogCachePublication.Entry.present(fourthKey, 1, CatalogCacheState.ACTIVE, Set.of(), false,
                CatalogCacheOpaque.of(deterministicCanonicalArray(50_000, 0x43E8FA1C20345637L)))));
    }

    private static CatalogCachePublication compressibleLargePublication() {
        CatalogCacheKey key = new CatalogCacheKey(new ServerId(UUID.fromString(
            "11111111-1111-4111-8111-111111111111")), 1, new ContentHash(
            "0000000000000000000000000000000000000000000000000000000000000000"));
        ContractRef<NodeId> definitionKey = ContractRef.of(new OwnerId("catalog.test"), new NodeId("repeated"));
        return new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key, 1, List.of(
            CatalogCachePublication.Entry.present(definitionKey, 1, CatalogCacheState.ACTIVE, Set.of(), false,
                CatalogCacheOpaque.of(repetitiveCanonicalArray(100_000)))));
    }

    private static byte[] deterministicCanonicalArray(int values, long seed) {
        StringBuilder text = new StringBuilder(values * 18 + 1);
        text.append('[');
        long state = seed;
        for (int index = 0; index < values; index++) {
            if (index > 0) {
                text.append(',');
            }
            state = state * 6364136223846793005L + 1442695040888963407L;
            text.append('"').append(Long.toUnsignedString(state, 16)).append('"');
        }
        text.append(']');
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] repetitiveCanonicalArray(int values) {
        StringBuilder text = new StringBuilder(values * 8 + 1);
        text.append('[');
        for (int index = 0; index < values; index++) {
            if (index > 0) {
                text.append(',');
            }
            text.append("\"value\"");
        }
        text.append(']');
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static final class RecordingSender extends FlowPacketSender {
        private byte[] payload = new byte[0];
        private final List<byte[]> catalogPayloads = new ArrayList<>();

        private RecordingSender() {
            super(null, 0, Set.of());
            setAuthorityEpoch(AuthorityEpoch.fixed(1L));
        }

        @Override
        protected CatalogFrameSendResult sendCatalogFrameResult(Session session, byte[] payload) {
            this.payload = payload;
            catalogPayloads.add(payload);
            return CatalogFrameSendResult.success();
        }

        @Override
        public boolean sendRawAcknowledged(Session session, byte[] payload, boolean compress) {
            this.payload = payload;
            return true;
        }
    }

    private static final class PartialFailureSender extends FlowPacketSender {
        private final List<byte[]> catalogPayloads = new ArrayList<>();
        private final int failureIndex;

        private PartialFailureSender(int failureIndex) {
            super(null, 0, Set.of());
            this.failureIndex = failureIndex;
        }

        @Override
        protected CatalogFrameSendResult sendCatalogFrameResult(Session session, byte[] payload) {
            catalogPayloads.add(payload);
            return catalogPayloads.size() != failureIndex
                ? CatalogFrameSendResult.success()
                : CatalogFrameSendResult.failure("CATALOG_PUBLICATION.SEND_FAILED");
        }
    }

    private static final class FalsePublicationAcknowledgementSender extends FlowPacketSender {
        private FalsePublicationAcknowledgementSender(Set<Session> sessions) {
            super(null, 0, sessions);
        }

        @Override
        public boolean sendCatalogCachePublicationAcknowledged(Session session, CatalogCachePublication publication) {
            return false;
        }
    }

    private static final class BlockingCompressionPool extends CompressionPool {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicReference<String> threadName = new AtomicReference<>("");

        private BlockingCompressionPool() {
            super(6, 1);
        }

        @Override
        public byte[] compress(byte[] data) {
            threadName.compareAndSet("", Thread.currentThread().getName());
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Catalog encoding test release timed out");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Catalog encoding test was interrupted", exception);
            }
            return super.compress(data);
        }
    }
}
