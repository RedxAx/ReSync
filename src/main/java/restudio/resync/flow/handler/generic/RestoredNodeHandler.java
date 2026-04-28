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
import restudio.resync.Log;
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
            default -> ctx.triggerOutput("flow");
        }
    }

    private void entityMount(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        Entity mount = ctx.getInputValue(node, "mount_entity", Entity.class, null);
        if (entity != null && mount != null) {
            entity.teleport(mount.getLocation());
            mount.addPassenger(entity);
        }
        ctx.triggerOutput("flow");
    }

    private void entityDismount(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        if (entity != null && entity.isInsideVehicle()) {
            entity.leaveVehicle();
        }
        ctx.triggerOutput("flow");
    }

    private void entityAi(FlowContext ctx, FlowNode node, boolean enabled) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        if (entity instanceof Mob mob) {
            mob.setAI(enabled);
        }
        ctx.triggerOutput("flow");
    }

    private void entitySetInvulnerable(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        Boolean noDamage = ctx.getInputValue(node, "no_damage", Boolean.class, false);
        if (entity != null) {
            entity.setInvulnerable(noDamage);
        }
        ctx.triggerOutput("flow");
    }

    private void entitySetSilent(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        Boolean silent = ctx.getInputValue(node, "silent", Boolean.class, false);
        if (entity != null) {
            entity.setSilent(silent);
        }
        ctx.triggerOutput("flow");
    }

    private void entityAddPotion(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        String effectName = ctx.getInputValue(node, "effect_type", String.class, "SPEED");
        Integer duration = ctx.getInputValue(node, "duration", Integer.class, 200);
        Integer amplifier = ctx.getInputValue(node, "amplifier", Integer.class, 0);
        if (entity instanceof LivingEntity living && effectName != null) {
            PotionEffectType effectType = PotionEffectType.getByName(effectName.toUpperCase());
            if (effectType != null) {
                living.addPotionEffect(new PotionEffect(effectType, duration, amplifier));
            } else {
                Log.warn("[Flow] Invalid potion effect type: " + effectName);
            }
        }
        ctx.triggerOutput("flow");
    }

    private void entityClearPotions(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        if (entity instanceof LivingEntity living) {
            List<PotionEffectType> effects = living.getActivePotionEffects().stream().map(PotionEffect::getType).toList();
            for (PotionEffectType effect : effects) {
                living.removePotionEffect(effect);
            }
        }
        ctx.triggerOutput("flow");
    }

    private void entityLeash(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        Entity holder = ctx.getInputValue(node, "holder_entity", Entity.class, null);
        if (entity instanceof LivingEntity living && holder instanceof LivingEntity leashHolder) {
            living.setLeashHolder(leashHolder);
        }
        ctx.triggerOutput("flow");
    }

    private void entityUnleash(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        if (entity instanceof LivingEntity living) {
            living.setLeashHolder(null);
        }
        ctx.triggerOutput("flow");
    }

    private void entitySetCustomName(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        String name = ctx.getInputValue(node, "name", String.class, "");
        if (entity != null) {
            entity.setCustomName(name);
            entity.setCustomNameVisible(true);
        }
        ctx.triggerOutput("flow");
    }

    private void entityGetPassengers(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        ctx.setOutput(node, "passengers_list", entity != null ? entity.getPassengers() : List.of());
        ctx.triggerOutput("flow");
    }

    private void entityGetVehicle(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        ctx.setOutput(node, "vehicle", entity != null ? entity.getVehicle() : null);
        ctx.triggerOutput("flow");
    }

    private void entitySetFireTicks(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        Integer ticks = ctx.getInputValue(node, "ticks", Integer.class, 0);
        if (entity != null) {
            entity.setFireTicks(ticks);
        }
        ctx.triggerOutput("flow");
    }

    private void entitySetFrozen(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        Integer ticks = ctx.getInputValue(node, "ticks", Integer.class, 0);
        if (entity != null) {
            entity.setFreezeTicks(ticks);
        }
        ctx.triggerOutput("flow");
    }

    private void entityAddTag(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        String tag = ctx.getInputValue(node, "tag", String.class, "");
        if (entity != null && !tag.isEmpty()) {
            entity.addScoreboardTag(tag);
        }
        ctx.triggerOutput("flow");
    }

    private void entityRemoveTag(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        String tag = ctx.getInputValue(node, "tag", String.class, "");
        if (entity != null && !tag.isEmpty()) {
            entity.removeScoreboardTag(tag);
        }
        ctx.triggerOutput("flow");
    }

    private void entityClearTags(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        if (entity != null) {
            for (String tag : new ArrayList<>(entity.getScoreboardTags())) {
                entity.removeScoreboardTag(tag);
            }
        }
        ctx.triggerOutput("flow");
    }

    private void entityHasTag(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        String tag = ctx.getInputValue(node, "tag", String.class, "");
        ctx.setOutput(node, "has_tag", entity != null && !tag.isEmpty() && entity.getScoreboardTags().contains(tag));
    }

    private void entityHasAnyTag(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        List<?> tags = ctx.getInputValue(node, "tags", List.class, List.of());
        boolean hasAny = false;
        if (entity != null && tags != null) {
            for (Object tag : tags) {
                if (tag instanceof String value && entity.getScoreboardTags().contains(value)) {
                    hasAny = true;
                    break;
                }
            }
        }
        ctx.setOutput(node, "has_any", hasAny);
    }

    private void entityHasAllTags(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        List<?> tags = ctx.getInputValue(node, "tags", List.class, List.of());
        boolean hasAll = entity != null && tags != null && !tags.isEmpty() && tags.stream().allMatch(tag -> tag instanceof String value && entity.getScoreboardTags().contains(value));
        ctx.setOutput(node, "has_all", hasAll);
    }

    private void entityGetTags(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        ctx.setOutput(node, "tags", entity != null ? new ArrayList<>(entity.getScoreboardTags()) : List.of());
    }

    private void playerCountItem(FlowContext ctx, FlowNode node) {
        Player player = ctx.getInputValue(node, "player", Player.class, null);
        ItemStack target = getMaterialOrItem(ctx, node);
        int count = 0;
        if (player != null && target != null) {
            for (ItemStack item : player.getInventory().getStorageContents()) {
                if (item != null && item.isSimilar(target)) {
                    count += item.getAmount();
                }
            }
        }
        ctx.setOutput(node, "count", count);
    }

    private void playerGetFirstEmptySlot(FlowContext ctx, FlowNode node) {
        Player player = ctx.getInputValue(node, "player", Player.class, null);
        ctx.setOutput(node, "slot_index", player != null ? player.getInventory().firstEmpty() : -1);
    }

    private void playerGetAllItems(FlowContext ctx, FlowNode node) {
        Player player = ctx.getInputValue(node, "player", Player.class, null);
        List<ItemStack> items = new ArrayList<>();
        if (player != null) {
            for (ItemStack item : player.getInventory().getStorageContents()) {
                if (item != null && item.getType() != Material.AIR) {
                    items.add(item);
                }
            }
        }
        ctx.setOutput(node, "items_list", items);
    }

    private void playerGetHotbarItems(FlowContext ctx, FlowNode node) {
        Player player = ctx.getInputValue(node, "player", Player.class, null);
        List<ItemStack> items = new ArrayList<>();
        if (player != null) {
            for (int i = 0; i < 9; i++) {
                ItemStack item = player.getInventory().getItem(i);
                items.add(item != null ? item : new ItemStack(Material.AIR));
            }
        }
        ctx.setOutput(node, "items_list", items);
    }

    private void playerGetArmorItems(FlowContext ctx, FlowNode node) {
        Player player = ctx.getInputValue(node, "player", Player.class, null);
        ItemStack[] armor = player != null ? player.getInventory().getArmorContents() : new ItemStack[4];
        ctx.setOutput(node, "helmet", armor[3]);
        ctx.setOutput(node, "chestplate", armor[2]);
        ctx.setOutput(node, "leggings", armor[1]);
        ctx.setOutput(node, "boots", armor[0]);
    }

    private void playerGetInventorySize(FlowContext ctx, FlowNode node) {
        Player player = ctx.getInputValue(node, "player", Player.class, null);
        ctx.setOutput(node, "size", player != null ? player.getInventory().getSize() : 0);
    }

    private void playerGetMainhandItem(FlowContext ctx, FlowNode node) {
        Player player = ctx.getInputValue(node, "player", Player.class, null);
        ctx.setOutput(node, "item", player != null ? player.getInventory().getItemInMainHand() : null);
    }

    private void playerGetOffhandItem(FlowContext ctx, FlowNode node) {
        Player player = ctx.getInputValue(node, "player", Player.class, null);
        ctx.setOutput(node, "item", player != null ? player.getInventory().getItemInOffHand() : null);
    }

    private void playerIsOnGround(FlowContext ctx, FlowNode node) {
        Player player = ctx.getInputValue(node, "player", Player.class, null);
        ctx.setOutput(node, "on_ground", player != null && player.isOnGround());
    }

    private void playerIsSleeping(FlowContext ctx, FlowNode node) {
        Player player = ctx.getInputValue(node, "player", Player.class, null);
        ctx.setOutput(node, "is_sleeping", player != null && player.isSleeping());
    }

    private void playerGetBedLocation(FlowContext ctx, FlowNode node) {
        Player player = ctx.getInputValue(node, "player", Player.class, null);
        ctx.setOutput(node, "bed_location", player != null ? player.getBedSpawnLocation() : null);
    }

    private void playerGetLastDamage(FlowContext ctx, FlowNode node) {
        Player player = ctx.getInputValue(node, "player", Player.class, null);
        EntityDamageEvent lastDamage = player != null ? player.getLastDamageCause() : null;
        ctx.setOutput(node, "damage_cause", lastDamage != null ? lastDamage.getCause().name() : null);
        ctx.setOutput(node, "damage_source", lastDamage != null ? lastDamage.getEntity() : null);
    }

    private void playerGetKiller(FlowContext ctx, FlowNode node) {
        Player player = ctx.getInputValue(node, "player", Player.class, null);
        ctx.setOutput(node, "killer", player != null ? player.getKiller() : null);
    }

    private void playerGetPing(FlowContext ctx, FlowNode node) {
        Player player = ctx.getInputValue(node, "player", Player.class, null);
        ctx.setOutput(node, "ping_ms", player != null ? player.getPing() : 0);
    }

    private void playerGetLore(FlowContext ctx, FlowNode node) {
        Player player = ctx.getInputValue(node, "player", Player.class, null);
        String hand = ctx.getInputValue(node, "hand", String.class, "main");
        List<String> loreLines = new ArrayList<>();
        if (player != null) {
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
        }
        ctx.setOutput(node, "lore_lines_list", loreLines);
    }

    private void playerGetDisplayName(FlowContext ctx, FlowNode node) {
        Player player = ctx.getInputValue(node, "player", Player.class, null);
        ctx.setOutput(node, "display_name", player != null ? player.getDisplayName() : null);
    }

    private void playerGetPlayerListName(FlowContext ctx, FlowNode node) {
        Player player = ctx.getInputValue(node, "player", Player.class, null);
        ctx.setOutput(node, "list_name", player != null ? player.getPlayerListName() : null);
    }

    private void playerIsOp(FlowContext ctx, FlowNode node) {
        Player player = ctx.getInputValue(node, "player", Player.class, null);
        ctx.setOutput(node, "is_op", player != null && player.isOp());
    }

    private void playerGetAllowedFlight(FlowContext ctx, FlowNode node) {
        Player player = ctx.getInputValue(node, "player", Player.class, null);
        ctx.setOutput(node, "can_fly", player != null && player.getAllowFlight());
    }

    private ItemStack getMaterialOrItem(FlowContext ctx, FlowNode node) {
        ItemStack item = ctx.getInputValue(node, "material_or_item", ItemStack.class, null);
        if (item != null) {
            return item;
        }
        String materialName = ctx.getInputValue(node, "material", String.class, "");
        Material material = Material.matchMaterial(materialName);
        return material != null ? new ItemStack(material) : null;
    }
}
