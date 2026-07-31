package restudio.resync.modules.flow;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.world.WorldManagementService;
import restudio.resync.world.WorldOperationResult;
import restudio.resync.world.WorldRegistryEntry;

public final class WorldWorkspaceDocumentProvider implements FlowWorkspaceDocumentProvider {
    private final WorldManagementService worlds;
    private final Gson gson = new Gson();

    public WorldWorkspaceDocumentProvider(WorldManagementService worlds) {
        this.worlds = worlds;
    }

    @Override
    public String type() {
        return ReSyncResourceCatalog.WORLD;
    }

    @Override
    public JsonObject load(String resourceId) {
        if (worlds == null || resourceId == null || resourceId.isBlank()) {
            return null;
        }
        return worlds.createSnapshot().getWorlds().stream()
            .filter(world -> world != null && resourceId.equalsIgnoreCase(world.getWorldName()))
            .findFirst()
            .map(world -> gson.toJsonTree(world).getAsJsonObject())
            .orElse(null);
    }

    @Override
    public void persist(String resourceId, JsonObject document) {
        WorldRegistryEntry world = document != null ? gson.fromJson(document, WorldRegistryEntry.class) : null;
        if (world == null || world.getWorldName() == null || !resourceId.equalsIgnoreCase(world.getWorldName())) {
            throw new IllegalStateException("Workspace resource identity changed");
        }
        WorldOperationResult result = worlds.updateWorld(world);
        if (result == null || !result.isSuccess()) {
            throw new IllegalStateException(result != null ? result.getMessage() : "World update failed");
        }
    }
}
