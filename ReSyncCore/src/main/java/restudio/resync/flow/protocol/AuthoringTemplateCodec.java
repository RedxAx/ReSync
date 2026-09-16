package restudio.resync.flow.protocol;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.contract.identity.IdentityCodec;
import restudio.resync.flow.authoring.AuthoringTemplateLimits;
import restudio.resync.flow.authoring.AuthoringTemplatePayload;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.function.FunctionSourceDocumentCodec;
import restudio.resync.flow.graph.GraphDocumentCodec;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.IdentitySupport;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.resource.ResourceManagementDescriptor;
import restudio.resync.flow.type.TypeValueCodec;
import restudio.resync.flow.type.TypedValue;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class AuthoringTemplateCodec {
    public static final int VERSION = 1;
    public static final AuthoringTemplateCodec INSTANCE = new AuthoringTemplateCodec();

    private static final String REQUEST_KIND = "authoring-template-request";
    private static final String RESPONSE_KIND = "authoring-template-response";
    private static final Set<String> REQUEST_KNOWN = Set.of("kind", "version", "resource", "acknowledgedCatalogKey",
        "acknowledgedAuthoringPublicationChecksum", "expectedTemplateKind", "expectedTemplateVersion",
        "acknowledgedManagementDescriptorChecksum", "acknowledgedManagementCapability", "authoringInputs");
    private static final Set<String> AUTHORING_INPUT_KNOWN = Set.of("id", "value");
    private static final Set<String> RESPONSE_KNOWN = Set.of("kind", "version", "resource", "publicationKey",
        "catalogBinding", "payload", "templateChecksum", "authoringPublicationChecksum", "editCapabilities",
        "requiredCapabilities", "managementDescriptorChecksum", "managementCapability");
    private static final Set<String> PAYLOAD_KNOWN = Set.of("kind", "document");
    private static final Set<String> RESOURCE_PAYLOAD_KNOWN = Set.of("kind", "version", "resource", "catalogBinding",
        "payloadType", "payload", "requiredCapabilities");
    private static final Set<String> BINDING_KNOWN = Set.of("generation", "catalogChecksum", "bindingManifestHash");

    public AuthoringTemplateCodec() {
    }

    public static AuthoringTemplateCodec instance() {
        return INSTANCE;
    }

    public CanonicalCodec<AuthoringTemplateRequest> requestCodec() {
        return new CanonicalCodec<>() {
            @Override
            public JsonValue encode(AuthoringTemplateRequest value) {
                return encodeRequest(value);
            }

            @Override
            public AuthoringTemplateRequest decode(JsonValue value) {
                return decodeRequest(value);
            }

            @Override
            public byte[] encodeBytes(AuthoringTemplateRequest value) {
                return encodeRequestBytes(value);
            }

            @Override
            public AuthoringTemplateRequest decodeBytes(byte[] input) {
                return decodeRequestBytes(input);
            }

            @Override
            public String encodeText(AuthoringTemplateRequest value) {
                return encodeRequestText(value);
            }

            @Override
            public AuthoringTemplateRequest decodeText(String input) {
                return decodeRequestText(input);
            }
        };
    }

    public CanonicalCodec<AuthoringTemplateResponse> responseCodec() {
        return new CanonicalCodec<>() {
            @Override
            public JsonValue encode(AuthoringTemplateResponse value) {
                return encodeResponse(value);
            }

            @Override
            public AuthoringTemplateResponse decode(JsonValue value) {
                return decodeResponse(value);
            }

            @Override
            public byte[] encodeBytes(AuthoringTemplateResponse value) {
                return encodeResponseBytes(value);
            }

            @Override
            public AuthoringTemplateResponse decodeBytes(byte[] input) {
                return decodeResponseBytes(input);
            }

            @Override
            public String encodeText(AuthoringTemplateResponse value) {
                return encodeResponseText(value);
            }

            @Override
            public AuthoringTemplateResponse decodeText(String input) {
                return decodeResponseText(input);
            }
        };
    }

    public JsonValue.JsonObject encodeRequest(AuthoringTemplateRequest request) {
        Objects.requireNonNull(request, "Authoring template request is required");
        Map<String, Object> known = new LinkedHashMap<>();
        known.put("kind", REQUEST_KIND);
        known.put("version", VERSION);
        known.put("resource", IdentityCodec.encode(request.resource()));
        known.put("acknowledgedCatalogKey", request.acknowledgedCatalogKey().canonicalText());
        if (request.acknowledgedAuthoringPublicationChecksum() != null) {
            known.put("acknowledgedAuthoringPublicationChecksum",
                request.acknowledgedAuthoringPublicationChecksum().canonicalText());
        }
        if (request.hasManagementAcknowledgement()) {
            known.put("expectedTemplateKind", request.expectedTemplateKind().wireName());
            known.put("expectedTemplateVersion", request.expectedTemplateVersion());
            known.put("acknowledgedManagementDescriptorChecksum",
                request.acknowledgedManagementDescriptorChecksum().canonicalText());
            known.put("acknowledgedManagementCapability", IdentityCodec.encode(request.acknowledgedManagementCapability()));
            known.put("authoringInputs", encodeAuthoringInputs(request.authoringInputs()));
        }
        return object(known, request.unknown());
    }

    public AuthoringTemplateRequest decodeRequest(JsonValue value) {
        JsonValue.JsonObject object = object(value, "Authoring template request");
        requireKind(object, REQUEST_KIND);
        requireVersion(object);
        ServerResourceLocator resource = IdentityCodec.decodeLocator(require(object, "resource"));
        CatalogCacheKey key = CatalogCacheKey.parseCanonicalText(text(object, "acknowledgedCatalogKey"));
        ContentHash acknowledgedAuthoringPublicationChecksum = optionalText(object,
            "acknowledgedAuthoringPublicationChecksum").map(ContentHash::new).orElse(null);
        AuthoringTemplatePayload.Kind expectedTemplateKind = optionalText(object, "expectedTemplateKind")
            .map(AuthoringTemplatePayload.Kind::fromWireName).orElse(null);
        Integer expectedTemplateVersion = object.contains("expectedTemplateVersion")
            ? Math.toIntExact(requireLong(object, "expectedTemplateVersion")) : null;
        ContentHash acknowledgedManagementDescriptorChecksum = optionalText(object,
            "acknowledgedManagementDescriptorChecksum").map(ContentHash::new).orElse(null);
        ContractRef<CapabilityId> acknowledgedManagementCapability = object.contains("acknowledgedManagementCapability")
            ? IdentityCodec.decodeReference(require(object, "acknowledgedManagementCapability"), CapabilityId::new) : null;
        Map<InspectorFieldId, TypedValue> authoringInputs = object.contains("authoringInputs")
            ? decodeAuthoringInputs(requireArray(object, "authoringInputs")) : Map.of();
        AuthoringTemplateRequest request = new AuthoringTemplateRequest(resource, key,
            acknowledgedAuthoringPublicationChecksum, expectedTemplateKind, expectedTemplateVersion,
            acknowledgedManagementDescriptorChecksum, acknowledgedManagementCapability, authoringInputs,
            unknown(object, REQUEST_KNOWN));
        requireCanonical(encodeRequest(request), object, "Authoring template request");
        return request;
    }

    public byte[] encodeRequestBytes(AuthoringTemplateRequest request) {
        return encodeRequest(request).canonicalBytes(AuthoringTemplateLimits.canonical());
    }

    public String encodeRequestText(AuthoringTemplateRequest request) {
        return encodeRequest(request).canonicalText(AuthoringTemplateLimits.canonical());
    }

    public AuthoringTemplateRequest decodeRequestBytes(byte[] input) {
        return decodeRequest(CanonicalCodec.decode(input, AuthoringTemplateLimits.canonical()));
    }

    public AuthoringTemplateRequest decodeRequestText(String input) {
        return decodeRequest(CanonicalCodec.decode(input, AuthoringTemplateLimits.canonical()));
    }

    public JsonValue.JsonObject encodeResponse(AuthoringTemplateResponse response) {
        Objects.requireNonNull(response, "Authoring template response is required");
        Map<String, Object> known = new LinkedHashMap<>();
        known.put("kind", RESPONSE_KIND);
        known.put("version", VERSION);
        known.put("resource", IdentityCodec.encode(response.resource()));
        known.put("publicationKey", response.publicationKey().canonicalText());
        known.put("catalogBinding", encodeBinding(response.catalogBinding()));
        known.put("payload", encodePayload(response.payload()));
        known.put("templateChecksum", response.templateChecksum().canonicalText());
        if (response.authoringPublicationChecksum() != null) {
            known.put("authoringPublicationChecksum", response.authoringPublicationChecksum().canonicalText());
        }
        known.put("editCapabilities", encodeCapabilities(response.editCapabilities()));
        known.put("requiredCapabilities", encodeCapabilities(response.requiredCapabilities()));
        if (response.hasManagementAcknowledgement()) {
            known.put("managementDescriptorChecksum", response.managementDescriptorChecksum().canonicalText());
            known.put("managementCapability", IdentityCodec.encode(response.managementCapability()));
        }
        return object(known, response.unknown());
    }

    public AuthoringTemplateResponse decodeResponse(JsonValue value) {
        JsonValue.JsonObject object = object(value, "Authoring template response");
        requireKind(object, RESPONSE_KIND);
        requireVersion(object);
        ServerResourceLocator resource = IdentityCodec.decodeLocator(require(object, "resource"));
        CatalogCacheKey publicationKey = CatalogCacheKey.parseCanonicalText(text(object, "publicationKey"));
        CatalogBinding binding = decodeBinding(require(object, "catalogBinding"));
        AuthoringTemplatePayload payload = decodePayload(require(object, "payload"));
        ContentHash templateChecksum = new ContentHash(text(object, "templateChecksum"));
        ContentHash authoringPublicationChecksum = optionalText(object, "authoringPublicationChecksum")
            .map(ContentHash::new).orElse(null);
        Set<ContractRef<CapabilityId>> editCapabilities = decodeCapabilities(requireArray(object, "editCapabilities"));
        Set<ContractRef<CapabilityId>> requiredCapabilities = decodeCapabilities(requireArray(object, "requiredCapabilities"));
        ContentHash managementDescriptorChecksum = optionalText(object, "managementDescriptorChecksum")
            .map(ContentHash::new).orElse(null);
        ContractRef<CapabilityId> managementCapability = object.contains("managementCapability")
            ? IdentityCodec.decodeReference(require(object, "managementCapability"), CapabilityId::new) : null;
        AuthoringTemplateResponse response = new AuthoringTemplateResponse(resource, publicationKey, binding, payload,
            templateChecksum, authoringPublicationChecksum, editCapabilities, requiredCapabilities,
            managementDescriptorChecksum, managementCapability, unknown(object, RESPONSE_KNOWN));
        requireCanonical(encodeResponse(response), object, "Authoring template response");
        return response;
    }

    public byte[] encodeResponseBytes(AuthoringTemplateResponse response) {
        return encodeResponse(response).canonicalBytes(AuthoringTemplateLimits.canonical());
    }

    public String encodeResponseText(AuthoringTemplateResponse response) {
        return encodeResponse(response).canonicalText(AuthoringTemplateLimits.canonical());
    }

    public AuthoringTemplateResponse decodeResponseBytes(byte[] input) {
        return decodeResponse(CanonicalCodec.decode(input, AuthoringTemplateLimits.canonical()));
    }

    public AuthoringTemplateResponse decodeResponseText(String input) {
        return decodeResponse(CanonicalCodec.decode(input, AuthoringTemplateLimits.canonical()));
    }

    private static JsonValue.JsonObject encodePayload(AuthoringTemplatePayload payload) {
        if (payload instanceof AuthoringTemplatePayload.Resource resource) {
            return object(resource.canonicalValue(), Map.of());
        }
        Map<String, Object> known = new LinkedHashMap<>();
        known.put("kind", payload.kind().wireName());
        known.put("document", switch (payload) {
            case AuthoringTemplatePayload.Flow flow -> GraphDocumentCodec.INSTANCE.encode(flow.document());
            case AuthoringTemplatePayload.Command command -> GraphDocumentCodec.INSTANCE.encode(command.document());
            case AuthoringTemplatePayload.Function function -> FunctionSourceDocumentCodec.INSTANCE.encode(function.document());
            case AuthoringTemplatePayload.Resource ignored -> throw new IllegalStateException("Resource payload was not encoded explicitly");
        });
        return object(known, payload.unknown());
    }

    private static AuthoringTemplatePayload decodePayload(JsonValue value) {
        JsonValue.JsonObject object = object(value, "Authoring template payload");
        AuthoringTemplatePayload.Kind kind = AuthoringTemplatePayload.Kind.fromWireName(text(object, "kind"));
        AuthoringTemplatePayload decoded = switch (kind) {
            case FLOW -> new AuthoringTemplatePayload.Flow(GraphDocumentCodec.INSTANCE.decode(require(object, "document")), unknown(object, PAYLOAD_KNOWN));
            case COMMAND -> new AuthoringTemplatePayload.Command(GraphDocumentCodec.INSTANCE.decode(require(object, "document")), unknown(object, PAYLOAD_KNOWN));
            case FUNCTION -> new AuthoringTemplatePayload.Function(FunctionSourceDocumentCodec.INSTANCE.decode(require(object, "document")), unknown(object, PAYLOAD_KNOWN));
            case RESOURCE -> decodeResourcePayload(object);
        };
        requireCanonical(encodePayload(decoded), object, "Authoring template payload");
        return decoded;
    }

    private static AuthoringTemplatePayload.Resource decodeResourcePayload(JsonValue.JsonObject object) {
        if (requireLong(object, "version") != AuthoringTemplatePayload.Resource.VERSION) {
            throw new IllegalArgumentException("Unsupported Resource authoring template version");
        }
        return new AuthoringTemplatePayload.Resource(
            IdentityCodec.decodeLocator(require(object, "resource")),
            decodeBinding(require(object, "catalogBinding")),
            TypeValueCodec.INSTANCE.decodeTypeReference(require(object, "payloadType")),
            TypeValueCodec.INSTANCE.decode(require(object, "payload")),
            decodeCapabilities(requireArray(object, "requiredCapabilities")),
            unknown(object, RESOURCE_PAYLOAD_KNOWN));
    }

    private static Map<String, Object> encodeBinding(CatalogBinding binding) {
        return Map.of("generation", binding.generation(),
            "catalogChecksum", binding.catalogChecksum().canonicalText(),
            "bindingManifestHash", binding.bindingManifestHash().canonicalText());
    }

    private static CatalogBinding decodeBinding(JsonValue value) {
        JsonValue.JsonObject object = object(value, "Authoring template catalog binding");
        Map<String, Object> unknown = unknown(object, BINDING_KNOWN);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown authoring template catalog binding fields are not permitted");
        }
        return new CatalogBinding(requireLong(object, "generation"), new ContentHash(text(object, "catalogChecksum")),
            new ContentHash(text(object, "bindingManifestHash")));
    }

    private static List<JsonValue.JsonObject> encodeCapabilities(Set<ContractRef<CapabilityId>> capabilities) {
        return capabilities.stream().sorted(Comparator.comparing(ContractRef::canonicalText))
            .map(IdentityCodec::encode).toList();
    }

    private static List<JsonValue.JsonObject> encodeAuthoringInputs(Map<InspectorFieldId, TypedValue> inputs) {
        return inputs.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry -> object(Map.of(
            "id", entry.getKey().canonicalText(),
            "value", TypeValueCodec.INSTANCE.encode(entry.getValue())), Map.of())).toList();
    }

    private static Map<InspectorFieldId, TypedValue> decodeAuthoringInputs(JsonValue.JsonArray values) {
        if (values.values().size() > ResourceManagementDescriptor.MAX_INPUTS) {
            throw new IllegalArgumentException("Resource authoring request contains too many inputs");
        }
        Map<InspectorFieldId, TypedValue> inputs = new LinkedHashMap<>();
        for (JsonValue value : values.values()) {
            JsonValue.JsonObject object = object(value, "Resource authoring input");
            InspectorFieldId id = new InspectorFieldId(text(object, "id"));
            TypedValue typedValue = TypeValueCodec.INSTANCE.decode(require(object, "value"));
            if (inputs.put(id, typedValue) != null) {
                throw new IllegalArgumentException("Duplicate Resource authoring input: " + id);
            }
            requireCanonical(object(Map.of("id", id.canonicalText(), "value", TypeValueCodec.INSTANCE.encode(typedValue)),
                Map.of()), object, "Resource authoring input");
        }
        return Map.copyOf(inputs);
    }

    private static Set<ContractRef<CapabilityId>> decodeCapabilities(JsonValue.JsonArray values) {
        if (values.values().size() > AuthoringTemplateLimits.MAX_CAPABILITIES) {
            throw new IllegalArgumentException("Authoring template contains too many capabilities");
        }
        LinkedHashSet<ContractRef<CapabilityId>> capabilities = new LinkedHashSet<>();
        for (JsonValue value : values.values()) {
            ContractRef<CapabilityId> capability = IdentityCodec.decodeReference(value, CapabilityId::new);
            if (!capabilities.add(capability)) {
                throw new IllegalArgumentException("Duplicate authoring template capability: " + capability.canonicalText());
            }
        }
        return Set.copyOf(capabilities);
    }

    private static void requireKind(JsonValue.JsonObject object, String expected) {
        if (!expected.equals(text(object, "kind"))) {
            throw new IllegalArgumentException("Unexpected authoring template kind");
        }
    }

    private static void requireVersion(JsonValue.JsonObject object) {
        if (requireLong(object, "version") != VERSION) {
            throw new IllegalArgumentException("Unsupported authoring template version");
        }
    }

    private static void requireCanonical(JsonValue.JsonObject encoded, JsonValue.JsonObject input, String name) {
        if (!encoded.canonicalText().equals(input.canonicalText())) {
            throw new IllegalArgumentException(name + " is not in the exact canonical shape");
        }
    }

    private static JsonValue.JsonObject object(Map<String, Object> known, Map<String, ?> unknown) {
        Map<String, JsonValue> knownValues = new LinkedHashMap<>();
        known.forEach((key, value) -> knownValues.put(key, JsonValue.fromJava(value)));
        Map<String, JsonValue> unknownValues = new LinkedHashMap<>();
        if (unknown != null) {
            unknown.forEach((key, value) -> unknownValues.put(key, JsonValue.fromJava(value)));
        }
        return CanonicalCodec.mergeKnownFields(knownValues, unknownValues);
    }

    private static JsonValue.JsonObject object(JsonValue value, String name) {
        if (!(value instanceof JsonValue.JsonObject object)) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        return object;
    }

    private static JsonValue require(JsonValue.JsonObject object, String field) {
        JsonValue value = object.value(field);
        if (value == null) {
            throw new IllegalArgumentException("Required authoring template field is missing: " + field);
        }
        return value;
    }

    private static String text(JsonValue.JsonObject object, String field) {
        JsonValue value = require(object, field);
        if (!(value instanceof JsonValue.JsonString string)) {
            throw new IllegalArgumentException("Authoring template field must be text: " + field);
        }
        return string.value();
    }

    private static Optional<String> optionalText(JsonValue.JsonObject object, String field) {
        JsonValue value = object.value(field);
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof JsonValue.JsonString string)) {
            throw new IllegalArgumentException("Authoring template field must be text: " + field);
        }
        return Optional.of(string.value());
    }

    private static long requireLong(JsonValue.JsonObject object, String field) {
        JsonValue value = require(object, field);
        if (!(value instanceof JsonValue.JsonNumber number)
            || number.value().stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("Authoring template field must be an integer: " + field);
        }
        try {
            return number.value().longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Authoring template integer is out of range: " + field, exception);
        }
    }

    private static JsonValue.JsonArray requireArray(JsonValue.JsonObject object, String field) {
        JsonValue value = require(object, field);
        if (!(value instanceof JsonValue.JsonArray array)) {
            throw new IllegalArgumentException("Authoring template field must be an array: " + field);
        }
        return array;
    }

    private static Map<String, Object> unknown(JsonValue.JsonObject object, Set<String> known) {
        Map<String, Object> values = new LinkedHashMap<>();
        object.fields().forEach((key, value) -> {
            if (!known.contains(key)) {
                values.put(key, value.toJava());
            }
        });
        return IdentitySupport.unknown(values, "authoring template unknown data");
    }
}
