package restudio.resync.flow.handler.generic;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.Powerable;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Cake;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.block.data.type.Sapling;
import org.bukkit.block.data.type.Slab;
import org.bukkit.entity.FallingBlock;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class BlockActionHandler implements NodeHandler {
    private static final long MAX_BLOCKS_PER_ACTION = 1_000_000L;
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public BlockActionHandler() {
        operations.put("block_set", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (location == null) throw new IllegalArgumentException("Block location is required");
            String materialName = ctx.getInputValue(node, "material", String.class, "STONE");
            Material material = Material.matchMaterial(materialName.toUpperCase());
            if (material == null) throw new IllegalArgumentException("Block material is invalid");
            location.getBlock().setType(material);
        });

        operations.put("block_get", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (location == null) throw new IllegalArgumentException("Block location is required");
            Block block = location.getBlock();
            ctx.setOutput(node, "type", block.getType().name());
            ctx.setOutput(node, "data", block.getBlockData().getAsString());
        });

        operations.put("block_replace", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (location == null) throw new IllegalArgumentException("Block location is required");
            String oldMaterialName = ctx.getInputValue(node, "old_type", String.class, "");
            String newMaterialName = ctx.getInputValue(node, "new_type", String.class, "STONE");
            Block block = location.getBlock();
            if (!oldMaterialName.isBlank()) {
                Material oldMaterial = Material.matchMaterial(oldMaterialName.toUpperCase());
                if (oldMaterial == null) throw new IllegalArgumentException("Expected block material is invalid");
                if (block.getType() != oldMaterial) return;
            }
            Material newMaterial = Material.matchMaterial(newMaterialName.toUpperCase());
            if (newMaterial == null) throw new IllegalArgumentException("Replacement block material is invalid");
            block.setType(newMaterial);
        });

        operations.put("block_fill", (ctx, node) -> {
            Location minLoc = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLoc = ctx.getInputValue(node, "max_location", Location.class, null);
            if (minLoc == null || maxLoc == null) throw new IllegalArgumentException("Block region bounds are required");
            String materialName = ctx.getInputValue(node, "material", String.class, "STONE");
            Material material = Material.matchMaterial(materialName.toUpperCase());
            if (material == null) throw new IllegalArgumentException("Block material is invalid");
            int minX = Math.min(minLoc.getBlockX(), maxLoc.getBlockX());
            int minY = Math.min(minLoc.getBlockY(), maxLoc.getBlockY());
            int minZ = Math.min(minLoc.getBlockZ(), maxLoc.getBlockZ());
            int maxX = Math.max(minLoc.getBlockX(), maxLoc.getBlockX());
            int maxY = Math.max(minLoc.getBlockY(), maxLoc.getBlockY());
            int maxZ = Math.max(minLoc.getBlockZ(), maxLoc.getBlockZ());
            World world = minLoc.getWorld();
            if (world == null) throw new IllegalArgumentException("Block world is required");
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        world.getBlockAt(x, y, z).setType(material);
                    }
                }
            }
        });

        operations.put("block_replace_area", (ctx, node) -> {
            Location minLoc = ctx.getInputValue(node, "min_location", Location.class, null);
            Location maxLoc = ctx.getInputValue(node, "max_location", Location.class, null);
            if (minLoc == null || maxLoc == null) throw new IllegalArgumentException("Block region bounds are required");
            String oldMaterialName = ctx.getInputValue(node, "old_type", String.class, "");
            String newMaterialName = ctx.getInputValue(node, "new_type", String.class, "STONE");
            Material oldMaterial = !oldMaterialName.isBlank() ? Material.matchMaterial(oldMaterialName.toUpperCase()) : null;
            if (!oldMaterialName.isBlank() && oldMaterial == null) throw new IllegalArgumentException("Expected block material is invalid");
            Material newMaterial = Material.matchMaterial(newMaterialName.toUpperCase());
            if (newMaterial == null) throw new IllegalArgumentException("Replacement block material is invalid");
            int minX = Math.min(minLoc.getBlockX(), maxLoc.getBlockX());
            int minY = Math.min(minLoc.getBlockY(), maxLoc.getBlockY());
            int minZ = Math.min(minLoc.getBlockZ(), maxLoc.getBlockZ());
            int maxX = Math.max(minLoc.getBlockX(), maxLoc.getBlockX());
            int maxY = Math.max(minLoc.getBlockY(), maxLoc.getBlockY());
            int maxZ = Math.max(minLoc.getBlockZ(), maxLoc.getBlockZ());
            World world = minLoc.getWorld();
            if (world == null) throw new IllegalArgumentException("Block world is required");
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        Block block = world.getBlockAt(x, y, z);
                        if (oldMaterial == null || block.getType() == oldMaterial) {
                            block.setType(newMaterial);
                        }
                    }
                }
            }
        });

        operations.put("block_break_naturally", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (location == null) throw new IllegalArgumentException("Block location is required");
            location.getBlock().breakNaturally();
        });

        operations.put("block_drop_item", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            String materialName = ctx.getInputValue(node, "item", String.class, "STONE");
            if (location == null) throw new IllegalArgumentException("Block location is required");
            Material material = Material.matchMaterial(materialName.toUpperCase());
            if (material == null) throw new IllegalArgumentException("Block material is invalid");
            World world = location.getWorld();
            if (world == null) throw new IllegalArgumentException("Block world is required");
            ItemStack item = new ItemStack(material);
            world.dropItemNaturally(location, item);
        });

        operations.put("block_update", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (location == null) throw new IllegalArgumentException("Block location is required");
            location.getBlock().getState().update(true, true);
        });

        operations.put("block_set_biome", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            String biomeName = ctx.getInputValue(node, "biome", String.class, "PLAINS");
            if (location == null) throw new IllegalArgumentException("Block location is required");
            World world = location.getWorld();
            if (world == null) throw new IllegalArgumentException("Block world is required");
            Biome biome;
            try {
                biome = Biome.valueOf(biomeName.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unknown biome: " + biomeName, exception);
            }
            world.setBiome(location, biome);
        });

        operations.put("block_set_type", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            String materialName = ctx.getInputValue(node, "material", String.class, "STONE");
            if (location == null) throw new IllegalArgumentException("Block location is required");
            Material material = Material.matchMaterial(materialName.toUpperCase());
            if (material == null) throw new IllegalArgumentException("Block material is invalid");
            location.getBlock().setType(material);
        });

        operations.put("block_get_type", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (location == null) throw new IllegalArgumentException("Block location is required");
            Block block = location.getBlock();
            ctx.setOutput(node, "type", block.getType().name());
        });

        operations.put("block_set_data", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (location == null) throw new IllegalArgumentException("Block location is required");
            @SuppressWarnings("unchecked")
            Map<String, Object> dataMap = ctx.getInputValue(node, "data", Map.class, null);
            if (dataMap == null) throw new IllegalArgumentException("Block data map is required");
            Block block = location.getBlock();
            BlockData blockData = block.getBlockData();
            for (Map.Entry<String, Object> entry : dataMap.entrySet()) {
                try {
                    String key = entry.getKey().toLowerCase();
                    String value = entry.getValue().toString();
                    switch (blockData) {
                        case Waterlogged waterlogged when key.equals("waterlogged") ->
                            waterlogged.setWaterlogged(Boolean.parseBoolean(value));
                        case Powerable powerable when key.equals("powered") ->
                            powerable.setPowered(Boolean.parseBoolean(value));
                        case Lightable lightable when key.equals("lit") ->
                            lightable.setLit(Boolean.parseBoolean(value));
                        case Openable openable when key.equals("open") ->
                            openable.setOpen(Boolean.parseBoolean(value));
                        case Directional directional when key.equals("facing") ->
                            directional.setFacing(BlockFace.valueOf(value.toUpperCase()));
                        case Rotatable rotatable when key.equals("rotation") ->
                            rotatable.setRotation(BlockFace.valueOf(value.toUpperCase()));
                        case Ageable ageable when key.equals("age") ->
                            ageable.setAge(Integer.parseInt(value));
                        case Levelled levelled when key.equals("level") -> levelled.setLevel(Integer.parseInt(value));
                        default -> throw new IllegalArgumentException("Unsupported block data property: " + key);
                    }
                } catch (RuntimeException exception) {
                    throw new IllegalArgumentException("Invalid block data property " + entry.getKey() + ": " + entry.getValue(), exception);
                }
            }
            block.setBlockData(blockData);
        });

        operations.put("block_get_data", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (location == null) throw new IllegalArgumentException("Block location is required");
            Block block = location.getBlock();
            BlockData blockData = block.getBlockData();
            ctx.setOutput(node, "data", blockData.getAsString());
        });

        operations.put("block_set_age", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            Integer age = ctx.getInputValue(node, "age", Integer.class, 0);
            if (location == null) throw new IllegalArgumentException("Block location is required");
            Block block = location.getBlock();
            BlockData blockData = block.getBlockData();
            switch (blockData) {
                case Ageable ageable -> {
                    requireRange(age, 0, ageable.getMaximumAge(), "Block age");
                    ageable.setAge(age);
                }
                case Sapling sapling -> {
                    requireRange(age, 0, sapling.getMaximumStage(), "Sapling stage");
                    sapling.setStage(age);
                }
                case Leaves leaves -> {
                    requireRange(age, 1, leaves.getMaximumDistance(), "Leaves distance");
                    leaves.setDistance(age);
                }
                case Farmland farmland -> {
                    requireRange(age, 0, farmland.getMaximumMoisture(), "Farmland moisture");
                    farmland.setMoisture(age);
                }
                default -> throw new IllegalArgumentException("Block data does not expose age: " + block.getType());
            }
            block.setBlockData(blockData);
        });

        operations.put("block_set_level", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            Integer level = ctx.getInputValue(node, "level", Integer.class, 0);
            if (location == null) throw new IllegalArgumentException("Block location is required");
            Block block = location.getBlock();
            BlockData blockData = block.getBlockData();
            switch (blockData) {
                case Levelled levelled -> {
                    requireRange(level, 0, levelled.getMaximumLevel(), "Block level");
                    levelled.setLevel(level);
                }
                case Cake cake -> {
                    requireRange(level, 0, cake.getMaximumBites(), "Cake bites");
                    cake.setBites(level);
                }
                case Slab slab -> {
                    requireRange(level, 0, 1, "Slab level");
                    slab.setType(level == 1 ? Slab.Type.TOP : Slab.Type.BOTTOM);
                }
                default -> throw new IllegalArgumentException("Block data does not expose a level: " + blockData.getClass().getSimpleName());
            }
            block.setBlockData(blockData);
        });

        operations.put("block_set_rotation", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            String rotationName = ctx.getInputValue(node, "rotation", String.class, "NORTH");
            if (location == null) throw new IllegalArgumentException("Block location is required");
            Block block = location.getBlock();
            BlockData blockData = block.getBlockData();
            BlockFace face;
            try {
                face = BlockFace.valueOf(rotationName.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unknown block rotation: " + rotationName, exception);
            }
            if (!(blockData instanceof Rotatable) && !(blockData instanceof Directional)) {
                throw new IllegalArgumentException("Block does not support rotation: " + block.getType());
            }
            if (blockData instanceof Rotatable rotatable) {
                rotatable.setRotation(face);
            } else if (blockData instanceof Directional directional) {
                if (!directional.getFaces().contains(face)) throw new IllegalArgumentException("Block does not support rotation face: " + face);
                directional.setFacing(face);
            }
            block.setBlockData(blockData);
        });

        operations.put("block_set_face", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            String faceName = ctx.getInputValue(node, "face", String.class, "NORTH");
            if (location == null) throw new IllegalArgumentException("Block location is required");
            Block block = location.getBlock();
            BlockData blockData = block.getBlockData();
            if (!(blockData instanceof Directional)) {
                throw new IllegalArgumentException("Block does not support facing: " + block.getType());
            }
            BlockFace face;
            try {
                face = BlockFace.valueOf(faceName.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unknown block face: " + faceName, exception);
            }
            Directional directional = (Directional) blockData;
            if (!directional.getFaces().contains(face)) throw new IllegalArgumentException("Block does not support face: " + face);
            directional.setFacing(face);
            block.setBlockData(directional);
        });

        operations.put("block_set_powered", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            Boolean powered = ctx.getInputValue(node, "powered", Boolean.class, false);
            if (location == null) throw new IllegalArgumentException("Block location is required");
            Block block = location.getBlock();
            BlockData blockData = block.getBlockData();
            if (!(blockData instanceof Powerable powerable)) throw new IllegalArgumentException("Block does not support powered state: " + block.getType());
            powerable.setPowered(powered);
            block.setBlockData(powerable);
        });

        operations.put("block_set_lit", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            Boolean lit = ctx.getInputValue(node, "lit", Boolean.class, false);
            if (location == null) throw new IllegalArgumentException("Block location is required");
            Block block = location.getBlock();
            BlockData blockData = block.getBlockData();
            if (!(blockData instanceof Lightable lightable)) throw new IllegalArgumentException("Block does not support lit state: " + block.getType());
            lightable.setLit(lit);
            block.setBlockData(lightable);
        });

        operations.put("block_interact", (ctx, node) -> {
            throw new UnsupportedOperationException("Block interaction requires an explicit player, hand, face, and interaction policy");
        });

        operations.put("block_break_particles", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            String typeName = ctx.getInputValue(node, "type", String.class, "STONE");
            if (location == null) throw new IllegalArgumentException("Block location is required");
            Material type = Material.matchMaterial(typeName.toUpperCase());
            if (type == null) throw new IllegalArgumentException("Block particle material is invalid");
            World world = location.getWorld();
            if (world == null) throw new IllegalArgumentException("Block world is required");
            world.spawnParticle(Particle.BLOCK, location, 10, type.createBlockData());
        });

        operations.put("block_play_sound", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            String soundName = ctx.getInputValue(node, "type", String.class, "BLOCK_STONE_PLACE");
            Float volume = ctx.getInputValue(node, "volume", Float.class, 1.0f);
            Float pitch = ctx.getInputValue(node, "pitch", Float.class, 1.0f);
            if (location == null) throw new IllegalArgumentException("Block location is required");
            World world = location.getWorld();
            if (world == null) throw new IllegalArgumentException("Block world is required");
            Sound sound;
            try {
                sound = Sound.valueOf(soundName.toUpperCase(Locale.ROOT).replace('.', '_'));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unknown sound: " + soundName, exception);
            }
            requireFiniteRange(volume, 0.0f, 16.0f, "Sound volume");
            requireFiniteRange(pitch, 0.0f, 2.0f, "Sound pitch");
            world.playSound(location, sound, volume, pitch);
        });

        operations.put("block_physics", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "block_location", Location.class, null);
            if (location == null) throw new IllegalArgumentException("Block location is required");
            location.getBlock().getState().update(true, true);
        });

        operations.put("block_explode", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (location == null) throw new IllegalArgumentException("Block location is required");
            World world = location.getWorld();
            if (world == null) throw new IllegalArgumentException("Block world is required");
            Float power = ctx.getInputValue(node, "power", Float.class, 4.0f);
            Boolean fire = ctx.getInputValue(node, "fire", Boolean.class, false);
            Boolean breakBlocks = ctx.getInputValue(node, "break_blocks", Boolean.class, true);
            requireFiniteRange(power, 0.0f, 100.0f, "Explosion power");
            world.createExplosion(location, power, fire, breakBlocks);
        });

        operations.put("block_raytrace", (ctx, node) -> {
            Location startLocation = ctx.getInputValue(node, "start_location", Location.class, null);
            Vector direction = ctx.getInputValue(node, "direction_vector", Vector.class, null);
            Double maxDistance = ctx.getInputValue(node, "max_distance", Double.class, 50.0);
            if (startLocation == null || direction == null) throw new IllegalArgumentException("Ray trace start location and direction are required");
            World world = startLocation.getWorld();
            if (world == null) throw new IllegalArgumentException("Block world is required");
            if (!Double.isFinite(direction.getX()) || !Double.isFinite(direction.getY()) || !Double.isFinite(direction.getZ()) || direction.lengthSquared() == 0.0) {
                throw new IllegalArgumentException("Ray trace direction must be finite and non-zero");
            }
            ctx.setOutput(node, "hit_block", null);
            ctx.setOutput(node, "hit_location", null);
            ctx.setOutput(node, "distance", -1.0);
            var result = world.rayTraceBlocks(startLocation, direction.clone().normalize(), maxDistance);
            if (result != null) {
                Block hitBlock = result.getHitBlock();
                Location hitLocation = result.getHitPosition().toLocation(world);
                double distance = startLocation.distance(hitLocation);
                if (hitBlock != null) {
                    ctx.setOutput(node, "hit_block", hitBlock);
                }
                ctx.setOutput(node, "hit_location", hitLocation);
                ctx.setOutput(node, "distance", distance);
            }
        });

        operations.put("block_offset", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "block_location", Location.class, null);
            if (location == null) throw new IllegalArgumentException("Block location is required");
            Integer offsetX = ctx.getInputValue(node, "offset_x", Integer.class, 0);
            Integer offsetY = ctx.getInputValue(node, "offset_y", Integer.class, 0);
            Integer offsetZ = ctx.getInputValue(node, "offset_z", Integer.class, 0);
            Block block = location.getBlock().getRelative(offsetX, offsetY, offsetZ);
            ctx.setOutput(node, "block", block);
        });

        operations.put("block_sign_text", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "block_location", Location.class, null);
            if (location == null) throw new IllegalArgumentException("Block location is required");
            Block block = location.getBlock();
            BlockState state = block.getState();
            if (!(state instanceof Sign)) throw new IllegalArgumentException("Target block is not a sign");
            Sign sign = (Sign) state;
            String line1 = ctx.getInputValue(node, "line1", String.class, "");
            String line2 = ctx.getInputValue(node, "line2", String.class, "");
            String line3 = ctx.getInputValue(node, "line3", String.class, "");
            String line4 = ctx.getInputValue(node, "line4", String.class, "");
            sign.setLine(0, line1);
            sign.setLine(1, line2);
            sign.setLine(2, line3);
            sign.setLine(3, line4);
            if (!sign.update(true, true)) throw new IllegalStateException("Sign update was rejected");
        });

        operations.put("block_container_get", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "block_location", Location.class, null);
            if (location == null) throw new IllegalArgumentException("Block location is required");
            Block block = location.getBlock();
            BlockState state = block.getState();
            if (!(state instanceof Container container)) throw new IllegalArgumentException("Target block is not a container");
            Inventory inventory = container.getInventory();
            ctx.setOutput(node, "items_list", inventory.getContents());
        });

        operations.put("block_container_set", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "block_location", Location.class, null);
            if (location == null) throw new IllegalArgumentException("Block location is required");
            Block block = location.getBlock();
            BlockState state = block.getState();
            if (!(state instanceof Container container)) throw new IllegalArgumentException("Target block is not a container");
            @SuppressWarnings("unchecked")
            List<ItemStack> itemsList = ctx.getInputValue(node, "items_list", List.class, null);
            if (itemsList == null) throw new IllegalArgumentException("Container item list is required");
            Inventory inventory = container.getInventory();
            if (itemsList.size() > inventory.getSize()) throw new IllegalArgumentException("Container item list exceeds inventory size");
            inventory.setContents(itemsList.toArray(new ItemStack[0]));
        });

        operations.put("block_container_add", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "block_location", Location.class, null);
            if (location == null) throw new IllegalArgumentException("Block location is required");
            Block block = location.getBlock();
            BlockState state = block.getState();
            if (!(state instanceof Container container)) throw new IllegalArgumentException("Target block is not a container");
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            if (item == null || item.getType().isAir() || item.getAmount() <= 0) throw new IllegalArgumentException("Container item is required");
            Inventory inventory = container.getInventory();
            if (!canFit(inventory, item)) throw new IllegalStateException("Container does not have enough space for the item");
            if (!inventory.addItem(item.clone()).isEmpty()) throw new IllegalStateException("Container rejected part of the item");
        });

        operations.put("block_spawn_falling", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (location == null) throw new IllegalArgumentException("Block location is required");
            World world = location.getWorld();
            if (world == null) throw new IllegalArgumentException("Block world is required");
            String materialName = ctx.getInputValue(node, "material_type", String.class, "STONE");
            Material material = Material.matchMaterial(materialName.toUpperCase());
            if (material == null) throw new IllegalArgumentException("Block material is invalid");
            FallingBlock fallingBlock = world.spawnFallingBlock(location, material.createBlockData());
            ctx.setOutput(node, "falling_block_entity", fallingBlock);
        });

        operations.put("block_break_naturally_drops", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "block_location", Location.class, null);
            if (location == null) throw new IllegalArgumentException("Block location is required");
            Block block = location.getBlock();
            ItemStack tool = ctx.getInputValue(node, "tool_item", ItemStack.class, null);
            Collection<ItemStack> drops = block.getDrops(tool);
            block.setType(Material.AIR);
            ctx.setOutput(node, "dropped_items_list", drops.toArray(new ItemStack[0]));
        });

        operations.put("block_break_instantly", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "block_location", Location.class, null);
            if (location == null) throw new IllegalArgumentException("Block location is required");
            location.getBlock().setType(Material.AIR);
        });

        operations.put("block_get_drops", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "block_location", Location.class, null);
            if (location == null) throw new IllegalArgumentException("Block location is required");
            Block block = location.getBlock();
            ItemStack tool = ctx.getInputValue(node, "tool_item", ItemStack.class, null);
            Collection<ItemStack> drops = block.getDrops(tool);
            ctx.setOutput(node, "dropped_items_list", drops.toArray(new ItemStack[0]));
        });

        operations.put("block_get_state", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "block_location", Location.class, null);
            if (location == null) throw new IllegalArgumentException("Block location is required");
            Block block = location.getBlock();
            BlockState state = block.getState();
            ctx.setOutput(node, "state_data", state.getBlockData().getAsString());
        });

        operations.put("block_set_state", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "block_location", Location.class, null);
            if (location == null) throw new IllegalArgumentException("Block location is required");
            String stateData = ctx.getInputValue(node, "state_data", String.class, "");
            if (stateData.isEmpty()) throw new IllegalArgumentException("Block state data is required");
            Block block = location.getBlock();
            BlockData blockData = Bukkit.createBlockData(stateData);
            block.setBlockData(blockData);
        });

        operations.put("block_is_solid", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "block_location", Location.class, null);
            if (location == null) throw new IllegalArgumentException("Block location is required");
            Block block = location.getBlock();
            boolean isSolid = block.getType().isSolid();
            ctx.setOutput(node, "is_solid", isSolid);
        });
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("BlockActionHandler", this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op == null) {
            throw new IllegalArgumentException("Unknown block action operation: " + operation);
        }
        validateActionBudget(ctx, node, operation);
        op.accept(ctx, node);
        ctx.triggerOutput("flow");
    }

    private void requireRange(int value, int minimum, int maximum, String label) {
        if (value < minimum || value > maximum) throw new IllegalArgumentException(label + " must be between " + minimum + " and " + maximum);
    }

    private void requireFiniteRange(float value, float minimum, float maximum, String label) {
        if (!Float.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(label + " must be between " + minimum + " and " + maximum);
        }
    }

    private boolean canFit(Inventory inventory, ItemStack item) {
        int remaining = item.getAmount();
        int stackSize = Math.min(inventory.getMaxStackSize(), item.getMaxStackSize());
        for (ItemStack existing : inventory.getStorageContents()) {
            if (existing == null || existing.getType().isAir()) {
                remaining -= stackSize;
            } else if (existing.isSimilar(item)) {
                remaining -= Math.max(0, stackSize - existing.getAmount());
            }
            if (remaining <= 0) return true;
        }
        return false;
    }

    private void validateActionBudget(FlowContext ctx, FlowNode node, String operation) {
        Location minimum = ctx.getInputValue(node, "min_location", Location.class, null);
        Location maximum = ctx.getInputValue(node, "max_location", Location.class, null);
        if (minimum != null || maximum != null) {
            if (minimum == null || maximum == null || minimum.getWorld() == null || maximum.getWorld() == null
                || !minimum.getWorld().equals(maximum.getWorld())) {
                throw new IllegalArgumentException("Block region bounds must exist in the same world");
            }
            long sizeX = Math.abs((long) maximum.getBlockX() - minimum.getBlockX()) + 1L;
            long sizeY = Math.abs((long) maximum.getBlockY() - minimum.getBlockY()) + 1L;
            long sizeZ = Math.abs((long) maximum.getBlockZ() - minimum.getBlockZ()) + 1L;
            long volume;
            try {
                volume = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("Block region volume overflow", exception);
            }
            if (volume > MAX_BLOCKS_PER_ACTION) {
                throw new IllegalArgumentException("Block action exceeds the " + MAX_BLOCKS_PER_ACTION + " block limit");
            }
        }
        if ("block_raytrace".equals(operation)) {
            Double distance = ctx.getInputValue(node, "max_distance", Double.class, 50.0);
            if (distance == null || !Double.isFinite(distance) || distance < 0.0 || distance > 1024.0) {
                throw new IllegalArgumentException("Block ray trace distance must be between 0 and 1024");
            }
        }
    }
}
