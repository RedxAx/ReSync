package restudio.resync.protocol.messages;

import restudio.resync.protocol.MessageType;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class HandshakeResponse extends Message {

    private boolean success;
    private String message;
    private int serverProtocolVersion;
    private String serverVersion;
    private List<String> worlds;
    private int[] supportedTileSizes;
    private Map<String, Integer> channels = new LinkedHashMap<>();
    private String capabilitiesJson = "";

    @Override
    public MessageType getType() {
        return MessageType.HANDSHAKE_RESPONSE;
    }

    @Override
    public byte[] serialize() {
        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
        byte[] serverVersionBytes = serverVersion != null ? serverVersion.getBytes(StandardCharsets.UTF_8) : new byte[0];

        int worldsBytesLength = 0;
        if (worlds != null) {
            for (String world : worlds) {
                worldsBytesLength += 4 + world.getBytes(StandardCharsets.UTF_8).length;
            }
        }

        byte[] capabilitiesBytes = capabilitiesBytes();
        ByteBuffer buffer = ByteBuffer.allocate(
            1 + 4 + messageBytes.length +
            4 + 4 + serverVersionBytes.length +
            4 + worldsBytesLength +
            4 + (supportedTileSizes != null ? supportedTileSizes.length * 4 : 0) +
            4 + channelsBytesLength() +
            4 + capabilitiesBytes.length
        );

        buffer.put((byte) (success ? 1 : 0));

        buffer.putInt(messageBytes.length);
        buffer.put(messageBytes);

        buffer.putInt(serverProtocolVersion);

        buffer.putInt(serverVersionBytes.length);
        buffer.put(serverVersionBytes);

        buffer.putInt(worlds != null ? worlds.size() : 0);
        if (worlds != null) {
            for (String world : worlds) {
                byte[] worldBytes = world.getBytes(StandardCharsets.UTF_8);
                buffer.putInt(worldBytes.length);
                buffer.put(worldBytes);
            }
        }

        buffer.putInt(supportedTileSizes != null ? supportedTileSizes.length : 0);
        if (supportedTileSizes != null) {
            for (int tileSize : supportedTileSizes) {
                buffer.putInt(tileSize);
            }
        }

        buffer.putInt(channels != null ? channels.size() : 0);
        if (channels != null) {
            for (Map.Entry<String, Integer> entry : channels.entrySet()) {
                byte[] channelBytes = entry.getKey().getBytes(StandardCharsets.UTF_8);
                buffer.putInt(channelBytes.length);
                buffer.put(channelBytes);
                buffer.putInt(entry.getValue());
            }
        }

        buffer.putInt(capabilitiesBytes.length);
        buffer.put(capabilitiesBytes);
        return buffer.array();
    }

    @Override
    public void deserialize(ByteBuffer buffer) {
        success = buffer.get() == 1;

        int messageLen = buffer.getInt();
        byte[] messageBytes = new byte[messageLen];
        buffer.get(messageBytes);
        message = new String(messageBytes, StandardCharsets.UTF_8);

        serverProtocolVersion = buffer.getInt();

        int serverVersionLen = buffer.getInt();
        byte[] serverVersionBytes = new byte[serverVersionLen];
        buffer.get(serverVersionBytes);
        serverVersion = new String(serverVersionBytes, StandardCharsets.UTF_8);

        int worldCount = buffer.getInt();
        if (worldCount > 0) {
            String[] worldsArray = new String[worldCount];
            for (int i = 0; i < worldCount; i++) {
                int worldLen = buffer.getInt();
                byte[] worldBytes = new byte[worldLen];
                buffer.get(worldBytes);
                worldsArray[i] = new String(worldBytes, StandardCharsets.UTF_8);
            }
            worlds = Arrays.asList(worldsArray);
        }

        int tileSizeCount = buffer.getInt();
        if (tileSizeCount > 0) {
            supportedTileSizes = new int[tileSizeCount];
            for (int i = 0; i < tileSizeCount; i++) {
                supportedTileSizes[i] = buffer.getInt();
            }
        }

        if (buffer.remaining() >= 4) {
            int channelCount = buffer.getInt();
            channels = new LinkedHashMap<>();
            for (int i = 0; i < channelCount && buffer.remaining() >= 8; i++) {
                int channelLen = buffer.getInt();
                if (channelLen < 0 || buffer.remaining() < channelLen + 4) {
                    break;
                }
                byte[] channelBytes = new byte[channelLen];
                buffer.get(channelBytes);
                channels.put(new String(channelBytes, StandardCharsets.UTF_8), buffer.getInt());
            }
        }
        if (buffer.remaining() >= 4) {
            int capabilitiesLen = buffer.getInt();
            if (capabilitiesLen >= 0 && buffer.remaining() >= capabilitiesLen) {
                byte[] capabilitiesBytes = new byte[capabilitiesLen];
                buffer.get(capabilitiesBytes);
                capabilitiesJson = new String(capabilitiesBytes, StandardCharsets.UTF_8);
            }
        }
    }

    private int channelsBytesLength() {
        if (channels == null || channels.isEmpty()) {
            return 0;
        }
        int length = 0;
        for (String channel : channels.keySet()) {
            length += 4 + channel.getBytes(StandardCharsets.UTF_8).length + 4;
        }
        return length;
    }

    private byte[] capabilitiesBytes() {
        return capabilitiesJson != null ? capabilitiesJson.getBytes(StandardCharsets.UTF_8) : new byte[0];
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public int getServerProtocolVersion() {
        return serverProtocolVersion;
    }

    public void setServerProtocolVersion(int serverProtocolVersion) {
        this.serverProtocolVersion = serverProtocolVersion;
    }

    public String getServerVersion() {
        return serverVersion;
    }

    public void setServerVersion(String serverVersion) {
        this.serverVersion = serverVersion;
    }

    public List<String> getWorlds() {
        return worlds;
    }

    public void setWorlds(List<String> worlds) {
        this.worlds = worlds;
    }

    public int[] getSupportedTileSizes() {
        return supportedTileSizes;
    }

    public void setSupportedTileSizes(int[] supportedTileSizes) {
        this.supportedTileSizes = supportedTileSizes;
    }

    public Map<String, Integer> getChannels() {
        return channels;
    }

    public void setChannels(Map<String, Integer> channels) {
        this.channels = channels == null ? new LinkedHashMap<>() : new LinkedHashMap<>(channels);
    }

    public String getCapabilitiesJson() {
        return capabilitiesJson;
    }

    public void setCapabilitiesJson(String capabilitiesJson) {
        this.capabilitiesJson = capabilitiesJson != null ? capabilitiesJson : "";
    }
}
