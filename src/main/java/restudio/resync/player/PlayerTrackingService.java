package restudio.resync.player;

import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

public interface PlayerTrackingService {
    Collection<PlayerDossier> getDossiers();

    PlayerDossier getDossier(UUID playerId);

    void markOnline(Player player, String source);

    void markOffline(UUID playerId, String playerName, String source);

    void recordEvent(UUID playerId, String playerName, String moduleId, String category, String type, Map<String, Object> data);

    void upsertFacet(UUID playerId, String playerName, String facetId, String moduleId, Map<String, Object> data);

    void removeFacet(UUID playerId, String facetId);

    void addListener(PlayerTrackingListener listener);

    void removeListener(PlayerTrackingListener listener);
}
