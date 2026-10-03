package restudio.resync.commands;

import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import restudio.resync.qa.QaService;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public final class QaCommand {
    private static final List<String> ACTIONS = List.of("help", "list", "describe", "run", "poll");
    private static final String DENIED = "{\"status\":\"failed\",\"code\":\"QA_PERMISSION_DENIED\",\"message\":\"Permission resync.qa is required\"}";
    private static final String UNAVAILABLE = "{\"status\":\"failed\",\"code\":\"QA_UNAVAILABLE\",\"message\":\"The QA service is unavailable\"}";
    private static final String FAILED = "{\"status\":\"failed\",\"code\":\"QA_FAILED\",\"message\":\"The QA command failed\"}";
    private final Supplier<QaService> service;

    public QaCommand(Supplier<QaService> service) {
        this.service = Objects.requireNonNull(service, "QA service supplier is required");
    }

    public boolean execute(CommandSender actor, String[] args) {
        if (!actor.hasPermission("resync.qa")) {
            send(actor, DENIED);
            return true;
        }
        QaService active;
        try {
            active = service.get();
        } catch (RuntimeException exception) {
            send(actor, FAILED);
            return true;
        }
        if (active == null) {
            send(actor, UNAVAILABLE);
            return true;
        }
        Map<String, Object> result;
        try {
            String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
            result = switch (action) {
                case "help" -> args.length <= 1 ? help() : usage("/resync qa help");
                case "list" -> args.length == 1 ? active.describe() : usage("/resync qa list");
                case "describe" -> args.length == 2 ? active.describe(args[1]) : usage("/resync qa describe <operation>");
                case "run" -> args.length >= 3
                    ? active.submit(actor, args[1], active.parse(String.join(" ", Arrays.copyOfRange(args, 2, args.length))))
                    : usage("/resync qa run <operation> <JSON object>");
                case "poll" -> args.length == 2 ? active.poll(actor, runId(args[1])) : usage("/resync qa poll <runId>");
                default -> usage("/resync qa <help|list|describe|run|poll>");
            };
        } catch (IllegalArgumentException exception) {
            String message = exception.getMessage();
            result = failure("QA_INVALID_ARGUMENT", message == null || message.isBlank() ? "Invalid QA argument" : message);
        } catch (RuntimeException exception) {
            result = failure("QA_FAILED", "The QA command failed");
        }
        try {
            send(actor, active.stringify(result));
        } catch (RuntimeException exception) {
            send(actor, FAILED);
        }
        return true;
    }

    public List<String> complete(CommandSender actor, String[] args) {
        if (!actor.hasPermission("resync.qa")) {
            return List.of();
        }
        if (args.length <= 1) {
            return matching(ACTIONS, args.length == 0 ? "" : args[0]);
        }
        if (args.length != 2 || !("run".equalsIgnoreCase(args[0]) || "describe".equalsIgnoreCase(args[0]))) {
            return List.of();
        }
        try {
            QaService active = service.get();
            if (active == null || !(active.describe().get("operations") instanceof List<?> operations)) {
                return List.of();
            }
            List<String> ids = operations.stream()
                .filter(operation -> operation instanceof Map<?, ?>)
                .map(operation -> ((Map<?, ?>) operation).get("id"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
            return matching(ids, args[1]);
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    private UUID runId(String value) {
        UUID id = UUID.fromString(value);
        if (!id.toString().equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("Run ID must be a UUID in the form xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx");
        }
        return id;
    }

    private Map<String, Object> help() {
        return Map.of(
            "status", "completed",
            "message", "Run returns a receipt immediately. Poll its runId to read the current state and outputs.",
            "commands", List.of(
                Map.of("usage", "/resync qa help", "description", "Show QA commands and how to read results"),
                Map.of("usage", "/resync qa list", "description", "List available operations, their inputs, and service limits"),
                Map.of("usage", "/resync qa describe <operation>", "description", "Show an operation's purpose and accepted inputs"),
                Map.of("usage", "/resync qa run <operation> <JSON object>", "description", "Submit an operation and return its receipt without waiting"),
                Map.of("usage", "/resync qa poll <runId>", "description", "Read a submitted operation's state, outputs, or failure")));
    }

    private Map<String, Object> usage(String usage) {
        return Map.of("status", "failed", "code", "QA_INVALID_ARGUMENT", "message", "Invalid QA command arguments", "usage", usage);
    }

    private Map<String, Object> failure(String code, String message) {
        return Map.of("status", "failed", "code", code, "message", message);
    }

    private List<String> matching(List<String> values, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalized)).toList();
    }

    private void send(CommandSender actor, String json) {
        actor.sendMessage(Component.text(json));
    }
}
