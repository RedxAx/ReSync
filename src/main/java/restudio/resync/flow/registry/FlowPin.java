package restudio.resync.flow.registry;

import restudio.flow.data.FlowType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface FlowPin {

    String name();

    NodeDefinition.PinType type() default NodeDefinition.PinType.DATA;

    FlowType dataType() default FlowType.ANY;
}
