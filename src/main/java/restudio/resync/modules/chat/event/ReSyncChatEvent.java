package restudio.resync.modules.chat.event;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;

public abstract class ReSyncChatEvent extends Event {
    private final Player player;
    private final String message;
    private final String channel;

    protected ReSyncChatEvent(Player player, String message, String channel) {
        super(!Bukkit.isPrimaryThread());
        this.player = player;
        this.message = message == null ? "" : message;
        this.channel = channel == null ? "" : channel;
    }

    public Player getPlayer() {
        return player;
    }

    public String getMessage() {
        return message;
    }

    public String getChannel() {
        return channel;
    }
}
