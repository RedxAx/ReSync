package restudio.resync.migration;

import restudio.resync.contract.diagnostic.DiagnosticCodeCatalog;

import java.util.Set;

public final class ManagedFlowFileMigrationDiagnosticCode {
    public static final String GRAPH_INVALID = "MIGRATION.MANAGED_FLOW_FILE_GRAPH_INVALID";
    public static final String PARTICIPANT_OWNERSHIP = "MIGRATION.MANAGED_FLOW_FILE_PARTICIPANT_OWNERSHIP";
    public static final String PATH_AMBIGUOUS = "MIGRATION.MANAGED_FLOW_FILE_PATH_AMBIGUOUS";
    public static final String PATH_CONFLICT = "MIGRATION.MANAGED_FLOW_FILE_PATH_CONFLICT";
    public static final String PATH_CONNECTED = "MIGRATION.MANAGED_FLOW_FILE_PATH_CONNECTED";
    public static final String PATH_TEMPLATE = "MIGRATION.MANAGED_FLOW_FILE_PATH_TEMPLATE";
    public static final String PATH_UNSAFE = "MIGRATION.MANAGED_FLOW_FILE_PATH_UNSAFE";
    public static final String SOURCE_KIND = "MIGRATION.MANAGED_FLOW_FILE_SOURCE_KIND";
    public static final String SOURCE_MISSING = "MIGRATION.MANAGED_FLOW_FILE_SOURCE_MISSING";
    public static final String SOURCE_NOT_MANIFESTED = "MIGRATION.MANAGED_FLOW_FILE_SOURCE_NOT_MANIFESTED";
    public static final String SOURCE_SYMLINK = "MIGRATION.MANAGED_FLOW_FILE_SOURCE_SYMLINK";
    public static final String SOURCE_TAMPERED = "MIGRATION.MANAGED_FLOW_FILE_SOURCE_TAMPERED";
    public static final String SOURCE_UNSAFE = "MIGRATION.MANAGED_FLOW_FILE_SOURCE_UNSAFE";
    public static final String TARGET_TAMPERED = "MIGRATION.MANAGED_FLOW_FILE_TARGET_TAMPERED";

    public static final Set<String> ALL = Set.of(
        GRAPH_INVALID, PARTICIPANT_OWNERSHIP, PATH_AMBIGUOUS, PATH_CONFLICT, PATH_CONNECTED, PATH_TEMPLATE,
        PATH_UNSAFE, SOURCE_KIND, SOURCE_MISSING, SOURCE_NOT_MANIFESTED, SOURCE_SYMLINK, SOURCE_TAMPERED,
        SOURCE_UNSAFE, TARGET_TAMPERED);

    static {
        DiagnosticCodeCatalog catalog = DiagnosticCodeCatalog.defaultCatalog();
        ALL.forEach(catalog::require);
    }

    private ManagedFlowFileMigrationDiagnosticCode() {
    }
}
