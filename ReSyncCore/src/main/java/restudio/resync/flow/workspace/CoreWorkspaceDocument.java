package restudio.resync.flow.workspace;

import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.function.FunctionSourceDocumentCodec;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphDocumentCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record CoreWorkspaceDocument(GraphDocument graph, FunctionSourceDocument source) {
    public CoreWorkspaceDocument {
        if ((graph == null) == (source == null)) {
            throw new IllegalArgumentException("Workspace requires exactly one typed document");
        }
    }

    public static CoreWorkspaceDocument decode(JsonValue value) {
        if (!(value instanceof JsonValue.JsonObject object)) {
            throw new IllegalArgumentException("Workspace document must be an object");
        }
        return object.values().containsKey("signature")
            ? new CoreWorkspaceDocument(null, FunctionSourceDocumentCodec.INSTANCE.decode(value))
            : new CoreWorkspaceDocument(GraphDocumentCodec.INSTANCE.decode(value), null);
    }

    public GraphDocument document() {
        return graph != null ? graph : source.graph();
    }

    public JsonValue.JsonObject encode() {
        return graph != null ? GraphDocumentCodec.INSTANCE.encode(graph) : FunctionSourceDocumentCodec.INSTANCE.encode(source);
    }

    public List<WorkspacePatch<JsonValue>> diff(CoreWorkspaceDocument desired) {
        requireKind(desired);
        if (graph != null) {
            return CoreGraphWorkspacePatch.diff(graph, desired.graph);
        }
        CoreFunctionSourcePatch.Patch patch = CoreFunctionSourcePatch.diff(source, desired.source);
        ArrayList<WorkspacePatch<JsonValue>> result = new ArrayList<>(patch.signaturePatches());
        for (WorkspacePatch<JsonValue> item : patch.graphPatches()) {
            result.add(new WorkspacePatch<>(item.op(), "/graph" + item.path(), item.value()));
        }
        return List.copyOf(result);
    }

    public CoreWorkspaceDocument apply(List<WorkspacePatch<JsonValue>> patches) {
        if (graph != null) {
            return new CoreWorkspaceDocument(CoreGraphWorkspacePatch.apply(graph, patches), null);
        }
        ArrayList<WorkspacePatch<JsonValue>> signature = new ArrayList<>();
        ArrayList<WorkspacePatch<JsonValue>> graphPatches = new ArrayList<>();
        for (WorkspacePatch<JsonValue> patch : patches) {
            if (patch.path().startsWith("/graph/")) {
                graphPatches.add(new WorkspacePatch<>(patch.op(), patch.path().substring(6), patch.value()));
            } else if (patch.path().startsWith("/signature/")) {
                signature.add(patch);
            } else {
                throw new IllegalArgumentException("Function workspace patch cannot change managed source fields");
            }
        }
        return new CoreWorkspaceDocument(null,
            CoreFunctionSourcePatch.apply(source, new CoreFunctionSourcePatch.Patch(signature, graphPatches)));
    }

    public CoreWorkspaceDocument rebase(CoreWorkspaceDocument desired, CoreWorkspaceDocument latest) {
        requireKind(desired);
        requireKind(latest);
        return graph != null
            ? new CoreWorkspaceDocument(CoreGraphWorkspacePatch.rebase(graph, desired.graph, latest.graph), null)
            : new CoreWorkspaceDocument(null, CoreFunctionSourcePatch.rebase(source, desired.source, latest.source));
    }

    private void requireKind(CoreWorkspaceDocument other) {
        Objects.requireNonNull(other, "Workspace document is required");
        if ((graph == null) != (other.graph == null)) {
            throw new IllegalArgumentException("Workspace document kind cannot change");
        }
    }
}
