package restudio.resync.modules;

import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import io.papermc.paper.event.player.PlayerTradeEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.Event;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerItemMendEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRecipeDiscoverEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import restudio.resync.advancement.AdvancementRuntimeBridge;
import restudio.resync.advancement.AdvancementService;
import restudio.resync.advancement.AdvancementFingerprintReconciler;
import restudio.resync.advancement.AdvancementPredicateEvaluator;
import restudio.resync.advancement.AdvancementTreeValidator;
import restudio.resync.advancement.AdvancementTriggerDescriptors;
import restudio.resync.advancement.PaperAdvancementRuntimeBridge;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.customcontent.CustomContentService;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.FlowPredicateSupport;
import restudio.resync.flow.FlowStorage;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.flow.data.FlowGraph;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

public class AdvancementModule implements Module, Listener, ReSyncJsonResourceStorage.ResourceMutationInterceptor {
    private static final ModuleMetadata METADATA = ModuleMetadata.of("advancements", "Advancements").withDependencies("flow");
    private final AdvancementTreeValidator validator = new AdvancementTreeValidator();
    private AdvancementRuntimeBridge bridge;
    private final AdvancementService service = new AdvancementService();
    private AdvancementPredicateEvaluator predicates;
    private ReSyncJsonResourceStorage storage;
    private AdvancementFingerprintReconciler fingerprints;
    private FlowStorage flowStorage;
    private FlowExecutor flowExecutor;
    private BukkitTask pollingTask;
    private JavaPlugin plugin;

    @Override
    public ModuleMetadata getMetadata() {
        return METADATA;
    }

    @Override
    public void initialize(ModuleContext context) {
        plugin = context.getPlugin();
        storage = context.getRequiredService(ReSyncJsonResourceStorage.class);
        fingerprints = new AdvancementFingerprintReconciler(context.getPlugin(), service);
        flowStorage = context.getService(FlowStorage.class);
        flowExecutor = context.getService(FlowExecutor.class);
        CustomContentService customContent = context.getService(CustomContentService.class);
        predicates = new AdvancementPredicateEvaluator(customContent);
        bridge = new PaperAdvancementRuntimeBridge(customContent);
        storage.addInterceptor(this);
        context.registerService(AdvancementModule.class, this);
        context.registerService(AdvancementService.class, service);
    }

    @Override
    public void start(ModuleContext context) {
        Bukkit.getPluginManager().registerEvents(this, context.getPlugin());
        reloadAdvancements();
        Bukkit.getScheduler().runTaskLater(context.getPlugin(), this::reloadAdvancements, 40L);
        pollingTask = Bukkit.getScheduler().runTaskTimer(context.getPlugin(), () -> Bukkit.getOnlinePlayers().forEach(player -> {
            dispatch(player, "held_item", null, Map.of("event.item", player.getInventory().getItemInMainHand()));
            dispatch(player, "permission", null, Map.of());
            dispatch(player, "in_biome", null, Map.of("event.biome", player.getLocation().getBlock().getBiome().getKey().toString()));
        }), 20, 20);
    }

    @Override
    public void stop(ModuleContext context) {
        HandlerList.unregisterAll(this);
        if (bridge.supported()) {
            bridge.replace(Map.of());
        }
        if (pollingTask != null) {
            pollingTask.cancel();
            pollingTask = null;
        }
    }

    @Override
    public void beforeSave(String type, JsonObject value) {
        if (!ReSyncResourceCatalog.ADVANCEMENT_TREE.equals(type)) {
            return;
        }
        requireSupported();
        Map<String, JsonObject> trees = trees(id(value), value);
        validator.validate(trees);
        replaceSynchronously(trees);
    }

    @Override
    public void beforeDelete(String type, String id) {
        if (!ReSyncResourceCatalog.ADVANCEMENT_TREE.equals(type)) {
            return;
        }
        requireSupported();
        Map<String, JsonObject> trees = trees(id, null);
        validator.validate(trees);
        replaceSynchronously(trees);
    }

