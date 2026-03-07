package restudio.resync.player;

public class PlayerSessionRecord {
    private String sessionId;
    private String source;
    private long startedAt;
    private long endedAt;
    private long durationMs;

    public PlayerSessionRecord copy() {
        PlayerSessionRecord copy = new PlayerSessionRecord();
        copy.sessionId = sessionId;
        copy.source = source;
        copy.startedAt = startedAt;
        copy.endedAt = endedAt;
        copy.durationMs = durationMs;
        return copy;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(long startedAt) {
        this.startedAt = startedAt;
    }

    public long getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(long endedAt) {
        this.endedAt = endedAt;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }
}
