package restudio.resync.flow.runtime;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.diagnostic.DiagnosticMetricPolicy;
import restudio.resync.flow.diagnostic.DiagnosticPhase;
import restudio.resync.flow.diagnostic.DiagnosticProvenance;
import restudio.resync.flow.diagnostic.DiagnosticRedaction;
import restudio.resync.flow.diagnostic.DiagnosticSeverity;
import restudio.resync.flow.diagnostic.DiagnosticSourceKind;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.identity.ResourceKey;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

final class RuntimeCanonicalDecoder {
    private RuntimeCanonicalDecoder() {
    }

    static <T> T parse(String value, Function<Map<String, Object>, T> decoder) {
        Objects.requireNonNull(value, "Canonical value is required");
        return decoder.apply(object(CanonicalJson.parse(value), "Canonical value"));
    }

    static RuntimeCapabilityDescriptor capability(Map<String, Object> value) {
        return new RuntimeCapabilityDescriptor(
            contractRef(value.get("capability"), "capability", CapabilityId::of),
            array(value.get("operations"), "operations").stream().map(item -> operation(object(item, "operation"))).toList(),
            unknown(value, Set.of("capability", "operations"), "capability"));
    }

    static RuntimeOperationDescriptor operation(Map<String, Object> value) {
        return new RuntimeOperationDescriptor(
            contractRef(value.get("capability"), "capability", CapabilityId::of),
            contractRef(value.get("operation"), "operation", OperationId::of),
            types(value.get("inputs"), "inputs"),
            types(value.get("outputs"), "outputs"),
            semantics(object(value.get("semantics"), "semantics")),
            unknown(value, Set.of("capability", "operation", "inputs", "outputs", "pins", "semantics"), "operation"),
            pins(value.get("pins"), "pins"));
    }

    static RuntimeProviderDescriptor provider(Map<String, Object> value) {
        return new RuntimeProviderDescriptor(
            contractRef(value.get("provider"), "provider", ProviderId::of),
            text(value.get("version"), "version"),
            enumValue(value.get("state"), RuntimeProviderState.values(), RuntimeProviderState::wireValue, "state"),
            integer(value.get("drainDeadlineMillis"), "drainDeadlineMillis"),
            integer(value.get("hardDeadlineMillis"), "hardDeadlineMillis"),
            enumValue(value.get("unloadPolicy"), RuntimeSemantics.UnloadPolicy.values(), RuntimeSemantics.UnloadPolicy::wireValue, "unloadPolicy"),
            unknown(value, Set.of("provider", "version", "state", "drainDeadlineMillis", "hardDeadlineMillis", "unloadPolicy"), "provider"));
    }

    static RuntimeBindingDescriptor binding(Map<String, Object> value) {
        return binding(value, null);
    }

    static RuntimeBindingDescriptor binding(Map<String, Object> value, ContentHash expectedFingerprint) {
        RuntimeBindingDescriptor descriptor = decodeBinding(value);
        ContentHash expected = expectedFingerprint == null ? descriptor.fingerprint() : expectedFingerprint;
        verifyBindingFingerprint(value, expected);
        return descriptor;
    }

    private static RuntimeBindingDescriptor decodeBinding(Map<String, Object> value) {
        return new RuntimeBindingDescriptor(
            contractRef(value.get("capability"), "capability", CapabilityId::of),
            contractRef(value.get("operation"), "operation", OperationId::of),
            contractRef(value.get("provider"), "provider", ProviderId::of),
            text(value.get("providerVersion"), "providerVersion"),
            types(value.get("inputs"), "inputs"),
            types(value.get("outputs"), "outputs"),
            semantics(object(value.get("semantics"), "semantics")),
            booleanValue(value.get("available"), "available"),
            unknown(value, Set.of("capability", "operation", "provider", "providerVersion", "inputs", "outputs", "pins", "semantics", "available", "fingerprint"), "binding"),
            pins(value.get("pins"), "pins"));
    }

