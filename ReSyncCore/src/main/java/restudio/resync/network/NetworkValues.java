package restudio.resync.network;

final class NetworkValues {
    private NetworkValues() {
    }

    static String required(String value, String label) {
        String normalized = normalized(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(label + " Is Required");
        }
        return normalized;
    }

    static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
