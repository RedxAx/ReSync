package restudio.resync.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ReSyncHandshakeCodec {
    public static final int DEFAULT_MAX_PAYLOAD_BYTES = ReSyncProtocolContract.MAX_DECOMPRESSED_PAYLOAD_BYTES;
    public static final int DEFAULT_MAX_FIELD_BYTES = ReSyncProtocolContract.MAX_HANDSHAKE_FIELD_BYTES;
    public static final int DEFAULT_MAX_COLLECTION_ENTRIES = ReSyncProtocolContract.MAX_HANDSHAKE_COLLECTION_ENTRIES;

    private final int maximumPayloadBytes;
    private final int maximumFieldBytes;
    private final int maximumCollectionEntries;

    public ReSyncHandshakeCodec() {
        this(DEFAULT_MAX_PAYLOAD_BYTES, DEFAULT_MAX_FIELD_BYTES, DEFAULT_MAX_COLLECTION_ENTRIES);
    }

    public ReSyncHandshakeCodec(int maximumPayloadBytes) {
        this(maximumPayloadBytes, Math.min(maximumPayloadBytes, DEFAULT_MAX_FIELD_BYTES), DEFAULT_MAX_COLLECTION_ENTRIES);
    }

    public ReSyncHandshakeCodec(int maximumPayloadBytes, int maximumFieldBytes, int maximumCollectionEntries) {
        if (maximumPayloadBytes < 1 || maximumFieldBytes < 1 || maximumCollectionEntries < 1
            || maximumFieldBytes > maximumPayloadBytes) {
            throw new ReSyncProtocolException(ReSyncProtocolException.Reason.INVALID_CONFIGURATION,
                "ReSync handshake limits are invalid");
        }
        this.maximumPayloadBytes = maximumPayloadBytes;
        this.maximumFieldBytes = maximumFieldBytes;
        this.maximumCollectionEntries = maximumCollectionEntries;
    }

    public byte[] encodeRequest(ReSyncHandshakeRequest request) {
        if (request == null) {
            throw malformed("ReSync handshake request is required");
        }
        byte[] apiKey = fieldBytes(request.apiKey(), "API key");
        byte[] clientId = fieldBytes(request.clientId(), "client ID");
        byte[] clientVersion = fieldBytes(request.clientVersion(), "client version");
        byte[] capabilities = fieldBytes(request.capabilitiesJson(), "capabilities");
        byte[] collaboration = fieldBytes(request.collaborationProfileJson(), "collaboration profile");
        ByteBuffer buffer = ByteBuffer.allocate(4 + apiKey.length + 4 + clientId.length + Integer.BYTES
            + 4 + clientVersion.length + 4 + capabilities.length + 4 + collaboration.length);
        writeBytes(buffer, apiKey);
        writeBytes(buffer, clientId);
        buffer.putInt(request.protocolVersion());
        writeBytes(buffer, clientVersion);
        writeBytes(buffer, capabilities);
        writeBytes(buffer, collaboration);
        return checkedPayload(buffer.array());
    }

    public ReSyncHandshakeRequest decodeRequest(byte[] payload) {
        ByteBuffer buffer = checkedInput(payload);
        String apiKey = readString(buffer, "API key");
        String clientId = readString(buffer, "client ID");
        int protocolVersion = readInt(buffer, "protocol version");
        String clientVersion = buffer.hasRemaining() ? readString(buffer, "client version") : "";
        String capabilities = buffer.hasRemaining() ? readString(buffer, "capabilities") : "";
        String collaboration = buffer.hasRemaining() ? readString(buffer, "collaboration profile") : "";
        requireConsumed(buffer, "handshake request");
        return new ReSyncHandshakeRequest(apiKey, clientId, protocolVersion, clientVersion, capabilities, collaboration);
    }

    public byte[] encodeResponse(ReSyncHandshakeResponse response) {
        if (response == null) {
            throw malformed("ReSync handshake response is required");
        }
        byte[] message = fieldBytes(response.message(), "message");
        byte[] serverVersion = fieldBytes(response.serverVersion(), "server version");
        byte[] capabilities = fieldBytes(response.capabilitiesJson(), "capabilities");
        List<String> worlds = response.worlds();
        if (worlds.size() > maximumCollectionEntries) {
            throw collectionTooLarge("worlds");
        }
        int[] tileSizes = response.supportedTileSizes();
        if (tileSizes.length > maximumCollectionEntries) {
            throw collectionTooLarge("tile sizes");
        }
        Set<Integer> tileSizeValues = new HashSet<>();
        for (int tileSize : tileSizes) {
            if (tileSize <= 0 || !tileSizeValues.add(tileSize)) {
                throw malformed("Handshake tile size is invalid");
            }
        }
        Map<String, Integer> channels = response.channels();
        if (channels.size() > maximumCollectionEntries) {
            throw collectionTooLarge("channels");
        }
        List<byte[]> worldBytes = new ArrayList<>(worlds.size());
        int worldsLength = 0;
        for (String world : worlds) {
            byte[] bytes = fieldBytes(world, "world");
            worldBytes.add(bytes);
            worldsLength = checkedAdd(worldsLength, checkedAdd(4, bytes.length));
        }
        List<Map.Entry<byte[], Integer>> channelBytes = new ArrayList<>(channels.size());
        Set<Integer> channelIds = new HashSet<>();
        int channelsLength = 0;
        for (Map.Entry<String, Integer> entry : channels.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw malformed("Channel name is required");
            }
            byte[] bytes = fieldBytes(entry.getKey(), "channel");
            if (entry.getValue() == null) {
                throw malformed("Channel identifier has no numeric value");
            }
            if (entry.getValue() < 0 || entry.getValue() > ReSyncProtocolContract.MAX_CHANNEL_ID) {
                throw malformed("Channel identifier has an invalid numeric value");
            }
            if (!channelIds.add(entry.getValue())) {
                throw malformed("Handshake contains a duplicate channel identifier");
            }
            channelBytes.add(Map.entry(bytes, entry.getValue()));
            channelsLength = checkedAdd(channelsLength, checkedAdd(checkedAdd(4, bytes.length), 4));
        }
        int size = 1;
        size = checkedAdd(size, checkedAdd(4, message.length));
        size = checkedAdd(size, 4);
        size = checkedAdd(size, checkedAdd(4, serverVersion.length));
        size = checkedAdd(size, checkedAdd(4, worldsLength));
        size = checkedAdd(size, checkedAdd(4, checkedMultiply(tileSizes.length, Integer.BYTES)));
        size = checkedAdd(size, checkedAdd(4, channelsLength));
        size = checkedAdd(size, checkedAdd(4, capabilities.length));
        ByteBuffer buffer = ByteBuffer.allocate(size);
        buffer.put((byte) (response.success() ? 1 : 0));
        writeBytes(buffer, message);
        buffer.putInt(response.serverProtocolVersion());
        writeBytes(buffer, serverVersion);
        buffer.putInt(worldBytes.size());
        for (byte[] world : worldBytes) {
            writeBytes(buffer, world);
        }
        buffer.putInt(tileSizes.length);
        for (int tileSize : tileSizes) {
            buffer.putInt(tileSize);
        }
        buffer.putInt(channelBytes.size());
        for (Map.Entry<byte[], Integer> channel : channelBytes) {
            writeBytes(buffer, channel.getKey());
            buffer.putInt(channel.getValue());
        }
        writeBytes(buffer, capabilities);
        return checkedPayload(buffer.array());
    }

    public ReSyncHandshakeResponse decodeResponse(byte[] payload) {
        ByteBuffer buffer = checkedInput(payload);
        int success = readUnsignedByte(buffer, "success flag");
        if (success > 1) {
            throw malformed("Handshake success flag is invalid");
        }
        String message = readString(buffer, "message");
        int serverProtocolVersion = readInt(buffer, "server protocol version");
        String serverVersion = readString(buffer, "server version");
        int worldCount = readCount(buffer, "worlds");
        requireMinimumEntries(buffer, worldCount, Integer.BYTES, "worlds");
        List<String> worlds = new ArrayList<>(worldCount);
        for (int index = 0; index < worldCount; index++) {
            worlds.add(readString(buffer, "world"));
        }
        int tileSizeCount = readCount(buffer, "tile sizes");
        requireMinimumEntries(buffer, tileSizeCount, Integer.BYTES, "tile sizes");
        int[] tileSizes = new int[tileSizeCount];
        Set<Integer> tileSizeValues = new HashSet<>();
        for (int index = 0; index < tileSizeCount; index++) {
            tileSizes[index] = buffer.getInt();
            if (tileSizes[index] <= 0 || !tileSizeValues.add(tileSizes[index])) {
                throw malformed("Handshake tile size is invalid");
            }
        }
        Map<String, Integer> channels = new LinkedHashMap<>();
        Set<Integer> channelIds = new HashSet<>();
        if (buffer.hasRemaining()) {
            int channelCount = readCount(buffer, "channels");
            requireMinimumEntries(buffer, channelCount, Integer.BYTES * 2, "channels");
            for (int index = 0; index < channelCount; index++) {
                String channel = readString(buffer, "channel");
                if (channel.isBlank()) {
                    throw malformed("Handshake channel name is required");
                }
                int channelId = readInt(buffer, "channel identifier");
                if (channelId < 0 || channelId > ReSyncProtocolContract.MAX_CHANNEL_ID) {
                    throw malformed("Handshake channel identifier is invalid");
                }
                if (!channelIds.add(channelId)) {
                    throw malformed("Handshake contains a duplicate channel identifier");
                }
                if (channels.put(channel, channelId) != null) {
                    throw malformed("Handshake contains a duplicate channel");
                }
            }
        }
        String capabilities = buffer.hasRemaining() ? readString(buffer, "capabilities") : "";
        requireConsumed(buffer, "handshake response");
        return new ReSyncHandshakeResponse(success == 1, message, serverProtocolVersion, serverVersion, worlds,
            tileSizes, channels, capabilities);
    }

    public int maximumPayloadBytes() {
        return maximumPayloadBytes;
    }

    public int maximumFieldBytes() {
        return maximumFieldBytes;
    }

    public int maximumCollectionEntries() {
        return maximumCollectionEntries;
    }

    private ByteBuffer checkedInput(byte[] payload) {
        if (payload == null) {
            throw malformed("Handshake payload is required");
        }
        if (payload.length > maximumPayloadBytes) {
            throw new ReSyncProtocolException(ReSyncProtocolException.Reason.PAYLOAD_TOO_LARGE,
                "Handshake payload exceeds the configured limit");
        }
        return ByteBuffer.wrap(Arrays.copyOf(payload, payload.length));
    }

    private byte[] checkedPayload(byte[] payload) {
        if (payload.length > maximumPayloadBytes) {
            throw new ReSyncProtocolException(ReSyncProtocolException.Reason.PAYLOAD_TOO_LARGE,
                "Handshake payload exceeds the configured limit");
        }
        return payload;
    }

    private byte[] fieldBytes(String value, String field) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maximumFieldBytes) {
            throw new ReSyncProtocolException(ReSyncProtocolException.Reason.HANDSHAKE_FIELD_TOO_LARGE,
                "Handshake " + field + " exceeds the configured limit");
        }
        return bytes;
    }

    private String readString(ByteBuffer buffer, String field) {
        int length = readInt(buffer, field + " length");
        if (length < 0) {
            throw malformed("Handshake " + field + " length is negative");
        }
        if (length > maximumFieldBytes) {
            throw new ReSyncProtocolException(ReSyncProtocolException.Reason.HANDSHAKE_FIELD_TOO_LARGE,
                "Handshake " + field + " exceeds the configured limit");
        }
        if (length > buffer.remaining()) {
            throw malformed("Handshake " + field + " ends early");
        }
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private int readCount(ByteBuffer buffer, String field) {
        int count = readInt(buffer, field + " count");
        if (count < 0) {
            throw malformed("Handshake " + field + " count is negative");
        }
        if (count > maximumCollectionEntries) {
            throw collectionTooLarge(field);
        }
        return count;
    }

    private int readInt(ByteBuffer buffer, String field) {
        if (buffer.remaining() < Integer.BYTES) {
            throw malformed("Handshake " + field + " is missing");
        }
        return buffer.getInt();
    }

    private int readUnsignedByte(ByteBuffer buffer, String field) {
        if (!buffer.hasRemaining()) {
            throw malformed("Handshake " + field + " is missing");
        }
        return Byte.toUnsignedInt(buffer.get());
    }

    private void writeBytes(ByteBuffer buffer, byte[] bytes) {
        buffer.putInt(bytes.length);
        buffer.put(bytes);
    }

    private int checkedAdd(int first, int second) {
        long result = (long) first + second;
        if (result > Integer.MAX_VALUE || result > maximumPayloadBytes) {
            throw new ReSyncProtocolException(ReSyncProtocolException.Reason.PAYLOAD_TOO_LARGE,
                "Handshake payload exceeds the configured limit");
        }
        return (int) result;
    }

    private int checkedMultiply(int first, int second) {
        long result = (long) first * second;
        if (result > Integer.MAX_VALUE || result > maximumPayloadBytes) {
            throw new ReSyncProtocolException(ReSyncProtocolException.Reason.PAYLOAD_TOO_LARGE,
                "Handshake payload exceeds the configured limit");
        }
        return (int) result;
    }

    private void requireMinimumEntries(ByteBuffer buffer, int count, int minimumBytes, String field) {
        if ((long) count * minimumBytes > buffer.remaining()) {
            throw malformed("Handshake " + field + " ends early");
        }
    }

    private void requireConsumed(ByteBuffer buffer, String field) {
        if (buffer.hasRemaining()) {
            throw malformed("Handshake " + field + " contains trailing bytes");
        }
    }

    private ReSyncProtocolException malformed(String message) {
        return new ReSyncProtocolException(ReSyncProtocolException.Reason.MALFORMED_HANDSHAKE, message);
    }

    private ReSyncProtocolException collectionTooLarge(String field) {
        return new ReSyncProtocolException(ReSyncProtocolException.Reason.HANDSHAKE_COLLECTION_TOO_LARGE,
            "Handshake " + field + " exceeds the configured limit");
    }
}
