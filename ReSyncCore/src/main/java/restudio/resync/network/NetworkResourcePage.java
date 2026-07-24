package restudio.resync.network;

import java.util.List;
import java.util.Locale;

public record NetworkResourcePage(List<NetworkResourceMetadata> resources, String nextType, String nextResourceId) {
    public NetworkResourcePage {
        resources = resources == null ? List.of() : List.copyOf(resources);
        nextType = NetworkValues.normalized(nextType).toLowerCase(Locale.ROOT);
        nextResourceId = NetworkValues.normalized(nextResourceId);
        if (resources.size() > 128) {
            throw new IllegalArgumentException("Network Resource Page Is Too Large");
        }
        if (nextType.isBlank() && !nextResourceId.isBlank()) {
            throw new IllegalArgumentException("Resource Cursor Type Is Required");
        }
    }

    public boolean hasNext() {
        return !nextType.isBlank();
    }
}
