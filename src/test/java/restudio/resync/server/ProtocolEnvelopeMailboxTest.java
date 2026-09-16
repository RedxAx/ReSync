package restudio.resync.server;

import org.junit.jupiter.api.Test;
import restudio.resync.core.ConnectionInfo;
import restudio.resync.core.ConnectionState;
import restudio.resync.core.Session;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.protocol.ProtocolBody;
import restudio.resync.flow.protocol.ProtocolEnvelope;
import restudio.resync.flow.protocol.ProtocolRejectionCode;
import restudio.resync.diagnostics.DiagnosticEvent;
import restudio.resync.diagnostics.DiagnosticSink;
import restudio.resync.protocol.FrameSender;
import restudio.resync.protocol.ProtocolEnvelopeBoundary;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolEnvelopeMailboxTest {
    private static final UUID SERVER_UUID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID MESSAGE_UUID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID REQUEST_UUID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID CORRELATION_UUID = UUID.fromString("55555555-5555-4555-8555-555555555555");

    @Test
    void dispatchesOneRequestPerConnectionAndPreservesFifo() throws Exception {
        ConnectionInfo connection = authenticatedConnection(1);
        Session session = new Session("session", "client", connection);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(2);
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger invocations = new AtomicInteger();
        ProtocolEnvelopeDispatchBoundary boundary = new ProtocolEnvelopeDispatchBoundary((ignoredConnection, ignoredSession, ignoredEnvelope) -> {
            int invocation = invocations.incrementAndGet();
            order.add(invocation);
            if (invocation == 1) {
                firstEntered.countDown();
                await(releaseFirst);
            }
            return ProtocolEnvelopeDispatchResult.accepted();
        });
        try (ProtocolEnvelopeMailbox mailbox = mailbox(boundary, 8, 16)) {
            mailbox.activate(connection, session);
            assertTrue(admit(mailbox, connection, session, delivered).accepted());
            assertTrue(admit(mailbox, connection, session, delivered).accepted());
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS));
            assertEquals(1, invocations.get());
            releaseFirst.countDown();
            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            mailbox.whenIdle().get(2, TimeUnit.SECONDS);
            assertEquals(List.of(1, 2), order);
        }
    }

    @Test
    void schedulesBusyConnectionsAtTheWorkerQueueTail() throws Exception {
        ConnectionInfo firstConnection = authenticatedConnection(7);
        ConnectionInfo secondConnection = authenticatedConnection(8);
        Session firstSession = new Session("first", "first", firstConnection);
        Session secondSession = new Session("second", "second", secondConnection);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(3);
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger invocations = new AtomicInteger();
        ProtocolEnvelopeDispatchBoundary boundary = new ProtocolEnvelopeDispatchBoundary((connection, ignoredSession, ignoredEnvelope) -> {
            int invocation = invocations.incrementAndGet();
            order.add(connection == firstConnection ? 1 : 2);
            if (invocation == 1) {
                firstEntered.countDown();
                await(releaseFirst);
            }
            return ProtocolEnvelopeDispatchResult.accepted();
        });
        ProtocolEnvelopeMailbox.Limits limits = new ProtocolEnvelopeMailbox.Limits(1, 8, 32_768L, 16, 65_536L, 4096);
        try (ProtocolEnvelopeMailbox mailbox = new ProtocolEnvelopeMailbox(boundary, limits)) {
            mailbox.activate(firstConnection, firstSession);
            mailbox.activate(secondConnection, secondSession);
            assertTrue(admit(mailbox, firstConnection, firstSession, delivered).accepted());
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS));
            assertTrue(admit(mailbox, firstConnection, firstSession, delivered).accepted());
            assertTrue(admit(mailbox, secondConnection, secondSession, delivered).accepted());
            releaseFirst.countDown();

            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            assertEquals(List.of(1, 2, 1), order);
        }
    }

    @Test
    void retainsCompletedResultAcrossBackpressureWithoutRerunningHandler() throws Exception {
        ConnectionInfo connection = authenticatedConnection(2);
        Session session = new Session("session", "client", connection);
        AtomicInteger invocations = new AtomicInteger();
        AtomicInteger deliveries = new AtomicInteger();
        CountDownLatch delivered = new CountDownLatch(1);
        ProtocolEnvelopeDispatchBoundary boundary = new ProtocolEnvelopeDispatchBoundary((ignoredConnection, ignoredSession, ignoredEnvelope) -> {
            invocations.incrementAndGet();
            return ProtocolEnvelopeDispatchResult.accepted();
        });
        try (ProtocolEnvelopeMailbox mailbox = mailbox(boundary, 8, 16)) {
            mailbox.activate(connection, session);
            ProtocolEnvelopeMailbox.Admission admission = mailbox.admit(connection, session, encodedRequest(),
                (candidate, candidateSession) -> candidate == connection && candidateSession == session,
                (ignoredResult, ignoredAuthorityEpoch) -> () -> {
                    if (deliveries.incrementAndGet() == 1) {
                        return FrameSender.SendResult.BACKPRESSURED;
                    }
                    delivered.countDown();
                    return FrameSender.SendResult.ACCEPTED;
                });

            assertTrue(admission.accepted());
            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            mailbox.whenIdle().get(2, TimeUnit.SECONDS);
            assertEquals(1, invocations.get());
            assertEquals(2, deliveries.get());
        }
    }

    @Test
    void retirementDropsQueuedRequestsAndSuppressesAnInflightResult() throws Exception {
        ConnectionInfo connection = authenticatedConnection(3);
        Session session = new Session("session", "client", connection);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();
        AtomicInteger deliveries = new AtomicInteger();
        ProtocolEnvelopeDispatchBoundary boundary = new ProtocolEnvelopeDispatchBoundary((ignoredConnection, ignoredSession, ignoredEnvelope) -> {
            invocations.incrementAndGet();
            entered.countDown();
            await(release);
            return ProtocolEnvelopeDispatchResult.accepted();
        });
        try (ProtocolEnvelopeMailbox mailbox = mailbox(boundary, 8, 16)) {
            mailbox.activate(connection, session);
            assertTrue(admit(mailbox, connection, session, deliveries).accepted());
            assertTrue(admit(mailbox, connection, session, deliveries).accepted());
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            mailbox.retire(connection);
            ProtocolEnvelopeMailbox.Admission retiring = admit(mailbox, connection, session, deliveries);
            assertFalse(retiring.accepted());
            assertEquals(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE, retiring.rejection().rejectionCode());
            release.countDown();
            mailbox.whenIdle().get(2, TimeUnit.SECONDS);
            assertEquals(1, invocations.get());
            assertEquals(0, deliveries.get());
            assertEquals(0L, mailbox.admittedRequestCount());
        }
    }

    @Test
    void shutdownRejectsNewWorkAndAllowsTheActiveHandlerToSettle() throws Exception {
        ConnectionInfo connection = authenticatedConnection(9);
        Session session = new Session("session", "client", connection);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger deliveries = new AtomicInteger();
        ProtocolEnvelopeDispatchBoundary boundary = new ProtocolEnvelopeDispatchBoundary((ignoredConnection, ignoredSession, ignoredEnvelope) -> {
            entered.countDown();
            await(release);
            return ProtocolEnvelopeDispatchResult.accepted();
        });
        ProtocolEnvelopeMailbox mailbox = mailbox(boundary, 8, 16);
        try {
            mailbox.activate(connection, session);
            assertTrue(admit(mailbox, connection, session, deliveries).accepted());
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            mailbox.shutdown();
            assertFalse(admit(mailbox, connection, session, deliveries).accepted());
            release.countDown();
            mailbox.whenIdle().get(2, TimeUnit.SECONDS);
            assertEquals(0, deliveries.get());
        } finally {
            release.countDown();
            mailbox.close();
        }
    }

    @Test
    void boundedAdmissionRejectsExcessRequestsWithoutEnteringTheHandler() throws Exception {
        ConnectionInfo connection = authenticatedConnection(4);
        Session session = new Session("session", "client", connection);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ProtocolEnvelopeDispatchBoundary boundary = new ProtocolEnvelopeDispatchBoundary((ignoredConnection, ignoredSession, ignoredEnvelope) -> {
            entered.countDown();
            await(release);
            return ProtocolEnvelopeDispatchResult.accepted();
        });
        ProtocolEnvelopeMailbox.Limits limits = new ProtocolEnvelopeMailbox.Limits(2, 1, 4096L, 1, 4096L, 4096);
        try (ProtocolEnvelopeMailbox mailbox = new ProtocolEnvelopeMailbox(boundary, limits)) {
            mailbox.activate(connection, session);
            assertTrue(admit(mailbox, connection, session, new AtomicInteger()).accepted());
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            ProtocolEnvelopeMailbox.Admission rejected = admit(mailbox, connection, session, new AtomicInteger());
            assertFalse(rejected.accepted());
            assertEquals(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE, rejected.rejection().rejectionCode());
            release.countDown();
            mailbox.whenIdle().get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void decodesAnOuterBridgeFrameOnTheMailboxWorker() throws Exception {
        ConnectionInfo connection = authenticatedConnection(5);
        Session session = new Session("session", "client", connection);
        AtomicReference<String> decoderThread = new AtomicReference<>();
        AtomicReference<String> handlerThread = new AtomicReference<>();
        CountDownLatch delivered = new CountDownLatch(1);
        ProtocolEnvelopeDispatchBoundary boundary = new ProtocolEnvelopeDispatchBoundary((ignoredConnection, ignoredSession, ignoredEnvelope) -> {
            handlerThread.set(Thread.currentThread().getName());
            return ProtocolEnvelopeDispatchResult.accepted();
        });
        try (ProtocolEnvelopeMailbox mailbox = mailbox(boundary, 8, 16)) {
            mailbox.activate(connection, session);
            ProtocolEnvelopeMailbox.Admission admission = mailbox.admitEncoded(connection, session, new byte[]{1}, encoded -> {
                decoderThread.set(Thread.currentThread().getName());
                return encodedRequest();
            }, (candidate, candidateSession) -> candidate == connection && candidateSession == session,
                (ignoredResult, ignoredAuthorityEpoch) -> () -> {
                    delivered.countDown();
                    return FrameSender.SendResult.ACCEPTED;
                });

            assertTrue(admission.accepted());
            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            assertTrue(decoderThread.get().startsWith("ReSync-Protocol-"));
            assertTrue(handlerThread.get().startsWith("ReSync-Protocol-"));
        }
    }

    @Test
    void malformedOuterFrameFailsClosedWithoutInvokingTheHandler() throws Exception {
        ConnectionInfo connection = authenticatedConnection(6);
        Session session = new Session("session", "client", connection);
        AtomicInteger invocations = new AtomicInteger();
        AtomicReference<ProtocolEnvelopeDispatchResult> delivered = new AtomicReference<>();
        CountDownLatch settled = new CountDownLatch(1);
        ProtocolEnvelopeDispatchBoundary boundary = new ProtocolEnvelopeDispatchBoundary((ignoredConnection, ignoredSession, ignoredEnvelope) -> {
            invocations.incrementAndGet();
            return ProtocolEnvelopeDispatchResult.accepted();
        });
        try (ProtocolEnvelopeMailbox mailbox = mailbox(boundary, 8, 16)) {
            mailbox.activate(connection, session);
            assertTrue(mailbox.admitEncoded(connection, session, new byte[]{1}, ignored -> {
                throw new IllegalArgumentException("malformed");
            }, (candidate, candidateSession) -> candidate == connection && candidateSession == session,
                (result, ignoredAuthorityEpoch) -> () -> {
                    delivered.set(result);
                    settled.countDown();
                    return FrameSender.SendResult.ACCEPTED;
                }).accepted());

            assertTrue(settled.await(2, TimeUnit.SECONDS));
            assertEquals(0, invocations.get());
            assertEquals(ProtocolRejectionCode.INVALID_PAYLOAD, delivered.get().rejectionCode());
        }
    }

    @Test
    void requiresExplicitActivationAndCannotRecreateARetiredLane() throws Exception {
        ConnectionInfo connection = authenticatedConnection(10);
        Session oldSession = new Session("old", "client", connection);
        Session newSession = new Session("new", "client", connection);
        ProtocolEnvelopeDispatchBoundary boundary = new ProtocolEnvelopeDispatchBoundary(
            (ignoredConnection, ignoredSession, ignoredEnvelope) -> ProtocolEnvelopeDispatchResult.accepted());
        try (ProtocolEnvelopeMailbox mailbox = mailbox(boundary, 8, 16)) {
            assertFalse(admit(mailbox, connection, oldSession, new AtomicInteger()).accepted());
            mailbox.activate(connection, oldSession);
            mailbox.retire(connection);
            assertFalse(admit(mailbox, connection, oldSession, new AtomicInteger()).accepted());

            mailbox.activate(connection, newSession);
            assertFalse(admit(mailbox, connection, oldSession, new AtomicInteger()).accepted());
            CountDownLatch delivered = new CountDownLatch(1);
            assertTrue(admit(mailbox, connection, newSession, delivered).accepted());
            assertTrue(delivered.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void deliveryFactoryReceivesTheAuthorityEpochCapturedAtDispatch() throws Exception {
        ConnectionInfo connection = authenticatedConnection(17);
        Session session = new Session("session", "client", connection);
        AtomicLong epoch = new AtomicLong(7L);
        AtomicInteger invocations = new AtomicInteger();
        AtomicLong preparedEpoch = new AtomicLong();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(1);
        ProtocolEnvelopeDispatchBoundary boundary = new ProtocolEnvelopeDispatchBoundary(new AuthorityEpoch(epoch::get),
            (ignoredConnection, ignoredSession, ignoredEnvelope) -> {
                if (invocations.incrementAndGet() == 1) {
                    firstEntered.countDown();
                    await(releaseFirst);
                }
                return ProtocolEnvelopeDispatchResult.accepted();
            });
        try (ProtocolEnvelopeMailbox mailbox = mailbox(boundary, 8, 16)) {
            mailbox.activate(connection, session);
            assertTrue(mailbox.admit(connection, session, encodedRequest(), exact(connection, session),
                (ignoredResult, ignoredAuthorityEpoch) ->
                    () -> FrameSender.SendResult.ACCEPTED).accepted());
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS));
            assertTrue(mailbox.admit(connection, session, encodedRequest(), exact(connection, session),
                (ignoredResult, dispatchAuthorityEpoch) -> {
                    preparedEpoch.set(dispatchAuthorityEpoch);
                    return () -> {
                        delivered.countDown();
                        return FrameSender.SendResult.ACCEPTED;
                    };
                }).accepted());
            epoch.set(8L);
            releaseFirst.countDown();
            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            mailbox.whenIdle().get(2, TimeUnit.SECONDS);
            assertEquals(2, invocations.get());
            assertEquals(8L, preparedEpoch.get());
        } finally {
            releaseFirst.countDown();
        }
    }

    @Test
    void dispatchAuthorityFailureSettlesTheHeadAndAllowsAnotherLaneToProgress() throws Exception {
        ConnectionInfo failedConnection = authenticatedConnection(18);
        ConnectionInfo progressingConnection = authenticatedConnection(19);
        Session failedSession = new Session("failed", "failed", failedConnection);
        Session progressingSession = new Session("progressing", "progressing", progressingConnection);
        AtomicInteger authorityReads = new AtomicInteger();
        AtomicInteger handlerInvocations = new AtomicInteger();
        AtomicInteger failedDeliveries = new AtomicInteger();
        CountDownLatch progressed = new CountDownLatch(1);
        AuthorityEpoch failingAuthority = new AuthorityEpoch(() -> {
            if (authorityReads.incrementAndGet() == 1) {
                return 7L;
            }
            throw new IllegalStateException("Authority unavailable");
        });
        ProtocolEnvelopeDispatchBoundary boundary = new ProtocolEnvelopeDispatchBoundary(failingAuthority,
            (ignoredConnection, ignoredSession, ignoredEnvelope) -> {
                handlerInvocations.incrementAndGet();
                return ProtocolEnvelopeDispatchResult.accepted();
            });
        ProtocolEnvelopeMailbox.Limits limits = new ProtocolEnvelopeMailbox.Limits(1, 8, 32_768L, 16, 65_536L, 4096);
        try (ProtocolEnvelopeMailbox mailbox = new ProtocolEnvelopeMailbox(boundary, limits)) {
            mailbox.activate(failedConnection, failedSession);
            mailbox.activate(progressingConnection, progressingSession);
            assertTrue(mailbox.admit(failedConnection, failedSession, encodedRequest(),
                exact(failedConnection, failedSession), (ignoredResult, ignoredAuthorityEpoch) -> () -> {
                    failedDeliveries.incrementAndGet();
                    return FrameSender.SendResult.ACCEPTED;
                }).accepted());
            assertTrue(mailbox.admitOutbound(progressingConnection, progressingSession, new byte[]{1},
                exact(progressingConnection, progressingSession), () -> true, () -> {
                    progressed.countDown();
                    return FrameSender.SendResult.ACCEPTED;
                }).accepted());

            assertTrue(progressed.await(2, TimeUnit.SECONDS));
            mailbox.whenIdle().get(2, TimeUnit.SECONDS);
            assertEquals(0, handlerInvocations.get());
            assertEquals(0, failedDeliveries.get());
            assertEquals(0L, mailbox.admittedRequestCount());
            assertEquals(0L, mailbox.admittedByteCount());
        }
    }

    @Test
    void outboundFramesShareCountByteBoundsAndFifoWithInboundWork() throws Exception {
        ConnectionInfo connection = authenticatedConnection(11);
        Session session = new Session("session", "client", connection);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(2);
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        ProtocolEnvelopeDispatchBoundary boundary = new ProtocolEnvelopeDispatchBoundary(
            (ignoredConnection, ignoredSession, ignoredEnvelope) -> ProtocolEnvelopeDispatchResult.accepted());
        ProtocolEnvelopeMailbox.Limits limits = new ProtocolEnvelopeMailbox.Limits(1, 2, 8L, 2, 8L, 4);
        try (ProtocolEnvelopeMailbox mailbox = new ProtocolEnvelopeMailbox(boundary, limits)) {
            mailbox.activate(connection, session);
            assertTrue(mailbox.admitOutbound(connection, session, new byte[]{1, 2, 3, 4}, exact(connection, session),
                () -> true, () -> {
                    order.add(1);
                    firstEntered.countDown();
                    await(releaseFirst);
                    delivered.countDown();
                    return FrameSender.SendResult.ACCEPTED;
                }).accepted());
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS));
            assertTrue(mailbox.admitOutbound(connection, session, new byte[]{5, 6, 7, 8}, exact(connection, session),
                () -> true, () -> {
                    order.add(2);
                    delivered.countDown();
                    return FrameSender.SendResult.ACCEPTED;
                }).accepted());
            assertEquals(2L, mailbox.admittedRequestCount());
            assertEquals(8L, mailbox.admittedByteCount());
            assertFalse(mailbox.admitOutbound(connection, session, new byte[]{9}, exact(connection, session),
                () -> true, () -> FrameSender.SendResult.ACCEPTED).accepted());
            releaseFirst.countDown();
            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            mailbox.whenIdle().get(2, TimeUnit.SECONDS);
            assertEquals(List.of(1, 2), order);
        }
    }

    @Test
    void outboundDeliveryRechecksSessionAndEventFencesAfterQueueing() throws Exception {
        ConnectionInfo connection = authenticatedConnection(12);
        Session session = new Session("session", "client", connection);
        AtomicBoolean sessionCurrent = new AtomicBoolean(true);
        AtomicBoolean eventCurrent = new AtomicBoolean(true);
        CountDownLatch blockerEntered = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        AtomicInteger deliveries = new AtomicInteger();
        ProtocolEnvelopeDispatchBoundary boundary = new ProtocolEnvelopeDispatchBoundary(
            (ignoredConnection, ignoredSession, ignoredEnvelope) -> ProtocolEnvelopeDispatchResult.accepted());
        try (ProtocolEnvelopeMailbox mailbox = mailbox(boundary, 8, 16)) {
            mailbox.activate(connection, session);
            assertTrue(mailbox.admitOutbound(connection, session, new byte[]{1}, exact(connection, session), () -> true,
                () -> {
                    blockerEntered.countDown();
                    await(releaseBlocker);
                    return FrameSender.SendResult.ACCEPTED;
                }).accepted());
            assertTrue(blockerEntered.await(1, TimeUnit.SECONDS));
            assertTrue(mailbox.admitOutbound(connection, session, new byte[]{2},
                (candidate, candidateSession) -> sessionCurrent.get(), eventCurrent::get, () -> {
                    deliveries.incrementAndGet();
                    return FrameSender.SendResult.ACCEPTED;
                }).accepted());
            eventCurrent.set(false);
            releaseBlocker.countDown();
            mailbox.whenIdle().get(2, TimeUnit.SECONDS);
            assertEquals(0, deliveries.get());

            mailbox.activate(connection, session);
            eventCurrent.set(true);
            sessionCurrent.set(true);
            CountDownLatch sessionBlockerEntered = new CountDownLatch(1);
            CountDownLatch releaseSessionBlocker = new CountDownLatch(1);
            assertTrue(mailbox.admitOutbound(connection, session, new byte[]{3}, exact(connection, session), () -> true,
                () -> {
                    sessionBlockerEntered.countDown();
                    await(releaseSessionBlocker);
                    return FrameSender.SendResult.ACCEPTED;
                }).accepted());
            assertTrue(sessionBlockerEntered.await(1, TimeUnit.SECONDS));
            assertTrue(mailbox.admitOutbound(connection, session, new byte[]{4},
                (candidate, candidateSession) -> sessionCurrent.get(), eventCurrent::get, () -> {
                    deliveries.incrementAndGet();
                    return FrameSender.SendResult.ACCEPTED;
                }).accepted());
            sessionCurrent.set(false);
            releaseSessionBlocker.countDown();
            mailbox.whenIdle().get(2, TimeUnit.SECONDS);
            assertEquals(0, deliveries.get());
        }
    }

    @Test
    void backpressuredOutboundDeliveryStopsWhenItsEventFenceChanges() throws Exception {
        ConnectionInfo connection = authenticatedConnection(13);
        Session session = new Session("session", "client", connection);
        AtomicBoolean eventCurrent = new AtomicBoolean(true);
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch firstAttempt = new CountDownLatch(1);
        ProtocolEnvelopeDispatchBoundary boundary = new ProtocolEnvelopeDispatchBoundary(
            (ignoredConnection, ignoredSession, ignoredEnvelope) -> ProtocolEnvelopeDispatchResult.accepted());
        try (ProtocolEnvelopeMailbox mailbox = mailbox(boundary, 8, 16)) {
            mailbox.activate(connection, session);
            assertTrue(mailbox.admitOutbound(connection, session, new byte[]{1}, exact(connection, session),
                eventCurrent::get, () -> {
                    attempts.incrementAndGet();
                    firstAttempt.countDown();
                    return FrameSender.SendResult.BACKPRESSURED;
                }).accepted());
            assertTrue(firstAttempt.await(1, TimeUnit.SECONDS));
            eventCurrent.set(false);
            mailbox.whenIdle().get(2, TimeUnit.SECONDS);
            assertEquals(1, attempts.get());
        }
    }

    @Test
    void permanentlyBackpressuredDeliveryRetiresAndClosesItsLaneAfterBoundedAttempts() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger closeCode = new AtomicInteger();
        AtomicBoolean closeOutsideMonitor = new AtomicBoolean();
        AtomicReference<ProtocolEnvelopeMailbox> reference = new AtomicReference<>();
        CountDownLatch closed = new CountDownLatch(1);
        FrameSender sender = new FrameSender() {
            @Override
            public void send(byte[] frame) {
            }

            @Override
            public SendResult trySend(byte[] frame) {
                attempts.incrementAndGet();
                return SendResult.BACKPRESSURED;
            }

            @Override
            public void close(int code, String reason) {
                closeCode.set(code);
                closes.incrementAndGet();
                closeOutsideMonitor.set(monitorAvailable(reference.get()));
                closed.countDown();
            }
        };
        ConnectionInfo connection = authenticatedConnection(sender, 20);
        Session session = new Session("session", "client", connection);
        ProtocolEnvelopeDispatchBoundary boundary = new ProtocolEnvelopeDispatchBoundary(
            (ignoredConnection, ignoredSession, ignoredEnvelope) -> ProtocolEnvelopeDispatchResult.accepted());
        ProtocolEnvelopeMailbox.Limits limits = new ProtocolEnvelopeMailbox.Limits(1, 8, 32_768L, 16, 65_536L, 4096);
        try (ProtocolEnvelopeMailbox mailbox = new ProtocolEnvelopeMailbox(boundary, limits)) {
            reference.set(mailbox);
            mailbox.activate(connection, session);
            assertTrue(mailbox.admitOutbound(connection, session, new byte[]{1}, exact(connection, session),
                () -> true, () -> sender.trySend(new byte[]{1})).accepted());

            mailbox.whenIdle().get(3, TimeUnit.SECONDS);
            assertTrue(closed.await(1, TimeUnit.SECONDS));
            assertEquals(8, attempts.get());
            assertEquals(1, closes.get());
            assertEquals(1013, closeCode.get());
            assertTrue(closeOutsideMonitor.get());
            assertEquals(0L, mailbox.admittedRequestCount());
            assertEquals(0L, mailbox.admittedByteCount());
        }
    }

    @Test
    void replacementGenerationCannotActivateUntilTheExhaustedGenerationFinishesClosing() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger replacementDeliveries = new AtomicInteger();
        CountDownLatch closeEntered = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        CountDownLatch closeFinished = new CountDownLatch(1);
        FrameSender sender = new FrameSender() {
            @Override
            public void send(byte[] frame) {
            }

            @Override
            public SendResult trySend(byte[] frame) {
                attempts.incrementAndGet();
                return SendResult.BACKPRESSURED;
            }

            @Override
            public void close(int code, String reason) {
                closeEntered.countDown();
                try {
                    await(releaseClose);
                } finally {
                    closeFinished.countDown();
                }
            }
        };
        ConnectionInfo connection = authenticatedConnection(sender, 21);
        Session exhaustedSession = new Session("exhausted", "client", connection);
        Session replacementSession = new Session("replacement", "client", connection);
        ProtocolEnvelopeDispatchBoundary boundary = new ProtocolEnvelopeDispatchBoundary(
            (ignoredConnection, ignoredSession, ignoredEnvelope) -> ProtocolEnvelopeDispatchResult.accepted());
        ProtocolEnvelopeMailbox.Limits limits = new ProtocolEnvelopeMailbox.Limits(1, 8, 32_768L, 16, 65_536L, 4096);
        try (ProtocolEnvelopeMailbox mailbox = new ProtocolEnvelopeMailbox(boundary, limits)) {
            mailbox.activate(connection, exhaustedSession);
            assertTrue(mailbox.admitOutbound(connection, exhaustedSession, new byte[]{1},
                exact(connection, exhaustedSession), () -> true, () -> sender.trySend(new byte[]{1})).accepted());
            assertTrue(closeEntered.await(3, TimeUnit.SECONDS));

            assertThrows(IllegalStateException.class, () -> mailbox.activate(connection, replacementSession));
            releaseClose.countDown();
            assertTrue(closeFinished.await(1, TimeUnit.SECONDS));
            mailbox.whenIdle().get(1, TimeUnit.SECONDS);

            mailbox.activate(connection, replacementSession);
            assertTrue(mailbox.admitOutbound(connection, replacementSession, new byte[]{2},
                exact(connection, replacementSession), () -> true, () -> {
                    replacementDeliveries.incrementAndGet();
                    return FrameSender.SendResult.ACCEPTED;
                }).accepted());
            mailbox.whenIdle().get(1, TimeUnit.SECONDS);
            assertEquals(8, attempts.get());
            assertEquals(1, replacementDeliveries.get());
        } finally {
            releaseClose.countDown();
        }
    }

    @Test
    void externalDeliveryCallbacksAreNeverCalledWhileTheMailboxMonitorIsHeld() throws Exception {
        ConnectionInfo connection = authenticatedConnection(14);
        Session session = new Session("session", "client", connection);
        AtomicBoolean sessionOutsideMonitor = new AtomicBoolean();
        AtomicBoolean eventOutsideMonitor = new AtomicBoolean();
        AtomicBoolean deliveryOutsideMonitor = new AtomicBoolean();
        AtomicInteger sessionFenceCalls = new AtomicInteger();
        AtomicInteger eventFenceCalls = new AtomicInteger();
        CountDownLatch delivered = new CountDownLatch(1);
        ProtocolEnvelopeDispatchBoundary boundary = new ProtocolEnvelopeDispatchBoundary(
            (ignoredConnection, ignoredSession, ignoredEnvelope) -> ProtocolEnvelopeDispatchResult.accepted());
        try (ProtocolEnvelopeMailbox mailbox = mailbox(boundary, 8, 16)) {
            mailbox.activate(connection, session);
            ProtocolEnvelopeMailbox.SessionFence sessionFence = (candidate, candidateSession) -> {
                if (sessionFenceCalls.incrementAndGet() > 1) {
                    sessionOutsideMonitor.set(monitorAvailable(mailbox));
                }
                return true;
            };
            ProtocolEnvelopeMailbox.EventFence eventFence = () -> {
                if (eventFenceCalls.incrementAndGet() > 1) {
                    eventOutsideMonitor.set(monitorAvailable(mailbox));
                }
                return true;
            };
            assertTrue(mailbox.admitOutbound(connection, session, new byte[]{1}, sessionFence, eventFence, () -> {
                deliveryOutsideMonitor.set(monitorAvailable(mailbox));
                delivered.countDown();
                return FrameSender.SendResult.ACCEPTED;
            }).accepted());
            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            assertTrue(sessionOutsideMonitor.get());
            assertTrue(eventOutsideMonitor.get());
            assertTrue(deliveryOutsideMonitor.get());
        }
    }

    @Test
    void idleCompletionCallbacksRunOutsideTheMailboxMonitor() throws Exception {
        ConnectionInfo connection = authenticatedConnection(15);
        Session session = new Session("session", "client", connection);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean outsideMonitor = new AtomicBoolean();
        ProtocolEnvelopeDispatchBoundary boundary = new ProtocolEnvelopeDispatchBoundary(
            (ignoredConnection, ignoredSession, ignoredEnvelope) -> ProtocolEnvelopeDispatchResult.accepted());
        try (ProtocolEnvelopeMailbox mailbox = mailbox(boundary, 8, 16)) {
            mailbox.activate(connection, session);
            assertTrue(mailbox.admitOutbound(connection, session, new byte[]{1}, exact(connection, session), () -> true,
                () -> {
                    entered.countDown();
                    await(release);
                    return FrameSender.SendResult.ACCEPTED;
                }).accepted());
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            CompletableFuture<Void> callback = mailbox.whenIdle().thenRun(() -> {
                try {
                    outsideMonitor.set(CompletableFuture.supplyAsync(mailbox::admittedRequestCount)
                        .get(1, TimeUnit.SECONDS) >= 0L);
                } catch (Exception exception) {
                    outsideMonitor.set(false);
                }
            });
            release.countDown();
            callback.get(2, TimeUnit.SECONDS);
            assertTrue(outsideMonitor.get());
        }
    }

    @Test
    void admissionAfterLogicalIdleReceivesANewFutureBeforePriorCompletionCallbacksRun() throws Exception {
        ConnectionInfo firstConnection = authenticatedConnection(22);
        ConnectionInfo secondConnection = authenticatedConnection(23);
        Session firstSession = new Session("first", "first", firstConnection);
        Session secondSession = new Session("second", "second", secondConnection);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        BlockingCompletion priorIdle = new BlockingCompletion();
        ProtocolEnvelopeDispatchBoundary boundary = new ProtocolEnvelopeDispatchBoundary(
            (ignoredConnection, ignoredSession, ignoredEnvelope) -> ProtocolEnvelopeDispatchResult.accepted());
        try (ProtocolEnvelopeMailbox mailbox = mailbox(boundary, 8, 16)) {
            mailbox.activate(firstConnection, firstSession);
            mailbox.activate(secondConnection, secondSession);
            assertTrue(mailbox.admitOutbound(firstConnection, firstSession, new byte[]{1},
                exact(firstConnection, firstSession), () -> true, () -> {
                    firstEntered.countDown();
                    await(releaseFirst);
                    return FrameSender.SendResult.ACCEPTED;
                }).accepted());
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS));
            replaceIdle(mailbox, priorIdle);
            releaseFirst.countDown();
            assertTrue(priorIdle.completionEntered.await(1, TimeUnit.SECONDS));

            assertTrue(mailbox.admitOutbound(secondConnection, secondSession, new byte[]{2},
                exact(secondConnection, secondSession), () -> true, () -> {
                    secondEntered.countDown();
                    await(releaseSecond);
                    return FrameSender.SendResult.ACCEPTED;
                }).accepted());
            assertTrue(secondEntered.await(1, TimeUnit.SECONDS));
            CompletableFuture<Void> currentIdle = mailbox.whenIdle();
            priorIdle.releaseCompletion.countDown();
            priorIdle.get(1, TimeUnit.SECONDS);

            assertFalse(currentIdle.isDone());
            assertEquals(1L, mailbox.admittedRequestCount());
            releaseSecond.countDown();
            currentIdle.get(1, TimeUnit.SECONDS);
            assertTrue(mailbox.isIdle());
        } finally {
            releaseFirst.countDown();
            releaseSecond.countDown();
            priorIdle.releaseCompletion.countDown();
        }
    }

    @Test
    void rejectionDiagnosticsRunOutsideTheMailboxMonitor() throws Exception {
        ConnectionInfo connection = authenticatedConnection(16);
        Session session = new Session("session", "client", connection);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean outsideMonitor = new AtomicBoolean();
        AtomicReference<ProtocolEnvelopeMailbox> reference = new AtomicReference<>();
        ProbeSink sink = new ProbeSink(event -> {
            if (!"protocol_mailbox_admission".equals(event.stage()) || event.value("outcome") == null
                || !"rejected".equals(event.value("outcome").toJava())) {
                return;
            }
            try {
                outsideMonitor.set(CompletableFuture.supplyAsync(reference.get()::admittedRequestCount)
                    .get(1, TimeUnit.SECONDS) >= 0L);
            } catch (Exception exception) {
                outsideMonitor.set(false);
            }
        });
        TemporaryLifecycleDiagnostics.bind(sink);
        ProtocolEnvelopeDispatchBoundary boundary = new ProtocolEnvelopeDispatchBoundary(
            (ignoredConnection, ignoredSession, ignoredEnvelope) -> ProtocolEnvelopeDispatchResult.accepted());
        ProtocolEnvelopeMailbox.Limits limits = new ProtocolEnvelopeMailbox.Limits(1, 1, 8L, 1, 8L, 4);
        try (ProtocolEnvelopeMailbox mailbox = new ProtocolEnvelopeMailbox(boundary, limits)) {
            reference.set(mailbox);
            mailbox.activate(connection, session);
            assertTrue(mailbox.admitOutbound(connection, session, new byte[]{1}, exact(connection, session), () -> true,
                () -> {
                    entered.countDown();
                    await(release);
                    return FrameSender.SendResult.ACCEPTED;
                }).accepted());
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertFalse(mailbox.admitOutbound(connection, session, new byte[]{2}, exact(connection, session), () -> true,
                () -> FrameSender.SendResult.ACCEPTED).accepted());
            assertTrue(outsideMonitor.get());
            release.countDown();
            mailbox.whenIdle().get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            TemporaryLifecycleDiagnostics.close(sink);
        }
    }

    private static ProtocolEnvelopeMailbox mailbox(ProtocolEnvelopeDispatchBoundary boundary, int perConnection, int global) {
        return new ProtocolEnvelopeMailbox(boundary,
            new ProtocolEnvelopeMailbox.Limits(2, perConnection, 32_768L, global, 65_536L, 4096));
    }

    private static ProtocolEnvelopeMailbox.Admission admit(ProtocolEnvelopeMailbox mailbox, ConnectionInfo connection,
                                                            Session session, CountDownLatch delivered) {
        return mailbox.admit(connection, session, encodedRequest(),
            (candidate, candidateSession) -> candidate == connection && candidateSession == session,
            (ignoredResult, ignoredAuthorityEpoch) -> () -> {
                delivered.countDown();
                return FrameSender.SendResult.ACCEPTED;
            });
    }

    private static ProtocolEnvelopeMailbox.Admission admit(ProtocolEnvelopeMailbox mailbox, ConnectionInfo connection,
                                                            Session session, AtomicInteger delivered) {
        return mailbox.admit(connection, session, encodedRequest(),
            (candidate, candidateSession) -> candidate == connection && candidateSession == session,
            (ignoredResult, ignoredAuthorityEpoch) -> () -> {
                delivered.incrementAndGet();
                return FrameSender.SendResult.ACCEPTED;
            });
    }

    private static ConnectionInfo authenticatedConnection(int id) {
        return authenticatedConnection(new FrameSender() {
            @Override
            public void send(byte[] frame) {
            }

            @Override
            public void close(int code, String reason) {
            }
        }, id);
    }

    private static ConnectionInfo authenticatedConnection(FrameSender sender, int id) {
        ConnectionInfo connection = new ConnectionInfo(null, sender, id);
        connection.setState(ConnectionState.AUTHENTICATED);
        return connection;
    }

    private static ProtocolEnvelopeMailbox.SessionFence exact(ConnectionInfo connection, Session session) {
        return (candidate, candidateSession) -> candidate == connection && candidateSession == session;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static boolean monitorAvailable(ProtocolEnvelopeMailbox mailbox) {
        try {
            return CompletableFuture.supplyAsync(mailbox::admittedRequestCount).get(1, TimeUnit.SECONDS) >= 0L;
        } catch (Exception exception) {
            return false;
        }
    }

    private static void replaceIdle(ProtocolEnvelopeMailbox mailbox, CompletableFuture<Void> idle) throws Exception {
        Field field = ProtocolEnvelopeMailbox.class.getDeclaredField("idle");
        field.setAccessible(true);
        field.set(mailbox, idle);
    }

    private static byte[] encodedRequest() {
        ProtocolEnvelope<Map<String, Object>> envelope = new ProtocolEnvelope<>(
            ProtocolEnvelope.Kind.REQUEST,
            new CatalogVersion(1, 0),
            MESSAGE_UUID,
            REQUEST_UUID,
            CORRELATION_UUID,
            CORRELATION_UUID,
            new ServerId(SERVER_UUID),
            null,
            0,
            null,
            ContractRef.of(new OwnerId("restudio.resync"), OperationId.of("control.test")),
            Set.of(),
            ContractRef.of(new OwnerId("restudio.resync"), ResourceTypeId.of("control.request")),
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            0,
            ProtocolEnvelope.Status.ACCEPTED,
            List.of(),
            Map.of(),
            new ProtocolBody.ControlRequest("test", Map.of("value", true)));
        return new ProtocolEnvelopeBoundary().encode(envelope);
    }

    private static final class ProbeSink implements DiagnosticSink {
        private final Consumer<DiagnosticEvent> consumer;

        private ProbeSink(Consumer<DiagnosticEvent> consumer) {
            this.consumer = consumer;
        }

        @Override
        public Status status() {
            return Status.ready(Mode.VERBOSE);
        }

        @Override
        public Offer offer(DiagnosticEvent event) {
            consumer.accept(event);
            return Offer.ACCEPTED;
        }

        @Override
        public Status pause() {
            return status();
        }

        @Override
        public Status resume() {
            return status();
        }

        @Override
        public Flush flush() {
            return Flush.FLUSHED;
        }

        @Override
        public void close() {
        }
    }

    private static final class BlockingCompletion extends CompletableFuture<Void> {
        private final CountDownLatch completionEntered = new CountDownLatch(1);
        private final CountDownLatch releaseCompletion = new CountDownLatch(1);

        @Override
        public boolean complete(Void value) {
            completionEntered.countDown();
            await(releaseCompletion);
            return super.complete(value);
        }
    }
}
