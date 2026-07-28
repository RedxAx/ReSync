package restudio.resync.flow.automation;

public record AutomationInstanceKey(String definitionId, AutomationScope scope, String ownerId) {
    public AutomationInstanceKey {
        if (definitionId == null || definitionId.isBlank() || scope == null || ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("Automation definition, scope, and owner are required");
        }
    }

    public String storageKey(String capability) {
        return "automation." + capability + "." + definitionId + "." + scope.name().toLowerCase() + "." + ownerId;
    }
}
