package restudio.resync.protocol.messages;

import com.google.gson.Gson;
import restudio.resync.protocol.MessageType;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ChannelRegistryMessage extends Message {
    public static final int REGISTRY_VERSION = 1;
    private static final Gson GSON = new Gson();
    private int registryVersion = REGISTRY_VERSION;
    private boolean snapshot;
    private Map<String, Integer> channels = new LinkedHashMap<>();
    private List<String> removedChannels = List.of();

    @Override
    public MessageType getType() {
        return MessageType.CHANNEL_REGISTRY;
    }

    @Override
    public byte[] serialize() {
        byte[] json = GSON.toJson(this).getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(json.length);
        buffer.put(json);
        return buffer.array();
    }

    @Override
    public void deserialize(ByteBuffer buffer) {
        byte[] jsonBytes = new byte[buffer.remaining()];
        buffer.get(jsonBytes);
        ChannelRegistryMessage message = GSON.fromJson(new String(jsonBytes, StandardCharsets.UTF_8), ChannelRegistryMessage.class);
        if (message == null) {
            return;
        }
        registryVersion = message.registryVersion;
        snapshot = message.snapshot;
        channels = message.channels == null ? new LinkedHashMap<>() : new LinkedHashMap<>(message.channels);
        removedChannels = message.removedChannels == null ? List.of() : List.copyOf(message.removedChannels);
    }

    public int getRegistryVersion() {
        return registryVersion;
    }

    public void setRegistryVersion(int registryVersion) {
        this.registryVersion = registryVersion;
    }

    public boolean isSnapshot() {
        return snapshot;
    }

    public void setSnapshot(boolean snapshot) {
        this.snapshot = snapshot;
    }

    public Map<String, Integer> getChannels() {
        return channels;
    }

    public void setChannels(Map<String, Integer> channels) {
        this.channels = channels == null ? new LinkedHashMap<>() : new LinkedHashMap<>(channels);
    }

    public List<String> getRemovedChannels() {
        return removedChannels;
    }

    public void setRemovedChannels(List<String> removedChannels) {
        this.removedChannels = removedChannels == null ? List.of() : List.copyOf(removedChannels);
    }
}
