package restudio.resync.modules.flow;

import org.junit.jupiter.api.Test;
import restudio.resync.core.Session;
import restudio.resync.contract.canonical.CanonicalHash;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.cache.CatalogCachePublicationTransport;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogPublicationReceipt;
import restudio.resync.flow.cache.CatalogPublicationReceiptPacket;
import restudio.resync.flow.cache.CatalogPublicationReceiptStore;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.migration.MigrationFence;
import restudio.resync.migration.ReSyncPersistenceCoordinator;
import restudio.resync.migration.Snapshot;
import restudio.resync.migration.SnapshotMetadata;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowCatalogPublicationReceiptIntegrationTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));

    @org.junit.jupiter.api.io.TempDir
    Path temporary;

    @Test
    void targetedDispatchCreatesAReceiptBaselineWithoutClaimingClientConvergence() throws Exception {
        Fixture fixture = fixture("targeted-dispatch");
        RecordingSender sender = new RecordingSender();
        FlowCatalogPublicationPacketHandler handler = new FlowCatalogPublicationPacketHandler(sender,
            new CatalogCachePublicationTransport(SERVER, () -> CatalogSnapshot.empty(new CatalogVersion(1, 0)), Set.of()),
            fixture.store());
        Session session = new Session("session", "client", null);

        try {
            assertTrue(handler.sendFull(session));
            CatalogPublicationReceipt dispatched = handler.publicationReceipt(session).orElseThrow();
            assertTrue(dispatched.serverDispatched());
            assertFalse(dispatched.clientReceived());
            assertFalse(dispatched.cacheApplied());
            assertFalse(dispatched.converged());

            CatalogPublicationReceipt.Transition received = handler.acknowledgeClientReceipt(session,
                dispatched.publicationKey(), dispatched.revision());
            assertTrue(received.accepted());
            assertFalse(received.receipt().orElseThrow().cacheApplied());

            CatalogPublicationReceipt.Transition applied = handler.acknowledgeCacheApplication(session,
                dispatched.publicationKey(), dispatched.revision());
            assertTrue(applied.accepted());
            assertTrue(applied.receipt().orElseThrow().converged());
        } finally {
            fixture.coordinator().close();
        }
    }

    @Test
    void unknownOrMismatchedClientReceiptsRemainFailClosed() throws Exception {
        Fixture fixture = fixture("unknown-receipt");
        RecordingSender sender = new RecordingSender();
        FlowCatalogPublicationPacketHandler handler = new FlowCatalogPublicationPacketHandler(sender,
            new CatalogCachePublicationTransport(SERVER, () -> CatalogSnapshot.empty(new CatalogVersion(1, 0)), Set.of()),
            fixture.store());
        Session session = new Session("session", "client", null);

        try {
            handler.resetSession(session);
            CatalogPublicationReceipt.Transition unknown = handler.acknowledgeClientReceipt(session, null, 0);

            assertFalse(unknown.accepted());
            assertTrue(unknown.code().contains("NO_BASELINE"));
            assertTrue(handler.publicationReceipt(session).isEmpty());
        } finally {
            fixture.coordinator().close();
        }
    }

    @Test
    void packetReceiptTransitionsUseTheSessionBaselineAndPersistApplicationRejection() throws Exception {
        Fixture fixture = fixture("packet-rejection");
        RecordingSender sender = new RecordingSender();
        FlowCatalogPublicationPacketHandler handler = new FlowCatalogPublicationPacketHandler(sender,
            new CatalogCachePublicationTransport(SERVER, () -> CatalogSnapshot.empty(new CatalogVersion(1, 0)), Set.of()),
            fixture.store());
        Session session = new Session("session", "client", null);

        try {
            assertTrue(handler.sendFull(session));
            CatalogPublicationReceipt dispatched = handler.publicationReceipt(session).orElseThrow();
            byte[] received = CatalogPublicationReceiptPacket.encodeClientReceived(dispatched.publicationKey(), dispatched.revision());
            handler.handleReceipt(session, received[0], ByteBuffer.wrap(received, 1, received.length - 1));
            byte[] rejected = CatalogPublicationReceiptPacket.encodeCacheRejected(dispatched.publicationKey(),
                dispatched.revision(), "CATALOG_PUBLICATION.CACHE_PROJECTION_FAILED");
            handler.handleReceipt(session, rejected[0], ByteBuffer.wrap(rejected, 1, rejected.length - 1));

            CatalogPublicationReceipt result = handler.publicationReceipt(session).orElseThrow();
            assertEquals(CatalogPublicationReceipt.CacheApplicationState.REJECTED, result.cacheApplication());
            assertEquals("CATALOG_PUBLICATION.CACHE_PROJECTION_FAILED", result.diagnosticCode());
        } finally {
            fixture.coordinator().close();
        }
    }

    @Test
    void targetedDispatchPersistsTheReceiptIntentBeforeSocketTransmission() throws Exception {
        Fixture fixture = fixture("targeted-ordering");
        Session session = new Session("ephemeral-session", "stable-client", null);
        AtomicBoolean baselineVisible = new AtomicBoolean();
        FlowPacketSender sender = new FlowPacketSender(null, 0, Set.of()) {
            @Override
            protected CatalogFrameSendResult sendCatalogFrameResult(Session target, byte[] payload) {
                baselineVisible.set(fixture.store().baseline("stable-client").isPresent());
                return CatalogFrameSendResult.success();
            }
        };
        FlowCatalogPublicationPacketHandler handler = new FlowCatalogPublicationPacketHandler(sender,
            new CatalogCachePublicationTransport(SERVER, () -> CatalogSnapshot.empty(new CatalogVersion(1, 0)), Set.of()),
            fixture.store());

        try {
            assertTrue(handler.sendFull(session));
            assertTrue(baselineVisible.get());
        } finally {
            fixture.coordinator().close();
        }
    }

    @Test
    void registeredHandlerSnapshotRebindAndRestartRetainAllReceiptTransitions() throws Exception {
        Fixture fixture = fixture("restart-source");
        RecordingSender firstSender = new RecordingSender();
        CatalogCachePublicationTransport firstTransport = transport();
        Session session = new Session("session", "client", null);
        FlowCatalogPublicationPacketHandler first = new FlowCatalogPublicationPacketHandler(firstSender, firstTransport,
            fixture.store());

        assertTrue(first.sendFull(session));
        CatalogPublicationReceipt dispatched = first.publicationReceipt(session).orElseThrow();
        assertTrue(first.acknowledgeClientReceipt(session, dispatched.publicationKey(), dispatched.revision()).accepted());
        assertTrue(first.acknowledgeCacheApplication(session, dispatched.publicationKey(), dispatched.revision()).accepted());

        Snapshot snapshot = fixture.coordinator().createSnapshot(temporary.resolve("restart-snapshot"), SnapshotMetadata.preflight());
        assertTrue(snapshot.verified());
        assertArrayEquals(Files.readAllBytes(fixture.store().root()),
            Files.readAllBytes(snapshot.root().resolve(CatalogPublicationReceiptStore.FILE_NAME)));
        fixture.coordinator().participants().quiesceAll();
        fixture.coordinator().participants().rebindAll(snapshot.root());
        fixture.coordinator().participants().resumeAll();
        assertEquals(2L, fixture.store().generation());
        fixture.coordinator().close();

        CatalogPublicationReceiptStore restartedStore = new CatalogPublicationReceiptStore(snapshot.root(),
            snapshot.root().resolve(CatalogPublicationReceiptStore.FILE_NAME));
        ReSyncPersistenceCoordinator restartedCoordinator = new ReSyncPersistenceCoordinator(snapshot.root(),
            temporary.resolve("restart-coordination"), new MigrationFence());
        restartedCoordinator.register(restartedStore);
        restartedCoordinator.seal();
        FlowCatalogPublicationPacketHandler restarted = new FlowCatalogPublicationPacketHandler(new RecordingSender(),
            transport(), restartedStore);

        try {
            restarted.resetSession(session);
            assertTrue(restarted.publicationReceipt(session).orElseThrow().converged());
        } finally {
            restartedCoordinator.close();
        }
    }

    @Test
    void storePersistenceFailureKeepsTheCommittedTransportBaseline() throws Exception {
        Fixture fixture = fixture("dispatch-failure");
        Path path = fixture.store().root();
        Files.delete(path);
        Files.createDirectory(path);
        Session session = new Session("session", "client", null);
        FlowCatalogPublicationPacketHandler handler = new FlowCatalogPublicationPacketHandler(new RecordingSender(),
            transport(), fixture.store());

        try {
            assertFalse(handler.sendFull(session));
            assertEquals("CATALOG_PUBLICATION.RECEIPT_PERSISTENCE_FAILED", handler.lastFailureCode().orElseThrow());
            assertTrue(handler.publicationReceipt(session).isEmpty());
            assertTrue(handler.lastValidPublication().isPresent());
            CatalogPublicationReceipt.Transition transition = handler.acknowledgeClientReceipt(session, null, 0);
            assertFalse(transition.accepted());
            assertTrue(transition.code().contains("NO_BASELINE"));
        } finally {
            repair(fixture.store());
            fixture.coordinator().close();
        }
    }

    @Test
    void acknowledgementPersistenceFailureRestoresTheDispatchedBaseline() throws Exception {
        Fixture fixture = fixture("acknowledgement-failure");
        Path path = fixture.store().root();
        Session session = new Session("session", "client", null);
        FlowCatalogPublicationPacketHandler handler = new FlowCatalogPublicationPacketHandler(new RecordingSender(),
            transport(), fixture.store());

        try {
            assertTrue(handler.sendFull(session));
            CatalogPublicationReceipt dispatched = handler.publicationReceipt(session).orElseThrow();
            Files.delete(path);
            Files.createDirectory(path);

            CatalogPublicationReceipt.Transition transition = handler.acknowledgeClientReceipt(session,
                dispatched.publicationKey(), dispatched.revision());

            assertFalse(transition.accepted());
            assertEquals("CATALOG_PUBLICATION.RECEIPT_PERSISTENCE_FAILED", transition.code());
            assertEquals(dispatched, handler.publicationReceipt(session).orElseThrow());
        } finally {
            repair(fixture.store());
            fixture.coordinator().close();
        }
    }

    @Test
    void targetedDispatchPersistenceFailureKeepsTheCommittedTransportAndPreviousReceiptBaseline() throws Exception {
        Fixture fixture = fixture("refresh-failure");
        Path path = fixture.store().root();
        AtomicReference<CatalogSnapshot> active = new AtomicReference<>(CatalogSnapshot.empty(new CatalogVersion(1, 0)));
        FlowCatalogPublicationPacketHandler handler = new FlowCatalogPublicationPacketHandler(new RecordingSender(),
            new CatalogCachePublicationTransport(SERVER, active::get, Set.of()), fixture.store());
        Session session = new Session("targeted-session", "client", null);

        try {
            assertTrue(handler.sendFull(session));
            CatalogCachePublication previousPublication = handler.lastValidPublication().orElseThrow();
            CatalogPublicationReceipt previousReceipt = handler.publicationReceipt(session).orElseThrow();
            Files.delete(path);
            Files.createDirectory(path);

            assertFalse(handler.sendRefresh(session));
            assertNotEquals(previousPublication, handler.lastValidPublication().orElseThrow());
            assertEquals(previousReceipt, handler.publicationReceipt(session).orElseThrow());
        } finally {
            repair(fixture.store());
            fixture.coordinator().close();
        }
    }

    @Test
    void broadcastReceiptPersistenceFailureKeepsTheCommittedTransportBaseline() throws Exception {
        Fixture fixture = fixture("broadcast-failure");
        Path path = fixture.store().root();
        Session session = new Session("broadcast-session", "client", null);
        FlowCatalogPublicationPacketHandler handler = new FlowCatalogPublicationPacketHandler(new RecordingSender(),
            transport(), fixture.store());

        try {
            assertTrue(handler.broadcastFull());
            CatalogCachePublication committed = handler.lastValidPublication().orElseThrow();
            Files.delete(path);
            Files.createDirectory(path);

            handler.resetSession(session);
            assertFalse(handler.recordBroadcastDispatch(Set.of(session)));
            assertEquals("CATALOG_PUBLICATION.RECEIPT_PERSISTENCE_FAILED", handler.lastFailureCode().orElseThrow());
            assertEquals(committed, handler.lastValidPublication().orElseThrow());
            assertTrue(handler.publicationReceipt(session).isEmpty());
        } finally {
            repair(fixture.store());
            fixture.coordinator().close();
        }
    }

    @Test
    void targetedSocketTransmissionRunsAfterTransportCommitAndReceiptPersistence() throws Exception {
        Fixture fixture = fixture("targeted-commit-ordering");
        AtomicReference<CatalogSnapshot> active = new AtomicReference<>(CatalogSnapshot.empty(new CatalogVersion(1, 0)));
        CatalogSnapshot replacement = CatalogSnapshot.empty(new CatalogVersion(2, 0));
        AtomicBoolean baselineVisible = new AtomicBoolean();
        FlowCatalogPublicationPacketHandler handler = new FlowCatalogPublicationPacketHandler(
            new CommitRejectingSender(() -> {
                baselineVisible.set(fixture.store().baseline("client").isPresent());
                active.set(replacement);
            }),
            new CatalogCachePublicationTransport(SERVER, active::get, Set.of()), fixture.store());
        Session session = new Session("targeted-session", "client", null);

        try {
            assertTrue(handler.sendFull(session));
            assertTrue(baselineVisible.get());
            assertTrue(handler.publicationReceipt(session).isPresent());
            assertTrue(handler.lastValidPublication().isPresent());
        } finally {
            fixture.coordinator().close();
        }
    }

    @Test
    void boundBroadcastPersistsEachTargetBeforeItsSocketTransmissionAndRetainsOnlyFailedReplay() throws Exception {
        Fixture fixture = fixture("broadcast-ordering");
        Session first = new Session("session-a", "client-a", null);
        Session second = new Session("session-b", "client-b", null);
        List<String> observed = new ArrayList<>();
        FlowPacketSender sender = new FlowPacketSender(null, 0, Set.of()) {
            @Override
            protected CatalogFrameSendResult sendCatalogFrameResult(Session target, byte[] payload) {
                observed.add(target.getClientId() + ":" + fixture.store().baseline(target.getClientId()).isPresent());
                return target == second ? CatalogFrameSendResult.failure("CATALOG_PUBLICATION.SEND_FAILED") : CatalogFrameSendResult.success();
            }
        };
        FlowCatalogPublicationPacketHandler handler = new FlowCatalogPublicationPacketHandler(sender, transport(), fixture.store(),
            List.of(first, second));

        try {
            assertFalse(handler.broadcastFull());
            assertEquals(List.of("client-a:true", "client-b:true"), observed);
            assertEquals(CatalogPublicationReceipt.DispatchState.DISPATCHED,
                handler.publicationReceipt(first).orElseThrow().dispatch());
            assertEquals(CatalogPublicationReceipt.DispatchState.FAILED,
                handler.publicationReceipt(second).orElseThrow().dispatch());
            assertEquals(1, handler.pendingReplays().stream()
                .filter(replay -> replay.sessionKey().equals("client-b"))
                .count());
        } finally {
            fixture.coordinator().close();
        }
    }

    @Test
    void disconnectRetiresAnEphemeralAliasOnlyWhenTheStableReplayBaselineExists() throws Exception {
        Fixture fixture = fixture("disconnect-cleanup");
        Session session = new Session("session-id", "stable-client", null);
        FlowCatalogPublicationPacketHandler handler = new FlowCatalogPublicationPacketHandler(new RecordingSender(), transport(),
            fixture.store());

        try {
            assertTrue(handler.sendFull(session));
            fixture.store().recordDispatch("session-id", session.getSessionId(),
                handler.lastValidPublication().orElseThrow());
            handler.cleanupSession(session);
            assertTrue(fixture.store().baseline("session-id").isEmpty());
            assertTrue(fixture.store().baseline("stable-client").isPresent());
        } finally {
            fixture.coordinator().close();
        }
    }

    @Test
    void replacementSessionClaimsStableRowAndOldSessionCannotAcknowledgeOrCleanupIt() throws Exception {
        Fixture fixture = fixture("replacement-session");
        FlowCatalogPublicationPacketHandler handler = new FlowCatalogPublicationPacketHandler(
            new RecordingSender(), transport(), fixture.store());
        Session oldSession = new Session("old-session", "stable-client", null);
        Session replacement = new Session("replacement-session", "stable-client", null);

        try {
            assertTrue(handler.sendFull(oldSession));
            CatalogPublicationReceipt initial = handler.publicationReceipt(oldSession).orElseThrow();

            handler.resetSession(replacement);
            assertTrue(handler.sendFull(replacement));
            CatalogPublicationReceipt current = handler.publicationReceipt(replacement).orElseThrow();
            assertEquals(replacement.getSessionId(), current.ownerToken());
            assertNotEquals(initial.ownerToken(), current.ownerToken());
            assertTrue(handler.publicationReceipt(oldSession).isEmpty());

            CatalogPublicationReceipt.Transition staleAck = handler.acknowledgeClientReceipt(oldSession,
                current.publicationKey(), current.revision());
            assertFalse(staleAck.accepted());
            assertEquals("CATALOG_PUBLICATION.RECEIPT_OWNER_STALE", staleAck.code());

            handler.cleanupSession(oldSession);
            assertEquals(current, fixture.store().baseline("stable-client").orElseThrow());

            assertTrue(handler.acknowledgeClientReceipt(replacement, current.publicationKey(), current.revision()).accepted());
            assertTrue(handler.acknowledgeCacheApplication(replacement, current.publicationKey(), current.revision()).accepted());
            handler.cleanupSession(oldSession);
            assertEquals(replacement.getSessionId(), fixture.store().baseline("stable-client").orElseThrow().ownerToken());
        } finally {
            fixture.coordinator().close();
        }
    }

    @Test
    void replacementSessionRedispatchesAnExactRejectedPublication() throws Exception {
        Fixture fixture = fixture("replacement-rejected-session");
        FlowCatalogPublicationPacketHandler handler = new FlowCatalogPublicationPacketHandler(
            new RecordingSender(), transport(), fixture.store());
        Session oldSession = new Session("old-session", "stable-client", null);
        Session replacement = new Session("replacement-session", "stable-client", null);

        try {
            assertTrue(handler.sendFull(oldSession));
            CatalogPublicationReceipt rejected = handler.publicationReceipt(oldSession).orElseThrow();
            assertTrue(handler.acknowledgeClientReceipt(oldSession, rejected.publicationKey(),
                rejected.revision()).accepted());
            assertTrue(handler.rejectCacheApplication(oldSession, rejected.publicationKey(), rejected.revision(),
                "CATALOG_PUBLICATION.RECEIPT_APPLICATION_REJECTED").accepted());

            handler.resetSession(replacement);
            assertTrue(handler.sendFull(replacement));
            CatalogPublicationReceipt redispatched = handler.publicationReceipt(replacement).orElseThrow();
            assertTrue(redispatched.serverDispatched());
            assertFalse(redispatched.clientReceived());
            assertFalse(redispatched.cacheApplied());
            assertTrue(handler.acknowledgeClientReceipt(replacement, redispatched.publicationKey(),
                redispatched.revision()).accepted());
            assertTrue(handler.acknowledgeCacheApplication(replacement, redispatched.publicationKey(),
                redispatched.revision()).accepted());
        } finally {
            fixture.coordinator().close();
        }
    }

    @Test
    void oldResetCannotReplaceARegisteredSession() throws Exception {
        Fixture fixture = fixture("replacement-reset");
        FlowCatalogPublicationPacketHandler handler = new FlowCatalogPublicationPacketHandler(new RecordingSender(),
            transport(), fixture.store());
        Session oldSession = new Session("old-session", "stable-client", null);
        Session replacement = new Session("replacement-session", "stable-client", null);

        try {
            assertTrue(handler.sendFull(oldSession));
            handler.resetSession(replacement);
            handler.resetSession(oldSession);

            assertTrue(handler.sendFull(replacement));
            assertEquals(replacement.getSessionId(), fixture.store().baseline("stable-client").orElseThrow().ownerToken());
            assertTrue(handler.publicationReceipt(oldSession).isEmpty());
        } finally {
            fixture.coordinator().close();
        }
    }

    @Test
    void staleSessionCannotPersistOrSendAfterOwnershipDisappearsDuringPreflight() throws Exception {
        Fixture fixture = fixture("stale-preflight");
        AtomicReference<FlowCatalogPublicationPacketHandler> handlerReference = new AtomicReference<>();
        AtomicBoolean sent = new AtomicBoolean();
        FlowPacketSender sender = new RecordingSender() {
            @Override
            public Optional<CatalogPublicationSendPlan> preflightCatalogCachePublication(
                Session session, CatalogCachePublication publication) {
                Optional<CatalogPublicationSendPlan> plan = super.preflightCatalogCachePublication(session, publication);
                handlerReference.get().cleanupSession(session);
                return plan;
            }

            @Override
            protected CatalogFrameSendResult sendCatalogFrameResult(Session session, byte[] payload) {
                sent.set(true);
                return CatalogFrameSendResult.success();
            }
        };
        FlowCatalogPublicationPacketHandler handler = new FlowCatalogPublicationPacketHandler(sender, transport(), fixture.store());
        handlerReference.set(handler);
        Session session = new Session("stale-session", "stable-client", null);

        try {
            assertFalse(handler.sendFull(session));
            assertEquals("CATALOG_PUBLICATION.RECEIPT_OWNER_STALE", handler.lastFailureCode().orElseThrow());
            assertTrue(fixture.store().baseline("stable-client").isEmpty());
            assertFalse(sent.get());
        } finally {
            fixture.coordinator().close();
        }
    }

    @Test
    void targetedDispatchRejectsAPlanForAnotherPublicationVariant() throws Exception {
        Fixture fixture = fixture("plan-mismatch");
        FlowCatalogPublicationPacketHandler handler = new FlowCatalogPublicationPacketHandler(
            new MismatchingPlanSender(), transport(), fixture.store());
        Session session = new Session("session", "client", null);

        try {
            assertFalse(handler.sendFull(session));
            assertEquals("CATALOG_PUBLICATION.SEND_PLAN_MISMATCH", handler.lastFailureCode().orElseThrow());
            assertTrue(fixture.store().baseline("client").isEmpty());
            assertTrue(handler.lastValidPublication().isEmpty());
        } finally {
            fixture.coordinator().close();
        }
    }

    @Test
    void restartedLegacyReceiptIsClaimedByTheCurrentSessionBeforeDispatchMutation() throws Exception {
        Path path = temporary.resolve("legacy-claim").resolve(CatalogPublicationReceiptStore.FILE_NAME);
        Files.createDirectories(path.getParent());
        CatalogCachePublicationTransport seedTransport = transport();
        CatalogCachePublication seeded = seedTransport.publishFull().orElseThrow();
        Files.write(path, legacyDocument("stable-client", seeded));

        CatalogPublicationReceiptStore restartedStore = new CatalogPublicationReceiptStore(path);
        FlowCatalogPublicationPacketHandler handler = new FlowCatalogPublicationPacketHandler(new RecordingSender(),
            transport(), restartedStore);
        Session session = new Session("restarted-session", "stable-client", null);

        assertTrue(handler.publicationReceipt(session).isEmpty());
        assertTrue(handler.sendFull(session));

        CatalogPublicationReceipt claimed = handler.publicationReceipt(session).orElseThrow();
        assertEquals(CatalogPublicationReceipt.OwnerState.CLAIMED, claimed.ownerState());
        assertEquals(session.getSessionId(), claimed.ownerToken());
        assertTrue(claimed.serverDispatched());
    }

    @Test
    void broadcastCommitRejectionDoesNotPersistUncommittedReceipts() throws Exception {
        Fixture fixture = fixture("broadcast-commit-rejection");
        AtomicReference<CatalogSnapshot> active = new AtomicReference<>(CatalogSnapshot.empty(new CatalogVersion(1, 0)));
        CatalogSnapshot replacement = CatalogSnapshot.empty(new CatalogVersion(2, 0));
        Session session = new Session("broadcast-session", "client", null);
        FlowCatalogPublicationPacketHandler handler = new FlowCatalogPublicationPacketHandler(
            new PreflightCommitRejectingSender(() -> active.set(replacement)),
            new CatalogCachePublicationTransport(SERVER, active::get, Set.of()), fixture.store(), List.of(session));

        try {
            assertFalse(handler.broadcastFull());
            assertEquals("CATALOG_PUBLICATION.KEY_MISMATCH", handler.lastFailureCode().orElseThrow());
            assertTrue(handler.publicationReceipt(session).isEmpty());
            assertTrue(handler.lastValidPublication().isEmpty());
        } finally {
            fixture.coordinator().close();
        }
    }

    private static CatalogCachePublicationTransport transport() {
        return new CatalogCachePublicationTransport(SERVER,
            () -> CatalogSnapshot.empty(new CatalogVersion(1, 0)), Set.of());
    }

    private static class RecordingSender extends FlowPacketSender {
        private RecordingSender() {
            super(null, 0, Set.of());
        }

        @Override
        protected CatalogFrameSendResult sendCatalogFrameResult(Session session, byte[] payload) {
            return CatalogFrameSendResult.success();
        }
    }

    private static final class MismatchingPlanSender extends RecordingSender {
        @Override
        public Optional<CatalogPublicationSendPlan> preflightCatalogCachePublication(
            Session session, CatalogCachePublication publication) {
            return super.preflightCatalogCachePublication(session, publication).map(plan -> {
                CatalogCachePublication mismatched = new CatalogCachePublication(publication.kind(), publication.key(),
                    publication.catalogBinding(), publication.revision() + 1, publication.entries(), publication.unknown());
                return new CatalogPublicationSendPlan(session, mismatched, plan.payloads());
            });
        }
    }

    private static final class CommitRejectingSender extends FlowPacketSender {
        private final Runnable beforeCommit;

        private CommitRejectingSender(Runnable beforeCommit) {
            super(null, 0, Set.of());
            this.beforeCommit = beforeCommit;
        }

        @Override
        protected CatalogFrameSendResult sendCatalogFrameResult(Session session, byte[] payload) {
            beforeCommit.run();
            return CatalogFrameSendResult.success();
        }
    }

    private static final class PreflightCommitRejectingSender extends FlowPacketSender {
        private final Runnable beforeCommit;

        private PreflightCommitRejectingSender(Runnable beforeCommit) {
            super(null, 0, Set.of());
            this.beforeCommit = beforeCommit;
        }

        @Override
        public Optional<CatalogPublicationSendPlan> preflightCatalogCachePublication(
            CatalogCachePublication publication) {
            beforeCommit.run();
            return super.preflightCatalogCachePublication(publication);
        }

        @Override
        public Optional<CatalogPublicationSendPlan> preflightCatalogCachePublication(
            Session session, CatalogCachePublication publication) {
            beforeCommit.run();
            return super.preflightCatalogCachePublication(session, publication);
        }

        @Override
        protected CatalogFrameSendResult sendCatalogFrameResult(Session session, byte[] payload) {
            return CatalogFrameSendResult.success();
        }
    }

    private Fixture fixture(String name) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve(name));
        CatalogPublicationReceiptStore store = new CatalogPublicationReceiptStore(dataRoot,
            dataRoot.resolve(CatalogPublicationReceiptStore.FILE_NAME));
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(dataRoot,
            temporary.resolve(name + "-coordination"), new MigrationFence());
        coordinator.register(store);
        coordinator.seal();
        return new Fixture(store, coordinator);
    }

    private static void repair(CatalogPublicationReceiptStore store) throws Exception {
        Path path = store.root();
        if (Files.isDirectory(path)) {
            Files.delete(path);
        }
        store.flush();
    }

    private record Fixture(CatalogPublicationReceiptStore store, ReSyncPersistenceCoordinator coordinator) {
    }

    private static byte[] legacyDocument(String sessionKey, CatalogCachePublication publication) {
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("sessionKey", sessionKey);
        receipt.put("publicationKey", publication.key().canonicalText());
        receipt.put("revision", publication.revision());
        receipt.put("dispatch", "dispatched");
        receipt.put("clientReceipt", "not-received");
        receipt.put("cacheApplication", "not-applied");
        receipt.put("diagnosticCode", "");
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("kind", "catalog-publication-receipts");
        document.put("version", 1);
        document.put("receipts", List.of(receipt));
        document.put("contentHash", CanonicalHash.sha256(JsonValue.fromJava(document)));
        return JsonValue.fromJava(document).canonicalBytes();
    }
}
