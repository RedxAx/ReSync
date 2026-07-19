package restudio.resync.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

public final class NetworkStateReconciliationCodec {
    private static final int MAXIMUM_VALUES = 100_000;
    private static final int MAXIMUM_STRING_BYTES = 256;

    private NetworkStateReconciliationCodec() {
    }

    public static byte[] encodeRequest(NetworkStateReconciliationRequest request) {
        return write(output -> {
            writeString(output, request.transitionId());
            writeStrings(output, request.nodeIds());
            writeStrings(output, request.families());
        });
    }

    public static NetworkStateReconciliationRequest decodeRequest(byte[] payload) {
        return read(payload, input -> new NetworkStateReconciliationRequest(readString(input), readStrings(input), readStrings(input)));
    }

    public static byte[] encodeTask(NetworkStateReconciliationTask task) {
        return write(output -> {
            writeString(output, task.transitionId());
            output.writeInt(task.playerIds().size());
            for (UUID playerId : task.playerIds()) {
                output.writeLong(playerId.getMostSignificantBits());
                output.writeLong(playerId.getLeastSignificantBits());
            }
            writeStrings(output, task.families());
        });
    }

    public static NetworkStateReconciliationTask decodeTask(byte[] payload) {
        return read(payload, input -> {
            String transitionId = readString(input);
            int count = boundedCount(input.readInt());
            Set<UUID> playerIds = new LinkedHashSet<>();
            for (int index = 0; index < count; index++) {
                playerIds.add(new UUID(input.readLong(), input.readLong()));
            }
            return new NetworkStateReconciliationTask(transitionId, playerIds, readStrings(input));
        });
    }

    private static byte[] write(Writer writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                writer.write(output);
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Encode State Reconciliation Failed", exception);
        }
    }

    private static <T> T read(byte[] payload, Reader<T> reader) {
        if (payload == null) {
            throw new IllegalArgumentException("State Reconciliation Payload Is Required");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            T value = reader.read(input);
            if (input.available() != 0) {
                throw new IllegalArgumentException("State Reconciliation Payload Has Trailing Data");
            }
            return value;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Decode State Reconciliation Failed", exception);
        }
    }

    private static void writeStrings(DataOutputStream output, Set<String> values) throws IOException {
        output.writeInt(values.size());
        for (String value : values) {
            writeString(output, value);
        }
    }

    private static Set<String> readStrings(DataInputStream input) throws IOException {
        int count = boundedCount(input.readInt());
        Set<String> values = new LinkedHashSet<>();
        for (int index = 0; index < count; index++) {
            values.add(readString(input));
        }
        return Set.copyOf(values);
    }

    private static int boundedCount(int count) {
        if (count < 0 || count > MAXIMUM_VALUES) {
            throw new IllegalArgumentException("State Reconciliation Value Count Is Invalid");
        }
        return count;
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAXIMUM_STRING_BYTES) {
            throw new IllegalArgumentException("State Reconciliation Value Is Too Long");
        }
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort();
        if (length > MAXIMUM_STRING_BYTES) {
            throw new IllegalArgumentException("State Reconciliation Value Is Too Long");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new IllegalArgumentException("State Reconciliation Payload Ended Early");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private interface Writer {
        void write(DataOutputStream output) throws IOException;
    }

    private interface Reader<T> {
        T read(DataInputStream input) throws IOException;
    }
}
