package restudio.resync.world;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class WorldPortalMapExtension implements WorldMapExtension {
    private static final String EXTENSION_ID = "portals";
    private final WorldManagementService worldManagementService;

    public WorldPortalMapExtension(WorldManagementService worldManagementService) {
        this.worldManagementService = worldManagementService;
    }

    @Override
    public String getExtensionId() {
        return EXTENSION_ID;
    }

    @Override
    public Collection<WorldMapControl> getControls(WorldMapQuery query) {
        return List.of();
    }

    @Override
    public Collection<WorldMapDrawing> getDrawings(WorldMapQuery query) {
        String worldName = query == null ? null : query.getWorldName();
        List<WorldPortal> portals = worldName == null || worldName.isBlank()
            ? worldManagementService.getPortals()
            : worldManagementService.getPortalsByWorld(worldName);
        List<WorldMapDrawing> drawings = new ArrayList<>();
        for (WorldPortal portal : portals) {
            if (portal == null) {
                continue;
            }
            WorldMapDrawing drawing = new WorldMapDrawing();
            drawing.setExtensionId(EXTENSION_ID);
            drawing.setDrawingId(portal.getPortalId());
            drawing.setLabel(portal.getPortalName());
            drawing.setKind("portalRegion");
            drawing.setWorldName(portal.getSourceWorld());
            drawing.setCoordinates(List.of(
                new WorldMapCoordinate(portal.getMinX(), portal.getMinY(), portal.getMinZ()),
                new WorldMapCoordinate(portal.getMaxX(), portal.getMaxY(), portal.getMaxZ()),
                new WorldMapCoordinate(portal.getDestinationX(), portal.getDestinationY(), portal.getDestinationZ())
            ));
            LinkedHashMap<String, Object> data = new LinkedHashMap<>();
            data.put("enabled", portal.isEnabled());
            data.put("destinationWorld", portal.getDestinationWorld());
            data.put("destinationYaw", portal.getDestinationYaw());
            data.put("destinationPitch", portal.getDestinationPitch());
            data.put("lastUsedAt", portal.getLastUsedAt());
            drawing.setData(data);
            drawings.add(drawing);
        }
        return drawings;
    }

    @Override
    public WorldMapActionResult handleAction(WorldMapAction action) {
        return WorldMapActionResult.failure(EXTENSION_ID, action == null ? null : action.getActionId(), "PortalMapActionsRemoved");
    }
}
