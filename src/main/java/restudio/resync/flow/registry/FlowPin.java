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

    NodeDefinition.WidgetType widget() default NodeDefinition.WidgetType.AUTO;

    String[] options() default {};

    String optionsSource() default "";

    String defaultValue() default "";

    double min() default Double.NaN;

    double max() default Double.NaN;

    double step() default Double.NaN;

    VisibleWhen[] visibleWhen() default {};

    String description() default "";
}
