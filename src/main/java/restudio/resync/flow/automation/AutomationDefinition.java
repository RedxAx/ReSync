package restudio.resync.flow.automation;

public interface AutomationDefinition {
    String id();

    String name();

    String description();

    AutomationScope scope();

    boolean persistent();
}
