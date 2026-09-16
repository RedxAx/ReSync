package restudio.resync.worldgen.preview;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import restudio.resync.worldgen.data.WorldGenGraph;
import restudio.resync.worldgen.data.WorldGenProject;
import restudio.resync.worldgen.contract.WorldGenGenerationMode;
import restudio.resync.worldgen.WorldGenGeneratedOutputController;
import restudio.resync.worldgen.datapack.WorldGenDatapackBuild;
import restudio.resync.worldgen.datapack.WorldGenBuildRecipe;
import restudio.resync.worldgen.datapack.WorldGenDatapackCompiler;
import restudio.resync.worldgen.datapack.WorldGenDatapackInstaller;
import restudio.resync.worldgen.datapack.WorldGenInstalledDatapackCapability;
import restudio.resync.worldgen.generator.NodeGraphBiomeProvider;
import restudio.resync.worldgen.generator.NodeGraphChunkGenerator;
import restudio.resync.worldgen.pipeline.PipelineCompiler;
import restudio.resync.worldgen.pipeline.TerrainPipeline;
import restudio.resync.worldgen.pipeline.TerrainPipelineHolder;
import restudio.resync.worldgen.runtime.WorldGenRuntimeRegistry;
import restudio.resync.network.paper.PaperPlayerDataMutationAdmission;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class WorldGenPreviewManager {
    private final Plugin plugin;
    private final Map<PreviewKey, PreviewWorld> activePreviews = new ConcurrentHashMap<>();
    private final Map<PreviewKey, PreviewRequest> activeRequests = new ConcurrentHashMap<>();
    private final Map<String, OwnedPreview> ownedPreviews = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Void>> previewCleanups = new ConcurrentHashMap<>();
    private final AtomicLong previewRevision = new AtomicLong();
    private final WorldGenDatapackCompiler datapackCompiler;
    private final WorldGenInstalledDatapackCapability datapackInstaller;
    private final boolean legacyCompatibility;
    private final WorldGenGeneratedOutputController generatedOutput;
    private final PaperPlayerDataMutationAdmission playerDataAdmission = PaperPlayerDataMutationAdmission.shared();

    public WorldGenPreviewManager(Plugin plugin, boolean legacyCompatibility,
                                  WorldGenGeneratedOutputController generatedOutput,
                                  WorldGenInstalledDatapackCapability datapackInstaller) {
        this.plugin = plugin;
        this.legacyCompatibility = legacyCompatibility;
        this.datapackCompiler = new WorldGenDatapackCompiler(plugin, legacyCompatibility);
        this.datapackInstaller = Objects.requireNonNull(datapackInstaller, "datapackInstaller");
        this.generatedOutput = Objects.requireNonNull(generatedOutput, "generatedOutput");
    }

    public void createPreview(String previewId, String playerUuid, WorldGenGraph graph, World.Environment environment, long seed, Consumer<PreviewWorld> onSuccess, Consumer<Throwable> onError) {
        createPreview(previewId, playerUuid, graph, environment, seed, onSuccess, onError, null, null);
    }

    public void createPreview(String previewId, String playerUuid, WorldGenGraph graph, World.Environment environment, long seed,
                              Consumer<PreviewWorld> onSuccess, Consumer<Throwable> onError,
                              Runnable onPhysicalComplete, Consumer<Throwable> onPhysicalError) {
        createPreview(previewId, playerUuid, graph, environment, seed, onSuccess, onError,
            onPhysicalComplete, onPhysicalError, null);
    }

    public void createPreview(String previewId, String playerUuid, WorldGenGraph graph, World.Environment environment, long seed,
                              Consumer<PreviewWorld> onSuccess, Consumer<Throwable> onError,
                              Runnable onPhysicalComplete, Consumer<Throwable> onPhysicalError,
                              Consumer<PreviewTermination> onLogicalTermination) {
        try {
            TerrainPipeline pipeline = PipelineCompiler.compile(graph, legacyCompatibility);
            createPreview(previewId, playerUuid, pipeline, environment, seed, onSuccess, onError,
                onPhysicalComplete, onPhysicalError, onLogicalTermination);
        } catch (Throwable failure) {
            notifyError(onError, failure);
            notifyPhysicalFailure(onPhysicalError, failure);
        }
    }

    public void createPreview(String previewId, String playerUuid, WorldGenProject project, World.Environment environment, long seed, Consumer<PreviewWorld> onSuccess, Consumer<Throwable> onError) {
        createPreview(previewId, playerUuid, project, environment, seed, onSuccess, onError, null, null);
    }

    public void createPreview(String previewId, String playerUuid, WorldGenProject project, World.Environment environment, long seed,
                              Consumer<PreviewWorld> onSuccess, Consumer<Throwable> onError,
                              Runnable onPhysicalComplete, Consumer<Throwable> onPhysicalError) {
        createPreview(previewId, playerUuid, project, environment, seed, onSuccess, onError,
            onPhysicalComplete, onPhysicalError, null);
    }

    public void createPreview(String previewId, String playerUuid, WorldGenProject project, World.Environment environment, long seed,
                              Consumer<PreviewWorld> onSuccess, Consumer<Throwable> onError,
                              Runnable onPhysicalComplete, Consumer<Throwable> onPhysicalError,
                              Consumer<PreviewTermination> onLogicalTermination) {
        try {
            WorldGenGenerationMode generationMode = WorldGenGenerationMode.resolve(project.getSettings().getGenerationMode());
            TerrainPipeline pipeline = generationMode == WorldGenGenerationMode.HYBRID ? PipelineCompiler.compileProject(project, legacyCompatibility) : null;
            createPreview(previewId, playerUuid, project, pipeline, environment, seed, onSuccess, onError,
                onPhysicalComplete, onPhysicalError, onLogicalTermination);
        } catch (Throwable failure) {
            notifyError(onError, failure);
            notifyPhysicalFailure(onPhysicalError, failure);
        }
    }

    private void createPreview(String previewId, String playerUuid, TerrainPipeline pipeline, World.Environment environment, long seed, Consumer<PreviewWorld> onSuccess, Consumer<Throwable> onError) {
        createPreview(previewId, playerUuid, pipeline, environment, seed, onSuccess, onError, null, null, null);
    }

    private void createPreview(String previewId, String playerUuid, TerrainPipeline pipeline, World.Environment environment, long seed,
                               Consumer<PreviewWorld> onSuccess, Consumer<Throwable> onError,
                               Runnable onPhysicalComplete, Consumer<Throwable> onPhysicalError,
                               Consumer<PreviewTermination> onLogicalTermination) {
        createPreview(previewId, playerUuid, null, pipeline, environment, seed, onSuccess, onError,
            onPhysicalComplete, onPhysicalError, onLogicalTermination);
    }

    private void createPreview(String previewId, String playerUuid, WorldGenProject project, TerrainPipeline pipeline,
                               World.Environment environment, long seed, Consumer<PreviewWorld> onSuccess, Consumer<Throwable> onError,
                               Runnable onPhysicalComplete, Consumer<Throwable> onPhysicalError,
                               Consumer<PreviewTermination> onLogicalTermination) {
        PreviewKey previewKey = requirePreviewKey(previewId);
        long revision = previewRevision.incrementAndGet();
        PreviewWorld previous = activePreviews.get(previewKey);
        String worldName = physicalWorldName(previewKey, revision);
        PreviewRequest request = new PreviewRequest(previewKey, revision, worldName,
            onPhysicalComplete, onPhysicalError, onLogicalTermination);
        PreviewRequest replacedRequest = activeRequests.put(previewKey, request);
        if (replacedRequest != null) {
            replacedRequest.cancel(PreviewTermination.REPLACED);
        }
        try {
            CompiledDatapack compiled = project == null ? null : compileGenerated(project, request);
            WorldGenDatapackBuild datapackBuild = compiled == null ? null : compiled.build();
            WorldGenGeneratedOutputController.Handoff handoff = compiled == null ? null : compiled.handoff();
            request.throwIfCancellationRequested();
            BukkitTask task = Bukkit.getScheduler().runTask(plugin, () -> {
                if (!request.markStarted()) {
                    return;
                }
                PreviewWorld previewWorld = null;
                PreviewWorld replaced = null;
                boolean activated = false;
                CompletableFuture<Void> replacedCleanup = CompletableFuture.completedFuture(null);
                try {
                    request.throwIfCancellationRequested();
                    Map<String, Location> previousLocations = capturePreviewPlayerLocations(previous);
                    previewWorld = createWorldSync(previewKey, playerUuid, pipeline, datapackBuild, worldName, environment, seed,
                        previousLocations, handoff);
                    if (request.isCancellationRequested() || activeRequests.get(previewKey) != request) {
                        finishCancelled(request, deletePreviewWorld(previewWorld.worldName()));
                        return;
                    }
                    replaced = activePreviews.put(previewKey, previewWorld);
                    activated = true;
                    if (replaced != null && !replaced.worldName().equals(previewWorld.worldName())) {
                        replacedCleanup = deletePreviewWorld(replaced.worldName());
                    }
                    if (request.isCancellationRequested() || activeRequests.get(previewKey) != request) {
                        CompletableFuture<Void> cleanup = combine(deleteActivePreview(previewKey, previewWorld), replacedCleanup);
                        finishCancelled(request, cleanup);
                        return;
                    }
                    if (onSuccess != null) {
                        onSuccess.accept(previewWorld);
                    }
                    finishPhysical(request, replacedCleanup, null);
                } catch (Throwable throwable) {
                    boolean cancelled = request.isCancellationRequested() || throwable instanceof CancellationException;
                    if (cancelled) {
                        request.markCancellation(PreviewTermination.CANCELLED);
                    }
                    CompletableFuture<Void> cleanup = previewWorld == null
                        ? deletePreviewWorld(worldName)
                        : activated ? deleteActivePreview(previewKey, previewWorld) : deletePreviewWorld(previewWorld.worldName());
                    if (activated) {
                        cleanup = combine(cleanup, replacedCleanup);
                    }
                    if (cancelled) {
                        finishCancelled(request, cleanup);
                    } else {
                        notifyError(onError, throwable);
                        finishPhysical(request, cleanup, throwable);
                    }
                }
            });
            request.setScheduled(task);
            if (request.isCancellationRequested()) {
                request.cancel(PreviewTermination.CANCELLED);
            }
        } catch (Throwable throwable) {
            if (request.isCancellationRequested() || throwable instanceof CancellationException) {
                request.markCancellation(PreviewTermination.CANCELLED);
                finishCancelled(request, CompletableFuture.completedFuture(null));
            } else {
                notifyError(onError, throwable);
                finishPhysical(request, deletePreviewWorld(worldName), throwable);
            }
        }
    }

    private CompiledDatapack compileGenerated(WorldGenProject project, PreviewRequest request) {
        WorldGenBuildRecipe recipe = WorldGenBuildRecipe.capture(project);
        WorldGenGeneratedOutputController.Handoff handoff = generatedOutput.acquireHandoff("worldgen-preview-compile");
        request.setHandoff(handoff);
        try {
            request.throwIfCancellationRequested();
            WorldGenGeneratedOutputController.TransactionResult<WorldGenDatapackBuild> transaction = handoff.transact(
                stage -> datapackCompiler.compile(project, stage, recipe));
            request.throwIfCancellationRequested();
            WorldGenDatapackBuild build = transaction.value();
            build.setFolder(transaction.activePath(build.getFolder()));
            return new CompiledDatapack(build, handoff);
        } catch (IOException | RuntimeException | Error exception) {
            handoff.close();
            request.clearHandoff(handoff);
            throw new IllegalStateException("WorldGen Preview Compile Failed: " + exception.getMessage(), exception);
        }
    }

    public void updatePreview(String previewId, WorldGenGraph graph, Consumer<PreviewWorld> onSuccess, Consumer<Throwable> onError) {
        PreviewKey previewKey = requirePreviewKey(previewId);
        PreviewWorld current = activePreviews.get(previewKey);
        if (current == null) throw new IllegalArgumentException("Preview Missing");
        createPreview(previewId, current.creatorPlayerUuid(), graph, current.world().getEnvironment(), current.world().getSeed(), onSuccess, onError);
    }

    public void stopPreview(String previewId, Runnable onComplete, Consumer<Throwable> onError) {
        PreviewKey previewKey = requirePreviewKey(previewId);
        PreviewRequest request = activeRequests.get(previewKey);
        if (request != null) {
            request.deferPhysicalNotification();
            request.cancel(PreviewTermination.CANCELLED);
        }
        PreviewWorld current = activePreviews.get(previewKey);
        List<OwnedPreview> owned = ownedPreviews.values().stream()
            .filter(entry -> entry.key().equals(previewKey))
            .toList();
        if (request == null && current == null && owned.isEmpty()) {
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }
        CompletableFuture<Void> cleanup = request == null ? CompletableFuture.completedFuture(null) : request.physicalCompletion();
        Map<String, OwnedPreview> targets = new LinkedHashMap<>();
        owned.forEach(entry -> targets.putIfAbsent(entry.preview().worldName(), entry));
        if (current != null) {
            targets.putIfAbsent(current.worldName(), new OwnedPreview(previewKey, current));
        }
        for (OwnedPreview entry : targets.values()) {
            if (request == null || !request.worldName().equals(entry.preview().worldName())) {
                cleanup = combine(cleanup, deleteActivePreview(entry.key(), entry.preview()));
            }
        }
        cleanup.whenComplete((unused, failure) -> {
            if (failure != null) {
                if (request != null) {
                    request.notifyPhysicalFailure(failure);
                    request.notifyLogicalTermination();
                }
                if (onError != null) {
                    onError.accept(failure);
                }
                return;
            }
            try {
                if (request != null) {
                    request.notifyPhysicalComplete();
                    request.notifyLogicalTermination();
                }
                if (onComplete != null) {
                    onComplete.run();
                }
            } catch (Throwable throwable) {
                if (onError != null) {
                    onError.accept(throwable);
                }
            }
        });
    }

    public CompletableFuture<Void> cleanupOrphanedPreviews() {
        CompletableFuture<Void> cleanup = new CompletableFuture<>();
        Runnable unload = () -> {
            try {
                List<CompletableFuture<Void>> loadedWorlds = new ArrayList<>();
                for (World world : new ArrayList<>(Bukkit.getWorlds())) {
                    if (isPreviewWorldName(world.getName())) {
                        loadedWorlds.add(deletePreviewWorld(world.getName()));
                    }
                }
                loadedWorlds.stream().reduce(CompletableFuture.completedFuture(null), this::combine)
                    .whenComplete((unused, unloadFailure) -> {
                        try {
                            if (unloadFailure != null) {
                                cleanup.completeExceptionally(unwrap(unloadFailure));
                                return;
                            }
                            Path root = resolveWorldRoot();
                            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                                try (Stream<Path> stream = Files.list(root)) {
                                    List<Path> orphaned = stream
                                        .filter(Files::isDirectory)
                                        .filter(path -> isPreviewWorldName(path.getFileName().toString()))
                                        .map(path -> path.toAbsolutePath().normalize())
                                        .toList();
                                    for (Path folder : orphaned) {
                                        deleteWorldFolder(root, folder);
                                        WorldGenRuntimeRegistry.unregister(folder.getFileName().toString());
                                    }
                                    cleanup.complete(null);
                                } catch (Throwable failure) {
                                    cleanup.completeExceptionally(failure);
                                }
                            });
                        } catch (Throwable failure) {
                            cleanup.completeExceptionally(failure);
                        }
                    });
            } catch (Throwable failure) {
                cleanup.completeExceptionally(failure);
            }
        };
        if (Bukkit.isPrimaryThread()) {
            unload.run();
        } else {
            try {
                Bukkit.getScheduler().runTask(plugin, unload);
            } catch (Throwable failure) {
                cleanup.completeExceptionally(failure);
            }
        }
        return cleanup;
    }

    public void stopAllPreviews() {
        stopAllPreviewsAsync();
    }

    public CompletableFuture<Void> stopAllPreviewsAsync() {
        List<PreviewRequest> requests = new ArrayList<>(activeRequests.values());
        activeRequests.clear();
        Map<String, OwnedPreview> previewsByWorld = new LinkedHashMap<>();
        ownedPreviews.forEach((worldName, entry) -> previewsByWorld.put(worldName, entry));
        activePreviews.forEach((key, preview) -> previewsByWorld.putIfAbsent(preview.worldName(), new OwnedPreview(key, preview)));
        List<OwnedPreview> previews = new ArrayList<>(previewsByWorld.values());
        requests.forEach(PreviewRequest::deferPhysicalNotification);
        requests.forEach(request -> request.cancel(PreviewTermination.CANCELLED));
        List<String> requestWorlds = requests.stream().map(PreviewRequest::worldName).toList();
        CompletableFuture<Void> cleanup = requests.stream().map(PreviewRequest::physicalCompletion)
            .reduce(CompletableFuture.completedFuture(null), this::combine);
        for (OwnedPreview entry : previews) {
            if (!requestWorlds.contains(entry.preview().worldName())) {
                cleanup = combine(cleanup, deleteActivePreview(entry.key(), entry.preview()));
            }
        }
        CompletableFuture<Void> result = cleanup;
        result.whenComplete((unused, failure) -> {
            requests.forEach(request -> {
                if (failure == null) {
                    request.notifyPhysicalComplete();
                    request.notifyLogicalTermination();
                } else {
                    request.notifyPhysicalFailure(failure);
                    request.notifyLogicalTermination();
                }
            });
        });
        return result;
    }

    private PreviewWorld createWorldSync(PreviewKey previewKey, String playerUuid, TerrainPipeline pipeline, WorldGenDatapackBuild datapackBuild,
                                         String worldName, World.Environment environment, long seed,
                                         Map<String, Location> previousLocations,
                                         WorldGenGeneratedOutputController.Handoff handoff) {
        boolean vanilla = datapackBuild != null
            && WorldGenGenerationMode.resolve(datapackBuild.getGenerationMode()) == WorldGenGenerationMode.VANILLA;
        TerrainPipelineHolder pipelineHolder = pipeline == null ? null : new TerrainPipelineHolder(pipeline);
        WorldCreator creator;
        if (vanilla) {
            WorldGenDatapackInstaller.InstallResult install = handoff == null
                ? datapackInstaller.installPreview(datapackBuild, worldName)
                : datapackInstaller.installPreviewWithHandoff(datapackBuild, worldName, handoff.mutationLease());
            if (!install.installed()) {
                throw new IllegalStateException(install.message());
            }
            NamespacedKey dimensionKey = NamespacedKey.fromString(datapackBuild.getDimensionKey());
            if (dimensionKey == null) {
                throw new IllegalStateException("Vanilla Preview Dimension Missing");
            }
            creator = WorldCreator.ofNameAndKey(worldName, dimensionKey);
        } else {
            creator = new WorldCreator(worldName);
            creator.generator(new NodeGraphChunkGenerator(pipelineHolder));
            creator.biomeProvider(new NodeGraphBiomeProvider(pipelineHolder));
            creator.environment(environment);
        }
        creator.seed(seed);
        creator.type(WorldType.NORMAL);
        creator.generateStructures(vanilla || (pipeline != null && pipeline.hasAnyVanillaStructuresEnabled()));
        World world = creator.createWorld();
        if (world == null) throw new IllegalStateException("Preview World Failed");
        configurePreviewWorld(world);
        if (pipelineHolder != null) {
            WorldGenRuntimeRegistry.register(world, pipelineHolder);
        }
        Player player = resolvePreviewPlayer(playerUuid);
        PreviewWorld preview = new PreviewWorld(worldName, player != null ? player.getUniqueId().toString() : playerUuid,
            pipelineHolder, world, datapackBuild);
        ownedPreviews.put(worldName, new OwnedPreview(previewKey, preview));
        restorePreviewPlayers(world, previousLocations, player);
        if (player != null && !previousLocations.containsKey(player.getUniqueId().toString())) {
            teleportPlayer(player, world.getSpawnLocation(), "worldgen-preview-player");
        }
        return preview;
    }

    private void configurePreviewWorld(World world) {
        world.setAutoSave(false);
        world.setKeepSpawnInMemory(false);
        world.setViewDistance(4);
        world.setSimulationDistance(4);
    }

    public String requirePreviewId(String previewId) {
        return requirePreviewKey(previewId).value();
    }

    private PreviewKey requirePreviewKey(String previewId) {
        if (previewId == null || previewId.isBlank()) {
            return new PreviewKey("worldgen");
        }
        String value = previewId;
        if (!value.equals(value.trim()) || value.length() > 96
            || !value.chars().allMatch(character -> character >= 'a' && character <= 'z'
                || character >= 'A' && character <= 'Z'
                || character >= '0' && character <= '9'
                || character == '_' || character == '-')) {
            throw new IllegalArgumentException("WorldGen Preview Identity Is Unsafe");
        }
        return new PreviewKey(value);
    }

    private String physicalWorldName(PreviewKey previewKey, long revision) {
        String encoded = HexFormat.of().formatHex(previewKey.value().getBytes(StandardCharsets.UTF_8));
        return "resync_preview_" + encoded + "_" + revision + "_" + UUID.randomUUID();
    }

    private boolean isPreviewWorldName(String worldName) {
        return worldName != null && worldName.startsWith("resync_preview_");
    }

    private Map<String, Location> capturePreviewPlayerLocations(PreviewWorld previous) {
        Map<String, Location> locations = new ConcurrentHashMap<>();
        if (previous == null || previous.world() == null) {
            return locations;
        }
        for (Player player : previous.world().getPlayers()) {
            locations.put(player.getUniqueId().toString(), player.getLocation().clone());
        }
        return locations;
    }

    private void restorePreviewPlayers(World world, Map<String, Location> previousLocations, Player fallbackPlayer) {
        for (Map.Entry<String, Location> entry : previousLocations.entrySet()) {
            try {
                Player player = Bukkit.getPlayer(UUID.fromString(entry.getKey()));
                if (player == null) {
                    continue;
                }
                Location previousLocation = entry.getValue();
                Location target = new Location(world, previousLocation.getX(), previousLocation.getY(), previousLocation.getZ(), previousLocation.getYaw(), previousLocation.getPitch());
                teleportPlayer(player, target, "worldgen-preview-restore");
            } catch (Exception ignored) {
            }
        }
        if (previousLocations.isEmpty() && fallbackPlayer != null) {
            teleportPlayer(fallbackPlayer, world.getSpawnLocation(), "worldgen-preview-fallback");
        }
    }

    private Player resolvePreviewPlayer(String playerUuid) {
        try {
            if (playerUuid != null && !playerUuid.isBlank()) {
                Player player = Bukkit.getPlayer(UUID.fromString(playerUuid));
                if (player != null) {
                    return player;
                }
            }
        } catch (Exception ignored) {
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            return player;
        }
        return null;
    }

    private void unloadWorldSync(String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world != null) {
            World fallbackWorld = findFallbackWorld(worldName);
            if (fallbackWorld != null) {
                Location fallbackLocation = fallbackWorld.getSpawnLocation();
                for (Player player : new ArrayList<>(world.getPlayers())) {
                    teleportPlayer(player, fallbackLocation, "worldgen-preview-unload");
                }
            }
            if (!Bukkit.unloadWorld(world, true)) {
                throw new IllegalStateException("WorldGen Preview World Could Not Be Unloaded: " + worldName);
            }
            if (Bukkit.getWorld(worldName) != null) {
                throw new IllegalStateException("WorldGen Preview World Remains Loaded: " + worldName);
            }
        }
    }

    private World findFallbackWorld(String excludedWorldName) {
        for (World world : Bukkit.getWorlds()) {
            if (!world.getName().equals(excludedWorldName) && !isPreviewWorldName(world.getName())) {
                return world;
            }
        }
        return null;
    }

    private void teleportPlayer(Player player, Location target, String operation) {
        playerDataAdmission.mutatePlayer(operation, player, () -> player.teleport(target));
    }

    private CompletableFuture<Void> deleteActivePreview(PreviewKey previewKey, PreviewWorld expected) {
        CompletableFuture<Void> cleanup = deletePreviewWorld(expected.worldName());
        cleanup.whenComplete((unused, failure) -> {
            if (failure == null) {
                activePreviews.remove(previewKey, expected);
            }
        });
        return cleanup;
    }

    private CompletableFuture<Void> deletePreviewWorld(String worldName) {
        CompletableFuture<Void> existing = previewCleanups.get(worldName);
        if (existing != null) {
            return existing;
        }
        CompletableFuture<Void> cleanup = new CompletableFuture<>();
        existing = previewCleanups.putIfAbsent(worldName, cleanup);
        if (existing != null) {
            return existing;
        }
        cleanup.whenComplete((unused, failure) -> previewCleanups.remove(worldName, cleanup));
        Runnable unload = () -> {
            try {
                unloadWorldSync(worldName);
            } catch (Throwable failure) {
                cleanup.completeExceptionally(failure);
                return;
            }
            Path root = resolveWorldRoot();
            Path folder = resolveWorldFolder(root, worldName);
            try {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        deleteWorldFolder(root, folder);
                        WorldGenRuntimeRegistry.unregister(worldName);
                        ownedPreviews.remove(worldName);
                        cleanup.complete(null);
                    } catch (Throwable failure) {
                        cleanup.completeExceptionally(failure);
                    }
                });
            } catch (Throwable failure) {
                cleanup.completeExceptionally(failure);
            }
        };
        if (Bukkit.isPrimaryThread()) {
            unload.run();
        } else {
            try {
                Bukkit.getScheduler().runTask(plugin, unload);
            } catch (Throwable failure) {
                cleanup.completeExceptionally(failure);
            }
        }
        return cleanup;
    }

    private void finishCancelled(PreviewRequest request, CompletionStage<Void> cleanup) {
        request.markCancellation(PreviewTermination.CANCELLED);
        finishPhysical(request, cleanup, null);
    }

    private void finishPhysical(PreviewRequest request, CompletionStage<Void> cleanup, Throwable failure) {
        CompletionStage<Void> physicalCleanup = cleanup == null ? CompletableFuture.completedFuture(null) : cleanup;
        if (failure == null && request.isCancellationRequested()) {
            PreviewWorld current = activePreviews.get(request.previewId());
            if (current != null && request.worldName().equals(current.worldName())) {
                physicalCleanup = combine(physicalCleanup, deleteActivePreview(request.previewId(), current));
            }
        }
        physicalCleanup.whenComplete((unused, cleanupFailure) -> {
            Throwable terminalFailure = failure;
            if (cleanupFailure != null) {
                terminalFailure = appendFailure(terminalFailure, cleanupFailure);
            }
            Throwable handoffFailure = request.closeHandoff();
            if (handoffFailure != null) {
                terminalFailure = appendFailure(terminalFailure, handoffFailure);
            }
            if (!request.complete(terminalFailure)) {
                return;
            }
            activeRequests.remove(request.previewId(), request);
            if (terminalFailure != null && !request.physicalNotificationDeferred()) {
                request.notifyPhysicalFailure(terminalFailure);
            } else if (terminalFailure == null && !request.physicalNotificationDeferred()) {
                request.notifyPhysicalComplete();
            }
            if (!request.physicalNotificationDeferred()) {
                request.notifyLogicalTermination();
            }
        });
    }

    private Throwable appendFailure(Throwable current, Throwable next) {
        if (current == null) {
            return next;
        }
        if (current != next) {
            current.addSuppressed(next);
        }
        return current;
    }

    private Throwable unwrap(Throwable failure) {
        if (failure instanceof CompletionException completion && completion.getCause() != null) {
            return completion.getCause();
        }
        return failure;
    }

    private void notifyError(Consumer<Throwable> onError, Throwable failure) {
        if (onError == null) {
            return;
        }
        try {
            onError.accept(failure);
        } catch (Throwable ignored) {
        }
    }

    private void notifyPhysicalFailure(Consumer<Throwable> onPhysicalError, Throwable failure) {
        if (onPhysicalError == null) {
            return;
        }
        try {
            onPhysicalError.accept(failure);
        } catch (Throwable ignored) {
        }
    }

    private CompletableFuture<Void> combine(CompletionStage<Void> first, CompletionStage<Void> second) {
        BiFunction<Void, Void, Void> completion = (unused, ignored) -> null;
        return first.toCompletableFuture().thenCombine(second.toCompletableFuture(), completion).toCompletableFuture();
    }

    private Path resolveWorldRoot() {
        return Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
    }

    private Path resolveWorldFolder(Path root, String worldName) {
        return root.resolve(worldName).normalize();
    }

    private void deleteWorldFolder(Path root, Path folder) throws IOException {
        if (!folder.startsWith(root) || folder.equals(root)) {
            throw new IllegalArgumentException("WorldGen Preview Folder Escapes World Root");
        }
        if (Files.notExists(folder)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(folder)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
        if (Files.exists(folder)) {
            throw new IOException("WorldGen Preview Folder Could Not Be Removed: " + folder);
        }
    }

    private record PreviewKey(String value) {
        private PreviewKey {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("WorldGen Preview Identity Is Required");
            }
        }
    }

    private record OwnedPreview(PreviewKey key, PreviewWorld preview) {
    }

    public record PreviewWorld(String worldName, String creatorPlayerUuid, TerrainPipelineHolder pipelineHolder, World world, WorldGenDatapackBuild datapackBuild) {
    }

    private record CompiledDatapack(WorldGenDatapackBuild build, WorldGenGeneratedOutputController.Handoff handoff) {
    }

    private final class PreviewRequest {
        private final PreviewKey previewId;
        private final long revision;
        private final String worldName;
        private final Runnable onPhysicalComplete;
        private final Consumer<Throwable> onPhysicalError;
        private final Consumer<PreviewTermination> onLogicalTermination;
        private final AtomicReference<BukkitTask> scheduled = new AtomicReference<>();
        private final AtomicReference<WorldGenGeneratedOutputController.Handoff> handoff = new AtomicReference<>();
        private final AtomicBoolean taskStarted = new AtomicBoolean();
        private final AtomicBoolean cancellationRequested = new AtomicBoolean();
        private final AtomicReference<PreviewTermination> cancellationReason = new AtomicReference<>();
        private final AtomicBoolean physicalNotificationDeferred = new AtomicBoolean();
        private final AtomicBoolean physicalNotificationSent = new AtomicBoolean();
        private final AtomicBoolean logicalTerminationSent = new AtomicBoolean();
        private final AtomicBoolean physicalComplete = new AtomicBoolean();
        private final CompletableFuture<Void> physicalCompletion = new CompletableFuture<>();

        private PreviewRequest(PreviewKey previewId, long revision, String worldName,
                               Runnable onPhysicalComplete, Consumer<Throwable> onPhysicalError,
                               Consumer<PreviewTermination> onLogicalTermination) {
            this.previewId = previewId;
            this.revision = revision;
            this.worldName = worldName;
            this.onPhysicalComplete = onPhysicalComplete;
            this.onPhysicalError = onPhysicalError;
            this.onLogicalTermination = onLogicalTermination;
        }

        private PreviewKey previewId() {
            return previewId;
        }

        private String worldName() {
            return worldName;
        }

        private boolean isCancellationRequested() {
            return cancellationRequested.get();
        }

        private void throwIfCancellationRequested() {
            WorldGenGeneratedOutputController.Handoff current = handoff.get();
            if (cancellationRequested.get() || current != null && current.isCancellationRequested()) {
                throw new CancellationException("WorldGen Preview Creation Was Cancelled");
            }
        }

        private boolean markStarted() {
            return taskStarted.compareAndSet(false, true);
        }

        private void setScheduled(BukkitTask task) {
            scheduled.set(task);
        }

        private void setHandoff(WorldGenGeneratedOutputController.Handoff value) {
            handoff.set(value);
            value.onCancel(this::cancel);
            if (cancellationRequested.get()) {
                value.cancel();
            }
        }

        private void clearHandoff(WorldGenGeneratedOutputController.Handoff value) {
            handoff.compareAndSet(value, null);
        }

        private void cancel() {
            cancel(PreviewTermination.CANCELLED);
        }

        private void cancel(PreviewTermination reason) {
            cancellationRequested.set(true);
            markCancellation(reason);
            WorldGenGeneratedOutputController.Handoff current = handoff.get();
            if (current != null && !current.isCancellationRequested()) {
                try {
                    current.cancel();
                } catch (RuntimeException ignored) {
                }
            }
            BukkitTask task = scheduled.get();
            if (task == null || taskStarted.get()) {
                return;
            }
            try {
                task.cancel();
            } catch (RuntimeException ignored) {
            }
            if (taskStarted.compareAndSet(false, true)) {
                finishCancelled(this, CompletableFuture.completedFuture(null));
            }
        }

        private void markCancellation(PreviewTermination reason) {
            if (reason != null) {
                cancellationReason.compareAndSet(null, reason);
            }
        }

        private void deferPhysicalNotification() {
            physicalNotificationDeferred.set(true);
        }

        private boolean physicalNotificationDeferred() {
            return physicalNotificationDeferred.get();
        }

        private CompletableFuture<Void> physicalCompletion() {
            return physicalCompletion;
        }

        private boolean complete(Throwable failure) {
            if (!physicalComplete.compareAndSet(false, true)) {
                return false;
            }
            if (failure == null) {
                physicalCompletion.complete(null);
            } else {
                physicalCompletion.completeExceptionally(failure);
            }
            return true;
        }

        private Throwable closeHandoff() {
            WorldGenGeneratedOutputController.Handoff current = handoff.getAndSet(null);
            if (current == null) {
                return null;
            }
            try {
                current.close();
                return null;
            } catch (Throwable failure) {
                return failure;
            }
        }

        private void notifyPhysicalComplete() {
            if (!physicalNotificationSent.compareAndSet(false, true)) {
                return;
            }
            if (onPhysicalComplete == null) {
                return;
            }
            try {
                onPhysicalComplete.run();
            } catch (RuntimeException ignored) {
            }
        }

        private void notifyPhysicalFailure(Throwable failure) {
            if (!physicalNotificationSent.compareAndSet(false, true)) {
                return;
            }
            if (onPhysicalError == null) {
                return;
            }
            try {
                onPhysicalError.accept(failure);
            } catch (RuntimeException ignored) {
            }
        }

        private void notifyLogicalTermination() {
            PreviewTermination reason = cancellationReason.get();
            if (reason == null || !logicalTerminationSent.compareAndSet(false, true)) {
                return;
            }
            if (onLogicalTermination == null) {
                return;
            }
            try {
                onLogicalTermination.accept(reason);
            } catch (RuntimeException ignored) {
            }
        }
    }

    public enum PreviewTermination {
        CANCELLED,
        REPLACED
    }
}
