package restudio.resync.flow.handler.event;

import org.bukkit.Material;
import org.bukkit.event.block.BlockBreakEvent;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.CompiledRuntimeContextAdapter;
import restudio.resync.flow.CompiledRuntimeValueCodec;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRuntime;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.family.JsonFamilyHandler;
import restudio.resync.flow.handler.generic.ServerHandler;
import restudio.resync.flow.handler.property.PropertyRegistry;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionLoader;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.runtime.RuntimeResult;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeferredBlockEventTest {
    @Test
    void catalogBlockBreakBroadcastsCapturedDisplayNameAfterTheWorldChanges() throws Exception {
        MockBukkit.mock();
        try {
            var server = MockBukkit.getMock();
            var player = server.addPlayer();
            var world = server.addSimpleWorld("deferred-block");
            world.getChunkAt(0, 0);
            var block = world.getBlockAt(3, 64, 2);
            ServerId serverId = ServerId.deterministic("deferred-block");
            List<NodeDefinition> definitions = new NodeDefinitionLoader().loadReplacementFromClasspath("nodes");
            NodeDefinition eventDefinition = definition(definitions, "event.block.break");
            NodeDefinition propertiesDefinition = definition(definitions, "block_properties");
            NodeDefinition broadcastDefinition = definition(definitions, "server.system_broadcast");
            var extractor = new FlowEventRegistry(null).buildVariableExtractor(eventDefinition);
            var registry = new NodeDefinitionRegistry(false);
            List.of(eventDefinition, propertiesDefinition, broadcastDefinition).forEach(registry::register);
            var properties = new PropertyRegistry();
            properties.loadNodeDefinitions(List.of(propertiesDefinition));
            var handlers = new HandlerRegistry();
            JsonFamilyHandler.registerFamilies(handlers, properties);
            for (Material material : List.of(Material.STONE, Material.DIRT, Material.OAK_LOG)) {
                block.setType(material);
                var event = new BlockBreakEvent(block, player);
                var captured = CompiledRuntimeContextAdapter.adapt(serverId, player, event, extractor.apply(event));
                assertTrue(captured.accepted(), captured.failure());
                block.setType(Material.AIR);
                var eventValue = captured.context().variables().get("event.block");
                var transported = RuntimeResult.fromCanonical(RuntimeResult.success(eventValue).canonicalJson()).value();
                Object triggerOutput = CompiledRuntimeValueCodec.decode(serverId, transported);
                Object target = CompiledRuntimeValueCodec.decode(serverId, CompiledRuntimeValueCodec.encode(serverId,
                    TypeExpr.named(TypeReference.of("builtin", "block")), triggerOutput));
                FlowNode query = new FlowNode(propertiesDefinition.getId(), 0, 0,
                    Map.of("target", target, "action", "get", "property", "display_name"));
                query.setHandlerConfig(propertiesDefinition.getHandlerConfig());
                FlowNode broadcast = new FlowNode(broadcastDefinition.getId(), 0, 0, Map.of());
                broadcast.setHandlerConfig(broadcastDefinition.getHandlerConfig());
                FlowGraph graph = new FlowGraph("block-broadcast", Map.of("query", query, "broadcast", broadcast),
                    List.of(new FlowConnection("query", "display_name", "broadcast", "message")), List.of());
                FlowRuntime runtime = new FlowRuntime(graph, new TypeAdapterRegistry(), Map.of(), Map.of(), registry);
                handlers.getHandler("block").execute(new FlowContext(runtime, player, null), query);
                new ServerHandler().execute(new FlowContext(runtime, player, null), broadcast);
                String expected = switch (material) {
                    case STONE -> "Stone";
                    case DIRT -> "Dirt";
                    case OAK_LOG -> "Oak Log";
                    default -> throw new IllegalStateException();
                };
                assertEquals(expected, player.nextMessage());
                assertEquals(Material.AIR, block.getType());
            }
        } finally {
            MockBukkit.unmock();
        }
    }

    private static NodeDefinition definition(List<NodeDefinition> definitions, String id) {
        return definitions.stream().filter(value -> "restudio.resync".equals(value.getOwner()) && id.equals(value.getId()))
            .findFirst().orElseThrow();
    }
}
