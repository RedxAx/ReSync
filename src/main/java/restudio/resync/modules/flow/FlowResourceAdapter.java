package restudio.resync.modules.flow;

import com.google.gson.Gson;
import restudio.resync.core.Session;
import restudio.resync.resources.ReSyncManagedResource;

import java.util.List;
import java.util.Set;

public interface FlowResourceAdapter<T> {
    ReSyncManagedResource descriptor();

    T get(String id);

    List<String> listIds();

    T deserialize(String json);

    default String serialize(T value) {
        return new Gson().toJson(value);
    }

    String id(T value);

    void save(T value);

    void delete(String id);

    default void validate(T value) {
    }

    default Set<String> supportedOperations() {
        return Set.of("discover", "query", "get", "create", "validate", "save", "update", "delete");
    }

    default String unsupportedOperationReason(String operation) {
        return switch (operation != null ? operation : "") {
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
