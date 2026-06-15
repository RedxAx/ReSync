package restudio.resync.runtime;

import com.google.gson.JsonObject;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public interface PlayerNpcRuntime {
    boolean available();

    String unavailableReason();

    boolean spawn(String id, JsonObject definition, Location location);

    boolean despawn(String id);

    boolean reload(String id, JsonObject definition, boolean deleted, Location fallbackLocation);

    boolean isActive(String id);

    Location location(String id);

    void shutdown();

    static PlayerNpcRuntime disabled(String reason) {
        return new DisabledPlayerNpcRuntime(reason, null);
    }

    static PlayerNpcRuntime disabled(String reason, RuntimeNotificationService notifications) {
        return new DisabledPlayerNpcRuntime(reason, notifications);
    }

    final class DisabledPlayerNpcRuntime implements PlayerNpcRuntime {
        private final String reason;
        private final RuntimeNotificationService notifications;

        private DisabledPlayerNpcRuntime(String reason, RuntimeNotificationService notifications) {
            this.reason = reason == null || reason.isBlank() ? "Player NPC runtime unavailable" : reason;
            this.notifications = notifications;
        }

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public String unavailableReason() {
            return reason;
        }

        @Override
        public boolean spawn(String id, JsonObject definition, Location location) {
            if (notifications != null) {
                notifications.broadcastError(reason);
            }
            return false;
        }

        @Override
        public boolean despawn(String id) {
            return false;
        }

        @Override
        public boolean reload(String id, JsonObject definition, boolean deleted, Location fallbackLocation) {
            return false;
        }

        @Override
        public boolean isActive(String id) {
            return false;
        }

        @Override
        public Location location(String id) {
            return null;
        }

        @Override
        public void shutdown() {
        }
    }

    interface InteractionDispatcher {
        void interact(String id, Player player, Location location, boolean leftClick, boolean shifting);
    }
}
