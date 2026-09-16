package restudio.resync.server.coverage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.flow.PersistentVariableStore;
import restudio.resync.migration.MigrationException;
import restudio.resync.migration.PersistenceExternalInput;
import restudio.resync.migration.RebindablePersistenceParticipant;
import restudio.resync.world.WorldManagementPersistenceParticipant;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceWriterCoverageBoundaryTest {
    @TempDir
    Path temporary;

    @Test
    void inventoryCoversEveryKnownFamilyInStableOrder() {
        PersistenceWriterCoverageBoundary.Boundary boundary = PersistenceWriterCoverageBoundary.current();

        assertEquals(List.of(
            "resync.catalog-publication-receipts",
            "resync.configuration",
            "resync.custom-blocks",
            "resync.extensions",
            "resync.flow-variables",
            "resync.flow.files",
            "resync.install-identity",
            "resync.jobs.automation-tasks",
            "resync.migration-reports",
            "resync.network",
            "resync.player-dossiers",
            "resync.runtime.luckperms-backend",
            "resync.runtime.luckperms-operations",
            "resync.runtime.npc-entities",
            "resync.runtime.player-npcs",
            "resync.runtime.resource-mutations",
            "resync.structures",
            "resync.triggers",
            "resync.world-audit",
            "resync.world-management"
        ), boundary.families().stream().map(PersistenceWriterCoverageBoundary.Family::writerId).toList());
        assertEquals(20, boundary.families().size());
        assertEquals(List.of("resync.jobs.ephemeral"), boundary.provenEphemeralFamilies().stream()
            .map(PersistenceWriterCoverageBoundary.EphemeralFamily::owner).toList());
        assertEquals(List.of(
            "src/main/java/restudio/resync/flow/jobs/FlowJobRegistry.java",
            "src/main/java/restudio/resync/jobs/JobManager.java",
            "src/main/java/restudio/resync/modules/FlowJobModule.java",
            "ReSyncCore/src/main/java/restudio/resync/migration/EphemeralLifecycleParticipant.java",
            "ReSyncCore/src/main/java/restudio/resync/migration/ReSyncPersistenceCoordinator.java"
        ), boundary.provenEphemeralFamilies().getFirst().sourceEvidence());
    }

    @Test
    void resolvesOnlyTheExistingWorldManagementLifecycle() throws Exception {
        PersistenceWriterCoverageBoundary.Boundary boundary = PersistenceWriterCoverageBoundary.current();
        PersistenceWriterCoverageBoundary.Family worldManagement = boundary.families().stream()
            .filter(family -> family.writerId().equals("resync.world-management"))
            .findFirst()
            .orElseThrow();

        assertTrue(RebindablePersistenceParticipant.class.isAssignableFrom(WorldManagementPersistenceParticipant.class));
        assertEquals(PersistenceWriterCoverageBoundary.LifecycleCoverage.PROVEN, worldManagement.lifecycleCoverage());
        assertEquals("restudio.resync.world.WorldManagementPersistenceParticipant", worldManagement.participantType());
        assertEquals(List.of(
            PersistenceWriterCoverageBoundary.LifecycleStep.FLUSH,
            PersistenceWriterCoverageBoundary.LifecycleStep.QUIESCE,
            PersistenceWriterCoverageBoundary.LifecycleStep.RESUME,
            PersistenceWriterCoverageBoundary.LifecycleStep.REBIND,
            PersistenceWriterCoverageBoundary.LifecycleStep.HEALTH_CHECK
        ), worldManagement.verifiedSteps());
        assertEquals("wave22.world-management-participant-lifecycle", worldManagement.evidenceId());
        assertFalse(boundary.complete());
        assertThrows(MigrationException.class, boundary.lifecycleCoverage()::requireComplete);
    }

    @Test
    void recordsRuntimeLeavesWithoutAParentRuntimeClaim() {
        PersistenceWriterCoverageBoundary.Boundary boundary = PersistenceWriterCoverageBoundary.current();
        PersistenceWriterCoverageBoundary.Family automation = boundary.families().stream()
            .filter(family -> family.writerId().equals("resync.jobs.automation-tasks"))
            .findFirst()
            .orElseThrow();

        assertEquals("runtime/automation-tasks.json", automation.inventoryRelativePath());
        assertEquals(List.of(
            "src/main/java/restudio/resync/flow/automation/AutomationTaskPersistenceParticipant.java",
            "src/main/java/restudio/resync/flow/automation/AutomationTaskService.java",
            "src/main/java/restudio/resync/flow/automation/AutomationTaskStore.java",
            "src/test/java/restudio/resync/flow/automation/AutomationTaskPersistenceParticipantTest.java"
        ), automation.sourceEvidence());
        assertEquals(PersistenceWriterCoverageBoundary.LifecycleCoverage.PROVEN, automation.lifecycleCoverage());
        assertTrue(boundary.unresolvedFamilies().stream().anyMatch(family -> family.writerId().equals("resync.runtime.npc-entities")));
        assertFalse(boundary.families().stream().anyMatch(family -> family.writerId().equals("resync.runtime")));
    }

    @Test
    void recordsFlowVariablePrimaryPreviousAndQuarantineOwnership() {
        PersistenceWriterCoverageBoundary.Boundary boundary = PersistenceWriterCoverageBoundary.current();
        PersistenceWriterCoverageBoundary.Family variables = boundary.families().stream()
            .filter(family -> family.writerId().equals(PersistentVariableStore.OWNER))
            .findFirst()
            .orElseThrow();

        assertEquals(PersistentVariableStore.FILE_NAME, variables.inventoryRelativePath());
        assertEquals(List.of(
            PersistentVariableStore.FILE_NAME,
            PersistentVariableStore.FILE_NAME + ".previous",
            ".quarantine/journals"
        ), variables.observedRelativePaths());
        assertEquals(PersistenceWriterCoverageBoundary.LifecycleCoverage.PROVEN, variables.lifecycleCoverage());
        assertEquals("restudio.resync.flow.PersistentVariableStore", variables.participantType());
        assertEquals("wave64.flow-variable-participant-lifecycle", variables.evidenceId());
        assertEquals(List.of(
            "src/main/java/restudio/resync/flow/PersistentVariableStore.java",
            "src/main/java/restudio/resync/flow/automation/VariableService.java",
            "src/main/java/restudio/resync/flow/handler/generic/VariableScopeHandler.java",
            "src/main/java/restudio/resync/modules/FlowRuntimeModule.java",
            "src/main/java/restudio/resync/server/ReSyncServer.java",
            "src/test/java/restudio/resync/flow/PersistentVariableStoreTest.java"
        ), variables.sourceEvidence());
    }

    @Test
    void exposesEveryRemainingFamilyAsAPathBoundFailClosedWriter() {
        PersistenceWriterCoverageBoundary.Boundary boundary = PersistenceWriterCoverageBoundary.current();
        Path dataRoot = temporary.resolve("resync");

        assertEquals(List.of(
            "resync.configuration",
            "resync.extensions",
            "resync.network",
            "resync.runtime.luckperms-backend",
            "resync.runtime.npc-entities"
        ), boundary.uncoveredWriters(dataRoot).stream().map(writer -> writer.id()).toList());
        assertTrue(boundary.uncoveredWriters(dataRoot).stream()
            .allMatch(writer -> writer.root().startsWith(dataRoot.toAbsolutePath().normalize())));
        assertTrue(boundary.uncoveredWriters(dataRoot).stream().allMatch(writer -> !writer.reason().isBlank()));

        Map<String, Object> payload = boundary.payload(dataRoot);
        assertEquals(1, payload.get("schemaVersion"));
        assertEquals(20, payload.get("familyCount"));
        assertEquals(List.of("resync.jobs.ephemeral"), payload.get("ephemeralLifecycleFamilies"));
        assertEquals(List.of(
            "resync.catalog-publication-receipts",
            "resync.custom-blocks",
            "resync.flow-variables",
            "resync.flow.files",
            "resync.install-identity",
            "resync.jobs.automation-tasks",
            "resync.migration-reports",
            "resync.player-dossiers",
            "resync.runtime.luckperms-operations",
            "resync.runtime.player-npcs",
            "resync.runtime.resource-mutations",
            "resync.structures",
            "resync.triggers",
            "resync.world-audit",
            "resync.world-management"), payload.get("provenLifecycleFamilies"));
        assertEquals(boundary.uncoveredWriters(dataRoot).stream().map(writer -> writer.id()).toList(), payload.get("uncoveredWriterIds"));
        assertEquals(List.of("dataRoot/nodes", "resync.properties"), boundary.externalInputs(dataRoot).stream()
            .map(PersistenceExternalInput.Input::id).toList());
        assertEquals(2, ((List<?>) payload.get("externalInputs")).size());
        assertEquals(false, payload.get("complete"));
    }
}
