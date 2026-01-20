package restudio.resync.flow;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import restudio.flow.data.GuiDefinition;

public class RemotelyHolder implements InventoryHolder {
    private final GuiDefinition guiDefinition;
    private final Player owner;

    public RemotelyHolder(GuiDefinition guiDefinition, Player owner) {
        this.guiDefinition = guiDefinition;
        this.owner = owner;
    }

    @Override
    public Inventory getInventory() {
        Inventory inv = Bukkit.createInventory(this, guiDefinition.getRows() * 9, guiDefinition.getTitle());
        return inv;
    }

    public GuiDefinition getGuiDefinition() {
        return guiDefinition;
    }

    public Player getOwner() {
        return owner;
    }
}
