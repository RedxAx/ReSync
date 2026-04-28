package restudio.flow.data;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.advancement.Advancement;
import org.bukkit.block.Block;
import org.bukkit.block.Biome;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;
import net.kyori.adventure.text.Component;

import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

public final class FlowDataType {
    public static final FlowDataType EXECUTION = new FlowDataType("execution", null, Void.class, null, 0xFFFFFF);
    public static final FlowDataType ANY = new FlowDataType("any", null, Object.class, null, 0x808080);
    public static final FlowDataType STRING = new FlowDataType("string", null, String.class, null, 0xDA00FF);
    public static final FlowDataType NUMBER = new FlowDataType("number", null, Number.class, null, 0x00FF93);
    public static final FlowDataType BOOLEAN = new FlowDataType("boolean", null, Boolean.class, null, 0xD20000);
    public static final FlowDataType ENTITY = new FlowDataType("entity", null, Entity.class, FlowEntityRef.class, 0x8B4513);
    public static final FlowDataType LIVING_ENTITY = new FlowDataType("living_entity", ENTITY, LivingEntity.class, FlowEntityRef.class, 0xA0522D);
    public static final FlowDataType PLAYER = new FlowDataType("player", LIVING_ENTITY, Player.class, FlowEntityRef.class, 0x0066FF);
    public static final FlowDataType MATERIAL = new FlowDataType("material", null, Material.class, null, 0x00AA00);
    public static final FlowDataType BLOCK = new FlowDataType("block", null, Block.class, FlowBlock.class, 0x228B22);
    public static final FlowDataType ITEM = new FlowDataType("item", MATERIAL, ItemStack.class, FlowItem.class, 0x32CD32);
    public static final FlowDataType WORLD = new FlowDataType("world", null, World.class, FlowWorldRef.class, 0x00CED1);
    public static final FlowDataType BIOME = new FlowDataType("biome", null, Biome.class, null, 0x20B2AA);
    public static final FlowDataType LOCATION = new FlowDataType("location", null, Location.class, null, 0xFFA500);
    public static final FlowDataType VECTOR = new FlowDataType("vector", null, Vector.class, null, 0x7FFFD4);
    public static final FlowDataType COLOR = new FlowDataType("color", null, String.class, null, 0xFF66CC);
    public static final FlowDataType UUID = new FlowDataType("uuid", null, UUID.class, null, 0x708090);
    public static final FlowDataType GAMEMODE = new FlowDataType("gamemode", null, GameMode.class, null, 0x4169E1);
    public static final FlowDataType DIFFICULTY = new FlowDataType("difficulty", null, Difficulty.class, null, 0xDC143C);
    public static final FlowDataType ENTITY_TYPE = new FlowDataType("entity_type", null, EntityType.class, null, 0xCD853F);
    public static final FlowDataType ENCHANTMENT = new FlowDataType("enchantment", null, Enchantment.class, FlowEnchantment.class, 0x9370DB);
    public static final FlowDataType ITEMSTACK = ITEM;
    public static final FlowDataType INVENTORY = new FlowDataType("inventory", null, Inventory.class, null, 0x4682B4);
    public static final FlowDataType POTION_EFFECT = new FlowDataType("potion_effect", null, PotionEffectType.class, null, 0xFF1493);
    public static final FlowDataType SOUND = new FlowDataType("sound", null, Sound.class, null, 0xFFB347);
    public static final FlowDataType ADVANCEMENT = new FlowDataType("advancement", null, Advancement.class, null, 0xFFD700);
    public static final FlowDataType PERMISSION_GROUP = new FlowDataType("permission_group", null, String.class, null, 0x6A5ACD);
    public static final FlowDataType SCOREBOARD = new FlowDataType("scoreboard", null, Scoreboard.class, null, 0x1E90FF);
    public static final FlowDataType TEAM = new FlowDataType("team", null, Team.class, null, 0x00BFFF);
    public static final FlowDataType REGION = new FlowDataType("region", null, String.class, null, 0x9ACD32);
    public static final FlowDataType COMPONENT = new FlowDataType("component", STRING, Component.class, null, 0xE066FF);
    public static final FlowDataType JSON_OBJECT = new FlowDataType("json_object", null, com.google.gson.JsonObject.class, null, 0x4B0082);
    public static final FlowDataType LIST = new FlowDataType("list", null, List.class, null, 0xFF69B4);
    public static final FlowDataType MAP = new FlowDataType("map", null, Map.class, null, 0x9932CC);
    public static final FlowDataType SET = new FlowDataType("set", null, Set.class, null, 0xFF4500);
    public static final FlowDataType QUEUE = new FlowDataType("queue", null, Queue.class, null, 0x2E8B57);
    public static final FlowDataType STACK = new FlowDataType("stack", null, Deque.class, null, 0x4682B4);

    private final String id;
    private final FlowDataType parent;
    private final Class<?> javaType;
    private final Class<? extends FlowDataObject> dataClass;
    private final int color;

    public FlowDataType(String id, FlowDataType parent, Class<?> javaType, Class<? extends FlowDataObject> dataClass, int color) {
        this.id = id;
        this.parent = parent;
        this.javaType = javaType;
        this.dataClass = dataClass;
        this.color = color;
    }

    public String getId() {
        return id;
    }

    public FlowDataType getParent() {
        return parent;
    }

    public Class<?> getJavaType() {
        return javaType;
    }

    public Class<? extends FlowDataObject> getDataClass() {
        return dataClass;
    }

    public int getColor() {
        return 0xFF000000 | color;
    }

    public boolean isAssignableFrom(FlowDataType other) {
        if (this == ANY || this == other) return true;
        if (other == null) return false;
        if (other.parent != null && (this == other.parent || isAssignableFrom(other.parent))) return true;
        return false;
    }

    private static final Map<String, FlowDataType> REGISTRY = new HashMap<>();

    static {
        for (Field field : FlowDataType.class.getDeclaredFields()) {
            if (field.getType() == FlowDataType.class && Modifier.isStatic(field.getModifiers())) {
                try {
                    FlowDataType type = (FlowDataType) field.get(null);
                    if (type != null) {
                        REGISTRY.put(type.id, type);
                    }
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        REGISTRY.put("itemstack", ITEM);
        REGISTRY.put("flow", EXECUTION);
    }

    public static FlowDataType fromString(String name) {
        if (name == null || name.isEmpty()) return ANY;
        FlowDataType type = REGISTRY.get(name.toLowerCase());
        return type != null ? type : ANY;
    }

    public static List<FlowDataType> values() {
        return List.copyOf(REGISTRY.values());
    }

    public boolean canStringify() {
        return this != EXECUTION && this != ANY;
    }
}
