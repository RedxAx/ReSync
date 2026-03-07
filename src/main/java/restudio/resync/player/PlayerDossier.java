package restudio.resync.player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PlayerDossier {
    private String playerId;
    private String playerName;
    private boolean online;
    private long firstSeenAt;
    private long lastSeenAt;
    private long totalPlayTimeMs;
    private PlayerSessionRecord activeSession;
    private List<PlayerSessionRecord> sessions = new ArrayList<>();
    private List<PlayerEventRecord> recentEvents = new ArrayList<>();
    private Map<String, PlayerFacetState> facets = new LinkedHashMap<>();

    public PlayerDossier copy() {
        PlayerDossier copy = new PlayerDossier();
        copy.playerId = playerId;
        copy.playerName = playerName;
        copy.online = online;
        copy.firstSeenAt = firstSeenAt;
        copy.lastSeenAt = lastSeenAt;
        copy.totalPlayTimeMs = totalPlayTimeMs;
        copy.activeSession = activeSession == null ? null : activeSession.copy();
        copy.sessions = new ArrayList<>();
        for (PlayerSessionRecord session : sessions) {
            copy.sessions.add(session.copy());
        }
        copy.recentEvents = new ArrayList<>();
        for (PlayerEventRecord event : recentEvents) {
            copy.recentEvents.add(event.copy());
        }
        copy.facets = new LinkedHashMap<>();
        for (Map.Entry<String, PlayerFacetState> entry : facets.entrySet()) {
            copy.facets.put(entry.getKey(), entry.getValue().copy());
        }
        return copy;
    }

    public String getPlayerId() {
        return playerId;
    }

    public void setPlayerId(String playerId) {
        this.playerId = playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public void setPlayerName(String playerName) {
        this.playerName = playerName;
    }

    public boolean isOnline() {
        return online;
    }

    public void setOnline(boolean online) {
        this.online = online;
    }

    public long getFirstSeenAt() {
        return firstSeenAt;
    }

    public void setFirstSeenAt(long firstSeenAt) {
        this.firstSeenAt = firstSeenAt;
    }

    public long getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(long lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public long getTotalPlayTimeMs() {
        return totalPlayTimeMs;
    }

    public void setTotalPlayTimeMs(long totalPlayTimeMs) {
        this.totalPlayTimeMs = totalPlayTimeMs;
    }

    public PlayerSessionRecord getActiveSession() {
        return activeSession;
    }

    public void setActiveSession(PlayerSessionRecord activeSession) {
        this.activeSession = activeSession;
    }

    public List<PlayerSessionRecord> getSessions() {
        return sessions;
    }

    public void setSessions(List<PlayerSessionRecord> sessions) {
        this.sessions = sessions == null ? new ArrayList<>() : sessions;
    }

    public List<PlayerEventRecord> getRecentEvents() {
        return recentEvents;
    }

    public void setRecentEvents(List<PlayerEventRecord> recentEvents) {
        this.recentEvents = recentEvents == null ? new ArrayList<>() : recentEvents;
    }

    public Map<String, PlayerFacetState> getFacets() {
        return facets;
    }

    public void setFacets(Map<String, PlayerFacetState> facets) {
        this.facets = facets == null ? new LinkedHashMap<>() : facets;
    }
}
