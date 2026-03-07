package restudio.resync.player;

import restudio.resync.core.Session;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DefaultPlayerSessionLinkService implements PlayerSessionLinkService {
    private final Map<UUID, Session> sessionsByPlayer = new ConcurrentHashMap<>();
    private final Map<String, UUID> playersBySessionId = new ConcurrentHashMap<>();

    @Override
    public Session getLinkedSession(UUID playerId) {
        return playerId == null ? null : sessionsByPlayer.get(playerId);
    }

    @Override
    public UUID getLinkedPlayer(Session session) {
        return session == null ? null : playersBySessionId.get(session.getSessionId());
    }

    @Override
    public void link(UUID playerId, Session session) {
        if (playerId == null || session == null) {
            return;
        }
        UUID previousPlayer = playersBySessionId.put(session.getSessionId(), playerId);
        if (previousPlayer != null && !previousPlayer.equals(playerId)) {
            sessionsByPlayer.remove(previousPlayer, session);
        }
        Session previousSession = sessionsByPlayer.put(playerId, session);
        if (previousSession != null && previousSession != session) {
            playersBySessionId.remove(previousSession.getSessionId(), playerId);
        }
    }

    @Override
    public void unlinkPlayer(UUID playerId) {
        if (playerId == null) {
            return;
        }
        Session session = sessionsByPlayer.remove(playerId);
        if (session != null) {
            playersBySessionId.remove(session.getSessionId(), playerId);
        }
    }

    @Override
    public void unlinkSession(Session session) {
        if (session == null) {
            return;
        }
        UUID playerId = playersBySessionId.remove(session.getSessionId());
        if (playerId != null) {
            sessionsByPlayer.remove(playerId, session);
        }
    }
}
