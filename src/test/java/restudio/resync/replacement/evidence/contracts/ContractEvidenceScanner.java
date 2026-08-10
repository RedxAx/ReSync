package restudio.resync.replacement.evidence.contracts;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public final class ContractEvidenceScanner {
    private ContractEvidenceScanner() {
    }

    public static Inventory scan(Path workspace) throws IOException {
        Path protocol = workspace.resolve("Remotely/contracts/resync-protocol.json");
        JsonObject root = JsonParser.parseString(Files.readString(protocol)).getAsJsonObject();
        List<PacketFamily> packets = new ArrayList<>();
        for (JsonElement value : root.getAsJsonArray("resources")) {
            JsonObject resource = value.getAsJsonObject();
            if (!resource.has("flowPackets")) {
                continue;
            }
            JsonObject values = resource.getAsJsonObject("flowPackets");
            packets.add(new PacketFamily(resource.get("typeId").getAsString(), values.get("request").getAsInt(), values.get("listRequest").getAsInt(),
                values.get("data").getAsInt(), values.get("list").getAsInt(), values.get("save").getAsInt(), values.get("delete").getAsInt(), values.get("saveAck").getAsInt()));
        }
        List<Mirror> mirrors = List.of(
            mirror(workspace, "ReSync/src/main/java/restudio/resync/flow/registry/NodeDefinition.java", "Remotely/src/main/java/redxax/oxy/remotely/flow/registry/NodeDefinition.java"),
            mirror(workspace, "ReSync/src/main/java/restudio/resync/flow/sync/NodeRegistrySnapshot.java", "Remotely/src/main/java/redxax/oxy/remotely/flow/sync/NodeRegistrySnapshot.java"),
            mirror(workspace, "ReSync/src/main/java/restudio/flow/data/FlowGraph.java", "Remotely/src/main/java/redxax/oxy/remotely/flow/data/FlowGraph.java"),
            mirror(workspace, "ReSync/src/main/java/restudio/flow/data/FlowNode.java", "Remotely/src/main/java/redxax/oxy/remotely/flow/data/FlowNode.java"),
            mirror(workspace, "ReSync/src/main/java/restudio/flow/data/FlowConnection.java", "Remotely/src/main/java/redxax/oxy/remotely/flow/data/FlowConnection.java"),
            mirror(workspace, "ReSync/src/main/java/restudio/flow/data/FlowDataType.java", "Remotely/src/main/java/redxax/oxy/remotely/flow/data/FlowDataType.java"),
            mirror(workspace, "ReSync/src/main/java/restudio/flow/data/FlowTypeRef.java", "Remotely/src/main/java/redxax/oxy/remotely/flow/data/FlowTypeRef.java"),
            mirror(workspace, "ReSync/src/main/java/restudio/flow/data/FlowSerializer.java", "Remotely/src/main/java/redxax/oxy/remotely/flow/data/FlowSerializer.java")
        );
        return new Inventory(hash(Files.readString(protocol)), List.copyOf(packets), mirrors);
    }

    private static Mirror mirror(Path workspace, String server, String client) throws IOException {
        return new Mirror(server, client, hash(Files.readString(workspace.resolve(server))), hash(Files.readString(workspace.resolve(client))));
    }

    private static String hash(String source) {
        try {
            byte[] bytes = source.replace("\r\n", "\n").replace('\r', '\n').getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record Inventory(String protocolHash, List<PacketFamily> packetFamilies, List<Mirror> mirrors) {
    }

    public record PacketFamily(String type, int request, int listRequest, int data, int list, int save, int delete, int saveAck) {
    }

    public record Mirror(String serverPath, String clientPath, String serverHash, String clientHash) {
    }
}
