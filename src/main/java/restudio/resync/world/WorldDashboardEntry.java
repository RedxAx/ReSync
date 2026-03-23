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
    private String alias;
    private boolean hidden;
    private boolean forceGameMode;
    private String gameMode;
    private boolean entryFeeEnabled;
    private double entryFee;
    private boolean pvpEnabled;
    private boolean keepSpawnLoaded;
    private boolean autoSaveEnabled;
    private boolean animalSpawnsEnabled;
    private boolean monsterSpawnsEnabled;
    private boolean hungerEnabled;
    private boolean autoHealEnabled;
    private boolean bedRespawnEnabled;
    private boolean anchorRespawnEnabled;

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
        copy.alias = alias;
        copy.hidden = hidden;
        copy.forceGameMode = forceGameMode;
        copy.gameMode = gameMode;
        copy.entryFeeEnabled = entryFeeEnabled;
        copy.entryFee = entryFee;
        copy.pvpEnabled = pvpEnabled;
        copy.keepSpawnLoaded = keepSpawnLoaded;
        copy.autoSaveEnabled = autoSaveEnabled;
        copy.animalSpawnsEnabled = animalSpawnsEnabled;
        copy.monsterSpawnsEnabled = monsterSpawnsEnabled;
        copy.hungerEnabled = hungerEnabled;
        copy.autoHealEnabled = autoHealEnabled;
        copy.bedRespawnEnabled = bedRespawnEnabled;
        copy.anchorRespawnEnabled = anchorRespawnEnabled;
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

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public boolean isHidden() {
        return hidden;
    }

    public void setHidden(boolean hidden) {
        this.hidden = hidden;
    }

    public boolean isForceGameMode() {
        return forceGameMode;
    }

    public void setForceGameMode(boolean forceGameMode) {
        this.forceGameMode = forceGameMode;
    }

    public String getGameMode() {
        return gameMode;
    }

    public void setGameMode(String gameMode) {
        this.gameMode = gameMode;
    }

    public boolean isEntryFeeEnabled() {
        return entryFeeEnabled;
    }

    public void setEntryFeeEnabled(boolean entryFeeEnabled) {
        this.entryFeeEnabled = entryFeeEnabled;
    }

    public double getEntryFee() {
        return entryFee;
    }

    public void setEntryFee(double entryFee) {
        this.entryFee = entryFee;
    }

    public boolean isPvpEnabled() {
        return pvpEnabled;
    }

    public void setPvpEnabled(boolean pvpEnabled) {
        this.pvpEnabled = pvpEnabled;
    }

    public boolean isKeepSpawnLoaded() {
        return keepSpawnLoaded;
    }

    public void setKeepSpawnLoaded(boolean keepSpawnLoaded) {
        this.keepSpawnLoaded = keepSpawnLoaded;
    }

    public boolean isAutoSaveEnabled() {
        return autoSaveEnabled;
    }

    public void setAutoSaveEnabled(boolean autoSaveEnabled) {
        this.autoSaveEnabled = autoSaveEnabled;
    }

    public boolean isAnimalSpawnsEnabled() {
        return animalSpawnsEnabled;
    }

    public void setAnimalSpawnsEnabled(boolean animalSpawnsEnabled) {
        this.animalSpawnsEnabled = animalSpawnsEnabled;
    }

    public boolean isMonsterSpawnsEnabled() {
        return monsterSpawnsEnabled;
    }

    public void setMonsterSpawnsEnabled(boolean monsterSpawnsEnabled) {
        this.monsterSpawnsEnabled = monsterSpawnsEnabled;
    }

    public boolean isHungerEnabled() {
        return hungerEnabled;
    }

    public void setHungerEnabled(boolean hungerEnabled) {
        this.hungerEnabled = hungerEnabled;
    }

    public boolean isAutoHealEnabled() {
        return autoHealEnabled;
    }

    public void setAutoHealEnabled(boolean autoHealEnabled) {
        this.autoHealEnabled = autoHealEnabled;
    }

    public boolean isBedRespawnEnabled() {
        return bedRespawnEnabled;
    }

    public void setBedRespawnEnabled(boolean bedRespawnEnabled) {
        this.bedRespawnEnabled = bedRespawnEnabled;
    }

    public boolean isAnchorRespawnEnabled() {
        return anchorRespawnEnabled;
    }

    public void setAnchorRespawnEnabled(boolean anchorRespawnEnabled) {
        this.anchorRespawnEnabled = anchorRespawnEnabled;
    }
}
