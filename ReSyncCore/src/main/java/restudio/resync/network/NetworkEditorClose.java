package restudio.resync.network;

import java.util.Objects;
import java.util.UUID;

public record NetworkEditorClose(UUID tunnelId, int code, String reason) {
    public NetworkEditorClose {
        Objects.requireNonNull(tunnelId, "Tunnel ID");
        if (code < 1000 || code > 4999) throw new IllegalArgumentException("Editor Close Code Is Invalid");
        reason = reason == null ? "" : reason;
        if (reason.length() > 256) throw new IllegalArgumentException("Editor Close Reason Is Too Long");
    }
}
