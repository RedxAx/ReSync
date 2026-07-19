package restudio.resync.flow.migration;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowResourceReference;
import restudio.flow.data.FlowSerializer;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

import java.util.Map;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowGraphMigratorSchemaVersionTest {
    @Test
    void migrationUsesResolvedDefinitionSchemaVersion() {
        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry();
        definitions.register(new NodeDefinition.Builder("test.versioned", "Versioned", NodeDefinition.NodeCategory.DATA)
            .schemaVersion(3)
            .handler("TestHandler")
            .output("value", NodeDefinition.PinType.DATA, FlowDataType.STRING)
            .build());
        FlowNode node = new FlowNode("test.versioned", 0, 0, Map.of());
        node.setVersion(1);
        FlowGraph graph = new FlowGraph();
        graph.setId("schema-version");
        graph.getNodes().put("node", node);

        boolean changed = new FlowGraphMigrator(null, definitions).migrateGraph(graph);

        assertTrue(changed);
        assertEquals(3, node.getVersion());
    }

    @Test
    void migratesLegacyPermissionRepeatableCount() {
        FlowGraph graph = new FlowGraph();
        graph.setVersion(0);
        FlowNode node = new FlowNode("permission.perm_has", 0, 0, new HashMap<>(Map.of("__permission_count", 4)));
        graph.getNodes().put("permission", node);

        boolean changed = new FlowGraphMigrator(null).migrateGraph(graph);

        assertTrue(changed);
        assertEquals(4, node.getInputValues().get("__repeatable_count:permissions"));
        assertFalse(node.getInputValues().containsKey("__permission_count"));
    }

    @Test
    void movesArmorSlotOutOfAllNodeInputsAndIntoContentConfiguration() {
        FlowGraph graph = new FlowGraph();
        FlowNode item = new FlowNode("custom_content.item", 0, 0, new HashMap<>(Map.of("content_id", "item", "armor_slot", "chest")));
        FlowNode block = new FlowNode("custom_content.block", 0, 0, new HashMap<>(Map.of("content_id", "block", "armor_slot", "head")));
        FlowNode armor = new FlowNode("custom_content.armor", 0, 0, new HashMap<>(Map.of("content_id", "armor", "armor_slot", "chest")));
        graph.getNodes().put("item", item);
        graph.getNodes().put("block", block);
        graph.getNodes().put("armor", armor);

        assertTrue(new FlowGraphMigrator(null).migrateGraph(graph));

        assertFalse(item.getInputValues().containsKey("armor_slot"));
        assertFalse(block.getInputValues().containsKey("armor_slot"));
        assertFalse(armor.getInputValues().containsKey("armor_slot"));
        assertEquals("chest", graph.getContentProperties().get("armor_slot"));
    }

    @Test
    void migratesControlAliasesDirectlyToCanonicalNodes() {
        FlowGraph graph = new FlowGraph();
        FlowNode count = new FlowNode("flow.loop_count", 0, 0, Map.of());
        FlowNode each = new FlowNode("logic_loop_for_each", 0, 0, Map.of());
        FlowNode switchCase = new FlowNode("logic.switch_case", 0, 0, Map.of());
        graph.getNodes().put("count", count);
        graph.getNodes().put("each", each);
        graph.getNodes().put("switch", switchCase);

        assertTrue(new FlowGraphMigrator(null).migrateGraph(graph));

        assertEquals("loop.count", count.getType());
        assertEquals("loop.for.each", each.getType());
        assertEquals("flow.switch_case", switchCase.getType());
    }

    @Test
    void migratesRuntimeResourceIdsToTypedReferences() {
        FlowGraph graph = new FlowGraph();
        FlowNode node = new FlowNode("trade.open_trades", 0, 0, new HashMap<>(Map.of("profile_id", "starter")));
        graph.getNodes().put("trade", node);

        boolean changed = new FlowGraphMigrator(null).migrateGraph(graph);

        assertTrue(changed);
        FlowResourceReference reference = (FlowResourceReference) node.getInputValues().get("profile_id");
        assertEquals("trade_profile", reference.kind());
        assertEquals("starter", reference.id());
    }

    @Test
    void migratesLegacyNetworkIdentifiersToTypedReferences() {
        FlowGraph graph = new FlowGraph();
        FlowNode node = new FlowNode("network.player.handoff", 0, 0,
            new HashMap<>(Map.of("target_node", "node-02", "server", "survival")));
        graph.getNodes().put("handoff", node);

        boolean changed = new FlowGraphMigrator(null).migrateGraph(graph);

        assertTrue(changed);
        FlowResourceReference targetNode = (FlowResourceReference) node.getInputValues().get("target_node");
        FlowResourceReference route = (FlowResourceReference) node.getInputValues().get("server");
        assertEquals("network_node", targetNode.kind());
        assertEquals("node-02", targetNode.id());
        assertEquals("network_route", route.kind());
        assertEquals("survival", route.id());
        assertFalse(new FlowGraphMigrator(null).migrateGraph(graph));
    }

    @Test
    void migratesLegacyScoreboardTemplateIdentifiers() {
        FlowGraph graph = new FlowGraph();
        FlowNode node = new FlowNode("scoreboard.show.template", 0, 0, new HashMap<>(Map.of("scoreboard_id", "lobby")));
        graph.getNodes().put("scoreboard", node);

        assertTrue(new FlowGraphMigrator(null).migrateGraph(graph));

        FlowResourceReference reference = (FlowResourceReference) node.getInputValues().get("scoreboard_id");
        assertEquals("scoreboard", reference.kind());
        assertEquals("lobby", reference.id());
    }

    @Test
    void migratesLegacyNamedAndRgbColorValuesByResolvedPinType() {
        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry();
        definitions.register(new NodeDefinition.Builder("test.colors", "Colors", NodeDefinition.NodeCategory.DATA)
            .handler("TestHandler")
            .input("named", NodeDefinition.PinType.DATA, FlowDataType.NAMED_TEXT_COLOR)
            .input("rgb", NodeDefinition.PinType.DATA, FlowDataType.RGB_COLOR)
            .build());
        FlowNode node = new FlowNode("test.colors", 0, 0, new HashMap<>(Map.of("named", "&4", "rgb", "gold")));
        FlowGraph graph = new FlowGraph();
        graph.getNodes().put("colors", node);

        boolean changed = new FlowGraphMigrator(null, definitions).migrateGraph(graph);

        assertTrue(changed);
        assertEquals("dark_red", node.getInputValues().get("named"));
        assertEquals("#FFAA00", node.getInputValues().get("rgb"));
        assertFalse(new FlowGraphMigrator(null, definitions).migrateGraph(graph));
    }

    @Test
    void migratesLenientMiscTimeNodesToTheStrictTimeContract() {
        FlowGraph graph = new FlowGraph();
        FlowNode format = new FlowNode("misc.time_format", 0, 0,
            new HashMap<>(Map.of("timestamp_ms", 1_000L, "format_pattern", "uuuu-MM-dd HH:mm:ss")));
        FlowNode target = new FlowNode("test.target", 0, 0, Map.of());
        graph.getNodes().put("format", format);
        graph.getNodes().put("target", target);
        graph.getConnections().add(new FlowConnection("format", "formatted_string", "target", "value"));

        assertTrue(new FlowGraphMigrator(null).migrateGraph(graph));

        assertEquals("time.format", format.getType());
        assertEquals(1_000L, format.getInputValues().get("time"));
        assertEquals("uuuu-MM-dd HH:mm:ss", format.getInputValues().get("format"));
        assertEquals("UTC", format.getInputValues().get("time_zone"));
        assertFalse(format.getInputValues().containsKey("timestamp_ms"));
        assertFalse(format.getInputValues().containsKey("format_pattern"));
        assertEquals("string", graph.getConnections().getFirst().getSourcePin());
    }

    @Test
    void migratesImplicitTemporalZonesToUtcIdempotently() {
        FlowGraph graph = new FlowGraph();
        FlowNode parser = new FlowNode("time.parse", 0, 0, new HashMap<>(Map.of("time_zone", "")));
        FlowNode schedule = new FlowNode("schedule.cron", 0, 0, Map.of());
        graph.getNodes().put("parser", parser);
        graph.getNodes().put("schedule", schedule);

        assertTrue(new FlowGraphMigrator(null).migrateGraph(graph));
        assertEquals("UTC", parser.getInputValues().get("time_zone"));
        assertEquals("UTC", schedule.getInputValues().get("time_zone"));
        assertFalse(new FlowGraphMigrator(null).migrateGraph(graph));
    }

    @Test
    void migratesLegacyParticleNodesAndConnectionsToTheParticleFamily() {
        FlowGraph graph = new FlowGraph();
        graph.setVersion(0);
        FlowNode source = new FlowNode("test.source", 0, 0, Map.of());
        FlowNode particle = new FlowNode("particle_circle", 0, 0, new HashMap<>(Map.of(
            "particle_type", "FLAME",
            "center_location", "legacy-location",
            "points", 32
        )));
        FlowNode target = new FlowNode("test.target", 0, 0, Map.of());
        graph.getNodes().put("source", source);
        graph.getNodes().put("particle", particle);
        graph.getNodes().put("target", target);
        graph.getConnections().add(new FlowConnection("source", "location", "particle", "center_location"));
        graph.getConnections().add(new FlowConnection("particle", "next", "target", "flow"));

        assertTrue(new FlowGraphMigrator(null).migrateGraph(graph));

        assertEquals("particle.apply", particle.getType());
        assertEquals("particle_apply", particle.getHandlerConfig().getString("operation"));
        assertEquals("circle", particle.getInputValues().get("mode"));
        assertEquals("FLAME", particle.getInputValues().get("particle"));
        assertEquals("legacy-location", particle.getInputValues().get("location"));
        assertEquals(32, particle.getInputValues().get("count"));
        assertEquals("location", graph.getConnections().getFirst().getTargetPin());
        assertEquals("flow", graph.getConnections().get(1).getSourcePin());
        assertFalse(new FlowGraphMigrator(null).migrateGraph(graph));
    }

    @Test
    void migratesCompatibleLegacyDelayNodesWithoutChangingTheirClockUnits() {
        FlowGraph graph = new FlowGraph();
        graph.setVersion(0);
        FlowNode delay = new FlowNode("misc.delay", 0, 0, new HashMap<>(Map.of("ticks", 40)));
        delay.setHandlerConfig(Map.of("operation", "delay"));
        FlowNode target = new FlowNode("test.target", 0, 0, Map.of());
        graph.getNodes().put("delay", delay);
        graph.getNodes().put("target", target);
        graph.getConnections().add(new FlowConnection("delay", "done", "target", "value"));

        assertTrue(new FlowGraphMigrator(null).migrateGraph(graph));

        assertEquals("schedule.wait_ticks", delay.getType());
        assertEquals("wait_ticks", delay.getHandlerConfig().getString("operation"));
        assertEquals(40, delay.getInputValues().get("ticks"));
        assertEquals("completed", graph.getConnections().getFirst().getSourcePin());
        assertFalse(new FlowGraphMigrator(null).migrateGraph(graph));
    }

    @Test
    void migratesLegacyMarketplaceExecutionPinsAndObsoleteLiterals() {
        FlowGraph graph = new FlowGraph();
        graph.setVersion(1);
        FlowNode loop = new FlowNode("loop_while", 0, 0, Map.of());
        FlowNode branch = new FlowNode("if", 0, 0, Map.of());
        FlowNode kill = new FlowNode("entity.kill", 0, 0, new HashMap<>(Map.of("action", "do")));
        graph.getNodes().put("loop", loop);
        graph.getNodes().put("branch", branch);
        graph.getNodes().put("kill", kill);
        graph.getConnections().add(new FlowConnection("loop", "completed", "branch", "flow"));

        assertTrue(new FlowGraphMigrator(null).migrateGraph(graph));

        assertEquals("done", graph.getConnections().getFirst().getSourcePin());
        assertFalse(kill.getInputValues().containsKey("action"));
        assertFalse(new FlowGraphMigrator(null).migrateGraph(graph));
    }

    @Test
    void migratesLegacyLoopBodyAndBreakContinuationTopology() {
        FlowGraph graph = new FlowGraph();
        graph.setVersion(1);
        graph.getNodes().put("loop", new FlowNode("loop.for.each", 0, 0, Map.of()));
        graph.getNodes().put("branch", new FlowNode("if", 0, 0, Map.of()));
        graph.getNodes().put("break", new FlowNode("break.loop", 0, 0, Map.of()));
        graph.getNodes().put("end", new FlowNode("function.end", 0, 0, Map.of()));
        graph.getConnections().add(new FlowConnection("loop", "flow", "branch", "flow"));
        graph.getConnections().add(new FlowConnection("branch", "true", "break", "flow"));
        graph.getConnections().add(new FlowConnection("break", "flow", "end", "flow"));

        assertTrue(new FlowGraphMigrator(null).migrateGraph(graph));

        assertEquals("loop", graph.getConnections().getFirst().getSourcePin());
        assertEquals("loop", graph.getConnections().get(2).getSourceNodeId());
        assertEquals("done", graph.getConnections().get(2).getSourcePin());
        assertFalse(new FlowGraphMigrator(null).migrateGraph(graph));
    }

    @Test
    void migratesFunctionAndVariableSemanticMetadata() {
        FlowGraph graph = FlowSerializer.deserialize("""
            {
              "id": "legacy_function",
              "version": 1,
              "nodes": {},
              "connections": [],
              "localVariables": [{"name":"value","type":"string","initialValue":""}],
              "function": true,
              "functionInputs": [{"name":"items","type":"list"}],
              "functionOutputs": []
            }
            """);

        assertTrue(new FlowGraphMigrator(null).migrateGraph(graph));

        assertEquals(FlowGraph.CURRENT_VERSION, graph.getVersion());
        assertEquals("server", graph.getFunctionOwner());
        assertEquals("local", graph.getFunctionNamespace());
        assertEquals("list<any>", graph.getFunctionInputs().getFirst().getTypeRef().toString());
        assertEquals("execution", graph.getLocalVariables().getFirst().getLifetime());
        assertEquals("isolated", graph.getLocalVariables().getFirst().getConcurrencyPolicy());
    }
}