    private static void verifyBindingFingerprint(Map<String, Object> value, ContentHash expected) {
        Object rawFingerprint = value.get("fingerprint");
        if (rawFingerprint == null) {
            throw new IllegalArgumentException("Binding fingerprint is required");
        }
        ContentHash declared = new ContentHash(text(rawFingerprint, "fingerprint"));
        if (!declared.equals(expected)) {
            throw new IllegalArgumentException("Binding fingerprint does not match its execution material");
        }
    }

    static RuntimeFailureContract failureContract(Map<String, Object> value) {
        return new RuntimeFailureContract(
            type(value.get("payloadType")),
            strings(value.get("diagnosticCodes"), "diagnosticCodes"),
            strings(value.get("branches"), "branches"),
            enumValue(value.get("commitBoundary"), RuntimeFailureContract.CommitBoundary.values(), RuntimeFailureContract.CommitBoundary::wireValue, "commitBoundary"),
            unknown(value, Set.of("payloadType", "diagnosticCodes", "branches", "commitBoundary"), "failureContract"));
    }

    static RuntimeSemantics semantics(Map<String, Object> value) {
        return new RuntimeSemantics(
            enumValue(value.get("effect"), RuntimeSemantics.Effect.values(), RuntimeSemantics.Effect::wireValue, "effect"),
            enumValue(value.get("thread"), RuntimeSemantics.ThreadMode.values(), RuntimeSemantics.ThreadMode::wireValue, "thread"),
            contractRef(value.get("authorization"), "authorization", CapabilityId::of),
            enumValue(value.get("cancellation"), RuntimeSemantics.Cancellation.values(), RuntimeSemantics.Cancellation::wireValue, "cancellation"),
            integer(value.get("timeoutMillis"), "timeoutMillis"),
            integer(value.get("drainDeadlineMillis"), "drainDeadlineMillis"),
            integer(value.get("hardDeadlineMillis"), "hardDeadlineMillis"),
            enumValue(value.get("unloadPolicy"), RuntimeSemantics.UnloadPolicy.values(), RuntimeSemantics.UnloadPolicy::wireValue, "unloadPolicy"),
            enumValue(value.get("retry"), RuntimeSemantics.Retry.values(), RuntimeSemantics.Retry::wireValue, "retry"),
            enumValue(value.get("idempotency"), RuntimeSemantics.Idempotency.values(), RuntimeSemantics.Idempotency::wireValue, "idempotency"),
            enumValue(value.get("audit"), RuntimeSemantics.Audit.values(), RuntimeSemantics.Audit::wireValue, "audit"),
            enumValue(value.get("confirmation"), RuntimeSemantics.Confirmation.values(), RuntimeSemantics.Confirmation::wireValue, "confirmation"),
            enumValue(value.get("sensitiveData"), RuntimeSemantics.SensitiveData.values(), RuntimeSemantics.SensitiveData::wireValue, "sensitiveData"),
            enumValue(value.get("determinism"), RuntimeSemantics.Determinism.values(), RuntimeSemantics.Determinism::wireValue, "determinism"),
            strings(value.get("success"), "success"),
            strings(value.get("failure"), "failure"),
            strings(value.get("cancelled"), "cancelled"),
            failureContract(object(value.get("failureContract"), "failureContract")),
            references(value.get("resourceReads"), "resourceReads", ResourceTypeId::of),
            references(value.get("resourceWrites"), "resourceWrites", ResourceTypeId::of),
            unknown(value, Set.of("effect", "thread", "authorization", "cancellation", "timeoutMillis", "drainDeadlineMillis", "hardDeadlineMillis", "unloadPolicy", "retry", "idempotency", "audit", "confirmation", "sensitiveData", "determinism", "success", "failure", "cancelled", "failureContract", "resourceReads", "resourceWrites"), "semantics"));
    }

