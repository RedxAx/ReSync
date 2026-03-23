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
    private String accessPermission;
    private String bypassPermission;
    private boolean usageFeeEnabled;
    private double usageFee;
    private long cooldownMillis;
    private int priority;
    private Boolean safeTeleport;
    private Boolean preserveVelocity;
    private String enterMessage;
    private Boolean vehiclePassthroughEnabled;
    private Boolean entityPassthroughEnabled;
    private String destinationMode;
    private double cannonPower;

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
        copy.accessPermission = accessPermission;
        copy.bypassPermission = bypassPermission;
        copy.usageFeeEnabled = usageFeeEnabled;
        copy.usageFee = usageFee;
        copy.cooldownMillis = cooldownMillis;
        copy.priority = priority;
        copy.safeTeleport = safeTeleport;
        copy.preserveVelocity = preserveVelocity;
        copy.enterMessage = enterMessage;
        copy.vehiclePassthroughEnabled = vehiclePassthroughEnabled;
        copy.entityPassthroughEnabled = entityPassthroughEnabled;
        copy.destinationMode = destinationMode;
        copy.cannonPower = cannonPower;
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

    public String getAccessPermission() {
        return accessPermission;
    }

    public void setAccessPermission(String accessPermission) {
        this.accessPermission = accessPermission;
    }

    public String getBypassPermission() {
        return bypassPermission;
    }

    public void setBypassPermission(String bypassPermission) {
        this.bypassPermission = bypassPermission;
    }

    public boolean isUsageFeeEnabled() {
        return usageFeeEnabled;
    }

    public void setUsageFeeEnabled(boolean usageFeeEnabled) {
        this.usageFeeEnabled = usageFeeEnabled;
    }

    public double getUsageFee() {
        return usageFee;
    }

    public void setUsageFee(double usageFee) {
        this.usageFee = usageFee;
    }

    public long getCooldownMillis() {
        return cooldownMillis > 0L ? cooldownMillis : 1500L;
    }

    public void setCooldownMillis(long cooldownMillis) {
        this.cooldownMillis = cooldownMillis;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public boolean isSafeTeleport() {
        return safeTeleport == null || safeTeleport;
    }

    public void setSafeTeleport(boolean safeTeleport) {
        this.safeTeleport = safeTeleport;
    }

    public boolean isPreserveVelocity() {
        return preserveVelocity != null && preserveVelocity;
    }

    public void setPreserveVelocity(boolean preserveVelocity) {
        this.preserveVelocity = preserveVelocity;
    }

    public String getEnterMessage() {
        return enterMessage;
    }

    public void setEnterMessage(String enterMessage) {
        this.enterMessage = enterMessage;
    }

    public boolean isVehiclePassthroughEnabled() {
        return vehiclePassthroughEnabled == null || vehiclePassthroughEnabled;
    }

    public void setVehiclePassthroughEnabled(boolean vehiclePassthroughEnabled) {
        this.vehiclePassthroughEnabled = vehiclePassthroughEnabled;
    }

    public boolean isEntityPassthroughEnabled() {
        return entityPassthroughEnabled == null || entityPassthroughEnabled;
    }

    public void setEntityPassthroughEnabled(boolean entityPassthroughEnabled) {
        this.entityPassthroughEnabled = entityPassthroughEnabled;
    }

    public String getDestinationMode() {
        return destinationMode == null || destinationMode.isBlank() ? "WORLD" : destinationMode;
    }

    public void setDestinationMode(String destinationMode) {
        this.destinationMode = destinationMode;
    }

    public double getCannonPower() {
        return cannonPower <= 0.0 ? 1.8 : cannonPower;
    }

    public void setCannonPower(double cannonPower) {
        this.cannonPower = cannonPower;
    }
}
