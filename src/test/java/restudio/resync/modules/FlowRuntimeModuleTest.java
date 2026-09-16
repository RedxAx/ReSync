package restudio.resync.modules;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowGraph;
import restudio.resync.flow.CustomFunctionNodeDefinitions;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowTypeRef;
import restudio.resync.api.ExtensionRegistryActivation;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.cache.CatalogPublicationReceiptStore;
import restudio.resync.flow.canonical.CanonicalLimits;
import restudio.resync.flow.catalog.CatalogCategoryDescriptor;
import restudio.resync.flow.catalog.CatalogProvenance;
import restudio.resync.flow.catalog.CatalogSourceIngestor;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.runtime.ReplacementRuntimeProviderAuthority;
import restudio.resync.flow.runtime.RuntimeBinding;
import restudio.resync.flow.runtime.RuntimeBindingRegistry;
import restudio.resync.flow.runtime.RuntimeFailureContract;
import restudio.resync.flow.runtime.RuntimeOperationDescriptor;
import restudio.resync.flow.runtime.RuntimeProviderDescriptor;
import restudio.resync.flow.runtime.RuntimeSemantics;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.migration.MigrationFence;
import restudio.resync.migration.ReSyncPersistenceCoordinator;
import restudio.resync.server.AuthorityEpoch;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowRuntimeModuleTest {
    @Test
    void shutdownRetiresThePublishedHandlerSetExactlyOnce() {
        HandlerRegistry handlers = new HandlerRegistry();
        AtomicInteger shutdowns = new AtomicInteger();
        handlers.register("test", new NodeHandler() {
            @Override
            public void execute(FlowContext context, FlowNode node) {
            }

            @Override
            public void shutdown() {
                shutdowns.incrementAndGet();
            }
        });
        ExtensionRegistryActivation activation = new ExtensionRegistryActivation(ExtensionRegistryActivation.capture(
            0, null, handlers, null, null, null, null, null, null, null, null, null, null));
        activation.bind(null, handlers, null, null, null, null, null, null, null, null, null, null);

        FlowRuntimeModule.shutdownHandlers(handlers);

        assertEquals(0, handlers.getHandlerCount());
        assertEquals(1, shutdowns.get());
    }

    @Test
    void stopClosesEventAdmissionBeforeExecutorAndHandlerRetirement() throws IOException {
        String source = Files.readString(Path.of("src/main/java/restudio/resync/modules/FlowRuntimeModule.java"));
        int stopStart = source.indexOf("private void stopFenced(");
        int stopEnd = source.indexOf("static void shutdownHandlers(", stopStart);
        String stop = source.substring(stopStart, stopEnd);
        int triggerClose = stop.indexOf("closeDefinitionAdmission()");
        int eventClose = stop.indexOf("flowEventRegistry.closeActivation()");
        int executorShutdown = stop.indexOf("runtimeExecutor.shutdown()");
        int handlerShutdown = stop.indexOf("shutdownHandlers(registry)");

        assertTrue(triggerClose >= 0);
        assertTrue(eventClose > triggerClose);
        assertTrue(executorShutdown > eventClose);
        assertTrue(handlerShutdown > executorShutdown);
        assertEquals(handlerShutdown, stop.lastIndexOf("shutdownHandlers(registry)"));
    }

    @Test
    void resourceCatalogRevisionChangesWhenEqualCountIdsChange() {
        String first = FlowRuntimeModule.resourceCatalogRevision("npc-definition", List.of("alpha", "bravo"));
        String second = FlowRuntimeModule.resourceCatalogRevision("npc-definition", List.of("alpha", "charlie"));

        assertNotEquals(first, second);
    }

    @Test
    void resourceCatalogRevisionIsOrderStable() {
        String first = FlowRuntimeModule.resourceCatalogRevision("dialog", List.of("bravo", "alpha"));
        String second = FlowRuntimeModule.resourceCatalogRevision("dialog", List.of("alpha", "bravo"));

        assertEquals(first, second);
    }

    @Test
    void runtimeDeclaresItsAuthoritativeServiceDependencies() {
        List<String> dependencies = new FlowRuntimeModule().getMetadata().dependencies();

        assertTrue(dependencies.containsAll(List.of("flowJobs", "playerNpcPackets", "worldGen", "worldManagement")));
    }

    @Test
    void nodeDefinitionReloadGateCoalescesConcurrentRequests() throws Exception {
        FlowRuntimeModule runtime = new FlowRuntimeModule();

        assertTrue(runtime.beginNodeDefinitionReload());
        assertFalse(runtime.beginNodeDefinitionReload());
        runtime.completeNodeDefinitionReload();

        var pending = FlowRuntimeModule.class.getDeclaredField("nodeDefinitionReloadPending");
        pending.setAccessible(true);
        assertTrue(pending.getBoolean(runtime));
        assertTrue(runtime.beginNodeDefinitionReload());
        assertFalse(pending.getBoolean(runtime));
        runtime.completeNodeDefinitionReload();
    }

    @Test
    void flowMutableStoresBindToTheActiveDataRoot() throws IOException {
        String source = Files.readString(Path.of("src/main/java/restudio/resync/modules/FlowRuntimeModule.java"));
        int automation = source.indexOf("automationTasks = createAutomationTaskService(context, automationDefinitions);");
        int trigger = source.indexOf("new TriggerRegistry(dataRoot.resolve(\"triggers.json\").toFile())");

        assertTrue(automation >= 0);
        assertTrue(trigger > automation);
        assertTrue(source.contains("dataRoot.resolve(AutomationTaskPersistenceParticipant.DIRECTORY)"));
        assertTrue(source.contains("AutomationTaskPersistenceParticipant.FILE_NAME"));
        assertTrue(source.contains("public ReSync getPlugin()"));
        assertTrue(source.contains("return context.getPlugin();"));
        assertTrue(source.contains("context.getRequiredService(PlayerNpcRuntime.class), legacyRuntimeGate, dataRoot);"));
        assertFalse(source.contains("new AutomationTaskService(context.getPlugin(), automationDefinitions);"));
        assertFalse(source.contains("new TriggerRegistry(context.getPlugin())"));
    }

    @Test
    void runtimeRetainsExplicitReplacementProviderAuthorityAndDefaultsUnavailable() {
        ReplacementRuntimeProviderAuthority authority = ReplacementRuntimeProviderAuthority.unavailable();
        FlowRuntimeModule runtime = new FlowRuntimeModule(null, null, authority);

        assertSame(authority, runtime.replacementRuntimeProviderAuthority());
        assertFalse(new FlowRuntimeModule().replacementRuntimeProviderAuthority().configured());
    }

    @Test
    void authoredResourceAliasesUseCanonicalBuiltInTypesWithoutRewritingExternalTypes() {
        OwnerId builtin = OwnerId.of("builtin");
        OwnerId extension = OwnerId.of("extension");
        ContractRef<ResourceTypeId> variableAlias = ContractRef.of(extension, ResourceTypeId.of("variable_reference"));
        ContractRef<ResourceTypeId> structure = ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("structure"));
        ContractRef<ResourceTypeId> external = ContractRef.of(OwnerId.of("other"), ResourceTypeId.of("variable_reference"));

        assertEquals(ContractRef.of(builtin, ResourceTypeId.of("variable_definition")),
            FlowModule.canonicalAuthoredResourceType(variableAlias, extension));
        assertEquals(structure, FlowModule.canonicalAuthoredResourceType(structure, OwnerId.of("restudio.resync")));
        assertEquals(external, FlowModule.canonicalAuthoredResourceType(external, extension));
    }

    @Test
    void authoredCategoryProjectionIncludesEveryRegisteredCategory() {
        List<String> expected = NodeDefinition.NodeCategory.values().stream()
            .map(NodeDefinition.NodeCategory::getId)
            .toList();
        List<String> actual = FlowModule.authoredCategories(new NodeDefinitionRegistry(false)).stream()
            .map(CatalogCategoryDescriptor::id)
            .map(value -> value.value())
            .toList();

        assertEquals(expected, actual);
        assertTrue(actual.contains("economy"));
    }

    @Test
    void replacementCatalogActivationIsGateOwnedAndNotApprovedByRuntimeWiring() throws IOException {
        String source = Files.readString(Path.of("src/main/java/restudio/resync/modules/FlowModule.java"));
        String runtimeSource = Files.readString(Path.of("src/main/java/restudio/resync/modules/FlowRuntimeModule.java"));
        int check = source.indexOf("CatalogActivationAuthority.evaluate(");
        int binding = source.indexOf("publishedRuntimeProviders = ensureRuntimeBindings(contributions);");
        int runtimeBarrier = runtimeSource.indexOf("replacementStartupDecision(!stagedRegistry.getAllDefinitions().isEmpty())");
        int runtimeRegistration = runtimeSource.indexOf("nodeDefinitionRegistry.registerAll(\"json-classpath\", stagedRegistry.getDefinitionsForPlugin(\"json-classpath\"));");

        assertTrue(source.contains("CatalogActivationAuthority.forbidden()"));
        assertTrue(check >= 0);
        assertTrue(check >= 0);
        assertTrue(check > binding);
        assertTrue(runtimeBarrier >= 0);
        assertTrue(runtimeRegistration > runtimeBarrier);
        assertTrue(source.contains("catalogActivationAuthority);"));
        assertFalse(source.contains("CatalogActivationAuthority.approvedGate2("));
        assertFalse(runtimeSource.contains("CatalogActivationAuthority.approvedGate2("));
    }

    @Test
    void runtimeCleanupHealthIsRetainedAndRetryDoesNotRepublish() throws IOException {
        String source = Files.readString(Path.of("src/main/java/restudio/resync/modules/FlowModule.java"));
        String runtimeSource = Files.readString(Path.of("src/main/java/restudio/resync/modules/FlowRuntimeModule.java"));
        int retryStart = source.indexOf("public synchronized boolean retryPendingCatalogRuntimeCleanup()");
        int retainStart = source.indexOf("private void retainCatalogRuntimeCleanup(", retryStart);
        String retrySource = retryStart >= 0 && retainStart > retryStart
            ? source.substring(retryStart, retainStart) : "";

        assertTrue(source.contains("pendingCatalogRuntimeCleanup"));
        assertTrue(source.contains("retainCatalogRuntimeCleanup(transaction, activation);"));
        assertTrue(source.contains("retainCatalogRuntimeCleanup(pending.transaction(), result);"));
        assertTrue(source.contains("activeCatalogRuntimeDiagnostics()"));
        assertTrue(retrySource.contains("pending.transaction().commit()"));
        assertFalse(retrySource.contains("catalogPublicationPolicy.publishRefresh()"));
        assertTrue(runtimeSource.contains("diagnostics.put(\"catalogRuntimeCleanup\""));
        assertTrue(runtimeSource.contains("retryPendingCatalogRuntimeCleanup()"));
    }

    @Test
    void authoringRefreshCompletesOnlyAfterDispatchAndRetriesFailures() throws IOException {
        String source = Files.readString(Path.of("src/main/java/restudio/resync/modules/FlowModule.java"));
        int start = source.indexOf("private void publishAuthoringWhenReady()");
        int end = source.indexOf("private static int nextAuthoringRefreshFailureCount", start);
        String refresh = start >= 0 && end > start ? source.substring(start, end) : "";
        int dispatch = refresh.indexOf("admitted = catalogPublicationPolicy.admitRefresh();");
        int completion = refresh.indexOf("authoringRefreshCompleted = true;");

        assertTrue(dispatch >= 0);
        assertTrue(completion > dispatch);
        assertTrue(refresh.contains("authoringRefreshInFlight = true;"));
        assertTrue(refresh.contains("authoringRefreshInFlight = false;"));
        assertTrue(refresh.contains("authoringRefreshRetryAt > now"));
        assertTrue(refresh.contains("authoringRefreshFailures = nextAuthoringRefreshFailureCount(authoringRefreshFailures);"));
        assertTrue(source.contains("AUTHORING_REFRESH_MAX_RETRY_DELAY_MS"));
    }

    @Test
    void runtimeStopUsesBoundedDrainAndCompletesBukkitCleanupBeforeReturning() throws IOException {
        String source = Files.readString(Path.of("src/main/java/restudio/resync/modules/FlowRuntimeModule.java"));
        int stopStart = source.indexOf("public synchronized void prepareStop(ModuleContext context)");
        int stopEnd = source.indexOf("private void stopFenced", stopStart);
        int reloadStart = source.indexOf("public void reloadNodeDefinitions()");
        int reloadEnd = source.indexOf("private void deferReloadNodeDefinitions", reloadStart);
        String stopSource = stopStart >= 0 && stopEnd > stopStart ? source.substring(stopStart, stopEnd) : "";
        String reloadSource = reloadStart >= 0 && reloadEnd > reloadStart ? source.substring(reloadStart, reloadEnd) : "";

        assertTrue(stopSource.contains("awaitDrained(STOP_DRAIN_TIMEOUT)"));
        assertTrue(stopSource.contains("stopFenced(context, runtimeExecutor, admissionFence)"));
        assertTrue(stopSource.contains("CompletableFuture.failedFuture"));
        assertTrue(source.contains("finishStopAsync(ModuleContext context)"));
        assertFalse(stopSource.contains("whenDrained()"));
        assertFalse(stopSource.contains("runTask(moduleContext.getPlugin()"));
        assertTrue(reloadSource.contains("awaitDrained(STOP_DRAIN_TIMEOUT)"));
        assertFalse(reloadSource.contains("admissionFence.awaitDrained();"));
        assertFalse(source.contains("private void deferStop"));
    }

    @Test
    void runtimeTickLeavesShutdownToTheModuleRegistryPrimaryThreadPath() throws IOException {
        String source = Files.readString(Path.of("src/main/java/restudio/resync/modules/FlowRuntimeModule.java"));
        int tickStart = source.indexOf("public void onTick()");
        int tickEnd = source.indexOf("public void cleanup(Session session)", tickStart);
        String tickSource = tickStart >= 0 && tickEnd > tickStart ? source.substring(tickStart, tickEnd) : "";

        assertTrue(tickSource.contains("if (stopPending || stopped)"));
        assertFalse(tickSource.contains("stop(moduleContext)"));
    }

    @Test
    void runtimeReplacementIndexesSourceDescriptorsOncePerDefinition() throws IOException {
        String source = Files.readString(Path.of("src/main/java/restudio/resync/modules/FlowModule.java"));
        int indexStart = source.indexOf("private Map<RuntimeOperationDescriptor, NodeDefinition> runtimeDefinitionIndex(");
        int indexEnd = source.indexOf("private NodeDefinition runtimeDefinition(", indexStart);
        String indexSource = indexStart >= 0 && indexEnd > indexStart ? source.substring(indexStart, indexEnd) : "";
        int prepareStart = source.indexOf("Map<RuntimeOperationDescriptor, NodeDefinition> runtimeDefinitionIndex =");
        int prepareEnd = source.indexOf("RuntimeBindingRegistry.RuntimeReplacement replacement", prepareStart);
        String prepareSource = prepareStart >= 0 && prepareEnd > prepareStart ? source.substring(prepareStart, prepareEnd) : "";

        assertTrue(indexSource.contains("definitionsRegistry.getAllDefinitions().values()"));
        assertTrue(indexSource.contains("index.putIfAbsent(runtimeOperationDescriptor(definition, handlers), definition);"));
        assertTrue(indexSource.contains("catch (RuntimeException ignored)"));
        assertEquals(1, count(indexSource, "runtimeOperationDescriptor(definition, handlers)"));
        assertTrue(prepareSource.contains("runtimeHandler(requirement, definitionsRegistry, handlers, runtimeDefinitionIndex)"));
        assertTrue(source.contains("runtimeDefinitionIndex.get(requirement)"));
        assertTrue(source.contains("Flow runtime binding has no source node definition:"));
    }

    @Test
    void legacyRuntimeInvocationUsesThePreparedDefinitionRegistry() throws IOException {
        String source = Files.readString(Path.of("src/main/java/restudio/resync/modules/FlowModule.java"));

        assertTrue(source.contains("invokeLegacyHandler(handler, definition, requirement, definitionsRegistry, invocation)"));
        assertTrue(source.contains("legacyContext.variables(), definitionsRegistry,"));
    }

    private int count(String source, String value) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(value, index)) >= 0) {
            count++;
            index += value.length();
        }
        return count;
    }

    @Test
    void lifecycleCancelsPendingResourceRefreshesBeforeTaskCancellation() throws IOException {
        String source = Files.readString(Path.of("src/main/java/restudio/resync/modules/FlowRuntimeModule.java"));
        int reloadStart = source.indexOf("public void reloadNodeDefinitions()");
        int reloadEnd = source.indexOf("private void deferReloadNodeDefinitions", reloadStart);
        String reloadSource = reloadStart >= 0 && reloadEnd > reloadStart ? source.substring(reloadStart, reloadEnd) : "";
        int stopStart = source.indexOf("public synchronized void prepareStop(ModuleContext context)");
        int stopEnd = source.indexOf("private void stopFenced", stopStart);
        int stopCancel = source.indexOf("resourceRegistry.cancelPendingLiveRefreshes();", stopStart);
        int stopTasks = source.indexOf("runtimeExecutor.cancelPendingTasks();", stopStart);
        int fencedStart = source.indexOf("private void stopFenced", stopStart);
        int fencedEnd = source.indexOf("private RuntimeException cleanupFailure", fencedStart);
        int fencedCancel = source.indexOf("resourceRegistry.cancelPendingLiveRefreshes();", fencedStart);
        int fencedTask = source.indexOf("task.cancel();", fencedStart);
        int rollbackStart = source.indexOf("private RuntimeException rollbackRuntimeActivation()");
        int rollbackEnd = source.indexOf("private void restoreDeferredStartupCallbacks", rollbackStart);
        int rollbackCancel = source.indexOf("resourceRegistry.cancelPendingLiveRefreshes();", rollbackStart);
        int rollbackTask = source.indexOf("task.cancel();", rollbackStart);

        assertTrue(reloadEnd > reloadStart);
        assertFalse(reloadSource.contains("resourceRegistry.cancelPendingLiveRefreshes();"));
        assertTrue(stopEnd > stopStart);
        assertTrue(stopCancel >= stopStart && stopCancel < stopTasks);
        assertTrue(fencedEnd > fencedStart);
        assertTrue(fencedCancel >= fencedStart && fencedCancel < fencedTask);
        assertTrue(rollbackEnd > rollbackStart);
        assertTrue(rollbackCancel >= rollbackStart && rollbackCancel < rollbackTask);
    }

    @Test
    void publicationIdentityReadsTheActiveCatalogRuntimeRecord() throws IOException {
        String source = Files.readString(Path.of("src/main/java/restudio/resync/modules/FlowModule.java"));
        String handlerSource = Files.readString(Path.of("src/main/java/restudio/resync/modules/flow/FlowCatalogPublicationPacketHandler.java"));
        String transportSource = Files.readString(Path.of("ReSyncCore/src/main/java/restudio/resync/flow/cache/CatalogCachePublicationTransport.java"));

        assertTrue(source.contains("catalogRuntimeActivation.activePublicationKey()"));
        assertFalse(source.contains("catalogPublicationHandler.activePublicationKey()"));
        assertTrue(handlerSource.contains("return transport.activeKey();"));
        assertTrue(handlerSource.contains("CatalogPublicationReceiptStore receiptStore"));
        assertFalse(handlerSource.contains("CatalogPublicationReceiptTracker"));
        assertFalse(handlerSource.contains("CatalogPublicationReceiptOperations"));
        assertTrue(transportSource.contains("activation.publishPublicationKey(draft.activation(), draft.publication().key());"));
    }

    @Test
    void receiptAuthorityMustBeTheRegisteredParticipantBeforeModuleActivation() throws IOException {
        String source = Files.readString(Path.of("src/main/java/restudio/resync/modules/FlowRuntimeModule.java"));
        int check = source.indexOf("requireRegisteredParticipant(publicationReceiptStore);");
        int delegate = source.indexOf("delegate = new FlowModule(");

        assertTrue(check >= 0);
        assertTrue(delegate > check);
    }

    @Test
    void unregisteredReceiptAuthorityFailsBeforeRuntimeModuleActivation() throws IOException {
        Path dataRoot = Files.createTempDirectory("resync-runtime-receipt-authority");
        CatalogPublicationReceiptStore registered = new CatalogPublicationReceiptStore(dataRoot,
            dataRoot.resolve(CatalogPublicationReceiptStore.FILE_NAME));
        CatalogPublicationReceiptStore alternate = new CatalogPublicationReceiptStore(dataRoot,
            dataRoot.resolve(CatalogPublicationReceiptStore.FILE_NAME));
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(dataRoot,
            dataRoot.resolveSibling(dataRoot.getFileName() + "-coordination"), new MigrationFence());
        coordinator.register(registered);

        ModuleContext context = new ModuleContext(null, null, null, null, null, null, null,
            null, null, null, null, null, null);
        context.registerService(AuthorityEpoch.class, AuthorityEpoch.fixed(1L));
        context.registerService(RuntimeBindingRegistry.class, new RuntimeBindingRegistry());
        context.registerService(CatalogPublicationReceiptStore.class, alternate);
        context.registerService(ReSyncPersistenceCoordinator.class, coordinator);

        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new FlowRuntimeModule().initialize(context));
            assertEquals("Persistence Participant Is Not The Registered Authority: "
                + CatalogPublicationReceiptStore.OWNER, failure.getMessage());
        } finally {
            coordinator.close();
        }
    }

    @Test
    void flowWiresPositiveAuthorityEpochAndLegacyCompatibilityPolicy() throws IOException {
        String flowSource = Files.readString(Path.of("src/main/java/restudio/resync/modules/FlowModule.java"));
        String runtimeSource = Files.readString(Path.of("src/main/java/restudio/resync/modules/FlowRuntimeModule.java"));

        assertTrue(runtimeSource.contains("context.getRequiredService(AuthorityEpoch.class)"));
        assertTrue(runtimeSource.contains("authorityEpoch.current() < 1L"));
        assertTrue(flowSource.contains("authorityEpoch, legacyCompatibility);"));
        assertTrue(runtimeSource.contains("authorityEpoch, FlowMutationPayloadReader::legacyCompatible"));
        assertFalse(runtimeSource.contains("AuthorityEpoch.fixed(0L)"));
    }

    @Test
    void runtimeReusesTheValidatedCatalogInventoryForAuthoredSources() throws IOException {
        String runtimeSource = Files.readString(Path.of("src/main/java/restudio/resync/modules/FlowRuntimeModule.java"));

        assertTrue(runtimeSource.contains("loadAuthoredCatalogSources(replacementSource.sourceInventory(), replacementSource.definitions())"));
        assertFalse(runtimeSource.contains("Files.walk(validatedRoot)"));
        assertFalse(runtimeSource.contains("scanAuthoredCatalogResource"));
    }

    @Test
    void reloadCarryForwardExcludesOwnedCatalogsAndRebuildsFunctionsFromStorage() throws IOException {
        String runtimeSource = Files.readString(Path.of("src/main/java/restudio/resync/modules/FlowRuntimeModule.java"));
        int carryStart = runtimeSource.indexOf("for (String pluginId : nodeDefinitionRegistry.getPluginIds())");
        int carryEnd = runtimeSource.indexOf("CustomFunctionNodeDefinitions.rebuild(completeStagedRegistry, storage)", carryStart);
        String carrySource = carryStart >= 0 && carryEnd > carryStart
            ? runtimeSource.substring(carryStart, carryEnd) : "";

        assertTrue(carrySource.contains("isReloadOwnedDefinitionPlugin(pluginId)"));
        assertTrue(carrySource.contains("CustomFunctionNodeDefinitions.PLUGIN_ID.equals(pluginId)"));
        assertTrue(carrySource.contains("rebuiltWorldgenIdentities.contains(identity)"));
        assertFalse(runtimeSource.contains("existingCustomFunctionDefinitions"));
        assertTrue(runtimeSource.contains("CustomFunctionNodeDefinitions.rebuild(completeStagedRegistry, storage)"));
    }

    @Test
    void startupCatalogReuseProofIsExactAndFailsClosedOnMutableInputs() throws IOException {
        CatalogSourceIngestor.CatalogSource original = new CatalogSourceIngestor.CatalogSource(
            OwnerId.of("builtin"), CatalogProvenance.SourceKind.BUNDLED, "classpath:/nodes/test.json", "1.0.0", "resync-flow",
            new byte[]{1, 2, 3});
        CatalogSourceIngestor.CatalogSource identical = new CatalogSourceIngestor.CatalogSource(
            OwnerId.of("builtin"), CatalogProvenance.SourceKind.BUNDLED, "classpath:/nodes/test.json", "1.0.0", "resync-flow",
            new byte[]{1, 2, 3});
        CatalogSourceIngestor.CatalogSource changed = new CatalogSourceIngestor.CatalogSource(
            OwnerId.of("builtin"), CatalogProvenance.SourceKind.BUNDLED, "classpath:/nodes/test.json", "1.0.0", "resync-flow",
            new byte[]{1, 2, 4});
        NodeHandler first = (context, node) -> {
        };
        NodeHandler replacement = (context, node) -> {
        };
        FlowModule.DefinitionRegistryInput originalDefinitions = definitionInput(0xFF102030);
        FlowModule.DefinitionRegistryInput identicalDefinitions = definitionInput(0xFF102030);
        FlowModule.DefinitionRegistryInput changedDefinitions = definitionInput(0xFF102031);
        FlowModule.StartupCatalogReuseProof exact = startupReuseProof(
            List.of(original), List.of(identical), Map.of("test", first), Map.of("test", first),
            originalDefinitions, identicalDefinitions);
        RuntimeBindingRegistry exactRegistry = new RuntimeBindingRegistry();
        var exactRuntime = exactRegistry.snapshot();
        var prepared = FlowModule.prepareStartupNoopRuntime(exact, exactRegistry, exactRuntime);

        assertTrue(FlowModule.sameAuthoredCatalogSources(List.of(original), List.of(identical)));
        assertFalse(FlowModule.sameAuthoredCatalogSources(List.of(original), List.of(changed)));
        assertTrue(FlowModule.sameHandlerRegistrations(Map.of("test", first), Map.of("test", first)));
        assertFalse(FlowModule.sameHandlerRegistrations(Map.of("test", first), Map.of("test", replacement)));
        assertTrue(originalDefinitions.supported());
        assertTrue(identicalDefinitions.supported());
        assertNotEquals(originalDefinitions, changedDefinitions);
        assertTrue(prepared.isPresent());
        assertTrue(prepared.orElseThrow().isNoop());
        FlowModule.StartupNoopCommitResult committed = FlowModule.commitStartupNoopRuntime(
            exactRegistry, prepared.orElseThrow());
        assertEquals(FlowModule.StartupNoopCommitStatus.COMMITTED, committed.status());
        assertFalse(committed.retryable());
        IllegalStateException committedClosed = assertThrows(IllegalStateException.class,
            () -> prepared.orElseThrow().preview());
        assertTrue(committedClosed.getMessage().contains("Closed"));

        FlowModule.StartupCatalogReuseProof mismatch = startupReuseProof(
            List.of(original), List.of(identical), Map.of("test", first), Map.of("test", first),
            originalDefinitions, changedDefinitions);
        RuntimeBindingRegistry mismatchRegistry = new RuntimeBindingRegistry();
        assertTrue(FlowModule.prepareStartupNoopRuntime(mismatch, mismatchRegistry,
            mismatchRegistry.snapshot()).isEmpty());

        RuntimeBindingRegistry staleRegistry = new RuntimeBindingRegistry();
        var staleBaseline = staleRegistry.snapshot();
        var staleReplacement = FlowModule.prepareStartupNoopRuntime(exact, staleRegistry, staleBaseline).orElseThrow();
        changeRuntimeBaseline(staleRegistry);
        FlowModule.StartupNoopCommitResult stale = FlowModule.commitStartupNoopRuntime(staleRegistry, staleReplacement);
        assertEquals(FlowModule.StartupNoopCommitStatus.STALE, stale.status());
        assertTrue(stale.retryable());
        IllegalStateException callerOwned = assertThrows(IllegalStateException.class, staleReplacement::preview);
        assertTrue(callerOwned.getMessage().contains("Active Snapshot Changed"));
        staleReplacement.close();
        IllegalStateException callerClosed = assertThrows(IllegalStateException.class, staleReplacement::preview);
        assertTrue(callerClosed.getMessage().contains("Closed"));

        String flowSource = Files.readString(Path.of("src/main/java/restudio/resync/modules/FlowModule.java"));
        String runtimeSource = Files.readString(Path.of("src/main/java/restudio/resync/modules/FlowRuntimeModule.java"));
        int reusePreparation = flowSource.indexOf("prepareStartupCatalogReuse(stagedDefinitions");
        int contributionBuild = flowSource.indexOf("buildCatalogContributions(stagedDefinitions");
        assertTrue(reusePreparation >= 0);
        assertTrue(contributionBuild > reusePreparation);
        assertTrue(runtimeSource.contains("startupCatalogReuseEligible = staticReuse != null"));
        assertTrue(runtimeSource.contains("CustomFunctionNodeDefinitions.rebuild(nodeDefinitionRegistry, storage)"));
        assertTrue(runtimeSource.contains("authoredCatalogSources, startupCatalogReuseEligible"));
        assertTrue(runtimeSource.contains("HandlerRegistry worldGenHandlers = handlerRegistry.copy()"));
        assertTrue(flowSource.contains("coherentCatalogIdentity.matches(active, runtime, projectionState)"));
    }

    @Test
    void unchangedFunctionDefinitionsReuseStartupButChangedOrDeletedFunctionsInvalidateIt() {
        FlowGraph graph = new FlowGraph();
        graph.setId("calculate_reward");
        graph.setFunction(true);
        NodeDefinitionRegistry initial = new NodeDefinitionRegistry(false);
        initial.register(CustomFunctionNodeDefinitions.PLUGIN_ID, CustomFunctionNodeDefinitions.buildDefinition(graph));
        FlowModule.DefinitionRegistryInput expected = FlowModule.catalogDefinitionInput(initial);
        NodeDefinitionRegistry unchanged = new NodeDefinitionRegistry(false);
        unchanged.register(CustomFunctionNodeDefinitions.PLUGIN_ID, CustomFunctionNodeDefinitions.buildDefinition(graph.copy()));
        RuntimeBindingRegistry runtime = new RuntimeBindingRegistry();
        var originalRuntime = runtime.snapshot();
        var exact = startupReuseProof(List.of(), List.of(), Map.of(), Map.of(), expected,
            FlowModule.catalogDefinitionInput(unchanged));
        var replacement = FlowModule.prepareStartupNoopRuntime(exact, runtime, originalRuntime).orElseThrow();
        assertTrue(replacement.isNoop());
        assertEquals(FlowModule.StartupNoopCommitStatus.COMMITTED,
            FlowModule.commitStartupNoopRuntime(runtime, replacement).status());
        assertSame(originalRuntime, runtime.snapshot());

        graph.setFunctionInputs(List.of(new FlowGraph.FunctionParameter("amount", FlowDataType.NUMBER)));
        NodeDefinitionRegistry changed = new NodeDefinitionRegistry(false);
        changed.register(CustomFunctionNodeDefinitions.PLUGIN_ID, CustomFunctionNodeDefinitions.buildDefinition(graph));
        var mutation = startupReuseProof(List.of(), List.of(), Map.of(), Map.of(), expected,
            FlowModule.catalogDefinitionInput(changed));
        assertTrue(FlowModule.prepareStartupNoopRuntime(mutation, runtime, runtime.snapshot()).isEmpty());
        var deletion = startupReuseProof(List.of(), List.of(), Map.of(), Map.of(), expected,
            FlowModule.catalogDefinitionInput(new NodeDefinitionRegistry(false)));
        assertTrue(FlowModule.prepareStartupNoopRuntime(deletion, runtime, runtime.snapshot()).isEmpty());
    }

    @Test
    void cyclicHandlerConfigurationFallsBackFromStartupReuse() {
        Map<String, Object> cyclic = new LinkedHashMap<>();
        cyclic.put("self", cyclic);
        FlowModule.DefinitionRegistryInput input = assertDoesNotThrow(() -> definitionInput(0xFF102030, cyclic));
        FlowModule.StartupCatalogReuseProof proof = startupReuseProof(
            List.of(), List.of(), Map.of(), Map.of(), input, input);
        RuntimeBindingRegistry runtime = new RuntimeBindingRegistry();

        assertFalse(input.supported());
        assertTrue(FlowModule.prepareStartupNoopRuntime(proof, runtime, runtime.snapshot()).isEmpty());
    }

    @Test
    void reorderedHandlerConfigurationSetFallsBackFromStartupReuse() {
        Map<String, Object> firstConfig = Map.of("values", new LinkedHashSet<>(List.of("alpha", "bravo")));
        Map<String, Object> secondConfig = Map.of("values", new LinkedHashSet<>(List.of("bravo", "alpha")));
        FlowModule.DefinitionRegistryInput first = definitionInput(0xFF102030, firstConfig);
        FlowModule.DefinitionRegistryInput second = definitionInput(0xFF102030, secondConfig);
        FlowModule.StartupCatalogReuseProof proof = startupReuseProof(
            List.of(), List.of(), Map.of(), Map.of(), first, second);
        RuntimeBindingRegistry runtime = new RuntimeBindingRegistry();

        assertTrue(first.supported());
        assertTrue(second.supported());
        assertNotEquals(first, second);
        assertTrue(FlowModule.prepareStartupNoopRuntime(proof, runtime, runtime.snapshot()).isEmpty());
    }

    @Test
    void deeplyNestedHandlerConfigurationFallsBackFromStartupReuse() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> current = root;
        for (int depth = 0; depth <= CanonicalLimits.catalog().depth(); depth++) {
            Map<String, Object> child = new LinkedHashMap<>();
            current.put("child", child);
            current = child;
        }
        FlowModule.DefinitionRegistryInput input = assertDoesNotThrow(() -> definitionInput(0xFF102030, root));
        FlowModule.StartupCatalogReuseProof proof = startupReuseProof(
            List.of(), List.of(), Map.of(), Map.of(), input, input);
        RuntimeBindingRegistry runtime = new RuntimeBindingRegistry();

        assertFalse(input.supported());
        assertTrue(FlowModule.prepareStartupNoopRuntime(proof, runtime, runtime.snapshot()).isEmpty());
    }

    @Test
    void dataTypeParentMutationFallsBackFromStartupReuse() {
        FlowDataType firstParent = new FlowDataType("parent", null, Number.class, null, 0x102030);
        FlowDataType secondParent = new FlowDataType("parent", null, Number.class, null, 0x102031);
        FlowDataType firstType = new FlowDataType("child", firstParent, Integer.class, null, 0x203040);
        FlowDataType secondType = new FlowDataType("child", secondParent, Integer.class, null, 0x203040);
        FlowModule.DefinitionRegistryInput first = definitionInput(0xFF102030, null, firstType);
        FlowModule.DefinitionRegistryInput second = definitionInput(0xFF102030, null, secondType);
        FlowModule.StartupCatalogReuseProof proof = startupReuseProof(
            List.of(), List.of(), Map.of(), Map.of(), first, second);
        RuntimeBindingRegistry runtime = new RuntimeBindingRegistry();

        assertNotEquals(first, second);
        assertTrue(FlowModule.prepareStartupNoopRuntime(proof, runtime, runtime.snapshot()).isEmpty());
    }

    @Test
    void dataTypeClassIdentityFallsBackFromStartupReuse() throws IOException {
        Class<?> originalClass = FlowRuntimeModuleTestClassIdentity.class;
        Class<?> isolatedClass = isolatedCopy(originalClass);
        FlowDataType firstType = new FlowDataType("class-identity", null, originalClass, null, 0x203040);
        FlowDataType secondType = new FlowDataType("class-identity", null, isolatedClass, null, 0x203040);
        FlowModule.DefinitionRegistryInput first = definitionInput(0xFF102030, null, firstType);
        FlowModule.DefinitionRegistryInput second = definitionInput(0xFF102030, null, secondType);
        FlowModule.StartupCatalogReuseProof proof = startupReuseProof(
            List.of(), List.of(), Map.of(), Map.of(), first, second);
        RuntimeBindingRegistry runtime = new RuntimeBindingRegistry();

        assertEquals(originalClass.getName(), isolatedClass.getName());
        assertNotSame(originalClass, isolatedClass);
        assertNotEquals(first, second);
        assertTrue(FlowModule.prepareStartupNoopRuntime(proof, runtime, runtime.snapshot()).isEmpty());
    }

    @Test
    void deeplyNestedTypeReferenceFallsBackFromStartupReuse() {
        FlowTypeRef typeRef = FlowTypeRef.simple("string");
        for (int depth = 0; depth <= CanonicalLimits.catalog().depth(); depth++) {
            typeRef = new FlowTypeRef("list", List.of(typeRef));
        }
        FlowTypeRef deeplyNested = typeRef;
        FlowModule.DefinitionRegistryInput input = assertDoesNotThrow(
            () -> definitionInput(0xFF102030, null, FlowDataType.EXECUTION, deeplyNested));
        FlowModule.StartupCatalogReuseProof proof = startupReuseProof(
            List.of(), List.of(), Map.of(), Map.of(), input, input);
        RuntimeBindingRegistry runtime = new RuntimeBindingRegistry();

        assertFalse(input.supported());
        assertTrue(FlowModule.prepareStartupNoopRuntime(proof, runtime, runtime.snapshot()).isEmpty());
    }

    private FlowModule.StartupCatalogReuseProof startupReuseProof(
        List<CatalogSourceIngestor.CatalogSource> expectedSources,
        List<CatalogSourceIngestor.CatalogSource> actualSources,
        Map<String, NodeHandler> expectedHandlers,
        Map<String, NodeHandler> actualHandlers,
        FlowModule.DefinitionRegistryInput expectedDefinitions,
        FlowModule.DefinitionRegistryInput actualDefinitions
    ) {
        return new FlowModule.StartupCatalogReuseProof(true, false, true, true,
            Set.of(), Set.of(), expectedSources, actualSources, expectedHandlers, actualHandlers,
            expectedDefinitions, actualDefinitions);
    }

    private FlowModule.DefinitionRegistryInput definitionInput(int color) {
        return definitionInput(color, null, FlowDataType.EXECUTION, null);
    }

    private FlowModule.DefinitionRegistryInput definitionInput(int color, Map<String, Object> handlerConfig) {
        return definitionInput(color, handlerConfig, FlowDataType.EXECUTION, null);
    }

    private FlowModule.DefinitionRegistryInput definitionInput(int color, Map<String, Object> handlerConfig,
                                                                FlowDataType dataType) {
        return definitionInput(color, handlerConfig, dataType, null);
    }

    private FlowModule.DefinitionRegistryInput definitionInput(int color, Map<String, Object> handlerConfig,
                                                                FlowDataType dataType, FlowTypeRef typeRef) {
        NodeDefinitionRegistry registry = new NodeDefinitionRegistry(false);
        NodeDefinition.Builder builder = new NodeDefinition.Builder("void-type", "Void Type", NodeDefinition.NodeCategory.FLOW);
        if (typeRef == null) {
            builder.input("flow", NodeDefinition.PinType.FLOW, dataType);
        } else {
            builder.input(new NodeDefinition.PinDefinition("flow", NodeDefinition.PinType.FLOW,
                NodeDefinition.PinDirection.INPUT, dataType, typeRef));
        }
        builder.color(color);
        if (handlerConfig != null) {
            builder.handlerConfig(handlerConfig);
        }
        registry.register("test", builder.build());
        return FlowModule.catalogDefinitionInput(registry);
    }

    private Class<?> isolatedCopy(Class<?> source) throws IOException {
        String resource = "/" + source.getName().replace('.', '/') + ".class";
        try (InputStream input = source.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Class bytes are unavailable: " + source.getName());
            }
            return new IsolatedClassLoader(source.getClassLoader()).define(source.getName(), input.readAllBytes());
        }
    }

    private static final class IsolatedClassLoader extends ClassLoader {
        private IsolatedClassLoader(ClassLoader parent) {
            super(parent);
        }

        private Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private void changeRuntimeBaseline(RuntimeBindingRegistry registry) {
        OwnerId owner = OwnerId.of("test");
        ContractRef<ProviderId> provider = ContractRef.of(owner, ProviderId.of("provider"));
        TypeExpr string = TypeExpr.named(TypeReference.of("builtin", "string"));
        RuntimeSemantics semantics = new RuntimeSemantics(
            RuntimeSemantics.Effect.PURE,
            RuntimeSemantics.ThreadMode.CURRENT,
            ContractRef.of(owner, CapabilityId.of("authorization")),
            RuntimeSemantics.Cancellation.NONE,
            0L,
            0L,
            0L,
            RuntimeSemantics.UnloadPolicy.DRAIN,
            RuntimeSemantics.Retry.NEVER,
            RuntimeSemantics.Idempotency.INTRINSIC,
            RuntimeSemantics.Audit.NONE,
            RuntimeSemantics.Confirmation.NONE,
            RuntimeSemantics.SensitiveData.NONE,
            RuntimeSemantics.Determinism.DETERMINISTIC,
            Set.of(),
            Set.of("failed"),
            Set.of(),
            new RuntimeFailureContract(string, Set.of("RUNTIME.FAILURE"), Set.of("failed"),
                RuntimeFailureContract.CommitBoundary.NO_MUTATION),
            Set.of(),
            Set.of());
        RuntimeOperationDescriptor operation = new RuntimeOperationDescriptor(
            ContractRef.of(owner, CapabilityId.of("capability")),
            ContractRef.of(owner, OperationId.of("operation")),
            List.of(), semantics);
        registry.activate(new RuntimeProviderDescriptor(provider, "1.0.0", 0L, 0L, RuntimeSemantics.UnloadPolicy.DRAIN),
            List.of(RuntimeBinding.unavailable(operation, provider, "1.0.0")));
    }
}

final class FlowRuntimeModuleTestClassIdentity {
}
