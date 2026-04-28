package restudio.flow.data;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.World;

public class FlowWorldRef implements FlowDataObject {
    private String name;
    private String environment;
    private Long seed;

    public FlowWorldRef() {
    }

    public FlowWorldRef(String name) {
        this.name = name;
    }

    public static FlowWorldRef fromWorld(World world) {
        if (world == null) {
            return null;
        }
        FlowWorldRef ref = new FlowWorldRef(world.getName());
        ref.environment = world.getEnvironment().name();
        ref.seed = world.getSeed();
        return ref;
    }

    public World resolveWorld() {
        if (name == null || name.isBlank()) {
            return null;
        }
        return Bukkit.getWorld(name);
    }

    @Override
    public String getTypeId() {
        return "world";
    }

    @Override
    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("name", name);
        if (environment != null) obj.addProperty("environment", environment);
        if (seed != null) obj.addProperty("seed", seed);
        return obj;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public Long getSeed() {
        return seed;
    }

    public void setSeed(Long seed) {
        this.seed = seed;
    }
}
