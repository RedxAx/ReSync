package restudio.resync.protocol;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncProtocolInventoryTest {
    @Test
    void sharedContractInventoryIsUniqueCompleteAndRequirementMapped() {
        List<Map<String, Object>> inventory = ReSyncProtocolInventory.snapshot();
        List<Object> ids = inventory.stream().map(item -> item.get("id")).toList();

        assertEquals(ids.size(), (int) ids.stream().distinct().count());
        assertTrue(ids.containsAll(List.of("PROTOCOL_VERSION", "FLOW_PACKET_NODE_REGISTRY", "FLOW_PACKET_OPTION_CATALOG", "FLOW_PACKET_FUNCTION_TEST_RESULT")));
        assertTrue(inventory.stream().allMatch(item -> "shared-contract".equals(item.get("owner"))));
        assertTrue(inventory.stream().allMatch(item -> item.get("requirements") instanceof List<?> requirements && requirements.contains("PROTO-001")));
    }

    @Test
    void exposesGenericEnvelopeWireAuthorityAlongsideLegacyMappings() {
        Map<String, Object> envelope = ReSyncProtocolInventory.snapshot().stream()
            .filter(item -> "MESSAGE_PROTOCOL_ENVELOPE".equals(item.get("id")))
            .findFirst()
            .orElseThrow();

        assertEquals("message", envelope.get("kind"));
        assertEquals(9, envelope.get("value"));
        assertEquals("generic-envelope", envelope.get("authority"));
        assertEquals("supported", envelope.get("disposition"));
        assertEquals("server-durable-when-advertised", envelope.get("resourceAuthority"));
        assertEquals(Map.of("generation", ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION.generation(),
            "minor", ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION.minor()), envelope.get("resourceContractVersion"));
        assertEquals(List.of("option_queries", "resource_activation", "resource_create_presentation"),
            envelope.get("resourceCapabilities"));
        assertEquals(Map.of("read", "compatibility-projection", "save", "blocked", "delete", "blocked"),
            envelope.get("legacyResourceRoutes"));
        assertEquals(Map.of("read", List.of("list", "query", "load"),
                "mutate", List.of("create", "save", "delete", "duplicate", "activate"),
                "unsupported", List.of("rename", "move", "subscribe")), envelope.get("resourceOperations"));
        Map<String, Object> legacy = ReSyncProtocolInventory.snapshot().stream()
            .filter(item -> "FLOW_PACKET_REQUEST".equals(item.get("id")))
            .findFirst()
            .orElseThrow();
        assertEquals("packet", legacy.get("kind"));
        assertEquals(1, legacy.get("value"));
    }
}
