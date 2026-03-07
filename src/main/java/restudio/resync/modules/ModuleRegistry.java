package restudio.resync.modules;

import restudio.resync.core.Session;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;

public class ModuleRegistry {
    private final ConcurrentHashMap<String, Module> modules;
    private final ConcurrentHashMap<String, Module> channels;
    private final List<Module> startOrder;
    private final List<String> registrationOrder;

    public ModuleRegistry() {
        this.modules = new ConcurrentHashMap<>();
        this.channels = new ConcurrentHashMap<>();
        this.startOrder = new ArrayList<>();
        this.registrationOrder = new CopyOnWriteArrayList<>();
    }

    public void registerModule(Module module) {
        if (module == null) {
            return;
        }
        modules.put(module.getModuleId(), module);
        registrationOrder.remove(module.getModuleId());
        registrationOrder.add(module.getModuleId());
        for (String channel : module.getChannels()) {
            if (channel != null && !channel.isBlank()) {
                channels.put(channel, module);
            }
        }
    }

    public void unregisterModule(String moduleId) {
        Module module = modules.remove(moduleId);
        if (module != null) {
            registrationOrder.remove(moduleId);
            for (String channel : module.getChannels()) {
                channels.remove(channel, module);
            }
            startOrder.remove(module);
        }
    }

    public void initializeModules(ModuleContext context) {
        startOrder.clear();
        List<Module> ordered = resolveOrder();
        for (Module module : ordered) {
            if (!module.isEnabledByDefault()) {
                continue;
            }
            for (String channel : module.getChannels()) {
                if (channel != null && !channel.isBlank() && context.getChannelMuxer().getChannel(channel) == null) {
                    context.getChannelMuxer().createChannel(channel);
                }
            }
            module.initialize(context);
            module.start(context);
            startOrder.add(module);
        }
    }

    public void shutdownModules(ModuleContext context) {
        for (int i = startOrder.size() - 1; i >= 0; i--) {
            startOrder.get(i).stop(context);
        }
        startOrder.clear();
    }

    public Module getModule(String moduleId) {
        return modules.get(moduleId);
    }

    public Module getModuleByChannel(String channelId) {
        return channels.get(channelId);
    }

    public boolean hasModule(String moduleId) {
        return modules.containsKey(moduleId);
    }

    public void cleanupSession(Session session) {
        for (Module module : startOrder) {
            module.cleanup(session);
        }
    }

    public void tickAll() {
        for (Module module : startOrder) {
            module.onTick();
        }
    }

    public int getModuleCount() {
        return modules.size();
    }

    public Collection<Module> getModules() {
        return List.copyOf(startOrder);
    }

    private List<Module> resolveOrder() {
        List<Module> ordered = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>(registrationOrder);
        while (!stack.isEmpty()) {
            visit(stack.pop(), ordered, visited, visiting);
        }
        return ordered;
    }

    private void visit(String moduleId, List<Module> ordered, Set<String> visited, Set<String> visiting) {
        if (visited.contains(moduleId)) {
            return;
        }
        if (!visiting.add(moduleId)) {
            throw new IllegalStateException("Circular module dependency at " + moduleId);
        }
        Module module = modules.get(moduleId);
        if (module == null) {
            visiting.remove(moduleId);
            return;
        }
        for (String dependency : module.getMetadata().dependencies()) {
            if (!modules.containsKey(dependency)) {
                throw new IllegalStateException("Missing dependency '" + dependency + "' for module '" + moduleId + "'");
            }
            visit(dependency, ordered, visited, visiting);
        }
        visiting.remove(moduleId);
        visited.add(moduleId);
        ordered.add(module);
    }
}
