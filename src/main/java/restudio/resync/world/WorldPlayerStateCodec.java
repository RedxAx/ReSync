package restudio.resync.world;

import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

public final class WorldPlayerStateCodec {
    private WorldPlayerStateCodec() {
    }

    public static WorldPlayerState capture(Player player, String worldKey) {
        WorldPlayerState state = new WorldPlayerState();
        state.setWorldName(worldKey);
        state.setGameMode(player.getGameMode().name());
        state.setHealth(player.getHealth());
        state.setFoodLevel(player.getFoodLevel());
        state.setSaturation(player.getSaturation());
        state.setExhaustion(player.getExhaustion());
        state.setExpProgress(player.getExp());
        state.setExpLevel(player.getLevel());
        state.setTotalExp(player.getTotalExperience());
        state.setInventory(encodeItemStacks(player.getInventory().getStorageContents()));
        state.setArmor(encodeItemStacks(player.getInventory().getArmorContents()));
        state.setOffhand(encodeItemStacks(new ItemStack[]{player.getInventory().getItemInOffHand()}));
        state.setUpdatedAt(System.currentTimeMillis());
        return state;
    }

    public static void apply(Player player, WorldPlayerState state) {
        if (player == null || state == null) {
            return;
        }
        if (state.getGameMode() != null && !state.getGameMode().isBlank()) {
            try {
                player.setGameMode(GameMode.valueOf(state.getGameMode()));
            } catch (IllegalArgumentException ignored) {
            }
        }
        double maxHealth = player.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null
            ? player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue()
            : 20.0;
        double health = state.getHealth();
        if (health <= 0) {
            health = maxHealth;
        }
        player.setHealth(Math.max(0.01, Math.min(maxHealth, health)));
        player.setFoodLevel(Math.max(0, Math.min(20, state.getFoodLevel())));
        player.setSaturation(Math.max(0f, Math.min(20f, state.getSaturation())));
        player.setExhaustion(Math.max(0f, state.getExhaustion()));
        player.setLevel(Math.max(0, state.getExpLevel()));
        player.setTotalExperience(Math.max(0, state.getTotalExp()));
        player.setExp(Math.max(0f, Math.min(1f, state.getExpProgress())));
        player.getInventory().setStorageContents(decodeItemStacks(state.getInventory(), 36));
        player.getInventory().setArmorContents(decodeItemStacks(state.getArmor(), 4));
        ItemStack[] offhand = decodeItemStacks(state.getOffhand(), 1);
        player.getInventory().setItemInOffHand(offhand.length > 0 ? offhand[0] : null);
        player.updateInventory();
    }

    private static String encodeItemStacks(ItemStack[] items) {
        if (items == null) {
            return "";
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            BukkitObjectOutputStream data = new BukkitObjectOutputStream(output);
            data.writeInt(items.length);
            for (ItemStack item : items) {
                data.writeObject(item);
            }
            data.close();
            return Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (Exception ignored) {
            return "";
        }
    }

    private static ItemStack[] decodeItemStacks(String encoded, int fallbackSize) {
        if (encoded == null || encoded.isBlank()) {
            return new ItemStack[fallbackSize];
        }
        try {
            byte[] raw = Base64.getDecoder().decode(encoded);
            BukkitObjectInputStream data = new BukkitObjectInputStream(new ByteArrayInputStream(raw));
            int size = data.readInt();
            ItemStack[] items = new ItemStack[Math.max(0, size)];
            for (int index = 0; index < items.length; index++) {
                Object value = data.readObject();
                items[index] = value instanceof ItemStack item ? item : null;
            }
            data.close();
            return items;
        } catch (Exception ignored) {
            return new ItemStack[fallbackSize];
        }
    }
}
