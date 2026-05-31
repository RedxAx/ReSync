package restudio.resync.modules.chat.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

public class ReSyncChannelJoinEvent extends ReSyncChatEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    public ReSyncChannelJoinEvent(Player player, String channel) {
        super(player, "", channel);
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
