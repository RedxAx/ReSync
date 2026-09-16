package restudio.resync.worldgen;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import restudio.flow.data.FlowJobReference;
import restudio.flow.data.FlowOperationResult;
import restudio.resync.flow.jobs.FlowJobRegistry;
import restudio.resync.worldgen.data.WorldGenProject;
import restudio.resync.worldgen.data.WorldGenSerializer;
import restudio.resync.worldgen.datapack.WorldGenDatapackBuild;
import restudio.resync.worldgen.datapack.WorldGenDatapackCompiler;
import restudio.resync.worldgen.datapack.WorldGenBuildRecipe;
import restudio.resync.worldgen.datapack.WorldGenDatapackInstaller;
import restudio.resync.worldgen.datapack.WorldGenInstalledDatapackCapability;
import restudio.resync.worldgen.pipeline.PipelineCompiler;
import restudio.resync.worldgen.pipeline.WorldGenCompileDiagnostics;
import restudio.resync.worldgen.preview.WorldGenPreviewManager;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class WorldGenOperationService {
    private final Plugin plugin;
    private final WorldGenProjectStorage storage;
    private final WorldGenPreviewManager previewManager;
    private final FlowJobRegistry jobs;
    private final WorldGenDatapackCompiler compiler;
    private final WorldGenInstalledDatapackCapability installer;
    private final WorldGenGeneratedOutputController generatedOutput;

    public WorldGenOperationService(Plugin plugin, WorldGenProjectStorage storage, WorldGenPreviewManager previewManager,
                                    FlowJobRegistry jobs, WorldGenGeneratedOutputController generatedOutput,
                                    WorldGenInstalledDatapackCapability installer) {
        if (plugin == null || storage == null || previewManager == null || jobs == null || generatedOutput == null || installer == null) {
            throw new IllegalArgumentException("WorldGen operation dependencies are required");
        }
        this.plugin = plugin;
        this.storage = storage;
        this.previewManager = previewManager;
        this.jobs = jobs;
        this.compiler = new WorldGenDatapackCompiler(plugin, storage.legacyCompatibilityEnabled());
        this.installer = installer;
        this.generatedOutput = generatedOutput;
    }

    public FlowOperationResult<Map<String, Object>> validateProject(String projectId) {
        WorldGenProject project = project(projectId);
        if (project == null) {
            return FlowOperationResult.failure("WORLDGEN_PROJECT_MISSING", "WorldGen Project Missing", Map.of("projectId", value(projectId)));
        }
        WorldGenCompileDiagnostics diagnostics = PipelineCompiler.diagnoseProject(project, storage.legacyCompatibilityEnabled());
        Map<String, Object> value = diagnosticsValue(projectId, diagnostics);
        return diagnostics.isSuccess()
            ? FlowOperationResult.success(value)
            : FlowOperationResult.failure("WORLDGEN_VALIDATION_FAILED", "WorldGen Validation Failed", value);
    }

    public FlowJobReference<Map<String, Object>> compileProject(String projectId, String owner) {
        return submitBuild(projectId, "worldgen_compile", owner, null);
    }

    public FlowJobReference<Map<String, Object>> installProject(String projectId, String worldName, String owner) {
        return submitBuild(projectId, "worldgen_install", owner, worldName == null ? "" : worldName.trim());
    }

    public FlowJobReference<Map<String, Object>> previewProject(String projectId, String previewId, String playerUuid, String environment, long seed, String owner) {
        FlowJobReference<Map<String, Object>> job = jobs.create("worldgen_preview", owner);
        WorldGenProject project = project(projectId);
        if (project == null) {
            jobs.fail(job, "WORLDGEN_PROJECT_MISSING", "WorldGen Project Missing", Map.of("projectId", value(projectId)));
            return job;
        }
        String resolvedPreviewId;
        try {
            resolvedPreviewId = previewManager.requirePreviewId(previewId == null || previewId.isBlank() ? projectId : previewId);
        } catch (Throwable failure) {
            jobs.fail(job, "WORLDGEN_PREVIEW_ID_INVALID", message(failure, "WorldGen Preview Identity Is Unsafe"),
                Map.of("projectId", value(projectId)));
            return job;
        }
        AtomicReference<BukkitTask> scheduled = new AtomicReference<>();
        AtomicBoolean taskStarted = new AtomicBoolean();
        AtomicBoolean cancellationRequested = new AtomicBoolean();
        CompletableFuture<Void> termination = new CompletableFuture<>();
        Object previewLock = new Object();
        Runnable cancellation = () -> {
            cancellationRequested.set(true);
            BukkitTask task = scheduled.get();
            if (task != null) {
                try {
                    task.cancel();
                } catch (RuntimeException ignored) {
                }
            }
            synchronized (previewLock) {
                if (!taskStarted.get()) {
                    termination.complete(null);
                    return;
                }
                try {
                    previewManager.stopPreview(resolvedPreviewId, () -> termination.complete(null), termination::completeExceptionally);
                } catch (Throwable failure) {
                    termination.completeExceptionally(failure);
                }
            }
        };
        try {
            if (!jobs.bindAndStart(job, cancellation, termination)) {
                jobs.cancel(job);
                termination.complete(null);
                return job;
            }
            jobs.update(job, 0.1, Map.of("projectId", projectId, "previewId", resolvedPreviewId, "phase", "validating"));
            World.Environment parsedEnvironment = parseEnvironment(environment);
            boolean launched = jobs.launch(job, () -> {
                try {
                    BukkitTask task = Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        synchronized (previewLock) {
                            taskStarted.set(true);
                            if (cancellationRequested.get() || job.isCancellationRequested()) {
                                termination.complete(null);
                                return;
                            }
                            try {
                                previewManager.createPreview(resolvedPreviewId, playerUuid, project, parsedEnvironment, seed, preview -> {
                                    try {
                                        if (cancellationRequested.get() || job.isCancellationRequested()) {
                                            return;
                                        }
                                        Map<String, Object> result = new LinkedHashMap<>();
                                        result.put("projectId", projectId);
                                        result.put("previewId", resolvedPreviewId);
                                        result.put("worldName", preview.worldName());
                                        result.put("seed", preview.world().getSeed());
                                        if (preview.datapackBuild() != null) {
                                            result.putAll(buildValue(preview.datapackBuild()));
                                        }
                                        jobs.succeed(job, result);
                                    } catch (Throwable failure) {
                                        jobs.fail(job, "WORLDGEN_PREVIEW_FAILED", message(failure, "WorldGen Preview Failed"),
                                            Map.of("projectId", projectId, "previewId", resolvedPreviewId));
                                    }
                                }, failure -> jobs.fail(job, "WORLDGEN_PREVIEW_FAILED", message(failure, "WorldGen Preview Failed"),
                                    Map.of("projectId", projectId, "previewId", resolvedPreviewId)),
                                     () -> termination.complete(null), termination::completeExceptionally,
                                     outcome -> cancelPreviewJobAfterPhysical(job));
                            } catch (Throwable failure) {
                                jobs.fail(job, "WORLDGEN_PREVIEW_FAILED", message(failure, "WorldGen Preview Failed"),
                                    Map.of("projectId", projectId, "previewId", resolvedPreviewId));
                                try {
                                    previewManager.stopPreview(resolvedPreviewId, () -> termination.complete(null), termination::completeExceptionally);
                                } catch (Throwable cleanupFailure) {
                                    failure.addSuppressed(cleanupFailure);
                                    termination.completeExceptionally(failure);
                                }
                            }
                        }
                    });
                    scheduled.set(task);
                    if (cancellationRequested.get() || job.isCancellationRequested()) {
                        task.cancel();
                    }
                } catch (Throwable failure) {
                    jobs.fail(job, "WORLDGEN_PREVIEW_SCHEDULING_FAILED", message(failure, "WorldGen Preview Scheduling Failed"),
                        Map.of("projectId", projectId, "previewId", resolvedPreviewId));
                    termination.completeExceptionally(failure);
                }
            });
            if (!launched) {
                cancellation.run();
            }
        } catch (Throwable failure) {
            jobs.fail(job, "WORLDGEN_PREVIEW_SCHEDULING_FAILED", message(failure, "WorldGen Preview Scheduling Failed"),
                Map.of("projectId", projectId, "previewId", resolvedPreviewId));
            cancellation.run();
        }
        return job;
    }

    public FlowJobReference<Map<String, Object>> stopPreview(String previewId, String owner) {
        FlowJobReference<Map<String, Object>> job = jobs.create("worldgen_preview_stop", owner);
        if (previewId == null || previewId.isBlank()) {
            jobs.fail(job, "WORLDGEN_PREVIEW_ID_REQUIRED", "WorldGen Preview ID Required", Map.of());
            return job;
        }
        final String resolvedPreviewId;
        try {
            resolvedPreviewId = previewManager.requirePreviewId(previewId);
        } catch (Throwable failure) {
            jobs.fail(job, "WORLDGEN_PREVIEW_ID_INVALID", message(failure, "WorldGen Preview Identity Is Unsafe"), Map.of());
            return job;
        }
        CompletableFuture<Void> termination = new CompletableFuture<>();
        Runnable stop = () -> {
            try {
                previewManager.stopPreview(resolvedPreviewId,
                    () -> {
                        jobs.succeed(job, Map.of("previewId", resolvedPreviewId, "stopped", true));
                        termination.complete(null);
                    }, failure -> {
                        jobs.fail(job, "WORLDGEN_PREVIEW_STOP_FAILED", message(failure, "WorldGen Preview Stop Failed"), Map.of("previewId", resolvedPreviewId));
                        termination.completeExceptionally(failure);
                    });
            } catch (Throwable failure) {
                termination.completeExceptionally(failure);
            }
        };
        try {
            if (!jobs.bindAndStart(job, stop, termination)) {
                jobs.cancel(job);
                termination.complete(null);
                return job;
            }
            if (!jobs.launch(job, stop)) {
                stop.run();
            }
        } catch (Throwable failure) {
            jobs.fail(job, "WORLDGEN_PREVIEW_STOP_SCHEDULING_FAILED", message(failure, "WorldGen Preview Stop Scheduling Failed"),
                Map.of("previewId", resolvedPreviewId));
            termination.completeExceptionally(failure);
        }
        return job;
    }

    private void cancelPreviewJobAfterPhysical(FlowJobReference<?> job) {
        if (job == null) {
            return;
        }
        if (job.getState() != FlowJobReference.State.CANCELLING) {
            job.requestCancellation();
        }
        jobs.completeCancellationAfterPhysical(job);
    }

    private FlowJobReference<Map<String, Object>> submitBuild(String projectId, String kind, String owner, String worldName) {
        FlowJobReference<Map<String, Object>> job = jobs.create(kind, owner);
        WorldGenProject project = project(projectId);
        if (project == null) {
            jobs.fail(job, "WORLDGEN_PROJECT_MISSING", "WorldGen Project Missing", Map.of("projectId", value(projectId)));
            return job;
        }
        AtomicReference<BukkitTask> scheduled = new AtomicReference<>();
        AtomicReference<AtomicBoolean> taskStarted = new AtomicReference<>(new AtomicBoolean());
        AtomicReference<WorldGenGeneratedOutputController.Handoff> handoff = new AtomicReference<>();
        AtomicBoolean cancellationRequested = new AtomicBoolean();
        AtomicBoolean cancellationHandled = new AtomicBoolean();
        CompletableFuture<Void> termination = new CompletableFuture<>();
        Runnable cancellation = () -> {
            if (!cancellationHandled.compareAndSet(false, true)) {
                return;
            }
            try {
                cancellationRequested.set(true);
                BukkitTask task = scheduled.get();
                if (task != null) {
                    task.cancel();
                    if (!taskStarted.get().get()) {
                        WorldGenGeneratedOutputController.Handoff generatedLease = handoff.getAndSet(null);
                        if (generatedLease != null) {
                            generatedLease.close();
                        }
                        termination.complete(null);
                    }
                } else {
                    WorldGenGeneratedOutputController.Handoff generatedLease = handoff.getAndSet(null);
                    if (generatedLease != null) {
                        generatedLease.close();
                    }
                    termination.complete(null);
                }
            } catch (Throwable failure) {
                termination.completeExceptionally(failure);
            }
        };
        try {
            if (!jobs.bindAndStart(job, cancellation, termination)) {
                jobs.cancel(job);
                termination.complete(null);
                return job;
            }
            jobs.update(job, 0.1, Map.of("projectId", projectId, "phase", "validating"));
            boolean launched = jobs.launch(job, () -> {
                taskStarted.set(new AtomicBoolean());
                BukkitTask task = Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                taskStarted.get().set(true);
                try {
                    if (cancellationRequested.get() || job.isCancellationRequested()) {
                        termination.complete(null);
                        return;
                    }
                    WorldGenCompileDiagnostics diagnostics = PipelineCompiler.diagnoseProject(project, storage.legacyCompatibilityEnabled());
                    if (!diagnostics.isSuccess()) {
                        jobs.fail(job, "WORLDGEN_VALIDATION_FAILED", "WorldGen Validation Failed", diagnosticsValue(projectId, diagnostics));
                        termination.complete(null);
                        return;
                    }
                    jobs.update(job, 0.35, Map.of("projectId", projectId, "phase", "compiling"));
                    CompiledBuild compiled = compileGenerated(project);
                    WorldGenDatapackBuild build = compiled.build();
                    handoff.set(compiled.handoff());
                    if (cancellationRequested.get() || job.isCancellationRequested()) {
                        WorldGenGeneratedOutputController.Handoff generatedLease = handoff.getAndSet(null);
                        if (generatedLease != null) {
                            generatedLease.close();
                        }
                        termination.complete(null);
                        return;
                    }
                    if (!"worldgen_install".equals(kind)) {
                        try {
                            jobs.succeed(job, buildValue(build));
                        } finally {
                            WorldGenGeneratedOutputController.Handoff generatedLease = handoff.getAndSet(null);
                            if (generatedLease != null) {
                                generatedLease.close();
                            }
                            termination.complete(null);
                        }
                        return;
                    }
                    jobs.update(job, 0.75, Map.of("projectId", projectId, "worldName", worldName, "phase", "installing"));
                    AtomicBoolean installStarted = new AtomicBoolean();
                    WorldGenGeneratedOutputController.Handoff generatedLease = handoff.get();
                    generatedLease.onCancel(() -> {
                        BukkitTask install = scheduled.get();
                        if (install != null) {
                            install.cancel();
                        }
                    });
                    BukkitTask installTask = Bukkit.getScheduler().runTask(plugin, () -> {
                        installStarted.set(true);
                        try {
                            generatedLease.throwIfCancellationRequested();
                            completeInstall(job, build, worldName, generatedLease);
                        } finally {
                            if (handoff.compareAndSet(generatedLease, null)) {
                                generatedLease.close();
                            }
                            termination.complete(null);
                        }
                    });
                    scheduled.set(installTask);
                    taskStarted.set(installStarted);
                    if (cancellationRequested.get()) {
                        installTask.cancel();
                        if (!installStarted.get()) {
                            if (handoff.compareAndSet(generatedLease, null)) {
                                generatedLease.close();
                            }
                            termination.complete(null);
                        }
                    }
                } catch (Throwable failure) {
                    try {
                        jobs.fail(job, "WORLDGEN_COMPILE_FAILED", message(failure, "WorldGen Compile Failed"), Map.of("projectId", projectId));
                    } finally {
                        WorldGenGeneratedOutputController.Handoff generatedLease = handoff.getAndSet(null);
                        if (generatedLease != null) {
                            generatedLease.close();
                        }
                        termination.complete(null);
                    }
                }
                });
                scheduled.set(task);
                if (cancellationRequested.get()) {
                    task.cancel();
                }
            });
            if (!launched) {
                cancellation.run();
            }
        } catch (Throwable failure) {
            try {
                jobs.fail(job, "WORLDGEN_JOB_SCHEDULING_FAILED", message(failure, "WorldGen Job Scheduling Failed"), Map.of("projectId", projectId));
            } finally {
                termination.complete(null);
            }
        }
        return job;
    }

    private void completeInstall(FlowJobReference<Map<String, Object>> job, WorldGenDatapackBuild build, String worldName,
                                 WorldGenGeneratedOutputController.Handoff generatedLease) {
        if (job.isCancellationRequested()) {
            return;
        }
        if (generatedLease.isCancellationRequested()) {
            return;
        }
        WorldGenDatapackInstaller.InstallResult install = installer.installWithHandoff(
            build, worldName, generatedLease.mutationLease());
        if (!install.installed()) {
            jobs.fail(job, "WORLDGEN_INSTALL_FAILED", install.message(), Map.of("projectId", build.getProjectId(), "worldName", value(worldName)));
            return;
        }
        Map<String, Object> result = new LinkedHashMap<>(buildValue(build));
        result.put("worldName", value(worldName));
        result.put("installed", true);
        result.put("enabled", install.enabled());
        result.put("message", install.message());
        jobs.succeed(job, result);
    }

    private WorldGenProject project(String projectId) {
        WorldGenProject stored = storage.getProject(projectId);
        if (stored == null) {
            return null;
        }
        WorldGenProject copy = WorldGenSerializer.deserializeProject(WorldGenSerializer.serializeProject(stored));
        if (copy != null) {
            copy.rebuildIndices();
        }
        return copy;
    }

    private CompiledBuild compileGenerated(WorldGenProject project) {
        WorldGenBuildRecipe recipe = WorldGenBuildRecipe.capture(project);
        WorldGenGeneratedOutputController.Handoff handoff = generatedOutput.acquireHandoff("worldgen-operation-compile");
        try {
            WorldGenGeneratedOutputController.TransactionResult<WorldGenDatapackBuild> transaction = handoff.transact(
                stage -> compiler.compile(project, stage, recipe));
            WorldGenDatapackBuild build = transaction.value();
            build.setFolder(transaction.activePath(build.getFolder()));
            return new CompiledBuild(build, handoff);
        } catch (IOException | RuntimeException | Error exception) {
            handoff.close();
            throw new IllegalStateException("WorldGen Compile Failed: " + exception.getMessage(), exception);
        }
    }

    private record CompiledBuild(WorldGenDatapackBuild build, WorldGenGeneratedOutputController.Handoff handoff) {
    }


    private Map<String, Object> diagnosticsValue(String projectId, WorldGenCompileDiagnostics diagnostics) {
        List<Map<String, Object>> entries = diagnostics.getDiagnostics().stream().map(entry -> Map.<String, Object>of(
            "stage", value(entry.stage()),
            "severity", value(entry.severity()),
            "message", value(entry.message())
        )).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", value(projectId));
        result.put("valid", diagnostics.isSuccess());
        result.put("elapsedMillis", diagnostics.getElapsedMillis());
        result.put("diagnostics", entries);
        return result;
    }

    private Map<String, Object> buildValue(WorldGenDatapackBuild build) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", value(build.getProjectId()));
        result.put("namespace", value(build.getNamespace()));
        result.put("packName", value(build.getPackName()));
        result.put("generationMode", value(build.getGenerationMode()));
        result.put("dimensionKey", value(build.getDimensionKey()));
        result.put("minecraftVersion", value(build.getMinecraftVersion()));
        result.put("packFormat", build.getPackFormat());
        result.put("packFormatMinor", build.getPackFormatMinor());
        result.put("datapackVersion", build.getDatapackVersion());
        result.put("revision", build.getRevision());
        result.put("fileCount", build.getFileCount());
        result.put("warnings", List.copyOf(build.getWarnings()));
        return result;
    }

    private World.Environment parseEnvironment(String value) {
        if (value == null || value.isBlank() || "CUSTOM".equalsIgnoreCase(value)) {
            return World.Environment.NORMAL;
        }
        try {
            return World.Environment.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return World.Environment.NORMAL;
        }
    }

    private String message(Throwable failure, String fallback) {
        return failure != null && failure.getMessage() != null && !failure.getMessage().isBlank() ? failure.getMessage() : fallback;
    }

    private String value(String value) {
        return value == null ? "" : value;
    }
}
