package restudio.resync.flow.resource;

import org.junit.jupiter.api.Test;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.protocol.ResourceOperationKind;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResourceManagementDescriptorCodecTest {
    private static final ContractRef<CapabilityId> CAPABILITY = new ContractRef<>(new OwnerId("restudio"),
        CapabilityId.of("world-authoring"), Map.of("capabilityFuture", true));
    private static final TypeExpr TEXT = new TypeExpr.Named(new TypeReference("builtin", "string",
        Map.of("typeReferenceFuture", true)), List.of(), Map.of("typeFuture", true));

    @Test
    void fullDescriptorRoundTripsEverySupportedUnknownBoundary() {
        ResourceManagementDescriptor descriptor = descriptor();

        byte[] encoded = ResourceManagementDescriptorCodec.INSTANCE.encodeBytes(descriptor);
        ResourceManagementDescriptor decoded = ResourceManagementDescriptorCodec.INSTANCE.decodeBytes(encoded);

        assertArrayEquals(encoded, ResourceManagementDescriptorCodec.INSTANCE.encodeBytes(decoded));
        assertEquals(true, decoded.unknown().get("descriptorFuture"));
        assertEquals(true, decoded.resourceType().unknown().get("resourceTypeFuture"));
        assertEquals(true, decoded.payloadType().unknown().get("payloadTypeFuture"));
        assertEquals(true, decoded.availability().unknown().get("availabilityFuture"));
        assertEquals(true, decoded.operations().getFirst().unknown().get("operationFuture"));
        assertEquals(true, decoded.serverAuthoring().unknown().get("authoringFuture"));
        assertEquals(true, decoded.serverAuthoring().capability().unknown().get("capabilityFuture"));
        assertEquals(true, decoded.serverAuthoring().inputs().getFirst().unknown().get("inputFuture"));
        assertEquals(true, decoded.serverAuthoring().inputs().getFirst().type().unknown().get("typeFuture"));
        assertEquals(true, decoded.serverAuthoring().inputs().getFirst().defaultValue().unknown().get("defaultFuture"));
    }

    @Test
    void checksumCoversFullCanonicalDescriptor() {
        ResourceManagementDescriptor first = descriptor();
        List<ResourceManagementDescriptor.Operation> changed = new ArrayList<>(first.operations());
        changed.set(ResourceOperationKind.QUERY.ordinal(), new ResourceManagementDescriptor.Operation(
            ResourceOperationKind.QUERY, ResourceManagementDescriptor.OperationState.UNAVAILABLE, "Query is disabled."));
        ResourceManagementDescriptor second = new ResourceManagementDescriptor(first.resourceType(), first.payloadType(),
            first.availability(), changed, first.serverAuthoring(), first.unknown());

        assertNotEquals(first.checksum(), second.checksum());
    }

    @Test
    void operationsMustContainTheExactFrozenSequence() {
        JsonValue.JsonObject encoded = ResourceManagementDescriptorCodec.INSTANCE.encode(descriptor());
        List<JsonValue> operations = new ArrayList<>(((JsonValue.JsonArray) encoded.value("operations")).values());

        assertMalformedOperations(encoded, operations.subList(0, operations.size() - 1));

        List<JsonValue> duplicate = new ArrayList<>(operations);
        duplicate.set(1, duplicate.getFirst());
        assertMalformedOperations(encoded, duplicate);

        List<JsonValue> extra = new ArrayList<>(operations);
        extra.add(operations.getFirst());
        assertMalformedOperations(encoded, extra);

        List<JsonValue> reordered = new ArrayList<>(operations);
        JsonValue first = reordered.get(0);
        reordered.set(0, reordered.get(1));
        reordered.set(1, first);
        assertMalformedOperations(encoded, reordered);
    }

    @Test
    void semanticVariantsAndVersionsFailClosed() {
        JsonValue.JsonObject encoded = ResourceManagementDescriptorCodec.INSTANCE.encode(descriptor());
        Map<String, JsonValue> futureKind = new LinkedHashMap<>(encoded.fields());
        futureKind.put("kind", JsonValue.of("future"));
        assertThrows(IllegalArgumentException.class,
            () -> ResourceManagementDescriptorCodec.INSTANCE.decode(JsonValue.object(futureKind)));

        Map<String, JsonValue> futureVersion = new LinkedHashMap<>(encoded.fields());
        futureVersion.put("version", JsonValue.fromJava(2));
        assertThrows(IllegalArgumentException.class,
            () -> ResourceManagementDescriptorCodec.INSTANCE.decode(JsonValue.object(futureVersion)));

        List<JsonValue> operations = new ArrayList<>(((JsonValue.JsonArray) encoded.value("operations")).values());
        JsonValue.JsonObject first = (JsonValue.JsonObject) operations.getFirst();
        Map<String, JsonValue> futureState = new LinkedHashMap<>(first.fields());
        futureState.put("state", JsonValue.of("future"));
        operations.set(0, JsonValue.object(futureState));
        assertMalformedOperations(encoded, operations);

        JsonValue.JsonObject authoring = (JsonValue.JsonObject) encoded.value("serverAuthoring");
        Map<String, JsonValue> futureAuthoringKind = new LinkedHashMap<>(authoring.fields());
        futureAuthoringKind.put("kind", JsonValue.of("future"));
        assertMalformedAuthoring(encoded, futureAuthoringKind);

        Map<String, JsonValue> futureAuthoringVersion = new LinkedHashMap<>(authoring.fields());
        futureAuthoringVersion.put("version", JsonValue.fromJava(2));
        assertMalformedAuthoring(encoded, futureAuthoringVersion);
    }

    @Test
    void inputBoundsAndNullableDefaultsFailClosedBeforeAdmission() {
        ResourceManagementDescriptor.Input template = new ResourceManagementDescriptor.Input(InspectorFieldId.of("name"),
            TEXT, true, ContractRef.of(new OwnerId("restudio"), CapabilityId.of("text-editor")), null);
        List<ResourceManagementDescriptor.Input> tooMany = new ArrayList<>();
        for (int index = 0; index <= ResourceManagementDescriptor.MAX_INPUTS; index++) {
            tooMany.add(new ResourceManagementDescriptor.Input(InspectorFieldId.of("input-" + index), TEXT, true,
                template.editor(), null));
        }
        assertThrows(IllegalArgumentException.class,
            () -> new ResourceManagementDescriptor.ServerAuthoring(CAPABILITY, tooMany));

        JsonValue.JsonObject encoded = ResourceManagementDescriptorCodec.INSTANCE.encode(descriptor());
        JsonValue.JsonObject authoring = (JsonValue.JsonObject) encoded.value("serverAuthoring");
        JsonValue input = ((JsonValue.JsonArray) authoring.value("inputs")).values().getFirst();
        List<JsonValue> oversizedWire = new ArrayList<>();
        for (int index = 0; index <= ResourceManagementDescriptor.MAX_INPUTS; index++) {
            oversizedWire.add(input);
        }
        Map<String, JsonValue> oversizedAuthoring = new LinkedHashMap<>(authoring.fields());
        oversizedAuthoring.put("inputs", JsonValue.array(oversizedWire));
        assertMalformedAuthoring(encoded, oversizedAuthoring);

        TypeExpr optionalText = TypeExpr.optional(TEXT);
        ResourceManagementDescriptor.Input nullable = new ResourceManagementDescriptor.Input(InspectorFieldId.of("note"),
            optionalText, false, template.editor(), TypedValue.nullValue(optionalText));
        assertEquals(TypedValue.State.NULL, nullable.defaultValue().state());
        assertThrows(IllegalArgumentException.class, () -> new ResourceManagementDescriptor.Input(
            InspectorFieldId.of("invalid-null"), TEXT, false, template.editor(), TypedValue.nullValue(TEXT)));
    }

    @Test
    void overallAvailabilityRejectsContradictoryOperationsWithoutInferringCreateFromAuthoring() {
        List<ResourceManagementDescriptor.Operation> allAvailable = operations(true);
        assertThrows(IllegalArgumentException.class, () -> new ResourceManagementDescriptor(resourceType(), payloadType(),
            new ResourceManagementDescriptor.Availability(ResourceManagementDescriptor.AvailabilityState.UNAVAILABLE,
                "Resource provider is offline."), allAvailable, authoring()));

        List<ResourceManagementDescriptor.Operation> readOnly = operations(false);
        readOnly.set(ResourceOperationKind.SAVE.ordinal(), ResourceManagementDescriptor.Operation.available(ResourceOperationKind.SAVE));
        assertThrows(IllegalArgumentException.class, () -> new ResourceManagementDescriptor(resourceType(), payloadType(),
            new ResourceManagementDescriptor.Availability(ResourceManagementDescriptor.AvailabilityState.READ_ONLY,
                "Resource mutation is disabled."), readOnly, authoring()));

        List<ResourceManagementDescriptor.Operation> authoringWithoutCreate = operations(true);
        authoringWithoutCreate.set(ResourceOperationKind.CREATE.ordinal(), new ResourceManagementDescriptor.Operation(
            ResourceOperationKind.CREATE, ResourceManagementDescriptor.OperationState.UNAVAILABLE, "Creation is disabled."));
        ResourceManagementDescriptor accepted = new ResourceManagementDescriptor(resourceType(), payloadType(),
            ResourceManagementDescriptor.Availability.available(), authoringWithoutCreate, authoring());
        assertEquals(ResourceManagementDescriptor.OperationState.UNAVAILABLE,
            accepted.operations().get(ResourceOperationKind.CREATE.ordinal()).state());
    }

    private static ResourceManagementDescriptor descriptor() {
        List<ResourceManagementDescriptor.Operation> operations = operations(true);
        operations.set(0, new ResourceManagementDescriptor.Operation(ResourceOperationKind.LIST,
            ResourceManagementDescriptor.OperationState.AVAILABLE, null, Map.of("operationFuture", true)));
        operations.set(ResourceOperationKind.DELETE.ordinal(), new ResourceManagementDescriptor.Operation(
            ResourceOperationKind.DELETE, ResourceManagementDescriptor.OperationState.UNAVAILABLE,
            "Deletion is managed externally."));
        return new ResourceManagementDescriptor(resourceType(), payloadType(),
            new ResourceManagementDescriptor.Availability(ResourceManagementDescriptor.AvailabilityState.AVAILABLE,
                null, Map.of("availabilityFuture", true)), operations, authoring(), Map.of("descriptorFuture", true));
    }

    private static ContractRef<ResourceTypeId> resourceType() {
        return new ContractRef<>(new OwnerId("restudio"), ResourceTypeId.of("world"),
            Map.of("resourceTypeFuture", true));
    }

    private static TypeReference payloadType() {
        return new TypeReference("restudio", "world-document", Map.of("payloadTypeFuture", true));
    }

    private static ResourceManagementDescriptor.ServerAuthoring authoring() {
        ResourceManagementDescriptor.Input input = new ResourceManagementDescriptor.Input(InspectorFieldId.of("name"),
            TEXT, true, ContractRef.of(new OwnerId("restudio"), CapabilityId.of("text-editor")),
            TypedValue.value(TEXT, "Spawn", Map.of("defaultFuture", true)), Map.of("inputFuture", true));
        return new ResourceManagementDescriptor.ServerAuthoring(CAPABILITY, List.of(input), Map.of("authoringFuture", true));
    }

    private static List<ResourceManagementDescriptor.Operation> operations(boolean available) {
        List<ResourceManagementDescriptor.Operation> result = new ArrayList<>();
        for (ResourceOperationKind operation : ResourceOperationKind.values()) {
            result.add(available ? ResourceManagementDescriptor.Operation.available(operation)
                : new ResourceManagementDescriptor.Operation(operation, ResourceManagementDescriptor.OperationState.UNAVAILABLE,
                "Operation is unavailable."));
        }
        return result;
    }

    private static void assertMalformedOperations(JsonValue.JsonObject encoded, List<JsonValue> operations) {
        Map<String, JsonValue> malformed = new LinkedHashMap<>(encoded.fields());
        malformed.put("operations", JsonValue.array(operations));
        assertThrows(IllegalArgumentException.class,
            () -> ResourceManagementDescriptorCodec.INSTANCE.decode(JsonValue.object(malformed)));
    }

    private static void assertMalformedAuthoring(JsonValue.JsonObject encoded, Map<String, JsonValue> authoring) {
        Map<String, JsonValue> malformed = new LinkedHashMap<>(encoded.fields());
        malformed.put("serverAuthoring", JsonValue.object(authoring));
        assertThrows(IllegalArgumentException.class,
            () -> ResourceManagementDescriptorCodec.INSTANCE.decode(JsonValue.object(malformed)));
    }
}
