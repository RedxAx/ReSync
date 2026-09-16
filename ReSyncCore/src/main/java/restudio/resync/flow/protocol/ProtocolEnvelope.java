package restudio.resync.flow.protocol;

import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.protocol.ReSyncProtocolContract;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record ProtocolEnvelope<P>(
    Kind kind,
    CatalogVersion contractVersion,
    UUID messageId,
    UUID requestId,
    UUID correlationId,
    UUID traceId,
    ServerId serverId,
    ServerResourceLocator resource,
    long revision,
    long authorityEpoch,
    UUID mutationId,
    ContractRef<OperationId> operation,
    Set<ContractRef<CapabilityId>> capabilities,
    ContractRef<ResourceTypeId> payloadType,
    CanonicalPayload<P> canonicalPayload,
    ContentHash payloadHash,
    boolean deleted,
    CatalogVersion selectedVersion,
    ContentHash catalogChecksum,
    ContentHash bindingManifestHash,
    ProtocolEditability editability,
    String fallbackReason,
    long sequence,
    Status status,
    List<Diagnostic> diagnostics,
    Map<String, Object> unknown,
    ProtocolBody body
) {
    public ProtocolEnvelope(Kind kind, CatalogVersion contractVersion, UUID messageId, UUID requestId, UUID correlationId,
                            UUID traceId, ServerId serverId, ServerResourceLocator resource, long revision, UUID mutationId,
                            ContractRef<OperationId> operation, Set<ContractRef<CapabilityId>> capabilities,
                            ContractRef<ResourceTypeId> payloadType, CanonicalPayload<P> canonicalPayload, ContentHash payloadHash,
                            boolean deleted, CatalogVersion selectedVersion, ContentHash catalogChecksum,
                            ContentHash bindingManifestHash, ProtocolEditability editability, String fallbackReason, long sequence,
                            Status status, List<Diagnostic> diagnostics, Map<String, ?> unknown) {
        this(kind, contractVersion, messageId, requestId, correlationId, traceId == null ? correlationId : traceId, serverId, resource, revision, 0L,
            mutationId, operation, capabilities, payloadType, canonicalPayload, payloadHash, deleted, selectedVersion, catalogChecksum,
            bindingManifestHash, editability, fallbackReason, sequence, status, diagnostics, copyUnknown(unknown), null);
    }

    public ProtocolEnvelope(Kind kind, CatalogVersion contractVersion, UUID messageId, UUID requestId, UUID correlationId,
                            UUID traceId, ServerId serverId, ServerResourceLocator resource, long revision, UUID mutationId,
                            ContractRef<OperationId> operation, Set<ContractRef<CapabilityId>> capabilities,
                            ContractRef<ResourceTypeId> payloadType, CanonicalPayload<P> canonicalPayload, ContentHash payloadHash,
                            boolean deleted, CatalogVersion selectedVersion, ContentHash catalogChecksum,
                            ContentHash bindingManifestHash, ProtocolEditability editability, String fallbackReason, long sequence,
                            Status status, List<Diagnostic> diagnostics, Map<String, ?> unknown, ProtocolBody body) {
        this(kind, contractVersion, messageId, requestId, correlationId, traceId == null ? correlationId : traceId, serverId, resource, revision,
            0L, mutationId, operation, capabilities, payloadType, canonicalPayload, payloadHash, deleted, selectedVersion,
            catalogChecksum, bindingManifestHash, editability, fallbackReason, sequence, status, diagnostics, copyUnknown(unknown), body);
    }

    public ProtocolEnvelope(Kind kind, CatalogVersion contractVersion, UUID messageId, UUID requestId, UUID correlationId,
                            UUID traceId, ServerId serverId, ServerResourceLocator resource, long revision, long authorityEpoch,
                            UUID mutationId, ContractRef<OperationId> operation, Set<ContractRef<CapabilityId>> capabilities,
                            ContractRef<ResourceTypeId> payloadType, CanonicalPayload<P> canonicalPayload, ContentHash payloadHash,
                            boolean deleted, CatalogVersion selectedVersion, ContentHash catalogChecksum,
                            ContentHash bindingManifestHash, ProtocolEditability editability, String fallbackReason, long sequence,
                            Status status, List<Diagnostic> diagnostics, Map<String, ?> unknown) {
        this(kind, contractVersion, messageId, requestId, correlationId, traceId == null ? correlationId : traceId, serverId, resource, revision,
            authorityEpoch, mutationId, operation, capabilities, payloadType, canonicalPayload, payloadHash, deleted, selectedVersion,
            catalogChecksum, bindingManifestHash, editability, fallbackReason, sequence, status, diagnostics, copyUnknown(unknown), null);
    }

    public ProtocolEnvelope {
        kind = Objects.requireNonNull(kind, "kind");
        contractVersion = Objects.requireNonNull(contractVersion, "contractVersion");
        messageId = Objects.requireNonNull(messageId, "messageId");
        correlationId = Objects.requireNonNull(correlationId, "correlationId");
        traceId = traceId == null ? correlationId : traceId;
        traceId = Objects.requireNonNull(traceId, "traceId");
        serverId = Objects.requireNonNull(serverId, "serverId");
        if (resource != null && !serverId.equals(resource.serverId())) {
            throw new IllegalArgumentException("Resource server does not match envelope server");
        }
        revision = ProtocolValues.revision(revision, "revision");
        authorityEpoch = ProtocolValues.revision(authorityEpoch, "authorityEpoch");
        operation = Objects.requireNonNull(operation, "operation");
        capabilities = ProtocolValues.set(capabilities, "capabilities");
        payloadType = Objects.requireNonNull(payloadType, "payloadType");
        if (deleted && canonicalPayload != null) {
            throw new IllegalArgumentException("Deleted envelopes cannot carry payload");
        }
        if (kind == Kind.REQUEST && requestId == null) {
            throw new IllegalArgumentException("Request envelopes require requestId");
        }
        if (canonicalPayload != null && (payloadHash == null || !payloadHash.equals(canonicalPayload.checksum()))) {
            throw new IllegalArgumentException("Envelope checksum does not match canonical payload");
        }
        if (kind == Kind.NEGOTIATION && (body == null || body instanceof ProtocolBody.NegotiationResponse)) {
            selectedVersion = Objects.requireNonNull(selectedVersion, "selectedVersion");
            catalogChecksum = Objects.requireNonNull(catalogChecksum, "catalogChecksum");
            bindingManifestHash = Objects.requireNonNull(bindingManifestHash, "bindingManifestHash");
            editability = Objects.requireNonNull(editability, "editability");
            boolean requiredReason = !(body instanceof ProtocolBody.NegotiationResponse response)
                || response.result().outcome() != CapabilityOutcome.ADDITIVE;
            fallbackReason = requiredReason
                ? ProtocolValues.requiredText(fallbackReason, "fallbackReason", 512)
                : ProtocolValues.optionalText(fallbackReason, "fallbackReason", 512);
        } else {
            fallbackReason = ProtocolValues.optionalText(fallbackReason, "fallbackReason", 512);
        }
        sequence = ProtocolValues.revision(sequence, "sequence");
        status = Objects.requireNonNull(status, "status");
        diagnostics = ProtocolValues.list(diagnostics, "diagnostics");
        unknown = copyUnknown(unknown);
        if (body != null) {
            validateActivationContract(contractVersion, capabilities, body);
            validateBody(kind, status, body, requestId, serverId, resource, revision, mutationId, payloadHash, deleted,
                canonicalPayload);
        } else {
            validateLegacy(kind, requestId, resource, mutationId, payloadHash, deleted, canonicalPayload);
        }
    }

    public P payload() {
        if (canonicalPayload != null) {
            return canonicalPayload.value();
        }
        if (body instanceof ProtocolBody.ResourceDocumentResponse response) {
            @SuppressWarnings("unchecked")
            P value = (P) response.document().payload();
            return value;
        }
        if (body instanceof ProtocolBody.ResourceCreateResponse response) {
            @SuppressWarnings("unchecked")
            P value = (P) response.result().resource().payload();
            return value;
        }
        if (body instanceof ProtocolBody.ConflictResponse conflict && conflict.current() != null) {
            @SuppressWarnings("unchecked")
            P value = (P) conflict.current().payload();
            return value;
        }
        return null;
    }

    public boolean typedBody() {
        return body != null;
    }

    public static boolean supportsResourceActivation(CatalogVersion contractVersion,
                                                     Collection<? extends ContractRef<CapabilityId>> capabilities) {
        return ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT.supportsResourceActivation(contractVersion, capabilities);
    }

    public static boolean supportsResourceCreatePresentation(CatalogVersion contractVersion,
                                                              Collection<? extends ContractRef<CapabilityId>> capabilities) {
        return ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT.supportsResourceCreatePresentation(contractVersion, capabilities);
    }

    public static boolean supportsOptionQueries(CatalogVersion contractVersion,
                                                Collection<? extends ContractRef<CapabilityId>> capabilities) {
        return ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT.supportsOptionQueries(contractVersion, capabilities);
    }

    private static void validateActivationContract(CatalogVersion contractVersion, Set<ContractRef<CapabilityId>> capabilities,
                                                    ProtocolBody body) {
        boolean activationOperation = switch (body) {
            case ProtocolBody.ResourceRequest request -> request.operation().kind() == ResourceOperationKind.ACTIVATE;
            case ProtocolBody.ResourceDocumentResponse response -> response.operation() == ResourceOperationKind.ACTIVATE;
            default -> false;
        };
        boolean inactiveDocument = switch (body) {
            case ProtocolBody.ResourceDocumentResponse response -> isInactive(response.document());
            case ProtocolBody.ResourceCreateResponse response -> isInactive(response.result().resource())
                || isInactive(response.result().projectMetadata());
            case ProtocolBody.ResourcePageResponse response -> response.page().items().stream().anyMatch(ProtocolEnvelope::isInactive);
            case ProtocolBody.ConflictResponse response -> response.current() != null && isInactive(response.current());
            default -> false;
        };
        if ((activationOperation || inactiveDocument) && !supportsResourceActivation(contractVersion, capabilities)) {
            throw new IllegalArgumentException("Resource activation requires generic resource contract 1.1 and resource_activation capability");
        }
        boolean aggregateCreate = switch (body) {
            case ProtocolBody.ResourceRequest request -> request.operation() instanceof ResourceCreateRequest<?> create
                && create.presentation() != null;
            case ProtocolBody.ResourceCreateResponse ignored -> true;
            default -> false;
        };
        if (aggregateCreate && !supportsResourceCreatePresentation(contractVersion, capabilities)) {
            throw new IllegalArgumentException(
                "Resource create presentation requires generic resource contract 1.2 and resource_create_presentation capability");
        }
        boolean optionBody = body instanceof ProtocolBody.OptionQueryRequest || body instanceof ProtocolBody.OptionPageResponse
            || body instanceof ProtocolBody.OptionInvalidationEvent;
        if (optionBody && !supportsOptionQueries(contractVersion, capabilities)) {
            throw new IllegalArgumentException("Option protocol requires generic resource contract 1.3 and option_queries capability");
        }
    }

    private static boolean isInactive(ResourceDocument<?> document) {
        return !document.deleted() && document.activationState() == ResourceActivationState.INACTIVE;
    }

    private static void validateLegacy(Kind kind, UUID requestId, ServerResourceLocator resource, UUID mutationId,
                                       ContentHash payloadHash, boolean deleted, CanonicalPayload<?> canonicalPayload) {
        if (kind == Kind.REQUEST && requestId == null) {
            throw new IllegalArgumentException("Request envelopes require requestId");
        }
        if (kind == Kind.REQUEST && canonicalPayload == null) {
            throw new IllegalArgumentException("Legacy request envelopes require payload");
        }
        if (kind == Kind.ACK || kind == Kind.EVENT || kind == Kind.CONFLICT) {
            if (resource == null || mutationId == null || payloadHash == null) {
                throw new IllegalArgumentException("Resource events require resource, mutationId, and payloadHash");
            }
            if (!deleted && canonicalPayload == null) {
                throw new IllegalArgumentException("Live resource events require payload");
            }
        }
        if (kind == Kind.CONFLICT && requestId == null) {
            throw new IllegalArgumentException("Conflict envelopes require requestId");
        }
    }

    private static void validateBody(Kind kind, Status status, ProtocolBody body, UUID requestId, ServerId serverId,
                                     ServerResourceLocator resource, long revision, UUID mutationId, ContentHash payloadHash,
                                     boolean deleted, CanonicalPayload<?> legacyPayload) {
        boolean requestBody = body instanceof ProtocolBody.ResourceRequest || body instanceof ProtocolBody.OptionQueryRequest
            || body instanceof ProtocolBody.NegotiationRequest || body instanceof ProtocolBody.ControlRequest;
        if (kind == Kind.REQUEST && (!requestBody || requestId == null)) {
            throw new IllegalArgumentException("Request envelopes require a request body and requestId");
        }
        if (kind == Kind.REQUEST && legacyPayload != null) {
            throw new IllegalArgumentException("Typed request envelopes carry payloads in their operation body");
        }
        if (kind == Kind.ACK && !(body instanceof ProtocolBody.ResourceDocumentResponse)
            && !(body instanceof ProtocolBody.ResourceCreateResponse)) {
            throw new IllegalArgumentException("Resource acknowledgements require a resource document body");
        }
        if (kind == Kind.EVENT && !(body instanceof ProtocolBody.ResourceDocumentResponse)
            && !(body instanceof ProtocolBody.ResourceCreateResponse) && !(body instanceof ProtocolBody.OptionInvalidationEvent)) {
            throw new IllegalArgumentException("Events require a resource document or option invalidation body");
        }
        if (body instanceof ProtocolBody.OptionQueryRequest request) {
            if (kind != Kind.REQUEST || !serverId.equals(request.query().serverId())) {
                throw new IllegalArgumentException("Option queries require a matching-server request envelope");
            }
        }
        if (body instanceof ProtocolBody.OptionPageResponse && kind != Kind.RESPONSE) {
            throw new IllegalArgumentException("Option pages require a response envelope");
        }
        if (body instanceof ProtocolBody.OptionInvalidationEvent event) {
            if (kind != Kind.EVENT || !serverId.equals(event.invalidation().serverId())) {
                throw new IllegalArgumentException("Option invalidations require a matching-server event envelope");
            }
        }
        if (kind == Kind.CONFLICT) {
            if (!(body instanceof ProtocolBody.ConflictResponse conflict) || requestId == null || resource == null) {
                throw new IllegalArgumentException("Conflict envelopes require a requested resource boundary");
            }
            if (!resource.equals(conflict.requestedResource())) {
                throw new IllegalArgumentException("Conflict resource does not match its body");
            }
            if (conflict.current() == null) {
                if (revision != 0 || mutationId != null || payloadHash != null || deleted) {
                    throw new IllegalArgumentException("Null-current conflicts omit authoritative lifecycle fields");
                }
            } else {
                if (mutationId == null || payloadHash == null) {
                    throw new IllegalArgumentException("Conflicts with an authoritative current require lifecycle fields");
                }
                requireDocumentFields(conflict.current(), resource, mutationId, payloadHash, deleted);
            }
        }
        if (body instanceof ProtocolBody.ResourceDocumentResponse response) {
            ResourceDocument<?> document = response.document();
            requireDocumentFields(document, resource, mutationId, payloadHash, deleted);
        }
        if (body instanceof ProtocolBody.ResourceCreateResponse response) {
            requireDocumentFields(response.result().resource(), resource, mutationId, payloadHash, deleted);
            if (response.result().projectMetadata().deleted()) {
                throw new IllegalArgumentException("Aggregate create project metadata must be live");
            }
        }
        if (body instanceof ProtocolBody.ResourceRequest request) {
            ServerResourceLocator bodyResource = request.resource();
            if (bodyResource != null && (resource == null || !bodyResource.equals(resource))) {
                throw new IllegalArgumentException("Resource request locator does not match its envelope");
            }
            if (bodyResource == null && resource != null && request.operation().kind() != ResourceOperationKind.SUBSCRIBE) {
                throw new IllegalArgumentException("Resource envelope locator is not represented by its operation body");
            }
            switch (request.operation()) {
                case ResourceCreateRequest<?> create -> requireMutationRequest(create.mutationId(), create.payloadHash(), revision,
                    mutationId, payloadHash);
                case ResourceSaveRequest<?> save -> requireMutationRequest(save.mutationId(), save.payloadHash(), save.expectedRevision(),
                    mutationId, payloadHash);
                case ResourceRenameRequest rename -> requireRevisionMutation(rename.mutationId(), rename.expectedRevision(), revision,
                    mutationId);
                case ResourceMoveRequest move -> requireRevisionMutation(move.mutationId(), move.expectedRevision(), revision, mutationId);
                case ResourceDuplicateRequest duplicate -> requireRevisionMutation(duplicate.mutationId(), duplicate.expectedRevision(), revision,
                    mutationId);
                case ResourceActivateRequest activate -> requireRevisionMutation(activate.mutationId(), activate.expectedRevision(), revision,
                    mutationId);
                case ResourceDeleteRequest delete -> requireRevisionMutation(delete.mutationId(), delete.expectedRevision(), revision,
                    mutationId);
                default -> {
                }
            }
        }
        if (body.bodyKind() == ProtocolBody.BodyKind.RESOURCE_PAGE && (resource != null || mutationId != null || payloadHash != null)) {
            throw new IllegalArgumentException("Resource pages cannot carry single-resource lifecycle fields");
        }
        if (!body.resourceBody() && (resource != null || mutationId != null || payloadHash != null || deleted)) {
            if (!(body instanceof ProtocolBody.ConflictResponse) && !isResourceRejection(kind, status, body)) {
                throw new IllegalArgumentException("Control and negotiation bodies cannot carry resource lifecycle fields");
            }
        }
    }

    private static boolean isResourceRejection(Kind kind, Status status, ProtocolBody body) {
        return kind == Kind.RESPONSE && (status == Status.REJECTED || status == Status.NOT_FOUND
            || status == Status.READ_ONLY || status == Status.UPGRADE_REQUIRED)
            && body instanceof ProtocolBody.ControlResponse response
            && "resource.rejection".equals(response.action());
    }

    private static void requireMutationRequest(UUID operationMutation, ContentHash operationHash, long expectedRevision,
                                               UUID envelopeMutation, ContentHash envelopeHash) {
        if (envelopeMutation == null || envelopeHash == null || !envelopeMutation.equals(operationMutation)
            || !envelopeHash.equals(operationHash)) {
            throw new IllegalArgumentException("Resource mutation fields do not match the operation body");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("Expected revision must not be negative");
        }
    }

    private static void requireRevisionMutation(UUID operationMutation, long expectedRevision, long envelopeRevision,
                                                UUID envelopeMutation) {
        if (envelopeMutation == null || !envelopeMutation.equals(operationMutation) || expectedRevision != envelopeRevision) {
            throw new IllegalArgumentException("Resource revision and mutation fields do not match the operation body");
        }
    }

    private static void requireDocumentFields(ResourceDocument<?> document, ServerResourceLocator resource, UUID mutationId,
                                              ContentHash payloadHash, boolean deleted) {
        if (resource == null || mutationId == null || payloadHash == null) {
            throw new IllegalArgumentException("Resource document envelopes require locator, mutation, and hash");
        }
        if (!resource.equals(document.resource()) || mutationId == null || !mutationId.equals(document.mutationId())
            || !payloadHash.equals(document.payloadHash()) || deleted != document.deleted()) {
            throw new IllegalArgumentException("Resource document does not match envelope lifecycle fields");
        }
    }

    private static Map<String, Object> copyUnknown(Map<String, ?> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("Unknown field names must be non-blank");
            }
            copy.put(key, Objects.requireNonNull(value, "Unknown field values"));
        });
        return Map.copyOf(copy);
    }

    public enum Kind {
        REQUEST,
        RESPONSE,
        ACK,
        EVENT,
        CONFLICT,
        NEGOTIATION
    }

    public enum Status {
        OK,
        ACCEPTED,
        REJECTED,
        CONFLICT,
        NOT_FOUND,
        READ_ONLY,
        UPGRADE_REQUIRED
    }

}
