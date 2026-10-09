package restudio.resync.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.storage.AssetProjectMetadata;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.AssetTransactionCoordinator.AssetKey;
import restudio.resync.storage.AssetTransactionCoordinator.Deleted;
import restudio.resync.storage.AssetTransactionCoordinator.ExpectedState;
import restudio.resync.storage.AssetTransactionCoordinator.Live;
import restudio.resync.storage.AssetTransactionCoordinator.Missing;
import restudio.resync.storage.AssetTransactionCoordinator.MutationView;
import restudio.resync.storage.AssetTransactionCoordinator.Snapshot;
import restudio.resync.storage.ProjectMetadataRecovery;
import restudio.resync.storage.StorageSafety;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;

final class CoordinatedResourceHistory {
    private static final int MAX_BINDINGS = 16384;
    private static final long MAX_BYTES = 128L * 1024L * 1024L;
    private static final AssetKey LINEAGE = new AssetKey("project_metadata.lineage", "project");

    private CoordinatedResourceHistory() {
    }

    static List<Entry> read(AssetTransactionCoordinator coordinator, MutationView baseline, Snapshot snapshot,
                            Predicate<String> allowedType) throws IOException {
        try {
            return readChecked(coordinator, baseline, snapshot, allowedType);
        } catch (IllegalArgumentException | IllegalStateException | ArithmeticException exception) {
            throw new IOException("Coordinated resource history is invalid", exception);
        }
    }

