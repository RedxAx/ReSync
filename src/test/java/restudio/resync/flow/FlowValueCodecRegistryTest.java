package restudio.resync.flow;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowJobReference;
import restudio.flow.data.FlowNpcHandle;
import restudio.flow.data.FlowOperationResult;
import restudio.flow.data.FlowPermission;
import restudio.flow.data.FlowTypeRef;
import restudio.flow.data.GuiDefinition;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowValueCodecRegistryTest {
    @Test
    void temporalValuesPreserveExactEpochAndDurationMilliseconds() {
        FlowValueCodecRegistry codecs = new FlowValueCodecRegistry();
        long instant = 8_640_000_000_000_001L;
        long duration = -604_800_001L;

        Object decodedInstant = codecs.decode(FlowTypeRef.simple("instant"), codecs.encode(FlowTypeRef.simple("instant"), instant));
        Object decodedDuration = codecs.decode(FlowTypeRef.simple("duration"), codecs.encode(FlowTypeRef.simple("duration"), duration));

        assertEquals(instant, decodedInstant);
        assertEquals(duration, decodedDuration);
    }

    @Test
    void typedCollectionsRoundTripWithoutErasingElements() {
        FlowValueCodecRegistry codecs = new FlowValueCodecRegistry();
        FlowTypeRef type = FlowTypeRef.parse("map<string,list<permission>>");
        Map<String, List<FlowPermission>> value = Map.of("staff", List.of(new FlowPermission("resync.admin")));

        Object encoded = codecs.encode(type, value);
        Object decoded = codecs.decode(type, encoded);

        Map<?, ?> map = assertInstanceOf(Map.class, decoded);
        List<?> permissions = assertInstanceOf(List.class, map.get("staff"));
        assertEquals(new FlowPermission("resync.admin"), permissions.getFirst());
        assertTrue(codecs.hasCodec(type));
    }

    @Test
    void structuredResultsPreserveFailuresAndTypedValues() {
        FlowValueCodecRegistry codecs = new FlowValueCodecRegistry();
        FlowTypeRef type = FlowTypeRef.parse("result<integer>");
        FlowOperationResult<Integer> failure = FlowOperationResult.failure("DENIED", "Permission Denied", Map.of("permission", "resync.admin"));

        FlowOperationResult<?> decoded = assertInstanceOf(FlowOperationResult.class, codecs.decode(type, codecs.encode(type, failure)));

        assertFalse(decoded.success());
        assertEquals("DENIED", decoded.errorCode());
        assertEquals("resync.admin", decoded.details().get("permission"));
        assertTrue(codecs.hasCodec(type));
    }

    @Test
    void managedResourceDefinitionsRetainTheirSemanticTypes() {
        FlowValueCodecRegistry codecs = new FlowValueCodecRegistry();
        GuiDefinition gui = new GuiDefinition("main", "Main", 3);
        JsonObject trade = new JsonObject();
        trade.addProperty("result", "minecraft:stone");

        GuiDefinition decodedGui = assertInstanceOf(GuiDefinition.class,
            codecs.decode(FlowTypeRef.simple("gui_definition"), codecs.encode(FlowTypeRef.simple("gui_definition"), gui)));
        JsonObject decodedTrade = assertInstanceOf(JsonObject.class,
            codecs.decode(FlowTypeRef.simple("trade_definition"), codecs.encode(FlowTypeRef.simple("trade_definition"), trade)));

        assertEquals("main", decodedGui.getId());
        assertEquals("minecraft:stone", decodedTrade.get("result").getAsString());
        assertTrue(codecs.hasCodec(FlowTypeRef.simple("gui_definition")));
        assertTrue(codecs.hasCodec(FlowTypeRef.simple("trade_definition")));
    }

    @Test
    void jobReferencesPreserveObservableStateAcrossTheBoundary() {
        FlowValueCodecRegistry codecs = new FlowValueCodecRegistry();
        FlowJobReference<String> job = new FlowJobReference<>("worldgen-17", "worldgen_compile", "flow:terrain_release");
        job.start();
        job.updateProgress(0.65, Map.of("stage", "noise"));
        job.fail("COMPILE_FAILED", "Noise graph is invalid", Map.of("node", "ridge"));

        FlowJobReference<?> decoded = assertInstanceOf(FlowJobReference.class,
            codecs.decode(FlowTypeRef.simple("job_reference"), codecs.encode(FlowTypeRef.simple("job_reference"), job)));

        assertEquals("worldgen-17", decoded.getId());
        assertEquals("worldgen_compile", decoded.getKind());
        assertEquals("flow:terrain_release", decoded.getOwner());
        assertEquals(FlowJobReference.State.FAILED, decoded.getState());
        assertEquals(0.65, decoded.getProgress());
        assertEquals("noise", decoded.getMetadata().get("stage"));
        assertEquals("COMPILE_FAILED", decoded.getCompletion().join().errorCode());
        assertTrue(codecs.hasCodec(FlowTypeRef.simple("job_reference")));
    }

    @Test
    void npcHandlesPreserveRuntimeIdentityWithoutPretendingToBeEntities() {
        FlowValueCodecRegistry codecs = new FlowValueCodecRegistry();
        FlowNpcHandle handle = new FlowNpcHandle("guide", "", true, true, "world", 12.5, 64, -8.25, 90, 15);

        FlowNpcHandle decoded = assertInstanceOf(FlowNpcHandle.class,
            codecs.decode(FlowTypeRef.simple("npc_handle"), codecs.encode(FlowTypeRef.simple("npc_handle"), handle)));

        assertEquals(handle, decoded);
        assertTrue(decoded.packetBacked());
        assertTrue(codecs.hasCodec(FlowTypeRef.simple("npc_handle")));
    }

    @Test
    void classifiedEntityAndItemStructuresRoundTripWithoutBecomingAny() {
        FlowValueCodecRegistry codecs = new FlowValueCodecRegistry();
        Map<String, Object> entityData = Map.of("fuse_ticks", 40, "incendiary", false);
        List<Object> modifiers = List.of(Map.of("type", "minecraft:attack_damage", "amount", 3.0, "operation", "add_value"));

        Map<?, ?> decodedEntityData = assertInstanceOf(Map.class,
            codecs.decode(FlowTypeRef.simple("entity_data"), codecs.encode(FlowTypeRef.simple("entity_data"), entityData)));
        List<?> decodedModifiers = assertInstanceOf(List.class,
            codecs.decode(FlowTypeRef.simple("item_component_list"), codecs.encode(FlowTypeRef.simple("item_component_list"), modifiers)));

        assertEquals(40, decodedEntityData.get("fuse_ticks"));
        assertEquals("minecraft:attack_damage", assertInstanceOf(Map.class, decodedModifiers.getFirst()).get("type"));
        assertTrue(codecs.hasCodec(FlowTypeRef.simple("entity_data")));
        assertTrue(codecs.hasCodec(FlowTypeRef.simple("item_components")));
        assertTrue(codecs.hasCodec(FlowTypeRef.simple("item_component_list")));
    }
}
