package restudio.resync.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.flow.diagnostics.DiagnosticReportPersistenceParticipant;
import restudio.resync.flow.diagnostics.StructuredFlowDiagnosticReporter;
import restudio.resync.flow.cache.CatalogPublicationReceiptStore;
import restudio.resync.flow.runtime.DurableRuntimeReceiptStore;
import restudio.resync.migration.MigrationFence;
import restudio.resync.migration.MigrationException;
import restudio.resync.migration.PersistenceParticipant;
import restudio.resync.migration.PersistenceParticipantClassification;
import restudio.resync.migration.PersistenceOwnershipContext;
import restudio.resync.migration.PersistenceOwnershipIndex;
import restudio.resync.migration.PersistenceOwnershipProvider;
import restudio.resync.migration.PersistenceRootReadiness;
import restudio.resync.migration.ProductionAuthorityBundle;
import restudio.resync.migration.ProductionAuthorityBundlePersistenceParticipant;
import restudio.resync.migration.RebindablePersistenceParticipant;
import restudio.resync.migration.ReSyncPersistenceCoordinator;
import restudio.resync.worldgen.WorldGenGeneratedOutputPolicy;
import restudio.resync.worldgen.datapack.WorldGenDatapackInstaller;
import restudio.resync.worldgen.datapack.WorldGenInstalledDatapackCapability;
import restudio.resync.worldgen.datapack.WorldGenInstalledDatapackPersistenceParticipant;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncPersistenceTopologyTest {
    @TempDir
    Path temporary;

    @Test
    void registersIdentityAndAuthorityParticipantsWithSharedQuarantineOwnership() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-authority-ownership"));
        Files.createDirectories(dataRoot.resolve(".quarantine/authority-bundle"));
        ServerIdentityStore identity = ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));
        ProductionAuthorityBundle.TrustedAuthority trustedAuthority =
            ProductionAuthorityBundle.TrustedAuthority.from(dataRoot, identity.productionAuthoritySigner());
        ProductionAuthorityBundlePersistenceParticipant authority =
            new ProductionAuthorityBundlePersistenceParticipant(dataRoot, trustedAuthority, false, false);

        Path sharedQuarantine = dataRoot.resolve(".quarantine");
        Path identityQuarantine = dataRoot.resolve(ServerIdentityStore.QUARANTINE_DIRECTORY);
        assertTrue(identity.owns(sharedQuarantine));
        assertTrue(identity.owns(identityQuarantine));

        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);
        ReSyncPersistenceTopology.Registration result = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(
                ReSyncPersistenceTopology.requiredForRestore(ServerIdentityStore.OWNER, identity.root(), identity),
                ReSyncPersistenceTopology.derivedCache(
                    ProductionAuthorityBundlePersistenceParticipant.OWNER, authority.root(), authority)));

        assertTrue(result.sealed(), result.unavailableReasons().toString());
        assertEquals(List.of(ProductionAuthorityBundlePersistenceParticipant.OWNER, ServerIdentityStore.OWNER),
            result.registeredOwners());
        assertTrue(result.unavailableOwners().isEmpty());
        assertTrue(coordinator.sealed());
        ReSyncPersistenceCoordinator.ReadinessProof proof = coordinator
            .currentValidatedReadinessProof(result.readiness()).orElseThrow();
        assertEquals(coordinator.participants().ownershipEpoch(), proof.topologyEpoch());
        assertEquals(0L, proof.derivationGeneration());
    }

    @Test
    void registersExistingRestoreSafeParticipantAndSeals() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync"));
        Path stateRoot = Files.createDirectory(dataRoot.resolve("state"));
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);

        ReSyncPersistenceTopology.Registration result = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(ReSyncPersistenceTopology.requiredForRestore("runtime.state", stateRoot, restoreSafeParticipant("runtime.state", dataRoot, stateRoot)))
        );

        assertTrue(result.sealed());
        assertEquals(List.of("runtime.state"), result.registeredOwners());
        assertTrue(result.unavailableOwners().isEmpty());
        assertTrue(coordinator.sealed());
        assertTrue(coordinator.restoreReady());
    }

    @Test
    void resolvesAllLocalWritersFromOneOwnershipSnapshot() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-batched-writer-resolution"));
        Path stateRoot = Files.createDirectory(dataRoot.resolve("state"));
        CountingOwnershipParticipant participant = new CountingOwnershipParticipant("runtime.state", stateRoot);
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);
        coordinator.register(participant);

        ReSyncPersistenceTopology.Registration result = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(ReSyncPersistenceTopology.required("runtime.state", stateRoot, participant)),
            List.of(
                PersistenceRootReadiness.UncoveredWriter.local("writer.first", stateRoot.resolve("first.json"), "Writer must be owned"),
                PersistenceRootReadiness.UncoveredWriter.local("writer.second", stateRoot.resolve("second.json"), "Writer must be owned"),
                PersistenceRootReadiness.UncoveredWriter.local("writer.third", stateRoot.resolve("third.json"), "Writer must be owned")
            ));

        assertTrue(result.sealed(), result.unavailableReasons().toString());
        assertTrue(result.readiness().uncoveredWriterIds().isEmpty());
        assertEquals(2, participant.indexBuilds());
    }

    @Test
    void retainsExistingParticipantIdentityAndRegistersEveryMissingOwner() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-batched-registration"));
        Path existingRoot = Files.createDirectory(dataRoot.resolve("existing"));
        Path firstRoot = Files.createDirectory(dataRoot.resolve("first"));
        Path secondRoot = Files.createDirectory(dataRoot.resolve("second"));
        PersistenceParticipant existing = implicitParticipant("existing", existingRoot);
        PersistenceParticipant first = implicitParticipant("first", firstRoot);
        PersistenceParticipant second = implicitParticipant("second", secondRoot);
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);
        coordinator.register(existing);

        ReSyncPersistenceTopology.Registration result = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(
                ReSyncPersistenceTopology.safeNoOp("second", secondRoot, second),
                ReSyncPersistenceTopology.safeNoOp("existing", existingRoot, existing),
                ReSyncPersistenceTopology.safeNoOp("first", firstRoot, first)));

        assertTrue(result.sealed(), result.unavailableReasons().toString());
        assertEquals(List.of("existing", "first", "second"), result.registeredOwners());
        assertEquals(List.of("existing", "first", "second"), coordinator.registeredParticipants().stream()
            .map(PersistenceParticipant::owner).toList());
        coordinator.requireRegisteredParticipant(existing);
        coordinator.requireRegisteredParticipant(first);
        coordinator.requireRegisteredParticipant(second);
    }

    @Test
    void leavesCoordinatorOpenWhenRequiredRootIsMissing() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync"));
        Path missingRoot = dataRoot.resolve("missing");
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);

        ReSyncPersistenceTopology.Registration result = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(ReSyncPersistenceTopology.required("runtime.state", missingRoot, explicitParticipant("runtime.state", missingRoot)))
        );

        assertFalse(result.sealed());
        assertEquals(List.of("runtime.state"), result.unavailableOwners());
        assertFalse(coordinator.sealed());
        assertTrue(coordinator.registeredParticipants().isEmpty());
    }

    @Test
    void registersARebindableRegularFileParticipantWithoutClaimingItsParentDirectory() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-file-root"));
        Path configurationFile = dataRoot.resolve("config.properties");
        Files.writeString(configurationFile, "resync.enabled=true\n");
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);

        ReSyncPersistenceTopology.Registration result = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(ReSyncPersistenceTopology.requiredForRestore(
                "resync.configuration", configurationFile, restoreSafeParticipant("resync.configuration", dataRoot, configurationFile))),
            List.of());

        assertTrue(result.sealed());
        assertEquals(List.of("resync.configuration"), result.registeredOwners());
        assertTrue(result.unavailableOwners().isEmpty());
        assertEquals(List.of("resync.configuration"), coordinator.registeredParticipants().stream()
            .map(PersistenceParticipant::owner).toList());
    }

    @Test
    void registersTheConfigurationLifecycleAndRemovesOnlyItsInventoryRow() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-configuration-inventory"));
        ConfigurationPersistenceParticipant configuration = new ConfigurationPersistenceParticipant(dataRoot);
        configuration.replaceProperties(properties("enabled", "true"));
        configuration.flush();
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);

        ReSyncPersistenceTopology.Registration result = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(ReSyncPersistenceTopology.requiredForRestore(
                ConfigurationPersistenceParticipant.OWNER,
                configuration.root(),
                configuration)),
            ReSyncUncoveredWriterInventory.forDataRoot(dataRoot));

        assertFalse(result.sealed());
        assertEquals(List.of(ConfigurationPersistenceParticipant.OWNER), result.registeredOwners());
        assertEquals(List.of(
            "resync.catalog-publication-receipts",
            "resync.extensions",
            "resync.flow-regions",
            "resync.flow-variables",
            "resync.flow.files",
            "resync.install-identity",
            "resync.migration-reports",
            "resync.network",
            "resync.player-dossiers",
            "resync.structures",
            "resync.triggers",
            "resync.world-management"), result.unavailableOwners());
        assertEquals(List.of(ConfigurationPersistenceParticipant.OWNER), coordinator.registeredParticipants().stream()
            .map(PersistenceParticipant::owner).toList());
        assertEquals(List.of(
            "resync.catalog-publication-receipts",
            "resync.extensions",
            "resync.flow-regions",
            "resync.flow-variables",
            "resync.flow.files",
            "resync.install-identity",
            "resync.migration-reports",
            "resync.network",
            "resync.player-dossiers",
            "resync.runtime.luckperms-backend",
            "resync.structures",
            "resync.triggers",
            "resync.world-management"), result.readiness().uncoveredWriterIds());
        assertFalse(result.readiness().uncoveredWriterIds().contains(ConfigurationPersistenceParticipant.OWNER));
    }

    @Test
    void registersCatalogPublicationReceiptsAsOneExactFileOwner() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-receipt-topology"));
        CatalogPublicationReceiptStore receipts = new CatalogPublicationReceiptStore(dataRoot,
            dataRoot.resolve(CatalogPublicationReceiptStore.FILE_NAME));
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);

        ReSyncPersistenceTopology.Registration result = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(ReSyncPersistenceTopology.requiredForRestore(
                CatalogPublicationReceiptStore.OWNER, receipts.root(), receipts)),
            ReSyncUncoveredWriterInventory.forDataRoot(dataRoot));

        assertFalse(result.sealed());
        assertEquals(List.of(CatalogPublicationReceiptStore.OWNER), result.registeredOwners());
        assertFalse(result.readiness().uncoveredWriterIds().contains(CatalogPublicationReceiptStore.OWNER));
        assertEquals(CatalogPublicationReceiptStore.OWNER,
            coordinator.participants().ownerFor(dataRoot, receipts.root()));
        assertThrows(MigrationException.class,
            () -> coordinator.participants().ownerFor(dataRoot, dataRoot.resolve(CatalogPublicationReceiptStore.FILE_NAME + ".tmp")));
    }

    @Test
    void resolvesAnExistingRuntimeReceiptLockThroughItsExactParticipantBinding() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-runtime-receipt-lock"));
        DurableRuntimeReceiptStore receipts = new DurableRuntimeReceiptStore(dataRoot);
        Path lock = receipts.root().resolveSibling(receipts.root().getFileName() + ".lock");
        Path unrelatedLock = receipts.root().resolveSibling("other.lock");
        Files.writeString(unrelatedLock, "unowned");
        assertTrue(Files.isRegularFile(lock));
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);
        String unknownLockOwner = "resync.runtime.unknown.runtime-receipts.json.lock";
        List<ReSyncPersistenceTopology.Binding> bindings = new ArrayList<>(List.of(
            ReSyncPersistenceTopology.requiredForRestore(
                DurableRuntimeReceiptStore.OWNER, receipts.root(), receipts)));
        List<PersistenceRootReadiness.UncoveredWriter> inventory = ReSyncUncoveredWriterInventory.forDataRoot(dataRoot);
        List<PersistenceRootReadiness.UncoveredWriter> diagnostics = ReSyncServer.addUncoveredWriterGaps(
            dataRoot, bindings, inventory, coordinator);

        ReSyncPersistenceTopology.Registration result = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            bindings,
            diagnostics);

        assertFalse(bindings.stream().anyMatch(binding -> binding.owner().equals(unknownLockOwner)));
        assertFalse(result.readiness().uncoveredWriterIds().contains(unknownLockOwner));
        assertTrue(bindings.stream().anyMatch(binding -> binding.owner().equals("resync.runtime.unknown.other.lock")));
        assertTrue(result.unavailableOwners().contains("resync.runtime.unknown.other.lock"));
        assertEquals(DurableRuntimeReceiptStore.OWNER, coordinator.participants().ownerFor(dataRoot, lock));
        assertThrows(MigrationException.class,
            () -> coordinator.participants().ownerFor(dataRoot, unrelatedLock));
    }

    @Test
    void leavesCoordinatorOpenWhenRequiredRootHasNoBoundParticipant() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync"));
        Path missingRoot = dataRoot.resolve("runtime");
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);

        ReSyncPersistenceTopology.Registration result = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(ReSyncPersistenceTopology.required("resync.runtime", missingRoot, null))
        );

        assertFalse(result.sealed());
        assertEquals(List.of("resync.runtime"), result.unavailableOwners());
        assertTrue(result.registeredOwners().isEmpty());
        assertFalse(coordinator.sealed());
        assertTrue(coordinator.registeredParticipants().isEmpty());
    }

    @Test
    void classifiesUnavailableGeneratedOutputAsOptionalDerivedCache() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-derived-cache"));
        Path stateRoot = Files.createDirectory(dataRoot.resolve("state"));
        Path generatedRoot = dataRoot.resolve("worldgen").resolve("generated");
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);

        ReSyncPersistenceTopology.Registration result = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(
                ReSyncPersistenceTopology.safeNoOp("runtime.state", stateRoot, implicitParticipant("runtime.state", stateRoot)),
                ReSyncPersistenceTopology.derivedUnavailable("resync.worldgen.generated", generatedRoot,
                    "WorldGen generated output will be rebuilt")));

        assertTrue(result.sealed());
        PersistenceRootReadiness.Owner owner = result.readiness().owner("resync.worldgen.generated");
        assertEquals(PersistenceParticipantClassification.DERIVED_CACHE, owner.classification());
        assertFalse(owner.required());
        assertEquals(PersistenceRootReadiness.State.UNAVAILABLE, owner.state());
        assertTrue(result.readiness().complete());
    }

    @Test
    void sealsRestoreSafeTopologyWithUnregisteredOptionalDerivedOwner() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-derived-absent"));
        Path stateRoot = Files.createDirectory(dataRoot.resolve("state"));
        Path generatedRoot = dataRoot.resolve("worldgen").resolve("generated");
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);
        RebindablePersistenceParticipant state = restoreSafeParticipant("runtime.state", dataRoot, stateRoot);

        ReSyncPersistenceTopology.Registration result = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(
                ReSyncPersistenceTopology.requiredForRestore(state.owner(), state.root(), state),
                ReSyncPersistenceTopology.derivedUnavailable("runtime.optional", generatedRoot,
                    "Optional derived owner is not installed")));

        assertTrue(result.sealed());
        assertTrue(coordinator.sealed());
        assertEquals(PersistenceRootReadiness.State.UNAVAILABLE,
            result.readiness().owner("runtime.optional").state());
        assertTrue(coordinator.currentValidatedReadinessProof(result.readiness()).isPresent());
    }

    @Test
    void refreshesAllLiveParticipantsWhilePreservingReadinessMetadataAndInventory() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-refresh"));
        Path stateRoot = Files.createDirectory(dataRoot.resolve("state"));
        Path derivedRoot = Files.createDirectory(dataRoot.resolve("derived"));
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);
        RefreshParticipant state = new RefreshParticipant("runtime.state", stateRoot,
            PersistenceParticipantClassification.AUTHORITATIVE);
        RefreshParticipant derived = new RefreshParticipant("runtime.derived", derivedRoot,
            PersistenceParticipantClassification.DERIVED_CACHE);
        PersistenceRootReadiness.UncoveredWriter writer = PersistenceRootReadiness.UncoveredWriter.externalAffected(
            "external.writer", temporary.resolve("external-writer"), "External writer remains outside persistence", "operator");

        ReSyncPersistenceTopology.Registration registration = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(
                ReSyncPersistenceTopology.requiredForRestore(state.owner(), state.root(), state),
                ReSyncPersistenceTopology.derivedUnavailable(derived.owner(), derived.root(), derived, "Derived output is pending")),
            List.of(writer));

        ReSyncPersistenceTopology.Registration refreshed = ReSyncPersistenceTopology.refresh(coordinator, registration);

        assertTrue(refreshed.sealed());
        assertEquals(PersistenceRootReadiness.State.REGISTERED, refreshed.readiness().owner(state.owner()).state());
        assertTrue(refreshed.readiness().owner(state.owner()).required());
        assertEquals(PersistenceParticipantClassification.AUTHORITATIVE,
            refreshed.readiness().owner(state.owner()).classification());
        assertEquals(PersistenceRootReadiness.State.REGISTERED, refreshed.readiness().owner(derived.owner()).state());
        assertFalse(refreshed.readiness().owner(derived.owner()).required());
        assertEquals(PersistenceParticipantClassification.DERIVED_CACHE,
            refreshed.readiness().owner(derived.owner()).classification());
        assertEquals(registration.readiness().uncoveredWriters(), refreshed.readiness().uncoveredWriters());
        assertEquals(registration.readiness().externalInputs(), refreshed.readiness().externalInputs());
    }

    @Test
    void refreshesLiveParticipantRootsForFutureAuthorityComparisons() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-refresh-root"));
        Path stateRoot = Files.createDirectory(dataRoot.resolve("state"));
        Path movedRoot = Files.createDirectory(dataRoot.resolve("moved-state"));
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);
        RefreshParticipant state = new RefreshParticipant("runtime.state", stateRoot,
            PersistenceParticipantClassification.AUTHORITATIVE);
        ReSyncPersistenceTopology.Registration registration = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(ReSyncPersistenceTopology.requiredForRestore(state.owner(), state.root(), state)));

        state.move(movedRoot);
        ReSyncPersistenceTopology.Registration refreshed = ReSyncPersistenceTopology.refresh(coordinator, registration);

        assertEquals(movedRoot, refreshed.readiness().owner(state.owner()).root());
        assertEquals(PersistenceRootReadiness.State.REGISTERED, refreshed.readiness().owner(state.owner()).state());
    }

    @Test
    void refreshesUnavailableStateAndReasonFromLiveParticipants() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-refresh-failure"));
        Path stateRoot = Files.createDirectory(dataRoot.resolve("state"));
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);
        RefreshParticipant state = new RefreshParticipant("runtime.state", stateRoot,
            PersistenceParticipantClassification.AUTHORITATIVE);
        ReSyncPersistenceTopology.Registration registration = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(ReSyncPersistenceTopology.requiredForRestore(state.owner(), state.root(), state)));

        state.fail("Live participant health failed");
        ReSyncPersistenceTopology.Registration refreshed = ReSyncPersistenceTopology.refresh(coordinator, registration);

        PersistenceRootReadiness.Owner owner = refreshed.readiness().owner(state.owner());
        assertEquals(PersistenceRootReadiness.State.UNAVAILABLE, owner.state());
        assertEquals("Live participant health failed", owner.reason());
        assertEquals(List.of(state.owner()), refreshed.unavailableOwners());
        assertEquals("Live participant health failed", refreshed.unavailableReasons().get(state.owner()));
    }

    @Test
    void retainsUnavailableDerivedParticipantForLifecycleWithoutReportingReadiness() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-derived-lifecycle"));
        Path stateRoot = Files.createDirectory(dataRoot.resolve("state"));
        Path generatedRoot = Files.createDirectories(dataRoot.resolve("worldgen").resolve("generated"));
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);
        PersistenceParticipant participant = derivedParticipant("resync.worldgen.generated", generatedRoot);

        ReSyncPersistenceTopology.Registration result = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(
                ReSyncPersistenceTopology.safeNoOp("runtime.state", stateRoot, implicitParticipant("runtime.state", stateRoot)),
                ReSyncPersistenceTopology.derivedUnavailable(participant.owner(), generatedRoot, participant,
                    "WorldGen generated output will be rebuilt")));

        assertTrue(result.sealed());
        assertFalse(result.registeredOwners().contains(participant.owner()));
        assertTrue(result.unavailableOwners().contains(participant.owner()));
        assertEquals(PersistenceRootReadiness.State.UNAVAILABLE, result.readiness().owner(participant.owner()).state());
        assertTrue(coordinator.registeredParticipants().stream().anyMatch(registered -> registered == participant));
    }

    @Test
    void registersAnUnavailableDerivedBindingWithoutReportingItAsReady() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-derived-participant"));
        Path stateRoot = Files.createDirectory(dataRoot.resolve("state"));
        Path generatedRoot = Files.createDirectories(dataRoot.resolve("worldgen").resolve("generated"));
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);

        ReSyncPersistenceTopology.Registration result = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(
                ReSyncPersistenceTopology.safeNoOp("runtime.state", stateRoot, implicitParticipant("runtime.state", stateRoot)),
                ReSyncPersistenceTopology.derivedUnavailable(
                    WorldGenGeneratedOutputPolicy.OWNER, generatedRoot, "WorldGen rebuild recipe is unavailable")));

        assertTrue(result.sealed());
        assertEquals(List.of("runtime.state"), result.registeredOwners());
        assertEquals(List.of(WorldGenGeneratedOutputPolicy.OWNER), result.unavailableOwners());
        assertEquals(PersistenceRootReadiness.State.UNAVAILABLE,
            result.readiness().owner(WorldGenGeneratedOutputPolicy.OWNER).state());
        assertTrue(coordinator.registeredParticipants().stream()
            .noneMatch(participant -> participant.owner().equals(WorldGenGeneratedOutputPolicy.OWNER)));
        assertFalse(coordinator.restoreReady());
        coordinator.close();
    }

    @Test
    void bindsInstalledDatapackLifecycleToItsSharedCapabilityOwner() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-installed-datapack"));
        Path worlds = Files.createDirectory(temporary.resolve("worlds"));
        WorldGenDatapackInstaller installer = new WorldGenDatapackInstaller(
            worlds, "26.2", ignored -> false, WorldGenDatapackInstaller.DirectoryDurability.noop());
        WorldGenInstalledDatapackPersistenceParticipant participant =
            new WorldGenInstalledDatapackPersistenceParticipant(dataRoot, installer);
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);
        Path stateRoot = Files.createDirectory(dataRoot.resolve("state"));

        ReSyncPersistenceTopology.Registration result = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(
                ReSyncPersistenceTopology.safeNoOp("runtime.state", stateRoot, implicitParticipant("runtime.state", stateRoot)),
                ReSyncPersistenceTopology.derivedCache(WorldGenInstalledDatapackCapability.OWNER, participant.root(), participant)));

        assertTrue(result.sealed());
        assertEquals(WorldGenInstalledDatapackCapability.OWNER, participant.owner());
        assertTrue(result.registeredOwners().contains(WorldGenInstalledDatapackCapability.OWNER));
        assertTrue(coordinator.participants().participants().stream()
            .anyMatch(registered -> registered == participant
                && registered.owner().equals(WorldGenInstalledDatapackCapability.OWNER)
                && registered.root().equals(participant.root())));
        coordinator.close();
    }

    @Test
    void recordsWhyAnExplicitlyUnavailableParticipantCannotSealTheCoordinator() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync"));
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);

        ReSyncPersistenceTopology.Registration result = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(ReSyncPersistenceTopology.unavailable(
                "resync.network", dataRoot.resolve("network"), "Live services cannot atomically rebind"))
        );

        assertFalse(result.sealed());
        assertEquals(List.of("resync.network"), result.unavailableOwners());
        assertEquals("Live services cannot atomically rebind", result.unavailableReasons().get("resync.network"));
        assertTrue(coordinator.registeredParticipants().isEmpty());
    }

    @Test
    void registersBoundedParticipantsWithAnExplicitLocalGap() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-bounded"));
        Path diagnosticsRoot = Files.createDirectory(dataRoot.resolve("diagnostics"));
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);
        StructuredFlowDiagnosticReporter reporter = new StructuredFlowDiagnosticReporter(diagnosticsRoot);

        ReSyncPersistenceTopology.Registration result = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(
                ReSyncPersistenceTopology.requiredForRestore(
                    DiagnosticReportPersistenceParticipant.OWNER,
                    diagnosticsRoot,
                    new DiagnosticReportPersistenceParticipant(dataRoot, diagnosticsRoot, reporter)),
                ReSyncPersistenceTopology.unavailable(
                    "resync.network", dataRoot.resolve("network"), "Live services cannot atomically rebind")));

        assertFalse(result.sealed());
        assertEquals(List.of(DiagnosticReportPersistenceParticipant.OWNER), result.registeredOwners());
        assertEquals(List.of("resync.network"), result.unavailableOwners());
        assertEquals(List.of(DiagnosticReportPersistenceParticipant.OWNER), coordinator.registeredParticipants().stream()
            .map(PersistenceParticipant::owner).toList());
        assertFalse(coordinator.sealed());
    }

    @Test
    void publishesEachUnsupportedWriterAsAnExplicitReadinessGap() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-writer-gaps"));
        Path diagnosticsRoot = Files.createDirectory(dataRoot.resolve("diagnostics"));
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);
        StructuredFlowDiagnosticReporter reporter = new StructuredFlowDiagnosticReporter(diagnosticsRoot);
        List<PersistenceRootReadiness.UncoveredWriter> inventory = List.of(
            PersistenceRootReadiness.UncoveredWriter.of(
                "resync.network", dataRoot.resolve("network"), "Network writer has no common lifecycle owner"),
            PersistenceRootReadiness.UncoveredWriter.of(
                "resync.runtime", dataRoot.resolve("runtime"), "Runtime writers have no common lifecycle owner"));

        ReSyncPersistenceTopology.Registration result = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(
                ReSyncPersistenceTopology.requiredForRestore(
                    DiagnosticReportPersistenceParticipant.OWNER,
                    diagnosticsRoot,
                    new DiagnosticReportPersistenceParticipant(dataRoot, diagnosticsRoot, reporter)),
                ReSyncPersistenceTopology.unavailable(
                    "resync.network", dataRoot.resolve("network"), "Network writer has no common lifecycle owner"),
                ReSyncPersistenceTopology.unavailable(
                    "resync.runtime", dataRoot.resolve("runtime"), "Runtime writers have no common lifecycle owner")),
            inventory);

        assertFalse(result.sealed());
        assertEquals(List.of("resync.diagnostics"), result.registeredOwners());
        assertEquals(List.of("resync.network", "resync.runtime"), result.unavailableOwners());
        assertEquals(List.of("resync.network", "resync.runtime"), result.readiness().uncoveredWriterIds());
        assertTrue(!result.readiness().owner("resync.network").reason().isBlank());
        assertTrue(!result.readiness().owner("resync.runtime").reason().isBlank());
    }

    @Test
    void readinessNamesTheExactRegisteredAndUnresolvedOwners() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-aggregate-report"));
        Path stateRoot = Files.createDirectory(dataRoot.resolve("state"));
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);
        List<PersistenceRootReadiness.UncoveredWriter> inventory = List.of(
            PersistenceRootReadiness.UncoveredWriter.of(
                "resync.network", dataRoot.resolve("network"), "Network writer has no common lifecycle owner"),
            PersistenceRootReadiness.UncoveredWriter.of(
                "resync.runtime", dataRoot.resolve("runtime"), "Runtime writers have no common lifecycle owner"));

        ReSyncPersistenceTopology.Registration result = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(
                ReSyncPersistenceTopology.requiredForRestore(
                    "resync.state", stateRoot, restoreSafeParticipant("resync.state", dataRoot, stateRoot)),
                ReSyncPersistenceTopology.unavailable(
                    "resync.network", dataRoot.resolve("network"), "Network writer has no common lifecycle owner"),
                ReSyncPersistenceTopology.unavailable(
                    "resync.runtime", dataRoot.resolve("runtime"), "Runtime writers have no common lifecycle owner")),
            inventory);

        assertFalse(result.sealed());
        assertEquals(List.of("resync.state"), result.registeredOwners());
        assertEquals(List.of("resync.network", "resync.runtime"), result.unavailableOwners());
        assertTrue(!result.unavailableReasons().get("resync.network").isBlank());
        assertTrue(!result.unavailableReasons().get("resync.runtime").isBlank());
        assertEquals(List.of("resync.network", "resync.runtime"), result.readiness().uncoveredWriterIds());
    }

    @Test
    void leavesCoordinatorOpenWhenRequiredParticipantUsesImplicitNoOpLifecycle() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync"));
        Path stateRoot = Files.createDirectory(dataRoot.resolve("state"));
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);

        ReSyncPersistenceTopology.Registration result = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(ReSyncPersistenceTopology.required("runtime.state", stateRoot, implicitParticipant("runtime.state", stateRoot)))
        );

        assertFalse(result.sealed());
        assertEquals(List.of("runtime.state"), result.unavailableOwners());
        assertFalse(coordinator.sealed());
    }

    @Test
    void leavesCoordinatorOpenWhenRequiredParticipantCannotProveAtomicRebind() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync"));
        Path stateRoot = Files.createDirectory(dataRoot.resolve("state"));
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);

        ReSyncPersistenceTopology.Registration result = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(ReSyncPersistenceTopology.requiredForRestore("runtime.state", stateRoot, explicitParticipant("runtime.state", stateRoot)))
        );

        assertFalse(result.sealed());
        assertEquals(List.of("runtime.state"), result.unavailableOwners());
        assertFalse(coordinator.sealed());
    }

    @Test
    void refreshPreservesRequiredRegistrationFailure() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-refresh-required-gap"));
        Path stateRoot = Files.createDirectory(dataRoot.resolve("state"));
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);
        PersistenceParticipant participant = explicitParticipant("runtime.state", stateRoot);
        coordinator.register(participant);

        ReSyncPersistenceTopology.Registration registration = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(ReSyncPersistenceTopology.requiredForRestore("runtime.state", stateRoot, participant))
        );
        ReSyncPersistenceTopology.Registration refreshed = ReSyncPersistenceTopology.refresh(coordinator, registration);

        assertFalse(refreshed.sealed());
        assertEquals(List.of("runtime.state"), refreshed.unavailableOwners());
        assertEquals("Participant Does Not Prove Atomic Rebind", refreshed.unavailableReasons().get("runtime.state"));
        assertEquals(PersistenceRootReadiness.State.UNAVAILABLE,
            refreshed.readiness().owner("runtime.state").state());
    }

    @Test
    void permitsAnExplicitlyApprovedNoOpOnlyForTheDeclaredBinding() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync"));
        Path stateRoot = Files.createDirectory(dataRoot.resolve("state"));
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);

        ReSyncPersistenceTopology.Registration result = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(ReSyncPersistenceTopology.safeNoOp("runtime.state", stateRoot, implicitParticipant("runtime.state", stateRoot)))
        );

        assertTrue(result.sealed());
        assertEquals(List.of("runtime.state"), result.registeredOwners());
        assertTrue(result.unavailableOwners().isEmpty());
        assertTrue(coordinator.currentValidatedReadinessProof(result.readiness()).isEmpty());
        assertFalse(coordinator.restoreReady());
    }

    @Test
    void removesInventoryRowsOnlyForParticipantsRegisteredInTheSameTopology() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-inventory-filter"));
        Path playerRoot = Files.createDirectory(dataRoot.resolve("player-dossiers"));
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);
        List<PersistenceRootReadiness.UncoveredWriter> inventory = List.of(
            PersistenceRootReadiness.UncoveredWriter.of(
                "resync.network", dataRoot.resolve("network"), "Network state has no coordinated participant"),
            PersistenceRootReadiness.UncoveredWriter.of(
                "resync.player-dossiers", playerRoot, "Player dossiers have no coordinated participant"));

        ReSyncPersistenceTopology.Registration result = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(ReSyncPersistenceTopology.requiredForRestore(
                "resync.player-dossiers", playerRoot, restoreSafeParticipant("resync.player-dossiers", dataRoot, playerRoot))),
            inventory);

        assertFalse(result.sealed());
        assertEquals(List.of("resync.player-dossiers"), result.registeredOwners());
        assertEquals(List.of("resync.network"), result.unavailableOwners());
        assertEquals(List.of("resync.network"), result.readiness().uncoveredWriterIds());
        assertEquals(List.of("resync.network"), result.readiness().requiredGaps().stream()
            .map(PersistenceRootReadiness.Owner::owner).toList());
    }

    @Test
    void preservesFilteredInventoryWhenSealingFails() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-inventory-seal-failure"));
        Path playerRoot = Files.createDirectory(dataRoot.resolve("player-dossiers"));
        Files.writeString(dataRoot.resolve("unowned.txt"), "unowned");
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);
        List<PersistenceRootReadiness.UncoveredWriter> inventory = List.of(
            PersistenceRootReadiness.UncoveredWriter.of(
                "resync.player-dossiers", playerRoot, "Player dossiers have no coordinated participant"));

        ReSyncPersistenceTopology.Registration result = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(ReSyncPersistenceTopology.requiredForRestore(
                "resync.player-dossiers", playerRoot, restoreSafeParticipant("resync.player-dossiers", dataRoot, playerRoot))),
            inventory);

        assertFalse(result.sealed());
        assertEquals(List.of("resync.player-dossiers"), result.registeredOwners());
        assertEquals(List.of("resync.persistence.seal"), result.unavailableOwners());
        assertTrue(result.readiness().uncoveredWriters().isEmpty());
        assertEquals(List.of("resync.persistence.seal"), result.readiness().requiredGaps().stream()
            .map(PersistenceRootReadiness.Owner::owner).toList());
    }

    @Test
    void rejectsAmbiguousRootsBeforeRegistration() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync"));
        Path firstRoot = Files.createDirectory(dataRoot.resolve("first"));
        Path secondRoot = Files.createDirectory(firstRoot.resolve("nested"));
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);

        assertThrows(IllegalArgumentException.class, () -> ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(
                ReSyncPersistenceTopology.safeNoOp("first", firstRoot, implicitParticipant("first", firstRoot)),
                ReSyncPersistenceTopology.safeNoOp("second", secondRoot, implicitParticipant("second", secondRoot))
            )
        ));
        assertFalse(coordinator.sealed());
        assertTrue(coordinator.registeredParticipants().isEmpty());
    }

    private ReSyncPersistenceCoordinator coordinator(Path dataRoot) throws IOException {
        return new ReSyncPersistenceCoordinator(dataRoot, temporary.resolve("coordination"), new MigrationFence());
    }

    private Properties properties(String key, String value) {
        Properties properties = new Properties();
        properties.setProperty(key, value);
        return properties;
    }

    private RebindablePersistenceParticipant restoreSafeParticipant(String owner, Path scopeRoot, Path root) {
        Path normalizedScope = scopeRoot.toAbsolutePath().normalize();
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path relative = normalizedScope.relativize(normalizedRoot);
        return new RebindablePersistenceParticipant() {
            private Path activeRoot = normalizedRoot;

            @Override
            public String owner() {
                return owner;
            }

            @Override
            public Path root() {
                return activeRoot;
            }

            @Override
            public void flush() {
            }

            @Override
            public void quiesce() {
            }

            @Override
            public void resume() {
            }

            @Override
            public void rebind(Path activeRoot) {
                this.activeRoot = activeRoot.resolve(relative).normalize();
            }

            @Override
            public void healthCheck() {
            }
        };
    }

    private PersistenceParticipant explicitParticipant(String owner, Path root) {
        return new PersistenceParticipant() {
            @Override
            public String owner() {
                return owner;
            }

            @Override
            public Path root() {
                return root;
            }

            @Override
            public void flush() {
            }

            @Override
            public void quiesce() {
            }

            @Override
            public void resume() {
            }

            @Override
            public void rebind(Path activeRoot) {
            }

            @Override
            public void healthCheck() {
            }
        };
    }

    private PersistenceParticipant derivedParticipant(String owner, Path root) {
        return new PersistenceParticipant() {
            @Override
            public String owner() {
                return owner;
            }

            @Override
            public Path root() {
                return root;
            }

            @Override
            public PersistenceParticipantClassification classification() {
                return PersistenceParticipantClassification.DERIVED_CACHE;
            }

            @Override
            public void flush() {
            }

            @Override
            public void quiesce() {
            }

            @Override
            public void resume() {
            }

            @Override
            public void rebind(Path activeRoot) {
            }

            @Override
            public void healthCheck() {
            }
        };
    }

    private PersistenceParticipant implicitParticipant(String owner, Path root) {
        return new PersistenceParticipant() {
            @Override
            public String owner() {
                return owner;
            }

            @Override
            public Path root() {
                return root;
            }
        };
    }

    private static final class RefreshParticipant implements RebindablePersistenceParticipant {
        private final String owner;
        private final Path scopeRoot;
        private final Path relativeRoot;
        private final PersistenceParticipantClassification classification;
        private Path root;
        private String failure = "";

        private RefreshParticipant(String owner, Path root, PersistenceParticipantClassification classification) {
            this.owner = owner;
            this.root = root.toAbsolutePath().normalize();
            this.scopeRoot = this.root.getParent();
            this.relativeRoot = scopeRoot.relativize(this.root);
            this.classification = classification;
        }

        @Override
        public String owner() {
            return owner;
        }

        @Override
        public Path root() {
            return root;
        }

        @Override
        public PersistenceParticipantClassification classification() {
            return classification;
        }

        @Override
        public void flush() {
        }

        @Override
        public void quiesce() {
        }

        @Override
        public void resume() {
        }

        @Override
        public void rebind(Path activeRoot) {
            root = activeRoot.resolve(relativeRoot).normalize();
        }

        @Override
        public void healthCheck() throws IOException {
            if (!failure.isBlank()) {
                throw new IOException(failure);
            }
        }

        private void move(Path nextRoot) {
            root = nextRoot.toAbsolutePath().normalize();
        }

        private void fail(String reason) {
            failure = reason;
        }
    }

    private static final class CountingOwnershipParticipant implements PersistenceParticipant, PersistenceOwnershipProvider {
        private final String owner;
        private final Path root;
        private final AtomicInteger indexBuilds = new AtomicInteger();

        private CountingOwnershipParticipant(String owner, Path root) {
            this.owner = owner;
            this.root = root.toAbsolutePath().normalize();
        }

        @Override
        public String owner() {
            return owner;
        }

        @Override
        public Path root() {
            return root;
        }

        @Override
        public PersistenceOwnershipIndex ownershipIndex(PersistenceOwnershipContext context) {
            indexBuilds.incrementAndGet();
            return PersistenceOwnershipIndex.builder(context).subtreeRoot().build();
        }

        @Override
        public void flush() {
        }

        @Override
        public void quiesce() {
        }

        @Override
        public void resume() {
        }

        @Override
        public void rebind(Path activeRoot) {
        }

        @Override
        public void healthCheck() {
        }

        private int indexBuilds() {
            return indexBuilds.get();
        }
    }

}
