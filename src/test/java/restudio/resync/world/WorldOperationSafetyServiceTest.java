package restudio.resync.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldOperationSafetyServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void confirmationBlocksWithoutTokenAndAllowsSingleUseToken() {
        WorldOperationSafetyService service = new WorldOperationSafetyService(tempDir.resolve("audit.json"), 100);
        WorldOperationResult result = service.requireConfirmation("deleteWorld", "world", "client", Map.of("worldName", "world"));
        String token = String.valueOf(result.getData().get("confirmationToken"));

        assertTrue(result.isRequiresConfirmation());
        assertFalse(service.consumeConfirmation("", "deleteWorld", "world", "client"));
        assertTrue(service.consumeConfirmation(token, "deleteWorld", "world", "client"));
        assertFalse(service.consumeConfirmation(token, "deleteWorld", "world", "client"));
    }

    @Test
    void externalPersistenceIsAlwaysBoundAndUnavailableByDefault() {
        WorldOperationSafetyService service = new WorldOperationSafetyService(tempDir.resolve("audit.json"), 100);

        assertFalse(service.externalPersistence().available());
        assertThrows(WorldExternalPersistenceCapability.OperationRejectedException.class,
            () -> service.acquireExternalMutation("deleteWorld", "world"));
        service.clearExternalPersistence();
        assertFalse(service.externalPersistence().available());
        assertEquals(WorldExternalPersistenceCapability.State.UNAVAILABLE, service.externalPersistence().state());
    }

    @Test
    void auditRecordsPersistSuccessAndFailure() {
        Path auditFile = tempDir.resolve("audit.json");
        WorldOperationSafetyService service = new WorldOperationSafetyService(auditFile, 100);
        WorldOperationAuditRecord success = service.begin("op1", "deleteWorld", "client", "world", Map.of());
        assertEquals(1, service.activeOperationCount());
        assertTrue(service.isOperationActive("op1"));
        WorldOperationResult successResult = WorldOperationResult.success("deleteWorld", "world", "Deleted");
        service.finish(success, successResult, null);
        assertEquals(0, service.activeOperationCount());
        assertFalse(service.isOperationActive("op1"));
        WorldOperationAuditRecord failure = service.begin("op2", "purgeWorld", "client", "world", Map.of());
        WorldOperationResult failureResult = WorldOperationResult.failure("purgeWorld", "world", "Failed");
        service.finish(failure, failureResult, new IllegalStateException("boom"));

        WorldOperationSafetyService reloaded = new WorldOperationSafetyService(auditFile, 100);

        assertEquals(2, reloaded.snapshot(10).size());
        assertNotNull(reloaded.snapshot(10).getFirst().getAuditId());
        assertEquals("boom", reloaded.snapshot(10).stream().filter(record -> "op2".equals(record.getOperationId())).findFirst().orElseThrow().getFailureReason());
    }
}
