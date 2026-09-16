package restudio.resync.contract.canonical;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.canonical.CanonicalLimits;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

public final class CanonicalHash {
    public static final String DOMAIN = CanonicalJson.HASH_NAMESPACE;
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern SEMANTIC_DOMAIN = Pattern.compile("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*");
    private static final int MAX_SEMANTIC_DOMAIN_CODE_POINTS = 64;

    private CanonicalHash() {
    }

    public static String sha256(JsonValue value) {
        return sha256(Objects.requireNonNull(value, "JSON value is required").canonicalBytes());
    }

    public static String sha256(byte[] canonicalBytes) {
        return CanonicalJson.genericCanonicalContentHash(requireCanonical(canonicalBytes));
    }

    public static String sha256(String semanticDomain, JsonValue value) {
        return sha256(semanticDomain, Objects.requireNonNull(value, "JSON value is required").canonicalBytes());
    }

    public static String sha256(String semanticDomain, byte[] canonicalBytes) {
        return CanonicalJson.sha256Canonical(semanticDomain, requireCanonical(canonicalBytes));
    }

    public static String rawSha256(byte[] canonicalBytes) {
        Objects.requireNonNull(canonicalBytes, "Canonical bytes are required");
        try {
            return CanonicalDigests.hex(CanonicalDigests.sha256(canonicalBytes));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static Sidecar sidecar(JsonValue value) {
        return sidecar(Objects.requireNonNull(value, "JSON value is required").canonicalBytes());
    }

    public static Sidecar sidecar(byte[] canonicalBytes) {
        byte[] checked = requireCanonical(canonicalBytes);
        return new Sidecar(DOMAIN, CanonicalJson.genericCanonicalContentHash(checked));
    }

    public static Sidecar semanticSidecar(String semanticDomain, JsonValue value) {
        return semanticSidecar(semanticDomain, Objects.requireNonNull(value, "JSON value is required").canonicalBytes());
    }

    public static Sidecar semanticSidecar(String semanticDomain, byte[] canonicalBytes) {
        byte[] checked = requireCanonical(canonicalBytes);
        return new Sidecar(Objects.requireNonNull(semanticDomain, "Semantic domain is required"), CanonicalJson.sha256Canonical(semanticDomain, checked));
    }

    public static Sidecar parseSidecar(String text) {
        Objects.requireNonNull(text, "Sidecar text is required");
        String domain = null;
        String hash = null;
        for (String line : text.split("\\R")) {
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("domain=") && domain == null) {
                domain = line.substring("domain=".length());
            } else if (line.startsWith("sha256=") && hash == null) {
                hash = line.substring("sha256=".length());
            } else {
                throw new IllegalArgumentException("Invalid canonical hash sidecar");
            }
        }
        if (domain == null || hash == null) {
            throw new IllegalArgumentException("Canonical hash sidecar requires domain and sha256");
        }
        return new Sidecar(domain, hash);
    }

    public static void verify(Sidecar sidecar, byte[] canonicalBytes) {
        Objects.requireNonNull(sidecar, "Sidecar is required");
        byte[] checked = requireCanonical(canonicalBytes);
        String actual = sidecar.domain().equals(DOMAIN)
            ? CanonicalJson.genericCanonicalContentHash(checked)
            : CanonicalJson.sha256Canonical(sidecar.domain(), checked);
        if (!sidecar.sha256().equals(actual)) {
            throw new IllegalArgumentException("Canonical hash sidecar does not match canonical bytes");
        }
    }

    private static byte[] requireCanonical(byte[] bytes) {
        Objects.requireNonNull(bytes, "Canonical bytes are required");
        return CanonicalCodec.canonicalBytes(bytes, CanonicalLimits.standard());
    }

    public record Sidecar(String domain, String sha256) {
        public Sidecar {
            Objects.requireNonNull(domain, "Hash domain is required");
            validateDomain(domain);
            Objects.requireNonNull(sha256, "Hash is required");
            if (!HASH.matcher(sha256).matches()) {
                throw new IllegalArgumentException("Invalid SHA-256 hash");
            }
        }

        public String text() {
            return "domain=" + domain + "\nsha256=" + sha256 + "\n";
        }

        public boolean matches(byte[] canonicalBytes) {
            try {
                verify(this, canonicalBytes);
                return true;
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }

        private static void validateDomain(String domain) {
            if (domain.isBlank() || domain.indexOf('\u0000') >= 0 || domain.indexOf('\n') >= 0 || domain.indexOf('\r') >= 0 || !StandardCharsets.US_ASCII.newEncoder().canEncode(domain)) {
                throw new IllegalArgumentException("Invalid hash domain");
            }
            if (!DOMAIN.equals(domain) && (domain.codePointCount(0, domain.length()) > MAX_SEMANTIC_DOMAIN_CODE_POINTS || !SEMANTIC_DOMAIN.matcher(domain).matches())) {
                throw new IllegalArgumentException("Invalid semantic hash domain: " + domain);
            }
        }
    }
}
