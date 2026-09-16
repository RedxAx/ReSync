package restudio.resync.modules.flow;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.CoreGraphStorageBoundary;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphDocumentCodec;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.util.Objects;
import java.util.Optional;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

final class CoreGraphWorkspaceDocumentProvider {
    private static final Set<String> TYPES = Set.of("flow", "function", "command");
    private final FlowStorage storage;
    private final CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
    private final boolean available;

    CoreGraphWorkspaceDocumentProvider(FlowStorage storage) {
        this.storage = Objects.requireNonNull(storage, "Flow storage is required");
        this.available = detectAvailability();
    }

    boolean supports(String type) {
        return available && TYPES.contains(normalize(type));
    }

    JsonObject load(String type, String resourceId) {
        String normalized = normalize(type);
        return supports(normalized) ? storage.getCoreGraph(normalized, resourceId).map(this::document).orElse(null) : null;
    }

    JsonObject project(String type, String resourceId, String payload) {
        type = normalize(type);
        if (!supports(type) || payload == null || payload.isBlank()) {
            return null;
        }
        try {
            ServerResourceLocator expected = current(type, resourceId).graph().resource();
            CoreGraphStorageBoundary.Decoded decoded = boundary.decodeText(payload, expected);
            GraphDocument graph = graph(decoded);
            return matches(graph, type, resourceId) ? json(graph) : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    JsonObject persist(String type, String resourceId, JsonObject document) {
        Loaded current = current(type, resourceId);
        return persist(type, resourceId, document, UUID.randomUUID(), current.decoded().envelope().assetRevision());
    }

    JsonObject persist(String type, String resourceId, JsonObject document, UUID mutationId, long expectedRevision) {
        Loaded current = current(type, resourceId);
        GraphDocument submitted = decode(document);
        if (mutationId.toString().equals(current.decoded().envelope().assetMutationId())) {
            if (expectedRevision != current.decoded().envelope().assetRevision() - 1L || !sameReplay(submitted, current.graph())) {
                throw new IllegalStateException("Workspace mutation ID was already committed with different state");
            }
            return document(current.decoded());
        }
        if (expectedRevision != current.decoded().envelope().assetRevision()) {
            throw new IllegalStateException("Workspace resource revision changed before persistence");
        }
        GraphDocument previous = current.graph();
        if (!submitted.resource().equals(previous.resource()) || submitted.revision() != previous.revision()) {
            throw new IllegalArgumentException("Workspace graph identity or revision changed");
        }
        long nextRevision = Math.addExact(previous.revision(), 1L);
        GraphDocument next = withRevision(submitted, nextRevision);
        CoreGraphStorageBoundary.Decoded saved;
        if (current.decoded().functionSourceDocument() != null) {
            FunctionSourceDocument source = withGraph(current.decoded().functionSourceDocument(), next, nextRevision);
            saved = storage.saveCoreGraph(source, current.decoded().envelope().assetActivationState(), mutationId, expectedRevision);
        } else {
            saved = storage.saveCoreGraph(next, current.decoded().envelope().assetActivationState(), mutationId, expectedRevision);
        }
        return document(saved);
    }

    private boolean sameReplay(GraphDocument submitted, GraphDocument current) {
        if (!submitted.resource().equals(current.resource())) {
            return false;
        }
        if (submitted.canonicalJson().equals(current.canonicalJson())) {
            return true;
        }
        if (submitted.revision() == Long.MAX_VALUE || submitted.revision() + 1L != current.revision()) {
            return false;
        }
        return withRevision(submitted, current.revision()).canonicalJson().equals(current.canonicalJson());
    }

    private Loaded current(String type, String resourceId) {
        type = normalize(type);
        if (!supports(type)) {
            throw new IllegalArgumentException("Unsupported Core workspace resource type: " + type);
        }
        Optional<CoreGraphStorageBoundary.Decoded> loaded = storage.getCoreGraph(type, resourceId);
        if (loaded.isEmpty()) {
            throw new IllegalStateException("Core workspace resource is unavailable: " + type + ':' + resourceId);
        }
        CoreGraphStorageBoundary.Decoded decoded = loaded.get();
        GraphDocument graph = graph(decoded);
        if (!matches(graph, type, resourceId)) {
            throw new IllegalStateException("Core workspace resource identity does not match storage");
        }
        return new Loaded(decoded, graph);
    }

    private boolean detectAvailability() {
        try {
            storage.getCoreGraph("flow", "workspace-authority-probe");
            return true;
        } catch (IllegalStateException exception) {
            if ("Core graph storage requires an authoritative server ID".equals(exception.getMessage())) {
                return false;
            }
            throw exception;
        }
    }

    private GraphDocument decode(JsonObject document) {
        Objects.requireNonNull(document, "Workspace graph document is required");
        return GraphDocumentCodec.INSTANCE.decode(jsonValue(document));
    }

    private JsonValue jsonValue(JsonElement value) {
        return GsonJsonValues.convert(value);
    }

    private GraphDocument graph(CoreGraphStorageBoundary.Decoded decoded) {
        return decoded.graphDocument() != null ? decoded.graphDocument() : decoded.functionSourceDocument().graph();
    }

    private JsonObject document(CoreGraphStorageBoundary.Decoded decoded) {
        return json(graph(decoded));
    }

    private JsonObject json(GraphDocument graph) {
        JsonElement parsed = JsonParser.parseString(graph.canonicalJson());
        if (!parsed.isJsonObject()) {
            throw new IllegalStateException("Core graph workspace projection must be an object");
        }
        return parsed.getAsJsonObject();
    }

    private boolean matches(GraphDocument graph, String type, String resourceId) {
        return graph != null && normalize(type).equals(graph.resource().resourceType().value()) && resourceId.equals(graph.resource().id());
    }

    private GraphDocument withRevision(GraphDocument graph, long revision) {
        return new GraphDocument(graph.schemaVersion(), graph.resource(), revision, graph.catalogBinding(), graph.requiredCapabilities(), graph.nodes(),
            graph.connections(), graph.passthroughs(), graph.variables(), graph.functions(), graph.unknown());
    }

    private FunctionSourceDocument withGraph(FunctionSourceDocument source, GraphDocument graph, long revision) {
        FunctionSignature signature = source.signature();
        FunctionSignature nextSignature = new FunctionSignature(signature.function(), FunctionRevision.of(revision), signature.inputs(), signature.outputs(),
            signature.unknown());
        return new FunctionSourceDocument(nextSignature, graph, source.unknown());
    }

    private record Loaded(CoreGraphStorageBoundary.Decoded decoded, GraphDocument graph) {
    }

    private String normalize(String type) {
        return type != null ? type.trim().toLowerCase(Locale.ROOT) : "";
    }
}
