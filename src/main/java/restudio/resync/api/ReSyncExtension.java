package restudio.resync.api;

public interface ReSyncExtension {
    String getPluginId();

    String getVersion();

    String getDescription();

    default void initialize(ReSyncExtensionContext context) {
    }

    default void start() {
    }

    default void stop() {
    }
}
