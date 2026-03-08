package restudio.resync.world;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DefaultWorldMapService implements WorldMapService {
    private final Map<String, WorldMapExtension> extensions = new ConcurrentHashMap<>();

    @Override
    public void registerExtension(WorldMapExtension extension) {
        if (extension == null || extension.getExtensionId() == null || extension.getExtensionId().isBlank()) {
            return;
        }
        extensions.put(extension.getExtensionId(), extension);
    }

    @Override
    public void unregisterExtension(String extensionId) {
        if (extensionId == null || extensionId.isBlank()) {
            return;
        }
        extensions.remove(extensionId);
    }

    @Override
    public Collection<WorldMapExtension> getExtensions() {
        return List.copyOf(extensions.values());
    }

    @Override
    public WorldMapSnapshot createSnapshot(WorldMapQuery query) {
        WorldMapQuery safeQuery = query == null ? new WorldMapQuery() : query;
        List<WorldMapControl> controls = new ArrayList<>();
        List<WorldMapDrawing> drawings = new ArrayList<>();
        for (WorldMapExtension extension : extensions.values()) {
            Collection<WorldMapControl> extensionControls = extension.getControls(safeQuery);
            if (extensionControls != null) {
                controls.addAll(extensionControls);
            }
            Collection<WorldMapDrawing> extensionDrawings = extension.getDrawings(safeQuery);
            if (extensionDrawings != null) {
                drawings.addAll(extensionDrawings);
            }
        }
        WorldMapSnapshot snapshot = new WorldMapSnapshot();
        snapshot.setControls(controls);
        snapshot.setDrawings(drawings);
        snapshot.setGeneratedAt(System.currentTimeMillis());
        return snapshot;
    }

    @Override
    public WorldMapActionResult handleAction(WorldMapAction action) {
        if (action == null || action.getExtensionId() == null || action.getExtensionId().isBlank()) {
            return WorldMapActionResult.failure(null, action != null ? action.getActionId() : null, "InvalidMapAction");
        }
        WorldMapExtension extension = extensions.get(action.getExtensionId());
        if (extension == null) {
            return WorldMapActionResult.failure(action.getExtensionId(), action.getActionId(), "MapExtensionNotFound");
        }
        return extension.handleAction(action);
    }
}
