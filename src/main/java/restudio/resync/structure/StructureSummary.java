package restudio.resync.structure;

import java.util.List;

public record StructureSummary(String id, String displayName, List<String> tags, int sizeX, int sizeY, int sizeZ, long updatedAt) {
}
