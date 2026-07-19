package restudio.resync.flow.triggers;

import org.bukkit.entity.Damageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.event.weather.LightningStrikeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.event.world.TimeSkipEvent;
import restudio.resync.flow.jobs.FlowJobCompletedEvent;

import java.util.Map;

public class TriggerDefinitions {

    @FlowTrigger(eventType = "flow_job_completed", nodeType = "event.job.completed", eventClass = FlowJobCompletedEvent.class, playerEvent = false)
    public void onFlowJobCompleted(FlowJobCompletedEvent event, Map<String, Object> vars) {
        vars.put("event.job", event.getReference());
        vars.put("event.job_id", event.getSnapshot().id());
        vars.put("event.job_kind", event.getSnapshot().kind());
        vars.put("event.job_owner", event.getSnapshot().owner());
        vars.put("event.job_state", event.getSnapshot().state().name());
        vars.put("event.job_progress", event.getSnapshot().progress());
        vars.put("event.job_metadata", event.getSnapshot().metadata());
        vars.put("event.job_outcome", event.getSnapshot().outcome());
    }

    @FlowTrigger(eventType = "player_join", eventClass = PlayerJoinEvent.class)
    public void onPlayerJoin(PlayerJoinEvent event, Map<String, Object> vars) {
        vars.put("event.join_message", event.getJoinMessage());
    }

    @FlowTrigger(eventType = "player_quit", eventClass = PlayerQuitEvent.class)
    public void onPlayerQuit(PlayerQuitEvent event, Map<String, Object> vars) {
        vars.put("event.quit_message", event.getQuitMessage());
    }

    @FlowTrigger(eventType = "async_chat", eventClass = AsyncPlayerChatEvent.class, priority = EventPriority.HIGHEST)
    public void onAsyncChat(AsyncPlayerChatEvent event, Map<String, Object> vars) {
        vars.put("event.message", event.getMessage());
        vars.put("event.format", event.getFormat());
    }

    @FlowTrigger(eventType = "player_sneak", nodeType = "event:toggle_sneak", eventClass = PlayerToggleSneakEvent.class, aliases = {"player_toggle_sneak"})
    public void onPlayerToggleSneak(PlayerToggleSneakEvent event, Map<String, Object> vars) {
        vars.put("event.is_sneaking", event.isSneaking());
    }

    @FlowTrigger(eventType = "player_death", eventClass = PlayerDeathEvent.class)
    public void onPlayerDeath(PlayerDeathEvent event, Map<String, Object> vars) {
        vars.put("event.death_message", event.getDeathMessage());
    }

    @FlowTrigger(eventType = "block_break", eventClass = BlockBreakEvent.class, priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent event, Map<String, Object> vars) {
        vars.put("event.block", event.getBlock());
        vars.put("event.location", event.getBlock().getLocation());
        vars.put("event.is_cancelled", event.isCancelled());
    }

    @FlowTrigger(eventType = "block_place", eventClass = BlockPlaceEvent.class, priority = EventPriority.HIGHEST)
    public void onBlockPlace(BlockPlaceEvent event, Map<String, Object> vars) {
        vars.put("event.block", event.getBlock());
        vars.put("event.placed_against", event.getBlockAgainst());
        vars.put("event.location", event.getBlock().getLocation());
        vars.put("event.against_location", event.getBlockAgainst() != null ? event.getBlockAgainst().getLocation() : null);
        vars.put("event.is_cancelled", event.isCancelled());
    }

    @FlowTrigger(eventType = "player_interact", eventClass = PlayerInteractEvent.class)
    public void onPlayerInteract(PlayerInteractEvent event, Map<String, Object> vars) {
        vars.put("event.clicked_block", event.getClickedBlock());
        vars.put("event.clicked_entity", null);
        vars.put("event.action_type", event.getAction().name());
    }

