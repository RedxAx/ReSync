package restudio.resync.flow.runtime;

import java.util.List;

public interface RuntimeProviderLifecycle {
    boolean ready(RuntimeProviderDescriptor provider, List<RuntimeBindingDescriptor> bindings);

    void compensate();

    void shutdown();

    static RuntimeProviderLifecycle stateless() {
        return new RuntimeProviderLifecycle() {
            @Override
            public boolean ready(RuntimeProviderDescriptor provider, List<RuntimeBindingDescriptor> bindings) {
                return true;
            }

            @Override
            public void compensate() {
            }

            @Override
            public void shutdown() {
            }
        };
    }
}
