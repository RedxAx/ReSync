package restudio.resync.flow.nodes;

import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;
import restudio.resync.ReSync;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

public class FileNodes implements NodeCategory {
    
    @Override
    public void registerNodes(FlowRegistry registry) {
        registry.register("file_write", (ctx, node) -> {
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
                    e.printStackTrace();
                }
            });
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("file_append", (ctx, node) -> {
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
                    e.printStackTrace();
                }
            });
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("file_read", (ctx, node) -> {
            String path = ctx.getInputValue(node, "path", String.class, "");
            
            ctx.runAsync(() -> {
                try {
                    File targetFile = resolveSafeFile(path);
                    if (targetFile != null && targetFile.exists()) {
                        String content = new String(Files.readAllBytes(targetFile.toPath()));
                        String nodeId = findNodeId(ctx, node);
                        ctx.runSync(() -> ctx.setNodeOutput(nodeId, "content", content));
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("file_read_lines", (ctx, node) -> {
            String path = ctx.getInputValue(node, "path", String.class, "");
            
            ctx.runAsync(() -> {
                try {
                    File targetFile = resolveSafeFile(path);
                    if (targetFile != null && targetFile.exists()) {
                        List<String> lines = Files.readAllLines(targetFile.toPath());
                        String nodeId = findNodeId(ctx, node);
                        ctx.runSync(() -> ctx.setNodeOutput(nodeId, "lines", lines));
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("file_delete", (ctx, node) -> {
            String path = ctx.getInputValue(node, "path", String.class, "");
            
            ctx.runAsync(() -> {
                try {
                    File targetFile = resolveSafeFile(path);
                    if (targetFile != null && targetFile.exists()) {
                        Files.delete(targetFile.toPath());
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("file_exists", (ctx, node) -> {
            String path = ctx.getInputValue(node, "path", String.class, "");
            File targetFile = resolveSafeFile(path);
            boolean exists = targetFile != null && targetFile.exists();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "exists", exists);
            ctx.triggerOutput("flow");
        });
        
        registry.register("file_copy", (ctx, node) -> {
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
                    e.printStackTrace();
                }
            });
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("file_move", (ctx, node) -> {
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
                    e.printStackTrace();
                }
            });
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("file_list_dir", (ctx, node) -> {
            String path = ctx.getInputValue(node, "path", String.class, "");
            
            ctx.runAsync(() -> {
                try {
                    File targetDir = resolveSafeFile(path);
                    if (targetDir != null && targetDir.exists() && targetDir.isDirectory()) {
                        List<String> files = Arrays.stream(targetDir.listFiles())
                            .map(File::getName)
                            .collect(Collectors.toList());
                        String nodeId = findNodeId(ctx, node);
                        ctx.runSync(() -> ctx.setNodeOutput(nodeId, "files", files));
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("file_create_dir", (ctx, node) -> {
            String path = ctx.getInputValue(node, "path", String.class, "");
            
            ctx.runAsync(() -> {
                try {
                    File targetDir = resolveSafeFile(path);
                    if (targetDir != null) {
                        targetDir.mkdirs();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("file_get_size", (ctx, node) -> {
            String path = ctx.getInputValue(node, "path", String.class, "");
            File targetFile = resolveSafeFile(path);
            long size = (targetFile != null && targetFile.exists()) ? targetFile.length() : 0;
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "size", size);
            ctx.triggerOutput("flow");
        });
    }
    
    private File resolveSafeFile(String path) {
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
    
    private static String findNodeId(FlowContext ctx, FlowNode node) {
        for (var entry : ctx.getRuntime().getGraph().getNodes().entrySet()) {
            if (entry.getValue() == node) {
                return entry.getKey();
            }
        }
        return null;
    }
}
