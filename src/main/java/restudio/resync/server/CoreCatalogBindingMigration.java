package restudio.resync.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import restudio.resync.flow.CoreGraphStorageBoundary;
import restudio.resync.contract.identity.IdentityCodec;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.storage.StorageSafety;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class CoreCatalogBindingMigration {
    public static final String ID = "core-catalog-binding-rebind-v1";
    public static final String ACTOR = "core-catalog-binding-migration-v1";
    public static final String REPORT_FILE = ID + ".json";
    public static final String RESOURCE = "/restudio/resync/migration/core-catalog-binding-rebind-v1.json";
    public static final CatalogBinding SOURCE = new CatalogBinding(53L,
        "ad47741b2163447b28b7e959879a083a4f32c324e7e2c84363925a0ad2b57d1e",
        "e37fae7f334e1cfe878b65d4816ca9581f05ac80950cf1dbe89a28ee863f45ea");
    public static final CatalogBinding TARGET = new CatalogBinding(54L,
        "6ffe6a7740232c2bc7d1eeb551488ba913c728fcd1fdf44712528bdc14d2bd84",
        "58f858c36ff7a799bbb610d8b40bfe297d56f92738b98c91f35a926024144116");

    private final String canonicalManifest;
    private final ContentHash manifestHash;
    private final Map<String, Incident> incidents;

    private CoreCatalogBindingMigration(String canonicalManifest, Map<String, Incident> incidents) {
        this.canonicalManifest = canonicalManifest;
        this.manifestHash = new ContentHash(StorageSafety.sha256(canonicalManifest));
        this.incidents = Map.copyOf(incidents);
    }

    public static CoreCatalogBindingMigration load() {
        try (InputStream input = CoreCatalogBindingMigration.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Core catalog binding migration manifest is missing");
            }
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
            return parse(text);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Core catalog binding migration manifest is invalid", exception);
        }
    }

    static CoreCatalogBindingMigration parse(String text) {
        try {
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            requireText(root, "format", ID);
            requireBinding(root.getAsJsonObject("source"), SOURCE);
            requireBinding(root.getAsJsonObject("target"), TARGET);
            LinkedHashMap<String, Incident> incidents = new LinkedHashMap<>();
            root.getAsJsonArray("incidents").forEach(element -> {
                JsonObject incident = element.getAsJsonObject();
                Incident value = new Incident(requireString(incident, "type"), requireString(incident, "id"),
                    incident.get("revision").getAsLong(), UUID.fromString(requireString(incident, "mutationId")),
                    new ContentHash(requireString(incident, "assetHash")));
                if (incidents.putIfAbsent(value.key(), value) != null) {
                    throw new IllegalStateException("Core catalog binding migration incident is duplicated");
                }
            });
            String canonical = CanonicalJson.canonicalize(CanonicalJson.parse(text));
            return new CoreCatalogBindingMigration(canonical, incidents);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Core catalog binding migration manifest is invalid", exception);
        }
    }

    public CatalogBinding source() {
        return SOURCE;
    }

    public CatalogBinding target() {
        return TARGET;
    }

    public ContentHash manifestHash() {
        return manifestHash;
    }

    public String canonicalManifest() {
        return canonicalManifest;
    }

    public Map<String, Incident> incidents() {
        return incidents;
    }

    public List<ServerResourceLocator> resources(ServerId serverId) {
        Objects.requireNonNull(serverId, "Core catalog binding migration server ID is required");
        OwnerId owner = OwnerId.of("restudio.resync");
        return incidents.values().stream().sorted().map(incident -> new ServerResourceLocator(serverId,
            ContractRef.of(owner, ResourceTypeId.of(incident.type())), incident.id())).toList();
    }

    public Incident incident(ServerResourceLocator resource) {
        Objects.requireNonNull(resource, "Core catalog binding migration resource is required");
        return incidents.get(resource.resourceType().value() + "/" + resource.id());
    }

    public boolean eligible(ServerResourceLocator resource, CoreGraphStorageBoundary.Decoded decoded) {
        if (decoded == null || decoded.envelope().assetFormatVersion() != CoreGraphStorageBoundary.CURRENT_ASSET_FORMAT_VERSION) {
            return false;
        }
        Incident incident = incidents.get(resource.resourceType().value() + "/" + resource.id());
        return incident != null && incident.matches(resource, decoded) && SOURCE.equals(graph(decoded).catalogBinding());
    }

    public CoreGraphStorageBoundary.Decoded project(CoreGraphStorageBoundary.Decoded source, UUID mutationId) {
        Objects.requireNonNull(source, "Core catalog binding migration source is required");
        Objects.requireNonNull(mutationId, "Core catalog binding migration mutation ID is required");
        ServerResourceLocator resource = graph(source).resource();
        if (!eligible(resource, source)) {
            throw new IllegalArgumentException("Core graph does not match the frozen migration source binding");
        }
        long revision = Math.addExact(source.envelope().assetRevision(), 1L);
        GraphDocument graph = graph(source);
        GraphDocument projectedGraph = new GraphDocument(graph.schemaVersion(), graph.resource(), revision, TARGET,
            graph.requiredCapabilities(), graph.nodes(), graph.connections(), graph.passthroughs(), graph.variables(), graph.functions(),
            graph.unknown());
        CoreGraphStorageBoundary.AssetMetadata metadata = new CoreGraphStorageBoundary.AssetMetadata(
            source.envelope().resourceType(), revision, mutationId, source.envelope().assetActivationState());
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        byte[] bytes;
        if (source.graphDocument() != null) {
            bytes = boundary.encode(projectedGraph, metadata, projectedGraph.resource());
        } else {
            FunctionSignature signature = source.functionSourceDocument().signature();
            FunctionSignature projectedSignature = new FunctionSignature(signature.function(), new FunctionRevision(revision),
                signature.inputs(), signature.outputs(), signature.unknown());
            FunctionSourceDocument projected = new FunctionSourceDocument(projectedSignature, projectedGraph,
                source.functionSourceDocument().unknown());
            bytes = boundary.encode(projected, metadata, projectedGraph.resource());
        }
        return boundary.decode(bytes, projectedGraph.resource());
    }

    public UUID mutationId(ServerResourceLocator resource, CoreGraphStorageBoundary.Decoded source) {
        Objects.requireNonNull(resource, "Core catalog binding migration resource is required");
        Objects.requireNonNull(source, "Core catalog binding migration source is required");
        if (!eligible(resource, source)) {
            throw new IllegalArgumentException("Core graph does not match the frozen migration incident proof");
        }
        return mutationId(resource, Objects.requireNonNull(incident(resource),
            "Core catalog binding migration incident is required"));
    }

    public UUID mutationId(ServerResourceLocator resource, Incident incident) {
        Objects.requireNonNull(resource, "Core catalog binding migration resource is required");
        Objects.requireNonNull(incident, "Core catalog binding migration incident is required");
        if (!"restudio.resync".equals(resource.owner().value())
            || !incident.type().equals(resource.resourceType().value()) || !incident.id().equals(resource.id())) {
            throw new IllegalArgumentException("Core catalog binding migration incident locator does not match");
        }
        String seed = ID + '\n' + manifestHash.canonicalText() + '\n' + resource.canonicalText() + '\n'
            + incident.revision() + '\n' + incident.mutationId() + '\n' + incident.assetHash().canonicalText();
        return IdentityCodec.deterministicUuid(ID, seed);
    }

    public ContentHash planHash(String canonicalPlan) {
        return new ContentHash(StorageSafety.sha256(ID + '\n' + manifestHash.canonicalText() + '\n' + canonicalPlan));
    }

    public static GraphDocument graph(CoreGraphStorageBoundary.Decoded decoded) {
        return decoded.graphDocument() != null ? decoded.graphDocument() : decoded.functionSourceDocument().graph();
    }

    private static void requireBinding(JsonObject value, CatalogBinding expected) {
        if (value == null || value.get("generation").getAsLong() != expected.generation()
            || !expected.catalogChecksum().canonicalText().equals(value.get("catalogChecksum").getAsString())
            || !expected.bindingManifestHash().canonicalText().equals(value.get("bindingManifestHash").getAsString())) {
            throw new IllegalStateException("Core catalog binding migration manifest binding is invalid");
        }
    }

    private static void requireText(JsonObject value, String name, String expected) {
        if (value == null || !value.has(name) || !expected.equals(value.get(name).getAsString())) {
            throw new IllegalStateException("Core catalog binding migration manifest field is invalid: " + name);
        }
    }

    private static String requireString(JsonObject value, String name) {
        if (value == null || !value.has(name) || !value.get(name).isJsonPrimitive()
            || !value.get(name).getAsJsonPrimitive().isString()) {
            throw new IllegalStateException("Core catalog binding migration manifest field is invalid: " + name);
        }
        return value.get(name).getAsString();
    }

    public record Incident(String type, String id, long revision, UUID mutationId, ContentHash assetHash)
        implements Comparable<Incident> {
        public Incident {
            if (!"flow".equals(type) && !"command".equals(type) && !"function".equals(type)) {
                throw new IllegalArgumentException("Core catalog binding migration incident type is invalid");
            }
            if (id == null || id.isBlank() || revision < 1L) {
                throw new IllegalArgumentException("Core catalog binding migration incident identity is invalid");
            }
            mutationId = Objects.requireNonNull(mutationId,
                "Core catalog binding migration incident mutation ID is required");
            assetHash = Objects.requireNonNull(assetHash,
                "Core catalog binding migration incident asset hash is required");
        }

        public String key() {
            return type + "/" + id;
        }

        public boolean matches(ServerResourceLocator resource, CoreGraphStorageBoundary.Decoded decoded) {
            return "restudio.resync".equals(resource.owner().value())
                && type.equals(resource.resourceType().value()) && id.equals(resource.id())
                && revision == decoded.envelope().assetRevision()
                && mutationId.toString().equals(decoded.envelope().assetMutationId())
                && assetHash.equals(decoded.envelope().assetHash())
                && resource.equals(graph(decoded).resource());
        }

        @Override
        public int compareTo(Incident other) {
            return key().compareTo(other.key());
        }
    }
}
