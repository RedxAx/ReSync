package restudio.resync.api;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.CustomContentDefinition;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.customcontent.CustomContentProvider;
import restudio.resync.customcontent.CustomContentService;
import restudio.resync.customcontent.CustomContentStorage;
import restudio.resync.customcontent.ItemAttributeSchemaService;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.FlowValueCodecRegistry;
import restudio.resync.flow.GlobalTriggers;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogPublicationReceiptStore;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.flow.handler.event.FlowEventRegistry;
import restudio.resync.flow.handler.property.PropertyRegistry;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.jobs.FlowJobRegistry;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.triggers.TriggerRegistry;
import restudio.resync.flow.triggers.TriggerDispatcher;
import restudio.resync.flow.validation.FlowGraphValidationRegistry;
import restudio.resync.flow.catalog.CatalogActivationAuthority;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.runtime.RuntimeAuditBoundary;
import restudio.resync.flow.runtime.RuntimeBindingKey;
import restudio.resync.flow.runtime.RuntimeBindingRegistry;
import restudio.resync.flow.runtime.RuntimeExecutionBoundary;
import restudio.resync.flow.runtime.RuntimeRegistrySnapshot;
import restudio.resync.flow.runtime.RuntimeReceiptStore;
import restudio.resync.flow.runtime.RuntimeSecurityBoundary;
import restudio.resync.modules.ModuleContext;
import restudio.resync.modules.FlowModule;
import restudio.resync.modules.ModuleRegistry;
import restudio.resync.modules.flow.FlowResourceRegistry;
import restudio.resync.messages.MessageLogService;
import restudio.resync.migration.PersistenceRootReadiness;
import restudio.resync.text.ReTextService;
import restudio.resync.protocol.Codec;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;

