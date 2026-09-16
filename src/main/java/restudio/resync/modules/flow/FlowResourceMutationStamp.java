package restudio.resync.modules.flow;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record FlowResourceMutationStamp(String type, String id, long revision, UUID mutationId,
                                        String payloadHash, boolean deleted) {
    private static final Pattern PAYLOAD_HASH = Pattern.compile("[0-9a-f]{64}");

    public FlowResourceMutationStamp {
        type = required(type, "type");
        id = required(id, "id");
        if (revision < 1L) {
            throw new IllegalArgumentException("revision must be positive");
        }
        mutationId = Objects.requireNonNull(mutationId, "mutationId is required");
        payloadHash = required(payloadHash, "payloadHash");
        if (!PAYLOAD_HASH.matcher(payloadHash).matches()) {
            throw new IllegalArgumentException("payloadHash must be 64 lowercase hexadecimal characters");
        }
    }

    private static String required(String value, String field) {
        Objects.requireNonNull(value, field + " is required");
        if (value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(field + " must be non-blank and untrimmed");
        }
        return value;
    }
}
