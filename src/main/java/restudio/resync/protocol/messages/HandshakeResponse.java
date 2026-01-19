package restudio.resync.protocol.messages;

import restudio.resync.protocol.MessageType;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class HandshakeResponse extends Message {

    private boolean success;
    private String message;
    private int serverProtocolVersion;
    private String serverVersion;
    private List<String> worlds;
    private int[] supportedTileSizes;

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

        ByteBuffer buffer = ByteBuffer.allocate(
            1 + 4 + messageBytes.length +
            4 + 4 + serverVersionBytes.length +
            4 + worldsBytesLength +
            4 + (supportedTileSizes != null ? supportedTileSizes.length * 4 : 0)
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
}
