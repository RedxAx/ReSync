package restudio.resync.flow.handler.generic;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        assertEquals(Instant.parse("2026-03-29T12:00:00Z").toEpochMilli(), day.outputs.get("output_time"));
        assertEquals(Instant.parse("2026-04-28T11:00:00Z").toEpochMilli(), month.outputs.get("output_time"));
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

    @Test
    void firstFiveOperationsPublishOnlyDeclaredDataAndNoFlow() {
        Map<String, Set<String>> expectedOutputs = Map.of(
            "time_format", Set.of("string", "valid", "error"),
            "time_parse", Set.of("time", "valid", "error"),
            "time_add", Set.of("output_time", "valid", "error"),
            "time_diff", Set.of("diff", "signed_diff", "unit_diff", "valid", "error"),
            "time_to_ticks", Set.of("ticks", "valid", "error"));
        Map<String, Map<String, Object>> inputs = Map.of(
            "time_format", Map.of("time", 0L),
            "time_parse", Map.of("string", "1970-01-01 00:00:00"),
            "time_add", Map.of("time", 0L),
            "time_diff", Map.of("time1", 0L, "time2", 1L),
            "time_to_ticks", Map.of("seconds", 1L));

        for (Map.Entry<String, Set<String>> entry : expectedOutputs.entrySet()) {
            TestFlowContext context = execute(entry.getKey(), inputs.get(entry.getKey()));

            assertEquals(entry.getValue(), context.outputs.keySet(), entry.getKey());
            assertTrue(context.triggeredOutputs.isEmpty(), entry.getKey() + " should not trigger flow");
        }
    }

    @Test
    void timeAddUsesCanonicalOutputAndRejectsInvalidAmounts() {
        TestFlowContext success = execute("time_add", Map.of("time", 1000L, "amount", 2.0, "unit", "seconds"));
        TestFlowContext fractional = execute("time_add", Map.of("time", 1000L, "amount", 0.5, "unit", "seconds"));
        TestFlowContext nonFinite = execute("time_add", Map.of("time", 1000L, "amount", Double.NaN, "unit", "seconds"));
        TestFlowContext overflow = execute("time_add", Map.of("time", 1000L, "amount", Long.MAX_VALUE, "unit", "ticks"));

        assertEquals(3000L, success.outputs.get("output_time"));
        assertFalse(success.outputs.containsKey("time"));
        assertEquals(false, fractional.outputs.get("valid"));
        assertEquals(1000L, fractional.outputs.get("output_time"));
        assertFalse(String.valueOf(fractional.outputs.get("error")).isBlank());
        assertEquals(false, nonFinite.outputs.get("valid"));
        assertEquals(1000L, nonFinite.outputs.get("output_time"));
        assertFalse(String.valueOf(nonFinite.outputs.get("error")).isBlank());
        assertEquals(false, overflow.outputs.get("valid"));
        assertEquals(1000L, overflow.outputs.get("output_time"));
        assertEquals("long overflow", overflow.outputs.get("error"));
    }

    @Test
    void timeToTicksAcceptsFiniteSecondsOnlyWhenTheResultIsWholeAndInRange() {
        TestFlowContext success = execute("time_to_ticks", Map.of("seconds", 0.05));
        TestFlowContext fractional = execute("time_to_ticks", Map.of("seconds", 0.01));
        TestFlowContext nonFinite = execute("time_to_ticks", Map.of("seconds", Double.POSITIVE_INFINITY));
        TestFlowContext overflow = execute("time_to_ticks", Map.of("seconds", Long.MAX_VALUE));

        assertEquals(1L, success.outputs.get("ticks"));
        assertEquals(true, success.outputs.get("valid"));
        assertEquals(false, fractional.outputs.get("valid"));
        assertEquals(Long.MAX_VALUE, fractional.outputs.get("ticks"));
        assertFalse(String.valueOf(fractional.outputs.get("error")).isBlank());
        assertEquals(false, nonFinite.outputs.get("valid"));
        assertEquals(Long.MAX_VALUE, nonFinite.outputs.get("ticks"));
        assertFalse(String.valueOf(nonFinite.outputs.get("error")).isBlank());
        assertEquals(false, overflow.outputs.get("valid"));
        assertEquals(Long.MAX_VALUE, overflow.outputs.get("ticks"));
        assertEquals("long overflow", overflow.outputs.get("error"));
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
        private final Set<String> triggeredOutputs = new HashSet<>();

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
            triggeredOutputs.add(pinName);
        }
    }
}
