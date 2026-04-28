package restudio.resync.flow.handler.generic;

import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.Log;
import restudio.resync.ReSync;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

public class FileHandler implements NodeHandler {
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public FileHandler() {
        operations.put("file_write", (ctx, node) -> {
            String path = ctx.getInputValue(node, "path", String.class, "");
            String content = ctx.getInputValue(node, "content", String.class, "");
            ctx.runAsync(() -> {
                try {
                    File targetFile = resolveSafeFile(path);
                    if (targetFile != null) {
                        targetFile.getParentFile().mkdirs();
                        Files.write(targetFile.toPath(), content.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    }
                } catch (IOException e) {
                    Log.warn("[Flow] File write failed: " + e.getMessage());
                }
            });
        });

        operations.put("file_append", (ctx, node) -> {
            String path = ctx.getInputValue(node, "path", String.class, "");
            String content = ctx.getInputValue(node, "content", String.class, "");
            ctx.runAsync(() -> {
                try {
                    File targetFile = resolveSafeFile(path);
                    if (targetFile != null) {
                        targetFile.getParentFile().mkdirs();
                        Files.write(targetFile.toPath(), content.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    }
                } catch (IOException e) {
                    Log.warn("[Flow] File append failed: " + e.getMessage());
                }
            });
        });

        operations.put("file_read", (ctx, node) -> {
            String path = ctx.getInputValue(node, "path", String.class, "");
            ctx.runAsync(() -> {
                try {
                    File targetFile = resolveSafeFile(path);
                    if (targetFile != null && targetFile.exists()) {
                        String content = new String(Files.readAllBytes(targetFile.toPath()));
                        ctx.runSync(() -> ctx.setOutput(node, "content", content));
                    }
                } catch (IOException e) {
                    Log.warn("[Flow] File read failed: " + e.getMessage());
                }
            });
        });

        operations.put("file_read_lines", (ctx, node) -> {
            String path = ctx.getInputValue(node, "path", String.class, "");
            ctx.runAsync(() -> {
                try {
                    File targetFile = resolveSafeFile(path);
                    if (targetFile != null && targetFile.exists()) {
                        List<String> lines = Files.readAllLines(targetFile.toPath());
                        ctx.runSync(() -> ctx.setOutput(node, "lines", lines));
                    }
                } catch (IOException e) {
                    Log.warn("[Flow] File read lines failed: " + e.getMessage());
                }
            });
        });

        operations.put("file_delete", (ctx, node) -> {
            String path = ctx.getInputValue(node, "path", String.class, "");
            ctx.runAsync(() -> {
                try {
                    File targetFile = resolveSafeFile(path);
                    if (targetFile != null && targetFile.exists()) {
                        Files.delete(targetFile.toPath());
                    }
                } catch (IOException e) {
                    Log.warn("[Flow] File delete failed: " + e.getMessage());
                }
            });
        });

        operations.put("file_exists", (ctx, node) -> {
            String path = ctx.getInputValue(node, "path", String.class, "");
            File targetFile = resolveSafeFile(path);
            boolean exists = targetFile != null && targetFile.exists();
            ctx.setOutput(node, "exists", exists);
        });

        operations.put("file_copy", (ctx, node) -> {
            String sourcePath = ctx.getInputValue(node, "source_path", String.class, "");
            String destPath = ctx.getInputValue(node, "dest_path", String.class, "");
            ctx.runAsync(() -> {
                try {
                    File sourceFile = resolveSafeFile(sourcePath);
                    File destFile = resolveSafeFile(destPath);
                    if (sourceFile != null && destFile != null && sourceFile.exists()) {
                        destFile.getParentFile().mkdirs();
                        Files.copy(sourceFile.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    Log.warn("[Flow] File copy failed: " + e.getMessage());
                }
            });
        });

        operations.put("file_move", (ctx, node) -> {
            String sourcePath = ctx.getInputValue(node, "source_path", String.class, "");
            String destPath = ctx.getInputValue(node, "dest_path", String.class, "");
            ctx.runAsync(() -> {
                try {
                    File sourceFile = resolveSafeFile(sourcePath);
                    File destFile = resolveSafeFile(destPath);
                    if (sourceFile != null && destFile != null && sourceFile.exists()) {
                        destFile.getParentFile().mkdirs();
                        Files.move(sourceFile.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    Log.warn("[Flow] File move failed: " + e.getMessage());
                }
            });
        });

        operations.put("file_list_dir", (ctx, node) -> {
            String path = ctx.getInputValue(node, "path", String.class, "");
            ctx.runAsync(() -> {
                try {
                    File targetDir = resolveSafeFile(path);
                    if (targetDir != null && targetDir.exists() && targetDir.isDirectory()) {
                        File[] files = targetDir.listFiles();
                        if (files != null) {
                            List<String> fileNames = Arrays.stream(files)
                                    .map(File::getName)
                                    .collect(Collectors.toList());
                            ctx.runSync(() -> ctx.setOutput(node, "files", fileNames));
                        }
                    }
                } catch (Exception e) {
                    Log.warn("[Flow] File list dir failed: " + e.getMessage());
                }
            });
        });

        operations.put("file_create_dir", (ctx, node) -> {
            String path = ctx.getInputValue(node, "path", String.class, "");
            ctx.runAsync(() -> {
                try {
                    File targetDir = resolveSafeFile(path);
                    if (targetDir != null) {
                        targetDir.mkdirs();
                    }
                } catch (Exception e) {
                    Log.warn("[Flow] File create dir failed: " + e.getMessage());
                }
            });
        });

        operations.put("file_get_size", (ctx, node) -> {
            String path = ctx.getInputValue(node, "path", String.class, "");
            File targetFile = resolveSafeFile(path);
            long size = (targetFile != null && targetFile.exists()) ? targetFile.length() : 0;
            ctx.setOutput(node, "size", size);
        });
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("FileHandler", this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op != null) {
            op.accept(ctx, node);
        }
        ctx.triggerOutput("flow");
    }

    private static File resolveSafeFile(String path) {
        if (path == null || path.isEmpty()) return null;
        File dataFolder = ReSync.getInstance().getDataFolder();
        File targetFile = new File(dataFolder, path);
        try {
            String canonicalPath = targetFile.getCanonicalPath();
            String dataPath = dataFolder.getCanonicalPath();
            if (!canonicalPath.startsWith(dataPath)) {
                return null;
            }
            return targetFile;
        } catch (IOException e) {
            return null;
        }
    }
}
