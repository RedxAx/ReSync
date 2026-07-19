package restudio.resync.flow.handler.generic;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class SchedulePattern {
    private static final int GREGORIAN_CYCLE_DAYS = 146_097;
    private static final Map<String, Integer> MONTH_NAMES = Map.ofEntries(
        Map.entry("JAN", 1), Map.entry("FEB", 2), Map.entry("MAR", 3), Map.entry("APR", 4),
        Map.entry("MAY", 5), Map.entry("JUN", 6), Map.entry("JUL", 7), Map.entry("AUG", 8),
        Map.entry("SEP", 9), Map.entry("OCT", 10), Map.entry("NOV", 11), Map.entry("DEC", 12)
    );
    private static final Map<String, Integer> DAY_NAMES = Map.ofEntries(
        Map.entry("SUN", 0), Map.entry("MON", 1), Map.entry("TUE", 2), Map.entry("WED", 3),
        Map.entry("THU", 4), Map.entry("FRI", 5), Map.entry("SAT", 6)
    );

    private final ZoneId zoneId;
    private final LocalTime dailyTime;
    private final CronExpression cronExpression;
    private final Instant oneTime;

    private SchedulePattern(ZoneId zoneId, LocalTime dailyTime, CronExpression cronExpression, Instant oneTime) {
        this.zoneId = zoneId;
        this.dailyTime = dailyTime;
        this.cronExpression = cronExpression;
        this.oneTime = oneTime;
    }

    public static SchedulePattern daily(String value, ZoneId zoneId) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Daily time is required");
        }
        String normalized = value.trim();
        if (!normalized.matches("\\d{2}:\\d{2}(?::\\d{2})?")) {
            throw new IllegalArgumentException("Daily time must use HH:mm or HH:mm:ss");
        }
        LocalTime time;
        try {
            time = LocalTime.parse(normalized, DateTimeFormatter.ISO_LOCAL_TIME);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Daily time must use HH:mm or HH:mm:ss", exception);
        }
        return new SchedulePattern(requireZone(zoneId), time, null, null);
    }

    public static SchedulePattern cron(String value, ZoneId zoneId) {
        return new SchedulePattern(requireZone(zoneId), null, CronExpression.parse(value), null);
    }

    public static SchedulePattern once(String value, ZoneId zoneId) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Scheduled time is required");
        }
        String normalized = value.trim();
        Instant instant;
        try {
            instant = Instant.parse(normalized);
        } catch (DateTimeParseException instantFailure) {
            try {
                instant = OffsetDateTime.parse(normalized, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
            } catch (DateTimeParseException offsetFailure) {
                try {
                    instant = ZonedDateTime.parse(normalized, DateTimeFormatter.ISO_ZONED_DATE_TIME).toInstant();
                } catch (DateTimeParseException zonedFailure) {
                    try {
                        instant = LocalDateTime.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                            .atZone(requireZone(zoneId)).toInstant();
                    } catch (DateTimeParseException localFailure) {
                        throw new IllegalArgumentException("Scheduled time must be ISO-8601", localFailure);
                    }
                }
            }
        }
        return new SchedulePattern(requireZone(zoneId), null, null, instant);
    }

    public Optional<Instant> nextAfter(Instant cursor) {
        if (cursor == null) {
            throw new IllegalArgumentException("Schedule cursor is required");
        }
        if (oneTime != null) {
            return oneTime.isAfter(cursor) ? Optional.of(oneTime) : Optional.empty();
        }
        if (dailyTime != null) {
            ZonedDateTime localCursor = cursor.atZone(zoneId);
            ZonedDateTime candidate = localCursor.toLocalDate().atTime(dailyTime).atZone(zoneId);
            if (!candidate.toInstant().isAfter(cursor)) {
                candidate = localCursor.toLocalDate().plusDays(1).atTime(dailyTime).atZone(zoneId);
            }
            return Optional.of(candidate.toInstant());
        }
        return cronExpression.nextAfter(cursor, zoneId);
    }

    public boolean isRecurring() {
        return oneTime == null;
    }

    public long delayMillisFrom(Instant now, Instant target) {
        if (now == null || target == null) {
            throw new IllegalArgumentException("Schedule instants are required");
        }
        try {
            long delay = Duration.between(now, target).toMillis();
            if (delay < 0L) throw new IllegalArgumentException("Schedule target cannot be before the current instant");
            return delay;
        } catch (ArithmeticException exception) {
            throw new ArithmeticException("Schedule delay overflow");
        }
    }

    private static ZoneId requireZone(ZoneId zoneId) {
        return zoneId != null ? zoneId : ZoneId.of("UTC");
    }

    private record CronExpression(CronField minute, CronField hour, CronField dayOfMonth, CronField month, CronField dayOfWeek) {
        private static CronExpression parse(String expression) {
            if (expression == null || expression.isBlank()) {
                throw new IllegalArgumentException("Cron pattern is required");
            }
            String[] fields = expression.trim().split("\\s+");
            if (fields.length != 5) {
                throw new IllegalArgumentException("Cron pattern must contain minute, hour, day, month, and weekday");
            }
            return new CronExpression(
                CronField.parse(fields[0], 0, 59, Map.of(), false),
                CronField.parse(fields[1], 0, 23, Map.of(), false),
                CronField.parse(fields[2], 1, 31, Map.of(), false),
                CronField.parse(fields[3], 1, 12, MONTH_NAMES, false),
                CronField.parse(fields[4], 0, 7, DAY_NAMES, true)
            );
        }

        private Optional<Instant> nextAfter(Instant cursor, ZoneId zoneId) {
            LocalDate firstDate = cursor.atZone(zoneId).toLocalDate();
            for (int dayOffset = 0; dayOffset <= GREGORIAN_CYCLE_DAYS; dayOffset++) {
                LocalDate date = firstDate.plusDays(dayOffset);
                if (!matchesDate(date)) {
                    continue;
                }
                for (int hourValue = hour.first(); hourValue >= 0; hourValue = hour.next(hourValue)) {
                    for (int minuteValue = minute.first(); minuteValue >= 0; minuteValue = minute.next(minuteValue)) {
                        LocalDateTime localDateTime = date.atTime(hourValue, minuteValue);
                        List<ZoneOffset> offsets = zoneId.getRules().getValidOffsets(localDateTime);
                        if (offsets.isEmpty()) {
                            continue;
                        }
                        Instant candidate = localDateTime.toInstant(offsets.getFirst());
                        if (candidate.isAfter(cursor)) {
                            return Optional.of(candidate);
                        }
                    }
                }
            }
            throw new IllegalStateException("Cron pattern has no occurrence within one Gregorian calendar cycle");
        }

        private boolean matchesDate(LocalDate value) {
            if (!month.contains(value.getMonthValue())) {
                return false;
            }
            boolean dayMatches = dayOfMonth.contains(value.getDayOfMonth());
            int cronDay = value.getDayOfWeek() == DayOfWeek.SUNDAY ? 0 : value.getDayOfWeek().getValue();
            boolean weekdayMatches = dayOfWeek.contains(cronDay);
            if (!dayOfMonth.wildcard() && !dayOfWeek.wildcard()) {
                return dayMatches || weekdayMatches;
            }
            return dayMatches && weekdayMatches;
        }
    }

    private record CronField(BitSet values, int minimum, int maximum, boolean wildcard) {
        private static CronField parse(String expression, int minimum, int maximum, Map<String, Integer> aliases, boolean sundayAlias) {
            String normalized = expression.trim().toUpperCase(Locale.ROOT);
            BitSet values = new BitSet(maximum + 1);
            for (String segment : normalized.split(",", -1)) {
                addSegment(values, segment, minimum, maximum, aliases, sundayAlias);
            }
            if (values.isEmpty()) {
                throw new IllegalArgumentException("Cron field has no values: " + expression);
            }
            int expectedValues = sundayAlias ? 7 : maximum - minimum + 1;
            boolean wildcard = values.cardinality() == expectedValues;
            return new CronField(values, minimum, maximum, wildcard);
        }

        private static void addSegment(BitSet values, String segment, int minimum, int maximum,
                                       Map<String, Integer> aliases, boolean sundayAlias) {
            String[] stepParts = segment.split("/", -1);
            if (stepParts.length > 2 || stepParts[0].isBlank()) {
                throw new IllegalArgumentException("Invalid cron segment: " + segment);
            }
            int step = stepParts.length == 2 ? parseStep(stepParts[1]) : 1;
            if (step <= 0) {
                throw new IllegalArgumentException("Cron step must be positive: " + segment);
            }
            int start;
            int end;
            if ("*".equals(stepParts[0])) {
                start = minimum;
                end = maximum;
            } else {
                String[] rangeParts = stepParts[0].split("-", -1);
                if (rangeParts.length > 2 || rangeParts[0].isBlank()) {
                    throw new IllegalArgumentException("Invalid cron range: " + segment);
                }
                start = parseValue(rangeParts[0], aliases);
                end = rangeParts.length == 2 ? parseValue(rangeParts[1], aliases) : stepParts.length == 2 ? maximum : start;
            }
            if (start < minimum || end > maximum || start > end) {
                throw new IllegalArgumentException("Cron value is outside " + minimum + '-' + maximum + ": " + segment);
            }
            for (int value = start; value <= end; value += step) {
                values.set(sundayAlias && value == 7 ? 0 : value);
            }
        }

        private static int parseValue(String value, Map<String, Integer> aliases) {
            Integer alias = aliases.get(value);
            if (alias != null) {
                return alias;
            }
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid cron value: " + value, exception);
            }
        }

        private static int parseStep(String value) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Cron step must be a positive integer: " + value, exception);
            }
        }

        private boolean contains(int value) {
            return value >= minimum && value <= maximum && values.get(value);
        }

        private int first() {
            int value = values.nextSetBit(minimum);
            return value >= minimum && value <= maximum ? value : -1;
        }

        private int next(int current) {
            int value = values.nextSetBit(current + 1);
            return value >= minimum && value <= maximum ? value : -1;
        }
    }
}
