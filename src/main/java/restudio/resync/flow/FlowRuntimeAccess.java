package restudio.resync.flow;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.function.Supplier;

public final class FlowRuntimeAccess {
    private static volatile Supplier<FlowStorage> storageSupplier;
    private static volatile Supplier<Map<String, Object>> globalVariablesSupplier;
    private static volatile JavaPlugin plugin;

    private FlowRuntimeAccess() {
    }

    public static void configure(JavaPlugin runtimePlugin, Supplier<FlowStorage> storage, Supplier<Map<String, Object>> globals) {
        plugin = runtimePlugin;
        storageSupplier = storage;
        globalVariablesSupplier = globals;
    }

    public static void clear() {
        plugin = null;
        storageSupplier = null;
        globalVariablesSupplier = null;
    }

    public static JavaPlugin getPlugin() {
        return plugin;
    }

    public static FlowStorage getStorage() {
        return storageSupplier != null ? storageSupplier.get() : null;
    }

    public static Map<String, Object> getGlobalVariables() {
        return globalVariablesSupplier != null ? globalVariablesSupplier.get() : null;
    }
}
