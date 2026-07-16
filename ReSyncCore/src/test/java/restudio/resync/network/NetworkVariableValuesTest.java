package restudio.resync.network;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkVariableValuesTest {
    @Test
    void preservesTypedValues() {
        UUID uuid = UUID.randomUUID();

        assertTrue(NetworkVariableValues.asBoolean(variable(NetworkVariableType.BOOLEAN, NetworkVariableValues.booleanValue(true))));
        assertEquals(42, NetworkVariableValues.asInteger(variable(NetworkVariableType.INTEGER, NetworkVariableValues.integerValue(42))));
        assertEquals(12.5, NetworkVariableValues.asDecimal(variable(NetworkVariableType.DECIMAL, NetworkVariableValues.decimalValue(12.5))));
        assertEquals("Network", NetworkVariableValues.asText(variable(NetworkVariableType.STRING, NetworkVariableValues.textValue("Network"))));
        assertEquals(uuid, NetworkVariableValues.asUuid(variable(NetworkVariableType.UUID, NetworkVariableValues.uuidValue(uuid))));
    }

    private NetworkVariable variable(NetworkVariableType type, byte[] value) {
        return new NetworkVariable("network", NetworkVariableScope.NETWORK, "", "value", type, value, 1, 0, "proxy", 1);
    }
}
