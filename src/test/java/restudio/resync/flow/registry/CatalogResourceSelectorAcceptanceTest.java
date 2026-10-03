package restudio.resync.flow.registry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import restudio.flow.data.FlowNode;
import restudio.resync.api.ReSyncExtensionData;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.catalog.CatalogBindingProof;
import restudio.resync.flow.catalog.CatalogCanonicalizer;
import restudio.resync.flow.catalog.CatalogCompiler;
import restudio.resync.flow.catalog.CatalogContribution;
import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.catalog.CatalogProvenance;
import restudio.resync.flow.catalog.CatalogSourceIngestor;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.runtime.RuntimeBindingKey;
import restudio.resync.flow.runtime.RuntimeOperationDescriptor;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.modules.FlowModule;
import restudio.resync.protocol.ReSyncProtocolContract;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogResourceSelectorAcceptanceTest {
    private static final Path CATALOG_ROOT = Path.of("src", "main", "resources", "nodes");
    private static final String MANIFEST = "/node-replacement/catalog-resource-selector-acceptance.tsv";
    private static Catalog cachedCatalog;
    private static Throwable catalogFailure;
    private static Manifest cachedManifest;

    @Test
    void manifestFreezesTheReviewedGateZeroCatalogEvidence() throws Exception {
        Manifest manifest = manifest();

        assertEquals("2", manifest.metadata().get("schemaVersion"));
        assertEquals("1240", manifest.metadata().get("baselineActiveDescriptors"));
        assertEquals("6748", manifest.metadata().get("baselineActivePins"));
        assertEquals("3440", manifest.metadata().get("baselineActiveInputs"));
        assertEquals("3308", manifest.metadata().get("baselineActiveOutputs"));
        assertEquals("128", manifest.metadata().get("baselineCatalogBackedResourceSelectors"));
        assertEquals("resource<{owner}:{type}>", manifest.metadata().get("authoritativeTypeSyntax"));
        assertEquals("true", manifest.metadata().get("legacyColumnsAreEvidenceOnly"));
    }

    @Test
    void manifestResolvesUniqueAdmittedInputsAndCoversEveryResourceCatalogSource() throws Exception {
        Catalog catalog = catalog();
        Manifest manifest = manifest();
        Set<String> manifestKeys = new HashSet<>();
        List<String> missingRows = new ArrayList<>();

        for (SelectorRow row : manifest.rows()) {
            assertTrue(manifestKeys.add(row.key()), "Duplicate manifest row " + row.key());
            Pin pin = catalog.inputs().get(row.key());
            if (pin == null) {
                missingRows.add(row.key());
                continue;
            }
            assertEquals(row.operation(), pin.operation(), row.key());
            assertFalse(row.expectedOwner().isBlank(), row.key());
            assertFalse(row.expectedType().isBlank(), row.key());
            assertFalse(row.runtimeProvider().isBlank(), row.key());
        }

        assertTrue(missingRows.isEmpty(), () -> "Manifest rows missing from the admitted catalog: " + sorted(missingRows) + "; current inventory=" + catalog.inventory());

        Set<String> unclassified = new TreeSet<>();
        for (Pin pin : catalog.inputs().values()) {
            if (pin.type() instanceof TypeExpr.ResourceType && !pin.optionSource().isBlank() && !manifestKeys.contains(pin.key())) {
                unclassified.add(pin.key() + "=" + pin.optionSource());
            }
        }
        assertTrue(unclassified.isEmpty(), () -> "Catalog-backed resource selectors missing from the manifest: " + unclassified + "; current inventory=" + catalog.inventory());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("selectorRows")
    void selectorsUseAdmittedTypesAndExactSourceBindings(SelectorRow row) throws Exception {
        Pin pin = catalog().inputs().get(row.key());
        assertNotNull(pin, row.key());
        List<String> mismatches = mismatches(row, pin);

        assertTrue(mismatches.isEmpty(), () -> row.key() + " operation=" + row.operation() + " owner=" + row.expectedOwner() + " type=" + row.expectedType()
            + " provider=" + row.runtimeProvider() + " legacyType=" + row.legacyType() + " legacyOptionSource=" + value(row.legacyOptionSource())
            + " mismatches=" + mismatches);
    }

    private static Stream<SelectorRow> selectorRows() throws Exception {
        return manifest().rows().stream();
    }

    private static List<String> mismatches(SelectorRow row, Pin pin) {
        List<String> mismatches = new ArrayList<>();
        String exactResourceType = "resource<" + row.expectedOwner() + ":" + row.expectedType() + ">";

        if (!pin.type().equals(pin.runtimeType())) {
            mismatches.add("RUNTIME_PIN_TYPE_MISMATCH");
        }
        if (pin.optionType() != null && !pin.type().equals(pin.optionType())) {
            mismatches.add("OPTION_PIN_TYPE_MISMATCH");
        }

        switch (row.intent()) {
            case REFERENCE_SELECTOR, REFERENCE_VALUE -> {
                if (!exactResourceType.equals(pin.dataType())) {
                    if ("string".equalsIgnoreCase(pin.dataType())) {
                        mismatches.add("STRING_RESOURCE_SELECTOR");
                    } else if ("any".equalsIgnoreCase(pin.dataType())) {
                        mismatches.add("ANY_RESOURCE_SELECTOR");
                    } else {
                        mismatches.add("MISSING_EXACT_GENERIC_RESOURCE_TYPE(" + pin.dataType() + " -> " + exactResourceType + ")");
                    }
                }
                if (!row.expectedOptionSource().equals(pin.optionSource())) {
                    mismatches.add("WRONG_SOURCE_TYPE_BINDING(" + value(pin.optionSource()) + " -> " + value(row.expectedOptionSource()) + ")");
                }
            }
            case SCALAR_SELECTOR, SCALAR_VALUE -> {
                if (!(pin.type() instanceof TypeExpr.Named named) || !row.expectedOwner().equals(named.reference().ownerId())
                    || !row.expectedType().equals(pin.dataType())) {
                    mismatches.add("WRONG_SCALAR_TYPE(" + pin.dataType() + " -> " + row.expectedOwner() + ":" + row.expectedType() + ")");
                }
                if (!row.expectedOptionSource().equals(pin.optionSource())) {
                    mismatches.add("WRONG_SOURCE_TYPE_BINDING(" + value(pin.optionSource()) + " -> " + value(row.expectedOptionSource()) + ")");
                }
            }
            case FREEFORM_CREATION -> {
                if (!"string".equals(pin.dataType())) {
                    mismatches.add("ACCIDENTAL_RESOURCE_CONVERSION(" + pin.dataType() + ")");
                }
                if (!pin.optionSource().isBlank()) {
                    mismatches.add("ACCIDENTAL_CREATION_OPTION_SOURCE(" + pin.optionSource() + ")");
                }
            }
        }
        return mismatches;
    }

    private static synchronized Catalog catalog() throws Exception {
        if (cachedCatalog != null) {
            return cachedCatalog;
        }
        if (catalogFailure != null) {
            throw new IllegalStateException("Catalog fixture admission failed", catalogFailure);
        }
        try {
            cachedCatalog = loadCatalog();
            return cachedCatalog;
        } catch (Exception | Error failure) {
            catalogFailure = failure;
            throw failure;
        }
    }

    private static Catalog loadCatalog() throws Exception {
        List<NodeDefinitionLoader.SourceFile> sources = new ArrayList<>();
        try (Stream<Path> files = Files.walk(CATALOG_ROOT)) {
            for (Path file : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".json"))
                .filter(path -> !path.getFileName().toString().startsWith("_"))
                .filter(path -> !CATALOG_ROOT.relativize(path).startsWith("migrated")).sorted().toList()) {
                String relative = CATALOG_ROOT.relativize(file).toString().replace('\\', '/');
                sources.add(new NodeDefinitionLoader.SourceFile(relative, "classpath:/nodes/" + relative, relative,
                    NodeDefinitionLoader.SourceOrigin.CLASSPATH, Files.readAllBytes(file)));
            }
        }
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        List<NodeDefinition> loaded = loader.loadReplacementFromSources(sources);
        assertTrue(loader.getDiagnostics().stream().noneMatch(value -> value.severity() == NodeDefinitionDiagnostic.Severity.ERROR),
            () -> "Strict replacement parsing failed: " + loader.getDiagnostics());
        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry(false);
        definitions.registerAll("json-classpath", loaded);
        HandlerRegistry handlers = catalogHandlers(loaded);
        Map<String, AuthoredSourceProvenance> proofs = new HashMap<>();
        for (NodeDefinition definition : loaded) {
            AuthoredSourceProvenance proof = definition.getAuthoredMetadata().sourceProvenance();
            AuthoredSourceProvenance previous = proofs.putIfAbsent(proof.sourceUri(), proof);
            if (previous != null) {
                assertEquals(previous.owner(), proof.owner(), proof.sourceUri());
                assertEquals(previous.sourceHash(), proof.sourceHash(), proof.sourceUri());
            }
        }
        List<CatalogSourceIngestor.CatalogSource> authoredSources = sources.stream().map(source -> {
            AuthoredSourceProvenance proof = proofs.get(source.sourceUri());
            assertNotNull(proof, source.sourceUri());
            return CatalogSourceIngestor.CatalogSource.prepared(OwnerId.of(proof.owner()), CatalogProvenance.SourceKind.BUNDLED,
                source.sourceUri(), "1.0.0", "resync-flow", source.bytes(), ContentHash.of(proof.sourceHash()));
        }).toList();
        CatalogVersion contract = ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION;
        List<CatalogContribution> contributions = contributions(definitions, handlers, authoredSources, contract);
        Map<RuntimeBindingKey, RuntimeOperationDescriptor> requirements = new HashMap<>();
        contributions.forEach(contribution -> contribution.runtimeRequirements().forEach(requirement ->
            assertTrue(requirements.putIfAbsent(requirement.key(), requirement) == null, requirement.key().canonical())));
        Map<RuntimeBindingKey, ContentHash> fingerprints = new HashMap<>();
        requirements.forEach((key, requirement) -> fingerprints.put(key, requirement.executionFingerprint()));
        var result = new CatalogCompiler(contract, CatalogBindingProof.fixed(fingerprints,
            CatalogCanonicalizer.bindingManifestHash(contributions))).compile(contributions, 1L);
        assertTrue(result.accepted(), () -> "Catalog admission failed: " + result.diagnostics());
        var snapshot = result.snapshot().orElseThrow();
        Map<String, Pin> inputs = new HashMap<>();
        int descriptors = 0;
        int inputCount = 0;
        int outputCount = 0;
        for (var owned : snapshot.definitions()) {
            CatalogNodeDescriptor descriptor = owned.descriptor();
            descriptors++;
            String id = descriptor.id().value();
            String operation = descriptor.handler().operation().id().value();
            RuntimeOperationDescriptor requirement = requirements.get(new RuntimeBindingKey(descriptor.handler().capability(),
                descriptor.handler().operation()));
            assertNotNull(requirement, id);
            for (CatalogNodeDescriptor.Pin pin : descriptor.pins()) {
                if (pin.direction() == CatalogNodeDescriptor.Direction.OUTPUT) {
                    outputCount++;
                    continue;
                }
                inputCount++;
                String pinId = pin.id().value();
                var runtimePin = requirement.pins().stream().filter(value -> value.id().equals(pin.id())
                    && value.direction() == RuntimeOperationDescriptor.Direction.INPUT).findFirst().orElseThrow();
                String source = "";
                TypeExpr optionType = null;
                if (pin.optionSource() != null) {
                    var option = snapshot.optionSource(pin.optionSource()).orElseThrow().descriptor();
                    assertEquals(pin.optionSource().owner(), option.capability().owner(), id + "|" + pinId);
                    assertTrue(snapshot.capability(option.capability()).isPresent(), id + "|" + pinId);
                    optionType = option.optionType();
                    source = authoredOptionSource(descriptor, pinId);
                }
                Pin previous = inputs.put(id + "|" + pinId, new Pin(id, pinId, operation, pin.type(), source,
                    optionType, runtimePin.type()));
                assertTrue(previous == null, "Duplicate admitted input " + id + "|" + pinId);
            }
        }
        return new Catalog(Map.copyOf(inputs), new Inventory(descriptors, inputCount + outputCount, inputCount, outputCount));
    }

    private static HandlerRegistry catalogHandlers(List<NodeDefinition> definitions) {
        Map<String, Set<String>> operations = new HashMap<>();
        for (NodeDefinition definition : definitions) {
            if (definition.getHandler() == null || definition.getHandler().isBlank()) {
                continue;
            }
            Object operation = definition.getHandlerConfig().get("operation");
            if (operation instanceof String name && !name.isBlank()) {
                operations.computeIfAbsent(definition.getHandler(), ignored -> new HashSet<>()).add(name);
            }
        }
        HandlerRegistry handlers = new HandlerRegistry();
        operations.forEach((id, names) -> handlers.register(id, new CatalogHandler(Set.copyOf(names))));
        return handlers;
    }

    @SuppressWarnings("unchecked")
    private static List<CatalogContribution> contributions(NodeDefinitionRegistry definitions, HandlerRegistry handlers,
        List<CatalogSourceIngestor.CatalogSource> sources, CatalogVersion contract) throws Exception {
        var method = FlowModule.class.getDeclaredMethod("buildCatalogContributions", NodeDefinitionRegistry.class,
            HandlerRegistry.class, ReSyncExtensionData.class, List.class, CatalogVersion.class);
        method.setAccessible(true);
        try {
            return (List<CatalogContribution>) method.invoke(null, definitions, handlers, null, sources, contract);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw (Error) failure.getCause();
        }
    }

    private static String authoredOptionSource(CatalogNodeDescriptor descriptor, String pinId) {
        assertTrue(descriptor.metadata().get("authoredSource") instanceof Map<?, ?>, descriptor.id().value());
        Map<?, ?> authored = (Map<?, ?>) descriptor.metadata().get("authoredSource");
        assertTrue(authored.get("inputs") instanceof List<?>, descriptor.id().value());
        List<?> pins = (List<?>) authored.get("inputs");
        Map<?, ?> pin = pins.stream().filter(value -> value instanceof Map<?, ?> values && pinId.equals(values.get("id")))
            .map(value -> (Map<?, ?>) value).findFirst().orElseThrow();
        assertTrue(pin.get("optionsSource") instanceof String source && !source.isBlank(), descriptor.id().value() + "|" + pinId);
        return (String) pin.get("optionsSource");
    }

    private static String typeText(TypeExpr type) {
        return switch (type) {
            case TypeExpr.ResourceType resource -> "resource<" + resource.resourceType().ownerId() + ":" + resource.resourceType().localId() + ">";
            case TypeExpr.Named named -> {
                String id = "builtin".equals(named.reference().ownerId()) ? named.reference().localId()
                    : named.reference().ownerId() + ":" + named.reference().localId();
                yield id + (named.arguments().isEmpty() ? "" : named.arguments().stream().map(CatalogResourceSelectorAcceptanceTest::typeText)
                    .collect(Collectors.joining(",", "<", ">")));
            }
            default -> type.canonicalJson();
        };
    }

    private static synchronized Manifest manifest() throws Exception {
        if (cachedManifest != null) {
            return cachedManifest;
        }
        InputStream stream = CatalogResourceSelectorAcceptanceTest.class.getResourceAsStream(MANIFEST);
        assertNotNull(stream, MANIFEST);
        Map<String, String> metadata = new LinkedHashMap<>();
        List<SelectorRow> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                if (line.startsWith("# ")) {
                    String[] entry = line.substring(2).split("=", 2);
                    metadata.put(entry[0], entry[1]);
                    continue;
                }
                if (line.startsWith("descriptor\t")) {
                    continue;
                }
                String[] fields = line.split("\t", -1);
                assertEquals(11, fields.length, "Malformed manifest row " + line);
                rows.add(new SelectorRow(fields[0], fields[1], fields[2], Intent.valueOf(fields[3]), fields[4], fields[5], fields[6], fields[7], fields[8], fields[9], fields[10]));
            }
        }
        cachedManifest = new Manifest(Map.copyOf(metadata), List.copyOf(rows));
        return cachedManifest;
    }

    private static String sorted(List<String> values) {
        return new TreeSet<>(values).toString();
    }

    private static String value(String value) {
        return value.isBlank() ? "<none>" : value;
    }

    private enum Intent {
        REFERENCE_SELECTOR,
        REFERENCE_VALUE,
        FREEFORM_CREATION,
        SCALAR_SELECTOR,
        SCALAR_VALUE
    }

    private record SelectorRow(
        String descriptor,
        String pin,
        String operation,
        Intent intent,
        String expectedOptionSource,
        String expectedOwner,
        String expectedType,
        String runtimeProvider,
        String legacyType,
        String legacyOptionSource,
        String note
    ) {
        String key() {
            return descriptor + "|" + pin;
        }

        @Override
        public String toString() {
            return key();
        }
    }

    private record Pin(String descriptor, String pin, String operation, TypeExpr type, String optionSource,
                       TypeExpr optionType, TypeExpr runtimeType) {
        String key() {
            return descriptor + "|" + pin;
        }

        String dataType() {
            return typeText(type);
        }
    }

    private record CatalogHandler(Set<String> operations) implements NodeHandler {
        @Override
        public void execute(FlowContext context, FlowNode node) {
            throw new UnsupportedOperationException("Catalog contract fixture does not execute handlers");
        }

        @Override
        public Set<String> getSupportedOperations() {
            return operations;
        }
    }

    private record Inventory(int admittedDescriptors, int admittedPins, int admittedInputs, int admittedOutputs) {
    }

    private record Catalog(Map<String, Pin> inputs, Inventory inventory) {
    }

    private record Manifest(Map<String, String> metadata, List<SelectorRow> rows) {
    }
}
