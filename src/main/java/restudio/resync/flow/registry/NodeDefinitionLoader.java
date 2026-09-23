package restudio.resync.flow.registry;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.SerializedName;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowTypeRef;
import restudio.resync.Log;
import restudio.resync.flow.catalog.CatalogSourceIndex;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.PinId;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class NodeDefinitionLoader {

    private static final Set<String> REPLACEMENT_LIFECYCLE_VALUES = Set.of("active", "deprecated", "retiring", "migration-only");

    public enum CatalogSource {
        REPLACEMENT,
        COMPATIBILITY
    }

    public enum SourceOrigin {
        CLASSPATH,
        LOCAL
    }

    public record SourceFile(String sourceName, String sourceUri, String relativePath, SourceOrigin origin, byte[] bytes) {
        public SourceFile {
            sourceName = sourceName == null || sourceName.isBlank() ? "source" : sourceName;
            sourceUri = sourceUri == null || sourceUri.isBlank() ? sourceName : sourceUri;
            relativePath = relativePath == null ? "" : relativePath;
            origin = origin == null ? SourceOrigin.CLASSPATH : origin;
            bytes = bytes == null ? new byte[0] : bytes.clone();
        }

        public byte[] rawBytes() {
            return bytes;
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private final Gson gson;
    private final List<NodeDefinitionDiagnostic> diagnostics = new ArrayList<>();
    private final Map<NodeDefinition, DefinitionOrigin> origins = new IdentityHashMap<>();
    private NodeDefinitionValidator validator;

    public NodeDefinitionLoader() {
        this.gson = new GsonBuilder()
            .registerTypeAdapter(NodeDefinition.PinType.class, new EnumAdapter<>(NodeDefinition.PinType.class))
            .registerTypeAdapter(NodeDefinition.PinDirection.class, new EnumAdapter<>(NodeDefinition.PinDirection.class))
            .registerTypeAdapter(NodeDefinition.WidgetType.class, new EnumAdapter<>(NodeDefinition.WidgetType.class))
            .registerTypeAdapter(NodeDefinition.NodeCategory.class, new TypeAdapter<NodeDefinition.NodeCategory>() {
                @Override
                public void write(JsonWriter out, NodeDefinition.NodeCategory value) throws IOException {
                    out.value(value != null ? value.getId() : null);
                }

                @Override
                public NodeDefinition.NodeCategory read(JsonReader in) throws IOException {
                    String id = in.nextString();
                    NodeDefinition.NodeCategory category = NodeDefinition.NodeCategory.find(id);
                    if (category == null) {
                        throw new JsonParseException("Unknown node category: " + id);
                    }
                    return category;
                }
            })
            .registerTypeAdapter(PinId.class, new TypeAdapter<PinId>() {
                @Override
                public void write(JsonWriter out, PinId value) throws IOException {
                    out.value(value != null ? value.value() : null);
                }

                @Override
                public PinId read(JsonReader in) throws IOException {
                    String value = in.nextString();
                    return value == null || value.isBlank() ? null : PinId.of(value);
                }
            })
            .registerTypeAdapter(NodeDefinition.NodeKind.class, new EnumAdapter<>(NodeDefinition.NodeKind.class))
            .create();
    }

    public void setValidator(NodeDefinitionValidator validator) {
        this.validator = validator;
    }

    public List<NodeDefinitionDiagnostic> getDiagnostics() {
        return List.copyOf(diagnostics);
    }

    public void recordFailure(String code, String source, String message) {
        addDiagnostic(NodeDefinitionDiagnostic.Severity.ERROR, code, source, -1, "", message);
    }

    public List<NodeDefinition> loadFromClasspath(String resourcePath) {
        return loadFromClasspath(resourcePath, CatalogSource.COMPATIBILITY);
    }

    public List<NodeDefinition> loadReplacementFromClasspath(String resourcePath) {
        return loadFromClasspath(resourcePath, CatalogSource.REPLACEMENT);
    }

    public List<NodeDefinition> loadFromClasspath(String resourcePath, CatalogSource source) {
        List<NodeDefinition> results = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        CatalogSource selectedSource = source != null ? source : CatalogSource.COMPATIBILITY;

        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = NodeDefinitionLoader.class.getClassLoader();
        }

        try {
            boolean loaded = loadFromCodeSource(resourcePath, results, errors, selectedSource);
            if (!loaded) {
                loadFromClassLoaderUrls(classLoader, resourcePath, results, errors, selectedSource);
            }
        } catch (Exception e) {
            Log.warn("[NodeDefinitionLoader] Failed to scan classpath resources: " + e.getMessage());
        }

        if (!errors.isEmpty()) {
            for (String error : errors) {
                Log.warn("[NodeDefinitionLoader] " + error);
            }
        }

        return results;
    }

    public List<NodeDefinition> loadFromClassLoader(ClassLoader classLoader, String resourcePath) {
        return loadFromClassLoader(classLoader, resourcePath, CatalogSource.COMPATIBILITY);
    }

    public List<NodeDefinition> loadReplacementFromClassLoader(ClassLoader classLoader, String resourcePath) {
        return loadFromClassLoader(classLoader, resourcePath, CatalogSource.REPLACEMENT);
    }

    public List<NodeDefinition> loadFromClassLoader(ClassLoader classLoader, String resourcePath, CatalogSource source) {
        List<NodeDefinition> results = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        CatalogSource selectedSource = source != null ? source : CatalogSource.COMPATIBILITY;
        if (classLoader == null || resourcePath == null || resourcePath.isBlank()) {
            return results;
        }
        try {
            loadFromClassLoaderUrls(classLoader, resourcePath, results, errors, selectedSource);
        } catch (Exception e) {
            Log.warn("[NodeDefinitionLoader] Failed to scan extension resources: " + e.getMessage());
        }
        if (!errors.isEmpty()) {
            for (String error : errors) {
                Log.warn("[NodeDefinitionLoader] " + error);
            }
        }
        return results;
    }

    private boolean loadFromCodeSource(String resourcePath, List<NodeDefinition> results, List<String> errors, CatalogSource source) throws IOException, URISyntaxException {
        URL location = NodeDefinitionLoader.class.getProtectionDomain().getCodeSource().getLocation();
        if (location == null) {
            return false;
        }

        Path codeSourcePath = Paths.get(location.toURI());
        if (Files.isDirectory(codeSourcePath)) {
            Path root = codeSourcePath.resolve(resourcePath);
            if (!Files.exists(root)) {
                return false;
            }
            scanPath(root, results, errors, source);
            return true;
        }

        if (Files.isRegularFile(codeSourcePath) && codeSourcePath.toString().toLowerCase().endsWith(".jar")) {
            scanJar(codeSourcePath, resourcePath, results, errors, source);
            return true;
        }

        return false;
    }

    private void loadFromClassLoaderUrls(ClassLoader classLoader, String resourcePath, List<NodeDefinition> results, List<String> errors, CatalogSource source) throws IOException, URISyntaxException {
        Enumeration<URL> urls = classLoader.getResources(resourcePath);
        while (urls.hasMoreElements()) {
            URL url = urls.nextElement();
            URI uri = url.toURI();
            if ("jar".equalsIgnoreCase(uri.getScheme())) {
                String ssp = uri.getSchemeSpecificPart();
                int bang = ssp.indexOf("!");
                if (bang <= 0) {
                    continue;
                }
                String jarUri = ssp.substring(0, bang);
                if (jarUri.startsWith("file:")) {
                    scanJar(Paths.get(URI.create(jarUri)), resourcePath, results, errors, source);
                }
            } else if ("file".equalsIgnoreCase(uri.getScheme())) {
                scanPath(Paths.get(uri), results, errors, source);
            }
        }
    }

    private void scanPath(Path root, List<NodeDefinition> results, List<String> errors, CatalogSource source) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".json"))
                .filter(p -> !p.getFileName().toString().startsWith("_"))
                .forEach(p -> {
                    try (InputStream is = Files.newInputStream(p)) {
                        results.addAll(parse(is, p.toString(), source));
                    } catch (Exception e) {
                        errors.add(p.getFileName() + ": " + e.getMessage());
                    }
                });
        }
    }

    private void scanJar(Path jarPath, String resourcePath, List<NodeDefinition> results, List<String> errors, CatalogSource source) throws IOException {
        String normalized = resourcePath.endsWith("/") ? resourcePath : resourcePath + "/";
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory()) {
                    continue;
                }
                if (!name.startsWith(normalized) || !name.endsWith(".json")) {
                    continue;
                }
                String fileName = name.substring(name.lastIndexOf('/') + 1);
                if (fileName.startsWith("_")) {
                    continue;
                }
                try (InputStream is = jarFile.getInputStream(entry)) {
                    results.addAll(parse(is, name, source));
                } catch (Exception e) {
                    errors.add(fileName + ": " + e.getMessage());
                }
            }
        }
    }

    public List<NodeDefinition> loadFromDirectory(Path dir) {
        return loadFromDirectory(dir, CatalogSource.COMPATIBILITY);
    }

    public List<NodeDefinition> loadReplacementFromDirectory(Path dir) {
        return loadFromDirectory(dir, CatalogSource.REPLACEMENT);
    }

    public List<NodeDefinition> loadReplacementFromSources(List<SourceFile> sources) {
        return loadFromSources(sources, CatalogSource.REPLACEMENT);
    }

    public List<NodeDefinition> loadFromSources(List<SourceFile> sources, CatalogSource source) {
        List<NodeDefinition> results = new ArrayList<>();
        if (sources == null || sources.isEmpty()) {
            return results;
        }
        CatalogSource selectedSource = source != null ? source : CatalogSource.COMPATIBILITY;
        List<SourceFile> present = new ArrayList<>();
        for (SourceFile sourceFile : sources) {
            if (sourceFile != null) {
                present.add(sourceFile);
            }
        }
        present.parallelStream().forEach(sourceFile -> CatalogSourceIndex.sourceHash(sourceFile.rawBytes()));
        for (SourceFile sourceFile : present) {
            try {
                results.addAll(parse(sourceFile.rawBytes(), sourceFile.sourceUri(), selectedSource));
            } catch (RuntimeException exception) {
                Log.warn("[NodeDefinitionLoader] Failed to load " + sourceFile.sourceName() + ": " + exception.getMessage());
            }
        }
        return results;
    }

    public List<NodeDefinition> loadFromDirectory(Path dir, CatalogSource source) {
        List<NodeDefinition> results = new ArrayList<>();
        CatalogSource selectedSource = source != null ? source : CatalogSource.COMPATIBILITY;
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return results;
        }

        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> p.toString().endsWith(".json"))
                  .filter(p -> !p.getFileName().toString().startsWith("_"))
                  .forEach(p -> {
                      try (InputStream is = Files.newInputStream(p)) {
                          results.addAll(parse(is, p.toString(), selectedSource));
                      } catch (Exception e) {
                          Log.warn("[NodeDefinitionLoader] Failed to load " + p + ": " + e.getMessage());
                      }
                  });
        } catch (IOException e) {
            Log.warn("[NodeDefinitionLoader] Failed to scan directory " + dir + ": " + e.getMessage());
        }

        return results;
    }

    public List<NodeDefinition> parse(InputStream inputStream) {
        return parse(inputStream, "stream");
    }

    public List<NodeDefinition> parse(InputStream inputStream, String source) {
        return parse(inputStream, source, CatalogSource.COMPATIBILITY);
    }

    public List<NodeDefinition> parseReplacement(InputStream inputStream, String source) {
        return parse(inputStream, source, CatalogSource.REPLACEMENT);
    }

    public List<NodeDefinition> parse(InputStream inputStream, String source, CatalogSource sourceKind) {
        List<NodeDefinition> results = new ArrayList<>();
        byte[] sourceBytes;
        try {
            sourceBytes = inputStream.readAllBytes();
        } catch (IOException exception) {
            addDiagnostic(NodeDefinitionDiagnostic.Severity.ERROR, "FILE_PARSE_FAILED", source, -1, "", exception.getMessage());
            return results;
        }
        return parse(sourceBytes, source, sourceKind);
    }

    public List<NodeDefinition> parse(byte[] sourceBytes, String source, CatalogSource sourceKind) {
        List<NodeDefinition> results = new ArrayList<>();
        if (sourceBytes == null) {
            addDiagnostic(NodeDefinitionDiagnostic.Severity.ERROR, "FILE_PARSE_FAILED", source, -1, "", "Definition file bytes are required");
            return results;
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(new String(sourceBytes, StandardCharsets.UTF_8));
        } catch (RuntimeException exception) {
            addDiagnostic(NodeDefinitionDiagnostic.Severity.ERROR, "FILE_PARSE_FAILED", source, -1, "", exception.getMessage());
            return results;
        }
        ContentHash sourceHash = CatalogSourceIndex.sourceHash(sourceBytes);

        if (root.isJsonArray()) {
            for (int index = 0; index < root.getAsJsonArray().size(); index++) {
                parseElement(root.getAsJsonArray().get(index), source, index, sourceKind, sourceHash, results);
            }
        } else if (root.isJsonObject()) {
            parseElement(root, source, 0, sourceKind, sourceHash, results);
        } else {
            addDiagnostic(NodeDefinitionDiagnostic.Severity.ERROR, "INVALID_ROOT", source, -1, "", "Definition file root must be an object or array");
        }

        return results;
    }

    private void parseElement(JsonElement element, String source, int index, CatalogSource sourceKind, ContentHash sourceHash, List<NodeDefinition> results) {
        String nodeId = extractNodeId(element);
        if (sourceKind == CatalogSource.REPLACEMENT && !validateReplacementSource(element, source, index, nodeId)) {
            return;
        }
        try {
            NodeDefinition definition = parseSingle(element, sourceKind);
            if (sourceKind == CatalogSource.REPLACEMENT && definition.getAuthoredMetadata() != null) {
                AuthoredSourceProvenance provenance = new AuthoredSourceProvenance(source, index, definition.getOwner(), sourceHash.canonicalText());
                definition = definition.withAuthoredMetadata(definition.getAuthoredMetadata().withSourceProvenance(provenance));
            }
            results.add(definition);
            origins.put(definition, new DefinitionOrigin(source, index));
        } catch (RuntimeException exception) {
            addDiagnostic(NodeDefinitionDiagnostic.Severity.ERROR, "DEFINITION_PARSE_FAILED", source, index, nodeId, exception.getMessage());
        }
    }

    private boolean validateReplacementSource(JsonElement element, String source, int index, String nodeId) {
        if (element == null || !element.isJsonObject()) {
            addDiagnostic(NodeDefinitionDiagnostic.Severity.ERROR, "CATALOG.REPLACEMENT_SOURCE_INCOMPLETE", source, index, nodeId,
                "Replacement definitions must be authored JSON objects");
            return false;
        }
        JsonObject object = element.getAsJsonObject();
        boolean valid = true;
        valid &= requireAuthoredNodeId(object, source, index, nodeId);
        valid &= requireAuthoredText(object, "displayName", "display name", source, index, nodeId);
        valid &= requireAuthoredText(object, "domain", "domain", source, index, nodeId);
        valid &= requireAuthoredText(object, "family", "family", source, index, nodeId);
        valid &= requireAuthoredLifecycle(object, source, index, nodeId);
        valid &= requireAuthoredReplacementIdentity(object, source, index, nodeId);
        valid &= requireAuthoredText(object, "description", "description", source, index, nodeId);
        valid &= requireAuthoredText(object, "handlerCapability", "handler capability", source, index, nodeId);
        valid &= requireAuthoredText(object, "selectorIntent", "selector intent", source, index, nodeId);
        valid &= requireAuthoredText(object, "inspectorIntent", "inspector intent", source, index, nodeId);
        Set<String> pinIds = new HashSet<>();
        valid &= validateReplacementPinDescriptions(object, "inputs", "input", source, index, nodeId, pinIds);
        valid &= validateReplacementPinDescriptions(object, "outputs", "output", source, index, nodeId, pinIds);
        valid &= validateReplacementPinMigration(object, source, index, nodeId);
        if (valid) {
            try {
                authoredMetadata(object);
            } catch (RuntimeException exception) {
                addDiagnostic(NodeDefinitionDiagnostic.Severity.ERROR, "CATALOG.REPLACEMENT_SOURCE_UNREPRESENTABLE", source, index, nodeId,
                    exception.getMessage());
                valid = false;
            }
        }
        return valid;
    }

    private AuthoredNodeMetadata authoredMetadata(JsonObject object) {
        return new AuthoredNodeMetadata(
            object.get("id").getAsString(),
            object.get("domain").getAsString(),
            object.get("family").getAsString(),
            object.get("lifecycle").getAsString(),
            object.get("description").getAsString(),
            object.get("handlerCapability").getAsString(),
            object.get("selectorIntent").getAsString(),
            object.get("inspectorIntent").getAsString());
    }

    private boolean requireAuthoredText(JsonObject object, String field, String label, String source, int index, String nodeId) {
        JsonElement value = object.get(field);
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() && !value.getAsString().isBlank()) {
            return true;
        }
        addDiagnostic(NodeDefinitionDiagnostic.Severity.ERROR, "CATALOG.REPLACEMENT_SOURCE_INCOMPLETE", source, index, nodeId,
            "Missing explicit authored " + label + " in '" + field + "'");
        return false;
    }

    private boolean requireAuthoredNodeId(JsonObject object, String source, int index, String nodeId) {
        JsonElement value = object.get("id");
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank()) {
            addDiagnostic(NodeDefinitionDiagnostic.Severity.ERROR, "CATALOG.REPLACEMENT_SOURCE_INCOMPLETE", source, index, nodeId,
                "Missing explicit authored stable identity in 'id'");
            return false;
        }
        try {
            String canonical = NodeId.of(value.getAsString()).value();
            if (canonical.equals(value.getAsString())) {
                return true;
            }
        } catch (RuntimeException ignored) {
        }
        addDiagnostic(NodeDefinitionDiagnostic.Severity.ERROR, "CATALOG.REPLACEMENT_SOURCE_UNREPRESENTABLE", source, index, nodeId,
            "Replacement definitions require a canonical explicit node ID in 'id'");
        return false;
    }

    private boolean requireAuthoredLifecycle(JsonObject object, String source, int index, String nodeId) {
        JsonElement value = object.get("lifecycle");
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String lifecycle = value.getAsString().strip().toLowerCase(Locale.ROOT);
            if (REPLACEMENT_LIFECYCLE_VALUES.contains(lifecycle)) {
                return true;
            }
            addDiagnostic(NodeDefinitionDiagnostic.Severity.ERROR, "CATALOG.REPLACEMENT_SOURCE_INCOMPLETE", source, index, nodeId,
                "Unknown authored lifecycle '" + value.getAsString() + "'");
            return false;
        }
        addDiagnostic(NodeDefinitionDiagnostic.Severity.ERROR, "CATALOG.REPLACEMENT_SOURCE_INCOMPLETE", source, index, nodeId,
            "Missing explicit authored lifecycle in 'lifecycle'");
        return false;
    }

    private boolean requireAuthoredReplacementIdentity(JsonObject object, String source, int index, String nodeId) {
        JsonElement lifecycleValue = object.get("lifecycle");
        if (lifecycleValue == null || !lifecycleValue.isJsonPrimitive() || !lifecycleValue.getAsJsonPrimitive().isString()
            || !"retiring".equals(lifecycleValue.getAsString().strip().toLowerCase(Locale.ROOT))) {
            return true;
        }
        JsonElement replacementValue = object.get("replacementFor");
        if (replacementValue == null || !replacementValue.isJsonPrimitive()
            || !replacementValue.getAsJsonPrimitive().isString() || replacementValue.getAsString().isBlank()) {
            addDiagnostic(NodeDefinitionDiagnostic.Severity.ERROR, "CATALOG.REPLACEMENT_SOURCE_INCOMPLETE", source, index, nodeId,
                "Retiring replacement definitions require explicit authored replacement identity in 'replacementFor'");
            return false;
        }
        try {
            NodeId.of(replacementValue.getAsString().strip());
            return true;
        } catch (RuntimeException exception) {
            addDiagnostic(NodeDefinitionDiagnostic.Severity.ERROR, "CATALOG.REPLACEMENT_SOURCE_UNREPRESENTABLE", source, index, nodeId,
                "Authored replacement identity cannot be represented by the Core node identity contract");
            return false;
        }
    }

    private boolean validateReplacementPinDescriptions(JsonObject object, String collection, String authoredDirection,
                                                       String source, int index, String nodeId, Set<String> pinIds) {
        JsonElement value = object.get(collection);
        if (value == null || value.isJsonNull()) {
            return true;
        }
        if (!value.isJsonArray()) {
            addDiagnostic(NodeDefinitionDiagnostic.Severity.ERROR, "CATALOG.REPLACEMENT_SOURCE_INCOMPLETE", source, index, nodeId,
                "Replacement '" + collection + "' must be an array so authored pin descriptions can be validated");
            return false;
        }
        boolean valid = true;
        JsonArray pins = value.getAsJsonArray();
        for (int pinIndex = 0; pinIndex < pins.size(); pinIndex++) {
            JsonElement pin = pins.get(pinIndex);
            if (pin == null || !pin.isJsonObject()) {
                addDiagnostic(NodeDefinitionDiagnostic.Severity.ERROR, "CATALOG.REPLACEMENT_PIN_DESCRIPTION_MISSING", source, index, nodeId,
                    "Missing authored description for " + collection + " pin at index " + pinIndex);
                valid = false;
                continue;
            }
            JsonObject pinObject = pin.getAsJsonObject();
            valid &= validateReplacementPinId(pinObject, collection, pinIndex, source, index, nodeId, pinIds);
            valid &= validateReplacementPinDirection(pinObject, collection, authoredDirection, pinIndex, source, index, nodeId);
            JsonElement displayName = pinObject.get("displayName");
            if (displayName == null || !displayName.isJsonPrimitive() || !displayName.getAsJsonPrimitive().isString() || displayName.getAsString().isBlank()) {
                addDiagnostic(NodeDefinitionDiagnostic.Severity.ERROR, "CATALOG.REPLACEMENT_PIN_DISPLAY_NAME_MISSING", source, index, nodeId,
                    "Missing explicit authored display name for " + collection + " pin at index " + pinIndex);
                valid = false;
            }
            JsonElement description = pinObject.get("description");
            if (description == null || !description.isJsonPrimitive() || !description.getAsJsonPrimitive().isString() || description.getAsString().isBlank()) {
                addDiagnostic(NodeDefinitionDiagnostic.Severity.ERROR, "CATALOG.REPLACEMENT_PIN_DESCRIPTION_MISSING", source, index, nodeId,
                    "Missing explicit authored description for " + collection + " pin at index " + pinIndex);
                valid = false;
            }
        }
        return valid;
    }

    private boolean validateReplacementPinId(JsonObject pin, String direction, int pinIndex, String source, int index, String nodeId,
                                             Set<String> pinIds) {
        JsonElement id = pin.get("id");
        if (id == null || !id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString() || id.getAsString().isBlank()) {
            addDiagnostic(NodeDefinitionDiagnostic.Severity.ERROR, "CATALOG.REPLACEMENT_PIN_ID_MISSING", source, index, nodeId,
                "Missing explicit authored ID for " + direction + " pin at index " + pinIndex);
            return false;
        }
        try {
            String canonical = PinId.of(id.getAsString()).value();
            if (canonical.equals(id.getAsString())) {
                if (!pinIds.add(canonical)) {
                    addDiagnostic(NodeDefinitionDiagnostic.Severity.ERROR, "CATALOG.REPLACEMENT_PIN_ID_DUPLICATE", source, index, nodeId,
                        "Duplicate authored pin ID '" + canonical + "' in replacement " + direction + " definitions");
                    return false;
                }
                return true;
            }
        } catch (RuntimeException ignored) {
        }
        addDiagnostic(NodeDefinitionDiagnostic.Severity.ERROR, "CATALOG.REPLACEMENT_PIN_ID_INVALID", source, index, nodeId,
            "Replacement " + direction + " pin at index " + pinIndex + " must use a canonical ID");
        return false;
    }

    private boolean validateReplacementPinDirection(JsonObject pin, String collection, String expectedDirection, int pinIndex,
                                                    String source, int index, String nodeId) {
        JsonElement value = pin.get("direction");
        if (value == null || value.isJsonNull()) {
            return true;
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
            && expectedDirection.equalsIgnoreCase(value.getAsString().strip())) {
            return true;
        }
        addDiagnostic(NodeDefinitionDiagnostic.Severity.ERROR, "CATALOG.REPLACEMENT_PIN_DIRECTION_MISMATCH", source, index, nodeId,
            "Replacement " + collection + " pin at index " + pinIndex + " declares a different direction");
        return false;
    }

    private boolean validateReplacementPinMigration(JsonObject object, String source, int index, String nodeId) {
        Set<PinKey> currentPins = new HashSet<>();
        collectCurrentAuthoredPins(object, "inputs", NodeDefinition.PinDirection.INPUT, currentPins);
        collectCurrentAuthoredPins(object, "outputs", NodeDefinition.PinDirection.OUTPUT, currentPins);

        if (!object.has("migrationMapping")) {
            return true;
        }

        JsonElement value = object.get("migrationMapping");
        if (value == null || !value.isJsonObject()) {
            addDiagnostic(NodeDefinitionDiagnostic.Severity.ERROR, "CATALOG.REPLACEMENT_PIN_MIGRATION_INCOMPLETE", source, index, nodeId,
                "Changed authored pin IDs require a complete 'migrationMapping'");
            return false;
        }

        NodeDefinition.MigrationMapping migration;
        try {
            migration = toMigrationMapping(gson.fromJson(value, MigrationMappingJson.class));
        } catch (RuntimeException exception) {
            addDiagnostic(NodeDefinitionDiagnostic.Severity.ERROR, "CATALOG.REPLACEMENT_PIN_MIGRATION_INVALID", source, index, nodeId,
                exception.getMessage());
            return false;
        }

        boolean valid = true;
        if (!migration.complete()) {
            addDiagnostic(NodeDefinitionDiagnostic.Severity.ERROR, "CATALOG.REPLACEMENT_PIN_MIGRATION_INCOMPLETE", source, index, nodeId,
                "Changed authored pin IDs require 'migrationMapping.complete' to be true");
            valid = false;
        }
        int targetSchemaVersion = integerValue(object.get("schemaVersion"), 1);
        if (migration.targetSchemaVersion() != targetSchemaVersion) {
            addDiagnostic(NodeDefinitionDiagnostic.Severity.ERROR, "CATALOG.REPLACEMENT_PIN_MIGRATION_INVALID", source, index, nodeId,
                "Pin migration target schema version must match the node schema version");
            valid = false;
        }

        for (NodeDefinition.PinMigrationMapping mapping : migration.pins()) {
            PinKey targetKey = new PinKey(mapping.direction(), mapping.targetPinId());
            if (!currentPins.contains(targetKey)) {
                addDiagnostic(NodeDefinitionDiagnostic.Severity.ERROR, "CATALOG.REPLACEMENT_PIN_MIGRATION_TARGET_UNKNOWN", source, index, nodeId,
                    "Pin migration target is not an active pin: " + mapping.targetPinId().value());
                valid = false;
            }
        }
        return valid;
    }

    private void collectCurrentAuthoredPins(JsonObject object, String field, NodeDefinition.PinDirection direction,
                                            Set<PinKey> currentPins) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonArray()) {
            return;
        }
        for (JsonElement element : value.getAsJsonArray()) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject pin = element.getAsJsonObject();
            String idValue = stringValue(pin.get("id"));
            if (idValue == null || idValue.isBlank()) {
                continue;
            }
            try {
                PinId id = PinId.of(idValue);
                currentPins.add(new PinKey(direction, id));
            } catch (RuntimeException ignored) {
            }
        }
    }

    private int integerValue(JsonElement value, int fallback) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() ? value.getAsInt() : fallback;
    }

    private String extractNodeId(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return "";
        }
        JsonObject object = element.getAsJsonObject();
        if (!object.has("id") || !object.get("id").isJsonPrimitive()) {
            return "";
        }
        return object.get("id").getAsString();
    }

    private JsonElement applyCompatibilityTransforms(JsonElement element) {
        if (!element.isJsonObject()) {
            return element;
        }
        JsonObject obj = element.getAsJsonObject();
        Set<String> pinIds = new HashSet<>();

        if (obj.has("inputs") && obj.get("inputs").isJsonArray()) {
            JsonArray arr = obj.getAsJsonArray("inputs");
            for (int i = 0; i < arr.size(); i++) {
                arr.set(i, normalizeLegacyPin(arr.get(i), "input", pinIds));
            }
        }
        if (obj.has("outputs") && obj.get("outputs").isJsonArray()) {
            JsonArray arr = obj.getAsJsonArray("outputs");
            for (int i = 0; i < arr.size(); i++) {
                arr.set(i, normalizeLegacyPin(arr.get(i), "output", pinIds));
            }
        }

        if (obj.has("handlerConfig") && obj.get("handlerConfig").isJsonObject()) {
            JsonObject hc = obj.getAsJsonObject("handlerConfig");
            if (hc.has("property") && !obj.has("property") && obj.has("id")) {
                String id = obj.get("id").getAsString();
                if (id.contains(".")) {
                    String inferredProperty = id.substring(id.lastIndexOf('.') + 1);
                    hc.addProperty("property", inferredProperty);
                }
            }
        }

        return obj;
    }

    private JsonElement normalizeLegacyPin(JsonElement pinEl, String direction, Set<String> pinIds) {
        if (!pinEl.isJsonObject()) {
            return pinEl;
        }
        JsonObject pin = pinEl.getAsJsonObject();

        String legacyName = stringValue(pin.get("name"));
        String explicitId = stringValue(pin.get("id"));
        String explicitDisplayName = stringValue(pin.get("displayName"));
        if (explicitId == null || explicitId.isBlank()) {
            if (legacyName != null && !legacyName.isBlank()) {
                explicitId = compatibilityPinId(legacyName, direction, pinIds);
                pin.addProperty("id", explicitId);
            }
        } else {
            pinIds.add(explicitId);
        }
        if (explicitDisplayName == null || explicitDisplayName.isBlank()) {
            String displayName = legacyName != null && !legacyName.isBlank() ? legacyName : explicitId;
            if (displayName != null && !displayName.isBlank()) {
                pin.addProperty("displayName", displayName);
            }
        }

        if (pin.has("type") && pin.get("type").isJsonPrimitive()) {
            String typeVal = pin.get("type").getAsString().toUpperCase();
            if ("INPUT".equals(typeVal) || "OUTPUT".equals(typeVal)) {
                pin.remove("type");
                if (!pin.has("pinType")) {
                    pin.addProperty("pinType", "DATA");
                }
            }
        }

        if (!pin.has("pinType") && pin.has("type")) {
            String typeVal = pin.get("type").getAsString().toUpperCase();
            if ("FLOW".equals(typeVal) || "EXEC".equals(typeVal) || "EXECUTION".equals(typeVal)) {
                pin.addProperty("pinType", "FLOW");
                pin.remove("type");
            } else if ("DATA".equals(typeVal)) {
                pin.addProperty("pinType", "DATA");
                pin.remove("type");
            }
        }

        return pin;
    }

    private String compatibilityPinId(String legacyName, String direction, Set<String> pinIds) {
        String base = canonicalLegacyPinId(legacyName);
        if (pinIds.add(base)) {
            return base;
        }
        String directional = direction + "_" + base;
        if (pinIds.add(directional)) {
            return directional;
        }
        int suffix = 2;
        while (!pinIds.add(directional + "_" + suffix)) {
            suffix++;
        }
        return directional + "_" + suffix;
    }

    private String canonicalLegacyPinId(String legacyName) {
        String candidate = legacyName.strip();
        try {
            return PinId.of(candidate).value();
        } catch (RuntimeException ignored) {
            String normalized = candidate.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
            if (normalized.isBlank() || !Character.isLetter(normalized.charAt(0))) {
                normalized = "pin_" + normalized;
            }
            return PinId.of(normalized).value();
        }
    }

    private String stringValue(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }

    private NodeDefinition parseSingle(JsonElement element, CatalogSource sourceKind) {
        if (sourceKind != CatalogSource.REPLACEMENT) {
            element = applyCompatibilityTransforms(element);
        }
        NodeJson dto = gson.fromJson(element, NodeJson.class);
        if (dto == null || dto.id == null || dto.id.isBlank()) {
            throw new JsonParseException("Node ID is required");
        }

        NodeDefinition.NodeCategory category = dto.category != null ? dto.category : NodeDefinition.NodeCategory.UTILITY;
        String displayName = dto.displayName != null ? dto.displayName : dto.id;
        NodeDefinition.Builder builder = new NodeDefinition.Builder(dto.id, displayName, category);
        builder.owner(dto.owner);

        if (dto.schemaVersion != null) {
            builder.schemaVersion(dto.schemaVersion);
        }
        if (dto.kind != null) {
            builder.kind(dto.kind);
        }
        NodeDefinition.Availability availability = dto.availability != null
            ? new NodeDefinition.Availability(dto.availability.plugin, dto.availability.platform, dto.availability.minVersion)
            : inferAvailability(dto.handler);
        builder.availability(availability != null ? availability : new NodeDefinition.Availability(null, null, null));
        if (dto.canonicalId != null && !dto.canonicalId.isBlank()) {
            builder.canonicalId(dto.canonicalId);
        }
        if (dto.legacyIds != null && !dto.legacyIds.isEmpty()) {
            builder.legacyIds(dto.legacyIds);
        }
        if (dto.deprecated != null) {
            builder.deprecated(dto.deprecated);
        }

        if (dto.color != null) {
            builder.color(dto.color);
        }
        if (dto.priority != null) {
            builder.priority(dto.priority);
        }
        if (dto.hidden != null && dto.hidden) {
            builder.hidden();
            builder.hiddenReason(dto.hiddenReason != null && !dto.hiddenReason.isBlank() ? dto.hiddenReason : defaultHiddenReason(dto));
        }
        if (dto.description != null && !dto.description.isBlank()) {
            builder.description(dto.description);
        } else if (sourceKind != CatalogSource.REPLACEMENT) {
            builder.description(defaultDescription(dto, displayName));
        }
        if (sourceKind == CatalogSource.REPLACEMENT) {
            builder.authoredMetadata(new AuthoredNodeMetadata(dto.id, dto.domain, dto.family, dto.lifecycle, dto.description,
                dto.handlerCapability, dto.selectorIntent, dto.inspectorIntent));
        }
        if (dto.handler != null && !dto.handler.isBlank()) {
            builder.handler(dto.handler);
        }
        if (dto.handlerConfig != null && !dto.handlerConfig.isEmpty()) {
            builder.handlerConfig(dto.handlerConfig);
        }
        if (dto.trigger != null && dto.trigger) {
            builder.trigger(true);
        }
        if (dto.eventType != null && !dto.eventType.isBlank()) {
            builder.eventType(dto.eventType);
        }
        if (dto.aliases != null && !dto.aliases.isEmpty()) {
            builder.aliases(dto.aliases);
        }
        if (dto.outputMappings != null && !dto.outputMappings.isEmpty()) {
            List<NodeDefinition.PinMapping> mappings = new ArrayList<>();
            for (PinMappingJson pm : dto.outputMappings) {
                if (pm.source != null && pm.target != null) {
                    mappings.add(new NodeDefinition.PinMapping(pm.source, pm.target));
                }
            }
            builder.outputMappings(mappings);
        }
        if (dto.migrationMapping != null) {
            builder.migrationMapping(toMigrationMapping(dto.migrationMapping));
        }
        builder.tags(dto.tags != null && !dto.tags.isEmpty() ? dto.tags : defaultTags(dto, category));
        builder.examples(dto.examples != null && !dto.examples.isEmpty() ? dto.examples : defaultExamples(dto, displayName));
        if (dto.family != null && !dto.family.isBlank()) {
            builder.family(dto.family);
        }
        if (dto.recommended != null) {
            builder.recommended(dto.recommended);
        }
        if (dto.replacementFor != null && !dto.replacementFor.isBlank()) {
            String replacementFor = sourceKind == CatalogSource.REPLACEMENT
                ? NodeId.of(dto.replacementFor.strip()).value() : dto.replacementFor;
            builder.replacementFor(replacementFor);
        }
        if (dto.authorizationPolicy != null && !dto.authorizationPolicy.isBlank()) {
            builder.authorizationPolicy(dto.authorizationPolicy);
        }
        boolean sensitive = dto.sensitive != null ? dto.sensitive : isSensitive(dto);
        builder.sensitive(sensitive);
        boolean destructive = dto.destructive != null ? dto.destructive : isDestructive(dto);
        builder.destructive(destructive);
        if (dto.auditPolicy != null && !dto.auditPolicy.isBlank()) {
            builder.auditPolicy(dto.auditPolicy);
        } else if (destructive) {
            builder.auditPolicy("high_impact");
        } else if (sensitive) {
            builder.auditPolicy("sensitive");
        }
        if (dto.confirmationPolicy != null && !dto.confirmationPolicy.isBlank()) {
            builder.confirmationPolicy(dto.confirmationPolicy);
        } else if (destructive) {
            builder.confirmationPolicy("explicit_flow_intent");
        }
        if (dto.clockDomain != null && !dto.clockDomain.isBlank()) {
            builder.clockDomain(dto.clockDomain);
        }

        if (dto.inputs != null) {
            for (PinJson pin : dto.inputs) {
                NodeDefinition.PinDefinition def = toPinDefinition(pin, NodeDefinition.PinDirection.INPUT);
                if (def == null) {
                    throw new JsonParseException("Invalid input pin in node " + dto.id);
                }
                builder.input(def);
            }
        }

        if (dto.outputs != null) {
            for (PinJson pin : dto.outputs) {
                NodeDefinition.PinDefinition def = toPinDefinition(pin, NodeDefinition.PinDirection.OUTPUT);
                if (def == null) {
                    throw new JsonParseException("Invalid output pin in node " + dto.id);
                }
                builder.output(def);
            }
        }

        return builder.build();
    }

    private NodeDefinition.MigrationMapping toMigrationMapping(MigrationMappingJson migration) {
        if (migration == null || migration.sourceSchemaVersion == null || migration.targetSchemaVersion == null
            || migration.complete == null || migration.pins == null) {
            throw new JsonParseException("Pin migration mapping requires sourceSchemaVersion, targetSchemaVersion, complete, and pins");
        }
        List<NodeDefinition.PinMigrationMapping> pins = new ArrayList<>();
        for (MigrationPinJson pin : migration.pins) {
            if (pin == null) {
                throw new JsonParseException("Pin migration mapping cannot contain null pins");
            }
            String source = pin.source != null ? pin.source : pin.sourcePinId;
            String target = pin.target != null ? pin.target : pin.targetPinId;
            if (source == null || target == null || source.isBlank() || target.isBlank()) {
                throw new JsonParseException("Pin migration mapping requires source and target pin IDs");
            }
            NodeDefinition.LegacyPinId sourcePinId = NodeDefinition.LegacyPinId.of(source);
            PinId targetPinId = PinId.of(target);
            if (!targetPinId.value().equals(target)) {
                throw new JsonParseException("Pin migration mapping IDs must be canonical");
            }
            pins.add(new NodeDefinition.PinMigrationMapping(sourcePinId, targetPinId, pin.direction,
                migration.sourceSchemaVersion, migration.targetSchemaVersion));
        }
        return new NodeDefinition.MigrationMapping(migration.sourceSchemaVersion, migration.targetSchemaVersion,
            migration.complete, pins);
    }

    private String defaultHiddenReason(NodeJson dto) {
        if (Boolean.TRUE.equals(dto.deprecated)) {
            return "deprecated";
        }
        if (dto.kind == NodeDefinition.NodeKind.ALIAS || dto.canonicalId != null && !dto.canonicalId.isBlank()) {
            return "migration_only";
        }
        return "internal";
    }

    private String defaultDescription(NodeJson dto, String displayName) {
        String phrase = displayName == null || displayName.isBlank() ? dto.id : displayName;
        String normalized = phrase.strip();
        if (Boolean.TRUE.equals(dto.trigger)) {
            String event = normalized.toLowerCase(Locale.ROOT);
            return "Runs when the " + event + (event.endsWith(" event") ? "" : " event") + " occurs.";
        }
        List<String> words = List.of(normalized.split("\\s+"));
        int verbIndex = -1;
        String action = null;
        for (int index = 0; index < words.size(); index++) {
            action = descriptionAction(words.get(index));
            if (action != null) {
                verbIndex = index;
                String candidate = words.get(index).toLowerCase(Locale.ROOT);
                if (index != 0 || !"list".equals(candidate) && !"set".equals(candidate) && !"map".equals(candidate)) {
                    break;
                }
            }
        }
        if (verbIndex < 0) {
            boolean actionNode = dto.kind == NodeDefinition.NodeKind.ACTION || dto.kind == NodeDefinition.NodeKind.FAMILY || dto.inputs != null && dto.inputs.stream()
                .anyMatch(pin -> pin != null && pin.pinType == NodeDefinition.PinType.FLOW);
            return (actionNode ? "Runs " : "Returns ") + normalized.toLowerCase(Locale.ROOT) + '.';
        }
        String verb = words.get(verbIndex).toLowerCase(Locale.ROOT);
        List<String> subjectWords = new ArrayList<>();
        subjectWords.addAll(words.subList(0, verbIndex));
        subjectWords.addAll(words.subList(verbIndex + 1, words.size()));
        String subject = String.join(" ", subjectWords).toLowerCase(Locale.ROOT);
        if ("is".equals(verb)) {
            String predicate = String.join(" ", words.subList(0, verbIndex)).toLowerCase(Locale.ROOT);
            String condition = String.join(" ", words.subList(verbIndex + 1, words.size())).toLowerCase(Locale.ROOT);
            return "Checks whether " + (predicate.isBlank() ? "the value" : predicate) + " is " + condition + '.';
        }
        if ("has".equals(verb)) {
            String predicate = String.join(" ", words.subList(0, verbIndex)).toLowerCase(Locale.ROOT);
            String condition = String.join(" ", words.subList(verbIndex + 1, words.size())).toLowerCase(Locale.ROOT);
            return "Checks whether " + (predicate.isBlank() ? "the target" : predicate) + " has " + condition + '.';
        }
        if ("contains".equals(verb)) {
            String source = String.join(" ", words.subList(0, verbIndex)).toLowerCase(Locale.ROOT);
            return "Checks whether the " + (source.isBlank() ? "source" : source) + " contains the requested value.";
        }
        if ("equals".equals(verb)) {
            return "Checks whether the supplied values are equal.";
        }
        if ("matches".equals(verb)) {
            return "Checks whether the supplied value matches the requested pattern.";
        }
        if ("starts".equals(verb) || "ends".equals(verb)) {
            String source = String.join(" ", words.subList(0, verbIndex)).toLowerCase(Locale.ROOT);
            return "Checks whether the " + (source.isBlank() ? "value" : source) + ' ' + verb + " with the requested text.";
        }
        String detail = subject.isBlank() ? normalized.toLowerCase(Locale.ROOT) : subject;
        return action + ' ' + detail + '.';
    }

    private String descriptionAction(String verb) {
        return switch (verb.toLowerCase(Locale.ROOT)) {
            case "get" -> "Gets";
            case "set" -> "Sets";
            case "add" -> "Adds";
            case "remove" -> "Removes";
            case "delete" -> "Deletes";
            case "create" -> "Creates";
            case "open" -> "Opens";
            case "close" -> "Closes";
            case "send" -> "Sends";
            case "play" -> "Plays";
            case "stop" -> "Stops";
            case "apply" -> "Applies";
            case "check" -> "Checks";
            case "find" -> "Finds";
            case "list" -> "Lists";
            case "generate" -> "Generates";
            case "spawn" -> "Spawns";
            case "despawn" -> "Despawns";
            case "teleport" -> "Teleports";
            case "give" -> "Gives";
            case "take" -> "Takes";
            case "save" -> "Saves";
            case "load" -> "Loads";
            case "reload" -> "Reloads";
            case "start" -> "Starts";
            case "cancel" -> "Cancels";
            case "update" -> "Updates";
            case "clear" -> "Clears";
            case "format" -> "Formats";
            case "parse" -> "Parses";
            case "convert" -> "Converts";
            case "calculate" -> "Calculates";
            case "execute" -> "Executes";
            case "run" -> "Runs";
            case "filter" -> "Filters";
            case "map" -> "Maps";
            case "flatten" -> "Flattens";
            case "group" -> "Groups";
            case "sort" -> "Sorts";
            case "merge" -> "Merges";
            case "join" -> "Joins";
            case "split" -> "Splits";
            case "select" -> "Selects";
            case "count" -> "Counts";
            case "contains", "equals", "matches", "starts", "ends" -> "Checks whether";
            case "broadcast" -> "Broadcasts";
            case "show" -> "Shows";
            case "hide" -> "Hides";
            case "enable" -> "Enables";
            case "disable" -> "Disables";
            case "is", "has" -> "Checks whether";
            default -> null;
        };
    }

    private List<String> defaultTags(NodeJson dto, NodeDefinition.NodeCategory category) {
        Set<String> tags = new HashSet<>();
        for (String token : dto.id.toLowerCase(Locale.ROOT).split("[.:_\\-]+")) {
            if (!token.isBlank()) {
                tags.add(token);
            }
        }
        if (category != null && category.getId() != null) {
            tags.add(category.getId().toLowerCase(Locale.ROOT));
        }
        if (dto.kind != null) {
            tags.add(dto.kind.name().toLowerCase(Locale.ROOT));
        }
        if (Boolean.TRUE.equals(dto.trigger)) {
            tags.add("event");
        }
        return tags.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private List<String> defaultExamples(NodeJson dto, String displayName) {
        String name = displayName != null && !displayName.isBlank() ? displayName : dto.id;
        if (Boolean.TRUE.equals(dto.trigger)) {
            return List.of("Connect the " + name + " Flow output to the actions that should run.");
        }
        List<String> dataInputs = dto.inputs != null ? dto.inputs.stream()
            .filter(pin -> pin != null && pin.pinType != NodeDefinition.PinType.FLOW && pinDisplayName(pin) != null)
            .map(pin -> pinDisplayName(pin).replace('_', ' '))
            .limit(3)
            .toList() : List.of();
        boolean action = dto.inputs != null && dto.inputs.stream().anyMatch(pin -> pin != null && pin.pinType == NodeDefinition.PinType.FLOW);
        boolean failure = dto.outputs != null && dto.outputs.stream()
            .anyMatch(pin -> pin != null && "failed".equalsIgnoreCase(pinDisplayName(pin)));
        String supplied = dataInputs.isEmpty() ? "its inputs" : String.join(", ", dataInputs);
        if (action) {
            return List.of("Connect Flow, provide " + supplied + ", then handle " + (failure ? "Flow or Failed." : "its continuation."));
        }
        List<String> dataOutputs = dto.outputs != null ? dto.outputs.stream()
            .filter(pin -> pin != null && pin.pinType != NodeDefinition.PinType.FLOW && pinDisplayName(pin) != null)
            .map(pin -> pinDisplayName(pin).replace('_', ' '))
            .limit(2)
            .toList() : List.of();
        String produced = dataOutputs.isEmpty() ? "its result" : String.join(" and ", dataOutputs);
        return List.of("Provide " + supplied + " and connect " + produced + " to a compatible input.");
    }

    private String pinDisplayName(PinJson pin) {
        if (pin == null) {
            return null;
        }
        String value = pin.displayName != null && !pin.displayName.isBlank() ? pin.displayName : pin.name;
        return value != null && !value.isBlank() ? value : pin.id;
    }

    private boolean isDestructive(NodeJson dto) {
        boolean action = dto.kind == NodeDefinition.NodeKind.ACTION || dto.inputs != null && dto.inputs.stream()
            .anyMatch(pin -> pin != null && pin.pinType == NodeDefinition.PinType.FLOW);
        if (!action) {
            return false;
        }
        String operation = dto.handlerConfig != null && dto.handlerConfig.get("operation") != null
            ? String.valueOf(dto.handlerConfig.get("operation")) : "";
        String identity = (dto.id + " " + operation).trim().toLowerCase(Locale.ROOT);
        return List.of("delete", "remove", "clear", "reset", "shutdown", "restart", "reload", "unload", "revoke", "grant", "command", "ban", "kick", "despawn", "dissolve")
            .stream().anyMatch(token -> identity.matches(".*(?:^|[\\s._:\\-])" + token + "(?:$|[\\s._:\\-]).*"));
    }

    private boolean isSensitive(NodeJson dto) {
        String handler = dto.handler != null ? dto.handler : "";
        if (Set.of("FileHandler", "HttpHandler", "DiscordHandler", "PermissionHandler", "NetworkFlowHandler", "ServerHandler", "EconomyHandler")
            .contains(handler)) {
            return true;
        }
        String operation = dto.handlerConfig != null && dto.handlerConfig.get("operation") != null
            ? String.valueOf(dto.handlerConfig.get("operation")) : "";
        String identity = (dto.id + " " + operation).toLowerCase(Locale.ROOT);
        return List.of("resourcepack", "webhook", "proxy_command", "player_route", "transfer")
            .stream().anyMatch(identity::contains);
    }

    private NodeDefinition.PinDefinition toPinDefinition(PinJson pin, NodeDefinition.PinDirection direction) {
        NodeDefinition.PinType pinType = pin.pinType != null ? pin.pinType : NodeDefinition.PinType.DATA;
        String declaredType = semanticCatalogType(pin.optionsSource, pin.dataType);
        FlowTypeRef typeRef;
        try {
            typeRef = declaredType == null || declaredType.isBlank()
                ? FlowTypeRef.simple(pinType == NodeDefinition.PinType.FLOW ? "execution" : "any")
                : FlowTypeRef.parse(declaredType);
        } catch (IllegalArgumentException exception) {
            Log.warn("[NodeDefinitionLoader] Invalid dataType expression: " + declaredType);
            return null;
        }
        FlowDataType dataType = typeRef.isTypeVariable() ? FlowDataType.ANY : parseAndValidateDataType(typeRef.getTypeId(), pinType);
        if (dataType == null) {
            return null;
        }

        NodeDefinition.WidgetType widget = pin.widget != null ? pin.widget : NodeDefinition.WidgetType.AUTO;
        List<String> options = pin.options != null ? pin.options : Collections.emptyList();
        String optionsSource = pin.optionsSource;

        if ((optionsSource == null || optionsSource.isBlank()) && widget == NodeDefinition.WidgetType.AUTO) {
            String inferred = inferOptionsSource(dataType);
            if (inferred != null) {
                optionsSource = inferred;
            }
        }

        NodeDefinition.PinConstraints constraints = null;
        if (pin.constraints != null) {
            constraints = new NodeDefinition.PinConstraints(pin.constraints.min, pin.constraints.max, pin.constraints.step);
        }

        Map<String, String> visibleWhen = pin.visibleWhen != null ? pin.visibleWhen : Collections.emptyMap();
        NodeDefinition.RepeatablePin repeatable = pin.repeatable != null
            ? new NodeDefinition.RepeatablePin(pin.repeatable.groupId, pin.repeatable.minItems, pin.repeatable.maxItems, pin.repeatable.itemLabel)
            : null;

        PinId pinId;
        try {
            pinId = PinId.of(pin.id);
        } catch (RuntimeException exception) {
            Log.warn("[NodeDefinitionLoader] Invalid pin ID: " + pin.id);
            return null;
        }
        String displayName = pin.displayName != null && !pin.displayName.isBlank() ? pin.displayName : pinId.value();
        String runtimeName = pin.name != null ? pin.name : pinId.value();

        return new NodeDefinition.PinDefinition(
            pinId,
            displayName,
            runtimeName,
            pinType,
            direction,
            dataType,
            widget,
            options,
            optionsSource,
            pin.defaultValue,
            constraints,
            visibleWhen,
            pin.description,
            Boolean.TRUE.equals(pin.optional),
            typeRef,
            repeatable
        );
    }

    private String semanticCatalogType(String optionsSource, String fallback) {
        if (optionsSource == null || !optionsSource.startsWith("server:resync:")) {
            return fallback;
        }
        return switch (optionsSource.substring("server:resync:".length())) {
            case "flow" -> "flow_id";
            case "function" -> "function";
            case "command" -> "command_id";
            case "custom_content" -> "custom_content_id";
            case "gui" -> "gui_id";
            case "scoreboard" -> "scoreboard_id";
            case "tab" -> "tab_id";
            case "chat" -> "chat_id";
            case "component_builder" -> "component_builder_id";
            case "motd_profile" -> "motd_profile_id";
            case "message_rule" -> "message_rule_id";
            case "recipe_definition" -> "recipe_id";
            case "text_template" -> "text_template_id";
            case "advancement_tree" -> "advancement_tree_id";
            case "dialog" -> "dialog_id";
            case "trade_profile" -> "trade_profile_id";
            case "npc_definition" -> "npc_id";
            case "loot_table" -> "loot_table_id";
            case "worldgen" -> "worldgen_project";
            default -> fallback;
        };
    }

    private FlowDataType parseAndValidateDataType(String raw, NodeDefinition.PinType pinType) {
        if (raw == null || raw.isBlank()) {
            if (pinType == NodeDefinition.PinType.FLOW) {
                return FlowDataType.EXECUTION;
            }
            return FlowDataType.ANY;
        }
        FlowDataType type = FlowDataType.fromString(raw);
        if (!type.isResolved()) {
            Log.warn("[NodeDefinitionLoader] Unknown dataType: " + raw);
        }
        return type;
    }

    private NodeDefinition.Availability inferAvailability(String handler) {
        if (handler == null || handler.isBlank()) {
            return null;
        }
        return switch (handler) {
            case "EconomyHandler" -> new NodeDefinition.Availability("Vault", "bukkit", null);
            case "PermissionHandler" -> new NodeDefinition.Availability("LuckPerms", "bukkit", null);
            case "PlaceholderHandler" -> new NodeDefinition.Availability("PlaceholderAPI", "bukkit", null);
            default -> null;
        };
    }

    private String inferOptionsSource(FlowDataType type) {
        if (type == null) return null;
        String id = type.getId();
        return switch (id) {
            case "material" -> "server:minecraft:material";
            case "gamemode" -> "server:minecraft:gamemode";
            case "difficulty" -> "server:minecraft:difficulty";
            case "potion_effect" -> "server:minecraft:potion_effect";
            case "sound" -> "server:minecraft:sound";
            case "advancement" -> "server:minecraft:advancement";
            case "biome" -> "server:minecraft:biome";
            case "entity_type" -> "server:minecraft:entity_type";
            case "enchantment" -> "server:minecraft:enchantment";
            default -> null;
        };
    }

    public void validateAndRegister(List<NodeDefinition> definitions, NodeDefinitionRegistry registry, HandlerRegistry handlerRegistry, String pluginId) {
        if (validator == null && handlerRegistry != null) {
            validator = new NodeDefinitionValidator(handlerRegistry);
        }
        Set<String> displayNames = new HashSet<>();
        Set<AdmissionIdentity> acceptedIdentities = new HashSet<>();
        List<NodeDefinition> accepted = new ArrayList<>();
        boolean identityConflict = false;
        for (NodeDefinition def : definitions) {
            AdmissionIdentity identity = new AdmissionIdentity(def.getOwner(), def.getId());
            if (registry.get(def.getOwner(), def.getId()) != null || acceptedIdentities.contains(identity)) {
                reject(def, "DUPLICATE_NODE_ID", "Duplicate node ID: " + def.getId());
                identityConflict = true;
                continue;
            }
            String displayKey = def.getCategory() + ":" + def.getDisplayName().toLowerCase();
            if (!def.isHidden() && !displayNames.add(displayKey)) {
                warn(def, "DUPLICATE_DISPLAY_NAME", "Duplicate visible display name in category " + def.getCategory() + ": " + def.getDisplayName());
            }
            if (validator != null) {
                NodeDefinitionValidator.ValidationResult result = validator.validate(def);
                if (result.hasErrors()) {
                    for (String error : result.errors()) {
                        reject(def, "VALIDATION_FAILED", error);
                    }
                    continue;
                }
                if (result.hasWarnings()) {
                    for (String warning : result.warnings()) {
                        warn(def, "VALIDATION_WARNING", warning);
                    }
                }
            }
            accepted.add(def);
            acceptedIdentities.add(identity);
        }
        if (!identityConflict && !accepted.isEmpty()) {
            registry.registerAll(pluginId, accepted);
        }
    }

    public void rejectUnavailable(NodeDefinition definition, String reason) {
        warn(definition, "UNAVAILABLE", reason);
    }

    private void reject(NodeDefinition definition, String code, String message) {
        DefinitionOrigin origin = origins.getOrDefault(definition, new DefinitionOrigin("unknown", -1));
        addDiagnostic(NodeDefinitionDiagnostic.Severity.ERROR, code, origin.source(), origin.index(), definition != null ? definition.getId() : "", message);
    }

    private void warn(NodeDefinition definition, String code, String message) {
        DefinitionOrigin origin = origins.getOrDefault(definition, new DefinitionOrigin("unknown", -1));
        addDiagnostic(NodeDefinitionDiagnostic.Severity.WARNING, code, origin.source(), origin.index(), definition != null ? definition.getId() : "", message);
    }

    private void addDiagnostic(NodeDefinitionDiagnostic.Severity severity, String code, String source, int index, String nodeId, String message) {
        NodeDefinitionDiagnostic diagnostic = new NodeDefinitionDiagnostic(severity, code, source, index, nodeId, message);
        diagnostics.add(diagnostic);
        String prefix = source + (index >= 0 ? "[" + index + "]" : "") + (!nodeId.isBlank() ? " " + nodeId : "");
        if (severity == NodeDefinitionDiagnostic.Severity.ERROR) {
            Log.warn("[NodeDefinitionLoader] " + prefix + ": " + message);
        } else {
            Log.fine("[NodeDefinitionLoader] " + prefix + ": " + message);
        }
    }

    private record DefinitionOrigin(String source, int index) {
    }

    private record AdmissionIdentity(String owner, String nodeId) {
    }

    private record PinKey(NodeDefinition.PinDirection direction, NodeDefinition.LegacyPinId pinId) {
        private PinKey(NodeDefinition.PinDirection direction, PinId pinId) {
            this(direction, NodeDefinition.LegacyPinId.of(pinId.value()));
        }
    }

    private static class NodeJson {
        String id;
        String displayName;
        NodeDefinition.NodeCategory category;
        String description;
        String domain;
        String lifecycle;
        String handlerCapability;
        String selectorIntent;
        String inspectorIntent;
        Integer color;
        Integer priority;
        Boolean hidden;
        String hiddenReason;
        String owner;
        Integer schemaVersion;
        NodeDefinition.NodeKind kind;
        AvailabilityJson availability;
        String canonicalId;
        List<String> legacyIds;
        Boolean deprecated;
        String handler;
        Map<String, Object> handlerConfig;
        Boolean trigger;
        String eventType;
        List<String> aliases;
        List<PinMappingJson> outputMappings;
        MigrationMappingJson migrationMapping;
        List<PinJson> inputs;
        List<PinJson> outputs;
        List<String> tags;
        List<String> examples;
        String family;
        Boolean recommended;
        String replacementFor;
        String authorizationPolicy;
        Boolean sensitive;
        Boolean destructive;
        String auditPolicy;
        String confirmationPolicy;
        String clockDomain;
    }

    private static class AvailabilityJson {
        String plugin;
        String platform;
        String minVersion;
    }

    private static class PinMappingJson {
        String source;
        String target;
    }

    private static class MigrationMappingJson {
        Integer sourceSchemaVersion;
        Integer targetSchemaVersion;
        Boolean complete;
        List<MigrationPinJson> pins;
    }

    private static class MigrationPinJson {
        String source;
        String target;
        String sourcePinId;
        String targetPinId;
        NodeDefinition.PinDirection direction;
    }

    private static class PinJson {
        String id;
        String displayName;
        String name;
        NodeDefinition.PinType pinType;
        String dataType;
        NodeDefinition.WidgetType widget;
        List<String> options;
        String optionsSource;
        String defaultValue;
        PinConstraintsJson constraints;
        Map<String, String> visibleWhen;
        String description;
        Boolean optional;
        RepeatablePinJson repeatable;
    }

    private static class RepeatablePinJson {
        String groupId;
        int minItems = 1;
        int maxItems = 32;
        String itemLabel;
    }

    private static class PinConstraintsJson {
        Double min;
        Double max;
        Double step;
    }

    private static class EnumAdapter<T extends Enum<T>> extends TypeAdapter<T> {
        private final Class<T> enumClass;

        EnumAdapter(Class<T> enumClass) {
            this.enumClass = enumClass;
        }

        @Override
        public void write(JsonWriter out, T value) throws IOException {
            out.value(value != null ? value.name() : null);
        }

        @Override
        public T read(JsonReader in) throws IOException {
            String name = in.nextString();
            if (name == null || name.isBlank()) return null;
            try {
                return Enum.valueOf(enumClass, name.toUpperCase());
            } catch (IllegalArgumentException e) {
                for (T constant : enumClass.getEnumConstants()) {
                    if (constant.name().equalsIgnoreCase(name)) {
                        return constant;
                    }
                }
                return null;
            }
        }
    }
}
