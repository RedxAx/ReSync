package restudio.resync.protocol;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncProtocolContractTest {
    @Test
    void flowContractOwnsTheStableVersionAndCapabilityNegotiation() {
        ReSyncProtocolContract.FlowContract contract = ReSyncProtocolContract.FLOW_CONTRACT;

        assertEquals(2, contract.version());
        assertEquals(2, contract.minimumClientVersion());
        assertTrue(contract.accepts(2, 0));
        assertFalse(contract.accepts(1, 0));
        assertFalse(contract.accepts(3, 0));
        assertEquals(List.of("nodes", "catalogs"), contract.negotiate(List.of("catalogs", "nodes", "unknown")));
        assertEquals(contract.serverCapabilities(), contract.negotiate(contract.clientCapabilities()));
        assertTrue(contract.serverCapabilities().contains(ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_CAPABILITY));
        assertTrue(contract.clientCapabilities().contains(ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_CAPABILITY));
    }

    @Test
    void flowContractListsAreImmutable() {
        ReSyncProtocolContract.FlowContract contract = ReSyncProtocolContract.FLOW_CONTRACT;

        assertSame(contract.serverCapabilities(), ReSyncProtocolContract.FLOW_CONTRACT.serverCapabilities());
        try {
            contract.clientCapabilities().add("unexpected");
        } catch (UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError("Flow contract capabilities must be immutable");
    }

    @Test
    void rejectsFabricatedOrEmptyNegotiationAndMissingRequiredEntries() {
        ReSyncProtocolContract.FlowContract contract = ReSyncProtocolContract.FLOW_CONTRACT;
        List<String> supported = contract.serverCapabilities();
        List<String> negotiated = contract.negotiate(contract.clientCapabilities());

        assertTrue(contract.matchesNegotiation(supported, negotiated));
        assertFalse(contract.matchesNegotiation(List.of(), List.of()));
        assertFalse(contract.matchesNegotiation(supported, List.of("fabricated")));
        assertFalse(contract.matchesNegotiation(supported, List.of("nodes", "nodes")));
        assertTrue(contract.hasRequiredCapabilities(negotiated));
        assertFalse(contract.hasRequiredCapabilities(List.of("nodes")));
    }

    @Test
    void genericResourceActivationUsesVersionedCapabilityGate() {
        ReSyncProtocolContract.GenericResourceContract contract = ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT;
        ContractRef<CapabilityId> activation = ReSyncProtocolContract.RESOURCE_ACTIVATION_CAPABILITY;

        ContractRef<CapabilityId> presentation = ReSyncProtocolContract.RESOURCE_CREATE_PRESENTATION_CAPABILITY;
        ContractRef<CapabilityId> options = ReSyncProtocolContract.OPTION_QUERIES_CAPABILITY;

        assertEquals(new CatalogVersion(1, 3), contract.version());
        assertEquals("resource_activation", activation.id().value());
        assertTrue(ReSyncProtocolContract.supportsGenericResourceContract(new CatalogVersion(1, 0)));
        assertTrue(ReSyncProtocolContract.supportsGenericResourceContract(new CatalogVersion(1, 1)));
        assertTrue(ReSyncProtocolContract.supportsGenericResourceContract(new CatalogVersion(1, 2)));
        assertTrue(ReSyncProtocolContract.supportsGenericResourceContract(new CatalogVersion(1, 3)));
        assertFalse(ReSyncProtocolContract.supportsGenericResourceContract(new CatalogVersion(1, 4)));
        assertTrue(contract.supportsResourceActivation(new CatalogVersion(1, 1), List.of(activation)));
        assertTrue(contract.supportsResourceActivation(new CatalogVersion(1, 2), List.of(activation)));
        assertFalse(contract.supportsResourceActivation(new CatalogVersion(1, 0), List.of(activation)));
        assertFalse(contract.supportsResourceActivation(new CatalogVersion(1, 1), List.of()));
        assertEquals("resource_create_presentation", presentation.id().value());
        assertTrue(contract.supportsResourceCreatePresentation(new CatalogVersion(1, 2), List.of(presentation)));
        assertFalse(contract.supportsResourceCreatePresentation(new CatalogVersion(1, 1), List.of(presentation)));
        assertFalse(contract.supportsResourceCreatePresentation(new CatalogVersion(1, 2), List.of()));
        assertEquals("option_queries", options.id().value());
        assertTrue(contract.supportsOptionQueries(new CatalogVersion(1, 3), List.of(options)));
        assertFalse(contract.supportsOptionQueries(new CatalogVersion(1, 2), List.of(options)));
        assertFalse(contract.supportsOptionQueries(new CatalogVersion(1, 3), List.of()));
    }

    @Test
    void flowContractAdvertisesAdditiveResourceCapabilitiesWithoutRequiringThem() {
        ReSyncProtocolContract.FlowContract contract = ReSyncProtocolContract.FLOW_CONTRACT;
        String activation = ReSyncProtocolContract.RESOURCE_ACTIVATION_CAPABILITY.id().value();
        String presentation = ReSyncProtocolContract.RESOURCE_CREATE_PRESENTATION_CAPABILITY.id().value();
        String options = ReSyncProtocolContract.OPTION_QUERIES_CAPABILITY.id().value();

        assertTrue(contract.serverCapabilities().contains(activation));
        assertTrue(contract.clientCapabilities().contains(activation));
        assertFalse(contract.requiredCapabilities().contains(activation));
        assertTrue(contract.serverCapabilities().contains(presentation));
        assertTrue(contract.clientCapabilities().contains(presentation));
        assertFalse(contract.requiredCapabilities().contains(presentation));
        assertTrue(contract.serverCapabilities().contains(options));
        assertTrue(contract.clientCapabilities().contains(options));
        assertFalse(contract.requiredCapabilities().contains(options));
        assertEquals(List.of(activation, options), contract.negotiate(List.of(activation, options)));
    }

    @Test
    void preservesWireConstants() {
        assertEquals(2, ReSyncProtocolContract.PROTOCOL_VERSION);
        assertEquals(1_048_576, ReSyncProtocolContract.MAX_ENCODED_FRAME_BYTES);
        assertEquals(4_194_304, ReSyncProtocolContract.MAX_DECOMPRESSED_PAYLOAD_BYTES);
        assertEquals((byte) 0x01, ReSyncProtocolContract.FLOW_PACKET_REQUEST);
        assertEquals((byte) 0x03, ReSyncProtocolContract.FLOW_PACKET_SAVE);
        assertEquals((byte) 0x09, ReSyncProtocolContract.FLOW_PACKET_LIST_REQUEST);
        assertEquals((byte) 0x37, ReSyncProtocolContract.FLOW_PACKET_OPTION_CATALOG_REQUEST);
        assertEquals((byte) 0x44, ReSyncProtocolContract.FLOW_PACKET_JOB);
        assertEquals((byte) 0x09, ReSyncProtocolContract.MESSAGE_PROTOCOL_ENVELOPE);
        assertEquals((byte) 0x0E, ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION);
        assertEquals((byte) 0x0F, ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION_REQUEST);
        assertEquals((byte) 0x77, ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION_CLIENT_RECEIVED);
        assertEquals((byte) 0x78, ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION_CACHE_APPLIED);
        assertEquals((byte) 0x79, ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION_CACHE_REJECTED);
        assertEquals((byte) 0xB4, ReSyncProtocolContract.DIALOG_PACKET_REQUEST);
        assertEquals((byte) 0xBA, ReSyncProtocolContract.DIALOG_PACKET_SAVE_ACK);
        assertEquals((byte) 0xD1, ReSyncProtocolContract.LOOT_TABLE_PACKET_SAVE_ACK);
        assertEquals((short) 1001, ReSyncProtocolContract.CHANNEL_FLOW_ID);
        assertEquals("flow", ReSyncProtocolContract.CHANNEL_FLOW);
        assertEquals("jobId", ReSyncProtocolContract.DTO_JOB_ID);
    }

    @Test
    void preservesResourcePacketMappings() {
        assertEquals(24, ReSyncProtocolContract.RESOURCE_CONTRACTS.length);
        assertResource("flow", "Flow", "Blueprints/Flows", false, 0x01, 0x09, 0x02, 0x0A, 0x03, 0x08, 0x07);
        assertResource("function", "Function", "Blueprints/Functions", false, 0xE7, 0xE8, 0xE9, 0xEA, 0xEB, 0xEC, 0xED);
        assertResource("command", "Command", "Blueprints/Commands", false, 0xEE, 0xEF, 0xF0, 0xF1, 0xF2, 0xF3, 0xF4);
        assertResource("custom_content", "Custom Content", "Content/Items", false, 0x30, 0x36, 0x32, 0x31, 0x33, 0x34, 0x35);
        assertResource("dialog", "Dialog", "Content/Dialogs", true, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xB9, 0xBA);
        assertResource("loot_table", "Loot Table", "Content/Loot Tables", true, 0xCB, 0xCC, 0xCD, 0xCE, 0xCF, 0xD0, 0xD1);
        assertEquals("WorldGen", ReSyncProtocolContract.resource("worldgen").displayName());
        assertEquals("Structure", ReSyncProtocolContract.resource("structure").displayName());
        assertNull(ReSyncProtocolContract.resource("missing"));
        assertNull(ReSyncProtocolContract.resource("worldgen").flowPackets());
        assertNull(ReSyncProtocolContract.resource("structure").flowPackets());
    }

    private static void assertResource(String typeId, String displayName, String defaultFolder, boolean jsonStorageSupported,
                                       int request, int listRequest, int data, int list, int save, int delete, int saveAck) {
        ReSyncProtocolContract.ResourceContract resource = ReSyncProtocolContract.resource(typeId);
        ReSyncProtocolContract.ResourceFlowPackets packets = resource.flowPackets();
        assertEquals(typeId, resource.typeId());
        assertEquals(displayName, resource.displayName());
        assertEquals(defaultFolder, resource.defaultFolder());
        assertEquals(jsonStorageSupported, resource.jsonStorageSupported());
        assertEquals((byte) request, packets.request());
        assertEquals((byte) listRequest, packets.listRequest());
        assertEquals((byte) data, packets.data());
        assertEquals((byte) list, packets.list());
        assertEquals((byte) save, packets.save());
        assertEquals((byte) delete, packets.delete());
        assertEquals((byte) saveAck, packets.saveAck());
    }
}
