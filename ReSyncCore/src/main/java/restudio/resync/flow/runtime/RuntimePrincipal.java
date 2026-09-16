package restudio.resync.flow.runtime;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class RuntimePrincipal {
    public enum Kind {
        SERVER,
        SYSTEM,
        PLAYER,
        AUTHENTICATED_CLIENT,
        EXTENSION
    }

    private final Kind kind;
    private final String identity;
    private final UUID token;
    private final String authorityIdentity;

    RuntimePrincipal(Kind kind, String identity, UUID token, String authorityIdentity) {
        this.kind = Objects.requireNonNull(kind, "Principal Kind Is Required");
        this.identity = requireText(identity, "Principal Identity");
        this.token = Objects.requireNonNull(token, "Principal Token Is Required");
        this.authorityIdentity = requireText(authorityIdentity, "Principal Authority Identity");
    }

    public Kind kind() {
        return kind;
    }

    public String identity() {
        return identity;
    }

    public String authorityIdentity() {
        return authorityIdentity;
    }

    UUID token() {
        return token;
    }

    public String canonical() {
        return kind.name().toLowerCase(Locale.ROOT) + ":" + identity;
    }

    @Override
    public String toString() {
        return canonical();
    }

    private static String requireText(String value, String label) {
        String normalized = Objects.requireNonNull(value, label + " Is Required").trim();
        if (normalized.isEmpty() || normalized.length() > 256 || normalized.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException(label + " Must Be Canonical Text");
        }
        return normalized;
    }
}
