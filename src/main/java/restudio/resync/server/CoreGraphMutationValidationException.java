package restudio.resync.server;

import restudio.resync.flow.diagnostic.Diagnostic;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class CoreGraphMutationValidationException extends IllegalArgumentException {
    private static final int MAX_MESSAGE_LENGTH = 512;
    private final List<Diagnostic> diagnostics;

    public CoreGraphMutationValidationException(List<Diagnostic> diagnostics) {
        super(message(diagnostics));
        this.diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "Core graph diagnostics are required"));
        if (this.diagnostics.isEmpty()) {
            throw new IllegalArgumentException("Core graph validation diagnostics are required");
        }
    }

    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    public String actionableMessage() {
        return getMessage();
    }

    private static String message(List<Diagnostic> diagnostics) {
        Objects.requireNonNull(diagnostics, "Core graph diagnostics are required");
        String details = diagnostics.stream()
            .map(diagnostic -> diagnostic.code() + ": " + diagnostic.message() + " " + diagnostic.remediation())
            .collect(Collectors.joining(" | "));
        String value = "Core graph mutation was rejected: " + details;
        return value.length() <= MAX_MESSAGE_LENGTH ? value : value.substring(0, MAX_MESSAGE_LENGTH - 3) + "...";
    }
}
