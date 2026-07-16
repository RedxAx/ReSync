package restudio.resync.flow.registry;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.SerializedName;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import restudio.flow.data.FlowDataType;
import restudio.resync.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class NodeDefinitionLoader {

    private final Gson gson;
    private NodeDefinitionValidator validator;

    public NodeDefinitionLoader() {
        this.gson = new GsonBuilder()
            .registerTypeAdapter(NodeDefinition.PinType.class, new EnumAdapter<>(NodeDefinition.PinType.class))
            .registerTypeAdapter(NodeDefinition.PinDirection.class, new EnumAdapter<>(NodeDefinition.PinDirection.class))
            .registerTypeAdapter(NodeDefinition.WidgetType.class, new EnumAdapter<>(NodeDefinition.WidgetType.class))
            .registerTypeAdapter(NodeDefinition.NodeCategory.class, new com.google.gson.TypeAdapter<NodeDefinition.NodeCategory>() {
                @Override
                public void write(com.google.gson.stream.JsonWriter out, NodeDefinition.NodeCategory value) throws IOException {
                    out.value(value != null ? value.getId() : null);
                }

                @Override
                public NodeDefinition.NodeCategory read(com.google.gson.stream.JsonReader in) throws IOException {
                    String id = in.nextString();
                    return NodeDefinition.NodeCategory.fromString(id);
                }
            })
            .registerTypeAdapter(NodeDefinition.NodeKind.class, new EnumAdapter<>(NodeDefinition.NodeKind.class))
            .create();
    }

    public void setValidator(NodeDefinitionValidator validator) {
        this.validator = validator;
    }

    public List<NodeDefinition> loadFromClasspath(String resourcePath) {
        List<NodeDefinition> results = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = NodeDefinitionLoader.class.getClassLoader();
        }

        try {
            boolean loaded = loadFromCodeSource(resourcePath, results, errors);
            if (!loaded) {
                loadFromClassLoaderUrls(classLoader, resourcePath, results, errors);
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
        List<NodeDefinition> results = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (classLoader == null || resourcePath == null || resourcePath.isBlank()) {
            return results;
        }
        try {
            loadFromClassLoaderUrls(classLoader, resourcePath, results, errors);
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

    private boolean loadFromCodeSource(String resourcePath, List<NodeDefinition> results, List<String> errors) throws IOException, URISyntaxException {
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
            scanPath(root, results, errors);
            return true;
        }

        if (Files.isRegularFile(codeSourcePath) && codeSourcePath.toString().toLowerCase().endsWith(".jar")) {
            scanJar(codeSourcePath, resourcePath, results, errors);
            return true;
        }

        return false;
    }

    private void loadFromClassLoaderUrls(ClassLoader classLoader, String resourcePath, List<NodeDefinition> results, List<String> errors) throws IOException, URISyntaxException {
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
                    scanJar(Paths.get(URI.create(jarUri)), resourcePath, results, errors);
                }
            } else if ("file".equalsIgnoreCase(uri.getScheme())) {
                scanPath(Paths.get(uri), results, errors);
            }
        }
    }

    private void scanPath(Path root, List<NodeDefinition> results, List<String> errors) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".json"))
                .filter(p -> !p.getFileName().toString().startsWith("_"))
                .forEach(p -> {
                    try (InputStream is = Files.newInputStream(p)) {
                        results.addAll(parse(is));
                    } catch (Exception e) {
                        errors.add(p.getFileName() + ": " + e.getMessage());
                    }
                });
        }
    }

    private void scanJar(Path jarPath, String resourcePath, List<NodeDefinition> results, List<String> errors) throws IOException {
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
                    results.addAll(parse(is));
                } catch (Exception e) {
                    errors.add(fileName + ": " + e.getMessage());
                }
            }
        }
    }

    public List<NodeDefinition> loadFromDirectory(java.nio.file.Path dir) {
        List<NodeDefinition> results = new ArrayList<>();
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return results;
        }

        try (Stream<java.nio.file.Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> p.toString().endsWith(".json"))
                  .filter(p -> !p.getFileName().toString().startsWith("_"))
                  .forEach(p -> {
                      try (InputStream is = Files.newInputStream(p)) {
                          results.addAll(parse(is));
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
        List<NodeDefinition> results = new ArrayList<>();
        JsonReader reader = new JsonReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        com.google.gson.JsonElement root = com.google.gson.JsonParser.parseReader(reader);

        if (root.isJsonArray()) {
            for (com.google.gson.JsonElement element : root.getAsJsonArray()) {
                NodeDefinition def = parseSingle(element);
                if (def != null) {
                    results.add(def);
                }
            }
        } else if (root.isJsonObject()) {
            NodeDefinition def = parseSingle(root);
            if (def != null) {
                results.add(def);
            }
        }

        return results;
    }

    private com.google.gson.JsonElement applyCompatibilityTransforms(com.google.gson.JsonElement element) {
        if (!element.isJsonObject()) {
            return element;
        }
        com.google.gson.JsonObject obj = element.getAsJsonObject();

        if (obj.has("inputs") && obj.get("inputs").isJsonArray()) {
            com.google.gson.JsonArray arr = obj.getAsJsonArray("inputs");
            for (int i = 0; i < arr.size(); i++) {
                arr.set(i, normalizeLegacyPin(arr.get(i)));
            }
        }
        if (obj.has("outputs") && obj.get("outputs").isJsonArray()) {
            com.google.gson.JsonArray arr = obj.getAsJsonArray("outputs");
            for (int i = 0; i < arr.size(); i++) {
                arr.set(i, normalizeLegacyPin(arr.get(i)));
            }
        }

        if (obj.has("handlerConfig") && obj.get("handlerConfig").isJsonObject()) {
            com.google.gson.JsonObject hc = obj.getAsJsonObject("handlerConfig");
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

    private com.google.gson.JsonElement normalizeLegacyPin(com.google.gson.JsonElement pinEl) {
        if (!pinEl.isJsonObject()) {
            return pinEl;
        }
        com.google.gson.JsonObject pin = pinEl.getAsJsonObject();

        if (pin.has("id") && !pin.has("name")) {
            pin.addProperty("name", pin.get("id").getAsString());
            pin.remove("id");
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

    private NodeDefinition parseSingle(com.google.gson.JsonElement element) {
        element = applyCompatibilityTransforms(element);
        NodeJson dto = gson.fromJson(element, NodeJson.class);
        if (dto == null || dto.id == null || dto.id.isBlank()) {
            return null;
        }

        NodeDefinition.NodeCategory category = dto.category != null ? dto.category : NodeDefinition.NodeCategory.UTILITY;
        NodeDefinition.Builder builder = new NodeDefinition.Builder(dto.id, dto.displayName != null ? dto.displayName : dto.id, category);

        if (dto.schemaVersion != null) {
            builder.schemaVersion(dto.schemaVersion);
        }
        if (dto.kind != null) {
            builder.kind(dto.kind);
        }
        NodeDefinition.Availability availability = dto.availability != null
            ? new NodeDefinition.Availability(dto.availability.plugin, dto.availability.platform, dto.availability.minVersion)
            : inferAvailability(dto.handler);
        if (availability != null) {
            builder.availability(availability);
        }
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
        }
        if (dto.description != null && !dto.description.isBlank()) {
            builder.description(dto.description);
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
        if (dto.tags != null) {
            builder.tags(dto.tags);
        }
        if (dto.examples != null) {
            builder.examples(dto.examples);
        }
        if (dto.family != null && !dto.family.isBlank()) {
            builder.family(dto.family);
        }
        if (dto.recommended != null) {
            builder.recommended(dto.recommended);
        }
        if (dto.replacementFor != null && !dto.replacementFor.isBlank()) {
            builder.replacementFor(dto.replacementFor);
        }

        if (dto.inputs != null) {
            for (PinJson pin : dto.inputs) {
                NodeDefinition.PinDefinition def = toPinDefinition(pin, NodeDefinition.PinDirection.INPUT);
                if (def == null) {
                    Log.warn("[NodeDefinitionLoader] Invalid input pin in node: " + dto.id);
                    return null;
                }
                builder.input(def);
            }
        }

        if (dto.outputs != null) {
            for (PinJson pin : dto.outputs) {
                NodeDefinition.PinDefinition def = toPinDefinition(pin, NodeDefinition.PinDirection.OUTPUT);
                if (def == null) {
                    Log.warn("[NodeDefinitionLoader] Invalid output pin in node: " + dto.id);
                    return null;
                }
                builder.output(def);
            }
        }

        return builder.build();
    }

    private NodeDefinition.PinDefinition toPinDefinition(PinJson pin, NodeDefinition.PinDirection direction) {
        NodeDefinition.PinType pinType = pin.pinType != null ? pin.pinType : NodeDefinition.PinType.DATA;
        FlowDataType dataType = parseAndValidateDataType(pin.dataType, pinType);
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
                widget = NodeDefinition.WidgetType.DROPDOWN;
                if (inferred.contains("material") || inferred.contains("advancement") || inferred.contains("enchantment") || inferred.contains("sound")) {
                    widget = NodeDefinition.WidgetType.SEARCHABLE_LIST;
                }
            }
        }

        NodeDefinition.PinConstraints constraints = null;
        if (pin.constraints != null) {
            constraints = new NodeDefinition.PinConstraints(pin.constraints.min, pin.constraints.max, pin.constraints.step);
        }

        Map<String, String> visibleWhen = pin.visibleWhen != null ? pin.visibleWhen : Collections.emptyMap();

        return new NodeDefinition.PinDefinition(
            pin.name,
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
            Boolean.TRUE.equals(pin.optional)
        );
    }

    private FlowDataType parseAndValidateDataType(String raw, NodeDefinition.PinType pinType) {
        if (raw == null || raw.isBlank()) {
            if (pinType == NodeDefinition.PinType.FLOW) {
                return FlowDataType.EXECUTION;
            }
            return FlowDataType.ANY;
        }
        FlowDataType type = FlowDataType.fromString(raw);
        if (type == FlowDataType.ANY && !"any".equalsIgnoreCase(raw)) {
            if (raw.contains(":")) {
                return new FlowDataType(raw, FlowDataType.STRING, String.class, null, 0x808080);
            }
            Log.warn("[NodeDefinitionLoader] Unknown dataType: " + raw);
            return null;
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
            case "material" -> "client:minecraft:material";
            case "gamemode" -> "client:minecraft:gamemode";
            case "difficulty" -> "client:minecraft:difficulty";
            case "potion_effect" -> "client:minecraft:potion_effect";
            case "sound" -> "client:minecraft:sound";
            case "advancement" -> "client:minecraft:advancement";
            case "biome" -> "client:minecraft:biome";
            case "entity_type" -> "client:minecraft:entity_type";
            case "enchantment" -> "client:minecraft:enchantment";
            default -> null;
        };
    }

    public void validateAndRegister(List<NodeDefinition> definitions, NodeDefinitionRegistry registry, restudio.resync.flow.handler.HandlerRegistry handlerRegistry, String pluginId) {
        if (validator == null && handlerRegistry != null) {
            validator = new NodeDefinitionValidator(handlerRegistry);
        }
        Set<String> displayNames = new HashSet<>();
        for (NodeDefinition def : definitions) {
            if (registry.getAllDefinitions().containsKey(def.getId())) {
                Log.warn("[NodeDefinitionLoader] Duplicate node ID: " + def.getId());
                continue;
            }
            String displayKey = def.getCategory() + ":" + def.getDisplayName().toLowerCase();
            if (!def.isHidden() && !displayNames.add(displayKey)) {
                Log.warn("[NodeDefinitionLoader] Duplicate visible display name in category " + def.getCategory() + ": " + def.getDisplayName());
            }
            if (validator != null) {
                NodeDefinitionValidator.ValidationResult result = validator.validate(def);
                if (result.hasErrors()) {
                    for (String error : result.errors()) {
                        Log.warn("[NodeDefinitionLoader] Validation error for " + def.getId() + ": " + error);
                    }
                    continue;
                }
                if (result.hasWarnings()) {
                    for (String warning : result.warnings()) {
                        Log.fine("[NodeDefinitionLoader] Validation warning for " + def.getId() + ": " + warning);
                    }
                }
            }
            registry.register(pluginId, def);
        }
    }

    private static class NodeJson {
        String id;
        String displayName;
        NodeDefinition.NodeCategory category;
        String description;
        Integer color;
        Integer priority;
        Boolean hidden;
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
        List<PinJson> inputs;
        List<PinJson> outputs;
        List<String> tags;
        List<String> examples;
        String family;
        Boolean recommended;
        String replacementFor;
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

    private static class PinJson {
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