    static RuntimeBindingManifest manifest(Map<String, Object> value) {
        String kind = text(value.get("kind"), "kind");
        if (!kind.equals("runtime-binding-manifest")) {
            throw new IllegalArgumentException("Unexpected runtime manifest kind: " + kind);
        }
        if (integer(value.get("version"), "version") != 1) {
            throw new IllegalArgumentException("Unsupported runtime manifest version");
        }
        List<RuntimeProviderDescriptor> providers = array(value.get("providers"), "providers").stream()
            .map(item -> provider(object(item, "provider"))).toList();
        Map<RuntimeBindingKey, ContentHash> fingerprints = new LinkedHashMap<>();
        object(value.get("executionFingerprints"), "executionFingerprints").forEach((key, item) ->
            fingerprints.put(bindingKey(key), new ContentHash(text(item, "executionFingerprint"))));
        Map<RuntimeBindingKey, Map<String, Object>> invalidationInputs = new LinkedHashMap<>();
        Object invalidationValue = value.get("invalidationInputs");
        if (invalidationValue != null) {
            object(invalidationValue, "invalidationInputs").forEach((key, item) ->
                invalidationInputs.put(bindingKey(key), object(item, "executionInvalidationInputs")));
        }
        List<RuntimeBindingDescriptor> bindings = array(value.get("bindings"), "bindings").stream()
            .map(item -> {
                Map<String, Object> bindingValue = object(item, "binding");
                RuntimeBindingDescriptor descriptor = decodeBinding(bindingValue);
                ContentHash expected = fingerprints.get(descriptor.key());
                if (expected == null) {
                    throw new IllegalArgumentException("Manifest is missing a binding execution fingerprint");
                }
                verifyBindingFingerprint(bindingValue, expected);
                return descriptor;
            }).toList();
        List<Map<String, Object>> diagnosticValues = array(value.get("diagnostics"), "diagnostics").stream()
            .map(item -> object(item, "diagnostic")).toList();
        List<Diagnostic> diagnostics = diagnosticValues.stream().map(RuntimeCanonicalDecoder::diagnostic).toList();
        RuntimeBindingManifest manifest = RuntimeBindingManifest.createDecoded(
            providers,
            bindings,
            fingerprints,
            diagnostics,
            diagnosticValues,
            unknown(value, Set.of("kind", "version", "providers", "bindings", "executionFingerprints", "invalidationInputs", "diagnostics", "bindingManifestHash"), "manifest"),
            invalidationInputs);
        if (!value.containsKey("bindingManifestHash")) {
            throw new IllegalArgumentException("Runtime manifest hash is required");
        }
        ContentHash declaredHash = new ContentHash(text(value.get("bindingManifestHash"), "bindingManifestHash"));
        if (!manifest.bindingManifestHash().equals(declaredHash)) {
            throw new IllegalArgumentException("Runtime manifest hash does not match its content");
        }
        return manifest;
    }

    static RuntimeBindingKey bindingKey(String value) {
        Objects.requireNonNull(value, "Binding key is required");
        String[] parts = value.split("#", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid runtime binding key: " + value);
        }
        return new RuntimeBindingKey(
            ContractRef.parseCanonicalText(parts[0], CapabilityId::of),
            ContractRef.parseCanonicalText(parts[1], OperationId::of));
    }

    static RuntimeResult result(Map<String, Object> value) {
        String status = text(value.get("status"), "result.status");
        String branch = optionalText(value, "branch");
        TypedValue scalar = value.containsKey("value") ? typedValue(value.get("value")) : null;
        Map<PinId, TypedValue> outputs = new LinkedHashMap<>();
        Object rawOutputs = value.get("outputs");
        if (rawOutputs != null) {
            object(rawOutputs, "result.outputs").forEach((key, item) -> outputs.put(new PinId(key), typedValue(item)));
        }
        RuntimeFailure failure = null;
        Object rawFailure = value.get("failure");
        if (rawFailure != null) {
            Map<String, Object> failureValue = object(rawFailure, "result.failure");
            failure = new RuntimeFailure(
                diagnostic(object(failureValue.get("diagnostic"), "result.failure.diagnostic")),
                booleanValue(failureValue.get("retryable"), "result.failure.retryable"),
                failureValue.containsKey("payload") ? typedValue(failureValue.get("payload")) : null);
        }
        RuntimeResult.Status selected = switch (status) {
            case "success" -> RuntimeResult.Status.SUCCESS;
            case "failure" -> RuntimeResult.Status.FAILURE;
            case "cancelled" -> RuntimeResult.Status.CANCELLED;
            default -> throw new IllegalArgumentException("Unknown result status: " + status);
        };
        return new RuntimeResult(selected, scalar, outputs, branch, failure);
    }

