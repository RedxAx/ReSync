package restudio.resync.flow;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.handler.FlowHandlerException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowPredicateSupportTest {
    @Test
    void omittedPredicatesRemainAnExplicitPassThrough() {
        assertTrue(FlowPredicateSupport.evaluate(null, null, "", null, null, Map.of()));
    }

    @Test
    void configuredPredicatesRequireTheirRuntimeAuthorities() {
        FlowHandlerException failure = assertThrows(FlowHandlerException.class,
            () -> FlowPredicateSupport.evaluate(null, null, "required_predicate", null, null, Map.of()));

        assertEquals("PREDICATE_STORAGE_UNAVAILABLE", failure.getCode());
        assertEquals("required_predicate", failure.getDetails().get("flowId"));
    }
}
