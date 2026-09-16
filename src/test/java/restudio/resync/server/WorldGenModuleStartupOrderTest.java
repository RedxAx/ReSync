package restudio.resync.server;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.modules.FlowRuntimeModule;
import restudio.resync.modules.Module;
import restudio.resync.modules.ModuleRegistry;
import restudio.resync.modules.WorldGenModule;
import restudio.resync.worldgen.registry.WorldGenFlowCatalogContribution;
import restudio.resync.worldgen.registry.WorldGenFlowCatalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGenModuleStartupOrderTest {
    @Test
    void worldGenContributionIsAvailableBeforeFlowRuntimeCatalogActivation() throws IOException {
        List<Module> modules = ReSyncServer.coreModules();
        ModuleRegistry registry = new ModuleRegistry();
        modules.forEach(registry::registerModule);
        List<String> order = registry.getInitializationOrder();

        assertTrue(order.indexOf("worldGen") >= 0);
        assertTrue(order.indexOf("flow") >= 0);
        assertTrue(order.indexOf("worldGen") < order.indexOf("flow"));

        WorldGenModule worldGen = modules.stream()
            .filter(WorldGenModule.class::isInstance)
            .map(WorldGenModule.class::cast)
            .findFirst()
            .orElseThrow();
        FlowRuntimeModule flow = modules.stream()
            .filter(FlowRuntimeModule.class::isInstance)
            .map(FlowRuntimeModule.class::cast)
            .findFirst()
            .orElseThrow();
        assertFalse(worldGen.getMetadata().dependencies().contains("flow"));
        assertTrue(flow.getMetadata().dependencies().contains("worldGen"));

        HandlerRegistry handlers = new HandlerRegistry();
        NodeDefinitionRegistry staged = new NodeDefinitionRegistry(false);
        WorldGenFlowCatalogContribution contribution = WorldGenFlowCatalogContribution.create();
        contribution.apply(staged, handlers);
        NodeDefinition simplex = staged.get("worldgen", "simplex");
        assertTrue(contribution.definitions().contains(simplex));
        assertEquals("worldgen", simplex.getOwner());
        assertEquals("worldgen", staged.getPluginForNode(simplex));
        assertTrue(handlers.hasOperation(WorldGenFlowCatalog.HANDLER_ID, "worldgen_node_simplex"));

        String worldGenSource = Files.readString(Path.of("src/main/java/restudio/resync/modules/WorldGenModule.java"));
        String flowSource = Files.readString(Path.of("src/main/java/restudio/resync/modules/FlowRuntimeModule.java"));
        String serverSource = Files.readString(Path.of("src/main/java/restudio/resync/server/ReSyncServer.java"));
        int contributionRegistration = worldGenSource.indexOf("registerService(WorldGenFlowCatalogContribution.class");
        int contributionLookup = flowSource.indexOf("getRequiredService(WorldGenFlowCatalogContribution.class)");
        int contributionApplication = flowSource.indexOf("worldGenCatalog.apply", contributionLookup);
        int activationDecision = flowSource.indexOf("replacementStartupDecision", contributionApplication);
        assertTrue(contributionRegistration >= 0);
        assertTrue(contributionLookup >= 0);
        assertTrue(contributionApplication > contributionLookup);
        assertTrue(activationDecision > contributionApplication);
        assertTrue(worldGenSource.contains("WorldGenGeneratedOutputController"));
        assertTrue(worldGenSource.contains("WorldGenGeneratedPersistenceParticipant"));
        String operationSource = Files.readString(Path.of("src/main/java/restudio/resync/worldgen/WorldGenOperationService.java"));
        String previewSource = Files.readString(Path.of("src/main/java/restudio/resync/worldgen/preview/WorldGenPreviewManager.java"));
        String worldManagementSource = Files.readString(Path.of("src/main/java/restudio/resync/world/WorldManagementManager.java"));
        assertTrue(operationSource.contains("generatedOutput.acquireHandoff"));
        assertTrue(previewSource.contains("generatedOutput.acquireHandoff"));
        assertTrue(worldManagementSource.contains("generatedOutput.acquireHandoff"));
        assertFalse(operationSource.contains("compiler.generatedRoot()"));
        assertFalse(previewSource.contains("datapackCompiler.generatedRoot()"));
        assertFalse(worldManagementSource.contains("worldGenDatapackCompiler.generatedRoot()"));
        assertEquals(1, count(worldGenSource, "new WorldGenDatapackInstaller("));
        assertTrue(worldGenSource.contains("registerService(WorldGenInstalledDatapackCapability.class, installer.capability())"));
        assertTrue(operationSource.contains("WorldGenInstalledDatapackCapability installer"));
        assertTrue(previewSource.contains("WorldGenInstalledDatapackCapability datapackInstaller"));
        assertTrue(worldManagementSource.contains("WorldGenInstalledDatapackCapability worldGenDatapackInstaller"));
        assertFalse(operationSource.contains("new WorldGenDatapackInstaller("));
        assertFalse(previewSource.contains("new WorldGenDatapackInstaller("));
        assertFalse(worldManagementSource.contains("new WorldGenDatapackInstaller("));
        assertFalse(worldGenSource.contains("FlowRuntimeModule"));
        assertFalse(worldGenSource.contains("FlowModule"));
        assertTrue(worldGenSource.contains("context.getRequiredService(AuthorityEpoch.class)"));
        assertTrue(worldGenSource.contains("RESPONSE_AUTHORITY_EPOCH_FIELD"));
        assertTrue(worldGenSource.contains("RESPONSE_REVISION_FIELD"));
        assertTrue(worldGenSource.contains("RESPONSE_DATA_FIELD"));
        assertTrue(worldGenSource.contains("if (revision < 1L)"));
        assertTrue(worldGenSource.contains("if (responseEpoch < 1L)"));
        assertTrue(worldGenSource.contains("if (responseEpoch != authorityEpoch.current())"));
        assertTrue(worldGenSource.contains("envelope.addProperty(RESPONSE_AUTHORITY_EPOCH_FIELD, responseEpoch)"));
        assertTrue(worldGenSource.contains("envelope.addProperty(RESPONSE_REVISION_FIELD, revision)"));
        assertTrue(worldGenSource.contains("envelope.add(RESPONSE_DATA_FIELD, responseData(json))"));
        assertFalse(worldGenSource.contains("AuthorityEpoch.fixed(0L)"));
        assertTrue(worldGenSource.contains("generatedOutputRebuilder.persistCurrentRecipe()"));
        assertTrue(worldGenSource.contains("generatedOutputController.rebuildForStartup(context, () -> {"));
        assertTrue(worldGenSource.contains("if (pendingOwners.isEmpty())"));
        assertTrue(worldGenSource.contains("strictProof = persistence.completeStartupActivation(proof, pendingOwners);"));
        assertTrue(worldGenSource.contains("if (generatedPending)"));
        assertFalse(worldGenSource.contains("ensureGeneratedOutputStartupReady()"));
        assertFalse(worldGenSource.contains("installedDatapackParticipant.resume()"));
        assertFalse(worldGenSource.contains("installedDatapackParticipant.healthCheck()"));
        assertFalse(worldGenSource.contains("installedDatapackParticipant.readinessCheck()"));
        assertTrue(worldGenSource.contains("ReSyncPersistenceCoordinator.ReadinessProof strictProof"));
        assertFalse(worldGenSource.contains("generated output will remain unavailable"));
        assertFalse(worldGenSource.contains("persistence.validateRestoreReadiness(readiness)"));
        assertTrue(serverSource.contains("currentValidatedReadinessProof(registration.readiness())"));
        assertTrue(serverSource.contains("ReSync persistence topology is not restore-ready"));
        int strictTransition = worldGenSource.indexOf("persistence.completeStartupActivation(proof, pendingOwners, context -> {");
        int preparationCallback = worldGenSource.indexOf("context -> {", strictTransition);
        int rootProof = worldGenSource.indexOf("context.requireActiveRoot(generatedOutputController.scopeRoot())", strictTransition);
        int recipeReconciliation = worldGenSource.indexOf("generatedOutputRebuilder.persistCurrentRecipe()", rootProof);
        int atomicRebuild = worldGenSource.indexOf("generatedOutputController.rebuildForStartup(context, () -> {", rootProof);
        int globalDerivedReadiness = worldGenSource.indexOf("requireWorldGenDerivedStartupReady(strictProof.readiness())", atomicRebuild);
        int strictActivationComplete = worldGenSource.indexOf("activationReady = System.nanoTime()", atomicRebuild);
        int runtimeStart = worldGenSource.indexOf("runtimeListener.start()");
        assertTrue(strictTransition >= 0);
        assertTrue(preparationCallback > strictTransition);
        assertTrue(rootProof > preparationCallback);
        assertTrue(atomicRebuild > rootProof);
        assertTrue(recipeReconciliation > atomicRebuild);
        assertTrue(globalDerivedReadiness > atomicRebuild);
        assertTrue(strictActivationComplete > globalDerivedReadiness);
        assertTrue(runtimeStart > strictActivationComplete);
        assertEquals(1, count(worldGenSource, "runtimeListener.start()"));
        assertFalse(worldGenSource.substring(preparationCallback, strictActivationComplete).contains("runtimeListener.start()"));
    }

    private int count(String source, String value) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(value, offset)) >= 0) {
            count++;
            offset += value.length();
        }
        return count;
    }
}
