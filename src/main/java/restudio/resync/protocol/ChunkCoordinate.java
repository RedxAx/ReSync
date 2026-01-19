package restudio.resync.protocol;

public class ChunkCoordinate {
    private int chunkX;
    private int chunkZ;

    public ChunkCoordinate() {}

    public ChunkCoordinate(int chunkX, int chunkZ) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }

    public int getChunkX() {
        return chunkX;
    }

    public void setChunkX(int chunkX) {
        this.chunkX = chunkX;
    }

    public int getChunkZ() {
        return chunkZ;
    }

    public void setChunkZ(int chunkZ) {
        this.chunkZ = chunkZ;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChunkCoordinate that = (ChunkCoordinate) o;
        return chunkX == that.chunkX && chunkZ == that.chunkZ;
    }

    @Override
    public int hashCode() {
        return 31 * chunkX + chunkZ;
    }
}
