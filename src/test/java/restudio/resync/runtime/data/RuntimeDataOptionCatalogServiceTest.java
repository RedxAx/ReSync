package restudio.resync.runtime.data;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowTypeRef;
import restudio.resync.api.OptionCatalogCapture;
import restudio.resync.api.OptionCatalogItem;
import restudio.resync.api.OptionCatalogProvider;
import restudio.resync.api.OptionCatalogQuery;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.api.RuntimeDataAdapter;
import restudio.resync.api.RuntimeDataQuery;
import restudio.resync.api.RuntimeDataRecord;
import restudio.resync.api.RuntimeDataRegistry;
import restudio.resync.server.OptionCatalogCaptureExecutor;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeDataOptionCatalogServiceTest {
    @Test
    void emptyItemContextListsEverySourceAndAggregatesEveryCategory() {
        RuntimeDataRegistry runtimeData = new RuntimeDataRegistry();
        runtimeData.register(callerAdapter("minecraft:items", Set.of("blocks", "tools"), () -> {}));
        runtimeData.register(callerAdapter("resync:custom_items", Set.of("custom"), () -> {}));
        OptionCatalogRegistry catalogs = catalogs(runtimeData);
        OptionCatalogProvider source = catalogs.provider(RuntimeDataOptionCatalogService.SOURCE_SOURCE);
        OptionCatalogProvider category = catalogs.provider(RuntimeDataOptionCatalogService.CATEGORY_SOURCE);

        OptionCatalogCapture sources = source.capture(query(source.sourceId(), Map.of()));
        OptionCatalogCapture categories = category.capture(query(category.sourceId(), Map.of()));

        assertEquals(Set.of("minecraft:items", "resync:custom_items"), Set.copyOf(sources.values()));
        assertEquals(Set.of("blocks", "tools", "custom"), Set.copyOf(categories.values()));
    }

    @Test
    void stalePreparedReaderCannotClearAConcurrentlyPublishedCapture() throws Exception {
        AtomicLong state = new AtomicLong(1L);
        CountDownLatch staleChecked = new CountDownLatch(1);
        CountDownLatch publishSecond = new CountDownLatch(1);
        AtomicReference<Throwable> staleFailure = new AtomicReference<>();
        RuntimeDataCategoryCatalog<Long> categories = new RuntimeDataCategoryCatalog<>(unsupportedAdapter("test:race")) {
            @Override
            public CaptureAffinity captureAffinity() {
                return CaptureAffinity.IO;
            }

            @Override
            protected Snapshot<Long> captureSnapshot() {
                long token = state.get();
                return snapshot("revision-" + token, List.of(record("test:race", Set.of("category-" + token))), token);
            }

            @Override
            protected boolean current(Long token) {
                long current = state.get();
                if (token == 1L && current == 2L) {
                    staleChecked.countDown();
                    try {
                        if (!publishSecond.await(2L, TimeUnit.SECONDS)) {
                            throw new AssertionError("Timed out awaiting the second category capture");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(exception);
                    }
                }
                return current == token;
            }
        };
        OptionCatalogQuery query = new OptionCatalogQuery(categories.sourceId(), Map.of());
        OptionCatalogCapture first = categories.capture(query);
        state.set(2L);
        Thread staleReader = new Thread(() -> {
            try {
                categories.preparedCapture(query);
            } catch (Throwable failure) {
                staleFailure.set(failure);
            }
        }, "stale-category-reader");

        staleReader.start();
        try {
            assertTrue(staleChecked.await(2L, TimeUnit.SECONDS));
            OptionCatalogCapture second = categories.capture(query);
            publishSecond.countDown();
            staleReader.join(2_000L);

            assertFalse(staleReader.isAlive());
            assertTrue(staleFailure.get() instanceof OptionCatalogRegistry.CaptureUnavailable);
            assertNotEquals(first.revision(), second.revision());
            assertEquals(second, categories.preparedCapture(query));
        } finally {
            publishSecond.countDown();
            staleReader.join(2_000L);
        }
    }

    @Test
    void aggregatesCategoriesFromOptionCatalogRuntimeAdaptersUsingOneCoherentCapture() {
        RuntimeDataRegistry runtimeData = new RuntimeDataRegistry();
        OptionCatalogRegistry catalogs = new OptionCatalogRegistry(runtimeData);
        AtomicBoolean available = new AtomicBoolean(true);
        AtomicInteger captures = new AtomicInteger();
        assertTrue(catalogs.register(new OptionCatalogProvider() {
            @Override
            public String sourceId() {
                return "test:catalog";
            }

            @Override
            public String runtimeDataDomain() {
                return "item";
            }

            @Override
            public CaptureAffinity captureAffinity() {
                return CaptureAffinity.CALLER;
            }

            @Override
            public OptionCatalogCapture capture(OptionCatalogQuery query) {
                captures.incrementAndGet();
                if (!available.get()) {
                    return new OptionCatalogCapture("catalog:pending", List.of(), "warming", "Catalog is warming");
                }
                return new OptionCatalogCapture("catalog:ready", List.of(new OptionCatalogItem("stone", "Stone", "", "",
                    "Blocks", Map.of("category", "Building", "categories", List.of("Solid")))), "available", "");
            }

            @Override
            public String revision() {
                throw new AssertionError("Runtime categories must use the coherent capture");
            }

            @Override
            public List<String> values() {
                throw new AssertionError("Runtime categories must use the coherent capture");
            }
        }));
        new RuntimeDataOptionCatalogService(runtimeData).registerProviders(catalogs);
        RuntimeDataAdapter<?> adapter = runtimeData.adapter("test:catalog");
        OptionCatalogProvider category = catalogs.provider(RuntimeDataOptionCatalogService.CATEGORY_SOURCE);
        OptionCatalogQuery query = query(category.sourceId(), Map.of("data_type", "item", "source", "test:catalog"));

        assertEquals(OptionCatalogProvider.CaptureAffinity.SERVER_MAIN, adapter.categoryCatalog().captureAffinity());
        OptionCatalogCapture captured = category.capture(query);

        assertEquals("available", captured.status());
        assertEquals(Set.of("blocks", "building", "solid"), Set.copyOf(captured.values()));
        assertEquals(1, captured.items().stream().filter(item -> item.value().equals("building"))
            .findFirst().orElseThrow().metadata().get("count"));
        assertEquals(1, captures.get());

        available.set(false);
        OptionCatalogCapture unavailable = adapter.categoryCatalog().capture(
            new OptionCatalogQuery(adapter.categoryCatalog().sourceId(), Map.of()));

        assertEquals("warming", unavailable.status());
        assertEquals("Catalog is warming", unavailable.diagnostic());
        assertTrue(unavailable.items().isEmpty());
        assertEquals(2, captures.get());
    }

    @Test
    void preparesIoCategoriesAndInvalidatesChangedSnapshotsWithoutEnumeratingRecordsOnTheCaller() {
        Thread main = Thread.currentThread();
        AtomicLong state = new AtomicLong(1L);
        AtomicInteger recordCalls = new AtomicInteger();
        AtomicReference<String> captureThread = new AtomicReference<>();
        RuntimeDataRegistry runtimeData = new RuntimeDataRegistry();
        RuntimeDataAdapter<String> adapter = preparedAdapter("test:items", state, recordCalls, captureThread, () -> {
        });
        runtimeData.register(adapter);
        OptionCatalogRegistry catalogs = catalogs(runtimeData);
        OptionCatalogProvider source = catalogs.provider(RuntimeDataOptionCatalogService.SOURCE_SOURCE);
        OptionCatalogProvider category = catalogs.provider(RuntimeDataOptionCatalogService.CATEGORY_SOURCE);
        OptionCatalogQuery query = query(category.sourceId(), Map.of("data_type", "item", "source", adapter.id()));

        assertEquals(OptionCatalogProvider.CaptureAffinity.CALLER, source.captureAffinity());
        assertEquals(OptionCatalogProvider.CaptureAffinity.SERVER_MAIN, category.captureAffinity());
        assertEquals(OptionCatalogProvider.CaptureAffinity.CALLER, category.captureAffinity(query));
        assertEquals("unavailable", category.capture(query).status());

        try (OptionCatalogCaptureExecutor executor = OptionCatalogCaptureExecutor.bounded(1, Duration.ofSeconds(2),
            () -> Thread.currentThread() == main, task -> {
                throw new AssertionError("Aggregate category capture should already be on the server main thread");
            }, task -> {
                Thread thread = new Thread(task, "runtime-category-io");
                thread.setDaemon(true);
                return thread;
            })) {
            RuntimeDataOptionCatalogService.prewarm(catalogs, executor).toCompletableFuture().join();
            OptionCatalogCapture initial = category.capture(query);

            assertEquals("available", initial.status());
            assertEquals(Set.of("building"), Set.copyOf(initial.values()));
            assertEquals("runtime-category-io", captureThread.get());
            assertEquals(0, recordCalls.get());

            state.set(2L);
            OptionCatalogCapture invalidated = category.capture(query);
            assertEquals("unavailable", invalidated.status());
            assertTrue(invalidated.diagnostic().contains("no longer current"));

            RuntimeDataOptionCatalogService.refresh(catalogs, executor, adapter.id()).toCompletableFuture().join();
            OptionCatalogCapture refreshed = category.capture(query);
            assertEquals("available", refreshed.status());
            assertEquals(Set.of("building", "solid"), Set.copyOf(refreshed.values()));
            assertNotEquals(initial.revision(), refreshed.revision());
            assertEquals(0, recordCalls.get());
        }
    }

    @Test
    void selectedResidentCategoriesStayOnTheProtocolCaller() {
        RuntimeDataRegistry runtimeData = new RuntimeDataRegistry();
        runtimeData.register(callerAdapter("minecraft:items", Set.of("blocks", "tools"), () -> {
        }));
        OptionCatalogProvider category = catalogs(runtimeData).provider(RuntimeDataOptionCatalogService.CATEGORY_SOURCE);
        OptionCatalogQuery query = query(category.sourceId(), Map.of("data_type", "item", "source", "minecraft:items"));
        AtomicInteger mainSchedules = new AtomicInteger();

        try (OptionCatalogCaptureExecutor executor = OptionCatalogCaptureExecutor.bounded(1, Duration.ofSeconds(2), () -> false,
            task -> {
                mainSchedules.incrementAndGet();
                task.run();
            }, task -> new Thread(task, "runtime-category-io"))) {
            OptionCatalogCapture capture = executor.capture(category, query);

            assertEquals("available", capture.status());
            assertEquals(Set.of("blocks", "tools"), Set.copyOf(capture.values()));
            assertEquals(0, mainSchedules.get());
        }
    }

    @Test
    void distinguishesSupportedEmptyCategoriesFromUnsupportedAndMissingSources() {
        RuntimeDataRegistry runtimeData = new RuntimeDataRegistry();
        runtimeData.register(callerAdapter("test:empty", Set.of(), () -> {
        }));
        runtimeData.register(unsupportedAdapter("test:unsupported"));
        OptionCatalogProvider category = catalogs(runtimeData).provider(RuntimeDataOptionCatalogService.CATEGORY_SOURCE);

        OptionCatalogCapture empty = category.capture(query(category.sourceId(), Map.of("data_type", "item", "source", "test:empty")));
        OptionCatalogCapture unsupported = category.capture(query(category.sourceId(), Map.of("data_type", "item", "source", "test:unsupported")));
        OptionCatalogCapture missing = category.capture(query(category.sourceId(), Map.of("data_type", "item", "source", "test:missing")));

        assertEquals("available", empty.status());
        assertTrue(empty.items().isEmpty());
        assertEquals("unavailable", unsupported.status());
        assertTrue(unsupported.diagnostic().contains("does not expose category captures"));
        assertEquals("unavailable", missing.status());
        assertTrue(missing.diagnostic().contains("source is unavailable"));
    }

    @Test
    void rejectsAdapterReplacementDuringCategoryCapture() {
        RuntimeDataRegistry runtimeData = new RuntimeDataRegistry();
        AtomicBoolean replaced = new AtomicBoolean();
        RuntimeDataAdapter<String> replacement = callerAdapter("test:items", Set.of("replacement"), () -> {
        });
        RuntimeDataAdapter<String> initial = callerAdapter("test:items", Set.of("initial"), () -> {
            if (replaced.compareAndSet(false, true)) {
                runtimeData.unregister("test:items");
                runtimeData.register(replacement);
            }
        });
        runtimeData.register(initial);
        OptionCatalogProvider category = catalogs(runtimeData).provider(RuntimeDataOptionCatalogService.CATEGORY_SOURCE);

        OptionCatalogCapture capture = category.capture(query(category.sourceId(), Map.of("data_type", "item", "source", "test:items")));

        assertEquals("unavailable", capture.status());
        assertTrue(capture.diagnostic().contains("changed during capture"));
        assertFalse(capture.values().contains("initial"));
    }

    @Test
    void rejectsAdapterReplacementDuringIoPreparation() {
        RuntimeDataRegistry runtimeData = new RuntimeDataRegistry();
        AtomicLong state = new AtomicLong(1L);
        AtomicInteger recordCalls = new AtomicInteger();
        AtomicReference<String> captureThread = new AtomicReference<>();
        RuntimeDataAdapter<String> replacement = preparedAdapter("test:items", state, recordCalls, captureThread, () -> {
        });
        AtomicBoolean replaced = new AtomicBoolean();
        RuntimeDataAdapter<String> initial = preparedAdapter("test:items", state, recordCalls, captureThread, () -> {
            if (replaced.compareAndSet(false, true)) {
                runtimeData.unregister("test:items");
                runtimeData.register(replacement);
            }
        });
        runtimeData.register(initial);
        OptionCatalogRegistry catalogs = catalogs(runtimeData);
        OptionCatalogCaptureExecutor executor = (provider, query) -> provider.capture(query);

        CompletionException failure = assertThrows(CompletionException.class,
            () -> RuntimeDataOptionCatalogService.prewarm(catalogs, executor).toCompletableFuture().join());

        assertTrue(failure.getCause() instanceof IllegalStateException);
        assertTrue(failure.getCause().getMessage().contains("changed during preparation"));
        assertEquals(0, recordCalls.get());
    }

    private OptionCatalogRegistry catalogs(RuntimeDataRegistry runtimeData) {
        OptionCatalogRegistry catalogs = new OptionCatalogRegistry(runtimeData);
        new RuntimeDataOptionCatalogService(runtimeData).registerProviders(catalogs);
        return catalogs;
    }

    private OptionCatalogQuery query(String source, Map<String, Object> context) {
        return new OptionCatalogQuery(source, context);
    }

    private RuntimeDataAdapter<String> preparedAdapter(String id, AtomicLong state, AtomicInteger recordCalls,
                                                       AtomicReference<String> captureThread, Runnable captureAction) {
        return new RuntimeDataAdapter<>() {
            private final RuntimeDataCategoryCatalog<Long> categories = new RuntimeDataCategoryCatalog<>(this) {
                @Override
                public CaptureAffinity captureAffinity() {
                    return CaptureAffinity.IO;
                }

                @Override
                protected Snapshot<Long> captureSnapshot() {
                    captureAction.run();
                    captureThread.set(Thread.currentThread().getName());
                    long token = state.get();
                    Set<String> values = token == 1L ? Set.of("building") : Set.of("building", "solid");
                    return snapshot("revision-" + token, List.of(record(id, values)), token);
                }

                @Override
                protected boolean current(Long token) {
                    return state.get() == token;
                }
            };

            @Override
            public String id() {
                return id;
            }

            @Override
            public String domain() {
                return "item";
            }

            @Override
            public FlowTypeRef valueType() {
                return FlowTypeRef.simple("string");
            }

            @Override
            public Class<String> valueClass() {
                return String.class;
            }

            @Override
            public OptionCatalogProvider categoryCatalog() {
                return categories;
            }

            @Override
            public List<RuntimeDataRecord> records(RuntimeDataQuery query) {
                recordCalls.incrementAndGet();
                throw new AssertionError("Aggregate category capture must not enumerate adapter records");
            }

            @Override
            public String resolve(RuntimeDataRecord record, int amount) {
                return record.id();
            }
        };
    }

    private RuntimeDataAdapter<String> callerAdapter(String id, Set<String> categoryValues, Runnable captureAction) {
        return new RuntimeDataAdapter<>() {
            private final RuntimeDataCategoryCatalog<Void> categories = new RuntimeDataCategoryCatalog<>(this) {
                @Override
                public CaptureAffinity captureAffinity() {
                    return CaptureAffinity.CALLER;
                }

                @Override
                protected Snapshot<Void> captureSnapshot() {
                    captureAction.run();
                    List<RuntimeDataRecord> records = categoryValues.isEmpty() ? List.of() : List.of(record(id, categoryValues));
                    return snapshot("caller", records, null);
                }
            };

            @Override
            public String id() {
                return id;
            }

            @Override
            public String domain() {
                return "item";
            }

            @Override
            public FlowTypeRef valueType() {
                return FlowTypeRef.simple("string");
            }

            @Override
            public Class<String> valueClass() {
                return String.class;
            }

            @Override
            public OptionCatalogProvider categoryCatalog() {
                return categories;
            }

            @Override
            public List<RuntimeDataRecord> records(RuntimeDataQuery query) {
                throw new AssertionError("Aggregate category capture must use the category capability");
            }

            @Override
            public String resolve(RuntimeDataRecord record, int amount) {
                return record.id();
            }
        };
    }

    private RuntimeDataAdapter<String> unsupportedAdapter(String id) {
        return new RuntimeDataAdapter<>() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String domain() {
                return "item";
            }

            @Override
            public FlowTypeRef valueType() {
                return FlowTypeRef.simple("string");
            }

            @Override
            public Class<String> valueClass() {
                return String.class;
            }

            @Override
            public List<RuntimeDataRecord> records(RuntimeDataQuery query) {
                throw new AssertionError("Unsupported adapters must not be enumerated for category capture");
            }

            @Override
            public String resolve(RuntimeDataRecord record, int amount) {
                return record.id();
            }
        };
    }

    private RuntimeDataRecord record(String adapter, Set<String> categories) {
        return new RuntimeDataRecord("item", adapter, "stone", "Stone", "", categories, Set.of(), Map.of());
    }
}
