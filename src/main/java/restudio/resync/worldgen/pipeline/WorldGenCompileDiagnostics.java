package restudio.resync.worldgen.pipeline;

import java.util.ArrayList;
import java.util.List;

public class WorldGenCompileDiagnostics {
    private boolean success;
    private long elapsedMillis;
    private List<WorldGenDiagnostic> diagnostics = new ArrayList<>();

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public long getElapsedMillis() { return elapsedMillis; }
    public void setElapsedMillis(long elapsedMillis) { this.elapsedMillis = elapsedMillis; }
    public List<WorldGenDiagnostic> getDiagnostics() { return diagnostics; }
    public void setDiagnostics(List<WorldGenDiagnostic> diagnostics) { this.diagnostics = diagnostics != null ? diagnostics : new ArrayList<>(); }
    public void add(String stage, String severity, String message) { diagnostics.add(new WorldGenDiagnostic(stage, severity, message)); }

    public record WorldGenDiagnostic(String stage, String severity, String message) {
    }
}
