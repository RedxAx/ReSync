package restudio.resync.modules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.migration.PersistenceParticipantClassification;
import restudio.resync.migration.PersistenceRootReadiness;
import restudio.resync.worldgen.WorldGenGeneratedPersistenceParticipant;
import restudio.resync.worldgen.datapack.WorldGenInstalledDatapackCapability;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class WorldGenModuleDerivedScopeTest {
    @TempDir
    Path root;

    @Test
    void selectsTheExactPendingWorldGenDerivedScope() {
        String generated = WorldGenGeneratedPersistenceParticipant.OWNER;
        String installed = WorldGenInstalledDatapackCapability.OWNER;

        assertEquals(Set.of(), WorldGenModule.pendingWorldGenDerivedOwners(readiness(false, false)));
        assertEquals(Set.of(generated), WorldGenModule.pendingWorldGenDerivedOwners(readiness(true, false)));
        assertEquals(Set.of(installed), WorldGenModule.pendingWorldGenDerivedOwners(readiness(false, true)));
        assertEquals(Set.of(generated, installed), WorldGenModule.pendingWorldGenDerivedOwners(readiness(true, true)));
    }

    @Test
    void unrelatedPendingDerivedOwnerDoesNotBlockWorldGenRuntime() {
        PersistenceRootReadiness readiness = new PersistenceRootReadiness(List.of(
            owner(WorldGenGeneratedPersistenceParticipant.OWNER, false),
            owner(WorldGenInstalledDatapackCapability.OWNER, false),
            owner("resync.authority.bundle", true)));

        assertEquals(Set.of(), WorldGenModule.pendingWorldGenDerivedOwners(readiness));
        assertDoesNotThrow(() -> WorldGenModule.requireWorldGenDerivedStartupReady(readiness));
    }

    private PersistenceRootReadiness readiness(boolean generatedPending, boolean installedPending) {
        return new PersistenceRootReadiness(List.of(
            owner(WorldGenGeneratedPersistenceParticipant.OWNER, generatedPending),
            owner(WorldGenInstalledDatapackCapability.OWNER, installedPending)));
    }

    private PersistenceRootReadiness.Owner owner(String owner, boolean pending) {
        if (pending) {
            return PersistenceRootReadiness.Owner.unavailable(owner, root, false,
                PersistenceParticipantClassification.DERIVED_CACHE, "Pending Startup Activation");
        }
        return PersistenceRootReadiness.Owner.registered(owner, root, false,
            PersistenceParticipantClassification.DERIVED_CACHE);
    }
}
