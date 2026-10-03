package restudio.resync.api;

import org.bukkit.plugin.java.JavaPlugin;
import restudio.resync.ReSync;
import restudio.resync.qa.QaService;

public final class ReSyncApi {
    private ReSyncApi() {
    }

    public static QaService qa() {
        ReSync instance = ReSync.getInstance();
        QaService service = instance == null || instance.getReSyncServer() == null ? null : instance.getReSyncServer().getQaService();
        if (service == null) throw new IllegalStateException("ReSync QA is not available");
        return service;
    }

    public static ExtensionRegistration registerExtension(JavaPlugin owner, ReSyncExtension extension) {
        if (owner == null) {
            throw new IllegalArgumentException("Owner plugin is required");
        }
        if (extension == null) {
            throw new IllegalArgumentException("Extension is required");
        }
        ReSync instance = ReSync.getInstance();
        if (instance == null || instance.getReSyncServer() == null) {
            throw new IllegalStateException("ReSync server is not available");
        }
        return instance.getReSyncServer().getExtensionManager().registerBukkitExtension(owner, extension);
    }
}
