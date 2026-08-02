package restudio.resync.replacement.evidence.performance;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowSerializer;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionLoader;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.validation.FlowGraphValidator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrentBaselineEvidenceTest {
    private static final int WARMUPS = 5;
    private static final int SAMPLES = 10;
    private static final Path CATALOG = Path.of("src", "main", "resources", "nodes");
    private static final Path GRAPH = Path.of("src", "test", "resources", "fixtures", "programmability", "legacy-command-flow.json");
    private static final Path FIXTURE_ROOT = Path.of("src", "test", "resources", "fixtures", "programmability");

    @TempDir
    Path temporaryDirectory;

    @Test
    void recordsCurrentFixedFixtureBaselines() throws Exception {
        assertTrue(Files.isDirectory(CATALOG));
        assertTrue(Files.isRegularFile(GRAPH));

        for (int index = 0; index < WARMUPS; index++) {
            parseCatalog();
            loadCatalog();
            deserializeGraph();
            validateGraph();
            copyFixture(index);
        }

        Map<String, List<Double>> samples = new LinkedHashMap<>();
        samples.put("catalogParseMillis", samples(this::parseCatalog));
        samples.put("catalogLoadMillis", samples(this::loadCatalog));
        samples.put("graphDeserializeMillis", samples(this::deserializeGraph));
        samples.put("graphValidateMillis", samples(this::validateGraph));
        samples.put("fixtureSnapshotCopyMillis", samples(() -> copyFixture(100)));
        samples.put("fixtureRestoreCopyMillis", samples(() -> restoreFixture(100)));

        List<NodeDefinition> definitions = loadCatalog();
        NodeDefinitionRegistry registry = registry(definitions);
        byte[] projection = new Gson().toJson(registryProjection(registry)).getBytes(StandardCharsets.UTF_8);
        long usedHeapBytes = usedHeapBytes();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("schemaVersion", 1);
        output.put("measurement", "current-fixed-fixture-baseline");
        output.put("warmups", WARMUPS);
        output.put("samples", SAMPLES);
        output.put("catalogSourceHash", treeHash(CATALOG));
        output.put("graphFixtureHash", sha256(Files.readAllBytes(GRAPH)));
        output.put("fixtureTreeHash", treeHash(FIXTURE_ROOT));
        output.put("catalogDefinitionCount", definitions.size());
        output.put("registryProjectionProxyJsonBytes", projection.length);
        output.put("fixtureCopyBytes", treeBytes(FIXTURE_ROOT));
        output.put("heapUsedBytesAfterCatalogLoad", usedHeapBytes);
        output.put("environment", environment());
        output.put("rawMillis", samples);
        output.put("statistics", statistics(samples));
        output.put("unavailable", List.of(
            "Remotely client hydration and interactive editing require a live supported client acceptance run.",
            "Current catalog compilation and immutable graph compilation do not exist; loader/validator proxies are recorded instead.",
            "Current coordinated migration and whole-folder snapshot/restore do not exist; fixture copy proxies are recorded instead.",
            "Execution overhead is not measured because the legacy fixture requires live handler/server state and would not be environment-safe."
        ));
        Path report = Path.of("build", "reports", "node-replacement-performance", "current-baseline.json");
        Files.createDirectories(report.getParent());
        Files.writeString(report, new Gson().toJson(output), StandardCharsets.UTF_8);
        assertFalse(definitions.isEmpty());
        assertTrue(Files.size(report) > 0L);
    }

    private void parseCatalog() throws IOException {
        try (var paths = Files.walk(CATALOG)) {
            for (Path path : paths.filter(path -> path.getFileName().toString().endsWith(".json")).filter(path -> !path.getFileName().toString().startsWith("_")).toList()) {
                JsonParser.parseString(Files.readString(path));
            }
        }
    }

    private List<NodeDefinition> loadCatalog() {
        return new NodeDefinitionLoader().loadFromDirectory(CATALOG);
    }

    private void deserializeGraph() throws IOException {
        FlowSerializer.deserialize(Files.readString(GRAPH));
    }

    private void validateGraph() throws IOException {
        NodeDefinitionRegistry definitions = registry(loadCatalog());
        FlowGraph graph = FlowSerializer.deserialize(Files.readString(GRAPH));
        new FlowGraphValidator(definitions, new HandlerRegistry(), new TypeAdapterRegistry(), new OptionCatalogRegistry()).validate(graph);
    }

    private void copyFixture(int suffix) throws IOException {
        copyTree(FIXTURE_ROOT, temporaryDirectory.resolve("snapshot-" + suffix));
    }

    private void restoreFixture(int suffix) throws IOException {
        Path snapshot = temporaryDirectory.resolve("snapshot-" + suffix);
        if (Files.notExists(snapshot)) {
            copyFixture(suffix);
        }
        copyTree(snapshot, temporaryDirectory.resolve("restore-" + suffix));
    }

    private List<Double> samples(CheckedOperation operation) throws Exception {
        List<Double> result = new ArrayList<>();
        for (int index = 0; index < SAMPLES; index++) {
            long started = System.nanoTime();
            operation.run();
            result.add((System.nanoTime() - started) / 1_000_000D);
        }
        return result;
    }

    private NodeDefinitionRegistry registry(List<NodeDefinition> definitions) {
        NodeDefinitionRegistry registry = new NodeDefinitionRegistry();
        registry.registerAll("baseline", definitions);
        return registry;
    }

    private Map<String, Object> registryProjection(NodeDefinitionRegistry registry) {
        Map<String, Object> projection = new LinkedHashMap<>();
        registry.getAllDefinitions().values().stream().sorted(Comparator.comparing(NodeDefinition::getId)).forEach(definition -> {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", definition.getId());
            node.put("owner", definition.getOwner());
            node.put("displayName", definition.getDisplayName());
            node.put("category", definition.getCategory().getId());
            node.put("hidden", definition.isHidden());
            node.put("handler", definition.getHandler());
            node.put("schemaVersion", definition.getSchemaVersion());
            node.put("canonicalId", definition.getCanonicalId());
            node.put("replacementFor", definition.getReplacementFor());
            node.put("inputs", pins(definition.getInputs()));
            node.put("outputs", pins(definition.getOutputs()));
            projection.put(definition.getId(), node);
        });
        return projection;
    }

    private List<Map<String, Object>> pins(List<NodeDefinition.PinDefinition> pins) {
        return pins.stream().map(pin -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("name", pin.getName());
            value.put("type", pin.getType().name());
            value.put("direction", pin.getDirection().name());
            value.put("dataType", pin.getDataType() != null ? pin.getDataType().getId() : "");
            value.put("optionsSource", pin.getOptionsSource());
            value.put("defaultValue", pin.getDefaultValue());
            value.put("optional", pin.isOptional());
            value.put("visibleWhen", pin.getVisibleWhen());
            return value;
        }).toList();
    }

    private void copyTree(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private long treeBytes(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).mapToLong(this::size).sum();
        }
    }

    private long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String treeHash(Path root) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var paths = Files.walk(root)) {
                for (Path path : paths.filter(Files::isRegularFile).sorted(Comparator.comparing(path -> root.relativize(path).toString())).toList()) {
                    digest.update(root.relativize(path).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    digest.update(Files.readAllBytes(path));
                    digest.update((byte) 0);
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String sha256(byte[] input) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Map<String, Object> environment() {
        return Map.of(
            "javaVersion", System.getProperty("java.version"),
            "javaVendor", System.getProperty("java.vendor"),
            "osName", System.getProperty("os.name"),
            "osVersion", System.getProperty("os.version"),
            "availableProcessors", Runtime.getRuntime().availableProcessors(),
            "maxHeapBytes", Runtime.getRuntime().maxMemory()
        );
    }

    private long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private Map<String, Map<String, Double>> statistics(Map<String, List<Double>> samples) {
        Map<String, Map<String, Double>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<Double>> entry : samples.entrySet()) {
            List<Double> values = entry.getValue().stream().sorted().toList();
            result.put(entry.getKey(), Map.of(
                "min", values.getFirst(),
                "median", percentile(values, 0.5D),
                "p95", percentile(values, 0.95D),
                "max", values.getLast()
            ));
        }
        return result;
    }

    private double percentile(List<Double> values, double percentile) {
        return values.get((int) Math.ceil(percentile * values.size()) - 1);
    }

    @FunctionalInterface
    private interface CheckedOperation {
        void run() throws Exception;
    }
}
