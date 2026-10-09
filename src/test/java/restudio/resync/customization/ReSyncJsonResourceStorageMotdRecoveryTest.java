package restudio.resync.customization;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncJsonResourceStorageMotdRecoveryTest {
    private static final String SERVER_ID = "e65887a4-ea27-4c55-bae2-e1c8d92da433";
    private final List<AssetTransactionCoordinator> coordinators = new ArrayList<>();
    private ReSyncJsonResourceStorage storage;

    @AfterEach
    void tearDown() throws Exception {
        if (storage != null) {
            storage.closePersistence();
        }
        for (AssetTransactionCoordinator coordinator : coordinators.reversed()) {
            coordinator.close();
        }
        MockBukkit.unmock();
    }

    @Test
    void normalizesRequestedMotdIconForRecoveryWithoutMutatingCaller() throws Exception {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        storage = storage(plugin);
        byte[] png = pngIcon();
        String hash = sha256(png);
        String encoded = Base64.getEncoder().encodeToString(png);

        JsonObject previous = new JsonObject();
        previous.addProperty("id", "motd");
        previous.addProperty("line1", "Before");
        JsonObject requested = new JsonObject();
        requested.addProperty("id", "motd");
        requested.addProperty("line1", "After");
        requested.addProperty("icon", "motd-icons/" + hash + ".png");
        requested.addProperty("iconData", encoded);
        requested.addProperty("iconHash", hash);
        JsonObject requestedBefore = requested.deepCopy();
        JsonObject actual = requested.deepCopy();
        actual.addProperty("icon", "assets/motd-icons/" + hash + ".png");

        assertTrue(storage.matchesCommittedPayloadRecovery(ReSyncResourceCatalog.MOTD_PROFILE, previous, requested, actual));
        assertEquals(requestedBefore, requested);
    }

    @Test
    void malformedMotdIconRecoveryFailsWithoutMutationOrAssetWrite() throws Exception {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        storage = storage(plugin);
        JsonObject previous = new JsonObject();
        previous.addProperty("id", "motd");
        JsonObject requested = new JsonObject();
        requested.addProperty("id", "motd");
        requested.addProperty("icon", "motd-icons/malformed.png");
        requested.addProperty("iconData", "%%%malformed%%%");
        JsonObject requestedBefore = requested.deepCopy();
        Path iconPath = storage.getAssetsPath().resolve("motd-icons/malformed.png");

        assertFalse(storage.matchesCommittedPayloadRecovery(ReSyncResourceCatalog.MOTD_PROFILE, previous, requested,
            requested.deepCopy()));
        assertEquals(requestedBefore, requested);
        assertFalse(Files.exists(iconPath));
        BufferedImage wide = new BufferedImage(128, 64, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(wide, "png", output);
        requested.addProperty("iconData", Base64.getEncoder().encodeToString(output.toByteArray()));
        requestedBefore = requested.deepCopy();
        assertFalse(storage.matchesCommittedPayloadRecovery(ReSyncResourceCatalog.MOTD_PROFILE, previous, requested,
            requested.deepCopy()));
        assertEquals(requestedBefore, requested);
        assertFalse(Files.exists(iconPath));
    }

    @Test
    void iconHydrationRejectsColdTamperingAndTracksCommittedBlobChanges() throws Exception {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        storage = storage(plugin);
        AssetTransactionCoordinator coordinator = coordinators.getLast();
        String relative = "motd-icons/main.png";
        Path icon = storage.getAssetsPath().resolve(relative);
        AssetTransactionCoordinator.AssetKey key = new AssetTransactionCoordinator.AssetKey("blob",
            sha256(relative.getBytes(StandardCharsets.UTF_8)));
        byte[] original = pngIcon();
        BufferedImage changed = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        changed.setRGB(0, 0, 0xffabcdef);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(changed, "png", output);
        byte[] replacement = output.toByteArray();
        AssetTransactionCoordinator.Snapshot snapshot = coordinator.read(Function.identity());
        coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(UUID.randomUUID(), snapshot.project(),
            List.of(AssetTransactionCoordinator.AssetDelta.write(key, icon, AssetTransactionCoordinator.Missing.INSTANCE, original)),
            List.of()));
        JsonObject profile = new JsonObject();
        profile.addProperty("id", "motd");
        profile.addProperty("icon", "assets/" + relative);
        storage.save(ReSyncResourceCatalog.MOTD_PROFILE, profile);
        try {
            Files.write(icon, replacement);
            assertFalse(storage.get(ReSyncResourceCatalog.MOTD_PROFILE, "motd").has("iconData"));
            try (var channel = Files.newByteChannel(icon, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                channel.position(1024L * 1024);
                channel.write(ByteBuffer.wrap(new byte[]{0}));
            }
            assertFalse(storage.get(ReSyncResourceCatalog.MOTD_PROFILE, "motd").has("iconData"));
            Files.write(icon, original);
            assertEquals(Base64.getEncoder().encodeToString(original),
                storage.get(ReSyncResourceCatalog.MOTD_PROFILE, "motd").get("iconData").getAsString());
            Files.write(icon, replacement);
            assertEquals(Base64.getEncoder().encodeToString(original),
                storage.get(ReSyncResourceCatalog.MOTD_PROFILE, "motd").get("iconData").getAsString());
        } finally {
            Files.write(icon, original);
        }
        snapshot = coordinator.read(Function.identity());
        coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(UUID.randomUUID(), snapshot.project(),
            List.of(AssetTransactionCoordinator.AssetDelta.write(key, icon, snapshot.state(key).orElseThrow(), replacement)),
            List.of()));
        assertEquals(Base64.getEncoder().encodeToString(replacement),
            storage.get(ReSyncResourceCatalog.MOTD_PROFILE, "motd").get("iconData").getAsString());
    }

    private ReSyncJsonResourceStorage storage(JavaPlugin plugin) throws Exception {
        Path scope = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        AssetTransactionCoordinator coordinator = coordinator(scope);
        AssetTransactionCoordinator.Snapshot snapshot = coordinator.read(Function.identity());
        coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(UUID.randomUUID(), snapshot.project(), List.of(),
            List.of(AssetTransactionCoordinator.ProjectDelta.set(List.of("serverId"), new Gson().toJsonTree(SERVER_ID)))));
        return new ReSyncJsonResourceStorage(plugin, LegacyRuntimeActivationGate.runtime(scope),
            new AssetPersistenceGate(scope), coordinator);
    }

    private AssetTransactionCoordinator coordinator(Path scope) throws Exception {
        Path assets = scope.resolve("assets").toAbsolutePath().normalize();
        Files.createDirectories(assets);
        AssetTransactionCoordinator coordinator = new AssetTransactionCoordinator(assets, new Gson());
        coordinators.add(coordinator);
        return coordinator;
    }

    private static byte[] pngIcon() throws Exception {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xff336699);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }
}
