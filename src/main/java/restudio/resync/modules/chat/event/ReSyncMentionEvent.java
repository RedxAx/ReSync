package restudio.resync.modules.chat.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

public class ReSyncMentionEvent extends ReSyncChatEvent {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player mentioned;

    public ReSyncMentionEvent(Player player, Player mentioned, String message, String channel) {
        super(player, message, channel);
        this.mentioned = mentioned;
    }

    public Player getMentioned() {
        return mentioned;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
