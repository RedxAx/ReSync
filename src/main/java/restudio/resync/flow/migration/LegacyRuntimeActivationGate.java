package restudio.resync.flow.migration;

import restudio.resync.contract.canonical.CanonicalHash;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.contract.diagnostic.DiagnosticCodeCatalog;
import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.diagnostic.DiagnosticPhase;
import restudio.resync.flow.diagnostic.DiagnosticProvenance;
import restudio.resync.flow.diagnostic.DiagnosticRedaction;
import restudio.resync.flow.diagnostic.DiagnosticSourceKind;
import restudio.resync.flow.diagnostics.StructuredFlowDiagnosticReporter;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import restudio.resync.migration.MigrationActivationMarker;

public final class LegacyRuntimeActivationGate {
    public static final String MARKER_FILE = "assets/.migrations/replacement-activation.marker";
    private static final String QUARANTINE_FILE = "assets/.quarantine/legacy-runtime/policy.txt";
    private static final String DIAGNOSTIC_DIRECTORY = "diagnostics";
    private static final OwnerId OWNER = OwnerId.of("resync");
    private static final ContractRef<OperationId> MESSAGE_KEY = ContractRef.of(OWNER, OperationId.of("legacy-runtime-boundary"));

    public record Decision(boolean allowed, String code, String detail) {
        public Decision {
            code = requireText(code, "code");
            detail = requireText(detail, "detail");
        }
    }

    private final Path dataFolder;
    private final Decision decision;
    private final boolean compatibilityMode;

    private LegacyRuntimeActivationGate(Path dataFolder, Decision decision, boolean compatibilityMode) {
        this.dataFolder = requireDirectoryRoot(dataFolder);
        this.decision = Objects.requireNonNull(decision, "decision");
        this.compatibilityMode = compatibilityMode;
    }

    public static LegacyRuntimeActivationGate runtime(Path dataFolder) {
        Path root = requireDirectoryRoot(dataFolder);
        return new LegacyRuntimeActivationGate(root, verify(root), false);
    }

    public static LegacyRuntimeActivationGate runtime(File dataFolder) {
        return runtime(Objects.requireNonNull(dataFolder, "dataFolder").toPath());
    }

    public static LegacyRuntimeActivationGate compatibility(Path dataFolder) {
        Path root = requireDirectoryRoot(dataFolder);
        return new LegacyRuntimeActivationGate(root,
            new Decision(true, "LEGACY_COMPATIBILITY_EXPLICIT", "Legacy compatibility was explicitly requested"), true);
    }

    public static LegacyRuntimeActivationGate compatibility(File dataFolder) {
        return compatibility(Objects.requireNonNull(dataFolder, "dataFolder").toPath());
    }

    public static Path markerPath(Path dataFolder) {
        return MigrationActivationMarker.markerPath(requireDirectoryRoot(dataFolder));
    }

    public static void writeMarker(Path dataFolder, MigrationActivationMarker.Values values) throws IOException {
        MigrationActivationMarker.write(requireDirectoryRoot(dataFolder), values);
    }

    public Decision decision() {
        return decision;
    }

    public boolean isReplacementActivated() {
        return !compatibilityMode && decision.allowed();
    }

    public boolean isCompatibilityMode() {
        return compatibilityMode;
    }

    public boolean allowsLegacyRuntime() {
        return compatibilityMode;
    }

    public boolean allowsLegacyMigration() {
        return allowsLegacyRuntime();
    }

    public boolean allowsLegacyFallback() {
        return allowsLegacyRuntime();
    }

    public boolean allowsLegacyAliases() {
        return allowsLegacyRuntime();
    }

    public void recordBlocked(String operation) {
        if (allowsLegacyRuntime()) {
            return;
        }
        String action = operation == null || operation.isBlank() ? "legacy runtime operation" : operation.trim();
        Path quarantine = dataFolder.resolve(QUARANTINE_FILE).normalize();
        try {
            requireSafeParent(dataFolder, quarantine.getParent());
            Files.createDirectories(quarantine.getParent());
            String line = "code=" + decision.code() + "\n"
                + "detail=" + decision.detail() + "\n"
                + "operation=" + action.replace('\n', ' ') + "\n"
                + "marker=" + markerPath(dataFolder) + "\n";
            Files.writeString(quarantine, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException | RuntimeException ignored) {
        }
        persistStructuredDiagnostic(action);
    }

    private void persistStructuredDiagnostic(String operation) {
        try {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("operation", operation);
            evidence.put("decisionCode", decision.code());
            evidence.put("decisionDetail", decision.detail());
            evidence.put("marker", markerPath(dataFolder).toString());
            evidence.put("replacementActivated", isReplacementActivated());
            evidence.put("compatibilityMode", compatibilityMode);
            String sourceHash = CanonicalHash.sha256(JsonValue.fromJava(evidence));
            UUID correlationId = UUID.nameUUIDFromBytes((decision.code() + "|" + operation + "|" + sourceHash)
                .getBytes(StandardCharsets.UTF_8));
            DiagnosticCodeCatalog catalog = DiagnosticCodeCatalog.defaultCatalog();
            Diagnostic diagnostic = Diagnostic.builder(
                    "MIGRATION.RUNTIME_LEGACY_INPUT",
                    catalog.require("MIGRATION.RUNTIME_LEGACY_INPUT").severity(),
                    DiagnosticPhase.SYNTACTIC,
                    "root-marker")
                .catalog(catalog)
                .messageKey(MESSAGE_KEY)
                .evidence(evidence)
                .correlationId(correlationId)
                .provenance(new DiagnosticProvenance(OWNER, DiagnosticSourceKind.LOCAL, "legacy-runtime", sourceHash,
                    "replacement-boundary", "resync", null))
                .durable(true)
                .redaction(DiagnosticRedaction.PUBLIC)
                .build();
            new StructuredFlowDiagnosticReporter(dataFolder.resolve(DIAGNOSTIC_DIRECTORY), catalog)
                .reportDiagnostics(List.of(diagnostic));
        } catch (RuntimeException ignored) {
        }
    }

    private static Decision verify(Path root) {
        Path marker = markerPath(root);
        try {
            if (Files.isSymbolicLink(marker) || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
                return new Decision(false, "LEGACY_RUNTIME_UPGRADE_NOT_ACTIVATED", "A committed offline upgrade activation marker is required");
            }
            MigrationActivationMarker.read(root);
            return new Decision(true, "LEGACY_RUNTIME_UPGRADE_ACTIVATED", "A verified committed offline upgrade activation marker is present");
        } catch (IOException | RuntimeException exception) {
            return invalid();
        }
    }

    private static Decision invalid() {
        return new Decision(false, "LEGACY_RUNTIME_ACTIVATION_MARKER_INVALID", "The offline upgrade activation marker is missing, malformed, or failed verification");
    }

    private static Path requireDirectoryRoot(Path root) {
        Objects.requireNonNull(root, "dataFolder");
        Path normalized = root.toAbsolutePath().normalize();
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(normalized)) {
            throw new IllegalArgumentException("dataFolder Cannot Be A Symbolic Link");
        }
        return normalized;
    }

    private static void requireSafeParent(Path root, Path parent) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedParent = parent.toAbsolutePath().normalize();
        if (!normalizedParent.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("Marker Path Is Outside Data Folder");
        }
        Path current = normalizedRoot;
        while (current != null && normalizedParent.startsWith(current)) {
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("Marker Path Traverses A Symbolic Link");
            }
            if (current.equals(normalizedParent)) {
                break;
            }
            current = current.getParent();
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(field + " Is Invalid");
        }
        return value;
    }

}
