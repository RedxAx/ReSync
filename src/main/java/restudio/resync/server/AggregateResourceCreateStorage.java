package restudio.resync.server;

import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ResourcePresentationIntent;
import restudio.resync.modules.flow.FlowResourceMutationContext;
import restudio.resync.modules.flow.FlowResourceMutationStamp;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public interface AggregateResourceCreateStorage {
    static PreCommitRejection rejectBeforeCommit(String errorCode, String message, RuntimeException cause) {
        return new PreCommitRejection(errorCode, message, cause);
    }

    static AggregateResourceCreateStorage unavailable() {
        return new AggregateResourceCreateStorage() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public Result create(ServerResourceLocator resource, Object value, FlowResourceMutationContext context,
                                 ResourcePresentationIntent presentation) {
                throw new IllegalStateException("Aggregate resource create storage is unavailable");
            }

            @Override
            public void publishCommitted(ServerResourceLocator resource, UUID mutationId) {
                throw new IllegalStateException("Aggregate resource create storage is unavailable");
            }
        };
    }

    boolean available();

    default boolean available(ServerResourceLocator resource) {
        return available();
    }

    Result create(ServerResourceLocator resource, Object value, FlowResourceMutationContext context,
                  ResourcePresentationIntent presentation);

    default void publishCommitted(ServerResourceLocator resource, UUID mutationId) {
    }

    final class PreCommitRejection extends RuntimeException {
        private final String errorCode;

        private PreCommitRejection(String errorCode, String message, RuntimeException cause) {
            super(normalizeMessage(message), cause);
            this.errorCode = errorCode == null || errorCode.isBlank() ? "RESOURCE_OPERATION_FAILED" : errorCode;
        }

        public String errorCode() {
            return errorCode;
        }

        private static String normalizeMessage(String message) {
            String normalized = message == null || message.isBlank() ? "Aggregate resource create was rejected" : message.trim();
            return normalized.length() <= 512 ? normalized : normalized.substring(0, 509) + "...";
        }
    }

    record Result(ResourceState primary, ResourceState projectMetadata) {
        public Result {
            primary = Objects.requireNonNull(primary, "primary");
            projectMetadata = Objects.requireNonNull(projectMetadata, "projectMetadata");
            if (primary.resource().equals(projectMetadata.resource())
                || !primary.resource().serverId().equals(projectMetadata.resource().serverId())
                || !"project_metadata".equals(projectMetadata.resource().resourceType().value())) {
                throw new IllegalArgumentException("Aggregate create result requires distinct primary and project metadata states");
            }
        }
    }

    record ResourceState(ServerResourceLocator resource, FlowResourceMutationStamp stamp,
                         Map<String, Object> canonicalPayload, String assetHash, String corePayloadHash,
                         String corePayloadKind) {
        public ResourceState {
            resource = Objects.requireNonNull(resource, "resource");
            stamp = Objects.requireNonNull(stamp, "stamp");
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            Objects.requireNonNull(canonicalPayload, "canonicalPayload").forEach((key, value) -> {
                if (key == null || key.isBlank()) {
                    throw new IllegalArgumentException("Aggregate create payload keys must be non-blank");
                }
                payload.put(key, value);
            });
            canonicalPayload = Collections.unmodifiableMap(payload);
            if (stamp.deleted() || !resource.resourceType().value().equals(stamp.type()) || !resource.id().equals(stamp.id())) {
                throw new IllegalArgumentException("Aggregate create state must be a live exact typed resource");
            }
            if ((assetHash == null) != (corePayloadHash == null) || (assetHash == null) != (corePayloadKind == null)) {
                throw new IllegalArgumentException("Aggregate Core identity must be complete or absent");
            }
        }

        public ResourceState(ServerResourceLocator resource, FlowResourceMutationStamp stamp,
                             Map<String, Object> canonicalPayload) {
            this(resource, stamp, canonicalPayload, null, null, null);
        }
    }
}
