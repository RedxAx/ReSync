package restudio.resync.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import restudio.resync.contract.identity.IdentityCodec;
import restudio.resync.flow.CoreGraphStorageBoundary;
import restudio.resync.flow.catalog.CatalogCanonicalizer;
import restudio.resync.flow.catalog.CatalogFunctionShape;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.storage.StorageSafety;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class CoreCatalogEvolution {
    static final String ID = "core-catalog-evolution-v1";
    static final String ACTOR = ID;
    static final String FUNCTION_ID = "core-function-capability-rebind-v1";
    static final String COMMAND = "restudio.resync/event.command";
    static final String STRUCTURE_ID = "core-catalog-evolution-v2";
    static final String STRUCTURE = "restudio.resync/structure_delete";
    static final String REBIND_ID = "core-catalog-evolution-v3";
    static final String RESOURCE_DECLARATION_ID = "core-catalog-evolution-v4";
    private static final String RESOURCE = "/restudio/resync/migration/core-catalog-evolution-v1.json";
    private static final ContentHash REGISTRATION_HASH = new ContentHash(
        "9a90bc28495574d4ec0d701dcf2af9d4fe078b813ae8b9295488dff2fdcf3fba");
    private static final CatalogBinding REBIND_SOURCE = CatalogBinding.parseCanonicalText(
        "57|d9c25c92807d45e79d80fcb941fdf181111d7af53c7f9f93126a62b310377a13|eb30c3b9bad4a5f57d1e62af0f66b25bf3d31d1d6b4c2158d6e5f2d2e417d0b4");
    private static final CatalogBinding REBIND_TARGET = CatalogBinding.parseCanonicalText(
        "58|d79bad6b646cf8b0483bbd29d4936734b6eb1cf2e20dfbfcb8738098b88f72ad|eb30c3b9bad4a5f57d1e62af0f66b25bf3d31d1d6b4c2158d6e5f2d2e417d0b4");
    private static final CatalogBinding RESOURCE_DECLARATION_SOURCE = REBIND_TARGET;
    private static final CatalogBinding RESOURCE_DECLARATION_TARGET = CatalogBinding.parseCanonicalText(
        "59|ff977f428b6131db6ba8d9e4f8c94419f28efdba5764f5b5fb8387150ff186ea|c60b31780fb0a24a10a2dc3fbf8bb3cef3af7f291c24669cc950e9dee5111273");
    private final ContentHash sourceChecksum;
    private final String id;
    private final CatalogBinding exactTarget;
    private final ContentHash targetChecksum;
    private final ContentHash registrationHash;
    private final Set<CatalogBinding> sources;
    private final Set<String> blockedDefinitions;
    private final List<Change> changes;
    private CachedProof cachedProof;

    CoreCatalogEvolution(String content, ContentHash registrationHash) {
        this.registrationHash = Objects.requireNonNull(registrationHash, "Core catalog evolution registration hash is required");
        if (!this.registrationHash.canonicalText().equals(StorageSafety.sha256(content))) {
            throw new IllegalArgumentException("Core catalog evolution registration hash is invalid");
        }
        JsonObject root = JsonParser.parseString(content).getAsJsonObject();
        id = root.get("id").getAsString();
        if (FUNCTION_ID.equals(id)) {
            sourceChecksum = null;
            targetChecksum = null;
            exactTarget = null;
            sources = Set.of();
            blockedDefinitions = Set.of();
            changes = List.of();
            return;
        }
        if (!ID.equals(id) && !STRUCTURE_ID.equals(id) && !REBIND_ID.equals(id)
            && !RESOURCE_DECLARATION_ID.equals(id)) {
            throw new IllegalArgumentException("Core catalog evolution registration identity is invalid");
        }
        sourceChecksum = new ContentHash(root.get("sourceChecksum").getAsString());
        targetChecksum = new ContentHash(root.get("targetChecksum").getAsString());
        exactTarget = ID.equals(id) ? null : CatalogBinding.parseCanonicalText(root.get("targetBinding").getAsString());
        if (STRUCTURE_ID.equals(id) && (exactTarget.generation() != 57L
            || !targetChecksum.equals(exactTarget.catalogChecksum()))) {
            throw new IllegalArgumentException("Structure catalog target binding is invalid");
        }
        List<CatalogBinding> bindings = new ArrayList<>();
        for (JsonElement entry : root.getAsJsonArray("sourceBindings")) {
            JsonObject binding = entry.getAsJsonObject();
            bindings.add(new CatalogBinding(binding.get("generation").getAsLong(),
                binding.get("catalogChecksum").getAsString(), binding.get("bindingManifestHash").getAsString()));
        }
        sources = Set.copyOf(bindings);
        if (STRUCTURE_ID.equals(id) && (sources.size() != 1 || sources.iterator().next().generation() != 56L
            || !sourceChecksum.equals(sources.iterator().next().catalogChecksum()))) {
            throw new IllegalArgumentException("Structure catalog source binding is invalid");
        }
        if (REBIND_ID.equals(id) && (!REBIND_TARGET.equals(exactTarget) || !sources.equals(Set.of(REBIND_SOURCE))
            || !sourceChecksum.equals(REBIND_SOURCE.catalogChecksum()))) {
            throw new IllegalArgumentException("Rebind catalog binding edge is invalid");
        }
        if (RESOURCE_DECLARATION_ID.equals(id) && (!RESOURCE_DECLARATION_TARGET.equals(exactTarget)
            || !sources.equals(Set.of(RESOURCE_DECLARATION_SOURCE))
            || !sourceChecksum.equals(RESOURCE_DECLARATION_SOURCE.catalogChecksum())
            || !targetChecksum.equals(RESOURCE_DECLARATION_TARGET.catalogChecksum()))) {
            throw new IllegalArgumentException("Resource declaration catalog binding edge is invalid");
        }
        if (REBIND_ID.equals(id)) {
            JsonArray blocked = root.getAsJsonArray("blockedDefinitions");
            List<String> definitions = new ArrayList<>();
            blocked.forEach(value -> definitions.add(value.getAsString()));
            blockedDefinitions = Set.copyOf(definitions);
            if (blockedDefinitions.size() != blocked.size() || blockedDefinitions.isEmpty()) {
                throw new IllegalArgumentException("Rebind blocked definition policy is invalid");
            }
        } else {
            blockedDefinitions = Set.of();
        }
        List<Change> parsed = new ArrayList<>();
        for (JsonElement entry : root.getAsJsonArray("changes")) {
            JsonObject change = entry.getAsJsonObject();
            for (JsonElement rawPath : change.getAsJsonArray("paths")) {
                List<String> path = new ArrayList<>();
                rawPath.getAsJsonArray().forEach(segment -> path.add(segment.getAsString()));
                parsed.add(new Change(change.get("kind").getAsString(), path,
                    change.has("index") ? change.get("index").getAsInt() : -1,
                    change.has("targetIndex") ? change.get("targetIndex").getAsInt() : -1,
                    change.has("beforePresent") && change.get("beforePresent").getAsBoolean(),
                    change.has("afterPresent") && change.get("afterPresent").getAsBoolean(),
                    change.has("before") ? change.get("before").toString() : "null",
                    change.has("after") ? change.get("after").toString() : "null",
                    change.has("values") ? change.get("values").toString() : "[]"));
            }
        }
        changes = List.copyOf(parsed);
    }

    public static CoreCatalogEvolution functions() {
        return Registered.FUNCTIONS;
    }

    boolean requiresSourceReceipt() {
        return FUNCTION_ID.equals(id);
    }

    public static CoreCatalogEvolution load() {
        return Registered.VALUE;
    }

    public static Optional<CoreCatalogEvolution> select(CatalogBinding target) {
        return Registered.ALL.stream().filter(evolution -> evolution.registeredTarget(target)).findFirst();
    }

    static CoreCatalogEvolution registered(String hash) {
        return Registered.ALL.stream().filter(evolution -> evolution.registrationHash.canonicalText().equals(hash))
            .findFirst().orElseThrow(() -> new IllegalStateException("Core catalog evolution registration is unknown"));
    }

    static boolean isActor(String actor) {
        return FUNCTION_ID.equals(actor) || ID.equals(actor) || STRUCTURE_ID.equals(actor) || REBIND_ID.equals(actor)
            || RESOURCE_DECLARATION_ID.equals(actor);
    }

    String id() {
        return id;
    }

    public ContentHash registrationHash() {
        return registrationHash;
    }

    public ContentHash targetChecksum() {
        return targetChecksum;
    }

    Set<CatalogBinding> sources() {
        return sources;
    }

    public Optional<Proof> bootstrapProof(CatalogBinding persistedBinding, CatalogBinding candidateBinding,
                                          String canonicalContent) {
        if (FUNCTION_ID.equals(id) || persistedBinding == null || candidateBinding == null
            || !targetChecksum.equals(candidateBinding.catalogChecksum())) {
            return Optional.empty();
        }
        if (!sources.contains(persistedBinding)
            && !(persistedBinding.equals(candidateBinding) && registeredTarget(candidateBinding))) {
            return Optional.empty();
        }
        long floor = Math.addExact(sources.stream().mapToLong(CatalogBinding::generation).max().orElseThrow(), 1L);
        if (persistedBinding.equals(candidateBinding) && candidateBinding.generation() < floor) {
            throw new IllegalArgumentException("Existing evolution target is below the registered startup generation floor");
        }
        CatalogBinding target = new CatalogBinding(Math.max(candidateBinding.generation(), floor),
            candidateBinding.catalogChecksum(), candidateBinding.bindingManifestHash());
        return Optional.of(proveContent(canonicalContent, target, persistedBinding.generation()));
    }

    public Proof prove(CatalogSnapshot snapshot, CatalogBinding binding) {
        Objects.requireNonNull(snapshot, "Evolution target catalog is required");
        if (!binding.equals(new CatalogBinding(snapshot.generation(), snapshot.contentChecksum(),
            snapshot.bindingManifestHash()))) {
            throw new IllegalArgumentException("Evolution target binding does not match the catalog");
        }
        if (FUNCTION_ID.equals(id)) {
            Map<String, Integer> functions = new LinkedHashMap<>();
            snapshot.definitions().forEach(owned -> {
                if (CatalogFunctionShape.from(owned.key().owner(), owned.descriptor()).isPresent()
                    && CatalogFunctionShape.capability(ContractRef.of(owned.key().owner(), owned.descriptor().id())).equals(owned.descriptor().handler().capability())) {
                    functions.put(owned.key().canonicalText(), owned.descriptor().schemaVersion());
                }
            });
            return new Proof(this, binding, functions);
        }
        return proveContent(snapshot.canonicalContent(), binding);
    }

    Proof proveContent(String canonicalContent, CatalogBinding binding) {
        return proveContent(canonicalContent, binding, null);
    }

    private synchronized Proof proveContent(String canonicalContent, CatalogBinding binding,
                                             Long bootstrapSourceGeneration) {
        CachedProof cached = cachedProof;
        if (cached != null && cached.binding().equals(binding) && cached.canonicalContent().equals(canonicalContent)) {
            return cached.proof();
        }
        requireTarget(binding);
        JsonObject target = JsonParser.parseString(canonicalContent).getAsJsonObject();
        ContentHash derivedChecksum = CatalogCanonicalizer.checksumForCanonicalContent(canonicalContent);
        if (!targetChecksum.equals(derivedChecksum)) {
            throw new IllegalArgumentException("Evolution target catalog content is not registered");
        }
        requireTargetEnvelope(target, binding, derivedChecksum, bootstrapSourceGeneration);
        JsonObject reversed = target.deepCopy();
        JsonObject targetDefinition = identityProjection() ? null
            : definition(reversed, ID.equals(id) ? "event.command" : "structure_delete").deepCopy();
        for (Change change : changes) {
            change.reverse(reversed);
        }
        if (!sourceChecksum.equals(CatalogCanonicalizer.checksumForCanonicalContent(reversed.toString()))) {
            throw new IllegalArgumentException("Evolution source catalog reverse proof is invalid");
        }
        JsonObject sourceDefinition = identityProjection() ? null
            : definition(reversed, ID.equals(id) ? "event.command" : "structure_delete");
        if (RESOURCE_DECLARATION_ID.equals(id)) {
            requireResourceDeclarationPolicy(reversed, target);
        } else if (REBIND_ID.equals(id)) {
            requireRebindPolicy(reversed, target);
        } else if (STRUCTURE_ID.equals(id)) {
            if (sourceDefinition.get("schemaVersion").getAsInt() != 2
                || targetDefinition.get("schemaVersion").getAsInt() != 3) {
                throw new IllegalArgumentException("Evolution structure descriptor versions are invalid");
            }
        } else {
            JsonArray previous = sourceDefinition.getAsJsonArray("pins");
            JsonArray next = targetDefinition.getAsJsonArray("pins");
            if (sourceDefinition.get("schemaVersion").getAsInt() != 2
                || targetDefinition.get("schemaVersion").getAsInt() != 3 || previous.size() != 4 || next.size() != 10) {
                throw new IllegalArgumentException("Evolution command descriptor versions or pin counts are invalid");
            }
            for (int index = 0; index < previous.size(); index++) {
                if (!previous.get(index).equals(next.get(index))) {
                    throw new IllegalArgumentException("Evolution changes an existing command pin");
                }
            }
        }
        Map<String, Integer> versions = new LinkedHashMap<>();
        for (JsonElement entry : reversed.getAsJsonArray("definitions")) {
            JsonObject definition = entry.getAsJsonObject();
            String key = definition.get("ownerId").getAsString() + "/" + definition.get("id").getAsString();
            if (versions.put(key, definition.get("schemaVersion").getAsInt()) != null) {
                throw new IllegalArgumentException("Evolution source catalog has duplicate definitions");
            }
        }
        Proof proof = new Proof(this, binding, versions);
        cachedProof = new CachedProof(binding, canonicalContent, proof);
        return proof;
    }

    private void requireRebindPolicy(JsonObject source, JsonObject target) {
        Map<String, JsonObject> previous = definitions(source);
        Map<String, JsonObject> next = definitions(target);
        if (!previous.keySet().equals(next.keySet())) {
            throw new IllegalArgumentException("Rebind catalog definition identities changed");
        }
        Set<String> unsafe = new LinkedHashSet<>();
        for (Map.Entry<String, JsonObject> entry : previous.entrySet()) {
            JsonObject before = entry.getValue();
            JsonObject after = next.get(entry.getKey());
            if (before.get("schemaVersion").getAsInt() != after.get("schemaVersion").getAsInt()) {
                throw new IllegalArgumentException("Rebind catalog definition versions changed");
            }
            if (!withoutSourceHash(before).equals(withoutSourceHash(after))) {
                unsafe.add(entry.getKey());
            }
        }
        if (!unsafe.equals(blockedDefinitions)) {
            throw new IllegalArgumentException("Rebind blocked definition policy does not match the catalog delta");
        }
        if (!source.getAsJsonArray("runtimeRequirements").equals(target.getAsJsonArray("runtimeRequirements"))) {
            throw new IllegalArgumentException("Rebind catalog runtime requirements changed");
        }
    }

    private void requireResourceDeclarationPolicy(JsonObject source, JsonObject target) {
        JsonObject previous = source.deepCopy();
        JsonObject next = target.deepCopy();
        requireResourceDeclarations(requiredArray(previous, "definitions"), requiredArray(next, "definitions"));
        requireResourceDeclarations(requiredArray(previous, "runtimeRequirements"), requiredArray(next, "runtimeRequirements"));
        JsonArray previousContributions = requiredArray(previous, "contributions");
        JsonArray nextContributions = requiredArray(next, "contributions");
        if (previousContributions.size() != nextContributions.size()) {
            throw new IllegalArgumentException("Resource declaration catalog contribution count changed");
        }
        for (int index = 0; index < previousContributions.size(); index++) {
            JsonObject previousContribution = requiredObject(previousContributions.get(index), "contribution");
            JsonObject nextContribution = requiredObject(nextContributions.get(index), "contribution");
            requireResourceDeclarations(requiredArray(previousContribution, "definitions"),
                requiredArray(nextContribution, "definitions"));
            requireResourceDeclarations(requiredArray(previousContribution, "runtimeRequirements"),
                requiredArray(nextContribution, "runtimeRequirements"));
        }
        for (String field : List.of("generation", "contentChecksum", "bindingManifestHash")) {
            previous.remove(field);
            next.remove(field);
        }
        if (!previous.equals(next)) {
            throw new IllegalArgumentException("Resource declaration catalog changed outside derived read authority");
        }
    }

    private static void requireResourceDeclarations(JsonArray previous, JsonArray next) {
        if (previous.size() != next.size()) {
            throw new IllegalArgumentException("Resource declaration descriptor count changed");
        }
        for (int index = 0; index < previous.size(); index++) {
            JsonObject before = requiredObject(previous.get(index), "resource declaration source descriptor");
            JsonObject after = requiredObject(next.get(index), "resource declaration target descriptor");
            JsonObject beforeSemantics = requiredObject(before.get("semantics"), "resource declaration source semantics");
            JsonObject afterSemantics = requiredObject(after.get("semantics"), "resource declaration target semantics");
            JsonArray beforeReads = requiredArray(beforeSemantics, "resourceReads");
            JsonArray beforeWrites = requiredArray(beforeSemantics, "resourceWrites");
            JsonArray afterReads = requiredArray(afterSemantics, "resourceReads");
            JsonArray afterWrites = requiredArray(afterSemantics, "resourceWrites");
            if (!beforeReads.isEmpty() || !beforeWrites.isEmpty() || !afterWrites.isEmpty()) {
                throw new IllegalArgumentException("Resource declaration authority is not a read-only empty-source transition");
            }
            JsonArray expectedReads = derivedResourceReads(requiredArray(after, "pins"));
            if (!expectedReads.equals(afterReads)) {
                throw new IllegalArgumentException("Resource declaration reads do not match descriptor pins");
            }
            beforeSemantics.add("resourceReads", new JsonArray());
            afterSemantics.add("resourceReads", new JsonArray());
        }
    }

    private static JsonArray derivedResourceReads(JsonArray pins) {
        Map<String, JsonObject> references = new LinkedHashMap<>();
        for (JsonElement value : pins) {
            JsonObject pin = requiredObject(value, "resource declaration pin");
            collectResourceTypes(requiredObject(pin.get("type"), "resource declaration pin type"), references);
        }
        List<String> keys = new ArrayList<>(references.keySet());
        keys.sort(String::compareTo);
        JsonArray reads = new JsonArray();
        keys.forEach(key -> reads.add(references.get(key).deepCopy()));
        return reads;
    }

    private static void collectResourceTypes(JsonObject type, Map<String, JsonObject> references) {
        String kind = requiredText(type, "kind");
        switch (kind) {
            case "resource" -> {
                JsonObject resource = requiredObject(type.get("resourceType"), "resource type reference");
                String owner = requiredText(resource, "ownerId");
                String localId = requiredText(resource, "localId");
                JsonObject reference = new JsonObject();
                reference.addProperty("localId", localId);
                reference.addProperty("ownerId", owner);
                references.put(owner + '\0' + localId, reference);
            }
            case "named" -> collectResourceTypes(requiredArray(type, "arguments"), references);
            case "optional", "list" -> collectResourceTypes(
                requiredObject(type.get("element"), "resource declaration element type"), references);
            case "map" -> {
                collectResourceTypes(requiredObject(type.get("key"), "resource declaration map key"), references);
                collectResourceTypes(requiredObject(type.get("value"), "resource declaration map value"), references);
            }
            case "tuple" -> collectResourceTypes(requiredArray(type, "elements"), references);
            case "result" -> {
                collectResourceTypes(requiredObject(type.get("success"), "resource declaration result success"), references);
                collectResourceTypes(requiredObject(type.get("failure"), "resource declaration result failure"), references);
            }
            case "union" -> {
                for (JsonElement value : requiredArray(type, "variants")) {
                    JsonObject variant = requiredObject(value, "resource declaration union variant");
                    collectResourceTypes(requiredObject(variant.get("type"), "resource declaration union type"), references);
                }
            }
            case "opaque" -> {
            }
            default -> throw new IllegalArgumentException("Resource declaration type kind is invalid: " + kind);
        }
    }

    private static void collectResourceTypes(JsonArray types, Map<String, JsonObject> references) {
        for (JsonElement value : types) {
            collectResourceTypes(requiredObject(value, "resource declaration type"), references);
        }
    }

    private static JsonArray requiredArray(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException("Resource declaration array is invalid: " + field);
        }
        return value.getAsJsonArray();
    }

    private static JsonObject requiredObject(JsonElement value, String field) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException("Resource declaration object is invalid: " + field);
        }
        return value.getAsJsonObject();
    }

    private static String requiredText(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
            || value.getAsString().isBlank()) {
            throw new IllegalArgumentException("Resource declaration text is invalid: " + field);
        }
        return value.getAsString();
    }

    private boolean identityProjection() {
        return REBIND_ID.equals(id) || RESOURCE_DECLARATION_ID.equals(id);
    }

    private static Map<String, JsonObject> definitions(JsonObject snapshot) {
        Map<String, JsonObject> definitions = new LinkedHashMap<>();
        for (JsonElement entry : snapshot.getAsJsonArray("definitions")) {
            JsonObject definition = entry.getAsJsonObject();
            String key = definition.get("ownerId").getAsString() + "/" + definition.get("id").getAsString();
            if (definitions.put(key, definition) != null) {
                throw new IllegalArgumentException("Rebind catalog has duplicate definitions");
            }
        }
        return definitions;
    }

    private static JsonObject withoutSourceHash(JsonObject definition) {
        JsonObject normalized = definition.deepCopy();
        JsonElement metadata = normalized.get("metadata");
        if (metadata != null && metadata.isJsonObject()) {
            JsonElement authored = metadata.getAsJsonObject().get("authoredSource");
            if (authored != null && authored.isJsonObject()) {
                JsonElement provenance = authored.getAsJsonObject().get("sourceProvenance");
                if (provenance != null && provenance.isJsonObject()) {
                    provenance.getAsJsonObject().remove("sourceHash");
                }
            }
        }
        return normalized;
    }

    boolean accepts(CatalogBinding source, CatalogBinding target) {
        return FUNCTION_ID.equals(id) ? source != null && target != null && target.generation() > source.generation()
            : sources.contains(source) && registeredTarget(target) && target.generation() > source.generation();
    }

    private void requireTarget(CatalogBinding target) {
        if (!registeredTarget(target)) {
            throw new IllegalArgumentException("Evolution target binding is not a registered forward catalog");
        }
    }

    private boolean registeredTarget(CatalogBinding target) {
        return !FUNCTION_ID.equals(id) && target != null && targetChecksum.equals(target.catalogChecksum())
            && (exactTarget == null || exactTarget.equals(target))
            && sources.stream().anyMatch(source -> target.generation() > source.generation());
    }

    private void requireTargetEnvelope(JsonObject target, CatalogBinding binding, ContentHash derivedChecksum,
                                       Long bootstrapSourceGeneration) {
        if (!"snapshot".equals(requiredEnvelopeText(target, "kind"))) {
            throw new IllegalArgumentException("Evolution target catalog envelope is invalid");
        }
        ContentHash embeddedChecksum = new ContentHash(requiredEnvelopeText(target, "contentChecksum"));
        ContentHash embeddedManifest = new ContentHash(requiredEnvelopeText(target, "bindingManifestHash"));
        if (!derivedChecksum.equals(embeddedChecksum)) {
            throw new IllegalArgumentException("Evolution target catalog envelope checksum does not match derived content");
        }
        if (!binding.bindingManifestHash().equals(embeddedManifest)) {
            throw new IllegalArgumentException("Evolution target catalog envelope manifest does not match the binding");
        }
        long embeddedGeneration = requiredEnvelopeGeneration(target);
        long floor = Math.addExact(sources.stream().mapToLong(CatalogBinding::generation).max().orElseThrow(), 1L);
        boolean floorBootstrap = bootstrapSourceGeneration != null && binding.generation() == floor
            && embeddedGeneration == bootstrapSourceGeneration
            && sources.stream().anyMatch(source -> source.generation() == bootstrapSourceGeneration);
        if (embeddedGeneration < 1L || embeddedGeneration != binding.generation() && !floorBootstrap) {
            throw new IllegalArgumentException("Evolution target catalog envelope generation does not match the binding");
        }
    }

    private static String requiredEnvelopeText(JsonObject target, String field) {
        JsonElement value = target.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Evolution target catalog envelope field is invalid: " + field);
        }
        return value.getAsString();
    }

    private static long requiredEnvelopeGeneration(JsonObject target) {
        JsonElement value = target.get("generation");
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Evolution target catalog envelope generation is invalid");
        }
        try {
            return value.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("Evolution target catalog envelope generation is invalid", failure);
        }
    }

    CoreGraphStorageBoundary.Decoded projectReceipt(CoreGraphStorageBoundary.Decoded source, CatalogBinding target,
                                                     UUID mutationId) {
        GraphDocument graph = CoreCatalogCompatibilityRebind.graph(source);
        if (source.envelope().assetFormatVersion() != CoreGraphStorageBoundary.CURRENT_ASSET_FORMAT_VERSION
            || !accepts(graph.catalogBinding(), target) || !projectionEligible(graph)) {
            throw new IllegalArgumentException("Core graph source binding is not registered for evolution");
        }
        List<GraphNode> nodes = graph.nodes().stream().map(node -> {
            if (identityProjection() || FUNCTION_ID.equals(id)) {
                return node;
            }
            if (STRUCTURE_ID.equals(id)) {
                if (STRUCTURE.equals(node.definition().canonicalText())) {
                    throw new IllegalArgumentException("Structure Delete requires an explicit typed resource migration");
                }
                return node;
            }
            if (!COMMAND.equals(node.definition().canonicalText())) {
                return node;
            }
            if (node.definitionVersion() != 2) {
                throw new IllegalArgumentException("Core command source version is not registered for evolution");
            }
            Map<Object, Object> inspector = new LinkedHashMap<>();
            inspector.putAll(node.inspector());
            inspector.putAll(node.inspectorFields());
            return new GraphNode(node.instanceId(), node.definition(), 3, node.modeId(), node.values(), inspector,
                node.branches(), node.repeatables(), node.inspectorState(), node.x(), node.y(), node.unknown());
        }).toList();
        long revision = Math.addExact(source.envelope().assetRevision(), 1L);
        GraphDocument evolved = new GraphDocument(graph.schemaVersion(), graph.resource(), revision, target,
            FUNCTION_ID.equals(id) ? functionCapabilities(graph) : graph.requiredCapabilities(),
            nodes, graph.connections(), graph.passthroughs(), graph.variables(), graph.functions(), graph.unknown());
        CoreGraphStorageBoundary.AssetMetadata metadata = new CoreGraphStorageBoundary.AssetMetadata(
            source.envelope().resourceType(), revision, mutationId, source.envelope().assetActivationState());
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        if (source.graphDocument() != null) {
            return boundary.decode(boundary.encode(evolved, metadata, graph.resource()), graph.resource());
        }
        FunctionSourceDocument function = source.functionSourceDocument();
        FunctionSignature signature = function.signature();
        FunctionSignature evolvedSignature = new FunctionSignature(signature.function(), new FunctionRevision(revision),
            signature.inputs(), signature.outputs(), signature.unknown());
        FunctionSourceDocument document = new FunctionSourceDocument(evolvedSignature, evolved, function.unknown());
        return boundary.decode(boundary.encode(document, metadata, graph.resource()), graph.resource());
    }

    private static Map<ContractRef<CapabilityId>, ContractRef<NodeId>> functionRequirements(GraphDocument graph) {
        Map<ContractRef<CapabilityId>, ContractRef<NodeId>> result = new LinkedHashMap<>();
        Set<ContractRef<NodeId>> definitions = new LinkedHashSet<>();
        graph.nodes().forEach(node -> definitions.add(node.definition()));
        for (ContractRef<CapabilityId> capability : graph.requiredCapabilities()) {
            List<ContractRef<NodeId>> matches = definitions.stream()
                .filter(node -> CatalogFunctionShape.legacyCapability(capability, node)).toList();
            if (matches.size() > 1) {
                throw new IllegalArgumentException("Historical Function capability identity is ambiguous");
            }
            if (matches.size() == 1) {
                result.put(capability, matches.getFirst());
            }
        }
        return Map.copyOf(result);
    }

    private static Set<ContractRef<CapabilityId>> functionCapabilities(GraphDocument graph) {
        Map<ContractRef<CapabilityId>, ContractRef<NodeId>> changes = functionRequirements(graph);
        Set<ContractRef<CapabilityId>> capabilities = new LinkedHashSet<>(graph.requiredCapabilities());
        changes.forEach((previous, node) -> {
            capabilities.remove(previous);
            capabilities.add(CatalogFunctionShape.capability(node));
        });
        return Set.copyOf(capabilities);
    }

    private boolean projectionEligible(GraphDocument graph) {
        return graph.nodes().stream().noneMatch(node -> blockedDefinitions.contains(node.definition().canonicalText()));
    }

    UUID mutationId(CoreGraphStorageBoundary.Decoded source, CatalogBinding target) {
        if (!accepts(CoreCatalogCompatibilityRebind.graph(source).catalogBinding(), target)) {
            throw new IllegalArgumentException("Evolution receipt binding edge is not registered");
        }
        return IdentityCodec.deterministicUuid(id, seed(source, target));
    }

    String fingerprint(CoreGraphStorageBoundary.Decoded source, CatalogBinding target, UUID mutationId) {
        return StorageSafety.sha256(String.join("\n", id, seed(source, target), mutationId.toString()));
    }

    private String seed(CoreGraphStorageBoundary.Decoded source, CatalogBinding target) {
        GraphDocument graph = CoreCatalogCompatibilityRebind.graph(source);
        return String.join("\n", registrationHash.canonicalText(), graph.resource().canonicalText(),
            Long.toString(source.envelope().assetRevision()), source.envelope().assetMutationId(),
            source.envelope().assetHash().canonicalText(), graph.catalogBinding().canonicalText(), target.canonicalText());
    }

    private static JsonObject definition(JsonObject snapshot, String localId) {
        for (JsonElement entry : snapshot.getAsJsonArray("definitions")) {
            JsonObject definition = entry.getAsJsonObject();
            if ("restudio.resync".equals(definition.get("ownerId").getAsString())
                && localId.equals(definition.get("id").getAsString())) {
                return definition;
            }
        }
        throw new IllegalArgumentException("Evolution definition is missing: " + localId);
    }

    private record CachedProof(CatalogBinding binding, String canonicalContent, Proof proof) {
    }

    public static final class Proof {
        private final CoreCatalogEvolution evolution;
        private final CatalogBinding target;
        private final Map<String, Integer> sourceVersions;

        private Proof(CoreCatalogEvolution evolution, CatalogBinding target, Map<String, Integer> sourceVersions) {
            this.evolution = evolution;
            this.target = target;
            this.sourceVersions = Map.copyOf(sourceVersions);
        }

        public CatalogBinding target() {
            return target;
        }

        CoreCatalogEvolution evolution() {
            return evolution;
        }

        public boolean eligible(CoreGraphStorageBoundary.Decoded source) {
            if (FUNCTION_ID.equals(evolution.id)) {
                if (source == null || source.envelope().assetFormatVersion() != CoreGraphStorageBoundary.CURRENT_ASSET_FORMAT_VERSION) {
                    return false;
                }
                GraphDocument graph = CoreCatalogCompatibilityRebind.graph(source);
                if (!evolution.accepts(graph.catalogBinding(), target)) {
                    return false;
                }
                Map<ContractRef<CapabilityId>, ContractRef<NodeId>> requirements = functionRequirements(graph);
                return !requirements.isEmpty() && requirements.values().stream().allMatch(node ->
                    Objects.equals(sourceVersions.get(node.canonicalText()), 1)
                        && graph.nodes().stream().filter(instance -> instance.definition().equals(node))
                            .allMatch(instance -> instance.definitionVersion() == 1));
            }
            return source != null && source.envelope().assetFormatVersion() == CoreGraphStorageBoundary.CURRENT_ASSET_FORMAT_VERSION
                && evolution.accepts(CoreCatalogCompatibilityRebind.graph(source).catalogBinding(), target)
                && evolution.projectionEligible(CoreCatalogCompatibilityRebind.graph(source))
                && CoreCatalogCompatibilityRebind.graph(source).nodes().stream().allMatch(node ->
                Objects.equals(sourceVersions.get(node.definition().canonicalText()), node.definitionVersion())
                    && !(STRUCTURE_ID.equals(evolution.id) && STRUCTURE.equals(node.definition().canonicalText())));
        }

        public CoreGraphStorageBoundary.Decoded project(CoreGraphStorageBoundary.Decoded source, UUID mutationId) {
            if (!eligible(source)) {
                throw new IllegalArgumentException("Core graph does not match the proven evolution source catalog");
            }
            return evolution.projectReceipt(source, target, mutationId);
        }

        UUID mutationId(CoreGraphStorageBoundary.Decoded source) {
            return evolution.mutationId(source, target);
        }

        String fingerprint(CoreGraphStorageBoundary.Decoded source, UUID mutationId) {
            return evolution.fingerprint(source, target, mutationId);
        }
    }

    private record Change(String kind, List<String> path, int index, int targetIndex, boolean beforePresent, boolean afterPresent,
                          String before, String after, String values) {
        private Change {
            path = List.copyOf(path);
        }

        private void reverse(JsonObject snapshot) {
            JsonElement parent = snapshot;
            int segments = "insert".equals(kind) || "relocate".equals(kind) ? path.size() : path.size() - 1;
            for (int segment = 0; segment < segments; segment++) {
                parent = parent.isJsonArray() ? parent.getAsJsonArray().get(Integer.parseInt(path.get(segment)))
                    : parent.getAsJsonObject().get(path.get(segment));
                if (parent == null) {
                    throw new IllegalArgumentException("Evolution proof path is missing");
                }
            }
            if ("relocate".equals(kind)) {
                JsonArray array = parent.getAsJsonArray();
                if (index < 0 || index >= array.size() || targetIndex < 0 || targetIndex >= array.size()
                    || !JsonParser.parseString(after).equals(array.get(targetIndex))) {
                    throw new IllegalArgumentException("Evolution relocated descriptor proof does not match");
                }
                array.remove(targetIndex);
                array.add(JsonParser.parseString(before));
                for (int offset = array.size() - 1; offset > index; offset--) {
                    array.set(offset, array.get(offset - 1));
                }
                array.set(index, JsonParser.parseString(before));
                return;
            }
            if ("insert".equals(kind)) {
                JsonArray array = parent.getAsJsonArray();
                JsonArray inserted = JsonParser.parseString(values).getAsJsonArray();
                for (int offset = 0; offset < inserted.size(); offset++) {
                    if (index + offset >= array.size() || !inserted.get(offset).equals(array.get(index + offset))) {
                        throw new IllegalArgumentException("Evolution inserted output proof does not match");
                    }
                }
                for (int offset = 0; offset < inserted.size(); offset++) {
                    array.remove(index);
                }
                return;
            }
            String field = path.getLast();
            JsonObject object = parent.getAsJsonObject();
            if (object.has(field) != afterPresent
                || afterPresent && !JsonParser.parseString(after).equals(object.get(field))) {
                throw new IllegalArgumentException("Evolution exact catalog delta does not match");
            }
            if (beforePresent) {
                object.add(field, JsonParser.parseString(before));
            } else {
                object.remove(field);
            }
        }
    }

    private static final class Registered {
        private static final CoreCatalogEvolution VALUE = read(RESOURCE, REGISTRATION_HASH);
        private static final CoreCatalogEvolution FUNCTIONS = read(
            "/restudio/resync/migration/core-function-capability-rebind-v1.json", new ContentHash("3dbaac39e42f14ab288915c9acbf71c825e12a234e0bca7161817fcc69b5a973"));
        private static final List<CoreCatalogEvolution> ALL = List.of(VALUE, FUNCTIONS,
            read("/restudio/resync/migration/core-catalog-evolution-v2.json", new ContentHash(
                "dd095d09369965822a9ebb068ebd06bff82ef8d5e9df73d7821bd232fd5c00e9")),
            read("/restudio/resync/migration/core-catalog-evolution-v3.json", new ContentHash(
                "f2a8183c1e52ae159d464294b115caa9a13456f26e9db3dfbb9e7d63d987da55")),
            read("/restudio/resync/migration/core-catalog-evolution-v4.json", new ContentHash(
                "a58aab1e0ddfd35fa3829b43e33ba614d1d5d0345ff889c9dfe8e7e0fd159428")));

        private static CoreCatalogEvolution read(String resource, ContentHash hash) {
            try (InputStream stream = CoreCatalogEvolution.class.getResourceAsStream(resource)) {
                if (stream == null) {
                    throw new IllegalStateException("Core catalog evolution registration is missing");
                }
                return new CoreCatalogEvolution(new String(stream.readAllBytes(), StandardCharsets.UTF_8), hash);
            } catch (IOException failure) {
                throw new IllegalStateException("Core catalog evolution registration could not be read", failure);
            }
        }
    }
}
