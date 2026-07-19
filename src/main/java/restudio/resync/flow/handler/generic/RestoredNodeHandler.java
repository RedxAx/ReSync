package restudio.resync.flow.handler.generic;

import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class RestoredNodeHandler implements NodeHandler {
    private static final Set<String> RESTORED_IDS = Set.of(
        "entity_mount",
        "entity_dismount",
        "entity_ai_disable",
        "entity_ai_enable",
        "entity_set_no_damage",
        "entity_set_silent",
        "entity_add_potion",
        "entity_clear_potions",
        "entity_leash",
        "entity_unleash",
        "entity_set_custom_name",
        "entity_get_passengers",
        "entity_get_vehicle",
        "entity_set_fire_ticks",
        "entity_set_frozen",
        "entity_add_tag",
        "entity_remove_tag",
        "entity_clear_tags",
        "entity_has_tag",
        "entity_has_any_tag",
        "entity_has_all_tags",
        "entity_get_tags",
        "player_count_item",
        "player_get_first_empty_slot",
        "player_get_all_items",
        "player_get_hotbar_items",
        "player_get_armor_items",
        "player_get_inventory_size",
        "player_get_mainhand_item",
        "player_get_offhand_item",
        "player_is_on_ground",
        "player_is_sleeping",
        "player_get_bed_location",
        "player_get_last_damage",
        "player_get_killer",
        "player_get_ping",
        "player_get_lore",
        "player_get_display_name",
        "player_get_player_list_name",
        "player_is_op",
        "player_get_allowed_flight"
    );

    public void registerTo(HandlerRegistry registry) {
        for (String id : RESTORED_IDS) {
            registry.register(id, this);
        }
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        switch (node.getType()) {
            case "entity_mount" -> entityMount(ctx, node);
            case "entity_dismount" -> entityDismount(ctx, node);
            case "entity_ai_disable" -> entityAi(ctx, node, false);
            case "entity_ai_enable" -> entityAi(ctx, node, true);
            case "entity_set_no_damage" -> entitySetInvulnerable(ctx, node);
            case "entity_set_silent" -> entitySetSilent(ctx, node);
            case "entity_add_potion" -> entityAddPotion(ctx, node);
            case "entity_clear_potions" -> entityClearPotions(ctx, node);
            case "entity_leash" -> entityLeash(ctx, node);
            case "entity_unleash" -> entityUnleash(ctx, node);
            case "entity_set_custom_name" -> entitySetCustomName(ctx, node);
            case "entity_get_passengers" -> entityGetPassengers(ctx, node);
            case "entity_get_vehicle" -> entityGetVehicle(ctx, node);
            case "entity_set_fire_ticks" -> entitySetFireTicks(ctx, node);
            case "entity_set_frozen" -> entitySetFrozen(ctx, node);
            case "entity_add_tag" -> entityAddTag(ctx, node);
            case "entity_remove_tag" -> entityRemoveTag(ctx, node);
            case "entity_clear_tags" -> entityClearTags(ctx, node);
            case "entity_has_tag" -> entityHasTag(ctx, node);
            case "entity_has_any_tag" -> entityHasAnyTag(ctx, node);
            case "entity_has_all_tags" -> entityHasAllTags(ctx, node);
            case "entity_get_tags" -> entityGetTags(ctx, node);
            case "player_count_item" -> playerCountItem(ctx, node);
            case "player_get_first_empty_slot" -> playerGetFirstEmptySlot(ctx, node);
            case "player_get_all_items" -> playerGetAllItems(ctx, node);
            case "player_get_hotbar_items" -> playerGetHotbarItems(ctx, node);
            case "player_get_armor_items" -> playerGetArmorItems(ctx, node);
            case "player_get_inventory_size" -> playerGetInventorySize(ctx, node);
            case "player_get_mainhand_item" -> playerGetMainhandItem(ctx, node);
            case "player_get_offhand_item" -> playerGetOffhandItem(ctx, node);
            case "player_is_on_ground" -> playerIsOnGround(ctx, node);
            case "player_is_sleeping" -> playerIsSleeping(ctx, node);
            case "player_get_bed_location" -> playerGetBedLocation(ctx, node);
            case "player_get_last_damage" -> playerGetLastDamage(ctx, node);
            case "player_get_killer" -> playerGetKiller(ctx, node);
            case "player_get_ping" -> playerGetPing(ctx, node);
            case "player_get_lore" -> playerGetLore(ctx, node);
            case "player_get_display_name" -> playerGetDisplayName(ctx, node);
            case "player_get_player_list_name" -> playerGetPlayerListName(ctx, node);
            case "player_is_op" -> playerIsOp(ctx, node);
            case "player_get_allowed_flight" -> playerGetAllowedFlight(ctx, node);
            default -> throw new IllegalArgumentException("Unknown restored node operation: " + node.getType());
        }
    }

    private void entityMount(FlowContext ctx, FlowNode node) {
        Entity entity = requireEntity(ctx, node, "entity");
        Entity mount = requireEntity(ctx, node, "mount_entity");
        if (entity.equals(mount)) throw new IllegalArgumentException("Entity cannot mount itself");
        if (!entity.teleport(mount.getLocation())) throw new IllegalStateException("Entity could not be moved to the mount");
        if (!mount.addPassenger(entity)) throw new IllegalStateException("Entity could not be mounted");
        ctx.triggerOutput("flow");
    }

    private void entityDismount(FlowContext ctx, FlowNode node) {
        Entity entity = requireEntity(ctx, node, "entity");
        if (!entity.isInsideVehicle()) throw new IllegalStateException("Entity is not mounted");
        if (!entity.leaveVehicle()) throw new IllegalStateException("Entity could not dismount");
        ctx.triggerOutput("flow");
    }

    private void entityAi(FlowContext ctx, FlowNode node, boolean enabled) {
        Entity entity = requireEntity(ctx, node, "entity");
        if (!(entity instanceof Mob mob)) throw new IllegalArgumentException("Entity does not support AI");
        mob.setAI(enabled);
        ctx.triggerOutput("flow");
    }

    private void entitySetInvulnerable(FlowContext ctx, FlowNode node) {
        Entity entity = requireEntity(ctx, node, "entity");
        Boolean noDamage = ctx.getInputValue(node, "no_damage", Boolean.class, false);
        entity.setInvulnerable(noDamage);
        ctx.triggerOutput("flow");
    }

    private void entitySetSilent(FlowContext ctx, FlowNode node) {
        Entity entity = requireEntity(ctx, node, "entity");
        Boolean silent = ctx.getInputValue(node, "silent", Boolean.class, false);
        entity.setSilent(silent);
        ctx.triggerOutput("flow");
    }

    private void entityAddPotion(FlowContext ctx, FlowNode node) {
        LivingEntity living = requireLivingEntity(ctx, node, "entity");
        String effectName = ctx.getInputValue(node, "effect_type", String.class, "SPEED");
        Integer duration = ctx.getInputValue(node, "duration", Integer.class, 200);
        Integer amplifier = ctx.getInputValue(node, "amplifier", Integer.class, 0);
        if (effectName == null || effectName.isBlank()) throw new IllegalArgumentException("Potion effect type is required");
        PotionEffectType effectType = PotionEffectType.getByName(effectName.toUpperCase());
        if (effectType == null) throw new IllegalArgumentException("Unknown potion effect type: " + effectName);
        if (duration < 1 || duration > 72_000) throw new IllegalArgumentException("Potion duration must be between 1 and 72000 ticks");
        if (amplifier < 0 || amplifier > 255) throw new IllegalArgumentException("Potion amplifier must be between 0 and 255");
        living.addPotionEffect(new PotionEffect(effectType, duration, amplifier));
        ctx.triggerOutput("flow");
    }

    private void entityClearPotions(FlowContext ctx, FlowNode node) {
        LivingEntity living = requireLivingEntity(ctx, node, "entity");
        List<PotionEffectType> effects = living.getActivePotionEffects().stream().map(PotionEffect::getType).toList();
        for (PotionEffectType effect : effects) {
            living.removePotionEffect(effect);
        }
        ctx.triggerOutput("flow");
    }

    private void entityLeash(FlowContext ctx, FlowNode node) {
        LivingEntity living = requireLivingEntity(ctx, node, "entity");
        Entity holder = requireEntity(ctx, node, "holder_entity");
        if (!living.setLeashHolder(holder)) throw new IllegalStateException("Entity could not be leashed to the holder");
        ctx.triggerOutput("flow");
    }

    private void entityUnleash(FlowContext ctx, FlowNode node) {
        LivingEntity living = requireLivingEntity(ctx, node, "entity");
        if (!living.isLeashed()) throw new IllegalStateException("Entity is not leashed");
        if (!living.setLeashHolder(null)) throw new IllegalStateException("Entity could not be unleashed");
        ctx.triggerOutput("flow");
    }

    private void entitySetCustomName(FlowContext ctx, FlowNode node) {
        Entity entity = requireEntity(ctx, node, "entity");
        String name = ctx.getInputValue(node, "name", String.class, "");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Entity custom name is required");
        entity.setCustomName(name);
        entity.setCustomNameVisible(true);
        ctx.triggerOutput("flow");
    }

    private void entityGetPassengers(FlowContext ctx, FlowNode node) {
        Entity entity = requireEntity(ctx, node, "entity");
        ctx.setOutput(node, "passengers_list", entity.getPassengers());
        ctx.triggerOutput("flow");
    }

    private void entityGetVehicle(FlowContext ctx, FlowNode node) {
        Entity entity = requireEntity(ctx, node, "entity");
        ctx.setOutput(node, "vehicle", entity.getVehicle());
        ctx.triggerOutput("flow");
    }

    private void entitySetFireTicks(FlowContext ctx, FlowNode node) {
        Entity entity = requireEntity(ctx, node, "entity");
        Integer ticks = ctx.getInputValue(node, "ticks", Integer.class, 0);
        if (ticks < 0 || ticks > 72_000) throw new IllegalArgumentException("Fire ticks must be between 0 and 72000");
        entity.setFireTicks(ticks);
        ctx.triggerOutput("flow");
    }

    private void entitySetFrozen(FlowContext ctx, FlowNode node) {
        Entity entity = requireEntity(ctx, node, "entity");
        Integer ticks = ctx.getInputValue(node, "ticks", Integer.class, 0);
        if (ticks < 0 || ticks > entity.getMaxFreezeTicks()) throw new IllegalArgumentException("Freeze ticks must be between 0 and " + entity.getMaxFreezeTicks());
        entity.setFreezeTicks(ticks);
        ctx.triggerOutput("flow");
    }

    private void entityAddTag(FlowContext ctx, FlowNode node) {
        Entity entity = requireEntity(ctx, node, "entity");
        String tag = ctx.getInputValue(node, "tag", String.class, "");
        if (tag == null || tag.isBlank()) throw new IllegalArgumentException("Entity tag is required");
        if (!entity.addScoreboardTag(tag)) throw new IllegalStateException("Entity tag could not be added");
        ctx.triggerOutput("flow");
    }

    private void entityRemoveTag(FlowContext ctx, FlowNode node) {
        Entity entity = requireEntity(ctx, node, "entity");
        String tag = ctx.getInputValue(node, "tag", String.class, "");
        if (tag == null || tag.isBlank()) throw new IllegalArgumentException("Entity tag is required");
        if (!entity.removeScoreboardTag(tag)) throw new IllegalStateException("Entity does not contain tag: " + tag);
        ctx.triggerOutput("flow");
    }

    private void entityClearTags(FlowContext ctx, FlowNode node) {
        Entity entity = requireEntity(ctx, node, "entity");
        for (String tag : new ArrayList<>(entity.getScoreboardTags())) {
            if (!entity.removeScoreboardTag(tag)) throw new IllegalStateException("Entity tag could not be removed: " + tag);
        }
        ctx.triggerOutput("flow");
    }

    private void entityHasTag(FlowContext ctx, FlowNode node) {
        Entity entity = requireEntity(ctx, node, "entity");
        String tag = ctx.getInputValue(node, "tag", String.class, "");
        if (tag == null || tag.isBlank()) throw new IllegalArgumentException("Entity tag is required");
        ctx.setOutput(node, "has_tag", entity.getScoreboardTags().contains(tag));
    }

    private void entityHasAnyTag(FlowContext ctx, FlowNode node) {
        Entity entity = requireEntity(ctx, node, "entity");
        List<?> tags = ctx.getInputValue(node, "tags", List.class, List.of());
        if (tags == null || tags.isEmpty()) throw new IllegalArgumentException("Entity tags are required");
        boolean hasAny = false;
        for (Object tag : tags) {
            if (!(tag instanceof String value) || value.isBlank()) throw new IllegalArgumentException("Entity tags must contain non-empty strings");
            if (entity.getScoreboardTags().contains(value)) {
                hasAny = true;
                break;
            }
        }
        ctx.setOutput(node, "has_any", hasAny);
    }

    private void entityHasAllTags(FlowContext ctx, FlowNode node) {
        Entity entity = requireEntity(ctx, node, "entity");
        List<?> tags = ctx.getInputValue(node, "tags", List.class, List.of());
        if (tags == null || tags.isEmpty()) throw new IllegalArgumentException("Entity tags are required");
        if (tags.stream().anyMatch(tag -> !(tag instanceof String value) || value.isBlank())) throw new IllegalArgumentException("Entity tags must contain non-empty strings");
        boolean hasAll = tags.stream().allMatch(tag -> entity.getScoreboardTags().contains((String) tag));
        ctx.setOutput(node, "has_all", hasAll);
    }

    private void entityGetTags(FlowContext ctx, FlowNode node) {
        Entity entity = requireEntity(ctx, node, "entity");
        ctx.setOutput(node, "tags", new ArrayList<>(entity.getScoreboardTags()));
    }

    private void playerCountItem(FlowContext ctx, FlowNode node) {
        Player player = requirePlayer(ctx, node);
        ItemStack target = requireMaterialOrItem(ctx, node);
        int count = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && item.isSimilar(target)) {
                count += item.getAmount();
            }
        }
        ctx.setOutput(node, "count", count);
    }

    private void playerGetFirstEmptySlot(FlowContext ctx, FlowNode node) {
        Player player = requirePlayer(ctx, node);
        ctx.setOutput(node, "slot_index", player.getInventory().firstEmpty());
    }

    private void playerGetAllItems(FlowContext ctx, FlowNode node) {
        Player player = requirePlayer(ctx, node);
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && item.getType() != Material.AIR) {
                items.add(item.clone());
            }
        }
        ctx.setOutput(node, "items_list", items);
    }

    private void playerGetHotbarItems(FlowContext ctx, FlowNode node) {
        Player player = requirePlayer(ctx, node);
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            ItemStack item = player.getInventory().getItem(i);
            items.add(item != null ? item.clone() : new ItemStack(Material.AIR));
        }
        ctx.setOutput(node, "items_list", items);
    }

    private void playerGetArmorItems(FlowContext ctx, FlowNode node) {
        Player player = requirePlayer(ctx, node);
        ItemStack[] armor = player.getInventory().getArmorContents();
        ctx.setOutput(node, "helmet", armor[3]);
        ctx.setOutput(node, "chestplate", armor[2]);
        ctx.setOutput(node, "leggings", armor[1]);
        ctx.setOutput(node, "boots", armor[0]);
    }

    private void playerGetInventorySize(FlowContext ctx, FlowNode node) {
        Player player = requirePlayer(ctx, node);
        ctx.setOutput(node, "size", player.getInventory().getSize());
    }

    private void playerGetMainhandItem(FlowContext ctx, FlowNode node) {
        Player player = requirePlayer(ctx, node);
        ctx.setOutput(node, "item", player.getInventory().getItemInMainHand());
    }

    private void playerGetOffhandItem(FlowContext ctx, FlowNode node) {
        Player player = requirePlayer(ctx, node);
        ctx.setOutput(node, "item", player.getInventory().getItemInOffHand());
    }

    private void playerIsOnGround(FlowContext ctx, FlowNode node) {
        Player player = requirePlayer(ctx, node);
        ctx.setOutput(node, "on_ground", player.isOnGround());
    }

    private void playerIsSleeping(FlowContext ctx, FlowNode node) {
        Player player = requirePlayer(ctx, node);
        ctx.setOutput(node, "is_sleeping", player.isSleeping());
    }

    private void playerGetBedLocation(FlowContext ctx, FlowNode node) {
        Player player = requirePlayer(ctx, node);
        ctx.setOutput(node, "bed_location", player.getBedSpawnLocation());
    }

    private void playerGetLastDamage(FlowContext ctx, FlowNode node) {
        Player player = requirePlayer(ctx, node);
        EntityDamageEvent lastDamage = player.getLastDamageCause();
        ctx.setOutput(node, "damage_cause", lastDamage != null ? lastDamage.getCause().name() : null);
        ctx.setOutput(node, "damage_source", lastDamage != null ? lastDamage.getEntity() : null);
    }

    private void playerGetKiller(FlowContext ctx, FlowNode node) {
        Player player = requirePlayer(ctx, node);
        ctx.setOutput(node, "killer", player.getKiller());
    }

    private void playerGetPing(FlowContext ctx, FlowNode node) {
        Player player = requirePlayer(ctx, node);
        ctx.setOutput(node, "ping_ms", player.getPing());
    }

    private void playerGetLore(FlowContext ctx, FlowNode node) {
        Player player = requirePlayer(ctx, node);
        String hand = ctx.getInputValue(node, "hand", String.class, "main");
        if (!"main".equalsIgnoreCase(hand) && !"off".equalsIgnoreCase(hand)) throw new IllegalArgumentException("Hand must be main or off");
        List<String> loreLines = new ArrayList<>();
        ItemStack item = "off".equalsIgnoreCase(hand) ? player.getInventory().getItemInOffHand() : player.getInventory().getItemInMainHand();
        if (item != null && item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            List<Component> lore = meta.lore();
            if (lore != null) {
                PlainTextComponentSerializer serializer = PlainTextComponentSerializer.plainText();
                for (Component line : lore) {
                    loreLines.add(serializer.serialize(line));
                }
            }
        }
        ctx.setOutput(node, "lore_lines_list", loreLines);
    }

    private void playerGetDisplayName(FlowContext ctx, FlowNode node) {
        Player player = requirePlayer(ctx, node);
        ctx.setOutput(node, "display_name", player.getDisplayName());
    }

    private void playerGetPlayerListName(FlowContext ctx, FlowNode node) {
        Player player = requirePlayer(ctx, node);
        ctx.setOutput(node, "list_name", player.getPlayerListName());
    }

    private void playerIsOp(FlowContext ctx, FlowNode node) {
        Player player = requirePlayer(ctx, node);
        ctx.setOutput(node, "is_op", player.isOp());
    }

    private void playerGetAllowedFlight(FlowContext ctx, FlowNode node) {
        Player player = requirePlayer(ctx, node);
        ctx.setOutput(node, "can_fly", player.getAllowFlight());
    }

    private ItemStack requireMaterialOrItem(FlowContext ctx, FlowNode node) {
        ItemStack item = ctx.getInputValue(node, "material_or_item", ItemStack.class, null);
        if (item != null && !item.getType().isAir()) {
            return item;
        }
        String materialName = ctx.getInputValue(node, "material", String.class, "");
        Material material = Material.matchMaterial(materialName);
        if (material == null || material.isAir()) throw new IllegalArgumentException("Material or item is required");
        return new ItemStack(material);
    }

    private Entity requireEntity(FlowContext context, FlowNode node, String inputName) {
        Entity entity = context.getInputValue(node, inputName, Entity.class, null);
        if (entity == null) throw new IllegalArgumentException("Entity input is required: " + inputName);
        if (!entity.isValid()) throw new IllegalArgumentException("Entity is no longer valid: " + inputName);
        return entity;
    }

    private LivingEntity requireLivingEntity(FlowContext context, FlowNode node, String inputName) {
        Entity entity = requireEntity(context, node, inputName);
        if (!(entity instanceof LivingEntity living)) throw new IllegalArgumentException("Entity must be living: " + inputName);
        return living;
    }

    private Player requirePlayer(FlowContext context, FlowNode node) {
        Player player = context.getInputValue(node, "player", Player.class, context.getPlayer());
        if (player == null) throw new IllegalArgumentException("Player is required");
        if (!player.isOnline()) throw new IllegalArgumentException("Player is offline");
        return player;
    }
}
