package restudio.resync.modules.flow;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.CoreGraphStorageBoundary;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.resources.ReSyncManagedResource;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.server.CoreGraphResourceAuthority;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowResourceRegistryCoreGraphTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final UUID MUTATION = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final ContentHash HASH = new ContentHash("a".repeat(64));

    @Test
    void bindsCoreAuthorityOnceAndPreservesItAcrossRegistryStaging() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        RecordingAuthority authority = new RecordingAuthority(true);

        registry.bindCoreGraphResourceAuthority(authority);

        assertSame(authority, registry.coreGraphResourceAuthority());
        assertSame(authority, registry.copy().coreGraphResourceAuthority());
        assertThrows(IllegalStateException.class, () -> registry.bindCoreGraphResourceAuthority(new RecordingAuthority(true)));

        FlowResourceRegistry staged = new FlowResourceRegistry();
        staged.register(new LegacyAdapter());
        registry.replaceFrom(staged);

        assertSame(authority, registry.coreGraphResourceAuthority());
    }

    @Test
    void unavailableCoreProtocolNeverTouchesLegacyGraphAdapter() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        LegacyAdapter adapter = new LegacyAdapter();
        registry.register(adapter);
        ServerResourceLocator resource = resource("flow", "main");

        assertEquals("restudio.resync", registry.protocolOwner("flow"));
        assertTrue(registry.protocolSupports("flow", "get"));
        assertTrue(registry.protocolSupports("function", "list"));
        assertTrue(registry.protocolSupports("command", "activation"));
        assertFalse(registry.coreGraphResourceAuthorityAvailable());
        assertThrows(IllegalStateException.class, () -> registry.protocolLoad(resource));
        assertThrows(IllegalStateException.class, () -> registry.protocolDocument(resource));
        assertThrows(IllegalStateException.class, () -> registry.protocolList(SERVER,
            ContractRef.of(new OwnerId("restudio.resync"), new ResourceTypeId("flow")), ""));
        assertThrows(IllegalStateException.class, () -> registry.protocolQuery(SERVER,
            ContractRef.of(new OwnerId("restudio.resync"), new ResourceTypeId("flow")), ""));
        assertThrows(IllegalStateException.class, () -> registry.protocolState(resource));
        assertThrows(IllegalStateException.class,
            () -> registry.protocolSave(resource, new byte[] {1}, MUTATION, 0L, HASH));
        assertThrows(IllegalStateException.class, () -> registry.protocolDelete(resource, MUTATION, 0L, HASH));
        assertThrows(IllegalStateException.class,
            () -> registry.protocolActivation(resource, ResourceActivationState.ACTIVE, MUTATION, 0L, HASH));
        assertEquals(0, adapter.calls.get());
    }

    @Test
    void coreProtocolOperationsUseTheBoundAuthorityWithoutAdapterFallback() {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        LegacyAdapter adapter = new LegacyAdapter();
        RecordingAuthority authority = new RecordingAuthority(true);
        registry.register(adapter);
        registry.bindCoreGraphResourceAuthority(authority);
        ServerResourceLocator resource = resource("flow", "main");

        assertNull(registry.protocolLoad(resource));
        assertNull(registry.protocolDocument(resource));
        assertTrue(registry.protocolList(SERVER,
            ContractRef.of(new OwnerId("restudio.resync"), new ResourceTypeId("flow")), "").isEmpty());
        assertTrue(registry.protocolQuery(SERVER,
            ContractRef.of(new OwnerId("restudio.resync"), new ResourceTypeId("flow")), "").isEmpty());
        assertEquals(2, authority.lists.get());
        assertEquals(1, authority.loads.get());
        assertEquals(1, authority.states.get());
        assertEquals(0, adapter.calls.get());

        registry.protocolState(resource);
        registry.protocolSave(resource, new byte[] {1}, MUTATION, 0L, HASH);
        registry.protocolDelete(resource, MUTATION, 0L, HASH);
        registry.protocolActivation(resource, ResourceActivationState.ACTIVE, MUTATION, 0L, HASH);

        assertEquals(2, authority.states.get());
        assertEquals(1, authority.saves.get());
        assertEquals(1, authority.deletes.get());
        assertEquals(1, authority.activations.get());
        assertEquals(0, adapter.calls.get());
    }

    private static ServerResourceLocator resource(String type, String id) {
        return new ServerResourceLocator(SERVER,
            ContractRef.of(new OwnerId("restudio.resync"), new ResourceTypeId(type)), id);
    }

    private static final class RecordingAuthority implements CoreGraphResourceAuthority {
        private final boolean available;
        private final AtomicInteger loads = new AtomicInteger();
        private final AtomicInteger lists = new AtomicInteger();
        private final AtomicInteger states = new AtomicInteger();
        private final AtomicInteger saves = new AtomicInteger();
        private final AtomicInteger deletes = new AtomicInteger();
        private final AtomicInteger activations = new AtomicInteger();

        private RecordingAuthority(boolean available) {
            this.available = available;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public Optional<CoreGraphStorageBoundary.Decoded> load(ServerResourceLocator resource) {
            loads.incrementAndGet();
            return Optional.empty();
        }

        @Override
        public List<CoreGraphResourceState> list(String type) {
            lists.incrementAndGet();
            return List.of();
        }

        @Override
        public Optional<CoreGraphResourceState> state(ServerResourceLocator resource) {
            states.incrementAndGet();
            return Optional.empty();
        }

        @Override
        public CoreGraphStorageBoundary.Decoded save(ServerResourceLocator resource, byte[] canonicalEnvelope,
                                                      UUID mutationId, long expectedRevision, ContentHash payloadChecksum) {
            saves.incrementAndGet();
            return null;
        }

        @Override
        public CoreGraphStorageBoundary.CoreGraphTombstone delete(ServerResourceLocator resource, UUID mutationId,
                                                                   long expectedRevision, ContentHash payloadChecksum) {
            deletes.incrementAndGet();
            return null;
        }

        @Override
        public CoreGraphStorageBoundary.Decoded activate(ServerResourceLocator resource,
                                                          ResourceActivationState activationState, UUID mutationId,
                                                          long expectedRevision, ContentHash payloadChecksum) {
            activations.incrementAndGet();
            return null;
        }
    }

    private static final class LegacyAdapter implements FlowResourceAdapter<String> {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public ReSyncManagedResource descriptor() {
            return ReSyncResourceCatalog.byType(ReSyncResourceCatalog.FLOW);
        }

        @Override
        public String get(String id) {
            calls.incrementAndGet();
            throw new AssertionError("Core protocol must not read through the legacy adapter");
        }

        @Override
        public List<String> listIds() {
            calls.incrementAndGet();
            throw new AssertionError("Core protocol must not list through the legacy adapter");
        }

        @Override
        public String deserialize(String json) {
            calls.incrementAndGet();
            throw new AssertionError("Core protocol must not deserialize through the legacy adapter");
        }

        @Override
        public String id(String value) {
            calls.incrementAndGet();
            throw new AssertionError("Core protocol must not resolve IDs through the legacy adapter");
        }

        @Override
        public void save(String value) {
            calls.incrementAndGet();
            throw new AssertionError("Core protocol must not save through the legacy adapter");
        }

        @Override
        public void delete(String id) {
            calls.incrementAndGet();
            throw new AssertionError("Core protocol must not delete through the legacy adapter");
        }
    }
}
