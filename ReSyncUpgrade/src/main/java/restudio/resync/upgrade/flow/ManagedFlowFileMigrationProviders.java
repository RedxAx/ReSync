package restudio.resync.upgrade.flow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;

public final class ManagedFlowFileMigrationProviders {
    private ManagedFlowFileMigrationProviders() {
    }

    public static List<ManagedFlowFileMigrationProvider> discover() {
        List<ManagedFlowFileMigrationProvider> providers = new ArrayList<>();
        ServiceLoader.load(ManagedFlowFileMigrationProvider.class).forEach(providers::add);
        providers.sort(Comparator.comparing(ManagedFlowFileMigrationProvider::id));
        Set<String> ids = new HashSet<>();
        for (ManagedFlowFileMigrationProvider provider : providers) {
            if (provider.id() == null || provider.id().isBlank() || !ids.add(provider.id())) {
                throw new IllegalStateException("Duplicate or invalid managed flow-file migration provider");
            }
        }
        return List.copyOf(providers);
    }
}