    private static TypedValue typedValue(Object raw) {
        Map<String, Object> value = object(raw, "typed value");
        TypeExpr type = type(value.get("type"));
        TypedValue.State state = switch (text(value.get("state"), "typed value.state")) {
            case "absent" -> TypedValue.State.ABSENT;
            case "null" -> TypedValue.State.NULL;
            case "value" -> TypedValue.State.VALUE;
            case "locator" -> TypedValue.State.LOCATOR;
            case "opaque" -> TypedValue.State.OPAQUE;
            default -> throw new IllegalArgumentException("Unknown typed value state");
        };
        String variant = optionalText(value, "variantId");
        Map<String, Object> unknown = unknown(value, Set.of("state", "type", "value", "locator", "variantId"), "typed value");
        return switch (state) {
            case ABSENT -> TypedValue.absent(type, unknown);
            case NULL -> TypedValue.nullValue(type, unknown);
            case LOCATOR -> {
                if (!(type instanceof TypeExpr.ResourceType)
                    && !(type instanceof TypeExpr.OptionalType)
                    && !(type instanceof TypeExpr.UnionType)) {
                    throw new IllegalArgumentException("Typed locator type is not resource-like");
                }
                if (variant != null && !(type instanceof TypeExpr.UnionType)) {
                    throw new IllegalArgumentException("Typed locator variant requires a union type");
                }
                ServerResourceLocator locator = resource(object(value.get("locator"), "typed value.locator"));
                yield variant == null ? TypedValue.locator(type, locator, unknown)
                    : TypedValue.unionLocator((TypeExpr.UnionType) type, variant, locator, unknown);
            }
            case OPAQUE -> {
                if (!(type instanceof TypeExpr.OpaqueType) && !(type instanceof TypeExpr.UnionType)) {
                    throw new IllegalArgumentException("Typed opaque value type is not opaque");
                }
                Object material = value.get("value");
                yield variant == null ? TypedValue.opaque((TypeExpr.OpaqueType) type, material, unknown)
                    : TypedValue.unionOpaque((TypeExpr.UnionType) type, variant, material, unknown);
            }
            case VALUE -> {
                Object material = normalizeMaterial(type, value.get("value"));
                if (variant != null && !(type instanceof TypeExpr.UnionType)) {
                    throw new IllegalArgumentException("Typed value variant requires a union type");
                }
                yield variant == null ? TypedValue.value(type, material, unknown)
                    : TypedValue.unionValue((TypeExpr.UnionType) type, variant, material, unknown);
            }
        };
    }

