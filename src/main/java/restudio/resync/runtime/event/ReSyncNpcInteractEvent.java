package restudio.resync.runtime.event;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import restudio.flow.data.FlowNpcHandle;

public final class ReSyncNpcInteractEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final FlowNpcHandle handle;
    private final Player player;
    private final Entity entity;
    private final Location location;
    private final boolean leftClick;
    private final boolean shifting;
    private boolean cancelled;

    public ReSyncNpcInteractEvent(FlowNpcHandle handle, Player player, Entity entity, Location location, boolean leftClick, boolean shifting) {
        this.handle = handle;
        this.player = player;
        this.entity = entity;
        this.location = location != null ? location.clone() : null;
        this.leftClick = leftClick;
        this.shifting = shifting;
    }

    public FlowNpcHandle getHandle() {
        return handle;
    }

    public String getNpcId() {
        return handle != null ? handle.definitionId() : "";
    }

    public Player getPlayer() {
        return player;
    }

    public Entity getEntity() {
        return entity;
    }

    public Location getLocation() {
        return location != null ? location.clone() : null;
    }

    public boolean isLeftClick() {
        return leftClick;
    }

    public boolean isRightClick() {
        return !leftClick;
    }

    public boolean isShifting() {
        return shifting;
    }

    public boolean isPacketBacked() {
        return handle != null && handle.packetBacked();
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
