package restudio.resync.protocol;

public record ReSyncHandshakeRequest(String apiKey, String clientId, int protocolVersion, String clientVersion,
                                     String capabilitiesJson, String collaborationProfileJson) {
    public ReSyncHandshakeRequest {
        apiKey = apiKey == null ? "" : apiKey;
        clientId = clientId == null ? "" : clientId;
        clientVersion = clientVersion == null ? "" : clientVersion;
        capabilitiesJson = capabilitiesJson == null ? "" : capabilitiesJson;
        collaborationProfileJson = collaborationProfileJson == null ? "" : collaborationProfileJson;
    }
}
