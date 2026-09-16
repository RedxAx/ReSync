package restudio.resync.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.diagnostics.DiagnosticSink;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomContentPreparationDiagnosticsTest {
    @TempDir
    Path directory;

    @Test
    void preservesBoundedDefinitionIdentityWithoutExposingPayloadsOrStackText() throws Exception {
        ServerId server = new ServerId(UUID.fromString("e65887a4-ea27-4c55-bae2-e1c8d92da433"));
        ServerResourceLocator resource = new ServerResourceLocator(server,
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("custom_content")), "g");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("typedKey", resource);
        values.put("nodeInstanceId", "3f0cfaf3-3776-46ea-949b-e623832dca0d");
        values.put("nodeId", "custom_content.item");
        values.put("definitionId", "custom_content.item");
        values.put("definitionOwner", "restudio.resync");
        values.put("metadataContract", "authored-source-or-legacy-source-node");
        values.put("errorType", "UnsupportedGraphException");
        values.put("failureType", "java.lang.IllegalArgumentException");
        values.put("payload", "private content");
        var event = LifecycleDiagnosticEventAdapter.event("custom_content_prepare", 7, server.canonicalText(), values, true);
        assertEquals(resource, event.identity().resource());
        for (String field : values.keySet()) {
            if (!"typedKey".equals(field) && !"payload".equals(field)) {
                assertEquals(values.get(field), event.values().get(field).toJava(), field);
            }
        }
        assertFalse(event.values().containsKey("payload"));
        try (LifecycleDiagnosticFileSink sink = new LifecycleDiagnosticFileSink(directory, DiagnosticSink.Mode.RECOVERY, 64_000L, null)) {
            assertEquals(DiagnosticSink.Offer.ACCEPTED, sink.offer(event));
            assertEquals(DiagnosticSink.Flush.FLUSHED, sink.flush());
            String record = Files.readString(sink.activePath());
            assertTrue(record.contains("\"errorType\":\"UnsupportedGraphException\""));
            assertTrue(record.contains("\"definitionId\":\"custom_content.item\""));
            assertTrue(record.contains("\"nodeInstanceId\":\"3f0cfaf3-3776-46ea-949b-e623832dca0d\""));
            assertFalse(record.contains("private content"));
        }
        assertEquals("[redacted]", LifecycleDiagnosticPolicy.sanitizeValues(
            Map.of("errorType", "at restudio.resync.Handler.run(Handler.java:1)")).get("errorType"));
    }
}
