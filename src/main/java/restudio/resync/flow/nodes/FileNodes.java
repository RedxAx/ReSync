package restudio.resync.flow.nodes;

import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowType;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.ReSync;
import restudio.resync.flow.registry.DefineNode;
import restudio.resync.flow.registry.FlowPin;
import restudio.resync.flow.registry.NodeDefinition;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class FileNodes {

    private static final Map<String, BiConsumer<FlowContext, FlowNode>> LEGACY_EXECUTORS = new ConcurrentHashMap<>();
    private static volatile boolean initialized;
    
    private static void registerLegacyNodes(FlowRegistry registry) {
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
    
    public void registerNodes(FlowRegistry registry) {
        registerLegacyNodes(registry);
    }

    private static void ensureLegacyInitialized() {
        if (initialized) {
            return;
        }
        synchronized (FileNodes.class) {
            if (initialized) {
                return;
            }
            FlowRegistry legacyRegistry = new FlowRegistry();
            registerLegacyNodes(legacyRegistry);
            for (String type : legacyRegistry.getRegisteredTypes()) {
                LEGACY_EXECUTORS.put(type, legacyRegistry.getExecutor(type));
            }
            initialized = true;
        }
    }

    private void executeLegacy(String id, FlowContext ctx, FlowNode node) {
        ensureLegacyInitialized();
        BiConsumer<FlowContext, FlowNode> executor = LEGACY_EXECUTORS.get(id);
        if (executor == null) {
            ctx.triggerOutput("flow");
            return;
        }
        executor.accept(ctx, node);
    }

    @DefineNode(id = "file_write", displayName = "Write File", category = NodeDefinition.NodeCategory.DATABASE,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "path", dataType = FlowType.STRING),
                    @FlowPin(name = "content", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void fileWrite(FlowContext ctx, FlowNode node) {
        executeLegacy("file_write", ctx, node);
    }

    @DefineNode(id = "file_append", displayName = "Append File", category = NodeDefinition.NodeCategory.DATABASE,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "path", dataType = FlowType.STRING),
                    @FlowPin(name = "content", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void fileAppend(FlowContext ctx, FlowNode node) {
        executeLegacy("file_append", ctx, node);
    }

    @DefineNode(id = "file_read", displayName = "Read File", category = NodeDefinition.NodeCategory.DATABASE,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "path", dataType = FlowType.STRING)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "content", dataType = FlowType.STRING)
            })
    public void fileRead(FlowContext ctx, FlowNode node) {
        executeLegacy("file_read", ctx, node);
    }

    @DefineNode(id = "file_read_lines", displayName = "Read File Lines", category = NodeDefinition.NodeCategory.DATABASE,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "path", dataType = FlowType.STRING)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "lines", dataType = FlowType.LIST)
            })
    public void fileReadLines(FlowContext ctx, FlowNode node) {
        executeLegacy("file_read_lines", ctx, node);
    }

    @DefineNode(id = "file_delete", displayName = "Delete File", category = NodeDefinition.NodeCategory.DATABASE,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "path", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void fileDelete(FlowContext ctx, FlowNode node) {
        executeLegacy("file_delete", ctx, node);
    }

    @DefineNode(id = "file_exists", displayName = "File Exists", category = NodeDefinition.NodeCategory.DATABASE,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "path", dataType = FlowType.STRING)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "exists", dataType = FlowType.BOOLEAN)
            })
    public void fileExists(FlowContext ctx, FlowNode node) {
        executeLegacy("file_exists", ctx, node);
    }

    @DefineNode(id = "file_copy", displayName = "Copy File", category = NodeDefinition.NodeCategory.DATABASE,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "source_path", dataType = FlowType.STRING),
                    @FlowPin(name = "dest_path", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void fileCopy(FlowContext ctx, FlowNode node) {
        executeLegacy("file_copy", ctx, node);
    }

    @DefineNode(id = "file_move", displayName = "Move File", category = NodeDefinition.NodeCategory.DATABASE,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "source_path", dataType = FlowType.STRING),
                    @FlowPin(name = "dest_path", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void fileMove(FlowContext ctx, FlowNode node) {
        executeLegacy("file_move", ctx, node);
    }

    @DefineNode(id = "file_list_dir", displayName = "List Directory", category = NodeDefinition.NodeCategory.DATABASE,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "path", dataType = FlowType.STRING)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "files", dataType = FlowType.LIST)
            })
    public void fileListDir(FlowContext ctx, FlowNode node) {
        executeLegacy("file_list_dir", ctx, node);
    }

    @DefineNode(id = "file_create_dir", displayName = "Create Directory", category = NodeDefinition.NodeCategory.DATABASE,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "path", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void fileCreateDir(FlowContext ctx, FlowNode node) {
        executeLegacy("file_create_dir", ctx, node);
    }

    @DefineNode(id = "file_get_size", displayName = "Get File Size", category = NodeDefinition.NodeCategory.DATABASE,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "path", dataType = FlowType.STRING)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "size", dataType = FlowType.NUMBER)
            })
    public void fileGetSize(FlowContext ctx, FlowNode node) {
        executeLegacy("file_get_size", ctx, node);
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
    
    private static String findNodeId(FlowContext ctx, FlowNode node) {
        for (var entry : ctx.getRuntime().getGraph().getNodes().entrySet()) {
            if (entry.getValue() == node) {
                return entry.getKey();
            }
        }
        return null;
    }
}
