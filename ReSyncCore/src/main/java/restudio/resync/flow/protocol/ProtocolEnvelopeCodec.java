package restudio.resync.flow.protocol;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.contract.diagnostic.DiagnosticCodeCatalog;
import restudio.resync.contract.identity.IdentityCodec;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.diagnostic.DiagnosticMetricPolicy;
import restudio.resync.flow.diagnostic.DiagnosticPhase;
import restudio.resync.flow.diagnostic.DiagnosticProvenance;
import restudio.resync.flow.diagnostic.DiagnosticRedaction;
import restudio.resync.flow.diagnostic.DiagnosticSeverity;
import restudio.resync.flow.diagnostic.DiagnosticSourceKind;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

public final class ProtocolEnvelopeCodec<P> {
    private static final String BODY = "body";
    private final ProtocolPayloadCodec<P> payloadCodec;
    private final Function<Object, P> payloadDecoder;

    public ProtocolEnvelopeCodec(ProtocolPayloadCodec<P> payloadCodec) {
        this(payloadCodec, value -> {
            @SuppressWarnings("unchecked")
            P payload = (P) value;
            return payload;
        });
    }

    public ProtocolEnvelopeCodec(ProtocolPayloadCodec<P> payloadCodec, Function<Object, P> payloadDecoder) {
        this.payloadCodec = Objects.requireNonNull(payloadCodec, "payloadCodec");
        this.payloadDecoder = Objects.requireNonNull(payloadDecoder, "payloadDecoder");
    }

    public byte[] encodeBytes(ProtocolEnvelope<P> envelope) {
        return encode(envelope).canonicalBytes();
    }

    public String encodeText(ProtocolEnvelope<P> envelope) {
        return encode(envelope).canonicalText();
    }

    public JsonValue.JsonObject encode(ProtocolEnvelope<P> envelope) {
        Objects.requireNonNull(envelope, "envelope");
        if (!envelope.typedBody()) {
            throw new IllegalArgumentException("Typed envelope body is required for wire encoding");
        }
        boolean activationSupported = ProtocolEnvelope.supportsResourceActivation(envelope.contractVersion(), envelope.capabilities());
        boolean presentationSupported = ProtocolEnvelope.supportsResourceCreatePresentation(envelope.contractVersion(), envelope.capabilities());
        boolean optionSupported = ProtocolEnvelope.supportsOptionQueries(envelope.contractVersion(), envelope.capabilities());
        Map<String, Object> known = new LinkedHashMap<>();
        known.put("body", encodeBody(envelope.body(), activationSupported, presentationSupported, optionSupported));
        known.put("capabilities", envelope.capabilities().stream()
            .sorted(Comparator.comparing(ContractRef::canonicalText)).map(IdentityCodec::encode).toList());
        known.put("contractVersion", version(envelope.contractVersion()));
        known.put("correlationId", envelope.correlationId().toString());
        known.put("diagnostics", envelope.diagnostics().stream().map(Diagnostic::toMap).toList());
        known.put("kind", lower(envelope.kind().name()));
        known.put("messageId", envelope.messageId().toString());
        known.put("operation", IdentityCodec.encode(envelope.operation()));
        known.put("payloadType", IdentityCodec.encode(envelope.payloadType()));
        known.put("sequence", envelope.sequence());
        known.put("serverId", envelope.serverId().canonicalText());
        known.put("status", lower(envelope.status().name()));
        known.put("statusCode", ProtocolStatusCode.fromEnvelopeStatus(envelope.status()).wireValue());
        known.put("traceId", envelope.traceId().toString());
        if (envelope.requestId() != null) {
            known.put("requestId", envelope.requestId().toString());
        }
        if (envelope.resource() != null) {
            known.put("resource", IdentityCodec.encode(envelope.resource()));
        }
        if (includeRevision(envelope)) {
            known.put("revision", envelope.revision());
        }
        if (envelope.authorityEpoch() > 0L) {
            known.put("authorityEpoch", envelope.authorityEpoch());
        }
        if (envelope.mutationId() != null) {
            known.put("mutationId", envelope.mutationId().toString());
        }
        if (envelope.payloadHash() != null) {
            known.put("payloadHash", envelope.payloadHash().canonicalText());
        }
        if (includeDeleted(envelope)) {
            known.put("deleted", envelope.deleted());
        }
        if (envelope.selectedVersion() != null) {
            known.put("selectedVersion", version(envelope.selectedVersion()));
        }
        if (envelope.catalogChecksum() != null) {
            known.put("catalogChecksum", envelope.catalogChecksum().canonicalText());
        }
        if (envelope.bindingManifestHash() != null) {
            known.put("bindingManifestHash", envelope.bindingManifestHash().canonicalText());
        }
        if (envelope.editability() != null) {
            known.put("editability", lower(envelope.editability().name()));
        }
        if (envelope.fallbackReason() != null) {
            known.put("fallbackReason", envelope.fallbackReason());
        }
        return object(known, envelope.unknown());
    }

    public ProtocolEnvelope<P> decodeBytes(byte[] input) {
        return decode(CanonicalCodec.decode(Objects.requireNonNull(input, "input")));
    }

    public ProtocolEnvelope<P> decodeText(String input) {
        return decode(CanonicalCodec.decode(Objects.requireNonNull(input, "input")));
    }

    public ProtocolEnvelope<P> decode(JsonValue value) {
        JsonValue.JsonObject object = object(value, "Envelope");
        Set<String> known = Set.of("body", "capabilities", "contractVersion", "correlationId", "diagnostics", "kind", "messageId",
            "operation", "payloadType", "sequence", "serverId", "status", "statusCode", "traceId", "requestId", "resource", "revision", "authorityEpoch",
            "mutationId", "payloadHash", "deleted", "selectedVersion", "catalogChecksum", "bindingManifestHash", "editability",
            "fallbackReason");
        ProtocolEnvelope.Kind kind = enumValue(text(object, "kind"), ProtocolEnvelope.Kind.class);
        CatalogVersion contractVersion = decodeVersion(require(object, "contractVersion"));
        Set<ContractRef<CapabilityId>> capabilities = decodeCapabilityRefs(requireArray(object, "capabilities"));
        boolean activationSupported = ProtocolEnvelope.supportsResourceActivation(contractVersion, capabilities);
        boolean presentationSupported = ProtocolEnvelope.supportsResourceCreatePresentation(contractVersion, capabilities);
        boolean optionSupported = ProtocolEnvelope.supportsOptionQueries(contractVersion, capabilities);
        ServerId serverId = ServerId.parseCanonicalText(text(object, "serverId"));
        ProtocolBody body = decodeBody(require(object, "body"), activationSupported, presentationSupported, optionSupported, serverId);
        ServerResourceLocator resource = optional(object, "resource").map(IdentityCodec::decodeLocator).orElse(null);
        ContentHash payloadHash = optionalText(object, "payloadHash").map(ContentHash::new).orElse(null);
        UUID mutationId = optionalText(object, "mutationId").map(UUID::fromString).orElse(null);
        long revision = optionalLong(object, "revision").orElse(0L);
        long authorityEpoch = optionalLong(object, "authorityEpoch").orElse(0L);
        boolean deleted = optionalBoolean(object, "deleted").orElse(false);
        rejectNullCurrentConflictLifecycleFields(object, body);
        ProtocolStatusCode statusCode = ProtocolStatusCode.fromWire(text(object, "status"));
        optionalText(object, "statusCode").map(ProtocolStatusCode::fromWire).ifPresent(declared -> {
            if (declared != statusCode) {
                throw new IllegalArgumentException("Protocol status and statusCode do not match");
            }
        });
        ProtocolEnvelope<P> envelope = new ProtocolEnvelope<>(
            kind,
            contractVersion,
            UUID.fromString(text(object, "messageId")),
            optionalText(object, "requestId").map(UUID::fromString).orElse(null),
            UUID.fromString(text(object, "correlationId")),
            UUID.fromString(text(object, "traceId")),
            serverId,
            resource,
            revision,
            authorityEpoch,
            mutationId,
            IdentityCodec.decodeReference(require(object, "operation"), OperationId::new),
            capabilities,
            IdentityCodec.decodeReference(require(object, "payloadType"), ResourceTypeId::new),
            null,
            payloadHash,
            deleted,
            optional(object, "selectedVersion").map(ProtocolEnvelopeCodec::decodeVersion).orElse(null),
            optionalText(object, "catalogChecksum").map(ContentHash::new).orElse(null),
            optionalText(object, "bindingManifestHash").map(ContentHash::new).orElse(null),
            optionalText(object, "editability").map(editability -> enumValue(editability, ProtocolEditability.class)).orElse(null),
            optionalText(object, "fallbackReason").orElse(null),
            requireLong(object, "sequence"),
            statusCode.envelopeStatus(),
            decodeDiagnostics(requireArray(object, "diagnostics")),
            unknown(object, known),
            body);
        verifyEnvelopePayload(envelope);
        return envelope;
    }

