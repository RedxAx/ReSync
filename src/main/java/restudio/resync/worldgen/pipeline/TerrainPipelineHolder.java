package restudio.resync.worldgen.pipeline;

public class TerrainPipelineHolder {
    private volatile TerrainPipeline pipeline;

    public TerrainPipelineHolder(TerrainPipeline pipeline) {
        this.pipeline = pipeline;
    }

    public TerrainPipeline get() {
        return pipeline;
    }

    public void set(TerrainPipeline pipeline) {
        if (pipeline != null) {
            this.pipeline = pipeline;
        }
    }
}
