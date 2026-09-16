package restudio.resync.flow.command;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandGraphContractTest {
    @Test
    void separatesCanonicalLegacyAndUnknownTypedStarts() {
        ContractRef<NodeId> dottedLegacy = ContractRef.of(OwnerId.of("restudio.resync"), NodeId.of("event.resync.command"));
        ContractRef<NodeId> qualifiedLegacy = ContractRef.of(OwnerId.of("event"), NodeId.of("resync_command"));
        ContractRef<NodeId> unknown = ContractRef.of(OwnerId.of("restudio.resync"), NodeId.of("event.chat"));

        assertTrue(CommandGraphContract.isCanonicalStart(CommandGraphContract.CANONICAL_START));
        assertFalse(CommandGraphContract.isLegacyStart(CommandGraphContract.CANONICAL_START));
        assertTrue(CommandGraphContract.isLegacyStart(dottedLegacy));
        assertTrue(CommandGraphContract.isLegacyStart(qualifiedLegacy));
        assertTrue(CommandGraphContract.isAnyStart(dottedLegacy));
        assertFalse(CommandGraphContract.isAnyStart(unknown));
    }

    @Test
    void separatesCanonicalLegacyAndUnknownSerializedStarts() {
        assertTrue(CommandGraphContract.isCanonicalStart(" RESTUDIO.RESYNC:EVENT.COMMAND "));
        assertTrue(CommandGraphContract.isCanonicalStart("event.command"));
        assertTrue(CommandGraphContract.isCanonicalStart("restudio.resync/event.command"));
        assertFalse(CommandGraphContract.isLegacyStart(CommandGraphContract.CANONICAL_SERIALIZED_START));
        assertFalse(CommandGraphContract.isLegacyStart("restudio.resync/event.command"));
        assertTrue(CommandGraphContract.isLegacyStart("event.resync.command"));
        assertTrue(CommandGraphContract.isLegacyStart("event:resync_command"));
        assertTrue(CommandGraphContract.isLegacyStart("restudio.resync:event.resync.command"));
        assertTrue(CommandGraphContract.isLegacyStart("restudio.resync/event.resync.command"));
        assertTrue(CommandGraphContract.isAnyStart("event:resync_command"));
        assertFalse(CommandGraphContract.isAnyStart("event.chat"));
        assertFalse(CommandGraphContract.isAnyStart((String) null));
    }
}
