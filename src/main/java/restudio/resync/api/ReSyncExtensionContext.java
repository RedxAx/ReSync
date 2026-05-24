package restudio.resync.api;

import org.bukkit.plugin.java.JavaPlugin;
import restudio.resync.customcontent.CustomContentProvider;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.property.PropertyHandler;
import restudio.resync.flow.handler.property.PropertyRegistry;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.sync.FlowCategoryMetadata;
import restudio.resync.flow.sync.FlowConversionRule;
import restudio.resync.flow.sync.FlowOptionSourceMetadata;
import restudio.resync.flow.sync.FlowTypeMetadata;
import restudio.resync.modules.Module;
import restudio.resync.world.WorldMapExtension;

public interface ReSyncExtensionContext {
    String pluginId();

    JavaPlugin owner();

    FlowRegistration flow();

    ModuleRegistration modules();

    CustomContentRegistration customContent();

    WorldMapRegistration worldMap();

    OptionCatalogRegistration optionCatalogs();

    ExtensionStorage storage();

    <T> T service(Class<T> type);

    <T> T requiredService(Class<T> type);

    interface FlowRegistration {
        FlowRegistry runtimeRegistry();

        NodeDefinitionRegistry nodeDefinitions();

        HandlerRegistry handlers();

        PropertyRegistry properties();

        void registerNode(NodeDefinition definition);

        void registerNodes(String resourcePath);

        void registerHandler(String handlerId, NodeHandler handler);

        void registerProperty(String family, String property, PropertyHandler handler);

        void registerType(FlowTypeMetadata metadata);

        void registerCategory(FlowCategoryMetadata metadata);

        void registerConversion(FlowConversionRule rule);

        void registerOptionSource(FlowOptionSourceMetadata metadata);
    }

    interface ModuleRegistration {
        void register(Module module);

        void unregister(String moduleId);
    }

    interface CustomContentRegistration {
        void register(CustomContentProvider provider);
    }

    interface WorldMapRegistration {
        void register(WorldMapExtension extension);
    }

    interface OptionCatalogRegistration {
        void register(OptionCatalogProvider provider);
    }
}
