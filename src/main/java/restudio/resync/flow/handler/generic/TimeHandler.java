package restudio.resync.flow.handler.generic;

import org.bukkit.Bukkit;
import org.bukkit.World;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAccessor;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

public class TimeHandler implements NodeHandler {
    private static final String DEFAULT_PATTERN = "uuuu-MM-dd HH:mm:ss";
    private final Clock clock;
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new HashMap<>();

    public TimeHandler() {
        this(Clock.systemUTC());
    }

    TimeHandler(Clock clock) {
        this.clock = clock;

        operations.put("time_current", (ctx, node) -> {
            ctx.setOutput(node, "time", clock.millis());
            succeed(ctx, node);
        });

        operations.put("time_format", (ctx, node) -> {
            long time = ctx.getInputValue(node, "time", Long.class, 0L);
            String pattern = ctx.getInputValue(node, "format", String.class, DEFAULT_PATTERN);
            try {
                DateTimeFormatter formatter = formatter(pattern, resolveLocale(ctx, node));
                String result = Instant.ofEpochMilli(time).atZone(resolveZone(ctx, node)).format(formatter);
                ctx.setOutput(node, "string", result);
                succeed(ctx, node);
            } catch (Exception exception) {
                ctx.setOutput(node, "string", "");
                fail(ctx, node, exception);
            }
        });

        operations.put("time_parse", (ctx, node) -> {
            String value = ctx.getInputValue(node, "string", String.class, "");
            String pattern = ctx.getInputValue(node, "format", String.class, DEFAULT_PATTERN);
            try {
                DateTimeFormatter formatter = formatter(pattern, resolveLocale(ctx, node));
                ctx.setOutput(node, "time", parse(value, formatter, resolveZone(ctx, node)).toEpochMilli());
                succeed(ctx, node);
            } catch (Exception exception) {
                ctx.setOutput(node, "time", 0L);
                fail(ctx, node, exception);
            }
        });

        operations.put("time_add", (ctx, node) -> {
            long time = ctx.getInputValue(node, "time", Long.class, 0L);
            long amount = ctx.getInputValue(node, "amount", Long.class, 0L);
            String unit = ctx.getInputValue(node, "unit", String.class, "seconds");
            try {
                Instant result = add(Instant.ofEpochMilli(time), amount, unit, resolveZone(ctx, node));
                ctx.setOutput(node, "time", result.toEpochMilli());
                succeed(ctx, node);
            } catch (Exception exception) {
                ctx.setOutput(node, "time", time);
                fail(ctx, node, exception);
            }
        });

        operations.put("time_diff", (ctx, node) -> {
            long first = ctx.getInputValue(node, "time1", Long.class, 0L);
            long second = ctx.getInputValue(node, "time2", Long.class, 0L);
            String unit = ctx.getInputValue(node, "unit", String.class, "milliseconds");
            try {
                long signed = Math.subtractExact(second, first);
                if (signed == Long.MIN_VALUE) {
                    throw new ArithmeticException("Time difference overflow");
                }
                ctx.setOutput(node, "diff", Math.abs(signed));
                ctx.setOutput(node, "signed_diff", signed);
                ctx.setOutput(node, "unit_diff", differenceInUnit(signed, unit));
                succeed(ctx, node);
            } catch (Exception exception) {
                ctx.setOutput(node, "diff", Long.MAX_VALUE);
                ctx.setOutput(node, "signed_diff", second >= first ? Long.MAX_VALUE : Long.MIN_VALUE);
                ctx.setOutput(node, "unit_diff", second >= first ? Long.MAX_VALUE : Long.MIN_VALUE);
                fail(ctx, node, exception);
            }
        });

        operations.put("time_to_ticks", (ctx, node) -> {
            long seconds = ctx.getInputValue(node, "seconds", Long.class, 0L);
            try {
                ctx.setOutput(node, "ticks", Math.multiplyExact(seconds, 20L));
                succeed(ctx, node);
            } catch (Exception exception) {
                ctx.setOutput(node, "ticks", seconds >= 0L ? Long.MAX_VALUE : Long.MIN_VALUE);
                fail(ctx, node, exception);
            }
        });

        operations.put("time_get_current_time", (ctx, node) -> {
            World world = ctx.getInputValue(node, "world", World.class, null);
            if (world == null) {
                ctx.setOutput(node, "time", 0L);
                fail(ctx, node, new IllegalArgumentException("World is required"));
                return;
            }
            ctx.setOutput(node, "time", world.getTime());
            succeed(ctx, node);
        });

        operations.put("time_get_current_ticks", (ctx, node) -> {
            ctx.setOutput(node, "ticks", (long) Bukkit.getCurrentTick());
            succeed(ctx, node);
        });
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("TimeHandler", this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> handler = operation != null ? operations.get(operation) : null;
        if (handler == null) {
            throw new IllegalArgumentException("Unknown time operation: " + operation);
        }
        handler.accept(ctx, node);
        ctx.triggerOutput("flow");
    }

    private DateTimeFormatter formatter(String pattern, Locale locale) {
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("Time pattern is required");
        }
        return DateTimeFormatter.ofPattern(pattern, locale).withResolverStyle(ResolverStyle.STRICT);
    }

