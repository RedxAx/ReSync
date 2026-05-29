package restudio.resync.modules.flow;

import restudio.resync.core.Session;
import restudio.resync.resources.ReSyncManagedResource;

import java.util.List;

public interface FlowResourceAdapter<T> {
    ReSyncManagedResource descriptor();

    T get(String id);

    List<String> listIds();

    T deserialize(String json);

    String id(T value);

    void save(T value);

    void delete(String id);

    void sendData(Session session, T value);

    void sendList(Session session, List<String> ids);

    void sendSaveAck(Session session, String id);

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
    }

    default void afterDelete(Session session, String id) {
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
