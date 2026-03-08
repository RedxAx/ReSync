package restudio.resync.world;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import restudio.resync.ReSync;
import restudio.resync.player.PlayerTrackingService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class WorldManagementManager implements WorldManagementService, Listener {
    private static final String MODULE_ID = "worldManagement";
    private static final String GLOBAL_STATE_KEY = "__global__";
    private static final String WORLD_LOCATION_FACET = "worldLocation";
    private static final long PORTAL_COOLDOWN_MS = 1500L;
    private static final long FACET_UPDATE_INTERVAL_MS = 500L;
    private final ReSync plugin;
    private final PlayerTrackingService trackingService;
    private final WorldStateStorage storage;
    private final WorldMapService mapService;
    private final Map<String, WorldRegistryEntry> worlds = new ConcurrentHashMap<>();
    private final Map<String, WorldPortal> portals = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, WorldPlayerState>> playerStates = new ConcurrentHashMap<>();
    private final List<WorldManagementListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<UUID, Long> portalCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> facetUpdates = new ConcurrentHashMap<>();
    private volatile Map<String, List<WorldPortal>> portalIndex = Map.of();
    private volatile BukkitTask lockTask;

    public WorldManagementManager(ReSync plugin, PlayerTrackingService trackingService) {
        this.plugin = plugin;
        this.trackingService = trackingService;
        this.storage = new WorldStateStorage(plugin);
        this.mapService = new DefaultWorldMapService();
        for (WorldRegistryEntry entry : storage.loadWorlds()) {
            if (entry == null || entry.getWorldName() == null || entry.getWorldName().isBlank()) {
                continue;
            }
            worlds.put(worldKey(entry.getWorldName()), entry.copy());
        }
        for (WorldPortal portal : storage.loadPortals()) {
            if (portal == null || portal.getPortalId() == null || portal.getPortalId().isBlank()) {
                continue;
            }
            portal.normalizeBounds();
            portals.put(portal.getPortalId(), portal.copy());
        }
        for (Map.Entry<UUID, Map<String, WorldPlayerState>> entry : storage.loadPlayerStates().entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            Map<String, WorldPlayerState> perWorld = new ConcurrentHashMap<>();
            if (entry.getValue() != null) {
                for (Map.Entry<String, WorldPlayerState> stateEntry : entry.getValue().entrySet()) {
                    if (stateEntry.getKey() == null || stateEntry.getKey().isBlank() || stateEntry.getValue() == null) {
                        continue;
                    }
                    perWorld.put(stateEntry.getKey(), stateEntry.getValue().copy());
                }
            }
            playerStates.put(entry.getKey(), perWorld);
        }
        rebuildPortalIndex();
        mapService.registerExtension(new WorldPortalMapExtension(this));
        mapService.registerExtension(new WorldPlayersMapExtension(trackingService));
    }

    @Override
    public WorldMapService getMapService() {
        return mapService;
    }

    @Override
    public void addListener(WorldManagementListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public void removeListener(WorldManagementListener listener) {
        listeners.remove(listener);
    }

    @Override
    public WorldSnapshot createSnapshot() {
        return callSync(this::createSnapshotSync);
    }

    @Override
    public List<WorldGameRuleDescriptor> getGameRuleDescriptors() {
        return callSync(this::createGameRuleDescriptors);
    }

    @Override
    public List<WorldPortal> getPortals() {
        List<WorldPortal> output = new ArrayList<>();
        for (WorldPortal portal : portals.values()) {
            output.add(portal.copy());
        }
        output.sort(Comparator.comparing(WorldPortal::getPortalName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return output;
    }

    @Override
    public List<WorldPortal> getPortalsByWorld(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return List.of();
        }
        List<WorldPortal> source = portalIndex.get(worldKey(worldName));
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<WorldPortal> output = new ArrayList<>();
        for (WorldPortal portal : source) {
            output.add(portal.copy());
        }
        output.sort(Comparator.comparing(WorldPortal::getPortalName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return output;
    }

    @Override
    public WorldPortal getPortal(String portalIdOrName) {
        return callSync(() -> {
            PortalLookupResult lookup = findPortal(portalIdOrName);
            return lookup.portal() == null ? null : lookup.portal().copy();
        });
    }

    @Override
    public WorldOperationResult createWorld(String worldName, String seed, String environment, String generator) {
        return callSync(() -> createWorldSync(worldName, seed, environment, generator));
    }

    @Override
    public WorldOperationResult importUnregisteredWorlds() {
        return callSync(this::importUnregisteredWorldsSync);
    }

    @Override
    public WorldOperationResult scanUnregisteredWorlds() {
        return callSync(this::scanUnregisteredWorldsSync);
    }

    @Override
    public WorldOperationResult cloneWorldAsync(String sourceWorld, String targetWorld, boolean loadAfterClone) {
        return callSync(() -> cloneWorldAsyncSync(sourceWorld, targetWorld, loadAfterClone));
    }

    @Override
    public WorldOperationResult deleteWorld(String worldName, boolean deleteFiles, String fallbackWorld) {
        return callSync(() -> deleteWorldSync(worldName, deleteFiles, fallbackWorld));
    }

    @Override
    public WorldOperationResult loadWorld(String worldName) {
        return callSync(() -> loadWorldSync(worldName));
    }

    @Override
    public WorldOperationResult unloadWorld(String worldName, String fallbackWorld) {
        return callSync(() -> unloadWorldSync(worldName, fallbackWorld));
    }

    @Override
    public WorldOperationResult setGameRule(String worldName, String ruleName, String value) {
        return callSync(() -> setGameRuleSync(worldName, ruleName, value));
    }

    @Override
    public WorldOperationResult setGameRules(String worldName, Map<String, String> rules) {
        return callSync(() -> setGameRulesSync(worldName, rules));
    }

    @Override
    public WorldOperationResult setDifficulty(String worldName, String difficulty) {
        return callSync(() -> setDifficultySync(worldName, difficulty));
    }

    @Override
    public WorldOperationResult setTimeLock(String worldName, boolean enabled, long lockedTime) {
        return callSync(() -> setTimeLockSync(worldName, enabled, lockedTime));
    }

    @Override
    public WorldOperationResult setWeatherLock(String worldName, boolean enabled, boolean storm, boolean thundering) {
        return callSync(() -> setWeatherLockSync(worldName, enabled, storm, thundering));
    }

    @Override
    public WorldOperationResult setIsolatedPlayerState(String worldName, boolean enabled) {
        return callSync(() -> setIsolatedPlayerStateSync(worldName, enabled));
    }

    @Override
    public WorldOperationResult createPortal(WorldPortal portal) {
        return callSync(() -> createPortalSync(portal));
    }

    @Override
    public WorldOperationResult resizePortal(WorldPortal portal) {
        return callSync(() -> resizePortalSync(portal));
    }

    @Override
    public WorldOperationResult deletePortal(String portalId) {
        return callSync(() -> deletePortalSync(portalId));
    }

    @Override
    public WorldOperationResult setPortalEnabled(String portalIdOrName, boolean enabled) {
        return callSync(() -> setPortalEnabledSync(portalIdOrName, enabled));
    }

    @Override
    public WorldOperationResult setPortalDestination(String portalIdOrName, String destinationWorld, double destinationX, double destinationY, double destinationZ,
                                                     float destinationYaw, float destinationPitch) {
        return callSync(() -> setPortalDestinationSync(portalIdOrName, destinationWorld, destinationX, destinationY, destinationZ, destinationYaw, destinationPitch));
    }

    @Override
    public WorldOperationResult setPortalBounds(String portalIdOrName, String sourceWorld, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return callSync(() -> setPortalBoundsSync(portalIdOrName, sourceWorld, minX, minY, minZ, maxX, maxY, maxZ));
    }

    @Override
    public WorldOperationResult teleportPlayerToWorld(String playerName, String worldName, Double x, Double y, Double z, Float yaw, Float pitch) {
        return callSync(() -> teleportPlayerToWorldSync(playerName, worldName, x, y, z, yaw, pitch));
    }

    @Override
    public WorldOperationResult teleportPlayerToWorldSpawn(String playerName, String worldName) {
        return callSync(() -> teleportPlayerToWorldSpawnSync(playerName, worldName));
    }

    @Override
    public WorldOperationResult teleportPlayerToPortal(String playerName, String portalIdOrName) {
        return callSync(() -> teleportPlayerToPortalSync(playerName, portalIdOrName));
    }

    @Override
    public void start() {
        callSync(() -> {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            bootstrapLoadedWorlds();
            if (lockTask != null) {
                lockTask.cancel();
            }
            lockTask = Bukkit.getScheduler().runTaskTimer(plugin, this::applyLocksTick, 20L, 20L);
            publishSnapshotEvent();
            return null;
        });
    }

    @Override
    public void stop() {
        callSync(() -> {
            HandlerList.unregisterAll(this);
            if (lockTask != null) {
                lockTask.cancel();
                lockTask = null;
            }
            persistAll();
            return null;
        });
    }

    @Override
    public void tick() {
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        World world = event.getWorld();
        WorldRegistryEntry entry = getOrCreateEntry(world.getName());
        syncEntryFromWorld(entry, world);
        applyEntryState(entry, world);
        persistWorlds();
        publishMessage(WorldChannelMessage.event("worldLoaded", buildWorldStatePayload(entry)));
        publishSnapshotEvent();
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        String worldName = event.getWorld().getName();
        WorldRegistryEntry entry = getOrCreateEntry(worldName);
        entry.setLoaded(false);
        entry.setUpdatedAt(System.currentTimeMillis());
        persistWorlds();
        publishMessage(WorldChannelMessage.event("worldUnloaded", buildWorldStatePayload(entry)));
        publishSnapshotEvent();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String targetWorld = player.getWorld().getName();
        initializePlayerState(player, targetWorld);
        updatePlayerFacet(player, true);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        captureState(player, stateKey(player.getWorld().getName()));
        persistPlayerStates();
        portalCooldowns.remove(player.getUniqueId());
        facetUpdates.remove(player.getUniqueId());
        if (trackingService != null) {
            trackingService.removeFacet(player.getUniqueId(), WORLD_LOCATION_FACET);
        }
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        String fromWorld = event.getFrom() == null ? null : event.getFrom().getName();
        String targetWorld = player.getWorld().getName();
        handleWorldTransition(player, fromWorld, targetWorld);
        updatePlayerFacet(player, true);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getTo() == null) {
            return;
        }
        if (event.getFrom().getWorld() == event.getTo().getWorld()
            && event.getFrom().getBlockX() == event.getTo().getBlockX()
            && event.getFrom().getBlockY() == event.getTo().getBlockY()
            && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }
        tryPortalTeleport(event.getPlayer(), event.getTo());
        updatePlayerFacet(event.getPlayer(), false);
    }

    private WorldSnapshot createSnapshotSync() {
        bootstrapLoadedWorlds();
        WorldSnapshot snapshot = new WorldSnapshot();
        List<WorldRegistryEntry> worldEntries = new ArrayList<>();
        for (WorldRegistryEntry entry : worlds.values()) {
            worldEntries.add(entry.copy());
        }
        worldEntries.sort(Comparator.comparing(WorldRegistryEntry::getWorldName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        List<WorldDashboardEntry> dashboardEntries = new ArrayList<>();
        for (WorldRegistryEntry entry : worldEntries) {
            dashboardEntries.add(buildDashboardEntry(entry));
        }
        List<WorldPortal> portalEntries = new ArrayList<>();
        for (WorldPortal portal : portals.values()) {
            portalEntries.add(portal.copy());
        }
        portalEntries.sort(Comparator.comparing(WorldPortal::getPortalName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        snapshot.setWorlds(worldEntries);
        snapshot.setDashboard(dashboardEntries);
        snapshot.setPortals(portalEntries);
        snapshot.setGameRuleDescriptors(createGameRuleDescriptors());
        snapshot.setGeneratorHints(createGeneratorHints());
        snapshot.setGeneratedAt(System.currentTimeMillis());
        return snapshot;
    }

    private WorldOperationResult createWorldSync(String worldName, String seed, String environment, String generator) {
        String normalizedName = sanitizeWorldName(worldName);
        if (normalizedName == null) {
            return WorldOperationResult.failure("createWorld", worldName, "InvalidWorldName");
        }
        if (Bukkit.getWorld(normalizedName) != null) {
            return WorldOperationResult.failure("createWorld", normalizedName, "WorldAlreadyLoaded");
        }
        Path folder = Bukkit.getWorldContainer().toPath().resolve(normalizedName);
        if (Files.exists(folder)) {
            return WorldOperationResult.failure("createWorld", normalizedName, "WorldFolderAlreadyExists");
        }
        WorldCreator creator = new WorldCreator(normalizedName);
        World.Environment parsedEnvironment = parseEnvironment(environment);
        if (parsedEnvironment != null) {
            creator.environment(parsedEnvironment);
        }
        Long parsedSeed = parseSeed(seed);
        if (parsedSeed != null) {
            creator.seed(parsedSeed);
        }
        if (generator != null && !generator.isBlank()) {
            creator.generator(generator);
        }
        World world = creator.createWorld();
        if (world == null) {
            return WorldOperationResult.failure("createWorld", normalizedName, "WorldCreationFailed");
        }
        WorldRegistryEntry entry = getOrCreateEntry(world.getName());
        syncEntryFromWorld(entry, world);
        entry.setGenerator(generator == null ? "" : generator);
        persistWorlds();
        publishMessage(WorldChannelMessage.event("worldCreated", buildWorldStatePayload(entry)));
        publishSnapshotEvent();
        return WorldOperationResult.success("createWorld", world.getName(), "WorldCreated").withData("world", entry.copy());
    }

    private WorldOperationResult scanUnregisteredWorldsSync() {
        List<String> unregistered = findUnregisteredWorldFolders();
        unregistered.sort(String.CASE_INSENSITIVE_ORDER);
        return WorldOperationResult.success("scanUnregisteredWorlds", null, "ScanCompleted")
            .withData("worlds", unregistered)
            .withData("count", unregistered.size());
    }

    private WorldOperationResult importUnregisteredWorldsSync() {
        List<String> candidates = findUnregisteredWorldFolders();
        List<WorldRegistryEntry> imported = new ArrayList<>();
        for (String worldName : candidates) {
            WorldRegistryEntry entry = getOrCreateEntry(worldName);
            World loaded = Bukkit.getWorld(worldName);
            if (loaded != null) {
                syncEntryFromWorld(entry, loaded);
            } else {
                entry.setWorldName(worldName);
                if (entry.getEnvironment() == null || entry.getEnvironment().isBlank()) {
                    entry.setEnvironment(World.Environment.NORMAL.name());
                }
                if (entry.getDifficulty() == null || entry.getDifficulty().isBlank()) {
                    entry.setDifficulty(Difficulty.NORMAL.name());
                }
                entry.setLoaded(false);
                entry.setUpdatedAt(System.currentTimeMillis());
            }
            imported.add(entry.copy());
        }
        if (!imported.isEmpty()) {
            persistWorlds();
        }
        LinkedHashMap<String, Object> importedData = new LinkedHashMap<>();
        importedData.put("count", imported.size());
        importedData.put("worlds", imported);
        publishMessage(WorldChannelMessage.event("worldsImported", importedData));
        publishSnapshotEvent();
        return WorldOperationResult.success("importUnregisteredWorlds", null, "ImportCompleted")
            .withData("worlds", imported)
            .withData("count", imported.size());
    }

    private WorldOperationResult cloneWorldAsyncSync(String sourceWorld, String targetWorld, boolean loadAfterClone) {
        String source = sanitizeWorldName(sourceWorld);
        String target = sanitizeWorldName(targetWorld);
        if (source == null || target == null) {
            return WorldOperationResult.failure("cloneWorld", targetWorld, "InvalidWorldName");
        }
        if (source.equalsIgnoreCase(target)) {
            return WorldOperationResult.failure("cloneWorld", target, "SourceAndTargetMustDiffer");
        }
        Path sourceFolder = Bukkit.getWorldContainer().toPath().resolve(source);
        Path targetFolder = Bukkit.getWorldContainer().toPath().resolve(target);
        if (!Files.exists(sourceFolder)) {
            return WorldOperationResult.failure("cloneWorld", target, "SourceWorldMissing");
        }
        if (Files.exists(targetFolder) || worlds.containsKey(worldKey(target)) || Bukkit.getWorld(target) != null) {
            return WorldOperationResult.failure("cloneWorld", target, "TargetWorldAlreadyExists");
        }
        World sourceLoaded = Bukkit.getWorld(source);
        if (sourceLoaded != null) {
            sourceLoaded.save();
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                WorldFileUtil.copyDirectory(sourceFolder, targetFolder);
                Files.deleteIfExists(targetFolder.resolve("uid.dat"));
                Files.deleteIfExists(targetFolder.resolve("session.lock"));
                Bukkit.getScheduler().runTask(plugin, () -> {
                    WorldRegistryEntry sourceEntry = worlds.get(worldKey(source));
                    WorldRegistryEntry targetEntry = getOrCreateEntry(target);
                    targetEntry.setWorldName(target);
                    if (sourceEntry != null) {
                        targetEntry.setEnvironment(sourceEntry.getEnvironment());
                        targetEntry.setDifficulty(sourceEntry.getDifficulty());
                        targetEntry.setGenerator(sourceEntry.getGenerator());
                        targetEntry.setGameRules(sourceEntry.getGameRules());
                        targetEntry.setTimeLockEnabled(sourceEntry.isTimeLockEnabled());
                        targetEntry.setLockedTime(sourceEntry.getLockedTime());
                        targetEntry.setWeatherLockEnabled(sourceEntry.isWeatherLockEnabled());
                        targetEntry.setLockedStorm(sourceEntry.isLockedStorm());
                        targetEntry.setLockedThundering(sourceEntry.isLockedThundering());
                        targetEntry.setIsolatedPlayerState(sourceEntry.isIsolatedPlayerState());
                    }
                    targetEntry.setLoaded(false);
                    targetEntry.setUpdatedAt(System.currentTimeMillis());
                    persistWorlds();
                    publishMessage(WorldChannelMessage.event("worldCloned", buildWorldStatePayload(targetEntry)));
                    if (loadAfterClone) {
                        loadWorldSync(target);
                    } else {
                        publishSnapshotEvent();
                    }
                });
            } catch (Exception exception) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        WorldFileUtil.deleteDirectory(targetFolder);
                    } catch (Exception ignored) {
                    }
                    publishMessage(WorldChannelMessage.error("cloneWorld", "CloneFailed:" + exception.getMessage()));
                });
            }
        });
        return WorldOperationResult.success("cloneWorld", target, "CloneStarted");
    }

    private WorldOperationResult deleteWorldSync(String worldName, boolean deleteFiles, String fallbackWorld) {
        String normalizedName = sanitizeWorldName(worldName);
        if (normalizedName == null) {
            return WorldOperationResult.failure("deleteWorld", worldName, "InvalidWorldName");
        }
        World loaded = Bukkit.getWorld(normalizedName);
        if (loaded != null) {
            World fallback = resolveFallbackWorld(normalizedName, fallbackWorld);
            if (fallback == null) {
                return WorldOperationResult.failure("deleteWorld", normalizedName, "FallbackWorldMissing");
            }
            for (Player player : new ArrayList<>(loaded.getPlayers())) {
                player.teleport(fallback.getSpawnLocation());
            }
            if (!Bukkit.unloadWorld(loaded, true)) {
                return WorldOperationResult.failure("deleteWorld", normalizedName, "WorldUnloadFailed");
            }
        }
        worlds.remove(worldKey(normalizedName));
        for (Map<String, WorldPlayerState> states : playerStates.values()) {
            if (states != null) {
                states.remove(normalizedName);
            }
        }
        int removedPortals = 0;
        for (WorldPortal portal : new ArrayList<>(portals.values())) {
            if (equalsWorld(portal.getSourceWorld(), normalizedName) || equalsWorld(portal.getDestinationWorld(), normalizedName)) {
                portals.remove(portal.getPortalId());
                removedPortals++;
            }
        }
        rebuildPortalIndex();
        persistWorlds();
        persistPortals();
        if (deleteFiles) {
            Path targetFolder = Bukkit.getWorldContainer().toPath().resolve(normalizedName);
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    WorldFileUtil.deleteDirectory(targetFolder);
                    LinkedHashMap<String, Object> data = new LinkedHashMap<>();
                    data.put("worldName", normalizedName);
                    publishMessage(WorldChannelMessage.event("worldFilesDeleted", data));
                } catch (Exception exception) {
                    publishMessage(WorldChannelMessage.error("deleteWorldFiles", "DeleteFilesFailed:" + exception.getMessage()));
                }
            });
        }
        LinkedHashMap<String, Object> deletedData = new LinkedHashMap<>();
        deletedData.put("worldName", normalizedName);
        deletedData.put("removedPortals", removedPortals);
        publishMessage(WorldChannelMessage.event("worldDeleted", deletedData));
        publishSnapshotEvent();
        return WorldOperationResult.success("deleteWorld", normalizedName, "WorldDeleted")
            .withData("removedPortals", removedPortals)
            .withData("deleteFiles", deleteFiles);
    }

    private WorldOperationResult loadWorldSync(String worldName) {
        String normalizedName = sanitizeWorldName(worldName);
        if (normalizedName == null) {
            return WorldOperationResult.failure("loadWorld", worldName, "InvalidWorldName");
        }
        World world = Bukkit.getWorld(normalizedName);
        if (world == null) {
            Path folder = Bukkit.getWorldContainer().toPath().resolve(normalizedName);
            if (!Files.exists(folder)) {
                return WorldOperationResult.failure("loadWorld", normalizedName, "WorldFolderMissing");
            }
            WorldCreator creator = new WorldCreator(normalizedName);
            WorldRegistryEntry existing = worlds.get(worldKey(normalizedName));
            if (existing != null) {
                World.Environment environment = parseEnvironment(existing.getEnvironment());
                if (environment != null) {
                    creator.environment(environment);
                }
                if (existing.getGenerator() != null && !existing.getGenerator().isBlank()) {
                    creator.generator(existing.getGenerator());
                }
            }
            world = creator.createWorld();
            if (world == null) {
                return WorldOperationResult.failure("loadWorld", normalizedName, "WorldLoadFailed");
            }
        }
        WorldRegistryEntry entry = getOrCreateEntry(world.getName());
        syncEntryFromWorld(entry, world);
        applyEntryState(entry, world);
        persistWorlds();
        publishMessage(WorldChannelMessage.event("worldLoaded", buildWorldStatePayload(entry)));
        publishSnapshotEvent();
        return WorldOperationResult.success("loadWorld", world.getName(), "WorldLoaded").withData("world", entry.copy());
    }

    private WorldOperationResult unloadWorldSync(String worldName, String fallbackWorld) {
        String normalizedName = sanitizeWorldName(worldName);
        if (normalizedName == null) {
            return WorldOperationResult.failure("unloadWorld", worldName, "InvalidWorldName");
        }
        World world = Bukkit.getWorld(normalizedName);
        if (world == null) {
            WorldRegistryEntry entry = worlds.get(worldKey(normalizedName));
            if (entry != null) {
                entry.setLoaded(false);
                entry.setUpdatedAt(System.currentTimeMillis());
                persistWorlds();
            }
            publishSnapshotEvent();
            return WorldOperationResult.success("unloadWorld", normalizedName, "WorldAlreadyUnloaded");
        }
        World fallback = resolveFallbackWorld(normalizedName, fallbackWorld);
        if (fallback == null) {
            return WorldOperationResult.failure("unloadWorld", normalizedName, "FallbackWorldMissing");
        }
        for (Player player : new ArrayList<>(world.getPlayers())) {
            player.teleport(fallback.getSpawnLocation());
        }
        boolean unloaded = Bukkit.unloadWorld(world, true);
        if (!unloaded) {
            return WorldOperationResult.failure("unloadWorld", normalizedName, "WorldUnloadFailed");
        }
        WorldRegistryEntry entry = getOrCreateEntry(normalizedName);
        entry.setLoaded(false);
        entry.setUpdatedAt(System.currentTimeMillis());
        persistWorlds();
        publishMessage(WorldChannelMessage.event("worldUnloaded", buildWorldStatePayload(entry)));
        publishSnapshotEvent();
        return WorldOperationResult.success("unloadWorld", normalizedName, "WorldUnloaded").withData("world", entry.copy());
    }

    private WorldOperationResult setGameRuleSync(String worldName, String ruleName, String value) {
        String normalizedName = sanitizeWorldName(worldName);
        if (normalizedName == null || ruleName == null || ruleName.isBlank()) {
            return WorldOperationResult.failure("setGameRule", worldName, "InvalidGameRuleRequest");
        }
        String safeValue = value == null ? "" : value;
        WorldRegistryEntry entry = getOrCreateEntry(normalizedName);
        World world = Bukkit.getWorld(normalizedName);
        if (world != null) {
            if (!applyGameRule(world, ruleName, safeValue)) {
                return WorldOperationResult.failure("setGameRule", normalizedName, "InvalidGameRuleValue");
            }
            entry.setGameRules(readGameRules(world));
            entry.setLoaded(true);
        } else {
            LinkedHashMap<String, String> rules = new LinkedHashMap<>(entry.getGameRules());
            rules.put(ruleName, safeValue);
            entry.setGameRules(rules);
            entry.setLoaded(false);
        }
        entry.setUpdatedAt(System.currentTimeMillis());
        persistWorlds();
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("worldName", normalizedName);
        data.put("ruleName", ruleName);
        data.put("value", safeValue);
        publishMessage(WorldChannelMessage.event("worldRuleUpdated", data));
        publishSnapshotEvent();
        return WorldOperationResult.success("setGameRule", normalizedName, "GameRuleUpdated")
            .withData("ruleName", ruleName)
            .withData("value", safeValue);
    }

    private WorldOperationResult setGameRulesSync(String worldName, Map<String, String> rules) {
        String normalizedName = sanitizeWorldName(worldName);
        if (normalizedName == null || rules == null || rules.isEmpty()) {
            return WorldOperationResult.failure("setGameRules", worldName, "InvalidGameRuleRequest");
        }
        LinkedHashMap<String, String> changedRules = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : rules.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            changedRules.put(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
        }
        if (changedRules.isEmpty()) {
            return WorldOperationResult.failure("setGameRules", normalizedName, "NoGameRulesProvided");
        }
        WorldRegistryEntry worldEntry = getOrCreateEntry(normalizedName);
        World world = Bukkit.getWorld(normalizedName);
        if (world != null) {
            for (Map.Entry<String, String> entry : changedRules.entrySet()) {
                if (!applyGameRule(world, entry.getKey(), entry.getValue())) {
                    return WorldOperationResult.failure("setGameRules", normalizedName, "InvalidGameRuleValue");
                }
            }
            worldEntry.setGameRules(readGameRules(world));
            worldEntry.setLoaded(true);
        } else {
            LinkedHashMap<String, String> storedRules = new LinkedHashMap<>(worldEntry.getGameRules());
            storedRules.putAll(changedRules);
            worldEntry.setGameRules(storedRules);
            worldEntry.setLoaded(false);
        }
        worldEntry.setUpdatedAt(System.currentTimeMillis());
        persistWorlds();
        LinkedHashMap<String, Object> eventData = new LinkedHashMap<>();
        eventData.put("worldName", normalizedName);
        eventData.put("count", changedRules.size());
        eventData.put("rules", new LinkedHashMap<>(changedRules));
        publishMessage(WorldChannelMessage.event("worldRulesUpdated", eventData));
        publishSnapshotEvent();
        return WorldOperationResult.success("setGameRules", normalizedName, "GameRulesUpdated")
            .withData("count", changedRules.size())
            .withData("rules", new LinkedHashMap<>(changedRules));
    }

    private WorldOperationResult setDifficultySync(String worldName, String difficulty) {
        String normalizedName = sanitizeWorldName(worldName);
        Difficulty parsed = parseDifficulty(difficulty);
        if (normalizedName == null || parsed == null) {
            return WorldOperationResult.failure("setDifficulty", worldName, "InvalidDifficulty");
        }
        WorldRegistryEntry entry = getOrCreateEntry(normalizedName);
        entry.setDifficulty(parsed.name());
        World loaded = Bukkit.getWorld(normalizedName);
        if (loaded != null) {
            loaded.setDifficulty(parsed);
            entry.setLoaded(true);
        } else {
            entry.setLoaded(false);
        }
        entry.setUpdatedAt(System.currentTimeMillis());
        persistWorlds();
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("worldName", normalizedName);
        data.put("difficulty", parsed.name());
        publishMessage(WorldChannelMessage.event("worldDifficultyUpdated", data));
        publishSnapshotEvent();
        return WorldOperationResult.success("setDifficulty", normalizedName, "DifficultyUpdated").withData("difficulty", parsed.name());
    }

    private WorldOperationResult setTimeLockSync(String worldName, boolean enabled, long lockedTime) {
        String normalizedName = sanitizeWorldName(worldName);
        if (normalizedName == null) {
            return WorldOperationResult.failure("setTimeLock", worldName, "InvalidWorldName");
        }
        WorldRegistryEntry entry = getOrCreateEntry(normalizedName);
        entry.setTimeLockEnabled(enabled);
        if (lockedTime >= 0) {
            entry.setLockedTime(lockedTime);
        }
        entry.setUpdatedAt(System.currentTimeMillis());
        World world = Bukkit.getWorld(normalizedName);
        if (world != null && enabled) {
            world.setTime(entry.getLockedTime());
            entry.setLoaded(true);
        }
        persistWorlds();
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("worldName", normalizedName);
        data.put("enabled", enabled);
        data.put("lockedTime", entry.getLockedTime());
        publishMessage(WorldChannelMessage.event("worldTimeLockUpdated", data));
        publishSnapshotEvent();
        return WorldOperationResult.success("setTimeLock", normalizedName, "TimeLockUpdated")
            .withData("enabled", enabled)
            .withData("lockedTime", entry.getLockedTime());
    }

    private WorldOperationResult setWeatherLockSync(String worldName, boolean enabled, boolean storm, boolean thundering) {
        String normalizedName = sanitizeWorldName(worldName);
        if (normalizedName == null) {
            return WorldOperationResult.failure("setWeatherLock", worldName, "InvalidWorldName");
        }
        WorldRegistryEntry entry = getOrCreateEntry(normalizedName);
        entry.setWeatherLockEnabled(enabled);
        entry.setLockedStorm(storm);
        entry.setLockedThundering(thundering);
        entry.setUpdatedAt(System.currentTimeMillis());
        World world = Bukkit.getWorld(normalizedName);
        if (world != null && enabled) {
            world.setStorm(storm);
            world.setThundering(thundering);
            entry.setLoaded(true);
        }
        persistWorlds();
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("worldName", normalizedName);
        data.put("enabled", enabled);
        data.put("storm", storm);
        data.put("thundering", thundering);
        publishMessage(WorldChannelMessage.event("worldWeatherLockUpdated", data));
        publishSnapshotEvent();
        return WorldOperationResult.success("setWeatherLock", normalizedName, "WeatherLockUpdated")
            .withData("enabled", enabled)
            .withData("storm", storm)
            .withData("thundering", thundering);
    }

    private WorldOperationResult setIsolatedPlayerStateSync(String worldName, boolean enabled) {
        String normalizedName = sanitizeWorldName(worldName);
        if (normalizedName == null) {
            return WorldOperationResult.failure("setIsolatedPlayerState", worldName, "InvalidWorldName");
        }
        WorldRegistryEntry entry = getOrCreateEntry(normalizedName);
        entry.setIsolatedPlayerState(enabled);
        entry.setUpdatedAt(System.currentTimeMillis());
        persistWorlds();
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("worldName", normalizedName);
        data.put("enabled", enabled);
        publishMessage(WorldChannelMessage.event("worldIsolatedStateUpdated", data));
        publishSnapshotEvent();
        return WorldOperationResult.success("setIsolatedPlayerState", normalizedName, "IsolatedStateUpdated").withData("enabled", enabled);
    }

    private WorldOperationResult createPortalSync(WorldPortal portal) {
        if (portal == null) {
            return WorldOperationResult.failure("createPortal", null, "InvalidPortal");
        }
        String sourceWorld = sanitizeWorldName(portal.getSourceWorld());
        String destinationWorld = sanitizeWorldName(portal.getDestinationWorld());
        if (sourceWorld == null || destinationWorld == null) {
            return WorldOperationResult.failure("createPortal", sourceWorld, "InvalidPortalWorld");
        }
        WorldPortal normalized = portal.copy();
        if (normalized.getPortalId() == null || normalized.getPortalId().isBlank()) {
            normalized.setPortalId(UUID.randomUUID().toString());
        }
        if (normalized.getPortalName() == null || normalized.getPortalName().isBlank()) {
            normalized.setPortalName(normalized.getPortalId());
        }
        normalized.setSourceWorld(sourceWorld);
        normalized.setDestinationWorld(destinationWorld);
        normalized.normalizeBounds();
        portals.put(normalized.getPortalId(), normalized);
        rebuildPortalIndex();
        persistPortals();
        publishMessage(WorldChannelMessage.event("portalCreated", normalized.copy()));
        publishSnapshotEvent();
        return WorldOperationResult.success("createPortal", sourceWorld, "PortalCreated").withData("portal", normalized.copy());
    }

    private WorldOperationResult resizePortalSync(WorldPortal portal) {
        if (portal == null || portal.getPortalId() == null || portal.getPortalId().isBlank()) {
            return WorldOperationResult.failure("resizePortal", null, "InvalidPortal");
        }
        WorldPortal existing = portals.get(portal.getPortalId());
        if (existing == null) {
            return WorldOperationResult.failure("resizePortal", null, "PortalNotFound");
        }
        WorldPortal updated = existing.copy();
        if (portal.getPortalName() != null && !portal.getPortalName().isBlank()) {
            updated.setPortalName(portal.getPortalName());
        }
        if (portal.getSourceWorld() != null && !portal.getSourceWorld().isBlank()) {
            updated.setSourceWorld(sanitizeWorldName(portal.getSourceWorld()));
        }
        if (portal.getDestinationWorld() != null && !portal.getDestinationWorld().isBlank()) {
            updated.setDestinationWorld(sanitizeWorldName(portal.getDestinationWorld()));
        }
        updated.setMinX(portal.getMinX());
        updated.setMinY(portal.getMinY());
        updated.setMinZ(portal.getMinZ());
        updated.setMaxX(portal.getMaxX());
        updated.setMaxY(portal.getMaxY());
        updated.setMaxZ(portal.getMaxZ());
        updated.setDestinationX(portal.getDestinationX());
        updated.setDestinationY(portal.getDestinationY());
        updated.setDestinationZ(portal.getDestinationZ());
        updated.setDestinationYaw(portal.getDestinationYaw());
        updated.setDestinationPitch(portal.getDestinationPitch());
        updated.setEnabled(portal.isEnabled());
        updated.normalizeBounds();
        portals.put(updated.getPortalId(), updated);
        rebuildPortalIndex();
        persistPortals();
        publishMessage(WorldChannelMessage.event("portalResized", updated.copy()));
        publishSnapshotEvent();
        return WorldOperationResult.success("resizePortal", updated.getSourceWorld(), "PortalUpdated").withData("portal", updated.copy());
    }

    private WorldOperationResult setPortalEnabledSync(String portalIdOrName, boolean enabled) {
        PortalLookupResult lookup = findPortal(portalIdOrName);
        if (lookup.errorMessage() != null) {
            return WorldOperationResult.failure("setPortalEnabled", null, lookup.errorMessage());
        }
        WorldPortal updated = lookup.portal().copy();
        updated.setEnabled(enabled);
        portals.put(updated.getPortalId(), updated);
        rebuildPortalIndex();
        persistPortals();
        publishMessage(WorldChannelMessage.event("portalUpdated", updated.copy()));
        publishSnapshotEvent();
        return WorldOperationResult.success("setPortalEnabled", updated.getSourceWorld(), "PortalUpdated")
            .withData("portal", updated.copy())
            .withData("portalId", updated.getPortalId())
            .withData("enabled", enabled);
    }

    private WorldOperationResult setPortalDestinationSync(String portalIdOrName, String destinationWorld, double destinationX, double destinationY, double destinationZ,
                                                          float destinationYaw, float destinationPitch) {
        PortalLookupResult lookup = findPortal(portalIdOrName);
        if (lookup.errorMessage() != null) {
            return WorldOperationResult.failure("setPortalDestination", null, lookup.errorMessage());
        }
        String normalizedDestinationWorld = sanitizeWorldName(destinationWorld);
        if (normalizedDestinationWorld == null) {
            return WorldOperationResult.failure("setPortalDestination", null, "InvalidDestinationWorld");
        }
        WorldPortal updated = lookup.portal().copy();
        updated.setDestinationWorld(normalizedDestinationWorld);
        updated.setDestinationX(destinationX);
        updated.setDestinationY(destinationY);
        updated.setDestinationZ(destinationZ);
        updated.setDestinationYaw(destinationYaw);
        updated.setDestinationPitch(destinationPitch);
        portals.put(updated.getPortalId(), updated);
        rebuildPortalIndex();
        persistPortals();
        publishMessage(WorldChannelMessage.event("portalUpdated", updated.copy()));
        publishSnapshotEvent();
        return WorldOperationResult.success("setPortalDestination", updated.getSourceWorld(), "PortalUpdated")
            .withData("portal", updated.copy())
            .withData("portalId", updated.getPortalId());
    }

    private WorldOperationResult setPortalBoundsSync(String portalIdOrName, String sourceWorld, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        PortalLookupResult lookup = findPortal(portalIdOrName);
        if (lookup.errorMessage() != null) {
            return WorldOperationResult.failure("setPortalBounds", null, lookup.errorMessage());
        }
        String normalizedSourceWorld = sanitizeWorldName(sourceWorld);
        if (normalizedSourceWorld == null) {
            return WorldOperationResult.failure("setPortalBounds", null, "InvalidSourceWorld");
        }
        WorldPortal updated = lookup.portal().copy();
        updated.setSourceWorld(normalizedSourceWorld);
        updated.setMinX(minX);
        updated.setMinY(minY);
        updated.setMinZ(minZ);
        updated.setMaxX(maxX);
        updated.setMaxY(maxY);
        updated.setMaxZ(maxZ);
        updated.normalizeBounds();
        portals.put(updated.getPortalId(), updated);
        rebuildPortalIndex();
        persistPortals();
        publishMessage(WorldChannelMessage.event("portalUpdated", updated.copy()));
        publishSnapshotEvent();
        return WorldOperationResult.success("setPortalBounds", updated.getSourceWorld(), "PortalUpdated")
            .withData("portal", updated.copy())
            .withData("portalId", updated.getPortalId());
    }

    private WorldOperationResult deletePortalSync(String portalId) {
        if (portalId == null || portalId.isBlank()) {
            return WorldOperationResult.failure("deletePortal", null, "InvalidPortalId");
        }
        PortalLookupResult lookup = findPortal(portalId);
        if (lookup.errorMessage() != null) {
            return WorldOperationResult.failure("deletePortal", null, lookup.errorMessage());
        }
        WorldPortal removed = portals.remove(lookup.portal().getPortalId());
        rebuildPortalIndex();
        persistPortals();
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("portalId", removed.getPortalId());
        publishMessage(WorldChannelMessage.event("portalDeleted", data));
        publishSnapshotEvent();
        return WorldOperationResult.success("deletePortal", removed.getSourceWorld(), "PortalDeleted").withData("portalId", removed.getPortalId());
    }

    private WorldOperationResult teleportPlayerToWorldSync(String playerName, String worldName, Double x, Double y, Double z, Float yaw, Float pitch) {
        Player player = findPlayer(playerName);
        if (player == null) {
            return WorldOperationResult.failure("teleportPlayerToWorld", worldName, "PlayerNotFound");
        }
        World targetWorld = ensureWorldLoaded(worldName, "teleportPlayerToWorld");
        if (targetWorld == null) {
            return WorldOperationResult.failure("teleportPlayerToWorld", worldName, "WorldLoadFailed");
        }
        Location target = x == null || y == null || z == null
            ? targetWorld.getSpawnLocation()
            : new Location(targetWorld, x, y, z, yaw == null ? player.getLocation().getYaw() : yaw, pitch == null ? player.getLocation().getPitch() : pitch);
        return teleportPlayer(player, target, "teleportPlayerToWorld", "PlayerTeleported");
    }

    private WorldOperationResult teleportPlayerToWorldSpawnSync(String playerName, String worldName) {
        Player player = findPlayer(playerName);
        if (player == null) {
            return WorldOperationResult.failure("teleportPlayerToWorldSpawn", worldName, "PlayerNotFound");
        }
        World targetWorld = ensureWorldLoaded(worldName, "teleportPlayerToWorldSpawn");
        if (targetWorld == null) {
            return WorldOperationResult.failure("teleportPlayerToWorldSpawn", worldName, "WorldLoadFailed");
        }
        return teleportPlayer(player, targetWorld.getSpawnLocation(), "teleportPlayerToWorldSpawn", "PlayerTeleported");
    }

    private WorldOperationResult teleportPlayerToPortalSync(String playerName, String portalIdOrName) {
        Player player = findPlayer(playerName);
        if (player == null) {
            return WorldOperationResult.failure("teleportPlayerToPortal", null, "PlayerNotFound");
        }
        PortalLookupResult lookup = findPortal(portalIdOrName);
        if (lookup.errorMessage() != null) {
            return WorldOperationResult.failure("teleportPlayerToPortal", null, lookup.errorMessage());
        }
        WorldPortal portal = lookup.portal();
        World destinationWorld = ensureWorldLoaded(portal.getDestinationWorld(), "teleportPlayerToPortal");
        if (destinationWorld == null) {
            return WorldOperationResult.failure("teleportPlayerToPortal", portal.getDestinationWorld(), "WorldLoadFailed");
        }
        Location target = new Location(destinationWorld,
            portal.getDestinationX(),
            portal.getDestinationY(),
            portal.getDestinationZ(),
            portal.getDestinationYaw(),
            portal.getDestinationPitch());
        return teleportPlayer(player, target, "teleportPlayerToPortal", "PlayerTeleported")
            .withData("portalId", portal.getPortalId())
            .withData("portalName", portal.getPortalName());
    }

    private void bootstrapLoadedWorlds() {
        Set<String> known = new LinkedHashSet<>();
        for (World world : Bukkit.getWorlds()) {
            WorldRegistryEntry entry = getOrCreateEntry(world.getName());
            syncEntryFromWorld(entry, world);
            applyEntryState(entry, world);
            known.add(worldKey(world.getName()));
        }
        for (WorldRegistryEntry entry : worlds.values()) {
            if (!known.contains(worldKey(entry.getWorldName()))) {
                entry.setLoaded(false);
            }
        }
        persistWorlds();
    }

    private void applyLocksTick() {
        for (WorldRegistryEntry entry : worlds.values()) {
            if (entry == null || entry.getWorldName() == null || entry.getWorldName().isBlank()) {
                continue;
            }
            World world = Bukkit.getWorld(entry.getWorldName());
            if (world == null) {
                entry.setLoaded(false);
                continue;
            }
            entry.setLoaded(true);
            if (entry.isTimeLockEnabled()) {
                world.setTime(entry.getLockedTime());
            }
            if (entry.isWeatherLockEnabled()) {
                world.setStorm(entry.isLockedStorm());
                world.setThundering(entry.isLockedThundering());
            }
        }
    }

    private void handleWorldTransition(Player player, String fromWorld, String targetWorld) {
        String sourceKey = stateKey(fromWorld);
        String targetKey = stateKey(targetWorld);
        if (sourceKey == null || targetKey == null) {
            return;
        }
        captureState(player, sourceKey);
        if (!sourceKey.equals(targetKey)) {
            Map<String, WorldPlayerState> states = playerStates.computeIfAbsent(player.getUniqueId(), ignored -> new ConcurrentHashMap<>());
            WorldPlayerState applyState = states.get(targetKey);
            if (applyState == null) {
                WorldPlayerState source = states.get(sourceKey);
                if (source != null) {
                    WorldPlayerState copy = source.copy();
                    copy.setWorldName(targetKey);
                    copy.setUpdatedAt(System.currentTimeMillis());
                    states.put(targetKey, copy);
                    applyState = copy;
                }
            }
            if (applyState != null) {
                WorldPlayerStateCodec.apply(player, applyState);
            }
        }
        persistPlayerStates();
    }

    private void initializePlayerState(Player player, String worldName) {
        String key = stateKey(worldName);
        if (key == null) {
            return;
        }
        Map<String, WorldPlayerState> states = playerStates.computeIfAbsent(player.getUniqueId(), ignored -> new ConcurrentHashMap<>());
        WorldPlayerState globalState = states.get(GLOBAL_STATE_KEY);
        if (globalState == null) {
            globalState = WorldPlayerStateCodec.capture(player, GLOBAL_STATE_KEY);
            states.put(GLOBAL_STATE_KEY, globalState);
        }
        WorldPlayerState targetState = states.get(key);
        if (targetState == null) {
            targetState = key.equals(GLOBAL_STATE_KEY) ? globalState.copy() : globalState.copy();
            targetState.setWorldName(key);
            targetState.setUpdatedAt(System.currentTimeMillis());
            states.put(key, targetState);
        }
        if (!GLOBAL_STATE_KEY.equals(key)) {
            WorldPlayerStateCodec.apply(player, targetState);
        }
        persistPlayerStates();
    }

    private void tryPortalTeleport(Player player, Location location) {
        String sourceWorld = location.getWorld() == null ? null : location.getWorld().getName();
        if (sourceWorld == null || sourceWorld.isBlank()) {
            return;
        }
        List<WorldPortal> sourcePortals = portalIndex.get(worldKey(sourceWorld));
        if (sourcePortals == null || sourcePortals.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long cooldownUntil = portalCooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (cooldownUntil > now) {
            return;
        }
        for (WorldPortal portal : sourcePortals) {
            if (portal == null || !portal.isEnabled()) {
                continue;
            }
            if (!portal.contains(location)) {
                continue;
            }
            World destination = Bukkit.getWorld(portal.getDestinationWorld());
            if (destination == null) {
                WorldOperationResult loadResult = loadWorldSync(portal.getDestinationWorld());
                if (!loadResult.isSuccess()) {
                    publishMessage(WorldChannelMessage.error("portalTeleport", "DestinationLoadFailed:" + portal.getDestinationWorld()));
                    return;
                }
                destination = Bukkit.getWorld(portal.getDestinationWorld());
            }
            if (destination == null) {
                return;
            }
            portalCooldowns.put(player.getUniqueId(), now + PORTAL_COOLDOWN_MS);
            portal.setLastUsedAt(now);
            Location target = new Location(destination,
                portal.getDestinationX(),
                portal.getDestinationY(),
                portal.getDestinationZ(),
                portal.getDestinationYaw(),
                portal.getDestinationPitch());
            player.teleport(target);
            LinkedHashMap<String, Object> data = new LinkedHashMap<>();
            data.put("portalId", portal.getPortalId());
            data.put("playerId", player.getUniqueId().toString());
            data.put("playerName", player.getName());
            data.put("sourceWorld", sourceWorld);
            data.put("destinationWorld", destination.getName());
            publishMessage(WorldChannelMessage.event("portalUsed", data));
            break;
        }
    }

    private void updatePlayerFacet(Player player, boolean force) {
        if (trackingService == null || player == null || player.getWorld() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && now - facetUpdates.getOrDefault(player.getUniqueId(), 0L) < FACET_UPDATE_INTERVAL_MS) {
            return;
        }
        Location location = player.getLocation();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("world", player.getWorld().getName());
        data.put("x", location.getX());
        data.put("y", location.getY());
        data.put("z", location.getZ());
        data.put("yaw", location.getYaw());
        data.put("pitch", location.getPitch());
        data.put("gameMode", player.getGameMode().name());
        data.put("health", player.getHealth());
        data.put("food", player.getFoodLevel());
        data.put("online", true);
        trackingService.upsertFacet(player.getUniqueId(), player.getName(), WORLD_LOCATION_FACET, MODULE_ID, data);
        facetUpdates.put(player.getUniqueId(), now);
    }

    private void captureState(Player player, String stateKey) {
        if (player == null || stateKey == null || stateKey.isBlank()) {
            return;
        }
        Map<String, WorldPlayerState> perWorld = playerStates.computeIfAbsent(player.getUniqueId(), ignored -> new ConcurrentHashMap<>());
        perWorld.put(stateKey, WorldPlayerStateCodec.capture(player, stateKey));
    }

    private String stateKey(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return GLOBAL_STATE_KEY;
        }
        WorldRegistryEntry entry = worlds.get(worldKey(worldName));
        if (entry != null && entry.isIsolatedPlayerState()) {
            return worldName;
        }
        return GLOBAL_STATE_KEY;
    }

    private WorldRegistryEntry getOrCreateEntry(String worldName) {
        return worlds.computeIfAbsent(worldKey(worldName), ignored -> {
            WorldRegistryEntry entry = new WorldRegistryEntry();
            entry.setWorldName(worldName);
            entry.setEnvironment(World.Environment.NORMAL.name());
            entry.setDifficulty(Difficulty.NORMAL.name());
            entry.setLoaded(Bukkit.getWorld(worldName) != null);
            entry.setUpdatedAt(System.currentTimeMillis());
            return entry;
        });
    }

    private void syncEntryFromWorld(WorldRegistryEntry entry, World world) {
        entry.setWorldName(world.getName());
        entry.setEnvironment(world.getEnvironment().name());
        entry.setDifficulty(world.getDifficulty().name());
        entry.setLoaded(true);
        if (entry.getGenerator() == null) {
            entry.setGenerator("");
        }
        entry.setGameRules(readGameRules(world));
        entry.setUpdatedAt(System.currentTimeMillis());
    }

    private void applyEntryState(WorldRegistryEntry entry, World world) {
        if (entry.getDifficulty() != null && !entry.getDifficulty().isBlank()) {
            Difficulty difficulty = parseDifficulty(entry.getDifficulty());
            if (difficulty != null) {
                world.setDifficulty(difficulty);
            }
        }
        if (entry.getGameRules() != null && !entry.getGameRules().isEmpty()) {
            for (Map.Entry<String, String> gameRule : entry.getGameRules().entrySet()) {
                applyGameRule(world, gameRule.getKey(), gameRule.getValue());
            }
        }
        if (entry.isTimeLockEnabled()) {
            world.setTime(entry.getLockedTime());
        }
        if (entry.isWeatherLockEnabled()) {
            world.setStorm(entry.isLockedStorm());
            world.setThundering(entry.isLockedThundering());
        }
    }

    private WorldDashboardEntry buildDashboardEntry(WorldRegistryEntry entry) {
        WorldDashboardEntry dashboardEntry = new WorldDashboardEntry();
        dashboardEntry.setWorldName(entry.getWorldName());
        World loaded = entry.getWorldName() == null ? null : Bukkit.getWorld(entry.getWorldName());
        boolean isLoaded = loaded != null;
        dashboardEntry.setLoaded(isLoaded);
        dashboardEntry.setStatus(isLoaded ? "Loaded" : "Unloaded");
        dashboardEntry.setPlayerCount(isLoaded ? loaded.getPlayers().size() : 0);
        dashboardEntry.setEnvironment(isLoaded ? loaded.getEnvironment().name() : entry.getEnvironment());
        dashboardEntry.setDifficulty(isLoaded ? loaded.getDifficulty().name() : entry.getDifficulty());
        dashboardEntry.setIsolatedPlayerState(entry.isIsolatedPlayerState());
        dashboardEntry.setTimeLockEnabled(entry.isTimeLockEnabled());
        dashboardEntry.setWeatherLockEnabled(entry.isWeatherLockEnabled());
        return dashboardEntry;
    }

    private Map<String, Object> buildWorldStatePayload(WorldRegistryEntry entry) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("world", entry.copy());
        payload.put("dashboard", buildDashboardEntry(entry));
        return payload;
    }

    private List<String> findUnregisteredWorldFolders() {
        Path worldContainer = Bukkit.getWorldContainer().toPath();
        List<String> worldsOnDisk = new ArrayList<>();
        try (var stream = Files.list(worldContainer)) {
            stream.filter(Files::isDirectory).forEach(path -> {
                if (Files.exists(path.resolve("level.dat"))) {
                    String name = path.getFileName().toString();
                    if (!worlds.containsKey(worldKey(name))) {
                        worldsOnDisk.add(name);
                    }
                }
            });
        } catch (Exception exception) {
            publishMessage(WorldChannelMessage.error("scanWorldFolders", "ScanFailed:" + exception.getMessage()));
        }
        return worldsOnDisk;
    }

    private World resolveFallbackWorld(String excludedWorld, String preferredFallback) {
        if (preferredFallback != null && !preferredFallback.isBlank()) {
            World preferred = Bukkit.getWorld(preferredFallback);
            if (preferred != null && !equalsWorld(preferred.getName(), excludedWorld)) {
                return preferred;
            }
        }
        for (World world : Bukkit.getWorlds()) {
            if (!equalsWorld(world.getName(), excludedWorld)) {
                return world;
            }
        }
        return null;
    }

    private void rebuildPortalIndex() {
        Map<String, List<WorldPortal>> byWorld = new LinkedHashMap<>();
        for (WorldPortal portal : portals.values()) {
            if (portal == null || portal.getSourceWorld() == null || portal.getSourceWorld().isBlank()) {
                continue;
            }
            byWorld.computeIfAbsent(worldKey(portal.getSourceWorld()), ignored -> new ArrayList<>()).add(portal);
        }
        for (List<WorldPortal> list : byWorld.values()) {
            list.sort(Comparator.comparing(WorldPortal::getPortalName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        }
        portalIndex = byWorld;
    }

    private PortalLookupResult findPortal(String portalIdOrName) {
        if (portalIdOrName == null || portalIdOrName.isBlank()) {
            return new PortalLookupResult(null, "InvalidPortal");
        }
        WorldPortal direct = portals.get(portalIdOrName);
        if (direct != null) {
            return new PortalLookupResult(direct, null);
        }
        WorldPortal caseInsensitiveId = null;
        List<WorldPortal> nameMatches = new ArrayList<>();
        for (WorldPortal portal : portals.values()) {
            if (portal == null) {
                continue;
            }
            if (portal.getPortalId() != null && portal.getPortalId().equalsIgnoreCase(portalIdOrName)) {
                caseInsensitiveId = portal;
                break;
            }
            if (portal.getPortalName() != null && portal.getPortalName().equalsIgnoreCase(portalIdOrName)) {
                nameMatches.add(portal);
            }
        }
        if (caseInsensitiveId != null) {
            return new PortalLookupResult(caseInsensitiveId, null);
        }
        if (nameMatches.size() == 1) {
            return new PortalLookupResult(nameMatches.getFirst(), null);
        }
        if (nameMatches.size() > 1) {
            return new PortalLookupResult(null, "PortalNameAmbiguous");
        }
        return new PortalLookupResult(null, "PortalNotFound");
    }

    private List<WorldGameRuleDescriptor> createGameRuleDescriptors() {
        List<WorldGameRuleDescriptor> descriptors = new ArrayList<>();
        for (GameRule<?> gameRule : GameRule.values()) {
            if (gameRule == null || gameRule.getName() == null || gameRule.getName().isBlank()) {
                continue;
            }
            WorldGameRuleDescriptor descriptor = new WorldGameRuleDescriptor();
            descriptor.setName(gameRule.getName());
            descriptor.setType(resolveGameRuleType(gameRule));
            descriptors.add(descriptor);
        }
        descriptors.sort(Comparator.comparing(WorldGameRuleDescriptor::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return descriptors;
    }

    private List<String> createGeneratorHints() {
        List<String> hints = new ArrayList<>();
        for (Plugin value : Bukkit.getPluginManager().getPlugins()) {
            if (value == null) {
                continue;
            }
            try {
                if (value.getDefaultWorldGenerator("__resync_probe__", null) != null) {
                    hints.add(value.getName());
                }
            } catch (Exception ignored) {
            }
        }
        hints.sort(String.CASE_INSENSITIVE_ORDER);
        return hints;
    }

    private Map<String, String> readGameRules(World world) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (GameRule<?> gameRule : GameRule.values()) {
            String value = readGameRule(world, gameRule);
            if (value != null) {
                values.put(gameRule.getName(), value);
            }
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private String readGameRule(World world, GameRule<?> gameRule) {
        try {
            Object value = world.getGameRuleValue((GameRule<Object>) gameRule);
            return value == null ? null : String.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private boolean applyGameRule(World world, String ruleName, String value) {
        GameRule<?> gameRule = GameRule.getByName(ruleName);
        if (gameRule == null) {
            return false;
        }
        Class<?> type = gameRule.getType();
        try {
            if (Boolean.class.equals(type)) {
                return world.setGameRule((GameRule<Boolean>) gameRule, Boolean.parseBoolean(value));
            }
            if (Integer.class.equals(type)) {
                return world.setGameRule((GameRule<Integer>) gameRule, Integer.parseInt(value));
            }
        } catch (IllegalArgumentException ignored) {
            return false;
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    private String resolveGameRuleType(GameRule<?> gameRule) {
        if (gameRule == null || gameRule.getType() == null) {
            return "unknown";
        }
        if (Boolean.class.equals(gameRule.getType())) {
            return "boolean";
        }
        if (Integer.class.equals(gameRule.getType())) {
            return "integer";
        }
        return gameRule.getType().getSimpleName().toLowerCase(Locale.ROOT);
    }

    private Player findPlayer(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return null;
        }
        Player exact = Bukkit.getPlayerExact(playerName);
        return exact != null ? exact : Bukkit.getPlayer(playerName);
    }

    private World ensureWorldLoaded(String worldName, String action) {
        String normalizedName = sanitizeWorldName(worldName);
        if (normalizedName == null) {
            return null;
        }
        World world = Bukkit.getWorld(normalizedName);
        if (world != null) {
            return world;
        }
        WorldOperationResult result = loadWorldSync(normalizedName);
        if (!result.isSuccess()) {
            publishMessage(WorldChannelMessage.error(action, result.getMessage()));
            return null;
        }
        return Bukkit.getWorld(normalizedName);
    }

    private WorldOperationResult teleportPlayer(Player player, Location target, String action, String message) {
        if (player == null || target == null || target.getWorld() == null) {
            return WorldOperationResult.failure(action, null, "InvalidTeleportTarget");
        }
        boolean teleported = player.teleport(target);
        if (!teleported) {
            return WorldOperationResult.failure(action, target.getWorld().getName(), "TeleportFailed");
        }
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("playerId", player.getUniqueId().toString());
        data.put("playerName", player.getName());
        data.put("worldName", target.getWorld().getName());
        data.put("x", target.getX());
        data.put("y", target.getY());
        data.put("z", target.getZ());
        data.put("yaw", target.getYaw());
        data.put("pitch", target.getPitch());
        publishMessage(WorldChannelMessage.event("worldPlayerTeleported", data));
        return WorldOperationResult.success(action, target.getWorld().getName(), message)
            .withData("playerName", player.getName())
            .withData("worldName", target.getWorld().getName())
            .withData("x", target.getX())
            .withData("y", target.getY())
            .withData("z", target.getZ())
            .withData("yaw", target.getYaw())
            .withData("pitch", target.getPitch());
    }

    private record PortalLookupResult(WorldPortal portal, String errorMessage) {
    }

    private void publishSnapshotEvent() {
        publishMessage(WorldChannelMessage.event("snapshot", createSnapshotSync()));
    }

    private void publishMessage(WorldChannelMessage message) {
        for (WorldManagementListener listener : listeners) {
            try {
                listener.onMessage(message);
            } catch (Exception ignored) {
            }
        }
    }

    private void persistWorlds() {
        List<WorldRegistryEntry> entries = new ArrayList<>();
        for (WorldRegistryEntry entry : worlds.values()) {
            entries.add(entry.copy());
        }
        entries.sort(Comparator.comparing(WorldRegistryEntry::getWorldName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        storage.saveWorlds(entries);
    }

    private void persistPortals() {
        List<WorldPortal> entries = new ArrayList<>();
        for (WorldPortal portal : portals.values()) {
            entries.add(portal.copy());
        }
        entries.sort(Comparator.comparing(WorldPortal::getPortalName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        storage.savePortals(entries);
    }

    private void persistPlayerStates() {
        Map<UUID, Map<String, WorldPlayerState>> copy = new LinkedHashMap<>();
        for (Map.Entry<UUID, Map<String, WorldPlayerState>> entry : playerStates.entrySet()) {
            Map<String, WorldPlayerState> states = new LinkedHashMap<>();
            if (entry.getValue() != null) {
                for (Map.Entry<String, WorldPlayerState> stateEntry : entry.getValue().entrySet()) {
                    if (stateEntry.getValue() != null) {
                        states.put(stateEntry.getKey(), stateEntry.getValue().copy());
                    }
                }
            }
            copy.put(entry.getKey(), states);
        }
        storage.savePlayerStates(copy);
    }

    private void persistAll() {
        persistWorlds();
        persistPortals();
        persistPlayerStates();
    }

    private String sanitizeWorldName(String worldName) {
        if (worldName == null) {
            return null;
        }
        String trimmed = worldName.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains("..")) {
            return null;
        }
        return trimmed;
    }

    private String worldKey(String worldName) {
        return worldName == null ? "" : worldName.toLowerCase(Locale.ROOT);
    }

    private boolean equalsWorld(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.equalsIgnoreCase(right);
    }

    private World.Environment parseEnvironment(String environment) {
        if (environment == null || environment.isBlank()) {
            return null;
        }
        try {
            return World.Environment.valueOf(environment.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private Difficulty parseDifficulty(String difficulty) {
        if (difficulty == null || difficulty.isBlank()) {
            return null;
        }
        try {
            return Difficulty.valueOf(difficulty.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private Long parseSeed(String seed) {
        if (seed == null || seed.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(seed);
        } catch (NumberFormatException ignored) {
            return (long) seed.hashCode();
        }
    }

    private <T> T callSync(Supplier<T> supplier) {
        if (Bukkit.isPrimaryThread()) {
            return supplier.get();
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("SyncExecutionFailed", exception);
        }
    }
}
