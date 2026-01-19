package restudio.resync.protocol.messages;

import restudio.resync.protocol.MessageType;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class HandshakeRequest extends Message {

    private String apiKey;
    private String clientId;
    private int protocolVersion = 2;
    private String clientVersion;

    @Override
    public MessageType getType() {
        return MessageType.HANDSHAKE_REQUEST;
    }

    @Override
    public byte[] serialize() {
        byte[] apiKeyBytes = apiKey.getBytes(StandardCharsets.UTF_8);
        byte[] clientIdBytes = clientId.getBytes(StandardCharsets.UTF_8);
        byte[] clientVersionBytes = clientVersion != null ? clientVersion.getBytes(StandardCharsets.UTF_8) : new byte[0];

        ByteBuffer buffer = ByteBuffer.allocate(4 + apiKeyBytes.length + 4 + clientIdBytes.length + 4 + clientVersionBytes.length);

        buffer.putInt(apiKeyBytes.length);
        buffer.put(apiKeyBytes);

        buffer.putInt(clientIdBytes.length);
        buffer.put(clientIdBytes);

        buffer.putInt(protocolVersion);

        buffer.putInt(clientVersionBytes.length);
        buffer.put(clientVersionBytes);

        return buffer.array();
    }

    @Override
    public void deserialize(ByteBuffer buffer) {
        int apiKeyLen = buffer.getInt();
        byte[] apiKeyBytes = new byte[apiKeyLen];
        buffer.get(apiKeyBytes);
        apiKey = new String(apiKeyBytes, StandardCharsets.UTF_8);

        int clientIdLen = buffer.getInt();
        byte[] clientIdBytes = new byte[clientIdLen];
        buffer.get(clientIdBytes);
        clientId = new String(clientIdBytes, StandardCharsets.UTF_8);

        protocolVersion = buffer.getInt();

        if (buffer.remaining() >= 4) {
            int clientVersionLen = buffer.getInt();
            byte[] clientVersionBytes = new byte[clientVersionLen];
            buffer.get(clientVersionBytes);
            clientVersion = new String(clientVersionBytes, StandardCharsets.UTF_8);
        }
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
}
