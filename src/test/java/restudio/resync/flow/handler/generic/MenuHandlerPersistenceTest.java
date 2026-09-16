package restudio.resync.flow.handler.generic;

import com.google.gson.Gson;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.flow.data.GuiDefinition;
import restudio.flow.data.GuiElement;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRuntime;
import restudio.resync.flow.FlowRuntimeAccess;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.storage.AssetTransactionCoordinator;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MenuHandlerPersistenceTest {
    private FlowStorage storage;
    private MenuHandler handler;
    private AssetTransactionCoordinator coordinator;

    @BeforeEach
    void setUp() throws IOException {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        coordinator = AssetTransactionCoordinator.open(plugin.getDataFolder().toPath().resolve("assets"), new Gson());
        storage = new FlowStorage(plugin, coordinator);
        FlowRuntimeAccess.configure(plugin, () -> storage, Map::of);
        handler = new MenuHandler();
    }

    @AfterEach
    void tearDown() throws IOException {
        try {
            FlowRuntimeAccess.clear();
        } finally {
            try {
                coordinator.close();
            } finally {
                MockBukkit.unmock();
            }
        }
    }

    @Test
    void menuOperationsMutatePersistedGuiDefinitions() {
        execute("menu_create", Map.of(
            "menu_id", "main",
            "title", "Main Menu",
            "rows", 2
        ));
        execute("menu_set_item", Map.of(
            "menu_id", "main",
            "slot", 3,
            "material", "DIAMOND",
            "name", "Open Shop",
            "lore", "Line One\nLine Two",
            "flow_to_execute", "shop.open"
        ));
        execute("menu_set_click_sound", Map.of("menu_id", "main", "sound", "UI_BUTTON_CLICK"));
        execute("menu_set_update_interval", Map.of("menu_id", "main", "interval_ticks", 20, "flow_id", "menu.refresh"));

        GuiDefinition definition = storage.getGui("main");
        assertNotNull(definition);
        assertEquals("Main Menu", definition.getTitle());
        assertEquals(2, definition.getRows());
        assertEquals("UI_BUTTON_CLICK", definition.getClickSound());
        assertEquals("menu.refresh", definition.getUpdateFlowId());
        assertEquals(20, definition.getUpdateIntervalTicks());

        GuiElement element = definition.getElements().getFirst();
        assertEquals(3, element.getSlots().getFirst());
        assertEquals("DIAMOND", element.getVisual().getMaterial());
        assertEquals("Open Shop", element.getVisual().getName());
        assertEquals(2, element.getVisual().getLore().size());
        assertEquals("shop.open", element.getFlowId());
    }

    @Test
    void menuOperationsAcceptTypedResourceLocators() {
        ServerResourceLocator locator = new ServerResourceLocator(ServerId.deterministic("menu-handler-test"),
            new ContractRef<>(new OwnerId("builtin"), new ResourceTypeId("gui")), "typed-menu");

        execute("menu_create", Map.of(
            "menu_id", locator,
            "title", "Typed Menu",
            "rows", 1
        ));

        GuiDefinition definition = storage.getGui("typed-menu");
        assertNotNull(definition);
        assertEquals("Typed Menu", definition.getTitle());
    }

    private void execute(String operation, Map<String, Object> inputs) {
        FlowNode node = new FlowNode("menu.test", 0, 0, inputs);
        node.setHandlerConfig(Map.of("operation", operation));
        FlowGraph graph = new FlowGraph();
        graph.setId("menu-handler-test");
        graph.getNodes().put("node", node);
        FlowRuntime runtime = new FlowRuntime(graph, new TypeAdapterRegistry(), Map.of());
        handler.execute(new FlowContext(runtime, null, null), node);
    }
}
