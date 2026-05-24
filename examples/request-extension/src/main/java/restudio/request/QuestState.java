package restudio.request;

public class QuestState {
    private final String questId;
    private int progress;
    private long startedAt;
    private long completedAt;
    private long quitAt;
    private long lastProgressAt;

    public QuestState(String questId) {
        this.questId = questId;
        this.startedAt = System.currentTimeMillis();
        this.lastProgressAt = this.startedAt;
    }

    private QuestState(String questId, int progress, long startedAt, long completedAt, long quitAt, long lastProgressAt) {
        this.questId = questId;
        this.progress = Math.max(0, progress);
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.quitAt = quitAt;
        this.lastProgressAt = lastProgressAt > 0 ? lastProgressAt : startedAt;
    }

    public static QuestState restore(String questId, int progress, long startedAt, long completedAt, long quitAt) {
        return restore(questId, progress, startedAt, completedAt, quitAt, startedAt);
    }

    public static QuestState restore(String questId, int progress, long startedAt, long completedAt, long quitAt, long lastProgressAt) {
        return new QuestState(questId, progress, startedAt, completedAt, quitAt, lastProgressAt);
    }

    public String questId() {
        return questId;
    }

    public int progress() {
        return progress;
    }

    public void progress(int progress) {
        this.progress = Math.max(0, progress);
        lastProgressAt = System.currentTimeMillis();
    }

    public long startedAt() {
        return startedAt;
    }

    public long completedAt() {
        return completedAt;
    }

    public void complete() {
        completedAt = System.currentTimeMillis();
    }

    public long quitAt() {
        return quitAt;
    }

    public void quit() {
        quitAt = System.currentTimeMillis();
    }

    public long lastProgressAt() {
        return lastProgressAt;
    }

    public long timeToComplete() {
        return completedAt > 0 && startedAt > 0 ? completedAt - startedAt : 0L;
    }

    public long activeTime() {
        if (startedAt <= 0 || completedAt > 0 || quitAt > 0) {
            return 0L;
        }
        return System.currentTimeMillis() - startedAt;
    }

    public boolean active() {
        return startedAt > 0 && completedAt == 0 && quitAt == 0;
    }

    public boolean completed() {
        return completedAt > 0;
    }

    public boolean abandoned() {
        return quitAt > 0;
    }
}
