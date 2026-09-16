package restudio.resync.modules.flow;

@FunctionalInterface
public interface CoreResourceMutationRegistration extends AutoCloseable {
    @Override
    void close();
}
