package restudio.resync.flow.handler.generic;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldActionHandlerTest {
    private World world;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        world = MockBukkit.getMock().addSimpleWorld("contract-world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void getLocationRejectsMissingCoordinate() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> execute("get_location", Map.of("x", 1.0, "y", 2.0)));

        assertEquals("Location coordinate must be a finite number: z", failure.getMessage());
    }

    @Test
    void getLocationRejectsNonNumericCoordinate() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> execute("get_location", Map.of("x", "not-a-number", "y", 2.0, "z", 3.0)));

        assertEquals("Location coordinate must be a finite number: x", failure.getMessage());
    }

    @Test
    void getLocationRejectsNonFiniteCoordinate() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> execute("get_location", Map.of("x", Double.NaN, "y", 2.0, "z", 3.0)));

        assertEquals("Location coordinate must be a finite number: x", failure.getMessage());
    }

    @Test
    void worldGetByNameRejectsMissingBlankAndUnknownWorlds() {
        assertEquals("World name is required", assertThrows(IllegalArgumentException.class,
            () -> execute("world_get_by_name", Map.of())).getMessage());
        assertEquals("World name is required", assertThrows(IllegalArgumentException.class,
            () -> execute("world_get_by_name", Map.of("world_name", "  "))).getMessage());
        assertEquals("Unknown world: missing-world", assertThrows(IllegalArgumentException.class,
            () -> execute("world_get_by_name", Map.of("world_name", "missing-world"))).getMessage());
    }

    @Test
    void successfulWorldOperationsPublishAuthoredOutputKeys() {
        TestFlowContext locationContext = execute("get_location", Map.of("x", 1.5, "y", 64.0, "z", -2.25));
        Location location = assertInstanceOf(Location.class, locationContext.outputs.get("location"));
        assertEquals(world, location.getWorld());
        assertEquals(Map.of("location", location), locationContext.outputs);

        TestFlowContext byNameContext = execute("world_get_by_name", Map.of("world_name", "contract-world"));
        assertEquals(Map.of("world", world), byNameContext.outputs);

        TestFlowContext allContext = execute("world_get_all", Map.of());
        assertEquals(Map.of("worlds_list", Bukkit.getWorlds()), allContext.outputs);
        assertTrue(allContext.outputs.containsKey("worlds_list"));
    }

    private TestFlowContext execute(String operation, Map<String, Object> inputs) {
        FlowNode node = new FlowNode("world." + operation, 0, 0, Map.of());
        node.setHandlerConfig(Map.of("operation", operation));
        TestFlowContext context = new TestFlowContext(inputs);
        new WorldActionHandler().execute(context, node);
        assertEquals("flow", context.triggeredOutput);
        return context;
    }

    private static final class TestFlowContext extends FlowContext {
        private final Map<String, Object> inputs;
        private final Map<String, Object> outputs = new HashMap<>();
        private String triggeredOutput;

        private TestFlowContext(Map<String, Object> inputs) {
            super(null, null, null);
            this.inputs = inputs;
        }

        @Override
        public Object getInputValue(FlowNode node, String pinName) {
            return inputs.get(pinName);
        }

        @Override
        public <T> T getInputValue(FlowNode node, String pinName, Class<T> type, T defaultValue) {
            Object value = inputs.get(pinName);
            return value != null ? type.cast(value) : defaultValue;
        }

        @Override
        public void setOutput(FlowNode node, String pinName, Object value) {
            outputs.put(pinName, value);
        }

        @Override
        public Object getOutput(FlowNode node, String pinName) {
            return outputs.get(pinName);
        }

        @Override
        public void triggerOutput(String pinName) {
            triggeredOutput = pinName;
        }
    }
}
