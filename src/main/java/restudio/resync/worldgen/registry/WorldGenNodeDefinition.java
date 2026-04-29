package restudio.resync.worldgen.registry;

import restudio.flow.data.FlowDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class WorldGenNodeDefinition {
    private final String id;
    private final String displayName;
    private final String category;
    private final List<PinDefinition> inputs;
    private final List<PinDefinition> outputs;
    private final int color;
    private final int priority;
    private final String description;
    private final boolean hidden;

    private WorldGenNodeDefinition(Builder builder) {
        this.id = builder.id;
        this.displayName = builder.displayName;
        this.category = builder.category;
        this.inputs = List.copyOf(builder.inputs);
        this.outputs = List.copyOf(builder.outputs);
        this.color = builder.color;
        this.priority = builder.priority;
        this.description = builder.description;
        this.hidden = builder.hidden;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getCategory() {
        return category;
    }

    public List<PinDefinition> getInputs() {
        return inputs;
    }

    public List<PinDefinition> getOutputs() {
        return outputs;
    }

    public int getColor() {
        return color;
    }

    public int getPriority() {
        return priority;
    }

    public String getDescription() {
        return description;
    }

    public boolean isHidden() {
        return hidden;
    }

    public PinDefinition input(String name) {
        return inputs.stream().filter(pin -> pin.name().equals(name)).findFirst().orElse(null);
    }

    public PinDefinition output(String name) {
        return outputs.stream().filter(pin -> pin.name().equals(name)).findFirst().orElse(null);
    }

    public static Builder builder(String id, String displayName) {
        return new Builder(id, displayName);
    }

    public static class Builder {
        private final String id;
        private final String displayName;
        private String category = "World Gen";
        private final List<PinDefinition> inputs = new ArrayList<>();
        private final List<PinDefinition> outputs = new ArrayList<>();
        private int color = 0xFF228B22;
        private int priority = 2000;
        private String description = "";
        private boolean hidden;

        private Builder(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public Builder input(String name, FlowDataType dataType, Object defaultValue, String widgetType) {
            inputs.add(new PinDefinition(name, dataType, PinDirection.INPUT, defaultValue, widgetType, Map.of(), "", null, List.of()));
            return this;
        }

        public Builder input(String name, FlowDataType dataType, Object defaultValue, String widgetType, List<String> options) {
            inputs.add(new PinDefinition(name, dataType, PinDirection.INPUT, defaultValue, widgetType, Map.of(), "", null, options != null ? options : List.of()));
            return this;
        }

        public Builder output(String name, FlowDataType dataType) {
            outputs.add(new PinDefinition(name, dataType, PinDirection.OUTPUT, null, null, Map.of(), "", null, List.of()));
            return this;
        }

        public Builder priority(int priority) {
            this.priority = priority;
            return this;
        }

        public WorldGenNodeDefinition build() {
            if ("World Gen".equals(category)) {
                category = inferCategory(id);
            }
            return new WorldGenNodeDefinition(this);
        }

        private String inferCategory(String id) {
            if (id == null) return "World Gen";
            if (id.startsWith("biome_") || "climate_map".equals(id) || "temperature".equals(id) || "humidity".equals(id) || "continentalness".equals(id) || "erosion".equals(id) || "weirdness".equals(id) || "output_biome".equals(id)) return "Biomes";
            if (id.startsWith("surface_") || id.endsWith("_rule") || "material_layer".equals(id) || "height_band".equals(id) || "slope_mask".equals(id) || "output_block".equals(id)) return "Surface";
            if (id.contains("cave") || "ravine".equals(id) || "carve_if".equals(id) || "density_combine".equals(id)) return "Caves";
            if (id.contains("feature") || id.contains("scatter") || id.endsWith("_filter") || "ore_vein".equals(id) || "vegetation_patch".equals(id) || "liquid_lake".equals(id) || "disk".equals(id) || "boulder".equals(id) || "output_features".equals(id)) return "Features";
            if (id.contains("structure") || "output_structures".equals(id)) return "Structures";
            if (id.contains("spawn") || "output_spawns".equals(id)) return "Spawns";
            return "Terrain";
        }
    }

    public enum PinDirection {
        INPUT,
        OUTPUT
    }

    public record PinDefinition(String name, FlowDataType dataType, PinDirection direction, Object defaultValue, String widgetType,
                                Map<String, Object> constraints, String description, String visibleWhen, List<String> options) {
    }
}
