package restudio.resync.server;

import restudio.resync.core.ConnectionInfo;
import restudio.resync.core.Session;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ResourceDocument;
import restudio.resync.flow.protocol.ProtocolEnvelope;
import restudio.resync.flow.protocol.ProtocolRejectionCode;
import restudio.resync.flow.protocol.ResourceOperation;

import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;

public interface ProtocolResourceMutationAuthority {
    ProtocolEnvelopeDispatchResult mutate(ConnectionInfo connection, Session session,
                                           ProtocolEnvelope<Map<String, Object>> envelope, ResourceOperation operation);

    default ProtocolEnvelopeDispatchResult mutateOperator(ProtocolRequestAuthority.OperatorGrant grant,
                                                          ProtocolEnvelope<Map<String, Object>> envelope,
                                                          ResourceOperation operation) {
        return ProtocolEnvelopeDispatchResult.rejected(ProtocolRejectionCode.AUTHORIZATION_DENIED,
            "Local operator resource admission is unavailable");
    }

    default boolean authoritativeReads() {
        return false;
    }

    default boolean authoritativeCoreReads() {
        return false;
    }

    default boolean allowLegacyReads() {
        return !durable();
    }

    default ResourceDocument<Map<String, Object>> load(ServerResourceLocator resource) {
        return null;
    }

    default List<ResourceDocument<Map<String, Object>>> list(ServerId serverId,
                                                              ContractRef<ResourceTypeId> type,
                                                              String search) {
        return List.of();
    }

    boolean durable();

    default boolean supportsActivation() {
        return false;
    }

    default boolean supports(ResourceOperation operation) {
        return operation != null && switch (operation.kind()) {
            case CREATE, SAVE, DELETE, DUPLICATE -> true;
            case ACTIVATE -> supportsActivation();
            default -> false;
        };
    }

    default String unsupportedOperationReason(ResourceOperation operation) {
        String name = operation == null ? "unknown" : operation.kind().name().toLowerCase(Locale.ROOT);
        return "The durable resource authority does not expose a " + name + " boundary";
    }

    default ProtocolEnvelopeDispatchResult rejectUnsupported(ResourceOperation operation) {
        if (!durable()) {
            return ProtocolEnvelopeDispatchResult.rejected(ProtocolRejectionCode.RESOURCE_DURABILITY_UNAVAILABLE,
                unsupportedOperationReason(operation));
        }
        return ProtocolEnvelopeDispatchResult.rejected(ProtocolRejectionCode.RESOURCE_OPERATION_UNSUPPORTED,
            unsupportedOperationReason(operation));
    }

    static ProtocolResourceMutationAuthority failClosed() {
        return new FailClosedProtocolResourceMutationAuthority();
    }

    static ProtocolResourceMutationAuthority require(ProtocolResourceMutationAuthority authority) {
        return Objects.requireNonNull(authority, "Mutation authority is required");
    }
}
