package restudio.resync.qa;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import restudio.flow.data.CustomContentDefinition;
import restudio.flow.data.FlowNpcHandle;
import restudio.resync.customcontent.CustomContentService;
import restudio.resync.customcontent.CustomContentStorage;
import restudio.resync.network.paper.PaperPlayerDataMutationAdmission;
import restudio.resync.runtime.NpcService;
import restudio.resync.runtime.PlayerNpcRuntime;
import restudio.resync.server.ProtocolRequestAuthority;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

public final class QaGameAdapter {
    private static final int MAX_NEARBY_ENTITIES = 64;
    private static final Map<String, Object> DESCRIPTION = Map.of("operations", List.of(
        operation("game.inspect", "Inspect online players, a player's inventory, nearby entities and active NPCs", List.of(), List.of("playerId", "radius")),
        operation("custom.inspect", "Inspect a committed custom content definition and its available provider", List.of("contentId"), List.of()),
        operation("custom.item.inspect", "Inspect the actual item and custom identity in one player inventory slot", List.of("playerId"), List.of("slot")),
        operation("custom.item.give", "Create an item through its content provider and put it in an empty player slot", List.of("contentId", "playerId"), List.of("slot", "amount")),
        operation("custom.item.remove", "Clear one player slot only when its custom identity matches contentId", List.of("contentId", "playerId"), List.of("slot")),
        operation("custom.block.inspect", "Inspect the material and custom identity at a loaded block position", List.of("worldId", "x", "y", "z"), List.of()),
        operation("custom.block.place", "Place a tagged Vanilla custom block at an empty loaded block position", List.of("contentId", "worldId", "x", "y", "z"), List.of()),
        operation("custom.block.remove", "Remove a tagged Vanilla custom block only when its identity matches contentId", List.of("contentId", "worldId", "x", "y", "z"), List.of()),
        operation("npc.inspect", "Inspect a stored NPC definition and its actual live entity or packet instance", List.of("npcId"), List.of()),
        operation("npc.spawn", "Spawn a stored NPC through its existing service at a loaded position", List.of("npcId", "worldId", "x", "y", "z"), List.of("yaw", "pitch")),
        operation("npc.remove", "Despawn an NPC through its existing service and remove its saved instance", List.of("npcId"), List.of()),
        operation("npc.interact", "Dispatch a right-click fixture through the live NPC service within six blocks", List.of("npcId", "playerId"), List.of())),
        "permission", "resync.qa", "thread", "Server thread", "completion", "The service call and fixture snapshot have completed",
        "runtimeHooks", "Asynchronous runtime hooks are not awaited", "resourceWrites", "Use the typed resource QA operations",
        "limits", Map.of("itemAmount", 64, "inventorySlots", "0 to 35", "nearbyRadius", 32, "nearbyEntities", MAX_NEARBY_ENTITIES));
    private final Plugin plugin;
    private final Supplier<CustomContentStorage> storage;
    private final Supplier<CustomContentService> content;
    private final Supplier<NpcService> npcs;
    private final Supplier<PlayerNpcRuntime> playerNpcs;

    public QaGameAdapter(Plugin plugin, Supplier<CustomContentStorage> storage, Supplier<CustomContentService> content,
                         Supplier<NpcService> npcs, Supplier<PlayerNpcRuntime> playerNpcs) {
        this.plugin = Objects.requireNonNull(plugin, "QA plugin is required");
        this.storage = Objects.requireNonNull(storage, "Custom content storage supplier is required");
        this.content = Objects.requireNonNull(content, "Custom content service supplier is required");
        this.npcs = Objects.requireNonNull(npcs, "NPC service supplier is required");
        this.playerNpcs = Objects.requireNonNull(playerNpcs, "Player NPC runtime supplier is required");
    }

    public Map<String, Object> describe() {
        return DESCRIPTION;
    }

