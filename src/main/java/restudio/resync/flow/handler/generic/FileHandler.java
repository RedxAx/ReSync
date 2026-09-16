package restudio.resync.flow.handler.generic;

import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowOperationResult;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class FileHandler implements NodeHandler {
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();
    private final ManagedFlowFileCapability capability;

    public FileHandler(ManagedFlowFileCapability capability) {
        this.capability = Objects.requireNonNull(capability, "capability");
        registerOperations();
    }

    private void registerOperations() {
        operations.put("file_write", (ctx, node) -> executeAsync(ctx, node, () -> {
            String target = capability.write(ctx.getInputValue(node, "path", String.class, ""),
                ctx.getInputValue(node, "content", String.class, ""));
            return outcome(true, Map.of(), target);
        }, Map.of()));
        operations.put("file_append", (ctx, node) -> executeAsync(ctx, node, () -> {
            String target = capability.append(ctx.getInputValue(node, "path", String.class, ""),
                ctx.getInputValue(node, "content", String.class, ""));
            return outcome(true, Map.of(), target);
        }, Map.of()));
        operations.put("file_read", (ctx, node) -> executeAsync(ctx, node, () -> {
            String path = ctx.getInputValue(node, "path", String.class, "");
            String target = capability.normalize(path);
            String content = capability.read(path);
            return outcome(content, Map.of("content", content), target);
        }, Map.of("content", "")));
        operations.put("file_read_lines", (ctx, node) -> executeAsync(ctx, node, () -> {
            String path = ctx.getInputValue(node, "path", String.class, "");
            String target = capability.normalize(path);
            List<String> lines = capability.readLines(path);
            return outcome(lines, Map.of("lines", lines), target);
        }, Map.of("lines", List.of())));
        operations.put("file_delete", (ctx, node) -> executeAsync(ctx, node, () -> {
            String path = ctx.getInputValue(node, "path", String.class, "");
            String target = capability.normalize(path);
            boolean exists = capability.exists(path);
            boolean preview = ctx.getInputValue(node, "preview", Boolean.class, false);
            if (!exists) {
                throw new ManagedFlowFileCapability.AccessException("FILE_NOT_FOUND", "File does not exist");
            }
            boolean deleted = !preview && capability.delete(path);
            return new FileOutcome<>(deleted, Map.of("preview", preview, "would_delete", exists, "deleted", deleted),
                Map.of("path", target, "preview", preview));
        }, Map.of("preview", false, "would_delete", false, "deleted", false)));
        operations.put("file_exists", (ctx, node) -> executeSync(ctx, node, () -> {
            String path = ctx.getInputValue(node, "path", String.class, "");
            String target = capability.normalize(path);
            boolean exists = capability.exists(path);
            return outcome(exists, Map.of("exists", exists), target);
        }, Map.of("exists", false)));
        operations.put("file_copy", (ctx, node) -> executeAsync(ctx, node, () -> {
            String sourcePath = ctx.getInputValue(node, "source_path", String.class, "");
            String destinationPath = ctx.getInputValue(node, "dest_path", String.class, "");
            String source = capability.normalize(sourcePath);
            String destination = capability.copy(sourcePath, destinationPath);
            return new FileOutcome<>(true, Map.of(), Map.of("source", source, "destination", destination));
        }, Map.of()));
        operations.put("file_move", (ctx, node) -> executeAsync(ctx, node, () -> {
            String sourcePath = ctx.getInputValue(node, "source_path", String.class, "");
            String destinationPath = ctx.getInputValue(node, "dest_path", String.class, "");
            String source = capability.normalize(sourcePath);
            String destination = capability.move(sourcePath, destinationPath);
            return new FileOutcome<>(true, Map.of(), Map.of("source", source, "destination", destination));
        }, Map.of()));
        operations.put("file_list_dir", (ctx, node) -> executeAsync(ctx, node, () -> {
            String path = ctx.getInputValue(node, "path", String.class, "");
            String directory = capability.normalize(path);
            List<String> files = capability.list(path);
            return outcome(files, Map.of("files", files), directory);
        }, Map.of("files", List.of())));
        operations.put("file_create_dir", (ctx, node) -> executeAsync(ctx, node, () -> {
            String path = ctx.getInputValue(node, "path", String.class, "");
            String directory = capability.normalize(path);
            boolean existed = capability.exists(path);
            capability.createDirectory(path);
            return new FileOutcome<>(true, Map.of("created", !existed),
                Map.of("path", directory, "created", !existed));
        }, Map.of("created", false)));
        operations.put("file_get_size", (ctx, node) -> executeSync(ctx, node, () -> {
            String path = ctx.getInputValue(node, "path", String.class, "");
            String target = capability.normalize(path);
            long size = capability.size(path);
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

    private void executeAsync(FlowContext context, FlowNode node, FileOperation<?> operation,
                              Map<String, Object> failureOutputs) {
        context.runAsync(() -> {
            Completion completion = perform(operation, failureOutputs);
            context.runSync(() -> complete(context, node, completion));
        });
    }

    private void executeSync(FlowContext context, FlowNode node, FileOperation<?> operation,
                             Map<String, Object> failureOutputs) {
        complete(context, node, perform(operation, failureOutputs));
    }

    private Completion perform(FileOperation<?> operation, Map<String, Object> failureOutputs) {
        try {
            FileOutcome<?> outcome = operation.execute();
            FlowOperationResult<?> result = new FlowOperationResult<>(true, outcome.value(), "", "", outcome.details());
            return new Completion(result, outcome.outputs());
        } catch (ManagedFlowFileCapability.AccessException exception) {
            FlowOperationResult<?> result = FlowOperationResult.failure(exception.code(), exception.getMessage(), Map.of());
            return new Completion(result, failureOutputs);
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

    private <T> FileOutcome<T> outcome(T value, Map<String, Object> outputs, String path) {
        return new FileOutcome<>(value, outputs, Map.of("path", path));
    }

    String resolveSafePath(String path) throws IOException, FileOperationException {
        try {
            return capability.normalize(path);
        } catch (ManagedFlowFileCapability.AccessException exception) {
            throw new FileOperationException(exception.code(), exception.getMessage(), exception);
        }
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

        private FileOperationException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        String code() {
            return code;
        }
    }
}