import java.io.IOException;
import java.time.Clock;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.Collection;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncExtensionManagerLifecycleTest {
    private static final ReSyncExtensionManager.LifecycleRecoveryBackend SUPPORTED_RECOVERY_BACKEND = directory -> {
    };
    private static final ServerId CORE_SERVER_ID = ServerId.deterministic("manager-core-lifecycle");

    @TempDir
    Path extensionRoot;

    private JavaPlugin plugin;
    private ModuleContext moduleContext;
    private FaultingCustomContentService customContent;
    private ReSyncExtensionManager manager;
    private final List<CustomContentStorage> contentStorages = new ArrayList<>();
    private final List<ReSyncJsonResourceStorage> jsonStorages = new ArrayList<>();
    private final List<AssetPersistenceGate> assetGates = new ArrayList<>();
    private final List<AssetTransactionCoordinator> assetCoordinators = new ArrayList<>();

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        HandlerRegistry handlers = new HandlerRegistry();
        TypeAdapterRegistry adapters = new TypeAdapterRegistry();
        RuntimeDataRegistry runtimeData = new RuntimeDataRegistry();
        OptionCatalogRegistry catalogs = new OptionCatalogRegistry(runtimeData);
        moduleContext = new ModuleContext(null, null, null, null, null, null, new ModuleRegistry(),
            null, null, null, null, null, null);
        moduleContext.registerService(NodeDefinitionRegistry.class, new NodeDefinitionRegistry(false));
        moduleContext.registerService(HandlerRegistry.class, handlers);
        moduleContext.registerService(PropertyRegistry.class, new PropertyRegistry());
        moduleContext.registerService(OptionCatalogRegistry.class, catalogs);
        moduleContext.registerService(RuntimeDataRegistry.class, runtimeData);
        moduleContext.registerService(FlowValueCodecRegistry.class, new FlowValueCodecRegistry());
        moduleContext.registerService(TypeAdapterRegistry.class, adapters);
        moduleContext.registerService(FlowGraphValidationRegistry.class, new FlowGraphValidationRegistry());
        moduleContext.registerService(FlowResourceRegistry.class, new FlowResourceRegistry());
        moduleContext.registerService(ReSyncExtensionData.class, new ReSyncExtensionData());
        moduleContext.registerService(FlowRegistry.class, new FlowRegistry());
        moduleContext.registerService(FlowExecutor.class, new FlowExecutor(handlers, adapters, Map.of()));
        customContent = new FaultingCustomContentService(customContentStorage(), null,
            moduleContext.getService(FlowExecutor.class));
        moduleContext.registerService(CustomContentService.class, customContent);
        manager = new ReSyncExtensionManager(moduleContext, extensionRoot, SUPPORTED_RECOVERY_BACKEND);
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            assetGates.forEach(AssetPersistenceGate::quiesce);
            for (ReSyncJsonResourceStorage storage : jsonStorages.reversed()) {
                storage.closePersistence();
            }
            for (CustomContentStorage storage : contentStorages.reversed()) {
                storage.close();
            }
            for (AssetTransactionCoordinator coordinator : assetCoordinators.reversed()) {
                coordinator.close();
            }
        } finally {
            MockBukkit.unmock();
        }
    }

    @Test
    void noOpTickKeepsAdmissionsOpenWhileAnExecutionIsPending() throws Exception {
        manager.registerBukkitExtension(plugin, extension("enabled-owner", context -> {
        }, () -> {
        }, () -> {
        }));
        PendingExecution fixture = pendingExecution();
        try {
            CompletableFuture<Void> running = fixture.execute();
            assertFalse(running.isDone());

            manager.tick();

            fixture.execute().get(5, TimeUnit.SECONDS);
            assertEquals(2, fixture.invocations().get());
            assertFalse(running.isDone());
            assertTrue(manager.getPluginIds().contains("enabled-owner"));
            fixture.completion().complete(null);
            running.get(5, TimeUnit.SECONDS);
        } finally {
            fixture.completion().complete(null);
            fixture.executor().shutdown();
        }
    }

    @Test
    void disabledOwnerTickFencesAdmissionsUntilPendingExecutionDrains() throws Exception {
        AtomicInteger stops = new AtomicInteger();
        manager.registerBukkitExtension(plugin, extension("disabled-owner", context -> {
        }, () -> {
        }, stops::incrementAndGet));
        PendingExecution fixture = pendingExecution();
        try {
            CompletableFuture<Void> running = fixture.execute();
            assertFalse(running.isDone());
            Bukkit.getPluginManager().disablePlugin(plugin);

            manager.tick();

            CompletionException failure = assertThrows(CompletionException.class, () -> fixture.execute().join());
            assertEquals("EXECUTION_FENCED",
                assertInstanceOf(FlowExecutor.FlowExecutionException.class, failure.getCause()).getCode());
            assertTrue(manager.getPluginIds().contains("disabled-owner"));
            assertEquals(0, stops.get());
            assertFalse(running.isDone());

            fixture.completion().complete(null);
            running.get(5, TimeUnit.SECONDS);

            assertFalse(manager.getPluginIds().contains("disabled-owner"));
            assertEquals(1, stops.get());
            fixture.execute().get(5, TimeUnit.SECONDS);
            assertEquals(2, fixture.invocations().get());
        } finally {
            fixture.completion().complete(null);
            fixture.executor().shutdown();
        }
    }

    private PendingExecution pendingExecution() {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        AtomicInteger invocations = new AtomicInteger();
        moduleContext.getService(HandlerRegistry.class).register("pending-lifecycle", new NodeHandler() {
            @Override
            public void execute(FlowContext context, FlowNode node) {
                if (invocations.incrementAndGet() == 1) {
                    context.awaitBeforeContinuation(completion);
                }
            }

            @Override
            public ThreadPolicy getThreadPolicy() {
                return ThreadPolicy.CURRENT;
            }
        });
        FlowGraph graph = new FlowGraph();
        graph.setId("pending-lifecycle");
        graph.getNodes().put("start", new FlowNode("pending-lifecycle", 0, 0, Map.of()));
        return new PendingExecution(moduleContext.getService(FlowExecutor.class), graph, completion, invocations);
    }

    private record PendingExecution(FlowExecutor executor, FlowGraph graph, CompletableFuture<Void> completion,
                                    AtomicInteger invocations) {
        private CompletableFuture<Void> execute() {
            return executor.execute(graph, "start", null, null, Map.of());
        }
    }

    @Test
    void externalRemovalRestoresProviderWhenMutationThrowsAfterRemoval() {
        CustomContentProvider provider = provider("remove:provider");
        ReSyncExtension extension = extension("remove", context -> context.customContent().register(provider),
            () -> {
            }, () -> {
            });
        ExtensionRegistration registration = manager.registerBukkitExtension(plugin, extension);

        customContent.throwAfterUnregister.set(true);
        registration.close();
        assertTrue(manager.getPluginIds().contains("remove"));
        assertTrue(customContent.hasProvider(provider.getId()));
        assertFalse(manager.isShutdownPending());
    }

    @Test
    void externalAdditionRestoresMapWhenMutationThrowsAfterAddition() {
        CustomContentProvider provider = provider("add:provider");
        customContent.throwAfterRegister.set(true);

        assertThrows(IllegalStateException.class, () -> manager.registerBukkitExtension(plugin,
            extension("add", context -> context.customContent().register(provider), () -> {
            }, () -> {
            })));
        assertTrue(manager.getPluginIds().isEmpty());
        assertFalse(customContent.hasProvider(provider.getId()));
        assertFalse(manager.isShutdownPending());
    }

    @Test
    void oldStopFailureRestoresExtensionMapAndRunsInverseStart() {
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger stops = new AtomicInteger();
        AtomicBoolean failStop = new AtomicBoolean();
        ReSyncExtension extension = extension("stop", context -> {
        }, starts::incrementAndGet, () -> {
            stops.incrementAndGet();
            if (failStop.get()) {
                throw new IllegalStateException("stop failure");
            }
        });
        ExtensionRegistration registration = manager.registerBukkitExtension(plugin, extension);
        failStop.set(true);

        registration.close();
        assertTrue(manager.getPluginIds().contains("stop"));
        assertEquals(2, starts.get());
        assertEquals(1, stops.get());
    }

    @Test
    void mapSwapFailureRestoresThePreviousExtensionMap() {
        AtomicBoolean registryStayedDetached = new AtomicBoolean();
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger stops = new AtomicInteger();
        ReSyncExtensionManager mapManager = new ReSyncExtensionManager(moduleContext,
            List.of(extensionRoot.resolve("map")), phase -> {
                if (phase == ReSyncExtensionManager.LifecyclePhase.MAP_SWAP) {
                    ExtensionRegistryActivation.State snapshot = moduleContext
                        .getService(ExtensionRegistryActivation.class).snapshot();
                    registryStayedDetached.set(snapshot.extensionIds().isEmpty()
                        && snapshot.extensionData().pluginIds().isEmpty());
                    throw new IllegalStateException("map swap failure");
                }
            }, SUPPORTED_RECOVERY_BACKEND);

        assertThrows(IllegalStateException.class, () -> mapManager.registerBukkitExtension(plugin,
            extension("map", context -> {
            }, starts::incrementAndGet, stops::incrementAndGet)));
        assertTrue(mapManager.getPluginIds().isEmpty());
        assertTrue(mapManager.activeRegistryState().extensionIds().isEmpty());
        assertTrue(registryStayedDetached.get());
        assertEquals(1, starts.get());
        assertEquals(1, stops.get());
    }

    @Test
    void unsupportedRecoveryDurabilityFencesBeforeMutation() throws Exception {
        AtomicBoolean probed = new AtomicBoolean();
        Path unsupportedRoot = extensionRoot.resolve("unsupported");
        Files.createDirectories(unsupportedRoot);
        Path marker = unsupportedRoot.resolve(".resync-lifecycle-recovery");
        Files.writeString(marker, "existing");
        ReSyncExtensionManager unsupportedManager = new ReSyncExtensionManager(moduleContext,
            List.of(unsupportedRoot), ReSyncExtensionManager.LifecycleFailureInjector.none(),
            ReSyncExtensionManager.LifecycleRecoveryDurability.none(), directory -> {
                probed.set(true);
                throw new UnsupportedOperationException("directory force unavailable");
            });
        ExtensionRegistryActivation.State before = unsupportedManager.activeRegistryState();

        assertTrue(probed.get());
        assertFalse(unsupportedManager.recoveryDurabilityAvailable());
        assertThrows(IllegalStateException.class, () -> unsupportedManager.registerBukkitExtension(plugin,
            extension("unsupported", context -> {
            }, () -> {
            }, () -> {
            })));
        assertSame(before, unsupportedManager.activeRegistryState());
        assertTrue(unsupportedManager.getPluginIds().isEmpty());
        assertTrue(unsupportedManager.isShutdownPending());
        assertEquals("existing", Files.readString(marker));
    }

    @Test
    void pendingClearRetriesDirectoryForceAfterMarkerDeletion() {
        Path root = extensionRoot.resolve("pending-force");
        AtomicBoolean failBeforeClear = new AtomicBoolean(true);
        AtomicBoolean failPostDelete = new AtomicBoolean();
        AtomicInteger forceCalls = new AtomicInteger();
        CustomContentProvider provider = provider("pending-force:provider");
        ReSyncExtensionManager.LifecycleRecoveryBackend backend = directory -> {
            forceCalls.incrementAndGet();
            if (failPostDelete.get() && !Files.exists(directory.resolve(".resync-lifecycle-recovery"))
                && failPostDelete.compareAndSet(true, false)) {
                throw new IOException("post-delete directory force failure");
            }
        };
        ReSyncExtensionManager recoveryManager = new ReSyncExtensionManager(moduleContext, List.of(root),
            phase -> {
                if (phase == ReSyncExtensionManager.LifecyclePhase.EXTERNAL_ADD) {
                    throw new IllegalStateException("mutation failure");
                }
            }, (point, marker) -> {
                if (point == ReSyncExtensionManager.RecoveryPoint.BEFORE_MARKER_CLEAR
                    && failBeforeClear.getAndSet(false)) {
                    throw new IllegalStateException("initial clear failure");
                }
            }, backend);

        assertThrows(IllegalStateException.class, () -> recoveryManager.registerBukkitExtension(plugin,
            extension("pending-force", context -> context.customContent().register(provider), () -> {
            }, () -> {
            })));
        Path marker = root.resolve(".resync-lifecycle-recovery");
        assertTrue(Files.exists(marker));
        int beforeRetry = forceCalls.get();
        failPostDelete.set(true);

        recoveryManager.tick();
        int afterFirstRetry = forceCalls.get();
        assertTrue(afterFirstRetry > beforeRetry);
        assertFalse(Files.exists(marker));
        assertTrue(recoveryManager.isShutdownPending());

        recoveryManager.tick();
        assertTrue(forceCalls.get() > afterFirstRetry);
        assertFalse(recoveryManager.isShutdownPending());
    }

    @Test
    void startupRecoveryRetriesDirectoryForceAfterMarkerDeletion() throws Exception {
        Path root = extensionRoot.resolve("startup-force");
        AtomicBoolean failPostDelete = new AtomicBoolean();
        AtomicInteger forceCalls = new AtomicInteger();
        ReSyncExtensionManager.LifecycleRecoveryBackend backend = directory -> {
            forceCalls.incrementAndGet();
            if (failPostDelete.get() && !Files.exists(directory.resolve(".resync-lifecycle-recovery"))
                && failPostDelete.compareAndSet(true, false)) {
                throw new IOException("startup post-delete directory force failure");
            }
        };
        ReSyncExtensionManager seed = new ReSyncExtensionManager(moduleContext, root, backend);
        Path marker = root.resolve(".resync-lifecycle-recovery");
        Files.writeString(marker, "version=1\n");
        ReSyncExtensionManager restarted = new ReSyncExtensionManager(moduleContext, root, backend);
        assertTrue(seed.recoveryDurabilityAvailable());
        assertTrue(restarted.startupRecoveryRequired());
        int beforeRetry = forceCalls.get();
        failPostDelete.set(true);

        assertThrows(IllegalStateException.class, restarted::loadInitialExtensions);
        int afterFirstRetry = forceCalls.get();
        assertTrue(afterFirstRetry > beforeRetry);
        assertTrue(restarted.startupRecoveryRequired());
        assertFalse(Files.exists(marker));

        restarted.loadInitialExtensions();
        assertTrue(forceCalls.get() > afterFirstRetry);
        assertFalse(restarted.startupRecoveryRequired());
    }

    @Test
    void reloadStartFailureLeavesThePublishedStateUntouched() {
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger stops = new AtomicInteger();
        AtomicBoolean failNextStart = new AtomicBoolean();
        ReSyncExtension extension = extension("reload", context -> {
        }, () -> {
            if (failNextStart.getAndSet(false)) {
                throw new IllegalStateException("start failure");
            }
            starts.incrementAndGet();
        }, stops::incrementAndGet);
        manager.registerBukkitExtension(plugin, extension);
        failNextStart.set(true);

        assertFalse(manager.reloadExtensions());
        assertTrue(manager.getPluginIds().contains("reload"));
        assertEquals(1, starts.get());
        assertEquals(1, stops.get());
    }

    @Test
    void failedExternalCompensationRetainsFenceUntilRetrySucceeds() {
        CustomContentProvider provider = provider("retry:provider");
        customContent.throwAfterRegister.set(true);
        customContent.throwAfterUnregister.set(true);

        assertThrows(IllegalStateException.class, () -> manager.registerBukkitExtension(plugin,
            extension("retry", context -> context.customContent().register(provider), () -> {
            }, () -> {
            })));
        assertTrue(manager.isShutdownPending());
        customContent.throwAfterRegister.set(false);
        customContent.throwAfterUnregister.set(false);

        manager.tick();

        assertFalse(manager.isShutdownPending());
        assertFalse(customContent.hasProvider(provider.getId()));
        ExtensionRegistration recovered = manager.registerBukkitExtension(plugin,
            extension("recovered", context -> {
            }, () -> {
            }, () -> {
            }));
        recovered.close();
    }

    @Test
    void restartFailsClosedUntilPendingExternalEffectsAreReconciled() {
        CustomContentProvider provider = provider("restart:provider");
        customContent.throwAfterRegister.set(true);
        customContent.throwAfterUnregister.set(true);

        assertThrows(IllegalStateException.class, () -> manager.registerBukkitExtension(plugin,
            extension("restart", context -> context.customContent().register(provider), () -> {
            }, () -> {
            })));
        Path marker = extensionRoot.resolve(".resync-lifecycle-recovery");
        assertTrue(Files.exists(marker));
        customContent.throwAfterRegister.set(false);
        customContent.throwAfterUnregister.set(false);
        customContent.registerProvider(provider);

        ReSyncExtensionManager restarted = new ReSyncExtensionManager(moduleContext, extensionRoot,
            SUPPORTED_RECOVERY_BACKEND);
        assertTrue(restarted.startupRecoveryRequired());
        assertThrows(IllegalStateException.class, restarted::loadInitialExtensions);

        customContent.unregisterProvider(provider.getId());
        restarted.loadInitialExtensions();
        assertFalse(restarted.startupRecoveryRequired());
        assertFalse(Files.exists(marker));
    }

    @Test
    void recoveryMarkerBeforeRenamePreservesTemporaryEvidenceAndFailsClosed() throws IOException {
        Path root = extensionRoot.resolve("before-rename");
        ReSyncExtensionManager recoveryManager = new ReSyncExtensionManager(moduleContext, List.of(root),
            ReSyncExtensionManager.LifecycleFailureInjector.none(), (point, marker) -> {
                if (point == ReSyncExtensionManager.RecoveryPoint.BEFORE_MARKER_RENAME) {
                    throw new IllegalStateException("marker rename crash");
                }
            }, SUPPORTED_RECOVERY_BACKEND);

        assertThrows(IllegalStateException.class, () -> recoveryManager.registerBukkitExtension(plugin,
            extension("before-rename", context -> {
            }, () -> {
            }, () -> {
            })));
        assertFalse(Files.exists(root.resolve(".resync-lifecycle-recovery")));
        List<Path> temporaryEvidence;
        try (var entries = Files.list(root)) {
            temporaryEvidence = entries.filter(path -> path.getFileName().toString()
                .startsWith(".resync-lifecycle-recovery.")
                && path.getFileName().toString().endsWith(".tmp")).toList();
        }
        assertEquals(1, temporaryEvidence.size());
        assertEquals("version=1\nplugin=before-rename\n", Files.readString(temporaryEvidence.getFirst()));
        assertTrue(recoveryManager.getPluginIds().isEmpty());
        assertTrue(recoveryManager.startupRecoveryRequired());
        assertTrue(recoveryManager.isShutdownPending());
    }

    @Test
    void startupInventoryPreservesCanonicalTemporaryEvidenceAndFencesRecovery() throws IOException {
        Path root = extensionRoot.resolve("orphan-temporary");
        ReSyncExtensionManager seed = new ReSyncExtensionManager(moduleContext, root,
            SUPPORTED_RECOVERY_BACKEND);
        Path temporary = root.resolve(".resync-lifecycle-recovery.00000000-0000-0000-0000-000000000001.tmp");
        Files.writeString(temporary, "version=1\n");

        ReSyncExtensionManager restarted = new ReSyncExtensionManager(moduleContext, root,
            SUPPORTED_RECOVERY_BACKEND);

        assertTrue(seed.recoveryDurabilityAvailable());
        assertTrue(restarted.startupRecoveryRequired());
        assertThrows(IllegalStateException.class, restarted::loadInitialExtensions);
        assertEquals("version=1\n", Files.readString(temporary));
    }

    @Test
    void markerPublicationCollisionPreservesExistingRecoveryEvidence() throws IOException {
        Path root = extensionRoot.resolve("marker-collision");
        ReSyncExtensionManager recoveryManager = new ReSyncExtensionManager(moduleContext, root,
            SUPPORTED_RECOVERY_BACKEND);
        Path marker = root.resolve(".resync-lifecycle-recovery");
        Files.writeString(marker, "version=1\n");

        assertThrows(IllegalStateException.class, () -> recoveryManager.registerBukkitExtension(plugin,
            extension("marker-collision", context -> {
            }, () -> {
            }, () -> {
            })));

        assertEquals("version=1\n", Files.readString(marker));
        assertTrue(recoveryManager.startupRecoveryRequired());
        assertTrue(recoveryManager.isShutdownPending());
    }

    @Test
    void malformedRecoveryMarkerContentRemainsFencedAndUntouched() throws IOException {
        Path root = extensionRoot.resolve("malformed-marker");
        Path marker = root.resolve(".resync-lifecycle-recovery");
        Files.createDirectories(root);
        Files.writeString(marker, "version=1\nplugin=malformed\nplugin=malformed\n");

        ReSyncExtensionManager recoveryManager = new ReSyncExtensionManager(moduleContext, root,
            SUPPORTED_RECOVERY_BACKEND);

        assertTrue(recoveryManager.startupRecoveryRequired());
        assertThrows(IllegalStateException.class, recoveryManager::loadInitialExtensions);
        assertEquals("version=1\nplugin=malformed\nplugin=malformed\n", Files.readString(marker));
    }

    @Test
    void oversizedRecoveryMarkerIsRejectedBeforeTemporaryPublication() throws IOException {
        Path root = extensionRoot.resolve("oversized-marker");
        ReSyncExtensionManager recoveryManager = new ReSyncExtensionManager(moduleContext, root,
            SUPPORTED_RECOVERY_BACKEND);
        String oversizedProviderId = "oversized-marker:" + "x".repeat(70000);

        assertThrows(IllegalStateException.class, () -> recoveryManager.registerBukkitExtension(plugin,
            extension("oversized-marker", context -> context.customContent().register(provider(oversizedProviderId)),
                () -> {
                }, () -> {
                })));

        try (var entries = Files.list(root)) {
            assertTrue(entries.noneMatch(path -> path.getFileName().toString()
                .startsWith(".resync-lifecycle-recovery.")
                && path.getFileName().toString().endsWith(".tmp")));
        }
        assertFalse(Files.exists(root.resolve(".resync-lifecycle-recovery")));
        assertFalse(recoveryManager.startupRecoveryRequired());
    }

    @Test
    void lifecycleRecoveryLockOwnershipFencesAnotherManager() throws IOException {
        Path root = extensionRoot.resolve("lock-ownership");
        ReSyncExtensionManager first = new ReSyncExtensionManager(moduleContext, root,
            SUPPORTED_RECOVERY_BACKEND);
        Path lockPath = root.resolve(".resync-lifecycle-recovery.lock");
        assertTrue(first.recoveryDurabilityAvailable());

        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.WRITE);
             FileLock lock = channel.lock()) {
            ReSyncExtensionManager second = new ReSyncExtensionManager(moduleContext, root,
                SUPPORTED_RECOVERY_BACKEND);
            assertFalse(second.recoveryDurabilityAvailable());
            assertThrows(IllegalStateException.class, () -> second.registerBukkitExtension(plugin,
                extension("lock-ownership", context -> {
                }, () -> {
                }, () -> {
                })));
        }
    }

    @Test
    void replacementRecoveryMarkerIsPreservedWhenClearIdentityChanges() throws IOException {
        Path root = extensionRoot.resolve("replacement-marker");
        AtomicBoolean replaced = new AtomicBoolean();
        ReSyncExtensionManager recoveryManager = new ReSyncExtensionManager(moduleContext, List.of(root),
            ReSyncExtensionManager.LifecycleFailureInjector.none(), (point, marker) -> {
                if (point == ReSyncExtensionManager.RecoveryPoint.BEFORE_MARKER_CLEAR
                    && replaced.compareAndSet(false, true)) {
                    try {
                        Path replacement = marker.resolveSibling(".replacement-marker");
                        Files.writeString(replacement, "version=1\nplugin=replacement\n");
                        Files.move(replacement, marker, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException exception) {
                        throw new IllegalStateException("marker replacement failed", exception);
                    }
                }
            }, SUPPORTED_RECOVERY_BACKEND);

        assertThrows(IllegalStateException.class, () -> recoveryManager.registerBukkitExtension(plugin,
            extension("replacement-marker", context -> {
            }, () -> {
            }, () -> {
            })));

        assertTrue(replaced.get());
        assertEquals("version=1\nplugin=replacement\n",
            Files.readString(root.resolve(".resync-lifecycle-recovery")));
        assertTrue(recoveryManager.isShutdownPending());
    }

    @Test
    void recoveryMarkerPublishedBeforeMutationRetainsFenceUntilClearRecovery() {
        Path root = extensionRoot.resolve("published");
        AtomicBoolean failClear = new AtomicBoolean(true);
        ReSyncExtensionManager recoveryManager = new ReSyncExtensionManager(moduleContext, List.of(root),
            ReSyncExtensionManager.LifecycleFailureInjector.none(), (point, marker) -> {
                if (point == ReSyncExtensionManager.RecoveryPoint.AFTER_MARKER_PUBLISH) {
                    throw new IllegalStateException("published marker crash");
                }
                if (point == ReSyncExtensionManager.RecoveryPoint.BEFORE_MARKER_CLEAR && failClear.get()) {
                    throw new IllegalStateException("marker clear crash");
                }
            }, SUPPORTED_RECOVERY_BACKEND);

        assertThrows(IllegalStateException.class, () -> recoveryManager.registerBukkitExtension(plugin,
            extension("published", context -> {
            }, () -> {
            }, () -> {
            })));
        assertTrue(Files.exists(root.resolve(".resync-lifecycle-recovery")));
        assertTrue(recoveryManager.getPluginIds().isEmpty());
        assertTrue(recoveryManager.isShutdownPending());

        failClear.set(false);
        recoveryManager.tick();
        assertFalse(Files.exists(root.resolve(".resync-lifecycle-recovery")));
        assertFalse(recoveryManager.isShutdownPending());
    }

    @Test
    void mutationBeforeMarkerClearRetainsRecoveryMarkerAfterExternalRollback() {
        Path root = extensionRoot.resolve("mutation");
        AtomicBoolean failClear = new AtomicBoolean(true);
        CustomContentProvider provider = provider("mutation:provider");
        ReSyncExtensionManager recoveryManager = new ReSyncExtensionManager(moduleContext, List.of(root),
            phase -> {
                if (phase == ReSyncExtensionManager.LifecyclePhase.EXTERNAL_ADD) {
                    throw new IllegalStateException("mutation crash");
                }
            }, (point, marker) -> {
                if (point == ReSyncExtensionManager.RecoveryPoint.BEFORE_MARKER_CLEAR && failClear.get()) {
                    throw new IllegalStateException("marker clear crash");
                }
            }, SUPPORTED_RECOVERY_BACKEND);

        assertThrows(IllegalStateException.class, () -> recoveryManager.registerBukkitExtension(plugin,
            extension("mutation", context -> context.customContent().register(provider), () -> {
            }, () -> {
            })));
        assertFalse(customContent.hasProvider(provider.getId()));
        assertTrue(Files.exists(root.resolve(".resync-lifecycle-recovery")));
        assertTrue(recoveryManager.isShutdownPending());

        failClear.set(false);
        recoveryManager.tick();
        assertFalse(Files.exists(root.resolve(".resync-lifecycle-recovery")));
        assertFalse(recoveryManager.isShutdownPending());
    }

    @Test
    void markerClearCompletesBeforeRestartAndDoesNotResurrectExtension() {
        Path root = extensionRoot.resolve("after-clear");
        AtomicBoolean failAfterClear = new AtomicBoolean(true);
        ReSyncExtensionManager recoveryManager = new ReSyncExtensionManager(moduleContext, List.of(root),
            ReSyncExtensionManager.LifecycleFailureInjector.none(), (point, marker) -> {
                if (point == ReSyncExtensionManager.RecoveryPoint.AFTER_MARKER_CLEAR && failAfterClear.getAndSet(false)) {
                    throw new IllegalStateException("marker clear completed crash");
                }
            }, SUPPORTED_RECOVERY_BACKEND);
        assertThrows(IllegalStateException.class, () -> recoveryManager.registerBukkitExtension(plugin,
            extension("after-clear", context -> {
            }, () -> {
            }, () -> {
            })));

        Path marker = root.resolve(".resync-lifecycle-recovery");
        assertFalse(Files.exists(marker));
        assertTrue(recoveryManager.getPluginIds().isEmpty());
        ReSyncExtensionManager restarted = new ReSyncExtensionManager(moduleContext, root,
            SUPPORTED_RECOVERY_BACKEND);
        restarted.loadInitialExtensions();
        assertFalse(restarted.startupRecoveryRequired());
        assertTrue(restarted.getPluginIds().isEmpty());
    }

    @Test
    void quiesceFailureCompensatesCompletedExtensionsAndCanRetry() throws IOException {
        Path root = extensionRoot.resolve("quiesce-retry");
        AtomicBoolean failSecond = new AtomicBoolean(true);
        AtomicInteger firstQuiesces = new AtomicInteger();
        AtomicInteger firstResumes = new AtomicInteger();
        AtomicInteger secondQuiesces = new AtomicInteger();
        AtomicInteger secondResumes = new AtomicInteger();
        ReSyncExtensionManager quiesceManager = new ReSyncExtensionManager(moduleContext, root,
            SUPPORTED_RECOVERY_BACKEND);

        quiesceManager.registerBukkitExtension(plugin, persistenceExtension("quiesce-first",
            () -> firstQuiesces.incrementAndGet(), () -> firstResumes.incrementAndGet()));
        quiesceManager.registerBukkitExtension(plugin, persistenceExtension("quiesce-second", () -> {
            secondQuiesces.incrementAndGet();
            if (failSecond.get()) {
                throw new IOException("second extension quiesce failure");
            }
        }, () -> secondResumes.incrementAndGet()));

        assertThrows(IOException.class, quiesceManager::quiescePersistence);
        assertEquals(1, firstQuiesces.get());
        assertEquals(1, firstResumes.get());
        assertEquals(1, secondQuiesces.get());
        assertEquals(0, secondResumes.get());
        quiesceManager.flushPersistence();

        failSecond.set(false);
        quiesceManager.quiescePersistence();
        assertEquals(2, firstQuiesces.get());
        assertEquals(2, secondQuiesces.get());
        quiesceManager.resumePersistence();
        assertEquals(2, firstResumes.get());
        assertEquals(1, secondResumes.get());
    }

    @Test
    void quiesceCompensationFailureRemainsFaultedUntilResumeRetry() throws IOException {
        Path root = extensionRoot.resolve("quiesce-fault");
        AtomicBoolean failSecond = new AtomicBoolean(true);
        AtomicBoolean failFirstResume = new AtomicBoolean(true);
        ReSyncExtensionManager quiesceManager = new ReSyncExtensionManager(moduleContext, root,
            SUPPORTED_RECOVERY_BACKEND);

        quiesceManager.registerBukkitExtension(plugin, persistenceExtension("fault-first", () -> {
        }, () -> {
            if (failFirstResume.get()) {
                throw new IOException("first extension resume failure");
            }
        }));
        quiesceManager.registerBukkitExtension(plugin, persistenceExtension("fault-second", () -> {
            if (failSecond.get()) {
                throw new IOException("second extension quiesce failure");
            }
        }, () -> {
        }));

        assertThrows(IOException.class, quiesceManager::quiescePersistence);
        assertThrows(IOException.class, quiesceManager::resumePersistence);
        assertThrows(IOException.class, quiesceManager::flushPersistence);

        failFirstResume.set(false);
        quiesceManager.resumePersistence();
        failSecond.set(false);
        quiesceManager.quiescePersistence();
    }

    @Test
    void failedDurabilityProbeLeavesRecognizedProbeEvidenceForBoundedRecovery() throws IOException {
        Path root = extensionRoot.resolve("probe-recovery");
        AtomicBoolean failFirstForce = new AtomicBoolean(true);
        ReSyncExtensionManager.LifecycleRecoveryBackend backend = directory -> {
            if (failFirstForce.getAndSet(false)) {
                throw new IOException("probe directory force failure");
            }
        };

        ReSyncExtensionManager failed = new ReSyncExtensionManager(moduleContext, root, backend);
        assertFalse(failed.recoveryDurabilityAvailable());
        assertTrue(failed.startupRecoveryRequired());
        try (var entries = Files.list(root)) {
            assertTrue(entries.anyMatch(path -> path.getFileName().toString()
                .startsWith(".resync-lifecycle-capability-")
                && (path.getFileName().toString().endsWith(".source")
                || path.getFileName().toString().endsWith(".tmp"))));
        }

        ReSyncExtensionManager restarted = new ReSyncExtensionManager(moduleContext, root, backend);
        assertTrue(restarted.recoveryDurabilityAvailable());
        assertFalse(restarted.startupRecoveryRequired());
        try (var entries = Files.list(root)) {
            assertTrue(entries.noneMatch(path -> path.getFileName().toString()
                .startsWith(".resync-lifecycle-capability-")
                && (path.getFileName().toString().endsWith(".source")
                || path.getFileName().toString().endsWith(".tmp"))));
        }
    }

    @Test
    void eventProjectionDoubleFailureRetainsAdmissionFenceAndFailsClosed() {
        AtomicBoolean failCandidate = new AtomicBoolean();
        AtomicBoolean failRollback = new AtomicBoolean();
        AtomicBoolean candidateSeen = new AtomicBoolean();
        AtomicBoolean candidateMembershipSeen = new AtomicBoolean();
        AtomicBoolean rollbackMembershipSeen = new AtomicBoolean();
        TriggerDispatcher dispatcher = new TriggerDispatcher(null, null, plugin) {
            @Override
            public synchronized void replaceManagedDefinitions(Set<String> managedNodeTypes,
                                                               Collection<ManagedDefinition> replacements) {
                boolean candidate = replacements.stream().anyMatch(value -> "projection/event".equals(value.nodeType()));
                if (candidate && failCandidate.get()) {
                    candidateSeen.set(true);
                    candidateMembershipSeen.set(manager.getPluginIds().contains("projection"));
                    throw new IllegalStateException("candidate projection failure");
                }
                if (!candidate && candidateSeen.get() && failRollback.get()) {
                    rollbackMembershipSeen.set(manager.getPluginIds().isEmpty());
                    throw new IllegalStateException("rollback projection failure");
                }
            }
        };
        moduleContext.registerService(FlowEventRegistry.class, new FlowEventRegistry(dispatcher,
            moduleContext.getService(TypeAdapterRegistry.class)));
        failCandidate.set(true);
        failRollback.set(true);

        assertThrows(IllegalStateException.class, () -> manager.registerBukkitExtension(plugin,
            extension("projection", context -> context.flow().registerNode(
                new NodeDefinition.Builder("projection:event", "Projection", NodeDefinition.NodeCategory.EVENT)
                    .trigger(true)
                    .eventType("org.bukkit.event.player.PlayerJoinEvent")
                    .description("Provides a projection failure lifecycle test event.")
                    .build()), () -> {
            }, () -> {
            })));
        assertTrue(manager.getPluginIds().isEmpty());
        assertTrue(candidateMembershipSeen.get());
        assertTrue(rollbackMembershipSeen.get());
        assertTrue(manager.isShutdownPending());
        manager.tick();
        assertTrue(manager.isShutdownPending());
        assertThrows(IllegalStateException.class, () -> manager.registerBukkitExtension(plugin,
            extension("projection-two", context -> {
            }, () -> {
            }, () -> {
            })));
    }

    @Test
    void aggregateProjectionFailureRollsBackTheRealCatalogRuntimeTransaction() {
        CoreFixture fixture = coreFixture(new AtomicBoolean(), new AtomicBoolean());
        ExtensionRegistration stable = fixture.manager.registerBukkitExtension(plugin,
            extension("stable", context -> {
                context.flow().registerHandler("stable:handler", handler());
                context.flow().registerNode(new NodeDefinition.Builder("stable:node", "Stable",
                    NodeDefinition.NodeCategory.UTILITY)
                    .handler("stable:handler")
                    .handlerConfig(Map.of("operation", "execute"))
                    .description("Provides the stable aggregate lifecycle capability.")
                    .build());
            }, () -> {
            }, () -> {
            }));
        ContentHash previousCatalogChecksum = fixture.flow.activeCatalogSnapshot().contentChecksum();
        ContentHash previousBindingManifestHash = fixture.flow.activeRuntimeBindingManifest().bindingManifestHash();
        long previousGeneration = fixture.flow.activeCatalogSnapshot().generation();
        var previousPublicationKey = fixture.flow.activeCatalogPublicationKey();
        AtomicBoolean candidateSeen = new AtomicBoolean();
        AtomicBoolean candidateMembershipSeen = new AtomicBoolean();
        TriggerDispatcher dispatcher = new TriggerDispatcher(null, null, plugin) {
            @Override
            public synchronized void replaceManagedDefinitions(Set<String> managedNodeTypes,
                                                               Collection<ManagedDefinition> replacements) {
                if (replacements.stream().anyMatch(value -> "aggregate/event".equals(value.nodeType()))) {
                    candidateSeen.set(true);
                    candidateMembershipSeen.set(fixture.manager.getPluginIds().equals(Set.of("stable", "aggregate")));
                    throw new IllegalStateException("aggregate projection failure");
                }
            }
        };
        fixture.context.registerService(FlowEventRegistry.class, new FlowEventRegistry(dispatcher,
            fixture.context.getService(TypeAdapterRegistry.class)));

        assertThrows(IllegalStateException.class, () -> fixture.manager.registerBukkitExtension(plugin,
            extension("aggregate", context -> {
                context.flow().registerHandler("aggregate:handler", handler());
                context.flow().registerNode(new NodeDefinition.Builder("aggregate:event", "Aggregate",
                    NodeDefinition.NodeCategory.EVENT)
                    .handler("aggregate:handler")
                    .handlerConfig(Map.of("operation", "execute"))
                    .trigger(true)
                    .eventType("org.bukkit.event.player.PlayerJoinEvent")
                    .description("Provides the aggregate projection failure capability.")
                    .build());
            }, () -> {
            }, () -> {
            })));

        assertTrue(candidateSeen.get());
        assertTrue(candidateMembershipSeen.get());
        assertEquals(Set.of("stable"), fixture.manager.getPluginIds());
        assertEquals(previousCatalogChecksum, fixture.flow.activeCatalogSnapshot().contentChecksum());
        assertEquals(previousGeneration, fixture.flow.activeCatalogSnapshot().generation());
        assertEquals(previousBindingManifestHash, fixture.flow.activeRuntimeBindingManifest().bindingManifestHash());
        assertEquals(previousPublicationKey, fixture.flow.activeCatalogPublicationKey());
        assertTrue(fixture.flow.isCatalogCoherent());
        assertFalse(fixture.manager.isShutdownPending());
        stable.close();
    }

    @Test
    void coreCommitFailureRetainsPendingCleanupUntilRollbackRefreshCanRetry() {
        AtomicBoolean failCoreCommit = new AtomicBoolean();
        AtomicBoolean failRollbackRefresh = new AtomicBoolean();
        CoreFixture fixture = coreFixture(failCoreCommit, failRollbackRefresh);
        ReSyncExtension extension = extension("core", context -> {
            context.flow().registerHandler("core:handler", new NodeHandler() {
                @Override
                public void execute(FlowContext flowContext, FlowNode node) {
                }

                @Override
                public Set<String> getSupportedOperations() {
                    return Set.of("execute");
                }
            });
            context.flow().registerNode(new NodeDefinition.Builder("core:node", "Core", NodeDefinition.NodeCategory.UTILITY)
                .handler("core:handler")
                .handlerConfig(Map.of("operation", "execute"))
                .description("Provides a stable core lifecycle test capability.")
                .build());
        }, () -> {
        }, () -> {
        });
        ExtensionRegistration registration = fixture.manager.registerBukkitExtension(plugin, extension);
        ContentHash previousCatalogChecksum = fixture.flow.activeCatalogSnapshot().contentChecksum();
        ContentHash previousBindingManifestHash = fixture.flow.activeRuntimeBindingManifest().bindingManifestHash();
        CatalogCacheKey attachedKey = attachCatalogPublicationKey(fixture);
        var previousPublicationKey = fixture.flow.activeCatalogPublicationKey();
        assertEquals(attachedKey.canonicalText(), previousPublicationKey.orElseThrow());
        assertEquals(fixture.flow.activeCatalogSnapshot().generation(), attachedKey.catalogGeneration());
        assertEquals(previousCatalogChecksum, attachedKey.snapshotChecksum());
        RuntimeRegistrySnapshot previousRuntime = fixture.flow.activeCatalogRuntimeActivation().runtime();
        Set<ContractRef<ProviderId>> previousProviders = Set.copyOf(previousRuntime.providers().keySet());
        Set<RuntimeBindingKey> previousBindings = Set.copyOf(previousRuntime.bindings().keySet());
        assertFalse(previousBindings.isEmpty());
        previousBindings.forEach(key -> {
            assertEquals(key, fixture.runtimeBindings.resolve(key).key());
            assertTrue(fixture.runtimeBindings.resolve(key).available());
        });

        fixture.receipts.failRelease.set(true);
        failCoreCommit.set(true);
        failRollbackRefresh.set(true);
        assertFalse(fixture.manager.reloadExtensions());

        assertTrue(fixture.manager.getPluginIds().contains("core"));
        assertTrue(fixture.flow.catalogRuntimeCleanupPending());
        assertTrue(fixture.manager.isShutdownPending());

        fixture.receipts.failRelease.set(false);
        failRollbackRefresh.set(false);
        fixture.manager.tick();

        assertFalse(fixture.flow.catalogRuntimeCleanupPending());
        assertEquals(previousCatalogChecksum, fixture.flow.activeCatalogSnapshot().contentChecksum());
        assertEquals(attachedKey.catalogGeneration(), fixture.flow.activeCatalogSnapshot().generation());
        assertEquals(previousBindingManifestHash, fixture.flow.activeRuntimeBindingManifest().bindingManifestHash());
        assertEquals(previousPublicationKey, fixture.flow.activeCatalogPublicationKey());
        assertEquals(previousProviders,
            Set.copyOf(fixture.flow.activeCatalogRuntimeActivation().runtime().providers().keySet()));
        assertTrue(fixture.flow.isCatalogCoherent());
        previousBindings.forEach(key -> {
            assertEquals(key, fixture.runtimeBindings.resolve(key).key());
            assertTrue(fixture.runtimeBindings.resolve(key).available());
        });
        registration.close();
        assertTrue(fixture.manager.getPluginIds().isEmpty());
        assertFalse(fixture.manager.isShutdownPending());
    }

    private ReSyncExtension extension(String pluginId, Consumer<ReSyncExtensionContext> initializer,
                                      Runnable start, Runnable stop) {
        return new ReSyncExtension() {
            @Override
            public String getPluginId() {
                return pluginId;
            }

            @Override
            public String getVersion() {
                return "1";
            }

            @Override
            public String getDescription() {
                return "Lifecycle test extension";
            }

            @Override
            public void initialize(ReSyncExtensionContext context) {
                initializer.accept(context);
            }

            @Override
            public void start() {
                start.run();
            }

            @Override
            public void stop() {
                stop.run();
            }
        };
    }

    private ReSyncExtension persistenceExtension(String pluginId, PersistenceAction quiesce,
                                                  PersistenceAction resume) {
        return new ReSyncExtension() {
            @Override
            public String getPluginId() {
                return pluginId;
            }

            @Override
            public String getVersion() {
                return "1";
            }

            @Override
            public String getDescription() {
                return "Lifecycle persistence test extension";
            }

            @Override
            public void quiescePersistence() throws IOException {
                quiesce.run();
            }

            @Override
            public void resumePersistence() throws IOException {
                resume.run();
            }
        };
    }

    @FunctionalInterface
    private interface PersistenceAction {
        void run() throws IOException;
    }

    private NodeHandler handler() {
        return new NodeHandler() {
            @Override
            public void execute(FlowContext flowContext, FlowNode node) {
            }

            @Override
            public Set<String> getSupportedOperations() {
                return Set.of("execute");
            }
        };
    }

    private CustomContentProvider provider(String id) {
        return new CustomContentProvider() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public ItemStack createItem(CustomContentDefinition definition, int amount) {
                return null;
            }

            @Override
            public String identifyItem(ItemStack item) {
                return null;
            }

            @Override
            public String identifyBlock(Location location) {
                return null;
            }

            @Override
            public void markPlacedBlock(Location location, CustomContentDefinition definition) {
            }

            @Override
            public void clearPlacedBlock(Location location) {
            }
        };
    }

    private static final class FaultingCustomContentService extends CustomContentService {
        private final AtomicBoolean throwAfterRegister = new AtomicBoolean();
        private final AtomicBoolean throwAfterUnregister = new AtomicBoolean();

        private FaultingCustomContentService(CustomContentStorage storage, FlowStorage flowStorage,
                                              FlowExecutor executor) {
            super(storage, flowStorage, executor);
        }

        @Override
        public void registerProvider(CustomContentProvider provider) {
            super.registerProvider(provider);
            if (throwAfterRegister != null && throwAfterRegister.get() && provider != null && provider.getId() != null
                && !"vanilla".equalsIgnoreCase(provider.getId())) {
                throw new IllegalStateException("provider registration failure");
            }
        }

        @Override
        public void unregisterProvider(String providerId) {
            super.unregisterProvider(providerId);
            if (throwAfterUnregister != null && throwAfterUnregister.get() && providerId != null
                && !"vanilla".equalsIgnoreCase(providerId)) {
                throw new IllegalStateException("provider removal failure");
            }
        }
    }

    private CoreFixture coreFixture(AtomicBoolean failCoreCommit, AtomicBoolean failRollbackRefresh) {
        Path root = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        FlowStorage storage = new FlowStorage(plugin.getDataFolder(), LegacyRuntimeActivationGate.runtime(root),
            assetGates.getFirst(), assetCoordinators.getFirst());
        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry(false);
        HandlerRegistry handlers = new HandlerRegistry();
        handlers.register("builtin:test", new NodeHandler() {
            @Override
            public void execute(FlowContext context, FlowNode node) {
            }

            @Override
            public Set<String> getSupportedOperations() {
                return Set.of("execute");
            }
        });
        definitions.register("builtin", new NodeDefinition.Builder("builtin:test", "Test", NodeDefinition.NodeCategory.UTILITY)
            .owner("builtin")
            .handler("builtin:test")
            .handlerConfig(Map.of("operation", "execute"))
            .description("Provides a stable core lifecycle fixture capability.")
            .build());
        TypeAdapterRegistry adapters = new TypeAdapterRegistry();
        FlowExecutor executor = new FlowExecutor(handlers, definitions, adapters, Map.of());
        TriggerRegistry triggerRegistry = new TriggerRegistry(plugin);
        GlobalTriggers globalTriggers = new GlobalTriggers(storage, executor, triggerRegistry,
            new ReTextService(jsonStorage()), false);
        FlowEventRegistry events = new FlowEventRegistry(globalTriggers.getTriggerDispatcher(), adapters);
        RuntimeDataRegistry runtimeData = new RuntimeDataRegistry();
        OptionCatalogRegistry catalogs = new OptionCatalogRegistry(runtimeData);
        CustomContentStorage contentStorage = customContentStorage();
        CustomContentService contentService = new CustomContentService(contentStorage, storage, executor);
        ReSyncExtensionData extensionData = new ReSyncExtensionData();
        FlowResourceRegistry resources = new FlowResourceRegistry();
        FlowValueCodecRegistry valueCodecs = new FlowValueCodecRegistry();
        FlowRegistry flowRegistry = new FlowRegistry();
        PropertyRegistry properties = new PropertyRegistry();
        FlowGraphValidationRegistry validators = new FlowGraphValidationRegistry();
        FailingReceiptStore receipts = new FailingReceiptStore();
        Path dataRoot = root;
        CatalogPublicationReceiptStore publicationReceipts = new CatalogPublicationReceiptStore(
            dataRoot, dataRoot.resolve(CatalogPublicationReceiptStore.FILE_NAME));
        RuntimeBindingRegistry runtimeBindings = new RuntimeBindingRegistry(Clock.systemUTC(),
            RuntimeSecurityBoundary.denyAll(), RuntimeAuditBoundary.unavailable(),
            RuntimeExecutionBoundary.unavailable(), receipts);
        FlowModule flow = new FlowModule(storage, (Codec) null, 0, triggerRegistry, globalTriggers, flowRegistry,
            definitions, properties, contentStorage, contentService, extensionData, catalogs,
            jsonStorage(), new MessageLogService(), null, null, resources, valueCodecs,
            new FlowJobRegistry(), runtimeBindings, handlers, CatalogActivationAuthority.freshInstall(),
            CORE_SERVER_ID, publicationReceipts);
        flow.completeStartupActivation(new PersistenceRootReadiness(List.of(
            PersistenceRootReadiness.Owner.registered("manager-core-lifecycle", dataRoot, true))));
        ModuleContext context = new ModuleContext(null, null, null, null, null, null, new ModuleRegistry(),
            null, null, null, null, null, null);
        context.registerService(NodeDefinitionRegistry.class, definitions);
        context.registerService(HandlerRegistry.class, handlers);
        context.registerService(PropertyRegistry.class, properties);
        context.registerService(FlowValueCodecRegistry.class, valueCodecs);
        context.registerService(TypeAdapterRegistry.class, adapters);
        context.registerService(FlowGraphValidationRegistry.class, validators);
        context.registerService(FlowResourceRegistry.class, resources);
        context.registerService(ReSyncExtensionData.class, extensionData);
        context.registerService(FlowRegistry.class, flowRegistry);
        context.registerService(FlowEventRegistry.class, events);
        context.registerService(FlowExecutor.class, executor);
        context.registerService(CustomContentService.class, contentService);
        context.registerService(FlowModule.class, flow);
        ReSyncExtensionManager lifecycleManager = new ReSyncExtensionManager(context,
            List.of(extensionRoot.resolve("core")), phase -> {
                if (phase == ReSyncExtensionManager.LifecyclePhase.CORE_COMMIT && failCoreCommit.getAndSet(false)) {
                    throw new IllegalStateException("core commit failure");
                }
                if (phase == ReSyncExtensionManager.LifecyclePhase.ROLLBACK_REFRESH && failRollbackRefresh.getAndSet(false)) {
                    throw new IllegalStateException("rollback refresh failure");
                }
            }, SUPPORTED_RECOVERY_BACKEND);
        return new CoreFixture(lifecycleManager, flow, receipts, runtimeBindings, context);
    }

    private CatalogCacheKey attachCatalogPublicationKey(CoreFixture fixture) {
        var catalog = fixture.flow.activeCatalogSnapshot();
        CatalogCacheKey key = new CatalogCacheKey(CORE_SERVER_ID, catalog.generation(), catalog.contentChecksum(),
            catalog.bindingManifestHash(), CatalogProjectionVersion.current());
        assertTrue(fixture.flow.restoreCatalogPublicationKey(key.canonicalText()));
        return key;
    }

    private ReSyncJsonResourceStorage jsonStorage() {
        if (assetCoordinators.isEmpty() || assetGates.isEmpty()) {
            throw new IllegalStateException("Shared asset persistence is not initialized");
        }
        Path root = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        ReSyncJsonResourceStorage storage = new ReSyncJsonResourceStorage(plugin,
            LegacyRuntimeActivationGate.runtime(root), assetGates.getFirst(), assetCoordinators.getFirst());
        jsonStorages.add(storage);
        return storage;
    }

    private CustomContentStorage customContentStorage() {
        Path root = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        try {
            AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root.resolve("assets"), new Gson());
            AssetPersistenceGate gate = new AssetPersistenceGate(root);
            CustomContentStorage storage = new CustomContentStorage(plugin, root, new ItemAttributeSchemaService(),
                LegacyRuntimeActivationGate.runtime(root), gate, coordinator);
            assetCoordinators.add(coordinator);
            assetGates.add(gate);
            contentStorages.add(storage);
            return storage;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to open custom content test persistence", exception);
        }
    }

    private record CoreFixture(ReSyncExtensionManager manager, FlowModule flow, FailingReceiptStore receipts,
                               RuntimeBindingRegistry runtimeBindings, ModuleContext context) {
    }

    private static final class FailingReceiptStore implements RuntimeReceiptStore {
        private final RuntimeReceiptStore delegate = RuntimeReceiptStore.inMemory(false);
        private final AtomicBoolean failRelease = new AtomicBoolean();

        @Override
        public Claim claim(Key key, ContentHash inputHash) {
            return delegate.claim(key, inputHash);
        }

        @Override
        public boolean durable() {
            return false;
        }

        @Override
        public void releaseProvider(ContractRef<ProviderId> provider) {
            delegate.releaseProvider(provider);
            if (failRelease.get()) {
                throw new IllegalStateException("receipt cleanup failure");
            }
        }
    }
}
