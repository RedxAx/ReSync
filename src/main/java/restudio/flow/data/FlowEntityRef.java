package restudio.flow.data;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.UUID;

public class FlowEntityRef implements FlowDataObject {
    private String uuid;
    private String world;
    private String entityType;
    private String displayName;
    private Double x;
    private Double y;
    private Double z;

    public FlowEntityRef() {
    }

    public FlowEntityRef(String uuid, String world, String entityType) {
        this.uuid = uuid;
        this.world = world;
        this.entityType = entityType;
    }

    public static FlowEntityRef fromEntity(Entity entity) {
        if (entity == null || entity.getWorld() == null) {
            return null;
        }
        FlowEntityRef ref = new FlowEntityRef(
            entity.getUniqueId().toString(),
            entity.getWorld().getName(),
            entity.getType().name()
        );
        if (entity.getCustomName() != null) {
            ref.displayName = entity.getCustomName();
        }
        Location loc = entity.getLocation();
        ref.x = loc.getX();
        ref.y = loc.getY();
        ref.z = loc.getZ();
        return ref;
    }

    public Entity resolveEntity() {
        if (uuid == null || uuid.isBlank()) {
            return null;
        }
        try {
            UUID parsed = UUID.fromString(uuid);
            return Bukkit.getEntity(parsed);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @Override
    public String getTypeId() {
        return "entity";
    }

    @Override
    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("uuid", uuid);
        obj.addProperty("world", world);
        obj.addProperty("entityType", entityType);
        if (displayName != null) obj.addProperty("displayName", displayName);
        if (x != null) obj.addProperty("x", x);
        if (y != null) obj.addProperty("y", y);
        if (z != null) obj.addProperty("z", z);
        return obj;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String getWorld() {
        return world;
    }

    public void setWorld(String world) {
        this.world = world;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public Double getX() {
        return x;
    }

    public void setX(Double x) {
        this.x = x;
    }

    public Double getY() {
        return y;
    }

    public void setY(Double y) {
        this.y = y;
    }

    public Double getZ() {
        return z;
    }

    public void setZ(Double z) {
        this.z = z;
    }
}
