package restudio.resync.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import restudio.resync.diagnostics.DiagnosticEvent;
import restudio.resync.diagnostics.DiagnosticSink;
import restudio.resync.flow.handler.FlowHandlerException;
import restudio.resync.server.TemporaryLifecycleDiagnostics;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeFlowDispatcherTest {
    @AfterEach
    void closeDiagnostics() {
        TemporaryLifecycleDiagnostics.close();
    }

    @Test
    void dispatchAcknowledgementRejectsUnavailableRuntimeAuthorities() {
        RuntimeFlowDispatcher dispatcher = new RuntimeFlowDispatcher(null, null);

        assertFalse(dispatcher.dispatch("configured_flow", null, null, Map.of()));
        CompletionException thrown = assertThrows(CompletionException.class,
            () -> dispatcher.dispatchAsync("configured_flow", null, null, Map.of()).join());
        FlowHandlerException failure = assertInstanceOf(FlowHandlerException.class, thrown.getCause());
        assertEquals("FLOW_STORAGE_UNAVAILABLE", failure.getCode());
    }

    @Test
    void unavailableRuntimeDispatchHasOneCorrelatedTerminal() throws Exception {
        CapturingDiagnosticSink diagnostics = new CapturingDiagnosticSink();
        bindDiagnostics(diagnostics);
        RuntimeFlowDispatcher dispatcher = new RuntimeFlowDispatcher(null, null);

        assertThrows(CompletionException.class,
            () -> dispatcher.dispatchAsync("configured_flow", null, null, Map.of()).join());

        List<DiagnosticEvent> events = diagnostics.events.stream()
            .filter(event -> event.stage().startsWith("trigger_"))
            .toList();
        assertEquals(1L, events.stream().filter(event -> event.stage().equals("trigger_ingress")).count());
        assertEquals(1L, events.stream().filter(event -> event.stage().equals("trigger_execution_terminal")).count());
        assertEquals(1, events.stream().map(event -> event.identity().correlationId()).distinct().count());
    }

    private static void bindDiagnostics(DiagnosticSink sink) throws Exception {
        Method bind = TemporaryLifecycleDiagnostics.class.getDeclaredMethod("bind", DiagnosticSink.class);
        bind.setAccessible(true);
        bind.invoke(null, sink);
    }

    private static final class CapturingDiagnosticSink implements DiagnosticSink {
        private final List<DiagnosticEvent> events = new ArrayList<>();

        @Override
        public Status status() {
            return Status.ready(Mode.VERBOSE);
        }

        @Override
        public Offer offer(DiagnosticEvent event) {
            events.add(event);
            return Offer.ACCEPTED;
        }

        @Override
        public Status pause() {
            return status();
        }

        @Override
        public Status resume() {
            return status();
        }

        @Override
        public Flush flush() {
            return events.isEmpty() ? Flush.EMPTY : Flush.FLUSHED;
        }

        @Override
        public void close() {
        }
    }
}
