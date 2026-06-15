package restudio.resync.flow.handler.generic;

import org.bukkit.Location;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.runtime.LootTableService;
import restudio.resync.runtime.NpcService;
import restudio.resync.runtime.ReSyncRuntimeContentAccess;
import restudio.resync.runtime.VillageProfileService;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class ReSyncRuntimeResourceHandler implements NodeHandler {
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public ReSyncRuntimeResourceHandler() {
        operations.put("loot_generate", (ctx, node) -> {
            LootTableService service = ReSyncRuntimeContentAccess.lootTables();
            String id = ctx.getInputValue(node, "loot_table", String.class, "");
            Player player = ctx.getInputValue(node, "player", Player.class, ctx.getPlayer());
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            List<ItemStack> items = service != null ? service.generate(id, service.context(player, entity, location)) : List.of();
            ctx.setOutput(node, "items", items);
            ctx.setOutput(node, "success", !items.isEmpty());
        });
        operations.put("loot_give", (ctx, node) -> {
            LootTableService service = ReSyncRuntimeContentAccess.lootTables();
            Player player = ctx.getInputValue(node, "player", Player.class, ctx.getPlayer());
            String id = ctx.getInputValue(node, "loot_table", String.class, "");
            List<ItemStack> items = service != null ? service.give(player, id) : List.of();
            ctx.setOutput(node, "items", items);
            ctx.setOutput(node, "success", player != null && !items.isEmpty());
        });
        operations.put("loot_fill_container", (ctx, node) -> {
            LootTableService service = ReSyncRuntimeContentAccess.lootTables();
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            Player player = ctx.getInputValue(node, "player", Player.class, ctx.getPlayer());
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            String id = ctx.getInputValue(node, "loot_table", String.class, "");
            List<ItemStack> items = List.of();
            if (service != null && location != null && location.getBlock().getState() instanceof Container container) {
                items = service.fillContainer(container.getInventory(), id, service.context(player, entity, location));
            }
            ctx.setOutput(node, "items", items);
            ctx.setOutput(node, "success", !items.isEmpty());
        });
        operations.put("village_apply_trade_profile", (ctx, node) -> {
            VillageProfileService service = ReSyncRuntimeContentAccess.villageProfiles();
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            String id = ctx.getInputValue(node, "profile_id", String.class, "");
            boolean success = service != null && entity instanceof Villager villager && service.apply(villager, id);
            ctx.setOutput(node, "success", success);
        });
        operations.put("village_open_trades", (ctx, node) -> {
            VillageProfileService service = ReSyncRuntimeContentAccess.villageProfiles();
            Player player = ctx.getInputValue(node, "player", Player.class, ctx.getPlayer());
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            String id = ctx.getInputValue(node, "profile_id", String.class, "");
            boolean success = service != null && entity instanceof Villager villager && service.openTrades(player, villager, id);
            ctx.setOutput(node, "success", success);
        });
        operations.put("npc_spawn", (ctx, node) -> {
            NpcService service = ReSyncRuntimeContentAccess.npcs();
            String id = ctx.getInputValue(node, "npc_id", String.class, "");
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            Entity entity = service != null ? service.spawn(id, location) : null;
            ctx.setOutput(node, "entity", entity);
            ctx.setOutput(node, "success", service != null && service.isActive(id));
        });
        operations.put("npc_despawn", (ctx, node) -> {
            NpcService service = ReSyncRuntimeContentAccess.npcs();
            String id = ctx.getInputValue(node, "npc_id", String.class, "");
            boolean success = service != null && service.despawn(id);
            ctx.setOutput(node, "success", success);
        });
        operations.put("npc_open", (ctx, node) -> {
            NpcService service = ReSyncRuntimeContentAccess.npcs();
            Player player = ctx.getInputValue(node, "player", Player.class, ctx.getPlayer());
            String id = ctx.getInputValue(node, "npc_id", String.class, "");
            boolean success = service != null && service.open(player, id);
            ctx.setOutput(node, "success", success);
        });
        operations.put("npc_set_profile", (ctx, node) -> {
            NpcService service = ReSyncRuntimeContentAccess.npcs();
            String id = ctx.getInputValue(node, "npc_id", String.class, "");
            String profileId = ctx.getInputValue(node, "profile_id", String.class, "");
            boolean success = service != null && service.setProfile(id, profileId);
            ctx.setOutput(node, "success", success);
        });
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
        }
        ctx.triggerOutput("flow");
    }
}
