package restudio.resync.upgrade.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CommandBindingTransformerTest {
    private final CommandBindingTransformer transformer = new CommandBindingTransformer();

    @Test
    void transformsStructuredBindingAndPreservesUnknownGraphData() {
        RawGraphDocument graph = RawGraphDocument.parse("""
            {
              "id": "name_color",
              "resourceType": "command",
              "unknownRoot": {"nested": [{"opaque": true}]},
              "nodes": {
                "start": {
                  "type": "event.resync.command",
                  "unknownNode": {"deep": {"value": 7}},
                  "inputValues": {"unknownInput": {"opaque": [1, 2, 3]}}
                }
              },
              "connections": []
            }
            """);
        CommandBinding binding = new CommandBinding(
            "name_color:command",
            "name_color",
            "{\"command\":\"name_color\",\"subcommands\":[\"<text:colorMap>\"],\"structured\":false}");

        CommandBindingOutput result = transformer.transform(graph, binding);

        assertTrue(result.changed());
        assertEquals(CommandBindingTransformer.ADAPTER_ID, result.adapterId());
        assertEquals(CommandBindingTransformer.ADAPTER_VERSION, result.adapterVersion());
        assertEquals(1, result.appliedBindings().size());
        assertTrue(result.canonicalText().contains("\"command\":\"name_color\""));
        assertTrue(result.canonicalText().contains("\"unknownRoot\":{"));
        assertTrue(result.canonicalText().contains("\"unknownNode\":{"));
        assertTrue(result.canonicalText().contains("\"unknownInput\":{"));
    }

    @Test
    void acceptsPlainCommandContextAndReplacementInputs() {
        RawGraphDocument graph = RawGraphDocument.parse("""
            {"id":"restart","nodes":{"start":{"type":"event:resync_command","inputs":{"custom":{"preserve":true}}}}}
            """);

        CommandBindingOutput result = transformer.transform(graph, new CommandBinding("restart:command:start", "restart", "restart"));
        String text = result.canonicalText();

        assertTrue(result.changed());
        assertTrue(text.contains("\"inputs\":{"));
        assertTrue(text.contains("\"command\":\"restart\""));
        assertTrue(text.contains("\"subcommands\":[]"));
        assertTrue(text.contains("\"structured\":false"));
        assertTrue(text.contains("\"custom\":{"));
    }

    @Test
    void rejectsMalformedBindings() {
        RawGraphDocument graph = graph("start");
        assertThrows(IllegalArgumentException.class, () -> transformer.transform(graph,
            new CommandBinding("flow:command:start", "flow", "{\"command\":")));
        assertThrows(IllegalArgumentException.class, () -> transformer.transform(graph,
            new CommandBinding("flow:command:start", "flow", "{\"subcommands\":[]}")));
        assertThrows(IllegalArgumentException.class, () -> transformer.transform(graph,
            new CommandBinding("flow:command:start", "flow", "{\"command\":\"x\",\"structured\":\"no\"}")));
        assertThrows(IllegalArgumentException.class, () -> transformer.transform(graph,
            new CommandBinding("flow:command:start", "flow", "{\"command\":\"x\",\"subcommands\":[1]}")));
    }

    @Test
    void rejectsAmbiguousBindingsAndDuplicateIds() {
        RawGraphDocument twoNodes = RawGraphDocument.parse("""
            {"id":"flow","nodes":{
              "first":{"type":"event.resync.command","inputs":{}},
              "second":{"type":"event.resync.command","inputs":{}}
            }}
            """);
        CommandBinding binding = new CommandBinding("flow:command", "flow", "restart");

        IllegalArgumentException ambiguous = assertThrows(IllegalArgumentException.class,
            () -> transformer.transform(twoNodes, binding));
        assertTrue(ambiguous.getMessage().contains("COMMAND_BINDING_AMBIGUOUS"));

        RawGraphDocument oneNode = graph("start");
        CommandBinding duplicate = new CommandBinding("duplicate", "flow", "restart");
        IllegalArgumentException duplicateFailure = assertThrows(IllegalArgumentException.class,
            () -> transformer.transform(new CommandBindingInput(oneNode, List.of(duplicate, duplicate))));
        assertTrue(duplicateFailure.getMessage().contains("COMMAND_BINDING_AMBIGUOUS"));
    }

    @Test
    void isIdempotentAndRejectsConflictingExistingValues() {
        RawGraphDocument graph = graph("start");
        CommandBinding binding = new CommandBinding("flow:command:start", "flow", "{\"command\":\"restart\",\"subcommands\":[],\"structured\":false}");

        CommandBindingOutput first = transformer.transform(graph, binding);
        CommandBindingOutput second = transformer.transform(first.graph(), binding);

        assertTrue(first.changed());
        assertFalse(second.changed());
        assertEquals(first.canonicalText(), second.canonicalText());
        assertEquals(first.graph().contentHash(), second.graph().contentHash());

        RawGraphDocument conflict = RawGraphDocument.parse("""
            {"id":"flow","nodes":{"start":{"type":"event.resync.command","inputs":{"command":"other"}}}}
            """);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> transformer.transform(conflict, binding));
        assertTrue(failure.getMessage().contains("COMMAND_BINDING_CONFLICT"));
    }

    @Test
    void replacesBlankExistingCommandValue() {
        RawGraphDocument graph = RawGraphDocument.parse("""
            {"id":"flow","nodes":{"start":{"type":"event.resync.command","inputs":{"command":"   "}}}}
            """);

        CommandBindingOutput result = transformer.transform(graph,
            new CommandBinding("flow:command:start", "flow", "restart"));

        assertTrue(result.changed());
        assertTrue(result.canonicalText().contains("\"command\":\"restart\""));
    }

    @Test
    void rejectsInvalidAdapterIdentity() {
        RawGraphDocument graph = graph("start");

        assertThrows(IllegalArgumentException.class,
            () -> new CommandBindingOutput(graph, "resync\u0000command", 1, false, List.of()));
    }

    @Test
    void rawGraphDocumentCanonicalizesInputWithoutDroppingNestedUnknownFields() {
        RawGraphDocument graph = RawGraphDocument.parse("{ \"id\": \"flow\", \"nodes\": {\"start\": {\"type\": \"event.resync.command\", \"unknown\": {\"x\": [true, null]}}} }");

        assertEquals("{\"id\":\"flow\",\"nodes\":{\"start\":{\"type\":\"event.resync.command\",\"unknown\":{\"x\":[true,null]}}}}", graph.canonicalText());
        assertEquals(64, graph.contentHash().length());
    }

    @Test
    void rawGraphDocumentUsesGraphLimitForLargeUnknownPayloads() {
        String entry = "\"" + "x".repeat(1_024) + "\"";
        String unknown = "[" + (entry + ",").repeat(1_024) + entry + "]";
        RawGraphDocument graph = RawGraphDocument.parse("{\"id\":\"flow\",\"nodes\":{},\"future\":" + unknown + "}");

        assertTrue(graph.canonicalText().length() > 1_048_576);
        assertEquals(64, graph.contentHash().length());
    }

    private RawGraphDocument graph(String nodeId) {
        return RawGraphDocument.parse("""
            {"id":"flow","nodes":{"%s":{"type":"event.resync.command","inputValues":{}}}}
            """.formatted(nodeId));
    }
}
