package restudio.resync.flow.runtime;

import java.util.Locale;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RuntimePrincipalAuthority {
    private final RuntimeAuthority authority;
    private final ConcurrentHashMap<UUID, RuntimePrincipal> issued = new ConcurrentHashMap<>();

    public RuntimePrincipalAuthority(RuntimeAuthority authority) {
        this.authority = Objects.requireNonNull(authority, "Runtime Authority Is Required");
    }

    public RuntimeAuthority authority() {
        return authority;
    }

    public RuntimePrincipal issueServer(String identity) {
        return issue(RuntimePrincipal.Kind.SERVER, identity);
    }

    public RuntimePrincipal issueSystem(String identity) {
        return issue(RuntimePrincipal.Kind.SYSTEM, identity);
    }

    public RuntimePrincipal issuePersistentSystem(String identity) {
        String normalized = Objects.requireNonNull(identity, "Persistent System Identity Is Required").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Persistent System Identity Is Required");
        }
        UUID token = UUID.nameUUIDFromBytes((authority.identity() + ":system:" + normalized)
            .getBytes(StandardCharsets.UTF_8));
        return issued.computeIfAbsent(token, ignored ->
            new RuntimePrincipal(RuntimePrincipal.Kind.SYSTEM, normalized, token, authority.identity()));
    }

    public RuntimePrincipal issuePlayer(String identity) {
        return issue(RuntimePrincipal.Kind.PLAYER, identity);
    }

    public RuntimePrincipal issueAuthenticatedClient(String identity) {
        return issue(RuntimePrincipal.Kind.AUTHENTICATED_CLIENT, identity);
    }

    public RuntimePrincipal issueExtension(String identity) {
        return issue(RuntimePrincipal.Kind.EXTENSION, identity);
    }

    public boolean trusts(RuntimePrincipal principal, RuntimeAuthority requestedAuthority) {
        if (principal == null || requestedAuthority == null || requestedAuthority != authority
            || !principal.authorityIdentity().equals(authority.identity())) {
            return false;
        }
        return issued.get(principal.token()) == principal;
    }

    public void revoke(RuntimePrincipal principal) {
        if (principal != null) {
            issued.remove(principal.token(), principal);
        }
    }

    private RuntimePrincipal issue(RuntimePrincipal.Kind kind, String identity) {
        String normalized = identity == null ? "" : identity;
        UUID token = UUID.nameUUIDFromBytes((authority.identity() + ":" + kind.name().toLowerCase(Locale.ROOT) + ":" + normalized)
            .getBytes(StandardCharsets.UTF_8));
        return issued.computeIfAbsent(token, ignored ->
            new RuntimePrincipal(kind, normalized, token, authority.identity()));
    }
}
