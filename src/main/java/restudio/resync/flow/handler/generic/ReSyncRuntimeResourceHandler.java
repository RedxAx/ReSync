package restudio.resync.flow.handler.generic;

import com.google.gson.JsonObject;
import org.bukkit.Location;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowNpcHandle;
import restudio.flow.data.FlowOperationResult;
import restudio.flow.data.FlowResourceReference;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.modules.flow.FlowResourceRegistry;
import restudio.resync.modules.flow.FlowResourceMutationContext;
import restudio.resync.runtime.LootTableService;
import restudio.resync.runtime.NpcService;
import restudio.resync.runtime.ReSyncRuntimeContentAccess;
import restudio.resync.runtime.TradeProfileService;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public class ReSyncRuntimeResourceHandler implements NodeHandler {
    private static final Set<String> RESOURCE_OPERATIONS = Set.of("resource_discover", "resource_query", "resource_reference", "resource_get",
        "resource_validate", "resource_create", "resource_save", "resource_update", "resource_duplicate", "resource_delete", "resource_reload", "resource_apply");
    private static final Set<String> DOMAIN_ACTION_OPERATIONS = Set.of("loot_generate", "loot_give", "loot_fill_container", "trade_apply_trade_profile",
        "trade_open_trades", "trade_open_virtual_trades", "npc_spawn", "npc_despawn", "npc_open", "npc_set_profile", "npc_teleport");
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();
    private final FlowResourceRegistry resourceRegistry;

    public ReSyncRuntimeResourceHandler() {
        this(null);
    }

    public ReSyncRuntimeResourceHandler(FlowResourceRegistry resourceRegistry) {
        this.resourceRegistry = resourceRegistry;
        operations.put("loot_generate", (ctx, node) -> {
            LootTableService service = ReSyncRuntimeContentAccess.lootTables();
            String id = ctx.getInputValue(node, "loot_table", String.class, "");
            Player player = ctx.getInputValue(node, "player", Player.class, ctx.getPlayer());
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            FlowOperationResult<List<ItemStack>> result = lootResult(service, id, true,
                () -> service.generate(id, service.context(player, entity, location)));
            List<ItemStack> items = result.value() != null ? result.value() : List.of();
            ctx.setOutput(node, "items", items);
            setResult(ctx, node, result);
        });
        operations.put("loot_give", (ctx, node) -> {
            LootTableService service = ReSyncRuntimeContentAccess.lootTables();
            Player player = ctx.getInputValue(node, "player", Player.class, ctx.getPlayer());
            String id = ctx.getInputValue(node, "loot_table", String.class, "");
            FlowOperationResult<List<ItemStack>> result = lootResult(service, id, player != null, () -> service.give(player, id));
            List<ItemStack> items = result.value() != null ? result.value() : List.of();
            ctx.setOutput(node, "items", items);
            setResult(ctx, node, result);
        });
        operations.put("loot_fill_container", (ctx, node) -> {
            LootTableService service = ReSyncRuntimeContentAccess.lootTables();
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            Player player = ctx.getInputValue(node, "player", Player.class, ctx.getPlayer());
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            String id = ctx.getInputValue(node, "loot_table", String.class, "");
            Container container = location != null && location.getBlock().getState() instanceof Container found ? found : null;
            FlowOperationResult<List<ItemStack>> result = lootResult(service, id, container != null, () -> service != null && container != null
                ? service.fillContainer(container.getInventory(), id, service.context(player, entity, location))
                : List.of());
            List<ItemStack> items = result.value() != null ? result.value() : List.of();
            ctx.setOutput(node, "items", items);
            setResult(ctx, node, result);
        });
        operations.put("trade_apply_trade_profile", (ctx, node) -> {
            TradeProfileService service = ReSyncRuntimeContentAccess.tradeProfiles();
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            String id = ctx.getInputValue(node, "profile_id", String.class, "");
            FlowOperationResult<Boolean> result = tradeResult(service, id, entity instanceof Villager,
                () -> service != null && entity instanceof Villager villager && service.apply(villager, id));
            setResult(ctx, node, result);
        });
        operations.put("trade_open_trades", (ctx, node) -> {
            TradeProfileService service = ReSyncRuntimeContentAccess.tradeProfiles();
            Player player = ctx.getInputValue(node, "player", Player.class, ctx.getPlayer());
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            String id = ctx.getInputValue(node, "profile_id", String.class, "");
            FlowOperationResult<Boolean> result = tradeResult(service, id, player != null && entity instanceof Villager,
                () -> service != null && entity instanceof Villager villager && service.openTrades(player, villager, id));
            setResult(ctx, node, result);
        });
        operations.put("trade_open_virtual_trades", (ctx, node) -> {
            TradeProfileService service = ReSyncRuntimeContentAccess.tradeProfiles();
            Player player = ctx.getInputValue(node, "player", Player.class, ctx.getPlayer());
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            String id = ctx.getInputValue(node, "profile_id", String.class, "");
            FlowOperationResult<Boolean> result = tradeResult(service, id, player != null,
                () -> service != null && service.openVirtualTrades(player, entity, id));
            setResult(ctx, node, result);
        });
        operations.put("npc_spawn", (ctx, node) -> {
            NpcService service = ReSyncRuntimeContentAccess.npcs();
            String id = ctx.getInputValue(node, "npc_id", String.class, "");
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            FlowOperationResult<FlowNpcHandle> result = npcSpawnResult(service, id, location);
            ctx.setOutput(node, "handle", result.value());
            ctx.setOutput(node, "entity", service != null ? service.entity(id) : null);
            setResult(ctx, node, result);
        });
        operations.put("npc_despawn", (ctx, node) -> {
            NpcService service = ReSyncRuntimeContentAccess.npcs();
            String id = ctx.getInputValue(node, "npc_id", String.class, "");
            boolean active = service != null ? service.isActive(id) : false;
            FlowOperationResult<Boolean> result = npcResult(service, id, active,
                "NPC_NOT_ACTIVE", "NPC is not active", () -> service != null && service.despawn(id));
            setResult(ctx, node, result);
        });
        operations.put("npc_open", (ctx, node) -> {
            NpcService service = ReSyncRuntimeContentAccess.npcs();
            Player player = ctx.getInputValue(node, "player", Player.class, ctx.getPlayer());
            String id = ctx.getInputValue(node, "npc_id", String.class, "");
            FlowOperationResult<Boolean> result = npcResult(service, id, player != null, "INVALID_NPC_CONTEXT", "NPC player context is invalid",
                () -> service.open(player, id));
            setResult(ctx, node, result);
        });
        operations.put("npc_set_profile", (ctx, node) -> {
            NpcService service = ReSyncRuntimeContentAccess.npcs();
            String id = ctx.getInputValue(node, "npc_id", String.class, "");
            String profileId = ctx.getInputValue(node, "profile_id", String.class, "");
            FlowOperationResult<Boolean> result = npcResult(service, id, profileId != null && !profileId.isBlank(), "RESOURCE_ID_REQUIRED",
                "Trade profile ID is required", () -> service.setProfile(id, profileId));
            setResult(ctx, node, result);
        });
        operations.put("npc_lookup", (ctx, node) -> {
            NpcService service = ReSyncRuntimeContentAccess.npcs();
            String id = ctx.getInputValue(node, "npc_id", String.class, "");
            FlowOperationResult<Boolean> readiness = npcReadiness(service, id, service != null && service.isActive(id), "NPC_NOT_ACTIVE", "NPC is not active");
            FlowNpcHandle handle = readiness.success() && service != null ? service.handle(id) : null;
            FlowOperationResult<FlowNpcHandle> result = handle != null ? FlowOperationResult.success(handle)
                : readiness.success()
                    ? FlowOperationResult.failure("NPC_HANDLE_UNAVAILABLE", "NPC handle is unavailable", Map.of("npcId", id))
                    : FlowOperationResult.failure(readiness.errorCode(), readiness.message(), readiness.details());
            ctx.setOutput(node, "handle", handle);
            setResult(ctx, node, result);
        });
        operations.put("npc_list_active", (ctx, node) -> {
            NpcService service = ReSyncRuntimeContentAccess.npcs();
            List<FlowNpcHandle> handles = service != null ? service.activeHandles() : List.of();
            FlowOperationResult<List<FlowNpcHandle>> result = service != null ? FlowOperationResult.success(handles)
                : FlowOperationResult.failure("NPC_SERVICE_UNAVAILABLE", "NPC service is unavailable", Map.of());
            ctx.setOutput(node, "handles", handles);
            setResult(ctx, node, result);
        });
        operations.put("npc_is_active", (ctx, node) -> {
            NpcService service = ReSyncRuntimeContentAccess.npcs();
            String id = ctx.getInputValue(node, "npc_id", String.class, "");
            FlowOperationResult<Boolean> readiness = npcReadiness(service, id, true, "", "");
            boolean active = readiness.success() && service.isActive(id);
            FlowOperationResult<Boolean> result = readiness.success() ? FlowOperationResult.success(active) : readiness;
            ctx.setOutput(node, "active", active);
            ctx.setOutput(node, "handle", active ? service.handle(id) : null);
            setResult(ctx, node, result);
        });
        operations.put("npc_get_location", (ctx, node) -> {
            NpcService service = ReSyncRuntimeContentAccess.npcs();
            FlowNpcHandle handle = ctx.getInputValue(node, "handle", FlowNpcHandle.class, null);
            FlowOperationResult<Boolean> readiness = npcHandleReadiness(service, handle);
            Location location = readiness.success() ? service.location(handle.definitionId()) : null;
            FlowOperationResult<Location> result = location != null ? FlowOperationResult.success(location)
                : readiness.success()
                    ? FlowOperationResult.failure("NPC_LOCATION_UNAVAILABLE", "NPC location is unavailable", Map.of("npcId", handle.definitionId()))
                    : FlowOperationResult.failure(readiness.errorCode(), readiness.message(), readiness.details());
            ctx.setOutput(node, "location", location);
            setResult(ctx, node, result);
        });
        operations.put("npc_get_entity", (ctx, node) -> {
            NpcService service = ReSyncRuntimeContentAccess.npcs();
            FlowNpcHandle handle = ctx.getInputValue(node, "handle", FlowNpcHandle.class, null);
            FlowOperationResult<Boolean> readiness = npcHandleReadiness(service, handle);
            Entity entity = readiness.success() ? service.entity(handle.definitionId()) : null;
            boolean available = entity != null;
            FlowOperationResult<Boolean> result = readiness.success() ? FlowOperationResult.success(available) : readiness;
            ctx.setOutput(node, "entity", entity);
            ctx.setOutput(node, "available", available);
            setResult(ctx, node, result);
        });
        operations.put("npc_teleport", (ctx, node) -> {
            NpcService service = ReSyncRuntimeContentAccess.npcs();
            FlowNpcHandle handle = ctx.getInputValue(node, "handle", FlowNpcHandle.class, null);
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            FlowOperationResult<Boolean> readiness = npcHandleReadiness(service, handle);
            FlowOperationResult<FlowNpcHandle> result;
            if (!readiness.success()) {
                result = FlowOperationResult.failure(readiness.errorCode(), readiness.message(), readiness.details());
            } else if (location == null || location.getWorld() == null) {
                result = FlowOperationResult.failure("INVALID_NPC_CONTEXT", "NPC teleport location is invalid", Map.of("npcId", handle.definitionId()));
            } else if (!service.teleport(handle.definitionId(), location)) {
                result = FlowOperationResult.failure("NPC_OPERATION_FAILED", "NPC teleport failed", Map.of("npcId", handle.definitionId()));
            } else {
                result = FlowOperationResult.success(service.handle(handle.definitionId()));
            }
            ctx.setOutput(node, "handle", result.value());
            setResult(ctx, node, result);
        });
        operations.put("resource_discover", this::discoverResources);
        operations.put("resource_query", this::queryResources);
        operations.put("resource_reference", this::resolveResourceReference);
        operations.put("resource_get", this::getResource);
        operations.put("resource_validate", this::validateResource);
        operations.put("resource_create", this::createResource);
        operations.put("resource_save", this::saveResource);
        operations.put("resource_update", this::updateResource);
        operations.put("resource_duplicate", this::duplicateResource);
        operations.put("resource_delete", this::deleteResource);
        operations.put("resource_reload", this::reloadResource);
        operations.put("resource_apply", this::applyResource);
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("ReSyncRuntimeResourceHandler", this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op != null) {
            op.accept(ctx, node);
        } else {
            throw new IllegalArgumentException("Unknown runtime resource operation: " + operation);
        }
        if (RESOURCE_OPERATIONS.contains(operation) || DOMAIN_ACTION_OPERATIONS.contains(operation)) {
            if (!"resource_reference".equals(operation)) {
                Object success = ctx.getOutput(node, "success");
                ctx.triggerOutput(Boolean.TRUE.equals(success) ? "flow" : "failed");
            }
        } else {
            ctx.triggerOutput("flow");
        }
    }

    private void discoverResources(FlowContext ctx, FlowNode node) {
        String type = ctx.getInputValue(node, "resource_type", String.class, "");
        String query = ctx.getInputValue(node, "query", String.class, "");
        FlowOperationResult<List<FlowResourceReference>> result = resourceRegistry != null
            ? resourceRegistry.discover(type, query)
            : FlowOperationResult.failure("RESOURCE_AUTHORITY_UNAVAILABLE", "Resource authority is unavailable", Map.of("resourceType", type));
        ctx.setOutput(node, "references", result.value() != null ? result.value() : List.of());
        setResult(ctx, node, result);
    }

    private void queryResources(FlowContext ctx, FlowNode node) {
        String type = ctx.getInputValue(node, "resource_type", String.class, "");
        String query = ctx.getInputValue(node, "query", String.class, "");
        FlowOperationResult<List<FlowResourceReference>> result = resourceRegistry != null
            ? resourceRegistry.query(type, query)
            : FlowOperationResult.failure("RESOURCE_AUTHORITY_UNAVAILABLE", "Resource authority is unavailable", Map.of("resourceType", type));
        ctx.setOutput(node, "references", result.value() != null ? result.value() : List.of());
        setResult(ctx, node, result);
    }

    private void resolveResourceReference(FlowContext ctx, FlowNode node) {
        ResourceIdentity identity = resourceIdentity(ctx, node);
        FlowOperationResult<Object> resolution = resourceRegistry != null
            ? resourceRegistry.get(identity.type(), identity.id())
            : FlowOperationResult.failure("RESOURCE_AUTHORITY_UNAVAILABLE", "Resource authority is unavailable", Map.of("resourceType", identity.type()));
        FlowResourceReference reference = reference(identity, resolution.success());
        ctx.setOutput(node, "reference", reference);
        ctx.setOutput(node, "exists", resolution.success());
        FlowOperationResult<FlowResourceReference> result = resolution.success() ? FlowOperationResult.success(reference)
            : FlowOperationResult.failure(resolution.errorCode(), resolution.message(), resolution.details());
        setResult(ctx, node, result);
    }

    private void getResource(FlowContext ctx, FlowNode node) {
        ResourceIdentity identity = resourceIdentity(ctx, node);
        FlowOperationResult<Object> result = resourceRegistry != null
            ? resourceRegistry.get(identity.type(), identity.id())
            : FlowOperationResult.failure("RESOURCE_AUTHORITY_UNAVAILABLE", "Resource authority is unavailable", Map.of("resourceType", identity.type()));
        ctx.setOutput(node, "value", result.value());
        ctx.setOutput(node, "reference", reference(identity, result.success()));
        setResult(ctx, node, result);
    }

    private void validateResource(FlowContext ctx, FlowNode node) {
        String type = ctx.getInputValue(node, "resource_type", String.class, "");
        Object value = ctx.getInputValue(node, "value");
        FlowOperationResult<FlowResourceReference> result = resourceRegistry != null
            ? resourceRegistry.validate(type, value)
            : FlowOperationResult.failure("RESOURCE_AUTHORITY_UNAVAILABLE", "Resource authority is unavailable", Map.of("resourceType", type));
        ctx.setOutput(node, "reference", result.value());
        setResult(ctx, node, result);
    }

    private void saveResource(FlowContext ctx, FlowNode node) {
        String type = ctx.getInputValue(node, "resource_type", String.class, "");
        Object value = ctx.getInputValue(node, "value");
        FlowOperationResult<FlowResourceReference> result = resourceRegistry != null
            ? resourceRegistry.save(type, value, mutationContext(ctx, node))
            : FlowOperationResult.failure("RESOURCE_AUTHORITY_UNAVAILABLE", "Resource authority is unavailable", Map.of("resourceType", type));
        ctx.setOutput(node, "reference", result.value());
        setResult(ctx, node, result);
    }

    private void createResource(FlowContext ctx, FlowNode node) {
        String type = ctx.getInputValue(node, "resource_type", String.class, "");
        Object value = ctx.getInputValue(node, "value");
        FlowOperationResult<FlowResourceReference> result = resourceRegistry != null
            ? resourceRegistry.create(type, value, mutationContext(ctx, node))
            : FlowOperationResult.failure("RESOURCE_AUTHORITY_UNAVAILABLE", "Resource authority is unavailable", Map.of("resourceType", type));
        ctx.setOutput(node, "reference", result.value());
        setResult(ctx, node, result);
    }

    private void updateResource(FlowContext ctx, FlowNode node) {
        String type = ctx.getInputValue(node, "resource_type", String.class, "");
        Object value = ctx.getInputValue(node, "value");
        FlowOperationResult<FlowResourceReference> result = resourceRegistry != null
            ? resourceRegistry.update(type, value, mutationContext(ctx, node))
            : FlowOperationResult.failure("RESOURCE_AUTHORITY_UNAVAILABLE", "Resource authority is unavailable", Map.of("resourceType", type));
        ctx.setOutput(node, "reference", result.value());
        setResult(ctx, node, result);
    }

    private void duplicateResource(FlowContext ctx, FlowNode node) {
        ResourceIdentity identity = resourceIdentity(ctx, node);
        String targetId = ctx.getInputValue(node, "target_id", String.class, "");
        FlowOperationResult<FlowResourceReference> result = resourceRegistry != null
            ? resourceRegistry.duplicate(identity.type(), identity.id(), targetId, mutationContext(ctx, node))
            : FlowOperationResult.failure("RESOURCE_AUTHORITY_UNAVAILABLE", "Resource authority is unavailable", Map.of("resourceType", identity.type()));
        ctx.setOutput(node, "reference", result.value());
        setResult(ctx, node, result);
    }

    private void deleteResource(FlowContext ctx, FlowNode node) {
        ResourceIdentity identity = resourceIdentity(ctx, node);
        boolean preview = ctx.getInputValue(node, "preview", Boolean.class, true);
        FlowOperationResult<FlowResourceReference> result = resourceRegistry != null
            ? preview
                ? resourceRegistry.previewDelete(identity.type(), identity.id(), mutationContext(ctx, node))
                : resourceRegistry.delete(identity.type(), identity.id(), mutationContext(ctx, node))
            : FlowOperationResult.failure("RESOURCE_AUTHORITY_UNAVAILABLE", "Resource authority is unavailable", Map.of("resourceType", identity.type()));
        ctx.setOutput(node, "reference", result.value() != null ? result.value() : reference(identity, false));
        ctx.setOutput(node, "preview", preview);
        ctx.setOutput(node, "would_delete", Boolean.TRUE.equals(result.details().get("wouldDelete")));
        setResult(ctx, node, result);
    }

    private void reloadResource(FlowContext ctx, FlowNode node) {
        ResourceIdentity identity = resourceIdentity(ctx, node);
        FlowOperationResult<Object> result = resourceRegistry != null
            ? resourceRegistry.reload(identity.type(), identity.id(), mutationContext(ctx, node))
            : FlowOperationResult.failure("RESOURCE_AUTHORITY_UNAVAILABLE", "Resource authority is unavailable", Map.of("resourceType", identity.type()));
        ctx.setOutput(node, "value", result.value());
        setResult(ctx, node, result);
    }

    private void applyResource(FlowContext ctx, FlowNode node) {
        ResourceIdentity identity = resourceIdentity(ctx, node);
        FlowOperationResult<Object> result = resourceRegistry != null
            ? resourceRegistry.apply(identity.type(), identity.id(), ctx, mutationContext(ctx, node))
            : FlowOperationResult.failure("RESOURCE_AUTHORITY_UNAVAILABLE", "Resource authority is unavailable", Map.of("resourceType", identity.type()));
        ctx.setOutput(node, "value", result.value());
        setResult(ctx, node, result);
    }

    private void setResult(FlowContext ctx, FlowNode node, FlowOperationResult<?> result) {
        ctx.setOutput(node, "result", result);
        ctx.setOutput(node, "success", result.success());
        ctx.setOutput(node, "error_code", result.errorCode());
        ctx.setOutput(node, "message", result.message());
        ctx.setOutput(node, "changed", Boolean.TRUE.equals(result.details().get("changed")));
        ctx.setOutput(node, "created", Boolean.TRUE.equals(result.details().get("created")));
        ctx.setOutput(node, "updated", Boolean.TRUE.equals(result.details().get("updated")));
        ctx.setOutput(node, "deleted", Boolean.TRUE.equals(result.details().get("deleted")));
        ctx.setOutput(node, "reloaded", Boolean.TRUE.equals(result.details().get("reloaded")));
        ctx.setOutput(node, "refresh_succeeded", !result.details().containsKey("refreshSucceeded") || Boolean.TRUE.equals(result.details().get("refreshSucceeded")));
    }

    private FlowOperationResult<Boolean> tradeResult(TradeProfileService service, String id, boolean validContext, BooleanSupplier operation) {
        if (service == null) {
            return FlowOperationResult.failure("TRADE_SERVICE_UNAVAILABLE", "Trade service is unavailable", Map.of("profileId", id));
        }
        if (id == null || id.isBlank()) {
            return FlowOperationResult.failure("RESOURCE_ID_REQUIRED", "Trade profile ID is required", Map.of());
        }
        if (service.get(id) == null) {
            return FlowOperationResult.failure("RESOURCE_NOT_FOUND", "Trade profile not found: " + id, Map.of("profileId", id));
        }
        if (!validContext) {
            return FlowOperationResult.failure("INVALID_TRADE_CONTEXT", "Trade operation context is invalid", Map.of("profileId", id));
        }
        boolean success = operation.getAsBoolean();
        return success ? FlowOperationResult.success(true)
            : FlowOperationResult.failure("TRADE_OPERATION_FAILED", "Trade operation failed", Map.of("profileId", id));
    }

    private FlowOperationResult<List<ItemStack>> lootResult(LootTableService service, String id, boolean validContext,
                                                             Supplier<List<ItemStack>> operation) {
        if (service == null) {
            return FlowOperationResult.failure("LOOT_SERVICE_UNAVAILABLE", "Loot service is unavailable", Map.of("lootTableId", safe(id)));
        }
        if (id == null || id.isBlank()) {
            return FlowOperationResult.failure("RESOURCE_ID_REQUIRED", "Loot table ID is required", Map.of());
        }
        JsonObject definition = service.get(id);
        if (definition == null) {
            return FlowOperationResult.failure("RESOURCE_NOT_FOUND", "Loot table not found: " + id, Map.of("lootTableId", id));
        }
        if (!enabled(definition)) {
            return FlowOperationResult.failure("RESOURCE_DISABLED", "Loot table is disabled: " + id, Map.of("lootTableId", id));
        }
        if (!validContext) {
            return FlowOperationResult.failure("INVALID_LOOT_CONTEXT", "Loot operation context is invalid", Map.of("lootTableId", id));
        }
        try {
            List<ItemStack> items = operation.get();
            return FlowOperationResult.success(items != null ? List.copyOf(items) : List.of());
        } catch (RuntimeException exception) {
            return FlowOperationResult.failure("LOOT_OPERATION_FAILED", failureMessage(exception, "Loot operation failed"), Map.of("lootTableId", id));
        }
    }

    private FlowOperationResult<FlowNpcHandle> npcSpawnResult(NpcService service, String id, Location location) {
        FlowOperationResult<Boolean> readiness = npcReadiness(service, id, location != null && location.getWorld() != null,
            "INVALID_NPC_CONTEXT", "NPC spawn location is invalid");
        if (!readiness.success()) {
            return FlowOperationResult.failure(readiness.errorCode(), readiness.message(), readiness.details());
        }
        if (service.requiresPlayerRuntime(id) && !service.playerNpcAvailable()) {
            return FlowOperationResult.failure("PLAYER_NPC_RUNTIME_UNAVAILABLE", service.playerNpcUnavailableReason(), Map.of("npcId", id));
        }
        try {
            service.spawn(id, location);
            FlowNpcHandle handle = service.handle(id);
            return handle != null ? FlowOperationResult.success(handle)
                : FlowOperationResult.failure("NPC_OPERATION_FAILED", "NPC spawn failed", Map.of("npcId", id));
        } catch (RuntimeException exception) {
            return FlowOperationResult.failure("NPC_OPERATION_FAILED", failureMessage(exception, "NPC spawn failed"), Map.of("npcId", id));
        }
    }

    private FlowOperationResult<Boolean> npcResult(NpcService service, String id, boolean validContext, String contextCode,
                                                    String contextMessage, BooleanSupplier operation) {
        FlowOperationResult<Boolean> readiness = npcReadiness(service, id, validContext, contextCode, contextMessage);
        if (!readiness.success()) {
            return readiness;
        }
        try {
            return operation.getAsBoolean() ? FlowOperationResult.success(true)
                : FlowOperationResult.failure("NPC_OPERATION_FAILED", "NPC operation failed", Map.of("npcId", id));
        } catch (RuntimeException exception) {
            return FlowOperationResult.failure("NPC_OPERATION_FAILED", failureMessage(exception, "NPC operation failed"), Map.of("npcId", id));
        }
    }

    private FlowOperationResult<Boolean> npcReadiness(NpcService service, String id, boolean validContext, String contextCode, String contextMessage) {
        if (service == null) {
            return FlowOperationResult.failure("NPC_SERVICE_UNAVAILABLE", "NPC service is unavailable", Map.of("npcId", safe(id)));
        }
        if (id == null || id.isBlank()) {
            return FlowOperationResult.failure("RESOURCE_ID_REQUIRED", "NPC definition ID is required", Map.of());
        }
        JsonObject definition = service.get(id);
        if (definition == null) {
            return FlowOperationResult.failure("RESOURCE_NOT_FOUND", "NPC definition not found: " + id, Map.of("npcId", id));
        }
        if (!enabled(definition)) {
            return FlowOperationResult.failure("RESOURCE_DISABLED", "NPC definition is disabled: " + id, Map.of("npcId", id));
        }
        return validContext ? FlowOperationResult.success(true) : FlowOperationResult.failure(contextCode, contextMessage, Map.of("npcId", id));
    }

    private FlowOperationResult<Boolean> npcHandleReadiness(NpcService service, FlowNpcHandle handle) {
        if (service == null) {
            return FlowOperationResult.failure("NPC_SERVICE_UNAVAILABLE", "NPC service is unavailable", Map.of());
        }
        if (handle == null || handle.definitionId().isBlank()) {
            return FlowOperationResult.failure("NPC_HANDLE_REQUIRED", "NPC handle is required", Map.of());
        }
        if (!service.isActive(handle.definitionId())) {
            return FlowOperationResult.failure("NPC_NOT_ACTIVE", "NPC is not active", Map.of("npcId", handle.definitionId()));
        }
        return FlowOperationResult.success(true);
    }

    private boolean enabled(JsonObject definition) {
        return !definition.has("enabled") || !definition.get("enabled").isJsonPrimitive()
            || !definition.getAsJsonPrimitive("enabled").isBoolean() || definition.get("enabled").getAsBoolean();
    }

    private String safe(String value) {
        return value != null ? value : "";
    }

    private String failureMessage(RuntimeException exception, String fallback) {
        return exception.getMessage() != null && !exception.getMessage().isBlank() ? exception.getMessage() : fallback;
    }

    private FlowResourceMutationContext mutationContext(FlowContext context, FlowNode node) {
        return new FlowResourceMutationContext("flow", context.getFlowId(), context.resolveNodeId(node), "server");
    }

    private ResourceIdentity resourceIdentity(FlowContext ctx, FlowNode node) {
        Object rawReference = ctx.getInputValue(node, "reference");
        if (rawReference instanceof FlowResourceReference reference) {
            return new ResourceIdentity(reference.kind(), reference.id());
        }
        if (rawReference instanceof Map<?, ?> map) {
            Object kind = map.get("kind");
            Object id = map.get("id");
            return new ResourceIdentity(kind != null ? kind.toString() : "", id != null ? id.toString() : "");
        }
        String type = ctx.getInputValue(node, "resource_type", String.class, "");
        String id = ctx.getInputValue(node, "resource_id", String.class, rawReference != null ? rawReference.toString() : "");
        return new ResourceIdentity(type, id);
    }

    private FlowResourceReference reference(ResourceIdentity identity, boolean available) {
        return resourceRegistry != null ? resourceRegistry.reference(identity.type(), identity.id(), available)
            : new FlowResourceReference(identity.type(), identity.id(), "unresolved", false, Map.of());
    }

    private record ResourceIdentity(String type, String id) {
    }
}
