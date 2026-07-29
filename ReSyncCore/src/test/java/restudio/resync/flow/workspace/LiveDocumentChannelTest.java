package restudio.resync.flow.workspace;

import org.junit.jupiter.api.Test;

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

        channel.connect();
        channel.acceptSnapshot(new LiveDocumentChannel.Snapshot<>(target, 7L, "document", List.of()));
        String operationId = channel.publishOperation(target, "change");
        channel.disconnect("Disconnected");
        channel.connect();

        assertEquals(List.of(target, target), transport.joined);
        assertEquals(0L, channel.sequence(target));
        assertEquals(List.of("Disconnected"), listener.resyncReasons);
        assertFalse(operationId.isBlank());
        assertEquals(operationId, transport.operationIds.getFirst());
    }

    @Test
    void registersOptimisticOperationBeforeTransportAcknowledgement() {
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
                    operationTarget, 1L, operationId, "self", "Self", operation));
                return true;
            }

            @Override
            public boolean publishAwareness(WorkspaceTarget awarenessTarget, String awareness) {
                return true;
            }
        });
        channel.connect();
        channel.acceptSnapshot(new LiveDocumentChannel.Snapshot<>(target, 0L, "document", List.of()));

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
        channel.acceptSnapshot(new LiveDocumentChannel.Snapshot<>(target, 4L, "current", List.of()));
        channel.acceptOperation(new LiveDocumentChannel.Operation<>(target, 5L, "remote", "other", "Other", "change"));
        channel.acceptSnapshot(new LiveDocumentChannel.Snapshot<>(target, 4L, "stale", List.of()));

        assertEquals(5L, channel.sequence(target));
        assertEquals(List.of("current"), listener.snapshots);
    }

    private static final class RecordingListener implements LiveDocumentChannel.Listener<String, String, String, String> {
        private final List<String> snapshots = new ArrayList<>();
        private final List<Boolean> ownOperations = new ArrayList<>();
        private final List<String> resyncReasons = new ArrayList<>();

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
