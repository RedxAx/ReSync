package restudio.resync.player;

import java.util.LinkedHashMap;
import java.util.Map;

public class PlayerFacetState {
    private String facetId;
    private String moduleId;
    private long updatedAt;
    private PlayerFacetMetadata metadata;
    private Map<String, Object> data = new LinkedHashMap<>();

    public PlayerFacetState copy() {
        PlayerFacetState copy = new PlayerFacetState();
        copy.facetId = facetId;
        copy.moduleId = moduleId;
        copy.updatedAt = updatedAt;
        copy.metadata = metadata == null ? null : metadata.copy();
        copy.data = PlayerDossier.copyData(data);
        return copy;
    }

    public String getFacetId() {
        return facetId;
    }

    public void setFacetId(String facetId) {
        this.facetId = facetId;
    }

    public String getModuleId() {
        return moduleId;
    }

    public void setModuleId(String moduleId) {
        this.moduleId = moduleId;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public PlayerFacetMetadata getMetadata() {
        return metadata;
    }

    public void setMetadata(PlayerFacetMetadata metadata) {
        this.metadata = metadata == null ? null : metadata.copy();
    }

    public Map<String, Object> getData() {
        return data;
    }

    public void setData(Map<String, Object> data) {
        this.data = PlayerDossier.copyData(data);
    }
}
