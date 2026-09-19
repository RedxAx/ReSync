package restudio.resync.metadata;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServerSettingsBundleContractTest {
    private static final ServerSettingsBundleCodec CODEC = new ServerSettingsBundleCodec();

    @Test
    void writesExactCanonicalBundleAndPreservesYamlBytes() {
        ServerSettingsBundle.Source source = ServerSettingsBundle.Source.yaml("paper-global", 1,
                "providerId: paper\npacks: []");
        ServerSettingsBundle bundle = new ServerSettingsBundle("2026-09-19T13:00:00Z", List.of(source));
        String expected = "{\"artifactFamily\":\"server_settings\",\"createdAt\":\"2026-09-19T13:00:00Z\","
                + "\"formatVersion\":1,\"sources\":[{\"content\":\"providerId: paper\\npacks: []\\n\","
                + "\"contentSha256\":\"db9f84acb47db049ee86238b2ec163b1b09c6a503e4d5f78b49a7e2da3d543a1\","
                + "\"id\":\"paper-global\",\"mediaType\":\"application/yaml\",\"schemaVersion\":1}]}";

        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), CODEC.encodeBytes(bundle));
        assertEquals(bundle, CODEC.decodeBytes(expected.getBytes(StandardCharsets.UTF_8)));
        assertEquals("providerId: paper\npacks: []\n", bundle.sources().getFirst().content());
    }

    @Test
    void sortsAndFreezesSources() {
        List<ServerSettingsBundle.Source> sources = new ArrayList<>(List.of(
                ServerSettingsBundle.Source.yaml("velocity", 1, "providerId: velocity\n"),
                ServerSettingsBundle.Source.yaml("bukkit", 1, "providerId: bukkit\n")));
        ServerSettingsBundle bundle = new ServerSettingsBundle("created", sources);
        sources.clear();

        assertEquals(List.of("bukkit", "velocity"), bundle.sources().stream().map(ServerSettingsBundle.Source::id).toList());
        assertThrows(UnsupportedOperationException.class, () -> bundle.sources().clear());
    }

    @Test
    void rejectsTamperingDuplicatesAndNoncanonicalInput() {
        ServerSettingsBundle.Source source = ServerSettingsBundle.Source.yaml("paper", 1, "providerId: paper\n");
        assertThrows(IllegalArgumentException.class, () -> new ServerSettingsBundle.Source(source.id(), 1,
                source.mediaType(), "0".repeat(64), source.content()));
        assertThrows(IllegalArgumentException.class, () -> new ServerSettingsBundle("created", List.of(source, source)));
        assertThrows(IllegalArgumentException.class, () -> new ServerSettingsBundle("created", List.of()));
        assertThrows(IllegalArgumentException.class, () -> ServerSettingsBundle.Source.yaml("paper", 0, "value\n"));

        String canonical = CODEC.encodeText(new ServerSettingsBundle("created", List.of(source)));
        assertThrows(IllegalArgumentException.class, () -> CODEC.decodeText(canonical + "\n"));
        assertThrows(IllegalArgumentException.class, () -> CODEC.decodeText(canonical.replace("\"sources\":[", "\"unknown\":true,\"sources\":[")));
    }
}
