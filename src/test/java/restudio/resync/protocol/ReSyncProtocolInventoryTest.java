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
}
