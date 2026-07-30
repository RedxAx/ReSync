package restudio.resync.worldgen.datapack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class WorldGenDatapackBuild {
    private String projectId = "";
    private String namespace = "resync_worldgen";
    private String packName = "";
    private String generationMode = "hybrid";
    private String dimensionKey = "";
    private String minecraftVersion = "";
    private String datapackVersion = "";
    private int packFormat;
    private int packFormatMinor;
    private long revision;
    private Path folder;
    private int fileCount;
    private List<String> warnings = new ArrayList<>();

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }
    public String getPackName() { return packName; }
    public void setPackName(String packName) { this.packName = packName; }
    public String getGenerationMode() { return generationMode; }
    public void setGenerationMode(String generationMode) { this.generationMode = generationMode; }
    public String getDimensionKey() { return dimensionKey; }
    public void setDimensionKey(String dimensionKey) { this.dimensionKey = dimensionKey; }
    public String getMinecraftVersion() { return minecraftVersion; }
    public void setMinecraftVersion(String minecraftVersion) { this.minecraftVersion = minecraftVersion; }
    public String getDatapackVersion() { return datapackVersion; }
    public void setDatapackVersion(String datapackVersion) { this.datapackVersion = datapackVersion; }
    public int getPackFormat() { return packFormat; }
    public void setPackFormat(int packFormat) { this.packFormat = packFormat; }
    public int getPackFormatMinor() { return packFormatMinor; }
    public void setPackFormatMinor(int packFormatMinor) { this.packFormatMinor = packFormatMinor; }
    public long getRevision() { return revision; }
    public void setRevision(long revision) { this.revision = revision; }
    public Path getFolder() { return folder; }
    public void setFolder(Path folder) { this.folder = folder; }
    public int getFileCount() { return fileCount; }
    public void setFileCount(int fileCount) { this.fileCount = fileCount; }
    public List<String> getWarnings() { return warnings; }
    public void setWarnings(List<String> warnings) { this.warnings = warnings != null ? warnings : new ArrayList<>(); }
}
