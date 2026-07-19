package restudio.resync.flow.handler.generic;

import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowOperationResult;
import restudio.resync.ReSync;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

public class FileHandler implements NodeHandler {
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();
    private final Path root;

    public FileHandler() {
        this(ReSync.getInstance().getDataFolder().toPath());
    }

    FileHandler(Path root) {
        this.root = root.toAbsolutePath().normalize();
        operations.put("file_write", (ctx, node) -> executeAsync(ctx, node, () -> {
            Path target = resolveSafePath(ctx.getInputValue(node, "path", String.class, ""));
            createParent(target);
            Files.writeString(target, ctx.getInputValue(node, "content", String.class, ""), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return outcome(true, Map.of(), target);
        }, Map.of()));
        operations.put("file_append", (ctx, node) -> executeAsync(ctx, node, () -> {
            Path target = resolveSafePath(ctx.getInputValue(node, "path", String.class, ""));
            createParent(target);
            Files.writeString(target, ctx.getInputValue(node, "content", String.class, ""), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return outcome(true, Map.of(), target);
        }, Map.of()));
        operations.put("file_read", (ctx, node) -> executeAsync(ctx, node, () -> {
            Path target = requireFile(ctx.getInputValue(node, "path", String.class, ""));
            String content = Files.readString(target, StandardCharsets.UTF_8);
            return outcome(content, Map.of("content", content), target);
        }, Map.of("content", "")));
        operations.put("file_read_lines", (ctx, node) -> executeAsync(ctx, node, () -> {
            Path target = requireFile(ctx.getInputValue(node, "path", String.class, ""));
            List<String> lines = List.copyOf(Files.readAllLines(target, StandardCharsets.UTF_8));
            return outcome(lines, Map.of("lines", lines), target);
        }, Map.of("lines", List.of())));
        operations.put("file_delete", (ctx, node) -> executeAsync(ctx, node, () -> {
            Path target = resolveSafePath(ctx.getInputValue(node, "path", String.class, ""));
            boolean exists = Files.exists(target);
            boolean preview = ctx.getInputValue(node, "preview", Boolean.class, false);
            if (!exists) {
                throw new FileOperationException("FILE_NOT_FOUND", "File does not exist");
            }
            boolean deleted = !preview && Files.deleteIfExists(target);
            return new FileOutcome<>(deleted, Map.of("preview", preview, "would_delete", exists, "deleted", deleted),
                Map.of("path", relativePath(target), "preview", preview));
        }, Map.of("preview", false, "would_delete", false, "deleted", false)));
        operations.put("file_exists", (ctx, node) -> executeSync(ctx, node, () -> {
            Path target = resolveSafePath(ctx.getInputValue(node, "path", String.class, ""));
            boolean exists = Files.exists(target);
            return outcome(exists, Map.of("exists", exists), target);
        }, Map.of("exists", false)));
        operations.put("file_copy", (ctx, node) -> executeAsync(ctx, node, () -> {
            Path source = requireFile(ctx.getInputValue(node, "source_path", String.class, ""));
            Path destination = resolveSafePath(ctx.getInputValue(node, "dest_path", String.class, ""));
            createParent(destination);
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
            return new FileOutcome<>(true, Map.of(), Map.of("source", relativePath(source), "destination", relativePath(destination)));
        }, Map.of()));
        operations.put("file_move", (ctx, node) -> executeAsync(ctx, node, () -> {
            Path source = requireFile(ctx.getInputValue(node, "source_path", String.class, ""));
            Path destination = resolveSafePath(ctx.getInputValue(node, "dest_path", String.class, ""));
            createParent(destination);
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
            return new FileOutcome<>(true, Map.of(), Map.of("source", relativePath(source), "destination", relativePath(destination)));
        }, Map.of()));
        operations.put("file_list_dir", (ctx, node) -> executeAsync(ctx, node, () -> {
            Path directory = requireDirectory(ctx.getInputValue(node, "path", String.class, ""));
            List<String> files;
            try (Stream<Path> entries = Files.list(directory)) {
                files = entries.map(path -> path.getFileName().toString()).sorted(String.CASE_INSENSITIVE_ORDER).toList();
            }
            return outcome(files, Map.of("files", files), directory);
        }, Map.of("files", List.of())));
        operations.put("file_create_dir", (ctx, node) -> executeAsync(ctx, node, () -> {
            Path directory = resolveSafePath(ctx.getInputValue(node, "path", String.class, ""));
            boolean existed = Files.isDirectory(directory);
            Files.createDirectories(directory);
            return new FileOutcome<>(true, Map.of("created", !existed), Map.of("path", relativePath(directory), "created", !existed));
        }, Map.of("created", false)));
        operations.put("file_get_size", (ctx, node) -> executeSync(ctx, node, () -> {
            Path target = requireFile(ctx.getInputValue(node, "path", String.class, ""));
            long size = Files.size(target);
            return outcome(size, Map.of("size", size), target);
        }, Map.of("size", 0L)));
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("FileHandler", this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> handler = operation != null ? operations.get(operation) : null;
        if (handler == null) {
            throw new IllegalArgumentException("Unknown file operation: " + operation);
        }
        handler.accept(ctx, node);
    }

    private void executeAsync(FlowContext context, FlowNode node, FileOperation<?> operation, Map<String, Object> failureOutputs) {
        context.runAsync(() -> {
            Completion completion = perform(operation, failureOutputs);
            context.runSync(() -> complete(context, node, completion));
        });
    }

    private void executeSync(FlowContext context, FlowNode node, FileOperation<?> operation, Map<String, Object> failureOutputs) {
        complete(context, node, perform(operation, failureOutputs));
    }

    private Completion perform(FileOperation<?> operation, Map<String, Object> failureOutputs) {
        try {
            FileOutcome<?> outcome = operation.execute();
            FlowOperationResult<?> result = new FlowOperationResult<>(true, outcome.value(), "", "", outcome.details());
            return new Completion(result, outcome.outputs());
        } catch (FileOperationException exception) {
            FlowOperationResult<?> result = FlowOperationResult.failure(exception.code(), exception.getMessage(), Map.of());
            return new Completion(result, failureOutputs);
        } catch (IOException exception) {
            FlowOperationResult<?> result = FlowOperationResult.failure("FILE_IO_FAILED", message(exception, "File operation failed"), Map.of());
            return new Completion(result, failureOutputs);
        } catch (RuntimeException exception) {
            FlowOperationResult<?> result = FlowOperationResult.failure("FILE_OPERATION_FAILED", message(exception, "File operation failed"), Map.of());
            return new Completion(result, failureOutputs);
        }
    }

    private void complete(FlowContext context, FlowNode node, Completion completion) {
        completion.outputs().forEach((name, value) -> context.setOutput(node, name, value));
        FlowOperationResult<?> result = completion.result();
        context.setOutput(node, "result", result);
        context.setOutput(node, "success", result.success());
        context.setOutput(node, "error_code", result.errorCode());
        context.setOutput(node, "message", result.message());
        context.triggerOutput(result.success() ? "flow" : "failed");
    }

    private <T> FileOutcome<T> outcome(T value, Map<String, Object> outputs, Path path) {
        return new FileOutcome<>(value, outputs, Map.of("path", relativePath(path)));
    }

    Path resolveSafePath(String path) throws IOException, FileOperationException {
        if (path == null || path.isBlank()) {
            throw new FileOperationException("FILE_PATH_REQUIRED", "File path is required");
        }
        Path root = dataRoot();
        Path target;
        try {
            target = root.resolve(path).toAbsolutePath().normalize();
        } catch (RuntimeException exception) {
            throw new FileOperationException("FILE_PATH_INVALID", "File path is invalid");
        }
        if (!target.startsWith(root)) {
            throw new FileOperationException("FILE_PATH_OUTSIDE_DATA", "File path must stay inside the ReSync data folder");
        }
        Path existing = target;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null || !existing.toRealPath().startsWith(root.toRealPath())) {
            throw new FileOperationException("FILE_PATH_OUTSIDE_DATA", "File path must stay inside the ReSync data folder");
        }
        return target;
    }

    private Path requireFile(String path) throws IOException, FileOperationException {
        Path target = resolveSafePath(path);
        if (!Files.exists(target)) {
            throw new FileOperationException("FILE_NOT_FOUND", "File does not exist");
        }
        if (!Files.isRegularFile(target)) {
            throw new FileOperationException("FILE_NOT_REGULAR", "Path is not a regular file");
        }
        return target;
    }

    private Path requireDirectory(String path) throws IOException, FileOperationException {
        Path target = resolveSafePath(path);
        if (!Files.exists(target)) {
            throw new FileOperationException("DIRECTORY_NOT_FOUND", "Directory does not exist");
        }
        if (!Files.isDirectory(target)) {
            throw new FileOperationException("PATH_NOT_DIRECTORY", "Path is not a directory");
        }
        return target;
    }

    private void createParent(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private Path dataRoot() {
        return root;
    }

    private String relativePath(Path path) {
        return dataRoot().relativize(path).toString().replace('\\', '/');
    }

    private String message(Exception exception, String fallback) {
        return exception.getMessage() != null && !exception.getMessage().isBlank() ? exception.getMessage() : fallback;
    }

    @FunctionalInterface
    private interface FileOperation<T> {
        FileOutcome<T> execute() throws IOException, FileOperationException;
    }

    private record FileOutcome<T>(T value, Map<String, Object> outputs, Map<String, Object> details) {
        private FileOutcome {
            outputs = outputs != null ? Map.copyOf(outputs) : Map.of();
            details = details != null ? Map.copyOf(details) : Map.of();
        }
    }

    private record Completion(FlowOperationResult<?> result, Map<String, Object> outputs) {
    }

    static final class FileOperationException extends Exception {
        private final String code;

        private FileOperationException(String code, String message) {
            super(message);
            this.code = code;
        }

        private String code() {
            return code;
        }
    }
}