    private void verifyEnvelopePayload(ProtocolEnvelope<P> envelope) {
        if (envelope.body() instanceof ProtocolBody.ResourceRequest request) {
            if (request.operation() instanceof ResourceCreateRequest<?> create) {
                verifyPayload(create.payload(), create.payloadHash());
            } else if (request.operation() instanceof ResourceSaveRequest<?> save) {
                verifyPayload(save.payload(), save.payloadHash());
            }
        }
        if (envelope.body() instanceof ProtocolBody.ResourceDocumentResponse response && !response.document().deleted()) {
            verifyPayload(response.document().payload(), response.document().payloadHash());
        }
        if (envelope.body() instanceof ProtocolBody.ResourceCreateResponse response) {
            verifyPayload(response.result().resource().payload(), response.result().resource().payloadHash());
            verifyPayload(response.result().projectMetadata().payload(), response.result().projectMetadata().payloadHash());
        }
        if (envelope.body() instanceof ProtocolBody.ResourcePageResponse response) {
            for (ResourceDocument<?> document : response.page().items()) {
                if (!document.deleted()) {
                    verifyPayload(document.payload(), document.payloadHash());
                }
            }
        }
        if (envelope.body() instanceof ProtocolBody.ConflictResponse conflict && conflict.current() != null && !conflict.current().deleted()) {
            verifyPayload(conflict.current().payload(), conflict.current().payloadHash());
        }
    }

    private void verifyPayload(Object payload, ContentHash expected) {
        @SuppressWarnings("unchecked")
        P typed = (P) payload;
        payloadCodec.verify(typed, expected);
    }

    private static void rejectNullCurrentConflictLifecycleFields(JsonValue.JsonObject envelope, ProtocolBody body) {
        if (!(body instanceof ProtocolBody.ConflictResponse conflict) || conflict.current() != null) {
            return;
        }
        if (envelope.value("revision") != null || envelope.value("mutationId") != null || envelope.value("payloadHash") != null
            || envelope.value("deleted") != null) {
            throw new IllegalArgumentException("Null-current conflicts omit authoritative lifecycle fields");
        }
    }

    private JsonValue encodeBody(ProtocolBody body, boolean activationSupported, boolean presentationSupported,
                                 boolean optionSupported) {
        Map<String, Object> known = new LinkedHashMap<>();
        known.put("bodyKind", lower(body.bodyKind().name()));
        switch (body) {
            case ProtocolBody.ResourceRequest request -> encodeResourceRequest(known, request.operation(), activationSupported,
                presentationSupported);
            case ProtocolBody.ResourceDocumentResponse response -> {
                known.put("operation", lower(response.operation().name()));
                known.put("document", encodeDocument(response.document(), activationSupported));
            }
            case ProtocolBody.ResourceCreateResponse response -> {
                known.put("operation", "create");
                known.put("document", encodeDocument(response.result().resource(), activationSupported));
                known.put("projectMetadata", encodeDocument(response.result().projectMetadata(), activationSupported));
                known.put("presentation", presentation(response.result().presentation()));
            }
            case ProtocolBody.ResourcePageResponse response -> {
                known.put("operation", lower(response.operation().name()));
                known.put("items", response.page().items().stream().map(document -> encodeDocument(document, activationSupported)).toList());
                known.put("complete", response.page().complete());
                if (response.page().nextCursor() != null) {
                    known.put("nextCursor", response.page().nextCursor());
                }
            }
            case ProtocolBody.OptionQueryRequest request -> {
                requireOptionProtocol(optionSupported);
                known.putAll(optionQuery(request.query()));
            }
            case ProtocolBody.OptionPageResponse response -> {
                requireOptionProtocol(optionSupported);
                known.putAll(optionPage(response.page()));
            }
            case ProtocolBody.OptionInvalidationEvent event -> {
                requireOptionProtocol(optionSupported);
                known.putAll(optionInvalidation(event.invalidation()));
            }
            case ProtocolBody.NegotiationRequest request -> known.putAll(negotiationRequest(request.request()));
            case ProtocolBody.NegotiationResponse response -> known.putAll(negotiationResult(response.result()));
            case ProtocolBody.ControlRequest request -> {
                known.put("action", request.action());
                known.put("values", request.values());
            }
            case ProtocolBody.ControlResponse response -> {
                known.put("action", response.action());
                known.put("values", response.values());
            }
            case ProtocolBody.ConflictResponse conflict -> {
                known.put("requestedResource", IdentityCodec.encode(conflict.requestedResource()));
                if (conflict.current() != null) {
                    known.put("current", encodeDocument(conflict.current(), activationSupported));
                }
            }
            case ProtocolBody.EmptyResponse ignored -> {
            }
        }
        return object(known, body.unknown());
    }

