package restudio.resync.flow.migration;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.contract.diagnostic.DurableDiagnosticReportStore;
import restudio.resync.flow.FlowStorage;
import restudio.resync.migration.MigrationActivationMarker;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.StorageSafety;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyRuntimeActivationGateTest {
    @TempDir
    Path temporary;

    @Test
    void runtimeGateBlocksWithoutVerifiedActivation() {
        LegacyRuntimeActivationGate gate = LegacyRuntimeActivationGate.runtime(temporary);

        assertFalse(gate.allowsLegacyRuntime());
        assertFalse(gate.allowsLegacyMigration());
        assertFalse(gate.allowsLegacyFallback());
        assertFalse(gate.isReplacementActivated());
        assertFalse(gate.isCompatibilityMode());
        assertTrue(gate.decision().code().startsWith("LEGACY_RUNTIME_"));
    }

    @Test
    void formatOneMarkerCannotActivateLegacyRuntime() throws Exception {
        Path marker = LegacyRuntimeActivationGate.markerPath(temporary);
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "format=1\nstate=COMMITTED\n");

        LegacyRuntimeActivationGate gate = LegacyRuntimeActivationGate.runtime(temporary);

        assertFalse(gate.isReplacementActivated());
        assertFalse(gate.allowsLegacyRuntime());
        assertTrue(gate.decision().code().equals("LEGACY_RUNTIME_ACTIVATION_MARKER_INVALID"));
    }

    @Test
    void committedOfflineActivationRetiresLegacyRuntimePaths() throws Exception {
        LegacyRuntimeActivationGate.writeMarker(temporary, markerValues());

        LegacyRuntimeActivationGate gate = LegacyRuntimeActivationGate.runtime(temporary);

        assertFalse(gate.allowsLegacyRuntime());
        assertFalse(gate.allowsLegacyMigration());
        assertFalse(gate.allowsLegacyFallback());
        assertFalse(gate.allowsLegacyAliases());
        assertTrue(gate.isReplacementActivated());
        assertFalse(gate.isCompatibilityMode());
        assertTrue(gate.decision().allowed());
    }

    @Test
    void activatedRuntimeRecordsBlockedLegacyOperation() throws Exception {
        LegacyRuntimeActivationGate.writeMarker(temporary, markerValues());

        LegacyRuntimeActivationGate gate = LegacyRuntimeActivationGate.runtime(temporary);
        gate.recordBlocked("legacy graph transform");

        assertTrue(Files.isRegularFile(temporary.resolve("assets/.quarantine/legacy-runtime/policy.txt")));
    }

    @Test
    void activatedRuntimeKeepsFlowStorageOutsideTheLegacyMigrationBoundary() throws Exception {
        LegacyRuntimeActivationGate.writeMarker(temporary, markerValues());
        Path assets = temporary.resolve("assets");
        Path marker = LegacyRuntimeActivationGate.markerPath(temporary);
        byte[] markerBytes = Files.readAllBytes(marker);
        AssetTransactionCoordinator.AdoptionEvidence evidence = new AssetTransactionCoordinator.AdoptionEvidence(
            assets.relativize(marker).toString().replace('\\', '/'), StorageSafety.sha256(markerBytes), markerBytes.length);
        AssetTransactionCoordinator.AdoptionInventory adoption = new AssetTransactionCoordinator.AdoptionInventory(
            "legacy-runtime-activation-gate-test", "{}", List.of(), List.of(), List.of(evidence));
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.adoptExisting(assets, new Gson(), adoption)) {
            FlowStorage storage = new FlowStorage(temporary.toFile(), LegacyRuntimeActivationGate.runtime(temporary), coordinator);

            assertFalse(storage.allowsLegacyMigration());
            assertFalse(Files.exists(temporary.resolve("assets/.quarantine/legacy-runtime/policy.txt")));
            storage.recordBlockedLegacyOperation("legacy flow graph migration");
            assertTrue(Files.isRegularFile(temporary.resolve("assets/.quarantine/legacy-runtime/policy.txt")));
        }
    }

    @Test
    void blockedLegacyOperationIsPersistedAsStructuredDiagnostic() {
        LegacyRuntimeActivationGate gate = LegacyRuntimeActivationGate.runtime(temporary);

        gate.recordBlocked("startup graph migration");

        DurableDiagnosticReportStore store = new DurableDiagnosticReportStore(temporary.resolve("diagnostics"));
        assertTrue(store.list().stream()
            .flatMap(report -> report.diagnostics().diagnostics().stream())
            .anyMatch(diagnostic -> "MIGRATION.RUNTIME_LEGACY_INPUT".equals(diagnostic.code())
                && "startup graph migration".equals(diagnostic.evidence().get("operation"))));
    }

    @Test
    void tamperedActivationMarkerFailsClosedAndRecordsQuarantine() throws Exception {
        LegacyRuntimeActivationGate.writeMarker(temporary, markerValues());
        Path marker = LegacyRuntimeActivationGate.markerPath(temporary);
        Files.writeString(marker, Files.readString(marker).replace("state=COMMITTED", "state=FAILED"));

        LegacyRuntimeActivationGate gate = LegacyRuntimeActivationGate.runtime(temporary);
        gate.recordBlocked("legacy graph migration");

        assertFalse(gate.allowsLegacyRuntime());
        assertTrue(Files.isRegularFile(temporary.resolve("assets/.quarantine/legacy-runtime/policy.txt")));
    }

    @Test
    void compatibilityModeIsExplicit() {
        LegacyRuntimeActivationGate gate = LegacyRuntimeActivationGate.compatibility(temporary);

        assertTrue(gate.allowsLegacyRuntime());
        assertTrue(gate.allowsLegacyMigration());
        assertTrue(gate.allowsLegacyFallback());
        assertTrue(gate.allowsLegacyAliases());
        assertFalse(gate.isReplacementActivated());
        assertTrue(gate.isCompatibilityMode());
        assertTrue(gate.decision().code().equals("LEGACY_COMPATIBILITY_EXPLICIT"));
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private MigrationActivationMarker.Values markerValues() {
        return new MigrationActivationMarker.Values(
            digest("source"), digest("plan"), digest("replacement"), digest("catalog"), digest("runtime"), 1,
            digest("readiness"), 1, true);
    }
}
