package restudio.resync.storage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ProjectMetadataRecovery {
    private ProjectMetadataRecovery() {
    }

    public static void requireStoredEffect(JsonObject projectBefore, JsonObject projectAfter,
                                           String sourceType, String sourceId, String sourcePath,
                                           String operation, Set<String> deltaPaths) throws IOException {
        if (!Set.of("flow", "function", "command", "gui", "scoreboard", "tab", "custom_content").contains(sourceType)
            || !Set.of("CREATE", "SAVE", "DELETE").contains(operation)) {
            throw new IOException("Source resource mutation stored presentation operation is unsupported");
        }
        JsonArray beforeResources = projectMetadataArray(projectBefore, "resources");
        JsonArray beforeFolders = projectMetadataArray(projectBefore, "folders");
        JsonArray resources = storedResources(beforeResources);
        JsonArray folders = storedFolders(beforeFolders);
        List<JsonObject> matches = projectMetadataResourceMatches(resources, sourceType, sourceId);
        if ("DELETE".equals(operation)) {
            resources.asList().removeIf(element -> element.isJsonObject()
                && sourceType.equals(projectMetadataText(element.getAsJsonObject(), "type"))
                && sourceId.equals(projectMetadataText(element.getAsJsonObject(), "id")));
        } else {
            int separator = sourcePath.lastIndexOf('/');
            String folderPath = separator < 0 ? "" : sourcePath.substring(0, separator);
            ensureStoredFolders(folders, folderPath);
            JsonObject resource;
            if (matches.isEmpty()) {
                resource = new JsonObject();
                resource.addProperty("type", sourceType);
                resource.addProperty("id", sourceId);
                resource.addProperty("displayName", sourceId);
                resource.addProperty("path", folderPath);
                resource.addProperty("sortOrder", resources.size());
                resources.add(resource);
            } else {
                resource = matches.getFirst();
                resource.addProperty("path", folderPath);
                if (projectMetadataText(resource, "displayName").isBlank()) {
                    resource.addProperty("displayName", sourceId);
                }
            }
        }
        JsonObject expected = projectBefore.deepCopy();
        expected.add("resources", resources);
        expected.add("folders", folders);
        if (!expected.equals(projectAfter)) {
            throw new IOException("Source resource mutation stored metadata includes unrelated presentation changes");
        }
        Set<String> expectedDeltas = new LinkedHashSet<>();
        if (!beforeResources.equals(resources)) {
            expectedDeltas.add("resources");
        }
        if (!beforeFolders.equals(folders)) {
            expectedDeltas.add("folders");
        }
        if (!expectedDeltas.equals(deltaPaths)) {
            throw new IOException("Source resource mutation project deltas do not match its stored presentation effect");
        }
    }

    private static JsonArray storedResources(JsonArray before) throws IOException {
        JsonArray resources = before.deepCopy();
        for (JsonElement element : resources) {
            if (!element.isJsonObject()) {
                throw new IOException("Source resource mutation stored resource presentation is invalid");
            }
            JsonObject resource = element.getAsJsonObject();
            for (String field : List.of("type", "id", "displayName", "path")) {
                if (!resource.has(field)) {
                    resource.addProperty(field, "");
                }
            }
            if (!resource.has("sortOrder")) {
                resource.addProperty("sortOrder", 0);
            }
        }
        return resources;
    }

    private static JsonArray storedFolders(JsonArray before) throws IOException {
        JsonArray folders = before.deepCopy();
        for (JsonElement element : folders) {
            if (!element.isJsonObject()) {
                throw new IOException("Source resource mutation stored folder presentation is invalid");
            }
            JsonObject folder = element.getAsJsonObject();
            for (String field : List.of("path", "parentPath", "name")) {
                if (!folder.has(field)) {
                    folder.addProperty(field, "");
                }
            }
            if (!folder.has("sortOrder")) {
                folder.addProperty("sortOrder", 0);
            }
            if (!folder.has("collapsed")) {
                folder.addProperty("collapsed", false);
            }
        }
        return folders;
    }

    private static void ensureStoredFolders(JsonArray folders, String folderPath) throws IOException {
        if (folderPath.isBlank()) {
            return;
        }
        String parent = "";
        int order = folders.size();
        for (String part : folderPath.split("/")) {
            String path = parent.isBlank() ? part : parent + '/' + part;
            List<JsonObject> matches = folders.asList().stream().filter(JsonElement::isJsonObject)
                .map(JsonElement::getAsJsonObject).filter(folder -> path.equals(projectMetadataText(folder, "path"))).toList();
            if (matches.size() > 1) {
                throw new IOException("Source resource mutation project metadata repeats a folder path");
            }
            if (matches.isEmpty()) {
                JsonObject folder = new JsonObject();
                folder.addProperty("path", path);
                folder.addProperty("parentPath", parent);
                folder.addProperty("name", part);
                folder.addProperty("sortOrder", order);
                folder.addProperty("collapsed", false);
                folders.add(folder);
            }
            order++;
            parent = path;
        }
    }

    public static void requireEffect(JsonObject projectBefore, JsonObject projectAfter,
                                     String sourceType, String sourceId, String sourcePath,
                                     String operation, JsonObject semanticIntent,
                                     Set<String> deltaPaths) throws IOException {
        semanticIntent = semanticIntent == null ? new JsonObject() : semanticIntent;
        JsonArray beforeResources = projectMetadataArray(projectBefore, "resources");
        JsonArray beforeFolders = projectMetadataArray(projectBefore, "folders");
        JsonArray resources = beforeResources.deepCopy();
        JsonArray folders = beforeFolders.deepCopy();
        List<JsonObject> matches = projectMetadataResourceMatches(resources, sourceType, sourceId);
        if (("CREATE".equals(operation) && !matches.isEmpty())
            || ("SAVE".equals(operation) && matches.size() != 1)
            || ("DELETE".equals(operation) && matches.size() != 1)) {
            throw new IOException("Source resource mutation presentation precondition is invalid for " + operation);
        }
        resources.asList().removeIf(element -> element.isJsonObject()
            && sourceType.equals(projectMetadataText(element.getAsJsonObject(), "type"))
            && sourceId.equals(projectMetadataText(element.getAsJsonObject(), "id")));
        if (!"DELETE".equals(operation)) {
            JsonObject resource = matches.isEmpty() ? new JsonObject() : matches.getFirst().deepCopy();
            resource.addProperty("type", sourceType);
            resource.addProperty("id", sourceId);
            resource.addProperty("path", sourcePath);
            if (semanticIntent.has("presentation")) {
                JsonObject presentation = object(semanticIntent, "presentation",
                    "Source resource mutation presentation intent is invalid");
                if (!presentation.keySet().equals(Set.of("displayName", "path", "sortOrder"))
                    || !sourcePath.equals(stringValue(presentation, "path",
                    "Source resource mutation presentation path is invalid"))) {
                    throw new IOException("Source resource mutation presentation intent does not match its asset path");
                }
                applyProjectMetadataPresentation(resource, presentation, "displayName");
                applyProjectMetadataPresentation(resource, presentation, "sortOrder");
            }
            ensureProjectMetadataRecoveryFolders(folders, sourcePath);
            resources.add(resource);
        }
        JsonObject expected = projectBefore.deepCopy();
        expected.add("resources", resources);
        if (!beforeFolders.equals(folders)) {
            expected.add("folders", folders);
        }
        if (!expected.equals(projectAfter)) {
            throw new IOException("Source resource mutation project metadata transition includes unrelated presentation changes");
        }
        Set<String> expectedDeltas = new LinkedHashSet<>();
        if (!beforeResources.equals(resources)) {
            expectedDeltas.add("resources");
        }
        if (!beforeFolders.equals(folders)) {
            expectedDeltas.add("folders");
        }
        if (!expectedDeltas.equals(deltaPaths)) {
            throw new IOException("Source resource mutation project deltas do not match its presentation effect");
        }
    }

    private static JsonArray projectMetadataArray(JsonObject metadata, String field) throws IOException {
        JsonElement value = metadata.get(field);
        if (value == null || value.isJsonNull()) {
            return new JsonArray();
        }
        if (!value.isJsonArray()) {
            throw new IOException("Source resource mutation project metadata " + field + " is invalid");
        }
        return value.getAsJsonArray().deepCopy();
    }

    private static List<JsonObject> projectMetadataResourceMatches(JsonArray resources, String sourceType,
                                                                String sourceId) throws IOException {
        List<JsonObject> matches = resources.asList().stream().filter(JsonElement::isJsonObject)
            .map(JsonElement::getAsJsonObject)
            .filter(resource -> sourceType.equals(projectMetadataText(resource, "type"))
                && sourceId.equals(projectMetadataText(resource, "id")))
            .toList();
        if (matches.size() > 1) {
            throw new IOException("Source resource mutation project metadata repeats its typed identity");
        }
        return matches;
    }

    private static String projectMetadataText(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
            ? value.getAsString() : "";
    }

    private static void applyProjectMetadataPresentation(JsonObject resource, JsonObject presentation, String field) {
        JsonElement value = presentation.get(field);
        if (value != null && !value.isJsonNull()) {
            resource.add(field, value.deepCopy());
        }
    }

    private static void ensureProjectMetadataRecoveryFolders(JsonArray folders, String resourcePath) throws IOException {
        int separator = resourcePath.lastIndexOf('/');
        if (separator < 1) {
            return;
        }
        String parent = "";
        for (String part : resourcePath.substring(0, separator).split("/")) {
            String path = parent.isBlank() ? part : parent + '/' + part;
            List<JsonObject> matches = folders.asList().stream().filter(JsonElement::isJsonObject)
                .map(JsonElement::getAsJsonObject).filter(folder -> path.equals(projectMetadataText(folder, "path"))).toList();
            if (matches.size() > 1) {
                throw new IOException("Source resource mutation project metadata repeats a folder path");
            }
            if (matches.isEmpty()) {
                JsonObject folder = new JsonObject();
                folder.addProperty("path", path);
                folder.addProperty("parentPath", parent);
                folder.addProperty("name", part);
                folder.addProperty("sortOrder", 0);
                folder.addProperty("collapsed", false);
                folders.add(folder);
            }
            parent = path;
        }
    }

    private static JsonObject object(JsonObject owner, String field, String message) throws IOException {
        if (owner == null || !owner.has(field) || !owner.get(field).isJsonObject()) {
            throw new IOException(message);
        }
        return owner.getAsJsonObject(field);
    }

    private static String stringValue(JsonObject owner, String field, String message) throws IOException {
        try {
            String value = owner.get(field).getAsString();
            if (value == null || value.isBlank()) {
                throw new IOException(message);
            }
            return value;
        } catch (NullPointerException | UnsupportedOperationException | ClassCastException | IllegalStateException exception) {
            throw new IOException(message, exception);
        }
    }
}
