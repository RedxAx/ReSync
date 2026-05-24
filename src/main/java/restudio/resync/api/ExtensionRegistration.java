package restudio.resync.api;

public interface ExtensionRegistration extends AutoCloseable {
    String pluginId();

    @Override
    void close();
}
