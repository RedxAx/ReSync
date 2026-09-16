package restudio.resync.flow.catalog;

import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.IdentitySupport;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record CatalogMigrationEdge(CapabilityId id,
                                   int fromVersion,
                                   int toVersion,
                                   Kind kind,
                                   List<String> touchedIds,
                                   ConnectionPolicy connectionPolicy,
                                   List<String> quarantineCodes,
                                   List<PinMapping> pinMappings,
                                   Map<String, Object> unknown,
                                   OwnerId ownerId,
                                   NodeId nodeId) {
    private static final Set<String> KNOWN_UNKNOWN_FIELDS = Set.of(
        "kind", "id", "fromVersion", "toVersion", "touchedIds", "connectionPolicy", "quarantineCodes",
        "pinMappings", "ownerId", "nodeId");

    public CatalogMigrationEdge(CapabilityId id,
                                int fromVersion,
                                int toVersion,
                                Kind kind,
                                List<String> touchedIds,
                                ConnectionPolicy connectionPolicy,
                                List<String> quarantineCodes) {
        this(id, fromVersion, toVersion, kind, touchedIds, connectionPolicy, quarantineCodes, List.of(), Map.of(), null, null);
    }

    public CatalogMigrationEdge(CapabilityId id,
                                int fromVersion,
                                int toVersion,
                                Kind kind,
                                List<String> touchedIds,
                                ConnectionPolicy connectionPolicy,
                                List<String> quarantineCodes,
                                List<PinMapping> pinMappings) {
        this(id, fromVersion, toVersion, kind, touchedIds, connectionPolicy, quarantineCodes, pinMappings, Map.of(), null, null);
    }

    public CatalogMigrationEdge(CapabilityId id,
                                int fromVersion,
                                int toVersion,
                                Kind kind,
                                List<String> touchedIds,
                                ConnectionPolicy connectionPolicy,
                                List<String> quarantineCodes,
                                List<PinMapping> pinMappings,
                                Map<String, ?> unknown) {
        this(id, fromVersion, toVersion, kind, touchedIds, connectionPolicy, quarantineCodes, pinMappings,
            IdentitySupport.unknown(unknown, "migration edge unknown data"), null, null);
    }

    public CatalogMigrationEdge(CapabilityId id,
                                int fromVersion,
                                int toVersion,
                                Kind kind,
                                List<String> touchedIds,
                                ConnectionPolicy connectionPolicy,
                                List<String> quarantineCodes,
                                List<PinMapping> pinMappings,
                                OwnerId ownerId,
                                NodeId nodeId) {
        this(id, fromVersion, toVersion, kind, touchedIds, connectionPolicy, quarantineCodes, pinMappings, Map.of(), ownerId, nodeId);
    }

    public CatalogMigrationEdge(CapabilityId id,
                                OwnerId ownerId,
                                NodeId nodeId,
                                int sourceSchemaVersion,
                                int targetSchemaVersion,
                                Kind kind,
                                List<String> touchedIds,
                                ConnectionPolicy connectionPolicy,
                                List<String> quarantineCodes,
                                List<PinMapping> pinMappings) {
        this(id, sourceSchemaVersion, targetSchemaVersion, kind, touchedIds, connectionPolicy, quarantineCodes, pinMappings, Map.of(), ownerId, nodeId);
    }

    public CatalogMigrationEdge {
        id = Objects.requireNonNull(id, "migrationId");
        if (fromVersion < 1 || toVersion < 1) {
            throw new IllegalArgumentException("Migration versions must be positive");
        }
        kind = Objects.requireNonNull(kind, "kind");
        touchedIds = touchedIds == null ? List.of() : touchedIds.stream().map(value -> CatalogIds.local(value, "touchedId")).toList();
        connectionPolicy = Objects.requireNonNull(connectionPolicy, "connectionPolicy");
        quarantineCodes = quarantineCodes == null ? List.of() : quarantineCodes.stream().map(value -> CatalogIds.required(value, "quarantineCode")).toList();
        pinMappings = immutableMappings(pinMappings);
        unknown = IdentitySupport.unknown(unknown, "migration edge unknown data");
        rejectUnknownCollisions(unknown, KNOWN_UNKNOWN_FIELDS, "migration edge");
        if ((ownerId == null) != (nodeId == null)) {
            throw new IllegalArgumentException("Migration edge owner and node scope must be supplied together");
        }
        if (ownerId != null) {
            for (PinMapping mapping : pinMappings) {
                if (!ownerId.equals(mapping.ownerId()) || !nodeId.equals(mapping.nodeId())
                    || fromVersion != mapping.sourceSchemaVersion() || toVersion != mapping.targetSchemaVersion()) {
                    throw new IllegalArgumentException("Pin mapping scope does not match the migration edge scope");
                }
            }
        }
    }

    public ContractRef<CapabilityId> reference(OwnerId owner) {
        return ContractRef.of(Objects.requireNonNull(owner, "owner"), id);
    }

    public int sourceSchemaVersion() {
        return fromVersion;
    }

    public int targetSchemaVersion() {
        return toVersion;
    }

    public List<PinMapping> mappings() {
        return pinMappings;
    }

    public List<PinMapping> typedPinMappings() {
        return pinMappings;
    }

    private static List<PinMapping> immutableMappings(List<PinMapping> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<PinMapping> sorted = new ArrayList<>(values.size());
        Set<DirectionalPin> sources = new HashSet<>();
        Set<DirectionalPin> targets = new HashSet<>();
        for (PinMapping mapping : values) {
            PinMapping value = Objects.requireNonNull(mapping, "pin mapping");
            if (!sources.add(new DirectionalPin(value.direction(), value.source().canonicalText()))) {
                throw new IllegalArgumentException("Pin mappings cannot duplicate a source pin");
            }
            if (!targets.add(new DirectionalPin(value.direction(), value.target().canonicalText()))) {
                throw new IllegalArgumentException("Pin mappings cannot duplicate a target pin");
            }
            sorted.add(value);
        }
        sorted.sort(Comparator.comparing(PinMapping::canonicalOrderKey));
        return List.copyOf(sorted);
    }

    private static void rejectUnknownCollisions(Map<String, Object> values, Set<String> known, String name) {
        for (String key : values.keySet()) {
            if (known.contains(key)) {
                throw new IllegalArgumentException("Unknown data collides with known " + name + " field: " + key);
            }
        }
    }

    private record DirectionalPin(CatalogNodeDescriptor.Direction direction, String value) {
    }

    public enum Kind {
        DECLARATIVE,
        OFFLINE_ADAPTER
    }

    public enum ConnectionPolicy {
        PRESERVE,
        REMAP,
        SPLIT,
        QUARANTINE
    }

    public record LegacyPinId(String value) {
        public LegacyPinId {
            Objects.requireNonNull(value, "Legacy pin ID is required");
            if (value.isBlank() || value.length() > 128 || ".".equals(value) || "..".equals(value)) {
                throw new IllegalArgumentException("Legacy pin ID must be a nonblank safe token");
            }
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                boolean letter = character >= 'A' && character <= 'Z' || character >= 'a' && character <= 'z';
                boolean digit = character >= '0' && character <= '9';
                if (index == 0 && !letter || index > 0 && !letter && !digit && character != '_' && character != '-' && character != '.') {
                    throw new IllegalArgumentException("Legacy pin ID must be a nonblank safe token");
                }
                if (Character.isWhitespace(character) || Character.isISOControl(character)) {
                    throw new IllegalArgumentException("Legacy pin ID must not contain whitespace or control characters");
                }
            }
        }

        public static LegacyPinId of(String value) {
            return new LegacyPinId(value);
        }

        public String canonicalText() {
            return value;
        }

        @Override
        public String toString() {
            return value;
        }
    }

    public record PinScope(OwnerId ownerId,
                           NodeId nodeId,
                           int sourceSchemaVersion,
                           int targetSchemaVersion) {
        public PinScope {
            ownerId = Objects.requireNonNull(ownerId, "ownerId");
            nodeId = Objects.requireNonNull(nodeId, "nodeId");
            if (sourceSchemaVersion < 1 || targetSchemaVersion < 1) {
                throw new IllegalArgumentException("Pin mapping schema versions must be positive");
            }
            if (targetSchemaVersion <= sourceSchemaVersion) {
                throw new IllegalArgumentException("Pin mapping target schema version must advance the source version");
            }
        }

        public PinScope(String ownerId, String nodeId, int sourceSchemaVersion, int targetSchemaVersion) {
            this(OwnerId.of(ownerId), NodeId.of(nodeId), sourceSchemaVersion, targetSchemaVersion);
        }

        public OwnerId owner() {
            return ownerId;
        }

        public NodeId node() {
            return nodeId;
        }

        public int fromVersion() {
            return sourceSchemaVersion;
        }

        public int toVersion() {
            return targetSchemaVersion;
        }

        private String canonicalOrderKey() {
            return ownerId.canonicalText() + '\u0000' + nodeId.canonicalText() + '\u0000'
                + sourceSchemaVersion + '\u0000' + targetSchemaVersion;
        }
    }

    public record PinMapping(PinScope scope,
                             LegacyPinId source,
                             PinId target,
                             CatalogNodeDescriptor.Direction direction,
                             boolean identityMeaningful,
                             Map<String, Object> unknown) {
        private static final Set<String> KNOWN_UNKNOWN_FIELDS = Set.of(
            "kind", "ownerId", "nodeId", "sourceSchemaVersion", "targetSchemaVersion", "sourcePinId",
            "targetPinId", "direction", "identityMeaningful");

        public PinMapping(PinScope scope,
                          LegacyPinId source,
                          PinId target,
                          CatalogNodeDescriptor.Direction direction) {
            this(scope, source, target, direction, false, Map.of());
        }

        public PinMapping(PinScope scope,
                          PinId source,
                          PinId target,
                          CatalogNodeDescriptor.Direction direction) {
            this(scope, LegacyPinId.of(source.value()), target, direction, false, Map.of());
        }

        public PinMapping(PinScope scope,
                          LegacyPinId source,
                          PinId target,
                          CatalogNodeDescriptor.Direction direction,
                          boolean identityMeaningful) {
            this(scope, source, target, direction, identityMeaningful, Map.of());
        }

        public PinMapping(PinScope scope,
                          PinId source,
                          PinId target,
                          CatalogNodeDescriptor.Direction direction,
                          boolean identityMeaningful) {
            this(scope, LegacyPinId.of(source.value()), target, direction, identityMeaningful, Map.of());
        }

        public PinMapping(OwnerId ownerId,
                          NodeId nodeId,
                          int sourceSchemaVersion,
                          int targetSchemaVersion,
                          LegacyPinId source,
                          PinId target,
                          CatalogNodeDescriptor.Direction direction) {
            this(new PinScope(ownerId, nodeId, sourceSchemaVersion, targetSchemaVersion), source, target, direction);
        }

        public PinMapping(OwnerId ownerId,
                          NodeId nodeId,
                          int sourceSchemaVersion,
                          int targetSchemaVersion,
                          PinId source,
                          PinId target,
                          CatalogNodeDescriptor.Direction direction) {
            this(new PinScope(ownerId, nodeId, sourceSchemaVersion, targetSchemaVersion),
                LegacyPinId.of(source.value()), target, direction);
        }

        public PinMapping(OwnerId ownerId,
                          NodeId nodeId,
                          int sourceSchemaVersion,
                          int targetSchemaVersion,
                          LegacyPinId source,
                          PinId target,
                          CatalogNodeDescriptor.Direction direction,
                          boolean identityMeaningful) {
            this(new PinScope(ownerId, nodeId, sourceSchemaVersion, targetSchemaVersion), source, target, direction, identityMeaningful);
        }

        public PinMapping(OwnerId ownerId,
                          NodeId nodeId,
                          int sourceSchemaVersion,
                          int targetSchemaVersion,
                          PinId source,
                          PinId target,
                          CatalogNodeDescriptor.Direction direction,
                          boolean identityMeaningful) {
            this(new PinScope(ownerId, nodeId, sourceSchemaVersion, targetSchemaVersion),
                LegacyPinId.of(source.value()), target, direction, identityMeaningful);
        }

        public PinMapping(String ownerId,
                          String nodeId,
                          int sourceSchemaVersion,
                          int targetSchemaVersion,
                          String source,
                          String target,
                          CatalogNodeDescriptor.Direction direction) {
            this(OwnerId.of(ownerId), NodeId.of(nodeId), sourceSchemaVersion, targetSchemaVersion,
                LegacyPinId.of(source), PinId.of(target), direction);
        }

        public PinMapping(OwnerId ownerId,
                          NodeId nodeId,
                          int sourceSchemaVersion,
                          int targetSchemaVersion,
                          LegacyPinId source,
                          PinId target,
                          Direction direction) {
            this(ownerId, nodeId, sourceSchemaVersion, targetSchemaVersion, source, target, direction.catalogDirection());
        }

        public PinMapping(OwnerId ownerId,
                          NodeId nodeId,
                          int sourceSchemaVersion,
                          int targetSchemaVersion,
                          PinId source,
                          PinId target,
                          Direction direction) {
            this(ownerId, nodeId, sourceSchemaVersion, targetSchemaVersion,
                LegacyPinId.of(source.value()), target, direction.catalogDirection());
        }

        public PinMapping {
            scope = Objects.requireNonNull(scope, "pin mapping scope");
            source = Objects.requireNonNull(source, "source pin");
            target = Objects.requireNonNull(target, "target pin");
            direction = Objects.requireNonNull(direction, "direction");
            if (source.value().equals(target.value()) && !identityMeaningful) {
                throw new IllegalArgumentException("Identity pin mappings require explicit meaning");
            }
            unknown = IdentitySupport.unknown(unknown, "pin mapping unknown data");
            rejectUnknownCollisions(unknown, KNOWN_UNKNOWN_FIELDS, "pin mapping");
        }

        public OwnerId ownerId() {
            return scope.ownerId();
        }

        public OwnerId owner() {
            return scope.ownerId();
        }

        public NodeId nodeId() {
            return scope.nodeId();
        }

        public NodeId node() {
            return scope.nodeId();
        }

        public int sourceSchemaVersion() {
            return scope.sourceSchemaVersion();
        }

        public int targetSchemaVersion() {
            return scope.targetSchemaVersion();
        }

        public int fromVersion() {
            return sourceSchemaVersion();
        }

        public int toVersion() {
            return targetSchemaVersion();
        }

        public LegacyPinId sourcePinId() {
            return source;
        }

        public PinId targetPinId() {
            return target;
        }

        public boolean identity() {
            return identityMeaningful;
        }

        public PinMapping withScope(PinScope value) {
            return new PinMapping(value, source, target, direction, identityMeaningful, unknown);
        }

        private String canonicalOrderKey() {
            return scope.canonicalOrderKey() + '\u0000' + direction.name() + '\u0000'
                + source.canonicalText() + '\u0000' + target.canonicalText() + '\u0000' + identityMeaningful;
        }

        public enum Direction {
            INPUT,
            OUTPUT;

            private CatalogNodeDescriptor.Direction catalogDirection() {
                return CatalogNodeDescriptor.Direction.valueOf(name());
            }
        }
    }
}
