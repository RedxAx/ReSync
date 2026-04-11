package restudio.resync.flow;

import org.bukkit.Bukkit;
import restudio.resync.Log;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.registry.NodeRegistrar;

public class StandardNodes {

    public static void registerAll(FlowRegistry registry) {
        registerAll(registry, null);
    }

    public static void registerAll(FlowRegistry registry, NodeDefinitionRegistry definitionRegistry) {
        NodeRegistrar registrar = new NodeRegistrar(registry, definitionRegistry, "standard");

        registrar.scan(new restudio.resync.flow.nodes.CoreEventNodes());
        registrar.scan(new restudio.resync.flow.nodes.CoreUtilityNodes());
        registrar.scan(new restudio.resync.flow.nodes.PlayerNodes());
        registrar.scan(new restudio.resync.flow.nodes.WorldNodes());
        registrar.scan(new restudio.resync.flow.nodes.LogicNodes());
        registrar.scan(new restudio.resync.flow.nodes.MathNodes());
        registrar.scan(new restudio.resync.flow.nodes.FlowControlNodes());
        registrar.scan(new restudio.resync.flow.nodes.CoreVariableNodes());
        registrar.scan(new restudio.resync.flow.nodes.CoreInventoryNodes());
        registrar.scan(new restudio.resync.flow.nodes.StringNodes());
        registrar.scan(new restudio.resync.flow.nodes.TextFormattingNodes());
        registrar.scan(new restudio.resync.flow.nodes.ListNodes());
        registrar.scan(new restudio.resync.flow.nodes.ListTransformNodes());
        registrar.scan(new restudio.resync.flow.nodes.PlayerInventoryNodes());
        registrar.scan(new restudio.resync.flow.nodes.PlayerMessagingNodes());
        registrar.scan(new restudio.resync.flow.nodes.EntitySpawnNodes());
        registrar.scan(new restudio.resync.flow.nodes.RandomNodes());
        registrar.scan(new restudio.resync.flow.nodes.DebugNodes());
        registrar.scan(new restudio.resync.flow.nodes.EntityQueryNodes());
        registrar.scan(new restudio.resync.flow.nodes.PlayerActionNodes());
        registrar.scan(new restudio.resync.flow.nodes.EntityControlNodes());
        registrar.scan(new restudio.resync.flow.nodes.RegionNodes());
        registrar.scan(new restudio.resync.flow.nodes.WorldStateNodes());
        registrar.scan(new restudio.resync.flow.nodes.BlockNodes());
        registrar.scan(new restudio.resync.flow.nodes.InventoryNodes());
        registrar.scan(new restudio.resync.flow.nodes.ItemCreationNodes());
        registrar.scan(new restudio.resync.flow.nodes.MenuNodes());
        registrar.scan(new restudio.resync.flow.nodes.PlayerEventNodes());
        registrar.scan(new restudio.resync.flow.nodes.EntityEventNodes());
        registrar.scan(new restudio.resync.flow.nodes.WorldEventNodes());
        registrar.scan(new restudio.resync.flow.nodes.VariableNodes());
        registrar.scan(new restudio.resync.flow.nodes.FileNodes());
        registrar.scan(new restudio.resync.flow.nodes.JsonNodes());
        registrar.scan(new restudio.resync.flow.nodes.TimeNodes());
        registrar.scan(new restudio.resync.flow.nodes.ConversionNodes());
        registrar.scan(new restudio.resync.flow.nodes.SystemNodes());
        registrar.scan(new restudio.resync.flow.nodes.ScoreboardNodes());
        registrar.scan(new restudio.resync.flow.nodes.TeamNodes());
        registrar.scan(new restudio.resync.flow.nodes.SoundNodes());
        registrar.scan(new restudio.resync.flow.nodes.ParticleNodes());
        registrar.scan(new restudio.resync.flow.nodes.TitleNodes());
        registrar.scan(new restudio.resync.flow.nodes.SystemEventNodes());
        registrar.scan(new restudio.resync.flow.nodes.CustomEventNodes());
        scanSoftDependency(registrar, "Vault", "restudio.resync.flow.nodes.EconomyNodes");
        scanSoftDependency(registrar, "LuckPerms", "restudio.resync.flow.nodes.PermissionNodes");
        registrar.scan(new restudio.resync.flow.nodes.LocationNodes());
        registrar.scan(new restudio.resync.flow.nodes.MathAdvancedNodes());
        registrar.scan(new restudio.resync.flow.nodes.StringAdvancedNodes());
        registrar.scan(new restudio.resync.flow.nodes.ListAdvancedNodes());

        registerLegacyCategories(registry);
    }

    private static void scanSoftDependency(NodeRegistrar registrar, String pluginName, String className) {
        if (Bukkit.getPluginManager().getPlugin(pluginName) == null) {
            Log.fine("[Flow] Skipping " + className.substring(className.lastIndexOf('.') + 1) + " — " + pluginName + " not installed");
            return;
        }
        try {
            Class<?> containerClass = Class.forName(className);
            registrar.scan(containerClass);
        } catch (NoClassDefFoundError | ClassNotFoundException e) {
            Log.warn("[Flow] Failed to load " + className.substring(className.lastIndexOf('.') + 1) + ": " + e.getMessage());
        }
    }

    private static void registerLegacyCategories(FlowRegistry registry) {
        new restudio.resync.flow.nodes.EntityAdvancedNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.ItemNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.PlayerQueryNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.UtilityNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.RegionAdvancedNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.DataStructureNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.HttpNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.DiscordNodes().registerNodes(registry);
    }
}
