package restudio.resync.protocol;

import java.util.List;
import java.util.Locale;

public final class ReSyncTransportSecurity {
    public static final String CAPABILITY_KEY = "transportSecurity";
    public static final String SCHEME = "scheme";
    public static final String TLS = "tls";
    public static final String BROWSER_RELAY = "browserRelay";
    public static final String PROTOCOLS = "protocols";
    public static final String CERTIFICATE_FINGERPRINT = "certificateFingerprint";
    public static final String SPKI_FINGERPRINT = "spkiFingerprint";
    public static final String SPKI_PIN = "spkiPin";
    public static final String KEY_STORE_REVISION = "keyStoreRevision";
    public static final String RUNTIME_METADATA_VERSION = "runtimeMetadataVersion";
    public static final String ROTATION_ENABLED = "rotationEnabled";
    public static final String ROTATION_STATUS = "rotationStatus";
    public static final String ROTATION_LEAD_TIME_SECONDS = "rotationLeadTimeSeconds";
    public static final String CERTIFICATE_NOT_AFTER = "certificateNotAfter";
    public static final String NEXT_ROTATION_AT = "nextRotationAt";
    public static final String LAST_ROTATION_AT = "lastRotationAt";
    public static final String REVISION_EVENTS = "revisionEvents";
    public static final String REVISION_EVENT = "capabilityRevision";
    public static final String EVENT_VERSION = "version";
    public static final String EVENT_CAPABILITY = "capability";
    public static final String EVENT_PHASE = "phase";
    public static final String EVENT_EFFECTIVE_AT = "effectiveAt";
    public static final String EVENT_PREVIOUS_REVISION = "previousRevision";
    public static final String EVENT_REVISION = "revision";
    public static final String EVENT_FAILED_REVISION = "failedRevision";
    public static final String EVENT_PAYLOAD = "payload";
    public static final int REVISION_EVENT_VERSION = 1;
    public static final List<String> MODERN_TLS_PROTOCOLS = List.of("TLSv1.3", "TLSv1.2");

    private ReSyncTransportSecurity() {
    }

    public static boolean isSecureScheme(String scheme) {
        return "wss".equals(normalize(scheme));
    }

    public static boolean isLoopbackHost(String host) {
        String normalized = normalize(host);
        return "127.0.0.1".equals(normalized) || "localhost".equals(normalized) || "::1".equals(normalized)
            || "[::1]".equals(normalized) || "0:0:0:0:0:0:0:1".equals(normalized) || "[0:0:0:0:0:0:0:1]".equals(normalized);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
