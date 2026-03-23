package restudio.resync.world;

import org.bukkit.block.Block;

public class WorldSignPortal {
    private String signId;
    private String worldName;
    private int x;
    private int y;
    private int z;
    private String portalId;
    private String portalName;
    private boolean enabled = true;
    private long updatedAt;

    public WorldSignPortal copy() {
        WorldSignPortal copy = new WorldSignPortal();
        copy.signId = signId;
        copy.worldName = worldName;
        copy.x = x;
        copy.y = y;
        copy.z = z;
        copy.portalId = portalId;
        copy.portalName = portalName;
        copy.enabled = enabled;
        copy.updatedAt = updatedAt;
        return copy;
    }

    public boolean matches(Block block) {
        return block != null
            && block.getWorld() != null
            && worldName != null
            && worldName.equalsIgnoreCase(block.getWorld().getName())
            && x == block.getX()
            && y == block.getY()
            && z == block.getZ();
    }

    public String getSignId() {
        return signId;
    }

    public void setSignId(String signId) {
        this.signId = signId;
    }

    public String getWorldName() {
        return worldName;
    }

    public void setWorldName(String worldName) {
        this.worldName = worldName;
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

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
