package restudio.resync.flow.handler.family;

import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.flow.handler.property.PropertyRegistry;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonFamilyHandlerTest {
    @Test
    void playerFamilyReadsExplicitStateProperties() throws Exception {
        PlayerInventory inventory = proxy(PlayerInventory.class, (proxy, method, args) -> switch (method.getName()) {
            case "getSize" -> 36;
            case "getMaxStackSize" -> 64;
            default -> fallback(method.getReturnType());
        });
        UUID uuid = UUID.randomUUID();
        Player player = proxy(Player.class, (proxy, method, args) -> switch (method.getName()) {
            case "getName" -> "RedxAx";
            case "getUniqueId" -> uuid;
            case "getHealth" -> 18.0;
            case "getLevel" -> 12;
            case "getTotalExperience" -> 420;
            case "getExpToLevel" -> 33;
            case "isOp" -> true;
            case "getInventory" -> inventory;
            default -> fallback(method.getReturnType());
        });

        NodeHandler handler = family("player");

        assertEquals("RedxAx", read(handler, player, "name"));
        assertEquals(uuid.toString(), read(handler, player, "uuid"));
        assertEquals(18.0, read(handler, player, "health"));
        assertEquals(true, read(handler, player, "is_op"));
        assertEquals(12, read(handler, player, "xp_level"));
        assertEquals(420, read(handler, player, "total_exp"));
        assertEquals(33, read(handler, player, "exp_to_level"));
        assertSame(inventory, read(handler, player, "inventory"));
    }

    @Test
    void entityFamilyReadsExplicitStateProperties() throws Exception {
        AttributeInstance maxHealth = proxy(AttributeInstance.class, (proxy, method, args) -> switch (method.getName()) {
            case "getValue" -> 20.0;
            default -> fallback(method.getReturnType());
        });
        LivingEntity entity = proxy(LivingEntity.class, (proxy, method, args) -> switch (method.getName()) {
            case "getType" -> EntityType.ZOMBIE;
            case "getUniqueId" -> UUID.fromString("00000000-0000-0000-0000-000000000001");
            case "getHealth" -> 7.0;
            case "getAttribute" -> maxHealth;
            case "getVelocity" -> null;
            case "isValid" -> true;
            case "isDead" -> false;
            default -> fallback(method.getReturnType());
        });

        NodeHandler handler = family("entity");

        assertEquals("ZOMBIE", read(handler, entity, "type"));
        assertEquals(7.0, read(handler, entity, "health"));
        assertEquals(20.0, read(handler, entity, "max_health"));
        assertEquals(true, read(handler, entity, "is_alive"));
    }

    @Test
    void worldBlockAndInventoryFamiliesReadExplicitProperties() throws Exception {
        World world = proxy(World.class, (proxy, method, args) -> switch (method.getName()) {
            case "getTime" -> 6000L;
            case "getFullTime" -> 24000L;
            case "hasStorm" -> true;
            case "isThundering" -> false;
            case "getDifficulty" -> Difficulty.HARD;
            case "getPVP" -> true;
            default -> fallback(method.getReturnType());
        });
        BlockData blockData = proxy(BlockData.class, (proxy, method, args) -> switch (method.getName()) {
            case "getAsString" -> "minecraft:stone";
            default -> fallback(method.getReturnType());
        });
        BlockState state = proxy(BlockState.class, (proxy, method, args) -> switch (method.getName()) {
            case "getBlockData" -> blockData;
            default -> fallback(method.getReturnType());
        });
        Block block = proxy(Block.class, (proxy, method, args) -> switch (method.getName()) {
            case "getType" -> Material.STONE;
            case "getBlockData" -> blockData;
            case "getState" -> state;
            case "isSolid" -> true;
            default -> fallback(method.getReturnType());
        });
        ItemStack[] items = new ItemStack[] {null};
        Inventory inventory = proxy(Inventory.class, (proxy, method, args) -> switch (method.getName()) {
            case "getSize" -> 54;
            case "getContents", "getStorageContents" -> items;
            case "firstEmpty" -> 4;
            case "getMaxStackSize" -> 64;
            default -> fallback(method.getReturnType());
        });

        assertEquals(6000L, read(family("world"), world, "time"));
        assertEquals(24000L, read(family("world"), world, "full_time"));
        assertEquals("rain", read(family("world"), world, "weather_type"));
        assertEquals(true, read(family("world"), world, "has_storm"));
        assertEquals("HARD", read(family("world"), world, "difficulty"));
        assertEquals(true, read(family("world"), world, "pvp"));
        assertEquals("STONE", read(family("block"), block, "type"));
        assertEquals("minecraft:stone", read(family("block"), block, "data"));
        assertEquals(true, read(family("block"), block, "is_solid"));
        assertEquals(54, read(family("inventory"), inventory, "size"));
        assertSame(items, read(family("inventory"), inventory, "items"));
        assertEquals(4, read(family("inventory"), inventory, "first_empty"));
        assertEquals(64, read(family("inventory"), inventory, "max_stack_size"));
    }

    private NodeHandler family(String id) {
        HandlerRegistry registry = new HandlerRegistry();
        JsonFamilyHandler.registerFamilies(registry, new PropertyRegistry());
        return registry.getHandler(id);
    }

    private Object read(NodeHandler handler, Object target, String property) throws Exception {
        Method method = handler.getClass().getDeclaredMethod("readValue", Object.class, String.class);
        method.setAccessible(true);
        return method.invoke(handler, target, property);
    }

    private <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    private Object fallback(Class<?> returnType) {
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == double.class) {
            return 0.0;
        }
        if (returnType == float.class) {
            return 0.0f;
        }
        return null;
    }
}
