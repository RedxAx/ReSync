package restudio.resync.flow.validation;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public enum ValidationTarget {
    CATALOG("catalog"),
    GRAPH("graph"),
    INSPECTOR("inspector"),
    MIGRATION("migration"),
    PROTOCOL("protocol"),
    RUNTIME("runtime");

    private final String wireName;

    ValidationTarget(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static Set<ValidationTarget> all() {
        return Collections.unmodifiableSet(EnumSet.allOf(ValidationTarget.class));
    }
}
