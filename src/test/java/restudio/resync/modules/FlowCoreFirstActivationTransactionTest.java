package restudio.resync.modules;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.catalog.CatalogBindingProof;
import restudio.resync.flow.catalog.CatalogCompiler;
import restudio.resync.flow.catalog.CatalogRuntimeActivation;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.runtime.RuntimeRegistrySnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowCoreFirstActivationTransactionTest {
    @Test
    void authoredCoreContributionCommitsBeforeDetachedCompatibilityProjection() {
        ActivationFixture fixture = fixture("startup", false);

        assertTrue(fixture.transaction().commit());

        assertEquals(List.of("startup:core-commit", "startup:projection-commit", "startup:core-finalize"), fixture.events());
        assertEquals(fixture.candidateCore(), fixture.core().snapshot());
        assertEquals(fixture.candidateCompatibility(), snapshot(fixture.compatibility()));
        assertTrue(fixture.transaction().commit());
        assertEquals(List.of("startup:core-commit", "startup:projection-commit", "startup:core-finalize"), fixture.events());
    }

    @Test
    void projectionFailureRestoresExactCoreAndCompatibilitySnapshotsForStartupAndReload() {
        for (String phase : List.of("startup", "reload")) {
            ActivationFixture fixture = fixture(phase, true);

            assertThrows(IllegalStateException.class, () -> fixture.transaction().commit());

            assertEquals(List.of(phase + ":core-commit", phase + ":projection-commit",
                phase + ":core-rollback", phase + ":projection-rollback"), fixture.events());
            assertEquals(fixture.previousCore(), fixture.core().snapshot());
            assertEquals(fixture.previousCompatibility(), snapshot(fixture.compatibility()));
            assertTrue(fixture.transaction().compensated());
            assertFalse(fixture.transaction().compensationFailed());
        }
    }

    @Test
    void rejectedCoreCompensationDoesNotRollBackTheCandidateProjection() {
        List<String> events = new ArrayList<>();
        FlowModule.CoreFirstActivationTransaction transaction = new FlowModule.CoreFirstActivationTransaction(
            () -> {
                events.add("core-commit");
                return true;
            },
            () -> {
                events.add("core-rollback");
                return false;
            },
            () -> {
                events.add("projection-commit");
                throw new IllegalStateException("Projection failed");
            },
            () -> events.add("projection-rollback"));

        IllegalStateException failure = assertThrows(IllegalStateException.class, transaction::commit);

        assertEquals(List.of("core-commit", "projection-commit", "core-rollback"), events);
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("Core catalog rollback was rejected", failure.getSuppressed()[0].getMessage());
        assertFalse(transaction.compensated());
        assertTrue(transaction.compensationFailed());
    }

    @Test
    void reversibleFinalizationFailureRestoresCoreAndProjection() {
        List<String> events = new ArrayList<>();
        FlowModule.CoreFirstActivationTransaction transaction = new FlowModule.CoreFirstActivationTransaction(
            () -> {
                events.add("core-commit");
                return true;
            },
            () -> {
                events.add("core-rollback");
                return true;
            },
            () -> events.add("projection-commit"),
            () -> events.add("projection-rollback"),
            () -> {
                events.add("core-finalize");
                throw new IllegalStateException("Finalization failed");
            });

        assertThrows(IllegalStateException.class, transaction::commit);

        assertEquals(List.of("core-commit", "projection-commit", "core-finalize", "core-rollback",
            "projection-rollback"), events);
        assertTrue(transaction.compensated());
        assertFalse(transaction.compensationFailed());
    }

    @Test
    void irreversibleSettlementRunsBeforeProjectionAndFencesAnyLaterFailure() {
        List<String> events = new ArrayList<>();
        FlowModule.CoreFirstActivationTransaction transaction = new FlowModule.CoreFirstActivationTransaction(
            () -> {
                events.add("core-commit");
                return true;
            },
            () -> {
                events.add("core-rollback");
                return true;
            },
            () -> events.add("settlement"),
            () -> {
                events.add("projection-commit");
                throw new IllegalStateException("Projection failed");
            },
            () -> events.add("projection-rollback"),
            () -> events.add("core-finalize"));

        assertThrows(IllegalStateException.class, transaction::commit);

        assertEquals(List.of("core-commit", "settlement", "projection-commit"), events);
        assertFalse(transaction.compensated());
        assertTrue(transaction.compensationFailed());
        assertThrows(IllegalStateException.class, transaction::commit);
    }

    @Test
    void publicationKeyAttachmentDoesNotMakeTheLiveCoreCandidateStale() {
        RuntimeRegistrySnapshot runtime = RuntimeRegistrySnapshot.empty();
        CatalogVersion contract = new CatalogVersion(1, 0);
        CatalogSnapshot catalog = new CatalogCompiler(contract, CatalogBindingProof.snapshot(runtime))
            .compile(List.of(), 1)
            .snapshot()
            .orElseThrow();
        CatalogRuntimeActivation.ActivationRecord candidate = new CatalogRuntimeActivation.ActivationRecord(
            catalog, runtime, Optional.empty());
        CatalogCacheKey publicationKey = new CatalogCacheKey(ServerId.deterministic("core-first-activation"),
            catalog.generation(), catalog.contentChecksum());
        CatalogRuntimeActivation.ActivationRecord published = new CatalogRuntimeActivation.ActivationRecord(
            catalog, runtime, Optional.of(publicationKey));

        assertTrue(FlowModule.CatalogRuntimeTransaction.sameCatalogRuntimeCandidate(published, candidate));

        CatalogSnapshot different = new CatalogCompiler(contract, CatalogBindingProof.snapshot(runtime))
            .compile(List.of(), 2)
            .snapshot()
            .orElseThrow();
        assertFalse(FlowModule.CatalogRuntimeTransaction.sameCatalogRuntimeCandidate(
            new CatalogRuntimeActivation.ActivationRecord(different, runtime), candidate));
    }

    private ActivationFixture fixture(String phase, boolean failProjection) {
        CoreCatalog core = new CoreCatalog(List.of("existing-authored-node"), 7);
        CoreSnapshot previousCore = core.snapshot();
        CoreSnapshot candidateCore = new CoreSnapshot(List.of("replacement-authored-node"), 8);
        NodeDefinitionRegistry compatibility = registry("compatibility", "existing-node");
        CompatibilitySnapshot previousCompatibility = snapshot(compatibility);
        NodeDefinitionRegistry stagedCompatibility = registry("replacement", "replacement-node");
        CompatibilitySnapshot candidateCompatibility = snapshot(stagedCompatibility);
        List<String> events = new ArrayList<>();

        Supplier<Boolean> coreCommit = () -> {
            events.add(phase + ":core-commit");
            core.restore(candidateCore);
            return true;
        };
        Supplier<Boolean> coreRollback = () -> {
            events.add(phase + ":core-rollback");
            core.restore(previousCore);
            return true;
        };
        Runnable projectionCommit = () -> {
            events.add(phase + ":projection-commit");
            compatibility.replaceFrom(stagedCompatibility);
            if (failProjection) {
                throw new IllegalStateException("Compatibility projection failed");
            }
        };
        Runnable projectionRollback = () -> {
            events.add(phase + ":projection-rollback");
            NodeDefinitionRegistry restored = registry("compatibility", "existing-node");
            compatibility.replaceFrom(restored);
        };
        Runnable coreFinalize = () -> events.add(phase + ":core-finalize");
        FlowModule.CoreFirstActivationTransaction transaction = new FlowModule.CoreFirstActivationTransaction(
            coreCommit, coreRollback, projectionCommit, projectionRollback, coreFinalize);
        return new ActivationFixture(transaction, core, previousCore, candidateCore, compatibility,
            previousCompatibility, candidateCompatibility, events);
    }

    private NodeDefinitionRegistry registry(String pluginId, String nodeId) {
        NodeDefinition definition = new NodeDefinition.Builder(nodeId, nodeId, NodeDefinition.NodeCategory.DATA)
            .owner(pluginId)
            .build();
        NodeDefinitionRegistry registry = new NodeDefinitionRegistry(false);
        registry.register(pluginId, definition);
        return registry;
    }

    private CompatibilitySnapshot snapshot(NodeDefinitionRegistry registry) {
        Map<String, List<String>> definitionsByPlugin = new LinkedHashMap<>();
        for (String pluginId : registry.getPluginIds()) {
            definitionsByPlugin.put(pluginId, registry.getDefinitionsForPlugin(pluginId).stream()
                .map(definition -> definition.getOwner() + ":" + definition.getId())
                .toList());
        }
        return new CompatibilitySnapshot(List.copyOf(registry.getPluginIds()),
            List.copyOf(registry.getAllDefinitions().keySet()), definitionsByPlugin);
    }

    private static final class CoreCatalog {
        private List<String> contributions;
        private long generation;

        private CoreCatalog(List<String> contributions, long generation) {
            this.contributions = List.copyOf(contributions);
            this.generation = generation;
        }

        private CoreSnapshot snapshot() {
            return new CoreSnapshot(contributions, generation);
        }

        private void restore(CoreSnapshot snapshot) {
            contributions = List.copyOf(snapshot.contributions());
            generation = snapshot.generation();
        }
    }

    private record CoreSnapshot(List<String> contributions, long generation) {
        private CoreSnapshot {
            contributions = List.copyOf(contributions);
        }
    }

    private record CompatibilitySnapshot(List<String> plugins, List<String> identities,
                                         Map<String, List<String>> definitionsByPlugin) {
        private CompatibilitySnapshot {
            plugins = List.copyOf(plugins);
            identities = List.copyOf(identities);
            definitionsByPlugin = Map.copyOf(definitionsByPlugin);
        }
    }

    private record ActivationFixture(FlowModule.CoreFirstActivationTransaction transaction, CoreCatalog core,
                                     CoreSnapshot previousCore, CoreSnapshot candidateCore,
                                     NodeDefinitionRegistry compatibility, CompatibilitySnapshot previousCompatibility,
                                     CompatibilitySnapshot candidateCompatibility, List<String> events) {
    }
}
