package restudio.resync.world;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import restudio.resync.selection.InteractiveSelectionManager;
import restudio.resync.selection.InteractiveSelectionSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class PortalLinkCreationSession implements InteractiveSelectionSession {
    private static final long TIMEOUT_MS = 180_000L;

    private final UUID playerId;
    private final WorldManagementService worldManagementService;
    private final String portalName;
    private final String sourceWorld;
    private final String targetWorld;
    private final long startedAt = System.currentTimeMillis();

    private BossBar bossBar;
    private Stage stage = Stage.SOURCE_FIRST;
    private SelectedBlock sourceFirst;
    private SelectedBlock sourceSecond;
    private SelectedBlock targetFirst;
    private SelectedBlock targetSecond;
    private float sourceYaw;
    private float sourcePitch;
    private float targetYaw;
    private float targetPitch;

    public PortalLinkCreationSession(UUID playerId, WorldManagementService worldManagementService, String sourceWorld, String targetWorld, String portalName) {
        this.playerId = playerId;
        this.worldManagementService = worldManagementService;
        this.sourceWorld = sourceWorld;
        this.targetWorld = targetWorld;
        this.portalName = portalName;
    }

    @Override
    public UUID getPlayerId() {
        return playerId;
    }

    @Override
    public void start(InteractiveSelectionManager manager, Player player) {
        bossBar = manager.createBossBar(player, "ReSync Portal Setup", BarColor.BLUE, 1.0);
        manager.playStepSound(player, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f);
        player.sendMessage(Component.text("[ReSync] ", NamedTextColor.DARK_GRAY).append(Component.text("Portal Create", NamedTextColor.AQUA)));
        player.sendMessage(Component.text("Source ", NamedTextColor.GRAY).append(Component.text(sourceWorld, NamedTextColor.WHITE)).append(Component.text(" Target ", NamedTextColor.GRAY)).append(Component.text(targetWorld, NamedTextColor.GOLD)));
        player.sendMessage(Component.text("Name ", NamedTextColor.GRAY).append(Component.text(portalName, NamedTextColor.WHITE)));
        pushStep(manager, player);
    }

    @Override
    public void handleBlockSelect(InteractiveSelectionManager manager, Player player, Block block) {
        if (block == null || block.getWorld() == null) {
            return;
        }
        String expectedWorld = currentWorld();
        if (!block.getWorld().getName().equalsIgnoreCase(expectedWorld)) {
            manager.playStepSound(player, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f);
            manager.sendAction(player, Component.text("Use " + expectedWorld, NamedTextColor.RED));
            return;
        }
        SelectedBlock selected = new SelectedBlock(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        switch (stage) {
            case SOURCE_FIRST -> {
                sourceFirst = selected;
                manager.playStepSound(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.1f);
                manager.sendAction(player, Component.text("Source 1 Set", NamedTextColor.AQUA));
                stage = Stage.SOURCE_SECOND;
                pushStep(manager, player);
            }
            case SOURCE_SECOND -> {
                sourceSecond = selected;
                sourceYaw = player.getLocation().getYaw();
                sourcePitch = player.getLocation().getPitch();
                manager.playStepSound(player, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.0f);
                WorldOperationResult teleport = worldManagementService.teleportPlayerToWorldSpawn(player.getName(), targetWorld);
                if (!teleport.isSuccess()) {
                    player.sendMessage(Component.text("Failed To Open Target World", NamedTextColor.RED));
                    manager.cancelSession(playerId, "TeleportFailed");
                    return;
                }
                stage = Stage.TARGET_FIRST;
                player.sendMessage(Component.text("Target World Ready ", NamedTextColor.GOLD).append(Component.text(targetWorld, NamedTextColor.WHITE)));
                pushStep(manager, player);
            }
            case TARGET_FIRST -> {
                targetFirst = selected;
                manager.playStepSound(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.3f);
                manager.sendAction(player, Component.text("Target 1 Set", NamedTextColor.GOLD));
                stage = Stage.TARGET_SECOND;
                pushStep(manager, player);
            }
            case TARGET_SECOND -> {
                targetSecond = selected;
                targetYaw = player.getLocation().getYaw();
                targetPitch = player.getLocation().getPitch();
                createLinkedPortals(manager, player);
            }
        }
    }

    @Override
    public void tick(InteractiveSelectionManager manager, Player player, long now) {
        if (now - startedAt >= TIMEOUT_MS) {
            manager.cancelSession(playerId, "TimedOut");
            return;
        }
        manager.updateBossBar(bossBar, bossBarTitle(), bossBarColor(), 1.0 - ((double) (now - startedAt) / TIMEOUT_MS));
        if (sourceFirst != null) {
            manager.pulseBlock(sourceFirst.toBlock(player), Color.AQUA);
        }
        if (sourceFirst != null && sourceSecond != null) {
            Bounds bounds = Bounds.of(sourceFirst, sourceSecond);
            manager.pulseBox(player, bounds.worldName(), bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ(), Color.AQUA);
        }
        if (targetFirst != null) {
            manager.pulseBlock(targetFirst.toBlock(player), Color.ORANGE);
        }
        if (targetFirst != null && targetSecond != null) {
            Bounds bounds = Bounds.of(targetFirst, targetSecond);
            manager.pulseBox(player, bounds.worldName(), bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ(), Color.ORANGE);
        }
        manager.sendAction(player, Component.text(actionText(), actionColor()));
    }

    @Override
    public void stop(InteractiveSelectionManager manager, Player player, String reason, boolean completed) {
        manager.removeBossBar(bossBar);
        if (player == null) {
            return;
        }
        if (completed) {
            manager.playStepSound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f);
            player.sendMessage(Component.text("[ReSync] ", NamedTextColor.DARK_GRAY).append(Component.text("Portal Link Ready", NamedTextColor.GREEN)));
            return;
        }
        if ("SelectionReplaced".equals(reason) || "PlayerLeft".equals(reason)) {
            return;
        }
        manager.playStepSound(player, Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f);
        player.sendMessage(Component.text(cancelText(reason), NamedTextColor.RED));
    }

    private void createLinkedPortals(InteractiveSelectionManager manager, Player player) {
        Bounds sourceBounds = Bounds.of(sourceFirst, sourceSecond);
        Bounds targetBounds = Bounds.of(targetFirst, targetSecond);
        PortalDestination sourceDestination = PortalDestination.from(targetBounds, targetYaw, targetPitch);
        PortalDestination targetDestination = PortalDestination.from(sourceBounds, sourceYaw, sourcePitch);

        String forwardName = uniqueName(portalName + "To" + compactWorld(targetWorld));
        String reverseName = uniqueName(portalName + "To" + compactWorld(sourceWorld));

        WorldPortal forward = createPortal(forwardName, sourceBounds, sourceDestination);
        WorldPortal reverse = createPortal(reverseName, targetBounds, targetDestination);

        WorldOperationResult forwardResult = worldManagementService.createPortal(forward);
        if (!forwardResult.isSuccess()) {
            player.sendMessage(Component.text("Failed To Create Forward Link", NamedTextColor.RED));
            manager.cancelSession(playerId, "CreateFailed");
            return;
        }

        WorldOperationResult reverseResult = worldManagementService.createPortal(reverse);
        if (!reverseResult.isSuccess()) {
            Object portalData = forwardResult.getData().get("portal");
            if (portalData instanceof WorldPortal portal && portal.getPortalId() != null) {
                worldManagementService.deletePortal(portal.getPortalId());
            }
            player.sendMessage(Component.text("Failed To Create Return Link", NamedTextColor.RED));
            manager.cancelSession(playerId, "CreateFailed");
            return;
        }

        player.sendMessage(Component.text("Forward ", NamedTextColor.GRAY).append(Component.text(forwardName, NamedTextColor.AQUA)));
        player.sendMessage(Component.text("Return ", NamedTextColor.GRAY).append(Component.text(reverseName, NamedTextColor.GOLD)));
        manager.completeSession(playerId, "Created");
    }

    private WorldPortal createPortal(String name, Bounds sourceBounds, PortalDestination destination) {
        WorldPortal portal = new WorldPortal();
        portal.setPortalName(name);
        portal.setSourceWorld(sourceBounds.worldName());
        portal.setMinX(sourceBounds.minX());
        portal.setMinY(sourceBounds.minY());
        portal.setMinZ(sourceBounds.minZ());
        portal.setMaxX(sourceBounds.maxX());
        portal.setMaxY(sourceBounds.maxY());
        portal.setMaxZ(sourceBounds.maxZ());
        portal.expandBlockBounds();
        portal.setDestinationWorld(destination.worldName());
        portal.setDestinationX(destination.x());
        portal.setDestinationY(destination.y());
        portal.setDestinationZ(destination.z());
        portal.setDestinationYaw(destination.yaw());
        portal.setDestinationPitch(destination.pitch());
        portal.setEnabled(true);
        return portal;
    }

    private void pushStep(InteractiveSelectionManager manager, Player player) {
        manager.playStepSound(player, Sound.UI_BUTTON_CLICK, 1.0f);
        player.sendMessage(Component.text("[ReSync] ", NamedTextColor.DARK_GRAY).append(Component.text(actionText(), actionColor())));
    }

    private String uniqueName(String base) {
        List<String> used = new ArrayList<>();
        for (WorldPortal portal : worldManagementService.getPortals()) {
            if (portal != null && portal.getPortalName() != null) {
                used.add(portal.getPortalName().toLowerCase(Locale.ROOT));
            }
        }
        String candidate = base;
        int index = 2;
        while (used.contains(candidate.toLowerCase(Locale.ROOT))) {
            candidate = base + index;
            index++;
        }
        return candidate;
    }

    private String compactWorld(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return "World";
        }
        String text = worldName.replaceAll("[^A-Za-z0-9]+", " ").trim();
        String[] parts = text.split("\\s+");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            builder.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }
        return builder.isEmpty() ? "World" : builder.toString();
    }

    private String currentWorld() {
        return switch (stage) {
            case SOURCE_FIRST, SOURCE_SECOND -> sourceWorld;
            case TARGET_FIRST, TARGET_SECOND -> targetWorld;
        };
    }

    private String bossBarTitle() {
        return switch (stage) {
            case SOURCE_FIRST -> "Select Source 1";
            case SOURCE_SECOND -> "Select Source 2";
            case TARGET_FIRST -> "Select Target 1";
            case TARGET_SECOND -> "Select Target 2";
        };
    }

    private BarColor bossBarColor() {
        return switch (stage) {
            case SOURCE_FIRST, SOURCE_SECOND -> BarColor.BLUE;
            case TARGET_FIRST, TARGET_SECOND -> BarColor.YELLOW;
        };
    }

    private String actionText() {
        return switch (stage) {
            case SOURCE_FIRST -> "Break Source 1";
            case SOURCE_SECOND -> "Break Source 2";
            case TARGET_FIRST -> "Break Target 1";
            case TARGET_SECOND -> "Break Target 2";
        };
    }

    private NamedTextColor actionColor() {
        return switch (stage) {
            case SOURCE_FIRST, SOURCE_SECOND -> NamedTextColor.AQUA;
            case TARGET_FIRST, TARGET_SECOND -> NamedTextColor.GOLD;
        };
    }

    private String cancelText(String reason) {
        return switch (reason) {
            case "TimedOut" -> "Portal Link Timed Out";
            case "TeleportFailed" -> "Target World Failed";
            case "CreateFailed" -> "Portal Link Failed";
            default -> "Portal Link Cancelled";
        };
    }

    private enum Stage {
        SOURCE_FIRST,
        SOURCE_SECOND,
        TARGET_FIRST,
        TARGET_SECOND
    }

    private record SelectedBlock(String worldName, int x, int y, int z) {
        private Block toBlock(Player player) {
            if (player == null || player.getServer().getWorld(worldName) == null) {
                return null;
            }
            return player.getServer().getWorld(worldName).getBlockAt(x, y, z);
        }
    }

    private record Bounds(String worldName, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        private static Bounds of(SelectedBlock first, SelectedBlock second) {
            return new Bounds(
                first.worldName(),
                Math.min(first.x(), second.x()),
                Math.min(first.y(), second.y()),
                Math.min(first.z(), second.z()),
                Math.max(first.x(), second.x()),
                Math.max(first.y(), second.y()),
                Math.max(first.z(), second.z())
            );
        }
    }

    private record PortalDestination(String worldName, double x, double y, double z, float yaw, float pitch) {
        private static PortalDestination from(Bounds bounds, float yaw, float pitch) {
            return new PortalDestination(
                bounds.worldName(),
                (bounds.minX() + bounds.maxX() + 1.0) / 2.0,
                bounds.minY() + 1.0,
                (bounds.minZ() + bounds.maxZ() + 1.0) / 2.0,
                yaw,
                pitch
            );
        }
    }
}
