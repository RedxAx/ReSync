package restudio.resync.customcontent;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import restudio.flow.data.CustomContentDefinition;

public interface CustomContentProvider {
    String getId();

    boolean isAvailable();

    ItemStack createItem(CustomContentDefinition definition, int amount);

    String identifyItem(ItemStack item);

    String identifyBlock(Location location);

    void markPlacedBlock(Location location, CustomContentDefinition definition);

    void clearPlacedBlock(Location location);
}
