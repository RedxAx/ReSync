package restudio.resync.flow.canonical;

public record CanonicalLimits(
    int inputBytes,
    int canonicalBytes,
    int depth,
    long tokens,
    int stringCodePoints,
    int opaqueSubtreeBytes,
    int numericTokenCodePoints,
    int numericPrecisionDigits,
    int canonicalNumericExpansionCodePoints,
    int decimalPrecisionDigits,
    int decimalScale,
    boolean rejectBom
) {
    public static final String HASH_DOMAIN = "restudio.resync.contract.canonical/1";

    private static final CanonicalLimits STANDARD = new CanonicalLimits(
        33_554_432,
        33_554_432,
        64,
        1_000_000,
        1_048_576,
        1_048_576,
        128,
        128,
        4_096,
        38,
        18,
        true
    );

    private static final CanonicalLimits CATALOG = new CanonicalLimits(
        33_554_432,
        33_554_432,
        64,
        4_000_000,
        1_048_576,
        1_048_576,
        128,
        128,
        4_096,
        38,
        18,
        true
    );

    public CanonicalLimits {
        if (inputBytes < 1 || canonicalBytes < 1 || depth < 1 || tokens < 1 || stringCodePoints < 1 || opaqueSubtreeBytes < 1 || numericTokenCodePoints < 1 || numericPrecisionDigits < 1 || canonicalNumericExpansionCodePoints < 1 || decimalPrecisionDigits < 1 || decimalScale < 0) {
            throw new IllegalArgumentException("Canonical limits must be positive");
        }
    }

    public static CanonicalLimits standard() {
        return STANDARD;
    }

    public static CanonicalLimits catalog() {
        return CATALOG;
    }
}
