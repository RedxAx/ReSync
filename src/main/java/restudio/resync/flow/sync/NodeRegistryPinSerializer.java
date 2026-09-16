package restudio.resync.flow.sync;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import restudio.resync.flow.registry.NodeDefinition;

import java.lang.reflect.Type;

public final class NodeRegistryPinSerializer implements JsonSerializer<NodeDefinition.PinDefinition> {
    @Override
    public JsonElement serialize(NodeDefinition.PinDefinition pin, Type type, JsonSerializationContext context) {
        if (pin == null) {
            return JsonNull.INSTANCE;
        }
        JsonObject value = new JsonObject();
        value.addProperty("id", pin.getName());
        value.addProperty("name", pin.getRuntimeName());
        value.addProperty("runtimeName", pin.getRuntimeName());
        value.addProperty("displayName", pin.getDisplayName());
        value.add("type", context.serialize(pin.getType()));
        value.add("direction", context.serialize(pin.getDirection()));
        value.addProperty("dataType", pin.getDataType().getId());
        value.add("typeRef", context.serialize(pin.getTypeRef()));
        value.add("repeatable", context.serialize(pin.getRepeatable()));
        value.add("widgetType", context.serialize(pin.getWidgetType()));
        value.add("options", context.serialize(pin.getOptions()));
        value.add("optionsSource", context.serialize(pin.getOptionsSource()));
        value.add("defaultValue", context.serialize(pin.getDefaultValue()));
        value.add("constraints", context.serialize(pin.getConstraints()));
        value.add("visibleWhen", context.serialize(pin.getVisibleWhen()));
        value.add("description", context.serialize(pin.getDescription()));
        value.addProperty("optional", pin.isOptional());
        return value;
    }
}
