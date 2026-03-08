package restudio.resync.world;

import java.util.LinkedHashMap;
import java.util.Map;

public class WorldRegistryEntry {
    private String worldName;
    private String environment;
    private String generator;
    private String difficulty;
    private boolean loaded;
    private boolean isolatedPlayerState;
    private boolean timeLockEnabled;
    private long lockedTime;
    private boolean weatherLockEnabled;
    private boolean lockedStorm;
    private boolean lockedThundering;
    private Map<String, String> gameRules = new LinkedHashMap<>();
    private long updatedAt;

    public WorldRegistryEntry copy() {
        WorldRegistryEntry copy = new WorldRegistryEntry();
        copy.worldName = worldName;
        copy.environment = environment;
        copy.generator = generator;
        copy.difficulty = difficulty;
        copy.loaded = loaded;
        copy.isolatedPlayerState = isolatedPlayerState;
        copy.timeLockEnabled = timeLockEnabled;
        copy.lockedTime = lockedTime;
        copy.weatherLockEnabled = weatherLockEnabled;
        copy.lockedStorm = lockedStorm;
        copy.lockedThundering = lockedThundering;
        copy.gameRules = gameRules == null ? new LinkedHashMap<>() : new LinkedHashMap<>(gameRules);
        copy.updatedAt = updatedAt;
        return copy;
    }

    public String getWorldName() {
        return worldName;
    }

    public void setWorldName(String worldName) {
        this.worldName = worldName;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public String getGenerator() {
        return generator;
    }

    public void setGenerator(String generator) {
        this.generator = generator;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(String difficulty) {
        this.difficulty = difficulty;
    }

    public boolean isLoaded() {
        return loaded;
    }

    public void setLoaded(boolean loaded) {
        this.loaded = loaded;
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

    public long getLockedTime() {
        return lockedTime;
    }

    public void setLockedTime(long lockedTime) {
        this.lockedTime = lockedTime;
    }

    public boolean isWeatherLockEnabled() {
        return weatherLockEnabled;
    }

    public void setWeatherLockEnabled(boolean weatherLockEnabled) {
        this.weatherLockEnabled = weatherLockEnabled;
    }

    public boolean isLockedStorm() {
        return lockedStorm;
    }

    public void setLockedStorm(boolean lockedStorm) {
        this.lockedStorm = lockedStorm;
    }

    public boolean isLockedThundering() {
        return lockedThundering;
    }

    public void setLockedThundering(boolean lockedThundering) {
        this.lockedThundering = lockedThundering;
    }

    public Map<String, String> getGameRules() {
        return gameRules;
    }

    public void setGameRules(Map<String, String> gameRules) {
        this.gameRules = gameRules == null ? new LinkedHashMap<>() : new LinkedHashMap<>(gameRules);
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
