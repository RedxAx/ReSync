package restudio.resync.runtime;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.handler.FlowHandlerException;

import java.util.Map;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeFlowDispatcherTest {
    @Test
    void dispatchAcknowledgementRejectsUnavailableRuntimeAuthorities() {
        RuntimeFlowDispatcher dispatcher = new RuntimeFlowDispatcher(null, null);

        assertFalse(dispatcher.dispatch("configured_flow", null, null, Map.of()));
        CompletionException thrown = assertThrows(CompletionException.class,
            () -> dispatcher.dispatchAsync("configured_flow", null, null, Map.of()).join());
        FlowHandlerException failure = assertInstanceOf(FlowHandlerException.class, thrown.getCause());
        assertEquals("FLOW_STORAGE_UNAVAILABLE", failure.getCode());
    }
}
