package restudio.resync.upgrade.flow;

import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.QuarantineRecord;
import restudio.resync.upgrade.adapter.OfflineUpgradeSnapshotAdapter;
import restudio.resync.upgrade.adapter.OfflineUpgradeSnapshotInput;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public interface ManagedFlowFileMigrationProvider {
    String id();

    Result produce(OfflineUpgradeSnapshotInput input, Collection<Graph> graphs,
                   Collection<String> quarantinedGraphPaths) throws IOException;

    default Result produce(OfflineUpgradeSnapshotInput input, Collection<Graph> graphs,
                           Collection<String> quarantinedGraphPaths, boolean eligibleSource)
        throws IOException {
        return produce(input, graphs, quarantinedGraphPaths);
    }

    record Graph(String path, String type, String id, byte[] bytes) {
        public Graph {
            path = MigrationPaths.requireRelative(path);
            if (path.isBlank()) {
                throw new IllegalArgumentException("Managed flow-file graph path is required");
            }
            type = requireIdentity(type, "type");
            id = requireIdentity(id, "id");
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        private static String requireIdentity(String value, String field) {
            if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.indexOf('\u0000') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0
                || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0) {
                throw new IllegalArgumentException("Managed flow-file graph " + field + " is invalid");
            }
            return value;
        }
    }

    record Result(Collection<? extends OfflineUpgradeSnapshotAdapter.FileTransform> files,
                  Collection<String> claimedPaths,
                  Collection<? extends QuarantineRecord> quarantines,
                  Map<String, ? extends Collection<String>> generatedContributors) {
        public Result {
            files = List.copyOf(files == null ? List.of() : files);
            claimedPaths = List.copyOf(claimedPaths == null ? List.of() : claimedPaths);
            quarantines = List.copyOf(quarantines == null ? List.of() : quarantines);
            generatedContributors = generatedContributors == null ? Map.of() : generatedContributors.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                    value -> Set.copyOf(value.getValue() == null ? List.of() : value.getValue())));
        }

        public Result(Collection<? extends OfflineUpgradeSnapshotAdapter.FileTransform> files,
                      Collection<String> claimedPaths,
                      Collection<? extends QuarantineRecord> quarantines) {
            this(files, claimedPaths, quarantines, Map.of());
        }

        public static Result empty() {
            return new Result(List.of(), List.of(), List.of(), Map.of());
        }
    }
}
