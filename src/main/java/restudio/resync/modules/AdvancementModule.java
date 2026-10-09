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
import org.bukkit.event.EventPriority;
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
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
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
import restudio.resync.Log;
import restudio.resync.diagnostics.BoundedDiagnosticDeduplicator;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.flow.FlowPredicateSupport;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.FunctionCallSupport;
import restudio.resync.network.paper.PaperPlayerDataMutationAdmission;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.resources.JsonAssetStore;
import restudio.resync.modules.flow.FlowResourceMutationStamp;
import restudio.flow.data.FlowGraph;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class AdvancementModule implements Module, Listener, ReSyncJsonResourceStorage.ResourceMutationInterceptor {
    private static final ModuleMetadata METADATA = ModuleMetadata.of("advancements", "Advancements").withDependencies("flow");
    private final AdvancementTreeValidator validator = new AdvancementTreeValidator();
    private AdvancementRuntimeBridge bridge;
    private final AdvancementService service;
    private AdvancementPredicateEvaluator predicates;
    private ReSyncJsonResourceStorage storage;
    private AdvancementFingerprintReconciler fingerprints;
    private FlowStorage flowStorage;
    private FlowExecutor flowExecutor;
    private BukkitTask pollingTask;
    private BukkitTask reloadTask;
    private volatile boolean running;
    private JavaPlugin plugin;
    private final AtomicLong treeGeneration = new AtomicLong();
    private volatile TreeSnapshot residentSnapshot;
    private volatile NativeProjection projection;
    private final Map<PredicateKey, PendingPredicate> pendingPredicates = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> playerSessions = new HashMap<>();
    private final BoundedDiagnosticDeduplicator predicateFailures = new BoundedDiagnosticDeduplicator(256);
    private static final int MAX_PENDING_PREDICATES = 1024;

    private record PredicateKey(long storageGeneration, Path root, JsonAssetStore.AssetStamp tree, UUID player, String node, String criterion) {
    }

    private record PendingPredicate(long generation, UUID session, NativeProjection projection, FlowStorage.RuntimeObservation flows, AtomicBoolean completed) {
    }

    private record TreeSnapshot(long generation, long storageGeneration, long verifiedSequence, ReSyncJsonResourceStorage.ResourceSnapshot stamp, Map<String, JsonObject> trees, Map<String, JsonAssetStore.AssetStamp> stamps) {
    }

    private record NativeProjection(TreeSnapshot snapshot, boolean ready) {
    }

    public record TreeState(JsonObject definition, JsonAssetStore.AssetStamp stamp) {
        public TreeState {
            definition = definition.deepCopy();
        }

        @Override
        public JsonObject definition() {
            return definition.deepCopy();
        }
    }

    public AdvancementModule() {
        this(PaperPlayerDataMutationAdmission.shared());
    }

    public AdvancementModule(PaperPlayerDataMutationAdmission playerDataAdmission) {
        service = new AdvancementService(playerDataAdmission);
    }

    @Override
    public ModuleMetadata getMetadata() {
        return METADATA;
    }

    @Override
    public void initialize(ModuleContext context) {
        plugin = context.getPlugin();
        storage = context.getRequiredService(ReSyncJsonResourceStorage.class);
        PaperPlayerDataMutationAdmission playerDataAdmission =
            context.getRequiredService(PaperPlayerDataMutationAdmission.class);
        fingerprints = new AdvancementFingerprintReconciler(context.getPlugin(), service, playerDataAdmission);
        flowStorage = context.getService(FlowStorage.class);
        flowExecutor = context.getService(FlowExecutor.class);
        CustomContentService customContent = context.getService(CustomContentService.class);
        predicates = new AdvancementPredicateEvaluator(customContent);
        bridge = new PaperAdvancementRuntimeBridge(customContent, playerDataAdmission);
        storage.addInterceptor(this);
        context.registerService(AdvancementModule.class, this);
        context.registerService(AdvancementService.class, service);
    }

    @Override
    public void start(ModuleContext context) {
        running = true;
        Bukkit.getPluginManager().registerEvents(this, context.getPlugin());
        reloadAdvancements();
        reloadTask = Bukkit.getScheduler().runTaskLater(context.getPlugin(), this::reloadAdvancements, 40L);
        pollingTask = Bukkit.getScheduler().runTaskTimer(context.getPlugin(), () -> {
            if (!running) {
                return;
            }
            Map<String, JsonObject> trees = admitTrees();
            if (trees.isEmpty()) {
                return;
            }
            for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
                dispatch(player, "held_item", null, Map.of("event.item", player.getInventory().getItemInMainHand()), trees);
                dispatch(player, "permission", null, Map.of(), trees);
                dispatch(player, "in_biome", null, Map.of("event.biome", player.getLocation().getBlock().getBiome().getKey().toString()), trees);
                pollQuestCompletion(player, trees);
            }
        }, 20, 20);
    }

    @Override
    public void stop(ModuleContext context) {
        running = false;
        projection = null;
        playerSessions.clear();
        pendingPredicates.entrySet().removeIf(entry -> entry.getValue().completed().get());
        if (reloadTask != null) {
            reloadTask.cancel();
            reloadTask = null;
        }
        if (pollingTask != null) {
            pollingTask.cancel();
            pollingTask = null;
        }
        HandlerList.unregisterAll(this);
        if (storage != null) {
            storage.removeInterceptor(this);
        }
        invalidateTrees();
        if (bridge != null && bridge.supported()) {
            bridge.replace(Map.of());
        }
    }

    @Override
    public void beforeSave(String type, JsonObject value) {
        if (!ReSyncResourceCatalog.ADVANCEMENT_TREE.equals(type)) {
            return;
        }
        requireSupported();
        validator.validate(trees(id(value), value));
    }

    @Override
    public void beforeDelete(String type, String id) {
        if (!ReSyncResourceCatalog.ADVANCEMENT_TREE.equals(type)) {
            return;
        }
        requireSupported();
        validator.validate(trees(id, null));
    }

    @Override
    public void afterCommit(String type, String id, JsonObject value, FlowResourceMutationStamp stamp) {
        if (ReSyncResourceCatalog.ADVANCEMENT_TREE.equals(type)) {
            if (stamp == null || !type.equals(stamp.type()) || !id.equals(stamp.id())) {
                throw new IllegalArgumentException("Advancement commit identity is required");
            }
            replaceSynchronously(stamp);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        playerSessions.put(event.getPlayer().getUniqueId(), UUID.randomUUID());
        sync(event.getPlayer(), admitTrees());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        playerSessions.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String command = normalizeCommand(event.getMessage());
        if (!command.isBlank()) {
            completeCommandQuest(event.getPlayer(), event, command);
        }
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (!running || plugin == null || !bridge.supported()) {
            return;
        }
        if ("Nexo".equalsIgnoreCase(event.getPlugin().getName())) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (running) {
                    NativeProjection current = projection;
                    projection = current == null ? null : new NativeProjection(current.snapshot(), false);
                    reloadAdvancements();
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            dispatch(player, "obtain_item", event, Map.of("event.item", event.getItem().getItemStack()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        dispatch(event.getPlayer(), "consume_item", event, Map.of("event.item", event.getItem()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        dispatch(event.getPlayer(), "place_block", event, Map.of("event.block", event.getBlockPlaced()));
        dispatch(event.getPlayer(), "place_furniture", event, Map.of("event.block", event.getBlockPlaced(), "event.item", event.getItemInHand()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
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
        payload.put("supported", bridge != null && bridge.supported());
        payload.put("unsupportedReason", bridge == null ? "Advancement runtime is not initialized" : bridge.unsupportedReason());
        payload.put("active", running);
        payload.put("triggers", AdvancementTriggerDescriptors.sorted());
        return payload;
    }

    public AdvancementService advancementService() {
        return service;
    }

    public List<TreeState> treeStates() {
        TreeSnapshot snapshot = runtimeTrees();
        return snapshot.stamp().values().stream()
            .map(value -> new TreeState(snapshot.trees().get(value.id()), value.stamp())).toList();
    }

    public TreeState treeState(String treeId) {
        TreeSnapshot snapshot = runtimeTrees();
        return snapshot.stamp().values().stream().filter(value -> value.id().equals(treeId))
            .map(value -> new TreeState(snapshot.trees().get(treeId), value.stamp())).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown advancement tree: " + treeId));
    }

    public boolean conditionsMatch(Player player, String treeId, String nodeId, String criterionId, Map<String, Object> inputs) {
        JsonObject tree = treeState(treeId).definition();
        if (!bool(tree, "enabled", true)) {
            throw new IllegalArgumentException("Advancement tree is disabled: " + treeId);
        }
        JsonObject nodes = object(tree, "nodes");
        if (!nodes.has(nodeId) || !nodes.get(nodeId).isJsonObject()) {
            throw new IllegalArgumentException("Unknown advancement node: " + nodeId);
        }
        JsonObject node = nodes.getAsJsonObject(nodeId);
        if (!bool(node, "enabled", true)) {
            throw new IllegalArgumentException("Advancement node is disabled: " + nodeId);
        }
        JsonObject criteria = object(node, "criteria");
        if (!criteria.has(criterionId) || !criteria.get(criterionId).isJsonObject()) {
            throw new IllegalArgumentException("Unknown advancement criterion: " + criterionId);
        }
        return predicates.matches(player, object(criteria.getAsJsonObject(criterionId), "conditions"), inputs);
    }

    private TreeSnapshot runtimeTrees() {
        if (!Bukkit.isPrimaryThread() || !running || storage == null || bridge == null || !bridge.supported()) {
            throw new IllegalStateException("Advancement runtime is unavailable on this thread");
        }
        TreeSnapshot snapshot = admitSnapshot();
        if (!projected(snapshot)) {
            throw new IllegalStateException("Committed advancement trees are awaiting native runtime activation");
        }
        return snapshot;
    }

    public void requireRuntimeCurrent() {
        runtimeTrees();
    }

    public void requireCurrent(TreeState state) {
        TreeState current = treeState(state.stamp().id());
        if (!current.stamp().equals(state.stamp())) {
            throw new IllegalStateException("Advancement tree changed before native progress access");
        }
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
        dispatch(player, trigger, event, inputs, admitTrees());
    }

    private void dispatch(Player player, String trigger, Event event, Map<String, Object> inputs,
                          Map<String, JsonObject> trees) {
        if (!current(trees) || player == null || trees.isEmpty() || !AdvancementTriggerDescriptors.IDS.contains(trigger)) {
            return;
        }
        for (Map.Entry<String, JsonObject> treeEntry : trees.entrySet()) {
            if (!bool(treeEntry.getValue(), "enabled", true)) {
                continue;
            }
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
                    evaluateCriterion(trees, node, player, event, treeEntry.getKey(), nodeEntry.getKey(), criterionEntry.getKey(), vars,
                        text(criterion, "predicateFlowId"), object(criterion, "predicate"));
                }
            }
        }
    }

    private void pollQuestCompletion(Player player, Map<String, JsonObject> trees) {
        if (!current(trees) || player == null || trees.isEmpty() || flowStorage == null || flowExecutor == null) {
            return;
        }
        for (Map.Entry<String, JsonObject> treeEntry : trees.entrySet()) {
            if (!bool(treeEntry.getValue(), "enabled", true)) {
                continue;
            }
            JsonObject nodes = treeEntry.getValue().getAsJsonObject("nodes");
            for (Map.Entry<String, JsonElement> nodeEntry : nodes.entrySet()) {
                JsonObject node = nodeEntry.getValue().getAsJsonObject();
                if (!bool(node, "enabled", true) || service.complete(player, treeEntry.getKey(), nodeEntry.getKey())) {
                    continue;
                }
                JsonObject questCompletion = object(node, "questCompletion");
                String flowId = text(questCompletion, "flowId");
                JsonObject predicate = object(questCompletion, "predicate");
                String type = text(questCompletion, "type");
                if (!"Flow".equals(type) && !"Function".equals(type) || "Flow".equals(type) && flowId.isBlank() || "Function".equals(type) && !hasFunctionCall(predicate)) {
                    continue;
                }
                String criterion = firstCriterion(node);
                Map<String, Object> vars = eventVars(player, treeEntry.getKey(), nodeEntry.getKey(), criterion, "flow", Map.of());
                evaluateCriterion(trees, node, player, null, treeEntry.getKey(), nodeEntry.getKey(), criterion, vars,
                    "Flow".equals(type) ? flowId : "", "Function".equals(type) ? predicate : new JsonObject());
            }
        }
    }

    private void evaluateCriterion(Map<String, JsonObject> trees, JsonObject node, Player player, Event event,
            String tree, String nodeId, String criterion, Map<String, Object> vars, String flowId, JsonObject function) {
        if (!current(trees) || !player.isOnline() || service.has(player, tree, nodeId, criterion)) {
            return;
        }
        if (flowId.isBlank() && !hasFunctionCall(function)) {
            grantAndComplete(trees, node, player, event, tree, nodeId, criterion, vars);
            return;
        }
        TreeSnapshot snapshot = admitSnapshot();
        if (snapshot.trees() != trees || !projected(snapshot)) {
            return;
        }
        PredicateKey key = new PredicateKey(snapshot.storageGeneration(), snapshot.stamp().root(), snapshot.stamps().get(tree), player.getUniqueId(), nodeId, criterion);
        if (pendingPredicates.containsKey(key)) {
            return;
        }
        if (pendingPredicates.size() >= MAX_PENDING_PREDICATES) {
            reportPredicateFailure(tree, nodeId, criterion, new IllegalStateException("Advancement predicate capacity is exhausted"));
            return;
        }
        FlowStorage.RuntimeObservation flows = flowStorage == null ? null : flowStorage.observeRuntime().orElse(null);
        if (flows == null) {
            reportPredicateFailure(tree, nodeId, criterion, new IllegalStateException("Advancement predicate storage is unavailable"));
            return;
        }
        UUID session = playerSessions.computeIfAbsent(player.getUniqueId(), ignored -> UUID.randomUUID());
        PendingPredicate pending = new PendingPredicate(treeGeneration.get(), session, projection, flows, new AtomicBoolean());
        if (pendingPredicates.putIfAbsent(key, pending) != null) {
            return;
        }
        Map<String, Object> inputs = new LinkedHashMap<>();
        vars.forEach((name, value) -> inputs.put(name, value instanceof ItemStack item ? item.clone() : value));
        Map<String, Object> admitted = Collections.unmodifiableMap(inputs);
        JsonObject call = function.deepCopy();
        CompletableFuture<Boolean> evaluation;
        try {
            FlowExecutor.FunctionInvocationContext invocation = !hasFunctionCall(call) || flowExecutor == null ? null
                : flowExecutor.defaultFunctionInvocationContext(player, event, admitted, CorrelationId.random(), System.currentTimeMillis() + 5000L);
            evaluation = FlowPredicateSupport.evaluateAsync(flowStorage, flowExecutor, flowId, player, event, admitted)
                .thenCompose(passed -> passed ? evaluateFunction(key, pending, trees, player, event, admitted, call, invocation)
                    : CompletableFuture.completedFuture(false));
        } catch (RuntimeException failure) {
            evaluation = CompletableFuture.failedFuture(failure);
        }
        evaluation.whenComplete((passed, failure) -> {
            pending.completed().set(true);
            if (!running || plugin == null || !plugin.isEnabled()) {
                pendingPredicates.remove(key, pending);
                return;
            }
            Runnable finish = () -> {
                try {
                    if (!predicateCurrent(key, pending, trees, player)) {
                        return;
                    }
                    if (failure != null) {
                        reportPredicateFailure(tree, nodeId, criterion, failure);
                    } else if (Boolean.TRUE.equals(passed) && !service.has(player, tree, nodeId, criterion)) {
                        grantAndComplete(trees, node, player, null, tree, nodeId, criterion, admitted);
                    }
                } catch (RuntimeException failed) {
                    reportPredicateFailure(tree, nodeId, criterion, failed);
                } finally {
                    pendingPredicates.remove(key, pending);
                }
            };
            if (Bukkit.isPrimaryThread()) {
                finish.run();
            } else {
                try {
                    Bukkit.getScheduler().runTask(plugin, finish);
                } catch (RuntimeException stopped) {
                    pendingPredicates.remove(key, pending);
                    reportPredicateFailure(tree, nodeId, criterion, stopped);
                }
            }
        });
    }

    private CompletableFuture<Boolean> evaluateFunction(PredicateKey key, PendingPredicate pending, Map<String, JsonObject> trees,
            Player player, Event event, Map<String, Object> vars, JsonObject call, FlowExecutor.FunctionInvocationContext invocation) {
        if (Bukkit.isPrimaryThread()) {
            return predicateCurrent(key, pending, trees, player)
                ? FunctionCallSupport.evaluateAsync(flowStorage, flowExecutor, call, player, event, vars, invocation)
                : CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    if (!predicateCurrent(key, pending, trees, player)) {
                        result.complete(false);
                        return;
                    }
                    FunctionCallSupport.evaluateAsync(flowStorage, flowExecutor, call, player, null, vars, invocation)
                        .whenComplete((passed, failure) -> {
                            if (failure == null) {
                                result.complete(passed);
                            } else {
                                result.completeExceptionally(failure);
                            }
                        });
                } catch (RuntimeException failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException stopped) {
            result.completeExceptionally(stopped);
        }
        return result;
    }

    private boolean predicateCurrent(PredicateKey key, PendingPredicate pending, Map<String, JsonObject> trees, Player player) {
        return pendingPredicates.get(key) == pending && running && plugin != null && plugin.isEnabled()
            && pending.generation() == treeGeneration.get() && pending.projection() == projection && player.isOnline() && Bukkit.getPlayer(key.player()) == player
            && pending.session().equals(playerSessions.get(key.player())) && current(trees)
            && flowStorage.isRuntimeObservationCurrent(pending.flows());
    }

    private void reportPredicateFailure(String tree, String node, String criterion, Throwable failure) {
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        if (predicateFailures.add(tree + ":" + node + ":" + criterion + ":" + message)) {
            Log.warn("Advancement predicate failed for " + tree + "/" + node + "/" + criterion + ": " + message, failure);
        }
    }

    private void completeCommandQuest(Player player, Event event, String command) {
        if (player == null || command.isBlank()) {
            return;
        }
        Map<String, JsonObject> trees = admitTrees();
        if (!current(trees)) {
            return;
        }
        for (Map.Entry<String, JsonObject> treeEntry : trees.entrySet()) {
            if (!bool(treeEntry.getValue(), "enabled", true)) {
                continue;
            }
            JsonObject nodes = treeEntry.getValue().getAsJsonObject("nodes");
            for (Map.Entry<String, JsonElement> nodeEntry : nodes.entrySet()) {
                JsonObject node = nodeEntry.getValue().getAsJsonObject();
                if (!bool(node, "enabled", true) || service.complete(player, treeEntry.getKey(), nodeEntry.getKey())) {
                    continue;
                }
                JsonObject questCompletion = object(node, "questCompletion");
                if (!"Command".equals(text(questCompletion, "type")) || !command.equals(normalizeCommand(text(questCompletion, "command")))) {
                    continue;
                }
                String criterion = firstCriterion(node);
                Map<String, Object> vars = eventVars(player, treeEntry.getKey(), nodeEntry.getKey(), criterion, "command", Map.of("event.command", command));
                grantAndComplete(trees, node, player, event, treeEntry.getKey(), nodeEntry.getKey(), criterion, vars);
            }
        }
    }

    private void grantAndComplete(Map<String, JsonObject> trees, JsonObject node, Player player, Event event, String tree, String nodeId, String criterion, Map<String, Object> vars) {
        if (!current(trees)) {
            return;
        }
        boolean completed = service.complete(player, tree, nodeId);
        if (service.grant(player, tree, nodeId, criterion) && !completed && service.complete(player, tree, nodeId)) {
            complete(node, player, event, vars);
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
        FunctionCallSupport.execute(flowStorage, flowExecutor, object(onComplete, "action"), player, event, vars);
        FunctionCallSupport.execute(flowStorage, flowExecutor, object(onComplete, "function"), player, event, vars);
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

    private String firstCriterion(JsonObject node) {
        JsonObject criteria = object(node, "criteria");
        return criteria.keySet().stream().findFirst().orElse("impossible");
    }

    private boolean hasFunctionCall(JsonObject call) {
        String function = text(call, "functionId");
        if (function.isBlank()) {
            function = text(call, "id");
        }
        return call != null && (call.has("graph") && call.get("graph").isJsonObject() || !function.isBlank() && !"none".equalsIgnoreCase(function));
    }

    private Map<String, JsonObject> trees(String replacementId, JsonObject replacement) {
        Map<String, JsonObject> trees = new LinkedHashMap<>(admitSnapshot().trees());
        if (replacementId != null) {
            trees.remove(replacementId);
        }
        if (replacement != null) {
            trees.put(id(replacement), replacement);
        }
        return trees;
    }

    private Map<String, JsonObject> admitTrees() {
        try {
            return storage == null ? Map.of() : admitSnapshot().trees();
        } catch (RuntimeException failed) {
            invalidateTrees();
            return Map.of();
        }
    }

    private TreeSnapshot admitSnapshot() {
        while (true) {
            long storageGeneration = storage.snapshotGeneration();
            long sequence = storage.committedSequence();
            long generation = treeGeneration.get();
            TreeSnapshot current = residentSnapshot;
            if (current != null && current.generation() == generation && current.storageGeneration() == storageGeneration) {
                if (current.verifiedSequence() == sequence) {
                    return current;
                }
                if (storage.isCurrent(current.stamp()) && sequence == storage.committedSequence()
                    && storageGeneration == storage.snapshotGeneration() && generation == treeGeneration.get()) {
                    TreeSnapshot admitted = new TreeSnapshot(generation, storageGeneration, sequence, current.stamp(), current.trees(), current.stamps());
                    residentSnapshot = admitted;
                    return admitted;
                }
            }
            ReSyncJsonResourceStorage.ResourceSnapshot snapshot = storage.readSnapshot(ReSyncResourceCatalog.ADVANCEMENT_TREE);
            Map<String, JsonObject> trees = new LinkedHashMap<>();
            Map<String, JsonAssetStore.AssetStamp> stamps = new LinkedHashMap<>();
            for (ReSyncJsonResourceStorage.ResourceSnapshotValue value : snapshot.values()) {
                trees.put(value.id(), value.value());
                stamps.put(value.id(), value.stamp());
            }
            if (generation == treeGeneration.get() && storageGeneration == storage.snapshotGeneration()
                && snapshot.rootSequence() == storage.committedSequence()) {
                TreeSnapshot admitted = new TreeSnapshot(generation, storageGeneration, snapshot.rootSequence(), snapshot, Map.copyOf(trees), Map.copyOf(stamps));
                residentSnapshot = admitted;
                return admitted;
            }
        }
    }

    private boolean current(Map<String, JsonObject> trees) {
        if (!running || !Bukkit.isPrimaryThread()) {
            return false;
        }
        try {
            TreeSnapshot snapshot = admitSnapshot();
            return snapshot.trees() == trees && projected(snapshot);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private boolean projected(TreeSnapshot snapshot) {
        NativeProjection nativeProjection = projection;
        return nativeProjection != null && nativeProjection.ready() && sameTrees(nativeProjection.snapshot(), snapshot);
    }

    private boolean sameTrees(TreeSnapshot left, TreeSnapshot right) {
        return left.storageGeneration() == right.storageGeneration() && left.stamp().root().equals(right.stamp().root())
            && left.stamp().revision().equals(right.stamp().revision());
    }

    private void invalidateTrees() {
        treeGeneration.incrementAndGet();
        residentSnapshot = null;
    }

    private void requireSupported() {
        if (!running || bridge == null) {
            throw new IllegalStateException("Advancement runtime is stopped");
        }
        if (!bridge.supported()) {
            throw new IllegalStateException(bridge.unsupportedReason());
        }
    }

    private void sync(Player player, Map<String, JsonObject> trees) {
        if (current(trees)) {
            fingerprints.reconcile(player, trees);
            bridge.sync(player);
        }
    }

    private void reloadAdvancements() {
        if (running && bridge.supported()) {
            replaceSynchronously(null);
        }
    }

    private void replaceSynchronously(FlowResourceMutationStamp stamp) {
        if (Bukkit.isPrimaryThread()) {
            activateCommitted(stamp);
            return;
        }
        try {
            Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                activateCommitted(stamp);
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
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Advancement update failed", cause);
        }
    }

    private void activateCommitted(FlowResourceMutationStamp stamp) {
        requireSupported();
        requireStamp(stamp);
        TreeSnapshot snapshot = admitSnapshot();
        validator.validate(snapshot.trees());
        if (projected(snapshot)) {
            requireStamp(stamp);
            return;
        }
        NativeProjection previous = projection;
        projection = previous == null ? null : new NativeProjection(previous.snapshot(), false);
        List<Player> players = List.copyOf(Bukkit.getOnlinePlayers());
        Map<UUID, Map<NamespacedKey, Set<String>>> progress = service.snapshot(players);
        boolean replaced = false;
        try {
            players.forEach(player -> fingerprints.revokeChanged(player, snapshot.trees()));
            bridge.replace(snapshot.trees());
            replaced = true;
            players.forEach(player -> fingerprints.commit(player, snapshot.trees()));
            players.forEach(bridge::sync);
            requireStamp(stamp);
            if (snapshot.storageGeneration() != storage.snapshotGeneration() || !storage.isCurrent(snapshot.stamp())) {
                throw new IllegalStateException("Committed advancement trees changed during native activation");
            }
            projection = new NativeProjection(snapshot, true);
        } catch (RuntimeException | Error failure) {
            try {
                if (replaced) {
                    Map<String, JsonObject> oldTrees = previous == null ? Map.of() : previous.snapshot().trees();
                    bridge.replace(oldTrees);
                    players.forEach(player -> fingerprints.commit(player, oldTrees));
                }
                service.restore(players, progress);
            } catch (RuntimeException | Error compensationFailure) {
                if (failure != compensationFailure) {
                    failure.addSuppressed(compensationFailure);
                }
            }
            throw failure;
        }
    }

    private void requireStamp(FlowResourceMutationStamp stamp) {
        if (stamp != null && !stamp.equals(storage.readMutationStamp(stamp.type(), stamp.id()))) {
            throw new IllegalStateException("Committed advancement identity changed before native activation");
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

    private String normalizeCommand(String command) {
        if (command == null) {
            return "";
        }
        String normalized = command.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1).trim();
        }
        return normalized;
    }

    private boolean bool(JsonObject value, String key, boolean fallback) {
        return value != null && value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsBoolean() : fallback;
    }
}
