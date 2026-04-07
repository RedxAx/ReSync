package restudio.resync.flow.triggers;

import org.bukkit.event.EventPriority;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface FlowTrigger {

    String eventType();

    String nodeType() default "";

    Class<?> eventClass();

    EventPriority priority() default EventPriority.NORMAL;

    String[] aliases() default {};

    boolean playerEvent() default true;

    boolean ignoreCancelled() default false;

    String playerExtractor() default "";
}
