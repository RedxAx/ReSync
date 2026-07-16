package restudio.resync.network.paper.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.network.NetworkPayloads;
import restudio.resync.network.PlayerStateSnapshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkPlayerStateCodecTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsCompressedStateData() {
        NetworkPlayerStateData data = new NetworkPlayerStateData("1.21.10", 5000, "SURVIVAL", 18, 2, 17, 4, 1, 200, 10, 0, 0.5f, 4, 100, true, false, 0.1f, 0.2f, 3, List.of(new byte[]{1, 2}), List.of(), new byte[]{3}, new byte[0], List.of(), List.of(new NetworkPlayerStateData.Effect("minecraft:speed", 100, 1, false, true, true)), Map.of("minecraft:max_health", 20d), Map.of("minecraft:story/root", List.of("root")), Set.of("minecraft:bread"), List.of(new NetworkPlayerStateData.StatisticValue("JUMP", "NONE", "-", 3)), new byte[]{4}, Map.of("survival", new NetworkPlayerStateData.LocationValue("00000000-0000-0000-0000-000000000001", "world", 1, 2, 3, 4, 5)));
        NetworkPlayerStateCodec.Captured captured = new NetworkPlayerStateCodec.Captured(127, data);

        NetworkPlayerStateCodec.Captured decoded = NetworkPlayerStateCodec.decode(NetworkPlayerStateCodec.encode(captured));

        assertEquals(captured.families(), decoded.families());
        assertEquals(data.minecraftVersion(), decoded.data().minecraftVersion());
        assertEquals(data.gameMode(), decoded.data().gameMode());
        assertArrayEquals(data.inventory().getFirst(), decoded.data().inventory().getFirst());
        assertArrayEquals(data.offhand(), decoded.data().offhand());
        assertEquals(data.effects(), decoded.data().effects());
        assertEquals(data.attributes(), decoded.data().attributes());
        assertEquals(data.advancements(), decoded.data().advancements());
        assertEquals(data.recipes(), decoded.data().recipes());
        assertEquals(data.statistics(), decoded.data().statistics());
        assertArrayEquals(data.persistentData(), decoded.data().persistentData());
        assertEquals(data.locations(), decoded.data().locations());
    }

    @Test
    void loadsPresenceAndCustomRealmProfiles() throws Exception {
        assertFalse(NetworkPlayerStateConfig.load(temporaryDirectory).enabled());
        Files.writeString(temporaryDirectory.resolve("resync.properties"), "network.node-id=survival-one\nnetwork.transfer.profile=CUSTOM\nnetwork.transfer.realm=survival\nnetwork.transfer.family.inventory=true\nnetwork.transfer.family.vitals=true\n");

        NetworkPlayerStateConfig config = NetworkPlayerStateConfig.load(temporaryDirectory);

        assertTrue(config.enabled());
        assertEquals("survival/custom", config.family());
        assertTrue(config.inventory());
        assertTrue(config.vitals());
        assertFalse(config.effects());
    }

    @Test
    void loadsExtendedRealmFamiliesAndAllowlistedPersistentData() throws Exception {
        Files.writeString(temporaryDirectory.resolve("resync.properties"), "network.node-id=survival-one\nnetwork.transfer.profile=CUSTOM\nnetwork.transfer.realm=survival\nnetwork.transfer.family.advancements=true\nnetwork.transfer.family.recipes=true\nnetwork.transfer.family.statistics=true\nnetwork.transfer.family.persistent-data=true\nnetwork.transfer.location-policy=REALM_RETURN_POINT\nnetwork.transfer.persistent-data-namespaces=quests, cosmetics\n");

        NetworkPlayerStateConfig config = NetworkPlayerStateConfig.load(temporaryDirectory);

        assertTrue(config.advancements());
        assertTrue(config.recipes());
        assertTrue(config.statistics());
        assertTrue(config.persistentData());
        assertEquals(NetworkPlayerLocationPolicy.REALM_RETURN_POINT, config.locationPolicy());
        assertEquals(Set.of("quests", "cosmetics"), config.persistentDataNamespaces());
    }

    @Test
    void persistsAndRemovesDisconnectSnapshotOutboxEntries() {
        NetworkSnapshotOutbox outbox = new NetworkSnapshotOutbox(temporaryDirectory.resolve("outbox"));
        byte[] payload = new byte[]{1, 2, 3};
        PlayerStateSnapshot snapshot = new PlayerStateSnapshot("snapshot", "network", UUID.randomUUID(), 3, "survival/custom", payload, NetworkPayloads.sha256(payload), 1, 5000, "survival", 1000, false);

        outbox.save(snapshot);
        outbox.save(snapshot);

        assertEquals(List.of(snapshot), outbox.load());
        outbox.remove(snapshot.snapshotId());
        assertTrue(outbox.load().isEmpty());
    }
}
