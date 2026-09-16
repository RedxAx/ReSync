package restudio.resync.storage;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class AssetProjectMetadataTest {
    @Test
    void canonicalHashIgnoresObjectOrderAndNumberSpelling() {
        AssetProjectMetadata first = AssetProjectMetadata.parse("{\"unknown\":{\"b\":1.00,\"a\":true},\"resources\":[]}");
        AssetProjectMetadata second = AssetProjectMetadata.parse("{\"resources\":[],\"unknown\":{\"a\":true,\"b\":1}}");

        assertEquals(first.canonicalHash(), second.canonicalHash());
        assertEquals(first.canonicalJson(), second.canonicalJson());
    }

    @Test
    void targetedDeltasPreserveUnknownFieldsAndNoOpsPreserveIdentity() {
        AssetProjectMetadata metadata = AssetProjectMetadata.parse("{\"serverId\":\"project\",\"unknown\":{\"kept\":7},\"folders\":[]}");
        AssetProjectMetadata changed = metadata.apply(List.of(AssetProjectMetadata.Delta.set(
            List.of("folders"), JsonParser.parseString("[{\"path\":\"Blueprints\",\"collapsed\":true}]"))));
        AssetProjectMetadata unchanged = changed.apply(List.of(AssetProjectMetadata.Delta.remove(List.of("missing"))));

        assertNotEquals(metadata.hash(), changed.hash());
        assertEquals(7, changed.document().getAsJsonObject("unknown").get("kept").getAsInt());
        assertFalse(changed.document().getAsJsonArray("folders").isEmpty());
        assertSame(changed, unchanged);
    }
}
