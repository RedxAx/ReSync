package restudio.resync.modules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.flow.data.FlowNode;
import restudio.resync.api.ExtensionRegistryActivation;
import restudio.resync.api.OptionCatalogProvider;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.storage.AssetPersistenceGate;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FlowRuntimeModuleAbortShutdownTest {
    @TempDir
    Path temporary;

    @Test
    void abortShutdownClearsBoundHandlersWithoutReadingAQuiescedAssetCatalog() {
        AssetPersistenceGate gate = new AssetPersistenceGate(temporary);
        AtomicInteger revisions = new AtomicInteger();
        AtomicInteger lists = new AtomicInteger();
        AtomicInteger shutdowns = new AtomicInteger();
        HandlerRegistry handlers = handlers(shutdowns);
        OptionCatalogRegistry catalogs = catalogs(gate, revisions, lists);
        ExtensionRegistryActivation activation = activation(handlers, catalogs);
        handlers.bindActivation(activation);
        int revisionsBeforeShutdown = revisions.get();
        int listsBeforeShutdown = lists.get();
        gate.quiesce();

        FlowRuntimeModule.shutdownHandlers(handlers);

        assertEquals(0, handlers.getHandlerCount());
        assertEquals(0, activation.snapshot().handlers().getHandlerCount());
        assertEquals(1, shutdowns.get());
        assertEquals(revisionsBeforeShutdown, revisions.get());
        assertEquals(listsBeforeShutdown, lists.get());

        FlowRuntimeModule.shutdownHandlers(handlers);

        assertEquals(1, shutdowns.get());
        assertEquals(revisionsBeforeShutdown, revisions.get());
        assertEquals(listsBeforeShutdown, lists.get());
    }

    @Test
    void failedAbortPublicationRollsBackBeforeHandlerShutdownAndCanBeRetried() {
        AssetPersistenceGate gate = new AssetPersistenceGate(temporary);
        AtomicInteger revisions = new AtomicInteger();
        AtomicInteger lists = new AtomicInteger();
        AtomicInteger shutdowns = new AtomicInteger();
        HandlerRegistry handlers = handlers(shutdowns);
        ExtensionRegistryActivation activation = activation(handlers, catalogs(gate, revisions, lists));
        handlers.bindActivation(activation);
        Consumer<ExtensionRegistryActivation.State> rejectingListener = state -> {
            if (state.handlers().getHandlerCount() == 0) {
                throw new IllegalStateException("handler retirement projection rejected");
            }
        };
        activation.addListener(rejectingListener);
        int revisionsBeforeShutdown = revisions.get();
        gate.quiesce();

        assertThrows(IllegalStateException.class, () -> FlowRuntimeModule.shutdownHandlers(handlers));

        assertEquals(1, handlers.getHandlerCount());
        assertEquals(1, activation.snapshot().handlers().getHandlerCount());
        assertEquals(0, shutdowns.get());
        assertEquals(revisionsBeforeShutdown, revisions.get());
        assertEquals(0, lists.get());

        activation.removeListener(rejectingListener);
        FlowRuntimeModule.shutdownHandlers(handlers);

        assertEquals(0, handlers.getHandlerCount());
        assertEquals(1, shutdowns.get());
        assertEquals(revisionsBeforeShutdown, revisions.get());
        assertEquals(0, lists.get());
    }

    @Test
    void failedHandlerClearRetainsModuleOwnershipUntilPrepareRetrySucceeds() throws Exception {
        AssetPersistenceGate gate = new AssetPersistenceGate(temporary);
        AtomicInteger revisions = new AtomicInteger();
        AtomicInteger lists = new AtomicInteger();
        AtomicInteger shutdowns = new AtomicInteger();
        HandlerRegistry handlers = handlers(shutdowns);
        ExtensionRegistryActivation activation = activation(handlers, catalogs(gate, revisions, lists));
        handlers.bindActivation(activation);
        Consumer<ExtensionRegistryActivation.State> rejectingListener = state -> {
            if (state.handlers().getHandlerCount() == 0) {
                throw new IllegalStateException("handler retirement projection rejected");
            }
        };
        activation.addListener(rejectingListener);
        FlowRuntimeModule module = new FlowRuntimeModule();
        Field handlerRegistry = FlowRuntimeModule.class.getDeclaredField("handlerRegistry");
        handlerRegistry.setAccessible(true);
        handlerRegistry.set(module, handlers);
        int revisionsBeforeShutdown = revisions.get();
        gate.quiesce();

        assertThrows(IllegalStateException.class, () -> module.prepareStop(null));

        assertSame(handlers, handlerRegistry.get(module));
        assertEquals(1, handlers.getHandlerCount());
        assertEquals(0, shutdowns.get());
        assertEquals(revisionsBeforeShutdown, revisions.get());
        assertEquals(0, lists.get());

        activation.removeListener(rejectingListener);
        module.prepareStop(null);

        assertNull(handlerRegistry.get(module));
        assertEquals(0, handlers.getHandlerCount());
        assertEquals(1, shutdowns.get());
        assertEquals(revisionsBeforeShutdown, revisions.get());
        assertEquals(0, lists.get());

        module.prepareStop(null);

        assertEquals(1, shutdowns.get());
    }

    private HandlerRegistry handlers(AtomicInteger shutdowns) {
        HandlerRegistry handlers = new HandlerRegistry();
        handlers.register("catalog-handler", new NodeHandler() {
            @Override
            public void execute(FlowContext context, FlowNode node) {
            }

            @Override
            public void shutdown() {
                shutdowns.incrementAndGet();
            }
        });
        return handlers;
    }

    private OptionCatalogRegistry catalogs(AssetPersistenceGate gate, AtomicInteger revisions, AtomicInteger lists) {
        OptionCatalogRegistry catalogs = new OptionCatalogRegistry();
        catalogs.register(new OptionCatalogProvider() {
            @Override
            public String sourceId() {
                return "server:resync:flow-assets";
            }

            @Override
            public String revision() {
                revisions.incrementAndGet();
                try (AssetPersistenceGate.MutationLease ignored = gate.acquire()) {
                    return "1";
                }
            }

            @Override
            public List<String> values() {
                lists.incrementAndGet();
                try (AssetPersistenceGate.MutationLease ignored = gate.acquire()) {
                    return List.of("flow");
                }
            }
        });
        return catalogs;
    }

    private ExtensionRegistryActivation activation(HandlerRegistry handlers, OptionCatalogRegistry catalogs) {
        ExtensionRegistryActivation.State state = ExtensionRegistryActivation.capture(1L,
            null, handlers, null, catalogs, null, null, null, null, null, null, null, null);
        return new ExtensionRegistryActivation(state);
    }
}
