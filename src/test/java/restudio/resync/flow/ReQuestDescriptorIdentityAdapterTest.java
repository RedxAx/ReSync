package restudio.resync.flow;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowGraph;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.registry.NodeDefinition;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReQuestDescriptorIdentityAdapterTest {
    private static final FunctionParameterId TARGET = FunctionParameterId.of(
        UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
    private static final FunctionParameterId MESSAGE = FunctionParameterId.of(
        UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"));

    @Test
    void authenticatedV2FunctionResolvesItsCanonicalDescriptorAndLegacyWireAlias() {
        FlowGraph graph = function();
        NodeDefinition legacy = CustomFunctionNodeDefinitions.buildDefinition(graph);
        ReQuestDescriptorIdentityAdapter.Descriptor descriptor = ReQuestDescriptorIdentityAdapter.descriptor(graph);
        NodeDefinition adapted = ReQuestDescriptorIdentityAdapter.adapt(legacy);

        assertEquals("requestmessage", descriptor.localId());
        assertEquals("server:requestmessage", descriptor.wireId());
        assertEquals("server/function/reQuestMessage", descriptor.functionResourceId());
        assertEquals("custom_function:reQuestMessage", adapted.getId());
        assertEquals("requestmessage", adapted.getCanonicalId());
        assertEquals(List.of("custom_function:reQuestMessage"), adapted.getLegacyIds());
        assertEquals(legacy.getOwner(), adapted.getOwner());
        assertEquals(legacy.getSchemaVersion(), adapted.getSchemaVersion());
        assertEquals(legacy.getHandler(), adapted.getHandler());
        assertEquals(legacy.getHandlerConfig(), adapted.getHandlerConfig());
        assertEquals(legacy.getInputs().stream().map(NodeDefinition.PinDefinition::getName).toList(),
            adapted.getInputs().stream().map(NodeDefinition.PinDefinition::getName).toList());
        assertEquals(legacy.getOutputs().stream().map(NodeDefinition.PinDefinition::getName).toList(),
            adapted.getOutputs().stream().map(NodeDefinition.PinDefinition::getName).toList());
        assertEquals("server:requestmessage", descriptor.resolve("custom_function:reQuestMessage"));
        assertEquals("server:requestmessage", descriptor.resolve("server:requestmessage"));
    }

    @Test
    void legacyAliasesRejectBareOrAmbiguousFunctionIds() {
        ReQuestDescriptorIdentityAdapter.Descriptor descriptor = ReQuestDescriptorIdentityAdapter.descriptor();

        assertThrows(IllegalArgumentException.class, () -> descriptor.resolve("reQuestMessage"));
        assertThrows(IllegalArgumentException.class, () -> new ReQuestDescriptorIdentityAdapter.AliasMap(List.of(
            new ReQuestDescriptorIdentityAdapter.Alias("custom_function:reQuestMessage", "server:requestmessage"),
            new ReQuestDescriptorIdentityAdapter.Alias("custom_function:reQuestMessage", "server:other"))));
        assertThrows(IllegalArgumentException.class, () -> new ReQuestDescriptorIdentityAdapter.AliasMap(List.of(
            new ReQuestDescriptorIdentityAdapter.Alias("custom_function:reQuestMessage", "server:requestmessage"),
            new ReQuestDescriptorIdentityAdapter.Alias("custom_function:reQuestMessage", "server:requestmessage"))));
    }

    @Test
    void nonAuthenticatedFunctionDoesNotAcquireTheReQuestCanonicalIdentity() {
        FlowGraph graph = function();
        graph.setFunctionVersion(1);

        NodeDefinition definition = CustomFunctionNodeDefinitions.buildDefinition(graph);

        assertEquals("custom_function:reQuestMessage", definition.getId());
        assertNull(definition.getCanonicalId());
        assertEquals(List.of(), definition.getLegacyIds());
    }

    @Test
    void pinTypeDriftRejectsTheCanonicalIdentity() {
        FlowGraph graph = function();
        graph.getFunctionInputs().get(0).setType(FlowDataType.STRING);

        assertThrows(IllegalArgumentException.class, () -> ReQuestDescriptorIdentityAdapter.descriptor(graph));
        assertEquals("custom_function:reQuestMessage", CustomFunctionNodeDefinitions.buildDefinition(graph).getId());
    }

    @Test
    void authenticatedIdentitySurvivesParameterReorderAndDisplayRename() {
        FlowGraph graph = function();
        graph.setFunctionInputs(List.of(
            new FlowGraph.FunctionParameter(MESSAGE, "Body", FlowDataType.STRING),
            new FlowGraph.FunctionParameter(TARGET, "Recipient", FlowDataType.PLAYER)));

        assertTrue(ReQuestDescriptorIdentityAdapter.isAuthenticatedFunction(graph));
        NodeDefinition definition = CustomFunctionNodeDefinitions.buildDefinition(graph);
        assertEquals("requestmessage", definition.getCanonicalId());
        NodeDefinition.PinDefinition message = definition.getInputs().stream()
            .filter(pin -> ("function-input-" + MESSAGE.canonicalText()).equals(pin.getId().value()))
            .findFirst().orElseThrow();
        assertEquals("Body", message.getDisplayName());
    }

    private FlowGraph function() {
        FlowGraph graph = new FlowGraph();
        graph.setId("reQuestMessage");
        graph.setFunction(true);
        graph.setFunctionOwner("server");
        graph.setFunctionNamespace("local");
        graph.setFunctionVersion(2);
        graph.setFunctionInputs(List.of(
            new FlowGraph.FunctionParameter(TARGET, "target", FlowDataType.PLAYER),
            new FlowGraph.FunctionParameter(MESSAGE, "message", FlowDataType.STRING)));
        graph.setFunctionOutputs(List.of());
        return graph;
    }
}
