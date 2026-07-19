package restudio.resync.customcontent;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.flow.data.CustomAbilityBinding;
import restudio.flow.data.CustomContentDefinition;
import restudio.flow.data.CustomContentGraphAdapter;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.flow.handler.generic.CustomContentHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class CustomContentDispatchRuntimeTest {
    private final List<String> observedTriggers = new CopyOnWriteArrayList<>();
    private final AtomicReference<Map<String, Object>> observedVariables = new AtomicReference<>();
    private CustomContentStorage contentStorage;
    private FlowStorage flowStorage;
    private CustomContentService service;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        HandlerRegistry handlers = new HandlerRegistry();
        new CustomContentHandler().registerTo(handlers);
        handlers.register("capture", new NodeHandler() {
            @Override
            public void execute(FlowContext context, FlowNode node) {
                Map<String, Object> variables = context.getRuntime().getEventVariables();
                observedVariables.set(variables);
                observedTriggers.add(variables.get("event.content_id") + ":" + variables.get("event.trigger"));
            }

            @Override
            public ThreadPolicy getThreadPolicy() {
                return ThreadPolicy.CURRENT;
            }

            @Override
            public Set<String> getSupportedOperations() {
                return Set.of();
            }
        });
        handlers.register("noop", new NodeHandler() {
            @Override
            public void execute(FlowContext context, FlowNode node) {
            }

            @Override
            public ThreadPolicy getThreadPolicy() {
                return ThreadPolicy.CURRENT;
            }

            @Override
            public Set<String> getSupportedOperations() {
                return Set.of();
            }
        });
        FlowExecutor executor = new FlowExecutor(handlers, new TypeAdapterRegistry(), Map.of());
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        contentStorage = new CustomContentStorage(plugin);
        flowStorage = new FlowStorage(plugin);
        service = new CustomContentService(contentStorage, flowStorage, executor);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void everyAdvertisedContentTriggerSelectsItsEmbeddedExecutionBranch() {
        List<String> expected = new ArrayList<>();
        for (String type : List.of("item", "block", "armor")) {
            String contentId = "runtime_" + type;
            List<CustomContentGraphAdapter.TriggerDescriptor> triggers = CustomContentGraphAdapter.triggersForType(type);
            saveContentGraph(contentId, type, triggers);
            for (CustomContentGraphAdapter.TriggerDescriptor trigger : triggers) {
                expected.add(contentId + ":" + trigger.trigger());
                service.dispatch(contentId, trigger.trigger(), null, null, Map.of());
            }
        }

        assertEquals(expected, observedTriggers);
    }

    @Test
    void consumeListenerIdentifiesStampedItemAndExecutesItsConfiguredBranch() {
        List<CustomContentGraphAdapter.TriggerDescriptor> triggers = CustomContentGraphAdapter.triggersForType("item").stream()
            .filter(trigger -> "item.consume".equals(trigger.trigger()))
            .toList();
        saveContentGraph("resin", "item", triggers);
        Player player = MockBukkit.getMock().addPlayer();
        ItemStack item = service.createItem("resin", 1);
        assertNotNull(item);

        new CustomContentListener(contentStorage, service).onConsume(new PlayerItemConsumeEvent(player, item, EquipmentSlot.HAND));

        assertEquals(List.of("resin:item.consume"), observedTriggers);
        assertSame(player, observedVariables.get().get("event.player"));
        assertEquals(item, observedVariables.get().get("event.item"));
    }

    @Test
    void externalAbilityFlowUsesTheExecutorStartAuthorityInsteadOfAnArbitraryNode() {
        FlowGraph externalFlow = new FlowGraph("external_flow", Map.of(
            "a_detached", new FlowNode("noop", 0, 0, Map.of()),
            "z_start", new FlowNode("noop", 0, 0, Map.of()),
            "capture", new FlowNode("capture", 0, 0, Map.of())
        ), List.of(new FlowConnection("z_start", "flow", "capture", "flow")), List.of());
        flowStorage.saveGraph(externalFlow);

        FlowGraph contentGraph = CustomContentGraphAdapter.createContentGraph("external_item", "item", "External Item");
        CustomContentDefinition definition = CustomContentGraphAdapter.toDefinition(contentGraph);
        assertNotNull(definition);
        definition.setGraph(null);
        definition.setFlowId("external_flow");
        definition.setAbilities(List.of(new CustomAbilityBinding("external_item.use", "item.use", "external_flow")));
        contentStorage.save(definition);

        service.dispatch("external_item", "item.use", null, null, Map.of());

        assertEquals(List.of("external_item:item.use"), observedTriggers);
    }

    private void saveContentGraph(String contentId, String type, List<CustomContentGraphAdapter.TriggerDescriptor> triggers) {
        FlowGraph graph = CustomContentGraphAdapter.createContentGraph(contentId, type, "Runtime " + type);
        String startNodeId = graph.getNodes().entrySet().stream()
            .filter(entry -> CustomContentGraphAdapter.typeFromNode(entry.getValue().getType()) != null)
            .map(Map.Entry::getKey)
            .findFirst()
            .orElseThrow();
        CustomContentGraphAdapter.setEnabledTriggerBranches(graph, triggers.stream().map(CustomContentGraphAdapter.TriggerDescriptor::pin).toList());
        graph.getNodes().put("capture", new FlowNode("capture", 500, 120, Map.of()));
        for (CustomContentGraphAdapter.TriggerDescriptor trigger : triggers) {
            graph.getConnections().add(new FlowConnection(startNodeId, trigger.pin(), "capture", "flow"));
        }
        CustomContentDefinition definition = CustomContentGraphAdapter.toDefinition(graph);
        assertNotNull(definition);
        contentStorage.save(definition);
    }
}
