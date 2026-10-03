package restudio.resync.flow;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.command.CommandGraphContract;
import restudio.resync.flow.command.CommandGraphMetadata;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionParameterContract;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.FunctionBinding;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.GraphVariable;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.graph.PinValue;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;
import restudio.resync.storage.AssetTransactionCoordinator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypedCommandGraphAdapterTest {
    @TempDir
    Path tempDir;

    @Test
    void indexesCommandIdentityAndMetadataWithoutLegacyBindings() {
        FlowGraph graph = graph("restart", "restart");
        graph.getOpaqueProperties().put("description", new JsonPrimitive("Restart server"));
        graph.getOpaqueProperties().put("permission", new JsonPrimitive("resync.restart"));
        graph.getOpaqueProperties().put("aliases", new JsonArray());

        TypedCommandGraphAdapter.Snapshot snapshot = TypedCommandGraphAdapter.index(
            Map.of("restart", graph), Map.of());

        assertEquals(1, snapshot.activeBindings().size());
        TypedCommandGraphAdapter.CommandBinding binding = snapshot.activeBindings().getFirst();
        assertEquals("restart:command:start", binding.bindingId());
        assertEquals("restart", binding.command());
        assertEquals("resync.restart", binding.permission());
        assertEquals("Restart server", binding.description());
    }

    @Test
    void rejectsDuplicateCommandLabelsAtomically() {
        FlowGraph first = graph("first", "shared");
        FlowGraph second = graph("second", "shared");

        TypedCommandGraphAdapter.Snapshot snapshot = TypedCommandGraphAdapter.index(
            new LinkedHashMap<>(Map.of("first", first, "second", second)), Map.of());

        assertTrue(snapshot.activeBindings().isEmpty());
        assertEquals(2, snapshot.rejections().stream()
            .filter(rejection -> "COMMAND_LABEL_COLLISION".equals(rejection.code())).count());
        assertThrows(IllegalArgumentException.class, () -> {
            try (AssetTransactionCoordinator coordinator = coordinator()) {
                FlowStorage storage = new FlowStorage(tempDir.toFile(), coordinator);
                storage.saveGraph(first);
                storage.saveGraph(second);
            }
        });
    }

    @Test
    void rejectsMalformedCommandGraphFailClosed() {
        FlowGraph graph = new FlowGraph();
        graph.setId("malformed");
        graph.setResourceType("command");
        graph.setNodes(new HashMap<>(Map.of(
            "first", new FlowNode("event.resync.command", 0, 0, Map.of()),
            "second", new FlowNode("event.resync.command", 0, 0, Map.of())
        )));

        TypedCommandGraphAdapter.Snapshot snapshot = TypedCommandGraphAdapter.index(
            Map.of("malformed", graph), Map.of());

        assertTrue(snapshot.activeBindings().isEmpty());
        assertEquals("COMMAND_GRAPH_INVALID", snapshot.rejections().getFirst().code());
    }

    @Test
    void preservesStructuredPathsAndAliases() {
        FlowGraph graph = graph("admin", "admin");
        graph.getNodes().get("start").setInputValues(Map.of(
            "aliases", List.of("staff"),
            "subcommands", List.of("reload <any>"),
            "structured", true
        ));

        TypedCommandGraphAdapter.CommandBinding binding = TypedCommandGraphAdapter.read(graph);

        assertEquals(List.of("staff"), binding.aliases());
        assertEquals(List.of("reload <any>"), binding.subcommands());
        assertEquals(List.of(List.of("reload", "<any>")), binding.commandPaths());
        assertTrue(binding.structured());
    }

    @Test
    void commandMetadataIsDeeplyImmutable() {
        FlowGraph graph = graph("admin", "admin");
        graph.setContentProperties(Map.of("metadata", Map.of("nested", List.of("value"))));

        TypedCommandGraphAdapter.CommandBinding binding = TypedCommandGraphAdapter.read(graph);

        assertThrows(UnsupportedOperationException.class,
            () -> ((List<?>) binding.metadata().get("nested")).clear());
    }

    @Test
    void recognizesEveryDeclaredCommandStartAlias() {
        List<String> aliases = new ArrayList<>();
        aliases.addAll(CommandGraphContract.CANONICAL_SERIALIZED_STARTS);
        aliases.addAll(CommandGraphContract.LEGACY_SERIALIZED_STARTS);

        for (String alias : aliases) {
            FlowGraph graph = graph("alias", "alias");
            graph.getNodes().get("start").setType(alias);

            assertEquals("alias", TypedCommandGraphAdapter.read(graph).command());
        }
    }

    @Test
    void materializesCommandMetadataIntoExplicitContentProperties() {
        ServerResourceLocator resource = new ServerResourceLocator(
            new ServerId(UUID.fromString("55555555-5555-4555-8555-555555555555")),
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("command")), "admin");
        GraphNode start = new GraphNode(NodeInstanceId.of(UUID.fromString("66666666-6666-4666-8666-666666666666")),
            CommandGraphContract.CANONICAL_START, 1, Map.of());
        GraphDocument document = new GraphDocument(new CatalogVersion(1, 0), resource, 1L,
            new CatalogBinding(1L, new ContentHash("a".repeat(64)), new ContentHash("b".repeat(64))),
            Set.of(), List.of(start), List.of(), List.of(), List.of(), OpaqueData.of(Map.of("future", "preserved")));
        document = new CommandGraphMetadata("/Admin", true, List.of("reload   <target>")).apply(document);

        FlowGraph graph = TypedCommandGraphAdapter.materialize(document, true,
            "77777777-7777-4777-8777-777777777777");

        assertEquals(Map.of("commandLabel", "admin", "structured", true,
            "commandPaths", List.of("reload <target>")), graph.getContentProperties());
        assertFalse(graph.getContentProperties().containsKey("future"));
        assertTrue(graph.getOpaqueProperties().isEmpty());
        assertEquals("admin", TypedCommandGraphAdapter.read(graph).command());
    }

    @Test
    void typedCommandHeadersRetainPermissionsAliasesAndPathsWithoutProjectingFunctionBindings() {
        ServerId server = ServerId.deterministic("typed-command-header-test");
        ServerResourceLocator resource = new ServerResourceLocator(server,
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("command")), "admin");
        ServerResourceLocator child = new ServerResourceLocator(server,
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("function")), "child");
        GraphNode start = new GraphNode(NodeInstanceId.deterministic("typed-command-header-start"), CommandGraphContract.CANONICAL_START, 3, Map.of());
        FunctionBinding dependency = new FunctionBinding(child, 5L, List.of(), List.of());
        GraphDocument document = new CommandGraphMetadata("admin", true, List.of("reload <target>")).apply(new GraphDocument(
            new CatalogVersion(1, 0), resource, 1L, new CatalogBinding(1L, new ContentHash("a".repeat(64)), new ContentHash("b".repeat(64))),
            Set.of(), List.of(start), List.of(), List.of(), List.of(), List.of(dependency), OpaqueData.of(Map.of(
                "permission", "resync.admin", "command_aliases", List.of("staff"), "permission_message", "Permission Required",
                "command_description", "Runs the administration command.", "command_usage", "/admin reload <target>"))));

        TypedCommandGraphAdapter.CommandBinding binding = TypedCommandGraphAdapter.read(document, true);

        assertEquals("admin", binding.command());
        assertEquals("resync.admin", binding.permission());
        assertEquals("Permission Required", binding.permissionMessage());
        assertEquals(List.of("staff"), binding.aliases());
        assertEquals(List.of(List.of("reload", "<target>")), binding.commandPaths());
        assertTrue(binding.structured());
        assertEquals(dependency, document.functions().getFirst());
        assertThrows(IllegalStateException.class, () -> TypedCommandGraphAdapter.materialize(document, true, "mutation"));
        TypedCommandGraphAdapter.CommandBinding competing = TypedCommandGraphAdapter.read(graph("other", "staff"));
        assertTrue(TypedCommandGraphAdapter.indexBindings(List.of(binding, competing), List.of()).activeBindings().isEmpty());
        assertEquals(2, TypedCommandGraphAdapter.indexBindings(List.of(binding, competing), List.of()).rejections().size());
        assertFalse(TypedCommandGraphAdapter.read(document, false).enabled());
        GraphDocument ambiguous = new GraphDocument(document.schemaVersion(), resource, 1L, document.catalogBinding(), Set.of(),
            document.nodes(), List.of(), List.of(), List.of(), List.of(dependency),
            document.unknown().with("permissionMessage", "Conflicting Permission Message"));
        assertThrows(IllegalArgumentException.class, () -> TypedCommandGraphAdapter.read(ambiguous, true));
        GraphDocument malformed = new GraphDocument(document.schemaVersion(), resource, 1L, document.catalogBinding(), Set.of(),
            List.of(start, new GraphNode(NodeInstanceId.deterministic("second-command-start"), CommandGraphContract.CANONICAL_START, 2, Map.of())),
            List.of(), List.of(), List.of(), List.of(dependency), document.unknown());
        assertThrows(IllegalArgumentException.class, () -> TypedCommandGraphAdapter.read(malformed, true));
    }

    @Test
    void materializesCoreVariablesAndDistinguishesNullFromAbsent() {
        TypeExpr string = TypeExpr.named(TypeReference.of("builtin", "string"));
        PinId nullable = PinId.of("nullable");
        PinId absent = PinId.of("absent");
        GraphNode node = new GraphNode(NodeInstanceId.of(UUID.fromString("22222222-2222-4222-8222-222222222222")),
            ContractRef.of(OwnerId.of("restudio.resync"), NodeId.of("logic.true")), 1,
            Map.of(nullable, new PinValue(nullable, TypedValue.nullValue(string)),
                absent, new PinValue(absent, TypedValue.absent(string))));
        ServerResourceLocator resource = new ServerResourceLocator(
            new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111")),
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("flow")), "core-values");
        GraphVariable variable = new GraphVariable(UUID.fromString("33333333-3333-4333-8333-333333333333"),
            "message", string, TypedValue.value(string, "ready"));
        GraphDocument document = new GraphDocument(new CatalogVersion(1, 0), resource, 1L,
            new CatalogBinding(1L, new ContentHash("a".repeat(64)), new ContentHash("b".repeat(64))),
            Set.of(), List.of(node), List.of(), List.of(variable), List.of(), OpaqueData.empty());

        FlowGraph graph = TypedCommandGraphAdapter.materialize(document, true,
            "44444444-4444-4444-8444-444444444444");

        Map<String, Object> values = graph.getNodes().values().iterator().next().getInputValues();
        assertTrue(values.containsKey("nullable"));
        assertEquals(null, values.get("nullable"));
        assertTrue(!values.containsKey("absent"));
        assertEquals("message", graph.getLocalVariables().getFirst().getName());
        assertEquals("string", graph.getLocalVariables().getFirst().getType());
        assertEquals("ready", graph.getLocalVariables().getFirst().getInitialValue());
    }

    @Test
    void materializesFunctionParameterNamesWidgetsAndDefaults() {
        ServerResourceLocator resource = new ServerResourceLocator(
            new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111")),
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("function")), "predicate");
        CatalogBinding binding = new CatalogBinding(1L, new ContentHash("a".repeat(64)),
            new ContentHash("b".repeat(64)));
        GraphDocument document = new GraphDocument(new CatalogVersion(1, 0), resource, 1L, binding,
            Set.of(), List.of(), List.of(), List.of(), List.of(), OpaqueData.empty());
        TypeExpr player = TypeExpr.named(TypeReference.of("builtin", "player"));
        TypeExpr bool = TypeExpr.named(TypeReference.of("builtin", "boolean"));
        FunctionParameterContract input = new FunctionParameterContract(FunctionParameterId.deterministic("player"),
            player, true, null, Map.of("name", "player", "widget", "player", "optionsSource", "online_players"));
        FunctionParameterContract output = new FunctionParameterContract(FunctionParameterId.deterministic("result"),
            bool, false, TypedValue.value(bool, false), Map.of("name", "result", "widget", "boolean"));
        FunctionSignature signature = new FunctionSignature(new FunctionLocator(resource), FunctionRevision.of(1),
            List.of(input), List.of(output));

        FlowGraph graph = TypedCommandGraphAdapter.materialize(document,
            new FunctionSourceDocument(signature, document), true,
            "44444444-4444-4444-8444-444444444444");

        assertEquals("player", graph.getFunctionInputs().getFirst().getName());
        assertEquals("player", graph.getFunctionInputs().getFirst().getWidget());
        assertEquals("online_players", graph.getFunctionInputs().getFirst().getOptionsSource());
        assertEquals("result", graph.getFunctionOutputs().getFirst().getName());
        assertEquals("false", graph.getFunctionOutputs().getFirst().getDefaultValue());
    }

    private AssetTransactionCoordinator coordinator() {
        try {
            return AssetTransactionCoordinator.open(tempDir.resolve("assets"), new Gson());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create the test asset coordinator", exception);
        }
    }

    private FlowGraph graph(String id, String command) {
        FlowGraph graph = new FlowGraph();
        graph.setId(id);
        graph.setResourceType("command");
        graph.setNodes(new HashMap<>(Map.of(
            "start", new FlowNode("event.resync.command", 0, 0, Map.of("command", command))
        )));
        return graph;
    }
}
