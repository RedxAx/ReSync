package restudio.request;

public record Quest(
    String id,
    String title,
    String description,
    String reward,
    int target,
    String permission,
    String world,
    String requiredQuest,
    int maxActive,
    int cooldownSeconds,
    int requiredLevel,
    int xpReward,
    String scope,
    String owner,
    long createdAt
) {
    public Quest(String id, String title, String description, String reward, int target, String permission, String world, String requiredQuest, int maxActive, int cooldownSeconds, int requiredLevel, int xpReward, String scope, String owner) {
        this(id, title, description, reward, target, permission, world, requiredQuest, maxActive, cooldownSeconds, requiredLevel, xpReward, scope, owner, System.currentTimeMillis());
    }

    public Quest(String id, String title, String description, String reward, int target, String permission, String world, String requiredQuest, int maxActive, int cooldownSeconds, int requiredLevel, int xpReward) {
        this(id, title, description, reward, target, permission, world, requiredQuest, maxActive, cooldownSeconds, requiredLevel, xpReward, "global", "");
    }

    public Quest normalize() {
        String normalizedScope = scope(scope);
        return new Quest(
            cleanId(id),
            text(title, cleanId(id)),
            text(description, ""),
            text(reward, ""),
            Math.max(1, target),
            text(permission, ""),
            text(world, ""),
            cleanId(requiredQuest),
            Math.max(1, maxActive),
            Math.max(0, cooldownSeconds),
            Math.max(1, requiredLevel),
            Math.max(0, xpReward),
            normalizedScope,
            "global".equals(normalizedScope) ? "" : text(owner, ""),
            createdAt > 0 ? createdAt : System.currentTimeMillis()
        );
    }

    public Quest withOwner(String owner) {
        return new Quest(id, title, description, reward, target, permission, world, requiredQuest, maxActive, cooldownSeconds, requiredLevel, xpReward, scope, owner, createdAt).normalize();
    }

    private static String cleanId(String value) {
        String normalized = text(value, "").trim().toLowerCase().replace(' ', '_');
        return normalized.replaceAll("[^a-z0-9_.:-]", "");
    }

    private static String scope(String value) {
        String normalized = text(value, "global").trim().toLowerCase().replace(' ', '_');
        return switch (normalized) {
            case "player", "permission" -> normalized;
            default -> "global";
        };
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
