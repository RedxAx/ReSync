package restudio.resync.world;

import java.util.Collection;

public interface WorldMapService {
    void registerExtension(WorldMapExtension extension);

    void unregisterExtension(String extensionId);

    Collection<WorldMapExtension> getExtensions();

    WorldMapSnapshot createSnapshot(WorldMapQuery query);

    WorldMapActionResult handleAction(WorldMapAction action);
}
