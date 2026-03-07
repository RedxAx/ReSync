package restudio.resync.player;

import restudio.resync.core.Session;

import java.util.UUID;

public interface PlayerSessionLinkService {
    Session getLinkedSession(UUID playerId);

    UUID getLinkedPlayer(Session session);

    void link(UUID playerId, Session session);

    void unlinkPlayer(UUID playerId);

    void unlinkSession(Session session);
}