    private void encodeResourceRequest(Map<String, Object> known, ResourceOperation operation, boolean activationSupported,
                                       boolean presentationSupported) {
        known.put("operation", lower(operation.kind().name()));
        switch (operation) {
            case ResourceListRequest request -> {
                known.put("type", IdentityCodec.encode(request.type()));
                putOptional(known, "cursor", request.cursor());
                known.put("limit", request.limit());
                putOptional(known, "search", request.search());
            }
            case ResourceQueryRequest request -> {
                known.put("type", IdentityCodec.encode(request.type()));
                known.put("filters", typedMap(request.filters()));
                putOptional(known, "cursor", request.cursor());
                known.put("limit", request.limit());
                putOptional(known, "search", request.search());
            }
            case ResourceLoadRequest request -> known.put("resource", IdentityCodec.encode(request.resource()));
            case ResourceCreateRequest<?> request -> {
                known.put("resource", IdentityCodec.encode(request.resource()));
                known.put("mutationId", request.mutationId().toString());
                known.put("payloadHash", request.payloadHash().canonicalText());
                known.put("payload", payloadValue(request.payload()));
                if (request.presentation() != null) {
                    if (!presentationSupported) {
                        throw new IllegalArgumentException(
                            "Resource create presentation requires generic resource contract 1.2 and resource_create_presentation capability");
                    }
                    known.put("presentation", presentation(request.presentation()));
                }
            }
            case ResourceSaveRequest<?> request -> {
                known.put("resource", IdentityCodec.encode(request.resource()));
                known.put("expectedRevision", request.expectedRevision());
                known.put("mutationId", request.mutationId().toString());
                known.put("payloadHash", request.payloadHash().canonicalText());
                known.put("payload", payloadValue(request.payload()));
            }
            case ResourceRenameRequest request -> {
                known.put("resource", IdentityCodec.encode(request.resource()));
                known.put("expectedRevision", request.expectedRevision());
                known.put("newName", request.newName());
                known.put("mutationId", request.mutationId().toString());
            }
            case ResourceMoveRequest request -> {
                known.put("resource", IdentityCodec.encode(request.resource()));
                known.put("expectedRevision", request.expectedRevision());
                known.put("destination", request.destination());
                known.put("mutationId", request.mutationId().toString());
            }
            case ResourceDuplicateRequest request -> {
                known.put("source", IdentityCodec.encode(request.source()));
                known.put("target", IdentityCodec.encode(request.target()));
                known.put("expectedRevision", request.expectedRevision());
                known.put("mutationId", request.mutationId().toString());
            }
            case ResourceActivateRequest request -> {
                if (!activationSupported) {
                    throw new IllegalArgumentException("Resource activation requires generic resource contract 1.1 and resource_activation capability");
                }
                known.put("resource", IdentityCodec.encode(request.resource()));
                known.put("expectedRevision", request.expectedRevision());
                known.put("mutationId", request.mutationId().toString());
                known.put("targetState", request.targetState().wireName());
            }
            case ResourceDeleteRequest request -> {
                known.put("resource", IdentityCodec.encode(request.resource()));
                known.put("expectedRevision", request.expectedRevision());
                known.put("mutationId", request.mutationId().toString());
            }
            case ResourceSubscribeRequest request -> {
                if (request.type() != null) {
                    known.put("type", IdentityCodec.encode(request.type()));
                }
                if (request.resource() != null) {
                    known.put("resource", IdentityCodec.encode(request.resource()));
                }
                known.put("afterRevision", request.afterRevision());
                known.put("includePayload", request.includePayload());
            }
        }
    }

    private JsonValue encodeDocument(ResourceDocument<?> document, boolean activationSupported) {
        Map<String, Object> known = new LinkedHashMap<>();
        known.put("deleted", document.deleted());
        known.put("locator", IdentityCodec.encode(document.resource()));
        known.put("mutationId", document.mutationId().toString());
        known.put("payloadHash", document.payloadHash().canonicalText());
        known.put("revision", document.revision());
        if (!document.deleted()) {
            known.put("payload", payloadValue(document.payload()));
            if (activationSupported) {
                known.put("activationState", document.activationState().wireName());
            }
        }
        putOptional(known, "author", document.author());
        return object(known, Map.of());
    }

