package restudio.resync.network.paper.state;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import restudio.resync.network.paper.PaperPlayerDataMutationAdmission;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class NetworkPlayerStateCodec {
    public static final int SCHEMA_VERSION = 2;
    private static final int MAGIC = 0x52535053;
    private static final int FORMAT_VERSION = 2;
    private static final int MAXIMUM_COMPRESSED_BYTES = 16777216;
    private static final int MAXIMUM_UNCOMPRESSED_BYTES = 33554432;
    private static final int MAXIMUM_STRING_BYTES = 4096;
    private static final int MAXIMUM_ITEM_BYTES = 2097152;
    private static final int MAXIMUM_ITEMS = 128;
    private static final int MAXIMUM_EFFECTS = 256;
    private static final int MAXIMUM_ATTRIBUTES = 256;
    private static final int MAXIMUM_ADVANCEMENTS = 4096;
    private static final int MAXIMUM_CRITERIA = 256;
    private static final int MAXIMUM_RECIPES = 32768;
    private static final int MAXIMUM_STATISTICS = 65535;
    private static final int MAXIMUM_PERSISTENT_DATA_BYTES = 4194304;
    private static final int MAXIMUM_LOCATIONS = 256;
    private static final int INVENTORY = 1;
    private static final int ENDER_CHEST = 1 << 1;
    private static final int VITALS = 1 << 2;
    private static final int EXPERIENCE = 1 << 3;
    private static final int MOVEMENT = 1 << 4;
    private static final int EFFECTS = 1 << 5;
    private static final int ATTRIBUTES = 1 << 6;
    private static final int ADVANCEMENTS = 1 << 7;
    private static final int RECIPES = 1 << 8;
    private static final int STATISTICS = 1 << 9;
    private static final int PERSISTENT_DATA = 1 << 10;
    private static final int LOCATION = 1 << 11;
    private static final NamespacedKey LOCATION_HISTORY_KEY = Objects.requireNonNull(NamespacedKey.fromString("resync:network_locations"));

    private NetworkPlayerStateCodec() {
    }

    public static Captured capture(Player player, NetworkPlayerStateConfig config) {
        int families = families(config);
        List<byte[]> inventory = config.inventory() ? items(player.getInventory().getStorageContents()) : List.of();
        List<byte[]> armor = config.inventory() ? items(player.getInventory().getArmorContents()) : List.of();
        byte[] offhand = config.inventory() ? item(player.getInventory().getItemInOffHand()) : new byte[0];
        byte[] cursor = config.inventory() ? item(player.getItemOnCursor()) : new byte[0];
        List<byte[]> enderChest = config.enderChest() ? items(player.getEnderChest().getContents()) : List.of();
        List<NetworkPlayerStateData.Effect> effects = config.effects() ? player.getActivePotionEffects().stream().map(effect -> new NetworkPlayerStateData.Effect(effect.getType().getKey().toString(), effect.getDuration(), effect.getAmplifier(), effect.isAmbient(), effect.hasParticles(), effect.hasIcon())).toList() : List.of();
        Map<String, Double> attributes = new LinkedHashMap<>();
        if (config.attributes()) {
            for (Attribute attribute : Registry.ATTRIBUTE) {
                AttributeInstance instance = player.getAttribute(attribute);
                if (instance != null) {
                    attributes.put(attribute.getKey().toString(), instance.getBaseValue());
                }
            }
        }
        Map<String, List<String>> advancements = config.advancements() ? advancements(player) : Map.of();
        Set<String> recipes = config.recipes() ? player.getDiscoveredRecipes().stream().map(NamespacedKey::toString).collect(Collectors.toCollection(LinkedHashSet::new)) : Set.of();
        List<NetworkPlayerStateData.StatisticValue> statistics = config.statistics() ? statistics(player) : List.of();
        byte[] persistentData = config.persistentData() ? persistentData(player, config) : new byte[0];
        Map<String, NetworkPlayerStateData.LocationValue> locations = config.locationPolicy() == NetworkPlayerLocationPolicy.NEVER ? Map.of() : locations(player, config);
        NetworkPlayerStateData data = new NetworkPlayerStateData(Bukkit.getMinecraftVersion(), Bukkit.getUnsafe().getDataVersion(), player.getGameMode().name(), player.getHealth(), player.getAbsorptionAmount(), player.getFoodLevel(), player.getSaturation(), player.getExhaustion(), player.getRemainingAir(), player.getFireTicks(), player.getFreezeTicks(), player.getExp(), player.getLevel(), player.getTotalExperience(), player.getAllowFlight(), player.isFlying(), player.getFlySpeed(), player.getWalkSpeed(), player.getInventory().getHeldItemSlot(), inventory, armor, offhand, cursor, enderChest, effects, attributes, advancements, recipes, statistics, persistentData, locations);
        return new Captured(families, data);
    }

    public static byte[] encode(Captured captured) {
        if (uncompressedSize(captured) > MAXIMUM_UNCOMPRESSED_BYTES) {
            throw new IllegalArgumentException("Network Player State Expands Beyond Its Limit");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(bytes); DataOutputStream output = new DataOutputStream(gzip)) {
                NetworkPlayerStateData data = captured.data();
                output.writeInt(MAGIC);
                output.writeShort(FORMAT_VERSION);
                output.writeInt(captured.families());
                writeString(output, data.minecraftVersion());
                output.writeInt(data.dataVersion());
                writeString(output, data.gameMode());
                output.writeDouble(data.health());
                output.writeDouble(data.absorption());
                output.writeInt(data.food());
                output.writeFloat(data.saturation());
                output.writeFloat(data.exhaustion());
                output.writeInt(data.remainingAir());
                output.writeInt(data.fireTicks());
                output.writeInt(data.freezeTicks());
                output.writeFloat(data.experienceProgress());
                output.writeInt(data.experienceLevel());
                output.writeInt(data.totalExperience());
                output.writeBoolean(data.allowFlight());
                output.writeBoolean(data.flying());
                output.writeFloat(data.flySpeed());
                output.writeFloat(data.walkSpeed());
                output.writeInt(data.selectedSlot());
                writeItems(output, data.inventory());
                writeItems(output, data.armor());
                writeBytes(output, data.offhand());
                writeBytes(output, data.cursor());
                writeItems(output, data.enderChest());
                output.writeShort(data.effects().size());
                for (NetworkPlayerStateData.Effect effect : data.effects()) {
                    writeString(output, effect.type());
                    output.writeInt(effect.duration());
                    output.writeInt(effect.amplifier());
                    output.writeBoolean(effect.ambient());
                    output.writeBoolean(effect.particles());
                    output.writeBoolean(effect.icon());
                }
                output.writeShort(data.attributes().size());
                for (Map.Entry<String, Double> attribute : data.attributes().entrySet()) {
                    writeString(output, attribute.getKey());
                    output.writeDouble(attribute.getValue());
                }
                if (data.advancements().size() > MAXIMUM_ADVANCEMENTS) {
                    throw new IllegalArgumentException("Network Player Advancements Are Too Large");
                }
                output.writeInt(data.advancements().size());
                for (Map.Entry<String, List<String>> advancement : data.advancements().entrySet()) {
                    writeString(output, advancement.getKey());
                    if (advancement.getValue().size() > MAXIMUM_CRITERIA) {
                        throw new IllegalArgumentException("Network Player Advancement Criteria Are Too Large");
                    }
                    output.writeShort(advancement.getValue().size());
                    for (String criterion : advancement.getValue()) {
                        writeString(output, criterion);
                    }
                }
                if (data.recipes().size() > MAXIMUM_RECIPES) {
                    throw new IllegalArgumentException("Network Player Recipes Are Too Large");
                }
                output.writeInt(data.recipes().size());
                for (String recipe : data.recipes()) {
                    writeString(output, recipe);
                }
                if (data.statistics().size() > MAXIMUM_STATISTICS) {
                    throw new IllegalArgumentException("Network Player Statistics Are Too Large");
                }
                output.writeInt(data.statistics().size());
                for (NetworkPlayerStateData.StatisticValue statistic : data.statistics()) {
                    writeString(output, statistic.statistic());
                    writeString(output, statistic.qualifierType());
                    writeString(output, statistic.qualifier());
                    output.writeInt(statistic.value());
                }
                writeBoundedBytes(output, data.persistentData(), MAXIMUM_PERSISTENT_DATA_BYTES);
                if (data.locations().size() > MAXIMUM_LOCATIONS) {
                    throw new IllegalArgumentException("Network Player Location History Is Too Large");
                }
                output.writeShort(data.locations().size());
                for (Map.Entry<String, NetworkPlayerStateData.LocationValue> entry : data.locations().entrySet()) {
                    NetworkPlayerStateData.LocationValue location = entry.getValue();
                    writeString(output, entry.getKey());
                    writeString(output, location.worldId());
                    writeString(output, location.worldName());
                    output.writeDouble(location.x());
                    output.writeDouble(location.y());
                    output.writeDouble(location.z());
                    output.writeFloat(location.yaw());
                    output.writeFloat(location.pitch());
                }
            }
            byte[] encoded = bytes.toByteArray();
            if (encoded.length > MAXIMUM_COMPRESSED_BYTES) {
                throw new IllegalArgumentException("Network Player State Is Too Large");
            }
            return encoded;
        } catch (IOException exception) {
            throw new IllegalStateException("Encode Network Player State Failed", exception);
        }
    }

    public static Captured decode(byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > MAXIMUM_COMPRESSED_BYTES) {
            throw new IllegalArgumentException("Network Player State Payload Is Invalid");
        }
        try {
            byte[] raw;
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(payload))) {
                raw = gzip.readNBytes(MAXIMUM_UNCOMPRESSED_BYTES + 1);
                if (raw.length > MAXIMUM_UNCOMPRESSED_BYTES || gzip.read() != -1) {
                    throw new IllegalArgumentException("Network Player State Expands Beyond Its Limit");
                }
            }
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(raw));
            if (input.readInt() != MAGIC) {
                throw new IllegalArgumentException("Network Player State Format Is Invalid");
            }
            int formatVersion = input.readUnsignedShort();
            if (formatVersion < 1 || formatVersion > FORMAT_VERSION) {
                throw new IllegalArgumentException("Network Player State Format Is Invalid");
            }
            int families = input.readInt();
            String minecraftVersion = readString(input);
            int dataVersion = input.readInt();
            String gameMode = readString(input);
            double health = input.readDouble();
            double absorption = input.readDouble();
            int food = input.readInt();
            float saturation = input.readFloat();
            float exhaustion = input.readFloat();
            int remainingAir = input.readInt();
            int fireTicks = input.readInt();
            int freezeTicks = input.readInt();
            float experienceProgress = input.readFloat();
            int experienceLevel = input.readInt();
            int totalExperience = input.readInt();
            boolean allowFlight = input.readBoolean();
            boolean flying = input.readBoolean();
            float flySpeed = input.readFloat();
            float walkSpeed = input.readFloat();
            int selectedSlot = input.readInt();
            List<byte[]> inventory = readItems(input);
            List<byte[]> armor = readItems(input);
            byte[] offhand = readBytes(input);
            byte[] cursor = readBytes(input);
            List<byte[]> enderChest = readItems(input);
            int effectCount = input.readUnsignedShort();
            if (effectCount > MAXIMUM_EFFECTS) {
                throw new IllegalArgumentException("Network Player Effects Are Invalid");
            }
            List<NetworkPlayerStateData.Effect> effects = new ArrayList<>(effectCount);
            for (int index = 0; index < effectCount; index++) {
                effects.add(new NetworkPlayerStateData.Effect(readString(input), input.readInt(), input.readInt(), input.readBoolean(), input.readBoolean(), input.readBoolean()));
            }
            int attributeCount = input.readUnsignedShort();
            if (attributeCount > MAXIMUM_ATTRIBUTES) {
                throw new IllegalArgumentException("Network Player Attributes Are Invalid");
            }
            Map<String, Double> attributes = new LinkedHashMap<>();
            for (int index = 0; index < attributeCount; index++) {
                String key = readString(input);
                if (attributes.putIfAbsent(key, input.readDouble()) != null) {
                    throw new IllegalArgumentException("Network Player Attribute Is Duplicated");
                }
            }
            Map<String, List<String>> advancements = new LinkedHashMap<>();
            Set<String> recipes = new LinkedHashSet<>();
            List<NetworkPlayerStateData.StatisticValue> statistics = new ArrayList<>();
            byte[] persistentData = new byte[0];
            Map<String, NetworkPlayerStateData.LocationValue> locations = new LinkedHashMap<>();
            if (formatVersion >= 2) {
                int advancementCount = input.readInt();
                if (advancementCount < 0 || advancementCount > MAXIMUM_ADVANCEMENTS) {
                    throw new IllegalArgumentException("Network Player Advancements Are Invalid");
                }
                for (int index = 0; index < advancementCount; index++) {
                    String key = readString(input);
                    int criteriaCount = input.readUnsignedShort();
                    if (criteriaCount > MAXIMUM_CRITERIA) {
                        throw new IllegalArgumentException("Network Player Advancement Criteria Are Invalid");
                    }
                    List<String> criteria = new ArrayList<>(criteriaCount);
                    for (int criterion = 0; criterion < criteriaCount; criterion++) {
                        criteria.add(readString(input));
                    }
                    if (advancements.putIfAbsent(key, List.copyOf(criteria)) != null) {
                        throw new IllegalArgumentException("Network Player Advancement Is Duplicated");
                    }
                }
                int recipeCount = input.readInt();
                if (recipeCount < 0 || recipeCount > MAXIMUM_RECIPES) {
                    throw new IllegalArgumentException("Network Player Recipes Are Invalid");
                }
                for (int index = 0; index < recipeCount; index++) {
                    if (!recipes.add(readString(input))) {
                        throw new IllegalArgumentException("Network Player Recipe Is Duplicated");
                    }
                }
                int statisticCount = input.readInt();
                if (statisticCount < 0 || statisticCount > MAXIMUM_STATISTICS) {
                    throw new IllegalArgumentException("Network Player Statistics Are Invalid");
                }
                for (int index = 0; index < statisticCount; index++) {
                    statistics.add(new NetworkPlayerStateData.StatisticValue(readString(input), readString(input), readString(input), input.readInt()));
                }
                persistentData = readBoundedBytes(input, MAXIMUM_PERSISTENT_DATA_BYTES);
                int locationCount = input.readUnsignedShort();
                if (locationCount > MAXIMUM_LOCATIONS) {
                    throw new IllegalArgumentException("Network Player Location History Is Invalid");
                }
                for (int index = 0; index < locationCount; index++) {
                    String nodeId = readString(input);
                    NetworkPlayerStateData.LocationValue location = new NetworkPlayerStateData.LocationValue(readString(input), readString(input), input.readDouble(), input.readDouble(), input.readDouble(), input.readFloat(), input.readFloat());
                    if (locations.putIfAbsent(nodeId, location) != null) {
                        throw new IllegalArgumentException("Network Player Location Is Duplicated");
                    }
                }
            }
            if (input.available() != 0) {
                throw new IllegalArgumentException("Network Player State Has Trailing Data");
            }
            return new Captured(families, new NetworkPlayerStateData(minecraftVersion, dataVersion, gameMode, health, absorption, food, saturation, exhaustion, remainingAir, fireTicks, freezeTicks, experienceProgress, experienceLevel, totalExperience, allowFlight, flying, flySpeed, walkSpeed, selectedSlot, inventory, armor, offhand, cursor, enderChest, effects, attributes, advancements, recipes, statistics, persistentData, locations));
        } catch (EOFException exception) {
            throw new IllegalArgumentException("Network Player State Ended Early", exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Decode Network Player State Failed", exception);
        }
    }

    public static void validate(Captured captured, NetworkPlayerStateConfig config) {
        Objects.requireNonNull(captured, "captured");
        Objects.requireNonNull(config, "config");
        if (captured.families() != families(config)) {
            throw new IllegalArgumentException("Network Player State Families Do Not Match The Target Realm");
        }
        if (captured.data().dataVersion() > Bukkit.getUnsafe().getDataVersion()) {
            throw new IllegalArgumentException("Network Player State Comes From A Newer Minecraft Data Version");
        }
    }

    public static Location apply(Player player, Captured captured, NetworkPlayerStateConfig config) {
        return apply(player, captured, config, PaperPlayerDataMutationAdmission.shared());
    }

    public static Location apply(Player player, Captured captured, NetworkPlayerStateConfig config,
                                 PaperPlayerDataMutationAdmission playerDataAdmission) {
        Objects.requireNonNull(playerDataAdmission, "playerDataAdmission");
        if (player == null || player.getWorld() == null || player.getWorld().getWorldFolder() == null) {
            throw new IllegalStateException("Network Player World Root Is Unavailable");
        }
        try (PaperPlayerDataMutationAdmission.Lease ignored = playerDataAdmission.acquirePdc(
            "network-player-state-apply:" + player.getUniqueId(), player.getUniqueId(),
            player.getWorld().getWorldFolder().toPath())) {
            return applyAdmitted(player, captured, config);
        }
    }

    private static Location applyAdmitted(Player player, Captured captured, NetworkPlayerStateConfig config) {
        validate(captured, config);
        ApplyPlan plan = preflight(player, captured.data(), config);
        if (families(config) == 0) {
            return applyPlan(player, config, plan);
        }
        Captured previous = capture(player, config);
        ApplyPlan rollback = preflight(player, previous.data(), config);
        byte[] persistentData = config.persistentData() || config.locationPolicy() != NetworkPlayerLocationPolicy.NEVER
            ? persistentDataSnapshot(player) : null;
        try {
            return applyPlan(player, config, plan);
        } catch (RuntimeException | Error failure) {
            try {
                applyPlan(player, config, rollback);
            } catch (RuntimeException | Error compensationFailure) {
                failure.addSuppressed(compensationFailure);
            }
            if (persistentData != null) {
                try {
                    restorePersistentData(player, persistentData);
                } catch (RuntimeException | Error compensationFailure) {
                    failure.addSuppressed(compensationFailure);
                }
            }
            throw failure;
        }
    }

    private static ApplyPlan preflight(Player player, NetworkPlayerStateData data, NetworkPlayerStateConfig config) {
        GameMode gameMode = null;
        boolean allowFlight = false;
        boolean flying = false;
        float flySpeed = 0;
        float walkSpeed = 0;
        int selectedSlot = 0;
        if (config.movement()) {
            try {
                gameMode = GameMode.valueOf(data.gameMode());
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Network Player Game Mode Is Not Compatible With This Server", exception);
            }
            allowFlight = data.allowFlight();
            flying = data.flying();
            if (!Float.isFinite(data.flySpeed()) || !Float.isFinite(data.walkSpeed())) {
                throw new IllegalArgumentException("Network Player Movement Is Invalid");
            }
            flySpeed = Math.clamp(data.flySpeed(), -1f, 1f);
            walkSpeed = Math.clamp(data.walkSpeed(), -1f, 1f);
            selectedSlot = Math.clamp(data.selectedSlot(), 0, 8);
        }

        double health = 0;
        double absorption = 0;
        int food = 0;
        float saturation = 0;
        float exhaustion = 0;
        int remainingAir = 0;
        int fireTicks = 0;
        int freezeTicks = 0;
        if (config.vitals()) {
            if (!Double.isFinite(data.health()) || !Double.isFinite(data.absorption()) || !Float.isFinite(data.saturation()) || !Float.isFinite(data.exhaustion())) {
                throw new IllegalArgumentException("Network Player Vitals Are Invalid");
            }
            AttributeInstance maximumHealth = player.getAttribute(Attribute.MAX_HEALTH);
            double maximum = maximumHealth == null ? 20 : maximumHealth.getValue();
            if (!Double.isFinite(maximum)) {
                throw new IllegalArgumentException("Network Player Maximum Health Is Invalid");
            }
            int maximumAir = player.getMaximumAir();
            int maximumFreeze = player.getMaxFreezeTicks();
            if (maximumAir < 0 || maximumFreeze < 0) {
                throw new IllegalArgumentException("Network Player Vitals Are Invalid");
            }
            health = Math.clamp(data.health(), 0.01, Math.max(0.01, maximum));
            absorption = Math.max(0, data.absorption());
            food = Math.clamp(data.food(), 0, 20);
            saturation = Math.clamp(data.saturation(), 0, 20);
            exhaustion = Math.max(0, data.exhaustion());
            remainingAir = Math.clamp(data.remainingAir(), 0, maximumAir);
            fireTicks = Math.max(0, data.fireTicks());
            freezeTicks = Math.clamp(data.freezeTicks(), 0, maximumFreeze);
        }

        int experienceLevel = 0;
        int totalExperience = 0;
        float experienceProgress = 0;
        if (config.experience()) {
            if (!Float.isFinite(data.experienceProgress())) {
                throw new IllegalArgumentException("Network Player Experience Is Invalid");
            }
            experienceLevel = Math.max(0, data.experienceLevel());
            totalExperience = Math.max(0, data.totalExperience());
            experienceProgress = Math.clamp(data.experienceProgress(), 0, 1);
        }

        ItemStack[] inventory = config.inventory() ? items(data.inventory(), player.getInventory().getStorageContents().length) : null;
        ItemStack[] armor = config.inventory() ? items(data.armor(), player.getInventory().getArmorContents().length) : null;
        ItemStack offhand = config.inventory() ? item(data.offhand()) : null;
        ItemStack cursor = config.inventory() ? item(data.cursor()) : null;
        ItemStack[] enderChest = config.enderChest() ? items(data.enderChest(), player.getEnderChest().getSize()) : null;
        List<PotionEffect> effects = config.effects() ? potionEffects(data.effects()) : List.of();
        List<AttributeUpdate> attributes = config.attributes() ? attributeUpdates(player, data.attributes()) : List.of();
        AdvancementPlan advancements = config.advancements() ? advancementPlan(player, data.advancements()) : null;
        List<NamespacedKey> recipes = config.recipes() ? recipePlan(data.recipes()) : List.of();
        List<StatisticUpdate> statistics = config.statistics() ? statisticPlan(data.statistics()) : List.of();
        byte[] persistentData = config.persistentData() ? persistentDataPlan(player, config, data.persistentData()) : null;
        byte[] locationHistory = config.locationPolicy() != NetworkPlayerLocationPolicy.NEVER ? encodeLocations(data.locations()) : null;
        Location destination = location(config, data.locations());
        return new ApplyPlan(gameMode, allowFlight, flying, flySpeed, walkSpeed, selectedSlot, health, absorption, food, saturation, exhaustion,
            remainingAir, fireTicks, freezeTicks, experienceLevel, totalExperience, experienceProgress, inventory, armor,
            offhand, cursor, enderChest, effects, attributes, advancements, recipes, statistics, persistentData,
            locationHistory, destination);
    }

    private static Location applyPlan(Player player, NetworkPlayerStateConfig config, ApplyPlan plan) {
        if (config.attributes()) {
            for (AttributeUpdate update : plan.attributes()) {
                update.instance().setBaseValue(update.value());
            }
        }
        if (config.movement()) {
            player.setGameMode(plan.gameMode());
            player.setAllowFlight(plan.allowFlight());
            player.setFlying(plan.allowFlight() && plan.flying());
            player.setFlySpeed(plan.flySpeed());
            player.setWalkSpeed(plan.walkSpeed());
            player.getInventory().setHeldItemSlot(plan.selectedSlot());
        }
        if (config.vitals()) {
            player.setHealth(plan.health());
            player.setAbsorptionAmount(plan.absorption());
            player.setFoodLevel(plan.food());
            player.setSaturation(plan.saturation());
            player.setExhaustion(plan.exhaustion());
            player.setRemainingAir(plan.remainingAir());
            player.setFireTicks(plan.fireTicks());
            player.setFreezeTicks(plan.freezeTicks());
        }
        if (config.experience()) {
            player.setLevel(plan.experienceLevel());
            player.setTotalExperience(plan.totalExperience());
            player.setExp(plan.experienceProgress());
        }
        if (config.inventory()) {
            player.getInventory().setStorageContents(plan.inventory());
            player.getInventory().setArmorContents(plan.armor());
            player.getInventory().setItemInOffHand(plan.offhand());
            player.setItemOnCursor(plan.cursor());
        }
        if (config.enderChest()) {
            player.getEnderChest().setContents(plan.enderChest());
        }
        if (config.effects()) {
            player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
            plan.effects().forEach(effect -> player.addPotionEffect(effect));
        }
        if (config.advancements()) {
            applyAdvancements(plan.advancements());
        }
        if (config.recipes()) {
            applyRecipes(player, plan.recipes());
        }
        if (config.statistics()) {
            applyStatistics(player, plan.statistics());
        }
        if (config.persistentData()) {
            applyPersistentData(player, config, plan.persistentData());
        }
        if (config.locationPolicy() != NetworkPlayerLocationPolicy.NEVER) {
            writeLocations(player, plan.locationHistory());
        }
        player.updateInventory();
        return plan.destination();
    }

    private record ApplyPlan(GameMode gameMode, boolean allowFlight, boolean flying, float flySpeed, float walkSpeed,
                             int selectedSlot, double health, double absorption, int food, float saturation,
                             float exhaustion, int remainingAir, int fireTicks, int freezeTicks, int experienceLevel,
                             int totalExperience, float experienceProgress, ItemStack[] inventory, ItemStack[] armor,
                             ItemStack offhand, ItemStack cursor, ItemStack[] enderChest, List<PotionEffect> effects,
                             List<AttributeUpdate> attributes, AdvancementPlan advancements, List<NamespacedKey> recipes,
                             List<StatisticUpdate> statistics, byte[] persistentData, byte[] locationHistory,
                             Location destination) {
    }

    private record AttributeUpdate(AttributeInstance instance, double value) {
    }

    private record AdvancementPlan(List<AdvancementProgress> resets, List<AdvancementUpdate> updates) {
    }

    private record AdvancementUpdate(AdvancementProgress progress, List<String> criteria) {
    }

    private record StatisticUpdate(Statistic statistic, Material material, EntityType entityType, int value) {
    }

    public static int families(NetworkPlayerStateConfig config) {
        int flags = 0;
        flags |= config.inventory() ? INVENTORY : 0;
        flags |= config.enderChest() ? ENDER_CHEST : 0;
        flags |= config.vitals() ? VITALS : 0;
        flags |= config.experience() ? EXPERIENCE : 0;
        flags |= config.movement() ? MOVEMENT : 0;
        flags |= config.effects() ? EFFECTS : 0;
        flags |= config.attributes() ? ATTRIBUTES : 0;
        flags |= config.advancements() ? ADVANCEMENTS : 0;
        flags |= config.recipes() ? RECIPES : 0;
        flags |= config.statistics() ? STATISTICS : 0;
        flags |= config.persistentData() ? PERSISTENT_DATA : 0;
        flags |= config.locationPolicy() != NetworkPlayerLocationPolicy.NEVER ? LOCATION : 0;
        return flags;
    }

    private static Map<String, List<String>> advancements(Player player) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        Bukkit.advancementIterator().forEachRemaining(advancement -> {
            AdvancementProgress progress = player.getAdvancementProgress(advancement);
            if (!progress.getAwardedCriteria().isEmpty()) {
                values.put(advancement.getKey().toString(), progress.getAwardedCriteria().stream().sorted().toList());
            }
        });
        return Map.copyOf(values);
    }

    private static List<AttributeUpdate> attributeUpdates(Player player, Map<String, Double> desired) {
        List<AttributeUpdate> updates = new ArrayList<>(desired.size());
        for (Map.Entry<String, Double> value : desired.entrySet()) {
            NamespacedKey key = NamespacedKey.fromString(value.getKey());
            Attribute attribute = key == null ? null : Registry.ATTRIBUTE.get(key);
            AttributeInstance instance = attribute == null ? null : player.getAttribute(attribute);
            if (instance == null || !Double.isFinite(value.getValue())) {
                throw new IllegalArgumentException("Network Player Attribute Is Not Compatible With This Server");
            }
            updates.add(new AttributeUpdate(instance, value.getValue()));
        }
        return List.copyOf(updates);
    }

    private static List<PotionEffect> potionEffects(List<NetworkPlayerStateData.Effect> desired) {
        List<PotionEffect> effects = new ArrayList<>(desired.size());
        Set<PotionEffectType> seen = new LinkedHashSet<>();
        for (NetworkPlayerStateData.Effect value : desired) {
            NamespacedKey key = NamespacedKey.fromString(value.type());
            PotionEffectType type = key == null ? null : Registry.MOB_EFFECT.get(key);
            if (type == null || !seen.add(type)) {
                throw new IllegalArgumentException("Network Player Effect Is Not Compatible With This Server");
            }
            effects.add(new PotionEffect(type, value.duration(), value.amplifier(), value.ambient(), value.particles(), value.icon()));
        }
        return List.copyOf(effects);
    }

    private static AdvancementPlan advancementPlan(Player player, Map<String, List<String>> desired) {
        List<AdvancementProgress> resets = new ArrayList<>();
        Bukkit.advancementIterator().forEachRemaining(advancement -> resets.add(player.getAdvancementProgress(advancement)));
        List<AdvancementUpdate> updates = new ArrayList<>(desired.size());
        for (Map.Entry<String, List<String>> entry : desired.entrySet()) {
            NamespacedKey key = NamespacedKey.fromString(entry.getKey());
            Advancement advancement = key == null ? null : Bukkit.getAdvancement(key);
            if (advancement == null) {
                throw new IllegalArgumentException("Network Player Advancement Is Not Available On This Server");
            }
            Set<String> criteria = new LinkedHashSet<>();
            for (String criterion : entry.getValue()) {
                if (!advancement.getCriteria().contains(criterion) || !criteria.add(criterion)) {
                    throw new IllegalArgumentException("Network Player Advancement Criterion Is Not Compatible With This Server");
                }
            }
            updates.add(new AdvancementUpdate(player.getAdvancementProgress(advancement), List.copyOf(criteria)));
        }
        return new AdvancementPlan(List.copyOf(resets), List.copyOf(updates));
    }

    private static void applyAdvancements(AdvancementPlan plan) {
        for (AdvancementProgress progress : plan.resets()) {
            List.copyOf(progress.getAwardedCriteria()).forEach(progress::revokeCriteria);
        }
        for (AdvancementUpdate update : plan.updates()) {
            for (String criterion : update.criteria()) {
                if (!update.progress().awardCriteria(criterion)) {
                    throw new IllegalStateException("Network Player Advancement Could Not Be Applied");
                }
            }
        }
    }

    private static List<NamespacedKey> recipePlan(Set<String> desired) {
        List<NamespacedKey> recipes = new ArrayList<>(desired.size());
        for (String value : desired) {
            NamespacedKey key = NamespacedKey.fromString(value);
            if (key == null || Bukkit.getRecipe(key) == null) {
                throw new IllegalArgumentException("Network Player Recipe Is Not Available On This Server");
            }
            recipes.add(key);
        }
        return List.copyOf(recipes);
    }

    private static void applyRecipes(Player player, List<NamespacedKey> desired) {
        player.undiscoverRecipes(player.getDiscoveredRecipes());
        player.discoverRecipes(desired);
    }

    private static List<NetworkPlayerStateData.StatisticValue> statistics(Player player) {
        List<NetworkPlayerStateData.StatisticValue> values = new ArrayList<>();
        for (Statistic statistic : Statistic.values()) {
            switch (statistic.getType()) {
                case UNTYPED -> addStatistic(values, statistic, "NONE", "-", statisticValue(player, statistic, null, null));
                case ITEM, BLOCK -> {
                    for (Material material : Material.values()) {
                        if (statistic.getType() == Statistic.Type.ITEM && !material.isItem() || statistic.getType() == Statistic.Type.BLOCK && !material.isBlock()) {
                            continue;
                        }
                        addStatistic(values, statistic, statistic.getType().name(), material.getKey().toString(), statisticValue(player, statistic, material, null));
                    }
                }
                case ENTITY -> {
                    for (EntityType entityType : Registry.ENTITY_TYPE) {
                        addStatistic(values, statistic, "ENTITY", entityType.getKey().toString(), statisticValue(player, statistic, null, entityType));
                    }
                }
            }
        }
        if (values.size() > MAXIMUM_STATISTICS) {
            throw new IllegalArgumentException("Network Player Statistics Are Too Large");
        }
        return List.copyOf(values);
    }

    private static void addStatistic(List<NetworkPlayerStateData.StatisticValue> values, Statistic statistic, String qualifierType, String qualifier, int value) {
        if (value > 0) {
            values.add(new NetworkPlayerStateData.StatisticValue(statistic.name(), qualifierType, qualifier, value));
        }
    }

    private static int statisticValue(Player player, Statistic statistic, Material material, EntityType entityType) {
        try {
            if (material != null) {
                return player.getStatistic(statistic, material);
            }
            if (entityType != null) {
                return player.getStatistic(statistic, entityType);
            }
            return player.getStatistic(statistic);
        } catch (IllegalArgumentException exception) {
            return 0;
        }
    }

    private static List<StatisticUpdate> statisticPlan(List<NetworkPlayerStateData.StatisticValue> desired) {
        if (desired.size() > MAXIMUM_STATISTICS) {
            throw new IllegalArgumentException("Network Player Statistics Are Too Large");
        }
        List<StatisticUpdate> updates = new ArrayList<>(desired.size());
        Set<String> identities = new LinkedHashSet<>();
        for (NetworkPlayerStateData.StatisticValue value : desired) {
            Statistic statistic;
            try {
                statistic = Statistic.valueOf(value.statistic());
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Network Player Statistic Is Not Available On This Server", exception);
            }
            String identity = value.statistic() + "\u0000" + value.qualifierType() + "\u0000" + value.qualifier();
            if (!identities.add(identity)) {
                throw new IllegalArgumentException("Network Player Statistic Is Duplicated");
            }
            switch (value.qualifierType()) {
                case "NONE" -> {
                    if (statistic.getType() != Statistic.Type.UNTYPED || !value.qualifier().equals("-")) {
                        throw new IllegalArgumentException("Network Player Statistic Is Not Compatible With This Server");
                    }
                    updates.add(new StatisticUpdate(statistic, null, null, value.value()));
                }
                case "ITEM", "BLOCK" -> {
                    if (statistic.getType() != Statistic.Type.valueOf(value.qualifierType())) {
                        throw new IllegalArgumentException("Network Player Statistic Is Not Compatible With This Server");
                    }
                    Material material = Material.matchMaterial(value.qualifier());
                    if (material == null || "ITEM".equals(value.qualifierType()) && !material.isItem() || "BLOCK".equals(value.qualifierType()) && !material.isBlock()) {
                        throw new IllegalArgumentException("Network Player Statistic Material Is Not Available On This Server");
                    }
                    updates.add(new StatisticUpdate(statistic, material, null, value.value()));
                }
                case "ENTITY" -> {
                    if (statistic.getType() != Statistic.Type.ENTITY) {
                        throw new IllegalArgumentException("Network Player Statistic Is Not Compatible With This Server");
                    }
                    NamespacedKey key = NamespacedKey.fromString(value.qualifier());
                    EntityType entityType = key == null ? null : Registry.ENTITY_TYPE.get(key);
                    if (entityType == null) {
                        throw new IllegalArgumentException("Network Player Statistic Entity Is Not Available On This Server");
                    }
                    updates.add(new StatisticUpdate(statistic, null, entityType, value.value()));
                }
                default -> throw new IllegalArgumentException("Network Player Statistic Qualifier Is Invalid");
            }
        }
        return List.copyOf(updates);
    }

    private static void applyStatistics(Player player, List<StatisticUpdate> desired) {
        statisticPlan(statistics(player)).forEach(value -> setStatistic(player, value, 0));
        desired.forEach(value -> setStatistic(player, value, value.value()));
    }

    private static void setStatistic(Player player, StatisticUpdate value, int amount) {
        try {
            if (value.material() != null) {
                player.setStatistic(value.statistic(), value.material(), amount);
            } else if (value.entityType() != null) {
                player.setStatistic(value.statistic(), value.entityType(), amount);
            } else {
                player.setStatistic(value.statistic(), amount);
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Network Player Statistic Is Not Compatible With This Server", exception);
        }
    }

    private static byte[] persistentData(Player player, NetworkPlayerStateConfig config) {
        try {
            PersistentDataContainer source = player.getPersistentDataContainer();
            PersistentDataContainer filtered = source.getAdapterContext().newPersistentDataContainer();
            filtered.readFromBytes(source.serializeToBytes());
            for (NamespacedKey key : Set.copyOf(filtered.getKeys())) {
                if (!config.persistentDataNamespaces().contains(key.getNamespace()) || key.equals(LOCATION_HISTORY_KEY)) {
                    filtered.remove(key);
                }
            }
            byte[] value = filtered.serializeToBytes();
            if (value.length > MAXIMUM_PERSISTENT_DATA_BYTES) {
                throw new IllegalArgumentException("Network Player Persistent Data Is Too Large");
            }
            return value;
        } catch (IOException exception) {
            throw new IllegalStateException("Capture Network Player Persistent Data Failed", exception);
        }
    }

    private static byte[] persistentDataPlan(Player player, NetworkPlayerStateConfig config, byte[] payload) {
        if (payload.length > MAXIMUM_PERSISTENT_DATA_BYTES) {
            throw new IllegalArgumentException("Network Player Persistent Data Is Too Large");
        }
        try {
            PersistentDataContainer validated = player.getPersistentDataContainer().getAdapterContext().newPersistentDataContainer();
            if (payload.length > 0) {
                validated.readFromBytes(payload);
            }
            if (validated.getKeys().stream().anyMatch(key -> !config.persistentDataNamespaces().contains(key.getNamespace()) || key.equals(LOCATION_HISTORY_KEY))) {
                throw new IllegalArgumentException("Network Player Persistent Data Exceeds The Target Allowlist");
            }
            return payload.clone();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Network Player Persistent Data Is Invalid", exception);
        }
    }

    private static void applyPersistentData(Player player, NetworkPlayerStateConfig config, byte[] payload) {
        try {
            PersistentDataContainer target = player.getPersistentDataContainer();
            for (NamespacedKey key : Set.copyOf(target.getKeys())) {
                if (config.persistentDataNamespaces().contains(key.getNamespace()) && !key.equals(LOCATION_HISTORY_KEY)) {
                    target.remove(key);
                }
            }
            if (payload.length > 0) {
                target.readFromBytes(payload, false);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Apply Network Player Persistent Data Failed", exception);
        }
    }

    private static byte[] persistentDataSnapshot(Player player) {
        try {
            return player.getPersistentDataContainer().serializeToBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Capture Network Player Persistent Data Snapshot Failed", exception);
        }
    }

    private static void restorePersistentData(Player player, byte[] payload) {
        try {
            player.getPersistentDataContainer().readFromBytes(payload, true);
        } catch (IOException exception) {
            throw new IllegalStateException("Restore Network Player Persistent Data Snapshot Failed", exception);
        }
    }

    private static Map<String, NetworkPlayerStateData.LocationValue> locations(Player player, NetworkPlayerStateConfig config) {
        Map<String, NetworkPlayerStateData.LocationValue> locations = readLocations(player);
        NetworkPlayerStateData.LocationValue current = location(player.getLocation());
        locations.put(config.nodeId(), current);
        locations.put("@current", current);
        return Map.copyOf(locations);
    }

    private static NetworkPlayerStateData.LocationValue location(Location location) {
        World world = Objects.requireNonNull(location.getWorld(), "Player World Is Required");
        return new NetworkPlayerStateData.LocationValue(world.getUID().toString(), world.getName(), location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }

    private static Location location(NetworkPlayerStateConfig config, Map<String, NetworkPlayerStateData.LocationValue> locations) {
        NetworkPlayerStateData.LocationValue value = switch (config.locationPolicy()) {
            case NEVER -> null;
            case SAME_SERVER_ONLY, REALM_RETURN_POINT -> locations.get(config.nodeId());
            case EXACT_COMPATIBLE_WORLD -> locations.get("@current");
        };
        if (value == null) {
            return null;
        }
        World world;
        try {
            world = Bukkit.getWorld(UUID.fromString(value.worldId()));
        } catch (IllegalArgumentException exception) {
            world = null;
        }
        if (world == null) {
            world = Bukkit.getWorld(value.worldName());
        }
        if (world == null || value.y() < world.getMinHeight() || value.y() >= world.getMaxHeight()) {
            return null;
        }
        Location destination = new Location(world, value.x(), value.y(), value.z(), value.yaw(), value.pitch());
        return world.getWorldBorder().isInside(destination) ? destination : null;
    }

    private static Map<String, NetworkPlayerStateData.LocationValue> readLocations(Player player) {
        byte[] payload = player.getPersistentDataContainer().get(LOCATION_HISTORY_KEY, PersistentDataType.BYTE_ARRAY);
        if (payload == null || payload.length == 0) {
            return new LinkedHashMap<>();
        }
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload));
            int count = input.readUnsignedShort();
            if (count > MAXIMUM_LOCATIONS) {
                throw new IllegalArgumentException("Network Player Location History Is Invalid");
            }
            Map<String, NetworkPlayerStateData.LocationValue> locations = new LinkedHashMap<>();
            for (int index = 0; index < count; index++) {
                String nodeId = readString(input);
                if (locations.putIfAbsent(nodeId, new NetworkPlayerStateData.LocationValue(readString(input), readString(input), input.readDouble(), input.readDouble(), input.readDouble(), input.readFloat(), input.readFloat())) != null) {
                    throw new IllegalArgumentException("Network Player Location History Is Duplicated");
                }
            }
            if (input.available() != 0) {
                throw new IllegalArgumentException("Network Player Location History Has Trailing Data");
            }
            return locations;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Decode Network Player Location History Failed", exception);
        }
    }

    private static byte[] encodeLocations(Map<String, NetworkPlayerStateData.LocationValue> locations) {
        if (locations.size() > MAXIMUM_LOCATIONS) {
            throw new IllegalArgumentException("Network Player Location History Is Too Large");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeShort(locations.size());
            for (Map.Entry<String, NetworkPlayerStateData.LocationValue> entry : locations.entrySet()) {
                NetworkPlayerStateData.LocationValue value = entry.getValue();
                writeString(output, entry.getKey());
                writeString(output, value.worldId());
                writeString(output, value.worldName());
                output.writeDouble(value.x());
                output.writeDouble(value.y());
                output.writeDouble(value.z());
                output.writeFloat(value.yaw());
                output.writeFloat(value.pitch());
            }
            output.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Encode Network Player Location History Failed", exception);
        }
    }

    private static void writeLocations(Player player, byte[] payload) {
        player.getPersistentDataContainer().set(LOCATION_HISTORY_KEY, PersistentDataType.BYTE_ARRAY, payload);
    }

    private static long uncompressedSize(Captured captured) {
        NetworkPlayerStateData data = captured.data();
        long size = Integer.BYTES * 3L + Short.BYTES + stringSize(data.minecraftVersion()) + stringSize(data.gameMode()) + Double.BYTES * 2L + Integer.BYTES * 12L + Byte.BYTES * 2L;
        size += itemsSize(data.inventory()) + itemsSize(data.armor()) + bytesSize(data.offhand()) + bytesSize(data.cursor()) + itemsSize(data.enderChest());
        size += Short.BYTES;
        for (NetworkPlayerStateData.Effect effect : data.effects()) {
            size += stringSize(effect.type()) + Integer.BYTES * 2L + Byte.BYTES * 3L;
        }
        size += Short.BYTES;
        for (String attribute : data.attributes().keySet()) {
            size += stringSize(attribute) + Double.BYTES;
        }
        size += Integer.BYTES;
        for (Map.Entry<String, List<String>> advancement : data.advancements().entrySet()) {
            size += stringSize(advancement.getKey()) + Short.BYTES;
            size += advancement.getValue().stream().mapToLong(NetworkPlayerStateCodec::stringSize).sum();
        }
        size += Integer.BYTES + data.recipes().stream().mapToLong(NetworkPlayerStateCodec::stringSize).sum();
        size += Integer.BYTES;
        for (NetworkPlayerStateData.StatisticValue statistic : data.statistics()) {
            size += stringSize(statistic.statistic()) + stringSize(statistic.qualifierType()) + stringSize(statistic.qualifier()) + Integer.BYTES;
        }
        size += bytesSize(data.persistentData()) + Short.BYTES;
        for (Map.Entry<String, NetworkPlayerStateData.LocationValue> location : data.locations().entrySet()) {
            size += stringSize(location.getKey()) + stringSize(location.getValue().worldId()) + stringSize(location.getValue().worldName()) + Double.BYTES * 3L + Float.BYTES * 2L;
        }
        return size;
    }

    private static long itemsSize(List<byte[]> values) {
        return Short.BYTES + values.stream().mapToLong(NetworkPlayerStateCodec::bytesSize).sum();
    }

    private static long bytesSize(byte[] value) {
        return Integer.BYTES + value.length;
    }

    private static long stringSize(String value) {
        return Short.BYTES + value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static List<byte[]> items(ItemStack[] items) {
        List<byte[]> serialized = new ArrayList<>(items.length);
        for (ItemStack item : items) {
            serialized.add(item(item));
        }
        return List.copyOf(serialized);
    }

    private static byte[] item(ItemStack item) {
        return item == null || item.getType().isAir() ? new byte[0] : item.serializeAsBytes();
    }

    private static ItemStack[] items(List<byte[]> values, int expectedSize) {
        if (values.size() != expectedSize) {
            throw new IllegalArgumentException("Network Player Inventory Size Is Not Compatible With This Server");
        }
        ItemStack[] items = new ItemStack[values.size()];
        for (int index = 0; index < values.size(); index++) {
            items[index] = item(values.get(index));
        }
        return items;
    }

    private static ItemStack item(byte[] value) {
        return value == null || value.length == 0 ? null : ItemStack.deserializeBytes(value);
    }

    private static void writeItems(DataOutputStream output, List<byte[]> items) throws IOException {
        if (items.size() > MAXIMUM_ITEMS) {
            throw new IllegalArgumentException("Network Player Inventory Is Too Large");
        }
        output.writeShort(items.size());
        for (byte[] item : items) {
            writeBytes(output, item);
        }
    }

    private static List<byte[]> readItems(DataInputStream input) throws IOException {
        int count = input.readUnsignedShort();
        if (count > MAXIMUM_ITEMS) {
            throw new IllegalArgumentException("Network Player Inventory Is Invalid");
        }
        List<byte[]> items = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            items.add(readBytes(input));
        }
        return List.copyOf(items);
    }

    private static void writeBytes(DataOutputStream output, byte[] value) throws IOException {
        if (value.length > MAXIMUM_ITEM_BYTES) {
            throw new IllegalArgumentException("Network Player Item Is Too Large");
        }
        output.writeInt(value.length);
        output.write(value);
    }

    private static byte[] readBytes(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAXIMUM_ITEM_BYTES || length > input.available()) {
            throw new IllegalArgumentException("Network Player Item Is Invalid");
        }
        byte[] value = input.readNBytes(length);
        if (value.length != length) {
            throw new EOFException("Network Player Item Ended Early");
        }
        return value;
    }

    private static void writeBoundedBytes(DataOutputStream output, byte[] value, int maximum) throws IOException {
        if (value.length > maximum) {
            throw new IllegalArgumentException("Network Player State Binary Data Is Too Large");
        }
        output.writeInt(value.length);
        output.write(value);
    }

    private static byte[] readBoundedBytes(DataInputStream input, int maximum) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > maximum || length > input.available()) {
            throw new IllegalArgumentException("Network Player State Binary Data Is Invalid");
        }
        byte[] value = input.readNBytes(length);
        if (value.length != length) {
            throw new EOFException("Network Player State Binary Data Ended Early");
        }
        return value;
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAXIMUM_STRING_BYTES) {
            throw new IllegalArgumentException("Network Player State Text Is Invalid");
        }
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort();
        if (length == 0 || length > MAXIMUM_STRING_BYTES || length > input.available()) {
            throw new IllegalArgumentException("Network Player State Text Is Invalid");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Network Player State Text Ended Early");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public record Captured(int families, NetworkPlayerStateData data) {
        public Captured {
            if (families < 0 || data == null) {
                throw new IllegalArgumentException("Network Player State Capture Is Invalid");
            }
        }
    }
}