    private static List<Entry> readChecked(AssetTransactionCoordinator coordinator, MutationView baseline,
                                           Snapshot snapshot, Predicate<String> allowedType) throws IOException {
        Path root = coordinator.canonicalRoot();
        Path bindings = root.resolve(".asset-coordinator/bindings");
        List<Path> files;
        try (Stream<Path> stream = Files.list(bindings)) {
            files = stream.sorted().limit(MAX_BINDINGS + 1L).toList();
        }
        if (files.size() > MAX_BINDINGS) throw failure("Binding inventory exceeds its recovery bound");
        List<MutationView> history = new ArrayList<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !name.endsWith(".json")) {
                throw failure("Binding inventory is not canonical");
            }
            UUID id = UUID.fromString(name.substring(0, name.length() - 5));
            if (!name.equals(id + ".json")) throw failure("Binding identity is not canonical");
            MutationView mutation = coordinator.mutation(id).orElseThrow(() -> failure("Binding has no durable mutation"));
            if (mutation.result().rootSequence() > baseline.result().rootSequence()
                && mutation.result().rootSequence() <= snapshot.rootSequence()) history.add(mutation);
        }
        history.sort(Comparator.comparingLong(mutation -> mutation.result().rootSequence()));
        Map<AssetKey, ExpectedState> states = new LinkedHashMap<>(baseline.result().states());
        Map<AssetKey, Path> paths = new LinkedHashMap<>();
        Map<AssetKey, UUID> mutations = new LinkedHashMap<>();
        Map<AssetKey, String> auxiliary = new LinkedHashMap<>();
        AssetProjectMetadata metadata = baseline.projectAfter();
        AssetTransactionCoordinator.ExpectedProject project = baseline.result().project();
        long sequence = baseline.result().rootSequence();
        long retained = 0L;
        List<Entry> entries = new ArrayList<>();
        for (MutationView mutation : history) {
            try {
                if (mutation.result().rootSequence() != Math.addExact(sequence, 1L)) throw failure("Root sequence is not contiguous");
                JsonObject intent = mutation.intent();
                JsonObject expectedProject = object(intent, "expectedProject");
                if (number(expectedProject, "revision") != project.revision()
                    || !text(expectedProject, "hash").equals(project.hash())) throw failure("Project baseline changed");
                Map<String, byte[]> writes = coordinator.inspectMutation(mutation.mutationId())
                    .orElseThrow(() -> failure("Mutation has no committed transaction evidence")).stagedWrites();
                for (byte[] bytes : writes.values()) {
                    retained = Math.addExact(retained, bytes.length);
                    if (retained > MAX_BYTES) throw failure("Staged evidence exceeds its recovery bound");
                }
                Map<AssetKey, JsonObject> assets = new LinkedHashMap<>();
                Map<AssetKey, JsonObject> payloads = new LinkedHashMap<>();
                for (JsonElement element : array(intent, "assets")) {
                    if (!element.isJsonObject()) throw failure("Asset intent is invalid");
                    JsonObject asset = element.getAsJsonObject();
                    AssetKey key = new AssetKey(text(asset, "type"), text(asset, "id"));
                    if (assets.putIfAbsent(key, asset) != null) throw failure("Asset identity repeats");
                    ExpectedState expected = expected(object(asset, "expected"));
                    ExpectedState known = states.get(key);
                    if (known != null && !known.equals(expected)) throw failure("Asset baseline changed");
                    ExpectedState result = mutation.result().states().get(key);
                    if (result == null || result.revision() != Math.addExact(expected.revision(), 1L)) {
                        throw failure("Asset revision did not advance exactly once");
                    }
                    String relative = text(asset, "path");
                    Path path = root.resolve(relative).normalize();
                    MigrationPaths.requireNoSymlinkTraversal(root, path);
                    if (!path.startsWith(root) || path.equals(root)
                        || !root.relativize(path).toString().replace('\\', '/').equals(relative)) {
                        throw failure("Asset path is not canonical within its root");
                    }
                    if (paths.containsKey(key) && !paths.get(key).equals(path)) throw failure("Asset path changed during recovery");
                    String operation = text(asset, "operation");
                    if ("WRITE".equals(operation) && result instanceof Live live) {
                        byte[] bytes = writes.get(relative);
                        if (bytes == null || bytes.length != number(asset, "payloadSize")
                            || !live.hash().equals(text(asset, "payloadHash"))
                            || !live.hash().equals(StorageSafety.sha256(bytes))) throw failure("Staged asset bytes differ from their result");
                        if (allowedType.test(key.type()) || key.equals(LINEAGE) || key.type().endsWith(".intent")
                            || key.type().endsWith(".tombstone") || key.type().startsWith("tombstone:")) {
                            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
                            if (!parsed.isJsonObject()) throw failure("Staged asset payload is not an object");
                            payloads.put(key, parsed.getAsJsonObject());
                        }
                    } else if ("DELETE".equals(operation) && expected instanceof Live && result instanceof Deleted deleted) {
                        String hash = StorageSafety.sha256("deleted\n" + key.canonical() + "\n" + deleted.revision()
                            + "\n" + mutation.mutationId());
                        if (!deleted.hash().equals(hash) || !textAllowEmpty(asset, "payloadHash").isEmpty()
                            || number(asset, "payloadSize") != 0L || writes.containsKey(relative)) throw failure("Deleted asset result is invalid");
                    } else {
                        throw failure("Asset operation is unsupported by recovery");
                    }
                    states.put(key, result);
                    paths.put(key, path);
                    mutations.put(key, mutation.mutationId());
                }
                if (!assets.keySet().equals(mutation.result().states().keySet())) throw failure("Asset result inventory differs from its intent");
                List<AssetKey> primaries = assets.keySet().stream().filter(key -> allowedType.test(key.type())).toList();
                if (primaries.isEmpty()) {
                    if (assets.containsKey(LINEAGE) || !array(intent, "projectDeltas").isEmpty()
                        || !project.equals(mutation.result().project())
                        || !metadata.document().equals(mutation.projectAfter().document())) {
                        throw failure("Unregistered asset mutation changes project metadata");
                    }
                    sequence = mutation.result().rootSequence();
                    continue;
                }
                if (primaries.size() != 1) throw failure("Mutation does not own one registered typed resource");
                AssetKey primary = primaries.getFirst();
                AssetKey intentKey = new AssetKey(primary.type() + ".intent", primary.id());
                AssetKey tombstoneKey = new AssetKey(primary.type() + ".tombstone", primary.id());
                AssetKey typedTombstone = new AssetKey("tombstone:" + primary.type(), primary.id());
                for (AssetKey key : assets.keySet()) {
                    if (!key.equals(primary) && !key.equals(LINEAGE) && !key.equals(intentKey) && !"blob".equals(key.type())
                        && !key.equals(tombstoneKey) && !key.equals(typedTombstone)) throw failure("Mutation includes an unrelated asset");
                }
                if (assets.containsKey(tombstoneKey) && assets.containsKey(typedTombstone)) throw failure("Mutation repeats tombstone ownership");
                ExpectedState before = expected(object(assets.get(primary), "expected"));
                ExpectedState after = mutation.result().states().get(primary);
                String operation = after instanceof Deleted ? "DELETE" : before instanceof Live ? "SAVE" : "CREATE";
                JsonObject payload = payloads.get(primary);
                if ("DELETE".equals(operation)) {
                    payload = payloads.get(tombstoneKey);
                    if (payload == null) payload = payloads.get(typedTombstone);
                    if (payload == null) throw failure("Deleted resource has no staged typed tombstone");
                    if (!Set.of("flow", "function", "command").contains(primary.type())) {
                        requireTombstone(payload, primary, after.revision(), mutation.mutationId());
                    }
                }
                JsonObject semantic = payloads.get(intentKey);
                if (assets.containsKey(intentKey) && semantic == null) throw failure("Resource intent was retired without replacement");
                if (semantic != null && (!primary.type().equals(text(semantic, "type"))
                    || !("DELETE".equals(operation) ? "delete" : "save").equals(text(semantic, "operation"))
                    || !"DELETE".equals(operation) && !primary.id().equals(text(semantic, "id")))) {
                    throw failure("Resource intent differs from its primary operation");
                }
                requireSemantic(semantic, payload, primary, operation, assets, mutation, auxiliary);
                Set<String> deltaPaths = new LinkedHashSet<>();
                List<AssetProjectMetadata.Delta> deltas = new ArrayList<>();
                for (JsonElement element : array(intent, "projectDeltas")) {
                    if (!element.isJsonObject()) throw failure("Project delta is invalid");
                    JsonObject delta = element.getAsJsonObject();
                    JsonArray path = array(delta, "path");
                    if (!delta.keySet().equals(Set.of("operation", "path", "value")) || path.size() != 1
                        || !"SET".equals(text(delta, "operation"))) throw failure("Project delta is not an exact field set");
                    String field = path.get(0).getAsString();
                    if (!Set.of("resources", "folders").contains(field) || !deltaPaths.add(field)) throw failure("Project delta changes an unrelated field");
                    deltas.add(AssetProjectMetadata.Delta.set(List.of(field), delta.get("value")));
                }
                AssetProjectMetadata afterMetadata = metadata.apply(deltas);
                boolean changed = !afterMetadata.document().equals(metadata.document());
                if (!afterMetadata.document().equals(mutation.projectAfter().document())
                    || changed && !afterMetadata.hash().equals(mutation.result().project().hash())
                    || !changed && !project.hash().equals(mutation.result().project().hash())
                    || mutation.result().project().revision() != project.revision() + (changed ? 1L : 0L)
                    || !changed && !deltas.isEmpty()
                    || changed != assets.containsKey(LINEAGE)) throw failure("Project result differs from its presentation and lineage effect");
                String relative = text(assets.get(primary), "path");
                if (changed) {
                    if (semantic == null) {
                        ProjectMetadataRecovery.requireStoredEffect(metadata.document(), afterMetadata.document(), primary.type(),
                            primary.id(), relative, operation, deltaPaths);
                    } else {
                        ProjectMetadataRecovery.requireEffect(metadata.document(), afterMetadata.document(), primary.type(),
                            primary.id(), relative, operation, semantic, deltaPaths);
                    }
                }
                entries.add(new Entry(mutation, primary, operation, before, after, payload, semantic, relative, changed));
                metadata = afterMetadata;
                project = mutation.result().project();
                sequence = mutation.result().rootSequence();
            } catch (IOException | RuntimeException exception) {
                throw new IOException("Coordinated resource history mutation " + mutation.mutationId()
                    + " at sequence " + mutation.result().rootSequence() + ": " + exception.getMessage(), exception);
            }
        }
        if (sequence != snapshot.rootSequence() || !project.equals(snapshot.project())
            || !metadata.document().equals(snapshot.metadata().document())) throw failure("History does not reach the active snapshot");
        for (AssetKey key : mutations.keySet()) {
            if (!states.get(key).equals(snapshot.state(key).orElse(null))
                || !mutations.get(key).toString().equals(snapshot.mutationValue(key).orElse(""))
                || !paths.get(key).equals(snapshot.path(key).orElse(null))) throw failure("Terminal asset state differs from its committed history");
        }
        coordinator.healthCheck();
        if (coordinator.read(Snapshot::rootSequence) != snapshot.rootSequence()) throw failure("Active root advanced during recovery validation");
        return List.copyOf(entries);
    }

    private static void requireTombstone(JsonObject payload, AssetKey key, long revision, UUID mutationId) throws IOException {
        if (!key.type().equals(text(payload, "type")) || !key.id().equals(text(payload, "id"))
            || revision != number(payload, "revision") || !mutationId.toString().equals(text(payload, "mutationId"))
            || !payload.has("deleted") || !payload.get("deleted").isJsonPrimitive()
            || !payload.get("deleted").getAsJsonPrimitive().isBoolean() || !payload.get("deleted").getAsBoolean()
            || !text(payload, "payloadHash").matches("[0-9a-f]{64}")) throw failure("Typed tombstone identity is invalid");
        if (payload.has("resourceType") && !key.type().equals(text(payload, "resourceType"))
            || payload.has("assetRevision") && revision != number(payload, "assetRevision")
            || payload.has("assetMutationId") && !mutationId.toString().equals(text(payload, "assetMutationId"))) {
            throw failure("Typed tombstone identities disagree");
        }
    }

    private static void requireSemantic(JsonObject semantic, JsonObject primary, AssetKey key, String operation,
                                         Map<AssetKey, JsonObject> assets, MutationView mutation,
                                         Map<AssetKey, String> auxiliary) throws IOException {
        List<AssetKey> blobs = assets.keySet().stream().filter(asset -> "blob".equals(asset.type())).toList();
        if (semantic == null) {
            if (!blobs.isEmpty() || !Set.of("flow", "function", "command", "gui", "scoreboard", "tab", "custom_content")
                .contains(key.type())) throw failure("Resource has no proved semantic intent or stored resource owner");
            return;
        }
        if ("DELETE".equals(operation)) {
            if (!semantic.keySet().equals(Set.of("operation", "type")) || !blobs.isEmpty()) {
                throw failure("Delete resource intent includes unrelated fields or auxiliary writes");
            }
            auxiliary.remove(key);
            return;
        }
        Set<String> fields = semantic.has("presentation")
            ? Set.of("operation", "type", "id", "payloadHash", "auxiliaryHash", "presentation")
            : Set.of("operation", "type", "id", "payloadHash", "auxiliaryHash");
        String hash = textAllowEmpty(semantic, "auxiliaryHash");
        if (!semantic.keySet().equals(fields) || !text(semantic, "payloadHash").matches("[0-9a-f]{64}")
            || !hash.isEmpty() && !hash.matches("[0-9a-f]{64}")
            || !hash.equals(textAllowEmpty(primary, "assetAuxiliaryHash"))) throw failure("Resource semantic hashes are invalid");
        List<String> fingerprints = new ArrayList<>();
        for (AssetKey blob : blobs) {
            JsonObject asset = assets.get(blob);
            String path = text(asset, "path");
            ExpectedState result = mutation.result().states().get(blob);
            if (!blob.id().equals(StorageSafety.sha256(path)) || !(result instanceof Live live)
                || !"WRITE".equals(text(asset, "operation"))) throw failure("Auxiliary asset does not own its committed path");
            fingerprints.add(path + "\n" + live.hash());
        }
        fingerprints.sort(String::compareTo);
        String expectedHash = fingerprints.isEmpty() ? "" : StorageSafety.sha256(String.join("\n", fingerprints));
        if (!hash.equals(expectedHash) && (!blobs.isEmpty() || !hash.equals(auxiliary.get(key)))) {
            throw failure("Auxiliary asset fingerprint is not proven by its history");
        }
        auxiliary.put(key, hash);
    }

    private static ExpectedState expected(JsonObject value) throws IOException {
        String kind = text(value, "kind");
        long revision = number(value, "revision");
        String hash = textAllowEmpty(value, "hash");
        return switch (kind) {
            case "MISSING" -> {
                if (revision != 0L || !hash.isEmpty()) throw failure("Missing asset baseline is invalid");
                yield Missing.INSTANCE;
            }
            case "LIVE" -> new Live(revision, hash);
            case "DELETED" -> new Deleted(revision, hash);
            default -> throw failure("Asset baseline kind is invalid");
        };
    }

    private static JsonObject object(JsonObject value, String field) throws IOException {
        if (value == null || !value.has(field) || !value.get(field).isJsonObject()) throw failure("Missing object " + field);
        return value.getAsJsonObject(field);
    }

    private static JsonArray array(JsonObject value, String field) throws IOException {
        if (value == null || !value.has(field) || !value.get(field).isJsonArray()) throw failure("Missing array " + field);
        return value.getAsJsonArray(field);
    }

    private static String text(JsonObject value, String field) throws IOException {
        String result = textAllowEmpty(value, field);
        if (result.isBlank()) throw failure("Blank text " + field);
        return result;
    }

    private static String textAllowEmpty(JsonObject value, String field) throws IOException {
        if (value == null || !value.has(field) || !value.get(field).isJsonPrimitive()
            || !value.get(field).getAsJsonPrimitive().isString()) throw failure("Invalid text " + field);
        return value.get(field).getAsString();
    }

    private static long number(JsonObject value, String field) throws IOException {
        if (value == null || !value.has(field) || !value.get(field).isJsonPrimitive()
            || !value.get(field).getAsJsonPrimitive().isNumber()) throw failure("Invalid number " + field);
        try {
            return value.get(field).getAsBigDecimal().longValueExact();
        } catch (ArithmeticException exception) {
            throw new IOException("Coordinated resource history has an inexact number " + field, exception);
        }
    }

    private static IOException failure(String message) {
        return new IOException("Coordinated resource history: " + message);
    }

    record Entry(MutationView mutation, AssetKey key, String operation, ExpectedState expected, ExpectedState result,
                 JsonObject primaryPayload, JsonObject semanticIntent, String path, boolean metadataChanged) {
    }
}