    @Override
    public void afterSaveFailure(String type, JsonObject value, RuntimeException failure) {
        rollback(type);
    }

    @Override
    public void afterDeleteFailure(String type, String id, RuntimeException failure) {
        rollback(type);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        sync(event.getPlayer(), trees(null, null));
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (plugin == null || !bridge.supported()) {
            return;
        }
        if ("Nexo".equalsIgnoreCase(event.getPlugin().getName())) {
            Bukkit.getScheduler().runTask(plugin, this::reloadAdvancements);
        }
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            dispatch(player, "obtain_item", event, Map.of("event.item", event.getItem().getItemStack()));
        }
    }

    @EventHandler
    public void onConsume(PlayerItemConsumeEvent event) {
        dispatch(event.getPlayer(), "consume_item", event, Map.of("event.item", event.getItem()));
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        dispatch(event.getPlayer(), "place_block", event, Map.of("event.block", event.getBlockPlaced()));
        dispatch(event.getPlayer(), "place_furniture", event, Map.of("event.block", event.getBlockPlaced(), "event.item", event.getItemInHand()));
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        dispatch(event.getPlayer(), "break_block", event, Map.of("event.block", event.getBlock()));
        dispatch(event.getPlayer(), "break_furniture", event, Map.of("event.block", event.getBlock()));
        if (event.getBlock().getType() == Material.BEE_NEST || event.getBlock().getType() == Material.BEEHIVE) {
            dispatch(event.getPlayer(), "bee_nest_destroyed", event, Map.of("event.block", event.getBlock()));
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() != null) {
            dispatch(event.getPlayer(), "item_used_on_block", event, Map.of("event.block", event.getClickedBlock(), "event.item", event.getItem() != null ? event.getItem() : new ItemStack(Material.AIR)));
            dispatch(event.getPlayer(), "interact_furniture", event, Map.of("event.block", event.getClickedBlock(), "event.item", event.getItem() != null ? event.getItem() : new ItemStack(Material.AIR)));
        }
        if (event.getItem() != null) {
            dispatch(event.getPlayer(), "using_item", event, Map.of("event.item", event.getItem()));
            if (event.getItem().getType() == Material.ENDER_EYE) {
                dispatch(event.getPlayer(), "used_ender_eye", event, Map.of("event.item", event.getItem()));
            }
        }
    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        dispatch(event.getPlayer(), "changed_dimension", event, Map.of("event.from", event.getFrom().getName(), "event.to", event.getPlayer().getWorld().getName()));
    }

    @EventHandler
    public void onSleep(PlayerBedEnterEvent event) {
        dispatch(event.getPlayer(), "slept_in_bed", event, Map.of("event.block", event.getBed()));
    }

    @EventHandler
    public void onHeldItem(PlayerItemHeldEvent event) {
        dispatch(event.getPlayer(), "held_item", event, Map.of("event.slot", event.getNewSlot()));
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            dispatch(player, "player_hurt_entity", event, Map.of("event.entity", event.getEntity(), "event.damage", event.getFinalDamage()));
        }
        if (event.getEntity() instanceof Player player) {
            dispatch(player, "entity_hurt_player", event, Map.of("event.entity", event.getDamager(), "event.damage", event.getFinalDamage()));
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        dispatch(player, "entity_killed_player", event, Map.of("event.entity", player.getKiller() != null ? player.getKiller() : player));
        if (player.getKiller() != null) {
            dispatchKill(player.getKiller(), player, event);
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (event instanceof PlayerDeathEvent || event.getEntity().getKiller() == null) {
            return;
        }
        dispatchKill(event.getEntity().getKiller(), event.getEntity(), event);
    }

    @EventHandler
    public void onCraft(CraftItemEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            Map<String, Object> vars = event.getRecipe() instanceof Keyed keyed ? Map.of("event.item", event.getRecipe().getResult(), "event.recipe", keyed.getKey().toString()) : Map.of("event.item", event.getRecipe().getResult());
            dispatch(player, "craft_recipe", event, vars);
            dispatch(player, "recipe_crafted", event, vars);
        }
    }

    @EventHandler
    public void onRecipeDiscover(PlayerRecipeDiscoverEvent event) {
        dispatch(event.getPlayer(), "recipe_unlocked", event, Map.of("event.recipe", event.getRecipe().toString()));
    }

    @EventHandler
    public void onEnchant(EnchantItemEvent event) {
        dispatch(event.getEnchanter(), "enchanted_item", event, Map.of("event.item", event.getItem(), "event.level", event.getExpLevelCost()));
    }

    @EventHandler
    public void onShoot(EntityShootBowEvent event) {
        if (event.getEntity() instanceof Player player) {
            Map<String, Object> vars = Map.of("event.item", event.getBow(), "event.projectile", event.getProjectile());
            dispatch(player, event.getBow().getType() == Material.CROSSBOW ? "shot_crossbow" : "shoot_bow", event, vars);
        }
    }

    @EventHandler
    public void onFish(PlayerFishEvent event) {
        dispatch(event.getPlayer(), "fishing_rod_hooked", event, Map.of("event.hook", event.getHook(), "event.state", event.getState().name()));
    }

    @EventHandler
    public void onBucketFill(PlayerBucketFillEvent event) {
        dispatch(event.getPlayer(), "filled_bucket", event, Map.of("event.block", event.getBlock(), "event.item", event.getItemStack()));
    }

    @EventHandler
    public void onItemDamage(PlayerItemDamageEvent event) {
        dispatch(event.getPlayer(), "item_durability_changed", event, Map.of("event.item", event.getItem(), "event.damage", event.getDamage()));
    }

    @EventHandler
    public void onItemMend(PlayerItemMendEvent event) {
        dispatch(event.getPlayer(), "item_durability_changed", event, Map.of("event.item", event.getItem(), "event.repair", event.getRepairAmount()));
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        ItemStack item = event.getPlayer().getInventory().getItem(event.getHand());
        dispatch(event.getPlayer(), "player_interacted_with_entity", event, Map.of("event.entity", event.getRightClicked(), "event.item", item != null ? item : new ItemStack(Material.AIR)));
    }

    @EventHandler
    public void onShear(PlayerShearEntityEvent event) {
        dispatch(event.getPlayer(), "player_sheared_equipment", event, Map.of("event.entity", event.getEntity(), "event.item", event.getItem()));
    }

    @EventHandler
    public void onMount(EntityMountEvent event) {
        if (event.getEntity() instanceof Player player) {
            dispatch(player, "started_riding", event, Map.of("event.entity", event.getMount()));
        }
    }

    @EventHandler
    public void onPotionEffect(EntityPotionEffectEvent event) {
        if (event.getEntity() instanceof Player player) {
            dispatch(player, "effects_changed", event, Map.of("event.effect", event.getModifiedType().getKey().toString(), "event.action", event.getAction().name()));
        }
    }

    @EventHandler
    public void onResurrect(EntityResurrectEvent event) {
        if (event.getEntity() instanceof Player player) {
            dispatch(player, "used_totem", event, Map.of("event.item", new ItemStack(Material.TOTEM_OF_UNDYING)));
        }
    }

    @EventHandler
    public void onFall(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            dispatch(player, "fall_from_height", event, Map.of("event.damage", event.getFinalDamage(), "event.distance", player.getFallDistance()));
        }
    }

    @EventHandler
    public void onTame(EntityTameEvent event) {
        if (event.getOwner() instanceof Player player) {
            dispatch(player, "tame_animal", event, Map.of("event.entity", event.getEntity()));
        }
    }

    @EventHandler
    public void onBreed(EntityBreedEvent event) {
        if (event.getBreeder() instanceof Player player) {
            dispatch(player, "bred_animals", event, Map.of("event.entity", event.getEntity(), "event.parent", event.getMother()));
        }
    }

    @EventHandler
    public void onTrade(PlayerTradeEvent event) {
        dispatch(event.getPlayer(), "villager_trade", event, Map.of("event.entity", event.getVillager(), "event.item", event.getTrade().getResult()));
    }

    public Map<String, Object> capabilityPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("supported", bridge.supported());
        payload.put("unsupportedReason", bridge.unsupportedReason());
        payload.put("triggers", AdvancementTriggerDescriptors.sorted());
        return payload;
    }

    private void dispatchKill(Player killer, Entity killed, EntityDeathEvent event) {
        Map<String, Object> vars = Map.of("event.entity", killed, "event.item", killer.getInventory().getItemInMainHand());
        dispatch(killer, "player_killed_entity", event, vars);
        dispatch(killer, "kill_entity_with_item", event, vars);
        if (killed.getLastDamageCause() instanceof EntityDamageByEntityEvent damage && damage.getDamager() instanceof AbstractArrow arrow && arrow.getShooter() == killer) {
            dispatch(killer, "killed_by_arrow", event, vars);
        }
    }

    public void dispatch(Player player, String trigger, Event event, Map<String, Object> inputs) {
        if (player == null || !AdvancementTriggerDescriptors.IDS.contains(trigger)) {
            return;
        }
        for (Map.Entry<String, JsonObject> treeEntry : trees(null, null).entrySet()) {
            JsonObject nodes = treeEntry.getValue().getAsJsonObject("nodes");
            for (Map.Entry<String, JsonElement> nodeEntry : nodes.entrySet()) {
                JsonObject node = nodeEntry.getValue().getAsJsonObject();
                if (!bool(node, "enabled", true)) {
                    continue;
                }
                JsonObject criteria = object(node, "criteria");
                for (Map.Entry<String, JsonElement> criterionEntry : criteria.entrySet()) {
                    JsonObject criterion = criterionEntry.getValue().getAsJsonObject();
                    if (!trigger.equals(text(criterion, "trigger")) || !predicates.matches(player, object(criterion, "conditions"), inputs)) {
                        continue;
                    }
                    Map<String, Object> vars = eventVars(player, treeEntry.getKey(), nodeEntry.getKey(), criterionEntry.getKey(), trigger, inputs);
                    if (!FlowPredicateSupport.evaluate(flowStorage, flowExecutor, text(criterion, "predicateFlowId"), player, event, vars)) {
                        continue;
                    }
                    boolean completed = service.complete(player, treeEntry.getKey(), nodeEntry.getKey());
                    if (service.grant(player, treeEntry.getKey(), nodeEntry.getKey(), criterionEntry.getKey()) && !completed && service.complete(player, treeEntry.getKey(), nodeEntry.getKey())) {
                        complete(node, player, event, vars);
                    }
                }
            }
        }
    }

    private void complete(JsonObject node, Player player, Event event, Map<String, Object> vars) {
        JsonObject onComplete = object(node, "onComplete");
        if (onComplete.has("commands") && onComplete.get("commands").isJsonArray()) {
            onComplete.getAsJsonArray("commands").forEach(command -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.getAsString().replace("{player}", player.getName())));
        }
        String sound = text(onComplete, "sound");
        if (!sound.isBlank()) {
            player.playSound(player.getLocation(), sound, 1, 1);
        }
        String title = text(onComplete, "title");
        String subtitle = text(onComplete, "subtitle");
        if (!title.isBlank() || !subtitle.isBlank()) {
            player.sendTitle(title, subtitle);
        }
        String actionBar = text(onComplete, "actionBar");
        if (!actionBar.isBlank()) {
            player.sendActionBar(Component.text(actionBar));
        }
        String flowId = text(onComplete, "flowId");
        if (flowId.isBlank() || flowStorage == null || flowExecutor == null) {
            return;
        }
        FlowGraph graph = flowStorage.getGraph(flowId);
        if (graph != null && graph.getNodes() != null && !graph.getNodes().isEmpty()) {
            String start = graph.getNodes().keySet().stream().sorted(String.CASE_INSENSITIVE_ORDER).findFirst().orElse(null);
            flowExecutor.execute(graph, start, player, event, vars);
        }
    }

    private Map<String, Object> eventVars(Player player, String tree, String node, String criterion, String trigger, Map<String, Object> inputs) {
        Map<String, Object> vars = new HashMap<>();
        if (inputs != null) {
            vars.putAll(inputs);
        }
        vars.put("event.player", player);
        vars.put("event.advancement_tree", tree);
        vars.put("event.advancement", node);
        vars.put("event.criterion", criterion);
        vars.put("event.trigger", trigger);
        return vars;
    }

    private Map<String, JsonObject> trees(String replacementId, JsonObject replacement) {
        Map<String, JsonObject> trees = new LinkedHashMap<>();
        for (String id : storage.listIds(ReSyncResourceCatalog.ADVANCEMENT_TREE)) {
            if (!id.equals(replacementId)) {
                trees.put(id, storage.get(ReSyncResourceCatalog.ADVANCEMENT_TREE, id));
            }
        }
        if (replacement != null) {
            trees.put(id(replacement), replacement);
        }
        return trees;
    }

    private void requireSupported() {
        if (!bridge.supported()) {
            throw new IllegalStateException(bridge.unsupportedReason());
        }
    }

    private void sync(Player player, Map<String, JsonObject> trees) {
        fingerprints.reconcile(player, trees);
        bridge.sync(player);
    }

    private void reloadAdvancements() {
        if (!bridge.supported()) {
            return;
        }
        Map<String, JsonObject> trees = trees(null, null);
        if (trees.isEmpty()) {
            return;
        }
        validator.validate(trees);
        replaceSynchronously(trees);
        Bukkit.getOnlinePlayers().forEach(player -> sync(player, trees));
    }

    private void replaceSynchronously(Map<String, JsonObject> trees) {
        if (Bukkit.isPrimaryThread()) {
            replace(trees);
            return;
        }
        try {
            Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                replace(trees);
                return null;
            }).get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Advancement update interrupted", interrupted);
        } catch (ExecutionException execution) {
            Throwable cause = execution.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Advancement update failed", cause);
        }
    }

    private void replace(Map<String, JsonObject> trees) {
        List<Player> players = List.copyOf(Bukkit.getOnlinePlayers());
        Map<UUID, Map<NamespacedKey, Set<String>>> snapshots = service.snapshot(players);
        try {
            players.forEach(player -> fingerprints.revokeChanged(player, trees));
            bridge.replace(trees);
            players.forEach(player -> fingerprints.commit(player, trees));
            players.forEach(bridge::sync);
        } catch (RuntimeException failure) {
            service.restore(players, snapshots);
            throw failure;
        }
    }

    private void rollback(String type) {
        if (ReSyncResourceCatalog.ADVANCEMENT_TREE.equals(type) && bridge.supported()) {
            replaceSynchronously(trees(null, null));
        }
    }

    private String id(JsonObject tree) {
        if (tree == null || !tree.has("id") || tree.get("id").isJsonNull() || tree.get("id").getAsString().isBlank()) {
            throw new IllegalArgumentException("Advancement tree requires an ID");
        }
        return tree.get("id").getAsString();
    }

    private JsonObject object(JsonObject value, String key) {
        return value != null && value.has(key) && value.get(key).isJsonObject() ? value.getAsJsonObject(key) : new JsonObject();
    }

    private String text(JsonObject value, String key) {
        return value != null && value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsString() : "";
    }

    private boolean bool(JsonObject value, String key, boolean fallback) {
        return value != null && value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsBoolean() : fallback;
    }
}
