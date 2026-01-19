package restudio.resync.modules;

import restudio.resync.core.Session;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ModuleRegistry {
    private final ConcurrentHashMap<String, Module> modules;
    private final ConcurrentHashMap<String, String> channelIdToModule;

    public ModuleRegistry() {
        this.modules = new ConcurrentHashMap<>();
        this.channelIdToModule = new ConcurrentHashMap<>();
    }

    public void registerModule(Module module) {
        modules.put(module.getChannelId(), module);
        channelIdToModule.put(module.getChannelId(), module.getChannelId());
    }

    public void unregisterModule(String channelId) {
        Module module = modules.remove(channelId);
        if (module != null) {
            channelIdToModule.remove(channelId);
        }
    }

    public Module getModule(String channelId) {
        return modules.get(channelId);
    }

    public boolean hasModule(String channelId) {
        return modules.containsKey(channelId);
    }

    public void cleanupSession(Session session) {
        for (Module module : modules.values()) {
            module.cleanup(session);
        }
    }

    public void tickAll() {
        for (Module module : modules.values()) {
            module.onTick();
        }
    }

    public int getModuleCount() {
        return modules.size();
    }
}
