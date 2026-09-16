package restudio.resync.flow.function;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.ContentHash;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class FunctionSourceDocument {
    private final FunctionSignature signature;
    private final GraphDocument graph;
    private final OpaqueData unknown;

    public FunctionSourceDocument(FunctionSignature signature, GraphDocument graph, OpaqueData unknown) {
        this.signature = Objects.requireNonNull(signature, "Function Source Signature Is Required");
        this.graph = Objects.requireNonNull(graph, "Function Source Graph Is Required");
        if (!signature.function().resource().equals(graph.resource())) {
            throw new IllegalArgumentException("Function Source Locator Must Match The Graph Resource");
        }
        if (signature.revision().value() != graph.revision()) {
            throw new IllegalArgumentException("Function Source Revision Must Match The Graph Revision");
        }
        if (!"function".equals(graph.resource().resourceType().value())) {
            throw new IllegalArgumentException("Function Source Graph Must Use The Function Resource Type");
        }
        this.unknown = unknown != null ? unknown : OpaqueData.empty();
        rejectUnknownCollisions(this.unknown);
    }

    public FunctionSourceDocument(FunctionSignature signature, GraphDocument graph) {
        this(signature, graph, OpaqueData.empty());
    }

    public FunctionSignature signature() {
        return signature;
    }

    public GraphDocument graph() {
        return graph;
    }

    public OpaqueData unknown() {
        return unknown;
    }

    public ContentHash checksum() {
        return new ContentHash(CanonicalJson.sha256("function-source", canonicalValue()));
    }

    public String canonicalJson() {
        return CanonicalJson.canonicalize(canonicalValue());
    }

    private Map<String, Object> canonicalValue() {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("signature", signature.canonicalValue());
        values.put("graph", CanonicalJson.parse(graph.canonicalJson()));
        unknown.fields().forEach((key, value) -> {
            if (values.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("Function Source Unknown Data Collides With Known Field: " + key);
            }
        });
        return Collections.unmodifiableMap(values);
    }

    private static void rejectUnknownCollisions(OpaqueData unknown) {
        if (unknown.contains("signature") || unknown.contains("graph")) {
            throw new IllegalArgumentException("Function Source Unknown Data Collides With Known Field");
        }
    }
}
