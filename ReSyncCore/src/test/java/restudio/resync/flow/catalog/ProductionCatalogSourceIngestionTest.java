package restudio.resync.flow.catalog;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.inspector.InspectorCapability;
import restudio.resync.flow.inspector.InspectorFallback;
import restudio.resync.flow.inspector.InspectorOptionSource;
import restudio.resync.flow.inspector.InspectorValueSchema;
import restudio.resync.flow.inspector.OptionQuerySchemaV1;
import restudio.resync.flow.runtime.RuntimeFailureContract;
import restudio.resync.flow.runtime.RuntimeBindingKey;
import restudio.resync.flow.runtime.RuntimeOperationDescriptor;
import restudio.resync.flow.runtime.RuntimeSemantics;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionCatalogSourceIngestionTest {
    private static final Path NODE_ROOT = Path.of("..", "src", "main", "resources", "nodes");
    private static final OwnerId OWNER = OwnerId.of("restudio.resync");
    private static final CatalogVersion CONTRACT = new CatalogVersion(1, 3);
    private static final CatalogContractRange CONTRACT_RANGE = new CatalogContractRange(CONTRACT, CONTRACT);
    private static final String BUILD_ID = "production-catalog-source-ingestion-test";
    private static final int EXPECTED_FILES = 80;
    private static final int EXPECTED_DEFINITIONS = 1257;
    private static final int EXPECTED_PINS = 6909;
    private static final int EXPECTED_MIGRATED_DEFINITIONS = 972;
    private static final int EXPECTED_MIGRATIONS = 973;
    private static final int EXPECTED_TRIGGERS = 104;
    private static final int EXPECTED_REPEATABLE_PINS = 10;
    private static final int EXPECTED_REPEATABLE_GROUPS = 9;
    private static final Map<String, String> OPTION_SOURCE_TYPES = Map.ofEntries(
        Map.entry("server-custom-content-asset", "string"),
        Map.entry("server-custom-content-provider", "string"),
        Map.entry("server-luckperms-group", "permission_group"),
        Map.entry("server-luckperms-permission", "permission"),
        Map.entry("server-luckperms-track", "permission_track"),
        Map.entry("server-minecraft-advancement", "string"),
        Map.entry("server-minecraft-attribute", "string"),
        Map.entry("server-minecraft-block", "material"),
        Map.entry("server-minecraft-difficulty", "difficulty"),
        Map.entry("server-minecraft-entity-boolean-property", "string"),
        Map.entry("server-minecraft-entity-data-property", "string"),
        Map.entry("server-minecraft-entity-location-property", "string"),
        Map.entry("server-minecraft-entity-number-property", "string"),
        Map.entry("server-minecraft-entity-reference-property", "string"),
        Map.entry("server-minecraft-entity-text-property", "string"),
        Map.entry("server-minecraft-entity-type", "entity_type"),
        Map.entry("server-minecraft-entity-vector-property", "string"),
        Map.entry("server-minecraft-entity-writable-data-property", "string"),
        Map.entry("server-minecraft-item-attribute-schema", "string"),
        Map.entry("server-minecraft-item-component-boolean", "string"),
        Map.entry("server-minecraft-item-component-list", "string"),
        Map.entry("server-minecraft-item-component-number", "string"),
        Map.entry("server-minecraft-item-component-object", "string"),
        Map.entry("server-minecraft-item-component-presence", "string"),
        Map.entry("server-minecraft-item-component-text", "string"),
        Map.entry("server-minecraft-material", "material"),
        Map.entry("server-minecraft-particle", "string"),
        Map.entry("server-minecraft-potion-effect", "potion_effect"),
        Map.entry("server-minecraft-sound", "sound"),
        Map.entry("server-resync-advancement-tree", "advancement_tree_id"),
        Map.entry("server-resync-chat", "chat_id"),
        Map.entry("server-resync-command", "command_id"),
        Map.entry("server-resync-custom-content", "custom_content_id"),
        Map.entry("server-resync-dialog", "dialog_id"),
        Map.entry("server-resync-flow", "flow_id"),
        Map.entry("server-resync-function", "function"),
        Map.entry("server-resync-gui", "gui_id"),
        Map.entry("server-resync-loot-table", "loot_table_id"),
        Map.entry("server-resync-message-rule", "message_rule_id"),
        Map.entry("server-resync-motd-profile", "motd_profile_id"),
        Map.entry("server-resync-network-node", "network_node"),
        Map.entry("server-resync-network-node-status", "string"),
        Map.entry("server-resync-network-scope", "network_scope"),
        Map.entry("server-resync-network-server-group", "string"),
        Map.entry("server-resync-network-variable-type", "string"),
        Map.entry("server-resync-npc-definition", "npc_id"),
        Map.entry("server-resync-recipe-definition", "recipe_id"),
        Map.entry("server-resync-resource-kind", "string"),
        Map.entry("server-resync-schedule-definition", "schedule_reference"),
        Map.entry("server-resync-scoreboard", "scoreboard_id"),
        Map.entry("server-resync-structure", "structure"),
        Map.entry("server-resync-tab", "tab_id"),
        Map.entry("server-resync-text-template", "text_template_id"),
        Map.entry("server-resync-time-zone", "string"),
        Map.entry("server-resync-timer-definition", "timer_reference"),
        Map.entry("server-resync-trade-profile", "trade_profile_id"),
        Map.entry("server-resync-variable-definition", "variable_reference"),
        Map.entry("server-resync-world", "string"),
        Map.entry("server-resync-world-generator", "string"),
        Map.entry("server-resync-worldgen", "worldgen_project"),
        Map.entry("server-runtime-data-category", "string"),
        Map.entry("server-runtime-data-source", "string"),
        Map.entry("server-runtime-data-type", "string"));
    private static final Set<String> BUILTIN_OPTION_TYPES = Set.of(
        "difficulty", "entity_type", "material", "network_scope", "permission", "permission_group", "potion_effect",
        "sound", "string");
    private static final RuntimeSemantics TEST_SEMANTICS = testSemantics();

    @Test
    void ingestsAndCompilesEveryDirectProductionCatalogSource() {
        List<SourceFile> files = sourceFiles();
        List<CatalogCategoryDescriptor> categories = categories(files);
        InspectorCapability editor = editor();
        List<CatalogContribution> perFileContributions = files.stream()
            .map(file -> ingest(file, categories, editor))
            .toList();

        assertEquals(EXPECTED_FILES, files.size());
        assertEquals(EXPECTED_FILES, perFileContributions.size());
        assertIngestionParity(files, perFileContributions);

        CatalogContribution combined = CatalogSourceIngestor.combineContributions(perFileContributions);
        assertCombinedParity(perFileContributions, combined);
        List<CatalogContribution> contributions = List.of(combined);

        CatalogBindingProof proof = bindingProof(contributions);
        CatalogCompilationResult ordered = new CatalogCompiler(CONTRACT, proof).compile(contributions, 1);
        assertTrue(ordered.accepted(), diagnostics(ordered));
        CatalogSnapshot orderedSnapshot = ordered.snapshot().orElseThrow();
        assertEquals(1, orderedSnapshot.contributions().size());
        assertEquals(EXPECTED_DEFINITIONS, orderedSnapshot.definitions().size());
        assertEquals(EXPECTED_MIGRATIONS, orderedSnapshot.migrations().size());

        List<CatalogContribution> reversedPerFile = new ArrayList<>(perFileContributions);
        Collections.reverse(reversedPerFile);
        CatalogContribution reversedCombined = CatalogSourceIngestor.combineContributions(reversedPerFile);
        CatalogCompilationResult reversedResult = new CatalogCompiler(CONTRACT, proof)
            .compile(List.of(reversedCombined), 1);
        assertTrue(reversedResult.accepted(), diagnostics(reversedResult));
        CatalogSnapshot reversedSnapshot = reversedResult.snapshot().orElseThrow();
        assertEquals(orderedSnapshot.contentChecksum(), reversedSnapshot.contentChecksum());
        assertEquals(orderedSnapshot.canonicalContent(), reversedSnapshot.canonicalContent());
    }

    private static void assertCombinedParity(List<CatalogContribution> perFileContributions,
                                             CatalogContribution combined) {
        assertEquals(EXPECTED_DEFINITIONS, combined.definitions().size());
        assertEquals(EXPECTED_MIGRATIONS, combined.migrations().size());
        assertEquals(EXPECTED_DEFINITIONS, combined.provenance().entries().size());
        Set<CatalogProvenance.SourceEntry> perFileEntries = perFileContributions.stream()
            .flatMap(value -> value.provenance().entries().stream())
            .collect(Collectors.toSet());
        assertEquals(perFileEntries, new HashSet<>(combined.provenance().entries()));
        Set<String> perFileDefinitionIds = perFileContributions.stream()
            .flatMap(value -> value.definitions().stream())
            .map(value -> value.id().value())
            .collect(Collectors.toSet());
        assertEquals(perFileDefinitionIds, combined.definitions().stream()
            .map(value -> value.id().value()).collect(Collectors.toSet()));
        Set<String> perFileMigrationIds = perFileContributions.stream()
            .flatMap(value -> value.migrations().stream())
            .map(value -> value.id().value())
            .collect(Collectors.toSet());
        assertEquals(perFileMigrationIds, combined.migrations().stream()
            .map(value -> value.id().value()).collect(Collectors.toSet()));
        assertEquals(EXPECTED_FILES, combined.provenance().entries().stream()
            .map(CatalogProvenance.SourceEntry::sourceUri).distinct().count());
    }

    private static void assertIngestionParity(List<SourceFile> files, List<CatalogContribution> contributions) {
        List<Map<String, Object>> authoredRows = files.stream()
            .flatMap(file -> file.rows().stream())
            .toList();
        assertEquals(EXPECTED_DEFINITIONS, authoredRows.size());
        assertEquals(EXPECTED_DEFINITIONS, contributions.stream().mapToInt(value -> value.definitions().size()).sum());
        assertEquals(EXPECTED_PINS, authoredRows.stream().mapToInt(ProductionCatalogSourceIngestionTest::authoredPinCount).sum());
        assertEquals(EXPECTED_PINS, contributions.stream().flatMap(value -> value.definitions().stream())
            .mapToInt(value -> value.pins().size()).sum());
        assertEquals(EXPECTED_MIGRATED_DEFINITIONS, authoredRows.stream().filter(value -> value.containsKey("migrationMapping")).count());
        assertEquals(EXPECTED_MIGRATIONS, authoredRows.stream().mapToInt(value -> authoredMigrations(value).size()).sum());
        assertEquals(EXPECTED_MIGRATIONS, contributions.stream().mapToInt(value -> value.migrations().size()).sum());
        assertEquals(EXPECTED_TRIGGERS, authoredRows.stream().filter(ProductionCatalogSourceIngestionTest::isTrigger).count());
        assertEquals(EXPECTED_REPEATABLE_PINS, contributions.stream().flatMap(value -> value.definitions().stream())
            .flatMap(value -> value.pins().stream()).filter(value -> value.repeatable().groupId() != null).count());
        assertEquals(EXPECTED_REPEATABLE_GROUPS, contributions.stream().flatMap(value -> value.definitions().stream())
            .mapToInt(value -> value.repeatables().size()).sum());

        Map<String, Map<String, Object>> expectedById = new LinkedHashMap<>();
        Set<String> actualIds = new LinkedHashSet<>();
        Set<String> optionSourceIds = new LinkedHashSet<>();
        Map<String, CatalogMigrationEdge> migrationsById = new LinkedHashMap<>();
        for (int fileIndex = 0; fileIndex < files.size(); fileIndex++) {
            SourceFile file = files.get(fileIndex);
            CatalogContribution contribution = contributions.get(fileIndex);
            ContentHash sourceHash = ContentHash.of(CanonicalJson.genericCanonicalContentHash(CanonicalJson.parse(file.bytes())));
            assertEquals("nodes/" + file.name(), contribution.provenance().sourceUri());
            assertEquals(sourceHash, contribution.provenance().sourceHash());
            assertEquals(BUILD_ID, contribution.provenance().buildId());
            assertEquals(file.rows().size(), contribution.provenance().entries().size());
            for (int rowIndex = 0; rowIndex < file.rows().size(); rowIndex++) {
                Map<String, Object> row = file.rows().get(rowIndex);
                String id = string(row, "id");
                assertNull(expectedById.putIfAbsent(id, row), "Duplicate authored node ID: " + id);
                CatalogNodeDescriptor definition = contribution.definitions().get(rowIndex);
                RuntimeOperationDescriptor runtime = contribution.runtimeRequirements().get(rowIndex);
                assertEquals(NodeId.of(id), definition.id());
                assertEquals(integer(row, "schemaVersion"), definition.schemaVersion());
                assertTrue(actualIds.add(id), "Duplicate ingested node ID: " + id);
                assertEquals(OWNER, definition.handler().capability().owner());
                assertEquals(runtime.pins(), runtimePins(definition));
                assertEquals(TEST_SEMANTICS, runtime.semantics());
                assertEquals(TEST_SEMANTICS, definition.semantics());
                assertEquals(new HashSet<>(definition.pins().stream().map(value -> value.id().value()).toList()).size(),
                    definition.pins().size(), "Duplicate descriptor pin identity: " + id);
                CatalogProvenance.SourceEntry entry = contribution.provenanceFor(definition).entries().getFirst();
                assertEquals(rowIndex, entry.rowIndex());
                assertEquals(id, entry.definitionId());
                assertEquals(OWNER, entry.owner());
                assertEquals(sourceHash, entry.sourceHash());
                if (isTrigger(row)) {
                    assertEquals(row.get("eventType"), runtime.unknown().get("eventType"));
                    assertEquals(row.get("handlerConfig"), runtime.unknown().get("handlerConfig"));
                }
                if (id.equals("event.command")) {
                    assertCommandOutputs(definition);
                }
                if (id.equals("flow.switch_case")) {
                    assertSwitchRepeatable(definition);
                }
                for (InspectorOptionSource optionSource : contribution.optionSources()) {
                    optionSourceIds.add(optionSource.id().value());
                }
            }
            for (CatalogMigrationEdge migration : contribution.migrations()) {
                assertNull(migrationsById.putIfAbsent(migration.id().value(), migration),
                    "Duplicate migration identity: " + migration.id().value());
            }
        }
        assertEquals(EXPECTED_DEFINITIONS, expectedById.size());
        assertEquals(expectedById.keySet(), actualIds);
        assertEquals(OPTION_SOURCE_TYPES.keySet(), optionSourceIds);
        assertMigrationParity(expectedById, migrationsById);
        assertCommandHistory(expectedById, migrationsById);
    }

    private static void assertMigrationParity(Map<String, Map<String, Object>> authoredById,
                                              Map<String, CatalogMigrationEdge> migrationsById) {
        Set<String> expectedIds = new LinkedHashSet<>();
        for (Map<String, Object> authored : authoredById.values()) {
            String nodeId = string(authored, "id");
            for (Map<String, Object> mapping : authoredMigrations(authored)) {
                String migrationId = mapping.containsKey("id") ? string(mapping, "id") : "migration." + nodeId;
                assertTrue(expectedIds.add(migrationId), "Duplicate authored migration identity: " + migrationId);
                CatalogMigrationEdge edge = migrationsById.get(migrationId);
                assertNotNull(edge, migrationId);
                assertMigrationEdge(nodeId, mapping, edge);
            }
        }
        assertEquals(expectedIds, migrationsById.keySet());
    }

    private static void assertMigrationEdge(String nodeId, Map<String, Object> mapping, CatalogMigrationEdge edge) {
        assertEquals(integer(mapping, "sourceSchemaVersion"), edge.fromVersion());
        assertEquals(integer(mapping, "targetSchemaVersion"), edge.toVersion());
        assertEquals(nodeId, edge.nodeId().value());
        assertEquals(OWNER, edge.ownerId());
        assertEquals(Boolean.TRUE, mapping.get("complete"));
        assertEquals(CatalogMigrationEdge.Kind.DECLARATIVE, edge.kind());
        assertEquals(CatalogMigrationEdge.ConnectionPolicy.REMAP, edge.connectionPolicy());
        assertEquals(List.of(nodeId), edge.touchedIds());
        assertTrue(edge.quarantineCodes().isEmpty());
        List<?> pins = list(mapping, "pins");
        assertEquals(pins.size(), edge.pinMappings().size());
        Set<String> expectedMappings = new LinkedHashSet<>();
        for (Object pinValue : pins) {
            Map<String, Object> pin = object(pinValue);
            expectedMappings.add(string(pin, "direction").toUpperCase(Locale.ROOT) + "\u0000"
                + string(pin, "source") + "\u0000" + string(pin, "target"));
        }
        Set<String> actualMappings = edge.pinMappings().stream()
            .map(value -> value.direction().name() + "\u0000" + value.source().value() + "\u0000" + value.target().value())
            .collect(Collectors.toCollection(LinkedHashSet::new));
        assertEquals(expectedMappings, actualMappings);
        edge.pinMappings().forEach(pin -> {
            assertEquals(OWNER, pin.ownerId());
            assertEquals(edge.nodeId(), pin.nodeId());
            assertEquals(edge.fromVersion(), pin.sourceSchemaVersion());
            assertEquals(edge.toVersion(), pin.targetSchemaVersion());
            assertEquals(pin.source().value().equals(pin.target().value()), pin.identityMeaningful());
        });
    }

    private static List<Map<String, Object>> authoredMigrations(Map<String, Object> row) {
        List<Map<String, Object>> mappings = new ArrayList<>();
        if (row.containsKey("migrationHistory")) {
            list(row, "migrationHistory").forEach(value -> mappings.add(object(value)));
        }
        if (row.containsKey("migrationMapping")) {
            mappings.add(object(row.get("migrationMapping")));
        }
        return List.copyOf(mappings);
    }

    private static void assertCommandHistory(Map<String, Map<String, Object>> authoredById,
                                             Map<String, CatalogMigrationEdge> migrationsById) {
        assertEquals(Set.of("event.command"), authoredById.values().stream().filter(value -> value.containsKey("migrationHistory"))
            .map(value -> string(value, "id")).collect(Collectors.toSet()));
        Map<String, Object> command = authoredById.get("event.command");
        assertEquals(3, integer(command, "schemaVersion"));
        assertEquals(1, list(command, "migrationHistory").size());
        assertEquals("migration.event.command", string(object(list(command, "migrationHistory").getFirst()), "id"));
        assertEquals("migration.event.command.v2-v3", string(object(command.get("migrationMapping")), "id"));
        CatalogMigrationEdge retained = migrationsById.get("migration.event.command");
        CatalogMigrationEdge current = migrationsById.get("migration.event.command.v2-v3");
        assertEquals(1, retained.fromVersion());
        assertEquals(2, retained.toVersion());
        assertEquals(retained.toVersion(), current.fromVersion());
        assertEquals(3, current.toVersion());
        Set<String> originalPins = Set.of("flow", "event.player", "event.command", "event.is_cancelled");
        for (CatalogMigrationEdge edge : List.of(retained, current)) {
            assertEquals(4, edge.pinMappings().size());
            assertEquals(originalPins, edge.pinMappings().stream().map(pin -> pin.source().value()).collect(Collectors.toSet()));
            assertEquals(originalPins, edge.pinMappings().stream().map(pin -> pin.target().value()).collect(Collectors.toSet()));
            assertTrue(edge.pinMappings().stream().allMatch(pin -> pin.direction() == CatalogNodeDescriptor.Direction.OUTPUT && pin.identityMeaningful()));
        }
    }

    private static void assertCommandOutputs(CatalogNodeDescriptor definition) {
        TypeExpr text = TypeExpr.named(TypeReference.of("builtin", "string"));
        Map<String, TypeExpr> added = Map.of("event.bound_command", text, "event.command_label", text, "event.args", text,
            "event.args_list", TypeExpr.list(text), "event.args_count", TypeExpr.named(TypeReference.of("builtin", "number")),
            "event.is_console", TypeExpr.named(TypeReference.of("builtin", "boolean")));
        assertEquals(10, definition.pins().size());
        assertTrue(definition.pins().stream().allMatch(pin -> pin.direction() == CatalogNodeDescriptor.Direction.OUTPUT));
        Map<String, TypeExpr> actual = definition.pins().stream().filter(pin -> added.containsKey(pin.id().value()))
            .collect(Collectors.toMap(pin -> pin.id().value(), CatalogNodeDescriptor.Pin::type));
        assertEquals(added, actual);
    }

    private static void assertSwitchRepeatable(CatalogNodeDescriptor definition) {
        CatalogNodeDescriptor.RepeatableGroup group = definition.repeatables().getFirst();
        CatalogNodeDescriptor.Pin input = definition.pins().stream()
            .filter(value -> value.id().equals(PinId.of("case"))).findFirst().orElseThrow();
        CatalogNodeDescriptor.Pin output = definition.pins().stream()
            .filter(value -> value.id().equals(PinId.of("output_case"))).findFirst().orElseThrow();

        assertEquals("cases", group.id().value());
        assertEquals("Case", group.title());
        assertEquals(1, group.minimum());
        assertEquals(128, group.maximum());
        assertTrue(group.ordered());
        assertTrue(group.description().length() <= 240);
        assertEquals(List.of(
            new CatalogNodeDescriptor.RepeatableMember(input.id(), CatalogNodeDescriptor.Direction.INPUT, input.type()),
            new CatalogNodeDescriptor.RepeatableMember(output.id(), CatalogNodeDescriptor.Direction.OUTPUT, output.type())),
            group.members());
        assertEquals(group.id(), input.repeatable().groupId());
        assertEquals(group.id(), output.repeatable().groupId());
        assertEquals(TypeExpr.tuple(List.of(input.type(), output.type())), group.elementType());
    }

    private static CatalogBindingProof bindingProof(List<CatalogContribution> contributions) {
        Map<RuntimeBindingKey, ContentHash> fingerprints = new LinkedHashMap<>();
        for (CatalogContribution contribution : contributions) {
            for (RuntimeOperationDescriptor runtime : contribution.runtimeRequirements()) {
                assertNull(fingerprints.putIfAbsent(runtime.key(), runtime.executionFingerprint()),
                    "Duplicate runtime binding identity: " + runtime.key());
            }
        }
        assertEquals(EXPECTED_DEFINITIONS, fingerprints.size());
        return CatalogBindingProof.fixed(fingerprints, ContentHash.of("b".repeat(64)));
    }

    private static CatalogContribution ingest(SourceFile file, List<CatalogCategoryDescriptor> categories,
                                              InspectorCapability editor) {
        CatalogSourceIngestor.CatalogSource source = new CatalogSourceIngestor.CatalogSource(
            OWNER, CatalogProvenance.SourceKind.BUNDLED, "nodes/" + file.name(), "1.0.0", BUILD_ID, file.bytes());
        CatalogSourceIngestor.CatalogIngestionContext context = new CatalogSourceIngestor.CatalogIngestionContext(
            CONTRACT_RANGE, categories, editor, ProductionCatalogSourceIngestionTest::optionSource,
            ProductionCatalogSourceIngestionTest::runtime, ProductionCatalogSourceIngestionTest::resourceType);
        return new CatalogSourceIngestor().ingest(source, context);
    }

    private static Optional<InspectorOptionSource> optionSource(InspectorFieldId id) {
        String resource = OPTION_SOURCE_TYPES.get(id.value());
        if (resource == null) {
            return Optional.empty();
        }
        TypeExpr type = BUILTIN_OPTION_TYPES.contains(resource)
            ? TypeExpr.named(new TypeReference("builtin", resource))
            : TypeExpr.resource(new TypeReference(OWNER.value(), resource));
        return Optional.of(new InspectorOptionSource(id, "Production Catalog Options",
            "Provides deterministic options for the production catalog source.", type, OptionQuerySchemaV1.empty(),
            ContractRef.of(OWNER, CapabilityId.of("catalog-options")), 100));
    }

    private static Optional<ContractRef<ResourceTypeId>> resourceType(ContractRef<ResourceTypeId> resourceType) {
        return Optional.of(resourceType);
    }

    private static Optional<RuntimeOperationDescriptor> runtime(CatalogSourceIngestor.RuntimeRequest request) {
        Map<String, Object> unknown = new LinkedHashMap<>();
        if (request.trigger()) {
            unknown.put("eventType", request.source().get("eventType"));
            unknown.put("handlerConfig", request.handlerConfig());
        }
        return Optional.of(new RuntimeOperationDescriptor(request.capability(), request.operation(), request.pins(),
            TEST_SEMANTICS, unknown));
    }

    private static List<CatalogCategoryDescriptor> categories(List<SourceFile> files) {
        Set<String> ids = new TreeSet<>();
        for (SourceFile file : files) {
            for (Map<String, Object> row : file.rows()) {
                ids.add(string(row, "category").toLowerCase(Locale.ROOT));
            }
        }
        List<CatalogCategoryDescriptor> result = new ArrayList<>();
        int order = 0;
        for (String id : ids) {
            result.add(new CatalogCategoryDescriptor(id, id + " Category",
                "Deterministic category for the authored production catalog.", order++));
        }
        return List.copyOf(result);
    }

    private static InspectorCapability editor() {
        TypeExpr any = TypeExpr.named(new TypeReference("builtin", "any"));
        return new InspectorCapability(ContractRef.of(OWNER, CapabilityId.of("generic-editor")), "Production Catalog Editor",
            "Provides the deterministic editor capability for authored production catalog pins.", new InspectorValueSchema(any),
            new InspectorValueSchema(any), List.of(), ContractRef.of(OWNER, CapabilityId.of("generic-editor")),
            InspectorFallback.GENERIC);
    }

    private static RuntimeSemantics testSemantics() {
        TypeExpr payload = TypeExpr.named(new TypeReference("builtin", "any"));
        return new RuntimeSemantics(RuntimeSemantics.Effect.PURE, RuntimeSemantics.ThreadMode.CURRENT,
            ContractRef.of(OWNER, CapabilityId.of("catalog-runtime")), RuntimeSemantics.Cancellation.NONE,
            0, 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER,
            RuntimeSemantics.Idempotency.INTRINSIC, RuntimeSemantics.Audit.NONE,
            RuntimeSemantics.Confirmation.NONE, RuntimeSemantics.SensitiveData.NONE,
            RuntimeSemantics.Determinism.DETERMINISTIC, Set.of(), Set.of("failed"), Set.of(),
            new RuntimeFailureContract(payload, Set.of("RUNTIME.FAILURE"), Set.of("failed"),
                RuntimeFailureContract.CommitBoundary.NO_MUTATION), Set.of(), Set.of());
    }

    private static List<SourceFile> sourceFiles() {
        try (Stream<Path> paths = Files.list(NODE_ROOT)) {
            return paths.filter(Files::isRegularFile)
                .filter(value -> value.getFileName().toString().endsWith(".json"))
                .sorted()
                .map(ProductionCatalogSourceIngestionTest::sourceFile)
                .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static SourceFile sourceFile(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            return new SourceFile(path.getFileName().toString(), bytes, authoredRows(bytes));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static List<Map<String, Object>> authoredRows(byte[] bytes) {
        Object parsed = CanonicalJson.parse(bytes);
        if (parsed instanceof Map<?, ?> map) {
            return List.of(object(map));
        }
        if (parsed instanceof List<?> list && !list.isEmpty()) {
            return list.stream().map(ProductionCatalogSourceIngestionTest::object).toList();
        }
        throw new IllegalArgumentException("Production catalog source must be a nonempty object or array");
    }

    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Production catalog value must be an object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("Production catalog object keys must be strings");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static int authoredPinCount(Map<String, Object> row) {
        return listValue(row, "inputs").size() + listValue(row, "outputs").size();
    }

    private static boolean isTrigger(Map<String, Object> row) {
        return Boolean.TRUE.equals(row.get("trigger"));
    }

    private static List<?> listValue(Map<String, Object> value, String key) {
        Object raw = value.get(key);
        return raw instanceof List<?> list ? list : List.of();
    }

    private static List<?> list(Map<String, Object> value, String key) {
        Object raw = value.get(key);
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("Expected list: " + key);
        }
        return list;
    }

    private static String string(Map<String, Object> value, String key) {
        Object raw = value.get(key);
        if (!(raw instanceof String text)) {
            throw new IllegalArgumentException("Expected string: " + key);
        }
        return text;
    }

    private static int integer(Map<String, Object> value, String key) {
        Object raw = value.get(key);
        if (!(raw instanceof Number number)) {
            throw new IllegalArgumentException("Expected number: " + key);
        }
        return number.intValue();
    }

    private static List<RuntimeOperationDescriptor.Pin> runtimePins(CatalogNodeDescriptor definition) {
        return definition.pins().stream().map(value -> new RuntimeOperationDescriptor.Pin(value.id(),
            value.direction() == CatalogNodeDescriptor.Direction.INPUT
                ? RuntimeOperationDescriptor.Direction.INPUT : RuntimeOperationDescriptor.Direction.OUTPUT,
            value.type())).toList();
    }

    private static String diagnostics(CatalogCompilationResult result) {
        return result.diagnostics().stream().map(Object::toString).reduce((left, right) -> left + "\n" + right).orElse("");
    }

    private record SourceFile(String name, byte[] bytes, List<Map<String, Object>> rows) {
        private SourceFile {
            name = Objects.requireNonNull(name, "source file name");
            bytes = bytes.clone();
            rows = List.copyOf(rows);
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
