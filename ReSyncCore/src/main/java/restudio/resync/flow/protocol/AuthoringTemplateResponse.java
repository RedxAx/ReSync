package restudio.resync.flow.protocol;

import restudio.resync.flow.authoring.AuthoringTemplatePayload;
import restudio.resync.flow.authoring.AuthoringTemplateLimits;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.IdentitySupport;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record AuthoringTemplateResponse(
    ServerResourceLocator resource,
    CatalogCacheKey publicationKey,
    CatalogBinding catalogBinding,
    AuthoringTemplatePayload payload,
    ContentHash templateChecksum,
    ContentHash authoringPublicationChecksum,
    Set<ContractRef<CapabilityId>> editCapabilities,
    Set<ContractRef<CapabilityId>> requiredCapabilities,
    ContentHash managementDescriptorChecksum,
    ContractRef<CapabilityId> managementCapability,
    Map<String, Object> unknown
) {
    private static final Set<String> KNOWN_FIELDS = Set.of("kind", "version", "resource", "publicationKey",
        "catalogBinding", "payload", "templateChecksum", "authoringPublicationChecksum", "editCapabilities",
        "requiredCapabilities", "managementDescriptorChecksum", "managementCapability");

    public AuthoringTemplateResponse {
        resource = Objects.requireNonNull(resource, "Authoring template resource is required");
        publicationKey = Objects.requireNonNull(publicationKey, "Authoring template publication key is required");
        catalogBinding = Objects.requireNonNull(catalogBinding, "Authoring template catalog binding is required");
        payload = Objects.requireNonNull(payload, "Authoring template payload is required");
        templateChecksum = Objects.requireNonNull(templateChecksum, "Authoring template checksum is required");
        editCapabilities = immutableCapabilities(editCapabilities, "edit capabilities");
        requiredCapabilities = immutableCapabilities(requiredCapabilities, "required capabilities");
        if (!resource.serverId().equals(publicationKey.serverId()) || !resource.equals(payload.resource())) {
            throw new IllegalArgumentException("Authoring template resource does not match its publication identity");
        }
        if (!publicationKey.hasCatalogBinding() || !catalogBinding.equals(publicationKey.catalogBinding())) {
            throw new IllegalArgumentException("Authoring template binding does not match its publication identity");
        }
        if (!catalogBinding.equals(payload.catalogBinding())) {
            throw new IllegalArgumentException("Authoring template payload does not match its catalog binding");
        }
        if (!templateChecksum.equals(payload.checksum())) {
            throw new IllegalArgumentException("Authoring template checksum does not match its payload");
        }
        if (!requiredCapabilities.equals(payload.requiredCapabilities())) {
            throw new IllegalArgumentException("Authoring template required capabilities do not match its payload");
        }
        boolean resourcePayload = payload.kind() == AuthoringTemplatePayload.Kind.RESOURCE;
        if (resourcePayload && authoringPublicationChecksum == null) {
            throw new IllegalArgumentException("Resource authoring requires an exact authoring publication checksum");
        }
        boolean completeManagement = managementDescriptorChecksum != null && managementCapability != null;
        if (resourcePayload != completeManagement || (!resourcePayload
            && (managementDescriptorChecksum != null || managementCapability != null))) {
            throw new IllegalArgumentException("Management acknowledgement is required exactly for Resource templates");
        }
        if (resourcePayload && (!editCapabilities.contains(managementCapability)
            || !requiredCapabilities.contains(managementCapability))) {
            throw new IllegalArgumentException("Resource management capability must be both editable and required");
        }
        unknown = IdentitySupport.unknown(unknown, "authoring template response unknown data");
        for (String field : unknown.keySet()) {
            if (KNOWN_FIELDS.contains(field)) {
                throw new IllegalArgumentException("Unknown authoring template response field collides with known field: " + field);
            }
        }
    }

    public static AuthoringTemplateResponse of(ServerResourceLocator resource, CatalogCacheKey publicationKey,
                                               CatalogBinding catalogBinding, AuthoringTemplatePayload payload,
                                               Set<ContractRef<CapabilityId>> editCapabilities) {
        return of(resource, publicationKey, catalogBinding, payload, null, editCapabilities);
    }

    public static AuthoringTemplateResponse of(ServerResourceLocator resource, CatalogCacheKey publicationKey,
                                               CatalogBinding catalogBinding, AuthoringTemplatePayload payload,
                                               ContentHash authoringPublicationChecksum,
                                               Set<ContractRef<CapabilityId>> editCapabilities) {
        Objects.requireNonNull(payload, "Authoring template payload is required");
        return new AuthoringTemplateResponse(resource, publicationKey, catalogBinding, payload, payload.checksum(),
            authoringPublicationChecksum, editCapabilities, payload.requiredCapabilities(), null, null, Map.of());
    }

    public static AuthoringTemplateResponse ofResource(ServerResourceLocator resource, CatalogCacheKey publicationKey,
                                                       CatalogBinding catalogBinding,
                                                       AuthoringTemplatePayload.Resource payload,
                                                       ContentHash authoringPublicationChecksum,
                                                       Set<ContractRef<CapabilityId>> editCapabilities,
                                                       ContentHash managementDescriptorChecksum,
                                                       ContractRef<CapabilityId> managementCapability) {
        Objects.requireNonNull(payload, "Authoring template payload is required");
        Objects.requireNonNull(authoringPublicationChecksum,
            "Resource authoring publication checksum is required");
        return new AuthoringTemplateResponse(resource, publicationKey, catalogBinding, payload, payload.checksum(),
            authoringPublicationChecksum, editCapabilities, payload.requiredCapabilities(), managementDescriptorChecksum,
            managementCapability, Map.of());
    }

    public AuthoringTemplateResponse(ServerResourceLocator resource, CatalogCacheKey publicationKey,
                                     CatalogBinding catalogBinding, AuthoringTemplatePayload payload,
                                     ContentHash templateChecksum, Set<ContractRef<CapabilityId>> editCapabilities,
                                     Set<ContractRef<CapabilityId>> requiredCapabilities) {
        this(resource, publicationKey, catalogBinding, payload, templateChecksum, null, editCapabilities,
            requiredCapabilities, null, null, Map.of());
    }

    public AuthoringTemplateResponse(ServerResourceLocator resource, CatalogCacheKey publicationKey,
                                     CatalogBinding catalogBinding, AuthoringTemplatePayload payload,
                                     ContentHash templateChecksum, Set<ContractRef<CapabilityId>> editCapabilities,
                                     Set<ContractRef<CapabilityId>> requiredCapabilities, Map<String, ?> unknown) {
        this(resource, publicationKey, catalogBinding, payload, templateChecksum, null, editCapabilities,
            requiredCapabilities, null, null, IdentitySupport.unknown(unknown, "authoring template response unknown data"));
    }

    public AuthoringTemplateResponse(ServerResourceLocator resource, CatalogCacheKey publicationKey,
                                     CatalogBinding catalogBinding, AuthoringTemplatePayload payload,
                                     ContentHash templateChecksum, ContentHash authoringPublicationChecksum,
                                     Set<ContractRef<CapabilityId>> editCapabilities,
                                     Set<ContractRef<CapabilityId>> requiredCapabilities) {
        this(resource, publicationKey, catalogBinding, payload, templateChecksum, authoringPublicationChecksum,
            editCapabilities, requiredCapabilities, null, null, Map.of());
    }

    public AuthoringTemplateResponse(ServerResourceLocator resource, CatalogCacheKey publicationKey,
                                     CatalogBinding catalogBinding, AuthoringTemplatePayload payload,
                                     ContentHash templateChecksum, ContentHash authoringPublicationChecksum,
                                     Set<ContractRef<CapabilityId>> editCapabilities,
                                     Set<ContractRef<CapabilityId>> requiredCapabilities, Map<String, ?> unknown) {
        this(resource, publicationKey, catalogBinding, payload, templateChecksum, authoringPublicationChecksum,
            editCapabilities, requiredCapabilities, null, null,
            IdentitySupport.unknown(unknown, "authoring template response unknown data"));
    }

    public AuthoringTemplateResponse(ServerResourceLocator resource, CatalogCacheKey publicationKey,
                                     CatalogBinding catalogBinding, AuthoringTemplatePayload payload,
                                     ContentHash templateChecksum, ContentHash authoringPublicationChecksum,
                                     Set<ContractRef<CapabilityId>> editCapabilities,
                                     Set<ContractRef<CapabilityId>> requiredCapabilities,
                                     ContentHash managementDescriptorChecksum,
                                     ContractRef<CapabilityId> managementCapability) {
        this(resource, publicationKey, catalogBinding, payload, templateChecksum, authoringPublicationChecksum,
            editCapabilities, requiredCapabilities, managementDescriptorChecksum, managementCapability, Map.of());
    }

    public boolean hasAuthoringPublicationChecksum() {
        return authoringPublicationChecksum != null;
    }

    public boolean hasManagementAcknowledgement() {
        return managementDescriptorChecksum != null;
    }

    public AuthoringTemplateResponse requireAcknowledgements(AuthoringTemplateRequest request) {
        AuthoringTemplateRequest checked = Objects.requireNonNull(request, "Authoring template request is required");
        boolean exact = payload.kind() == AuthoringTemplatePayload.Kind.RESOURCE
            && checked.hasManagementAcknowledgement()
            && resource.equals(checked.resource())
            && publicationKey.equals(checked.acknowledgedCatalogKey())
            && catalogBinding.equals(checked.acknowledgedCatalogKey().catalogBinding())
            && authoringPublicationChecksum.equals(checked.acknowledgedAuthoringPublicationChecksum())
            && checked.expectedTemplateKind() == AuthoringTemplatePayload.Kind.RESOURCE
            && checked.expectedTemplateVersion() == AuthoringTemplatePayload.Resource.VERSION
            && managementDescriptorChecksum.equals(checked.acknowledgedManagementDescriptorChecksum())
            && managementCapability.equals(checked.acknowledgedManagementCapability());
        if (!exact) {
            throw new IllegalArgumentException("Resource authoring response does not exactly acknowledge its request");
        }
        return this;
    }

    public CatalogCacheKey catalogKey() {
        return publicationKey;
    }

    public CatalogCacheKey acknowledgedPublicationKey() {
        return publicationKey;
    }

    public CatalogBinding binding() {
        return catalogBinding;
    }

    public Set<ContractRef<CapabilityId>> capabilitiesRequired() {
        return requiredCapabilities;
    }

    public Set<ContractRef<CapabilityId>> capabilitiesForEdit() {
        return editCapabilities;
    }

    public Set<ContractRef<CapabilityId>> advertisedEditCapabilities() {
        return editCapabilities;
    }

    private static Set<ContractRef<CapabilityId>> immutableCapabilities(Set<ContractRef<CapabilityId>> values, String label) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        if (values.size() > AuthoringTemplateLimits.MAX_CAPABILITIES) {
            throw new IllegalArgumentException("Authoring template " + label + " contains too many capabilities");
        }
        return Set.copyOf(values.stream()
            .map(value -> Objects.requireNonNull(value, label + " cannot contain null"))
            .toList());
    }
}
