package restudio.request;

public class PlayerQuestProfile {
    private int xp;

    public int xp() {
        return xp;
    }

    public void addXp(int amount) {
        xp += Math.max(0, amount);
    }

    public void xp(int xp) {
        this.xp = Math.max(0, xp);
    }

    public int level() {
        return Math.max(1, (xp / 100) + 1);
    }

    public int xpToNextLevel() {
        return level() * 100 - xp;
    }
}
