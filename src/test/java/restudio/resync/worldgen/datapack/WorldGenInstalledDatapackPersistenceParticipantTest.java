package restudio.resync.worldgen.datapack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.migration.PersistenceOwnershipContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGenInstalledDatapackPersistenceParticipantTest {
    @TempDir
    Path temporary;

    @Test
    void exposesOneLifecycleCapabilityWithoutOwningExternalWorldFiles() throws IOException {
        Path scope = Files.createDirectory(temporary.resolve("scope"));
        Path worlds = Files.createDirectory(temporary.resolve("worlds"));
        WorldGenDatapackInstaller installer = new WorldGenDatapackInstaller(
            worlds,
            "26.2",
            ignored -> false,
            WorldGenDatapackInstaller.DirectoryDurability.noop());
        WorldGenInstalledDatapackPersistenceParticipant participant =
            new WorldGenInstalledDatapackPersistenceParticipant(scope, installer);

        assertSame(installer, participant.capability());
        assertEquals(WorldGenInstalledDatapackCapability.OWNER, participant.owner());
        assertFalse(participant.owns(worlds.resolve("survival").resolve("datapacks")));

        participant.healthCheck();
        Path orphan = orphanStage(worlds, "9".repeat(32));
        Path reboundScope = Files.createDirectory(temporary.resolve("rebound"));
        participant.quiesce();
        participant.rebind(reboundScope);
        participant.healthCheck();

        assertEquals(reboundScope.resolve("worldgen").resolve("installed-datapack-lifecycle").toAbsolutePath().normalize(), participant.root());
        assertTrue(Files.isDirectory(participant.root()));
        assertFalse(Files.exists(orphan));
        Path cachedOrphan = orphanStage(worlds, "a".repeat(32));
        participant.resume();
        participant.healthCheck();
        assertTrue(Files.isDirectory(cachedOrphan));
        participant.close();
        assertEquals(WorldGenInstalledDatapackCapability.State.CLOSED, installer.state());
    }

    @Test
    void ownershipIndexRemainsEmptyForTheNoOpRoot() throws IOException {
        Path scope = Files.createDirectory(temporary.resolve("ownership-scope"));
        Path worlds = Files.createDirectory(temporary.resolve("ownership-worlds"));
        WorldGenDatapackInstaller installer = new WorldGenDatapackInstaller(
            worlds,
            "26.2",
            ignored -> false,
            WorldGenDatapackInstaller.DirectoryDurability.noop());
        WorldGenInstalledDatapackPersistenceParticipant participant =
            new WorldGenInstalledDatapackPersistenceParticipant(scope, installer);
        var index = participant.ownershipIndex(new PersistenceOwnershipContext(scope, participant.root()));

        assertFalse(index.owns("worldgen/installed-datapack-lifecycle"));
        assertFalse(index.owns("worldgen/installed-datapack-lifecycle/child"));
        participant.close();
    }

    private Path orphanStage(Path worlds, String token) throws IOException {
        Path orphan = Files.createDirectories(worlds.resolve("survival").resolve("datapacks")
            .resolve(".resync-stage-" + token + "-resync_worldgen_pack"));
        Files.writeString(orphan.resolve("pack.mcmeta"), "{\"pack\":{}}");
        Files.writeString(orphan.resolve("partial.txt"), "partial");
        Files.writeString(orphan.resolve("resync-manifest.json"), "{\"owner\":\""
            + WorldGenInstalledDatapackCapability.OWNER
            + "\",\"projectId\":\"project\",\"packName\":\"resync_worldgen_pack\",\"revision\":1}");
        return orphan;
    }
}
