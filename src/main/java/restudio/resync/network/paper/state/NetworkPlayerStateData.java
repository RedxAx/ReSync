package restudio.resync.network.paper.state;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public record NetworkPlayerStateData(String minecraftVersion, int dataVersion, String gameMode, double health, double absorption, int food, float saturation, float exhaustion, int remainingAir, int fireTicks, int freezeTicks, float experienceProgress, int experienceLevel, int totalExperience, boolean allowFlight, boolean flying, float flySpeed, float walkSpeed, int selectedSlot, List<byte[]> inventory, List<byte[]> armor, byte[] offhand, byte[] cursor, List<byte[]> enderChest, List<Effect> effects, Map<String, Double> attributes, Map<String, List<String>> advancements, Set<String> recipes, List<StatisticValue> statistics, byte[] persistentData, Map<String, LocationValue> locations) {
    public NetworkPlayerStateData {
        minecraftVersion = minecraftVersion == null ? "" : minecraftVersion.trim();
        gameMode = gameMode == null ? "" : gameMode.trim();
        inventory = copy(inventory);
        armor = copy(armor);
        offhand = offhand == null ? new byte[0] : offhand.clone();
        cursor = cursor == null ? new byte[0] : cursor.clone();
        enderChest = copy(enderChest);
        effects = effects == null ? List.of() : List.copyOf(effects);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        Map<String, List<String>> copiedAdvancements = new LinkedHashMap<>();
        if (advancements != null) {
            advancements.forEach((key, value) -> copiedAdvancements.put(key, value == null ? List.of() : List.copyOf(value)));
        }
        advancements = Map.copyOf(copiedAdvancements);
        recipes = recipes == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(recipes));
        statistics = statistics == null ? List.of() : List.copyOf(statistics);
        persistentData = persistentData == null ? new byte[0] : persistentData.clone();
        locations = locations == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(locations));
    }

    @Override
    public byte[] offhand() {
        return offhand.clone();
    }

    @Override
    public byte[] cursor() {
        return cursor.clone();
    }

    @Override
    public List<byte[]> inventory() {
        return copy(inventory);
    }

    @Override
    public List<byte[]> armor() {
        return copy(armor);
    }

    @Override
    public List<byte[]> enderChest() {
        return copy(enderChest);
    }

    @Override
    public byte[] persistentData() {
        return persistentData.clone();
    }

    private static List<byte[]> copy(List<byte[]> values) {
        return values == null ? List.of() : values.stream().map(value -> value == null ? new byte[0] : value.clone()).toList();
    }

    public record Effect(String type, int duration, int amplifier, boolean ambient, boolean particles, boolean icon) {
        public Effect {
            type = type == null ? "" : type.trim();
            if (type.isBlank() || duration < 0 || amplifier < 0) {
                throw new IllegalArgumentException("Network Player Effect Is Invalid");
            }
        }
    }

    public record StatisticValue(String statistic, String qualifierType, String qualifier, int value) {
        public StatisticValue {
            statistic = statistic == null ? "" : statistic.trim();
            qualifierType = qualifierType == null ? "" : qualifierType.trim();
            qualifier = qualifier == null ? "" : qualifier.trim();
            if (statistic.isBlank() || qualifierType.isBlank() || value < 0) {
                throw new IllegalArgumentException("Network Player Statistic Is Invalid");
            }
        }
    }

    public record LocationValue(String worldId, String worldName, double x, double y, double z, float yaw, float pitch) {
        public LocationValue {
            worldId = worldId == null ? "" : worldId.trim();
            worldName = worldName == null ? "" : worldName.trim();
            if (worldId.isBlank() || worldName.isBlank() || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
                throw new IllegalArgumentException("Network Player Location Is Invalid");
            }
        }
    }
}
