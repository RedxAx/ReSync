package restudio.resync.protocol;

public record ReSyncCompressionNegotiation(boolean enabled, String algorithm, int thresholdBytes) {
    public ReSyncCompressionNegotiation {
        if (thresholdBytes < 0) {
            throw new ReSyncProtocolException(ReSyncProtocolException.Reason.INVALID_CONFIGURATION,
                "ReSync compression threshold cannot be negative");
        }
        if (enabled && (algorithm == null || algorithm.isBlank())) {
            throw new ReSyncProtocolException(ReSyncProtocolException.Reason.INVALID_CONFIGURATION,
                "ReSync compression algorithm is required when compression is enabled");
        }
        algorithm = algorithm == null ? "" : algorithm.trim();
    }

    public static ReSyncCompressionNegotiation disabled() {
        return new ReSyncCompressionNegotiation(false, "", 0);
    }

    public static ReSyncCompressionNegotiation enabled(String algorithm, int thresholdBytes) {
        return new ReSyncCompressionNegotiation(true, algorithm, thresholdBytes);
    }

    public boolean supports(ReSyncCompression compression) {
        return enabled && compression != null && algorithm.equalsIgnoreCase(compression.algorithm());
    }
}
