package restudio.resync.network;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NetworkVariableCodecTest {
    @Test
    void roundTripsQueriesMutationsAndValues() {
        NetworkVariableQuery query = new NetworkVariableQuery(NetworkVariableScope.PLAYER, "player-one", "queue.preference");
        NetworkVariableMutation mutation = new NetworkVariableMutation(NetworkVariableScope.PLAYER, "player-one", "queue.preference", NetworkVariableType.STRING, NetworkVariableValues.textValue("survival"), 12, 10000);
        NetworkVariable variable = new NetworkVariable("network-one", NetworkVariableScope.PLAYER, "player-one", "queue.preference", NetworkVariableType.STRING, NetworkVariableValues.textValue("survival"), 13, 10000, "lobby-one", 5000);

        assertEquals(query, NetworkVariableCodec.decodeQuery(NetworkVariableCodec.encodeQuery(query)));
        assertEquals(mutation, NetworkVariableCodec.decodeMutation(NetworkVariableCodec.encodeMutation(mutation)));
        assertEquals(variable, NetworkVariableCodec.decodeVariable(NetworkVariableCodec.encodeVariable(variable)));
    }

    @Test
    void rejectsMissingScopedIdentityAndTrailingData() {
        assertThrows(IllegalArgumentException.class, () -> new NetworkVariableQuery(NetworkVariableScope.SERVER, "", "state"));
        byte[] encoded = NetworkVariableCodec.encodeQuery(new NetworkVariableQuery(NetworkVariableScope.NETWORK, "", "state"));
        assertThrows(IllegalArgumentException.class, () -> NetworkVariableCodec.decodeQuery(Arrays.copyOf(encoded, encoded.length + 1)));
    }
}
