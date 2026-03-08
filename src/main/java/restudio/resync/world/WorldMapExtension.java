package restudio.resync.world;

import java.util.Collection;

public interface WorldMapExtension {
    String getExtensionId();

    Collection<WorldMapControl> getControls(WorldMapQuery query);

    Collection<WorldMapDrawing> getDrawings(WorldMapQuery query);

    WorldMapActionResult handleAction(WorldMapAction action);
}
