package restudio.resync.flow.workspace;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveDocumentChannelTest {
    @Test
    void reconnectsEveryActiveTargetWithFreshRevisionState() {
        LiveDocumentChannel<String, String, String, String> channel = new LiveDocumentChannel<>();
        WorkspaceTarget target = new WorkspaceTarget("text", "notes");
        RecordingTransport transport = new RecordingTransport();
        RecordingListener listener = new RecordingListener();
        channel.bind(transport);
        channel.join(target, listener);

        channel.connect(1L);
        channel.acceptSnapshot(new LiveDocumentChannel.Snapshot<>(target, 7L, "document", List.of()), 1L, 1L);
        String operationId = channel.publishOperation(target, "change");
        channel.disconnect("Disconnected");
        channel.connect(2L);
        channel.acceptSnapshot(new LiveDocumentChannel.Snapshot<>(target, 99L, "stale", List.of()), 1L, 1L);

        assertEquals(List.of(target, target), transport.joined);
        assertEquals(0L, channel.sequence(target));
        assertEquals(List.of("Disconnected"), listener.resyncReasons);
        assertEquals(List.of("document"), listener.snapshots);
        assertFalse(operationId.isBlank());
        assertEquals(operationId, transport.operationIds.getFirst());
    }

    @Test
    void attributesSynchronousEchoBeforeAcceptedTransportReturn() {
        LiveDocumentChannel<String, String, String, String> channel = new LiveDocumentChannel<>();
        WorkspaceTarget target = new WorkspaceTarget("text", "notes");
        RecordingListener listener = new RecordingListener();
        channel.join(target, listener);
        channel.bind(new LiveDocumentChannel.Transport<>() {
            @Override
            public void join(WorkspaceTarget joinedTarget) {
            }

            @Override
            public void leave(WorkspaceTarget leftTarget) {
            }

            @Override
            public boolean publishOperation(WorkspaceTarget operationTarget, long baseSequence, String operationId,
                                            String operation) {
                channel.acceptOperation(new LiveDocumentChannel.Operation<>(
                    operationTarget, 1L, operationId, "self", "Self", operation), 1L, 1L);
                return true;
            }

            @Override
            public boolean publishAwareness(WorkspaceTarget awarenessTarget, String awareness) {
                return true;
            }
        });
        channel.connect(1L);
        channel.acceptSnapshot(new LiveDocumentChannel.Snapshot<>(target, 0L, "document", List.of()), 1L, 1L);

        String operationId = channel.publishOperation(target, "change");

        assertFalse(operationId.isBlank());
        assertEquals(List.of(true), listener.ownOperations);
        assertEquals(1L, channel.sequence(target));
    }

    @Test
    void rejectsStaleSnapshotsAfterNewerOperations() {
        LiveDocumentChannel<String, String, String, String> channel = new LiveDocumentChannel<>();
        WorkspaceTarget target = new WorkspaceTarget("text", "notes");
        RecordingListener listener = new RecordingListener();
        channel.join(target, listener);
        channel.connect(1L);
        channel.acceptSnapshot(new LiveDocumentChannel.Snapshot<>(target, 4L, "current", List.of()), 1L, 0L);
        channel.acceptOperation(new LiveDocumentChannel.Operation<>(target, 5L, "remote", "other", "Other", "change"), 1L, 0L);
        channel.acceptSnapshot(new LiveDocumentChannel.Snapshot<>(target, 4L, "stale", List.of()), 1L, 0L);

        assertEquals(5L, channel.sequence(target));
        assertEquals(List.of("current"), listener.snapshots);
    }

    @Test
    void issuesOneJoinWhenRegistrationReentersTheJoinCallback() {
        LiveDocumentChannel<String, String, String, String> channel = new LiveDocumentChannel<>();
        WorkspaceTarget target = new WorkspaceTarget("text", "notes");
        RecordingListener first = new RecordingListener();
        RecordingListener second = new RecordingListener();
        List<WorkspaceTarget> joined = new ArrayList<>();
        channel.bind(new LiveDocumentChannel.Transport<>() {
            @Override
            public void join(WorkspaceTarget joinedTarget) {
                joined.add(joinedTarget);
                channel.join(joinedTarget, second);
            }

            @Override
            public void leave(WorkspaceTarget leftTarget) {
            }

            @Override
            public boolean publishOperation(WorkspaceTarget operationTarget, long baseSequence, String operationId,
                                            String operation) {
                return true;
            }

            @Override
            public boolean publishAwareness(WorkspaceTarget awarenessTarget, String awareness) {
                return true;
            }
        });
        channel.connect(1L);

        channel.join(target, first);
        channel.acceptSnapshot(new LiveDocumentChannel.Snapshot<>(target, 0L, "document", List.of()), 1L, 1L);

        assertEquals(List.of(target), joined);
        assertEquals(List.of("document"), first.snapshots);
        assertEquals(List.of("document"), second.snapshots);
    }

    @Test
    void stopsSnapshotDeliveryWhenAnEarlierListenerRemovesTheNextListener() {
        LiveDocumentChannel<String, String, String, String> channel = new LiveDocumentChannel<>();
        WorkspaceTarget target = new WorkspaceTarget("text", "notes");
        RecordingListener removed = new RecordingListener();
        RecordingListener removing = new RecordingListener() {
            @Override
            public void onSnapshot(LiveDocumentChannel.Snapshot<String, String, String> snapshot) {
                super.onSnapshot(snapshot);
                channel.leave(target, removed);
            }
        };
        channel.join(target, removing);
        channel.join(target, removed);
        channel.connect(1L);

        channel.acceptSnapshot(new LiveDocumentChannel.Snapshot<>(target, 0L, "document", List.of()), 1L, 0L);

        assertEquals(List.of("document"), removing.snapshots);
        assertTrue(removed.snapshots.isEmpty());
    }

    @Test
    void gatesEqualSnapshotsUntilAResyncIsActive() {
        LiveDocumentChannel<String, String, String, String> channel = new LiveDocumentChannel<>();
        WorkspaceTarget target = new WorkspaceTarget("text", "notes");
        RecordingListener listener = new RecordingListener();
        channel.join(target, listener);
        channel.connect(1L);

        channel.acceptSnapshot(new LiveDocumentChannel.Snapshot<>(target, 4L, "initial", List.of()), 1L, 0L);
        channel.acceptSnapshot(new LiveDocumentChannel.Snapshot<>(target, 4L, "duplicate", List.of()), 1L, 0L);
        channel.acceptResync(target, "Refresh", 1L, 0L);
        channel.acceptSnapshot(new LiveDocumentChannel.Snapshot<>(target, 4L, "recovered", List.of()), 1L, 0L);

        assertEquals(List.of("initial", "recovered"), listener.snapshots);
        assertEquals(List.of("Refresh"), listener.resyncReasons);
    }

    @Test
    void keepsEqualSnapshotGateOpenWhileMissingOperationsCatchUp() {
        LiveDocumentChannel<String, String, String, String> channel = new LiveDocumentChannel<>();
        WorkspaceTarget target = new WorkspaceTarget("text", "notes");
        RecordingListener listener = new RecordingListener();
        channel.join(target, listener);
        channel.connect(1L);
        channel.acceptSnapshot(new LiveDocumentChannel.Snapshot<>(target, 4L, "initial", List.of()), 1L, 0L);
        channel.acceptOperation(new LiveDocumentChannel.Operation<>(target, 6L, "gap", "other", "Other", "gap"), 1L, 0L);

        channel.acceptOperation(new LiveDocumentChannel.Operation<>(target, 5L, "missing", "other", "Other", "missing"), 1L, 0L);
        channel.acceptSnapshot(new LiveDocumentChannel.Snapshot<>(target, 5L, "recovered", List.of()), 1L, 0L);

        assertEquals(List.of("initial", "recovered"), listener.snapshots);
        assertEquals(List.of("Operation Gap"), listener.resyncReasons);
        assertEquals(5L, channel.sequence(target));
    }

    @Test
    void preservesPendingOperationsAcrossTargetRejoinAndSnapshot() {
        LiveDocumentChannel<String, String, String, String> channel = new LiveDocumentChannel<>();
        WorkspaceTarget target = new WorkspaceTarget("text", "notes");
        RecordingTransport transport = new RecordingTransport();
        RecordingListener listener = new RecordingListener();
        channel.bind(transport);
        channel.join(target, listener);
        channel.connect(1L);
        channel.acceptSnapshot(new LiveDocumentChannel.Snapshot<>(target, 0L, "initial", List.of()), 1L, 1L);
        String operationId = channel.publishOperation(target, "change");

        channel.leave(target, listener);
        channel.join(target, listener);
        channel.acceptSnapshot(new LiveDocumentChannel.Snapshot<>(target, 0L, "rejoined", List.of()), 1L, 1L);
        channel.acceptOperation(new LiveDocumentChannel.Operation<>(target, 1L, operationId, "self", "Self", "change"), 1L, 1L);

        assertEquals(List.of(true), listener.ownOperations);
        assertEquals(1L, channel.sequence(target));
    }

    @Test
    void doesNotRetainSentOperationWhenTransportRejects() {
        LiveDocumentChannel<String, String, String, String> channel = new LiveDocumentChannel<>();
        WorkspaceTarget target = new WorkspaceTarget("text", "notes");
        RecordingListener listener = new RecordingListener();
        List<String> operationIds = new ArrayList<>();
        long source = channel.bind(new LiveDocumentChannel.Transport<>() {
            @Override
            public void join(WorkspaceTarget joinedTarget) {
            }

            @Override
            public void leave(WorkspaceTarget leftTarget) {
            }

            @Override
            public boolean publishOperation(WorkspaceTarget operationTarget, long baseSequence, String operationId,
                                            String operation) {
                operationIds.add(operationId);
                channel.sent(operationTarget, operationId);
                return false;
            }

            @Override
            public boolean publishAwareness(WorkspaceTarget awarenessTarget, String awareness) {
                return true;
            }
        });
        channel.join(target, listener);
        channel.connect(1L);
        channel.acceptSnapshot(new LiveDocumentChannel.Snapshot<>(target, 0L, "document", List.of()), 1L, source);

        String published = channel.publishOperation(target, "change");
        channel.acceptOperation(new LiveDocumentChannel.Operation<>(
            target, 1L, operationIds.getFirst(), "other", "Other", "remote"), 1L, source);

        assertTrue(published.isBlank());
        assertEquals(List.of(false), listener.ownOperations);
    }

    @Test
    void settlesFalseTransportResultWhenSynchronousEchoWasDelivered() {
        LiveDocumentChannel<String, String, String, String> channel = new LiveDocumentChannel<>();
        WorkspaceTarget target = new WorkspaceTarget("text", "notes");
        RecordingListener listener = new RecordingListener();
        long source = channel.bind(new LiveDocumentChannel.Transport<>() {
            @Override
            public void join(WorkspaceTarget joinedTarget) {
            }

            @Override
            public void leave(WorkspaceTarget leftTarget) {
            }

            @Override
            public boolean publishOperation(WorkspaceTarget operationTarget, long baseSequence, String operationId,
                                            String operation) {
                channel.acceptOperation(new LiveDocumentChannel.Operation<>(
                    operationTarget, 1L, operationId, "self", "Self", operation), 1L, 1L);
                return false;
            }

            @Override
            public boolean publishAwareness(WorkspaceTarget awarenessTarget, String awareness) {
                return true;
            }
        });
        channel.join(target, listener);
        channel.connect(1L);
        channel.acceptSnapshot(new LiveDocumentChannel.Snapshot<>(target, 0L, "document", List.of()), 1L, source);

        String operationId = channel.publishOperation(target, "change");
        channel.acceptOperation(new LiveDocumentChannel.Operation<>(
            target, 1L, operationId, "self", "Self", "change"), 1L, source);

        assertFalse(operationId.isBlank());
        assertEquals(List.of(true), listener.ownOperations);
        assertEquals(1L, channel.sequence(target));
    }

    @Test
    void finishesSnapshotDeliveryBeforeAReentrantOperation() {
        LiveDocumentChannel<String, String, String, String> channel = new LiveDocumentChannel<>();
        WorkspaceTarget target = new WorkspaceTarget("text", "notes");
        List<String> events = new ArrayList<>();
        RecordingListener first = new RecordingListener() {
            @Override
            public void onSnapshot(LiveDocumentChannel.Snapshot<String, String, String> snapshot) {
                events.add("first snapshot");
                channel.acceptOperation(new LiveDocumentChannel.Operation<>(target, 1L, "remote", "other", "Other", "change"), 1L, 0L);
            }

            @Override
            public void onOperation(LiveDocumentChannel.Operation<String, String> operation, boolean own) {
                events.add("first operation");
            }
        };
        RecordingListener second = new RecordingListener() {
            @Override
            public void onSnapshot(LiveDocumentChannel.Snapshot<String, String, String> snapshot) {
                events.add("second snapshot");
            }

            @Override
            public void onOperation(LiveDocumentChannel.Operation<String, String> operation, boolean own) {
                events.add("second operation");
            }
        };
        channel.join(target, first);
        channel.join(target, second);
        channel.connect(1L);

        channel.acceptSnapshot(new LiveDocumentChannel.Snapshot<>(target, 0L, "document", List.of()), 1L, 0L);

        assertEquals(List.of("first snapshot", "second snapshot", "first operation", "second operation"), events);
    }

    @Test
    void dropsQueuedCallbacksFromAReplacedMembership() {
        LiveDocumentChannel<String, String, String, String> channel = new LiveDocumentChannel<>();
        WorkspaceTarget target = new WorkspaceTarget("text", "notes");
        RecordingListener stable = new RecordingListener();
        RecordingListener replacement = new RecordingListener();
        RecordingListener first = new RecordingListener() {
            @Override
            public void onSnapshot(LiveDocumentChannel.Snapshot<String, String, String> snapshot) {
                channel.acceptAwareness(new LiveDocumentChannel.Awareness<>(target, "other", "Other", "cursor", 1L), 1L, 0L);
                channel.leave(target, this);
                channel.join(target, replacement);
            }
        };
        channel.join(target, first);
        channel.join(target, stable);
        channel.connect(1L);

        channel.acceptSnapshot(new LiveDocumentChannel.Snapshot<>(target, 0L, "document", List.of()), 1L, 0L);

        assertTrue(replacement.awareness.isEmpty());
        assertEquals(List.of("cursor"), stable.awareness);
    }

    @Test
    void rejectsCallbacksFromTheReplacedTransportSource() {
        LiveDocumentChannel<String, String, String, String> channel = new LiveDocumentChannel<>();
        WorkspaceTarget target = new WorkspaceTarget("text", "notes");
        RecordingListener listener = new RecordingListener();
        long firstSource = channel.bind(new RecordingTransport());
        channel.join(target, listener);
        channel.connect(1L);
        channel.acceptSnapshot(new LiveDocumentChannel.Snapshot<>(target, 4L, "initial", List.of()), 1L, firstSource);

        long secondSource = channel.bind(new RecordingTransport());
        channel.acceptSnapshot(new LiveDocumentChannel.Snapshot<>(target, 10L, "stale", List.of()), 1L, firstSource);
        channel.acceptSnapshot(new LiveDocumentChannel.Snapshot<>(target, 0L, "current", List.of()), 1L, secondSource);

        assertEquals(List.of("initial", "current"), listener.snapshots);
        assertEquals(0L, channel.sequence(target));
    }

    @Test
    void isolatesThrowingListenersAcrossEveryDelivery() {
        LiveDocumentChannel<String, String, String, String> channel = new LiveDocumentChannel<>();
        WorkspaceTarget target = new WorkspaceTarget("text", "notes");
        RecordingListener throwing = new RecordingListener() {
            @Override
            public void onSnapshot(LiveDocumentChannel.Snapshot<String, String, String> snapshot) {
                throw new IllegalStateException("Snapshot");
            }

            @Override
            public void onOperation(LiveDocumentChannel.Operation<String, String> operation, boolean own) {
                throw new IllegalStateException("Operation");
            }

            @Override
            public void onAwareness(LiveDocumentChannel.Awareness<String, String> awareness) {
                throw new IllegalStateException("Awareness");
            }

            @Override
            public void onResync(String reason) {
                throw new IllegalStateException("Resync");
            }
        };
        RecordingListener recording = new RecordingListener();
        channel.join(target, throwing);
        channel.join(target, recording);
        channel.connect(1L);
        LiveDocumentChannel.Awareness<String, String> awareness =
            new LiveDocumentChannel.Awareness<>(target, "other", "Other", "cursor", 1L);

        channel.acceptSnapshot(new LiveDocumentChannel.Snapshot<>(target, 0L, "document", List.of(awareness)), 1L, 0L);
        channel.acceptOperation(new LiveDocumentChannel.Operation<>(target, 1L, "remote", "other", "Other", "change"), 1L, 0L);
        channel.acceptResync(target, "Refresh", 1L, 0L);
        channel.disconnect("Disconnected");

        assertEquals(List.of("document"), recording.snapshots);
        assertEquals(List.of("cursor"), recording.awareness);
        assertEquals(List.of(false), recording.ownOperations);
        assertEquals(List.of("Refresh", "Disconnected"), recording.resyncReasons);
    }

    @Test
    void commitsAcceptanceBeforeDispatchingOrderedCallbacks() {
        LiveDocumentChannel<String, String, String, String> channel = new LiveDocumentChannel<>();
        WorkspaceTarget target = new WorkspaceTarget("text", "notes");
        ArrayDeque<Runnable> callbacks = new ArrayDeque<>();
        List<String> events = new ArrayList<>();
        RecordingListener listener = new RecordingListener() {
            @Override
            public void onSnapshot(LiveDocumentChannel.Snapshot<String, String, String> snapshot) {
                events.add("snapshot");
            }

            @Override
            public void onOperation(LiveDocumentChannel.Operation<String, String> operation, boolean own) {
                events.add("operation");
            }
        };
        long source = channel.bind(new RecordingTransport(), callbacks::addLast);
        channel.join(target, listener);
        channel.connect(1L);

        channel.acceptSnapshot(new LiveDocumentChannel.Snapshot<>(target, 0L, "document", List.of()), 1L, source);
        channel.acceptOperation(new LiveDocumentChannel.Operation<>(target, 1L, "remote", "other", "Other", "change"), 1L, source);

        assertEquals(1L, channel.sequence(target));
        assertTrue(events.isEmpty());
        while (!callbacks.isEmpty()) {
            callbacks.removeFirst().run();
        }
        assertEquals(List.of("snapshot", "operation"), events);
    }

    @Test
    void dropsQueuedDeliveryAfterListenerRejoins() {
        LiveDocumentChannel<String, String, String, String> channel = new LiveDocumentChannel<>();
        WorkspaceTarget target = new WorkspaceTarget("text", "notes");
        ArrayDeque<Runnable> callbacks = new ArrayDeque<>();
        RecordingListener listener = new RecordingListener();
        long source = channel.bind(new RecordingTransport(), callbacks::addLast);
        channel.join(target, listener);
        channel.connect(1L);
        channel.acceptSnapshot(new LiveDocumentChannel.Snapshot<>(target, 0L, "stale", List.of()), 1L, source);

        channel.leave(target, listener);
        channel.join(target, listener);
        while (!callbacks.isEmpty()) {
            callbacks.removeFirst().run();
        }

        assertTrue(listener.snapshots.isEmpty());
    }

    @Test
    void keepsQueuedDisconnectWhenListenerRemainsRegisteredElsewhere() {
        LiveDocumentChannel<String, String, String, String> channel = new LiveDocumentChannel<>();
        WorkspaceTarget first = new WorkspaceTarget("text", "first");
        WorkspaceTarget second = new WorkspaceTarget("text", "second");
        ArrayDeque<Runnable> callbacks = new ArrayDeque<>();
        RecordingListener listener = new RecordingListener();
        channel.bind(new RecordingTransport(), callbacks::addLast);
        channel.join(first, listener);
        channel.join(second, listener);
        channel.connect(1L);

        channel.disconnect("Disconnected");
        channel.leave(first, listener);
        channel.join(first, listener);
        while (!callbacks.isEmpty()) {
            callbacks.removeFirst().run();
        }

        assertEquals(List.of("Disconnected"), listener.resyncReasons);
    }

    @Test
    void dropsQueuedDisconnectAfterListenerFullyRejoins() {
        LiveDocumentChannel<String, String, String, String> channel = new LiveDocumentChannel<>();
        WorkspaceTarget target = new WorkspaceTarget("text", "notes");
        ArrayDeque<Runnable> callbacks = new ArrayDeque<>();
        RecordingListener listener = new RecordingListener();
        channel.bind(new RecordingTransport(), callbacks::addLast);
        channel.join(target, listener);
        channel.connect(1L);

        channel.disconnect("Disconnected");
        channel.leave(target, listener);
        channel.join(target, listener);
        while (!callbacks.isEmpty()) {
            callbacks.removeFirst().run();
        }

        assertTrue(listener.resyncReasons.isEmpty());
    }

    private static class RecordingListener implements LiveDocumentChannel.Listener<String, String, String, String> {
        private final List<String> snapshots = new ArrayList<>();
        private final List<Boolean> ownOperations = new ArrayList<>();
        private final List<String> resyncReasons = new ArrayList<>();
        private final List<String> awareness = new ArrayList<>();

        @Override
        public void onSnapshot(LiveDocumentChannel.Snapshot<String, String, String> snapshot) {
            snapshots.add(snapshot.document());
        }

        @Override
        public void onOperation(LiveDocumentChannel.Operation<String, String> operation, boolean own) {
            ownOperations.add(own);
        }

        @Override
        public void onAwareness(LiveDocumentChannel.Awareness<String, String> awareness) {
            this.awareness.add(awareness.state());
        }

        @Override
        public void onResync(String reason) {
            resyncReasons.add(reason);
        }
    }

    private static final class RecordingTransport implements LiveDocumentChannel.Transport<String, String> {
        private final List<WorkspaceTarget> joined = new ArrayList<>();
        private final List<String> operationIds = new ArrayList<>();

        @Override
        public void join(WorkspaceTarget target) {
            joined.add(target);
        }

        @Override
        public void leave(WorkspaceTarget target) {
        }

        @Override
        public boolean publishOperation(WorkspaceTarget target, long baseSequence, String operationId, String operation) {
            operationIds.add(operationId);
            return true;
        }

        @Override
        public boolean publishAwareness(WorkspaceTarget target, String awareness) {
            return true;
        }
    }
}
