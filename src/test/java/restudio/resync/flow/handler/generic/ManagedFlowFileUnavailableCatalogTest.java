package restudio.resync.flow.handler.generic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowOperationResult;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.ActiveNodeDefinitionSource;
import restudio.resync.flow.registry.NodeDefinitionDiagnostic;
import restudio.resync.flow.registry.NodeDefinitionLoader;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.registry.NodeDefinitionValidator;
import restudio.resync.migration.PersistenceRootReadiness;
import restudio.resync.migration.ReSyncPersistenceCoordinator;
import restudio.resync.server.ReSyncPersistenceTopology;
import restudio.resync.server.ReSyncUncoveredWriterInventory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedFlowFileUnavailableCatalogTest {
    @TempDir
    Path temporary;

    @Test
    void unavailableStorageKeepsFileCatalogValidAndFailsOnlyFileExecution() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync"));
        ManagedFlowFileCapability unavailable = ManagedFlowFileCapability.unavailable(dataRoot,
            new IllegalStateException("corrupt managed flow-file database"));
        HandlerRegistry handlers = new HandlerRegistry();
        new FileHandler(unavailable).registerTo(handlers);
        new GenericMathHandler().registerTo(handlers);

        assertNotNull(handlers.getHandler("FileHandler"));
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        Path fileDefinitionSource = ActiveNodeDefinitionSource.root().resolve("file.json");
        List<NodeDefinition> fileDefinitions;
        try (var input = Files.newInputStream(fileDefinitionSource)) {
            fileDefinitions = loader.parse(input, fileDefinitionSource.toString()).stream()
                .filter(definition -> "FileHandler".equals(definition.getHandler()))
                .toList();
        }
        loader.setValidator(new NodeDefinitionValidator(handlers, true));
        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry(false);
        loader.validateAndRegister(fileDefinitions, definitions, handlers, "compatibility");

        assertFalse(fileDefinitions.isEmpty());
        assertEquals(fileDefinitions.size(), definitions.getDefinitionsForPlugin("compatibility").size());
        assertTrue(loader.getDiagnostics().stream().noneMatch(
            diagnostic -> diagnostic.severity() == NodeDefinitionDiagnostic.Severity.ERROR));

        TestFlowContext fileContext = new TestFlowContext(Map.of());
        NodeDefinition existsDefinition = fileDefinitions.stream()
            .filter(definition -> "file_exists".equals(definition.getId())).findFirst().orElseThrow();
        FlowNode fileNode = new FlowNode(existsDefinition.getId(), 0, 0, Map.of());
        fileNode.setHandlerConfig(existsDefinition.getHandlerConfig());
        handlers.getHandler("FileHandler").execute(fileContext, fileNode);

        FlowOperationResult<?> result = (FlowOperationResult<?>) fileContext.outputs.get("result");
        assertNotNull(result);
        assertFalse(result.success());
        assertEquals("FILE_CAPABILITY_UNAVAILABLE", result.errorCode());

        TestFlowContext mathContext = new TestFlowContext(Map.of("value", -4.0));
        FlowNode mathNode = new FlowNode("math.abs", 0, 0, Map.of());
        mathNode.setHandlerConfig(Map.of("operation", "abs"));
        handlers.getHandler("GenericMathHandler").execute(mathContext, mathNode);
        assertEquals(4.0, mathContext.outputs.get("absolute"));
    }

    @Test
    void unavailableStorageLeavesPersistenceReadinessFailClosed() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("readiness"));
        Path flowRoot = Files.createDirectory(dataRoot.resolve(ManagedFlowFileCapability.ROOT_DIRECTORY));
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(
            dataRoot, temporary.resolve("coordination"));
        String unavailableReason = "Managed flow-file capability is unavailable; File nodes remain registered but fail closed; "
            + "If legacy physical FileHandler bytes are present, they require an explicit manifest-backed offline migration";

        ReSyncPersistenceTopology.Registration registration = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(ReSyncPersistenceTopology.unavailable(
                ManagedFlowFilePersistenceParticipant.OWNER,
                flowRoot,
                unavailableReason)),
            ReSyncUncoveredWriterInventory.forDataRoot(dataRoot));

        assertFalse(registration.sealed());
        assertTrue(registration.readiness().unavailableOwners().stream()
            .map(PersistenceRootReadiness.Owner::owner)
            .anyMatch(ManagedFlowFilePersistenceParticipant.OWNER::equals));
        assertEquals(unavailableReason,
            registration.readiness().owner(ManagedFlowFilePersistenceParticipant.OWNER).reason());
        assertEquals(unavailableReason,
            registration.unavailableReasons().get(ManagedFlowFilePersistenceParticipant.OWNER));
        assertFalse(registration.readiness().complete());
    }

    private static final class TestFlowContext extends FlowContext {
        private final Map<String, Object> inputs;
        private final Map<String, Object> outputs = new HashMap<>();

        private TestFlowContext(Map<String, Object> inputs) {
            super(null, null, null);
            this.inputs = inputs;
        }

        @Override
        public <T> T getInputValue(FlowNode node, String pinName, Class<T> type, T defaultValue) {
            Object value = inputs.get(pinName);
            return value == null ? defaultValue : type.cast(value);
        }

        @Override
        public void setOutput(FlowNode node, String pinName, Object value) {
            outputs.put(pinName, value);
        }

        @Override
        public void triggerOutput(String pinName) {
        }

        @Override
        public CompletableFuture<Void> runAsync(Runnable action) {
            action.run();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> runSync(Runnable action) {
            action.run();
            return CompletableFuture.completedFuture(null);
        }
    }
}