    private Map<String, Object> optionQuery(OptionQuery query) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("sourceRef", IdentityCodec.encode(query.sourceRef()));
        value.put("query", IdentityCodec.encode(query.query()));
        value.put("serverId", query.serverId().canonicalText());
        if (query.resource() != null) {
            value.put("resource", IdentityCodec.encode(query.resource()));
        }
        value.put("context", typedMap(query.context()));
        value.put("dependencies", typedMap(query.dependencies()));
        putOptional(value, "cursor", query.cursor());
        value.put("limit", query.limit());
        putOptional(value, "search", query.search());
        value.put("revision", query.revision());
        value.put("invalidationKey", query.invalidationKey());
        return value;
    }

    private Map<String, Object> optionPage(OptionPage page) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("sourceRef", IdentityCodec.encode(page.sourceRef()));
        value.put("query", IdentityCodec.encode(page.query()));
        value.put("revision", page.revision());
        value.put("invalidationKey", page.invalidationKey());
        value.put("items", page.items().stream().map(item -> {
            Map<String, Object> itemValue = new LinkedHashMap<>();
            itemValue.put("value", item.value().canonicalValue());
            itemValue.put("label", item.label());
            itemValue.put("description", item.description());
            itemValue.put("available", item.available());
            putOptional(itemValue, "reason", item.reason());
            return itemValue;
        }).toList());
        putOptional(value, "nextCursor", page.nextCursor());
        value.put("complete", page.complete());
        value.put("diagnostics", page.diagnostics().stream().map(Diagnostic::toMap).toList());
        return value;
    }

    private Map<String, Object> optionInvalidation(OptionInvalidation invalidation) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("sourceRef", IdentityCodec.encode(invalidation.sourceRef()));
        value.put("query", IdentityCodec.encode(invalidation.query()));
        value.put("serverId", invalidation.serverId().canonicalText());
        if (invalidation.resource() != null) {
            value.put("resource", IdentityCodec.encode(invalidation.resource()));
        }
        value.put("revision", invalidation.revision());
        value.put("invalidationKey", invalidation.invalidationKey());
        value.put("dependencyKeys", invalidation.dependencyKeys().stream().sorted().toList());
        return value;
    }

    private Map<String, Object> negotiationRequest(CapabilityNegotiationRequest request) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("minimumVersion", version(request.minimumVersion()));
        value.put("maximumVersion", version(request.maximumVersion()));
        value.put("capabilities", request.capabilities().stream().sorted(Comparator.comparing(requirement -> requirement.capability().canonicalText()))
            .map(requirement -> Map.of("capability", IdentityCodec.encode(requirement.capability()), "minimumVersion", requirement.minimumVersion(),
                "required", requirement.required())).toList());
        value.put("catalogChecksum", request.catalogChecksum().canonicalText());
        value.put("bindingManifestHash", request.bindingManifestHash().canonicalText());
        return value;
    }

    private Map<String, Object> negotiationResult(CapabilityNegotiationResult result) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("selectedVersion", version(result.selectedVersion()));
        value.put("granted", refs(result.granted()));
        value.put("additive", refs(result.additive()));
        value.put("missing", refs(result.missing()));
        value.put("outcome", lower(result.outcome().name()));
        value.put("editability", lower(result.editability().name()));
        value.put("catalogChecksum", result.catalogChecksum().canonicalText());
        value.put("bindingManifestHash", result.bindingManifestHash().canonicalText());
        putOptional(value, "fallbackReason", result.fallbackReason());
        value.put("diagnostics", result.diagnostics().stream().map(Diagnostic::toMap).toList());
        return value;
    }

    private ProtocolBody decodeBody(JsonValue value, boolean activationSupported, boolean presentationSupported,
                                    boolean optionSupported, ServerId serverId) {
        JsonValue.JsonObject object = object(value, "Protocol body");
        ProtocolBody.BodyKind kind = enumValue(text(object, "bodyKind"), ProtocolBody.BodyKind.class);
        return switch (kind) {
            case LIST_REQUEST, QUERY_REQUEST, LOAD_REQUEST, CREATE_REQUEST, SAVE_REQUEST, RENAME_REQUEST, MOVE_REQUEST,
                DUPLICATE_REQUEST, ACTIVATE_REQUEST, DELETE_REQUEST, SUBSCRIBE_REQUEST -> new ProtocolBody.ResourceRequest(
                decodeResourceRequest(object, kind, activationSupported, presentationSupported, serverId), unknown(object, bodyKnown(kind)));
            case RESOURCE_DOCUMENT -> new ProtocolBody.ResourceDocumentResponse(enumValue(text(object, "operation"), ResourceOperationKind.class),
                decodeDocument(require(object, "document"), activationSupported), unknown(object, Set.of("bodyKind", "operation", "document")));
            case RESOURCE_CREATE_RESULT -> {
                if (!presentationSupported || enumValue(text(object, "operation"), ResourceOperationKind.class) != ResourceOperationKind.CREATE) {
                    throw new IllegalArgumentException(
                        "Resource create presentation requires generic resource contract 1.2 and resource_create_presentation capability");
                }
                yield new ProtocolBody.ResourceCreateResponse(new ResourceCreateResult(
                    decodeDocument(require(object, "document"), activationSupported),
                    decodeDocument(require(object, "projectMetadata"), activationSupported),
                    decodePresentation(require(object, "presentation"))),
                    unknown(object, Set.of("bodyKind", "operation", "document", "projectMetadata", "presentation")));
            }
            case RESOURCE_PAGE -> new ProtocolBody.ResourcePageResponse(enumValue(text(object, "operation"), ResourceOperationKind.class),
                decodePage(object, activationSupported), unknown(object, Set.of("bodyKind", "operation", "items", "nextCursor", "complete")));
            case OPTION_QUERY -> {
                requireOptionProtocol(optionSupported);
                yield new ProtocolBody.OptionQueryRequest(decodeOptionQuery(object, serverId), unknown(object, bodyKnown(kind)));
            }
            case OPTION_PAGE -> {
                requireOptionProtocol(optionSupported);
                yield new ProtocolBody.OptionPageResponse(decodeOptionPage(object, serverId), unknown(object, bodyKnown(kind)));
            }
            case OPTION_INVALIDATION -> {
                requireOptionProtocol(optionSupported);
                yield new ProtocolBody.OptionInvalidationEvent(decodeOptionInvalidation(object, serverId), unknown(object, bodyKnown(kind)));
            }
            case NEGOTIATION_REQUEST -> new ProtocolBody.NegotiationRequest(decodeNegotiationRequest(object), unknown(object, bodyKnown(kind)));
            case NEGOTIATION_RESPONSE -> new ProtocolBody.NegotiationResponse(decodeNegotiationResult(object), unknown(object, bodyKnown(kind)));
            case CONTROL_REQUEST -> new ProtocolBody.ControlRequest(text(object, "action"), javaMap(require(object, "values")),
                unknown(object, Set.of("bodyKind", "action", "values")));
            case CONTROL_RESPONSE -> new ProtocolBody.ControlResponse(text(object, "action"), javaMap(require(object, "values")),
                unknown(object, Set.of("bodyKind", "action", "values")));
            case CONFLICT -> new ProtocolBody.ConflictResponse(IdentityCodec.decodeLocator(require(object, "requestedResource")),
                optional(object, "current").map(currentValue -> decodeDocument(currentValue, activationSupported)).orElse(null),
                unknown(object, Set.of("bodyKind", "requestedResource", "current")));
            case EMPTY -> new ProtocolBody.EmptyResponse(unknown(object, Set.of("bodyKind")));
        };
    }

    private ResourceOperation decodeResourceRequest(JsonValue.JsonObject object, ProtocolBody.BodyKind bodyKind,
                                                    boolean activationSupported, boolean presentationSupported, ServerId serverId) {
        ResourceOperationKind operation = enumValue(text(object, "operation"), ResourceOperationKind.class);
        if (operation != operationFor(bodyKind)) {
            throw new IllegalArgumentException("Resource operation does not match body kind");
        }
        return switch (operation) {
            case LIST -> new ResourceListRequest(IdentityCodec.decodeReference(require(object, "type"), ResourceTypeId::new),
                optionalText(object, "cursor").orElse(null), requireInt(object, "limit"), optionalText(object, "search").orElse(null));
            case QUERY -> new ResourceQueryRequest(IdentityCodec.decodeReference(require(object, "type"), ResourceTypeId::new),
                typedMapDecode(require(object, "filters"), serverId), optionalText(object, "cursor").orElse(null), requireInt(object, "limit"),
                optionalText(object, "search").orElse(null));
            case LOAD -> new ResourceLoadRequest(IdentityCodec.decodeLocator(require(object, "resource")));
            case CREATE -> {
                ContentHash hash = new ContentHash(text(object, "payloadHash"));
                ResourcePresentationIntent presentation = optional(object, "presentation")
                    .map(this::decodePresentation).orElse(null);
                if (presentation != null && !presentationSupported) {
                    throw new IllegalArgumentException(
                        "Resource create presentation requires generic resource contract 1.2 and resource_create_presentation capability");
                }
                yield new ResourceCreateRequest<>(IdentityCodec.decodeLocator(require(object, "resource")),
                    decodePayload(require(object, "payload"), hash), UUID.fromString(text(object, "mutationId")), presentation);
            }
            case SAVE -> {
                ContentHash hash = new ContentHash(text(object, "payloadHash"));
                yield new ResourceSaveRequest<>(IdentityCodec.decodeLocator(require(object, "resource")), requireLong(object, "expectedRevision"),
                    decodePayload(require(object, "payload"), hash), UUID.fromString(text(object, "mutationId")));
            }
            case RENAME -> new ResourceRenameRequest(IdentityCodec.decodeLocator(require(object, "resource")), requireLong(object, "expectedRevision"),
                text(object, "newName"), UUID.fromString(text(object, "mutationId")));
            case MOVE -> new ResourceMoveRequest(IdentityCodec.decodeLocator(require(object, "resource")), requireLong(object, "expectedRevision"),
                text(object, "destination"), UUID.fromString(text(object, "mutationId")));
            case DUPLICATE -> new ResourceDuplicateRequest(IdentityCodec.decodeLocator(require(object, "source")),
                IdentityCodec.decodeLocator(require(object, "target")), requireLong(object, "expectedRevision"), UUID.fromString(text(object, "mutationId")));
            case ACTIVATE -> {
                if (!activationSupported) {
                    throw new IllegalArgumentException("Resource activation requires generic resource contract 1.1 and resource_activation capability");
                }
                yield new ResourceActivateRequest(IdentityCodec.decodeLocator(require(object, "resource")), requireLong(object, "expectedRevision"),
                    ResourceActivationState.fromWireName(text(object, "targetState")), UUID.fromString(text(object, "mutationId")));
            }
            case DELETE -> new ResourceDeleteRequest(IdentityCodec.decodeLocator(require(object, "resource")), requireLong(object, "expectedRevision"),
                UUID.fromString(text(object, "mutationId")));
            case SUBSCRIBE -> {
                ContractRef<ResourceTypeId> type = optional(object, "type")
                    .map(value -> IdentityCodec.decodeReference(value, ResourceTypeId::new)).orElse(null);
                ServerResourceLocator resource = optional(object, "resource").map(IdentityCodec::decodeLocator).orElse(null);
                yield new ResourceSubscribeRequest(type, resource, requireLong(object, "afterRevision"), booleanValue(object, "includePayload"));
            }
        };
    }

    private ResourceDocument<P> decodeDocument(JsonValue value, boolean activationSupported) {
        JsonValue.JsonObject object = object(value, "Resource document");
        ServerResourceLocator locator = IdentityCodec.decodeLocator(require(object, "locator"));
        long revision = requireLong(object, "revision");
        UUID mutation = UUID.fromString(text(object, "mutationId"));
        ContentHash hash = new ContentHash(text(object, "payloadHash"));
        boolean deleted = booleanValue(object, "deleted");
        String author = optionalText(object, "author").orElse(null);
        ResourceActivationState activationState = optionalText(object, "activationState")
            .map(ResourceActivationState::fromWireName).orElse(ResourceActivationState.ACTIVE);
        if (deleted) {
            if (object.contains("payload") || object.contains("activationState")) {
                throw new IllegalArgumentException("Deleted resource documents cannot carry payload or activation state");
            }
            return ResourceDocument.tombstone(locator, revision, mutation, hash, author);
        }
        if (object.contains("activationState") && !activationSupported) {
            throw new IllegalArgumentException("Resource activation state requires generic resource contract 1.1 and resource_activation capability");
        }
        return ResourceDocument.live(locator, revision, mutation, decodePayload(require(object, "payload"), hash), activationState, author);
    }

    private ResourcePage<P> decodePage(JsonValue.JsonObject object, boolean activationSupported) {
        List<ResourceDocument<P>> items = requireArray(object, "items").values().stream()
            .map(value -> decodeDocument(value, activationSupported)).toList();
        String next = optionalText(object, "nextCursor").orElse(null);
        return new ResourcePage<>(items, next, booleanValue(object, "complete"));
    }

    private OptionQuery decodeOptionQuery(JsonValue.JsonObject object, ServerId envelopeServerId) {
        ServerId serverId = ServerId.parseCanonicalText(text(object, "serverId"));
        if (!serverId.equals(envelopeServerId)) {
            throw new IllegalArgumentException("Option query server does not match envelope server");
        }
        return new OptionQuery(IdentityCodec.decodeReference(require(object, "sourceRef"), InspectorFieldId::new),
            IdentityCodec.decodeReference(require(object, "query"), CapabilityId::new), serverId,
            optional(object, "resource").map(IdentityCodec::decodeLocator).orElse(null),
            typedMapDecode(require(object, "context"), serverId), typedMapDecode(require(object, "dependencies"), serverId),
            optionalText(object, "cursor").orElse(null),
            requireInt(object, "limit"), optionalText(object, "search").orElse(null), requireLong(object, "revision"),
            text(object, "invalidationKey"));
    }

    private OptionPage decodeOptionPage(JsonValue.JsonObject object, ServerId serverId) {
        List<OptionItem> items = requireArray(object, "items").values().stream().map(value -> {
            JsonValue.JsonObject item = object(value, "Option item");
            return new OptionItem(decodeTypedValue(require(item, "value"), serverId), text(item, "label"), text(item, "description"),
                booleanValue(item, "available"), optionalText(item, "reason").orElse(null));
        }).toList();
        return new OptionPage(IdentityCodec.decodeReference(require(object, "sourceRef"), InspectorFieldId::new),
            IdentityCodec.decodeReference(require(object, "query"), CapabilityId::new), requireLong(object, "revision"),
            text(object, "invalidationKey"), items, optionalText(object, "nextCursor").orElse(null),
            booleanValue(object, "complete"),
            optional(object, "diagnostics").map(value -> decodeDiagnostics(requireArray(object, "diagnostics"))).orElse(List.of()));
    }

    private OptionInvalidation decodeOptionInvalidation(JsonValue.JsonObject object, ServerId envelopeServerId) {
        ServerId serverId = ServerId.parseCanonicalText(text(object, "serverId"));
        if (!serverId.equals(envelopeServerId)) {
            throw new IllegalArgumentException("Option invalidation server does not match envelope server");
        }
        LinkedHashSet<String> dependencyKeys = new LinkedHashSet<>();
        for (JsonValue value : requireArray(object, "dependencyKeys").values()) {
            if (!(value instanceof JsonValue.JsonString string) || string.value().isBlank()) {
                throw new IllegalArgumentException("Option invalidation dependency keys must be non-blank text");
            }
            if (!dependencyKeys.add(string.value())) {
                throw new IllegalArgumentException("Option invalidation dependency keys must be unique");
            }
        }
        return new OptionInvalidation(IdentityCodec.decodeReference(require(object, "sourceRef"), InspectorFieldId::new),
            IdentityCodec.decodeReference(require(object, "query"), CapabilityId::new), serverId,
            optional(object, "resource").map(IdentityCodec::decodeLocator).orElse(null), requireLong(object, "revision"),
            text(object, "invalidationKey"), dependencyKeys);
    }

    private CapabilityNegotiationRequest decodeNegotiationRequest(JsonValue.JsonObject object) {
        Set<CapabilityRequirement> capabilities = new LinkedHashSet<>();
        for (JsonValue value : requireArray(object, "capabilities").values()) {
            JsonValue.JsonObject item = object(value, "Capability requirement");
            capabilities.add(new CapabilityRequirement(IdentityCodec.decodeReference(require(item, "capability"), CapabilityId::new),
                requireInt(item, "minimumVersion"), booleanValue(item, "required")));
        }
        return new CapabilityNegotiationRequest(decodeVersion(require(object, "minimumVersion")), decodeVersion(require(object, "maximumVersion")),
            capabilities, new ContentHash(text(object, "catalogChecksum")), new ContentHash(text(object, "bindingManifestHash")));
    }

    private CapabilityNegotiationResult decodeNegotiationResult(JsonValue.JsonObject object) {
        return new CapabilityNegotiationResult(decodeVersion(require(object, "selectedVersion")), decodeRefs(requireArray(object, "granted")),
            decodeRefs(requireArray(object, "additive")), decodeRefs(requireArray(object, "missing")),
            enumValue(text(object, "outcome"), CapabilityOutcome.class), enumValue(text(object, "editability"), ProtocolEditability.class),
            new ContentHash(text(object, "catalogChecksum")), new ContentHash(text(object, "bindingManifestHash")),
            optionalText(object, "fallbackReason").orElse(null),
            optional(object, "diagnostics").map(value -> decodeDiagnostics(requireArray(object, "diagnostics"))).orElse(List.of()));
    }

    private TypeExpr decodeType(JsonValue value) {
        JsonValue.JsonObject object = object(value, "Type expression");
        String kind = text(object, "kind");
        Map<String, Object> unknown = unknown(object, typeKnown(kind));
        return switch (kind) {
            case "named" -> new TypeExpr.Named(decodeTypeReference(require(object, "type")),
                requireArray(object, "arguments").values().stream().map(this::decodeType).toList(), unknown);
            case "optional" -> new TypeExpr.OptionalType(decodeType(require(object, "element")), unknown);
            case "list" -> new TypeExpr.ListType(decodeType(require(object, "element")), unknown);
            case "map" -> new TypeExpr.MapType(decodeType(require(object, "key")), decodeType(require(object, "value")), unknown);
            case "tuple" -> new TypeExpr.TupleType(requireArray(object, "elements").values().stream().map(this::decodeType).toList(), unknown);
            case "result" -> new TypeExpr.ResultType(decodeType(require(object, "success")), decodeType(require(object, "failure")), unknown);
            case "resource" -> new TypeExpr.ResourceType(decodeTypeReference(require(object, "resourceType")), unknown);
            case "union" -> new TypeExpr.UnionType(requireArray(object, "variants").values().stream().map(this::decodeUnionVariant).toList(), unknown);
            case "opaque" -> new TypeExpr.OpaqueType(decodeTypeReference(require(object, "type")), unknown);
            default -> throw new IllegalArgumentException("Unknown type expression kind: " + kind);
        };
    }

    private TypeExpr.UnionVariant decodeUnionVariant(JsonValue value) {
        JsonValue.JsonObject object = object(value, "Union variant");
        return new TypeExpr.UnionVariant(text(object, "variantId"), decodeType(require(object, "type")), optionalText(object, "displayName").orElse(null),
            optionalText(object, "description").orElse(null), unknown(object, Set.of("variantId", "type", "displayName", "description")));
    }

    private TypeReference decodeTypeReference(JsonValue value) {
        JsonValue.JsonObject object = object(value, "Type reference");
        return new TypeReference(text(object, "ownerId"), text(object, "localId"), unknown(object, Set.of("ownerId", "localId")));
    }

    private TypedValue decodeTypedValue(JsonValue value, ServerId serverId) {
        JsonValue.JsonObject object = object(value, "Typed value");
        TypeExpr type = decodeType(require(object, "type"));
        TypedValue.State state = enumValue(text(object, "state"), TypedValue.State.class);
        String variantId = optionalText(object, "variantId").orElse(null);
        boolean hasValue = object.contains("value");
        boolean hasLocator = object.contains("locator");
        TypeExpr selectedType = selectedType(type, variantId, state);
        Object material = null;
        ServerResourceLocator locator = null;
        switch (state) {
            case ABSENT, NULL -> {
                if (hasValue || hasLocator || variantId != null) {
                    throw new IllegalArgumentException("Absent and null typed values cannot carry value, locator, or variant material");
                }
            }
            case VALUE -> {
                if (!hasValue || hasLocator || require(object, "value") instanceof JsonValue.JsonNull) {
                    throw new IllegalArgumentException("Value typed values require non-null value material and no locator");
                }
                material = decodeMaterial(selectedType, require(object, "value"), serverId);
            }
            case LOCATOR -> {
                if (!hasLocator || hasValue) {
                    throw new IllegalArgumentException("Locator typed values require locator material and no value");
                }
                locator = decodeLocator(require(object, "locator"), serverId);
            }
            case OPAQUE -> {
                if (!hasValue || hasLocator) {
                    throw new IllegalArgumentException("Opaque typed values require value material and no locator");
                }
                material = require(object, "value").toJava();
            }
        }
        Map<String, Object> unknown = unknown(object, Set.of("type", "state", "variantId", "value", "locator"));
        return new TypedValue(type, state, variantId, material, locator, unknown);
    }

    private static TypeExpr selectedType(TypeExpr type, String variantId, TypedValue.State state) {
        if (type instanceof TypeExpr.UnionType union && state != TypedValue.State.ABSENT && state != TypedValue.State.NULL) {
            if (variantId == null) {
                throw new IllegalArgumentException("Union typed values require variantId");
            }
            return union.variant(variantId).type();
        }
        if (!(type instanceof TypeExpr.UnionType) && variantId != null) {
            throw new IllegalArgumentException("Non-union typed values cannot carry variantId");
        }
        return type;
    }

    private static Object decodeMaterial(TypeExpr type, JsonValue value, ServerId serverId) {
        if (value instanceof JsonValue.JsonNull) {
            return null;
        }
        return switch (type) {
            case TypeExpr.ResourceType ignored -> decodeLocator(value, serverId);
            case TypeExpr.OptionalType optional -> decodeMaterial(optional.element(), value, serverId);
            case TypeExpr.ListType list -> decodeListMaterial(value, list.element(), serverId);
            case TypeExpr.MapType map -> decodeMapMaterial(value, map, serverId);
            case TypeExpr.TupleType tuple -> decodeTupleMaterial(value, tuple, serverId);
            case TypeExpr.ResultType result -> decodeResultMaterial(value, result, serverId);
            case TypeExpr.Named named -> decodeNamedMaterial(value, named, serverId);
            case TypeExpr.UnionType ignored -> throw new IllegalArgumentException("Union branch type is unresolved");
            case TypeExpr.OpaqueType ignored -> value.toJava();
        };
    }

    private static Object decodeNamedMaterial(JsonValue value, TypeExpr.Named type, ServerId serverId) {
        Object raw = value.toJava();
        if ("builtin".equals(type.reference().ownerId()) && type.arguments().isEmpty()) {
            return switch (type.reference().localId()) {
                case "uuid" -> value instanceof JsonValue.JsonString string
                    ? canonicalUuid(string.value()) : fail("Typed UUID value must be a string");
                case "integer" -> value instanceof JsonValue.JsonNumber number
                    ? integral(number.value()) : fail("Typed integer value must be a number");
                case "number" -> value instanceof JsonValue.JsonNumber number
                    ? number.value() : fail("Typed number value must be a number");
                default -> raw;
            };
        }
        if (value instanceof JsonValue.JsonObject object && containsResource(type) && looksLikeLocator(object)) {
            return decodeLocator(value, serverId);
        }
        return raw;
    }

    private static List<Object> decodeListMaterial(JsonValue value, TypeExpr element, ServerId serverId) {
        if (!(value instanceof JsonValue.JsonArray array)) {
            throw new IllegalArgumentException("Typed list value must be an array");
        }
        ArrayList<Object> result = new ArrayList<>(array.values().size());
        for (JsonValue item : array.values()) {
            result.add(decodeMaterial(element, item, serverId));
        }
        return Collections.unmodifiableList(result);
    }

    private static Map<String, Object> decodeMapMaterial(JsonValue value, TypeExpr.MapType type, ServerId serverId) {
        if (!(value instanceof JsonValue.JsonObject object)) {
            throw new IllegalArgumentException("Typed map value must be an object");
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> entry : object.fields().entrySet()) {
            Object decodedKey = decodeMaterial(type.key(), JsonValue.fromJava(entry.getKey()), serverId);
            if (!(decodedKey instanceof String key)) {
                throw new IllegalArgumentException("Typed map key type must decode to a string");
            }
            result.put(key, decodeMaterial(type.value(), entry.getValue(), serverId));
        }
        return Collections.unmodifiableMap(result);
    }

    private static List<Object> decodeTupleMaterial(JsonValue value, TypeExpr.TupleType type, ServerId serverId) {
        if (!(value instanceof JsonValue.JsonArray array) || array.values().size() != type.elements().size()) {
            throw new IllegalArgumentException("Typed tuple value shape is invalid");
        }
        ArrayList<Object> result = new ArrayList<>(array.values().size());
        for (int index = 0; index < array.values().size(); index++) {
            result.add(decodeMaterial(type.elements().get(index), array.values().get(index), serverId));
        }
        return Collections.unmodifiableList(result);
    }

    private static Map<String, Object> decodeResultMaterial(JsonValue value, TypeExpr.ResultType type, ServerId serverId) {
        if (!(value instanceof JsonValue.JsonObject object) || !(object.value("success") instanceof JsonValue.JsonBoolean success)
            || !object.contains("value")) {
            throw new IllegalArgumentException("Typed result value shape is invalid");
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> entry : object.fields().entrySet()) {
            result.put(entry.getKey(), "value".equals(entry.getKey())
                ? decodeMaterial(success.value() ? type.success() : type.failure(), entry.getValue(), serverId)
                : entry.getValue().toJava());
        }
        return Collections.unmodifiableMap(result);
    }

    private static ServerResourceLocator decodeLocator(JsonValue value, ServerId serverId) {
        ServerResourceLocator locator = IdentityCodec.decodeLocator(value);
        if (!locator.serverId().equals(serverId)) {
            throw new IllegalArgumentException("Typed resource locator server does not match envelope server");
        }
        return locator;
    }

    private static boolean looksLikeLocator(JsonValue.JsonObject value) {
        return value.contains("serverId") && value.contains("type") && value.contains("id");
    }

    private static boolean containsResource(TypeExpr expression) {
        return switch (expression) {
            case TypeExpr.ResourceType ignored -> true;
            case TypeExpr.Named named -> named.arguments().stream().anyMatch(ProtocolEnvelopeCodec::containsResource);
            case TypeExpr.OptionalType optional -> containsResource(optional.element());
            case TypeExpr.ListType list -> containsResource(list.element());
            case TypeExpr.MapType map -> containsResource(map.key()) || containsResource(map.value());
            case TypeExpr.TupleType tuple -> tuple.elements().stream().anyMatch(ProtocolEnvelopeCodec::containsResource);
            case TypeExpr.ResultType result -> containsResource(result.success()) || containsResource(result.failure());
            case TypeExpr.UnionType union -> union.variants().stream().map(TypeExpr.UnionVariant::type)
                .anyMatch(ProtocolEnvelopeCodec::containsResource);
            case TypeExpr.OpaqueType ignored -> false;
        };
    }

    private static BigInteger integral(BigDecimal value) {
        try {
            return value.toBigIntegerExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Typed integer value must be integral", exception);
        }
    }

    private static UUID canonicalUuid(String value) {
        try {
            UUID uuid = UUID.fromString(value);
            if (!uuid.toString().equals(value)) {
                throw new IllegalArgumentException("Typed UUID value must be canonical");
            }
            return uuid;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Typed UUID value must be a canonical UUID", exception);
        }
    }

    private static <T> T fail(String message) {
        throw new IllegalArgumentException(message);
    }

    private CanonicalPayload<P> decodePayload(JsonValue value, ContentHash expectedHash) {
        P payload = payloadDecoder.apply(value.toJava());
        return payloadCodec.verify(payload, expectedHash);
    }

    private static Map<String, Object> presentation(ResourcePresentationIntent presentation) {
        return Map.of("displayName", presentation.displayName(), "path", presentation.path(), "sortOrder", presentation.sortOrder());
    }

    private ResourcePresentationIntent decodePresentation(JsonValue value) {
        JsonValue.JsonObject object = object(value, "Resource presentation");
        if (!unknown(object, Set.of("displayName", "path", "sortOrder")).isEmpty()) {
            throw new IllegalArgumentException("Resource presentation contains unsupported fields");
        }
        return new ResourcePresentationIntent(text(object, "displayName"), text(object, "path"), requireInt(object, "sortOrder"));
    }

    private Object payloadValue(Object payload) {
        @SuppressWarnings("unchecked")
        P typed = (P) payload;
        return payloadCodec.canonicalValue(typed);
    }

    private static List<JsonValue.JsonObject> refs(Set<ContractRef<CapabilityId>> references) {
        return references.stream().sorted(Comparator.comparing(ContractRef::canonicalText)).map(IdentityCodec::encode).toList();
    }

    private static Set<ContractRef<CapabilityId>> decodeCapabilityRefs(JsonValue.JsonArray values) {
        LinkedHashSet<ContractRef<CapabilityId>> result = new LinkedHashSet<>();
        values.values().forEach(value -> result.add(IdentityCodec.decodeReference(value, CapabilityId::new)));
        return Set.copyOf(result);
    }

    private static Set<ContractRef<CapabilityId>> decodeRefs(JsonValue.JsonArray values) {
        return decodeCapabilityRefs(values);
    }

    private static Map<String, Object> typedMap(Map<String, TypedValue> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, value.canonicalValue()));
        return result;
    }

    private Map<String, TypedValue> typedMapDecode(JsonValue value, ServerId serverId) {
        JsonValue.JsonObject object = object(value, "Typed value map");
        Map<String, TypedValue> result = new LinkedHashMap<>();
        object.fields().forEach((key, item) -> result.put(key, decodeTypedValue(item, serverId)));
        return result;
    }

    private static Map<String, Object> javaMap(JsonValue value) {
        if (!(value instanceof JsonValue.JsonObject)) {
            throw new IllegalArgumentException("Control values must be an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) value.toJava();
        return map;
    }

    private static Set<String> bodyKnown(ProtocolBody.BodyKind kind) {
        return switch (kind) {
            case LIST_REQUEST, QUERY_REQUEST, LOAD_REQUEST, CREATE_REQUEST, SAVE_REQUEST, RENAME_REQUEST, MOVE_REQUEST,
                DUPLICATE_REQUEST, ACTIVATE_REQUEST, DELETE_REQUEST, SUBSCRIBE_REQUEST -> Set.of("bodyKind", "operation", "type", "resource",
                "source", "target", "cursor", "limit", "search", "filters", "expectedRevision", "newName", "destination", "mutationId",
                "payloadHash", "payload", "presentation", "targetState", "activationState", "afterRevision", "includePayload");
            case OPTION_QUERY -> Set.of("bodyKind", "sourceRef", "query", "serverId", "resource", "context", "dependencies", "cursor",
                "limit", "search", "revision", "invalidationKey");
            case OPTION_PAGE -> Set.of("bodyKind", "sourceRef", "query", "revision", "invalidationKey", "items", "nextCursor", "complete",
                "diagnostics");
            case OPTION_INVALIDATION -> Set.of("bodyKind", "sourceRef", "query", "serverId", "resource", "revision", "invalidationKey",
                "dependencyKeys");
            case NEGOTIATION_REQUEST -> Set.of("bodyKind", "minimumVersion", "maximumVersion", "capabilities", "catalogChecksum", "bindingManifestHash");
            case NEGOTIATION_RESPONSE -> Set.of("bodyKind", "selectedVersion", "granted", "additive", "missing", "outcome", "editability",
                "catalogChecksum", "bindingManifestHash", "fallbackReason", "diagnostics");
            default -> Set.of("bodyKind");
        };
    }

    private static Set<String> typeKnown(String kind) {
        return switch (kind) {
            case "named" -> Set.of("kind", "type", "arguments");
            case "optional", "list" -> Set.of("kind", "element");
            case "map" -> Set.of("kind", "key", "value");
            case "tuple" -> Set.of("kind", "elements");
            case "result" -> Set.of("kind", "success", "failure");
            case "resource" -> Set.of("kind", "resourceType");
            case "union" -> Set.of("kind", "variants");
            case "opaque" -> Set.of("kind", "type", "raw");
            default -> Set.of("kind");
        };
    }

    private static ResourceOperationKind operationFor(ProtocolBody.BodyKind kind) {
        return switch (kind) {
            case LIST_REQUEST -> ResourceOperationKind.LIST;
            case QUERY_REQUEST -> ResourceOperationKind.QUERY;
            case LOAD_REQUEST -> ResourceOperationKind.LOAD;
            case CREATE_REQUEST -> ResourceOperationKind.CREATE;
            case SAVE_REQUEST -> ResourceOperationKind.SAVE;
            case RENAME_REQUEST -> ResourceOperationKind.RENAME;
            case MOVE_REQUEST -> ResourceOperationKind.MOVE;
            case DUPLICATE_REQUEST -> ResourceOperationKind.DUPLICATE;
            case ACTIVATE_REQUEST -> ResourceOperationKind.ACTIVATE;
            case DELETE_REQUEST -> ResourceOperationKind.DELETE;
            case SUBSCRIBE_REQUEST -> ResourceOperationKind.SUBSCRIBE;
            default -> throw new IllegalArgumentException("Body kind is not a resource request: " + kind);
        };
    }

    private static boolean includeRevision(ProtocolEnvelope<?> envelope) {
        if (isNullCurrentConflict(envelope)) {
            return false;
        }
        return envelope.body().resourceBody() && (envelope.resource() != null || envelope.mutationId() != null || envelope.payloadHash() != null
            || envelope.body() instanceof ProtocolBody.ResourceDocumentResponse || envelope.body() instanceof ProtocolBody.ConflictResponse);
    }

    private static boolean includeDeleted(ProtocolEnvelope<?> envelope) {
        if (isNullCurrentConflict(envelope)) {
            return false;
        }
        return envelope.body() instanceof ProtocolBody.ResourceDocumentResponse || envelope.body() instanceof ProtocolBody.ConflictResponse
            || envelope.deleted();
    }

    private static boolean isNullCurrentConflict(ProtocolEnvelope<?> envelope) {
        return envelope.body() instanceof ProtocolBody.ConflictResponse conflict && conflict.current() == null;
    }

    private static Map<String, Object> version(CatalogVersion version) {
        return Map.of("generation", version.generation(), "minor", version.minor());
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static void requireOptionProtocol(boolean optionSupported) {
        if (!optionSupported) {
            throw new IllegalArgumentException("Option protocol requires generic resource contract 1.3 and option_queries capability");
        }
    }

    private static <E extends Enum<E>> E enumValue(String value, Class<E> type) {
        return Enum.valueOf(type, value.toUpperCase(Locale.ROOT).replace('-', '_'));
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
            throw new IllegalArgumentException("Required protocol field is missing: " + field);
        }
        return value;
    }

    private static Optional<JsonValue> optional(JsonValue.JsonObject object, String field) {
        return Optional.ofNullable(object.value(field));
    }

    private static String text(JsonValue.JsonObject object, String field) {
        JsonValue value = require(object, field);
        if (!(value instanceof JsonValue.JsonString string)) {
            throw new IllegalArgumentException("Protocol field must be text: " + field);
        }
        return string.value();
    }

    private static Optional<String> optionalText(JsonValue.JsonObject object, String field) {
        return optional(object, field).map(value -> {
            if (!(value instanceof JsonValue.JsonString string)) {
                throw new IllegalArgumentException("Protocol field must be text: " + field);
            }
            return string.value();
        });
    }

    private static long requireLong(JsonValue.JsonObject object, String field) {
        JsonValue value = require(object, field);
        if (!(value instanceof JsonValue.JsonNumber number)) {
            throw new IllegalArgumentException("Protocol field must be an integer: " + field);
        }
        if (number.value().stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("Protocol field must be an integer: " + field);
        }
        try {
            return number.value().longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Protocol integer is out of range: " + field, exception);
        }
    }

    private static int requireInt(JsonValue.JsonObject object, String field) {
        long value = requireLong(object, field);
        try {
            return Math.toIntExact(value);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Protocol integer is out of range: " + field, exception);
        }
    }

    private static Optional<Long> optionalLong(JsonValue.JsonObject object, String field) {
        return optional(object, field).map(value -> {
            if (!(value instanceof JsonValue.JsonNumber number)) {
                throw new IllegalArgumentException("Protocol field must be an integer: " + field);
            }
            if (number.value().stripTrailingZeros().scale() > 0) {
                throw new IllegalArgumentException("Protocol field must be an integer: " + field);
            }
            try {
                return number.value().longValueExact();
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("Protocol integer is out of range: " + field, exception);
            }
        });
    }

    private static boolean booleanValue(JsonValue.JsonObject object, String field) {
        JsonValue value = require(object, field);
        if (!(value instanceof JsonValue.JsonBoolean booleanValue)) {
            throw new IllegalArgumentException("Protocol field must be boolean: " + field);
        }
        return booleanValue.value();
    }

    private static Optional<Boolean> optionalBoolean(JsonValue.JsonObject object, String field) {
        return optional(object, field).map(value -> {
            if (!(value instanceof JsonValue.JsonBoolean booleanValue)) {
                throw new IllegalArgumentException("Protocol field must be boolean: " + field);
            }
            return booleanValue.value();
        });
    }

    private static JsonValue.JsonArray requireArray(JsonValue.JsonObject object, String field) {
        JsonValue value = require(object, field);
        if (!(value instanceof JsonValue.JsonArray array)) {
            throw new IllegalArgumentException("Protocol field must be an array: " + field);
        }
        return array;
    }

    private static Map<String, Object> unknown(JsonValue.JsonObject object, Set<String> known) {
        Map<String, Object> result = new LinkedHashMap<>();
        object.fields().forEach((key, value) -> {
            if (!known.contains(key)) {
                result.put(key, value.toJava());
            }
        });
        return result;
    }

    private static CatalogVersion decodeVersion(JsonValue value) {
        JsonValue.JsonObject object = object(value, "Contract version");
        return new CatalogVersion(requireInt(object, "generation"), requireInt(object, "minor"));
    }

    private static List<Diagnostic> decodeDiagnostics(JsonValue.JsonArray values) {
        return values.values().stream().map(ProtocolEnvelopeCodec::decodeDiagnostic).toList();
    }

    private static Diagnostic decodeDiagnostic(JsonValue value) {
        return Diagnostic.fromCanonical(mapValue(object(value, "Diagnostic"), "Diagnostic"));
    }

    private static DiagnosticProvenance decodeProvenance(JsonValue value) {
        JsonValue.JsonObject object = object(value, "Diagnostic provenance");
        Set<String> known = Set.of("ownerId", "sourceKind", "sourceUri", "sourceHash", "sourceVersion", "buildId", "loadedAt");
        OwnerId ownerId = optionalText(object, "ownerId").map(OwnerId::new).orElse(null);
        DiagnosticSourceKind sourceKind = wireEnum(text(object, "sourceKind"), DiagnosticSourceKind.values(), DiagnosticSourceKind::wireName,
            "provenance.sourceKind");
        String sourceUri = canonicalText(object, "sourceUri");
        String sourceHash = text(object, "sourceHash");
        String sourceVersion = canonicalText(object, "sourceVersion");
        String buildId = canonicalText(object, "buildId");
        Instant loadedAt = optionalText(object, "loadedAt").map(valueText -> decodeInstant(valueText, "provenance.loadedAt")).orElse(null);
        return new DiagnosticProvenance(ownerId, sourceKind, sourceUri, sourceHash, sourceVersion, buildId, loadedAt, unknown(object, known));
    }

    private static Map<String, Object> mapValue(JsonValue value, String field) {
        JsonValue.JsonObject object = object(value, field);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) object.toJava();
        return result;
    }

    private static UUID decodeUuid(String value, String field) {
        try {
            UUID uuid = UUID.fromString(value);
            if (!uuid.toString().equals(value)) {
                throw new IllegalArgumentException(field + " must be a canonical lowercase UUID");
            }
            return uuid;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(field + " must be a canonical lowercase UUID", exception);
        }
    }

    private static String canonicalText(JsonValue.JsonObject object, String field) {
        String value = text(object, field);
        if (!value.equals(value.trim())) {
            throw new IllegalArgumentException(field + " must not have leading or trailing whitespace");
        }
        return value;
    }

    private static Instant decodeInstant(String value, String field) {
        try {
            Instant instant = Instant.parse(value);
            if (!instant.toString().equals(value)) {
                throw new IllegalArgumentException(field + " must be canonical ISO-8601 text");
            }
            return instant;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(field + " must be canonical ISO-8601 text", exception);
        }
    }

    private static <E> E wireEnum(String value, E[] values, Function<E, String> wire, String field) {
        for (E candidate : values) {
            if (wire.apply(candidate).equals(value)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Unknown " + field + ": " + value);
    }

    private static void putOptional(Map<String, Object> values, String field, Object value) {
        if (value != null) {
            values.put(field, value);
        }
    }
}