    private Instant parse(String value, DateTimeFormatter formatter, ZoneId zoneId) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Time value is required");
        }
        TemporalAccessor parsed = formatter.parseBest(value, ZonedDateTime::from, OffsetDateTime::from,
            LocalDateTime::from);
        return switch (parsed) {
            case ZonedDateTime zoned -> zoned.toInstant();
            case OffsetDateTime offset -> offset.toInstant();
            case LocalDateTime local -> local.atZone(zoneId).toInstant();
            default -> throw new IllegalArgumentException("Time pattern does not contain a complete date and time");
        };
    }

    private Instant add(Instant instant, long amount, String unit, ZoneId zoneId) {
        String normalized = unit != null ? unit.trim().toLowerCase(Locale.ROOT) : "";
        return switch (normalized) {
            case "millisecond", "milliseconds", "ms" -> instant.plus(amount, ChronoUnit.MILLIS);
            case "tick", "ticks" -> instant.plus(Math.multiplyExact(amount, 50L), ChronoUnit.MILLIS);
            case "second", "seconds", "s" -> instant.plus(amount, ChronoUnit.SECONDS);
            case "minute", "minutes", "m" -> instant.plus(amount, ChronoUnit.MINUTES);
            case "hour", "hours", "h" -> instant.plus(amount, ChronoUnit.HOURS);
            case "day", "days", "d" -> instant.plus(amount, ChronoUnit.DAYS);
            case "week", "weeks", "w" -> instant.plus(amount, ChronoUnit.WEEKS);
            case "month", "months" -> instant.atZone(zoneId).plusMonths(amount).toInstant();
            case "year", "years", "y" -> instant.atZone(zoneId).plusYears(amount).toInstant();
            default -> throw new IllegalArgumentException("Unknown time unit: " + unit);
        };
    }

    private long differenceInUnit(long milliseconds, String unit) {
        String normalized = unit != null ? unit.trim().toLowerCase(Locale.ROOT) : "";
        long divisor = switch (normalized) {
            case "millisecond", "milliseconds", "ms" -> 1L;
            case "tick", "ticks" -> 50L;
            case "second", "seconds", "s" -> 1_000L;
            case "minute", "minutes", "m" -> 60_000L;
            case "hour", "hours", "h" -> 3_600_000L;
            case "day", "days", "d" -> 86_400_000L;
            case "week", "weeks", "w" -> 604_800_000L;
            default -> throw new IllegalArgumentException("Unknown time unit: " + unit);
        };
        return milliseconds / divisor;
    }

    private ZoneId resolveZone(FlowContext context, FlowNode node) {
        String zone = context.getInputValue(node, "time_zone", String.class, "");
        if (zone == null || zone.isBlank()) {
            return ZoneId.of("UTC");
        }
        return ZoneId.of(zone.trim());
    }

    private Locale resolveLocale(FlowContext context, FlowNode node) {
        String languageTag = context.getInputValue(node, "locale", String.class, "");
        return languageTag == null || languageTag.isBlank() ? Locale.ROOT : new Locale.Builder().setLanguageTag(languageTag.trim()).build();
    }

    private void succeed(FlowContext context, FlowNode node) {
        context.setOutput(node, "valid", true);
        context.setOutput(node, "error", "");
    }

    private void fail(FlowContext context, FlowNode node, Exception exception) {
        context.setOutput(node, "valid", false);
        context.setOutput(node, "error", exception.getMessage() != null ? exception.getMessage() : exception.getClass().getSimpleName());
    }
}
