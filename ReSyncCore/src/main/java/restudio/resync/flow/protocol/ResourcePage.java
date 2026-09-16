package restudio.resync.flow.protocol;

import java.util.List;

public record ResourcePage<P>(List<ResourceDocument<P>> items, String nextCursor, boolean complete) {
    public ResourcePage {
        items = ProtocolValues.list(items, "items");
        if (items.size() > 500) {
            throw new IllegalArgumentException("A resource page cannot contain more than 500 items");
        }
        nextCursor = ProtocolValues.optionalText(nextCursor, "nextCursor", 2048);
        if (complete && nextCursor != null) {
            throw new IllegalArgumentException("Complete pages cannot expose a next cursor");
        }
        if (!complete && nextCursor == null) {
            throw new IllegalArgumentException("Incomplete pages require a next cursor");
        }
    }
}
