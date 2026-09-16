package restudio.resync.modules.flow;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import restudio.flow.data.FlowOperationResult;
import org.junit.jupiter.api.Test;
import restudio.resync.core.Session;
import restudio.resync.flow.ResourceRevisionConflictException;
import restudio.resync.flow.sync.FlowResourceMetadata;
import restudio.resync.resources.ReSyncManagedResource;
import restudio.resync.resources.ReSyncResourceCatalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowResourceRegistryTest {
    @Test
    void advertisesOnlyOperationsBackedByTheRegisteredAuthority() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        registry.register(new TestAdapter());

        FlowResourceMetadata gui = registry.metadata().stream().filter(value -> "gui".equals(value.getTypeId())).findFirst().orElseThrow();
        FlowResourceMetadata world = registry.metadata().stream().filter(value -> "world".equals(value.getTypeId())).findFirst().orElseThrow();

        assertTrue(gui.isAvailable());
        assertEquals(2, gui.getSchemaVersion());
        assertEquals("resource_reference<gui>", gui.getReferenceType());
        assertEquals("trusted_server_flow", gui.getAuthorizationPolicy());
        assertTrue(gui.isAudited());
        assertEquals(List.of("create", "delete", "discover", "duplicate", "get", "query", "save", "update", "validate"), gui.getOperations());
        assertEquals("available", gui.getOperationAvailability().get("duplicate"));
        assertEquals("This resource domain does not expose a durable presentation rename transaction", gui.getOperationAvailability().get("rename"));
        assertEquals("This resource domain does not expose a durable presentation move transaction", gui.getOperationAvailability().get("move"));
        assertEquals("This resource domain does not expose a session-bound durable resource subscription", gui.getOperationAvailability().get("subscribe"));
        assertEquals("This resource domain does not expose an explicit reload operation", gui.getOperationAvailability().get("reload"));
        assertEquals("This resource domain does not expose a generic apply operation", gui.getOperationAvailability().get("apply"));
        assertFalse(world.isAvailable());
        assertTrue(world.getOperations().isEmpty());
        assertEquals("No authoritative lifecycle adapter is registered", world.getOperationAvailability().get("get"));
        assertEquals("No authoritative lifecycle adapter is registered", world.getUnavailableReason());

        assertTrue(registry.save("gui", "main").success());
        assertEquals("main", registry.discover("gui", "mai").value().getFirst().id());
        assertEquals("main", registry.query("gui", "mai").value().getFirst().id());
        assertFalse(registry.create("gui", "main").success());
        assertFalse(registry.update("gui", "missing").success());
        var unsupportedApply = registry.apply("gui", "main", Map.of());
        assertFalse(unsupportedApply.success());
        assertEquals("This resource domain does not expose a generic apply operation", unsupportedApply.details().get("reason"));
        assertTrue(registry.duplicate("gui", "main", "copy").success());
        assertEquals("copy", registry.get("gui", "copy").value());
        var preview = registry.previewDelete("gui", "main", FlowResourceMutationContext.system());
        assertTrue(preview.success());
        assertTrue((Boolean) preview.details().get("wouldDelete"));
        assertEquals("main", registry.get("gui", "main").value());
        assertTrue(registry.delete("gui", "main").success());
        assertFalse(registry.get("gui", "main").success());
        assertEquals(7, registry.auditSnapshot().size());
        assertEquals("delete", registry.auditSnapshot().getLast().operation());
    }

    @Test
    void exposesTheGenericAuthorityFenceForLegacyPacketWriters() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        assertFalse(registry.genericMutationAuthorityRequired());

        registry.requireGenericMutationAuthority("Use the generic resource protocol");
        assertTrue(registry.genericMutationAuthorityRequired());
        assertEquals("Use the generic resource protocol", registry.genericMutationAuthorityReason());

        registry.clearGenericMutationAuthorityRequirement();
        assertFalse(registry.genericMutationAuthorityRequired());
    }

    @Test
    void mutationLeasesCanBeClosedFromAnotherThread() throws Exception {
        FlowResourceMutationAdmission admission = new FlowResourceMutationAdmission();
        FlowResourceKey key = new FlowResourceKey("gui", "main");
        FlowResourceMutationLease lease = admission.acquire(key);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> close = executor.submit(lease::close);
            close.get(2, TimeUnit.SECONDS);
            FlowResourceMutationLease replacement = admission.tryAcquire(key).orElseThrow();
            replacement.close();
        } finally {
            executor.shutdownNow();
            lease.close();
        }
    }

    @Test
    void continuationRetainsTheClaimAcrossOwnerClose() {
        FlowResourceMutationAdmission admission = new FlowResourceMutationAdmission();
        FlowResourceKey key = new FlowResourceKey("gui", "main");
        FlowResourceMutationLease owner = admission.acquire(key);
        FlowResourceMutationLease continuation = admission.tryContinue(List.of(key), owner.mutationId()).orElseThrow();

        owner.close();
        assertTrue(admission.isAdmitted(key));
        continuation.close();
        assertFalse(admission.isAdmitted(key));
    }

    @Test
    void staleLeaseCloseCannotReleaseAReplacementToken() {
        FlowResourceMutationAdmission admission = new FlowResourceMutationAdmission();
        FlowResourceKey key = new FlowResourceKey("gui", "main");
        FlowResourceMutationLease first = admission.acquire(key);
        first.close();
        FlowResourceMutationLease second = admission.acquire(key);

        first.close();
        assertTrue(admission.tryAcquire(key).isEmpty());
        second.close();
        FlowResourceMutationLease replacement = admission.tryAcquire(key).orElseThrow();
        replacement.close();
    }

    @Test
    void multiKeyAdmissionRejectsDuplicatesAndRollsBackPartialClaims() {
        FlowResourceMutationAdmission admission = new FlowResourceMutationAdmission();
        FlowResourceKey source = new FlowResourceKey("gui", "source");
        FlowResourceKey target = new FlowResourceKey("gui", "target");
        FlowResourceMutationLease sourceLease = admission.acquire(source);

        assertTrue(admission.tryAcquire(List.of(source, source)).isEmpty());
        assertTrue(admission.tryAcquire(List.of(source, target)).isEmpty());
        sourceLease.close();
        FlowResourceMutationLease ordered = admission.acquire(List.of(target, source));
        assertEquals(List.of(source, target), ordered.keys());
        ordered.close();
        FlowResourceMutationLease targetLease = admission.tryAcquire(List.of(target)).orElseThrow();
        targetLease.close();
    }

    @Test
    void registryCopiesShareResourceMutationAdmission() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        registry.register(new TestAdapter());
        FlowResourceRegistry copy = registry.copy();
        assertSame(registry.mutationAdmission(), copy.mutationAdmission());

        FlowResourceMutationLease lease = registry.mutationAdmission().acquire("gui", "main");
        try {
            assertFalse(copy.save("gui", "main").success());
        } finally {
            lease.close();
        }
        assertTrue(copy.save("gui", "main").success());
    }

    @Test
    void queuedRefreshKeepsTheMutationLeaseUntilCompletion() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        registry.register(new TestAdapter());
        AtomicReference<Runnable> queued = new AtomicReference<>();
        registry.setLiveRefreshExecutor(queued::set);

        assertTrue(registry.save("gui", "main").success());
        assertFalse(registry.save("gui", "main").success());
        queued.get().run();
        assertTrue(registry.save("gui", "main").success());
        queued.get().run();
    }

    @Test
    void anExistingMutationLeaseCanCrossAnAuthorityContextWithoutReacquisition() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        registry.register(new TestAdapter());
        FlowResourceMutationLease lease = registry.mutationAdmission().acquire("gui", "main");
        FlowResourceMutationContext context = new FlowResourceMutationContext("flow", "flow", "node", "server", lease);

        try {
            assertTrue(registry.save("gui", "main", context).success());
            assertTrue(lease.active());
        } finally {
            lease.close();
        }
    }

    @Test
    void anExistingMutationIdResolvesTheOwnedLeaseWithoutReacquisition() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        registry.register(new TestAdapter());
        FlowResourceMutationLease lease = registry.mutationAdmission().acquire(List.of(new FlowResourceKey("gui", "main")), "mutation-1");
        FlowResourceMutationContext context = new FlowResourceMutationContext("flow", "flow", "node", "server", "mutation-1");

        try {
            assertTrue(registry.save("gui", "main", context).success());
            assertTrue(lease.active());
        } finally {
            lease.close();
        }
    }

    @Test
    void mutationContextRejectsALeaseAndTokenMismatch() {
        FlowResourceMutationAdmission admission = new FlowResourceMutationAdmission();
        FlowResourceMutationLease lease = admission.acquire("gui", "main");
        try {
            assertThrows(IllegalArgumentException.class,
                () -> new FlowResourceMutationContext("flow", "flow", "node", "server", lease, "other"));
        } finally {
            lease.close();
        }
    }

    @Test
    void exactMutationContextRequiresTheBoundPayloadHash() {
        FlowResourceMutationAdmission admission = new FlowResourceMutationAdmission();
        UUID mutationId = UUID.randomUUID();
        FlowResourceMutationLease lease = admission.acquire(List.of(new FlowResourceKey("gui", "main")), mutationId.toString());
        try {
            assertThrows(IllegalArgumentException.class,
                () -> new FlowResourceMutationContext("protocol", "", "", "client", lease, mutationId, 0L));
            assertThrows(IllegalArgumentException.class,
                () -> new FlowResourceMutationContext("protocol", "", "", "client", lease, mutationId, 0L,
                    "A".repeat(64)));
        } finally {
            lease.close();
        }
    }

    @Test
    void missingContinuationCannotReacquireTheResource() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        registry.register(new TestAdapter());
        FlowResourceMutationContext context = FlowResourceMutationContext.withMutationId("flow", "flow", "node", "server", "missing");

        var result = registry.save("gui", "main", context);

        assertFalse(result.success());
        assertEquals("RESOURCE_MUTATION_CONTINUATION_MISSING", result.errorCode());
        assertTrue(registry.save("gui", "main").success());
    }

    @Test
    void deferredCompletionRetainsItsLeaseUntilRunAndRejectsDoubleRun() {
        FlowResourceMutationAdmission admission = new FlowResourceMutationAdmission();
        FlowResourceKey key = new FlowResourceKey("gui", "main");
        FlowResourceMutationLease lease = admission.acquire(key);
        FlowResourceMutationLease.DeferredCompletion completion = lease.defer(() -> {
        });

        lease.close();
        assertTrue(admission.isAdmitted(key));
        completion.run();
        assertFalse(admission.isAdmitted(key));
        assertThrows(IllegalStateException.class, completion::run);
    }

    @Test
    void deferredCompletionRunWinsCancellationRace() throws Exception {
        FlowResourceMutationAdmission admission = new FlowResourceMutationAdmission();
        FlowResourceKey key = new FlowResourceKey("gui", "main");
        FlowResourceMutationLease lease = admission.acquire(key);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FlowResourceMutationLease.DeferredCompletion completion = lease.defer(() -> {
            entered.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> run = executor.submit(completion::run);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertFalse(completion.cancel());
            assertTrue(admission.isAdmitted(key));
            release.countDown();
            run.get(2, TimeUnit.SECONDS);
            lease.close();
            assertFalse(admission.isAdmitted(key));
        } finally {
            release.countDown();
            executor.shutdownNow();
            lease.close();
        }
    }

    @Test
    void deferredCompletionCancelWinsCancellationRace() {
        FlowResourceMutationAdmission admission = new FlowResourceMutationAdmission();
        FlowResourceKey key = new FlowResourceKey("gui", "main");
        FlowResourceMutationLease lease = admission.acquire(key);
        AtomicInteger executions = new AtomicInteger();
        FlowResourceMutationLease.DeferredCompletion completion = lease.defer(executions::incrementAndGet);

        assertTrue(completion.cancel());
        assertTrue(admission.isAdmitted(key));
        lease.close();
        assertFalse(admission.isAdmitted(key));
        assertEquals(0, executions.get());
        assertThrows(IllegalStateException.class, completion::run);
    }

    @Test
    void duplicateSourceAndTargetIsDeterministicallyInvalidInput() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        registry.register(new TestAdapter());

        var result = registry.duplicate("gui", "main", "main");

        assertFalse(result.success());
        assertEquals("RESOURCE_MUTATION_INVALID_INPUT", result.errorCode());
        assertTrue(registry.save("gui", "main").success());
    }

    @Test
    void explicitMissingContextCannotReuseTheCurrentThreadLease() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        AtomicReference<FlowOperationResult<?>> nested = new AtomicReference<>();
        TestAdapter adapter = new TestAdapter() {
            private boolean nestedSave;

            @Override
            public void save(String value) {
                if (!nestedSave) {
                    nestedSave = true;
                    nested.set(registry.save("gui", value,
                        FlowResourceMutationContext.withMutationId("flow", "flow", "node", "server", "other")));
                }
                super.save(value);
            }
        };
        registry.register(adapter);
        FlowResourceMutationLease lease = registry.mutationAdmission().acquire("gui", "main");
        try {
            assertTrue(registry.save("gui", "main", new FlowResourceMutationContext("flow", "flow", "node", "server", lease)).success());
            assertEquals("RESOURCE_MUTATION_CONTINUATION_MISSING", nested.get().errorCode());
        } finally {
            lease.close();
        }
    }

    @Test
    void aDifferentSessionCannotCancelOrCompleteAnotherSessionSave() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        TestAdapter adapter = new TestAdapter();
        registry.register(adapter);
        Session owner = new Session("owner", "client", null);
        Session other = new Session("other", "client", null);

        registry.saveFromSession(owner, adapter, "main");

        assertFalse(registry.cancelSessionSave(other, adapter, "main"));
        assertThrows(IllegalStateException.class, () -> registry.completeSessionSave(other, adapter, "main"));
        assertFalse(registry.save("gui", "main").success());
        assertTrue(registry.cancelSessionSave(owner, adapter, "main"));
        assertTrue(registry.save("gui", "main").success());
    }

    @Test
    void sessionCompletionTransitionCanBeCancelledAfterScheduling() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        TestAdapter adapter = new TestAdapter();
        registry.register(adapter);
        Session owner = new Session("owner", "client", null);
        registry.saveFromSession(owner, adapter, "main");
        registry.setLiveRefreshExecutor(action -> {
        });

        registry.completeSessionSave(owner, adapter, "main");

        assertTrue(registry.cancelSessionSave(owner, adapter, "main"));
        assertTrue(registry.save("gui", "main").success());
    }

    @Test
    void sameKeyDeferredCompletionsAreAllCancellable() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        registry.register(new TestAdapter());
        List<Runnable> queued = new ArrayList<>();
        registry.setLiveRefreshExecutor(queued::add);
        FlowResourceMutationLease lease = registry.mutationAdmission().acquire("gui", "main");
        FlowResourceMutationContext context = new FlowResourceMutationContext("flow", "flow", "node", "server", lease);

        try {
            assertTrue(registry.save("gui", "main", context).success());
            assertTrue(registry.save("gui", "main", context).success());
            assertEquals(2, queued.size());
            assertTrue(registry.cancelPendingLiveRefresh(new FlowResourceKey("gui", "main")));
        } finally {
            lease.close();
        }
        assertTrue(registry.save("gui", "main").success());
    }

    @Test
    void extensionResourcePreservesOwnershipAndUnloadsOnlyWithMatchingOwner() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        FlowResourceAdapter<String> adapter = new ExtensionAdapter();
        registry.register("fixture", adapter);

        FlowResourceMetadata metadata = registry.metadata().stream()
            .filter(value -> "fixture:quest".equals(value.getTypeId()))
            .findFirst()
            .orElseThrow();
        assertTrue(metadata.isAvailable());
        assertEquals("fixture", metadata.getOwner());
        assertEquals("resource_reference<fixture:quest>", metadata.getReferenceType());

        registry.unregister("other", "fixture:quest");
        assertEquals(adapter, registry.get("fixture:quest"));
        registry.unregister("fixture", "fixture:quest");
        assertNull(registry.get("fixture:quest"));
    }

    @Test
    void mutationsPublishCatalogChangesWithoutReclassifyingCommittedWritesAsFailures() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        registry.register(new TestAdapter());
        AtomicInteger refreshes = new AtomicInteger();
        registry.setChangeListener(source -> refreshes.incrementAndGet());

        assertTrue(registry.save("gui", "main").success());
        assertTrue(registry.delete("gui", "main").success());
        assertEquals(2, refreshes.get());

        registry.setChangeListener(source -> {
            throw new IllegalStateException("Catalog transport unavailable");
        });
        var result = registry.save("gui", "secondary");
        assertTrue(result.success());
        assertFalse((Boolean) result.details().get("refreshSucceeded"));
        assertEquals("Catalog transport unavailable", result.details().get("refreshError"));
    }

    @Test
    void liveRefreshesUseTheConfiguredRuntimeExecutor() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger refreshes = new AtomicInteger();
        registry.setLiveRefreshExecutor(refresh -> {
            executions.incrementAndGet();
            refresh.run();
        });
        registry.register(new TestAdapter() {
            @Override
            public void afterSave(String value) {
                refreshes.incrementAndGet();
            }

            @Override
            public void afterDelete(String id) {
                refreshes.incrementAndGet();
            }
        });

        assertTrue(registry.save("gui", "main").success());
        assertTrue(registry.delete("gui", "main").success());
        assertEquals(2, executions.get());
        assertEquals(2, refreshes.get());
    }

    @Test
    void sessionAttributionSurvivesRuntimeThreadRefreshes() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        TestAdapter adapter = new TestAdapter();
        registry.register(adapter);
        Session session = new Session("session", "client", null);
        AtomicReference<Session> committedBy = new AtomicReference<>();
        registry.setCommitListener(new FlowResourceCommitListener() {
            @Override
            public void saved(String type, String resourceId, String payload) {
            }

            @Override
            public void saved(Session actor, String type, String resourceId, String payload) {
                committedBy.set(actor);
            }

            @Override
            public void deleted(String type, String resourceId) {
            }
        });
        ExecutorService runtime = Executors.newSingleThreadExecutor();
        registry.setLiveRefreshExecutor(action -> {
            try {
                runtime.submit(action).get(2, TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
        try {
            String saved = registry.saveFromSession(session, adapter, "main");
            registry.completeSessionSave(session, adapter, saved);
        } finally {
            runtime.shutdownNow();
        }

        assertEquals(session, committedBy.get());
    }

    @Test
    void sessionSaveReleasesAdmissionWhenCompletionIsSkipped() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        TestAdapter adapter = new TestAdapter();
        registry.register(adapter);
        Session session = new Session("session", "client", null);

        assertEquals("main", registry.saveFromSession(session, adapter, "main"));
        assertFalse(registry.save("gui", "main").success());
        assertTrue(registry.cancelSessionSave(session, adapter, "main"));
        assertTrue(registry.save("gui", "main").success());
    }

    @Test
    void rejectedSessionRefreshFallsBackToInlineFinalization() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        TestAdapter adapter = new TestAdapter();
        registry.register(adapter);
        Session session = new Session("session", "client", null);
        registry.saveFromSession(session, adapter, "main");
        registry.setLiveRefreshExecutor(action -> {
            throw new IllegalStateException("refresh rejected");
        });

        registry.completeSessionSave(session, adapter, "main");
        registry.setLiveRefreshExecutor(Runnable::run);
        assertTrue(registry.save("gui", "main").success());
    }

    @Test
    void durableSaveDefersRuntimeRefreshAndCommitNotification() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        AtomicInteger refreshes = new AtomicInteger();
        AtomicInteger commits = new AtomicInteger();
        TestAdapter adapter = new TestAdapter() {
            @Override
            public void afterSave(String value) {
                refreshes.incrementAndGet();
            }
        };
        registry.register(adapter);
        registry.setCommitListener(new FlowResourceCommitListener() {
            @Override
            public void saved(String type, String resourceId, String payload) {
                commits.incrementAndGet();
            }

            @Override
            public void deleted(String type, String resourceId) {
            }
        });

        Runnable completion = registry.saveAuthoritativeDurable("gui", "main");

        assertEquals("main", adapter.get("main"));
        assertEquals(0, refreshes.get());
        assertEquals(0, commits.get());
        completion.run();
        assertEquals(1, refreshes.get());
        assertEquals(1, commits.get());
    }

    @Test
    void durableCompletionRetainsAndCanCancelItsMutationLease() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        registry.register(new TestAdapter());

        Runnable completion = registry.saveAuthoritativeDurable("gui", "main");

        assertFalse(registry.save("gui", "main").success());
        assertTrue(registry.cancelPendingLiveRefresh(new FlowResourceKey("gui", "main")));
        assertTrue(registry.save("gui", "main").success());

        Runnable completed = registry.saveAuthoritativeDurable("gui", "main");
        completed.run();
        assertThrows(IllegalStateException.class, completed::run);
        assertThrows(IllegalStateException.class, completion::run);
    }

    @Test
    void activationRunsAsOneRuntimeThreadOperation() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        ActivationAdapter adapter = new ActivationAdapter();
        registry.register(adapter);
        ExecutorService runtime = Executors.newSingleThreadExecutor();
        AtomicReference<Thread> runtimeThread = new AtomicReference<>();
        AtomicReference<Session> committedBy = new AtomicReference<>();
        Session session = new Session("session", "client", null);
        registry.setCommitListener(new FlowResourceCommitListener() {
            @Override
            public void saved(String type, String resourceId, String payload) {
            }

            @Override
            public void saved(Session actor, String type, String resourceId, String payload) {
                committedBy.set(actor);
            }

            @Override
            public void deleted(String type, String resourceId) {
            }
        });
        registry.setLiveRefreshExecutor(action -> {
            if (Thread.currentThread() == runtimeThread.get()) {
                action.run();
                return;
            }
            try {
                runtime.submit(() -> {
                    runtimeThread.compareAndSet(null, Thread.currentThread());
                    action.run();
                }).get(2, TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
        try {
            JsonObject disabled = new Gson().fromJson(registry.setEnabledAuthoritative(session, "gui", "main", false), JsonObject.class);

            assertFalse(disabled.get("enabled").getAsBoolean());
            assertFalse(adapter.current.get("enabled").getAsBoolean());
            assertEquals(session, committedBy.get());
        } finally {
            runtime.shutdownNow();
        }
    }

    @Test
    void publishesDurableResourceMutations() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        registry.register(new TestAdapter());
        List<String> mutations = new ArrayList<>();
        registry.setMutationListener(new FlowResourceMutationListener() {
            @Override
            public void saved(String type, String resourceId, String payload) {
                mutations.add("save:" + type + ":" + resourceId + ":" + payload);
            }

            @Override
            public void deleted(String type, String resourceId) {
                mutations.add("delete:" + type + ":" + resourceId);
            }
        });

        assertTrue(registry.save("gui", "main").success());
        assertTrue(registry.delete("gui", "main").success());
        assertEquals(List.of("save:gui:main:\"main\"", "delete:gui:main"), mutations);
    }

    @Test
    void broadcastsCommittedResourceMutationsWithoutReplacingDurabilityPublishing() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        registry.register(new TestAdapter());
        List<String> durable = new ArrayList<>();
        List<String> collaboration = new ArrayList<>();
        registry.setMutationListener(new FlowResourceMutationListener() {
            @Override
            public void saved(String type, String resourceId, String payload) {
                durable.add("save:" + resourceId);
            }

            @Override
            public void deleted(String type, String resourceId) {
                durable.add("delete:" + resourceId);
            }
        });
        registry.setCommitListener(new FlowResourceCommitListener() {
            @Override
            public void saved(String type, String resourceId, String payload) {
                collaboration.add("save:" + resourceId + ":" + payload);
            }

            @Override
            public void deleted(String type, String resourceId) {
                collaboration.add("delete:" + resourceId);
            }
        });

        assertTrue(registry.save("gui", "main").success());
        assertTrue(registry.delete("gui", "main").success());
        assertEquals(List.of("save:main", "delete:main"), durable);
        assertEquals(List.of("save:main:\"main\"", "delete:main"), collaboration);
    }

    @Test
    void authorizationFailuresAreStructuredAndAudited() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        registry.register(new TestAdapter());
        registry.setAuthorizationPolicy((context, operation, resourceType, resourceId) -> false);

        var result = registry.create("gui", "main", new FlowResourceMutationContext("flow", "setup", "create", "server"));

        assertFalse(result.success());
        assertEquals("RESOURCE_AUTHORIZATION_DENIED", result.errorCode());
        assertEquals(1, registry.auditSnapshot().size());
        assertFalse(registry.auditSnapshot().getFirst().success());
        assertEquals("setup", registry.auditSnapshot().getFirst().flowId());
    }

    @Test
    void disablingRetriesAgainstTheLatestResourceWithoutRevalidatingIt() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        ActivationAdapter adapter = new ActivationAdapter();
        registry.register(adapter);

        registry.setEnabledAuthoritative("gui", "main", false);

        assertFalse(adapter.current.get("enabled").getAsBoolean());
        assertEquals("newer", adapter.current.get("content").getAsString());
        assertEquals(2L, adapter.current.get("resourceRevision").getAsLong());
    }

    @Test
    void activationRollsBackWhenTheLiveRuntimeCannotRefresh() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        FailingRefreshActivationAdapter adapter = new FailingRefreshActivationAdapter();
        registry.register(adapter);

        assertThrows(IllegalStateException.class, () -> registry.setEnabledAuthoritative("gui", "main", false));

        assertTrue(adapter.current.get("enabled").getAsBoolean());
        assertEquals("newer", adapter.current.get("content").getAsString());
    }

    @Test
    void exactCreateUsesTheBoundMutationAndRetainsTheCallerLease() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        ExactAdapter adapter = new ExactAdapter();
        registry.register(adapter);
        UUID mutationId = UUID.randomUUID();
        FlowResourceMutationLease lease = registry.mutationAdmission().acquire(List.of(new FlowResourceKey("gui", "main")), mutationId.toString());
        FlowResourceMutationContext context = new FlowResourceMutationContext("protocol", "", "", "client", lease,
            mutationId, 0L, ExactAdapter.HASH);

        try {
            assertTrue(registry.create("gui", "main", context).success());
            assertEquals(1, adapter.exactSaves);
            assertEquals(0, adapter.legacySaves);
            assertTrue(lease.active());
        } finally {
            lease.close();
        }
        assertFalse(registry.mutationAdmission().isAdmitted(new FlowResourceKey("gui", "main")));
    }

    @Test
    void exactDeleteUsesTheBoundMutationAndPublishesATombstone() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        ExactAdapter adapter = new ExactAdapter();
        registry.register(adapter);
        UUID previousMutation = UUID.randomUUID();
        adapter.seed("main", 1L, previousMutation, ExactAdapter.HASH);
        UUID mutationId = UUID.randomUUID();
        FlowResourceMutationLease lease = registry.mutationAdmission().acquire(List.of(new FlowResourceKey("gui", "main")), mutationId.toString());
        FlowResourceMutationContext context = new FlowResourceMutationContext("protocol", "", "", "client", lease,
            mutationId, 1L, ExactAdapter.HASH);

        try {
            assertTrue(registry.delete("gui", "main", context).success());
            assertEquals(1, adapter.exactDeletes);
            assertEquals(0, adapter.legacyDeletes);
            assertTrue(adapter.readMutationStamp("main").deleted());
            assertEquals(2L, adapter.readMutationStamp("main").revision());
        } finally {
            lease.close();
        }
    }

    @Test
    void exactDuplicateAdmitsBothKeysButOnlyMutatesTheTarget() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        ExactAdapter adapter = new ExactAdapter();
        registry.register(adapter);
        UUID sourceMutation = UUID.randomUUID();
        adapter.seed("source", 3L, sourceMutation, ExactAdapter.HASH);
        UUID mutationId = UUID.randomUUID();
        FlowResourceMutationLease lease = registry.mutationAdmission().acquire(List.of(
            new FlowResourceKey("gui", "source"), new FlowResourceKey("gui", "target")), mutationId.toString());
        FlowResourceMutationContext context = new FlowResourceMutationContext("protocol", "", "", "client", lease,
            mutationId, 3L, ExactAdapter.HASH);

        try {
            assertTrue(registry.duplicate("gui", "source", "target", context).success());
            assertEquals(1, adapter.exactSaves);
            assertEquals(0, adapter.exactDeletes);
            assertEquals(3L, adapter.readMutationStamp("source").revision());
            assertEquals(1L, adapter.readMutationStamp("target").revision());
        } finally {
            lease.close();
        }
    }

    @Test
    void exactActivationUsesTheBoundMutationIdentity() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        ExactActivationAdapter adapter = new ExactActivationAdapter();
        registry.register(adapter);
        UUID mutationId = UUID.randomUUID();
        FlowResourceMutationLease lease = registry.mutationAdmission().acquire(
            List.of(new FlowResourceKey("gui", "main")), mutationId.toString());
        FlowResourceMutationContext context = new FlowResourceMutationContext("protocol", "", "", "client", lease,
            mutationId, 1L, ExactAdapter.HASH);

        try {
            registry.setEnabledAuthoritative(context, "gui", "main", false);
            assertEquals(1, adapter.exactSaves);
            assertFalse(adapter.current.get("enabled").getAsBoolean());
            assertEquals(mutationId, adapter.readMutationStamp("main").mutationId());
        } finally {
            lease.close();
        }
    }

    @Test
    void exactMutationFailsClosedForUnsupportedAdapters() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        TestAdapter adapter = new TestAdapter();
        registry.register(adapter);
        UUID mutationId = UUID.randomUUID();
        FlowResourceMutationLease lease = registry.mutationAdmission().acquire(List.of(new FlowResourceKey("gui", "main")), mutationId.toString());
        FlowResourceMutationContext context = new FlowResourceMutationContext("protocol", "", "", "client", lease,
            mutationId, 0L, ExactAdapter.HASH);

        try {
            var result = registry.create("gui", "main", context);
            assertFalse(result.success());
            assertEquals("RESOURCE_OPERATION_UNSUPPORTED", result.errorCode());
            assertTrue(adapter.values.isEmpty());
        } finally {
            lease.close();
        }
    }

    @Test
    void exactStampIdentityMismatchesFailAfterTheAdapterMutation() {
        for (ExactAdapter.StampMismatch mismatch : ExactAdapter.StampMismatch.values()) {
            FlowResourceRegistry registry = new FlowResourceRegistry();
            ExactAdapter adapter = new ExactAdapter();
            adapter.mismatch = mismatch;
            registry.register(adapter);
            UUID mutationId = UUID.randomUUID();
            FlowResourceMutationLease lease = registry.mutationAdmission().acquire(List.of(new FlowResourceKey("gui", "main")), mutationId.toString());
            FlowResourceMutationContext context = new FlowResourceMutationContext("protocol", "", "", "client", lease,
                mutationId, 0L, ExactAdapter.HASH);
            try {
                assertFalse(registry.create("gui", "main", context).success(), mismatch.name());
                assertEquals(1, adapter.exactSaves);
                assertEquals(0, adapter.legacySaves);
            } finally {
                lease.close();
            }
        }
    }

    @Test
    void closingSessionSavesFencesFutureReservationsAndReleasesPendingLease() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        TestAdapter adapter = new TestAdapter();
        registry.register(adapter);
        Session session = new Session("closed", "client", null);
        registry.saveFromSession(session, adapter, "main");
        FlowResourceRegistry copy = registry.copy();

        assertTrue(copy.closeSessionSaves(session));
        FlowResourceMutationLease replacement = registry.mutationAdmission().tryAcquire("gui", "main").orElseThrow();
        replacement.close();
        assertThrows(IllegalStateException.class, () -> registry.saveFromSession(session, adapter, "main"));
    }

    @Test
    void cancellingAStoringSessionSaveFinalizesTheDurableMutation() throws Exception {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TestAdapter adapter = new TestAdapter() {
            @Override
            public void save(String value) {
                entered.countDown();
                try {
                    if (!release.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Persistence was not released");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                super.save(value);
            }
        };
        registry.register(adapter);
        AtomicInteger commits = new AtomicInteger();
        registry.setCommitListener(new FlowResourceCommitListener() {
            @Override
            public void saved(String type, String resourceId, String payload) {
                commits.incrementAndGet();
            }

            @Override
            public void deleted(String type, String resourceId) {
            }
        });
        Session session = new Session("storing", "client", null);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> save = executor.submit(() -> registry.saveFromSession(session, adapter, "main"));
        FlowResourceKey key = new FlowResourceKey("gui", "main");
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(registry.cancelSessionSave(session, adapter, "main"));
            assertTrue(registry.mutationAdmission().isAdmitted(key));
            release.countDown();
            assertEquals("main", save.get(2, TimeUnit.SECONDS));
            assertFalse(registry.mutationAdmission().isAdmitted(key));
            assertEquals(1, commits.get());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void globalCancellationCannotMissARegisteringDurableSessionCompletion() throws Exception {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TestAdapter adapter = new TestAdapter() {
            @Override
            public void save(String value) {
                entered.countDown();
                try {
                    if (!release.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Persistence was not released");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                super.save(value);
            }
        };
        registry.register(adapter);
        AtomicInteger commits = new AtomicInteger();
        registry.setCommitListener(new FlowResourceCommitListener() {
            @Override
            public void saved(String type, String resourceId, String payload) {
                commits.incrementAndGet();
            }

            @Override
            public void deleted(String type, String resourceId) {
            }
        });
        Session session = new Session("registering", "client", null);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> save = executor.submit(() -> registry.saveFromSession(session, adapter, "registering"));
        FlowResourceKey key = new FlowResourceKey("gui", "registering");
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            registry.cancelPendingLiveRefreshes();
            release.countDown();
            assertEquals("registering", save.get(2, TimeUnit.SECONDS));
            assertEquals(1, commits.get());
            assertFalse(registry.mutationAdmission().isAdmitted(key));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void closeAllSessionSavesFencesUnknownFutureSessionsAcrossCopies() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        TestAdapter adapter = new TestAdapter();
        registry.register(adapter);
        FlowResourceRegistry copy = registry.copy();

        assertFalse(copy.closeAllSessionSaves());
        assertThrows(IllegalStateException.class,
            () -> registry.saveFromSession(new Session("future", "client", null), adapter, "future"));
        assertTrue(adapter.values.isEmpty());
    }

    @Test
    void closeAllSessionSavesReleasesPendingAndDeferredStates() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        TestAdapter adapter = new TestAdapter();
        registry.register(adapter);
        Session pendingSession = new Session("pending", "client", null);
        registry.saveFromSession(pendingSession, adapter, "pending");
        assertFalse(registry.save("gui", "pending").success());

        assertTrue(registry.closeAllSessionSaves());
        assertTrue(registry.save("gui", "pending").success());

        FlowResourceRegistry deferredRegistry = new FlowResourceRegistry();
        TestAdapter deferredAdapter = new TestAdapter();
        deferredRegistry.register(deferredAdapter);
        deferredRegistry.setLiveRefreshExecutor(ignored -> {
        });
        Session deferredSession = new Session("deferred", "client", null);
        deferredRegistry.saveFromSession(deferredSession, deferredAdapter, "deferred");
        deferredRegistry.completeSessionSave(deferredSession, deferredAdapter, "deferred");
        assertFalse(deferredRegistry.save("gui", "deferred").success());

        assertTrue(deferredRegistry.closeAllSessionSaves());
        assertTrue(deferredRegistry.save("gui", "deferred").success());
    }

    @Test
    void closeAllSessionSavesFinalizesAStoringDurableMutation() throws Exception {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TestAdapter adapter = new TestAdapter() {
            @Override
            public void save(String value) {
                entered.countDown();
                try {
                    if (!release.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Persistence was not released");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                super.save(value);
            }
        };
        registry.register(adapter);
        AtomicInteger commits = new AtomicInteger();
        registry.setCommitListener(new FlowResourceCommitListener() {
            @Override
            public void saved(String type, String resourceId, String payload) {
                commits.incrementAndGet();
            }

            @Override
            public void deleted(String type, String resourceId) {
            }
        });
        Session session = new Session("global-storing", "client", null);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> save = executor.submit(() -> registry.saveFromSession(session, adapter, "global-storing"));
        FlowResourceKey key = new FlowResourceKey("gui", "global-storing");
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(registry.closeAllSessionSaves());
            assertTrue(registry.mutationAdmission().isAdmitted(key));
            release.countDown();
            assertEquals("global-storing", save.get(2, TimeUnit.SECONDS));
            assertFalse(registry.mutationAdmission().isAdmitted(key));
            assertEquals(1, commits.get());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void exactDurableHandleCancelsOnlyItsOwnCompletion() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        registry.register(new TestAdapter());
        FlowResourceMutationLease.DeferredCompletion handle = registry.saveAuthoritativeDurableHandle("gui", "main");

        assertFalse(registry.save("gui", "main").success());
        assertTrue(handle.cancel());
        assertTrue(registry.save("gui", "main").success());
    }

    private static class TestAdapter implements FlowResourceAdapter<String> {
        private final List<String> values = new ArrayList<>();

        @Override
        public ReSyncManagedResource descriptor() {
            return ReSyncResourceCatalog.byType(ReSyncResourceCatalog.GUI);
        }

        @Override
        public String get(String id) {
            return values.stream().filter(id::equals).findFirst().orElse(null);
        }

        @Override
        public List<String> listIds() {
            return List.copyOf(values);
        }

        @Override
        public String deserialize(String json) {
            return json;
        }

        @Override
        public String id(String value) {
            return value;
        }

        @Override
        public void save(String value) {
            values.add(value);
        }

        @Override
        public void delete(String id) {
            values.remove(id);
        }

        @Override
        public String duplicate(String value, String targetId) {
            return targetId;
        }

        @Override
        public Set<String> supportedOperations() {
            return Set.of("discover", "query", "get", "create", "validate", "save", "update", "duplicate", "delete");
        }

        @Override
        public void sendData(Session session, String value) {
        }

        @Override
        public void sendList(Session session, List<String> ids) {
        }

        @Override
        public void sendSaveAck(Session session, String id) {
        }
    }

    private static final class ExactAdapter implements FlowResourceAdapter<String> {
        private static final String HASH = "1".repeat(64);

        private enum StampMismatch {
            TYPE,
            ID,
            REVISION,
            UUID,
            HASH,
            DELETED
        }

        private final Map<String, String> values = new HashMap<>();
        private final Map<String, FlowResourceMutationStamp> stamps = new HashMap<>();
        private int exactSaves;
        private int exactDeletes;
        private int legacySaves;
        private int legacyDeletes;
        private StampMismatch mismatch;
        private boolean mutated;

        @Override
        public ReSyncManagedResource descriptor() {
            return ReSyncResourceCatalog.byType(ReSyncResourceCatalog.GUI);
        }

        @Override
        public String get(String id) {
            return values.get(id);
        }

        @Override
        public boolean conflicts(String id) {
            return stamps.containsKey(id);
        }

        @Override
        public List<String> listIds() {
            return List.copyOf(values.keySet());
        }

        @Override
        public String deserialize(String json) {
            return json;
        }

        @Override
        public String id(String value) {
            return value;
        }

        @Override
        public void save(String value) {
            legacySaves++;
            values.put(value, value);
        }

        @Override
        public void save(String value, UUID mutationId, long expectedRevision) {
            exactSaves++;
            values.put(value, value);
            stamps.put(value, new FlowResourceMutationStamp("gui", value, expectedRevision + 1L, mutationId, HASH, false));
            mutated = true;
        }

        @Override
        public void delete(String id) {
            legacyDeletes++;
            values.remove(id);
        }

        @Override
        public void delete(String id, UUID mutationId, long expectedRevision) {
            exactDeletes++;
            values.remove(id);
            stamps.put(id, new FlowResourceMutationStamp("gui", id, expectedRevision + 1L, mutationId, HASH, true));
            mutated = true;
        }

        @Override
        public String duplicate(String value, String targetId) {
            return targetId;
        }

        @Override
        public FlowResourceMutationStamp readMutationStamp(String id) {
            FlowResourceMutationStamp stamp = stamps.get(id);
            if (!mutated || mismatch == null || stamp == null) {
                return stamp;
            }
            return switch (mismatch) {
                case TYPE -> new FlowResourceMutationStamp("wrong", stamp.id(), stamp.revision(), stamp.mutationId(), stamp.payloadHash(), stamp.deleted());
                case ID -> new FlowResourceMutationStamp(stamp.type(), "wrong", stamp.revision(), stamp.mutationId(), stamp.payloadHash(), stamp.deleted());
                case REVISION -> new FlowResourceMutationStamp(stamp.type(), stamp.id(), stamp.revision() + 1L, stamp.mutationId(), stamp.payloadHash(), stamp.deleted());
                case UUID -> new FlowResourceMutationStamp(stamp.type(), stamp.id(), stamp.revision(), UUID.randomUUID(), stamp.payloadHash(), stamp.deleted());
                case HASH -> new FlowResourceMutationStamp(stamp.type(), stamp.id(), stamp.revision(), stamp.mutationId(), "2".repeat(64), stamp.deleted());
                case DELETED -> new FlowResourceMutationStamp(stamp.type(), stamp.id(), stamp.revision(), stamp.mutationId(), stamp.payloadHash(), true);
            };
        }

        @Override
        public boolean supportsAuthoritativeMutationIdentity() {
            return true;
        }

        @Override
        public Set<String> supportedOperations() {
            return Set.of("discover", "query", "get", "create", "validate", "save", "update", "duplicate", "delete");
        }

        private void seed(String id, long revision, UUID mutationId, String hash) {
            values.put(id, id);
            stamps.put(id, new FlowResourceMutationStamp("gui", id, revision, mutationId, hash, false));
        }
    }

    private static final class ExactActivationAdapter extends ActivationAdapter {
        private int exactSaves;
        private FlowResourceMutationStamp stamp = new FlowResourceMutationStamp("gui", "main", 1L, UUID.randomUUID(),
            ExactAdapter.HASH, false);

        @Override
        public boolean supportsAuthoritativeMutationIdentity() {
            return true;
        }

        @Override
        public JsonObject deserialize(String json) {
            return super.deserialize(json);
        }

        @Override
        public void save(JsonObject value, UUID mutationId, long expectedRevision) {
            exactSaves++;
            current = value.deepCopy();
            stamp = new FlowResourceMutationStamp("gui", "main", expectedRevision + 1L, mutationId, ExactAdapter.HASH, false);
        }

        @Override
        public FlowResourceMutationStamp readMutationStamp(String id) {
            return "main".equals(id) ? stamp : null;
        }
    }

    private static final class ExtensionAdapter implements FlowResourceAdapter<String> {
        private final ReSyncManagedResource descriptor = new ReSyncManagedResource("fixture:quest", "Quest", "fixture/quests", null, true);

        @Override
        public ReSyncManagedResource descriptor() {
            return descriptor;
        }

        @Override
        public String get(String id) {
            return null;
        }

        @Override
        public List<String> listIds() {
            return List.of();
        }

        @Override
        public String deserialize(String json) {
            return json;
        }

        @Override
        public String id(String value) {
            return value;
        }

        @Override
        public void save(String value) {
        }

        @Override
        public void delete(String id) {
        }
    }

    private static class ActivationAdapter implements FlowResourceAdapter<JsonObject> {
        private final Gson gson = new Gson();
        protected JsonObject current = resource("initial", 1L, true);
        private boolean conflict = true;

        @Override
        public ReSyncManagedResource descriptor() {
            return ReSyncResourceCatalog.byType(ReSyncResourceCatalog.GUI);
        }

        @Override
        public JsonObject get(String id) {
            return "main".equals(id) ? current.deepCopy() : null;
        }

        @Override
        public List<String> listIds() {
            return List.of("main");
        }

        @Override
        public JsonObject deserialize(String json) {
            return gson.fromJson(json, JsonObject.class);
        }

        @Override
        public String serialize(JsonObject value) {
            return gson.toJson(value);
        }

        @Override
        public String id(JsonObject value) {
            return value.get("id").getAsString();
        }

        @Override
        public void validate(JsonObject value) {
            throw new IllegalArgumentException("Disabled resource is incomplete");
        }

        @Override
        public void save(JsonObject value) {
            if (conflict) {
                conflict = false;
                current = resource("newer", 2L, true);
                throw new ResourceRevisionConflictException("main", 1L, 2L);
            }
            current = value.deepCopy();
        }

        @Override
        public void delete(String id) {
        }

        private static JsonObject resource(String content, long revision, boolean enabled) {
            JsonObject value = new JsonObject();
            value.addProperty("id", "main");
            value.addProperty("content", content);
            value.addProperty("resourceRevision", revision);
            value.addProperty("enabled", enabled);
            return value;
        }
    }

    private static final class FailingRefreshActivationAdapter extends ActivationAdapter {
        @Override
        public void afterSave(JsonObject value) {
            if (!value.get("enabled").getAsBoolean()) {
                throw new IllegalStateException("Runtime refresh failed");
            }
        }
    }
}
