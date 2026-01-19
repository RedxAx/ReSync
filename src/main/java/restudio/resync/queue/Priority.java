package restudio.resync.queue;

public enum Priority {
    CRITICAL(0, 10000),
    HIGH(1, 5000),
    NORMAL(2, 2000),
    LOW(3, 1000),
    BACKGROUND(4, 500);

    private final int value;
    private final int weight;

    Priority(int value, int weight) {
        this.value = value;
        this.weight = weight;
    }

    public int getValue() {
        return value;
    }

    public int getWeight() {
        return weight;
    }

    public static Priority fromValue(int value) {
        for (Priority p : values()) {
            if (p.value == value) {
                return p;
            }
        }
        return NORMAL;
    }
}
