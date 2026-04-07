package restudio.resync.flow.registry;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface DefineNode {

    String id();

    String displayName();

    NodeDefinition.NodeCategory category() default NodeDefinition.NodeCategory.ACTION;

    FlowPin[] inputs() default {};

    FlowPin[] outputs() default {};

    int color() default -1;

    int priority() default 0;

    boolean hidden() default false;
}
