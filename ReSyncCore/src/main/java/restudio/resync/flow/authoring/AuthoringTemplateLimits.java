package restudio.resync.flow.authoring;

import restudio.resync.flow.canonical.CanonicalLimits;

public final class AuthoringTemplateLimits {
    public static final int MAX_INPUT_BYTES = 8_388_608;
    public static final int MAX_CANONICAL_BYTES = 8_388_608;
    public static final int MAX_STRING_CODE_POINTS = 1_048_576;
    public static final int MAX_OPAQUE_SUBTREE_BYTES = 1_048_576;
    public static final int MAX_CAPABILITIES = 256;
    public static final CanonicalLimits CANONICAL = new CanonicalLimits(
        MAX_INPUT_BYTES,
        MAX_CANONICAL_BYTES,
        64,
        1_000_000,
        MAX_STRING_CODE_POINTS,
        MAX_OPAQUE_SUBTREE_BYTES,
        128,
        128,
        4_096,
        38,
        18,
        true
    );

    private AuthoringTemplateLimits() {
    }

    public static CanonicalLimits canonical() {
        return CANONICAL;
    }
}
