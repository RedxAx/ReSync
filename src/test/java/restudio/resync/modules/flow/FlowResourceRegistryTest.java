package restudio.resync.modules.flow;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void staleDurableCompletionCannotReplaceANewerCommit() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        AtomicReference<JsonObject> stored = new AtomicReference<>();
        List<Integer> refreshes = new ArrayList<>();
        List<Integer> commits = new ArrayList<>();
        FlowResourceAdapter<JsonObject> adapter = new FlowResourceAdapter<>() {
            @Override
            public ReSyncManagedResource descriptor() {
                return ReSyncResourceCatalog.byType(ReSyncResourceCatalog.GUI);
            }

            @Override
            public JsonObject get(String id) {
                JsonObject current = stored.get();
                return current != null ? current.deepCopy() : null;
            }

            @Override
            public List<String> listIds() {
                return stored.get() != null ? List.of("main") : List.of();
            }

            @Override
            public JsonObject deserialize(String json) {
                return new Gson().fromJson(json, JsonObject.class);
            }

            @Override
            public String serialize(JsonObject value) {
                return value.toString();
            }

            @Override
            public String id(JsonObject value) {
                return value.get("id").getAsString();
            }

            @Override
            public void save(JsonObject value) {
                stored.set(value.deepCopy());
            }

            @Override
            public void delete(String id) {
                stored.set(null);
            }

            @Override
            public void afterSave(JsonObject value) {
                refreshes.add(value.get("revision").getAsInt());
            }
        };
        registry.register(adapter);
        registry.setCommitListener(new FlowResourceCommitListener() {
            @Override
            public void saved(String type, String resourceId, String payload) {
                commits.add(new Gson().fromJson(payload, JsonObject.class).get("revision").getAsInt());
            }

            @Override
            public void deleted(String type, String resourceId) {
            }
        });
        JsonObject first = new JsonObject();
        first.addProperty("id", "main");
        first.addProperty("revision", 1);
        JsonObject second = new JsonObject();
        second.addProperty("id", "main");
        second.addProperty("revision", 2);

        Runnable staleCompletion = registry.saveAuthoritativeDurable("gui", first);
        Runnable latestCompletion = registry.saveAuthoritativeDurable("gui", second);
        latestCompletion.run();
        staleCompletion.run();

        assertEquals(List.of(2), refreshes);
        assertEquals(List.of(2), commits);
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
