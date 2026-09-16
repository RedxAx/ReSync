package restudio.resync.world;

import restudio.resync.migration.MigrationPaths;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

public interface WorldExternalPersistenceAdapter {
    String id();

    default ExecutionContract executionContract() {
        return ExecutionContract.direct();
    }

    default void healthCheck(Collection<WorldExternalPersistenceCapability.WorldRoot> roots) throws IOException {
        throw new IOException("External World Health Check Is Not Supported By Adapter " + id());
    }

    default TransactionReceipt save(Collection<WorldExternalPersistenceCapability.WorldRoot> roots) throws IOException {
        throw unsupported("Save");
    }

    default TransactionReceipt save(Collection<WorldExternalPersistenceCapability.WorldRoot> roots,
                                   BehavioralProbe probe) throws IOException {
        return save(roots);
    }

    default TransactionReceipt quiesce(Collection<WorldExternalPersistenceCapability.WorldRoot> roots) throws IOException {
        throw unsupported("Quiesce");
    }

    default TransactionReceipt quiesce(Collection<WorldExternalPersistenceCapability.WorldRoot> roots,
                                       BehavioralProbe probe) throws IOException {
        return quiesce(roots);
    }

    default TransactionReceipt resume(Collection<WorldExternalPersistenceCapability.WorldRoot> roots) throws IOException {
        throw unsupported("Resume");
    }

    default TransactionReceipt resume(Collection<WorldExternalPersistenceCapability.WorldRoot> roots,
                                      BehavioralProbe probe) throws IOException {
        return resume(roots);
    }

    default TransactionReceipt coordinatorProbe(WorldExternalPersistenceCapability.WorldRoot root, Path marker) throws IOException {
        throw unsupported("Coordinator Probe");
    }

    default SnapshotArtifact snapshot(WorldExternalPersistenceCapability.WorldRoot root, Path destination, long generation)
        throws IOException {
        throw unsupported("Snapshot");
    }

    default SnapshotArtifact snapshot(WorldExternalPersistenceCapability.WorldRoot root, Path destination, long generation,
                                      BehavioralProbe probe) throws IOException {
        return snapshot(root, destination, generation);
    }

    default TransactionReceipt restore(WorldExternalPersistenceCapability.WorldRoot root,
                                       WorldExternalPersistenceCapability.ExternalWorldSnapshot snapshot) throws IOException {
        throw unsupported("Restore");
    }

    default TransactionReceipt restore(WorldExternalPersistenceCapability.WorldRoot root,
                                       WorldExternalPersistenceCapability.ExternalWorldSnapshot snapshot,
                                       BehavioralProbe probe) throws IOException {
        return restore(root, snapshot);
    }

    default TransactionReceipt rebind(Map<String, WorldExternalPersistenceCapability.WorldRoot> roots) throws IOException {
        throw unsupported("Rebind");
    }

    default TransactionReceipt rebind(Map<String, WorldExternalPersistenceCapability.WorldRoot> roots,
                                      BehavioralProbe probe) throws IOException {
        return rebind(roots);
    }

    default void rollback(TransactionReceipt receipt) throws IOException {
        throw new IOException("External World Transaction Rollback Is Not Supported By Adapter " + id());
    }

    class TransactionFailure extends IOException {
        private final TransactionReceipt receipt;

        public TransactionFailure(String message, TransactionReceipt receipt) {
            super(message);
            this.receipt = Objects.requireNonNull(receipt, "receipt");
        }

        public TransactionReceipt receipt() {
            return receipt;
        }
    }

    private IOException unsupported(String operation) {
        return new IOException("External World " + operation + " Is Not Supported By Adapter " + id());
    }

    record TransactionReceipt(String operation, String token) {
        public TransactionReceipt {
            operation = requireText(operation, "operation");
            token = requireText(token, "token");
        }

        private static String requireText(String value, String field) {
            if (value == null || value.isBlank() || !value.equals(value.trim())) {
                throw new IllegalArgumentException(field + " Must Be Exact And Non-Blank");
            }
            return value;
        }
    }

    record SnapshotArtifact(WorldExternalPersistenceCapability.SnapshotId snapshotId, Path path, String transactionToken) {
        public SnapshotArtifact {
            snapshotId = Objects.requireNonNull(snapshotId, "snapshotId");
            path = Objects.requireNonNull(path, "path");
            transactionToken = TransactionReceipt.requireText(transactionToken, "transactionToken");
        }
    }

    record BehavioralProbe(String operation, Map<String, Path> markers, Path artifact) {
        public BehavioralProbe {
            operation = TransactionReceipt.requireText(operation, "operation");
            Objects.requireNonNull(markers, "markers");
            Map<String, Path> normalized = new LinkedHashMap<>();
            for (Map.Entry<String, Path> entry : markers.entrySet()) {
                String worldName = TransactionReceipt.requireText(entry.getKey(), "worldName");
                normalized.put(worldName, MigrationPaths.requirePath(entry.getValue(), "marker"));
            }
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("behavioralProbe markers Must Not Be Empty");
            }
            markers = Map.copyOf(normalized);
            artifact = artifact == null ? null : MigrationPaths.requirePath(artifact, "artifact");
        }

        public Path marker(String worldName) {
            return markers.get(worldName);
        }
    }

    interface ExecutionContract {
        boolean requiresMainThread();

        boolean isMainThread();

        <T> T execute(CheckedOperation<T> operation) throws Exception;

        static ExecutionContract direct() {
            return new ExecutionContract() {
                @Override
                public boolean requiresMainThread() {
                    return false;
                }

                @Override
                public boolean isMainThread() {
                    return true;
                }

                @Override
                public <T> T execute(CheckedOperation<T> operation) throws Exception {
                    return operation.call();
                }
            };
        }

        static ExecutionContract paper(BooleanSupplier primaryThread, Executor mainThreadExecutor) {
            Objects.requireNonNull(primaryThread, "primaryThread");
            Objects.requireNonNull(mainThreadExecutor, "mainThreadExecutor");
            return new ExecutionContract() {
                @Override
                public boolean requiresMainThread() {
                    return true;
                }

                @Override
                public boolean isMainThread() {
                    return primaryThread.getAsBoolean();
                }

                @Override
                public <T> T execute(CheckedOperation<T> operation) throws Exception {
                    if (primaryThread.getAsBoolean()) {
                        return operation.call();
                    }
                    CompletableFuture<T> future = new CompletableFuture<>();
                    mainThreadExecutor.execute(() -> {
                        try {
                            future.complete(operation.call());
                        } catch (Throwable throwable) {
                            future.completeExceptionally(throwable);
                        }
                    });
                    try {
                        return future.get();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Paper Main Thread Execution Was Interrupted", exception);
                    } catch (ExecutionException exception) {
                        Throwable cause = exception.getCause();
                        if (cause instanceof Exception checked) {
                            throw checked;
                        }
                        if (cause instanceof Error error) {
                            throw error;
                        }
                        throw new IOException("Paper Main Thread Execution Failed", cause);
                    }
                }
            };
        }
    }

    @FunctionalInterface
    interface CheckedOperation<T> {
        T call() throws Exception;
    }
}
