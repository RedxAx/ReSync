package restudio.resync.worldgen.runtime;

public record SpawnTable(String entityType, int weight, int minGroupSize, int maxGroupSize, String category) {
}
