package restudio.resync.flow.protocol;

import java.util.List;

public record ResourcePresentationIntent(String displayName, String path, int sortOrder) {
    public ResourcePresentationIntent {
        displayName = ProtocolValues.requiredText(displayName, "displayName", 256);
        path = ProtocolValues.requiredText(path, "path", 1024);
        if (!displayName.equals(displayName.strip()) || displayName.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("displayName must be untrimmed and contain no control characters");
        }
        if (!path.equals(path.strip()) || !path.equals(path.replace('\\', '/')) || path.startsWith("/") || path.endsWith("/")
            || path.chars().anyMatch(Character::isISOControl) || path.chars().anyMatch(character -> "<>:\"|?*".indexOf(character) >= 0)
            || path.contains("//")
            || List.of(path.split("/", -1)).stream().anyMatch(segment -> segment.isBlank() || ".".equals(segment) || "..".equals(segment))) {
            throw new IllegalArgumentException("path must be a canonical relative resource path");
        }
        if (sortOrder < 0) {
            throw new IllegalArgumentException("sortOrder must not be negative");
        }
    }
}
