package restudio.resync.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogPublicationReceiptStore;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ServerId;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceParticipantRegistryTest {
    @TempDir
    Path temporary;

    @Test
    void firstRebindRestoresTheActualPreparedScopeWithoutAnOriginalReceiptFile() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source"));
        Path prepared = Files.createDirectory(temporary.resolve("prepared"));

        assertReceiptRollback(source, prepared, false);

        assertFalse(Files.exists(source.resolve(CatalogPublicationReceiptStore.FILE_NAME)));
    }

    @Test
    void firstRebindRestoresParticipantsThatStillStartAtTheOriginalScope() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source"));

        assertReceiptRollback(source, source, false);
    }

    @Test
    void laterRebindRestoresTheLastCommittedScopeNotTheOriginalParticipantDeclaration() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source"));
        Path prepared = Files.createDirectory(temporary.resolve("prepared"));

        assertReceiptRollback(source, prepared, true);

        assertFalse(Files.exists(source.resolve(CatalogPublicationReceiptStore.FILE_NAME)));
    }

    @Test
    void rejectsMixedOriginalAndPreparedScopesBeforeMovingAnyParticipant() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source"));
        Path prepared = Files.createDirectory(temporary.resolve("prepared"));
        Path target = Files.createDirectory(temporary.resolve("target"));
        List<String> calls = new ArrayList<>();
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(source);
        TestParticipant original = participant("original", source, target, calls, false, false);
        CatalogPublicationReceiptStore receipts = receiptStore(prepared, "prepared");
        try {
            registry.register(original);
            registry.register(receipts);

            assertThrows(MigrationException.class, () -> registry.rebindAll(target));

            assertTrue(calls.isEmpty());
            assertEquals(source.resolve("original-scope"), original.root());
            assertEquals(prepared.resolve(CatalogPublicationReceiptStore.FILE_NAME), receipts.path());
            assertEquals(PersistenceRebindStatus.notAttempted(), registry.rebindStatus());
            receipts.healthCheck();
        } finally {
            receipts.close();
        }
    }

    @Test
    void rollsBackTheFailedAndEarlierParticipantsInDeterministicOrder() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source"));
        Path target = Files.createDirectory(temporary.resolve("target"));
        List<String> calls = new ArrayList<>();
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(source);
        TestParticipant first = participant("first", source, target, calls, false, false);
        TestParticipant second = participant("second", source, target, calls, true, false);
        registry.register(first);
        registry.register(second);

        MigrationException failure = assertThrows(MigrationException.class, () -> registry.rebindAll(target));

        assertEquals(source.resolve("first-scope"), first.root());
        assertEquals(source.resolve("second-scope"), second.root());
        assertEquals(List.of("first:new", "second:new", "second:old", "first:old"), calls);
        assertEquals(PersistenceRebindStatus.State.ROLLED_BACK, registry.rebindStatus().state());
        assertEquals("second", registry.rebindStatus().failedOwner());
        assertEquals(List.of("first"), registry.rebindStatus().reboundOwners());
        assertEquals(List.of("second", "first"), registry.rebindStatus().rolledBackOwners());
        assertEquals(source, registry.rebindStatus().activeRoot().orElseThrow());
        assertEquals("ROLLED_BACK", registry.rebindStatus().payload().get("state"));
        assertEquals(List.of("second", "first"), registry.rebindStatus().payload().get("rolledBackOwners"));
        assertTrue(failure.getMessage().contains("previous participant roots restored"));
    }

    @Test
    void reportsAnInconsistentStateWhenCompensationFails() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source-inconsistent"));
        Path target = Files.createDirectory(temporary.resolve("target-inconsistent"));
        List<String> calls = new ArrayList<>();
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(source);
        TestParticipant first = participant("first", source, target, calls, false, true);
        TestParticipant second = participant("second", source, target, calls, true, false);
        registry.register(first);
        registry.register(second);

        MigrationException failure = assertThrows(MigrationException.class, () -> registry.rebindAll(target));

        assertEquals(target.resolve("first-scope"), first.root());
        assertEquals(source.resolve("second-scope"), second.root());
        assertEquals(PersistenceRebindStatus.State.INCONSISTENT, registry.rebindStatus().state());
        assertFalse(registry.rebindStatus().stable());
        assertEquals(List.of("first"), registry.rebindStatus().rollbackFailures().keySet().stream().toList());
        assertEquals("INCONSISTENT", registry.rebindStatus().payload().get("state"));
        assertEquals(Map.of("first", "rollback failed"), registry.rebindStatus().payload().get("rollbackFailures"));
        assertFalse(registry.rebindStatus().payload().containsKey("activeRoot"));
        assertTrue(failure.getMessage().contains("rollback failed for first"));
        assertThrows(MigrationException.class, () -> registry.rebindAll(target));
    }

    @Test
    void usesComponentBoundDefaultOwnershipAndFailsClosedForUnownedFiles() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("ownership-source"));
        Path databaseRoot = Files.createDirectory(source.resolve("database"));
        Path databaseFile = Files.createFile(databaseRoot.resolve("resource.db"));
        Path automationFile = Files.createFile(source.resolve("automation-tasks.json"));
        Path automationSidecar = Files.createFile(source.resolve("automation-tasks.json-wal"));
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(source);
        registry.register(defaultParticipant("database", databaseRoot));
        registry.register(defaultParticipant("automation", automationFile));

        assertEquals("database", registry.ownerFor(source, databaseFile));
        assertEquals("automation", registry.ownerFor(source, automationFile));
        assertThrows(MigrationException.class, () -> registry.ownerFor(source, automationSidecar));
    }

    @Test
    void skipsRetainedAssetHistoryDuringOwnershipScan() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("retained-history-source"));
        Path assets = Files.createDirectories(source.resolve("assets"));
        Path live = Files.writeString(assets.resolve("project.json"), "{}");
        Path journal = Files.createDirectories(assets.resolve(".transactions").resolve("tx-1"));
        Files.writeString(journal.resolve("journal.json"), "{\"entries\":[]}");
        Path snapshot = Files.createDirectories(assets.resolve(".snapshots").resolve("snap-1"));
        Files.writeString(snapshot.resolve("state.json"), "{}");
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(source);
        registry.register(new RebindableRootParticipant("assets", source, assets, assets));

        registry.validateForRestore(source);

        PersistenceParticipantRegistry.OwnershipScanMetrics metrics = registry.ownershipValidationMetrics().get("restore");
        assertEquals(1L, metrics.fileCount());
        assertEquals("assets", registry.ownerFor(source, live));
        assertEquals("assets", registry.ownerFor(source, journal.resolve("journal.json")));
        assertEquals("assets", registry.ownerFor(source, snapshot.resolve("state.json")));
    }

    @Test
    void usesTheValidatedParticipantRootSnapshotForDefaultOwnership() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("ownership-snapshot-source"));
        Path participantRoot = Files.createDirectory(source.resolve("participant"));
        Path value = Files.createFile(participantRoot.resolve("value.json"));
        AtomicInteger rootCalls = new AtomicInteger();
        PersistenceParticipant participant = new PersistenceParticipant() {
            @Override
            public String owner() {
                return "participant";
            }

            @Override
            public Path root() {
                rootCalls.incrementAndGet();
                return participantRoot;
            }
        };
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(source);
        registry.register(participant);

        assertEquals("participant", registry.ownerFor(source, value));
        assertEquals(2, rootCalls.get());
    }

    @Test
    void rechecksAnAbsentParticipantRootOnTheNextResolution() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("ownership-freshness-source"));
        Path participantRoot = source.resolve("optional");
        PersistenceParticipant participant = new PersistenceParticipant() {
            @Override
            public String owner() {
                return "optional";
            }

            @Override
            public Path root() {
                return participantRoot;
            }

            @Override
            public boolean rootMayBeAbsent() {
                return true;
            }
        };
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(source);
        registry.register(participant);
        assertDoesNotThrow(() -> registry.resolutionForRoot(source));

        Files.createDirectory(participantRoot);
        assertDoesNotThrow(() -> registry.resolutionForRoot(source));
    }

    @Test
    void compensatesQuiescedParticipantsAfterUncheckedFailureAndRetainsResumeFailures() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("quiesce-source"));
        Path firstRoot = Files.createDirectory(source.resolve("first"));
        Path secondRoot = Files.createDirectory(source.resolve("second"));
        List<String> calls = new ArrayList<>();
        RuntimeException quiesceFailure = new IllegalStateException("quiesce failed");
        IOException resumeFailure = new IOException("resume failed");
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(source);
        registry.register(quiesceParticipant("first", firstRoot, calls, null, resumeFailure));
        registry.register(quiesceParticipant("second", secondRoot, calls, quiesceFailure, null));

        MigrationException failure = assertThrows(MigrationException.class, registry::quiesceAll);

        assertSame(quiesceFailure, failure.getCause());
        assertEquals(List.of("first:quiesce", "second:quiesce", "first:resume"), calls);
        assertEquals(1, failure.getSuppressed().length);
        assertSame(resumeFailure, failure.getSuppressed()[0]);
    }

    @Test
    void quiescesEveryScopedParticipantWhenDerivedActivationFails() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("activation-source"));
        Path firstRoot = Files.createDirectory(source.resolve("first"));
        Path secondRoot = Files.createDirectory(source.resolve("second"));
        List<String> calls = new ArrayList<>();
        AtomicBoolean firstOpen = new AtomicBoolean();
        AtomicBoolean secondOpen = new AtomicBoolean();
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(source);
        registry.register(activationParticipant("first", firstRoot, calls, firstOpen, false));
        registry.register(activationParticipant("second", secondRoot, calls, secondOpen, true));

        assertThrows(MigrationException.class, () -> registry.activateAndValidate(
            Set.of("first", "second"), PersistenceParticipantClassification.DERIVED_CACHE));

        assertEquals(List.of("second:resume", "first:resume", "first:health", "first:readiness",
            "second:health", "first:quiesce", "second:quiesce"), calls);
        assertFalse(firstOpen.get());
        assertFalse(secondOpen.get());
    }

    @Test
    void rejectsCustomOwnershipThatClaimsAnotherParticipantRoot() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("overlap-source"));
        Path firstRoot = Files.createDirectory(source.resolve("first"));
        Path secondRoot = Files.createDirectory(source.resolve("second"));
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(source);
        registry.register(claimingParticipant("first", firstRoot, secondRoot));

        assertThrows(IllegalArgumentException.class,
            () -> registry.register(defaultParticipant("second", secondRoot)));
    }

    @Test
    void rejectsFilesWithMultipleCustomOwnersDuringOwnershipResolution() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("multiple-owner-source"));
        Path firstRoot = Files.createDirectory(source.resolve("first"));
        Path secondRoot = Files.createDirectory(source.resolve("second"));
        Path sharedFile = Files.createFile(source.resolve("shared.db"));
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(source);
        registry.register(claimingParticipant("first", firstRoot, sharedFile));
        registry.register(claimingParticipant("second", secondRoot, sharedFile));

        assertThrows(MigrationException.class, () -> registry.ownerFor(source, sharedFile));
        assertThrows(MigrationException.class, () -> registry.validateForRoot(source));
    }

    @Test
    void validatesCustomOwnershipOutsideDeclaredParticipantRoots() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("custom-ownership-source"));
        Path participantRoot = Files.createDirectory(source.resolve("participant"));
        Path customFile = Files.createFile(source.resolve("custom.db"));
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(source);
        registry.register(claimingParticipant("custom", participantRoot, customFile));

        assertEquals("custom", registry.ownerFor(source, customFile));
        assertDoesNotThrow(() -> registry.validateForRoot(source));
    }

    @Test
    void reusesValidatedOwnershipResolutionAndKeepsFilesystemValidationFresh() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("ownership-plan-source"));
        Path state = Files.writeString(source.resolve("state.txt"), "state");
        AtomicInteger indexBuilds = new AtomicInteger();
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(source);
        registry.register(new IndexedRebindableParticipant(source, state, indexBuilds));

        registry.validateForRestore(source);
        registry.validateForRestore(source);

        assertEquals(1, indexBuilds.get());
        Path unexpected = Files.writeString(source.resolve("unexpected.txt"), "unexpected");
        assertThrows(MigrationException.class, () -> registry.validateForRestore(source));
        assertEquals(1, indexBuilds.get());
        Files.delete(unexpected);
        assertDoesNotThrow(() -> registry.validateForRestore(source));
        assertEquals(1, indexBuilds.get());
    }

    @Test
    void reusesWitnessedOwnersWhileClassifyingNewPathsFailClosed() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("ownership-witness-source"));
        Path target = Files.createDirectory(temporary.resolve("ownership-witness-target"));
        Path sourceRoot = Files.createDirectory(source.resolve("state"));
        Path targetRoot = Files.createDirectory(target.resolve("state"));
        Files.writeString(sourceRoot.resolve("value.json"), "source");
        Files.writeString(targetRoot.resolve("value.json"), "target");
        AtomicInteger ownershipCalls = new AtomicInteger();
        CountingRebindableParticipant participant = new CountingRebindableParticipant(
            source, sourceRoot, targetRoot, ownershipCalls);
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(source);
        registry.register(participant);

        registry.rebindAll(target);
        int classifiedAfterRebind = ownershipCalls.get();
        registry.validateForRestore(target);

        assertEquals(Set.of("rebind", "restore"), registry.ownershipValidationMetrics().keySet());
        assertTrue(registry.ownershipValidationMetrics().get("rebind").complete());
        assertEquals(1L, registry.ownershipValidationMetrics().get("rebind").ownerResolutionCount());
        assertEquals(1L, registry.ownershipValidationMetrics().get("restore").witnessHitCount());
        assertEquals(classifiedAfterRebind, ownershipCalls.get());
        Files.writeString(targetRoot.resolve("added.json"), "added");
        registry.validateForRestore(target);
        assertEquals(classifiedAfterRebind + 1, ownershipCalls.get());
        registry.validateForRestore(target);
        assertEquals(classifiedAfterRebind + 1, ownershipCalls.get());

        Files.writeString(target.resolve("unowned.json"), "unowned");
        assertThrows(MigrationException.class, () -> registry.validateForRestore(target));
        assertEquals(classifiedAfterRebind + 2, ownershipCalls.get());
    }

    @Test
    void provisionalRebindCommitsOnlyAfterFinalOwnershipValidation() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("provisional-source"));
        Path target = Files.createDirectory(temporary.resolve("provisional-target"));
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(source);
        TestParticipant participant = participant("first", source, target, new ArrayList<>(), false, false);
        Files.writeString(target.resolve("first-scope").resolve("value.txt"), "target");
        registry.register(participant);

        registry.rebindAllProvisional(target);

        assertEquals(PersistenceRebindStatus.State.PROVISIONAL, registry.rebindStatus().state());
        assertFalse(registry.rebindStatus().stable());
        assertTrue(registry.rebindStatus().activeRoot().isEmpty());
        assertThrows(MigrationException.class, () -> registry.commitProvisionalRebind(target));
        assertTrue(registry.ownershipValidationMetrics().isEmpty());

        registry.validateForRestore(target);
        registry.commitProvisionalRebind(target);

        assertEquals(PersistenceRebindStatus.State.COMMITTED, registry.rebindStatus().state());
        assertEquals(target, registry.rebindStatus().activeRoot().orElseThrow());
        assertEquals(Set.of("restore"), registry.ownershipValidationMetrics().keySet());
    }

    @Test
    void keepsRestoreReadinessFreshAcrossExternalAndLifecycleMutation() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("restore-readiness-source"));
        Path state = Files.writeString(source.resolve("state.txt"), "state");
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(source);
        registry.register(new IndexedRebindableParticipant(source, state, new AtomicInteger()));

        registry.validateForRestore(source);
        Path unexpected = Files.writeString(source.resolve("unexpected.txt"), "unexpected");
        assertThrows(MigrationException.class, () -> registry.validateRestoreReadiness(source));
        Files.delete(unexpected);
        assertDoesNotThrow(() -> registry.validateRestoreReadiness(source));
        Files.writeString(unexpected, "unexpected");
        registry.resumeAll();

        assertThrows(MigrationException.class, () -> registry.validateRestoreReadiness(source));
    }

    @Test
    void rejectsIndexedRootSiblingClaimsAgainstParticipantRootsAndExternalInputs() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("root-sibling-boundary-source"));
        Path state = Files.createDirectory(source.resolve("state"));
        Path configuration = Files.createFile(state.resolve("config.properties"));
        Path temporaryRoot = Files.createDirectory(state.resolve("config.properties7.tmp"));

        PersistenceParticipantRegistry participantRegistry = new PersistenceParticipantRegistry(source);
        participantRegistry.register(rootSiblingParticipant("configuration", configuration));
        participantRegistry.register(defaultParticipant("temporary", temporaryRoot));
        assertThrows(MigrationException.class, () -> participantRegistry.resolutionForRoot(source));

        PersistenceParticipantRegistry externalRegistry = new PersistenceParticipantRegistry(source);
        externalRegistry.register(rootSiblingParticipant("configuration", configuration));
        externalRegistry.registerExternalInputs(List.of(new PersistenceExternalInput.Input(
            "temporary", temporaryRoot, PersistenceExternalInput.Kind.OPERATOR_CONFIGURATION,
            "temporary input")));
        assertThrows(MigrationException.class, () -> externalRegistry.resolutionForRoot(source));
    }

    @Test
    void rejectsRootSiblingClaimsAgainstAllIndexedClaimKinds() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("root-sibling-index-source"));
        Path state = Files.createDirectory(source.resolve("state"));
        Path configuration = Files.createFile(state.resolve("config.properties"));
        Path other = Files.createFile(state.resolve("config.properties7"));
        Path exactOwner = Files.createDirectory(source.resolve("exact-owner"));
        Path subtreeOwner = Files.createDirectory(source.resolve("subtree-owner"));
        Path directOwner = Files.createDirectory(source.resolve("direct-owner"));

        PersistenceParticipantRegistry exactRegistry = new PersistenceParticipantRegistry(source);
        exactRegistry.register(rootSiblingParticipant("configuration", configuration));
        exactRegistry.register(indexedParticipant("exact", exactOwner,
            PersistenceOwnershipIndex.builder().exact("state/config.properties7.tmp").build()));
        assertThrows(MigrationException.class, () -> exactRegistry.resolutionForRoot(source));

        PersistenceParticipantRegistry subtreeRegistry = new PersistenceParticipantRegistry(source);
        subtreeRegistry.register(rootSiblingParticipant("configuration", configuration));
        subtreeRegistry.register(indexedParticipant("subtree", subtreeOwner,
            PersistenceOwnershipIndex.builder().subtree("state/config.properties7.tmp").build()));
        assertThrows(MigrationException.class, () -> subtreeRegistry.resolutionForRoot(source));

        PersistenceParticipantRegistry directRegistry = new PersistenceParticipantRegistry(source);
        directRegistry.register(rootSiblingParticipant("configuration", configuration));
        directRegistry.register(indexedParticipant("direct", directOwner,
            PersistenceOwnershipIndex.builder(new PersistenceOwnershipContext(source, state))
                .directChildPrefix("config.properties").build()));
        assertThrows(MigrationException.class, () -> directRegistry.resolutionForRoot(source));

        PersistenceParticipantRegistry siblingRegistry = new PersistenceParticipantRegistry(source);
        siblingRegistry.register(rootSiblingParticipant("configuration", configuration));
        siblingRegistry.register(rootSiblingParticipant("other", other, "7.tmp"));
        assertThrows(MigrationException.class, () -> siblingRegistry.resolutionForRoot(source));
    }

    @Test
    void allowsAnExactDirectoryClaimAlongsideARootSiblingDescendantMatcher() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("root-sibling-directory-source"));
        Path identityRoot = Files.createDirectory(source.resolve("server-id"));
        Path authorityRoot = Files.createDirectory(source.resolve("authority-bundle"));
        PersistenceOwnershipIndex identityIndex = PersistenceOwnershipIndex.builder()
            .exact(".quarantine")
            .build();
        PersistenceOwnershipIndex authorityIndex = PersistenceOwnershipIndex.builder(
                new PersistenceOwnershipContext(source, authorityRoot))
            .rootSiblingHashJson(".quarantine/authority-bundle")
            .build();
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(source);
        registry.register(indexedParticipant("identity", identityRoot, identityIndex));
        registry.register(indexedParticipant("authority", authorityRoot, authorityIndex));

        assertTrue(identityIndex.owns(".quarantine"));
        assertTrue(authorityIndex.owns(
            ".quarantine/authority-bundle/0000000000000000000000000000000000000000000000000000000000000000.json"));
        assertDoesNotThrow(() -> registry.resolutionForRoot(source));
    }

    @Test
    void restoresSourceRootsWhenRebindValidationRejectsRootSiblingBoundary() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("root-sibling-rebind-source"));
        Path sourceState = Files.createDirectory(source.resolve("state"));
        Path sourceConfiguration = Files.createFile(sourceState.resolve("config.properties"));
        Path sourceOther = Files.createFile(sourceState.resolve("other.properties"));
        Path target = Files.createDirectory(temporary.resolve("root-sibling-rebind-target"));
        Path targetState = Files.createDirectory(target.resolve("state"));
        Path targetConfiguration = Files.createFile(targetState.resolve("config.properties"));
        Path targetOther = Files.createFile(targetState.resolve("config.properties7.tmp"));

        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(source);
        RebindableRootSiblingParticipant configuration = new RebindableRootSiblingParticipant(
            "configuration", source, sourceConfiguration, targetConfiguration);
        RebindableRootParticipant other = new RebindableRootParticipant(
            "other", source, sourceOther, targetOther);
        registry.register(configuration);
        registry.register(other);

        assertThrows(MigrationException.class, () -> registry.rebindAll(target));

        assertEquals(sourceConfiguration, configuration.root());
        assertEquals(sourceOther, other.root());
        assertEquals(PersistenceRebindStatus.State.ROLLED_BACK, registry.rebindStatus().state());
    }

    private static PersistenceParticipant defaultParticipant(String owner, Path root) {
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

    private static PersistenceParticipant claimingParticipant(String owner, Path root, Path claimedFile) {
        Path normalizedClaimedFile = claimedFile.toAbsolutePath().normalize();
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
            public boolean owns(Path file) {
                return file.toAbsolutePath().normalize().equals(normalizedClaimedFile);
            }
        };
    }

    private static PersistenceParticipant rootSiblingParticipant(String owner, Path root) {
        return rootSiblingParticipant(owner, root, ".tmp");
    }

    private static PersistenceParticipant rootSiblingParticipant(String owner, Path root, String suffix) {
        return new IndexedRootParticipant(owner, root, null, suffix);
    }

    private static PersistenceParticipant indexedParticipant(String owner, Path root,
                                                              PersistenceOwnershipIndex index) {
        return new IndexedRootParticipant(owner, root, index, null);
    }

    private static PersistenceParticipant quiesceParticipant(String owner, Path root, List<String> calls,
                                                             RuntimeException quiesceFailure, IOException resumeFailure) {
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
            public void quiesce() throws IOException {
                calls.add(owner + ":quiesce");
                if (quiesceFailure != null) {
                    throw quiesceFailure;
                }
            }

            @Override
            public void resume() throws IOException {
                calls.add(owner + ":resume");
                if (resumeFailure != null) {
                    throw resumeFailure;
                }
            }
        };
    }

    private static PersistenceParticipant activationParticipant(String owner, Path root, List<String> calls,
                                                                AtomicBoolean open, boolean failHealth) {
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
            public void quiesce() {
                calls.add(owner + ":quiesce");
                open.set(false);
            }

            @Override
            public void resume() {
                calls.add(owner + ":resume");
                open.set(true);
            }

            @Override
            public void healthCheck() throws IOException {
                calls.add(owner + ":health");
                if (failHealth) {
                    throw new IOException("health failed");
                }
            }

            @Override
            public void readinessCheck() {
                calls.add(owner + ":readiness");
            }
        };
    }

    private void assertReceiptRollback(Path source, Path startingScope, boolean moveFirst) throws Exception {
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(source);
        CatalogPublicationReceiptStore receipts = receiptStore(startingScope, "starting");
        try {
            Path startingFile = receipts.path();
            byte[] startingBytes = Files.readAllBytes(startingFile);
            registry.register(receipts);
            registry.quiesceAll();
            Path expectedScope = startingScope;
            if (moveFirst) {
                expectedScope = Files.createDirectory(temporary.resolve("committed"));
                CatalogPublicationReceiptStore committed = receiptStore(expectedScope, "committed");
                committed.close();
                registry.rebindAll(expectedScope);
                assertEquals(PersistenceRebindStatus.State.COMMITTED, registry.rebindStatus().state());
                assertEquals(expectedScope.resolve(CatalogPublicationReceiptStore.FILE_NAME), receipts.path());
                assertEquals(startingScope, receipts.rebindScope());
            }
            Path expectedFile = receipts.path();
            byte[] expectedBytes = Files.readAllBytes(expectedFile);
            var expectedReceipts = receipts.baselines();
            Path rejected = Files.createDirectory(temporary.resolve("rejected"));
            CatalogPublicationReceiptStore candidate = receiptStore(rejected, "rejected");
            try {
                assertFalse(expectedReceipts.equals(candidate.baselines()));
            } finally {
                candidate.close();
            }
            Path rejectedFile = rejected.resolve(CatalogPublicationReceiptStore.FILE_NAME);
            byte[] rejectedBytes = Files.readAllBytes(rejectedFile);
            Files.writeString(rejected.resolve("unowned.txt"), "unowned topology must reject this transition");

            MigrationException failure = assertThrows(MigrationException.class, () -> registry.rebindAll(rejected));

            assertTrue(failure.getMessage().contains("topology validation"));
            assertEquals(PersistenceRebindStatus.State.ROLLED_BACK, registry.rebindStatus().state());
            assertTrue(registry.rebindStatus().stable());
            assertEquals(expectedScope, registry.rebindStatus().activeRoot().orElseThrow());
            assertEquals(List.of(CatalogPublicationReceiptStore.OWNER), registry.rebindStatus().reboundOwners());
            assertEquals(List.of(CatalogPublicationReceiptStore.OWNER), registry.rebindStatus().rolledBackOwners());
            assertTrue(registry.rebindStatus().rollbackFailures().isEmpty());
            assertEquals(expectedFile, receipts.path());
            assertEquals(expectedReceipts, receipts.baselines());
            assertArrayEquals(startingBytes, Files.readAllBytes(startingFile));
            assertArrayEquals(expectedBytes, Files.readAllBytes(expectedFile));
            assertArrayEquals(rejectedBytes, Files.readAllBytes(rejectedFile));
            registry.resumeAll();
            receipts.healthCheck();
            assertFalse(receipts.isQuiesced());
        } finally {
            receipts.close();
        }
    }

    private CatalogPublicationReceiptStore receiptStore(Path scope, String session) {
        CatalogPublicationReceiptStore store = new CatalogPublicationReceiptStore(scope,
            scope.resolve(CatalogPublicationReceiptStore.FILE_NAME));
        CatalogCacheKey key = new CatalogCacheKey(new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111")),
            7, ContentHash.of("a".repeat(64)));
        store.recordDispatch(session, "owner-1", new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key, 1, List.of()));
        return store;
    }

    private TestParticipant participant(String owner, Path source, Path target, List<String> calls,
                                       boolean failTarget, boolean failRollback) throws IOException {
        Path sourceRoot = Files.createDirectory(source.resolve(owner + "-scope"));
        Path targetRoot = Files.createDirectory(target.resolve(owner + "-scope"));
        return new TestParticipant(owner, source, sourceRoot, targetRoot, calls, failTarget, failRollback);
    }

    private static final class TestParticipant implements RebindablePersistenceParticipant {
        private final String owner;
        private final Path source;
        private final Path sourceRoot;
        private final Path targetRoot;
        private final List<String> calls;
        private final boolean failTarget;
        private final boolean failRollback;
        private Path activeRoot;

        private TestParticipant(String owner, Path source, Path sourceRoot, Path targetRoot,
                                List<String> calls, boolean failTarget, boolean failRollback) {
            this.owner = owner;
            this.source = source;
            this.sourceRoot = sourceRoot;
            this.targetRoot = targetRoot;
            this.calls = calls;
            this.failTarget = failTarget;
            this.failRollback = failRollback;
            this.activeRoot = sourceRoot;
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
        public void flush() {
        }

        @Override
        public void quiesce() {
        }

        @Override
        public void resume() {
        }

        @Override
        public void rebind(Path activeScope) throws IOException {
            boolean restoring = activeScope.equals(source);
            calls.add(owner + ":" + (restoring ? "old" : "new"));
            if (restoring && failRollback) {
                throw new IOException("rollback failed");
            }
            activeRoot = activeScope.equals(source) ? sourceRoot : targetRoot;
            if (!restoring && failTarget) {
                throw new IOException("target rebind failed");
            }
        }

        @Override
        public void healthCheck() {
        }
    }

    private static class RebindableRootParticipant implements RebindablePersistenceParticipant {
        private final String owner;
        private final Path sourceScope;
        private final Path sourceRoot;
        private final Path targetRoot;
        private Path activeRoot;

        private RebindableRootParticipant(String owner, Path sourceScope, Path sourceRoot, Path targetRoot) {
            this.owner = owner;
            this.sourceScope = sourceScope;
            this.sourceRoot = sourceRoot;
            this.targetRoot = targetRoot;
            this.activeRoot = sourceRoot;
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
        public Path rebindScope() {
            return sourceScope;
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
        public void rebind(Path activeScope) {
            activeRoot = activeScope.equals(sourceScope) ? sourceRoot : targetRoot;
        }

        @Override
        public void healthCheck() {
        }
    }

    private static final class CountingRebindableParticipant implements RebindablePersistenceParticipant {
        private final Path sourceScope;
        private final Path sourceRoot;
        private final Path targetRoot;
        private final AtomicInteger ownershipCalls;
        private Path activeRoot;

        private CountingRebindableParticipant(Path sourceScope, Path sourceRoot, Path targetRoot,
                                              AtomicInteger ownershipCalls) {
            this.sourceScope = sourceScope;
            this.sourceRoot = sourceRoot;
            this.targetRoot = targetRoot;
            this.ownershipCalls = ownershipCalls;
            this.activeRoot = sourceRoot;
        }

        @Override
        public String owner() {
            return "counting";
        }

        @Override
        public Path root() {
            return activeRoot;
        }

        @Override
        public Path rebindScope() {
            return sourceScope;
        }

        @Override
        public boolean owns(Path file) {
            ownershipCalls.incrementAndGet();
            return file.toAbsolutePath().normalize().startsWith(activeRoot);
        }

        @Override
        public void rebind(Path activeScope) {
            activeRoot = activeScope.equals(sourceScope) ? sourceRoot : targetRoot;
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
        public void healthCheck() {
        }
    }

    private static final class IndexedRootParticipant implements PersistenceParticipant, PersistenceOwnershipProvider {
        private final String owner;
        private final Path root;
        private final PersistenceOwnershipIndex index;
        private final String rootSiblingSuffix;

        private IndexedRootParticipant(String owner, Path root, PersistenceOwnershipIndex index, String rootSiblingSuffix) {
            this.owner = owner;
            this.root = root;
            this.index = index;
            this.rootSiblingSuffix = rootSiblingSuffix;
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
            return rootSiblingSuffix != null
                ? PersistenceOwnershipIndex.builder(context).rootSiblingAtomicTemp(rootSiblingSuffix).build()
                : index;
        }
    }

    private static final class RebindableRootSiblingParticipant extends RebindableRootParticipant
        implements PersistenceOwnershipProvider {
        private RebindableRootSiblingParticipant(String owner, Path sourceScope, Path sourceRoot, Path targetRoot) {
            super(owner, sourceScope, sourceRoot, targetRoot);
        }

        @Override
        public PersistenceOwnershipIndex ownershipIndex(PersistenceOwnershipContext context) {
            return PersistenceOwnershipIndex.builder(context).rootSiblingAtomicTemp(".tmp").build();
        }
    }

    private static final class IndexedRebindableParticipant implements RebindablePersistenceParticipant,
        PersistenceOwnershipProvider {
        private final Path root;
        private final Path state;
        private final AtomicInteger indexBuilds;

        private IndexedRebindableParticipant(Path root, Path state, AtomicInteger indexBuilds) {
            this.root = root;
            this.state = state;
            this.indexBuilds = indexBuilds;
        }

        @Override
        public String owner() {
            return "state";
        }

        @Override
        public Path root() {
            return root;
        }

        @Override
        public PersistenceOwnershipIndex ownershipIndex(PersistenceOwnershipContext context) {
            indexBuilds.incrementAndGet();
            return PersistenceOwnershipIndex.builder().exact(context.relativeToSource(state)).build();
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
    }
}
