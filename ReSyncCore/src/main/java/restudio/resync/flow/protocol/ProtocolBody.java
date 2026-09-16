package restudio.resync.flow.protocol;

import restudio.resync.flow.identity.ServerResourceLocator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public sealed interface ProtocolBody permits ProtocolBody.ResourceRequest, ProtocolBody.ResourceDocumentResponse,
    ProtocolBody.ResourceCreateResponse, ProtocolBody.ResourcePageResponse, ProtocolBody.OptionQueryRequest, ProtocolBody.OptionPageResponse,
    ProtocolBody.OptionInvalidationEvent, ProtocolBody.NegotiationRequest, ProtocolBody.NegotiationResponse, ProtocolBody.ControlRequest,
    ProtocolBody.ControlResponse, ProtocolBody.ConflictResponse, ProtocolBody.EmptyResponse {
    BodyKind bodyKind();

    Map<String, Object> unknown();

    default boolean resourceBody() {
        return bodyKind().resource();
    }

    enum BodyKind {
        LIST_REQUEST(true),
        QUERY_REQUEST(true),
        LOAD_REQUEST(true),
        CREATE_REQUEST(true),
        SAVE_REQUEST(true),
        RENAME_REQUEST(true),
        MOVE_REQUEST(true),
        DUPLICATE_REQUEST(true),
        ACTIVATE_REQUEST(true),
        DELETE_REQUEST(true),
        SUBSCRIBE_REQUEST(true),
        RESOURCE_DOCUMENT(true),
        RESOURCE_CREATE_RESULT(true),
        RESOURCE_PAGE(true),
        OPTION_QUERY(false),
        OPTION_PAGE(false),
        OPTION_INVALIDATION(false),
        NEGOTIATION_REQUEST(false),
        NEGOTIATION_RESPONSE(false),
        CONTROL_REQUEST(false),
        CONTROL_RESPONSE(false),
        CONFLICT(true),
        EMPTY(false);

        private final boolean resource;

        BodyKind(boolean resource) {
            this.resource = resource;
        }

        public boolean resource() {
            return resource;
        }
    }

    record ResourceCreateResponse(ResourceCreateResult result, Map<String, Object> unknown) implements ProtocolBody {
        public ResourceCreateResponse {
            result = Objects.requireNonNull(result, "result");
            unknown = ResourceRequest.copyUnknown(unknown);
        }

        public ResourceCreateResponse(ResourceCreateResult result) {
            this(result, Map.of());
        }

        @Override
        public BodyKind bodyKind() {
            return BodyKind.RESOURCE_CREATE_RESULT;
        }
    }

    record ResourceRequest(ResourceOperation operation, Map<String, Object> unknown) implements ProtocolBody {
        public ResourceRequest {
            operation = Objects.requireNonNull(operation, "operation");
            unknown = copyUnknown(unknown);
        }

        public ResourceRequest(ResourceOperation operation) {
            this(operation, Map.of());
        }

        @Override
        public BodyKind bodyKind() {
            return switch (operation.kind()) {
                case LIST -> BodyKind.LIST_REQUEST;
                case QUERY -> BodyKind.QUERY_REQUEST;
                case LOAD -> BodyKind.LOAD_REQUEST;
                case CREATE -> BodyKind.CREATE_REQUEST;
                case SAVE -> BodyKind.SAVE_REQUEST;
                case RENAME -> BodyKind.RENAME_REQUEST;
                case MOVE -> BodyKind.MOVE_REQUEST;
                case DUPLICATE -> BodyKind.DUPLICATE_REQUEST;
                case ACTIVATE -> BodyKind.ACTIVATE_REQUEST;
                case DELETE -> BodyKind.DELETE_REQUEST;
                case SUBSCRIBE -> BodyKind.SUBSCRIBE_REQUEST;
            };
        }

        public ServerResourceLocator resource() {
            return switch (operation) {
                case ResourceLoadRequest load -> load.resource();
                case ResourceCreateRequest<?> create -> create.resource();
                case ResourceSaveRequest<?> save -> save.resource();
                case ResourceRenameRequest rename -> rename.resource();
                case ResourceMoveRequest move -> move.resource();
                case ResourceDuplicateRequest duplicate -> duplicate.target();
                case ResourceActivateRequest activate -> activate.resource();
                case ResourceDeleteRequest delete -> delete.resource();
                case ResourceSubscribeRequest subscribe -> subscribe.resource();
                default -> null;
            };
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
    }

    record ResourceDocumentResponse(ResourceOperationKind operation, ResourceDocument<?> document, Map<String, Object> unknown)
        implements ProtocolBody {
        public ResourceDocumentResponse {
            operation = Objects.requireNonNull(operation, "operation");
            document = Objects.requireNonNull(document, "document");
            unknown = ResourceRequest.copyUnknown(unknown);
        }

        public ResourceDocumentResponse(ResourceOperationKind operation, ResourceDocument<?> document) {
            this(operation, document, Map.of());
        }

        @Override
        public BodyKind bodyKind() {
            return BodyKind.RESOURCE_DOCUMENT;
        }
    }

    record ResourcePageResponse(ResourceOperationKind operation, ResourcePage<?> page, Map<String, Object> unknown)
        implements ProtocolBody {
        public ResourcePageResponse {
            operation = Objects.requireNonNull(operation, "operation");
            page = Objects.requireNonNull(page, "page");
            unknown = ResourceRequest.copyUnknown(unknown);
        }

        public ResourcePageResponse(ResourceOperationKind operation, ResourcePage<?> page) {
            this(operation, page, Map.of());
        }

        @Override
        public BodyKind bodyKind() {
            return BodyKind.RESOURCE_PAGE;
        }
    }

    record OptionQueryRequest(OptionQuery query, Map<String, Object> unknown) implements ProtocolBody {
        public OptionQueryRequest {
            query = Objects.requireNonNull(query, "query");
            unknown = ResourceRequest.copyUnknown(unknown);
        }

        public OptionQueryRequest(OptionQuery query) {
            this(query, Map.of());
        }

        @Override
        public BodyKind bodyKind() {
            return BodyKind.OPTION_QUERY;
        }
    }

    record OptionPageResponse(OptionPage page, Map<String, Object> unknown) implements ProtocolBody {
        public OptionPageResponse {
            page = Objects.requireNonNull(page, "page");
            unknown = ResourceRequest.copyUnknown(unknown);
        }

        public OptionPageResponse(OptionPage page) {
            this(page, Map.of());
        }

        @Override
        public BodyKind bodyKind() {
            return BodyKind.OPTION_PAGE;
        }
    }

    record OptionInvalidationEvent(OptionInvalidation invalidation, Map<String, Object> unknown) implements ProtocolBody {
        public OptionInvalidationEvent {
            invalidation = Objects.requireNonNull(invalidation, "invalidation");
            unknown = ResourceRequest.copyUnknown(unknown);
        }

        public OptionInvalidationEvent(OptionInvalidation invalidation) {
            this(invalidation, Map.of());
        }

        @Override
        public BodyKind bodyKind() {
            return BodyKind.OPTION_INVALIDATION;
        }
    }

    record NegotiationRequest(CapabilityNegotiationRequest request, Map<String, Object> unknown) implements ProtocolBody {
        public NegotiationRequest {
            request = Objects.requireNonNull(request, "request");
            unknown = ResourceRequest.copyUnknown(unknown);
        }

        public NegotiationRequest(CapabilityNegotiationRequest request) {
            this(request, Map.of());
        }

        @Override
        public BodyKind bodyKind() {
            return BodyKind.NEGOTIATION_REQUEST;
        }
    }

    record NegotiationResponse(CapabilityNegotiationResult result, Map<String, Object> unknown) implements ProtocolBody {
        public NegotiationResponse {
            result = Objects.requireNonNull(result, "result");
            unknown = ResourceRequest.copyUnknown(unknown);
        }

        public NegotiationResponse(CapabilityNegotiationResult result) {
            this(result, Map.of());
        }

        @Override
        public BodyKind bodyKind() {
            return BodyKind.NEGOTIATION_RESPONSE;
        }
    }

    record ControlRequest(String action, Map<String, Object> values, Map<String, Object> unknown) implements ProtocolBody {
        public ControlRequest {
            action = ProtocolValues.requiredText(action, "action", 128);
            values = ResourceRequest.copyUnknown(values);
            unknown = ResourceRequest.copyUnknown(unknown);
        }

        public ControlRequest(String action, Map<String, Object> values) {
            this(action, values, Map.of());
        }

        @Override
        public BodyKind bodyKind() {
            return BodyKind.CONTROL_REQUEST;
        }
    }

    record ControlResponse(String action, Map<String, Object> values, Map<String, Object> unknown) implements ProtocolBody {
        public ControlResponse {
            action = ProtocolValues.requiredText(action, "action", 128);
            values = ResourceRequest.copyUnknown(values);
            unknown = ResourceRequest.copyUnknown(unknown);
        }

        public ControlResponse(String action, Map<String, Object> values) {
            this(action, values, Map.of());
        }

        @Override
        public BodyKind bodyKind() {
            return BodyKind.CONTROL_RESPONSE;
        }
    }

    record ConflictResponse(ServerResourceLocator requestedResource, ResourceDocument<?> current, Map<String, Object> unknown)
        implements ProtocolBody {
        public ConflictResponse {
            requestedResource = Objects.requireNonNull(requestedResource, "requestedResource");
            if (current != null && !requestedResource.equals(current.resource())) {
                throw new IllegalArgumentException("Conflict document does not match requested resource");
            }
            unknown = ResourceRequest.copyUnknown(unknown);
        }

        public ConflictResponse(ServerResourceLocator requestedResource, ResourceDocument<?> current) {
            this(requestedResource, current, Map.of());
        }

        @Override
        public BodyKind bodyKind() {
            return BodyKind.CONFLICT;
        }
    }

    record EmptyResponse(Map<String, Object> unknown) implements ProtocolBody {
        public EmptyResponse {
            unknown = ResourceRequest.copyUnknown(unknown);
        }

        public EmptyResponse() {
            this(Map.of());
        }

        @Override
        public BodyKind bodyKind() {
            return BodyKind.EMPTY;
        }
    }
}
