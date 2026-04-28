package restudio.flow.data;

import com.google.gson.JsonObject;
import org.bukkit.Location;
import org.bukkit.Material;

public class FlowBlock implements FlowDataObject {
    private String material;
    private String world;
    private int x;
    private int y;
    private int z;
    private String blockData;
    private String nbt;

    public FlowBlock() {
    }

    public FlowBlock(String material, String world, int x, int y, int z) {
        this.material = material;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public static FlowBlock fromLocation(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        FlowBlock block = new FlowBlock(
            location.getBlock().getType().name(),
            location.getWorld().getName(),
            location.getBlockX(),
            location.getBlockY(),
            location.getBlockZ()
        );
        try {
            block.blockData = location.getBlock().getBlockData().getAsString();
        } catch (Exception ignored) {
        }
        return block;
    }

    public Material getMaterialValue() {
        Material resolved = Material.matchMaterial(material == null ? "" : material);
        return resolved != null ? resolved : Material.AIR;
    }

    @Override
    public String getTypeId() {
        return "block";
    }

    @Override
    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("material", material);
        obj.addProperty("world", world);
        obj.addProperty("x", x);
        obj.addProperty("y", y);
        obj.addProperty("z", z);
        if (blockData != null) obj.addProperty("blockData", blockData);
        if (nbt != null) obj.addProperty("nbt", nbt);
        return obj;
    }

    public String getMaterial() {
        return material;
    }

    public void setMaterial(String material) {
        this.material = material;
    }

    public String getWorld() {
        return world;
    }

    public void setWorld(String world) {
        this.world = world;
    }

    public int getX() {
        return x;
    }

    public void setX(int x) {
        this.x = x;
    }

    public int getY() {
        return y;
    }

    public void setY(int y) {
        this.y = y;
    }

    public int getZ() {
        return z;
    }

    public void setZ(int z) {
        this.z = z;
    }

    public String getBlockData() {
        return blockData;
    }

    public void setBlockData(String blockData) {
        this.blockData = blockData;
    }

    public String getNbt() {
        return nbt;
    }

    public void setNbt(String nbt) {
        this.nbt = nbt;
    }
}
