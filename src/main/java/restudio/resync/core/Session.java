package restudio.resync.core;

import restudio.resync.modules.Module;
import restudio.resync.security.ClientIdentity;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class Session {
    private final String sessionId;
    private final String clientId;
    private final ConnectionInfo connection;
    private final ClientIdentity identity;
    private final Set<String> subscribedChannels;
    private final ConcurrentHashMap<String, Module> activeModules;
    private final long creationTime;
    private volatile long lastActivity;
    private long memoryUsage;

    public Session(String sessionId, String clientId, ConnectionInfo connection) {
        this(sessionId, clientId, connection, null);
    }

    public Session(String sessionId, String clientId, ConnectionInfo connection, ClientIdentity identity) {
        this.sessionId = sessionId;
        this.clientId = clientId;
        this.connection = connection;
        this.identity = identity;
        this.subscribedChannels = ConcurrentHashMap.newKeySet();
        this.activeModules = new ConcurrentHashMap<>();
        this.creationTime = System.currentTimeMillis();
        this.lastActivity = System.currentTimeMillis();
        this.memoryUsage = 0;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getClientId() {
        return clientId;
    }

    public ConnectionInfo getConnection() {
        return connection;
    }

    public ClientIdentity getIdentity() {
        return identity;
    }

    public Set<String> getSubscribedChannels() {
        return subscribedChannels;
    }

    public void subscribeChannel(String channelId) {
        subscribedChannels.add(channelId);
        updateActivity();
    }

    public void unsubscribeChannel(String channelId) {
        subscribedChannels.remove(channelId);
        updateActivity();
    }

    public void addModule(Module module) {
        activeModules.put(module.getChannelId(), module);
        updateActivity();
    }

    public boolean removeModule(String channelId) {
        Module module = activeModules.remove(channelId);
        if (module != null) {
            module.cleanup(this);
            updateActivity();
            return true;
        }
        updateActivity();
        return false;
    }

    public void updateActivity() {
        this.lastActivity = System.currentTimeMillis();
    }

    public long getLastActivity() {
        return lastActivity;
    }

    public long getIdleTime() {
        return System.currentTimeMillis() - lastActivity;
    }

    public long getMemoryUsage() {
        return memoryUsage;
    }

    public void setMemoryUsage(long memoryUsage) {
        this.memoryUsage = memoryUsage;
    }

    public void addMemoryUsage(long bytes) {
        this.memoryUsage += bytes;
    }

    public long getAge() {
        return System.currentTimeMillis() - creationTime;
    }
}
