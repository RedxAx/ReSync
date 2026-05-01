package restudio.resync.security;

import restudio.resync.server.ReSyncConfig;

import java.util.regex.Pattern;

public class ClientAuthorizer {
    private static final Pattern CLIENT_ID_PATTERN = Pattern.compile("[A-Za-z0-9_.:-]{3,96}");
    private final ReSyncConfig config;

    public ClientAuthorizer(ReSyncConfig config) {
        this.config = config;
    }

    public ClientIdentity authorize(String apiKey, String clientId, String clientVersion) {
        if (apiKey == null || apiKey.isBlank() || !apiKey.equals(config.getApiKey())) {
            throw new SecurityException("Invalid API key");
        }
        if (clientId == null || clientId.isBlank() || !CLIENT_ID_PATTERN.matcher(clientId).matches()) {
            throw new SecurityException("Invalid client id");
        }
        return new ClientIdentity(clientId, clientVersion);
    }
}
