package restudio.resync.flow.resource;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.IdentitySupport;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.protocol.ResourceOperationKind;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record ResourceManagementDescriptor(
    int schemaVersion,
    ContractRef<ResourceTypeId> resourceType,
    TypeReference payloadType,
    Availability availability,
    List<Operation> operations,
    ServerAuthoring serverAuthoring,
    Map<String, Object> unknown
) {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_INPUTS = 128;
    private static final String HASH_DOMAIN = "resource-management-descriptor.v1";

    public ResourceManagementDescriptor {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported resource management descriptor schema version");
        }
        resourceType = Objects.requireNonNull(resourceType, "Resource type is required");
        payloadType = Objects.requireNonNull(payloadType, "Payload type is required");
        availability = Objects.requireNonNull(availability, "Resource availability is required");
        operations = exactOperations(operations);
        validateAvailability(availability, operations);
        unknown = IdentitySupport.unknown(unknown, "resource management descriptor unknown data");
    }

    public ResourceManagementDescriptor(ContractRef<ResourceTypeId> resourceType, TypeReference payloadType,
                                        Availability availability, List<Operation> operations,
                                        ServerAuthoring serverAuthoring, Map<String, ?> unknown) {
        this(SCHEMA_VERSION, resourceType, payloadType, availability, operations, serverAuthoring,
            IdentitySupport.unknown(unknown, "resource management descriptor unknown data"));
    }

    public ResourceManagementDescriptor(ContractRef<ResourceTypeId> resourceType, TypeReference payloadType,
                                        Availability availability, List<Operation> operations,
                                        ServerAuthoring serverAuthoring) {
        this(resourceType, payloadType, availability, operations, serverAuthoring, Map.of());
    }

    public ContentHash checksum() {
        return new ContentHash(CanonicalJson.sha256(HASH_DOMAIN, ResourceManagementDescriptorCodec.INSTANCE.encode(this).toJava()));
    }

    private static List<Operation> exactOperations(List<Operation> values) {
        Objects.requireNonNull(values, "Resource operations are required");
        ResourceOperationKind[] kinds = ResourceOperationKind.values();
        if (values.size() != kinds.length) {
            throw new IllegalArgumentException("Resource operations must declare exactly " + kinds.length + " entries");
        }
        for (int index = 0; index < kinds.length; index++) {
            Operation operation = Objects.requireNonNull(values.get(index), "Resource operation is required");
            if (operation.operation() != kinds[index]) {
                throw new IllegalArgumentException("Resource operations must use the frozen enum order");
            }
        }
        return List.copyOf(values);
    }

    private static void validateAvailability(Availability availability, List<Operation> operations) {
        if (availability.state() == AvailabilityState.UNAVAILABLE
            && operations.stream().anyMatch(operation -> operation.state() == OperationState.AVAILABLE)) {
            throw new IllegalArgumentException("An unavailable resource cannot advertise available operations");
        }
        if (availability.state() == AvailabilityState.READ_ONLY) {
            Set<ResourceOperationKind> mutations = Set.of(ResourceOperationKind.CREATE, ResourceOperationKind.SAVE,
                ResourceOperationKind.RENAME, ResourceOperationKind.MOVE, ResourceOperationKind.DUPLICATE,
                ResourceOperationKind.ACTIVATE, ResourceOperationKind.DELETE);
            if (operations.stream().anyMatch(operation -> mutations.contains(operation.operation())
                && operation.state() == OperationState.AVAILABLE)) {
                throw new IllegalArgumentException("A read-only resource cannot advertise mutating operations");
            }
        }
    }

    public enum AvailabilityState {
        AVAILABLE("available"),
        READ_ONLY("read-only"),
        UNAVAILABLE("unavailable");

        private final String wireName;

        AvailabilityState(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static AvailabilityState fromWireName(String value) {
            for (AvailabilityState candidate : values()) {
                if (candidate.wireName.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("Unknown resource availability state: " + value);
        }
    }

    public enum OperationState {
        AVAILABLE("available"),
        UNAVAILABLE("unavailable");

        private final String wireName;

        OperationState(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static OperationState fromWireName(String value) {
            for (OperationState candidate : values()) {
                if (candidate.wireName.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("Unknown resource operation state: " + value);
        }
    }

    public record Availability(AvailabilityState state, String reason, Map<String, Object> unknown) {
        public Availability {
            state = Objects.requireNonNull(state, "Resource availability state is required");
            reason = ResourceManagementDescriptor.reason(state != AvailabilityState.AVAILABLE, reason,
                "resource availability");
            unknown = IdentitySupport.unknown(unknown, "resource availability unknown data");
        }

        public Availability(AvailabilityState state, String reason) {
            this(state, reason, Map.of());
        }

        public static Availability available() {
            return new Availability(AvailabilityState.AVAILABLE, null);
        }
    }

    public record Operation(ResourceOperationKind operation, OperationState state, String reason,
                            Map<String, Object> unknown) {
        public Operation {
            operation = Objects.requireNonNull(operation, "Resource operation kind is required");
            state = Objects.requireNonNull(state, "Resource operation state is required");
            reason = ResourceManagementDescriptor.reason(state == OperationState.UNAVAILABLE, reason,
                "resource operation");
            unknown = IdentitySupport.unknown(unknown, "resource operation unknown data");
        }

        public Operation(ResourceOperationKind operation, OperationState state, String reason) {
            this(operation, state, reason, Map.of());
        }

        public static Operation available(ResourceOperationKind operation) {
            return new Operation(operation, OperationState.AVAILABLE, null);
        }
    }

    public record ServerAuthoring(String kind, int version, ContractRef<CapabilityId> capability, List<Input> inputs,
                                  Map<String, Object> unknown) {
        public static final String RESOURCE_KIND = "resource";
        public static final int VERSION = 1;

        public ServerAuthoring {
            if (!RESOURCE_KIND.equals(kind)) {
                throw new IllegalArgumentException("Unsupported server authoring kind");
            }
            if (version != VERSION) {
                throw new IllegalArgumentException("Unsupported server authoring version");
            }
            capability = Objects.requireNonNull(capability, "Server authoring capability is required");
            inputs = inputs == null ? List.of() : List.copyOf(inputs);
            if (inputs.size() > MAX_INPUTS) {
                throw new IllegalArgumentException("Server authoring contains too many inputs");
            }
            Set<InspectorFieldId> ids = new HashSet<>();
            for (Input input : inputs) {
                Input checked = Objects.requireNonNull(input, "Server authoring input is required");
                if (!ids.add(checked.id())) {
                    throw new IllegalArgumentException("Duplicate server authoring input: " + checked.id());
                }
            }
            unknown = IdentitySupport.unknown(unknown, "server authoring unknown data");
        }

        public ServerAuthoring(ContractRef<CapabilityId> capability, List<Input> inputs, Map<String, ?> unknown) {
            this(RESOURCE_KIND, VERSION, capability, inputs, IdentitySupport.unknown(unknown, "server authoring unknown data"));
        }

        public ServerAuthoring(ContractRef<CapabilityId> capability, List<Input> inputs) {
            this(capability, inputs, Map.of());
        }
    }

    public record Input(InspectorFieldId id, TypeExpr type, boolean required, ContractRef<CapabilityId> editor,
                        TypedValue defaultValue, Map<String, Object> unknown) {
        public Input {
            id = Objects.requireNonNull(id, "Server authoring input ID is required");
            type = Objects.requireNonNull(type, "Server authoring input type is required");
            editor = Objects.requireNonNull(editor, "Server authoring input editor is required");
            if (defaultValue != null && (defaultValue.state() == TypedValue.State.ABSENT
                || defaultValue.state() == TypedValue.State.NULL && !(type instanceof TypeExpr.OptionalType)
                || !type.canonicalJson().equals(defaultValue.type().canonicalJson()))) {
                throw new IllegalArgumentException("Server authoring input default must be present and exactly match its type");
            }
            unknown = IdentitySupport.unknown(unknown, "server authoring input unknown data");
        }

        public Input(InspectorFieldId id, TypeExpr type, boolean required, ContractRef<CapabilityId> editor,
                     TypedValue defaultValue) {
            this(id, type, required, editor, defaultValue, Map.of());
        }
    }

    private static String reason(boolean required, String value, String label) {
        if (!required) {
            if (value != null) {
                throw new IllegalArgumentException("Available " + label + " must omit its reason");
            }
            return null;
        }
        Objects.requireNonNull(value, label + " reason is required");
        if (value.isBlank() || value.length() > 240) {
            throw new IllegalArgumentException("Invalid " + label + " reason");
        }
        return value;
    }
}
