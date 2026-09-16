package restudio.resync.modules;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowRuntimeModuleInitializationDiagnosticsTest {
    @Test
    void initializationDiagnosticsPartitionTheCompleteFlowModulePath() throws IOException {
        String source = Files.readString(Path.of("src/main/java/restudio/resync/modules/FlowRuntimeModule.java"));
        List<String> stages = List.of(
            "foundation_storage",
            "catalog_providers",
            "registry_runtime",
            "catalog_prepare",
            "execution_services",
            "catalog_runtime",
            "service_publish",
            "initialize_total"
        );
        int previous = -1;
        for (String stage : stages) {
            String token = "\"" + stage + "\"";
            int index = source.indexOf(token);
            assertTrue(index > previous, () -> "Missing or out-of-order Flow initialization stage: " + stage);
            assertEquals(index, source.lastIndexOf(token), () -> "Duplicate Flow initialization stage: " + stage);
            previous = index;
        }
        assertTrue(source.contains("participantTimings\", initializationTiming(started, cpuStarted)"));
        assertTrue(source.contains("reportInitializationStage(\"initialize_total\", initializationStarted, initializationCpuStarted)"));
        assertTrue(source.contains("int startupDefinitionCount = startupDefinitions.size()"));
        assertFalse(source.contains("\"definitionCount\", nodeDefinitionRegistry.getAllDefinitions().size()"));
        assertEquals(2, occurrences(source, "\"definitionCount\", startupDefinitionCount"));
    }

    private static int occurrences(String source, String token) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }
}
