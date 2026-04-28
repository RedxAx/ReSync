package restudio.resync.flow.handler.property;

import restudio.flow.data.FlowDataType;

import java.util.List;

public interface PropertyHandler<Target, Value> {
    String getPropertyName();

    FlowDataType getDataType();

    default Class<?> getValueType() {
        return getDataType().getJavaType();
    }

    List<String> getSupportedActions();

    Value get(Target target);

    boolean set(Target target, Value value);

    boolean execute(Target target);
}
