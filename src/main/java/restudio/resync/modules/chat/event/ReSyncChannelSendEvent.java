package restudio.resync.modules.chat.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

public class ReSyncChannelSendEvent extends ReSyncChatEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    public ReSyncChannelSendEvent(Player player, String message, String channel) {
        super(player, message, channel);
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
