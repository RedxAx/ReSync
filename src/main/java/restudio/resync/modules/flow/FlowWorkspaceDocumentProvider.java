package restudio.resync.modules.flow;

import com.google.gson.JsonObject;

public interface FlowWorkspaceDocumentProvider {
    String type();

    JsonObject load(String resourceId);

    void persist(String resourceId, JsonObject document);
}
