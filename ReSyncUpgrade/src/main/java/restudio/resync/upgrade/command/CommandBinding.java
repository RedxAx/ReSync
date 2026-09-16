package restudio.resync.upgrade.command;

import java.util.Objects;

public record CommandBinding(String bindingId, String flowId, String context) {
    public CommandBinding {
        bindingId = requireIdentity(bindingId, "bindingId");
        flowId = requireIdentity(flowId, "flowId");
        context = Objects.requireNonNull(context, "context");
        if (context.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("COMMAND_BINDING_INVALID: context contains a NUL character");
        }
    }

    private static String requireIdentity(String value, String field) {
        String identity = Objects.requireNonNull(value, field);
        if (identity.isBlank() || identity.indexOf('\u0000') >= 0 || identity.indexOf('\n') >= 0 || identity.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("COMMAND_BINDING_INVALID: " + field + " is invalid");
        }
        return identity;
    }
}
