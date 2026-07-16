package restudio.resync.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class NetworkRouteSetCodec {
    private static final int FORMAT_VERSION = 2;
    private static final int MAXIMUM_ROUTES = 4096;
    private static final int MAXIMUM_GROUPS = 1024;
    private static final int MAXIMUM_STRING_BYTES = 8192;

    private NetworkRouteSetCodec() {
    }

    public static byte[] encode(NetworkRouteSet routeSet) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeShort(FORMAT_VERSION);
            output.writeLong(routeSet.revision());
            writeOptionalString(output, routeSet.maintenanceRoute());
            output.writeShort(routeSet.routes().size());
            for (NetworkRoute route : routeSet.routes()) {
                writeString(output, route.nodeId());
                writeString(output, route.routeName());
                writeString(output, route.address());
                output.writeInt(route.port());
            }
            output.writeShort(routeSet.routingGroups().size());
            for (NetworkRoutingGroup group : routeSet.routingGroups()) {
                writeString(output, group.id());
                writeString(output, group.name());
                writeString(output, group.strategy().name());
                output.writeShort(group.nodeIds().size());
                for (String nodeId : group.nodeIds()) {
                    writeString(output, nodeId);
                }
                output.writeShort(group.weights().size());
                for (Map.Entry<String, Integer> entry : group.weights().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                    writeString(output, entry.getKey());
                    output.writeInt(entry.getValue());
                }
                writeOptionalString(output, group.fallbackGroupId());
                output.writeShort(group.forcedHosts().size());
                for (String host : group.forcedHosts().stream().sorted(Comparator.naturalOrder()).toList()) {
                    writeString(output, host);
                }
                writeOptionalString(output, group.permission());
            }
            output.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Encode Network Routes Failed", exception);
        }
    }

    public static NetworkRouteSet decode(byte[] payload) {
        if (payload == null || payload.length < Short.BYTES + Long.BYTES + Short.BYTES + Short.BYTES) {
            throw new IllegalArgumentException("Network Route Payload Is Invalid");
        }
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload));
            int version = input.readUnsignedShort();
            if (version < 1 || version > FORMAT_VERSION) {
                throw new IllegalArgumentException("Unsupported Network Route Format " + version);
            }
            long revision = input.readLong();
            String maintenanceRoute = readOptionalString(input);
            int count = input.readUnsignedShort();
            if (count > MAXIMUM_ROUTES) {
                throw new IllegalArgumentException("Network Route Set Is Too Large");
            }
            List<NetworkRoute> routes = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                routes.add(new NetworkRoute(readString(input), readString(input), readString(input), input.readInt()));
            }
            List<NetworkRoutingGroup> groups = new ArrayList<>();
            if (version >= 2) {
                int groupCount = input.readUnsignedShort();
                if (groupCount > MAXIMUM_GROUPS) {
                    throw new IllegalArgumentException("Network Routing Group Set Is Too Large");
                }
                for (int index = 0; index < groupCount; index++) {
                    String id = readString(input);
                    String name = readString(input);
                    NetworkRoutingStrategy strategy;
                    try {
                        strategy = NetworkRoutingStrategy.valueOf(readString(input));
                    } catch (IllegalArgumentException exception) {
                        throw new IllegalArgumentException("Network Routing Strategy Is Invalid", exception);
                    }
                    int nodeCount = input.readUnsignedShort();
                    if (nodeCount > MAXIMUM_ROUTES) {
                        throw new IllegalArgumentException("Network Routing Group Is Too Large");
                    }
                    List<String> nodeIds = new ArrayList<>(nodeCount);
                    for (int nodeIndex = 0; nodeIndex < nodeCount; nodeIndex++) {
                        nodeIds.add(readString(input));
                    }
                    int weightCount = input.readUnsignedShort();
                    if (weightCount > MAXIMUM_ROUTES) {
                        throw new IllegalArgumentException("Network Routing Group Is Too Large");
                    }
                    Map<String, Integer> weights = new LinkedHashMap<>();
                    for (int weightIndex = 0; weightIndex < weightCount; weightIndex++) {
                        if (weights.put(readString(input), input.readInt()) != null) {
                            throw new IllegalArgumentException("Network Routing Group Has Duplicate Weights");
                        }
                    }
                    String fallbackGroupId = readOptionalString(input);
                    int hostCount = input.readUnsignedShort();
                    if (hostCount > MAXIMUM_ROUTES) {
                        throw new IllegalArgumentException("Network Routing Group Is Too Large");
                    }
                    Set<String> forcedHosts = new LinkedHashSet<>();
                    for (int hostIndex = 0; hostIndex < hostCount; hostIndex++) {
                        if (!forcedHosts.add(readString(input))) {
                            throw new IllegalArgumentException("Network Routing Group Has Duplicate Forced Hosts");
                        }
                    }
                    groups.add(new NetworkRoutingGroup(id, name, strategy, nodeIds, weights, fallbackGroupId, forcedHosts, readOptionalString(input)));
                }
            }
            if (input.available() != 0) {
                throw new IllegalArgumentException("Network Route Payload Has Trailing Data");
            }
            return new NetworkRouteSet(revision, maintenanceRoute, routes, groups);
        } catch (EOFException exception) {
            throw new IllegalArgumentException("Network Route Payload Ended Early", exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Decode Network Routes Failed", exception);
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAXIMUM_STRING_BYTES) {
            throw new IllegalArgumentException("Network Route Value Is Invalid");
        }
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    private static void writeOptionalString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAXIMUM_STRING_BYTES) {
            throw new IllegalArgumentException("Network Route Value Is Invalid");
        }
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort();
        if (length == 0 || length > MAXIMUM_STRING_BYTES || length > input.available()) {
            throw new IllegalArgumentException("Network Route Value Is Invalid");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Network Route Value Ended Early");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String readOptionalString(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort();
        if (length > MAXIMUM_STRING_BYTES || length > input.available()) {
            throw new IllegalArgumentException("Network Route Value Is Invalid");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Network Route Value Ended Early");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
