package restudio.resync.replacement.evidence;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import restudio.resync.replacement.evidence.catalog.CatalogEvidenceScanner;
import restudio.resync.replacement.evidence.contracts.ContractEvidenceScanner;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EvidenceScannerTest {
    @Test
    void catalogInventoryIsDeterministicAndDetectsSourceDrift() throws Exception {
        Path root = Path.of("src", "main", "resources", "nodes");
        CatalogEvidenceScanner.Inventory first = CatalogEvidenceScanner.scan(root);
        CatalogEvidenceScanner.Inventory second = CatalogEvidenceScanner.scan(root);
        JsonObject expected = fixture("catalog-inventory.json");
        assertEquals(first, second);
        assertEquals(expected.get("sourceTreeHash").getAsString(), first.sourceTreeHash());
        assertEquals(expected.get("files").getAsInt(), first.files());
        assertEquals(expected.get("definitions").getAsInt(), first.definitions());
        assertEquals(expected.get("physicalPins").getAsInt(), first.physicalPins());
        assertEquals(expected.get("logicalCompatibilityPins").getAsInt(), first.logicalCompatibilityPins());
        assertEquals(expected.get("missingNodeDescriptions").getAsInt(), first.missingNodeDescriptions());
        assertEquals(expected.get("missingPhysicalPinDescriptions").getAsInt(), first.missingPhysicalPinDescriptions());
        assertEquals(expected.get("missingLogicalCompatibilityPinDescriptions").getAsInt(), first.missingLogicalCompatibilityPinDescriptions());
        assertEquals(expected.getAsJsonArray("definitionsWithoutInputs").asList().stream().map(element -> element.getAsString()).toList(), first.missingInputIds());
        assertEquals(expected.getAsJsonArray("definitionsWithoutInputs").size(), first.definitionsWithoutInputs());
    }

    @Test
    void protocolInventoryIsDeterministicAndDetectsSourceDrift() throws Exception {
        Path workspace = Path.of("..").toAbsolutePath().normalize();
        ContractEvidenceScanner.Inventory first = ContractEvidenceScanner.scan(workspace);
        ContractEvidenceScanner.Inventory second = ContractEvidenceScanner.scan(workspace);
        JsonObject expected = fixture("contract-protocol-inventory.json");
        assertEquals(first, second);
        assertEquals(expected.get("protocolHash").getAsString(), first.protocolHash());
        assertEquals(expected.get("packetFamilyCount").getAsInt(), first.packetFamilies().size());
        assertEquals(expected.get("mirrorPairCount").getAsInt(), first.mirrors().size());
        for (int index = 0; index < first.packetFamilies().size(); index++) {
            ContractEvidenceScanner.PacketFamily actual = first.packetFamilies().get(index);
            var expectedFamily = expected.getAsJsonArray("packetFamilies").get(index).getAsJsonArray();
            assertEquals(expectedFamily.get(0).getAsString(), actual.type());
            assertEquals(expectedFamily.get(1).getAsInt(), actual.request());
            assertEquals(expectedFamily.get(2).getAsInt(), actual.listRequest());
            assertEquals(expectedFamily.get(3).getAsInt(), actual.data());
            assertEquals(expectedFamily.get(4).getAsInt(), actual.list());
            assertEquals(expectedFamily.get(5).getAsInt(), actual.save());
            assertEquals(expectedFamily.get(6).getAsInt(), actual.delete());
            assertEquals(expectedFamily.get(7).getAsInt(), actual.saveAck());
        }
        for (ContractEvidenceScanner.Mirror mirror : first.mirrors()) {
            assertEquals(expected.getAsJsonObject("mirrors").get(mirror.serverPath()).getAsString(), mirror.serverHash());
            assertEquals(expected.getAsJsonObject("mirrors").get(mirror.clientPath()).getAsString(), mirror.clientHash());
        }
    }

    private JsonObject fixture(String name) throws Exception {
        Path path = Path.of("src", "test", "resources", "fixtures", "node-replacement", "catalog", name);
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }
}