    public CompletionStage<Map<String, Object>> invoke(CommandSender actor, String operation, Map<String, Object> input) {
        try {
            if (ProtocolRequestAuthority.trustedOperatorId(actor) == null) {
                throw new SecurityException("QA requires permission and admission on the server thread");
            }
            if (!plugin.isEnabled()) throw new IllegalStateException("Game QA is unavailable while the plugin is stopped");
            Map<String, Object> request = Objects.requireNonNull(input, "QA input is required");
            Map<String, Object> result = switch (Objects.requireNonNull(operation, "QA operation is required")) {
                case "game.inspect" -> inspectGame(actor, request);
                case "custom.inspect" -> inspectContent(request);
                case "custom.item.inspect" -> inspectSlot(request);
                case "custom.item.give" -> giveItem(request);
                case "custom.item.remove" -> removeItem(request);
                case "custom.block.inspect" -> inspectBlock(block(request));
                case "custom.block.place" -> placeBlock(request);
                case "custom.block.remove" -> removeBlock(request);
                case "npc.inspect" -> inspectNpc(text(request, "npcId"));
                case "npc.spawn" -> spawnNpc(request);
                case "npc.remove" -> removeNpc(request);
                case "npc.interact" -> interactNpc(request);
                default -> throw new IllegalArgumentException("Unknown game QA operation: " + operation);
            };
            return CompletableFuture.completedFuture(result);
        } catch (RuntimeException | Error failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private Map<String, Object> inspectGame(CommandSender actor, Map<String, Object> input) {
        List<Map<String, Object>> players = Bukkit.getOnlinePlayers().stream().sorted(Comparator.comparing(Player::getName))
            .limit(64).map(player -> Map.<String, Object>of("playerId", player.getUniqueId().toString(), "name", player.getName(),
                "location", location(player.getLocation()))).toList();
        NpcService service = npcs.get();
        PlayerNpcRuntime packets = playerNpcs.get();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("players", players);
        result.put("onlinePlayerCount", Bukkit.getOnlinePlayers().size());
        result.put("customContentAvailable", storage.get() != null && content.get() != null);
        result.put("npcServiceAvailable", service != null);
        result.put("playerNpcAvailable", packets != null && packets.available());
        result.put("playerNpcReason", packets == null ? "Player NPC runtime is unavailable" : packets.unavailableReason());
        result.put("npcs", service == null ? List.of() : service.activeHandles().stream().limit(128).map(QaGameAdapter::handle).toList());
        Player selected = input.containsKey("playerId") ? player(input) : actor instanceof Player player ? player : null;
        if (selected != null) {
            List<Map<String, Object>> inventory = new ArrayList<>();
            for (int slot = 0; slot < selected.getInventory().getSize(); slot++) {
                ItemStack stack = selected.getInventory().getItem(slot);
                if (stack != null && !stack.getType().isAir()) {
                    Map<String, Object> value = new LinkedHashMap<>(item(stack));
                    value.put("slot", slot);
                    inventory.add(Map.copyOf(value));
                }
            }
            double radius = input.containsKey("radius") ? decimal(input, "radius") : 8.0;
            if (radius < 0 || radius > 32) throw new IllegalArgumentException("Nearby radius must be from 0 to 32 blocks");
            Location origin = selected.getLocation();
            List<Map<String, Object>> nearby = selected.getNearbyEntities(radius, radius, radius).stream()
                .filter(entity -> entity.getLocation().distanceSquared(origin) <= radius * radius)
                .sorted(Comparator.comparingDouble(entity -> entity.getLocation().distanceSquared(origin)))
                .limit(MAX_NEARBY_ENTITIES).map(QaGameAdapter::entity).toList();
            result.put("player", Map.of("playerId", selected.getUniqueId().toString(), "name", selected.getName(),
                "location", location(origin), "heldSlot", selected.getInventory().getHeldItemSlot(),
                "inventory", List.copyOf(inventory), "nearbyEntities", nearby));
        }
        return Map.copyOf(result);
    }

    private Map<String, Object> inspectContent(Map<String, Object> input) {
        String id = text(input, "contentId");
        CustomContentDefinition definition = definition(id);
        CustomContentService service = content();
        return Map.of("contentId", id, "type", definition.getType(), "enabled", definition.isEnabled(),
            "provider", definition.getProvider(), "providerAvailable", service.isProviderAvailable(definition.getProvider()),
            "material", definition.getMaterial(), "hasGraph", definition.getGraph() != null);
    }

    private Map<String, Object> inspectSlot(Map<String, Object> input) {
        Player player = player(input);
        int slot = slot(input, player);
        return Map.of("playerId", player.getUniqueId().toString(), "slot", slot, "item", item(player.getInventory().getItem(slot)));
    }

    private Map<String, Object> giveItem(Map<String, Object> input) {
        Player player = player(input);
        String id = text(input, "contentId");
        CustomContentDefinition definition = definition(id);
        CustomContentService service = content();
        if (!definition.isEnabled() || !service.canCreateAuthoritativeItem(definition)) {
            throw new IllegalArgumentException("Custom content is disabled or its item provider is unavailable");
        }
        int amount = input.containsKey("amount") ? integer(input, "amount") : 1;
        if (amount < 1 || amount > 64) throw new IllegalArgumentException("Item amount must be from 1 to 64");
        int slot = slot(input, player);
        PaperPlayerDataMutationAdmission.shared().mutatePlayer("qa-custom-item-give:" + player.getUniqueId(), player, () -> {
            ItemStack existing = player.getInventory().getItem(slot);
            if (existing != null && !existing.getType().isAir()) throw new IllegalArgumentException("The target inventory slot must be empty");
            ItemStack stack = service.createItem(id, amount);
            if (stack == null || stack.getType().isAir() || stack.getAmount() != amount || amount > stack.getMaxStackSize()
                || !id.equals(service.identifyItem(stack))) {
                throw new IllegalArgumentException("The provider did not create a valid item with the requested custom identity");
            }
            player.getInventory().setItem(slot, stack);
        });
        return inspectSlot(input);
    }

    private Map<String, Object> removeItem(Map<String, Object> input) {
        Player player = player(input);
        String id = text(input, "contentId");
        int slot = slot(input, player);
        CustomContentService service = content();
        PaperPlayerDataMutationAdmission.shared().mutatePlayer("qa-custom-item-remove:" + player.getUniqueId(), player, () -> {
            if (!id.equals(service.identifyItem(player.getInventory().getItem(slot)))) {
                throw new IllegalArgumentException("The inventory slot does not contain the requested custom item");
            }
            player.getInventory().setItem(slot, null);
        });
        return inspectSlot(input);
    }

    private Map<String, Object> placeBlock(Map<String, Object> input) {
        CustomContentDefinition definition = vanillaBlock(text(input, "contentId"));
        Block block = block(input);
        CustomContentService service = content();
        if (!block.getType().isAir() || service.identifyBlock(block.getLocation()) != null) {
            throw new IllegalArgumentException("The target block position must be empty and untagged");
        }
        Material material = Material.matchMaterial(definition.getMaterial());
        if (material == null || !material.isBlock() || material.isAir()) throw new IllegalArgumentException("Custom block material must be a non-air block");
        BlockData previous = block.getBlockData();
        block.setType(material, false);
        try {
            service.markPlacedBlock(block.getLocation(), definition);
        } catch (RuntimeException | Error failure) {
            try {
                block.setBlockData(previous, false);
            } catch (RuntimeException | Error rollback) {
                failure.addSuppressed(rollback);
            }
            throw failure;
        }
        return inspectBlock(block);
    }

    private Map<String, Object> removeBlock(Map<String, Object> input) {
        CustomContentDefinition definition = vanillaBlock(text(input, "contentId"));
        Block block = block(input);
        CustomContentService service = content();
        if (!definition.getId().equals(service.identifyBlock(block.getLocation()))) {
            throw new IllegalArgumentException("The block position does not contain the requested custom block");
        }
        service.clearPlacedBlock(block.getLocation());
        try {
            block.setType(Material.AIR, false);
        } catch (RuntimeException | Error failure) {
            try {
                service.markPlacedBlock(block.getLocation(), definition);
            } catch (RuntimeException | Error rollback) {
                failure.addSuppressed(rollback);
            }
            throw failure;
        }
        return inspectBlock(block);
    }

    private Map<String, Object> inspectBlock(Block block) {
        String id = content().identifyBlock(block.getLocation());
        return Map.of("location", location(block.getLocation()), "material", block.getType().getKey().toString(),
            "blockData", block.getBlockData().getAsString(), "contentId", id == null ? "" : id);
    }

    private Map<String, Object> inspectNpc(String id) {
        NpcService service = npcs();
        FlowNpcHandle handle = service.handle(id);
        return Map.of("npcId", id, "definitionAvailable", service.get(id) != null, "active", service.isActive(id),
            "handle", handle == null ? Map.of() : handle(handle), "playerNpcAvailable", service.playerNpcAvailable(),
            "playerNpcReason", service.playerNpcUnavailableReason());
    }

    private Map<String, Object> spawnNpc(Map<String, Object> input) {
        String id = text(input, "npcId");
        NpcService service = npcs();
        if (service.get(id) == null) throw new IllegalArgumentException("The stored NPC definition is unavailable");
        service.spawn(id, position(input));
        if (!service.isActive(id)) throw new IllegalStateException("NPC spawn failed: " + service.spawnFailureReason(id));
        return inspectNpc(id);
    }

    private Map<String, Object> removeNpc(Map<String, Object> input) {
        String id = text(input, "npcId");
        boolean removed = npcs().despawn(id);
        return Map.of("removed", removed, "npc", inspectNpc(id));
    }

    private Map<String, Object> interactNpc(Map<String, Object> input) {
        String id = text(input, "npcId");
        Player player = player(input);
        NpcService service = npcs();
        FlowNpcHandle handle = service.handle(id);
        Location location = service.location(id);
        if (handle == null || location == null || location.getWorld() != player.getWorld()
            || location.distanceSquared(player.getLocation()) > 36) {
            throw new IllegalArgumentException("The NPC must be active in the player's world within six blocks");
        }
        if (handle.packetBacked()) {
            service.packetInteract(id, player, location, false, player.isSneaking());
        } else {
            Entity entity = service.entity(id);
            if (entity == null) throw new IllegalStateException("The live NPC entity is unavailable");
            Bukkit.getPluginManager().callEvent(new PlayerInteractEntityEvent(player, entity, EquipmentSlot.HAND));
        }
        return Map.of("semantics", "dispatch-only", "runtimeHooksAwaited", false, "npc", inspectNpc(id));
    }

    private Map<String, Object> item(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return Map.of("empty", true);
        CustomContentService service = content.get();
        String id = service == null ? null : service.identifyItem(stack);
        return Map.of("empty", false, "material", stack.getType().getKey().toString(), "amount", stack.getAmount(),
            "contentId", id == null ? "" : id, "contentType", service == null ? "" : service.itemContentType(stack),
            "instanceId", service == null ? "" : service.getVanillaProvider().getInstanceId(stack));
    }

    private CustomContentDefinition vanillaBlock(String id) {
        CustomContentDefinition definition = definition(id);
        if (!definition.isEnabled() || !"block".equalsIgnoreCase(definition.getType()) || !"vanilla".equalsIgnoreCase(definition.getProvider())) {
            throw new IllegalArgumentException("Block fixtures require an enabled Vanilla custom block");
        }
        return definition;
    }

    private CustomContentDefinition definition(String id) {
        CustomContentStorage current = storage.get();
        if (current == null) throw new IllegalStateException("Custom content storage is unavailable");
        CustomContentDefinition definition = current.get(id);
        if (definition == null) throw new IllegalArgumentException("The stored custom content definition is unavailable");
        return definition;
    }

    private CustomContentService content() {
        return Objects.requireNonNull(content.get(), "Custom content service is unavailable");
    }

    private NpcService npcs() {
        return Objects.requireNonNull(npcs.get(), "NPC service is unavailable");
    }

    private static Player player(Map<String, Object> input) {
        Player player = Bukkit.getPlayer(UUID.fromString(text(input, "playerId")));
        if (player == null || !player.isOnline()) throw new IllegalArgumentException("The player must be online");
        return player;
    }

    private static int slot(Map<String, Object> input, Player player) {
        int slot = input.containsKey("slot") ? integer(input, "slot") : player.getInventory().getHeldItemSlot();
        if (slot < 0 || slot > 35) throw new IllegalArgumentException("Inventory slot must be from 0 to 35");
        return slot;
    }

    private static Block block(Map<String, Object> input) {
        Location location = position(input);
        for (String key : List.of("x", "y", "z")) integer(input, key);
        return location.getBlock();
    }

    private static Location position(Map<String, Object> input) {
        World world = Bukkit.getWorld(UUID.fromString(text(input, "worldId")));
        if (world == null) throw new IllegalArgumentException("The world must be loaded");
        double x = decimal(input, "x");
        double y = decimal(input, "y");
        double z = decimal(input, "z");
        if (Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000 || y < world.getMinHeight() || y >= world.getMaxHeight()
            || !world.isChunkLoaded(((int) Math.floor(x)) >> 4, ((int) Math.floor(z)) >> 4)) {
            throw new IllegalArgumentException("The fixture position must be inside loaded chunks and world height");
        }
        double yaw = input.containsKey("yaw") ? decimal(input, "yaw") : 0;
        double pitch = input.containsKey("pitch") ? decimal(input, "pitch") : 0;
        if (Math.abs(yaw) > 360 || Math.abs(pitch) > 90) throw new IllegalArgumentException("Yaw must be from -360 to 360 and pitch from -90 to 90");
        return new Location(world, x, y, z, (float) yaw, (float) pitch);
    }

    private static Map<String, Object> location(Location location) {
        World world = Objects.requireNonNull(location.getWorld(), "Fixture world is unavailable");
        return Map.of("worldId", world.getUID().toString(), "world", world.getName(), "x", location.getX(),
            "y", location.getY(), "z", location.getZ(), "yaw", location.getYaw(), "pitch", location.getPitch());
    }

    private static Map<String, Object> entity(Entity entity) {
        return Map.of("entityId", entity.getUniqueId().toString(), "type", entity.getType().getKey().toString(),
            "location", location(entity.getLocation()));
    }

    private static Map<String, Object> handle(FlowNpcHandle handle) {
        return Map.of("npcId", handle.definitionId(), "entityId", handle.entityUuid(), "packetBacked", handle.packetBacked(),
            "active", handle.active(), "world", handle.world(), "x", handle.x(), "y", handle.y(), "z", handle.z(),
            "yaw", handle.yaw(), "pitch", handle.pitch());
    }

    private static String text(Map<String, Object> input, String key) {
        Object value = input.get(key);
        if (!(value instanceof String text) || text.isBlank() || text.length() > 128) {
            throw new IllegalArgumentException(key + " must be a non-empty string of at most 128 characters");
        }
        return text;
    }

    private static double decimal(Map<String, Object> input, String key) {
        Object value = input.get(key);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IllegalArgumentException(key + " must be a finite number");
        }
        return number.doubleValue();
    }

    private static int integer(Map<String, Object> input, String key) {
        Object value = input.get(key);
        if (!(value instanceof Number number)) throw new IllegalArgumentException(key + " must be an integer");
        try {
            return new BigDecimal(number.toString()).intValueExact();
        } catch (NumberFormatException | ArithmeticException failure) {
            throw new IllegalArgumentException(key + " must be a 32-bit integer", failure);
        }
    }

    private static Map<String, Object> operation(String id, String description, List<String> required, List<String> optional) {
        return Map.of("id", id, "description", description, "input", Map.of("required", required, "optional", optional));
    }
}
