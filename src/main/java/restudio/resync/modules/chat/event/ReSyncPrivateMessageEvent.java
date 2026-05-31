package restudio.resync.modules.chat.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

public class ReSyncPrivateMessageEvent extends ReSyncChatEvent {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player receiver;

    public ReSyncPrivateMessageEvent(Player player, Player receiver, String message) {
        super(player, message, "");
        this.receiver = receiver;
    }

    public Player getReceiver() {
        return receiver;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
