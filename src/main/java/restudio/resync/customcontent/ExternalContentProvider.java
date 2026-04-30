package restudio.resync.customcontent;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import restudio.flow.data.CustomContentDefinition;

public class ExternalContentProvider implements CustomContentProvider {
    private final String id;
    private final String pluginName;
    private final VanillaContentProvider fallback;

    public ExternalContentProvider(String id, String pluginName, VanillaContentProvider fallback) {
        this.id = id;
        this.pluginName = pluginName;
        this.fallback = fallback;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public boolean isAvailable() {
        return Bukkit.getPluginManager().getPlugin(pluginName) != null;
    }

    @Override
    public ItemStack createItem(CustomContentDefinition definition, int amount) {
        return fallback.createItem(definition, amount);
    }

    @Override
    public String identifyItem(ItemStack item) {
        return fallback.identifyItem(item);
    }

    @Override
    public String identifyBlock(Location location) {
        return fallback.identifyBlock(location);
    }

    @Override
    public void markPlacedBlock(Location location, CustomContentDefinition definition) {
        fallback.markPlacedBlock(location, definition);
    }

    @Override
    public void clearPlacedBlock(Location location) {
        fallback.clearPlacedBlock(location);
    }
}
