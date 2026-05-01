package restudio.resync.modules.flow;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.block.Biome;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.potion.PotionEffectType;
import restudio.resync.customcontent.CustomContentService;
import restudio.resync.core.Session;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class FlowOptionCatalogPacketHandler {
    private final FlowPacketSender sender;
    private final CustomContentService customContentService;

    public FlowOptionCatalogPacketHandler(FlowPacketSender sender, CustomContentService customContentService) {
        this.sender = sender;
        this.customContentService = customContentService;
    }

    public void handle(Session session, ByteBuffer buffer) {
        if (buffer.remaining() < 4) {
            return;
        }
        int sourceLength = buffer.getInt();
        if (sourceLength < 0 || sourceLength > FlowPacketSender.MAX_STRING_LENGTH || sourceLength > buffer.remaining()) {
            return;
        }
        byte[] sourceBytes = new byte[sourceLength];
        buffer.get(sourceBytes);
        String sourceId = new String(sourceBytes, StandardCharsets.UTF_8);
        sender.sendOptionCatalog(session, sourceId, values(sourceId), revision(sourceId));
    }

    private List<String> values(String sourceId) {
        return switch (normalize(sourceId)) {
            case "advancement" -> advancements();
            case "biome" -> registryKeys(Registry.BIOME);
            case "difficulty" -> List.of("peaceful", "easy", "normal", "hard");
            case "enchantment" -> registryKeys(Registry.ENCHANTMENT);
            case "entity_type" -> enumNames(EntityType.values());
            case "gamemode" -> List.of("survival", "creative", "adventure", "spectator");
            case "material" -> enumNames(Material.values());
            case "block" -> blocks();
            case "particle" -> registryKeys(Registry.PARTICLE_TYPE);
            case "potion_effect" -> potionEffects();
            case "sound" -> registryKeys(Registry.SOUNDS);
            case "world" -> Bukkit.getWorlds().stream().map(World::getName).sorted(String.CASE_INSENSITIVE_ORDER).toList();
            case "custom_content_provider" -> customContentService != null ? customContentService.getAvailableProviderIds() : List.of("vanilla");
            case "custom_content_nexo_item" -> customContentService != null ? customContentService.getProviderOptionIds("nexo", "item") : List.of();
            case "custom_content_nexo_block" -> customContentService != null ? customContentService.getProviderOptionIds("nexo", "block") : List.of();
            case "custom_content_nexo_furniture" -> customContentService != null ? customContentService.getProviderOptionIds("nexo", "furniture") : List.of();
            case "custom_content_nexo_armor" -> customContentService != null ? customContentService.getProviderOptionIds("nexo", "armor") : List.of();
            default -> List.of();
        };
    }

    private String revision(String sourceId) {
        return normalize(sourceId) + ":" + Bukkit.getVersion();
    }

    private String normalize(String sourceId) {
        String value = sourceId != null ? sourceId.toLowerCase(Locale.ROOT) : "";
        if (value.startsWith("server:minecraft:")) {
            return value.substring("server:minecraft:".length());
        }
        if (value.startsWith("minecraft:")) {
            return value.substring("minecraft:".length());
        }
        if (value.startsWith("client:minecraft:")) {
            return value.substring("client:minecraft:".length());
        }
        if (value.startsWith("server:custom_content:")) {
            return "custom_content_" + value.substring("server:custom_content:".length());
        }
        return value;
    }

    private List<String> advancements() {
        List<String> values = new ArrayList<>();
        Bukkit.advancementIterator().forEachRemaining(advancement -> values.add(key(advancement)));
        values.sort(String.CASE_INSENSITIVE_ORDER);
        return values;
    }

    private String key(Advancement advancement) {
        NamespacedKey key = advancement.getKey();
        return key != null ? key.toString() : "";
    }

    private List<String> potionEffects() {
        List<String> values = new ArrayList<>();
        for (PotionEffectType type : PotionEffectType.values()) {
            if (type != null) {
                values.add(type.getKey().toString());
            }
        }
        values.sort(String.CASE_INSENSITIVE_ORDER);
        return values;
    }

    private List<String> blocks() {
        List<String> values = new ArrayList<>();
        for (Material material : Material.values()) {
            if (material.isBlock()) {
                values.add(material.name().toLowerCase(Locale.ROOT));
            }
        }
        values.sort(String.CASE_INSENSITIVE_ORDER);
        return values;
    }

    private <T extends org.bukkit.Keyed> List<String> registryKeys(Registry<T> registry) {
        List<String> values = new ArrayList<>();
        for (T value : registry) {
            values.add(value.getKey().toString());
        }
        values.sort(String.CASE_INSENSITIVE_ORDER);
        return values;
    }

    private <E extends Enum<E>> List<String> enumNames(E[] values) {
        List<String> result = new ArrayList<>();
        for (E value : values) {
            result.add(value.name().toLowerCase(Locale.ROOT));
        }
        result.sort(Comparator.naturalOrder());
        return result;
    }
}