    private static Object normalizeMaterial(TypeExpr type, Object value) {
        if (type instanceof TypeExpr.UnionType union) {
            return value;
        }
        if (type instanceof TypeExpr.Named named && named.arguments().isEmpty()
            && "builtin".equals(named.reference().ownerId())) {
            return switch (named.reference().localId()) {
                case "integer" -> integerMaterial(value);
                case "number" -> decimalMaterial(value);
                case "uuid" -> value instanceof String text ? UUID.fromString(text) : value;
                default -> value;
            };
        }
        if (type instanceof TypeExpr.ResourceType) {
            return resource(object(value, "resource value"));
        }
        if (type instanceof TypeExpr.OptionalType optional) {
            return value == null ? null : normalizeMaterial(optional.element(), value);
        }
        if (type instanceof TypeExpr.ListType list && value instanceof List<?> values) {
            return values.stream().map(item -> item == null ? null : normalizeMaterial(list.element(), item)).toList();
        }
        if (type instanceof TypeExpr.TupleType tuple && value instanceof List<?> values) {
            if (tuple.elements().size() != values.size()) {
                throw new IllegalArgumentException("Tuple value length does not match its type");
            }
            List<Object> result = new ArrayList<>(values.size());
            for (int index = 0; index < values.size(); index++) {
                result.add(normalizeMaterial(tuple.elements().get(index), values.get(index)));
            }
            return result;
        }
        if (type instanceof TypeExpr.MapType map && value instanceof Map<?, ?> values) {
            Map<Object, Object> result = new LinkedHashMap<>();
            values.forEach((key, item) -> result.put(normalizeMaterial(map.key(), key),
                item == null ? null : normalizeMaterial(map.value(), item)));
            return result;
        }
        return value;
    }

