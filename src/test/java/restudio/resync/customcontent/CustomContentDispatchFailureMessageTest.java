package restudio.resync.customcontent;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.FlowExecutor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CustomContentDispatchFailureMessageTest {
    @Test
    void dispatchMessageKeepsTheHandlerReasonFromFrozenDiagnostics() {
        FlowExecutor.FlowExecutionException failure = new FlowExecutor.FlowExecutionException(
            "CORE_EXECUTION_FAILED",
            "Compiled Core execution did not complete successfully",
            null,
            "custom_content.item",
            "Inspect the compiled Core execution failure",
            Map.of("diagnostics", List.of(Map.of(
                "code", "RUNTIME.HANDLER_FAILURE",
                "evidence", Map.of("reason", "This ability needs a player. Connect the Player pin, or run it from a player event.")))));

        assertEquals(
            "Compiled Core execution did not complete successfully: This ability needs a player. Connect the Player pin, or run it from a player event.",
            CustomContentService.dispatchFailureMessage(null, failure));
    }
}
