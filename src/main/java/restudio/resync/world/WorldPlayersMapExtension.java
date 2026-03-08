package restudio.resync.world;

import restudio.resync.player.PlayerDossier;
import restudio.resync.player.PlayerFacetState;
import restudio.resync.player.PlayerTrackingService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class WorldPlayersMapExtension implements WorldMapExtension {
    private static final String EXTENSION_ID = "players";
    private static final String FACET_ID = "worldLocation";
    private final PlayerTrackingService trackingService;

    public WorldPlayersMapExtension(PlayerTrackingService trackingService) {
        this.trackingService = trackingService;
    }

    @Override
    public String getExtensionId() {
        return EXTENSION_ID;
    }

    @Override
    public Collection<WorldMapControl> getControls(WorldMapQuery query) {
        List<WorldMapControl> controls = new ArrayList<>();
        WorldMapControl control = new WorldMapControl();
        control.setExtensionId(EXTENSION_ID);
        control.setControlId("playerInfo");
        control.setLabel("PlayerInfo");
        control.setKind("panel");
        controls.add(control);
        return controls;
    }

    @Override
    public Collection<WorldMapDrawing> getDrawings(WorldMapQuery query) {
        if (trackingService == null) {
            return List.of();
        }
        String worldFilter = query == null ? null : query.getWorldName();
        List<WorldMapDrawing> drawings = new ArrayList<>();
        for (PlayerDossier dossier : trackingService.getDossiers()) {
            if (dossier == null || dossier.getFacets() == null) {
                continue;
            }
            PlayerFacetState facet = dossier.getFacets().get(FACET_ID);
            if (facet == null || facet.getData() == null) {
                continue;
            }
            String worldName = readString(facet.getData(), "world");
            if (worldName == null || worldName.isBlank()) {
                continue;
            }
            if (worldFilter != null && !worldFilter.isBlank() && !worldName.equalsIgnoreCase(worldFilter)) {
                continue;
            }
            double x = readDouble(facet.getData(), "x", 0.0);
            double y = readDouble(facet.getData(), "y", 0.0);
            double z = readDouble(facet.getData(), "z", 0.0);
            WorldMapDrawing drawing = new WorldMapDrawing();
            drawing.setExtensionId(EXTENSION_ID);
            drawing.setDrawingId(dossier.getPlayerId());
            drawing.setLabel(dossier.getPlayerName());
            drawing.setKind("player");
            drawing.setWorldName(worldName);
            drawing.setCoordinates(List.of(new WorldMapCoordinate(x, y, z)));
            LinkedHashMap<String, Object> data = new LinkedHashMap<>();
            data.put("playerId", dossier.getPlayerId());
            data.put("playerName", dossier.getPlayerName());
            data.put("online", dossier.isOnline());
            data.put("yaw", readDouble(facet.getData(), "yaw", 0.0));
            data.put("pitch", readDouble(facet.getData(), "pitch", 0.0));
            data.put("gameMode", readString(facet.getData(), "gameMode"));
            data.put("health", readDouble(facet.getData(), "health", 0.0));
            data.put("food", readDouble(facet.getData(), "food", 0.0));
            drawing.setData(data);
            drawings.add(drawing);
        }
        return drawings;
    }

    @Override
    public WorldMapActionResult handleAction(WorldMapAction action) {
        return WorldMapActionResult.failure(EXTENSION_ID, action == null ? null : action.getActionId(), "ActionNotSupported");
    }

    private String readString(Map<String, Object> data, String key) {
        if (data == null || key == null || key.isBlank()) {
            return null;
        }
        Object value = data.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private double readDouble(Map<String, Object> data, String key, double fallback) {
        if (data == null || key == null || key.isBlank()) {
            return fallback;
        }
        Object value = data.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }
}
