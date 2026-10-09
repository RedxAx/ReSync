package restudio.resync.modules;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.resync.ReSync;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.api.OptionCatalogQuery;
import restudio.resync.customcontent.CustomContentStorage;
import restudio.resync.customcontent.ItemAttributeSchemaService;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.FlowValueCodecRegistry;
import restudio.resync.flow.CoreGraphStorageBoundary;
import restudio.resync.flow.graph.GraphCompiler;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.server.CoreCatalogEvolution;
import restudio.resync.flow.PersistentVariableStore;
import restudio.resync.flow.automation.AutomationDefinitionRegistry;
import restudio.resync.flow.automation.AutomationTaskService;
import restudio.resync.flow.cache.CatalogAuthoringPublication;
import restudio.resync.flow.cache.CatalogCacheProjector;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCachePublicationCodec;
import restudio.resync.flow.catalog.CatalogBindingProof;
import restudio.resync.flow.catalog.CatalogCanonicalizer;
import restudio.resync.flow.catalog.CatalogCompiler;
import restudio.resync.flow.catalog.CatalogContribution;
import restudio.resync.flow.catalog.CatalogProvenance;
import restudio.resync.flow.catalog.CatalogRuntimeActivation;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogSourceIngestor;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.canonical.CanonicalLimits;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.family.JsonFamilyHandler;
import restudio.resync.flow.handler.generic.AbilityEffectHandler;
import restudio.resync.flow.handler.generic.BlockActionHandler;
import restudio.resync.flow.handler.generic.ChatHandler;
import restudio.resync.flow.handler.generic.ColorHandler;
import restudio.resync.flow.handler.generic.ConversionHandler;
import restudio.resync.flow.handler.generic.CustomContentHandler;
import restudio.resync.flow.handler.generic.CustomEventHandler;
import restudio.resync.flow.handler.generic.CustomFunctionCallHandler;
import restudio.resync.flow.handler.generic.DebugHandler;
import restudio.resync.flow.handler.generic.DiscordHandler;
import restudio.resync.flow.handler.generic.EntityActionHandler;
import restudio.resync.flow.handler.generic.FileHandler;
import restudio.resync.flow.handler.generic.FlowControlHandler;
import restudio.resync.flow.handler.generic.FlowJobHandler;
import restudio.resync.flow.handler.generic.FunctionCatalogHandler;
import restudio.resync.flow.handler.generic.FunctionHandler;
import restudio.resync.flow.handler.generic.GenericListHandler;
import restudio.resync.flow.handler.generic.GenericMapHandler;
import restudio.resync.flow.handler.generic.GenericMathHandler;
import restudio.resync.flow.handler.generic.GenericStringHandler;
import restudio.resync.flow.handler.generic.HttpHandler;
import restudio.resync.flow.handler.generic.InventoryActionHandler;
import restudio.resync.flow.handler.generic.JsonHandler;
import restudio.resync.flow.handler.generic.LocationHandler;
import restudio.resync.flow.handler.generic.LogicHandler;
import restudio.resync.flow.handler.generic.ManagedFlowFileCapability;
import restudio.resync.flow.handler.generic.MenuHandler;
import restudio.resync.flow.handler.generic.MiscHandler;
import restudio.resync.flow.handler.generic.NetworkFlowHandler;
import restudio.resync.flow.handler.generic.ParticleHandler;
import restudio.resync.flow.handler.generic.PermissionHandler;
import restudio.resync.flow.handler.generic.PlaceholderHandler;
import restudio.resync.flow.handler.generic.PlayerActionHandler;
import restudio.resync.flow.handler.generic.RandomHandler;
import restudio.resync.flow.handler.generic.ReSyncRuntimeResourceHandler;
import restudio.resync.flow.handler.generic.RegionHandler;
import restudio.resync.flow.handler.generic.ResourceDefinitionHandler;
import restudio.resync.flow.handler.generic.ResourceValueHandler;
import restudio.resync.flow.handler.generic.RestoredNodeHandler;
import restudio.resync.flow.handler.generic.ResultHandler;
import restudio.resync.flow.handler.generic.RuntimeDataHandler;
import restudio.resync.flow.handler.generic.ScheduleHandler;
import restudio.resync.flow.handler.generic.ScoreboardHandler;
import restudio.resync.flow.handler.generic.ServerHandler;
import restudio.resync.flow.handler.generic.SoundHandler;
import restudio.resync.flow.handler.generic.TeamHandler;
import restudio.resync.flow.handler.generic.TextFormatHandler;
import restudio.resync.flow.handler.generic.TextResourceHandler;
import restudio.resync.flow.handler.generic.TimeHandler;
import restudio.resync.flow.handler.generic.TimerHandler;
import restudio.resync.flow.handler.generic.TitleHandler;
import restudio.resync.flow.handler.generic.UuidHandler;
import restudio.resync.flow.handler.generic.VariableHandler;
import restudio.resync.flow.handler.generic.VariableScopeHandler;
import restudio.resync.flow.handler.generic.WorldActionHandler;
import restudio.resync.flow.handler.generic.WorldGenFlowHandler;
import restudio.resync.flow.handler.property.PropertyRegistry;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.jobs.FlowJobRegistry;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionDiagnostic;
import restudio.resync.flow.registry.NodeDefinitionLoader;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.registry.NodeDefinitionValidator;
import restudio.resync.flow.runtime.RuntimeBinding;
import restudio.resync.flow.runtime.RuntimeBindingRegistry;
import restudio.resync.flow.runtime.RuntimeRegistrySnapshot;
import restudio.resync.flow.runtime.RuntimeBindingManifest;
import restudio.resync.flow.runtime.RuntimeOperationDescriptor;
import restudio.resync.flow.runtime.RuntimeProviderDescriptor;
import restudio.resync.flow.runtime.RuntimeSemantics;
import restudio.resync.modules.flow.FlowResourceRegistry;
import restudio.resync.modules.flow.BuiltinOptionCatalogService;
import restudio.resync.modules.flow.LuckPermsOptionCatalogService;
import restudio.resync.runtime.data.RuntimeDataOptionCatalogService;
import restudio.resync.runtime.data.VanillaItemDataAdapter;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.StorageSafety;
import restudio.resync.structure.StructureLibrary;
import restudio.resync.server.OptionCatalogCaptureExecutor;
import restudio.resync.world.WorldManagementService;
import restudio.resync.worldgen.WorldGenGeneratedOutputController;
import restudio.resync.worldgen.WorldGenOperationService;
import restudio.resync.worldgen.WorldGenProjectStorage;
import restudio.resync.worldgen.datapack.WorldGenInstalledDatapackCapability;
import restudio.resync.worldgen.preview.WorldGenPreviewManager;
import restudio.resync.worldgen.registry.WorldGenFlowCatalogContribution;
import restudio.resync.worldgen.registry.WorldGenOptionCatalogs;

