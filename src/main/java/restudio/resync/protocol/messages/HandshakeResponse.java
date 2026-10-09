package restudio.resync.protocol.messages;

import restudio.resync.protocol.MessageType;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
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
        requireBytes(buffer, Byte.BYTES, "Handshake success");
        byte status = buffer.get();
        if (status != 0 && status != 1) {
            throw new IllegalArgumentException("Invalid handshake success");
        }
        success = status == 1;
        message = readString(buffer, "Handshake message");
        requireBytes(buffer, Integer.BYTES, "Handshake protocol version");
        serverProtocolVersion = buffer.getInt();
        serverVersion = readString(buffer, "Handshake server version");

        int worldCount = readCount(buffer, "Handshake world", Integer.BYTES);
        worlds = null;
        if (worldCount > 0) {
            String[] worldsArray = new String[worldCount];
            for (int i = 0; i < worldCount; i++) {
                worldsArray[i] = readString(buffer, "Handshake world");
            }
            worlds = Arrays.asList(worldsArray);
        }

        int tileSizeCount = readCount(buffer, "Handshake tile size", Integer.BYTES);
        supportedTileSizes = null;
        if (tileSizeCount > 0) {
            supportedTileSizes = new int[tileSizeCount];
            for (int i = 0; i < tileSizeCount; i++) {
                supportedTileSizes[i] = buffer.getInt();
            }
        }

        channels = new LinkedHashMap<>();
        if (buffer.hasRemaining()) {
            int channelCount = readCount(buffer, "Handshake channel", Integer.BYTES * 2);
            for (int i = 0; i < channelCount; i++) {
                String channel = readString(buffer, "Handshake channel");
                requireBytes(buffer, Integer.BYTES, "Handshake channel number");
                channels.put(channel, buffer.getInt());
            }
        }
        capabilitiesJson = "";
        if (buffer.hasRemaining()) {
            capabilitiesJson = readString(buffer, "Handshake capabilities");
        }
        requireComplete(buffer);
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
