package restudio.resync.world;

public class WorldDashboardEntry {
    private String worldName;
    private String status;
    private int playerCount;
    private String environment;
    private boolean loaded;
    private String difficulty;
    private boolean isolatedPlayerState;
    private boolean timeLockEnabled;
    private boolean weatherLockEnabled;

    public WorldDashboardEntry copy() {
        WorldDashboardEntry copy = new WorldDashboardEntry();
        copy.worldName = worldName;
        copy.status = status;
        copy.playerCount = playerCount;
        copy.environment = environment;
        copy.loaded = loaded;
        copy.difficulty = difficulty;
        copy.isolatedPlayerState = isolatedPlayerState;
        copy.timeLockEnabled = timeLockEnabled;
        copy.weatherLockEnabled = weatherLockEnabled;
        return copy;
    }

    public String getWorldName() {
        return worldName;
    }

    public void setWorldName(String worldName) {
        this.worldName = worldName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getPlayerCount() {
        return playerCount;
    }

    public void setPlayerCount(int playerCount) {
        this.playerCount = playerCount;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public boolean isLoaded() {
        return loaded;
    }

    public void setLoaded(boolean loaded) {
        this.loaded = loaded;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(String difficulty) {
        this.difficulty = difficulty;
    }

    public boolean isIsolatedPlayerState() {
        return isolatedPlayerState;
    }

    public void setIsolatedPlayerState(boolean isolatedPlayerState) {
        this.isolatedPlayerState = isolatedPlayerState;
    }

    public boolean isTimeLockEnabled() {
        return timeLockEnabled;
    }

    public void setTimeLockEnabled(boolean timeLockEnabled) {
        this.timeLockEnabled = timeLockEnabled;
    }

    public boolean isWeatherLockEnabled() {
        return weatherLockEnabled;
    }

    public void setWeatherLockEnabled(boolean weatherLockEnabled) {
        this.weatherLockEnabled = weatherLockEnabled;
    }
}
