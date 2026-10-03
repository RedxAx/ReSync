package restudio.resync.flow.registry;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeDefinitionSourceAdmissionTest {
    @Test
    void malformedSourceDoesNotAbortOtherAuthoredFiles() {
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        List<NodeDefinition> definitions = loader.loadReplacementFromSources(List.of(
            source("bad.json", "{invalid}"), source("valid.json", definition("admitted"))));

        assertEquals(List.of("admitted"), definitions.stream().map(NodeDefinition::getId).toList());
        assertTrue(loader.getDiagnostics().stream().anyMatch(value -> value.code().equals("FILE_PARSE_FAILED")
            && value.source().equals("valid:/bad.json")));
    }

    @Test
    void sourceBytesStayIsolatedFromCallersAndRepeatedLoads() {
        byte[] bytes = definition("isolated").getBytes(StandardCharsets.UTF_8);
        NodeDefinitionLoader.SourceFile source = new NodeDefinitionLoader.SourceFile("isolated.json", "valid:/isolated.json",
            "isolated.json", NodeDefinitionLoader.SourceOrigin.LOCAL, bytes);
        bytes[0] = '!';
        source.rawBytes()[0] = '!';
        source.bytes()[0] = '!';

        NodeDefinition first = new NodeDefinitionLoader().loadReplacementFromSources(List.of(source)).getFirst();
        NodeDefinition second = new NodeDefinitionLoader().loadReplacementFromSources(List.of(source)).getFirst();

        assertEquals("isolated", second.getId());
        assertEquals(first.getAuthoredMetadata().sourceProvenance(), second.getAuthoredMetadata().sourceProvenance());
    }

    @Test
    void integralDefaultsRemainUsableAfterCanonicalNumberNormalization() {
        for (String number : List.of("20", "1200", "-1000")) {
            String text = definition("integer_default").replace("\"outputs\":[]", "\"inputs\":[{\"id\":\"interval_ticks\","
                + "\"name\":\"interval_ticks\",\"displayName\":\"Interval Ticks\",\"pinType\":\"DATA\",\"dataType\":\"integer\","
                + "\"description\":\"The timer period in whole server ticks.\",\"defaultValue\":" + number + "}],\"outputs\":[]");
            NodeDefinitionLoader.SourceFile source = source("integer-default.json", text);
            for (int load = 0; load < 2; load++) {
                NodeDefinition node = new NodeDefinitionLoader().loadReplacementFromSources(List.of(source)).getFirst();
                String admitted = node.getInputs().getFirst().getDefaultValue();
                assertEquals(Integer.parseInt(number), Integer.parseInt(admitted));
            }
        }
    }

    @Test
    void decimalDefaultsKeepPlainNotationOnColdAndResidentLoads() {
        for (String number : List.of("0.00000001", "1000000000000000000000")) {
            String text = definition("decimal_default").replace("\"outputs\":[]", "\"inputs\":[{\"id\":\"value\","
                + "\"name\":\"value\",\"displayName\":\"Value\",\"pinType\":\"DATA\",\"dataType\":\"double\","
                + "\"description\":\"The numeric input supplied to this operation.\",\"defaultValue\":" + number + "}],\"outputs\":[]");
            NodeDefinitionLoader.SourceFile source = source("decimal-default.json", text);
            for (int load = 0; load < 2; load++) {
                NodeDefinition node = new NodeDefinitionLoader().loadReplacementFromSources(List.of(source)).getFirst();
                assertEquals(number, node.getInputs().getFirst().getDefaultValue());
            }
        }
    }

    private static NodeDefinitionLoader.SourceFile source(String name, String text) {
        return new NodeDefinitionLoader.SourceFile(name, "valid:/" + name, name, NodeDefinitionLoader.SourceOrigin.LOCAL,
            text.getBytes(StandardCharsets.UTF_8));
    }

    private static String definition(String id) {
        return "[{\"id\":\"" + id + "\",\"displayName\":\"Admitted\",\"category\":\"UTILITY\","
            + "\"description\":\"A valid authored operation used to verify source admission.\",\"domain\":\"automation\","
            + "\"family\":\"automation\",\"lifecycle\":\"active\",\"handlerCapability\":\"replacement.handler\","
            + "\"selectorIntent\":\"none\",\"inspectorIntent\":\"generic\",\"outputs\":[]}]";
    }
}
