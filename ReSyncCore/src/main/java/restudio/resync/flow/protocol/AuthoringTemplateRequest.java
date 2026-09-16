package restudio.resync.flow.protocol;

import restudio.resync.flow.authoring.AuthoringTemplatePayload;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.IdentitySupport;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.resource.ResourceManagementDescriptor;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypedValue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

public record AuthoringTemplateRequest(
    ServerResourceLocator resource,
    CatalogCacheKey acknowledgedCatalogKey,
    ContentHash acknowledgedAuthoringPublicationChecksum,
    AuthoringTemplatePayload.Kind expectedTemplateKind,
    Integer expectedTemplateVersion,
    ContentHash acknowledgedManagementDescriptorChecksum,
    ContractRef<CapabilityId> acknowledgedManagementCapability,
    Map<InspectorFieldId, TypedValue> authoringInputs,
    Map<String, Object> unknown
) {
    private static final Set<String> KNOWN_FIELDS = Set.of("kind", "version", "resource", "acknowledgedCatalogKey",
        "acknowledgedAuthoringPublicationChecksum", "expectedTemplateKind", "expectedTemplateVersion",
        "acknowledgedManagementDescriptorChecksum", "acknowledgedManagementCapability", "authoringInputs");

    public AuthoringTemplateRequest {
        resource = Objects.requireNonNull(resource, "Authoring template resource is required");
        acknowledgedCatalogKey = Objects.requireNonNull(acknowledgedCatalogKey, "Acknowledged catalog key is required");
        if (!resource.serverId().equals(acknowledgedCatalogKey.serverId())) {
            throw new IllegalArgumentException("Authoring template resource and catalog key must use the same server");
        }
        if (!acknowledgedCatalogKey.hasCatalogBinding()) {
            throw new IllegalArgumentException("Authoring template requests require an exact catalog binding");
        }
        int managementFields = (expectedTemplateKind == null ? 0 : 1) + (expectedTemplateVersion == null ? 0 : 1)
            + (acknowledgedManagementDescriptorChecksum == null ? 0 : 1)
            + (acknowledgedManagementCapability == null ? 0 : 1);
        if (managementFields != 0 && managementFields != 4) {
            throw new IllegalArgumentException("Resource authoring acknowledgement fields must be all present or all absent");
        }
        if (managementFields == 4 && acknowledgedAuthoringPublicationChecksum == null) {
            throw new IllegalArgumentException("Resource authoring requires an exact acknowledged authoring publication");
        }
        if (managementFields == 4 && (expectedTemplateKind != AuthoringTemplatePayload.Kind.RESOURCE
            || expectedTemplateVersion != 1)) {
            throw new IllegalArgumentException("Only explicit Resource authoring template version 1 is supported");
        }
        authoringInputs = freezeAuthoringInputs(authoringInputs);
        if (managementFields == 0 && !authoringInputs.isEmpty()) {
            throw new IllegalArgumentException("Only Resource authoring requests may carry authoring inputs");
        }
        unknown = IdentitySupport.unknown(unknown, "authoring template request unknown data");
        for (String field : unknown.keySet()) {
            if (KNOWN_FIELDS.contains(field)) {
                throw new IllegalArgumentException("Unknown authoring template request field collides with known field: " + field);
            }
        }
    }

    public AuthoringTemplateRequest(ServerResourceLocator resource, CatalogCacheKey acknowledgedCatalogKey) {
        this(resource, acknowledgedCatalogKey, null, null, null, null, null, Map.of(), Map.of());
    }

    public AuthoringTemplateRequest(ServerResourceLocator resource, CatalogCacheKey acknowledgedCatalogKey,
                                    Map<String, ?> unknown) {
        this(resource, acknowledgedCatalogKey, null, null, null, null, null, Map.of(), freezeUnknown(unknown));
    }

    public AuthoringTemplateRequest(ServerResourceLocator resource, CatalogCacheKey acknowledgedCatalogKey,
                                    ContentHash acknowledgedAuthoringPublicationChecksum) {
        this(resource, acknowledgedCatalogKey, acknowledgedAuthoringPublicationChecksum, null, null, null, null,
            Map.of(), Map.of());
    }

    public AuthoringTemplateRequest(ServerResourceLocator resource, CatalogCacheKey acknowledgedCatalogKey,
                                    ContentHash acknowledgedAuthoringPublicationChecksum, Map<String, ?> unknown) {
        this(resource, acknowledgedCatalogKey, acknowledgedAuthoringPublicationChecksum, null, null, null, null,
            Map.of(), freezeUnknown(unknown));
    }

    public AuthoringTemplateRequest(ServerResourceLocator resource, CatalogCacheKey acknowledgedCatalogKey,
                                    ContentHash acknowledgedAuthoringPublicationChecksum,
                                    AuthoringTemplatePayload.Kind expectedTemplateKind, Integer expectedTemplateVersion,
                                    ContentHash acknowledgedManagementDescriptorChecksum,
                                    ContractRef<CapabilityId> acknowledgedManagementCapability) {
        this(resource, acknowledgedCatalogKey, acknowledgedAuthoringPublicationChecksum, expectedTemplateKind,
            expectedTemplateVersion, acknowledgedManagementDescriptorChecksum, acknowledgedManagementCapability,
            Map.of(), Map.of());
    }

    public AuthoringTemplateRequest(ServerResourceLocator resource, CatalogCacheKey acknowledgedCatalogKey,
                                    ContentHash acknowledgedAuthoringPublicationChecksum,
                                    AuthoringTemplatePayload.Kind expectedTemplateKind, Integer expectedTemplateVersion,
                                    ContentHash acknowledgedManagementDescriptorChecksum,
                                    ContractRef<CapabilityId> acknowledgedManagementCapability, Map<String, ?> unknown) {
        this(resource, acknowledgedCatalogKey, acknowledgedAuthoringPublicationChecksum, expectedTemplateKind,
            expectedTemplateVersion, acknowledgedManagementDescriptorChecksum, acknowledgedManagementCapability,
            Map.of(), freezeUnknown(unknown));
    }

    public boolean hasAcknowledgedAuthoringPublicationChecksum() {
        return acknowledgedAuthoringPublicationChecksum != null;
    }

    public boolean hasManagementAcknowledgement() {
        return expectedTemplateKind != null;
    }

    public Map<InspectorFieldId, TypedValue> resolveAuthoringInputs(ResourceManagementDescriptor descriptor) {
        if (!hasManagementAcknowledgement()) {
            throw new IllegalArgumentException("Authoring input resolution requires a Resource authoring request");
        }
        ResourceManagementDescriptor checked = Objects.requireNonNull(descriptor,
            "Resource management descriptor is required");
        ResourceManagementDescriptor.ServerAuthoring authoring = checked.serverAuthoring();
        boolean exactAuthoring = authoring != null
            && ResourceManagementDescriptor.ServerAuthoring.RESOURCE_KIND.equals(authoring.kind())
            && authoring.version() == ResourceManagementDescriptor.ServerAuthoring.VERSION
            && authoring.capability().equals(acknowledgedManagementCapability);
        boolean createAvailable = checked.operations().stream()
            .filter(operation -> operation.operation() == ResourceOperationKind.CREATE)
            .anyMatch(operation -> operation.state() == ResourceManagementDescriptor.OperationState.AVAILABLE);
        if (!resource.type().equals(checked.resourceType())
            || !checked.checksum().equals(acknowledgedManagementDescriptorChecksum)
            || !exactAuthoring
            || !createAvailable) {
            throw new IllegalArgumentException("Resource authoring request does not match its exact management descriptor");
        }

        Map<InspectorFieldId, ResourceManagementDescriptor.Input> declarations = new LinkedHashMap<>();
        for (ResourceManagementDescriptor.Input input : authoring.inputs()) {
            declarations.put(input.id(), input);
        }
        for (InspectorFieldId id : authoringInputs.keySet()) {
            if (!declarations.containsKey(id)) {
                throw new IllegalArgumentException("Undeclared Resource authoring input: " + id);
            }
        }

        Map<InspectorFieldId, TypedValue> resolved = new LinkedHashMap<>();
        for (ResourceManagementDescriptor.Input input : authoring.inputs()) {
            TypedValue supplied = authoringInputs.get(input.id());
            if (supplied == null) {
                if (input.defaultValue() != null) {
                    resolved.put(input.id(), input.defaultValue());
                } else if (input.required()) {
                    throw new IllegalArgumentException("Required Resource authoring input is missing: " + input.id());
                }
                continue;
            }
            requireInputValue(input, supplied);
            resolved.put(input.id(), supplied);
        }
        return Collections.unmodifiableMap(resolved);
    }

    public ServerResourceLocator locator() {
        return resource;
    }

    public CatalogCacheKey catalogKey() {
        return acknowledgedCatalogKey;
    }

    public CatalogCacheKey acknowledgedPublicationKey() {
        return acknowledgedCatalogKey;
    }

    public CatalogCacheKey publicationIdentity() {
        return acknowledgedCatalogKey;
    }

    public CatalogCacheKey acknowledgedKey() {
        return acknowledgedCatalogKey;
    }

    private static Map<String, Object> freezeUnknown(Map<String, ?> unknown) {
        return IdentitySupport.unknown(unknown, "authoring template request unknown data");
    }

    private static Map<InspectorFieldId, TypedValue> freezeAuthoringInputs(Map<InspectorFieldId, TypedValue> values) {
        Objects.requireNonNull(values, "Resource authoring inputs are required");
        if (values.size() > ResourceManagementDescriptor.MAX_INPUTS) {
            throw new IllegalArgumentException("Resource authoring request contains too many inputs");
        }
        Map<InspectorFieldId, TypedValue> sorted = new TreeMap<>();
        for (Map.Entry<InspectorFieldId, TypedValue> entry : values.entrySet()) {
            InspectorFieldId id = Objects.requireNonNull(entry.getKey(), "Resource authoring input ID is required");
            TypedValue value = Objects.requireNonNull(entry.getValue(), "Resource authoring input value is required");
            if (value.state() == TypedValue.State.ABSENT) {
                throw new IllegalArgumentException("Resource authoring inputs cannot be absent");
            }
            if (sorted.put(id, value) != null) {
                throw new IllegalArgumentException("Duplicate Resource authoring input: " + id);
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    private static void requireInputValue(ResourceManagementDescriptor.Input input, TypedValue value) {
        if (!input.type().canonicalJson().equals(value.type().canonicalJson())) {
            throw new IllegalArgumentException("Resource authoring input type does not exactly match: " + input.id());
        }
        if (value.state() == TypedValue.State.ABSENT) {
            throw new IllegalArgumentException("Resource authoring input cannot be absent: " + input.id());
        }
        if (value.state() == TypedValue.State.NULL && !(input.type() instanceof TypeExpr.OptionalType)) {
            throw new IllegalArgumentException("Resource authoring input is not nullable: " + input.id());
        }
    }
}
