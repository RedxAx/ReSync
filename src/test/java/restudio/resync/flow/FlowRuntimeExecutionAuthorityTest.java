package restudio.resync.flow;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowGraph;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowRuntimeExecutionAuthorityTest {
    @Test
    void subflowRuntimesShareBudgetsTraceIdentityAndEventWindow() {
        FlowRuntime parent = new FlowRuntime(new FlowGraph(), new TypeAdapterRegistry(), Map.of("server.seed", "value"));
        FlowRuntime child = parent.createSubRuntime(new FlowGraph());

        assertEquals(parent.getExecutionId(), child.getExecutionId());
        assertEquals("value", child.getVariable("server.seed"));
        child.setVariable("server.shared", "updated");
        assertEquals("updated", parent.getVariable("server.shared"));
        child.setVariable("server.shared", null);
        assertFalse(parent.getGlobalVariables().containsKey("server.shared"));
        assertTrue(parent.acquireExecutionOperation(2));
        assertTrue(child.acquireExecutionOperation(2));
        assertFalse(parent.acquireExecutionOperation(2));

        parent.openEventMutationWindow(true);
        assertTrue(child.isEventMutationOpen());
        child.closeEventMutationWindow();
        assertFalse(parent.isEventMutationOpen());
    }
}
