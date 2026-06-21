package restudio.resync.modules;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.settings.PacketEventsSettings;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import restudio.resync.Log;
import restudio.resync.runtime.NpcService;
import restudio.resync.runtime.PlayerNpcPacketRuntime;
import restudio.resync.runtime.PlayerNpcRuntime;
import restudio.resync.runtime.RuntimeNotificationService;

public class PlayerNpcPacketModule implements Module {
    private static final ModuleMetadata METADATA = ModuleMetadata.of("playerNpcPackets", "PlayerNpcs");
    private PacketEventsAPI<Plugin> packetEvents;
    private PlayerNpcPacketRuntime runtime;
    private boolean ownsPacketEvents;

    @Override
    public ModuleMetadata getMetadata() {
        return METADATA;
    }

    @Override
    public void initialize(ModuleContext context) {
        RuntimeNotificationService notifications = context.getService(RuntimeNotificationService.class);
        if (notifications == null) {
            notifications = new RuntimeNotificationService(context);
            context.registerService(RuntimeNotificationService.class, notifications);
        }
        PacketEventsAPI<?> previous = PacketEvents.getAPI();
        try {
            PacketEventsAPI<?> existing = PacketEvents.getAPI();
            if (existing == null) {
                PacketEventsSettings settings = new PacketEventsSettings()
                    .checkForUpdates(false)
                    .bStats(false)
                    .debug(false)
                    .kickOnPacketException(false)
                    .kickIfTerminated(false);
                packetEvents = SpigotPacketEventsBuilder.buildNoCache(context.getPlugin(), settings);
                ownsPacketEvents = true;
                PacketEvents.setAPI(packetEvents);
                packetEvents.load();
                assertSupported(packetEvents);
                assertCompatibility();
                packetEvents.init();
                if (!packetEvents.isInitialized() || !context.getPlugin().isEnabled()) {
                    throw new IllegalStateException("PacketEvents did not initialize for this server version");
                }
            } else {
                packetEvents = cast(existing);
                if (!packetEvents.isInitialized()) {
                    throw new IllegalStateException("PacketEvents is not initialized");
                }
            }
            runtime = new PlayerNpcPacketRuntime(context.getPlugin(), packetEvents, notifications, (id, player, location, leftClick, shifting) -> {
                PlayerNpcRuntime runtimeService = context.getService(PlayerNpcRuntime.class);
                if (runtimeService == null || !runtimeService.isActive(id)) {
                    return;
                }
                NpcService npcService = context.getService(NpcService.class);
                if (npcService != null) {
                    npcService.packetInteract(id, player, location, leftClick, shifting);
                }
            });
            context.registerService(PlayerNpcRuntime.class, runtime);
            Log.info("Player NPC packet runtime enabled");
        } catch (Throwable error) {
            String message = "ReSync Player NPCs do not support this server version: " + clean(error);
            cleanupFailedInitialization(previous);
            context.registerService(PlayerNpcRuntime.class, PlayerNpcRuntime.disabled(message, notifications));
            notifications.broadcastError(message);
        }
    }

    @Override
    public void start(ModuleContext context) {
        if (runtime != null) {
            Bukkit.getPluginManager().registerEvents(runtime, context.getPlugin());
        }
    }

    @Override
    public void stop(ModuleContext context) {
        if (runtime != null) {
            HandlerList.unregisterAll(runtime);
            runtime.shutdown();
            runtime = null;
        }
        if (ownsPacketEvents && packetEvents != null) {
            try {
                packetEvents.terminate();
            } catch (RuntimeException ignored) {
            }
        }
        packetEvents = null;
        ownsPacketEvents = false;
    }

    @SuppressWarnings("unchecked")
    private PacketEventsAPI<Plugin> cast(PacketEventsAPI<?> api) {
        return (PacketEventsAPI<Plugin>) api;
    }

    private void cleanupFailedInitialization(PacketEventsAPI<?> previous) {
        if (ownsPacketEvents && packetEvents != null) {
            try {
                packetEvents.terminate();
            } catch (RuntimeException ignored) {
            }
        }
        if (previous == null && PacketEvents.getAPI() == packetEvents) {
            PacketEvents.setAPI(null);
        }
        if (previous == null) {
            SpigotPacketEventsBuilder.clearBuildCache();
        }
        packetEvents = null;
        runtime = null;
        ownsPacketEvents = false;
    }

    private void assertSupported(PacketEventsAPI<Plugin> api) {
        ServerVersion version = api.getServerManager().getVersion();
        if (isUnsupportedServerVersion(version)) {
            throw new IllegalStateException("PacketEvents does not support " + version.getReleaseName());
        }
    }

    private boolean isUnsupportedServerVersion(ServerVersion version) {
        return version == ServerVersion.ERROR || version.isNewerThan(maxSupportedServerVersion());
    }

    private ServerVersion maxSupportedServerVersion() {
        return ServerVersion.V_26_1_2;
    }

    private void assertCompatibility() {
        assertMinimumPluginVersion("ViaVersion", 4, 5);
        assertMinimumPluginVersion("ProtocolLib", 5, 0);
    }

    private void assertMinimumPluginVersion(String pluginName, int minimumMajor, int minimumMinor) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(pluginName);
        if (plugin == null) {
            return;
        }
        int[] version = version(plugin.getDescription().getVersion());
        if (version[0] < minimumMajor || version[0] == minimumMajor && version[1] < minimumMinor) {
            throw new IllegalStateException(pluginName + " " + plugin.getDescription().getVersion() + " is not supported by PacketEvents");
        }
    }

    private int[] version(String value) {
        String[] parts = value == null ? new String[0] : value.split("\\.", 3);
        return new int[] {part(parts, 0), part(parts, 1)};
    }

    private int part(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index].replaceAll("[^0-9].*$", ""));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String clean(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getClass().getSimpleName();
        }
        return message;
    }
}
