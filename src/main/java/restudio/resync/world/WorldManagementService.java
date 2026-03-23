package restudio.resync.world;

import java.util.List;
import java.util.Map;

public interface WorldManagementService {
    WorldMapService getMapService();

    void addListener(WorldManagementListener listener);

    void removeListener(WorldManagementListener listener);

    WorldSnapshot createSnapshot();

    List<WorldGameRuleDescriptor> getGameRuleDescriptors();

    List<WorldGeneratorDescriptor> getGeneratorDescriptors();

    List<WorldPortal> getPortals();

    List<WorldPortal> getPortalsByWorld(String worldName);

    List<WorldInventoryGroup> getInventoryGroups();

    List<WorldSignPortal> getSignPortals();

    WorldPortal getPortal(String portalIdOrName);

    WorldOperationResult createWorld(String worldName, String seed, String environment, String generator);

    WorldOperationResult createWorld(String worldName, String seed, String environment, String generator, String generatorConfig);

    WorldOperationResult importUnregisteredWorlds();

    WorldOperationResult scanUnregisteredWorlds();

    WorldOperationResult cloneWorldAsync(String sourceWorld, String targetWorld, boolean loadAfterClone);

    WorldOperationResult deleteWorld(String worldName, boolean deleteFiles, String fallbackWorld);

    WorldOperationResult loadWorld(String worldName);

    WorldOperationResult unloadWorld(String worldName, String fallbackWorld);

    WorldOperationResult setGameRule(String worldName, String ruleName, String value);

    WorldOperationResult setGameRules(String worldName, Map<String, String> rules);

    WorldOperationResult setDifficulty(String worldName, String difficulty);

    WorldOperationResult setTimeLock(String worldName, boolean enabled, long lockedTime);

    WorldOperationResult setWeatherLock(String worldName, boolean enabled, boolean storm, boolean thundering);

    WorldOperationResult setIsolatedPlayerState(String worldName, boolean enabled);

    WorldOperationResult setWorldProfile(String worldName, WorldProfileSettings profileSettings);

    WorldOperationResult createPortal(WorldPortal portal);

    WorldOperationResult resizePortal(WorldPortal portal);

    WorldOperationResult setPortalEnabled(String portalIdOrName, boolean enabled);

    WorldOperationResult setPortalDestination(String portalIdOrName, String destinationWorld, double destinationX, double destinationY, double destinationZ,
                                              float destinationYaw, float destinationPitch);

    WorldOperationResult setPortalBounds(String portalIdOrName, String sourceWorld, double minX, double minY, double minZ, double maxX, double maxY, double maxZ);

    WorldOperationResult deletePortal(String portalId);

    WorldOperationResult teleportPlayerToWorld(String playerName, String worldName, Double x, Double y, Double z, Float yaw, Float pitch);

    WorldOperationResult teleportPlayerToWorldSpawn(String playerName, String worldName);

    WorldOperationResult teleportPlayerToPortal(String playerName, String portalIdOrName);

    WorldOperationResult createInventoryGroup(WorldInventoryGroup group);

    WorldOperationResult updateInventoryGroup(WorldInventoryGroup group);

    WorldOperationResult deleteInventoryGroup(String groupId);

    WorldOperationResult createSignPortal(WorldSignPortal signPortal);

    WorldOperationResult deleteSignPortal(String signId);

    WorldOperationResult whoWorld(String worldName);

    WorldOperationResult purgeWorld(String worldName, boolean monsters, boolean animals, boolean ambient, boolean misc, boolean vehicles, boolean items);

    void start();

    void stop();

    void tick();
}
