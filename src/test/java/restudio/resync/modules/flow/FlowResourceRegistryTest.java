package restudio.resync.modules.flow;

import org.junit.jupiter.api.Test;
import restudio.resync.core.Session;
import restudio.resync.flow.sync.FlowResourceMetadata;
import restudio.resync.resources.ReSyncManagedResource;
import restudio.resync.resources.ReSyncResourceCatalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    private static final class TestAdapter implements FlowResourceAdapter<String> {
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
}
