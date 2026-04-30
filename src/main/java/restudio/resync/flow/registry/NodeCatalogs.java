package restudio.resync.flow.registry;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.Particle;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class NodeCatalogs {

    private NodeCatalogs() {
    }

    public static List<String> resolve(String source) {
        if (source == null || source.isBlank()) {
            return List.of();
        }
        return switch (source.trim().toLowerCase(Locale.ROOT)) {
            case "minecraft:gamemode", "minecraft:gamemodes" -> fromGameModes();
            case "minecraft:material", "minecraft:materials" -> fromMaterials();
            case "minecraft:potion_effect", "minecraft:potion_effects" -> fromPotionEffects();
            case "minecraft:difficulty", "minecraft:difficulties" -> fromDifficulties();
            case "minecraft:biome", "minecraft:biomes" -> fromBiomes();
            case "minecraft:entity_type", "minecraft:entity_types" -> fromEntityTypes();
            case "minecraft:enchantment", "minecraft:enchantments" -> fromEnchantments();
            case "minecraft:particle", "minecraft:particles" -> fromParticles();
            case "minecraft:advancement", "minecraft:advancements" -> fromAdvancements();
            case "minecraft:gamerule", "minecraft:gamerules" -> fromGameRules();
            case "variable:mode", "flow:variable_mode" -> List.of("get", "set", "delete", "exists", "list", "increment", "decrement", "multiply", "divide");
            case "variable:scope", "flow:variable_scope" -> List.of("local", "global", "player");
            default -> List.of();
        };
    }

    private static List<String> fromGameModes() {
        List<String> values = new ArrayList<>();
        for (GameMode gameMode : GameMode.values()) {
            values.add(gameMode.name().toLowerCase(Locale.ROOT));
        }
        values.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(values);
    }

    private static List<String> fromMaterials() {
        List<String> values = new ArrayList<>();
        for (Material material : Material.values()) {
            if (material.isLegacy()) {
                continue;
            }
            values.add(material.name().toLowerCase(Locale.ROOT));
        }
        values.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(values);
    }

    private static List<String> fromPotionEffects() {
        List<String> values = new ArrayList<>();
        for (PotionEffectType type : PotionEffectType.values()) {
            if (type == null || type.getName() == null || type.getName().isBlank()) {
                continue;
            }
            values.add(type.getName().toLowerCase(Locale.ROOT));
        }
        values.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(values);
    }

    private static List<String> fromDifficulties() {
        List<String> values = new ArrayList<>();
        for (Difficulty difficulty : Difficulty.values()) {
            values.add(difficulty.name().toLowerCase(Locale.ROOT));
        }
        values.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(values);
    }

    private static List<String> fromBiomes() {
        List<String> values = new ArrayList<>();
        for (Biome biome : Biome.values()) {
            values.add(biome.name().toLowerCase(Locale.ROOT));
        }
        values.sort(Comparator.naturalOrder());
        return List.copyOf(values);
    }

    private static List<String> fromEntityTypes() {
        List<String> values = new ArrayList<>();
        for (EntityType type : EntityType.values()) {
            if (type == null || type.name() == null || type.name().isBlank()) {
                continue;
            }
            values.add(type.name().toLowerCase(Locale.ROOT));
        }
        values.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(values);
    }

    private static List<String> fromEnchantments() {
        List<String> values = new ArrayList<>();
        for (Enchantment enchantment : Enchantment.values()) {
            if (enchantment == null || enchantment.getKey() == null) {
                continue;
            }
            values.add(enchantment.getKey().getKey().toLowerCase(Locale.ROOT));
        }
        values.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(values);
    }

    private static List<String> fromParticles() {
        List<String> values = new ArrayList<>();
        for (Particle particle : Particle.values()) {
            if (particle == null || particle.name() == null || particle.name().isBlank()) {
                continue;
            }
            values.add(particle.name().toLowerCase(Locale.ROOT));
        }
        values.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(values);
    }

    private static List<String> fromGameRules() {
        List<String> values = new ArrayList<>();
        for (org.bukkit.GameRule<?> rule : org.bukkit.GameRule.values()) {
            if (rule == null || rule.getName() == null || rule.getName().isBlank()) {
                continue;
            }
            values.add(rule.getName().toLowerCase(Locale.ROOT));
        }
        values.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(values);
    }

    private static List<String> fromAdvancements() {
        List<String> values = new ArrayList<>();
        var iterator = Bukkit.advancementIterator();
        while (iterator.hasNext()) {
            org.bukkit.advancement.Advancement advancement = iterator.next();
            if (advancement == null || advancement.getKey() == null) {
                continue;
            }
            values.add(advancement.getKey().getKey().toLowerCase(Locale.ROOT));
        }
        values.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(values);
    }
}
