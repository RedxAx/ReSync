package restudio.resync.flow;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.Vector;
import restudio.flow.data.FlowBlock;
import restudio.flow.data.FlowEntityRef;
import restudio.flow.data.FlowItem;
import restudio.flow.data.FlowNpcHandle;
import restudio.flow.data.FlowOperationResult;
import restudio.flow.data.FlowResourceReference;
import restudio.flow.data.FlowWorldRef;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class CompiledRuntimeValueCodec {
    private static final TypeExpr ANY = named("any");
    private static final int MAX_VALUES = 8_192;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_ITEM_BYTES = 1_048_576;
    private static final ThreadLocal<Integer> HOST_CACHE_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<IdentityHashMap<Object, Object>> HOST_CACHE =
        ThreadLocal.withInitial(IdentityHashMap::new);

    private CompiledRuntimeValueCodec() {
    }

    public static void beginHostCache() {
        HOST_CACHE_DEPTH.set(HOST_CACHE_DEPTH.get() + 1);
    }

    public static void endHostCache() {
        int depth = HOST_CACHE_DEPTH.get() - 1;
        if (depth <= 0) {
            HOST_CACHE_DEPTH.remove();
            HOST_CACHE.remove();
            return;
        }
        HOST_CACHE_DEPTH.set(depth);
    }

    public static TypedValue encode(ServerId serverId, TypeExpr type, Object value) {
        Objects.requireNonNull(type, "Runtime Value Type Is Required");
        return new Traversal(serverId, true).typed(type, value, 0);
    }

    public static Object decode(ServerId serverId, TypedValue value) {
        Objects.requireNonNull(value, "Runtime Typed Value Is Required");
        return new Traversal(serverId, false).decoded(value, 0);
    }

    static TypeExpr runtimeType(Object value) {
        if (value instanceof Player) return named("player");
        if (value instanceof LivingEntity) return named("living_entity");
        if (value instanceof Entity || value instanceof FlowEntityRef) return named("entity");
        if (value instanceof Block || value instanceof BlockState || value instanceof FlowBlock) return named("block");
        if (value instanceof World || value instanceof FlowWorldRef) return named("world");
        if (value instanceof Location) return named("location");
        if (value instanceof Vector) return named("vector");
        if (value instanceof ItemStack || value instanceof FlowItem) return named("item");
        if (value instanceof PlayerInventory) return named("inventory");
        if (value instanceof FlowNpcHandle) return named("npc_handle");
        return null;
    }

    private static TypeExpr named(String id) {
        return TypeExpr.named(TypeReference.of("builtin", id));
    }

    private static final class Traversal {
        private final ServerId serverId;
        private final boolean encoding;
        private final IdentityHashMap<Object, Boolean> active = new IdentityHashMap<>();
        private int values;

        private Traversal(ServerId serverId, boolean encoding) {
            this.serverId = serverId;
            this.encoding = encoding;
        }

        private TypedValue typed(TypeExpr type, Object raw, int depth) {
            if (raw instanceof TypedValue value) {
                if (type.equals(value.type())) {
                    decoded(value, depth);
                    return value;
                }
                if (isAny(type) && hostNamed(value.type())) {
                    decoded(value, depth);
                    return TypedValue.value(type, value.value());
                }
                throw invalid("Type Mismatch");
            }
            if (raw instanceof Optional<?> optional && type instanceof TypeExpr.OptionalType) raw = optional.orElse(null);
            if (raw instanceof ItemStack item && item.isEmpty()) raw = null;
            if (raw == null) {
                material(type, null, depth);
                return TypedValue.nullValue(type);
            }
            if (isAny(type) && runtimeType(raw) instanceof TypeExpr.Named host) {
                claim(depth);
                return TypedValue.value(type, encodeHost(host.reference().localId(), raw));
            }
            if (type instanceof TypeExpr.UnionType) throw invalid("Union Values Require An Explicit Typed Variant");
            Object value = material(type, raw, depth);
            if (type instanceof TypeExpr.ResourceType || type instanceof TypeExpr.OptionalType && value instanceof ServerResourceLocator) {
                return TypedValue.locator(type, (ServerResourceLocator) value);
            }
            if (type instanceof TypeExpr.OpaqueType opaque) return TypedValue.opaque(opaque, value);
            return TypedValue.value(type, value);
        }

        private Object decoded(TypedValue value, int depth) {
            TypeExpr type = value.type();
            if (value.state() == TypedValue.State.ABSENT) return null;
            if (value.state() == TypedValue.State.NULL) return material(type, null, depth);
            if (type instanceof TypeExpr.UnionType union) type = union.variant(value.variantId()).type();
            if (value.state() == TypedValue.State.LOCATOR) {
                return TypedResourceReferenceBoundary.requireValue(value.type(), value, requireServer()).locator();
            }
            if (encoding) {
                new Traversal(serverId, false).decoded(value, depth);
                return value.value();
            }
            return material(type, value.value(), depth);
        }

        private Object material(TypeExpr type, Object raw, int depth) {
            claim(depth);
            if (raw instanceof ItemStack item && item.isEmpty()) raw = null;
            if (raw == null) {
                return TypedResourceReferenceBoundary.containsResource(type)
                    ? TypedResourceReferenceBoundary.canonicalize(type, null, requireServer()) : null;
            }
            if (type instanceof TypeExpr.OptionalType optional) {
                if (raw instanceof Optional<?> value) {
                    return value.isEmpty() ? null : material(optional.element(), value.get(), depth + 1);
                }
                return material(optional.element(), raw, depth + 1);
            }
            boolean container = raw instanceof Map<?, ?> || raw instanceof Collection<?> || raw.getClass().isArray();
            if (container && active.put(raw, Boolean.TRUE) != null) throw invalid("Value Cycle");
            try {
                return switch (type) {
                    case TypeExpr.OptionalType optional -> material(optional.element(), raw instanceof Optional<?> value ? value.orElse(null) : raw, depth + 1);
                    case TypeExpr.ListType list -> list(list.element(), raw, depth);
                    case TypeExpr.MapType map -> map(map, raw, depth);
                    case TypeExpr.TupleType tuple -> tuple(tuple, raw, depth);
                    case TypeExpr.ResultType result -> result(result, raw, depth);
                    case TypeExpr.ResourceType resource -> TypedResourceReferenceBoundary.requireLocator(resource, raw, requireServer());
                    case TypeExpr.UnionType ignored -> throw invalid("Union Values Require An Explicit Typed Variant");
                    case TypeExpr.Named name -> {
                        Object value = builtin(name) ? scalar(name.reference().localId(), raw, depth) : portable(raw, depth);
                        yield TypedResourceReferenceBoundary.containsResource(name)
                            ? TypedResourceReferenceBoundary.canonicalize(name, value, requireServer()) : value;
                    }
                    case TypeExpr.OpaqueType ignored -> portable(raw, depth);
                };
            } finally {
                if (container) active.remove(raw);
            }
        }

        private Object scalar(String id, Object raw, int depth) {
            if (List.of("player", "living_entity", "entity", "world", "block", "location", "vector", "item", "itemstack", "inventory", "npc_handle").contains(id)) {
                return encoding ? encodeHost(id, raw) : decodeHost(id, raw);
            }
            return portable(raw, depth);
        }

        private Object portable(Object raw, int depth) {
            if (raw instanceof TypedValue || runtimeType(raw) != null) throw invalid("Requires An Explicit Host Type");
            if (raw instanceof ServerResourceLocator || raw instanceof FlowResourceReference) {
                return TypedResourceReferenceBoundary.requireLocator(raw, requireServer());
            }
            if (raw instanceof String || raw instanceof Boolean || raw instanceof UUID || raw instanceof BigInteger
                || raw instanceof BigDecimal || raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long) return raw;
            if (raw instanceof Float || raw instanceof Double) return decimal(raw);
            if (raw instanceof Character character) return character.toString();
            if (raw instanceof Enum<?> value) return value.name();
            if (raw instanceof Map<?, ?> map) return map(new TypeExpr.MapType(named("string"), ANY), map, depth);
            if (raw instanceof Collection<?> || raw.getClass().isArray()) return list(ANY, raw, depth);
            throw invalid("Unsupported Value " + raw.getClass().getSimpleName());
        }

        private Object list(TypeExpr element, Object raw, int depth) {
            List<Object> result = new ArrayList<>();
            if (raw.getClass().isArray()) {
                int size = Array.getLength(raw);
                size(size);
                for (int index = 0; index < size; index++) result.add(material(element, Array.get(raw, index), depth + 1));
            } else if (raw instanceof Collection<?> entries) {
                size(entries.size());
                for (Object entry : entries) result.add(material(element, entry, depth + 1));
            } else throw invalid("List Value Required");
            return result;
        }

        private Object map(TypeExpr.MapType type, Object raw, int depth) {
            if (!(raw instanceof Map<?, ?> entries)) throw invalid("Map Value Required");
            size(entries.size());
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : entries.entrySet()) {
                Object key = material(type.key(), entry.getKey(), depth + 1);
                if (!(key instanceof String name)) throw invalid("Map Keys Must Be Strings");
                result.put(name, material(type.value(), entry.getValue(), depth + 1));
            }
            return result;
        }

        private Object tuple(TypeExpr.TupleType type, Object raw, int depth) {
            if (!(raw instanceof List<?> entries) || entries.size() != type.elements().size()) throw invalid("Tuple Shape Mismatch");
            List<Object> result = new ArrayList<>();
            for (int index = 0; index < entries.size(); index++) result.add(material(type.elements().get(index), entries.get(index), depth + 1));
            return result;
        }

        private Object result(TypeExpr.ResultType type, Object raw, int depth) {
            if (raw instanceof FlowOperationResult<?> result) {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("success", result.success());
                value.put("value", result.value());
                value.put("errorCode", result.errorCode());
                value.put("message", result.message());
                value.put("details", result.details());
                raw = value;
            }
            if (!(raw instanceof Map<?, ?> entries) || !(entries.get("success") instanceof Boolean success) || !entries.containsKey("value")) {
                throw invalid("Result Shape Mismatch");
            }
            size(entries.size());
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : entries.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw invalid("Map Keys Must Be Strings");
                result.put(key, material("value".equals(key) ? success ? type.success() : type.failure() : ANY, entry.getValue(), depth + 1));
            }
            return result;
        }

        private Object encodeHost(String id, Object raw) {
            if (HOST_CACHE_DEPTH.get() > 0) {
                Object cached = HOST_CACHE.get().get(raw);
                if (cached != null) {
                    return cached;
                }
            }
            Object encoded;
            if (raw instanceof Map<?, ?>) {
                decodeHost(id, raw);
                encoded = raw;
            } else {
                encoded = switch (id) {
                    case "player", "living_entity", "entity" -> entity(id, raw);
                    case "world" -> world(raw);
                    case "block" -> block(raw);
                    case "location" -> location(raw);
                    case "vector" -> vector(raw);
                    case "item", "itemstack" -> item(raw);
                    case "inventory" -> inventory(raw);
                    case "npc_handle" -> npc(raw);
                    default -> throw invalid("Unsupported Host Type");
                };
            }
            if (HOST_CACHE_DEPTH.get() > 0) {
                HOST_CACHE.get().put(raw, encoded);
            }
            return encoded;
        }

        private Object decodeHost(String id, Object raw) {
            Map<?, ?> value = object(raw);
            Set<String> fields = switch (id) {
                case "player", "living_entity", "entity" -> Set.of("kind", "serverId", "worldId", "world", "uuid", "entityType");
                case "world" -> Set.of("kind", "serverId", "worldId", "world", "name");
                case "block" -> value.containsKey("blockData")
                    ? Set.of("kind", "serverId", "worldId", "world", "x", "y", "z", "material", "blockData")
                    : Set.of("kind", "serverId", "worldId", "world", "x", "y", "z", "material");
                case "location" -> Set.of("kind", "serverId", "worldId", "world", "x", "y", "z", "yaw", "pitch");
                case "vector" -> Set.of("x", "y", "z");
                case "item", "itemstack" -> Set.of("format", "data", "material", "amount");
                case "inventory" -> Set.of("kind", "serverId", "worldId", "world", "holderKind", "holderId", "inventoryType");
                case "npc_handle" -> Set.of("kind", "serverId", "worldId", "world", "definitionId", "entityUuid", "instanceUuid", "packetBacked", "active", "x", "y", "z", "yaw", "pitch");
                default -> throw invalid("Unsupported Host Type");
            };
            if (!value.keySet().equals(fields)) throw invalid("Reference Fields Do Not Match The Declared Type");
            if ("vector".equals(id)) return new Vector(number(value, "x"), number(value, "y"), number(value, "z"));
            if ("item".equals(id) || "itemstack".equals(id)) return item(value);
            if (!compatibleHostKind(id, value.get("kind"))) throw invalid("Reference Kind Mismatch");
            requireIdentity(value);
            World world = resolveWorld(value);
            return switch (id) {
                case "world" -> {
                    if (!world.getName().equals(text(value.get("name")))) throw invalid("World Name Mismatch");
                    yield world;
                }
                case "player", "living_entity", "entity" -> resolveEntity(id, value, world);
                case "block" -> resolveBlock(value, world);
                case "location" -> new Location(world, number(value, "x"), number(value, "y"), number(value, "z"), angle(value, "yaw"), angle(value, "pitch"));
                case "inventory" -> resolveInventory(value, world);
                case "npc_handle" -> resolveNpc(value, world);
                default -> throw invalid("Unsupported Host Type");
            };
        }

        private Map<String, Object> identity(World world) {
            Objects.requireNonNull(world, "Runtime World Is Required");
            World current = Bukkit.getWorld(world.getUID());
            if (current == null || !current.getName().equals(world.getName())) throw invalid("World Is No Longer Available");
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("serverId", requireServer().value().toString());
            value.put("worldId", world.getUID().toString());
            value.put("world", world.getName());
            return value;
        }

        private Object entity(String id, Object raw) {
            Entity entity;
            if (raw instanceof Entity value) entity = value;
            else if (raw instanceof FlowEntityRef ref) {
                entity = Bukkit.getEntity(uuid(ref.getUuid()));
                if (entity == null || !Objects.equals(ref.getWorld(), entity.getWorld().getName())
                    || !Objects.equals(ref.getEntityType(), entity.getType().name())) throw invalid("Stale Entity Reference");
            } else throw invalid("Entity Requires A Live Object Or Qualified Typed Reference");
            requireEntity(id, entity, true);
            Map<String, Object> value = identity(entity.getWorld());
            value.put("kind", id);
            value.put("uuid", entity.getUniqueId().toString());
            value.put("entityType", entity.getType().name());
            return value;
        }

        private Object world(Object raw) {
            World world = raw instanceof World value ? value : raw instanceof FlowWorldRef ref ? Bukkit.getWorld(ref.getName()) : null;
            if (world == null) throw invalid("World Requires A Loaded Object Or Qualified Typed Reference");
            Map<String, Object> value = identity(world);
            value.put("kind", "world");
            value.put("name", world.getName());
            return value;
        }

        private Object block(Object raw) {
            if (raw instanceof BlockState state) {
                if (!state.getWorld().isChunkLoaded(state.getX() >> 4, state.getZ() >> 4)) throw invalid("Block Chunk Is Unavailable");
                Map<String, Object> value = identity(state.getWorld());
                value.put("kind", "block");
                value.put("x", state.getX());
                value.put("y", state.getY());
                value.put("z", state.getZ());
                value.put("material", state.getType().name());
                value.put("blockData", state.getBlockData().getAsString());
                return value;
            }
            Block block;
            if (raw instanceof Block value) block = value;
            else if (raw instanceof FlowBlock ref) {
                World world = Bukkit.getWorld(ref.getWorld());
                if (world == null || !world.isChunkLoaded(ref.getX() >> 4, ref.getZ() >> 4)) throw invalid("Block World Or Chunk Is Unavailable");
                if (ref.getY() < world.getMinHeight() || ref.getY() >= world.getMaxHeight()) throw invalid("Block Height Is Invalid");
                block = world.getBlockAt(ref.getX(), ref.getY(), ref.getZ());
            } else throw invalid("Block Requires A Live Object Or Qualified Typed Reference");
            if (!block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) throw invalid("Block Chunk Is Unavailable");
            Map<String, Object> value = identity(block.getWorld());
            value.put("kind", "block");
            value.put("x", block.getX());
            value.put("y", block.getY());
            value.put("z", block.getZ());
            value.put("material", block.getType().name());
            return value;
        }

        private Object location(Object raw) {
            if (!(raw instanceof Location location)) throw invalid("Location Requires A Live Object Or Qualified Typed Reference");
            Map<String, Object> value = identity(location.getWorld());
            value.put("kind", "location");
            value.put("x", decimal(location.getX()));
            value.put("y", decimal(location.getY()));
            value.put("z", decimal(location.getZ()));
            value.put("yaw", decimal(location.getYaw()));
            value.put("pitch", decimal(location.getPitch()));
            return value;
        }

        private Object vector(Object raw) {
            if (!(raw instanceof Vector vector)) throw invalid("Vector Requires A Vector Or Typed Coordinates");
            return Map.of("x", decimal(vector.getX()), "y", decimal(vector.getY()), "z", decimal(vector.getZ()));
        }

        private Object item(Object raw) {
            ItemStack item;
            if (raw instanceof ItemStack value) item = value;
            else if (raw instanceof FlowItem ref) {
                Material material = Material.matchMaterial(Objects.requireNonNull(ref.getMaterial(), "Item Material Is Required"));
                if (material == null || ref.getAmount() < 1 || ref.getDurability() < 0 || ref.getDurability() > Short.MAX_VALUE) throw invalid("Invalid Item Literal");
                if (ref.getNbt() != null && !ref.getNbt().isEmpty()) throw invalid("Item NBT Requires A Serialized Item Snapshot");
                if (ref.getEnchantments() != null && ref.getEnchantments().stream().anyMatch(entry -> entry == null || entry.resolveEnchantment() == null)) {
                    throw invalid("Unknown Item Enchantment");
                }
                item = ref.toItemStack();
            } else throw invalid("Item Requires An Item Object Or Serialized Typed Snapshot");
            byte[] bytes = item.serializeAsBytes();
            if (bytes.length == 0 || bytes.length > MAX_ITEM_BYTES) throw invalid("Item Snapshot Size Exceeded");
            return Map.of("format", "paper-item-v1", "data", Base64.getEncoder().encodeToString(bytes), "material", item.getType().name(), "amount", item.getAmount());
        }

        private Object inventory(Object raw) {
            if (!(raw instanceof PlayerInventory inventory) || !(inventory.getHolder() instanceof Player holder)) {
                throw invalid("Inventory Requires A Live Player Inventory");
            }
            Player player = Bukkit.getPlayer(holder.getUniqueId());
            if (player == null || !player.isOnline() || !player.getWorld().getUID().equals(holder.getWorld().getUID())
                || !player.getInventory().equals(inventory) || !"PLAYER".equals(inventory.getType().name())) {
                throw invalid("Player Inventory Is No Longer Available");
            }
            Map<String, Object> value = identity(player.getWorld());
            value.put("kind", "inventory");
            value.put("holderKind", "player");
            value.put("holderId", player.getUniqueId().toString());
            value.put("inventoryType", "PLAYER");
            return value;
        }

        private PlayerInventory resolveInventory(Map<?, ?> value, World world) {
            if (!"player".equals(value.get("holderKind")) || !"PLAYER".equals(value.get("inventoryType"))) {
                throw invalid("Inventory Holder Type Is Unsupported");
            }
            Player player = Bukkit.getPlayer(uuid(value.get("holderId")));
            if (player == null || !player.isOnline() || !world.getUID().equals(player.getWorld().getUID())) {
                throw invalid("Player Inventory Is No Longer Available");
            }
            PlayerInventory inventory = player.getInventory();
            if (inventory == null || !(inventory.getHolder() instanceof Player holder)
                || !player.getUniqueId().equals(holder.getUniqueId()) || !"PLAYER".equals(inventory.getType().name())) {
                throw invalid("Player Inventory Is No Longer Available");
            }
            return inventory;
        }

        private Object npc(Object raw) {
            if (!(raw instanceof FlowNpcHandle handle)) throw invalid("NPC Handle Requires A Typed Snapshot");
            World world = Bukkit.getWorld(text(handle.world()));
            if (world == null) throw invalid("NPC World Is No Longer Available");
            Map<String, Object> value = identity(world);
            value.put("kind", "npc_handle");
            value.put("definitionId", handle.definitionId());
            value.put("entityUuid", handle.entityUuid());
            value.put("instanceUuid", handle.instanceUuid());
            value.put("packetBacked", handle.packetBacked());
            value.put("active", handle.active());
            value.put("x", decimal(handle.x()));
            value.put("y", decimal(handle.y()));
            value.put("z", decimal(handle.z()));
            value.put("yaw", decimal(handle.yaw()));
            value.put("pitch", decimal(handle.pitch()));
            resolveNpc(value, world);
            return value;
        }

        private FlowNpcHandle resolveNpc(Map<?, ?> value, World world) {
            if (!(value.get("packetBacked") instanceof Boolean packetBacked)
                || !(value.get("active") instanceof Boolean active) || !(value.get("entityUuid") instanceof String entityUuid)
                || !(value.get("instanceUuid") instanceof String instanceUuid)) {
                throw invalid("NPC Handle Fields Have Invalid Types");
            }
            String definitionId = text(value.get("definitionId"));
            uuid(instanceUuid);
            if (packetBacked) {
                if (!entityUuid.isEmpty()) throw invalid("Packet NPC Handle Cannot Have An Entity UUID");
            } else {
                UUID entityId = uuid(entityUuid);
                if (active) {
                    Entity entity = Bukkit.getEntity(entityId);
                    if (entity == null || entity.isDead() || !world.getUID().equals(entity.getWorld().getUID())) {
                        throw invalid("NPC Entity Is No Longer Available In This World");
                    }
                }
            }
            return new FlowNpcHandle(definitionId, entityUuid, instanceUuid, packetBacked, active, world.getName(),
                number(value, "x"), number(value, "y"), number(value, "z"), angle(value, "yaw"), angle(value, "pitch"));
        }

        private Object item(Map<?, ?> value) {
            if (!"paper-item-v1".equals(value.get("format"))) throw invalid("Item Snapshot Format Is Unsupported");
            String data = text(value.get("data"));
            if (data.length() > ((MAX_ITEM_BYTES + 2) / 3) * 4) throw invalid("Item Snapshot Size Exceeded");
            try {
                byte[] bytes = Base64.getDecoder().decode(data);
                if (bytes.length == 0 || bytes.length > MAX_ITEM_BYTES) throw invalid("Item Snapshot Size Exceeded");
                ItemStack item = ItemStack.deserializeBytes(bytes);
                if (item == null || !item.getType().name().equals(text(value.get("material"))) || item.getAmount() != integer(value, "amount")) {
                    throw invalid("Item Snapshot Metadata Mismatch");
                }
                return item;
            } catch (RuntimeException failure) {
                throw new IllegalArgumentException("Runtime Item Snapshot Is Invalid", failure);
            }
        }

        private Entity resolveEntity(String id, Map<?, ?> value, World world) {
            UUID identity = uuid(value.get("uuid"));
            Entity entity = "player".equals(id) ? Bukkit.getPlayer(identity) : Bukkit.getEntity(identity);
            if (entity == null || !world.getUID().equals(entity.getWorld().getUID()) || !entity.getType().name().equals(text(value.get("entityType")))) {
                throw invalid("Stale Entity Reference");
            }
            requireEntity(id, entity, false);
            return entity;
        }

        private static boolean compatibleHostKind(String requested, Object actual) {
            if (requested.equals(actual)) {
                return true;
            }
            if ("entity".equals(requested)) {
                return "player".equals(actual) || "living_entity".equals(actual);
            }
            return "living_entity".equals(requested) && "player".equals(actual);
        }

        private void requireEntity(String id, Entity entity, boolean capture) {
            if ("player".equals(id) && !(entity instanceof Player) || "living_entity".equals(id) && !(entity instanceof LivingEntity)) {
                throw invalid("Entity Type Mismatch");
            }
            if (entity instanceof Player player ? !player.isOnline() : entity.isDead() || !capture && !entity.isValid()) {
                throw invalid("Entity Is No Longer Available");
            }
        }

        private Object resolveBlock(Map<?, ?> value, World world) {
            if (Material.matchMaterial(text(value.get("material"))) == null) throw invalid("Block Material Is Invalid");
            int x = integer(value, "x");
            int y = integer(value, "y");
            int z = integer(value, "z");
            if (y < world.getMinHeight() || y >= world.getMaxHeight()) throw invalid("Block Height Is Invalid");
            if (!world.isChunkLoaded(x >> 4, z >> 4)) throw invalid("Block Chunk Is Unavailable");
            Block block = world.getBlockAt(x, y, z);
            if (value.containsKey("blockData")) {
                BlockState state = block.getState();
                state.setBlockData(Bukkit.createBlockData(text(value.get("blockData"))));
                if (!state.getType().name().equals(text(value.get("material")))) throw invalid("Block Snapshot Material Mismatch");
                return state;
            }
            return block;
        }

        private void requireIdentity(Map<?, ?> value) {
            if (!requireServer().value().equals(uuid(value.get("serverId")))) throw invalid("Server Identity Mismatch");
        }

        private World resolveWorld(Map<?, ?> value) {
            World world = Bukkit.getWorld(uuid(value.get("worldId")));
            if (world == null || !world.getName().equals(text(value.get("world")))) throw invalid("World Is No Longer Available");
            return world;
        }

        private ServerId requireServer() {
            if (serverId == null) throw invalid("Server Identity Is Required");
            return serverId;
        }

        private void claim(int depth) {
            if (depth > MAX_DEPTH) throw invalid("Value Depth Exceeded");
            if (++values > MAX_VALUES) throw invalid("Value Budget Exceeded");
        }

        private void size(int count) {
            if (count > MAX_VALUES - values) throw invalid("Value Budget Exceeded");
        }
    }

    private static boolean builtin(TypeExpr.Named type) {
        return "builtin".equals(type.reference().ownerId()) && type.arguments().isEmpty();
    }

    private static boolean isAny(TypeExpr type) {
        return type instanceof TypeExpr.Named named && builtin(named) && "any".equals(named.reference().localId());
    }

    private static boolean hostNamed(TypeExpr type) {
        return type instanceof TypeExpr.Named named && builtin(named)
            && List.of("player", "living_entity", "entity", "world", "block", "location", "vector", "item", "itemstack", "inventory", "npc_handle")
                .contains(named.reference().localId());
    }

    private static Map<?, ?> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw invalid("Qualified Typed Reference Required");
        return map;
    }

    private static String text(Object value) {
        if (!(value instanceof String text) || text.isBlank() || !text.equals(text.strip()) || text.indexOf('\u0000') >= 0) throw invalid("Canonical Text Is Required");
        return text;
    }

    private static UUID uuid(Object value) {
        String text = text(value);
        UUID uuid;
        try {
            uuid = UUID.fromString(text);
        } catch (IllegalArgumentException failure) {
            throw invalid("Canonical UUID Is Required");
        }
        if (!uuid.toString().equals(text)) throw invalid("Canonical UUID Is Required");
        return uuid;
    }

    private static BigDecimal decimal(Object value) {
        if (!(value instanceof Number number)) throw invalid("Numeric Value Required");
        double finite = number.doubleValue();
        if (!Double.isFinite(finite)) throw invalid("Finite Number Required");
        try {
            return new BigDecimal(number.toString());
        } catch (NumberFormatException failure) {
            throw invalid("Numeric Value Required");
        }
    }

    private static double number(Map<?, ?> value, String key) {
        return decimal(value.get(key)).doubleValue();
    }

    private static float angle(Map<?, ?> value, String key) {
        float number = decimal(value.get(key)).floatValue();
        if (!Float.isFinite(number)) throw invalid("Finite Angle Required");
        return number;
    }

    private static int integer(Map<?, ?> value, String key) {
        try {
            return decimal(value.get(key)).intValueExact();
        } catch (ArithmeticException failure) {
            throw invalid("Integral Coordinate Required");
        }
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("Runtime Value " + reason);
    }
}
