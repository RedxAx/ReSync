package restudio.flow.data;

import com.google.gson.Gson;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FlowDataObjectAdapter implements JsonSerializer<FlowDataObject>, JsonDeserializer<FlowDataObject> {
    private static final Gson GSON = new Gson();
    private static final Map<String, Class<? extends FlowDataObject>> GLOBAL_REGISTRY = new ConcurrentHashMap<>();
    private static TypeRegistry typeRegistry;
    private final Map<String, Class<? extends FlowDataObject>> registry = new HashMap<>();

    public FlowDataObjectAdapter() {
        registerGlobal("item", FlowItem.class);
        registerGlobal("block", FlowBlock.class);
        registerGlobal("entity", FlowEntityRef.class);
        registerGlobal("world", FlowWorldRef.class);
        registerGlobal("enchantment", FlowEnchantment.class);
    }

    public static void registerGlobal(String typeId, Class<? extends FlowDataObject> type) {
        if (typeId == null || typeId.isBlank() || type == null) {
            return;
        }
        GLOBAL_REGISTRY.put(typeId.toLowerCase(), type);
    }

    public static void setTypeRegistry(TypeRegistry registry) {
        typeRegistry = registry;
    }

    public final void register(String typeId, Class<? extends FlowDataObject> type) {
        if (typeId == null || typeId.isBlank() || type == null) {
            return;
        }
        String lower = typeId.toLowerCase();
        registry.put(lower, type);
        GLOBAL_REGISTRY.put(lower, type);
    }

    @Override
    public JsonElement serialize(FlowDataObject src, Type typeOfSrc, JsonSerializationContext context) {
        JsonObject object = src.toJson();
        object.addProperty("_type", src.getTypeId());
        return object;
    }

    @Override
    public FlowDataObject deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        if (!json.isJsonObject()) {
            return null;
        }
        JsonObject object = json.getAsJsonObject();
        JsonElement typeElement = object.get("_type");
        if (typeElement == null || typeElement.isJsonNull()) {
            return null;
        }
        return deserializeByType(typeElement.getAsString(), object);
    }

    public static FlowDataObject deserializeByType(String typeId, JsonObject object) {
        if (typeId == null || object == null) {
            return null;
        }
        String lower = typeId.toLowerCase();
        JsonObject copy = object.deepCopy();
        copy.remove("_type");

        Class<? extends FlowDataObject> globalType = GLOBAL_REGISTRY.get(lower);
        if (globalType != null) {
            return GSON.fromJson(copy, globalType);
        }

        if (typeRegistry != null) {
            FlowDataType dynamicType = typeRegistry.get(lower);
            if (dynamicType != null) {
                Class<? extends FlowDataObject> dataClass = dynamicType.getDataClass();
                if (dataClass != null) {
                    return GSON.fromJson(copy, dataClass);
                }
            }
        }

        return null;
    }
}
