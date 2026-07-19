package restudio.resync.flow.handler.generic;

import java.util.concurrent.atomic.AtomicBoolean;

public final class ScheduleExecutionGate {
    private final AtomicBoolean running = new AtomicBoolean();

    public boolean tryBegin() {
        return running.compareAndSet(false, true);
    }

    public void complete() {
        running.set(false);
    }

    public boolean isRunning() {
        return running.get();
    }
}
