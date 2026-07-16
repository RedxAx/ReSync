package restudio.resync.network;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

public final class NetworkVariableValues {
    private NetworkVariableValues() {
    }

    public static byte[] booleanValue(boolean value) {
        return new byte[]{(byte) (value ? 1 : 0)};
    }

    public static boolean asBoolean(NetworkVariable variable) {
        requireType(variable, NetworkVariableType.BOOLEAN);
        byte[] value = variable.value();
        if (value.length != 1 || value[0] < 0 || value[0] > 1) {
            throw new IllegalArgumentException("Network Boolean Value Is Invalid");
        }
        return value[0] == 1;
    }

    public static byte[] integerValue(long value) {
        return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }

    public static long asInteger(NetworkVariable variable) {
        requireType(variable, NetworkVariableType.INTEGER);
        byte[] value = requireLength(variable.value(), Long.BYTES, "Integer");
        return ByteBuffer.wrap(value).getLong();
    }

    public static byte[] decimalValue(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Network Decimal Must Be Finite");
        }
        return ByteBuffer.allocate(Double.BYTES).putDouble(value).array();
    }

    public static double asDecimal(NetworkVariable variable) {
        requireType(variable, NetworkVariableType.DECIMAL);
        double value = ByteBuffer.wrap(requireLength(variable.value(), Double.BYTES, "Decimal")).getDouble();
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Network Decimal Value Is Invalid");
        }
        return value;
    }

    public static byte[] textValue(String value) {
        return (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
    }

    public static String asText(NetworkVariable variable) {
        if (variable.type() != NetworkVariableType.STRING && variable.type() != NetworkVariableType.JSON) {
            throw new IllegalArgumentException("Network Variable Is Not Text");
        }
        return new String(variable.value(), StandardCharsets.UTF_8);
    }

    public static byte[] uuidValue(UUID value) {
        if (value == null) {
            throw new IllegalArgumentException("Network UUID Is Required");
        }
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }

    public static UUID asUuid(NetworkVariable variable) {
        requireType(variable, NetworkVariableType.UUID);
        ByteBuffer buffer = ByteBuffer.wrap(requireLength(variable.value(), 16, "UUID"));
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    public static byte[] bytesValue(byte[] value) {
        return value == null ? new byte[0] : Arrays.copyOf(value, value.length);
    }

    private static void requireType(NetworkVariable variable, NetworkVariableType type) {
        if (variable == null || variable.type() != type) {
            throw new IllegalArgumentException("Network Variable Is Not " + type.name());
        }
    }

    private static byte[] requireLength(byte[] value, int length, String type) {
        if (value.length != length) {
            throw new IllegalArgumentException("Network " + type + " Value Is Invalid");
        }
        return value;
    }
}
