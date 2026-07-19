package restudio.resync.flow.migration;

import java.util.List;

public record FlowMigrationReport(String migrationId, int scannedGraphs, int changedGraphs, int failedGraphs, int changedNodes,
                                  List<String> changedGraphIds, List<String> failedGraphIds) {
    public FlowMigrationReport {
        migrationId = migrationId != null ? migrationId : "";
        changedGraphIds = changedGraphIds != null ? List.copyOf(changedGraphIds) : List.of();
        failedGraphIds = failedGraphIds != null ? List.copyOf(failedGraphIds) : List.of();
    }

    public static FlowMigrationReport empty() {
        return new FlowMigrationReport("", 0, 0, 0, 0, List.of(), List.of());
    }
}
