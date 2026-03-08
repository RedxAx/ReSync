package restudio.resync.world;

import org.bukkit.Location;

public class WorldPortal {
    private String portalId;
    private String portalName;
    private String sourceWorld;
    private double minX;
    private double minY;
    private double minZ;
    private double maxX;
    private double maxY;
    private double maxZ;
    private String destinationWorld;
    private double destinationX;
    private double destinationY;
    private double destinationZ;
    private float destinationYaw;
    private float destinationPitch;
    private boolean enabled = true;
    private long lastUsedAt;

    public WorldPortal copy() {
        WorldPortal copy = new WorldPortal();
        copy.portalId = portalId;
        copy.portalName = portalName;
        copy.sourceWorld = sourceWorld;
        copy.minX = minX;
        copy.minY = minY;
        copy.minZ = minZ;
        copy.maxX = maxX;
        copy.maxY = maxY;
        copy.maxZ = maxZ;
        copy.destinationWorld = destinationWorld;
        copy.destinationX = destinationX;
        copy.destinationY = destinationY;
        copy.destinationZ = destinationZ;
        copy.destinationYaw = destinationYaw;
        copy.destinationPitch = destinationPitch;
        copy.enabled = enabled;
        copy.lastUsedAt = lastUsedAt;
        return copy;
    }

    public void normalizeBounds() {
        double normalizedMinX = Math.min(minX, maxX);
        double normalizedMinY = Math.min(minY, maxY);
        double normalizedMinZ = Math.min(minZ, maxZ);
        double normalizedMaxX = Math.max(minX, maxX);
        double normalizedMaxY = Math.max(minY, maxY);
        double normalizedMaxZ = Math.max(minZ, maxZ);
        minX = normalizedMinX;
        minY = normalizedMinY;
        minZ = normalizedMinZ;
        maxX = normalizedMaxX;
        maxY = normalizedMaxY;
        maxZ = normalizedMaxZ;
    }

    public void expandBlockBounds() {
        normalizeBounds();
        maxX += 0.999;
        maxY += 0.999;
        maxZ += 0.999;
    }

    public boolean contains(Location location) {
        if (location == null || location.getWorld() == null || sourceWorld == null || !sourceWorld.equals(location.getWorld().getName())) {
            return false;
        }
        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public String getPortalId() {
        return portalId;
    }

    public void setPortalId(String portalId) {
        this.portalId = portalId;
    }

    public String getPortalName() {
        return portalName;
    }

    public void setPortalName(String portalName) {
        this.portalName = portalName;
    }

    public String getSourceWorld() {
        return sourceWorld;
    }

    public void setSourceWorld(String sourceWorld) {
        this.sourceWorld = sourceWorld;
    }

    public double getMinX() {
        return minX;
    }

    public void setMinX(double minX) {
        this.minX = minX;
    }

    public double getMinY() {
        return minY;
    }

    public void setMinY(double minY) {
        this.minY = minY;
    }

    public double getMinZ() {
        return minZ;
    }

    public void setMinZ(double minZ) {
        this.minZ = minZ;
    }

    public double getMaxX() {
        return maxX;
    }

    public void setMaxX(double maxX) {
        this.maxX = maxX;
    }

    public double getMaxY() {
        return maxY;
    }

    public void setMaxY(double maxY) {
        this.maxY = maxY;
    }

    public double getMaxZ() {
        return maxZ;
    }

    public void setMaxZ(double maxZ) {
        this.maxZ = maxZ;
    }

    public String getDestinationWorld() {
        return destinationWorld;
    }

    public void setDestinationWorld(String destinationWorld) {
        this.destinationWorld = destinationWorld;
    }

    public double getDestinationX() {
        return destinationX;
    }

    public void setDestinationX(double destinationX) {
        this.destinationX = destinationX;
    }

    public double getDestinationY() {
        return destinationY;
    }

    public void setDestinationY(double destinationY) {
        this.destinationY = destinationY;
    }

    public double getDestinationZ() {
        return destinationZ;
    }

    public void setDestinationZ(double destinationZ) {
        this.destinationZ = destinationZ;
    }

    public float getDestinationYaw() {
        return destinationYaw;
    }

    public void setDestinationYaw(float destinationYaw) {
        this.destinationYaw = destinationYaw;
    }

    public float getDestinationPitch() {
        return destinationPitch;
    }

    public void setDestinationPitch(float destinationPitch) {
        this.destinationPitch = destinationPitch;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(long lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }
}