    @FlowTrigger(eventType = "player_interact_entity", eventClass = PlayerInteractEntityEvent.class)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event, Map<String, Object> vars) {
        vars.put("event.entity", event.getRightClicked());
    }

    @FlowTrigger(eventType = "entity_damage", nodeType = "event:player_damage", eventClass = EntityDamageEvent.class, playerEvent = true)
    public void onEntityDamage(EntityDamageEvent event, Map<String, Object> vars) {
        if (!(event.getEntity() instanceof Player)) return;
        Object damager = null;
        if (event instanceof EntityDamageByEntityEvent entityDamage) {
            damager = entityDamage.getDamager();
        }
        vars.put("event.damager", damager);
        vars.put("event.victim", event.getEntity());
        vars.put("event.damage", event.getDamage());
        vars.put("event.cause", event.getCause().name());
    }

    @FlowTrigger(eventType = "player_pickup_item", eventClass = PlayerPickupItemEvent.class)
    public void onPlayerPickupItem(PlayerPickupItemEvent event, Map<String, Object> vars) {
        vars.put("event.item", event.getItem().getItemStack());
    }

    @FlowTrigger(eventType = "player_drop_item", eventClass = PlayerDropItemEvent.class)
    public void onPlayerDropItem(PlayerDropItemEvent event, Map<String, Object> vars) {
        vars.put("event.item", event.getItemDrop().getItemStack());
    }

    @FlowTrigger(eventType = "player_item_consume", eventClass = PlayerItemConsumeEvent.class)
    public void onPlayerItemConsume(PlayerItemConsumeEvent event, Map<String, Object> vars) {
        vars.put("event.item", event.getItem());
    }

    @FlowTrigger(eventType = "craft_item", eventClass = CraftItemEvent.class, playerExtractor = "extractCraftPlayer")
    public void onCraftItem(CraftItemEvent event, Map<String, Object> vars) {
        vars.put("event.result", event.getRecipe().getResult());
    }

    @FlowTrigger(eventType = "furnace_smelt", eventClass = FurnaceSmeltEvent.class, playerEvent = false)
    public void onFurnaceSmelt(FurnaceSmeltEvent event, Map<String, Object> vars) {
        vars.put("event.furnace", event.getBlock());
        vars.put("event.location", event.getBlock().getLocation());
        vars.put("event.result", event.getResult());
        vars.put("event.player", null);
    }

    @FlowTrigger(eventType = "player_bed_enter", eventClass = PlayerBedEnterEvent.class)
    public void onPlayerBedEnter(PlayerBedEnterEvent event, Map<String, Object> vars) {
        vars.put("event.bed_location", event.getBed().getLocation());
    }

    @FlowTrigger(eventType = "player_bed_leave", eventClass = PlayerBedLeaveEvent.class)
    public void onPlayerBedLeave(PlayerBedLeaveEvent event, Map<String, Object> vars) {
        vars.put("event.bed_location", event.getBed().getLocation());
    }

    @FlowTrigger(eventType = "player_respawn", eventClass = PlayerRespawnEvent.class)
    public void onPlayerRespawn(PlayerRespawnEvent event, Map<String, Object> vars) {
        vars.put("event.respawn_location", event.getRespawnLocation());
    }

    @FlowTrigger(eventType = "player_level_change", eventClass = PlayerLevelChangeEvent.class)
    public void onPlayerLevelChange(PlayerLevelChangeEvent event, Map<String, Object> vars) {
        vars.put("event.old_level", event.getOldLevel());
        vars.put("event.new_level", event.getNewLevel());
    }

    @FlowTrigger(eventType = "player_move", eventClass = PlayerMoveEvent.class)
    public void onPlayerMove(PlayerMoveEvent event, Map<String, Object> vars) {
        if (event.getFrom().distanceSquared(event.getTo()) < 0.01) return;
        vars.put("event.from_location", event.getFrom());
        vars.put("event.to_location", event.getTo());
        vars.put("event.distance", event.getFrom().distance(event.getTo()));
    }

    @FlowTrigger(eventType = "player_teleport", eventClass = PlayerTeleportEvent.class)
    public void onPlayerTeleport(PlayerTeleportEvent event, Map<String, Object> vars) {
        vars.put("event.from_location", event.getFrom());
        vars.put("event.to_location", event.getTo());
        vars.put("event.cause", event.getCause().name());
    }

    @FlowTrigger(eventType = "player_gamemode_change", eventClass = PlayerGameModeChangeEvent.class)
    public void onPlayerGameModeChange(PlayerGameModeChangeEvent event, Map<String, Object> vars) {
        vars.put("event.old_gamemode", event.getPlayer().getGameMode().name());
        vars.put("event.new_gamemode", event.getNewGameMode().name());
    }

    @FlowTrigger(eventType = "player_toggle_flight", eventClass = PlayerToggleFlightEvent.class)
    public void onPlayerToggleFlight(PlayerToggleFlightEvent event, Map<String, Object> vars) {
        vars.put("event.is_flying", event.isFlying());
    }

    @FlowTrigger(eventType = "player_fish", eventClass = PlayerFishEvent.class)
    public void onPlayerFish(PlayerFishEvent event, Map<String, Object> vars) {
        vars.put("event.state", event.getState().name());
        vars.put("event.caught", event.getCaught());
    }

    @FlowTrigger(eventType = "player_shear_entity", nodeType = "event:shear_entity", eventClass = PlayerShearEntityEvent.class, aliases = {"player_shear"})
    public void onPlayerShearEntity(PlayerShearEntityEvent event, Map<String, Object> vars) {
        vars.put("event.entity", event.getEntity());
    }

    @FlowTrigger(eventType = "player_item_damage", eventClass = PlayerItemDamageEvent.class)
    public void onPlayerItemDamage(PlayerItemDamageEvent event, Map<String, Object> vars) {
        vars.put("event.item", event.getItem());
        vars.put("event.damage", event.getDamage());
    }

    @FlowTrigger(eventType = "player_item_break", eventClass = PlayerItemBreakEvent.class)
    public void onPlayerItemBreak(PlayerItemBreakEvent event, Map<String, Object> vars) {
        vars.put("event.broken_item", event.getBrokenItem());
    }

    @FlowTrigger(eventType = "player_exp_change", eventClass = PlayerExpChangeEvent.class)
    public void onPlayerExpChange(PlayerExpChangeEvent event, Map<String, Object> vars) {
        vars.put("event.amount", event.getAmount());
    }

    @FlowTrigger(eventType = "projectile_launch", eventClass = ProjectileLaunchEvent.class, playerEvent = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event, Map<String, Object> vars) {
        if (!(event.getEntity().getShooter() instanceof Player)) return;
        vars.put("event.projectile", event.getEntity());
    }

    @FlowTrigger(eventType = "projectile_hit", eventClass = ProjectileHitEvent.class, playerEvent = true)
    public void onProjectileHit(ProjectileHitEvent event, Map<String, Object> vars) {
        if (!(event.getEntity().getShooter() instanceof Player)) return;
        vars.put("event.projectile", event.getEntity());
        vars.put("event.hit_entity", event.getHitEntity());
    }

    @FlowTrigger(eventType = "creature_spawn", nodeType = "event:entity_spawn", eventClass = CreatureSpawnEvent.class, playerEvent = false, aliases = {"entity_spawn"})
    public void onCreatureSpawn(CreatureSpawnEvent event, Map<String, Object> vars) {
        vars.put("event.entity", event.getEntity());
        vars.put("event.location", event.getLocation());
        vars.put("event.entity_type", event.getEntityType().name());
    }

    @FlowTrigger(eventType = "entity_target", eventClass = EntityTargetEvent.class, playerEvent = false)
    public void onEntityTarget(EntityTargetEvent event, Map<String, Object> vars) {
        vars.put("event.entity", event.getEntity());
        vars.put("event.target", event.getTarget());
    }

    @FlowTrigger(eventType = "entity_breed", eventClass = EntityBreedEvent.class, playerEvent = false)
    public void onEntityBreed(EntityBreedEvent event, Map<String, Object> vars) {
        vars.put("event.entity1", event.getEntity());
        vars.put("event.entity2", event.getMother());
        vars.put("event.experience", event.getExperience());
        vars.put("event.bred_entity", event.getEntity());
    }

    @FlowTrigger(eventType = "entity_tame", eventClass = EntityTameEvent.class, playerEvent = false)
    public void onEntityTame(EntityTameEvent event, Map<String, Object> vars) {
        vars.put("event.entity", event.getEntity());
        vars.put("event.tamer", event.getOwner() instanceof Entity e ? e : null);
        vars.put("event.entity_type", event.getEntityType().name());
    }

    @FlowTrigger(eventType = "entity_transform", eventClass = EntityTransformEvent.class, playerEvent = false)
    public void onEntityTransform(EntityTransformEvent event, Map<String, Object> vars) {
        vars.put("event.old_entity", event.getEntity());
        var transformed = event.getTransformedEntities();
        if (!transformed.isEmpty()) {
            vars.put("event.new_entity", transformed.getFirst());
            vars.put("event.new_entity_type", transformed.getFirst().getType().name());
        }
    }

    @FlowTrigger(eventType = "entity_death", nodeType = "event:entity_death", eventClass = EntityDeathEvent.class, playerEvent = false, aliases = {"entity_death"})
    public void onEntityDeath(EntityDeathEvent event, Map<String, Object> vars) {
        vars.put("event.entity", event.getEntity());
        vars.put("event.killer", event.getEntity().getKiller());
    }

    @FlowTrigger(eventType = "entity_damaged", eventClass = EntityDamageEvent.class, playerEvent = false, aliases = {"entity_damage_entity"})
    public void onEntityDamaged(EntityDamageEvent event, Map<String, Object> vars) {
        vars.put("event.entity", event.getEntity());
        Object damager = null;
        if (event instanceof EntityDamageByEntityEvent entityDamage) {
            damager = entityDamage.getDamager();
        }
        vars.put("event.damager", damager);
        vars.put("event.damage", event.getDamage());
        vars.put("event.cause", event.getCause().name());
    }

    @FlowTrigger(eventType = "entity_regain_health", eventClass = EntityRegainHealthEvent.class, playerEvent = false, aliases = {"entity_heal"})
    public void onEntityRegainHealth(EntityRegainHealthEvent event, Map<String, Object> vars) {
        vars.put("event.entity", event.getEntity());
        vars.put("event.amount", event.getAmount());
        double newHealth = event.getAmount();
        if (event.getEntity() instanceof Damageable damageable) {
            newHealth = damageable.getHealth() + event.getAmount();
        }
        vars.put("event.new_health", newHealth);
        vars.put("event.reason", event.getRegainReason().name());
    }

    @FlowTrigger(eventType = "entity_pickup", nodeType = "event:entity_pickup_item", eventClass = EntityPickupItemEvent.class, playerEvent = false, aliases = {"entity_pickup_item"})
    public void onEntityPickup(EntityPickupItemEvent event, Map<String, Object> vars) {
        vars.put("event.entity", event.getEntity());
        vars.put("event.item", event.getItem());
        vars.put("event.remaining", event.getRemaining());
    }

    @FlowTrigger(eventType = "entity_drop", nodeType = "event:entity_drop_item", eventClass = EntityDropItemEvent.class, playerEvent = false, aliases = {"entity_drop_item"})
    public void onEntityDrop(EntityDropItemEvent event, Map<String, Object> vars) {
        vars.put("event.entity", event.getEntity());
        vars.put("event.dropped", event.getItemDrop());
    }

    @FlowTrigger(eventType = "item_merge", eventClass = ItemMergeEvent.class, playerEvent = false)
    public void onItemMerge(ItemMergeEvent event, Map<String, Object> vars) {
        vars.put("event.item1", event.getEntity());
        vars.put("event.item2", event.getTarget());
        vars.put("event.location", event.getEntity().getLocation());
        vars.put("event.result", event.getEntity().getItemStack());
    }

    @FlowTrigger(eventType = "chunk_load", eventClass = ChunkLoadEvent.class, playerEvent = false)
    public void onChunkLoad(ChunkLoadEvent event, Map<String, Object> vars) {
        vars.put("event.chunk", event.getChunk());
        vars.put("event.world_name", event.getWorld().getName());
        vars.put("event.chunk_x", event.getChunk().getX());
        vars.put("event.chunk_z", event.getChunk().getZ());
    }

    @FlowTrigger(eventType = "chunk_unload", eventClass = ChunkUnloadEvent.class, playerEvent = false)
    public void onChunkUnload(ChunkUnloadEvent event, Map<String, Object> vars) {
        vars.put("event.chunk", event.getChunk());
        vars.put("event.world_name", event.getWorld().getName());
        vars.put("event.chunk_x", event.getChunk().getX());
        vars.put("event.chunk_z", event.getChunk().getZ());
    }

    @FlowTrigger(eventType = "block_redstone", eventClass = BlockRedstoneEvent.class, playerEvent = false)
    public void onBlockRedstone(BlockRedstoneEvent event, Map<String, Object> vars) {
        vars.put("event.block", event.getBlock());
        vars.put("event.location", event.getBlock().getLocation());
        vars.put("event.old_power", event.getOldCurrent());
        vars.put("event.new_power", event.getNewCurrent());
    }

    @FlowTrigger(eventType = "block_physics", eventClass = BlockPhysicsEvent.class, playerEvent = false)
    public void onBlockPhysics(BlockPhysicsEvent event, Map<String, Object> vars) {
        vars.put("event.block", event.getBlock());
        vars.put("event.location", event.getBlock().getLocation());
    }

    @FlowTrigger(eventType = "explosion_prime", eventClass = ExplosionPrimeEvent.class, playerEvent = false)
    public void onExplosionPrime(ExplosionPrimeEvent event, Map<String, Object> vars) {
        vars.put("event.location", event.getEntity().getLocation());
        vars.put("event.power", event.getRadius());
        vars.put("event.break_blocks", null);
        vars.put("event.fire", event.getFire());
        vars.put("event.entity", event.getEntity());
        vars.put("event.world_name", event.getEntity().getWorld().getName());
    }

    @FlowTrigger(eventType = "block_grow", eventClass = BlockGrowEvent.class, playerEvent = false)
    public void onBlockGrow(BlockGrowEvent event, Map<String, Object> vars) {
        vars.put("event.block", event.getBlock());
        vars.put("event.location", event.getBlock().getLocation());
        vars.put("event.new_state", event.getNewState().getType());
    }

    @FlowTrigger(eventType = "block_from_to", eventClass = BlockFromToEvent.class, playerEvent = false)
    public void onBlockFromTo(BlockFromToEvent event, Map<String, Object> vars) {
        vars.put("event.from_block", event.getBlock());
        vars.put("event.to_block", event.getToBlock());
        vars.put("event.from_location", event.getBlock().getLocation());
        vars.put("event.to_location", event.getToBlock().getLocation());
    }

    @FlowTrigger(eventType = "world_save", eventClass = WorldSaveEvent.class, playerEvent = false)
    public void onWorldSave(WorldSaveEvent event, Map<String, Object> vars) {
        vars.put("event.world_name", event.getWorld().getName());
    }

    @FlowTrigger(eventType = "weather_change", eventClass = WeatherChangeEvent.class, playerEvent = false)
    public void onWeatherChange(WeatherChangeEvent event, Map<String, Object> vars) {
        vars.put("event.world_name", event.getWorld().getName());
        vars.put("event.old_weather", event.getWorld().isThundering() ? "thunder" : "clear");
        vars.put("event.new_weather", event.toWeatherState() ? "rain" : "clear");
    }

    @FlowTrigger(eventType = "time_skip", eventClass = TimeSkipEvent.class, playerEvent = false)
    public void onTimeSkip(TimeSkipEvent event, Map<String, Object> vars) {
        vars.put("event.world_name", event.getWorld().getName());
        vars.put("event.old_time", event.getWorld().getTime());
        vars.put("event.new_time", event.getWorld().getTime() + event.getSkipAmount());
    }

    @FlowTrigger(eventType = "block_dispense", eventClass = BlockDispenseEvent.class, playerEvent = false)
    public void onBlockDispense(BlockDispenseEvent event, Map<String, Object> vars) {
        vars.put("event.block", event.getBlock());
        vars.put("event.location", event.getBlock().getLocation());
        vars.put("event.item", event.getItem());
    }

    @FlowTrigger(eventType = "block_fade", eventClass = BlockFadeEvent.class, playerEvent = false)
    public void onBlockFade(BlockFadeEvent event, Map<String, Object> vars) {
        vars.put("event.block", event.getBlock());
        vars.put("event.new_state", event.getNewState().getType());
        vars.put("event.location", event.getBlock().getLocation());
    }

    @FlowTrigger(eventType = "block_form", eventClass = BlockFormEvent.class, playerEvent = false)
    public void onBlockForm(BlockFormEvent event, Map<String, Object> vars) {
        vars.put("event.block", event.getBlock());
        vars.put("event.new_state", event.getNewState().getType());
        vars.put("event.location", event.getBlock().getLocation());
    }

    @FlowTrigger(eventType = "block_spread", eventClass = BlockSpreadEvent.class, playerEvent = false)
    public void onBlockSpread(BlockSpreadEvent event, Map<String, Object> vars) {
        vars.put("event.block", event.getBlock());
        vars.put("event.new_block", event.getNewState().getBlock());
        vars.put("event.location", event.getBlock().getLocation());
    }

    @FlowTrigger(eventType = "lightning_strike", eventClass = LightningStrikeEvent.class, playerEvent = false)
    public void onLightningStrike(LightningStrikeEvent event, Map<String, Object> vars) {
        vars.put("event.location", event.getLightning().getLocation());
        vars.put("event.struck_entity", event.getLightning());
        vars.put("event.world_name", event.getWorld().getName());
    }

    @FlowTrigger(eventType = "leaves_decay", eventClass = LeavesDecayEvent.class, playerEvent = false)
    public void onLeavesDecay(LeavesDecayEvent event, Map<String, Object> vars) {
        vars.put("event.block", event.getBlock());
        vars.put("event.location", event.getBlock().getLocation());
    }

    @FlowTrigger(eventType = "sign_change", eventClass = SignChangeEvent.class)
    public void onSignChange(SignChangeEvent event, Map<String, Object> vars) {
        vars.put("event.block", event.getBlock());
        vars.put("event.location", event.getBlock().getLocation());
        vars.put("event.lines", event.getLines());
    }

    @FlowTrigger(eventType = "inventory_open", eventClass = InventoryOpenEvent.class)
    public void onInventoryOpen(InventoryOpenEvent event, Map<String, Object> vars) {
        vars.put("event.inventory_title", event.getView().getTitle());
        vars.put("event.inventory_type", event.getInventory().getType().name());
        vars.put("event.location", null);
    }

    @FlowTrigger(eventType = "inventory_close", eventClass = InventoryCloseEvent.class)
    public void onInventoryClose(InventoryCloseEvent event, Map<String, Object> vars) {
        vars.put("event.inventory_title", event.getView().getTitle());
        vars.put("event.inventory_type", event.getInventory().getType().name());
        vars.put("event.location", null);
    }

    @FlowTrigger(eventType = "note_play", eventClass = NotePlayEvent.class, playerEvent = false)
    public void onNotePlay(NotePlayEvent event, Map<String, Object> vars) {
        vars.put("event.block", event.getBlock());
        vars.put("event.location", event.getBlock().getLocation());
        vars.put("event.instrument", (int) event.getInstrument().getType());
        vars.put("event.note", (int) event.getNote().getId());
    }

    @FlowTrigger(eventType = "block_piston_extend", eventClass = BlockPistonExtendEvent.class, playerEvent = false)
    public void onBlockPistonExtend(BlockPistonExtendEvent event, Map<String, Object> vars) {
        vars.put("event.block", event.getBlock());
        vars.put("event.location", event.getBlock().getLocation());
        vars.put("event.length", event.getLength());
    }

    @FlowTrigger(eventType = "block_piston_retract", eventClass = BlockPistonRetractEvent.class, playerEvent = false)
    public void onBlockPistonRetract(BlockPistonRetractEvent event, Map<String, Object> vars) {
        vars.put("event.block", event.getBlock());
        vars.put("event.location", event.getBlock().getLocation());
    }

    @FlowTrigger(eventType = "entity_combust", eventClass = EntityCombustEvent.class, playerEvent = false)
    public void onEntityCombust(EntityCombustEvent event, Map<String, Object> vars) {
        vars.put("event.entity", event.getEntity());
        vars.put("event.duration", event.getDuration());
        vars.put("event.cancelled", event.isCancelled());
    }

    @FlowTrigger(eventType = "structure_spawn", eventClass = BlockGrowEvent.class, playerEvent = false, ignoreCancelled = true)
    public void onStructureSpawn(BlockGrowEvent event, Map<String, Object> vars) {
        vars.put("event.block", event.getBlock());
        vars.put("event.location", event.getBlock().getLocation());
        vars.put("event.structure_type", event.getNewState().getType().name());
        vars.put("event.world_name", event.getBlock().getWorld().getName());
        vars.put("event.new_state", event.getBlock().getType().name());
    }

    @FlowTrigger(eventType = "player_command", eventClass = PlayerCommandPreprocessEvent.class, priority = EventPriority.LOWEST)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event, Map<String, Object> vars) {
        vars.put("event.command", event.getMessage());
        vars.put("event.is_cancelled", event.isCancelled());
    }

    @FlowTrigger(eventType = "server_command", nodeType = "event:server_command", eventClass = ServerCommandEvent.class, playerEvent = false, aliases = {"console_command"})
    public void onServerCommand(ServerCommandEvent event, Map<String, Object> vars) {
        vars.put("event.command", event.getCommand());
        vars.put("event.is_cancelled", event.isCancelled());
    }

    public Player extractCraftPlayer(CraftItemEvent event) {
        return (Player) event.getWhoClicked();
    }
}
