package restudio.resync.storage;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import restudio.resync.flow.resource.ResourcePayloadCodecs;
import restudio.resync.resources.JsonAssetStore;
import restudio.resync.storage.AssetTransactionCoordinator.AssetDelta;
import restudio.resync.storage.AssetTransactionCoordinator.AssetKey;
import restudio.resync.storage.AssetTransactionCoordinator.Deleted;
import restudio.resync.storage.AssetTransactionCoordinator.ExpectedState;
import restudio.resync.storage.AssetTransactionCoordinator.Live;
import restudio.resync.storage.AssetTransactionCoordinator.Missing;
import restudio.resync.storage.AssetTransactionCoordinator.Snapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

public final class ProjectMetadataLineage {
    private static final String TYPE = "project_metadata";
    private static final String LINEAGE_TYPE = "project_metadata.lineage";
    private static final String LINEAGE_ID = "project";
    private static final String FORMAT = "project-metadata-lineage-v1";

    private ProjectMetadataLineage() {
    }

    public static JsonAssetStore.ProjectMetadataLineageWriter writer(Path assetsRoot, Gson gson) {
        return writer(assetsRoot, gson, true);
    }

    public static JsonAssetStore.ProjectMetadataLineageWriter writerIfPresent(Path assetsRoot, Gson gson) {
        return writer(assetsRoot, gson, false);
    }

    private static JsonAssetStore.ProjectMetadataLineageWriter writer(Path assetsRoot, Gson gson, boolean required) {
        Path lineageFile = assetsRoot.resolve(".durability").resolve("project-metadata-lineage.v1.json")
            .toAbsolutePath().normalize();
        AssetKey lineageKey = new AssetKey(LINEAGE_TYPE, LINEAGE_ID);
        return (snapshot, projectDeltas, mutationId) -> {
            AssetProjectMetadata after = snapshot.metadata().apply(projectDeltas.stream()
                .map(delta -> new AssetProjectMetadata.Delta(delta.path(), delta.value(), delta.remove()))
                .toList());
            String resourceId = resourceId(after.document(), required);
            if (resourceId == null) {
                return null;
            }
            ExpectedState expected = snapshot.state(lineageKey).orElse(Missing.INSTANCE);
            String currentPayloadHash = ResourcePayloadCodecs.json().hashPayload(
                gson.fromJson(snapshot.metadata().serializedJson(), Map.class)).canonicalText();
            long revision = revision(snapshot, expected, lineageKey, lineageFile, resourceId, currentPayloadHash);
            String payloadHash = ResourcePayloadCodecs.json().hashPayload(
                gson.fromJson(after.serializedJson(), Map.class)).canonicalText();
            JsonObject lineage = new JsonObject();
            lineage.addProperty("format", FORMAT);
            lineage.addProperty("type", TYPE);
            lineage.addProperty("id", resourceId);
            lineage.addProperty("revision", revision);
            lineage.addProperty("mutationId", mutationId.toString());
            lineage.addProperty("payloadHash", payloadHash);
            lineage.addProperty("deleted", false);
            return AssetDelta.write(lineageKey, lineageFile, expected,
                gson.toJson(lineage).getBytes(StandardCharsets.UTF_8));
        };
    }

    private static String resourceId(JsonObject metadata, boolean required) throws IOException {
        String resourceId = text(metadata, "serverId");
        if (resourceId.isBlank()) {
            if (!required) {
                return null;
            }
            throw new IOException("Project metadata server identity is missing");
        }
        try {
            UUID parsed = UUID.fromString(resourceId);
            if (!parsed.toString().equals(resourceId)) {
                throw new IOException("Project metadata server identity is not canonical");
            }
        } catch (IllegalArgumentException failure) {
            throw new IOException("Project metadata server identity is invalid", failure);
        }
        return resourceId;
    }

    private static long revision(Snapshot snapshot, ExpectedState expected, AssetKey lineageKey,
                                 Path lineageFile, String resourceId, String currentPayloadHash) throws IOException {
        if (expected instanceof Missing) {
            return Math.addExact(snapshot.project().revision(), 1L);
        }
        if (expected instanceof Deleted) {
            throw new IOException("Project metadata lineage state is deleted");
        }
        if (!(expected instanceof Live live)) {
            throw new IOException("Project metadata lineage state is invalid");
        }
        Path currentFile = snapshot.path(lineageKey)
            .orElseThrow(() -> new IOException("Project metadata lineage state has no path"));
        Path normalizedCurrentFile = currentFile.toAbsolutePath().normalize();
        if (!normalizedCurrentFile.equals(lineageFile)
            || !Files.isRegularFile(normalizedCurrentFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Project metadata lineage state has an unexpected path");
        }
        byte[] bytes = Files.readAllBytes(normalizedCurrentFile);
        if (!live.hash().equals(StorageSafety.sha256(bytes))) {
            throw new IOException("Project metadata lineage differs from the shared asset coordinator");
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
        } catch (RuntimeException failure) {
            throw new IOException("Project metadata lineage is invalid", failure);
        }
        if (!parsed.isJsonObject()) {
            throw new IOException("Project metadata lineage is not a JSON object");
        }
        JsonObject lineage = parsed.getAsJsonObject();
        String format = requiredString(lineage, "format");
        String type = requiredString(lineage, "type");
        String id = requiredString(lineage, "id");
        String mutationId = requiredString(lineage, "mutationId");
        String payloadHash = requiredString(lineage, "payloadHash");
        long currentRevision;
        boolean deleted;
        try {
            currentRevision = lineage.get("revision").getAsLong();
            deleted = lineage.get("deleted").getAsBoolean();
        } catch (RuntimeException failure) {
            throw new IOException("Project metadata lineage identity is invalid", failure);
        }
        if (!FORMAT.equals(format) || !TYPE.equals(type) || !resourceId.equals(id) || currentRevision < 1L
            || !payloadHash.equals(currentPayloadHash) || deleted
            || !mutationId.equals(snapshot.mutationValue(lineageKey).orElse(""))) {
            throw new IOException("Project metadata lineage identity is invalid");
        }
        try {
            UUID parsedMutationId = UUID.fromString(mutationId);
            if (!parsedMutationId.toString().equals(mutationId)) {
                throw new IOException("Project metadata lineage mutation ID is not canonical");
            }
        } catch (IllegalArgumentException failure) {
            throw new IOException("Project metadata lineage mutation ID is invalid", failure);
        }
        return Math.addExact(currentRevision, 1L);
    }

    private static String requiredString(JsonObject object, String field) throws IOException {
        String value = text(object, field);
        if (value.isBlank()) {
            throw new IOException("Project metadata lineage has an invalid " + field);
        }
        return value;
    }

    private static String text(JsonObject object, String field) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) {
            return "";
        }
        try {
            return object.get(field).getAsString();
        } catch (RuntimeException failure) {
            return "";
        }
    }
}
