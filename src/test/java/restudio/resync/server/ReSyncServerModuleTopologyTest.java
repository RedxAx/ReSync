package restudio.resync.server;

import org.junit.jupiter.api.Test;
import restudio.resync.modules.Module;
import restudio.resync.modules.ModuleRegistry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncServerModuleTopologyTest {
    @Test
    void productionModuleGraphIsCompleteAndAcyclic() {
        ModuleRegistry registry = new ModuleRegistry();
        List<Module> modules = ReSyncServer.coreModules();
        modules.forEach(registry::registerModule);

        List<String> order = registry.getInitializationOrder();

        assertEquals(modules.size(), order.size());
        assertTrue(order.indexOf("flowJobs") < order.indexOf("worldGen"));
        assertTrue(order.indexOf("playerTracking") < order.indexOf("worldManagement"));
        assertTrue(order.indexOf("worldManagement") < order.indexOf("flow"));
        assertTrue(order.indexOf("flow") < order.indexOf("chat"));
    }
}
