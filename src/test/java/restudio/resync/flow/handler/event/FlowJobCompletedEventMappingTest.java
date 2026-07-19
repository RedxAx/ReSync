package restudio.resync.flow.handler.event;

import org.bukkit.event.Event;
import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowJobReference;
import restudio.resync.flow.jobs.FlowJobCompletedEvent;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionLoader;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class FlowJobCompletedEventMappingTest {
    @Test
    void shippedJobCompletionDefinitionExtractsEveryOutput() throws Exception {
        Path path = Path.of("src", "main", "resources", "nodes", "migrated", "jobs.json");
        List<NodeDefinition> definitions;
        try (InputStream input = Files.newInputStream(path)) {
            definitions = new NodeDefinitionLoader().parse(input, path.toString());
        }
        NodeDefinition definition = definitions.stream().filter(candidate -> "event.job.completed".equals(candidate.getId())).findFirst().orElseThrow();
        FlowJobReference<String> reference = new FlowJobReference<>("job-17", "compile", "flow:test");
        reference.start();
        reference.updateProgress(0.75, Map.of("phase", "compile"));
        reference.succeed("ready");
        FlowJobReference.Snapshot<String> snapshot = reference.snapshot();
        Function<Event, Map<String, Object>> extractor = new FlowEventRegistry(null).buildVariableExtractor(definition);

        Map<String, Object> variables = extractor.apply(new FlowJobCompletedEvent(snapshot));

        assertEquals(definition.getOutputMappings().size(), variables.size());
        assertInstanceOf(FlowJobReference.class, variables.get("event.job"));
        assertEquals("job-17", variables.get("event.job_id"));
        assertEquals("compile", variables.get("event.job_kind"));
        assertEquals("flow:test", variables.get("event.job_owner"));
        assertEquals("SUCCEEDED", variables.get("event.job_state"));
        assertEquals(1.0, variables.get("event.job_progress"));
        assertEquals(Map.of("phase", "compile"), variables.get("event.job_metadata"));
        assertNotNull(variables.get("event.job_outcome"));
    }
}