import java.lang.reflect.Proxy;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CoreCatalogBaselineProofTest {
    private static final String CACHE_PROPERTY = "resync.catalog.baseline.cache";
    private static final String CACHE_ENVIRONMENT = "RESYNC_CATALOG_BASELINE_CACHE";
    private static final String EXPORT_PROPERTY = "resync.catalog.baseline.export";
    private static final String EXPORT_ENVIRONMENT = "RESYNC_CATALOG_BASELINE_EXPORT";
    private static final String CURRENT_EXPORT_PROPERTY = "resync.catalog.current.export";
    private static final String CURRENT_EXPORT_ENVIRONMENT = "RESYNC_CATALOG_CURRENT_EXPORT";
    private static final String PUBLICATION_PROPERTY = "resync.catalog.projection.publication";
    private static final String PUBLICATION_ENVIRONMENT = "RESYNC_CATALOG_PROJECTION_PUBLICATION";
    private static final String MANIFEST_PROPERTY = "resync.catalog.projection.manifest";
    private static final String MANIFEST_ENVIRONMENT = "RESYNC_CATALOG_PROJECTION_MANIFEST";
    private static final String SNAPSHOT_PROPERTY = "resync.catalog.baseline.snapshot";
    private static final String SNAPSHOT_ENVIRONMENT = "RESYNC_CATALOG_BASELINE_SNAPSHOT";
    private static final String SNAPSHOT_HASH = "7d0b56e501d1723d7aa8893045fb836e69cd8c90eb8998dd5e7370e5c557246e";
    private static final String EVENT_PROPERTY = "resync.catalog.baseline.event";
    private static final String EVENT_ENVIRONMENT = "RESYNC_CATALOG_BASELINE_EVENT";
    private static final String EVENT_HASH = "8296112a0eb3278f3dfa8af9c8154729e9d3fb1a538fec2747dc3f960c461545";
    private static final String STRUCTURE_PROPERTY = "resync.catalog.baseline.structure";
    private static final String STRUCTURE_ENVIRONMENT = "RESYNC_CATALOG_BASELINE_STRUCTURE";
    private static final String STRUCTURE_HASH = "dfe30e8f3150b069f1bbf367a45e1d588015db4caeb9e51fa306e21ed69f321e";
    private static final String SOURCE_JAR_ENVIRONMENT = "RESYNC_CATALOG_SOURCE_JAR";
    private static final String SOURCE_JAR_HASH = "83bab20e80439e1b357508956c2aee968ad0ca945e65b4de420907ddcf62d95a";
    private static final String CACHE_HASH = "e19c6330a27c31cb321fda3ec3c675e85bc5cb408aa0dbd6bc2d4a538493be5c";
    private static final String SERVER = "e65887a4-ea27-4c55-bae2-e1c8d92da433";
    private static final ContentHash CONTENT = new ContentHash("6ffe6a7740232c2bc7d1eeb551488ba913c728fcd1fdf44712528bdc14d2bd84");
    private static final ContentHash TARGET_CONTENT = new ContentHash("4f335d707ce8cd6c60c06e3296d92a46b8ee66feb22df533389b9f1c8ffa556b");
    private static final ContentHash MANIFEST = new ContentHash("eaeee39ce4691471212dc76f5974fe8da99a0d4deb83db37dacd645aa4cfdf7b");
    private static final CatalogVersion CONTRACT = new CatalogVersion(1, 0);
    private static final CatalogBinding BINDING = new CatalogBinding(55L, CONTENT, MANIFEST);
    private static final long MAX_CACHE_BYTES = 128L * 1024L * 1024L;

    @FunctionalInterface
    interface CurrentCatalogAction {
        void accept(CatalogRuntimeActivation.ActivationRecord activation) throws Exception;
    }

    @Test
    void reconstructsHistoricalCatalogContentWithoutClaimingDeployedRuntimeManifest(@TempDir Path temporary) throws Exception {
        String cacheLocation = configured(CACHE_PROPERTY, CACHE_ENVIRONMENT);
        assumeTrue(cacheLocation != null, "Supply an explicit frozen catalog cache path to run the historical content proof");
        Map<ContractRef<NodeId>, String> descriptorHashes = descriptorHashes(Path.of(cacheLocation));
        Set<ContractRef<NodeId>> expected = Set.copyOf(descriptorHashes.keySet());
        List<NodeDefinitionLoader.SourceFile> currentSources = sourceFiles();
        String baselineLocation = configured(SNAPSHOT_PROPERTY, SNAPSHOT_ENVIRONMENT);
        List<NodeDefinitionLoader.SourceFile> priorSources = baselineLocation == null ? currentSources
            : historicalSources(currentSources, Path.of(baselineLocation));

        Path assets = Files.createDirectory(temporary.resolve("assets"));
        try (AssetTransactionCoordinator coordinator = new AssetTransactionCoordinator(assets, new Gson());
             ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(action -> {
                 throw new AssertionError("Baseline proof must never schedule automation work");
             })) {
            PersistentVariableStore variables = new PersistentVariableStore(temporary);
            try (AutoCloseable variableScope = variables::close) {
                AutomationDefinitionRegistry automation = new AutomationDefinitionRegistry(null);
                AutomationTaskService tasks = new AutomationTaskService(unavailable(Plugin.class), automation,
                    Clock.systemUTC(), scheduler, temporary.resolve("automation-tasks.json"));
                try (AutoCloseable taskScope = tasks::shutdown) {
                    WorldGenGeneratedOutputController generated = new WorldGenGeneratedOutputController(temporary, ignored -> {
                        throw new AssertionError("Baseline proof must never rebuild generated output");
                    });
                    FlowJobRegistry jobs = new FlowJobRegistry();
                    try (ProviderFixture providers = providers(temporary, coordinator)) {
                        HandlerRegistry handlers = handlers(temporary, coordinator, generated, jobs, automation, tasks, variables);
                        CatalogSummary historical = reconstruct(priorSources, handlers, expected, descriptorHashes, true,
                            baselineLocation == null, providers.registry());
                        descriptorHashes.clear();
                        CatalogSummary target = baselineLocation == null ? historical
                            : reconstruct(currentSources, handlers, expected, Map.of(), false, true, providers.registry());
                        if (configured("", SOURCE_JAR_ENVIRONMENT) != null) {
                            assertEquals(TARGET_CONTENT, target.content(), "Packaged registered catalog content changed");
                            assertEquals(new ContentHash("8f0915fc00c8e51c98234f096c81166045e8c1e3bd4ef49f269d2cfda9d423c8"),
                                target.manifest(), "Packaged registered runtime manifest changed");
                        }
                        System.out.println("Historical catalog content proven: " + historical.content().canonicalText()
                            + ", targetCatalogContentCandidate=" + target.content().canonicalText()
                            + ", definitions=" + target.definitions()
                            + ", reconstructedRuntimeManifest=" + target.manifest().canonicalText()
                            + ", deployedRuntimeManifestProven=false");
                    } finally {
                        try {
                            generated.close();
                        } finally {
                            jobs.shutdownAsync().join();
                        }
                    }
                }
            }
        }
    }

    @Test
    void currentBundledCatalogPassesStartupPreflight(@TempDir Path temporary) throws Exception {
        withCurrentCatalog(temporary, 1L, true, activation -> {
            CatalogSnapshot snapshot = activation.catalog();
            System.out.println("Current catalog content proven: " + snapshot.contentChecksum().canonicalText()
                + ", runtimeManifest=" + snapshot.bindingManifestHash().canonicalText()
                + ", definitions=" + snapshot.definitions().size());
        });
    }

    static void withCurrentCatalog(Path temporary, long generation, CurrentCatalogAction action) throws Exception {
        withCurrentCatalog(temporary, generation, false, action);
    }

    private static void withCurrentCatalog(Path temporary, long generation, boolean exportCurrent, CurrentCatalogAction action) throws Exception {
        List<NodeDefinitionLoader.SourceFile> currentSources = currentSourceFiles();
        Path assets = Files.createDirectory(temporary.resolve("assets"));
        try (AssetTransactionCoordinator coordinator = new AssetTransactionCoordinator(assets, new Gson());
             ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
                 throw new AssertionError("Startup preflight must never schedule automation work");
             })) {
            PersistentVariableStore variables = new PersistentVariableStore(temporary);
            try (AutoCloseable variableScope = variables::close) {
                AutomationDefinitionRegistry automation = new AutomationDefinitionRegistry(null);
                AutomationTaskService tasks = new AutomationTaskService(unavailable(Plugin.class), automation,
                    Clock.systemUTC(), scheduler, temporary.resolve("automation-tasks.json"));
                try (AutoCloseable taskScope = tasks::shutdown) {
                    WorldGenGeneratedOutputController generated = new WorldGenGeneratedOutputController(temporary, ignored -> {
                        throw new AssertionError("Startup preflight must never rebuild generated output");
                    });
                    FlowJobRegistry jobs = new FlowJobRegistry();
                    try (ProviderFixture providers = providers(temporary, coordinator, false)) {
                        HandlerRegistry handlers = handlers(temporary, coordinator, generated, jobs, automation, tasks, variables);
                        action.accept(currentStartupActivation(currentSources, handlers, providers.registry(), generation, exportCurrent));
                    } finally {
                        try {
                            generated.close();
                        } finally {
                            jobs.shutdownAsync().join();
                        }
                    }
                }
            }
        }
    }

    private static Map<ContractRef<NodeId>, String> descriptorHashes(Path cache) throws Exception {
        CatalogCachePublication publication = observed(cache);
        Map<ContractRef<NodeId>, String> hashes = new LinkedHashMap<>();
        for (CatalogCachePublication.Entry entry : publication.entries()) {
            assertFalse(entry.tombstone(), "The frozen baseline must contain only live descriptors");
            assertFalse(entry.opaque(), "The frozen baseline must contain known descriptors");
            assertTrue(hashes.putIfAbsent(entry.definitionKey(), StorageSafety.sha256(entry.data().canonicalText())) == null,
                "Duplicate frozen descriptor identity");
        }
        assertEquals(1322, hashes.size());
        assertEquals(1243L, hashes.keySet().stream().filter(key -> key.owner().value().equals("restudio.resync")).count());
        assertEquals(79L, hashes.keySet().stream().filter(key -> key.owner().value().equals("worldgen")).count());
        return hashes;
    }

    private static ProviderFixture providers(Path temporary, AssetTransactionCoordinator coordinator) throws Exception {
        return providers(temporary, coordinator, true);
    }

    private static ProviderFixture providers(Path temporary, AssetTransactionCoordinator coordinator, boolean verifyArtifact) throws Exception {
        MockBukkit.mock();
        boolean ready = false;
        try {
            ReSync plugin = MockBukkit.loadSimple(CatalogAdmissionReSync.class);
            AssetPersistenceGate gate = new AssetPersistenceGate(temporary);
            LegacyRuntimeActivationGate legacy = LegacyRuntimeActivationGate.runtime(temporary);
            ItemAttributeSchemaService attributes = new ItemAttributeSchemaService();
            FlowStorage flow = new FlowStorage(temporary.toFile(), legacy, gate, coordinator);
            CustomContentStorage content = new CustomContentStorage(plugin, temporary, attributes, legacy, gate, coordinator);
            ReSyncJsonResourceStorage json = new ReSyncJsonResourceStorage(plugin, legacy, gate, coordinator);
            WorldGenProjectStorage worldGen = new WorldGenProjectStorage(temporary.toFile(), legacy, gate, coordinator);
            StructureLibrary structures = new StructureLibrary(temporary);
            ModuleContext context = new ModuleContext(null, null, null, null, null, null, null, null, null, null, null, null, null);
            context.registerService(StructureLibrary.class, structures);
            FlowRuntimeModule module = new FlowRuntimeModule();
            var contextField = FlowRuntimeModule.class.getDeclaredField("moduleContext");
            contextField.setAccessible(true);
            contextField.set(module, context);
            OptionCatalogRegistry registry = new OptionCatalogRegistry();
            OptionCatalogCaptureExecutor.Bounded captures = OptionCatalogCaptureExecutor.bounded(2, Duration.ofSeconds(5),
                () -> false, Runnable::run, Thread.ofPlatform().daemon().factory());
            registry.bindCapture(captures::capture);
            new BuiltinOptionCatalogService(() -> null, attributes).registerProviders(registry);
            WorldGenOptionCatalogs.register(registry);
            registry.runtimeData().register(new VanillaItemDataAdapter());
            new RuntimeDataOptionCatalogService(registry.runtimeData()).registerProviders(registry);
            new LuckPermsOptionCatalogService().registerProviders(registry);
            var network = FlowRuntimeModule.class.getDeclaredMethod("registerNetworkCatalog", OptionCatalogRegistry.class, ReSync.class);
            network.setAccessible(true);
            network.invoke(module, registry, plugin);
            var core = FlowRuntimeModule.class.getDeclaredMethod("registerCoreResourceCatalogs", OptionCatalogRegistry.class,
                FlowStorage.class, CustomContentStorage.class, WorldGenProjectStorage.class, WorldManagementService.class);
            core.setAccessible(true);
            core.invoke(module, registry, flow, content, worldGen, unavailable(WorldManagementService.class));
            var resources = FlowRuntimeModule.class.getDeclaredMethod("registerResourceCatalogs", OptionCatalogRegistry.class,
                ReSyncJsonResourceStorage.class);
            resources.setAccessible(true);
            resources.invoke(module, registry, json);
            assertNotNull(registry.provider("server:resync:structure"), "Production Structure provider registration is missing");
            assertEquals(structures.list().stream().map(value -> value.id()).toList(), registry.capture("server:resync:structure",
                new OptionCatalogQuery("server:resync:structure", Map.of())).values());
            String artifact = System.getenv("RESYNC_ACCEPTANCE_JAR");
            if (verifyArtifact && artifact != null && !artifact.isBlank()) {
                Path expected = Path.of(artifact).toRealPath();
                for (Class<?> type : List.of(FlowRuntimeModule.class, FlowModule.class, NodeDefinitionLoader.class,
                    NodeDefinitionValidator.class, StructureLibrary.class, BuiltinOptionCatalogService.class)) {
                    assertEquals(expected, Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath(),
                        "Strict admission must use the candidate class: " + type.getName());
                }
            }
            ready = true;
            return new ProviderFixture(registry, content, json, worldGen, structures, captures);
        } finally {
            if (!ready) {
                MockBukkit.unmock();
            }
        }
    }

    private static CatalogRuntimeActivation.ActivationRecord currentStartupActivation(
        List<NodeDefinitionLoader.SourceFile> sourceFiles,
        HandlerRegistry handlers,
        OptionCatalogRegistry options,
        long generation,
        boolean exportCurrent
    ) throws Exception {
        assertEquals(80, sourceFiles.size(), "The current bundled source inventory must be complete");
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        loader.setValidator(new NodeDefinitionValidator(handlers, options, true));
        Map<ContractRef<NodeId>, JsonObject> inventory = currentDefinitionInventory(sourceFiles);
        List<NodeDefinition> loaded = loader.loadReplacementFromSources(sourceFiles);
        assertEquals(inventory.keySet(), loaded.stream().map(CoreCatalogBaselineProofTest::identity).collect(Collectors.toSet()),
            "Every current authored definition must load exactly once");
        assertEquals(inventory.size(), loaded.size(), "Loaded definitions must not contain duplicate identities");
        for (NodeDefinition definition : loaded) {
            JsonObject authored = inventory.get(identity(definition));
            assertEquals(authoredPins(authored, "inputs"), definition.getInputs().stream().map(pin -> pin.getId().value()).toList(),
                "Current input pin coverage changed for " + identity(definition).canonicalText());
            assertEquals(authoredPins(authored, "outputs"), definition.getOutputs().stream().map(pin -> pin.getId().value()).toList(),
                "Current output pin coverage changed for " + identity(definition).canonicalText());
        }
        List<NodeDefinition> selected = loaded.stream()
            .filter(definition -> !"EconomyHandler".equals(definition.getHandler())).toList();
        List<NodeDefinition> excluded = loaded.stream()
            .filter(definition -> "EconomyHandler".equals(definition.getHandler())).toList();
        Set<ContractRef<NodeId>> unavailable = inventory.entrySet().stream()
            .filter(entry -> entry.getValue().has("handler") && "EconomyHandler".equals(entry.getValue().get("handler").getAsString()))
            .map(Map.Entry::getKey).collect(Collectors.toSet());
        assertEquals(unavailable, excluded.stream().map(CoreCatalogBaselineProofTest::identity).collect(Collectors.toSet()),
            "Only the current unavailable economy definitions may be excluded");
        Set<ContractRef<NodeId>> expected = new HashSet<>(inventory.keySet());
        expected.removeAll(unavailable);
        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry(false);
        WorldGenFlowCatalogContribution worldGen = WorldGenFlowCatalogContribution.create();
        for (NodeDefinition definition : worldGen.definitions()) {
            assertTrue(expected.add(identity(definition)), "WorldGen definition collides with an authored current source");
        }
        worldGen.apply(definitions, handlers);
        loader.validateAndRegister(selected, definitions, handlers, "json-classpath");
        assertTrue(loader.getDiagnostics().stream().noneMatch(value -> value.severity() == NodeDefinitionDiagnostic.Severity.ERROR),
            () -> "Strict production definition admission failed: " + loader.getDiagnostics().stream()
                .filter(value -> value.severity() == NodeDefinitionDiagnostic.Severity.ERROR).toList());
        assertEquals(expected, definitions.getAllDefinitions().values().stream()
            .map(CoreCatalogBaselineProofTest::identity).collect(Collectors.toSet()), "Startup must admit the complete current definition inventory");
        List<CatalogSourceIngestor.CatalogSource> sources = sourceFiles.stream().map(source ->
            new CatalogSourceIngestor.CatalogSource(OwnerId.of("restudio.resync"), CatalogProvenance.SourceKind.BUNDLED,
                source.sourceUri(), "1.0.0", "resync-flow", source.bytes())).toList();
        List<CatalogContribution> contributions = FlowModule.buildCatalogContributions(
            definitions, handlers, null, sources, FlowModule.CATALOG_CONTRACT_VERSION, options);
        assertTrue(contributions.stream().anyMatch(contribution -> !contribution.optionSources().isEmpty()),
            "The current startup catalog must retain selector option sources");
        RuntimeRegistrySnapshot runtime = bindings(contributions).snapshot();
        var result = new CatalogCompiler(FlowModule.CATALOG_CONTRACT_VERSION, CatalogBindingProof.snapshot(runtime))
            .compile(contributions, generation);

        assertTrue(result.accepted(), () -> result.diagnostics().stream()
            .map(diagnostic -> diagnostic.code() + " owner=" + diagnostic.ownerId() + " evidence=" + diagnostic.evidence())
            .collect(Collectors.joining(", ")));
        CatalogSnapshot snapshot = result.snapshot().orElseThrow();
        assertEquals(FlowModule.CATALOG_CONTRACT_VERSION, snapshot.contractVersion());
        assertEquals(expected, snapshot.definitions().stream().map(value -> value.key()).collect(Collectors.toSet()),
            "The published catalog must retain every admitted current definition");
        assertEquals(runtime.bindingManifestHash(), snapshot.bindingManifestHash());
        assertEquals(snapshot.contentChecksum(), CatalogCanonicalizer.checksumForCanonicalContent(snapshot.canonicalContent()),
            "Serialized current catalog content must independently reproduce its checksum");
        String exportLocation = exportCurrent ? configured(CURRENT_EXPORT_PROPERTY, CURRENT_EXPORT_ENVIRONMENT) : null;
        if (exportLocation != null) {
            Path destination = Path.of(exportLocation).toAbsolutePath().normalize();
            assertTrue(Files.isDirectory(destination.getParent()), "Explicit current export parent must already exist");
            Files.write(destination, snapshot.canonicalBytes(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
        return new CatalogRuntimeActivation.ActivationRecord(snapshot, runtime);
    }

    private static Map<ContractRef<NodeId>, JsonObject> currentDefinitionInventory(List<NodeDefinitionLoader.SourceFile> sources) {
        Map<ContractRef<NodeId>, JsonObject> inventory = new LinkedHashMap<>();
        for (NodeDefinitionLoader.SourceFile source : sources) {
            JsonElement root = JsonParser.parseString(new String(source.bytes(), StandardCharsets.UTF_8));
            JsonArray nodes = root.isJsonArray() ? root.getAsJsonArray() : new JsonArray();
            if (!root.isJsonArray()) {
                nodes.add(root);
            }
            assertFalse(nodes.isEmpty(), "Current bundled source has no definitions: " + source.sourceUri());
            for (JsonElement element : nodes) {
                JsonObject node = element.getAsJsonObject();
                ContractRef<NodeId> key = ContractRef.of(OwnerId.of(node.get("owner").getAsString()), NodeId.of(node.get("id").getAsString()));
                assertTrue(inventory.putIfAbsent(key, node) == null, "Duplicate current authored identity: " + key.canonicalText());
            }
        }
        return Map.copyOf(inventory);
    }

    private static List<String> authoredPins(JsonObject node, String direction) {
        JsonArray pins = node.getAsJsonArray(direction);
        return pins == null ? List.of() : pins.asList().stream().map(pin -> pin.getAsJsonObject().get("id").getAsString()).toList();
    }

    private static void assertStructureAdmission(NodeDefinitionRegistry definitions, HandlerRegistry handlers,
                                                  OptionCatalogRegistry options, List<CatalogSourceIngestor.CatalogSource> sources) {
        NodeDefinition structure = definitions.get("restudio.resync", "structure_delete");
        assertNotNull(structure);
        NodeDefinitionValidator validator = new NodeDefinitionValidator(handlers, options, true);
        var provider = options.provider("server:resync:structure");
        assertNotNull(provider);
        options.unregister("server:resync:structure");
        try {
            assertTrue(validator.validate(structure).errors().stream().anyMatch(error -> error.contains("Unknown optionsSource")
                && error.contains("server:resync:structure")), "Strict admission must reject the original missing-provider failure");
        } finally {
            assertTrue(options.register(provider));
        }
        assertTrue(validator.validate(structure).valid(), () -> validator.validate(structure).errors().toString());
        CatalogSourceIngestor.CatalogSource source = sources.stream()
            .filter(value -> value.sourceUri().equals("classpath:/nodes/structure.json")).findFirst().orElseThrow();
        JsonArray changed = JsonParser.parseString(new String(source.bytes(), StandardCharsets.UTF_8)).getAsJsonArray();
        JsonObject authored = changed.asList().stream().map(JsonElement::getAsJsonObject)
            .filter(value -> value.get("id").getAsString().equals("structure_delete")).findFirst().orElseThrow();
        JsonObject pin = authored.getAsJsonArray("inputs").asList().stream().map(JsonElement::getAsJsonObject)
            .filter(value -> value.get("id").getAsString().equals("structure_id")).findFirst().orElseThrow();
        pin.addProperty("dataType", "resource_reference<builtin:structure>");
        CatalogSourceIngestor.CatalogSource mismatched = new CatalogSourceIngestor.CatalogSource(OwnerId.of("restudio.resync"),
            CatalogProvenance.SourceKind.BUNDLED, source.sourceUri(), "1.0.0", "resync-flow",
            changed.toString().getBytes(StandardCharsets.UTF_8));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
            FlowModule.buildCatalogContributions(definitions, handlers, null, List.of(mismatched), CONTRACT));
        assertTrue(failure.getMessage().contains("Catalog option source type does not match pin: server-resync-structure"),
            failure::getMessage);
    }

    private record ProviderFixture(OptionCatalogRegistry registry, CustomContentStorage content,
                                   ReSyncJsonResourceStorage json, WorldGenProjectStorage worldGen,
                                   StructureLibrary structures, OptionCatalogCaptureExecutor.Bounded captures) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            json.quiescePersistence();
            try {
                json.closePersistence();
            } finally {
                try {
                    content.close();
                } finally {
                    try {
                        worldGen.closePersistence();
                    } finally {
                        captures.close();
                        structures.close();
                        MockBukkit.unmock();
                    }
                }
            }
        }
    }

    private static CatalogSummary reconstruct(List<NodeDefinitionLoader.SourceFile> sourceFiles, HandlerRegistry handlers,
                                              Set<ContractRef<NodeId>> expected, Map<ContractRef<NodeId>, String> descriptorHashes,
                                              boolean historical, boolean export, OptionCatalogRegistry options) throws Exception {
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        NodeDefinitionValidator validator = new NodeDefinitionValidator(handlers, options, true);
        loader.setValidator(validator);
        List<NodeDefinition> loaded = loader.loadReplacementFromSources(sourceFiles);
        assertEquals(1254, loaded.size(), "The complete source catalog must load");
        List<NodeDefinition> selected = loaded.stream().filter(definition -> expected.contains(identity(definition))).toList();
        List<NodeDefinition> excluded = loaded.stream().filter(definition -> !expected.contains(identity(definition))).toList();
        assertEquals(11, excluded.size());
        assertTrue(excluded.stream().allMatch(definition -> "EconomyHandler".equals(definition.getHandler())),
            "Only the observed unavailable economy family may be excluded");
        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry(false);
        WorldGenFlowCatalogContribution.create().apply(definitions, handlers);
        loader.validateAndRegister(selected, definitions, handlers, "json-classpath");
        assertTrue(loader.getDiagnostics().stream().noneMatch(value -> value.severity() == NodeDefinitionDiagnostic.Severity.ERROR),
            () -> "Strict production definition admission failed: " + loader.getDiagnostics().stream()
                .filter(value -> value.severity() == NodeDefinitionDiagnostic.Severity.ERROR).toList());
        assertEquals(expected, definitions.getAllDefinitions().values().stream()
            .map(CoreCatalogBaselineProofTest::identity).collect(Collectors.toSet()));
        List<String> missingOperations = new ArrayList<>();
        for (NodeDefinition definition : definitions.getAllDefinitions().values()) {
            if (definition.getHandler() != null && !definition.getHandler().isBlank()) {
                assertTrue(handlers.hasHandler(definition.getHandler()), "Missing production handler: " + definition.getHandler());
                Object operation = definition.getHandlerConfig() != null ? definition.getHandlerConfig().get("operation") : null;
                if (operation instanceof String name && !handlers.hasOperation(definition.getHandler(), name.strip())) {
                    missingOperations.add(definition.getId() + ": " + definition.getHandler() + "." + name);
                }
            }
        }
        assertTrue(missingOperations.isEmpty(), "Production operations missing from fixture: " + missingOperations);
        List<CatalogSourceIngestor.CatalogSource> sources = sourceFiles.stream().map(source ->
            new CatalogSourceIngestor.CatalogSource(OwnerId.of("restudio.resync"), CatalogProvenance.SourceKind.BUNDLED,
                source.sourceUri(), "1.0.0", "resync-flow", source.bytes())).toList();
        if (!historical) {
            assertStructureAdmission(definitions, handlers, options, sources);
        }
        List<CatalogContribution> contributions = FlowModule.buildCatalogContributions(definitions, handlers, null, sources, CONTRACT);
        var runtime = bindings(contributions).snapshot();
        var result = new CatalogCompiler(CONTRACT, CatalogBindingProof.snapshot(runtime))
            .compile(contributions, BINDING.generation());
        assertTrue(result.accepted(), result.diagnostics().toString());
        CatalogSnapshot snapshot = result.snapshot().orElseThrow();
        if (!historical) {
            validateSavedGraphs(contributions, runtime);
        }
        assertEquals(expected, snapshot.definitions().stream().map(value -> value.key()).collect(Collectors.toSet()));
        if (historical) {
            for (CatalogContribution contribution : snapshot.contributions()) {
                for (var descriptor : contribution.definitions()) {
                    ContractRef<NodeId> key = ContractRef.of(contribution.ownerId(), descriptor.id());
                    String actual = CatalogCanonicalizer.canonicalNodeContent(descriptor, contribution);
                    assertEquals(descriptorHashes.get(key), StorageSafety.sha256(actual),
                        "Historical descriptor differs: " + key.canonicalText());
                }
            }
            assertEquals(CONTENT, snapshot.contentChecksum(), "Historical full catalog content checksum must match before export");
        }
        assertEquals(snapshot.contentChecksum(), CatalogCanonicalizer.checksumForCanonicalContent(snapshot.canonicalContent()),
            "Serialized full catalog content must independently reproduce its checksum");
        String exportLocation = export ? configured(EXPORT_PROPERTY, EXPORT_ENVIRONMENT) : null;
        if (exportLocation != null) {
            Path destination = Path.of(exportLocation).toAbsolutePath().normalize();
            assertTrue(Files.isDirectory(destination.getParent()), "Explicit export parent must already exist");
            Files.write(destination, snapshot.canonicalBytes(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
        if (export) {
            exportProjection(snapshot, runtime.manifest());
        }
        return new CatalogSummary(snapshot.contentChecksum(), snapshot.bindingManifestHash(), snapshot.definitions().size());
    }

    private static void validateSavedGraphs(List<CatalogContribution> contributions, RuntimeRegistrySnapshot runtime) throws Exception {
        String configured = System.getenv("RESYNC_SAVED_ACCEPTANCE_ROOT");
        if (configured == null || configured.isBlank()) {
            return;
        }
        String artifact = System.getenv("RESYNC_ACCEPTANCE_JAR");
        if (artifact != null && !artifact.isBlank()) {
            assertEquals(Path.of(artifact).toRealPath(), Path.of(CoreCatalogEvolution.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toRealPath(), "Admission must execute the packaged production classes");
        }
        RuntimeBindingManifest manifest = runtime.manifest();
        var compiled = new CatalogCompiler(CONTRACT, CatalogBindingProof.snapshot(runtime)).compile(contributions, 57L);
        assertTrue(compiled.accepted(), compiled.diagnostics().toString());
        CatalogSnapshot catalog = compiled.snapshot().orElseThrow();
        CatalogBinding binding = new CatalogBinding(catalog.generation(), catalog.contentChecksum(), catalog.bindingManifestHash());
        CoreCatalogEvolution.Proof proof = CoreCatalogEvolution.select(binding).orElseThrow().prove(catalog, binding);
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        for (Map.Entry<String, String> entry : Map.of("asdgasd", "command", "ww", "command", "asd", "flow", "blockBreak", "flow").entrySet()) {
            ServerResourceLocator resource = new ServerResourceLocator(ServerId.parseCanonicalText(SERVER),
                ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of(entry.getValue())), entry.getKey());
            var source = boundary.decode(Files.readAllBytes(Path.of(configured).resolve(entry.getKey() + ".json")), resource);
            assertTrue(proof.eligible(source), resource.canonicalText());
            var projected = proof.project(source, UUID.nameUUIDFromBytes(resource.canonicalText().getBytes(StandardCharsets.UTF_8)));
            var graph = projected.graphDocument();
            var plan = new GraphCompiler(manifest).compileResult(graph, catalog);
            assertTrue(plan.compiled(), () -> resource.canonicalText() + ": " + plan.validation().diagnostics());
            JsonObject original = JsonParser.parseString(new String(boundary.encode(source), StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject changed = JsonParser.parseString(new String(boundary.encode(projected), StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(original.get("nodes"), changed.get("nodes"));
            assertEquals(original.get("connections"), changed.get("connections"));
            System.out.println("Saved graph compiled: " + resource.canonicalText() + ", revision=" + graph.revision()
                + ", steps=" + plan.plan().steps().size() + ", binding=" + binding.canonicalText());
        }
    }

    private static void exportProjection(CatalogSnapshot snapshot, RuntimeBindingManifest manifest) throws Exception {
        String publicationLocation = configured(PUBLICATION_PROPERTY, PUBLICATION_ENVIRONMENT);
        String manifestLocation = configured(MANIFEST_PROPERTY, MANIFEST_ENVIRONMENT);
        if (publicationLocation == null && manifestLocation == null) {
            return;
        }
        assertNotNull(publicationLocation, "Projection export requires paired publication and manifest paths");
        assertNotNull(manifestLocation, "Projection export requires paired publication and manifest paths");
        assertEquals(TARGET_CONTENT, snapshot.contentChecksum(), "Projection export requires the verified command schema 3 target");
        assertEquals(snapshot.bindingManifestHash(), manifest.bindingManifestHash());
        Path publicationPath = exportPath(publicationLocation);
        Path manifestPath = exportPath(manifestLocation);
        assertFalse(publicationPath.equals(manifestPath), "Projection export paths must be distinct");
        Set<ContractRef<CapabilityId>> supported = snapshot.capabilities().stream()
            .map(value -> ContractRef.of(value.key().owner(), CapabilityId.of(value.descriptor().id().canonicalText())))
            .collect(Collectors.toUnmodifiableSet());
        CatalogCachePublication publication = CatalogCachePublication.full(CatalogCacheProjector.project(
            ServerId.parseCanonicalText(SERVER), 1, snapshot, supported))
            .withAuthoringPublication(CatalogAuthoringPublication.project(snapshot, supported));
        assertEquals(1322, publication.entries().size());
        byte[] publicationBytes = new CatalogCachePublicationCodec().encodeBytes(publication);
        byte[] manifestBytes = manifest.canonicalForm().getBytes(StandardCharsets.UTF_8);
        assertTrue(publicationBytes.length <= CanonicalLimits.catalog().inputBytes(), "Publication exceeds the catalog codec limit");
        assertTrue(manifestBytes.length <= CanonicalLimits.catalog().inputBytes(), "Manifest exceeds the catalog codec limit");
        Files.write(publicationPath, publicationBytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        Files.write(manifestPath, manifestBytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        System.out.println("Catalog projection publication: " + publicationPath + ", sha256=" + StorageSafety.sha256(publicationBytes));
        System.out.println("Reconstructed runtime manifest: " + manifestPath + ", sha256=" + StorageSafety.sha256(manifestBytes)
            + ", deployedRuntimeManifestProven=false");
    }

    private static Path exportPath(String location) {
        Path path = Path.of(location);
        assertTrue(path.isAbsolute(), "Projection export requires an explicit absolute path");
        path = path.normalize();
        assertTrue(Files.isDirectory(path.getParent()), "Projection export parent must already exist");
        assertFalse(Files.exists(path), "Projection export must not overwrite an existing file: " + path);
        return path;
    }

    public static class CatalogAdmissionReSync extends ReSync {
        private ReSync previous;

        @Override
        public void onEnable() {
            previous = ReSync.getInstance();
            setInstance(this);
        }

        @Override
        public void onDisable() {
            if (ReSync.getInstance() == this) {
                setInstance(previous);
            }
        }

        private void setInstance(ReSync plugin) {
            try {
                Field field = ReSync.class.getDeclaredField("instance");
                field.setAccessible(true);
                field.set(null, plugin);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Catalog Fixture Plugin Identity Could Not Be Set", failure);
            }
        }
    }

    private record CatalogSummary(ContentHash content, ContentHash manifest, int definitions) {
    }

    private static List<NodeDefinitionLoader.SourceFile> historicalSources(List<NodeDefinitionLoader.SourceFile> currentSources,
                                                                          Path baselinePath) throws Exception {
        assertTrue(Files.isRegularFile(baselinePath), "Explicit baseline snapshot must exist");
        assertTrue(Files.size(baselinePath) <= MAX_CACHE_BYTES, "Baseline snapshot exceeds the bounded proof input");
        byte[] bytes = Files.readAllBytes(baselinePath);
        assertEquals(SNAPSHOT_HASH, StorageSafety.sha256(bytes), "Historical snapshot evidence changed");
        String canonical = new String(bytes, StandardCharsets.UTF_8);
        assertEquals(CONTENT, CatalogCanonicalizer.checksumForCanonicalContent(canonical));
        JsonObject baseline = JsonParser.parseString(canonical).getAsJsonObject();
        JsonArray oldEvents = authoredRows(baseline, "classpath:/nodes/event.json");
        JsonArray oldStructures = authoredRows(baseline, "classpath:/nodes/structure.json");
        byte[] originalEventBytes = verifiedSource(EVENT_PROPERTY, EVENT_ENVIRONMENT, EVENT_HASH, oldEvents, "event");
        byte[] originalStructureBytes = verifiedSource(STRUCTURE_PROPERTY, STRUCTURE_ENVIRONMENT, STRUCTURE_HASH, oldStructures, "structure");
        List<NodeDefinitionLoader.SourceFile> oldSources = new ArrayList<>();
        int changed = 0;
        for (NodeDefinitionLoader.SourceFile source : currentSources) {
            if (source.sourceUri().equals("classpath:/nodes/event.json")) {
                JsonArray currentEvents = JsonParser.parseString(new String(source.bytes(), StandardCharsets.UTF_8)).getAsJsonArray();
                assertEquals(oldEvents.size(), currentEvents.size());
                for (int index = 0; index < oldEvents.size(); index++) {
                    JsonObject old = oldEvents.get(index).getAsJsonObject();
                    JsonObject current = currentEvents.get(index).getAsJsonObject();
                    if (old.get("id").getAsString().equals("event.command")) {
                        assertCommandEvolution(old, current);
                        changed++;
                    } else {
                        assertEquals(old, current, "Unrelated event source changed: " + old.get("id"));
                    }
                }
                oldSources.add(new NodeDefinitionLoader.SourceFile(source.sourceName(), source.sourceUri(), source.relativePath(),
                    source.origin(), originalEventBytes));
            } else if (source.sourceUri().equals("classpath:/nodes/structure.json")) {
                oldSources.add(new NodeDefinitionLoader.SourceFile(source.sourceName(), source.sourceUri(), source.relativePath(),
                    source.origin(), originalStructureBytes));
            } else {
                oldSources.add(source);
            }
        }
        assertEquals(1, changed, "Exactly one command descriptor source may evolve");
        return List.copyOf(oldSources);
    }

    private static JsonArray authoredRows(JsonObject baseline, String sourceUri) {
        TreeMap<Integer, JsonObject> rows = new TreeMap<>();
        for (JsonElement element : baseline.getAsJsonArray("definitions")) {
            JsonObject descriptor = element.getAsJsonObject();
            JsonObject metadata = descriptor.getAsJsonObject("metadata");
            if (metadata == null || !metadata.has("authoredSource")) {
                continue;
            }
            JsonObject authored = metadata.getAsJsonObject("authoredSource");
            JsonObject provenance = authored.getAsJsonObject("sourceProvenance");
            if (provenance == null || !sourceUri.equals(provenance.get("sourceUri").getAsString())) {
                continue;
            }
            assertEquals("restudio.resync", provenance.get("owner").getAsString());
            int index = provenance.get("rowIndex").getAsInt();
            authored = authored.deepCopy();
            authored.remove("sourceProvenance");
            assertTrue(rows.putIfAbsent(index, authored) == null, "Duplicate historical source row");
        }
        assertFalse(rows.isEmpty(), "Historical source evidence must exist: " + sourceUri);
        JsonArray values = new JsonArray();
        for (int index = 0; index < rows.size(); index++) {
            assertNotNull(rows.get(index), "Historical source rows must be contiguous");
            values.add(rows.get(index));
        }
        return values;
    }

    private static byte[] verifiedSource(String property, String environment, String expectedHash, JsonArray expected,
                                         String name) throws Exception {
        String location = configured(property, environment);
        assertNotNull(location, "Target proof requires an explicit frozen " + name + " source path");
        Path path = Path.of(location);
        assertTrue(Files.isRegularFile(path), "Explicit frozen " + name + " source must exist");
        assertTrue(Files.size(path) <= MAX_CACHE_BYTES, "Frozen " + name + " source exceeds the bounded proof input");
        byte[] bytes = Files.readAllBytes(path);
        assertEquals(expectedHash, StorageSafety.sha256(bytes), "Frozen " + name + " source bytes changed");
        assertEquals(expected, JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)),
            "Frozen source rows must match the authenticated baseline descriptors");
        return bytes;
    }

    private static void assertCommandEvolution(JsonObject old, JsonObject current) {
        assertEquals(2, old.get("schemaVersion").getAsInt());
        assertEquals(3, current.get("schemaVersion").getAsInt());
        JsonArray priorPins = old.getAsJsonArray("outputs");
        JsonArray nextPins = current.getAsJsonArray("outputs");
        JsonArray priorMappings = old.getAsJsonArray("outputMappings");
        JsonArray nextMappings = current.getAsJsonArray("outputMappings");
        List<String> ids = List.of("event.bound_command", "event.command_label", "event.args", "event.args_list",
            "event.args_count", "event.is_console");
        List<String> types = List.of("string", "string", "string", "list<string>", "number", "boolean");
        assertEquals(4, priorPins.size());
        assertEquals(10, nextPins.size());
        assertEquals(priorMappings.size() + ids.size(), nextMappings.size());
        for (int index = 0; index < priorPins.size(); index++) {
            assertEquals(priorPins.get(index), nextPins.get(index), "Original command pins must remain exact");
        }
        for (int index = 0; index < priorMappings.size(); index++) {
            assertEquals(priorMappings.get(index), nextMappings.get(index), "Original command mappings must remain exact");
        }
        for (int index = 0; index < ids.size(); index++) {
            JsonObject pin = nextPins.get(priorPins.size() + index).getAsJsonObject();
            assertEquals(Set.of("id", "name", "displayName", "description", "direction", "pinType", "dataType"), pin.keySet());
            assertEquals(ids.get(index), pin.get("id").getAsString());
            assertEquals(ids.get(index), pin.get("name").getAsString());
            assertEquals(types.get(index), pin.get("dataType").getAsString());
            assertEquals("output", pin.get("direction").getAsString());
            assertEquals("DATA", pin.get("pinType").getAsString());
            assertFalse(pin.get("displayName").getAsString().isBlank());
            assertFalse(pin.get("description").getAsString().isBlank());
            JsonObject mapping = new JsonObject();
            mapping.addProperty("source", ids.get(index));
            mapping.addProperty("target", ids.get(index));
            assertEquals(mapping, nextMappings.get(priorMappings.size() + index));
        }
        JsonObject priorMigration = old.getAsJsonObject("migrationMapping");
        JsonObject currentMigration = priorMigration.deepCopy();
        currentMigration.addProperty("id", "migration.event.command.v2-v3");
        currentMigration.addProperty("sourceSchemaVersion", 2);
        currentMigration.addProperty("targetSchemaVersion", 3);
        assertEquals(currentMigration, current.get("migrationMapping"));
        JsonObject retained = priorMigration.deepCopy();
        retained.addProperty("id", "migration.event.command");
        JsonArray history = new JsonArray();
        history.add(retained);
        assertEquals(history, current.get("migrationHistory"));
        JsonObject reversed = current.deepCopy();
        reversed.addProperty("schemaVersion", 2);
        reversed.add("outputs", priorPins);
        reversed.add("outputMappings", priorMappings);
        reversed.add("migrationMapping", priorMigration);
        reversed.remove("migrationHistory");
        assertEquals(old, reversed, "Command evolution must not change unrelated authored fields");
    }

    private static CatalogCachePublication observed(Path cache) throws Exception {
        Path path = cache.toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(path), "Explicit evidence cache must be a regular file");
        assertTrue(Files.size(path) <= MAX_CACHE_BYTES, "Evidence cache exceeds the bounded proof input");
        byte[] bytes = Files.readAllBytes(path);
        assertEquals(CACHE_HASH, StorageSafety.sha256(bytes), "Evidence cache bytes differ from the frozen source");
        JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(1, root.get("schemaVersion").getAsInt());
        JsonObject server = root.getAsJsonObject("servers").getAsJsonObject(SERVER);
        assertNotNull(server, "Frozen server catalog evidence is missing");
        String key = SERVER + "|55|" + CONTENT.canonicalText() + "|" + MANIFEST.canonicalText() + "|1.1";
        JsonObject retained = server.getAsJsonObject(key);
        assertNotNull(retained, "Exact historical publication is missing");
        CatalogCachePublication publication = new CatalogCachePublicationCodec()
            .decodeText(retained.getAsJsonObject("publication").toString());
        assertEquals(BINDING, publication.catalogBinding());
        assertEquals(SERVER, publication.serverId().canonicalText());
        assertEquals(CatalogCachePublication.Kind.FULL, publication.kind());
        return publication;
    }

    private static List<NodeDefinitionLoader.SourceFile> sourceFiles() throws Exception {
        String candidate = System.getenv("RESYNC_ACCEPTANCE_JAR");
        String packaged = candidate != null && !candidate.isBlank() ? candidate : configured("", SOURCE_JAR_ENVIRONMENT);
        if (packaged != null) {
            Path jar = Path.of(packaged).toAbsolutePath().normalize();
            assertTrue(Files.isRegularFile(jar), "Explicit catalog source jar must exist");
            String expectedHash = candidate != null && !candidate.isBlank() ? System.getenv("RESYNC_ACCEPTANCE_SHA256") : SOURCE_JAR_HASH;
            assertNotNull(expectedHash, "Candidate artifact requires its verified checksum");
            assertEquals(expectedHash.toLowerCase(), StorageSafety.sha256(Files.readAllBytes(jar)), "Catalog source jar bytes changed");
            List<NodeDefinitionLoader.SourceFile> sources = new ArrayList<>();
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                List<? extends ZipEntry> entries = zip.stream()
                    .filter(entry -> !entry.isDirectory() && entry.getName().startsWith("nodes/")
                        && entry.getName().endsWith(".json") && entry.getName().indexOf('/', "nodes/".length()) < 0)
                    .sorted(Comparator.comparing(ZipEntry::getName)).toList();
                assertEquals(80, entries.size());
                for (var entry : entries) {
                    String name = entry.getName().substring("nodes/".length());
                    try (var input = zip.getInputStream(entry)) {
                        sources.add(new NodeDefinitionLoader.SourceFile(jar + "!/" + entry.getName(),
                            "classpath:/nodes/" + name, name, NodeDefinitionLoader.SourceOrigin.CLASSPATH,
                            input.readAllBytes()));
                    }
                }
            }
            return List.copyOf(sources);
        }
        return currentSourceFiles();
    }

    private static List<NodeDefinitionLoader.SourceFile> currentSourceFiles() throws Exception {
        Path root = Path.of("src/main/resources/nodes").toAbsolutePath().normalize();
        List<Path> paths;
        try (var files = Files.list(root)) {
            paths = files.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().endsWith(".json"))
                .sorted().toList();
        }
        assertEquals(80, paths.size());
        List<NodeDefinitionLoader.SourceFile> sources = new ArrayList<>();
        for (Path path : paths) {
            String name = path.getFileName().toString();
            sources.add(new NodeDefinitionLoader.SourceFile(path.toString(), "classpath:/nodes/" + name, name,
                NodeDefinitionLoader.SourceOrigin.CLASSPATH, Files.readAllBytes(path)));
        }
        return List.copyOf(sources);
    }

    private static RuntimeBindingRegistry bindings(List<CatalogContribution> contributions) {
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry();
        Map<String, RuntimeOperationDescriptor> requirements = new TreeMap<>();
        for (CatalogContribution contribution : contributions) {
            for (RuntimeOperationDescriptor requirement : contribution.runtimeRequirements()) {
                RuntimeOperationDescriptor prior = requirements.putIfAbsent(requirement.key().canonical(), requirement);
                assertTrue(prior == null || prior.equals(requirement), "Conflicting production runtime requirement");
            }
        }
        Map<OwnerId, List<RuntimeOperationDescriptor>> byOwner = new TreeMap<>();
        for (RuntimeOperationDescriptor requirement : requirements.values()) {
            byOwner.computeIfAbsent(requirement.capability().owner(), ignored -> new ArrayList<>()).add(requirement);
        }
        List<RuntimeBindingRegistry.RuntimeProviderContribution> additions = new ArrayList<>();
        for (Map.Entry<OwnerId, List<RuntimeOperationDescriptor>> entry : byOwner.entrySet()) {
            ContractRef<ProviderId> provider = ContractRef.of(entry.getKey(), ProviderId.of("flow"));
            List<RuntimeBinding> bindings = entry.getValue().stream().map(requirement ->
                RuntimeBinding.available(requirement, provider, "1.0.0", ignored -> {
                    throw new AssertionError("Baseline proof must never execute a runtime operation");
                })).toList();
            additions.add(new RuntimeBindingRegistry.RuntimeProviderContribution(
                new RuntimeProviderDescriptor(provider, "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN), bindings));
        }
        try (RuntimeBindingRegistry.RuntimeReplacement replacement = registry.prepareReplacement(additions, Set.of())) {
            CatalogBindingProof staged = CatalogBindingProof.snapshot(replacement.preview());
            for (RuntimeOperationDescriptor requirement : requirements.values()) {
                assertTrue(staged.proves(requirement), "Staged runtime must prove the exact production requirement: " + requirement.key().canonical());
            }
            assertTrue(replacement.commit().committed(), "Startup runtime binding activation must commit");
        }
        return registry;
    }

    private static HandlerRegistry handlers(Path temporary, AssetTransactionCoordinator coordinator,
                                             WorldGenGeneratedOutputController generated, FlowJobRegistry jobs,
                                             AutomationDefinitionRegistry automation, AutomationTaskService tasks,
                                             PersistentVariableStore variables) throws Exception {
        HandlerRegistry handlers = new HandlerRegistry();
        new AbilityEffectHandler(tasks).registerTo(handlers);
        new GenericMathHandler().registerTo(handlers);
        new GenericStringHandler().registerTo(handlers);
        new GenericListHandler().registerTo(handlers);
        new GenericMapHandler().registerTo(handlers);
        new VariableHandler().registerTo(handlers);
        new LogicHandler().registerTo(handlers);
        new ResultHandler().registerTo(handlers);
        new ResourceValueHandler().registerTo(handlers);
        new ConversionHandler().registerTo(handlers);
        new DebugHandler().registerTo(handlers);
        new DiscordHandler().registerTo(handlers);
        new ChatHandler().registerTo(handlers);
        new FlowControlHandler().registerTo(handlers);
        new HttpHandler().registerTo(handlers);
        new JsonHandler().registerTo(handlers);
        new LocationHandler().registerTo(handlers);
        new MenuHandler().registerTo(handlers);
        new ParticleHandler().registerTo(handlers);
        new PermissionHandler().registerTo(handlers);
        new PlaceholderHandler().registerTo(handlers);
        new RandomHandler().registerTo(handlers);
        new RegionHandler().registerTo(handlers);
        new ResourceDefinitionHandler().registerTo(handlers);
        new ScoreboardHandler().registerTo(handlers);
        new SoundHandler().registerTo(handlers);
        new ServerHandler().registerTo(handlers);
        new NetworkFlowHandler().registerTo(handlers);
        new TeamHandler().registerTo(handlers);
        new TextFormatHandler().registerTo(handlers);
        new TitleHandler().registerTo(handlers);
        new TimeHandler().registerTo(handlers);
        new UuidHandler().registerTo(handlers);
        new ColorHandler().registerTo(handlers);
        new CustomEventHandler().registerTo(handlers);
        new CustomContentHandler().registerTo(handlers);
        new CustomFunctionCallHandler().registerTo(handlers);
        new VariableScopeHandler(null, variables).registerTo(handlers);
        new FunctionHandler().registerTo(handlers);
        new PlayerActionHandler().registerTo(handlers);
        new EntityActionHandler().registerTo(handlers);
        new WorldActionHandler().registerTo(handlers);
        new BlockActionHandler().registerTo(handlers);
        new InventoryActionHandler().registerTo(handlers);
        new MiscHandler().registerTo(handlers);
        new RestoredNodeHandler().registerTo(handlers);
        new FileHandler(ManagedFlowFileCapability.unavailable(temporary,
            new IllegalStateException("Baseline proof has no managed file execution"))).registerTo(handlers);
        new FlowJobHandler(jobs).registerTo(handlers);
        new FunctionCatalogHandler(null).registerTo(handlers);
        new RuntimeDataHandler(null).registerTo(handlers);
        new ScheduleHandler(null, Clock.systemUTC(), automation, tasks, new FlowValueCodecRegistry()).registerTo(handlers);
        new TextResourceHandler(null).registerTo(handlers);
        new TimerHandler(automation, tasks).registerTo(handlers);
        new ReSyncRuntimeResourceHandler(new FlowResourceRegistry()).registerTo(handlers);
        JsonFamilyHandler.registerFamilies(handlers, new PropertyRegistry());
        Plugin plugin = unavailable(Plugin.class);
        WorldGenInstalledDatapackCapability installer = unavailable(WorldGenInstalledDatapackCapability.class);
        WorldGenProjectStorage storage = new WorldGenProjectStorage(temporary.toFile(),
            LegacyRuntimeActivationGate.compatibility(temporary), coordinator);
        WorldGenPreviewManager preview = new WorldGenPreviewManager(plugin, false, generated, installer);
        new WorldGenFlowHandler(new WorldGenOperationService(plugin, storage, preview, jobs, generated, installer))
            .registerTo(handlers);
        return handlers;
    }

    private static <T> T unavailable(Class<T> capability) {
        return capability.cast(Proxy.newProxyInstance(capability.getClassLoader(), new Class<?>[] {capability},
            (proxy, method, arguments) -> {
                throw new AssertionError("Baseline proof must never invoke " + capability.getSimpleName() + "." + method.getName());
            }));
    }

    private static ContractRef<NodeId> identity(NodeDefinition definition) {
        return ContractRef.of(OwnerId.of(definition.getOwner()), NodeId.of(definition.getId()));
    }

    private static String configured(String property, String environment) {
        String value = property.isBlank() ? null : System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(environment);
        }
        return value == null || value.isBlank() ? null : value;
    }
}
