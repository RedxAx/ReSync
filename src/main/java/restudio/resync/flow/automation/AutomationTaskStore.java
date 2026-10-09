package restudio.resync.flow.automation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.storage.RecoverableJsonStore;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class AutomationTaskStore {
    private static final Gson GSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    private static final Type STATE_LIST = new TypeToken<List<AutomationTaskService.PersistentTask>>() { }.getType();
    private final Path file;
    private final RecoverableJsonStore store;

    AutomationTaskStore(Path file) {
        this.file = file != null ? file.toAbsolutePath().normalize() : null;
        this.store = this.file != null ? new RecoverableJsonStore(this.file, GSON) : null;
    }

    Path file() {
        return file;
    }

    synchronized List<AutomationTaskService.PersistentTask> load() {
        try {
            return loadStrict();
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("Failed to load persistent automation tasks: " + file, failure);
        }
    }

    synchronized List<AutomationTaskService.PersistentTask> loadStrict() throws IOException {
        if (file == null || !Files.exists(file)) {
            return List.of();
        }
        if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) {
            throw new IOException("Automation task journal must be a regular non-symbolic-link file: " + file);
        }
        try {
            List<AutomationTaskService.PersistentTask> states = GSON.fromJson(normalizeTargetPayload(store.load()), STATE_LIST);
            return states != null ? List.copyOf(states) : List.of();
        } catch (RuntimeException | IOException failure) {
            throw new IOException("Failed to load persistent automation tasks: " + file, failure);
        }
    }

    synchronized void save(List<AutomationTaskService.PersistentTask> states) throws IOException {
        if (file == null) {
            return;
        }
        store.save(GSON.toJsonTree(new ArrayList<>(states), STATE_LIST));
    }

    synchronized void ensurePresent() throws IOException {
        if (file != null && !Files.exists(file)) {
            save(List.of());
        }
    }

    synchronized void healthCheck(List<AutomationTaskService.PersistentTask> expected) throws IOException {
        if (file == null) {
            return;
        }
        if (!Files.isRegularFile(file)) {
            throw new IOException("Automation task journal is missing: " + file);
        }
        List<AutomationTaskService.PersistentTask> actual = loadStrict();
        String expectedJson = GSON.toJson(new ArrayList<>(expected != null ? expected : List.of()), STATE_LIST);
        String actualJson = GSON.toJson(new ArrayList<>(actual), STATE_LIST);
        if (!expectedJson.equals(actualJson)) {
            throw new IOException("Automation task journal is out of sync: " + file);
        }
    }

    private static JsonElement normalizeTargetPayload(JsonElement payload) {
        if (payload == null || !payload.isJsonArray()) {
            return payload;
        }
        JsonArray normalized = payload.deepCopy().getAsJsonArray();
        for (JsonElement value : normalized) {
            if (!value.isJsonObject()) {
                continue;
            }
            JsonObject task = value.getAsJsonObject();
            if (!"schedule".equalsIgnoreCase(text(task, "kind"))) {
                continue;
            }
            normalizeLegacyTarget(task);
            normalizeLocatorObject(task);
        }
        return normalized;
    }

    private static void normalizeLegacyTarget(JsonObject task) {
        if (!task.has("target") || !task.get("target").isJsonObject()) {
            return;
        }
        JsonObject target = task.getAsJsonObject("target");
        String type = text(target, "type");
        if (type.isBlank() && target.has("type") && target.get("type").isJsonObject()) {
            type = text(target.getAsJsonObject("type"), "localId");
        }
        String id = text(target, "id");
        if (type.isBlank() || id.isBlank()) {
            throw new IllegalArgumentException("Persisted automation target is incomplete");
        }
        if ((task.has("targetType") && !text(task, "targetType").equalsIgnoreCase(type))
            || (task.has("targetId") && !text(task, "targetId").equals(id))) {
            throw new IllegalArgumentException("Persisted automation target identity fields conflict");
        }
        task.addProperty("targetType", type);
        task.addProperty("targetId", id);
    }

    private static void normalizeLocatorObject(JsonObject task) {
        if (!task.has("targetLocator") || !task.get("targetLocator").isJsonObject()) {
            return;
        }
        JsonObject locator = task.getAsJsonObject("targetLocator");
        String serverText = text(locator, "serverId");
        String id = text(locator, "id");
        JsonObject type = locator.has("type") && locator.get("type").isJsonObject()
            ? locator.getAsJsonObject("type") : null;
        String owner = type != null ? text(type, "ownerId") : "";
        String resourceType = type != null ? text(type, "localId") : "";
        if (serverText.isBlank() || owner.isBlank() || resourceType.isBlank() || id.isBlank()) {
            throw new IllegalArgumentException("Persisted automation target locator is incomplete");
        }
        ServerResourceLocator parsed = new ServerResourceLocator(ServerId.parseCanonicalText(serverText),
            ContractRef.of(OwnerId.of(owner), ResourceTypeId.of(resourceType)), id);
        task.addProperty("targetLocator", parsed.canonicalText());
    }

    private static String text(JsonObject object, String field) {
        return object.has(field) && object.get(field).isJsonPrimitive() && !object.get(field).isJsonNull()
            ? object.get(field).getAsString().trim() : "";
    }
}
