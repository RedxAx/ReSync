package restudio.resync.flow.handler.generic;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SchedulePatternTest {
    @Test
    void dailyPatternUsesSelectedZone() {
        SchedulePattern pattern = SchedulePattern.daily("12:30", ZoneId.of("Asia/Riyadh"));

        assertEquals(Instant.parse("2026-07-16T09:30:00Z"),
            pattern.nextAfter(Instant.parse("2026-07-16T08:00:00Z")).orElseThrow());
    }

    @Test
    void dailyPatternMovesToNextDayAfterOccurrence() {
        SchedulePattern pattern = SchedulePattern.daily("12:30", ZoneId.of("UTC"));

        assertEquals(Instant.parse("2026-07-17T12:30:00Z"),
            pattern.nextAfter(Instant.parse("2026-07-16T12:30:00Z")).orElseThrow());
    }

    @Test
    void absentZoneUsesDeterministicUtc() {
        SchedulePattern pattern = SchedulePattern.daily("12:30", null);

        assertEquals(Instant.parse("2026-07-16T12:30:00Z"),
            pattern.nextAfter(Instant.parse("2026-07-16T08:00:00Z")).orElseThrow());
    }

    @Test
    void cronPatternSupportsRangesNamesAndSteps() {
        SchedulePattern pattern = SchedulePattern.cron("*/15 9-17 * JAN,MAR MON-FRI", ZoneId.of("UTC"));

        assertEquals(Instant.parse("2026-03-02T09:00:00Z"),
            pattern.nextAfter(Instant.parse("2026-03-02T08:59:00Z")).orElseThrow());
    }

    @Test
    void cronPatternTreatsSevenAsSunday() {
        SchedulePattern pattern = SchedulePattern.cron("0 10 * * 7", ZoneId.of("UTC"));

        assertEquals(Instant.parse("2026-07-19T10:00:00Z"),
            pattern.nextAfter(Instant.parse("2026-07-16T10:00:00Z")).orElseThrow());
    }

    @Test
    void cronStepWithExplicitStartContinuesThroughFieldRange() {
        SchedulePattern pattern = SchedulePattern.cron("5/15 * * * *", ZoneId.of("UTC"));

        assertEquals(Instant.parse("2026-07-16T10:20:00Z"),
            pattern.nextAfter(Instant.parse("2026-07-16T10:06:00Z")).orElseThrow());
    }

    @Test
    void fullRangeStepUsesWildcardDayMatchingRules() {
        SchedulePattern pattern = SchedulePattern.cron("0 12 20 * */1", ZoneId.of("UTC"));

        assertEquals(Instant.parse("2026-07-20T12:00:00Z"),
            pattern.nextAfter(Instant.parse("2026-07-16T12:00:00Z")).orElseThrow());
    }

    @Test
    void oneTimePatternRejectsPastCursor() {
        SchedulePattern pattern = SchedulePattern.once("2026-07-16T12:00:00Z", ZoneId.of("UTC"));

        assertEquals(true, pattern.nextAfter(Instant.parse("2026-07-16T12:00:00Z")).isEmpty());
    }

    @Test
    void invalidCronPatternIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> SchedulePattern.cron("61 12 * * *", ZoneId.of("UTC")));
    }

    @Test
    void cronPatternRejectsTrailingListSeparator() {
        assertThrows(IllegalArgumentException.class,
            () -> SchedulePattern.cron("0, 12 * * *", ZoneId.of("UTC")));
    }

    @Test
    void cronStepRejectsNamedValues() {
        assertThrows(IllegalArgumentException.class,
            () -> SchedulePattern.cron("0 12 * * */MON", ZoneId.of("UTC")));
    }

    @Test
    void dailyPatternRejectsUndocumentedFractionalSeconds() {
        assertThrows(IllegalArgumentException.class,
            () -> SchedulePattern.daily("12:30:00.500", ZoneId.of("UTC")));
    }

    @Test
    void daylightSavingGapMovesDailyTimeForward() {
        SchedulePattern pattern = SchedulePattern.daily("02:30", ZoneId.of("Europe/Berlin"));

        assertEquals(Instant.parse("2026-03-29T01:30:00Z"),
            pattern.nextAfter(Instant.parse("2026-03-28T23:00:00Z")).orElseThrow());
    }

    @Test
    void daylightSavingOverlapRunsDailyPatternOnce() {
        SchedulePattern pattern = SchedulePattern.daily("02:30", ZoneId.of("Europe/Berlin"));

        assertEquals(Instant.parse("2026-10-25T00:30:00Z"),
            pattern.nextAfter(Instant.parse("2026-10-24T23:00:00Z")).orElseThrow());
        assertEquals(Instant.parse("2026-10-26T01:30:00Z"),
            pattern.nextAfter(Instant.parse("2026-10-25T00:30:00Z")).orElseThrow());
    }

    @Test
    void cronPatternFindsLeapDate() {
        SchedulePattern pattern = SchedulePattern.cron("0 12 29 FEB *", ZoneId.of("UTC"));

        assertEquals(Instant.parse("2028-02-29T12:00:00Z"),
            pattern.nextAfter(Instant.parse("2027-03-01T00:00:00Z")).orElseThrow());
    }

    @Test
    void cronPatternFindsLeapDateAcrossGregorianCenturyGap() {
        SchedulePattern pattern = SchedulePattern.cron("0 12 29 FEB *", ZoneId.of("UTC"));

        assertEquals(Instant.parse("2104-02-29T12:00:00Z"),
            pattern.nextAfter(Instant.parse("2097-03-01T00:00:00Z")).orElseThrow());
    }

    @Test
    void cronPatternUsesEarlierOffsetAndDoesNotRepeatDuringOverlap() {
        SchedulePattern pattern = SchedulePattern.cron("30 2 * * *", ZoneId.of("Europe/Berlin"));

        Instant first = pattern.nextAfter(Instant.parse("2026-10-24T23:00:00Z")).orElseThrow();

        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), first);
        assertEquals(Instant.parse("2026-10-26T01:30:00Z"), pattern.nextAfter(first).orElseThrow());
    }

    @Test
    void cronPatternSkipsNonexistentLocalMinuteDuringGap() {
        SchedulePattern pattern = SchedulePattern.cron("30 2 * * *", ZoneId.of("Europe/Berlin"));

        assertEquals(Instant.parse("2026-03-30T00:30:00Z"),
            pattern.nextAfter(Instant.parse("2026-03-28T23:00:00Z")).orElseThrow());
    }

    @Test
    void restrictedDayFieldsUseDocumentedOrSemantics() {
        SchedulePattern pattern = SchedulePattern.cron("0 12 31 FEB MON", ZoneId.of("UTC"));

        assertEquals(Instant.parse("2026-02-02T12:00:00Z"),
            pattern.nextAfter(Instant.parse("2026-02-01T00:00:00Z")).orElseThrow());
    }

    @Test
    void impossibleCalendarPatternIsRejectedAfterOneCalendarCycle() {
        SchedulePattern pattern = SchedulePattern.cron("0 12 31 FEB *", ZoneId.of("UTC"));

        assertThrows(IllegalStateException.class,
            () -> pattern.nextAfter(Instant.parse("2026-01-01T00:00:00Z")));
    }

    @Test
    void delayConversionRejectsOverflowAcrossInstantRange() {
        SchedulePattern pattern = SchedulePattern.daily("12:00", ZoneId.of("UTC"));

        assertThrows(ArithmeticException.class, () -> pattern.delayMillisFrom(Instant.MIN, Instant.MAX));
    }

    @Test
    void invalidLeapDateIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> SchedulePattern.once("2025-02-29T12:00:00", ZoneId.of("UTC")));
    }
}
