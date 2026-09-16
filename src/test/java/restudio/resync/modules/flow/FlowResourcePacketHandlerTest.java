package restudio.resync.modules.flow;

import org.junit.jupiter.api.Test;
import restudio.resync.core.Session;
import restudio.resync.jobs.JobRecord;
import restudio.resync.resources.ReSyncManagedResource;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.server.AuthorityEpoch;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowResourcePacketHandlerTest {
    private static final ReSyncManagedResource DESCRIPTOR = ReSyncResourceCatalog.byType(ReSyncResourceCatalog.GUI);

    @Test
    void failedSessionCompletionReleasesTheSaveLease() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        TestAdapter adapter = new TestAdapter();
        RecordingSender sender = new RecordingSender();
        registry.register(adapter);
        adapter.failAfterSave = true;

        handler(adapter, sender, registry).handle(new Session("session", "client", null),
            DESCRIPTOR.flowPackets().save(), savePacket("request", "main"));

        assertEquals(1, sender.failures);
        assertEquals(0, sender.successes);
        assertEquals(1, sender.completed);
        adapter.failAfterSave = false;
        assertTrue(registry.save(DESCRIPTOR.typeId(), "main").success());
    }

    @Test
    void successfulSessionSaveKeepsTheTrackedRefreshLeaseUntilRuntimeCompletion() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        TestAdapter adapter = new TestAdapter();
        RecordingSender sender = new RecordingSender();
        ArrayDeque<Runnable> refreshes = new ArrayDeque<>();
        registry.register(adapter);
        registry.setLiveRefreshExecutor(refreshes::add);

        handler(adapter, sender, registry).handle(new Session("session", "client", null),
            DESCRIPTOR.flowPackets().save(), savePacket("request", "main"));

        assertEquals(1, refreshes.size());
        assertEquals(0, sender.successes);
        assertEquals(0, sender.completed);
        assertFalse(registry.save(DESCRIPTOR.typeId(), "main").success());
        refreshes.remove().run();
        assertTrue(registry.save(DESCRIPTOR.typeId(), "main").success());
        assertEquals(1, sender.successes);
        assertEquals(1, sender.completed);
    }

    @Test
    void failedQueuedSaveReportsFailureOnlyAfterRuntimeCompletion() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        TestAdapter adapter = new TestAdapter();
        RecordingSender sender = new RecordingSender();
        ArrayDeque<Runnable> refreshes = new ArrayDeque<>();
        registry.register(adapter);
        registry.setLiveRefreshExecutor(refreshes::add);
        adapter.failAfterSave = true;

        handler(adapter, sender, registry).handle(new Session("session", "client", null),
            DESCRIPTOR.flowPackets().save(), savePacket("request", "main"));

        assertEquals(0, sender.successes);
        assertEquals(0, sender.failures);
        assertEquals(0, sender.completed);
        refreshes.remove().run();
        assertEquals(0, sender.successes);
        assertEquals(1, sender.failures);
        assertEquals(1, sender.completed);
        assertTrue(registry.save(DESCRIPTOR.typeId(), "main").success());
    }

    @Test
    void jobCancellationForcesTheExactQueuedSaveFinalizer() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        TestAdapter adapter = new TestAdapter();
        RecordingSender sender = new RecordingSender();
        ArrayDeque<Runnable> refreshes = new ArrayDeque<>();
        AtomicReference<String> published = new AtomicReference<>();
        registry.register(adapter);
        registry.setLiveRefreshExecutor(refreshes::add);
        registry.setMutationListener(new FlowResourceMutationListener() {
            @Override
            public void saved(String type, String resourceId, String payload) {
                published.set(type + ":" + resourceId);
            }

            @Override
            public void deleted(String type, String resourceId) {
            }
        });

        handler(adapter, sender, registry).handle(new Session("session", "client", null),
            DESCRIPTOR.flowPackets().save(), savePacket("request", "main"));
        sender.cancelJob();

        assertEquals("gui:main", published.get());
        assertEquals(0, sender.successes);
        assertEquals(0, sender.failures);
        assertEquals(1, sender.completed);
        assertTrue(registry.save(DESCRIPTOR.typeId(), "main").success());
        refreshes.remove().run();
        assertEquals(1, sender.completed);
    }

    @Test
    void completedSaveCancellationCannotFinalizeANewerSameKeySave() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        TestAdapter adapter = new TestAdapter();
        RecordingSender sender = new RecordingSender();
        Session session = new Session("session", "client", null);
        registry.register(adapter);
        adapter.onSave = sender::cancelJob;
        sender.afterCompletion = () -> registry.saveFromSession(session, adapter, "main");

        handler(adapter, sender, registry).handle(session,
            DESCRIPTOR.flowPackets().save(), savePacket("request", "main"));

        assertEquals(1, sender.completed);
        assertFalse(registry.save(DESCRIPTOR.typeId(), "main").success());
        registry.completeSessionSave(session, adapter, "main");
        assertTrue(registry.save(DESCRIPTOR.typeId(), "main").success());
    }

    @Test
    void failedSavePublicationDoesNotReportSuccess() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        TestAdapter adapter = new TestAdapter();
        RecordingSender sender = new RecordingSender();
        registry.register(adapter);
        registry.setCommitListener(new FlowResourceCommitListener() {
            @Override
            public void saved(String type, String resourceId, String payload) {
                throw new IllegalStateException("publication failed");
            }

            @Override
            public void deleted(String type, String resourceId) {
            }
        });

        handler(adapter, sender, registry).handle(new Session("session", "client", null),
            DESCRIPTOR.flowPackets().save(), savePacket("request", "main"));

        assertEquals(0, sender.successes);
        assertEquals(1, sender.failures);
        assertEquals(1, sender.completed);
        assertTrue(registry.save(DESCRIPTOR.typeId(), "main").success());
    }

    @Test
    void failedDeleteDoesNotCancelAnotherOperationForTheSameKey() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        TestAdapter adapter = new TestAdapter();
        RecordingSender sender = new RecordingSender();
        ArrayDeque<Runnable> refreshes = new ArrayDeque<>();
        Session session = new Session("session", "client", null);
        FlowResourcePacketHandler<String> handler = handler(adapter, sender, registry);
        registry.register(adapter);
        registry.setLiveRefreshExecutor(refreshes::add);

        handler.handle(session, DESCRIPTOR.flowPackets().save(), savePacket("save", "main"));
        handler.handle(session, DESCRIPTOR.flowPackets().delete(), deletePacket("delete", "main"));

        assertEquals(1, sender.failures);
        assertEquals(1, sender.completed);
        assertEquals(1, refreshes.size());
        assertFalse(registry.save(DESCRIPTOR.typeId(), "main").success());
        refreshes.remove().run();
        assertTrue(registry.save(DESCRIPTOR.typeId(), "main").success());
        assertEquals(1, sender.successes);
        assertEquals(2, sender.completed);
    }

    @Test
    void deleteUsesTheTrackedRefreshLifecycle() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        TestAdapter adapter = new TestAdapter();
        RecordingSender sender = new RecordingSender();
        ArrayDeque<Runnable> refreshes = new ArrayDeque<>();
        registry.register(adapter);
        adapter.values.put("main", "main");
        registry.setLiveRefreshExecutor(refreshes::add);
        Session session = new Session("session", "client", null);
        AtomicReference<Session> deletedBy = new AtomicReference<>();
        registry.setCommitListener(new FlowResourceCommitListener() {
            @Override
            public void saved(String type, String resourceId, String payload) {
            }

            @Override
            public void deleted(String type, String resourceId) {
            }

            @Override
            public void deleted(Session actor, String type, String resourceId) {
                deletedBy.set(actor);
            }
        });

        handler(adapter, sender, registry).handle(session,
            DESCRIPTOR.flowPackets().delete(), deletePacket("request", "main"));

        assertNull(adapter.get("main"));
        assertEquals(1, refreshes.size());
        assertEquals(0, sender.successes);
        assertEquals(0, sender.completed);
        assertNull(deletedBy.get());
        assertFalse(registry.save(DESCRIPTOR.typeId(), "main").success());
        refreshes.remove().run();
        assertTrue(registry.save(DESCRIPTOR.typeId(), "main").success());
        assertEquals(1, sender.successes);
        assertEquals(1, sender.completed);
        assertEquals(session, deletedBy.get());
    }

    @Test
    void failedDeleteRefreshPublishesTheDeletionWithoutReportingSuccess() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        TestAdapter adapter = new TestAdapter();
        RecordingSender sender = new RecordingSender();
        ArrayDeque<Runnable> refreshes = new ArrayDeque<>();
        registry.register(adapter);
        adapter.values.put("main", "main");
        adapter.failAfterDelete = true;
        registry.setLiveRefreshExecutor(refreshes::add);
        AtomicReference<String> deleted = new AtomicReference<>();
        registry.setMutationListener(new FlowResourceMutationListener() {
            @Override
            public void saved(String type, String resourceId, String payload) {
            }

            @Override
            public void deleted(String type, String resourceId) {
                deleted.set(type + ":" + resourceId);
            }
        });

        handler(adapter, sender, registry).handle(new Session("session", "client", null),
            DESCRIPTOR.flowPackets().delete(), deletePacket("request", "main"));

        assertEquals(0, sender.successes);
        assertEquals(0, sender.failures);
        refreshes.remove().run();
        assertEquals(0, sender.successes);
        assertEquals(1, sender.failures);
        assertEquals(1, sender.completed);
        assertEquals("gui:main", deleted.get());
        assertTrue(registry.save(DESCRIPTOR.typeId(), "main").success());
    }

    @Test
    void globalCancellationFinalizesAQueuedDurableDelete() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        TestAdapter adapter = new TestAdapter();
        RecordingSender sender = new RecordingSender();
        ArrayDeque<Runnable> refreshes = new ArrayDeque<>();
        AtomicReference<String> deleted = new AtomicReference<>();
        registry.register(adapter);
        adapter.values.put("main", "main");
        registry.setLiveRefreshExecutor(refreshes::add);
        registry.setMutationListener(new FlowResourceMutationListener() {
            @Override
            public void saved(String type, String resourceId, String payload) {
            }

            @Override
            public void deleted(String type, String resourceId) {
                deleted.set(type + ":" + resourceId);
            }
        });

        handler(adapter, sender, registry).handle(new Session("session", "client", null),
            DESCRIPTOR.flowPackets().delete(), deletePacket("request", "main"));
        registry.cancelPendingLiveRefreshes();

        assertEquals("gui:main", deleted.get());
        assertEquals(1, sender.successes);
        assertEquals(0, sender.failures);
        assertEquals(1, sender.completed);
        assertTrue(registry.save(DESCRIPTOR.typeId(), "main").success());
        refreshes.remove().run();
        assertEquals(1, sender.completed);
    }

    @Test
    void genericAuthorityFenceRejectsLegacyMutationPackets() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        TestAdapter adapter = new TestAdapter();
        RecordingSender sender = new RecordingSender();
        registry.register(adapter);
        registry.requireGenericMutationAuthority("Use generic resource protocol");

        handler(adapter, sender, registry).handle(new Session("session", "client", null),
            DESCRIPTOR.flowPackets().save(), savePacket("request", "main"));

        assertEquals(List.of("RESOURCE_GENERIC_AUTHORITY_REQUIRED"), sender.errorCodes);
        assertEquals(0, sender.started);
    }

    @Test
    void staleSaveEpochIsRejectedBeforeJobOrLeaseAdmission() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        TestAdapter adapter = new TestAdapter();
        RecordingSender sender = new RecordingSender();
        registry.register(adapter);
        FlowResourcePacketHandler<String> handler = new FlowResourcePacketHandler<>(adapter, sender, registry,
            AuthorityEpoch.fixed(4L), false);

        handler.handle(new Session("session", "client", null), DESCRIPTOR.flowPackets().save(),
            savePacket("request", "main", 3L));

        assertEquals(List.of(FlowMutationPayloadReader.AUTHORITY_EPOCH_STALE_CODE), sender.errorCodes);
        assertEquals(0, sender.started);
        assertTrue(registry.save(DESCRIPTOR.typeId(), "main").success());
    }

    @Test
    void futureDeleteEpochIsRejectedBeforeResourceStateChanges() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        TestAdapter adapter = new TestAdapter();
        RecordingSender sender = new RecordingSender();
        registry.register(adapter);
        adapter.values.put("main", "main");
        FlowResourcePacketHandler<String> handler = new FlowResourcePacketHandler<>(adapter, sender, registry,
            AuthorityEpoch.fixed(4L), false);

        handler.handle(new Session("session", "client", null), DESCRIPTOR.flowPackets().delete(),
            deletePacket("request", "{\"id\":\"main\",\"authorityEpoch\":5}"));

        assertEquals(List.of(FlowMutationPayloadReader.AUTHORITY_EPOCH_FUTURE_CODE), sender.errorCodes);
        assertEquals(0, sender.started);
        assertEquals("main", adapter.get("main"));
        assertTrue(registry.save(DESCRIPTOR.typeId(), "main").success());
    }

    @Test
    void omittedEpochRequiresLegacyCompatibility() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        TestAdapter adapter = new TestAdapter();
        RecordingSender sender = new RecordingSender();
        registry.register(adapter);
        FlowResourcePacketHandler<String> handler = new FlowResourcePacketHandler<>(adapter, sender, registry,
            AuthorityEpoch.fixed(4L), false);

        handler.handle(new Session("session", "client", null), DESCRIPTOR.flowPackets().save(),
            legacySavePacket("request", "main"));

        assertEquals(List.of(FlowMutationPayloadReader.AUTHORITY_EPOCH_REQUIRED_CODE), sender.errorCodes);
        assertEquals(0, sender.started);
        assertTrue(registry.save(DESCRIPTOR.typeId(), "main").success());
    }

    @Test
    void exactEpochIsAcceptedForEpochAwarePeer() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        TestAdapter adapter = new TestAdapter();
        RecordingSender sender = new RecordingSender();
        registry.register(adapter);
        FlowResourcePacketHandler<String> handler = new FlowResourcePacketHandler<>(adapter, sender, registry,
            AuthorityEpoch.fixed(4L), false);

        handler.handle(new Session("session", "client", null), DESCRIPTOR.flowPackets().save(),
            savePacket("request", "main", 4L));

        assertTrue(sender.errorCodes.isEmpty());
        assertEquals(1, sender.started);
        assertEquals(1, sender.successes);
        assertEquals(1, sender.completed);
    }

    private static ByteBuffer savePacket(String requestId, String id) {
        return packet(requestId, "{\"id\":\"" + id + "\",\"authorityEpoch\":1}");
    }

    private static ByteBuffer legacySavePacket(String requestId, String id) {
        return packet(requestId, "{\"id\":\"" + id + "\"}");
    }

    private static <T> FlowResourcePacketHandler<T> handler(FlowResourceAdapter<T> adapter, FlowPacketSender sender,
                                                             FlowResourceRegistry registry) {
        return new FlowResourcePacketHandler<>(adapter, sender, registry, AuthorityEpoch.fixed(1L), false);
    }

    private static ByteBuffer savePacket(String requestId, String id, long authorityEpoch) {
        return packet(requestId, "{\"id\":\"" + id + "\",\"authorityEpoch\":" + authorityEpoch + "}");
    }

    private static ByteBuffer deletePacket(String requestId, String id) {
        return packet(requestId, id.strip().startsWith("{") ? id
            : "{\"id\":\"" + id + "\",\"authorityEpoch\":1}");
    }

    private static ByteBuffer packet(String requestId, String payload) {
        byte[] requestBytes = requestId.getBytes(StandardCharsets.UTF_8);
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES + requestBytes.length + payloadBytes.length);
        buffer.putInt(requestBytes.length).put(requestBytes).put(payloadBytes).flip();
        return buffer;
    }

    private static final class RecordingSender extends FlowPacketSender {
        private final List<String> errorCodes = new ArrayList<>();
        private int started;
        private int successes;
        private int failures;
        private int completed;
        private Runnable cancellation;
        private Runnable afterCompletion;

        private RecordingSender() {
            super(null, 0, Set.of());
        }

        @Override
        public JobRecord<String> beginJob(Session session, String action, String target, String requestId) {
            started++;
            JobRecord<String> job = new JobRecord<>(UUID.randomUUID().toString(), requestId, action,
                session != null ? session.getClientId() : "client", target);
            job.markRunning();
            return job;
        }

        @Override
        public void setJobCancellation(JobRecord<?> job, Runnable cancellation) {
            this.cancellation = cancellation;
        }

        @Override
        public void succeedJob(JobRecord<String> job, String result, String message) {
            successes++;
        }

        @Override
        public void failJob(JobRecord<String> job, String message, Throwable throwable) {
            failures++;
        }

        @Override
        public void completeJobExecution(JobRecord<?> job) {
            completed++;
            if (afterCompletion != null) {
                Runnable action = afterCompletion;
                afterCompletion = null;
                action.run();
            }
        }

        @Override
        public void sendError(Session session, String errorCode, String message) {
            errorCodes.add(errorCode);
        }

        private void cancelJob() {
            cancellation.run();
        }
    }

    private static final class TestAdapter implements FlowResourceAdapter<String> {
        private final Map<String, String> values = new HashMap<>();
        private boolean failAfterSave;
        private boolean failAfterDelete;
        private Runnable onSave;

        @Override
        public ReSyncManagedResource descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public String get(String id) {
            return values.get(id);
        }

        @Override
        public List<String> listIds() {
            return List.copyOf(values.keySet());
        }

        @Override
        public String deserialize(String json) {
            int start = json.indexOf(":\"") + 2;
            int end = json.indexOf('"', start);
            return json.substring(start, end);
        }

        @Override
        public String serialize(String value) {
            return "{\"id\":\"" + value + "\"}";
        }

        @Override
        public String id(String value) {
            return value;
        }

        @Override
        public void save(String value) {
            values.put(value, value);
            if (onSave != null) {
                Runnable action = onSave;
                onSave = null;
                action.run();
            }
        }

        @Override
        public void delete(String id) {
            values.remove(id);
        }

        @Override
        public void afterSave(String value) {
            if (failAfterSave) {
                throw new IllegalStateException("refresh failed");
            }
        }

        @Override
        public void afterDelete(String id) {
            if (failAfterDelete) {
                throw new IllegalStateException("refresh failed");
            }
        }
    }
}
