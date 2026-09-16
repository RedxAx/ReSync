package restudio.resync.flow.runtime;

import java.util.Objects;

public record RuntimeAuthority(String identity) {
    public RuntimeAuthority {
        identity = Objects.requireNonNull(identity, "Authority Identity Is Required").trim();
        if (identity.isEmpty() || identity.length() > 256) {
            throw new IllegalArgumentException("Authority Identity Must Contain Between 1 And 256 Characters");
        }
    }

    public static RuntimeAuthority anonymous() {
        return new RuntimeAuthority("anonymous");
    }
}
