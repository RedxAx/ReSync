package restudio.resync.memory;

import restudio.resync.core.Session;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class MemoryMonitor {
    private final MemoryMXBean memoryMXBean;
    private final Set<Session> trackedSessions;
    private final long maxMemoryForSessions;
    private final long jvmMaxMemory;
    private volatile boolean memoryPressure;

    public MemoryMonitor(double sessionMemoryRatio) {
        this.memoryMXBean = ManagementFactory.getMemoryMXBean();
        this.trackedSessions = ConcurrentHashMap.newKeySet();
        this.jvmMaxMemory = Runtime.getRuntime().maxMemory();
        this.maxMemoryForSessions = (long) (jvmMaxMemory * sessionMemoryRatio);
        this.memoryPressure = false;

        startMonitoring();
    }

    public void trackSession(Session session) {
        trackedSessions.add(session);
    }

    public void untrackSession(Session session) {
        trackedSessions.remove(session);
    }

    public long getMaxMemoryForSessions() {
        return maxMemoryForSessions;
    }

    public long getUsedMemory() {
        MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
        return heapUsage.getUsed();
    }

    public long getFreeMemory() {
        return jvmMaxMemory - getUsedMemory();
    }

    public double getMemoryUsageRatio() {
        return (double) getUsedMemory() / jvmMaxMemory;
    }

    public boolean isMemoryPressure() {
        return memoryPressure;
    }

    public int getSessionCount() {
        return trackedSessions.size();
    }

    private void startMonitoring() {
        Thread monitorThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    checkMemory();
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "ReSync-MemoryMonitor");
        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    private void checkMemory() {
        double ratio = getMemoryUsageRatio();
        memoryPressure = ratio > 0.8;

        if (memoryPressure) {
            System.gc();
        }
    }
}
