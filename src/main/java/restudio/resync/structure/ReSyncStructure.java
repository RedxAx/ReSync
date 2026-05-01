package restudio.resync.structure;

import java.util.List;

public class ReSyncStructure {
    private int formatVersion = 1;
    private String id;
    private String displayName;
    private List<String> tags = List.of();
    private int originX;
    private int originY;
    private int originZ;
    private int sizeX;
    private int sizeY;
    private int sizeZ;
    private String[][][] blockTypes;
    private String[][][] blockDataStrings;
    private long createdAt;
    private long updatedAt;

    public int getFormatVersion() {
        return formatVersion;
    }

    public void setFormatVersion(int formatVersion) {
        this.formatVersion = formatVersion;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags == null ? List.of() : List.copyOf(tags);
    }

    public int getOriginX() {
        return originX;
    }

    public void setOriginX(int originX) {
        this.originX = originX;
    }

    public int getOriginY() {
        return originY;
    }

    public void setOriginY(int originY) {
        this.originY = originY;
    }

    public int getOriginZ() {
        return originZ;
    }

    public void setOriginZ(int originZ) {
        this.originZ = originZ;
    }

    public int getSizeX() {
        return sizeX;
    }

    public void setSizeX(int sizeX) {
        this.sizeX = sizeX;
    }

    public int getSizeY() {
        return sizeY;
    }

    public void setSizeY(int sizeY) {
        this.sizeY = sizeY;
    }

    public int getSizeZ() {
        return sizeZ;
    }

    public void setSizeZ(int sizeZ) {
        this.sizeZ = sizeZ;
    }

    public String[][][] getBlockTypes() {
        return blockTypes;
    }

    public void setBlockTypes(String[][][] blockTypes) {
        this.blockTypes = blockTypes;
    }

    public String[][][] getBlockDataStrings() {
        return blockDataStrings;
    }

    public void setBlockDataStrings(String[][][] blockDataStrings) {
        this.blockDataStrings = blockDataStrings;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
