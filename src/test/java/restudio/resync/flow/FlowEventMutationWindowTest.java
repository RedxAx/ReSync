package restudio.resync.flow;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowGraph;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowEventMutationWindowTest {
    @Test
    void eventMutationIsRejectedAfterTheSynchronousWindowCloses() {
        FlowRuntime runtime = new FlowRuntime(new FlowGraph(), new TypeAdapterRegistry(), new HashMap<>());
        TestEvent event = new TestEvent();
        FlowContext context = new FlowContext(runtime, null, event);

        runtime.openEventMutationWindow(true);
        assertTrue(context.setEventCancelled(true));
        assertTrue(event.isCancelled());

        runtime.closeEventMutationWindow();
        assertFalse(context.setEventCancelled(false));
        assertTrue(event.isCancelled());
    }

    private static final class TestEvent extends Event implements Cancellable {
        private static final HandlerList HANDLERS = new HandlerList();
        private boolean cancelled;

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void setCancelled(boolean cancelled) {
            this.cancelled = cancelled;
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }
}
