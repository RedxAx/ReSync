package restudio.flow.data;

import com.google.gson.JsonObject;

public interface FlowDataObject {
    String getTypeId();

    default String getType() {
        return getTypeId();
    }

    JsonObject toJson();

    static FlowDataObject fromJson(String typeId, JsonObject json) {
        return FlowDataObjectAdapter.deserializeByType(typeId, json);
    }
}