    private static Object integerMaterial(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.toBigIntegerExact();
        }
        return value;
    }

    private static Object decimalMaterial(Object value) {
        return value instanceof BigDecimal ? value : value;
    }

    static Diagnostic diagnostic(Map<String, Object> value) {
        Diagnostic.Builder builder = Diagnostic.builder(
            text(value.get("code"), "code"),
            enumValue(value.get("severity"), DiagnosticSeverity.values(), DiagnosticSeverity::wireName, "severity"),
            enumValue(value.get("phase"), DiagnosticPhase.values(), DiagnosticPhase::wireName, "phase"),
            text(value.get("stage"), "stage"));
        builder.messageKey(contractRef(value.get("messageKey"), "messageKey", CapabilityId::of));
        builder.message(text(value.get("message"), "message"));
        if (value.containsKey("arguments")) {
            builder.arguments(object(value.get("arguments"), "arguments"));
        }
        builder.evidence(object(value.get("evidence"), "evidence"));
        builder.remediation(text(value.get("remediation"), "remediation"));
        builder.correlationId(UUID.fromString(text(value.get("correlationId"), "correlationId")));
        if (value.containsKey("traceId")) {
            builder.traceId(UUID.fromString(text(value.get("traceId"), "traceId")));
        }
        if (value.containsKey("serverId")) {
            builder.serverId(ServerId.parseCanonicalText(text(value.get("serverId"), "serverId")));
        }
        if (value.containsKey("resource")) {
            builder.resource(resource(object(value.get("resource"), "resource")));
        }
        if (value.containsKey("nodeId")) {
            builder.nodeId(new NodeId(text(value.get("nodeId"), "nodeId")));
        }
        if (value.containsKey("pinId")) {
            builder.pinId(new PinId(text(value.get("pinId"), "pinId")));
        }
        if (value.containsKey("catalogGeneration")) {
            builder.catalogGeneration(integer(value.get("catalogGeneration"), "catalogGeneration"));
        }
        if (value.containsKey("provenance")) {
            builder.provenance(provenance(object(value.get("provenance"), "provenance")));
        }
        builder.durable(booleanValue(value.get("durable"), "durable"));
        builder.redaction(enumValue(value.get("redaction"), DiagnosticRedaction.values(), DiagnosticRedaction::wireName, "redaction"));
        builder.metricPolicy(enumValue(value.get("metricPolicy"), DiagnosticMetricPolicy.values(), DiagnosticMetricPolicy::wireName, "metricPolicy"));
        return builder.build();
    }

    private static DiagnosticProvenance provenance(Map<String, Object> value) {
        return new DiagnosticProvenance(
            value.containsKey("ownerId") ? new OwnerId(text(value.get("ownerId"), "provenance.ownerId")) : null,
            enumValue(value.get("sourceKind"), DiagnosticSourceKind.values(), DiagnosticSourceKind::wireName, "provenance.sourceKind"),
            text(value.get("sourceUri"), "provenance.sourceUri"),
            text(value.get("sourceHash"), "provenance.sourceHash"),
            text(value.get("sourceVersion"), "provenance.sourceVersion"),
            text(value.get("buildId"), "provenance.buildId"),
            value.containsKey("loadedAt") ? Instant.parse(text(value.get("loadedAt"), "provenance.loadedAt")) : null);
    }

    static ServerResourceLocator resource(Map<String, Object> value) {
        ContractRef<ResourceTypeId> type = contractRef(value.get("type"), "resource.type", ResourceTypeId::of);
        Map<String, Object> keyUnknown = Map.of();
        Object rawKey = value.get("key");
        if (rawKey != null) {
            Map<String, Object> key = object(rawKey, "resource.key");
            keyUnknown = unknown(key, Set.of("type", "id"), "resource.key");
        }
        ResourceKey key = new ResourceKey(type, text(value.get("id"), "resource.id"), keyUnknown);
        return new ServerResourceLocator(
            ServerId.parseCanonicalText(text(value.get("serverId"), "resource.serverId")),
            key,
            unknown(value, Set.of("serverId", "type", "id", "key"), "resource"));
    }

    private static TypeReference typeReference(Object raw) {
        Map<String, Object> value = object(raw, "typeReference");
        return new TypeReference(
            text(value.get("ownerId"), "typeReference.ownerId"),
            text(value.get("localId"), "typeReference.localId"),
            unknown(value, Set.of("ownerId", "localId"), "typeReference"));
    }

    static TypeExpr type(Object raw) {
        Map<String, Object> value = object(raw, "type");
        String kind = text(value.get("kind"), "type.kind");
        return switch (kind) {
            case "named" -> new TypeExpr.Named(
                typeReference(value.get("type")),
                types(value.get("arguments"), "type.arguments"),
                unknown(value, Set.of("kind", "type", "arguments"), "named type"));
            case "optional" -> new TypeExpr.OptionalType(
                type(value.get("element")), unknown(value, Set.of("kind", "element"), "optional type"));
            case "list" -> new TypeExpr.ListType(
                type(value.get("element")), unknown(value, Set.of("kind", "element"), "list type"));
            case "map" -> new TypeExpr.MapType(
                type(value.get("key")), type(value.get("value")), unknown(value, Set.of("kind", "key", "value"), "map type"));
            case "tuple" -> new TypeExpr.TupleType(
                types(value.get("elements"), "type.elements"), unknown(value, Set.of("kind", "elements"), "tuple type"));
            case "result" -> new TypeExpr.ResultType(
                type(value.get("success")), type(value.get("failure")), unknown(value, Set.of("kind", "success", "failure"), "result type"));
            case "resource" -> new TypeExpr.ResourceType(
                typeReference(value.get("resourceType")), unknown(value, Set.of("kind", "resourceType"), "resource type"));
            case "union" -> new TypeExpr.UnionType(
                array(value.get("variants"), "type.variants").stream().map(item -> unionVariant(object(item, "unionVariant"))).toList(),
                unknown(value, Set.of("kind", "variants"), "union type"));
            case "opaque" -> {
                if (!booleanValue(value.get("raw"), "type.raw")) {
                    throw new IllegalArgumentException("Opaque type raw must be true");
                }
                yield new TypeExpr.OpaqueType(
                    typeReference(value.get("type")), unknown(value, Set.of("kind", "raw", "type"), "opaque type"));
            }
            default -> throw new IllegalArgumentException("Unknown type kind: " + kind);
        };
    }

    private static TypeExpr.UnionVariant unionVariant(Map<String, Object> value) {
        return new TypeExpr.UnionVariant(
            text(value.get("variantId"), "variantId"),
            type(value.get("type")),
            optionalText(value, "displayName"),
            optionalText(value, "description"),
            unknown(value, Set.of("variantId", "type", "displayName", "description"), "union variant"));
    }

    private static List<TypeExpr> types(Object raw, String name) {
        return array(raw, name).stream().map(RuntimeCanonicalDecoder::type).toList();
    }

    private static List<RuntimeOperationDescriptor.Pin> pins(Object raw, String name) {
        return array(raw, name).stream()
            .map(item -> pin(object(item, name + " entry")))
            .toList();
    }

    private static RuntimeOperationDescriptor.Pin pin(Map<String, Object> value) {
        RuntimeOperationDescriptor.Direction direction = switch (text(value.get("direction"), "pin.direction")) {
            case "input" -> RuntimeOperationDescriptor.Direction.INPUT;
            case "output" -> RuntimeOperationDescriptor.Direction.OUTPUT;
            default -> throw new IllegalArgumentException("Unknown pin direction");
        };
        return new RuntimeOperationDescriptor.Pin(
            new PinId(text(value.get("id"), "pin.id")),
            direction,
            type(value.get("type")));
    }

    private static <T extends restudio.resync.flow.identity.LocalId> Set<ContractRef<T>> references(
        Object raw,
        String name,
        Function<String, T> factory
    ) {
        List<ContractRef<T>> values = array(raw, name).stream().map(item -> contractRef(item, name, factory)).toList();
        return Set.copyOf(values);
    }

    private static Set<String> strings(Object raw, String name) {
        return Set.copyOf(array(raw, name).stream().map(item -> text(item, name)).toList());
    }

    private static ContractRef<ResourceTypeId> resourceReference(Object raw) {
        return contractRef(raw, "resourceReference", ResourceTypeId::of);
    }

    private static <T extends restudio.resync.flow.identity.LocalId> ContractRef<T> contractRef(
        Object raw,
        String name,
        Function<String, T> factory
    ) {
        Map<String, Object> value = object(raw, name);
        return new ContractRef<>(
            new OwnerId(text(value.get("ownerId"), name + ".ownerId")),
            factory.apply(text(value.get("localId"), name + ".localId")),
            unknown(value, Set.of("ownerId", "localId"), name));
    }

    private static Map<String, Object> unknown(Map<String, Object> value, Set<String> known, String name) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        value.forEach((key, item) -> {
            if (!known.contains(key)) {
                result.put(key, item);
            }
        });
        return RuntimeCanonicalSupport.unknown(result, name + " unknown data");
    }

    static Map<String, Object> object(Object raw, String name) {
        if (!(raw instanceof Map<?, ?> source)) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(name + " keys must be strings");
            }
            value.put(key, entry.getValue());
        }
        return value;
    }

    private static List<?> array(Object raw, String name) {
        if (!(raw instanceof List<?> values)) {
            throw new IllegalArgumentException(name + " must be an array");
        }
        return values;
    }

    private static String text(Object raw, String name) {
        if (!(raw instanceof String value)) {
            throw new IllegalArgumentException(name + " must be text");
        }
        return value;
    }

    private static String optionalText(Map<String, Object> value, String name) {
        Object raw = value.get(name);
        return raw == null ? null : text(raw, name);
    }

    private static boolean booleanValue(Object raw, String name) {
        if (!(raw instanceof Boolean value)) {
            throw new IllegalArgumentException(name + " must be boolean");
        }
        return value;
    }

    private static long integer(Object raw, String name) {
        BigDecimal value = switch (raw) {
            case BigDecimal decimal -> decimal;
            case Byte number -> BigDecimal.valueOf(number.longValue());
            case Short number -> BigDecimal.valueOf(number.longValue());
            case Integer number -> BigDecimal.valueOf(number.longValue());
            case Long number -> BigDecimal.valueOf(number);
            case java.math.BigInteger number -> new BigDecimal(number);
            default -> throw new IllegalArgumentException(name + " must be an integer");
        };
        try {
            return value.longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
    }

    private static <E> E enumValue(Object raw, E[] values, Function<E, String> wire, String name) {
        String expected = text(raw, name);
        for (E value : values) {
            if (wire.apply(value).equals(expected)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown " + name + ": " + expected);
    }
}
