package restudio.resync.flow.handler.generic;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TimeHandlerTest {
    @Test
    void currentTimeUsesInjectedClock() {
        TestFlowContext context = execute("time_current", Map.of());

        assertEquals(123456789L, context.outputs.get("time"));
    }

    @Test
    void formatsWithExplicitZone() {
        TestFlowContext context = execute("time_format", Map.of(
            "time", 0L,
            "format", "uuuu-MM-dd HH:mm:ss",
            "time_zone", "Asia/Riyadh"
        ));

        assertEquals("1970-01-01 03:00:00", context.outputs.get("string"));
        assertEquals(true, context.outputs.get("valid"));
    }

    @Test
    void parsesWithExplicitZone() {
        TestFlowContext context = execute("time_parse", Map.of(
            "string", "1970-01-01 03:00:00",
            "format", "uuuu-MM-dd HH:mm:ss",
            "time_zone", "Asia/Riyadh"
        ));

        assertEquals(0L, context.outputs.get("time"));
        assertEquals(true, context.outputs.get("valid"));
    }

    @Test
    void absentZoneUsesUtcInsteadOfTheHostZone() {
        TestFlowContext context = execute("time_parse", Map.of(
            "string", "1970-01-01 00:00:00",
            "format", "uuuu-MM-dd HH:mm:ss"
        ));

        assertEquals(0L, context.outputs.get("time"));
        assertEquals(true, context.outputs.get("valid"));
    }

    @Test
    void invalidPatternProducesDiagnosticOutputs() {
        TestFlowContext context = execute("time_format", Map.of(
            "time", 0L,
            "format", "yyyy-MM-dd '"
        ));

        assertEquals(false, context.outputs.get("valid"));
        assertFalse(String.valueOf(context.outputs.get("error")).isBlank());
    }

    @Test
    void invalidCalendarDateIsNotNormalized() {
        TestFlowContext context = execute("time_parse", Map.of(
            "string", "2026-02-30 12:00:00",
            "format", "uuuu-MM-dd HH:mm:ss",
            "time_zone", "UTC"
        ));

        assertEquals(false, context.outputs.get("valid"));
        assertFalse(String.valueOf(context.outputs.get("error")).isBlank());
    }

    @Test
    void localParsingUsesDocumentedDaylightSavingResolution() {
        TestFlowContext gap = execute("time_parse", Map.of(
            "string", "2026-03-29 02:30:00",
            "format", "uuuu-MM-dd HH:mm:ss",
            "time_zone", "Europe/Berlin"
        ));
        TestFlowContext overlap = execute("time_parse", Map.of(
            "string", "2026-10-25 02:30:00",
            "format", "uuuu-MM-dd HH:mm:ss",
            "time_zone", "Europe/Berlin"
        ));

        assertEquals(Instant.parse("2026-03-29T01:30:00Z").toEpochMilli(), gap.outputs.get("time"));
        assertEquals(Instant.parse("2026-10-25T00:30:00Z").toEpochMilli(), overlap.outputs.get("time"));
        assertEquals(true, gap.outputs.get("valid"));
        assertEquals(true, overlap.outputs.get("valid"));
    }

    @Test
    void fixedDaysAndCalendarMonthsKeepDistinctSemantics() {
        long startingTime = Instant.parse("2026-03-28T12:00:00Z").toEpochMilli();
        TestFlowContext day = execute("time_add", Map.of(
            "time", startingTime,
            "amount", 1L,
            "unit", "days",
            "time_zone", "Europe/Berlin"
        ));
        TestFlowContext month = execute("time_add", Map.of(
            "time", startingTime,
            "amount", 1L,
            "unit", "months",
            "time_zone", "Europe/Berlin"
        ));

        assertEquals(Instant.parse("2026-03-29T12:00:00Z").toEpochMilli(), day.outputs.get("time"));
        assertEquals(Instant.parse("2026-04-28T11:00:00Z").toEpochMilli(), month.outputs.get("time"));
    }

    @Test
    void incompleteDateAndInvalidLocaleAreInspectableFailures() {
        TestFlowContext incomplete = execute("time_parse", Map.of(
            "string", "2026-07-16",
            "format", "uuuu-MM-dd",
            "time_zone", "UTC"
        ));
        TestFlowContext invalidLocale = execute("time_format", Map.of(
            "time", 0L,
            "format", "uuuu-MM-dd HH:mm:ss",
            "locale", "not_a_locale"
        ));

        assertEquals(false, incomplete.outputs.get("valid"));
        assertEquals(false, invalidLocale.outputs.get("valid"));
    }

    @Test
    void timeDifferencePreservesMillisecondsAndProvidesAnExplicitUnitView() {
        TestFlowContext context = execute("time_diff", Map.of(
            "time1", 1_000L,
            "time2", 121_999L,
            "unit", "minutes"
        ));

        assertEquals(120_999L, context.outputs.get("diff"));
        assertEquals(120_999L, context.outputs.get("signed_diff"));
        assertEquals(2L, context.outputs.get("unit_diff"));
        assertEquals(true, context.outputs.get("valid"));
    }

    @Test
    void timeDifferenceRejectsAmbiguousCalendarUnits() {
        TestFlowContext context = execute("time_diff", Map.of(
            "time1", 0L,
            "time2", 1_000L,
            "unit", "months"
        ));

        assertEquals(false, context.outputs.get("valid"));
        assertFalse(String.valueOf(context.outputs.get("error")).isBlank());
    }

    private TestFlowContext execute(String operation, Map<String, Object> inputs) {
        TimeHandler handler = new TimeHandler(Clock.fixed(Instant.ofEpochMilli(123456789L), ZoneOffset.UTC));
        FlowNode node = new FlowNode("time.test", 0, 0, Map.of());
        node.setHandlerConfig(Map.of("operation", operation));
        TestFlowContext context = new TestFlowContext(inputs);

        handler.execute(context, node);

        return context;
    }

    private static class TestFlowContext extends FlowContext {
        private final Map<String, Object> inputs;
        private final Map<String, Object> outputs = new HashMap<>();

        private TestFlowContext(Map<String, Object> inputs) {
            super(null, null, null);
            this.inputs = inputs;
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
        public void triggerOutput(String pinName) {
        }
    }
}
