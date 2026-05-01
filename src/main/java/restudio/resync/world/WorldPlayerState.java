package restudio.resync.world;

public class WorldPlayerState {
    private String worldName;
    private String gameMode;
    private double health;
    private int foodLevel;
    private float saturation;
    private float exhaustion;
    private float expProgress;
    private int expLevel;
    private int totalExp;
    private String inventory;
    private String armor;
    private String offhand;
    private String enderChest;
    private String extra;
    private long updatedAt;

    public WorldPlayerState copy() {
        WorldPlayerState copy = new WorldPlayerState();
        copy.worldName = worldName;
        copy.gameMode = gameMode;
        copy.health = health;
        copy.foodLevel = foodLevel;
        copy.saturation = saturation;
        copy.exhaustion = exhaustion;
        copy.expProgress = expProgress;
        copy.expLevel = expLevel;
        copy.totalExp = totalExp;
        copy.inventory = inventory;
        copy.armor = armor;
        copy.offhand = offhand;
        copy.enderChest = enderChest;
        copy.extra = extra;
        copy.updatedAt = updatedAt;
        return copy;
    }

    public String getWorldName() {
        return worldName;
    }

    public void setWorldName(String worldName) {
        this.worldName = worldName;
    }

    public String getGameMode() {
        return gameMode;
    }

    public void setGameMode(String gameMode) {
        this.gameMode = gameMode;
    }

    public double getHealth() {
        return health;
    }

    public void setHealth(double health) {
        this.health = health;
    }

    public int getFoodLevel() {
        return foodLevel;
    }

    public void setFoodLevel(int foodLevel) {
        this.foodLevel = foodLevel;
    }

    public float getSaturation() {
        return saturation;
    }

    public void setSaturation(float saturation) {
        this.saturation = saturation;
    }

    public float getExhaustion() {
        return exhaustion;
    }

    public void setExhaustion(float exhaustion) {
        this.exhaustion = exhaustion;
    }

    public float getExpProgress() {
        return expProgress;
    }

    public void setExpProgress(float expProgress) {
        this.expProgress = expProgress;
    }

    public int getExpLevel() {
        return expLevel;
    }

    public void setExpLevel(int expLevel) {
        this.expLevel = expLevel;
    }

    public int getTotalExp() {
        return totalExp;
    }

    public void setTotalExp(int totalExp) {
        this.totalExp = totalExp;
    }

    public String getInventory() {
        return inventory;
    }

    public void setInventory(String inventory) {
        this.inventory = inventory;
    }

    public String getArmor() {
        return armor;
    }

    public void setArmor(String armor) {
        this.armor = armor;
    }

    public String getOffhand() {
        return offhand;
    }

    public void setOffhand(String offhand) {
        this.offhand = offhand;
    }

    public String getEnderChest() {
        return enderChest;
    }

    public void setEnderChest(String enderChest) {
        this.enderChest = enderChest;
    }

    public String getExtra() {
        return extra;
    }

    public void setExtra(String extra) {
        this.extra = extra;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
