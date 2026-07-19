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

        ByteBuffer buffer = ByteBuffer.allocate(4 + apiKeyBytes.length + 4 + clientIdBytes.length + 4 + 4 + clientVersionBytes.length + 4 + capabilitiesBytes.length);

        buffer.putInt(apiKeyBytes.length);
        buffer.put(apiKeyBytes);

        buffer.putInt(clientIdBytes.length);
        buffer.put(clientIdBytes);

        buffer.putInt(protocolVersion);

        buffer.putInt(clientVersionBytes.length);
        buffer.put(clientVersionBytes);
        buffer.putInt(capabilitiesBytes.length);
        buffer.put(capabilitiesBytes);

        return buffer.array();
    }

    @Override
    public void deserialize(ByteBuffer buffer) {
        apiKey = readSizedString(buffer, "API key");
        clientId = readSizedString(buffer, "client ID");
        if (buffer.remaining() < Integer.BYTES) {
            throw new IllegalArgumentException("Handshake protocol version is missing");
        }
        protocolVersion = buffer.getInt();

        if (buffer.remaining() >= 4) {
            clientVersion = readSizedString(buffer, "client version");
        }
        if (buffer.remaining() >= 4) {
            capabilitiesJson = readSizedString(buffer, "capabilities");
        }
    }

    private String readSizedString(ByteBuffer buffer, String field) {
        if (buffer.remaining() < Integer.BYTES) {
            throw new IllegalArgumentException("Handshake " + field + " length is missing");
        }
        int length = buffer.getInt();
        if (length < 0 || length > MAX_FIELD_LENGTH || length > buffer.remaining()) {
            throw new IllegalArgumentException("Invalid handshake " + field + " length");
        }
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
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
}
