package restudio.resync.modules;

import org.bukkit.Bukkit;
import restudio.flow.data.FlowJobReference;
import restudio.resync.flow.jobs.FlowJobCompletedEvent;
import restudio.resync.flow.jobs.FlowJobRegistry;

import java.util.function.Consumer;

public final class FlowJobModule implements Module {
    private static final ModuleMetadata METADATA = ModuleMetadata.of("flowJobs", "FlowJobs");
    private FlowJobRegistry registry;
    private Consumer<FlowJobReference.Snapshot<?>> completionListener;

    @Override
    public ModuleMetadata getMetadata() {
        return METADATA;
    }

    @Override
    public void initialize(ModuleContext context) {
        registry = new FlowJobRegistry();
        completionListener = snapshot -> {
            if (snapshot == null || !terminal(snapshot.state())) {
                return;
            }
            Runnable dispatch = () -> Bukkit.getPluginManager().callEvent(new FlowJobCompletedEvent(snapshot));
            if (Bukkit.isPrimaryThread()) {
                dispatch.run();
            } else {
                Bukkit.getScheduler().runTask(context.getPlugin(), dispatch);
            }
        };
        registry.addListener(completionListener);
        context.registerService(FlowJobRegistry.class, registry);
    }

    @Override
    public void stop(ModuleContext context) {
        if (registry != null) {
            registry.removeListener(completionListener);
            registry.shutdown();
        }
    }

    private boolean terminal(FlowJobReference.State state) {
        return state == FlowJobReference.State.SUCCEEDED || state == FlowJobReference.State.FAILED || state == FlowJobReference.State.CANCELLED;
    }
}
