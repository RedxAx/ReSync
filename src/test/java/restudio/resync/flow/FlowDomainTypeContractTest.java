package restudio.resync.flow;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowTypeRef;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowDomainTypeContractTest {
    private static final List<String> REQUIRED_DOMAIN_TYPES = List.of(
        "permission", "permission_group", "permission_track", "permission_context",
        "instant", "duration",
        "component", "named_text_color", "rgb_color", "text_decoration", "formatting_policy",
        "item", "item_definition", "recipe_ingredient_definition", "recipe_definition", "recipe_condition",
        "gui_definition", "gui_session", "gui_element", "gui_event",
        "dialog_definition", "dialog_result", "dialog_event",
        "scoreboard_definition", "sidebar_session", "scoreboard_line", "display_slot",
        "tab_definition", "tab_application",
        "npc_definition", "npc_handle", "npc_event",
        "trade_profile", "trade_definition", "merchant", "trade_session",
        "loot_table_definition", "loot_context", "generated_loot",
        "advancement", "advancement_criterion", "advancement_tree_definition", "advancement_progress",
        "custom_content_definition", "placed_content",
        "item_attribute", "item_component", "item_modifier", "schema_value",
        "world", "location", "region", "structure", "worldgen_project", "worldgen_job", "scheduled_task",
        "player", "player_identity", "offline_player_dossier", "entity", "tracked_player_state",
        "network_node", "network_route", "network_scope", "network_variable", "network_snapshot", "network_transfer_result", "http_response"
    );

    @Test
    void requiredDomainTypesResolveWithStableNamespacedIdentity() {
        for (String typeId : REQUIRED_DOMAIN_TYPES) {
            FlowDataType type = FlowDataType.fromString(typeId);
            assertTrue(type.isResolved(), typeId);
            assertEquals("resync:" + typeId, type.getCanonicalId(), typeId);
            assertNotNull(type.getJavaType(), typeId);
        }
    }

    @Test
    void requiredDomainTypesThatCrossBoundariesHaveCodecs() {
        FlowValueCodecRegistry codecs = new FlowValueCodecRegistry();
        for (String typeId : REQUIRED_DOMAIN_TYPES) {
            FlowDataType type = FlowDataType.fromString(typeId);
            if (type == FlowDataType.PLAYER || type == FlowDataType.ENTITY || type == FlowDataType.WORLD || type == FlowDataType.LOCATION
                || type == FlowDataType.ADVANCEMENT || type == FlowDataType.ITEM) {
                continue;
            }
            assertTrue(codecs.hasCodec(FlowTypeRef.simple(typeId)), typeId);
        }
    }

    @Test
    void compatibilityAliasesDoNotDuplicateAdvertisedDescriptors() {
        List<String> advertisedIds = FlowDataType.values().stream().map(FlowDataType::getId).toList();
        Set<String> uniqueIds = advertisedIds.stream().collect(Collectors.toSet());

        assertEquals(uniqueIds.size(), advertisedIds.size());
        assertEquals(FlowDataType.ITEM, FlowDataType.fromString("itemstack"));
    }
}
