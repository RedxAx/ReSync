package restudio.resync.flow.workspace;

public record WorkspacePatch<V>(String op, String path, V value) {
}
