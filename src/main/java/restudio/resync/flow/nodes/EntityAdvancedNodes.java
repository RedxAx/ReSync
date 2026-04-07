package restudio.resync.flow.nodes;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowType;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.registry.DefineNode;
import restudio.resync.flow.registry.FlowPin;
import restudio.resync.flow.registry.NodeDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class EntityAdvancedNodes {

    private static final Map<String, BiConsumer<FlowContext, FlowNode>> LEGACY_EXECUTORS = new ConcurrentHashMap<>();
    private static volatile boolean initialized;

    private static void registerLegacyNodes(FlowRegistry registry) {
        registry.register("entity_mount", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Object mountEntityObj = ctx.getInputValue(node, "mount_entity", Entity.class, null);
            
            if (entityObj != null && mountEntityObj != null) {
                Entity entity = (Entity)entityObj;
                Entity mountEntity = (Entity)mountEntityObj;
                entity.teleport(mountEntity.getLocation());
                mountEntity.addPassenger(entity);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_dismount", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            
            if (entityObj != null) {
                Entity entity = (Entity)entityObj;
                if (entity.isInsideVehicle()) {
                    entity.leaveVehicle();
                }
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_ai_disable", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            
            if (entityObj != null && entityObj instanceof Mob) {
                ((Mob)entityObj).setAI(false);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_ai_enable", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            
            if (entityObj != null && entityObj instanceof Mob) {
                ((Mob)entityObj).setAI(true);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_set_no_damage", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Boolean noDamage = ctx.getInputValue(node, "no_damage", Boolean.class, false);
            
            if (entityObj != null) {
                ((Entity)entityObj).setInvulnerable(noDamage);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_set_silent", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Boolean silent = ctx.getInputValue(node, "silent", Boolean.class, false);
            
            if (entityObj != null) {
                ((Entity)entityObj).setSilent(silent);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_add_potion", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Object effectTypeObj = ctx.getInputValue(node, "effect_type", String.class, "SPEED");
            Integer duration = ctx.getInputValue(node, "duration", Integer.class, 200);
            Integer amplifier = ctx.getInputValue(node, "amplifier", Integer.class, 0);
            
            if (entityObj != null && entityObj instanceof LivingEntity && effectTypeObj != null) {
                PotionEffectType effectType = PotionEffectType.getByName(((String)effectTypeObj).toUpperCase());
                if (effectType != null) {
                    PotionEffect effect = new PotionEffect(effectType, duration, amplifier);
                    ((LivingEntity)entityObj).addPotionEffect(effect);
                } else {
                    Bukkit.getLogger().warning("[Flow] Invalid potion effect type: " + effectTypeObj);
                }
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_clear_potions", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            
            if (entityObj != null && entityObj instanceof LivingEntity) {
                LivingEntity living = (LivingEntity)entityObj;
                living.getActivePotionEffects().stream().map(PotionEffect::getType).forEach(living::removePotionEffect);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_leash", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Object holderEntityObj = ctx.getInputValue(node, "holder_entity", Entity.class, null);
            
            if (entityObj != null && holderEntityObj != null && entityObj instanceof LivingEntity && holderEntityObj instanceof LivingEntity) {
                ((LivingEntity)entityObj).setLeashHolder((LivingEntity)holderEntityObj);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_unleash", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            
            if (entityObj != null && entityObj instanceof LivingEntity) {
                ((LivingEntity)entityObj).setLeashHolder(null);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_set_custom_name", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Object nameObj = ctx.getInputValue(node, "name", String.class, "");
            
            if (entityObj != null) {
                Entity entity = (Entity)entityObj;
                entity.setCustomName((String)nameObj);
                entity.setCustomNameVisible(true);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_get_passengers", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            
            List<Entity> passengers = new ArrayList<>();
            if (entityObj != null) {
                passengers = ((Entity)entityObj).getPassengers();
            }
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "passengers_list", passengers);
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_get_vehicle", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            
            Entity vehicle = null;
            if (entityObj != null) {
                vehicle = ((Entity)entityObj).getVehicle();
            }
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "vehicle", vehicle);
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_set_fire_ticks", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Integer ticks = ctx.getInputValue(node, "ticks", Integer.class, 0);
            
            if (entityObj != null) {
                ((Entity)entityObj).setFireTicks(ticks);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_set_frozen", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Integer ticks = ctx.getInputValue(node, "ticks", Integer.class, 0);
            
            if (entityObj != null) {
                ((Entity)entityObj).setFreezeTicks(ticks);
            }
            
            ctx.triggerOutput("flow");
        });

        registry.register("entity_add_tag", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            String tag = ctx.getInputValue(node, "tag", String.class, "");
            if (entity != null && !tag.isEmpty()) {
                entity.addScoreboardTag(tag);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("entity_remove_tag", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            String tag = ctx.getInputValue(node, "tag", String.class, "");
            if (entity != null && !tag.isEmpty()) {
                entity.removeScoreboardTag(tag);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("entity_clear_tags", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            if (entity != null) {
                entity.getScoreboardTags().forEach(entity::removeScoreboardTag);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("entity_has_tag", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            String tag = ctx.getInputValue(node, "tag", String.class, "");
            boolean hasTag = entity != null && !tag.isEmpty() && entity.getScoreboardTags().contains(tag);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "has_tag", hasTag);
        });

        registry.register("entity_has_any_tag", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            List<String> tags = ctx.getInputValue(node, "tags", List.class, List.of());
            boolean hasAny = false;
            if (entity != null && tags != null && !tags.isEmpty()) {
                for (String tag : tags) {
                    if (entity.getScoreboardTags().contains(tag)) {
                        hasAny = true;
                        break;
                    }
                }
            }
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "has_any", hasAny);
        });

        registry.register("entity_has_all_tags", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            List<String> tags = ctx.getInputValue(node, "tags", List.class, List.of());
            boolean hasAll = entity != null && tags != null && !tags.isEmpty() && entity.getScoreboardTags().containsAll(tags);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "has_all", hasAll);
        });

        registry.register("entity_get_tags", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            List<String> tags = entity != null ? new ArrayList<>(entity.getScoreboardTags()) : List.of();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "tags", tags);
        });
    }

    public void registerNodes(FlowRegistry registry) {
        registerLegacyNodes(registry);
    }

    private static void ensureLegacyInitialized() {
        if (initialized) {
            return;
        }
        synchronized (EntityAdvancedNodes.class) {
            if (initialized) {
                return;
            }
            FlowRegistry legacyRegistry = new FlowRegistry();
            registerLegacyNodes(legacyRegistry);
            for (String type : legacyRegistry.getRegisteredTypes()) {
                LEGACY_EXECUTORS.put(type, legacyRegistry.getExecutor(type));
            }
            initialized = true;
        }
    }

    private void executeLegacy(String id, FlowContext ctx, FlowNode node) {
        ensureLegacyInitialized();
        BiConsumer<FlowContext, FlowNode> executor = LEGACY_EXECUTORS.get(id);
        if (executor == null) {
            if (ctx != null) {
                ctx.triggerOutput("flow");
            }
            return;
        }
        executor.accept(ctx, node);
    }

    @DefineNode(id = "entity_mount", displayName = "Entity Mount", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "mount_entity", dataType = FlowType.ENTITY)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void entityMount(FlowContext ctx, FlowNode node) { executeLegacy("entity_mount", ctx, node); }

    @DefineNode(id = "entity_dismount", displayName = "Entity Dismount", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void entityDismount(FlowContext ctx, FlowNode node) { executeLegacy("entity_dismount", ctx, node); }

    @DefineNode(id = "entity_ai_disable", displayName = "Entity AI Disable", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void entityAiDisable(FlowContext ctx, FlowNode node) { executeLegacy("entity_ai_disable", ctx, node); }

    @DefineNode(id = "entity_ai_enable", displayName = "Entity AI Enable", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void entityAiEnable(FlowContext ctx, FlowNode node) { executeLegacy("entity_ai_enable", ctx, node); }

    @DefineNode(id = "entity_set_no_damage", displayName = "Entity Set No Damage", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "no_damage", dataType = FlowType.BOOLEAN)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void entitySetNoDamage(FlowContext ctx, FlowNode node) { executeLegacy("entity_set_no_damage", ctx, node); }

    @DefineNode(id = "entity_set_silent", displayName = "Entity Set Silent", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "silent", dataType = FlowType.BOOLEAN)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void entitySetSilent(FlowContext ctx, FlowNode node) { executeLegacy("entity_set_silent", ctx, node); }

    @DefineNode(id = "entity_add_potion", displayName = "Entity Add Potion", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "effect_type", dataType = FlowType.STRING),
                    @FlowPin(name = "duration", dataType = FlowType.NUMBER),
                    @FlowPin(name = "amplifier", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void entityAddPotion(FlowContext ctx, FlowNode node) { executeLegacy("entity_add_potion", ctx, node); }

    @DefineNode(id = "entity_clear_potions", displayName = "Entity Clear Potions", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void entityClearPotions(FlowContext ctx, FlowNode node) { executeLegacy("entity_clear_potions", ctx, node); }

    @DefineNode(id = "entity_leash", displayName = "Entity Leash", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "holder_entity", dataType = FlowType.ENTITY)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void entityLeash(FlowContext ctx, FlowNode node) { executeLegacy("entity_leash", ctx, node); }

    @DefineNode(id = "entity_unleash", displayName = "Entity Unleash", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void entityUnleash(FlowContext ctx, FlowNode node) { executeLegacy("entity_unleash", ctx, node); }

    @DefineNode(id = "entity_set_custom_name", displayName = "Entity Set Custom Name", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "name", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void entitySetCustomName(FlowContext ctx, FlowNode node) { executeLegacy("entity_set_custom_name", ctx, node); }

    @DefineNode(id = "entity_get_passengers", displayName = "Entity Get Passengers", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "entity", dataType = FlowType.ENTITY)},
            outputs = {@FlowPin(name = "passengers_list", dataType = FlowType.LIST)})
    public void entityGetPassengers(FlowContext ctx, FlowNode node) { executeLegacy("entity_get_passengers", ctx, node); }

    @DefineNode(id = "entity_get_vehicle", displayName = "Entity Get Vehicle", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "entity", dataType = FlowType.ENTITY)},
            outputs = {@FlowPin(name = "vehicle", dataType = FlowType.ENTITY)})
    public void entityGetVehicle(FlowContext ctx, FlowNode node) { executeLegacy("entity_get_vehicle", ctx, node); }

    @DefineNode(id = "entity_set_fire_ticks", displayName = "Entity Set Fire Ticks", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "ticks", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void entitySetFireTicks(FlowContext ctx, FlowNode node) { executeLegacy("entity_set_fire_ticks", ctx, node); }

    @DefineNode(id = "entity_set_frozen", displayName = "Entity Set Frozen", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "ticks", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void entitySetFrozen(FlowContext ctx, FlowNode node) { executeLegacy("entity_set_frozen", ctx, node); }

    @DefineNode(id = "entity_add_tag", displayName = "Entity Add Tag", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "tag", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void entityAddTag(FlowContext ctx, FlowNode node) { executeLegacy("entity_add_tag", ctx, node); }

    @DefineNode(id = "entity_remove_tag", displayName = "Entity Remove Tag", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "tag", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void entityRemoveTag(FlowContext ctx, FlowNode node) { executeLegacy("entity_remove_tag", ctx, node); }

    @DefineNode(id = "entity_clear_tags", displayName = "Entity Clear Tags", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void entityClearTags(FlowContext ctx, FlowNode node) { executeLegacy("entity_clear_tags", ctx, node); }

    @DefineNode(id = "entity_has_tag", displayName = "Entity Has Tag", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "tag", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "has_tag", dataType = FlowType.BOOLEAN)})
    public void entityHasTag(FlowContext ctx, FlowNode node) { executeLegacy("entity_has_tag", ctx, node); }

    @DefineNode(id = "entity_has_any_tag", displayName = "Entity Has Any Tag", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "tags", dataType = FlowType.LIST)
            },
            outputs = {@FlowPin(name = "has_any", dataType = FlowType.BOOLEAN)})
    public void entityHasAnyTag(FlowContext ctx, FlowNode node) { executeLegacy("entity_has_any_tag", ctx, node); }

    @DefineNode(id = "entity_has_all_tags", displayName = "Entity Has All Tags", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "tags", dataType = FlowType.LIST)
            },
            outputs = {@FlowPin(name = "has_all", dataType = FlowType.BOOLEAN)})
    public void entityHasAllTags(FlowContext ctx, FlowNode node) { executeLegacy("entity_has_all_tags", ctx, node); }

    @DefineNode(id = "entity_get_tags", displayName = "Entity Get Tags", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "entity", dataType = FlowType.ENTITY)},
            outputs = {@FlowPin(name = "tags", dataType = FlowType.LIST)})
    public void entityGetTags(FlowContext ctx, FlowNode node) { executeLegacy("entity_get_tags", ctx, node); }
    
    private static String findNodeId(FlowContext ctx, FlowNode node) {
        for (var entry : ctx.getRuntime().getGraph().getNodes().entrySet()) {
            if (entry.getValue() == node) {
                return entry.getKey();
            }
        }
        return null;
    }
}
