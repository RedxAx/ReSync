package restudio.resync.network;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NetworkResourceCodecTest {
    @Test
    void roundTripsResourceMutationsResourcesAndPages() {
        byte[] payload = "{\"id\":\"welcome\"}".getBytes(StandardCharsets.UTF_8);
        NetworkResourceMutation mutation = NetworkResourceMutation.save("function", "welcome", 4, payload);
        NetworkResource resource = new NetworkResource("network", "function", "welcome", 5, NetworkPayloads.sha256(payload), payload, false, "lobby", 1_000);
        NetworkResourcePage page = new NetworkResourcePage(List.of(resource.metadata()), "function", "welcome");

        assertEquals(mutation, NetworkResourceCodec.decodeMutation(NetworkResourceCodec.encodeMutation(mutation)));
        assertEquals(resource, NetworkResourceCodec.decodeResource(NetworkResourceCodec.encodeResource(resource)));
        assertEquals(page, NetworkResourceCodec.decodePage(NetworkResourceCodec.encodePage(page)));
        assertEquals(new NetworkResourceKey("function", "welcome"), NetworkResourceCodec.decodeKey(NetworkResourceCodec.encodeKey(new NetworkResourceKey("function", "welcome"))));
        assertEquals(new NetworkResourceQuery("function", "welcome", 64), NetworkResourceCodec.decodeQuery(NetworkResourceCodec.encodeQuery(new NetworkResourceQuery("function", "welcome", 64))));
    }
}
