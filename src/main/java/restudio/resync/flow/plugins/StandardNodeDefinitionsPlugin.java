package restudio.resync.flow.plugins;

import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.definitions.*;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

public class StandardNodeDefinitionsPlugin implements FlowNodePlugin {
    public static final String PLUGIN_ID = "standard";

    @Override
    public String getPluginId() {
        return PLUGIN_ID;
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "Built-in flow node definitions";
    }

    @Override
    public void registerNodes(FlowRegistry registry) {
    }

    @Override
    public void registerNodeDefinitions(NodeDefinitionRegistry registry) {
        registry.setDefaultPluginId(PLUGIN_ID);
        new CoreNodes().registerNodes(registry);
        new MathNodes().registerNodes(registry);
        new LogicNodes().registerNodes(registry);
        new StringNodes().registerNodes(registry);
        new TextFormattingNodes().registerNodes(registry);
        new FlowControlNodes().registerNodes(registry);
        new BlockNodes().registerNodes(registry);
        new RegionNodes().registerNodes(registry);
        new WorldStateNodes().registerNodes(registry);
        new PlayerEventNodes().registerNodes(registry);
        new EntityEventNodes().registerNodes(registry);
        new WorldEventNodes().registerNodes(registry);
        new PlayerActionNodes().registerNodes(registry);
        new PlayerInventoryNodes().registerNodes(registry);
        new PlayerMessagingNodes().registerNodes(registry);
        new EntitySpawnNodes().registerNodes(registry);
        new EntityControlNodes().registerNodes(registry);
        new EntityQueryNodes().registerNodes(registry);
        new InventoryNodes().registerNodes(registry);
        new ItemCreationNodes().registerNodes(registry);
        new MenuNodes().registerNodes(registry);
        new VariableNodes().registerNodes(registry);
        new FileNodes().registerNodes(registry);
        new JsonNodes().registerNodes(registry);
        new TimeNodes().registerNodes(registry);
        new RandomNodes().registerNodes(registry);
        new ConversionNodes().registerNodes(registry);
        new DebugNodes().registerNodes(registry);
        new SystemNodes().registerNodes(registry);
        new SystemEventNodes().registerNodes(registry);
        new CustomEventNodes().registerNodes(registry);
        new SoundNodes().registerNodes(registry);
        new ParticleNodes().registerNodes(registry);
        new TitleNodes().registerNodes(registry);
        new EconomyNodes().registerNodes(registry);
        new PermissionNodes().registerNodes(registry);
        new ListNodes().registerNodes(registry);
        new ListTransformNodes().registerNodes(registry);
        new ListAdvancedNodes().registerNodes(registry);
        new ScoreboardNodes().registerNodes(registry);
        new TeamNodes().registerNodes(registry);
        new LocationNodes().registerNodes(registry);
        new MathAdvancedNodes().registerNodes(registry);
        new UtilityNodes().registerNodes(registry);
        new EntityAdvancedNodes().registerNodes(registry);
        new ItemNodes().registerNodes(registry);
        new PlayerQueryNodes().registerNodes(registry);
        new StringAdvancedNodes().registerNodes(registry);
        new DataStructureNodes().registerNodes(registry);
        new RegionAdvancedNodes().registerNodes(registry);
        new HttpNodes().registerNodes(registry);
        new DiscordNodes().registerNodes(registry);
    }
}
