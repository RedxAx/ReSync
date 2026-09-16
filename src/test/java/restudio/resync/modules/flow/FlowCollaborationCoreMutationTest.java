package restudio.resync.modules.flow;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import restudio.resync.flow.CoreGraphStorageBoundary;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowCollaborationCoreMutationTest {
    @Test
    void collaborationEventCarriesTheCanonicalTypedTombstoneIdentity() {
        ServerResourceLocator resource = new ServerResourceLocator(
            new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111")),
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("command")), "typed-collaboration");
        UUID mutationId = UUID.fromString("22222222-2222-4222-8222-222222222222");
        String envelope = new String(new CoreGraphStorageBoundary().encodeTombstone(
            resource, 7L, mutationId, new ContentHash("a".repeat(64))), StandardCharsets.UTF_8);
        CoreResourceMutationTransition transition = new CoreResourceMutationTransition(
            resource, 7L, mutationId, true, null, "protocol:operator", envelope);

        FlowCollaborationService.CoreResourceEvent event = FlowCollaborationService.coreResourceEvent(transition);
        JsonObject payload = JsonParser.parseString(FlowCollaborationService.coreResourcePayload(transition)).getAsJsonObject();

        assertEquals("command", event.type());
        assertEquals("typed-collaboration", event.resourceId());
        assertEquals(7L, event.revision());
        assertEquals(mutationId.toString(), event.mutationId());
        assertTrue(event.deleted());
        assertNull(event.activationState());
        assertEquals(envelope, event.canonicalEnvelope());
        assertEquals("protocol:operator", event.author());
        assertTrue(event.changedAt() > 0L);
        assertTrue(payload.has("activationState"));
        assertTrue(payload.get("activationState").isJsonNull());
        assertEquals(envelope, payload.get("canonicalEnvelope").getAsString());
        assertEquals("protocol:operator", payload.get("author").getAsString());
    }
}
