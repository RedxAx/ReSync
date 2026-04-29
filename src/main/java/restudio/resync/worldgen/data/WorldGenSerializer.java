package restudio.resync.worldgen.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class WorldGenSerializer {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static String serialize(WorldGenGraph graph) {
        return GSON.toJson(graph);
    }

    public static WorldGenGraph deserialize(String json) {
        WorldGenGraph graph = GSON.fromJson(json, WorldGenGraph.class);
        if (graph != null) {
            graph.rebuildIndices();
        }
        return graph;
    }

    public static String serializeProject(WorldGenProject project) {
        return GSON.toJson(project);
    }

    public static WorldGenProject deserializeProject(String json) {
        WorldGenProject project = GSON.fromJson(json, WorldGenProject.class);
        if (project != null) {
            project.rebuildIndices();
        }
        return project;
    }
}
