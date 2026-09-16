package restudio.resync.flow.authoring;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.IdentitySupport;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.type.TypeDescriptor;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public sealed interface AuthoringTemplatePayload
    permits AuthoringTemplatePayload.Flow, AuthoringTemplatePayload.Command, AuthoringTemplatePayload.Function,
    AuthoringTemplatePayload.Resource {
    Kind kind();

    ServerResourceLocator resource();

    CatalogBinding catalogBinding();

    Set<ContractRef<CapabilityId>> requiredCapabilities();

    ContentHash checksum();

    Map<String, Object> unknown();

    default GraphDocument graphDocument() {
        return null;
    }

    default FunctionSourceDocument functionSourceDocument() {
        return null;
    }

    static Flow flow(GraphDocument document) {
        return new Flow(document);
    }

    static Command command(GraphDocument document) {
        return new Command(document);
    }

    static Function function(FunctionSourceDocument document) {
        return new Function(document);
    }

    static Resource resource(ServerResourceLocator resource, CatalogBinding catalogBinding, TypeReference payloadType,
                             TypedValue payload, Set<ContractRef<CapabilityId>> requiredCapabilities) {
        return new Resource(resource, catalogBinding, payloadType, payload, requiredCapabilities);
    }

    enum Kind {
        FLOW("flow"),
        COMMAND("command"),
        FUNCTION("function"),
        RESOURCE("resource");

        private final String wireName;

        Kind(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static Kind fromWireName(String value) {
            for (Kind candidate : values()) {
                if (candidate.wireName.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("Unknown authoring template payload kind: " + value);
        }
    }

    record Flow(GraphDocument document, Map<String, Object> unknown) implements AuthoringTemplatePayload {
        public Flow {
            document = graph(document, Kind.FLOW);
            unknown = immutableUnknown(unknown);
        }

        public Flow(GraphDocument document) {
            this(document, Map.of());
        }

        @Override
        public Kind kind() {
            return Kind.FLOW;
        }

        @Override
        public ServerResourceLocator resource() {
            return document.resource();
        }

        @Override
        public CatalogBinding catalogBinding() {
            return document.catalogBinding();
        }

        @Override
        public Set<ContractRef<CapabilityId>> requiredCapabilities() {
            return document.requiredCapabilities();
        }

        @Override
        public ContentHash checksum() {
            return document.checksum();
        }

        @Override
        public GraphDocument graphDocument() {
            return document;
        }
    }

    record Command(GraphDocument document, Map<String, Object> unknown) implements AuthoringTemplatePayload {
        public Command {
            document = graph(document, Kind.COMMAND);
            unknown = immutableUnknown(unknown);
        }

        public Command(GraphDocument document) {
            this(document, Map.of());
        }

        @Override
        public Kind kind() {
            return Kind.COMMAND;
        }

        @Override
        public ServerResourceLocator resource() {
            return document.resource();
        }

        @Override
        public CatalogBinding catalogBinding() {
            return document.catalogBinding();
        }

        @Override
        public Set<ContractRef<CapabilityId>> requiredCapabilities() {
            return document.requiredCapabilities();
        }

        @Override
        public ContentHash checksum() {
            return document.checksum();
        }

        @Override
        public GraphDocument graphDocument() {
            return document;
        }
    }

    record Function(FunctionSourceDocument document, Map<String, Object> unknown) implements AuthoringTemplatePayload {
        public Function {
            document = checkedFunction(document);
            unknown = immutableUnknown(unknown);
        }

        public Function(FunctionSourceDocument document) {
            this(document, Map.of());
        }

        @Override
        public Kind kind() {
            return Kind.FUNCTION;
        }

        @Override
        public ServerResourceLocator resource() {
            return document.graph().resource();
        }

        @Override
        public CatalogBinding catalogBinding() {
            return document.graph().catalogBinding();
        }

        @Override
        public Set<ContractRef<CapabilityId>> requiredCapabilities() {
            return document.graph().requiredCapabilities();
        }

        @Override
        public ContentHash checksum() {
            return document.checksum();
        }

        @Override
        public FunctionSourceDocument functionSourceDocument() {
            return document;
        }
    }

    record Resource(ServerResourceLocator resource, CatalogBinding catalogBinding, TypeReference payloadType,
                    TypedValue payload, Set<ContractRef<CapabilityId>> requiredCapabilities,
                    Map<String, Object> unknown) implements AuthoringTemplatePayload {
        public static final int VERSION = 1;
        private static final String HASH_DOMAIN = "authoring-template-resource.v1";

        public Resource {
            resource = Objects.requireNonNull(resource, "Resource authoring locator is required");
            catalogBinding = Objects.requireNonNull(catalogBinding, "Resource authoring catalog binding is required");
            payloadType = Objects.requireNonNull(payloadType, "Resource authoring payload type is required");
            payload = Objects.requireNonNull(payload, "Resource authoring payload is required");
            if (payload.state() == TypedValue.State.ABSENT) {
                throw new IllegalArgumentException("Resource authoring payload cannot be absent");
            }
            requiredCapabilities = immutableCapabilities(requiredCapabilities);
            unknown = immutableUnknown(unknown);
        }

        public Resource(ServerResourceLocator resource, CatalogBinding catalogBinding, TypeReference payloadType,
                        TypedValue payload, Set<ContractRef<CapabilityId>> requiredCapabilities) {
            this(resource, catalogBinding, payloadType, payload, requiredCapabilities, Map.of());
        }

        @Override
        public Kind kind() {
            return Kind.RESOURCE;
        }

        @Override
        public ContentHash checksum() {
            return new ContentHash(CanonicalJson.sha256(HASH_DOMAIN, canonicalValue()));
        }

        public Map<String, Object> canonicalValue() {
            Map<String, Object> known = new LinkedHashMap<>();
            known.put("kind", kind().wireName());
            known.put("version", VERSION);
            known.put("resource", resource.canonicalValue());
            known.put("catalogBinding", Map.of("generation", catalogBinding.generation(),
                "catalogChecksum", catalogBinding.catalogChecksum().canonicalText(),
                "bindingManifestHash", catalogBinding.bindingManifestHash().canonicalText()));
            known.put("payloadType", payloadType.canonicalValue());
            known.put("payload", payload.canonicalValue());
            known.put("requiredCapabilities", requiredCapabilities.stream().sorted(Comparator.comparing(ContractRef::canonicalText))
                .map(ContractRef::canonicalValue).toList());
            return IdentitySupport.merge(unknown, known);
        }

        public TypeDescriptor requirePayloadDescriptor(TypeDescriptor descriptor) {
            TypeDescriptor checked = Objects.requireNonNull(descriptor, "Payload descriptor is required");
            if (!CanonicalJson.canonicalize(checked.id().canonicalValue()).equals(CanonicalJson.canonicalize(payloadType.canonicalValue()))
                || !checked.expression().canonicalJson().equals(payload.type().canonicalJson())) {
                throw new IllegalArgumentException("Payload descriptor does not exactly match the Resource authoring payload");
            }
            return checked;
        }

        private static Set<ContractRef<CapabilityId>> immutableCapabilities(Set<ContractRef<CapabilityId>> values) {
            if (values == null || values.isEmpty()) {
                return Set.of();
            }
            if (values.size() > AuthoringTemplateLimits.MAX_CAPABILITIES) {
                throw new IllegalArgumentException("Resource authoring payload contains too many capabilities");
            }
            return Set.copyOf(values.stream()
                .map(value -> Objects.requireNonNull(value, "Resource authoring capability is required"))
                .toList());
        }
    }

    private static GraphDocument graph(GraphDocument document, Kind kind) {
        GraphDocument checked = Objects.requireNonNull(document, "Graph document is required");
        if (checked.revision() != 0) {
            throw new IllegalArgumentException("Authoring template graph revision must be zero");
        }
        if (!kind.wireName().equals(checked.resource().resourceType().value())) {
            throw new IllegalArgumentException("Authoring template graph resource type does not match its payload kind");
        }
        return checked;
    }

    private static FunctionSourceDocument checkedFunction(FunctionSourceDocument document) {
        FunctionSourceDocument checked = Objects.requireNonNull(document, "Function source document is required");
        if (checked.graph().revision() != 0 || checked.signature().revision().value() != 0) {
            throw new IllegalArgumentException("Authoring template function revision must be zero");
        }
        if (!Kind.FUNCTION.wireName().equals(checked.graph().resource().resourceType().value())) {
            throw new IllegalArgumentException("Authoring template function resource type does not match its payload kind");
        }
        return checked;
    }

    private static Map<String, Object> immutableUnknown(Map<String, ?> unknown) {
        return IdentitySupport.unknown(unknown, "authoring template payload unknown data");
    }
}
