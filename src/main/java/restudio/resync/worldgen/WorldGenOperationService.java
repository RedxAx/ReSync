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
import restudio.resync.worldgen.datapack.WorldGenDatapackInstaller;
import restudio.resync.worldgen.pipeline.PipelineCompiler;
import restudio.resync.worldgen.pipeline.WorldGenCompileDiagnostics;
import restudio.resync.worldgen.preview.WorldGenPreviewManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class WorldGenOperationService {
    private final Plugin plugin;
    private final WorldGenProjectStorage storage;
    private final WorldGenPreviewManager previewManager;
    private final FlowJobRegistry jobs;
    private final WorldGenDatapackCompiler compiler;
    private final WorldGenDatapackInstaller installer;

    public WorldGenOperationService(Plugin plugin, WorldGenProjectStorage storage, WorldGenPreviewManager previewManager, FlowJobRegistry jobs) {
        if (plugin == null || storage == null || previewManager == null || jobs == null) {
            throw new IllegalArgumentException("WorldGen operation dependencies are required");
        }
        this.plugin = plugin;
        this.storage = storage;
        this.previewManager = previewManager;
        this.jobs = jobs;
        this.compiler = new WorldGenDatapackCompiler(plugin);
        this.installer = new WorldGenDatapackInstaller();
    }

    public FlowOperationResult<Map<String, Object>> validateProject(String projectId) {
        WorldGenProject project = project(projectId);
        if (project == null) {
            return FlowOperationResult.failure("WORLDGEN_PROJECT_MISSING", "WorldGen Project Missing", Map.of("projectId", value(projectId)));
        }
        WorldGenCompileDiagnostics diagnostics = PipelineCompiler.diagnoseProject(project);
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
        String resolvedPreviewId = previewId == null || previewId.isBlank() ? projectId : previewId;
        AtomicReference<BukkitTask> scheduled = new AtomicReference<>();
        job.setCancellation(() -> {
            BukkitTask task = scheduled.get();
            if (task != null) {
                task.cancel();
            }
            previewManager.stopPreview(resolvedPreviewId, null, null);
        });
        jobs.start(job);
        jobs.update(job, 0.1, Map.of("projectId", projectId, "previewId", resolvedPreviewId, "phase", "validating"));
        try {
            World.Environment parsedEnvironment = parseEnvironment(environment);
            scheduled.set(Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                if (job.isCancellationRequested()) {
                    return;
                }
                try {
                    previewManager.createPreview(resolvedPreviewId, playerUuid, project, parsedEnvironment, seed, preview -> {
                        if (job.isCancellationRequested()) {
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
                    }, failure -> jobs.fail(job, "WORLDGEN_PREVIEW_FAILED", message(failure, "WorldGen Preview Failed"),
                        Map.of("projectId", projectId, "previewId", resolvedPreviewId)));
                    jobs.update(job, 0.6, Map.of("projectId", projectId, "previewId", resolvedPreviewId, "phase", "creating_world"));
                } catch (RuntimeException failure) {
                    jobs.fail(job, "WORLDGEN_PREVIEW_FAILED", message(failure, "WorldGen Preview Failed"),
                        Map.of("projectId", projectId, "previewId", resolvedPreviewId));
                }
            }));
        } catch (RuntimeException failure) {
            jobs.fail(job, "WORLDGEN_PREVIEW_SCHEDULING_FAILED", message(failure, "WorldGen Preview Scheduling Failed"),
                Map.of("projectId", projectId, "previewId", resolvedPreviewId));
        }
        return job;
    }

    public FlowJobReference<Map<String, Object>> stopPreview(String previewId, String owner) {
        FlowJobReference<Map<String, Object>> job = jobs.create("worldgen_preview_stop", owner);
        if (previewId == null || previewId.isBlank()) {
            jobs.fail(job, "WORLDGEN_PREVIEW_ID_REQUIRED", "WorldGen Preview ID Required", Map.of());
            return job;
        }
        jobs.start(job);
        previewManager.stopPreview(previewId, () -> jobs.succeed(job, Map.of("previewId", previewId, "stopped", true)),
            failure -> jobs.fail(job, "WORLDGEN_PREVIEW_STOP_FAILED", message(failure, "WorldGen Preview Stop Failed"), Map.of("previewId", previewId)));
        return job;
    }

    private FlowJobReference<Map<String, Object>> submitBuild(String projectId, String kind, String owner, String worldName) {
        FlowJobReference<Map<String, Object>> job = jobs.create(kind, owner);
        WorldGenProject project = project(projectId);
        if (project == null) {
            jobs.fail(job, "WORLDGEN_PROJECT_MISSING", "WorldGen Project Missing", Map.of("projectId", value(projectId)));
            return job;
        }
        AtomicReference<BukkitTask> scheduled = new AtomicReference<>();
        job.setCancellation(() -> {
            BukkitTask task = scheduled.get();
            if (task != null) {
                task.cancel();
            }
        });
        jobs.start(job);
        jobs.update(job, 0.1, Map.of("projectId", projectId, "phase", "validating"));
        try {
            scheduled.set(Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    if (job.isCancellationRequested()) {
                        return;
                    }
                    WorldGenCompileDiagnostics diagnostics = PipelineCompiler.diagnoseProject(project);
                    if (!diagnostics.isSuccess()) {
                        jobs.fail(job, "WORLDGEN_VALIDATION_FAILED", "WorldGen Validation Failed", diagnosticsValue(projectId, diagnostics));
                        return;
                    }
                    jobs.update(job, 0.35, Map.of("projectId", projectId, "phase", "compiling"));
                    WorldGenDatapackBuild build = compiler.compile(project, compiler.generatedRoot(), System.currentTimeMillis());
                    if (job.isCancellationRequested()) {
                        return;
                    }
                    if (!"worldgen_install".equals(kind)) {
                        jobs.succeed(job, buildValue(build));
                        return;
                    }
                    jobs.update(job, 0.75, Map.of("projectId", projectId, "worldName", worldName, "phase", "installing"));
                    scheduled.set(Bukkit.getScheduler().runTask(plugin, () -> completeInstall(job, build, worldName)));
                } catch (RuntimeException failure) {
                    jobs.fail(job, "WORLDGEN_COMPILE_FAILED", message(failure, "WorldGen Compile Failed"), Map.of("projectId", projectId));
                }
            }));
        } catch (RuntimeException failure) {
            jobs.fail(job, "WORLDGEN_JOB_SCHEDULING_FAILED", message(failure, "WorldGen Job Scheduling Failed"), Map.of("projectId", projectId));
        }
        return job;
    }

    private void completeInstall(FlowJobReference<Map<String, Object>> job, WorldGenDatapackBuild build, String worldName) {
        if (job.isCancellationRequested()) {
            return;
        }
        WorldGenDatapackInstaller.InstallResult install = installer.install(build, worldName);
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
        result.put("minecraftVersion", value(build.getMinecraftVersion()));
        result.put("packFormat", build.getPackFormat());
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
