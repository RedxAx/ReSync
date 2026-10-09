package restudio.resync.flow.automation;

public interface AutomationDefinition {
    enum Kind {
        VARIABLE,
        TIMER,
        SCHEDULE
    }

    Kind kind();

    String id();

    String name();

    String description();

    AutomationScope scope();

    boolean persistent();
}
