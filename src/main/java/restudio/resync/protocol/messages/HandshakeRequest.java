package restudio.resync.protocol.messages;

import restudio.resync.protocol.MessageType;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class HandshakeRequest extends Message {
    private static final int MAX_FIELD_LENGTH = 65_536;

    private String apiKey;
    private String clientId;
    private int protocolVersion = 2;
    private String clientVersion;
    private String capabilitiesJson = "";
    private String collaborationProfileJson = "";

    @Override
    public MessageType getType() {
        return MessageType.HANDSHAKE_REQUEST;
    }

    @Override
    public byte[] serialize() {
        byte[] apiKeyBytes = apiKey != null ? apiKey.getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] clientIdBytes = clientId != null ? clientId.getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] clientVersionBytes = clientVersion != null ? clientVersion.getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] capabilitiesBytes = capabilitiesJson != null ? capabilitiesJson.getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] collaborationProfileBytes = collaborationProfileJson != null ? collaborationProfileJson.getBytes(StandardCharsets.UTF_8) : new byte[0];

        ByteBuffer buffer = ByteBuffer.allocate(4 + apiKeyBytes.length + 4 + clientIdBytes.length + 4 + 4 + clientVersionBytes.length
            + 4 + capabilitiesBytes.length + 4 + collaborationProfileBytes.length);

        buffer.putInt(apiKeyBytes.length);
        buffer.put(apiKeyBytes);

        buffer.putInt(clientIdBytes.length);
        buffer.put(clientIdBytes);

        buffer.putInt(protocolVersion);

        buffer.putInt(clientVersionBytes.length);
        buffer.put(clientVersionBytes);
        buffer.putInt(capabilitiesBytes.length);
        buffer.put(capabilitiesBytes);
        buffer.putInt(collaborationProfileBytes.length);
        buffer.put(collaborationProfileBytes);

        return buffer.array();
    }

    @Override
    public void deserialize(ByteBuffer buffer) {
        apiKey = readString(buffer, "Handshake API key", MAX_FIELD_LENGTH);
        clientId = readString(buffer, "Handshake client ID", MAX_FIELD_LENGTH);
        requireBytes(buffer, Integer.BYTES, "Handshake protocol version");
        protocolVersion = buffer.getInt();
        clientVersion = null;
        capabilitiesJson = "";
        collaborationProfileJson = "";

        if (buffer.hasRemaining()) {
            clientVersion = readString(buffer, "Handshake client version", MAX_FIELD_LENGTH);
        }
        if (buffer.hasRemaining()) {
            capabilitiesJson = readString(buffer, "Handshake capabilities", MAX_FIELD_LENGTH);
        }
        if (buffer.hasRemaining()) {
            collaborationProfileJson = readString(buffer, "Handshake collaboration profile", MAX_FIELD_LENGTH);
        }
        requireComplete(buffer);
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public int getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(int protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    public String getClientVersion() {
        return clientVersion;
    }

    public void setClientVersion(String clientVersion) {
        this.clientVersion = clientVersion;
    }

    public String getCapabilitiesJson() {
        return capabilitiesJson;
    }

    public void setCapabilitiesJson(String capabilitiesJson) {
        this.capabilitiesJson = capabilitiesJson != null ? capabilitiesJson : "";
    }

    public String getCollaborationProfileJson() {
        return collaborationProfileJson;
    }

    public void setCollaborationProfileJson(String collaborationProfileJson) {
        this.collaborationProfileJson = collaborationProfileJson != null ? collaborationProfileJson : "";
    }
}
