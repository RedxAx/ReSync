package restudio.resync.worldgen.data;

import java.util.UUID;

public class WorldGenProject {
    public static final int CURRENT_VERSION = 2;
    private String id;
    private int version;
    private WorldGenGraph terrainGraph;
    private WorldGenGraph biomeGraph;
    private WorldGenGraph surfaceGraph;
    private WorldGenGraph caveGraph;
    private WorldGenGraph featureGraph;
    private WorldGenGraph structureGraph;
    private WorldGenGraph spawnGraph;
    private WorldGenProjectSettings settings;

    public WorldGenProject() {
        this.id = UUID.randomUUID().toString();
        this.version = CURRENT_VERSION;
        this.terrainGraph = new WorldGenGraph();
        this.biomeGraph = new WorldGenGraph();
        this.surfaceGraph = new WorldGenGraph();
        this.caveGraph = new WorldGenGraph();
        this.featureGraph = new WorldGenGraph();
        this.structureGraph = new WorldGenGraph();
        this.spawnGraph = new WorldGenGraph();
        this.settings = new WorldGenProjectSettings();
    }

    public void rebuildIndices() {
        if (terrainGraph != null) terrainGraph.rebuildIndices();
        if (biomeGraph != null) biomeGraph.rebuildIndices();
        if (surfaceGraph != null) surfaceGraph.rebuildIndices();
        if (caveGraph != null) caveGraph.rebuildIndices();
        if (featureGraph != null) featureGraph.rebuildIndices();
        if (structureGraph != null) structureGraph.rebuildIndices();
        if (spawnGraph != null) spawnGraph.rebuildIndices();
    }

    public WorldGenGraph graph(WorldGenStage stage) {
        return switch (stage) {
            case TERRAIN -> terrainGraph;
            case BIOME -> biomeGraph;
            case SURFACE -> surfaceGraph;
            case CAVE -> caveGraph;
            case FEATURE -> featureGraph;
            case STRUCTURE -> structureGraph;
            case SPAWN -> spawnGraph;
        };
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public WorldGenGraph getTerrainGraph() { return terrainGraph; }
    public void setTerrainGraph(WorldGenGraph terrainGraph) { this.terrainGraph = terrainGraph; }
    public WorldGenGraph getBiomeGraph() { return biomeGraph; }
    public void setBiomeGraph(WorldGenGraph biomeGraph) { this.biomeGraph = biomeGraph; }
    public WorldGenGraph getSurfaceGraph() { return surfaceGraph; }
    public void setSurfaceGraph(WorldGenGraph surfaceGraph) { this.surfaceGraph = surfaceGraph; }
    public WorldGenGraph getCaveGraph() { return caveGraph; }
    public void setCaveGraph(WorldGenGraph caveGraph) { this.caveGraph = caveGraph; }
    public WorldGenGraph getFeatureGraph() { return featureGraph; }
    public void setFeatureGraph(WorldGenGraph featureGraph) { this.featureGraph = featureGraph; }
    public WorldGenGraph getStructureGraph() { return structureGraph; }
    public void setStructureGraph(WorldGenGraph structureGraph) { this.structureGraph = structureGraph; }
    public WorldGenGraph getSpawnGraph() { return spawnGraph; }
    public void setSpawnGraph(WorldGenGraph spawnGraph) { this.spawnGraph = spawnGraph; }
    public WorldGenProjectSettings getSettings() { return settings; }
    public void setSettings(WorldGenProjectSettings settings) { this.settings = settings; }
}
