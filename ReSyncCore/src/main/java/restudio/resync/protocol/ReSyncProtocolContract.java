package restudio.resync.protocol;

import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class ReSyncProtocolContract {
    public static final String FLOW_TRIGGER_UPDATE_CAPABILITY = "trigger_update_epoch";
    public static final int PROTOCOL_VERSION = 2;
    public static final int MAX_ENCODED_FRAME_BYTES = 1048576;
    public static final int MAX_DECOMPRESSED_PAYLOAD_BYTES = 4194304;
    public static final int FRAME_HEADER_BYTES = 12;
    public static final int FRAME_FLAG_COMPRESSED = 128;
    public static final int FRAME_FLAG_BATCH = 64;
    public static final int FRAME_FLAG_ACK = 32;
    public static final int FRAME_RESERVED_FLAGS = 31;
    public static final int MAX_CHANNEL_ID = 65535;
    public static final int DEFAULT_COMPRESSION_THRESHOLD_BYTES = 1024;
    public static final int MAX_HANDSHAKE_FIELD_BYTES = 65536;
    public static final int MAX_HANDSHAKE_COLLECTION_ENTRIES = 2048;
    public static final int MAX_MESSAGE_FIELD_BYTES = 65536;
    public static final CatalogVersion GENERIC_RESOURCE_CONTRACT_MINIMUM_VERSION = new CatalogVersion(1, 0);
    public static final GenericResourceContract GENERIC_RESOURCE_CONTRACT = new GenericResourceContract(
        new CatalogVersion(1, 3),
        Set.of(
            ContractRef.of(new OwnerId("restudio.resync"), CapabilityId.of("resource_activation")),
            ContractRef.of(new OwnerId("restudio.resync"), CapabilityId.of("resource_create_presentation")),
            ContractRef.of(new OwnerId("restudio.resync"), CapabilityId.of("option_queries"))
        )
    );
    public static final CatalogVersion GENERIC_RESOURCE_CONTRACT_VERSION = GENERIC_RESOURCE_CONTRACT.version();
    public static final ContractRef<CapabilityId> RESOURCE_ACTIVATION_CAPABILITY =
        ContractRef.of(new OwnerId("restudio.resync"), CapabilityId.of("resource_activation"));
    public static final ContractRef<CapabilityId> RESOURCE_CREATE_PRESENTATION_CAPABILITY =
        ContractRef.of(new OwnerId("restudio.resync"), CapabilityId.of("resource_create_presentation"));
    public static final ContractRef<CapabilityId> OPTION_QUERIES_CAPABILITY =
        ContractRef.of(new OwnerId("restudio.resync"), CapabilityId.of("option_queries"));

    public static boolean supportsGenericResourceContract(CatalogVersion candidateVersion) {
        return candidateVersion != null
            && candidateVersion.isBetween(GENERIC_RESOURCE_CONTRACT_MINIMUM_VERSION, GENERIC_RESOURCE_CONTRACT_VERSION);
    }

    public static final FlowContract FLOW_CONTRACT = new FlowContract(
        2,
        2,
        List.of("nodes", "types", "categories", "properties", "resources", "catalogs", "conversions", "extensions", "deltas", "diagnostics", "contextual_catalogs", "authorization", "destructive_safety", "function_tests", "jobs", "job_events", FLOW_TRIGGER_UPDATE_CAPABILITY, "resource_revisions", "asset_integrity", "transaction_recovery", "migration_fencing", "opaque_resources", "collaboration_presence", "collaboration_chat", "resource_events", "live_workspace", RESOURCE_ACTIVATION_CAPABILITY.id().value(), RESOURCE_CREATE_PRESENTATION_CAPABILITY.id().value(), OPTION_QUERIES_CAPABILITY.id().value()),
        List.of("nodes", "types", "categories", "properties", "resources", "catalogs", "conversions", "extensions", "deltas", "diagnostics", "contextual_catalogs", "authorization", "destructive_safety", "function_tests", "jobs", "job_events", FLOW_TRIGGER_UPDATE_CAPABILITY, "resource_operation_diagnostics", "extension_validators", "resource_revisions", "asset_integrity", "transaction_recovery", "migration_fencing", "opaque_resources", "collaboration_presence", "collaboration_chat", "resource_events", "live_workspace", RESOURCE_ACTIVATION_CAPABILITY.id().value(), RESOURCE_CREATE_PRESENTATION_CAPABILITY.id().value(), OPTION_QUERIES_CAPABILITY.id().value()),
        List.of("nodes", "types", "categories", "properties", "resources", "catalogs", "conversions", "extensions", "deltas", "diagnostics")
    );
    public static final String CHANNEL_FLOW = "flow";
    public static final String CHANNEL_PLAYER_TRACKING = "player_tracking";
    public static final String CHANNEL_WORLD_MANAGEMENT = "world_management";
    public static final String CHANNEL_WORLDGEN = "worldgen";
    public static final short CHANNEL_CONTROL_ID = 0;
    public static final short CHANNEL_FLOW_ID = 1001;
    public static final short CHANNEL_PLAYER_TRACKING_ID = 1002;
    public static final short CHANNEL_WORLD_MANAGEMENT_ID = 1003;
    public static final short CHANNEL_WORLDGEN_ID = 1004;
    public static final byte MESSAGE_HANDSHAKE_REQUEST = (byte) 0x00;
    public static final byte MESSAGE_HANDSHAKE_RESPONSE = (byte) 0x01;
    public static final byte MESSAGE_SUBSCRIBE = (byte) 0x02;
    public static final byte MESSAGE_UNSUBSCRIBE = (byte) 0x03;
    public static final byte MESSAGE_DATA = (byte) 0x04;
    public static final byte MESSAGE_HEARTBEAT = (byte) 0x05;
    public static final byte MESSAGE_ACK = (byte) 0x06;
    public static final byte MESSAGE_ERROR = (byte) 0x07;
    public static final byte MESSAGE_CHANNEL_REGISTRY = (byte) 0x08;
    public static final byte MESSAGE_PROTOCOL_ENVELOPE = (byte) 0x09;
    public static final int CHANNEL_REGISTRY_VERSION = 1;
    public static final byte FLOW_PACKET_REQUEST = (byte) 0x01;
    public static final byte FLOW_PACKET_DATA = (byte) 0x02;
    public static final byte FLOW_PACKET_SAVE = (byte) 0x03;
    public static final byte FLOW_PACKET_GUI_STATE = (byte) 0x04;
    public static final byte FLOW_PACKET_ERROR = (byte) 0x05;
    public static final byte FLOW_PACKET_TRIGGER_UPDATE = (byte) 0x06;
    public static final int FLOW_TRIGGER_UPDATE_FORMAT_VERSION = 1;
    public static final String FLOW_TRIGGER_UPDATE_FORMAT_VERSION_FIELD = "formatVersion";
    public static final String FLOW_TRIGGER_UPDATE_AUTHORITY_EPOCH_FIELD = "authorityEpoch";
    public static final String FLOW_TRIGGER_UPDATE_REQUEST_ID_FIELD = "requestId";
    public static final String FLOW_TRIGGER_UPDATE_BINDINGS_FIELD = "bindings";
    public static final String FLOW_TRIGGER_UPDATE_EXPECTED_BINDING_EPOCH_FIELD = "expectedBindingEpoch";
    public static final String FLOW_TRIGGER_UPDATE_EXPECTED_BINDING_HASH_FIELD = "expectedBindingHash";
    public static final String FLOW_TRIGGER_UPDATE_BINDING_HASH_DOMAIN = "trigger-binding-update";
    public static final String FLOW_TRIGGER_UPDATE_BINDING_STATE_CAPABILITY = "triggerBindingState";
    public static final String FLOW_TRIGGER_UPDATE_BINDING_STATE_EPOCH_FIELD = "epoch";
    public static final String FLOW_TRIGGER_UPDATE_BINDING_STATE_HASH_FIELD = "hash";
    public static final String FLOW_TRIGGER_UPDATE_RESULT_ACCEPTED_FIELD = "accepted";
    public static final String FLOW_TRIGGER_UPDATE_RESULT_STALE_FIELD = "stale";
    public static final String FLOW_TRIGGER_UPDATE_RESULT_DURABLE_FIELD = "durable";
    public static final String FLOW_TRIGGER_UPDATE_RESULT_RUNTIME_READY_FIELD = "runtimeReady";
    public static final String FLOW_TRIGGER_UPDATE_RESULT_FINALIZATION_ERROR_FIELD = "finalizationError";
    public static final String FLOW_TRIGGER_UPDATE_RESULT_EPOCH_FIELD = "bindingEpoch";
    public static final String FLOW_TRIGGER_UPDATE_RESULT_HASH_FIELD = "bindingHash";
    public static final String FLOW_TRIGGER_UPDATE_RESULT_BINDINGS_FIELD = "bindings";
    public static final byte FLOW_PACKET_SAVE_ACK = (byte) 0x07;
    public static final byte FLOW_PACKET_DELETE = (byte) 0x08;
    public static final byte FLOW_PACKET_LIST_REQUEST = (byte) 0x09;
    public static final byte FLOW_PACKET_LIST_RESPONSE = (byte) 0x0A;
    public static final byte FLOW_PACKET_NODE_REGISTRY = (byte) 0x0B;
    public static final byte FLOW_PACKET_NODE_REGISTRY_REQUEST = (byte) 0x0C;
    public static final byte FLOW_PACKET_NODE_REGISTRY_DELTA = (byte) 0x0D;
    public static final byte FLOW_PACKET_CATALOG_PUBLICATION = (byte) 0x0E;
    public static final byte FLOW_PACKET_CATALOG_PUBLICATION_REQUEST = (byte) 0x0F;
    public static final byte FLOW_PACKET_CATALOG_PUBLICATION_CLIENT_RECEIVED = (byte) 0x77;
    public static final byte FLOW_PACKET_CATALOG_PUBLICATION_CACHE_APPLIED = (byte) 0x78;
    public static final byte FLOW_PACKET_CATALOG_PUBLICATION_CACHE_REJECTED = (byte) 0x79;
    public static final byte FLOW_PACKET_CATALOG_PUBLICATION_CHUNK = (byte) 0x7A;
    public static final byte FLOW_PACKET_OPTION_CATALOG_REQUEST = (byte) 0x37;
    public static final byte FLOW_PACKET_OPTION_CATALOG = (byte) 0x38;
    public static final byte FLOW_PACKET_PLACEHOLDER_PREVIEW_REQUEST = (byte) 0x27;
    public static final byte FLOW_PACKET_PLACEHOLDER_PREVIEW = (byte) 0x28;
    public static final byte FLOW_PACKET_TRACE_TOGGLE = (byte) 0x40;
    public static final byte FLOW_PACKET_TRACE_SNAPSHOT = (byte) 0x41;
    public static final byte FLOW_PACKET_TRACE_EVENT = (byte) 0x42;
    public static final byte FLOW_PACKET_TRACE_CLEAR = (byte) 0x43;
    public static final byte FLOW_PACKET_JOB = (byte) 0x44;
    public static final byte FLOW_PACKET_JOB_SNAPSHOT_REQUEST = (byte) 0x45;
    public static final byte FLOW_PACKET_DEBUG_COMMAND = (byte) 0x46;
    public static final byte FLOW_PACKET_DEBUG_EVENT = (byte) 0x47;
    public static final byte FLOW_PACKET_FUNCTION_TEST_REQUEST = (byte) 0x48;
    public static final byte FLOW_PACKET_FUNCTION_TEST_RESULT = (byte) 0x49;
    public static final byte FLOW_PACKET_EDIT_TARGET_STATE = (byte) 0x5A;
    public static final byte FLOW_PACKET_PRESENCE_UPDATE = (byte) 0x5B;
    public static final byte FLOW_PACKET_PRESENCE_SNAPSHOT = (byte) 0x5C;
    public static final byte FLOW_PACKET_RESOURCE_CHANGED = (byte) 0x5D;
    public static final byte FLOW_PACKET_RESOURCE_DELETED = (byte) 0x5E;
    public static final byte FLOW_PACKET_WORKSPACE_JOIN = (byte) 0x6E;
    public static final byte FLOW_PACKET_WORKSPACE_LEAVE = (byte) 0x6F;
    public static final byte FLOW_PACKET_WORKSPACE_SNAPSHOT = (byte) 0x70;
    public static final byte FLOW_PACKET_WORKSPACE_OPERATION = (byte) 0x71;
    public static final byte FLOW_PACKET_WORKSPACE_AWARENESS = (byte) 0x72;
    public static final byte FLOW_PACKET_WORKSPACE_RESYNC = (byte) 0x73;
    public static final byte FLOW_PACKET_COLLABORATION_CHAT = (byte) 0x74;
    public static final byte FLOW_PACKET_RESOURCE_ACTIVATION = (byte) 0x75;
    public static final byte FLOW_PACKET_RESOURCE_ACTIVATION_RESULT = (byte) 0x76;
    public static final byte FLOW_PACKET_QUICK_EDIT_OPEN = (byte) 0x60;
    public static final byte FLOW_PACKET_QUICK_EDIT_APPLY = (byte) 0x61;
    public static final byte FLOW_PACKET_QUICK_EDIT_RESULT = (byte) 0x62;
    public static final byte FLOW_PACKET_OPEN_CUSTOM_CONTENT = (byte) 0x63;
    public static final byte CUSTOM_CONTENT_PACKET_LIST_RESPONSE = (byte) 0x31;
    public static final byte CUSTOM_CONTENT_PACKET_DATA = (byte) 0x32;
    public static final byte CUSTOM_CONTENT_PACKET_SAVE = (byte) 0x33;
    public static final byte CUSTOM_CONTENT_PACKET_DELETE = (byte) 0x34;
    public static final byte CUSTOM_CONTENT_PACKET_SAVE_ACK = (byte) 0x35;
    public static final byte CUSTOM_CONTENT_PACKET_LIST_REQUEST = (byte) 0x36;
    public static final byte PERMISSION_PROFILE_PACKET_REQUEST = (byte) 0x60;
    public static final byte PERMISSION_PROFILE_PACKET_LIST_REQUEST = (byte) 0x61;
    public static final byte PERMISSION_PROFILE_PACKET_DATA = (byte) 0x62;
    public static final byte PERMISSION_PROFILE_PACKET_LIST_RESPONSE = (byte) 0x63;
    public static final byte PERMISSION_PROFILE_PACKET_SAVE = (byte) 0x64;
    public static final byte PERMISSION_PROFILE_PACKET_DELETE = (byte) 0x65;
    public static final byte PERMISSION_PROFILE_PACKET_SAVE_ACK = (byte) 0x66;
    public static final byte CHAT_PACKET_REQUEST = (byte) 0x67;
    public static final byte CHAT_PACKET_LIST_REQUEST = (byte) 0x68;
    public static final byte CHAT_PACKET_DATA = (byte) 0x69;
    public static final byte CHAT_PACKET_LIST_RESPONSE = (byte) 0x6A;
    public static final byte CHAT_PACKET_SAVE = (byte) 0x6B;
    public static final byte CHAT_PACKET_DELETE = (byte) 0x6C;
    public static final byte CHAT_PACKET_SAVE_ACK = (byte) 0x6D;
    public static final byte MOTD_PROFILE_PACKET_REQUEST = (byte) 0x91;
    public static final byte MOTD_PROFILE_PACKET_LIST_REQUEST = (byte) 0x92;
    public static final byte MOTD_PROFILE_PACKET_DATA = (byte) 0x93;
    public static final byte MOTD_PROFILE_PACKET_LIST_RESPONSE = (byte) 0x94;
    public static final byte MOTD_PROFILE_PACKET_SAVE = (byte) 0x95;
    public static final byte MOTD_PROFILE_PACKET_DELETE = (byte) 0x96;
    public static final byte MOTD_PROFILE_PACKET_SAVE_ACK = (byte) 0x97;
    public static final byte MESSAGE_RULE_PACKET_REQUEST = (byte) 0x98;
    public static final byte MESSAGE_RULE_PACKET_LIST_REQUEST = (byte) 0x99;
    public static final byte MESSAGE_RULE_PACKET_DATA = (byte) 0x9A;
    public static final byte MESSAGE_RULE_PACKET_LIST_RESPONSE = (byte) 0x9B;
    public static final byte MESSAGE_RULE_PACKET_SAVE = (byte) 0x9C;
    public static final byte MESSAGE_RULE_PACKET_DELETE = (byte) 0x9D;
    public static final byte MESSAGE_RULE_PACKET_SAVE_ACK = (byte) 0x9E;
    public static final byte RECIPE_DEFINITION_PACKET_REQUEST = (byte) 0x9F;
    public static final byte RECIPE_DEFINITION_PACKET_LIST_REQUEST = (byte) 0xA0;
    public static final byte RECIPE_DEFINITION_PACKET_DATA = (byte) 0xA1;
    public static final byte RECIPE_DEFINITION_PACKET_LIST_RESPONSE = (byte) 0xA2;
    public static final byte RECIPE_DEFINITION_PACKET_SAVE = (byte) 0xA3;
    public static final byte RECIPE_DEFINITION_PACKET_DELETE = (byte) 0xA4;
    public static final byte RECIPE_DEFINITION_PACKET_SAVE_ACK = (byte) 0xA5;
    public static final byte TEXT_TEMPLATE_PACKET_REQUEST = (byte) 0xA6;
    public static final byte TEXT_TEMPLATE_PACKET_LIST_REQUEST = (byte) 0xA7;
    public static final byte TEXT_TEMPLATE_PACKET_DATA = (byte) 0xA8;
    public static final byte TEXT_TEMPLATE_PACKET_LIST_RESPONSE = (byte) 0xA9;
    public static final byte TEXT_TEMPLATE_PACKET_SAVE = (byte) 0xAA;
    public static final byte TEXT_TEMPLATE_PACKET_DELETE = (byte) 0xAB;
    public static final byte TEXT_TEMPLATE_PACKET_SAVE_ACK = (byte) 0xAC;
    public static final byte ADVANCEMENT_TREE_PACKET_REQUEST = (byte) 0xAD;
    public static final byte ADVANCEMENT_TREE_PACKET_LIST_REQUEST = (byte) 0xAE;
    public static final byte ADVANCEMENT_TREE_PACKET_DATA = (byte) 0xAF;
    public static final byte ADVANCEMENT_TREE_PACKET_LIST_RESPONSE = (byte) 0xB0;
    public static final byte ADVANCEMENT_TREE_PACKET_SAVE = (byte) 0xB1;
    public static final byte ADVANCEMENT_TREE_PACKET_DELETE = (byte) 0xB2;
    public static final byte ADVANCEMENT_TREE_PACKET_SAVE_ACK = (byte) 0xB3;
    public static final byte DIALOG_PACKET_REQUEST = (byte) 0xB4;
    public static final byte DIALOG_PACKET_LIST_REQUEST = (byte) 0xB5;
    public static final byte DIALOG_PACKET_DATA = (byte) 0xB6;
    public static final byte DIALOG_PACKET_LIST_RESPONSE = (byte) 0xB7;
    public static final byte DIALOG_PACKET_SAVE = (byte) 0xB8;
    public static final byte DIALOG_PACKET_DELETE = (byte) 0xB9;
    public static final byte DIALOG_PACKET_SAVE_ACK = (byte) 0xBA;
    public static final byte TRADE_PROFILE_PACKET_REQUEST = (byte) 0xBD;
    public static final byte TRADE_PROFILE_PACKET_LIST_REQUEST = (byte) 0xBE;
    public static final byte TRADE_PROFILE_PACKET_DATA = (byte) 0xBF;
    public static final byte TRADE_PROFILE_PACKET_LIST_RESPONSE = (byte) 0xC0;
    public static final byte TRADE_PROFILE_PACKET_SAVE = (byte) 0xC1;
    public static final byte TRADE_PROFILE_PACKET_DELETE = (byte) 0xC2;
    public static final byte TRADE_PROFILE_PACKET_SAVE_ACK = (byte) 0xC3;
    public static final byte NPC_DEFINITION_PACKET_REQUEST = (byte) 0xC4;
    public static final byte NPC_DEFINITION_PACKET_LIST_REQUEST = (byte) 0xC5;
    public static final byte NPC_DEFINITION_PACKET_DATA = (byte) 0xC6;
    public static final byte NPC_DEFINITION_PACKET_LIST_RESPONSE = (byte) 0xC7;
    public static final byte NPC_DEFINITION_PACKET_SAVE = (byte) 0xC8;
    public static final byte NPC_DEFINITION_PACKET_DELETE = (byte) 0xC9;
    public static final byte NPC_DEFINITION_PACKET_SAVE_ACK = (byte) 0xCA;
    public static final byte LOOT_TABLE_PACKET_REQUEST = (byte) 0xCB;
    public static final byte LOOT_TABLE_PACKET_LIST_REQUEST = (byte) 0xCC;
    public static final byte LOOT_TABLE_PACKET_DATA = (byte) 0xCD;
    public static final byte LOOT_TABLE_PACKET_LIST_RESPONSE = (byte) 0xCE;
    public static final byte LOOT_TABLE_PACKET_SAVE = (byte) 0xCF;
    public static final byte LOOT_TABLE_PACKET_DELETE = (byte) 0xD0;
    public static final byte LOOT_TABLE_PACKET_SAVE_ACK = (byte) 0xD1;
    public static final byte MESSAGE_LOG_PACKET_REQUEST = (byte) 0xBB;
    public static final byte MESSAGE_LOG_PACKET_RESPONSE = (byte) 0xBC;
    public static final byte WORLDGEN_PACKET_STATUS = (byte) 0x23;
    public static final byte WORLDGEN_PACKET_JOB = (byte) 0x39;
    public static final String JOB_PENDING = "pending";
    public static final String JOB_RUNNING = "running";
    public static final String JOB_SUCCEEDED = "succeeded";
    public static final String JOB_FAILED = "failed";
    public static final String JOB_CANCELLED = "cancelled";
    public static final String DTO_JOB_ID = "jobId";
    public static final String DTO_OPERATION_ID = "operationId";
    public static final String DTO_ACTION = "action";
    public static final String DTO_STATUS = "status";
    public static final String DTO_MESSAGE = "message";
    public static final String DTO_ERROR_TEXT = "errorText";
    public static final String DTO_RESULT = "result";

    public record FlowContract(int version, int minimumClientVersion, List<String> serverCapabilities,
                               List<String> clientCapabilities, List<String> requiredCapabilities) {
        public FlowContract {
            serverCapabilities = List.copyOf(serverCapabilities);
            clientCapabilities = List.copyOf(clientCapabilities);
            requiredCapabilities = List.copyOf(requiredCapabilities);
        }

        public boolean accepts(int candidateVersion, int candidateMinimumClientVersion) {
            return candidateVersion >= minimumClientVersion
                && candidateVersion <= version
                && candidateMinimumClientVersion <= version;
        }

        public List<String> negotiate(Collection<String> offeredCapabilities) {
            if (offeredCapabilities == null || offeredCapabilities.isEmpty()) {
                return List.of();
            }
            return serverCapabilities.stream().filter(offeredCapabilities::contains).toList();
        }

        public boolean hasRequiredCapabilities(Collection<String> capabilities) {
            return capabilities != null && capabilities.containsAll(requiredCapabilities);
        }

        public boolean matchesNegotiation(Collection<String> supportedCapabilities, Collection<String> negotiatedCapabilities) {
            if (supportedCapabilities == null || supportedCapabilities.isEmpty() || negotiatedCapabilities == null
                || supportedCapabilities.stream().anyMatch(Objects::isNull)
                || negotiatedCapabilities.stream().anyMatch(Objects::isNull)) {
                return false;
            }
            List<String> supported = List.copyOf(supportedCapabilities);
            List<String> negotiated = List.copyOf(negotiatedCapabilities);
            if (supported.stream().anyMatch(value -> value == null || value.isBlank())
                || negotiated.stream().anyMatch(value -> value == null || value.isBlank())
                || supported.size() != supported.stream().distinct().count()
                || negotiated.size() != negotiated.stream().distinct().count()) {
                return false;
            }
            List<String> expected = supported.stream().filter(clientCapabilities::contains).toList();
            return expected.equals(negotiated);
        }
    }

    public record GenericResourceContract(CatalogVersion version, Set<ContractRef<CapabilityId>> capabilities) {
        public GenericResourceContract {
            version = Objects.requireNonNull(version, "version");
            capabilities = Set.copyOf(capabilities);
        }

        public boolean supportsResourceActivation(CatalogVersion candidateVersion,
                                                   Collection<? extends ContractRef<CapabilityId>> offeredCapabilities) {
            return candidateVersion != null && candidateVersion.compareTo(new CatalogVersion(1, 1)) >= 0
                && offeredCapabilities != null && offeredCapabilities.contains(RESOURCE_ACTIVATION_CAPABILITY);
        }

        public boolean supportsResourceCreatePresentation(CatalogVersion candidateVersion,
                                                           Collection<? extends ContractRef<CapabilityId>> offeredCapabilities) {
            return candidateVersion != null && candidateVersion.compareTo(new CatalogVersion(1, 2)) >= 0
                && offeredCapabilities != null && offeredCapabilities.contains(RESOURCE_CREATE_PRESENTATION_CAPABILITY);
        }

        public boolean supportsOptionQueries(CatalogVersion candidateVersion,
                                             Collection<? extends ContractRef<CapabilityId>> offeredCapabilities) {
            return candidateVersion != null && candidateVersion.compareTo(new CatalogVersion(1, 3)) >= 0
                && offeredCapabilities != null && offeredCapabilities.contains(OPTION_QUERIES_CAPABILITY);
        }
    }

    public record ResourceFlowPackets(byte request, byte listRequest, byte data, byte list, byte save, byte delete, byte saveAck) {
    }

    public record ResourceContract(String typeId, String displayName, String defaultFolder, boolean jsonStorageSupported, ResourceFlowPackets flowPackets) {
    }

    public static final ResourceContract[] RESOURCE_CONTRACTS = {
        new ResourceContract(
            "flow",
            "Flow",
            "Blueprints/Flows",
            false,
            new ResourceFlowPackets(
            (byte) 0x01,
            (byte) 0x09,
            (byte) 0x02,
            (byte) 0x0A,
            (byte) 0x03,
            (byte) 0x08,
            (byte) 0x07
        )
        ),
        new ResourceContract(
            "function",
            "Function",
            "Blueprints/Functions",
            false,
            new ResourceFlowPackets(
            (byte) 0xE7,
            (byte) 0xE8,
            (byte) 0xE9,
            (byte) 0xEA,
            (byte) 0xEB,
            (byte) 0xEC,
            (byte) 0xED
        )
        ),
        new ResourceContract(
            "command",
            "Command",
            "Blueprints/Commands",
            false,
            new ResourceFlowPackets(
            (byte) 0xEE,
            (byte) 0xEF,
            (byte) 0xF0,
            (byte) 0xF1,
            (byte) 0xF2,
            (byte) 0xF3,
            (byte) 0xF4
        )
        ),
        new ResourceContract(
            "gui",
            "GUI",
            "GUIs",
            false,
            new ResourceFlowPackets(
            (byte) 0x11,
            (byte) 0x14,
            (byte) 0x12,
            (byte) 0x15,
            (byte) 0x13,
            (byte) 0x16,
            (byte) 0x17
        )
        ),
        new ResourceContract(
            "scoreboard",
            "Scoreboard",
            "Customization/Scoreboards",
            false,
            new ResourceFlowPackets(
            (byte) 0x18,
            (byte) 0x1A,
            (byte) 0x1C,
            (byte) 0x1D,
            (byte) 0x19,
            (byte) 0x1B,
            (byte) 0x1E
        )
        ),
        new ResourceContract(
            "tab",
            "Tab",
            "Customization/Tabs",
            false,
            new ResourceFlowPackets(
            (byte) 0x20,
            (byte) 0x22,
            (byte) 0x24,
            (byte) 0x25,
            (byte) 0x21,
            (byte) 0x23,
            (byte) 0x26
        )
        ),
        new ResourceContract(
            "custom_content",
            "Custom Content",
            "Content/Items",
            false,
            new ResourceFlowPackets(
            (byte) 0x30,
            (byte) 0x36,
            (byte) 0x32,
            (byte) 0x31,
            (byte) 0x33,
            (byte) 0x34,
            (byte) 0x35
        )
        ),
        new ResourceContract(
            "project_metadata",
            "Project Metadata",
            "",
            false,
            new ResourceFlowPackets(
            (byte) 0x50,
            (byte) 0x51,
            (byte) 0x52,
            (byte) 0x53,
            (byte) 0x54,
            (byte) 0x55,
            (byte) 0x56
        )
        ),
        new ResourceContract(
            "chat",
            "Chat",
            "Customization/Chat",
            true,
            new ResourceFlowPackets(
            (byte) 0x67,
            (byte) 0x68,
            (byte) 0x69,
            (byte) 0x6A,
            (byte) 0x6B,
            (byte) 0x6C,
            (byte) 0x6D
        )
        ),
        new ResourceContract(
            "component_builder",
            "Component Builder",
            "Content/Component Builders",
            true,
            null
        ),
        new ResourceContract(
            "motd_profile",
            "MOTD Profile",
            "Customization/MOTDs",
            true,
            new ResourceFlowPackets(
            (byte) 0x91,
            (byte) 0x92,
            (byte) 0x93,
            (byte) 0x94,
            (byte) 0x95,
            (byte) 0x96,
            (byte) 0x97
        )
        ),
        new ResourceContract(
            "message_rule",
            "Message Rule",
            "Customization/Messages",
            true,
            new ResourceFlowPackets(
            (byte) 0x98,
            (byte) 0x99,
            (byte) 0x9A,
            (byte) 0x9B,
            (byte) 0x9C,
            (byte) 0x9D,
            (byte) 0x9E
        )
        ),
        new ResourceContract(
            "recipe_definition",
            "Recipe Definition",
            "Content/Recipes",
            true,
            new ResourceFlowPackets(
            (byte) 0x9F,
            (byte) 0xA0,
            (byte) 0xA1,
            (byte) 0xA2,
            (byte) 0xA3,
            (byte) 0xA4,
            (byte) 0xA5
        )
        ),
        new ResourceContract(
            "text_template",
            "Text Template",
            "Text/Templates",
            true,
            new ResourceFlowPackets(
            (byte) 0xA6,
            (byte) 0xA7,
            (byte) 0xA8,
            (byte) 0xA9,
            (byte) 0xAA,
            (byte) 0xAB,
            (byte) 0xAC
        )
        ),
        new ResourceContract(
            "advancement_tree",
            "Advancement Tree",
            "Content/Advancements",
            true,
            new ResourceFlowPackets(
            (byte) 0xAD,
            (byte) 0xAE,
            (byte) 0xAF,
            (byte) 0xB0,
            (byte) 0xB1,
            (byte) 0xB2,
            (byte) 0xB3
        )
        ),
        new ResourceContract(
            "dialog",
            "Dialog",
            "Content/Dialogs",
            true,
            new ResourceFlowPackets(
            (byte) 0xB4,
            (byte) 0xB5,
            (byte) 0xB6,
            (byte) 0xB7,
            (byte) 0xB8,
            (byte) 0xB9,
            (byte) 0xBA
        )
        ),
        new ResourceContract(
            "trade_profile",
            "Trade Profile",
            "Content/Trades",
            true,
            new ResourceFlowPackets(
            (byte) 0xBD,
            (byte) 0xBE,
            (byte) 0xBF,
            (byte) 0xC0,
            (byte) 0xC1,
            (byte) 0xC2,
            (byte) 0xC3
        )
        ),
        new ResourceContract(
            "npc_definition",
            "NPC Definition",
            "Content/NPCs",
            true,
            new ResourceFlowPackets(
            (byte) 0xC4,
            (byte) 0xC5,
            (byte) 0xC6,
            (byte) 0xC7,
            (byte) 0xC8,
            (byte) 0xC9,
            (byte) 0xCA
        )
        ),
        new ResourceContract(
            "loot_table",
            "Loot Table",
            "Content/Loot Tables",
            true,
            new ResourceFlowPackets(
            (byte) 0xCB,
            (byte) 0xCC,
            (byte) 0xCD,
            (byte) 0xCE,
            (byte) 0xCF,
            (byte) 0xD0,
            (byte) 0xD1
        )
        ),
        new ResourceContract(
            "variable_definition",
            "Variable",
            "Automation/Variables",
            true,
            new ResourceFlowPackets(
            (byte) 0xD2,
            (byte) 0xD3,
            (byte) 0xD4,
            (byte) 0xD5,
            (byte) 0xD6,
            (byte) 0xD7,
            (byte) 0xD8
        )
        ),
        new ResourceContract(
            "timer_definition",
            "Timer",
            "Automation/Timers",
            true,
            new ResourceFlowPackets(
            (byte) 0xD9,
            (byte) 0xDA,
            (byte) 0xDB,
            (byte) 0xDC,
            (byte) 0xDD,
            (byte) 0xDE,
            (byte) 0xDF
        )
        ),
        new ResourceContract(
            "schedule_definition",
            "Schedule",
            "Automation/Schedules",
            true,
            new ResourceFlowPackets(
            (byte) 0xE0,
            (byte) 0xE1,
            (byte) 0xE2,
            (byte) 0xE3,
            (byte) 0xE4,
            (byte) 0xE5,
            (byte) 0xE6
        )
        ),
        new ResourceContract(
            "worldgen",
            "WorldGen",
            "WorldGen",
            false,
            null
        ),
        new ResourceContract(
            "world",
            "World",
            "Worlds",
            false,
            null
        ),
        new ResourceContract(
            "structure",
            "Structure",
            "Structures",
            false,
            null
        )
    };

    public static ResourceContract resource(String typeId) {
        if (typeId == null) {
            return null;
        }
        for (ResourceContract resource : RESOURCE_CONTRACTS) {
            if (resource.typeId().equals(typeId)) {
                return resource;
            }
        }
        return null;
    }

    private ReSyncProtocolContract() {
    }
}
