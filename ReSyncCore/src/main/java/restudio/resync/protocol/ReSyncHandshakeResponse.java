package restudio.resync.protocol;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ReSyncHandshakeResponse(boolean success, String message, int serverProtocolVersion, String serverVersion,
                                      List<String> worlds, int[] supportedTileSizes, Map<String, Integer> channels,
                                      String capabilitiesJson) {
    public ReSyncHandshakeResponse {
        message = message == null ? "" : message;
        serverVersion = serverVersion == null ? "" : serverVersion;
        worlds = worlds == null ? List.of() : List.copyOf(worlds);
        supportedTileSizes = supportedTileSizes == null ? new int[0] : Arrays.copyOf(supportedTileSizes, supportedTileSizes.length);
        channels = channels == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(channels));
        capabilitiesJson = capabilitiesJson == null ? "" : capabilitiesJson;
    }

    @Override
    public int[] supportedTileSizes() {
        return Arrays.copyOf(supportedTileSizes, supportedTileSizes.length);
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof ReSyncHandshakeResponse other
            && success == other.success
            && serverProtocolVersion == other.serverProtocolVersion
            && message.equals(other.message)
            && serverVersion.equals(other.serverVersion)
            && worlds.equals(other.worlds)
            && Arrays.equals(supportedTileSizes, other.supportedTileSizes)
            && channels.equals(other.channels)
            && capabilitiesJson.equals(other.capabilitiesJson);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(success, message, serverProtocolVersion, serverVersion, worlds, channels, capabilitiesJson);
        return 31 * result + Arrays.hashCode(supportedTileSizes);
    }
}
