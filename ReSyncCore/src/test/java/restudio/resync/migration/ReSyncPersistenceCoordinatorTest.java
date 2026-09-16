package restudio.resync.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.flow.cache.CatalogPublicationReceiptStore;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.restore.RestoreCompatibilityPolicy;
import restudio.resync.restore.RestoreRequest;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ReSyncPersistenceCoordinatorTest {
    private static final String CATALOG_CHECKSUM = "a".repeat(64);

    @TempDir
    Path temporary;

    @Test
    void preparedBootstrapReusesAnExistingActiveRoot() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("prepared-bootstrap-source"));
        Files.writeString(source.resolve("state.txt"), "state");
        Path coordination = temporary.resolve("prepared-bootstrap-coordination");
        ReSyncPersistenceCoordinator initial = new ReSyncPersistenceCoordinator(source, coordination,
            new MigrationFence());
        Path initialActiveRoot = initial.prepareActiveRoot();

        ReSyncPersistenceCoordinator.PreparedBootstrap prepared = ReSyncPersistenceCoordinator.bootstrapPrepared(
            source, coordination);

        assertEquals(initialActiveRoot, prepared.activeRoot());
        assertEquals(initialActiveRoot, prepared.coordinator().activeDataRoot());
        assertEquals("state", Files.readString(prepared.activeRoot().resolve("state.txt")));
        assertEquals(1L, prepared.coordinator().authorityEpoch());
    }

    @Test
    void bootstrapSealFailureRestoresParticipantsConstructedAtThePreparedRoot() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("bootstrap-source"));
        ReSyncPersistenceCoordinator coordinator = ReSyncPersistenceCoordinator.bootstrap(source,
            temporary.resolve("bootstrap-coordination"));
        Path prepared = coordinator.prepareActiveRoot();
        assertFalse(source.equals(prepared));
        Path sourceReceipt = source.resolve(CatalogPublicationReceiptStore.FILE_NAME);
        assertFalse(Files.exists(sourceReceipt));
        CatalogPublicationReceiptStore receipts = new CatalogPublicationReceiptStore(prepared,
            prepared.resolve(CatalogPublicationReceiptStore.FILE_NAME));
        try {
            Path preparedReceipt = receipts.path();
            byte[] before = Files.readAllBytes(preparedReceipt);
            coordinator.register(receipts);
            Files.writeString(prepared.resolve("unowned.txt"), "intentional missing participant");

            MigrationException failure = assertThrows(MigrationException.class, coordinator::seal);

            assertTrue(failure.getMessage().contains("topology validation"));
            assertEquals(PersistenceRebindStatus.State.ROLLED_BACK, coordinator.participants().rebindStatus().state());
            assertEquals(prepared, coordinator.participants().rebindStatus().activeRoot().orElseThrow());
            assertTrue(coordinator.participants().rebindStatus().stable());
            assertTrue(coordinator.participants().rebindStatus().rollbackFailures().isEmpty());
            assertEquals(prepared, coordinator.activation().activeRoot().orElseThrow());
            assertEquals(preparedReceipt, receipts.path());
            assertArrayEquals(before, Files.readAllBytes(preparedReceipt));
            assertFalse(Files.exists(sourceReceipt));
            assertFalse(receipts.isQuiesced());
            receipts.healthCheck();
            assertFalse(coordinator.restoreReady());
        } finally {
            receipts.close();
        }
    }

    @Test
    void coordinatesASealedWholeDataRootSnapshot() throws IOException {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync"));
        Path coordinationRoot = temporary.resolve("coordination");
        Files.writeString(dataRoot.resolve("state.txt"), "state");
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(dataRoot, coordinationRoot, new MigrationFence());
        Path preparedRoot = coordinator.prepareActiveRoot();
        coordinator.register(rebindableParticipant("resync", preparedRoot));

        coordinator.seal();

        Path staging = coordinator.snapshotRoot().resolve("snapshot-1");
        Snapshot snapshot = coordinator.createSnapshot(staging, metadata("snapshot-1"));

        assertTrue(snapshot.verified());
        assertEquals("resync", snapshot.manifest().entries().getFirst().owner());
        assertEquals("state", Files.readString(snapshot.root().resolve("state.txt")));
        assertEquals("state", Files.readString(dataRoot.resolve("state.txt")));
        assertFalse(coordinator.activeDataRoot().equals(dataRoot));
        assertTrue(coordinator.sealed());
        assertTrue(coordinator.participants().ownershipValidationMetrics().containsKey("restore"));
        assertFalse(coordinator.participants().ownershipValidationMetrics().containsKey("rebind"));
        assertTrue(coordinator.restoreReady());
        assertEquals(1, coordinator.registeredParticipants().size());

        Files.writeString(coordinator.activeDataRoot().resolve("state.txt"), "active");
        Snapshot activeSnapshot = coordinator.createSnapshot(coordinator.snapshotRoot().resolve("snapshot-2"), metadata("snapshot-2"));

        assertEquals("active", Files.readString(activeSnapshot.root().resolve("state.txt")));
        assertEquals("state", Files.readString(dataRoot.resolve("state.txt")));
    }

    @Test
    void freshBootstrapRelaxesUnavailableDerivedReadinessUntilStartupActivationCompletes() throws IOException {
        Path dataRoot = temporary.resolve("fresh-derived");
        ReSyncPersistenceCoordinator coordinator = ReSyncPersistenceCoordinator.bootstrap(
            dataRoot, temporary.resolve("fresh-derived-coordination"));
        Path activeRoot = coordinator.prepareActiveRoot();
        AtomicBoolean derivedAvailable = new AtomicBoolean();
        coordinator.register(unavailableDerivedParticipant(activeRoot, derivedAvailable));

        coordinator.seal();

        assertTrue(coordinator.restoreReady());
        assertEquals(List.of("derived"), coordinator.registeredParticipants().stream()
            .map(PersistenceParticipant::owner).toList());
        assertThrows(IllegalStateException.class, coordinator::completeStartupActivation);
        assertTrue(coordinator.restoreReady());
        derivedAvailable.set(true);
        coordinator.completeStartupActivation();
        assertTrue(coordinator.restoreReady());
        assertFalse(coordinator.freshDerivedRepairAuthorized());
        coordinator.close();
    }

    @Test
    void readinessAndProvenanceObserversShareTheCoordinatorPublicationMonitor() throws Exception {
        ReSyncPersistenceCoordinator coordinator = ReSyncPersistenceCoordinator.bootstrap(
            temporary.resolve("publication-monitor"), temporary.resolve("publication-monitor-coordination"));
        Method freshBootstrap = ReSyncPersistenceCoordinator.class.getDeclaredMethod("freshBootstrap");
        Method readinessValidation = ReSyncPersistenceCoordinator.class.getDeclaredMethod(
            "performRestoreReadinessValidation");
        readinessValidation.setAccessible(true);

        assertTrue(Modifier.isSynchronized(freshBootstrap.getModifiers()));
        assertTrue(Modifier.isSynchronized(readinessValidation.getModifiers()));
        assertObservationUsesMonitor(coordinator, coordinator::freshBootstrap);
        assertObservationUsesMonitor(coordinator, () -> {
            try {
                readinessValidation.invoke(coordinator);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError(exception);
            }
        });

        coordinator.close();
    }

    @Test
    void existingRootDefersUnavailableOptionalDerivedUntilScopedActivation() throws IOException {
        Path dataRoot = Files.createDirectory(temporary.resolve("existing-derived"));
        Path authoritativeRoot = Files.createDirectory(dataRoot.resolve("authoritative"));
        Path derivedRoot = Files.createDirectory(dataRoot.resolve("derived"));
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(
            dataRoot, temporary.resolve("existing-derived-coordination"), new MigrationFence());
        StartupParticipant authoritative = new StartupParticipant(
            "authoritative", authoritativeRoot, "authoritative", PersistenceParticipantClassification.AUTHORITATIVE,
            new AtomicBoolean(true));
        AtomicBoolean derivedAvailable = new AtomicBoolean();
        StartupParticipant derived = new StartupParticipant(
            "derived", derivedRoot, "derived", PersistenceParticipantClassification.DERIVED_CACHE, derivedAvailable);
        coordinator.register(authoritative);
        coordinator.register(derived);
        PersistenceRootReadiness declared = new PersistenceRootReadiness(List.of(
            PersistenceRootReadiness.Owner.registered("authoritative", authoritativeRoot, true,
                PersistenceParticipantClassification.AUTHORITATIVE),
            PersistenceRootReadiness.Owner.unavailable("derived", derivedRoot, false,
                PersistenceParticipantClassification.DERIVED_CACHE, "Derived output requires startup rebuild")));

        ReSyncPersistenceCoordinator.ReadinessProof initial = coordinator.seal(declared);

        assertEquals(PersistenceRootReadiness.State.UNAVAILABLE, initial.readiness().owner("derived").state());
        assertEquals(1, authoritative.flushes.get());
        assertEquals(1, authoritative.resumes.get());
        assertEquals(1, authoritative.readinessChecks.get());
        assertEquals(0, derived.flushes.get());
        assertEquals(0, derived.resumes.get());
        assertEquals(0, derived.healthChecks.get());
        assertEquals(0, derived.readinessChecks.get());
        assertFalse(derived.open.get());
        assertTrue(coordinator.publishReadinessCertificate(initial).isEmpty());
        assertTrue(coordinator.currentValidatedReadinessProof(initial.readiness()).isPresent());
        assertThrows(IllegalStateException.class, coordinator::completeStartupActivation);
        assertThrows(IllegalStateException.class,
            () -> coordinator.completeStartupActivation(initial, Set.of("derived")));
        assertEquals(0, derived.resumes.get());

        AtomicInteger preparations = new AtomicInteger();
        AtomicReference<ReSyncPersistenceCoordinator.DerivedStartupContext> preparedContext = new AtomicReference<>();
        assertTrue(Arrays.stream(ReSyncPersistenceCoordinator.DerivedStartupContext.class.getDeclaredConstructors())
            .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
        ReSyncPersistenceCoordinator.ReadinessProof activated = coordinator.completeStartupActivation(
            initial, Set.of("derived"), context -> {
                preparations.incrementAndGet();
                preparedContext.set(context);
                context.verify();
                context.requireOwner("derived");
                context.requireActiveRoot(initial.activeRoot());
                assertThrows(IllegalStateException.class, () -> context.requireOwner("authoritative"));
                assertThrows(IllegalStateException.class,
                    () -> context.requireActiveRoot(initial.activeRoot().resolve("other")));
                derivedAvailable.set(true);
            });

        assertEquals(1, preparations.get());
        assertEquals(Set.of("derived"), preparedContext.get().owners());
        assertEquals(initial.activeRoot(), preparedContext.get().activeRoot());
        assertEquals(initial.generation(), preparedContext.get().generation());
        assertEquals(initial.topologyEpoch(), preparedContext.get().topologyEpoch());
        assertEquals(initial.authorityEpoch(), preparedContext.get().authorityEpoch());
        assertTrue(preparedContext.get().preparationGeneration() > 0L);
        assertThrows(IllegalStateException.class, preparedContext.get()::verify);
        assertThrows(IllegalStateException.class, () -> preparedContext.get().requireOwner("derived"));
        assertThrows(IllegalStateException.class,
            () -> preparedContext.get().requireActiveRoot(initial.activeRoot()));
        assertEquals(PersistenceRootReadiness.State.REGISTERED, activated.readiness().owner("derived").state());
        assertEquals(1, derived.resumes.get());
        assertEquals(1, derived.healthChecks.get());
        assertEquals(1, derived.readinessChecks.get());
        assertTrue(derived.open.get());
        assertTrue(coordinator.restoreReady());
        coordinator.close();
    }

    @Test
    void promotesPendingDerivedOwnersByExactModuleSubsets() throws IOException {
        Path dataRoot = Files.createDirectory(temporary.resolve("derived-subsets"));
        Path authoritativeRoot = Files.createDirectory(dataRoot.resolve("authoritative"));
        Path generatedRoot = Files.createDirectory(dataRoot.resolve("generated"));
        Path installedRoot = Files.createDirectory(dataRoot.resolve("installed"));
        Path otherRoot = Files.createDirectory(dataRoot.resolve("other"));
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(
            dataRoot, temporary.resolve("derived-subsets-coordination"), new MigrationFence());
        StartupParticipant authoritative = new StartupParticipant(
            "authoritative", authoritativeRoot, "authoritative", PersistenceParticipantClassification.AUTHORITATIVE,
            new AtomicBoolean(true));
        AtomicBoolean generatedAvailable = new AtomicBoolean();
        AtomicBoolean installedAvailable = new AtomicBoolean();
        AtomicBoolean otherAvailable = new AtomicBoolean();
        StartupParticipant generated = new StartupParticipant(
            "worldgen.generated", generatedRoot, "generated", PersistenceParticipantClassification.DERIVED_CACHE,
            generatedAvailable);
        StartupParticipant installed = new StartupParticipant(
            "worldgen.installed", installedRoot, "installed", PersistenceParticipantClassification.DERIVED_CACHE,
            installedAvailable);
        StartupParticipant other = new StartupParticipant(
            "other.derived", otherRoot, "other", PersistenceParticipantClassification.DERIVED_CACHE, otherAvailable);
        coordinator.register(authoritative);
        coordinator.register(generated);
        coordinator.register(installed);
        coordinator.register(other);
        PersistenceRootReadiness declared = new PersistenceRootReadiness(List.of(
            PersistenceRootReadiness.Owner.registered("authoritative", authoritativeRoot, true,
                PersistenceParticipantClassification.AUTHORITATIVE),
            PersistenceRootReadiness.Owner.unavailable("worldgen.generated", generatedRoot, false,
                PersistenceParticipantClassification.DERIVED_CACHE, "Generated output requires repair"),
            PersistenceRootReadiness.Owner.unavailable("worldgen.installed", installedRoot, false,
                PersistenceParticipantClassification.DERIVED_CACHE, "Installed output requires repair"),
            PersistenceRootReadiness.Owner.unavailable("other.derived", otherRoot, false,
                PersistenceParticipantClassification.DERIVED_CACHE, "Other output requires repair")));

        ReSyncPersistenceCoordinator.ReadinessProof initial = coordinator.seal(declared);
        AtomicInteger preparations = new AtomicInteger();
        ReSyncPersistenceCoordinator.ReadinessProof empty = coordinator.completeStartupActivation(
            initial, Set.of(), context -> preparations.incrementAndGet());

        assertTrue(empty == initial);
        assertEquals(0, preparations.get());
        assertTrue(coordinator.publishReadinessCertificate(initial).isEmpty());
        assertThrows(IllegalStateException.class, () -> coordinator.completeStartupActivation(
            initial, Set.of("authoritative"), context -> preparations.incrementAndGet()));
        assertEquals(0, preparations.get());

        ReSyncPersistenceCoordinator.ReadinessProof generatedProof = coordinator.completeStartupActivation(
            initial, Set.of("worldgen.generated"), context -> {
                assertEquals(Set.of("worldgen.generated"), context.owners());
                generatedAvailable.set(true);
                preparations.incrementAndGet();
            });

        assertEquals(PersistenceRootReadiness.State.REGISTERED,
            generatedProof.readiness().owner("worldgen.generated").state());
        assertEquals(PersistenceRootReadiness.State.UNAVAILABLE,
            generatedProof.readiness().owner("worldgen.installed").state());
        assertEquals(PersistenceRootReadiness.State.UNAVAILABLE,
            generatedProof.readiness().owner("other.derived").state());
        assertTrue(coordinator.publishReadinessCertificate(generatedProof).isEmpty());
        assertThrows(IllegalStateException.class, () -> coordinator.completeStartupActivation(
            generatedProof, Set.of("worldgen.generated"), context -> preparations.incrementAndGet()));

        ReSyncPersistenceCoordinator.ReadinessProof installedProof = coordinator.completeStartupActivation(
            generatedProof, Set.of("worldgen.installed"), context -> {
                assertEquals(Set.of("worldgen.installed"), context.owners());
                installedAvailable.set(true);
                preparations.incrementAndGet();
            });

        assertEquals(PersistenceRootReadiness.State.REGISTERED,
            installedProof.readiness().owner("worldgen.installed").state());
        assertEquals(PersistenceRootReadiness.State.UNAVAILABLE,
            installedProof.readiness().owner("other.derived").state());
        assertTrue(coordinator.publishReadinessCertificate(installedProof).isEmpty());

        ReSyncPersistenceCoordinator.ReadinessProof complete = coordinator.completeStartupActivation(
            installedProof, Set.of("other.derived"), context -> {
                assertEquals(Set.of("other.derived"), context.owners());
                otherAvailable.set(true);
                preparations.incrementAndGet();
            });

        assertEquals(3, preparations.get());
        assertEquals(PersistenceRootReadiness.State.REGISTERED,
            complete.readiness().owner("other.derived").state());
        assertTrue(coordinator.publishReadinessCertificate(complete).isPresent());
        assertTrue(coordinator.restoreReady());
        coordinator.close();
    }

    @Test
    void defersOnlyUnavailableDerivedOwnerWhenSiblingIsAlreadyHealthy() throws IOException {
        Path dataRoot = Files.createDirectory(temporary.resolve("mixed-derived"));
        Path authoritativeRoot = Files.createDirectory(dataRoot.resolve("authoritative"));
        Path healthyRoot = Files.createDirectory(dataRoot.resolve("healthy"));
        Path unavailableRoot = Files.createDirectory(dataRoot.resolve("unavailable"));
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(
            dataRoot, temporary.resolve("mixed-derived-coordination"), new MigrationFence());
        StartupParticipant authoritative = new StartupParticipant(
            "authoritative", authoritativeRoot, "authoritative", PersistenceParticipantClassification.AUTHORITATIVE,
            new AtomicBoolean(true));
        StartupParticipant healthy = new StartupParticipant(
            "healthy.derived", healthyRoot, "healthy", PersistenceParticipantClassification.DERIVED_CACHE,
            new AtomicBoolean(true));
        AtomicBoolean unavailableReady = new AtomicBoolean();
        StartupParticipant unavailable = new StartupParticipant(
            "unavailable.derived", unavailableRoot, "unavailable", PersistenceParticipantClassification.DERIVED_CACHE,
            unavailableReady);
        coordinator.register(authoritative);
        coordinator.register(healthy);
        coordinator.register(unavailable);
        PersistenceRootReadiness declared = new PersistenceRootReadiness(List.of(
            PersistenceRootReadiness.Owner.registered("authoritative", authoritativeRoot, true,
                PersistenceParticipantClassification.AUTHORITATIVE),
            PersistenceRootReadiness.Owner.registered("healthy.derived", healthyRoot, false,
                PersistenceParticipantClassification.DERIVED_CACHE),
            PersistenceRootReadiness.Owner.unavailable("unavailable.derived", unavailableRoot, false,
                PersistenceParticipantClassification.DERIVED_CACHE, "Derived output requires repair")));

        ReSyncPersistenceCoordinator.ReadinessProof initial = coordinator.seal(declared);

        assertEquals(1, healthy.flushes.get());
        assertEquals(1, healthy.resumes.get());
        assertEquals(0, unavailable.flushes.get());
        assertEquals(0, unavailable.resumes.get());
        assertThrows(IllegalStateException.class, () -> coordinator.completeStartupActivation(
            initial, Set.of("healthy.derived"), context -> {
            }));
        ReSyncPersistenceCoordinator.ReadinessProof complete = coordinator.completeStartupActivation(
            initial, Set.of("unavailable.derived"), context -> unavailableReady.set(true));

        assertEquals(PersistenceRootReadiness.State.REGISTERED,
            complete.readiness().owner("healthy.derived").state());
        assertEquals(PersistenceRootReadiness.State.REGISTERED,
            complete.readiness().owner("unavailable.derived").state());
        assertTrue(coordinator.publishReadinessCertificate(complete).isPresent());
        coordinator.close();
    }

    @Test
    void emptyActivationStrictlyFinalizesWhenNoDerivedOwnerIsPending() throws IOException {
        Path dataRoot = Files.createDirectory(temporary.resolve("healthy-derived"));
        Path authoritativeRoot = Files.createDirectory(dataRoot.resolve("authoritative"));
        Path derivedRoot = Files.createDirectory(dataRoot.resolve("derived"));
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(
            dataRoot, temporary.resolve("healthy-derived-coordination"), new MigrationFence());
        StartupParticipant authoritative = new StartupParticipant(
            "authoritative", authoritativeRoot, "authoritative", PersistenceParticipantClassification.AUTHORITATIVE,
            new AtomicBoolean(true));
        StartupParticipant derived = new StartupParticipant(
            "healthy.derived", derivedRoot, "derived", PersistenceParticipantClassification.DERIVED_CACHE,
            new AtomicBoolean(true));
        coordinator.register(authoritative);
        coordinator.register(derived);
        PersistenceRootReadiness declared = new PersistenceRootReadiness(List.of(
            PersistenceRootReadiness.Owner.registered("authoritative", authoritativeRoot, true,
                PersistenceParticipantClassification.AUTHORITATIVE),
            PersistenceRootReadiness.Owner.registered("healthy.derived", derivedRoot, false,
                PersistenceParticipantClassification.DERIVED_CACHE)));
        ReSyncPersistenceCoordinator.ReadinessProof initial = coordinator.seal(declared);
        AtomicInteger preparations = new AtomicInteger();
        int healthChecksBeforeActivation = authoritative.healthChecks.get() + derived.healthChecks.get();
        int readinessChecksBeforeActivation = authoritative.readinessChecks.get() + derived.readinessChecks.get();

        ReSyncPersistenceCoordinator.ReadinessProof finalized = coordinator.completeStartupActivation(
            initial, Set.of(), context -> preparations.incrementAndGet());

        assertEquals(0, preparations.get());
        assertEquals(healthChecksBeforeActivation, authoritative.healthChecks.get() + derived.healthChecks.get());
        assertEquals(readinessChecksBeforeActivation + 2,
            authoritative.readinessChecks.get() + derived.readinessChecks.get());
        assertTrue(finalized.derivationGeneration() > initial.derivationGeneration());
        assertTrue(coordinator.publishReadinessCertificate(finalized).isPresent());
        assertTrue(coordinator.restoreReady());
        coordinator.close();
    }

    @Test
    void emptyActivationRejectsParticipantThatBecameUnreadyAfterSeal() throws IOException {
        Path dataRoot = Files.createDirectory(temporary.resolve("empty-activation-readiness"));
        Path authoritativeRoot = Files.createDirectory(dataRoot.resolve("authoritative"));
        Path derivedRoot = Files.createDirectory(dataRoot.resolve("derived"));
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(
            dataRoot, temporary.resolve("empty-activation-readiness-coordination"), new MigrationFence());
        AtomicBoolean authoritativeAvailable = new AtomicBoolean(true);
        AtomicBoolean derivedAvailable = new AtomicBoolean(true);
        coordinator.register(new StartupParticipant(
            "authoritative", authoritativeRoot, "authoritative", PersistenceParticipantClassification.AUTHORITATIVE,
            authoritativeAvailable));
        coordinator.register(new StartupParticipant(
            "healthy.derived", derivedRoot, "derived", PersistenceParticipantClassification.DERIVED_CACHE,
            derivedAvailable));
        PersistenceRootReadiness declared = new PersistenceRootReadiness(List.of(
            PersistenceRootReadiness.Owner.registered("authoritative", authoritativeRoot, true,
                PersistenceParticipantClassification.AUTHORITATIVE),
            PersistenceRootReadiness.Owner.registered("healthy.derived", derivedRoot, false,
                PersistenceParticipantClassification.DERIVED_CACHE)));
        ReSyncPersistenceCoordinator.ReadinessProof initial = coordinator.seal(declared);
        derivedAvailable.set(false);

        assertThrows(IllegalStateException.class,
            () -> coordinator.completeStartupActivation(initial, Set.of(), context -> {
            }));
        assertTrue(coordinator.currentValidatedReadinessProof(initial.readiness()).isPresent());

        derivedAvailable.set(true);
        ReSyncPersistenceCoordinator.ReadinessProof finalized = coordinator.completeStartupActivation(
            initial, Set.of(), context -> {
            });

        assertTrue(finalized.derivationGeneration() > initial.derivationGeneration());
        assertTrue(coordinator.publishReadinessCertificate(finalized).isPresent());
        coordinator.close();
    }

    @Test
    void failedDerivedPreparationPreservesThePendingProofAndQuiescedScope() throws IOException {
        DeferredStartupFixture fixture = deferredStartupFixture("failed-preparation");
        AtomicInteger preparations = new AtomicInteger();

        assertThrows(IllegalStateException.class, () -> fixture.coordinator.completeStartupActivation(
            fixture.proof, Set.of("derived"), context -> {
                preparations.incrementAndGet();
                context.requireOwner("derived");
                throw new IOException("preparation failed");
            }));

        assertEquals(1, preparations.get());
        assertFalse(fixture.derived.open.get());
        assertEquals(0, fixture.derived.resumes.get());
        assertTrue(fixture.coordinator.currentValidatedReadinessProof(fixture.proof.readiness()).isPresent());
        assertEquals(PersistenceRootReadiness.State.UNAVAILABLE, fixture.proof.readiness().owner("derived").state());

        assertThrows(IllegalStateException.class, () -> fixture.coordinator.completeStartupActivation(
            fixture.proof, Set.of("derived"), context -> context.verify()));
        assertEquals(1, fixture.derived.resumes.get());
        assertFalse(fixture.derived.open.get());
        assertTrue(fixture.coordinator.currentValidatedReadinessProof(fixture.proof.readiness()).isPresent());

        ReSyncPersistenceCoordinator.ReadinessProof activated = fixture.coordinator.completeStartupActivation(
            fixture.proof, Set.of("derived"), context -> fixture.derivedAvailable.set(true));
        assertEquals(PersistenceRootReadiness.State.REGISTERED, activated.readiness().owner("derived").state());
        fixture.coordinator.close();
    }

    @Test
    void staleProofAndWrongDerivedOwnerFailBeforePreparation() throws IOException {
        DeferredStartupFixture fixture = deferredStartupFixture("invalid-preparation");
        AtomicInteger preparations = new AtomicInteger();

        assertThrows(IllegalStateException.class, () -> fixture.coordinator.completeStartupActivation(
            fixture.proof, Set.of("authoritative"), context -> preparations.incrementAndGet()));
        assertEquals(0, preparations.get());
        assertTrue(fixture.coordinator.currentValidatedReadinessProof(fixture.proof.readiness()).isPresent());

        fixture.coordinator.invalidateReadinessCertificate();
        assertThrows(IllegalStateException.class, () -> fixture.coordinator.completeStartupActivation(
            fixture.proof, Set.of("derived"), context -> preparations.incrementAndGet()));
        assertEquals(0, preparations.get());
        assertFalse(fixture.derived.open.get());
    }

    @Test
    void heldMutationLeaseRejectsDerivedPreparationBeforeCallback() throws IOException {
        DeferredStartupFixture fixture = deferredStartupFixture("mutation-held-preparation");
        AtomicInteger preparations = new AtomicInteger();

        try (MigrationFence.MutationLease ignored = fixture.fence.beginMutation()) {
            assertThrows(IllegalStateException.class, () -> fixture.coordinator.completeStartupActivation(
                fixture.proof, Set.of("derived"), context -> preparations.incrementAndGet()));
        }

        assertEquals(0, preparations.get());
        assertTrue(fixture.coordinator.currentValidatedReadinessProof(fixture.proof.readiness()).isPresent());
        assertFalse(fixture.derived.open.get());
    }

    @Test
    void invokesEphemeralLifecycleAroundSnapshotsWithoutRegisteringAWriter() throws IOException {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-ephemeral"));
        Files.writeString(dataRoot.resolve("state.txt"), "state");
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(
            dataRoot, temporary.resolve("coordination-ephemeral"), new MigrationFence());
        coordinator.register(rebindableParticipant("resync", dataRoot));
        List<String> events = new ArrayList<>();
        coordinator.registerEphemeral(new EphemeralLifecycleParticipant() {
            @Override
            public String owner() {
                return "resync.jobs.ephemeral";
            }

            @Override
            public CompletionStage<Void> prepareSnapshot() {
                events.add("prepare");
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletionStage<Void> prepareRestore() {
                events.add("prepare-restore");
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void resumeAfterSnapshot() {
                events.add("resume");
            }

            @Override
            public void resumeAfterRestore() {
                events.add("resume-restore");
            }

            @Override
            public Health lifecycleHealth() {
                return new Health(true, "QUIESCED", 0, "");
            }
        });
        coordinator.seal();

        coordinator.createSnapshot(coordinator.snapshotRoot().resolve("ephemeral"), metadata("ephemeral"));

        assertEquals(List.of("prepare", "resume"), events);
        assertEquals(List.of("resync.jobs.ephemeral"), coordinator.ephemeralParticipants().stream()
            .map(EphemeralLifecycleParticipant::owner).toList());
        assertEquals(1, coordinator.registeredParticipants().size());
    }

    @Test
    void rejectsUseBeforeSealAndRegistrationAfterSeal() throws IOException {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync"));
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(dataRoot, temporary.resolve("coordination"), new MigrationFence());
        assertThrows(IllegalStateException.class, () -> coordinator.preflightSnapshot(temporary.resolve("snapshot"), 0));

        coordinator.register(rebindableParticipant("resync", dataRoot));
        coordinator.seal();

        assertThrows(IllegalStateException.class, () -> coordinator.register(participant("second", dataRoot.resolve("nested"))));
    }

    @Test
    void requiresARegisteredParticipantBeforeSealing() throws IOException {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync"));
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(dataRoot, temporary.resolve("coordination"), new MigrationFence());

        assertThrows(MigrationException.class, coordinator::seal);
        assertFalse(coordinator.sealed());
    }

    @Test
    void rejectsAnUnregisteredAuthorityWithTheSameOwnerAndRoot() throws IOException {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-authority-identity"));
        CatalogPublicationReceiptStore registered = new CatalogPublicationReceiptStore(dataRoot,
            dataRoot.resolve(CatalogPublicationReceiptStore.FILE_NAME));
        CatalogPublicationReceiptStore alternate = new CatalogPublicationReceiptStore(dataRoot,
            dataRoot.resolve(CatalogPublicationReceiptStore.FILE_NAME));
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(dataRoot,
            temporary.resolve("coordination-authority-identity"), new MigrationFence());
        coordinator.register(registered);
        coordinator.seal();

        coordinator.requireRegisteredParticipant(registered);
        assertThrows(IllegalStateException.class, () -> coordinator.requireRegisteredParticipant(alternate));
        coordinator.close();
    }

    @Test
    void rejectsSealingWhenAFileHasNoParticipantOwner() throws IOException {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-unowned"));
        Path participantRoot = Files.createDirectory(dataRoot.resolve("state"));
        Files.writeString(dataRoot.resolve("unowned.txt"), "unowned");
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(dataRoot, temporary.resolve("coordination-unowned"), new MigrationFence());
        coordinator.register(participant("state", participantRoot));

        assertThrows(MigrationException.class, coordinator::seal);
        assertFalse(coordinator.sealed());
    }

    @Test
    void sealsSnapshotRegistryWithoutRestoreReadiness() throws IOException {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync"));
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(dataRoot, temporary.resolve("coordination"), new MigrationFence());
        coordinator.register(participant("resync", dataRoot));

        coordinator.seal();
        assertTrue(coordinator.sealed());
        assertFalse(coordinator.restoreReady());
    }

    @Test
    void latchesRestoreFailureWithoutDisablingSnapshots() throws IOException {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-fault"));
        Files.writeString(dataRoot.resolve("state.txt"), "state");
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(dataRoot, temporary.resolve("coordination-fault"), new MigrationFence());
        coordinator.register(rebindableParticipant("resync", dataRoot));
        coordinator.seal();

        Snapshot target = coordinator.createSnapshot(coordinator.snapshotRoot().resolve("target"), metadata("target"));
        RestoreRequest request = new RestoreRequest(
            target,
            new RestoreCompatibilityPolicy(1, 1, Set.of("other-build"), new CatalogVersion(1, 0), new CatalogBinding(1, CATALOG_CHECKSUM, CATALOG_CHECKSUM), Map.of()),
            target.root().resolveSibling("current"),
            metadata("current"),
            target.root().resolveSibling("restore"),
            target.root().resolveSibling("journal"));

        assertThrows(MigrationException.class, () -> coordinator.restore(request));
        assertFalse(coordinator.restoreReady());
        assertTrue(coordinator.createSnapshot(coordinator.snapshotRoot().resolve("after-failure"), metadata("after-failure")).verified());
    }

    @Test
    void lowLevelActivationCannotBypassAuthorityRootConvergence() throws IOException {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-direct-restore"));
        Files.writeString(dataRoot.resolve("state.txt"), "state");
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(dataRoot, temporary.resolve("coordination-direct-restore"), new MigrationFence());
        coordinator.register(rebindableParticipant("resync", dataRoot));
        coordinator.seal();
        assertTrue(coordinator.restoreReady());

        Snapshot target = coordinator.createSnapshot(coordinator.snapshotRoot().resolve("target"), metadata("target"));
        StagedMigration initial = coordinator.activation().stage(target, coordinator.coordinationRoot().resolve("initial-active"));
        coordinator.activation().activate(initial);
        coordinator.participants().rebindAll(initial.root());
        coordinator.activation().converged(initial.root());

        assertFalse(coordinator.restoreReady());
    }

    @Test
    void rejectsParticipantOutsideTheReSyncRoot() throws IOException {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync"));
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(dataRoot, temporary.resolve("coordination"), new MigrationFence());

        assertThrows(IllegalArgumentException.class, () -> coordinator.register(participant("outside", outside)));
    }

    @Test
    void registersBatchFromOneActiveRootObservationAndInvalidatesReadinessOnce() throws IOException {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-registration-batch"));
        Path firstRoot = Files.createDirectory(dataRoot.resolve("first"));
        Path secondRoot = Files.createDirectory(dataRoot.resolve("second"));
        AtomicInteger activeRootObservations = new AtomicInteger();
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(
            dataRoot, temporary.resolve("coordination-registration-batch"), new MigrationFence(), () -> {
                activeRootObservations.incrementAndGet();
                return Optional.empty();
            });

        coordinator.registerAll(List.of(participant("second", secondRoot), participant("first", firstRoot)));

        assertEquals(1, activeRootObservations.get());
        assertEquals(1L, coordinator.readinessGeneration());
        assertEquals(List.of("first", "second"), coordinator.registeredParticipants().stream()
            .map(PersistenceParticipant::owner).toList());
    }

    @Test
    void rejectsOutOfScopeBatchWithoutPartialRegistrationOrReadinessInvalidation() throws IOException {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-registration-outside"));
        Path validRoot = Files.createDirectory(dataRoot.resolve("valid"));
        Path outsideRoot = Files.createDirectory(temporary.resolve("outside-registration-batch"));
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(
            dataRoot, temporary.resolve("coordination-registration-outside"), new MigrationFence());

        assertThrows(IllegalArgumentException.class, () -> coordinator.registerAll(List.of(
            participant("valid", validRoot), participant("outside", outsideRoot))));

        assertTrue(coordinator.registeredParticipants().isEmpty());
        assertEquals(0L, coordinator.readinessGeneration());
    }

    @Test
    void rejectsInvalidBatchWithoutPartialRegistrationOrReadinessInvalidation() throws IOException {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-registration-invalid"));
        Path validRoot = Files.createDirectory(dataRoot.resolve("valid"));
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(
            dataRoot, temporary.resolve("coordination-registration-invalid"), new MigrationFence());

        assertThrows(NullPointerException.class, () -> coordinator.registerAll(Arrays.asList(
            participant("valid", validRoot), null)));

        assertTrue(coordinator.registeredParticipants().isEmpty());
        assertEquals(0L, coordinator.readinessGeneration());
    }

    @Test
    void rejectsDuplicateOwnerBatchWithoutPartialRegistrationOrReadinessInvalidation() throws IOException {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-registration-duplicate"));
        Path firstRoot = Files.createDirectory(dataRoot.resolve("first"));
        Path secondRoot = Files.createDirectory(dataRoot.resolve("second"));
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(
            dataRoot, temporary.resolve("coordination-registration-duplicate"), new MigrationFence());

        assertThrows(IllegalArgumentException.class, () -> coordinator.registerAll(List.of(
            participant("duplicate", firstRoot), participant("duplicate", secondRoot))));

        assertTrue(coordinator.registeredParticipants().isEmpty());
        assertEquals(0L, coordinator.readinessGeneration());
    }

    @Test
    void rejectsBatchConflictWithExistingTopologyWithoutPartialRegistration() throws IOException {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-registration-existing-conflict"));
        Path existingRoot = Files.createDirectory(dataRoot.resolve("existing"));
        Path overlappingRoot = Files.createDirectory(existingRoot.resolve("nested"));
        Path validRoot = Files.createDirectory(dataRoot.resolve("valid"));
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(
            dataRoot, temporary.resolve("coordination-registration-existing-conflict"), new MigrationFence());
        coordinator.register(participant("existing", existingRoot));
        long registrationGeneration = coordinator.readinessGeneration();

        assertThrows(IllegalArgumentException.class, () -> coordinator.registerAll(List.of(
            participant("valid", validRoot), participant("overlapping", overlappingRoot))));

        assertEquals(List.of("existing"), coordinator.registeredParticipants().stream()
            .map(PersistenceParticipant::owner).toList());
        assertEquals(registrationGeneration, coordinator.readinessGeneration());
    }

    @Test
    void rejectsPreparedActiveRootNodeInputOverlapWithoutPartialRegistrationAndAllowsRetry() throws IOException {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-registration-active-nodes"));
        Files.createDirectory(dataRoot.resolve("nodes"));
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(
            dataRoot, temporary.resolve("coordination-registration-active-nodes"), new MigrationFence());
        List<PersistenceExternalInput.Input> externalInputs = PersistenceExternalInput.forDataRoot(dataRoot);
        coordinator.registerExternalInputs(externalInputs);
        Path activeRoot = coordinator.prepareActiveRoot();
        Path validRoot = Files.createDirectory(activeRoot.resolve("valid"));
        long registrationGeneration = coordinator.readinessGeneration();

        assertThrows(IllegalArgumentException.class, () -> coordinator.registerAll(List.of(
            participant("valid", validRoot), participant("nodes", activeRoot.resolve("nodes")))));

        assertTrue(coordinator.registeredParticipants().isEmpty());
        assertEquals(registrationGeneration, coordinator.readinessGeneration());
        assertEquals(externalInputs, coordinator.participants().externalInputs());

        PersistenceParticipant valid = participant("valid", validRoot);
        coordinator.registerAll(List.of(valid));

        assertEquals(List.of(valid), coordinator.registeredParticipants());
        assertEquals(Math.addExact(registrationGeneration, 1L), coordinator.readinessGeneration());
    }

    @Test
    void rejectsPreparedActiveRootPropertiesInputOverlapWithoutPartialRegistrationAndAllowsRetry() throws IOException {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-registration-active-properties"));
        Files.writeString(dataRoot.resolve("resync.properties"), "enabled=true\n");
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(
            dataRoot, temporary.resolve("coordination-registration-active-properties"), new MigrationFence());
        List<PersistenceExternalInput.Input> externalInputs = PersistenceExternalInput.forDataRoot(dataRoot);
        coordinator.registerExternalInputs(externalInputs);
        Path activeRoot = coordinator.prepareActiveRoot();
        Path validRoot = Files.createDirectory(activeRoot.resolve("valid"));
        long registrationGeneration = coordinator.readinessGeneration();

        assertThrows(IllegalArgumentException.class, () -> coordinator.registerAll(List.of(
            participant("valid", validRoot), participant("properties", activeRoot.resolve("resync.properties")))));

        assertTrue(coordinator.registeredParticipants().isEmpty());
        assertEquals(registrationGeneration, coordinator.readinessGeneration());
        assertEquals(externalInputs, coordinator.participants().externalInputs());

        PersistenceParticipant valid = participant("valid", validRoot);
        coordinator.registerAll(List.of(valid));

        assertEquals(List.of(valid), coordinator.registeredParticipants());
        assertEquals(Math.addExact(registrationGeneration, 1L), coordinator.readinessGeneration());
    }

    @Test
    void rejectsOriginalNodeInputAfterActiveRootPreparationWithoutPartialRegistrationAndAllowsRetry() throws IOException {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-registration-original-nodes"));
        Path originalNodes = Files.createDirectory(dataRoot.resolve("nodes"));
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(
            dataRoot, temporary.resolve("coordination-registration-original-nodes"), new MigrationFence());
        coordinator.registerExternalInputs(PersistenceExternalInput.forDataRoot(dataRoot));
        Path activeRoot = coordinator.prepareActiveRoot();
        Path validRoot = Files.createDirectory(activeRoot.resolve("valid"));
        long registrationGeneration = coordinator.readinessGeneration();

        assertThrows(IllegalArgumentException.class, () -> coordinator.registerAll(List.of(
            participant("valid", validRoot), participant("nodes", originalNodes))));

        assertTrue(coordinator.registeredParticipants().isEmpty());
        assertEquals(registrationGeneration, coordinator.readinessGeneration());

        PersistenceParticipant valid = participant("valid", validRoot);
        coordinator.registerAll(List.of(valid));

        assertEquals(List.of(valid), coordinator.registeredParticipants());
        assertEquals(Math.addExact(registrationGeneration, 1L), coordinator.readinessGeneration());
    }

    @Test
    void rejectsOriginalPropertiesInputAfterActiveRootPreparationWithoutPartialRegistrationAndAllowsRetry() throws IOException {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-registration-original-properties"));
        Path originalProperties = Files.writeString(dataRoot.resolve("resync.properties"), "enabled=true\n");
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(
            dataRoot, temporary.resolve("coordination-registration-original-properties"), new MigrationFence());
        coordinator.registerExternalInputs(PersistenceExternalInput.forDataRoot(dataRoot));
        Path activeRoot = coordinator.prepareActiveRoot();
        Path validRoot = Files.createDirectory(activeRoot.resolve("valid"));
        long registrationGeneration = coordinator.readinessGeneration();

        assertThrows(IllegalArgumentException.class, () -> coordinator.registerAll(List.of(
            participant("valid", validRoot), participant("properties", originalProperties))));

        assertTrue(coordinator.registeredParticipants().isEmpty());
        assertEquals(registrationGeneration, coordinator.readinessGeneration());

        PersistenceParticipant valid = participant("valid", validRoot);
        coordinator.registerAll(List.of(valid));

        assertEquals(List.of(valid), coordinator.registeredParticipants());
        assertEquals(Math.addExact(registrationGeneration, 1L), coordinator.readinessGeneration());
    }

    @Test
    void commitsFrozenParticipantWitnessesWithoutReReadingMutableRoots() throws IOException {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-registration-frozen-witness"));
        Path firstRoot = Files.createDirectory(dataRoot.resolve("first"));
        Path secondRoot = Files.createDirectory(dataRoot.resolve("second"));
        AtomicInteger secondRootReads = new AtomicInteger();
        PersistenceParticipant first = participant("first", firstRoot);
        PersistenceParticipant second = new PersistenceParticipant() {
            @Override
            public String owner() {
                return "second";
            }

            @Override
            public Path root() {
                if (secondRootReads.incrementAndGet() != 1) {
                    throw new IllegalStateException("Participant root was read after its registration witness");
                }
                return secondRoot;
            }
        };
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(
            dataRoot, temporary.resolve("coordination-registration-frozen-witness"), new MigrationFence());

        coordinator.registerAll(List.of(first, second));

        assertEquals(1, secondRootReads.get());
        assertEquals(2, coordinator.registeredParticipants().size());
        assertTrue(coordinator.registeredParticipants().stream().anyMatch(participant -> participant == first));
        assertTrue(coordinator.registeredParticipants().stream().anyMatch(participant -> participant == second));
        assertEquals(1L, coordinator.readinessGeneration());
    }

    @Test
    void rejectsSymbolicLinkBatchWithoutPartialRegistrationOrReadinessInvalidation() throws IOException {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-registration-symlink"));
        Path validRoot = Files.createDirectory(dataRoot.resolve("valid"));
        Path targetRoot = Files.createDirectory(dataRoot.resolve("target"));
        Path linkedRoot = dataRoot.resolve("linked");
        assumeTrue(createSymbolicLink(linkedRoot, targetRoot));
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(
            dataRoot, temporary.resolve("coordination-registration-symlink"), new MigrationFence());

        assertThrows(IllegalArgumentException.class, () -> coordinator.registerAll(List.of(
            participant("valid", validRoot), participant("linked", linkedRoot))));

        assertTrue(coordinator.registeredParticipants().isEmpty());
        assertEquals(0L, coordinator.readinessGeneration());
    }

    @Test
    void singletonRegistrationUsesTheBatchBoundary() throws IOException {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-registration-singleton"));
        Path stateRoot = Files.createDirectory(dataRoot.resolve("state"));
        AtomicInteger activeRootObservations = new AtomicInteger();
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(
            dataRoot, temporary.resolve("coordination-registration-singleton"), new MigrationFence(), () -> {
                activeRootObservations.incrementAndGet();
                return Optional.empty();
            });

        coordinator.register(participant("state", stateRoot));

        assertEquals(1, activeRootObservations.get());
        assertEquals(List.of("state"), coordinator.registeredParticipants().stream()
            .map(PersistenceParticipant::owner).toList());
    }

    @Test
    void startupConvergenceRunsDefaultHealthCheckOnce() throws IOException {
        Path dataRoot = Files.createDirectory(temporary.resolve("single-startup-health"));
        AtomicInteger healthChecks = new AtomicInteger();
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(
            dataRoot, temporary.resolve("single-startup-health-coordination"), new MigrationFence());
        coordinator.register(countingParticipant("authoritative", dataRoot, healthChecks));

        coordinator.seal();

        assertEquals(1, healthChecks.get());
    }

    @Test
    void startupConvergenceRunsDistinctReadinessCheckOnce() throws IOException {
        Path dataRoot = Files.createDirectory(temporary.resolve("distinct-startup-readiness"));
        AtomicInteger healthChecks = new AtomicInteger();
        AtomicInteger readinessChecks = new AtomicInteger();
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(
            dataRoot, temporary.resolve("distinct-startup-readiness-coordination"), new MigrationFence());
        coordinator.register(distinctReadinessParticipant(dataRoot, healthChecks, readinessChecks));

        coordinator.seal();

        assertEquals(1, healthChecks.get());
        assertEquals(1, readinessChecks.get());
    }

    private PersistenceParticipant participant(String owner, Path root) {
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

    private boolean createSymbolicLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            return false;
        }
    }

    private DeferredStartupFixture deferredStartupFixture(String id) throws IOException {
        Path dataRoot = Files.createDirectory(temporary.resolve(id));
        Path authoritativeRoot = Files.createDirectory(dataRoot.resolve("authoritative"));
        Path derivedRoot = Files.createDirectory(dataRoot.resolve("derived"));
        MigrationFence fence = new MigrationFence();
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(
            dataRoot, temporary.resolve(id + "-coordination"), fence);
        StartupParticipant authoritative = new StartupParticipant(
            "authoritative", authoritativeRoot, "authoritative", PersistenceParticipantClassification.AUTHORITATIVE,
            new AtomicBoolean(true));
        AtomicBoolean derivedAvailable = new AtomicBoolean();
        StartupParticipant derived = new StartupParticipant(
            "derived", derivedRoot, "derived", PersistenceParticipantClassification.DERIVED_CACHE, derivedAvailable);
        coordinator.register(authoritative);
        coordinator.register(derived);
        PersistenceRootReadiness readiness = new PersistenceRootReadiness(List.of(
            PersistenceRootReadiness.Owner.registered("authoritative", authoritativeRoot, true,
                PersistenceParticipantClassification.AUTHORITATIVE),
            PersistenceRootReadiness.Owner.unavailable("derived", derivedRoot, false,
                PersistenceParticipantClassification.DERIVED_CACHE, "Derived output requires startup rebuild")));
        ReSyncPersistenceCoordinator.ReadinessProof proof = coordinator.seal(readiness);
        return new DeferredStartupFixture(coordinator, fence, proof, derived, derivedAvailable);
    }

    private void assertObservationUsesMonitor(Object monitor, Runnable observation) throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread observer = new Thread(() -> {
            started.countDown();
            try {
                observation.run();
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "resync-persistence-observer");
        observer.setDaemon(true);

        synchronized (monitor) {
            observer.start();
            assertTrue(started.await(5L, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
            while (observer.isAlive() && observer.getState() != Thread.State.BLOCKED
                && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(Thread.State.BLOCKED, observer.getState());
        }

        observer.join(TimeUnit.SECONDS.toMillis(5L));
        assertFalse(observer.isAlive());
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    private RebindablePersistenceParticipant rebindableParticipant(String owner, Path root) {
        return rebindableParticipant(owner, root, "");
    }

    private RebindablePersistenceParticipant rebindableParticipant(String owner, Path root, String relativeRoot) {
        return new RebindablePersistenceParticipant() {
            private Path activeRoot = root;

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
                this.activeRoot = relativeRoot.isEmpty() ? activeRoot : activeRoot.resolve(relativeRoot);
            }

            @Override
            public void healthCheck() {
            }
        };
    }

    private RebindablePersistenceParticipant unavailableDerivedParticipant(Path root) {
        return unavailableDerivedParticipant(root, new AtomicBoolean());
    }

    private RebindablePersistenceParticipant unavailableDerivedParticipant(Path root, AtomicBoolean available) {
        return new RebindablePersistenceParticipant() {
            private Path activeRoot = root;

            @Override
            public String owner() {
                return "derived";
            }

            @Override
            public Path root() {
                return activeRoot;
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
                this.activeRoot = activeRoot;
            }

            @Override
            public void healthCheck() throws IOException {
                if (!available.get()) {
                    throw new IOException("derived output unavailable");
                }
            }
        };
    }

    private RebindablePersistenceParticipant countingParticipant(String owner, Path root, AtomicInteger healthChecks) {
        return new RebindablePersistenceParticipant() {
            private Path activeRoot = root;

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
                this.activeRoot = activeRoot;
            }

            @Override
            public void healthCheck() {
                healthChecks.incrementAndGet();
            }
        };
    }

    private RebindablePersistenceParticipant distinctReadinessParticipant(Path root, AtomicInteger healthChecks,
                                                                          AtomicInteger readinessChecks) {
        return new RebindablePersistenceParticipant() {
            private Path activeRoot = root;

            @Override
            public String owner() {
                return "authoritative";
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
                this.activeRoot = activeRoot;
            }

            @Override
            public void healthCheck() {
                healthChecks.incrementAndGet();
            }

            @Override
            public void readinessCheck() {
                readinessChecks.incrementAndGet();
            }
        };
    }

    private static final class StartupParticipant implements RebindablePersistenceParticipant {
        private final String owner;
        private final String relativeRoot;
        private final PersistenceParticipantClassification classification;
        private final AtomicBoolean available;
        private final AtomicBoolean open = new AtomicBoolean(true);
        private final AtomicInteger flushes = new AtomicInteger();
        private final AtomicInteger resumes = new AtomicInteger();
        private final AtomicInteger healthChecks = new AtomicInteger();
        private final AtomicInteger readinessChecks = new AtomicInteger();
        private Path activeRoot;

        private StartupParticipant(String owner, Path root, String relativeRoot,
                                   PersistenceParticipantClassification classification, AtomicBoolean available) {
            this.owner = owner;
            this.activeRoot = root;
            this.relativeRoot = relativeRoot;
            this.classification = classification;
            this.available = available;
        }

        @Override
        public String owner() {
            return owner;
        }

        @Override
        public Path root() {
            return activeRoot;
        }

        @Override
        public PersistenceParticipantClassification classification() {
            return classification;
        }

        @Override
        public void flush() throws IOException {
            flushes.incrementAndGet();
            requireAvailable();
        }

        @Override
        public void quiesce() {
            open.set(false);
        }

        @Override
        public void resume() {
            resumes.incrementAndGet();
            open.set(true);
        }

        @Override
        public void rebind(Path activeRoot) {
            this.activeRoot = activeRoot.resolve(relativeRoot);
        }

        @Override
        public void healthCheck() throws IOException {
            healthChecks.incrementAndGet();
            requireAvailable();
        }

        @Override
        public void readinessCheck() throws IOException {
            readinessChecks.incrementAndGet();
            requireAvailable();
        }

        private void requireAvailable() throws IOException {
            if (!available.get()) {
                throw new IOException("Participant is unavailable");
            }
        }
    }

    private record DeferredStartupFixture(ReSyncPersistenceCoordinator coordinator, MigrationFence fence,
                                          ReSyncPersistenceCoordinator.ReadinessProof proof,
                                          StartupParticipant derived, AtomicBoolean derivedAvailable) {
    }

    private SnapshotMetadata metadata(String id) {
        return new SnapshotMetadata(1, id, Instant.parse("2026-01-01T00:00:00Z"), "resync-test", CATALOG_CHECKSUM, Map.of());
    }
}
