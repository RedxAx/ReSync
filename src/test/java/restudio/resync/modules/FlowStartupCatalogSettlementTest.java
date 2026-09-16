package restudio.resync.modules;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.resync.customcontent.CustomContentStorage;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.api.ExtensionRegistryActivation;
import restudio.resync.api.ReSyncExtensionManager;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.flow.CompiledCoreFlowExecutionBridge;
import restudio.resync.flow.CompiledGraphMetadataProvider;
import restudio.resync.flow.CompiledTriggerExecution;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.jobs.FlowJobRegistry;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.FlowValueCodecRegistry;
import restudio.resync.flow.GlobalTriggers;
import restudio.resync.flow.PersistentVariableStore;
import restudio.resync.flow.ServerCompiledPlanRepository;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.automation.AutomationDefinitionRegistry;
import restudio.resync.flow.automation.AutomationTaskService;
import restudio.resync.flow.cache.CatalogPublicationReceiptStore;
import restudio.resync.flow.catalog.CatalogActivationAuthority;
import restudio.resync.flow.catalog.CatalogProvenance;
import restudio.resync.flow.catalog.CatalogSourceIngestor;
import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.event.FlowEventRegistry;
import restudio.resync.flow.handler.property.PropertyRegistry;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionDiagnostic;
import restudio.resync.flow.registry.NodeDefinitionLoader;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.registry.NodeDefinitionValidator;
import restudio.resync.flow.runtime.FlowRuntimeExecutionBoundary;
import restudio.resync.flow.runtime.RuntimeAuditBoundary;
import restudio.resync.flow.runtime.RuntimeAuthority;
import restudio.resync.flow.runtime.RuntimeBindingKey;
import restudio.resync.flow.runtime.RuntimeBindingRegistry;
import restudio.resync.flow.runtime.RuntimeLeaseInput;
import restudio.resync.flow.runtime.RuntimeReceiptStore;
import restudio.resync.flow.runtime.RuntimeSecurityBoundary;
import restudio.resync.flow.runtime.RuntimeSemantics;
import restudio.resync.flow.triggers.TriggerRegistry;
import restudio.resync.flow.triggers.TriggerType;
import restudio.resync.modules.flow.FlowResourceRegistry;
import restudio.resync.migration.ReplacementActivationRecord;
import restudio.resync.server.AuthorityEpoch;
import restudio.resync.server.CoreCatalogEvolution;
import restudio.resync.server.CoreGraphMutationValidator;
import restudio.resync.server.FlowStorageCoreGraphResourceAuthority;
import restudio.resync.server.ProtocolResourceAuthorizer;
import restudio.resync.server.SqliteProtocolResourceMutationAuthority;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.worldgen.registry.WorldGenFlowCatalogContribution;
import restudio.resync.worldgen.WorldGenGeneratedOutputController;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FlowStartupCatalogSettlementTest {
    private static final String ACTIVE_ROOT = "active-roots/bootstrap-7a5a01db-7964-4a6a-93d7-9d8dbf26a7e7";
    private static final ServerId SERVER = ServerId.parseCanonicalText("e65887a4-ea27-4c55-bae2-e1c8d92da433");

    @Test
    void savedCatalogEvolutionPublishesBeforeResidentBindingActivation(@TempDir Path temporary) throws Exception {
        String configured = System.getenv("RESYNC_STARTUP_ACCEPTANCE_ROOT");
        assumeTrue(configured != null && !configured.isBlank(), "An explicit full preboot coordination snapshot is required");
        Path snapshot = Path.of(configured);
        assertTrue(snapshot.isAbsolute());
        assertTrue(Files.isDirectory(snapshot));
        Path copy = temporary.resolve("coordination");
        copyTree(snapshot, copy);
        Path root = copy.resolve(ACTIVE_ROOT);
        assertTrue(Files.isRegularFile(root.resolve("runtime/resource-mutations.db")));
        assertTrue(Files.isRegularFile(root.resolve("assets/.migrations/replacement-activation.record")));
        PersistentVariableStore variables = new PersistentVariableStore(root);
        try (AutoCloseable variableScope = variables::close;
             AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root.resolve("assets"), new Gson());
             AutoCloseable providers = (AutoCloseable) helper("providers", root, coordinator);
             var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            JavaPlugin plugin = (JavaPlugin) Bukkit.getPluginManager().getPlugins()[0];
            OptionCatalogRegistry options = (OptionCatalogRegistry) accessor(providers, "registry");
            CustomContentStorage content = (CustomContentStorage) accessor(providers, "content");
            ReSyncJsonResourceStorage json = (ReSyncJsonResourceStorage) accessor(providers, "json");
            AutomationDefinitionRegistry automation = new AutomationDefinitionRegistry(null);
            AutomationTaskService tasks = new AutomationTaskService(plugin, automation, Clock.systemUTC(), scheduler,
                temporary.resolve("automation-tasks.json"));
            FlowJobRegistry jobs = new FlowJobRegistry();
            WorldGenGeneratedOutputController generated = new WorldGenGeneratedOutputController(root, ignored -> {
                throw new AssertionError("Catalog settlement must not rebuild world generation output");
            });
            try {
                HandlerRegistry handlers = (HandlerRegistry) helper("handlers", root, coordinator, generated, jobs, automation, tasks, variables);
                composeAndSettle(root, plugin, coordinator, options, content, json, handlers, jobs);
            } finally {
                generated.close();
                jobs.shutdownAsync().join();
                tasks.shutdown();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void composeAndSettle(Path root, JavaPlugin plugin, AssetTransactionCoordinator coordinator,
                                         OptionCatalogRegistry options, CustomContentStorage content,
                                         ReSyncJsonResourceStorage json, HandlerRegistry handlers, FlowJobRegistry jobs) throws Exception {
        List<NodeDefinitionLoader.SourceFile> sourceFiles = (List<NodeDefinitionLoader.SourceFile>) helper("sourceFiles");
        String artifact = System.getenv("RESYNC_ACCEPTANCE_JAR");
        if (artifact != null && !artifact.isBlank()) {
            for (Class<?> type : List.of(FlowRuntimeModule.class, FlowModule.class, GlobalTriggers.class,
                CompiledTriggerExecution.class, ServerCompiledPlanRepository.class, SqliteProtocolResourceMutationAuthority.class,
                ExtensionRegistryActivation.class, ReSyncExtensionManager.class, NodeDefinitionRegistry.class)) {
                assertEquals(Path.of(artifact).toRealPath(), Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath(),
                    "Startup acceptance must use the exact candidate class: " + type.getName());
            }
        }
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        loader.setValidator(new NodeDefinitionValidator(handlers, options, true));
        List<NodeDefinition> loaded = loader.loadReplacementFromSources(sourceFiles);
        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry(false);
        WorldGenFlowCatalogContribution.create().apply(definitions, handlers);
        loader.validateAndRegister(loaded.stream().filter(value -> !"EconomyHandler".equals(value.getHandler())).toList(),
            definitions, handlers, "json-classpath");
        assertTrue(loader.getDiagnostics().stream().noneMatch(value -> value.severity() == NodeDefinitionDiagnostic.Severity.ERROR),
            () -> "Strict candidate catalog admission failed: " + loader.getDiagnostics());
        assertEquals(1322, definitions.getAllDefinitions().size());
        List<CatalogSourceIngestor.CatalogSource> sources = sourceFiles.stream().map(source -> new CatalogSourceIngestor.CatalogSource(
            OwnerId.of("restudio.resync"), CatalogProvenance.SourceKind.BUNDLED, source.sourceUri(), "1.0.0", "resync-flow", source.bytes())).toList();
        AssetPersistenceGate gate = new AssetPersistenceGate(root);
        FlowStorage storage = new FlowStorage(root.toFile(), LegacyRuntimeActivationGate.runtime(root), gate, SERVER, coordinator);
        TypeAdapterRegistry adapters = new TypeAdapterRegistry();
        FlowExecutor executor = new FlowExecutor(handlers, adapters, Map.of());
        TriggerRegistry triggerRegistry = new TriggerRegistry(plugin);
        GlobalTriggers triggers = new GlobalTriggers(storage, executor, triggerRegistry, null, false);
        FlowResourceRegistry resources = new FlowResourceRegistry();
        List<RuntimeLeaseInput.AuditEvent> audits = new ArrayList<>();
        RuntimeBindingRegistry runtime = new RuntimeBindingRegistry(new RuntimeSecurityBoundary() {
            @Override
            public boolean authorize(RuntimeAuthority authority, ContractRef<CapabilityId> capability) { return true; }
            @Override
            public boolean confirm(RuntimeAuthority authority, RuntimeBindingKey binding, RuntimeSemantics.Confirmation confirmation) {
                return confirmation == RuntimeSemantics.Confirmation.NONE;
            }
        }, new RuntimeAuditBoundary() {
            @Override
            public boolean available(RuntimeSemantics.Audit audit) { return true; }
            @Override
            public void record(RuntimeLeaseInput.AuditEvent event) { audits.add(event); }
        }, new FlowRuntimeExecutionBoundary(task -> Bukkit.getScheduler().runTask(plugin, task),
            task -> { throw new AssertionError("Saved acceptance graphs must stay on the main thread"); }, Bukkit::isPrimaryThread),
            RuntimeReceiptStore.inMemory(true));
        FlowValueCodecRegistry codecs = new FlowValueCodecRegistry();
        CatalogActivationAuthority migrationAuthority = CatalogActivationAuthority.fromCommittedMigration(root);
        CatalogActivationAuthority activationAuthority = !migrationAuthority.approved() && ReplacementActivationRecord.exists(root)
            ? CatalogActivationAuthority.freshInstall() : migrationAuthority;
        assertTrue(activationAuthority.approved(), "Saved root must carry the production startup authority");
        CatalogPublicationReceiptStore receipts = new CatalogPublicationReceiptStore(root, root.resolve(CatalogPublicationReceiptStore.FILE_NAME));
        try (AutoCloseable receiptScope = receipts::close) {
            FlowModule module = new FlowModule(storage, null, 1, triggerRegistry, triggers, null, definitions, new PropertyRegistry(),
                content, null, null, options, json, null, null, null, resources, codecs, jobs, runtime, handlers,
                activationAuthority, SERVER, receipts, sources);
            module.setExecutor(executor);
            module.setConversionAdapterRegistry(adapters);
            new FlowEventRegistry(triggers.getTriggerDispatcher(), adapters).registerFromJson(new ArrayList<>(definitions.getAllDefinitions().values()));
            var activation = module.activeCatalogRuntimeActivation();
            CatalogBinding target = new CatalogBinding(activation.catalog().generation(), activation.catalog().contentChecksum(),
                activation.runtime().bindingManifestHash());
            assertEquals(57L, target.generation());
            assertTrue(CoreCatalogEvolution.select(target).isPresent(), "Candidate must use registered catalog evolution");
            CoreGraphMutationValidator validator = new CoreGraphMutationValidator(SERVER, module::activeCatalogRuntimeActivation,
                () -> activationAuthority);
            FlowStorageCoreGraphResourceAuthority authority = new FlowStorageCoreGraphResourceAuthority(storage, SERVER, validator);
            resources.bindCoreGraphResourceAuthority(authority);
            List<Diagnostic> diagnostics = new ArrayList<>();
            try (ServerCompiledPlanRepository plans = new ServerCompiledPlanRepository(authority, module::activeCatalogRuntimeActivation)) {
                CompiledGraphMetadataProvider metadata = new CompiledGraphMetadataProvider(module::activeCatalogRuntimeActivation, SERVER, codecs);
                CompiledCoreFlowExecutionBridge bridge = new CompiledCoreFlowExecutionBridge(module::activeCatalogRuntimeActivation,
                    runtime, RuntimeAuthority.anonymous(), null, plans);
                plans.bindTemplateCompiler(bridge::prepare);
                plans.bindMetadataCompiler(metadata::provide);
                executor.configureExecutionBridge(bridge);
                CompiledTriggerExecution execution = new CompiledTriggerExecution(executor, metadata, bridge, (id, values) -> {
                    diagnostics.addAll(values);
                    return id;
                }, null, plans);
                execution.bindCoreStorage(storage, SERVER);
                module.setCompiledTriggerExecution(execution);
                triggers.setCompiledExecution(execution);
                FlowRuntimeModule owner = new FlowRuntimeModule();
                field(owner, "compiledPlanRepository", plans);
                field(owner, "coreGraphResourceAuthority", authority);
                field(owner, "storage", storage);
                field(owner, "globalTriggers", triggers);
                field(owner, "delegate", module);
                field(owner, "serverId", SERVER);
                storage.setGraphChangeListener(change -> invoke(owner, "handleCoreGraphChange", change));
                List<Long> publicationResidents = new ArrayList<>();
                resources.addCoreMutationListener(transition -> publicationResidents.add((long) plans.cachedPlanCount()));
                try (SqliteProtocolResourceMutationAuthority mutations = new SqliteProtocolResourceMutationAuthority(resources, SERVER,
                    root.resolve("runtime/resource-mutations.db"), authority, ProtocolResourceAuthorizer.serverGranted(),
                    AuthorityEpoch.fixed(1L), resources, null, SqliteProtocolResourceMutationAuthority.CatalogStartup.DEFERRED)) {
                    assertFalse(mutations.authoritativeCoreReads());
                    FlowRuntimeModule.settleCatalog(activation, module::activeCatalogRuntimeActivation,
                        expected -> mutations.settleCatalogBinding(target));
                    assertTrue(mutations.authoritativeCoreReads());
                    assertTrue(mutations.durable());
                    assertEquals(4, publicationResidents.size());
                    assertTrue(publicationResidents.stream().allMatch(count -> count == 0),
                        "No runtime admission may happen while installed resources are partly evolved");
                    plans.initialize();
                    assertEquals(4, plans.cachedPlanCount());
                    field(owner, "startupActivationComplete", true);
                    module.refreshRuntimeGraphBindings();
                    triggers.activateRuntimeBindings();
                    assertEquals(2, triggerRegistry.getBindings(TriggerType.EVENT).size());
                    for (String command : List.of("asdgasd", "ww")) {
                        assertNotNull(MockBukkit.getMock().getCommandMap().getCommand(command), command);
                    }
                    Command command = MockBukkit.getMock().getCommandMap().getCommand("asdgasd");
                    var recipient = MockBukkit.getMock().addPlayer();
                    assertTrue(command.execute(Bukkit.getConsoleSender(), "asdgasd", new String[0]));
                    assertEquals("Before", recipient.nextMessage());
                    long deadline = System.nanoTime() + 10_000_000_000L;
                    while (bridge.activeInvocationCount() != 0 && System.nanoTime() < deadline) {
                        Thread.sleep(10);
                        MockBukkit.getMock().getScheduler().performOneTick();
                    }
                    assertEquals("Hi", recipient.nextMessage());
                    assertEquals("Hi", recipient.nextMessage());
                    assertTrue(diagnostics.isEmpty(), diagnostics::toString);
                    assertEquals(0, plans.activeLeaseCount());
                    assertEquals(0, bridge.activeInvocationCount());
                } finally {
                    module.deactivateCoreMutationSubscribers();
                }
            }
        } finally {
            triggers.shutdownRuntimeCommands();
            triggers.getTriggerDispatcher().shutdown();
            executor.shutdown();
        }
    }

    private static Object helper(String name, Object... arguments) throws Exception {
        return call(CoreCatalogBaselineProofTest.class, null, name, arguments);
    }

    private static Object accessor(Object value, String name) throws Exception {
        return call(value.getClass(), value, name);
    }

    private static void invoke(Object target, String name, Object argument) {
        try {
            call(target.getClass(), target, name, argument);
        } catch (Exception failure) {
            throw new IllegalStateException("Production startup callback failed", failure);
        }
    }

    private static Object call(Class<?> owner, Object target, String name, Object... arguments) throws Exception {
        Method method = List.of(owner.getDeclaredMethods()).stream().filter(value -> value.getName().equals(name)
            && value.getParameterCount() == arguments.length).findFirst().orElseThrow();
        method.setAccessible(true);
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            if (failure.getCause() instanceof Error error) throw error;
            throw failure;
        }
    }

    private static void field(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void copyTree(Path source, Path target) throws Exception {
        try (var paths = Files.walk(source)) {
            List<Path> entries = paths.toList();
            for (Path entry : entries) {
                Path destination = target.resolve(source.relativize(entry));
                if (Files.isDirectory(entry)) Files.createDirectories(destination);
                else Files.copy(entry, destination, StandardCopyOption.COPY_ATTRIBUTES);
            }
            for (Path entry : entries.stream().filter(Files::isDirectory).sorted(Comparator.reverseOrder()).toList()) {
                Files.setLastModifiedTime(target.resolve(source.relativize(entry)), Files.getLastModifiedTime(entry));
            }
        }
    }
}
