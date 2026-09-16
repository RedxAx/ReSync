package restudio.resync.modules.flow;

import com.google.gson.Gson;
import restudio.resync.core.Session;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ResourcePresentationIntent;
import restudio.resync.resources.ReSyncManagedResource;
import restudio.resync.server.AggregateResourceCreateStorage;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface FlowResourceAdapter<T> {
    String AUTHORITATIVE_MUTATION_IDENTITY_UNAVAILABLE =
        "Authoritative resource mutation identity durability is unavailable";

    ReSyncManagedResource descriptor();

    T get(String id);

    default boolean conflicts(String id) {
        return get(id) != null;
    }

    List<String> listIds();

    T deserialize(String json);

    default String serialize(T value) {
        return new Gson().toJson(value);
    }

    String id(T value);

    void save(T value);

    void delete(String id);

    default boolean supportsAuthoritativeMutationIdentity() {
        return false;
    }

    default void save(T value, UUID mutationId, long expectedRevision) {
        throw authoritativeMutationIdentityUnavailable();
    }

    default void delete(String id, UUID mutationId, long expectedRevision) {
        throw authoritativeMutationIdentityUnavailable();
    }

    default boolean supportsAggregateCreate() {
        return false;
    }

    default AggregateResourceCreateStorage.Result createAggregate(ServerResourceLocator resource, T value,
                                                                   FlowResourceMutationContext context,
                                                                   ResourcePresentationIntent presentation) {
        throw new UnsupportedOperationException("Aggregate resource create is unavailable");
    }

    default FlowResourceMutationStamp readMutationStamp(String id) {
        throw authoritativeMutationIdentityUnavailable();
    }

    default void completePostCommitRecovery(String id, UUID mutationId, long revision, boolean deleted) {
    }

    default boolean matchesCommittedPayloadRecovery(T previous, T requested, T actual) {
        return false;
    }

    default FlowResourceMutationStamp recoverProjectMetadataLineage(UUID sourceMutationId, String sourceType,
                                                                     String sourceId, long sourceRevision,
                                                                     String sourceHash, long previousMetadataRevision,
                                                                     UUID previousMetadataMutationId,
                                                                     String previousMetadataHash) {
        throw new UnsupportedOperationException("Project metadata lineage recovery is unavailable");
    }

    default FlowResourceMutationStamp recoverUnreceiptedProjectMetadataLineage(long previousMetadataRevision,
                                                                                UUID previousMetadataMutationId,
                                                                                String previousMetadataHash) {
        return null;
    }

    default void validate(T value) {
    }

    default Set<String> supportedOperations() {
        return Set.of("discover", "query", "get", "create", "validate", "save", "update", "delete");
    }

    default String unsupportedOperationReason(String operation) {
        return switch (operation != null ? operation : "") {
            case "rename" -> "This resource domain does not expose a durable presentation rename transaction";
            case "move" -> "This resource domain does not expose a durable presentation move transaction";
            case "subscribe" -> "This resource domain does not expose a session-bound durable resource subscription";
            case "duplicate" -> "This resource domain does not support duplication";
            case "reload" -> "This resource domain does not expose an explicit reload operation";
            case "apply" -> "This resource domain does not expose a generic apply operation";
            default -> "This resource operation is not supported by the authoritative service";
        };
    }

    default T duplicate(T value, String targetId) {
        throw new UnsupportedOperationException("Resource duplication is unsupported");
    }

    default T reload(String id) {
        throw new UnsupportedOperationException("Resource reload is unsupported");
    }

    default Object apply(T value, Object context) {
        throw new UnsupportedOperationException("Resource application is unsupported");
    }

    default String identityRules() {
        return "stable_id";
    }

    default String lifecycle() {
        return "durable";
    }

    default boolean durable() {
        return !"ephemeral".equalsIgnoreCase(lifecycle());
    }

    default String catalogSource() {
        return "server:resync:" + descriptor().typeId();
    }

    default String authoritativeService() {
        return descriptor().jsonStorageSupported() ? "ReSyncJsonResourceStorage" : "FlowStorage";
    }

    default boolean changeEvents() {
        return descriptor().jsonStorageSupported();
    }

    default boolean activeRefresh() {
        return false;
    }

    default void sendData(Session session, T value) {
    }

    default void sendList(Session session, List<String> ids) {
    }

    default void sendSaveAck(Session session, String id) {
    }

    default void sendSaveAck(Session session, String id, String requestId) {
        sendSaveAck(session, id);
    }

    default String requestMissingMessage() {
        return descriptor().displayName() + " ID not provided";
    }

    default String defaultRequestId() {
        return null;
    }

    default String invalidIdCode() {
        return "INVALID_" + descriptor().typeId().toUpperCase() + "_ID";
    }

    default String notFoundCode() {
        return descriptor().typeId().toUpperCase() + "_NOT_FOUND";
    }

    default String notFoundMessage(String id) {
        return descriptor().displayName() + " not found: " + id;
    }

    default String invalidValueCode() {
        return "INVALID_" + descriptor().typeId().toUpperCase();
    }

    default String missingIdMessage() {
        return descriptor().displayName() + " ID is missing";
    }

    default String saveAction() {
        return "save" + compactDisplayName();
    }

    default String deleteAction() {
        return "delete" + compactDisplayName();
    }

    default String saveErrorCode() {
        return "SAVE_FAILED";
    }

    default String deleteErrorCode() {
        return "DELETE_FAILED";
    }

    default String saveFailureMessage(Exception exception) {
        return "Failed to save " + descriptor().displayName() + ": " + exception.getMessage();
    }

    default String deleteFailureMessage(Exception exception) {
        return "Failed to delete " + descriptor().displayName() + ": " + exception.getMessage();
    }

    default void afterSave(Session session, T value) {
        afterSave(value);
    }

    default void afterDelete(Session session, String id) {
        afterDelete(id);
    }

    default void afterSave(T value) {
    }

    default void afterDelete(String id) {
    }

    private static IllegalStateException authoritativeMutationIdentityUnavailable() {
        return new IllegalStateException(AUTHORITATIVE_MUTATION_IDENTITY_UNAVAILABLE);
    }

    private String compactDisplayName() {
        String displayName = descriptor().displayName();
        StringBuilder builder = new StringBuilder();
        boolean uppercaseNext = true;
        for (int i = 0; i < displayName.length(); i++) {
            char current = displayName.charAt(i);
            if (!Character.isLetterOrDigit(current)) {
                uppercaseNext = true;
                continue;
            }
            builder.append(uppercaseNext ? Character.toUpperCase(current) : current);
            uppercaseNext = false;
        }
        return builder.toString();
    }
}
