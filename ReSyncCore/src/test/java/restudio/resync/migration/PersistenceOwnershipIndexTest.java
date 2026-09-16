package restudio.resync.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceOwnershipIndexTest {
    @TempDir
    Path temporary;

    @Test
    void buildsAnIndexedProviderOncePerValidationAndUsesIndexOnlyForFileResolution() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source"));
        Path indexedRoot = Files.createDirectory(source.resolve("indexed"));
        Path legacyRoot = Files.createDirectory(source.resolve("legacy"));
        Files.createFile(indexedRoot.resolve("exact.json"));
        Files.createDirectories(indexedRoot.resolve("nested"));
        Files.createFile(indexedRoot.resolve("nested/value.json"));
        Files.createFile(indexedRoot.resolve("cache-7.json"));
        Files.createFile(legacyRoot.resolve("value.json"));

        IndexedParticipant indexed = new IndexedParticipant(indexedRoot);
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(source);
        registry.register(indexed);
        registry.register(new DefaultParticipant("legacy", legacyRoot));

        registry.validateForRoot(source);

        assertEquals(1, indexed.builds);
        assertEquals(2, indexed.legacyCalls);
        assertEquals("indexed", registry.ownerFor(source, indexedRoot.resolve("exact.json")));
        assertEquals("indexed", registry.ownerFor(source, indexedRoot.resolve("nested/value.json")));
        assertEquals("indexed", registry.ownerFor(source, indexedRoot.resolve("cache-7.json")));
        assertEquals("legacy", registry.ownerFor(source, legacyRoot.resolve("value.json")));
        assertTrue(indexed.builds > 1);
        assertEquals(6, indexed.legacyCalls);
    }

    @Test
    void keepsLegacyOwnershipFallbackAndDetectsMixedMultipleOwners() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source"));
        Path indexedRoot = Files.createDirectory(source.resolve("indexed"));
        Path legacyRoot = Files.createDirectory(source.resolve("legacy"));
        Path shared = Files.createFile(source.resolve("shared.json"));
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(source);
        registry.register(new IndexedParticipant(indexedRoot, PersistenceOwnershipIndex.builder().exact("shared.json").build()));
        registry.register(new ClaimingParticipant("legacy", legacyRoot, shared));

        assertThrows(MigrationException.class, () -> registry.ownerFor(source, shared));
        assertEquals("legacy", registry.ownerFor(source, legacyRoot.resolve("missing.json")));
    }

    @Test
    void usesResolvedOwnershipDuringValidatedTreeTraversal() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("resolved-source"));
        Path directRoot = Files.createDirectory(source.resolve("direct"));
        Files.createFile(directRoot.resolve("value.json"));
        Path customRoot = Files.createDirectory(source.resolve("custom"));
        ResolvedParticipant custom = new ResolvedParticipant(customRoot);
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(source);
        registry.register(new DefaultParticipant("direct", directRoot));
        registry.register(custom);

        PersistenceParticipantRegistry.OwnershipResolution resolution = registry.resolutionForRoot(source);
        int legacyCalls = custom.legacyCalls;
        resolution.validate();

        assertEquals(legacyCalls, custom.legacyCalls);
        assertTrue(custom.resolvedCalls > 0);
    }

    @Test
    void rebuildsIndexesAfterEachValidation() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source"));
        Path indexedRoot = Files.createDirectory(source.resolve("indexed"));
        Files.createFile(indexedRoot.resolve("exact.json"));
        IndexedParticipant indexed = new IndexedParticipant(indexedRoot);
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(source);
        registry.register(indexed);

        registry.validateForRoot(source);
        registry.validateForRoot(source);

        assertEquals(2, indexed.builds);
    }

    @Test
    void resolutionSessionReusesOneImmutableIndexAndRejectsExternalFiles() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("session-source"));
        Path indexedRoot = Files.createDirectory(source.resolve("indexed"));
        Path value = Files.createFile(indexedRoot.resolve("exact.json"));
        IndexedParticipant indexed = new IndexedParticipant(indexedRoot);
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(source);
        registry.register(indexed);

        PersistenceParticipantRegistry.OwnershipResolution resolution = registry.resolutionForRoot(source);
        assertEquals(1, indexed.builds);
        assertEquals("indexed", resolution.ownerFor(value));
        assertEquals("indexed", resolution.ownerFor(value));
        assertEquals(1, indexed.builds);

        Path external = Files.createFile(source.resolve("external.json"));
        PersistenceParticipantRegistry externalRegistry = new PersistenceParticipantRegistry(source);
        IndexedParticipant externalIndexed = new IndexedParticipant(indexedRoot);
        externalRegistry.register(externalIndexed);
        externalRegistry.registerExternalInputs(List.of(new PersistenceExternalInput.Input(
            "external", external, PersistenceExternalInput.Kind.OPERATOR_CONFIGURATION, "external input")));
        assertEquals(1, externalIndexed.legacyCalls);
        PersistenceParticipantRegistry.OwnershipResolution externalResolution = externalRegistry.resolutionForRoot(source);
        assertThrows(MigrationException.class, () -> externalResolution.ownerFor(external));
        assertEquals(2, externalIndexed.legacyCalls);
    }

    @Test
    void validatesReboundIndexedTopologyBeforeCommitting() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("rebind-source"));
        Path target = Files.createDirectory(temporary.resolve("rebind-target"));
        Path sourceRoot = Files.createDirectory(source.resolve("indexed"));
        Path targetRoot = Files.createDirectory(target.resolve("indexed"));
        Files.createFile(sourceRoot.resolve("value.json"));
        Files.createFile(targetRoot.resolve("rogue.json"));
        RebindableIndexedParticipant indexed = new RebindableIndexedParticipant(source, sourceRoot, targetRoot);
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(source);
        registry.register(indexed);

        assertThrows(MigrationException.class, () -> registry.rebindAll(target));

        assertEquals(sourceRoot, indexed.root());
        assertEquals(PersistenceRebindStatus.State.ROLLED_BACK, registry.rebindStatus().state());
    }

    @Test
    void snapshotManifestUsesOneResolutionSessionForAllFiles() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("manifest-source"));
        Path indexedRoot = Files.createDirectory(source.resolve("indexed"));
        Files.createFile(indexedRoot.resolve("exact.json"));
        Files.createFile(indexedRoot.resolve("cache-1.json"));
        IndexedParticipant indexed = new IndexedParticipant(indexedRoot);
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(source);
        registry.register(indexed);

        SnapshotManifest.scan(source, SnapshotMetadata.preflight(), registry);

        assertEquals(1, indexed.builds);
    }

    @Test
    void invalidatesResolutionSessionsOnRegistryMutationAndRebind() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("epoch-source"));
        Path indexedRoot = Files.createDirectory(source.resolve("indexed"));
        Path value = Files.createFile(indexedRoot.resolve("exact.json"));
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(source);
        registry.register(new IndexedParticipant(indexedRoot));
        PersistenceParticipantRegistry.OwnershipResolution registrationSession = registry.resolutionForRoot(source);

        Path otherRoot = Files.createDirectory(source.resolve("other"));
        registry.register(new DefaultParticipant("other", otherRoot));
        assertThrows(IllegalStateException.class, () -> registrationSession.ownerFor(value));

        PersistenceParticipantRegistry externalRegistry = new PersistenceParticipantRegistry(source);
        externalRegistry.register(new DefaultParticipant("indexed", indexedRoot));
        PersistenceParticipantRegistry.OwnershipResolution externalSession = externalRegistry.resolutionForRoot(source);
        Path external = source.resolve("external.json");
        externalRegistry.registerExternalInputs(List.of(new PersistenceExternalInput.Input(
            "external", external, PersistenceExternalInput.Kind.OPERATOR_CONFIGURATION, "external input")));
        assertThrows(IllegalStateException.class, () -> externalSession.isExternalPath(external));

        Path target = Files.createDirectory(temporary.resolve("epoch-target"));
        Path targetRoot = Files.createDirectory(target.resolve("indexed"));
        Files.createFile(targetRoot.resolve("value.json"));
        PersistenceParticipantRegistry rebindRegistry = new PersistenceParticipantRegistry(source);
        RebindableIndexedParticipant rebindable = new RebindableIndexedParticipant(source, indexedRoot, targetRoot);
        rebindRegistry.register(rebindable);
        PersistenceParticipantRegistry.OwnershipResolution rebindSession = rebindRegistry.resolutionForRoot(source);
        rebindRegistry.rebindAll(target);
        assertThrows(IllegalStateException.class, () -> rebindSession.ownerFor(value));
    }

    @Test
    void rejectsIndexedClaimsAcrossRootsExternalDirectoriesAndProviders() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("boundary-source"));
        Path providerRoot = Files.createDirectory(source.resolve("provider"));
        Path otherRoot = Files.createDirectory(source.resolve("other"));
        PersistenceParticipantRegistry rootRegistry = new PersistenceParticipantRegistry(source);
        rootRegistry.register(new IndexedParticipant(providerRoot,
            PersistenceOwnershipIndex.builder().exact("other/child.json").build()));
        rootRegistry.register(new DefaultParticipant("other", otherRoot));
        assertThrows(MigrationException.class, () -> rootRegistry.resolutionForRoot(source));

        Path externalDirectory = Files.createDirectory(source.resolve("external"));
        PersistenceParticipantRegistry externalRegistry = new PersistenceParticipantRegistry(source);
        externalRegistry.register(new IndexedParticipant(providerRoot,
            PersistenceOwnershipIndex.builder().exact("external/nested/value.json").build()));
        externalRegistry.registerExternalInputs(List.of(new PersistenceExternalInput.Input(
            "external", externalDirectory, PersistenceExternalInput.Kind.OPERATOR_CONFIGURATION, "external input")));
        assertThrows(MigrationException.class, () -> externalRegistry.resolutionForRoot(source));

        PersistenceParticipantRegistry providerRegistry = new PersistenceParticipantRegistry(source);
        providerRegistry.register(new IndexedParticipant(providerRoot,
            PersistenceOwnershipIndex.builder().exact("shared/value.json").build()));
        providerRegistry.register(new IndexedParticipant("other-provider", otherRoot,
            PersistenceOwnershipIndex.builder().subtree("shared").build()));
        assertThrows(MigrationException.class, () -> providerRegistry.resolutionForRoot(source));

        PersistenceParticipantRegistry matcherProviderRegistry = new PersistenceParticipantRegistry(source);
        matcherProviderRegistry.register(new IndexedParticipant(providerRoot,
            PersistenceOwnershipIndex.builder().directChildPrefix("shared-").build()));
        matcherProviderRegistry.register(new IndexedParticipant("other-provider", otherRoot,
            PersistenceOwnershipIndex.builder().exact("shared-file").build()));
        assertThrows(MigrationException.class, () -> matcherProviderRegistry.resolutionForRoot(source));

        PersistenceParticipantRegistry directRegistry = new PersistenceParticipantRegistry(source);
        directRegistry.register(new IndexedParticipant(providerRoot,
            PersistenceOwnershipIndex.builder().directChildPrefix("other").build()));
        directRegistry.register(new DefaultParticipant("other", otherRoot));
        assertThrows(MigrationException.class, () -> directRegistry.resolutionForRoot(source));
    }

    @Test
    void rejectsAtomicTempClaimsAgainstEveryIndexedClaimKindBeforeArtifactsExist() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("atomic-overlap-matrix-source"));
        Path claimsRoot = Files.createDirectory(source.resolve("claims"));
        PersistenceOwnershipContext claimsContext = new PersistenceOwnershipContext(source, claimsRoot);
        PersistenceOwnershipIndex atomic = PersistenceOwnershipIndex.builder()
            .directChildAtomicTemp("claims")
            .build();

        assertIndexedOverlap(source, "exact", atomic,
            PersistenceOwnershipIndex.builder()
                .exact("claims/.resync-00000000-0000-0000-0000-000000000000.tmp")
                .build());
        assertIndexedOverlap(source, "subtree", atomic,
            PersistenceOwnershipIndex.builder().subtree("claims").build());
        assertIndexedOverlap(source, "direct", atomic,
            PersistenceOwnershipIndex.builder(claimsContext)
                .directChildPrefix(".resync-")
                .build());
        assertIndexedOverlap(source, "atomic", atomic,
            PersistenceOwnershipIndex.builder()
                .directChildAtomicTemp("claims")
                .build());

        Path nestedParent = Files.createDirectories(source.resolve("nested/claims"));
        Path nestedRoot = Files.createFile(nestedParent.resolve(".resync-"));
        PersistenceOwnershipContext nestedContext = new PersistenceOwnershipContext(source, nestedRoot);
        PersistenceOwnershipIndex nestedSibling = PersistenceOwnershipIndex.builder(nestedContext)
            .rootSiblingAtomicTemp("nested", ".tmp")
            .build();
        PersistenceOwnershipIndex nestedAtomic = PersistenceOwnershipIndex.builder()
            .directChildAtomicTemp("nested/claims/nested")
            .build();
        assertIndexedOverlap(source, "nested-root-sibling", nestedAtomic, nestedSibling);
    }

    @Test
    void rejectsAtomicTempClaimsAtParticipantAndExternalBoundariesBeforeArtifactsExist() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("atomic-boundary-source"));
        Path providerRoot = Files.createDirectory(source.resolve("provider"));
        Path participantRoot = Files.createDirectory(source.resolve("claims"));
        assertAtomicParticipantBoundary(source, providerRoot, participantRoot);

        Path externalRoot = Files.createDirectory(source.resolve("external"));
        PersistenceParticipantRegistry externalRegistry = new PersistenceParticipantRegistry(source);
        externalRegistry.register(new IndexedParticipant("atomic", providerRoot,
            PersistenceOwnershipIndex.builder().directChildAtomicTemp("external").build()));
        externalRegistry.registerExternalInputs(List.of(new PersistenceExternalInput.Input(
            "external", externalRoot, PersistenceExternalInput.Kind.OPERATOR_CONFIGURATION, "external input")));
        assertThrows(MigrationException.class, () -> externalRegistry.resolutionForRoot(source));
    }

    @Test
    void keepsProvablyDisjointAtomicTempGrammarsSeparateAtSeal() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("atomic-disjoint-matrix-source"));
        Path claimsRoot = Files.createDirectory(source.resolve("claims"));
        PersistenceOwnershipContext claimsContext = new PersistenceOwnershipContext(source, claimsRoot);
        PersistenceOwnershipIndex atomic = PersistenceOwnershipIndex.builder()
            .directChildAtomicTemp("claims")
            .build();

        assertIndexedDisjoint(source, "direct-prefix", atomic,
            PersistenceOwnershipIndex.builder(claimsContext)
                .directChildPrefix("recipe")
                .build());
        assertIndexedDisjoint(source, "exact", atomic,
            PersistenceOwnershipIndex.builder()
                .exact("claims/.resync-00000000-0000-0000-0000-000000000000.TMP")
                .build());

        Path nestedParent = Files.createDirectories(source.resolve("nested"));
        Path configuration = Files.createFile(nestedParent.resolve("config.properties"));
        PersistenceOwnershipContext siblingContext = new PersistenceOwnershipContext(source, configuration);
        PersistenceOwnershipIndex sibling = PersistenceOwnershipIndex.builder(siblingContext)
            .rootSiblingAtomicTemp("evidence", ".tmp")
            .build();
        PersistenceOwnershipIndex nestedAtomic = PersistenceOwnershipIndex.builder()
            .directChildAtomicTemp("nested/evidence")
            .build();
        assertIndexedDisjoint(source, "root-sibling-atomic", nestedAtomic, sibling);

        PersistenceOwnershipIndex uuidSibling = PersistenceOwnershipIndex.builder(siblingContext)
            .rootSiblingUuidSuffix("generated.")
            .build();
        PersistenceOwnershipIndex uuidAtomic = PersistenceOwnershipIndex.builder()
            .directChildAtomicTemp("nested")
            .build();
        assertIndexedDisjoint(source, "root-sibling-uuid", uuidAtomic, uuidSibling);

        PersistenceOwnershipIndex hashSibling = PersistenceOwnershipIndex.builder(siblingContext)
            .rootSiblingHashJson(".quarantine")
            .build();
        PersistenceOwnershipIndex hashAtomic = PersistenceOwnershipIndex.builder()
            .directChildAtomicTemp("nested/.quarantine")
            .build();
        assertIndexedDisjoint(source, "root-sibling-hash", hashAtomic, hashSibling);
    }

    private void assertIndexedOverlap(Path source, String name, PersistenceOwnershipIndex leftIndex,
                                      PersistenceOwnershipIndex rightIndex) throws Exception {
        Path leftRoot = Files.createDirectory(source.resolve(name + "-left"));
        Path rightRoot = Files.createDirectory(source.resolve(name + "-right"));
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(source);
        registry.register(new IndexedParticipant(name + "-left", leftRoot, leftIndex));
        registry.register(new IndexedParticipant(name + "-right", rightRoot, rightIndex));
        assertThrows(MigrationException.class, () -> registry.resolutionForRoot(source));
    }

    private void assertIndexedDisjoint(Path source, String name, PersistenceOwnershipIndex leftIndex,
                                       PersistenceOwnershipIndex rightIndex) throws Exception {
        Path leftRoot = Files.createDirectory(source.resolve(name + "-left"));
        Path rightRoot = Files.createDirectory(source.resolve(name + "-right"));
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(source);
        registry.register(new IndexedParticipant(name + "-left", leftRoot, leftIndex));
        registry.register(new IndexedParticipant(name + "-right", rightRoot, rightIndex));
        assertDoesNotThrow(() -> registry.resolutionForRoot(source));
    }

    private void assertAtomicParticipantBoundary(Path source, Path providerRoot,
                                                 Path participantRoot) throws Exception {
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(source);
        registry.register(new IndexedParticipant("atomic", providerRoot,
            PersistenceOwnershipIndex.builder().directChildAtomicTemp("claims").build()));
        registry.register(new DefaultParticipant("claims", participantRoot));
        assertThrows(MigrationException.class, () -> registry.resolutionForRoot(source));
    }

    @Test
    void rejectsLegacyRebindOntoRemappedExternalInputAndRestoresTheSourceRoot() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("legacy-external-source"));
        Path sourceRoot = Files.createDirectory(source.resolve("state"));
        Path sourceExternal = Files.createDirectory(source.resolve("nodes"));
        Path target = Files.createDirectory(temporary.resolve("legacy-external-target"));
        Path targetExternal = Files.createDirectory(target.resolve("nodes"));
        LegacyRebindableParticipant participant = new LegacyRebindableParticipant(source, sourceRoot, targetExternal);
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(source);
        registry.register(participant);
        registry.registerExternalInputs(List.of(new PersistenceExternalInput.Input(
            "nodes", sourceExternal, PersistenceExternalInput.Kind.OPERATOR_CATALOG, "authored nodes")));

        assertThrows(MigrationException.class, () -> registry.rebindAll(target));

        assertEquals(sourceRoot, participant.root());
    }

    @Test
    void directChildClaimsAreBoundedSafeMatchers() {
        PersistenceOwnershipIndex index = PersistenceOwnershipIndex.builder()
            .directChildLiteral("value.json")
            .build();
        PersistenceOwnershipIndex prefixIndex = PersistenceOwnershipIndex.builder()
            .directChildPrefix("cache-")
            .build();
        PersistenceOwnershipIndex suffixIndex = PersistenceOwnershipIndex.builder()
            .directChildSuffix(".tmp")
            .build();

        assertTrue(index.owns("value.json"));
        assertTrue(prefixIndex.owns("cache-123"));
        assertTrue(suffixIndex.owns("state.tmp"));
        assertFalse(index.owns("nested/value.json"));
        assertThrows(IllegalArgumentException.class, () -> PersistenceOwnershipIndex.builder().directChildPrefix("../").build());
        assertThrows(IllegalArgumentException.class, () -> PersistenceOwnershipIndex.builder().directChildSuffix("folder/").build());
        assertThrows(IllegalArgumentException.class,
            () -> PersistenceOwnershipIndex.builder().subtreeRoot().directChildPrefix("cache-").build());
    }

    @Test
    void directChildAtomicTempClaimsUseCanonicalResyncUuidNames() {
        PersistenceOwnershipIndex index = PersistenceOwnershipIndex.builder()
            .directChildAtomicTemp()
            .directChildAtomicTemp(".quarantine/evidence")
            .build();

        assertTrue(index.owns(".resync-00000000-0000-0000-0000-000000000000.tmp"));
        assertTrue(index.owns(".resync-abcdefab-cdef-abcd-efab-cdefabcdefab.tmp"));
        assertTrue(index.owns(".quarantine/evidence/.resync-00000000-0000-0000-0000-000000000001.tmp"));
        assertFalse(index.owns("recipe.json7.tmp"));
        assertFalse(index.owns(".resync-00000000-0000-0000-0000-000000000000.TMP"));
        assertFalse(index.owns(".resync-00000000-0000-0000-0000-00000000000A.tmp"));
        assertFalse(index.owns(".resync-000000000000000000000000000000000000.tmp"));
        assertFalse(index.owns(".resync-00000000-0000-0000-0000-000000000000.tmp/child"));
        assertFalse(index.owns("other/.resync-00000000-0000-0000-0000-000000000000.tmp"));
        assertThrows(IllegalArgumentException.class,
            () -> PersistenceOwnershipIndex.builder().directChildAtomicTemp("bad/prefix/").build());
    }

    @Test
    void rootSiblingClaimsUseStrictCanonicalNames() {
        Path source = temporary.resolve("root-sibling-source");
        PersistenceOwnershipContext context = new PersistenceOwnershipContext(source,
            source.resolve("state/config.properties"));
        PersistenceOwnershipIndex uuid = PersistenceOwnershipIndex.builder(context)
            .rootSiblingUuidSuffix("generated.stage.")
            .build();
        PersistenceOwnershipIndex hash = PersistenceOwnershipIndex.builder(context)
            .rootSiblingHashJson(".quarantine/authority-bundle")
            .build();
        PersistenceOwnershipIndex atomic = PersistenceOwnershipIndex.builder(context)
            .rootSiblingAtomicTemp(".tmp")
            .build();

        assertFalse(uuid.owns("state/generated.stage/00000000-0000-0000-0000-000000000000"));
        assertTrue(uuid.owns("state/generated.stage.00000000-0000-0000-0000-000000000000"));
        assertFalse(uuid.owns("state/generated.stage.00000000-0000-0000-0000-00000000000A"));
        assertFalse(uuid.owns("state/generated.stage.000000000000-0000-0000-0000-000000000000"));
        assertFalse(uuid.owns("state/nested/generated.stage.00000000-0000-0000-0000-000000000000"));

        assertTrue(hash.owns("state/.quarantine/authority-bundle/0000000000000000000000000000000000000000000000000000000000000000.json"));
        assertFalse(hash.owns("state/.quarantine/authority-bundle/000000000000000000000000000000000000000000000000000000000000000A.json"));
        assertFalse(hash.owns("state/.quarantine/authority-bundle/000000000000000000000000000000000000000000000000000000000000000.json"));
        assertFalse(hash.owns("state/.quarantine/authority-bundle/0000000000000000000000000000000000000000000000000000000000000000.JSON"));
        assertFalse(hash.owns("state/.quarantine/authority-bundle/nested/0000000000000000000000000000000000000000000000000000000000000000.json"));

        assertTrue(atomic.owns("state/config.properties7.tmp"));
        assertTrue(atomic.owns("state/config.propertiesabc-7_DEF.tmp"));
        assertFalse(atomic.owns("state/config.properties.tmp"));
        assertFalse(atomic.owns("state/config.properties7.tmp/child"));
        assertFalse(atomic.owns("state/config.properties7.tmp.bak"));
        assertFalse(atomic.owns("config.properties7.tmp"));
        PersistenceOwnershipIndex.RootSiblingClaim claim = atomic.rootSiblingClaims().getFirst();
        assertEquals("state", claim.parent());
        assertEquals("config.properties", claim.rootName());
        assertTrue(claim.overlapsDirectory(""));
        assertTrue(claim.overlapsDirectory("state"));
        assertTrue(claim.overlapsDirectory("state/config.properties7.tmp"));
        assertFalse(claim.overlapsDirectory("other"));
    }

    @Test
    void rootSiblingClaimsFollowTheCurrentParticipantRootAndRejectAmbiguousPatterns() {
        Path source = temporary.resolve("source-root");
        PersistenceOwnershipContext firstContext = new PersistenceOwnershipContext(source,
            source.resolve("first/config.properties"));
        PersistenceOwnershipContext secondContext = new PersistenceOwnershipContext(source,
            source.resolve("second/config.properties"));
        PersistenceOwnershipIndex first = PersistenceOwnershipIndex.builder(firstContext)
            .rootSiblingAtomicTemp(".tmp")
            .build();
        PersistenceOwnershipIndex second = PersistenceOwnershipIndex.builder(secondContext)
            .rootSiblingAtomicTemp(".tmp")
            .build();

        assertTrue(first.owns("first/config.properties1.tmp"));
        assertFalse(first.owns("second/config.properties1.tmp"));
        assertTrue(second.owns("second/config.properties1.tmp"));
        assertFalse(second.owns("first/config.properties1.tmp"));
        assertThrows(IllegalArgumentException.class,
            () -> PersistenceOwnershipIndex.builder().rootSiblingAtomicTemp(".tmp"));
        assertThrows(IllegalArgumentException.class,
            () -> PersistenceOwnershipIndex.builder(firstContext)
                .rootSiblingUuidSuffix("generated.")
                .rootSiblingUuidSuffix("generated.0")
                .build());
        assertThrows(IllegalArgumentException.class,
            () -> PersistenceOwnershipIndex.builder(firstContext)
                .rootSiblingHashJson(".quarantine")
                .rootSiblingHashJson(".quarantine")
                .build());
        assertThrows(IllegalArgumentException.class,
            () -> PersistenceOwnershipIndex.builder(firstContext)
                .rootSiblingAtomicTemp("x")
                .rootSiblingAtomicTemp("xx")
                .build());
        assertTrue(PersistenceOwnershipIndex.builder(firstContext)
            .rootSiblingUuidSuffix("generated.stage.")
            .rootSiblingUuidSuffix("generated.backup.")
            .build()
            .owns("first/generated.backup.00000000-0000-0000-0000-000000000000"));
    }

    @Test
    void nestedRootSiblingClaimsCoverExactEvidenceRootsAndCanonicalDirectChildren() {
        Path source = temporary.resolve("nested-root-sibling-source");
        PersistenceOwnershipContext context = new PersistenceOwnershipContext(source,
            source.resolve("runtime/runtime-receipts.json"));
        PersistenceOwnershipIndex index = PersistenceOwnershipIndex.builder(context)
            .exactRoot()
            .rootSiblingExact(".quarantine")
            .rootSiblingExact(".quarantine/runtime-receipt-temps")
            .rootSiblingAtomicTemp(".tmp")
            .rootSiblingAtomicTemp(".quarantine/runtime-receipt-temps", ".tmp")
            .build();

        assertTrue(index.owns("runtime/runtime-receipts.json"));
        assertTrue(index.owns("runtime/.quarantine"));
        assertTrue(index.owns("runtime/.quarantine/runtime-receipt-temps"));
        assertTrue(index.owns("runtime/runtime-receipts.json7.tmp"));
        assertTrue(index.owns("runtime/.quarantine/runtime-receipt-temps/runtime-receipts.json7.tmp"));
        assertFalse(index.owns("runtime/.quarantine/other"));
        assertFalse(index.owns("runtime/.quarantine/runtime-receipt-temps/nested"));
        assertFalse(index.owns("runtime/.quarantine/runtime-receipt-temps/runtime-receipts.json.tmp"));
        assertThrows(IllegalArgumentException.class,
            () -> PersistenceOwnershipIndex.builder(context)
                .rootSiblingExact(".quarantine")
                .rootSiblingExact(".quarantine")
                .build());
        assertThrows(IllegalArgumentException.class,
            () -> PersistenceOwnershipIndex.builder(context)
                .rootSiblingAtomicTemp(".quarantine/runtime-receipt-temps", ".tmp")
                .rootSiblingAtomicTemp(".quarantine/runtime-receipt-temps", ".tmp")
                .build());
    }

    @Test
    void nestedRootSiblingOverlapUsesEffectivePatternParentsAndTargetGrammars() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("nested-root-sibling-overlap-source"));
        PersistenceOwnershipContext hashContext = new PersistenceOwnershipContext(source,
            source.resolve("runtime/config.properties"));
        PersistenceOwnershipContext overlappingAtomicContext = new PersistenceOwnershipContext(source,
            source.resolve("runtime/.quarantine/a"));
        PersistenceOwnershipContext disjointAtomicContext = new PersistenceOwnershipContext(source,
            source.resolve("runtime/.quarantine/config.properties"));
        PersistenceOwnershipContext deeperAtomicContext = new PersistenceOwnershipContext(source,
            source.resolve("runtime/.quarantine/nested/a"));
        PersistenceOwnershipContext localOverlapContext = new PersistenceOwnershipContext(source,
            source.resolve("runtime/a"));

        PersistenceOwnershipIndex hash = PersistenceOwnershipIndex.builder(hashContext)
            .rootSiblingHashJson(".quarantine")
            .build();
        PersistenceOwnershipIndex overlappingAtomic = PersistenceOwnershipIndex.builder(overlappingAtomicContext)
            .rootSiblingAtomicTemp(".json")
            .build();
        assertIndexedOverlap(source, "nested-hash-atomic", hash, overlappingAtomic);

        PersistenceOwnershipIndex disjointAtomic = PersistenceOwnershipIndex.builder(disjointAtomicContext)
            .rootSiblingAtomicTemp(".tmp")
            .build();
        assertIndexedDisjoint(source, "nested-hash-disjoint", hash, disjointAtomic);

        PersistenceOwnershipIndex deeperAtomic = PersistenceOwnershipIndex.builder(deeperAtomicContext)
            .rootSiblingAtomicTemp(".json")
            .build();
        assertIndexedDisjoint(source, "nested-hash-deeper", hash, deeperAtomic);

        assertThrows(IllegalArgumentException.class, () -> PersistenceOwnershipIndex.builder(localOverlapContext)
            .rootSiblingHashJson(".quarantine")
            .rootSiblingAtomicTemp(".quarantine", ".json")
            .build());
        assertDoesNotThrow(() -> PersistenceOwnershipIndex.builder(hashContext)
            .rootSiblingHashJson(".quarantine")
            .rootSiblingAtomicTemp(".quarantine", ".tmp")
            .build());
    }

    @Test
    void rejectsUnsafeAndOverlappingClaims() {
        assertThrows(IllegalArgumentException.class,
            () -> PersistenceOwnershipIndex.builder().exact("../value.json").build());
        assertThrows(IllegalArgumentException.class,
            () -> PersistenceOwnershipIndex.builder().subtree("/value").build());
        assertThrows(IllegalArgumentException.class,
            () -> PersistenceOwnershipIndex.builder().exact("value.json").subtree("value.json").build());
    }

    private static class IndexedParticipant implements PersistenceParticipant, PersistenceOwnershipProvider {
        private final String owner;
        private final Path root;
        private final PersistenceOwnershipIndex fixed;
        private int builds;
        private int legacyCalls;

        private IndexedParticipant(Path root) {
            this("indexed", root, null);
        }

        private IndexedParticipant(Path root, PersistenceOwnershipIndex fixed) {
            this("indexed", root, fixed);
        }

        private IndexedParticipant(String owner, Path root, PersistenceOwnershipIndex fixed) {
            this.owner = owner;
            this.root = root;
            this.fixed = fixed;
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
        public boolean owns(Path file) {
            legacyCalls++;
            return false;
        }

        @Override
        public PersistenceOwnershipIndex ownershipIndex(PersistenceOwnershipContext context) {
            builds++;
            if (fixed != null) {
                return fixed;
            }
            return PersistenceOwnershipIndex.builder(context)
                .exact("exact.json")
                .subtree("nested")
                .directChildPrefix("cache-")
                .build();
        }
    }

    private record DefaultParticipant(String owner, Path root) implements PersistenceParticipant {
    }

    private record ClaimingParticipant(String owner, Path root, Path claimed) implements PersistenceParticipant {
        @Override
        public boolean owns(Path file) {
            Path candidate = file.toAbsolutePath().normalize();
            Path participantRoot = root.toAbsolutePath().normalize();
            return candidate.equals(claimed.toAbsolutePath().normalize()) || candidate.startsWith(participantRoot);
        }
    }

    private static final class ResolvedParticipant implements PersistenceParticipant, ResolvedPersistenceOwnership {
        private final Path root;
        private int legacyCalls;
        private int resolvedCalls;

        private ResolvedParticipant(Path root) {
            this.root = root;
        }

        @Override
        public String owner() {
            return "resolved";
        }

        @Override
        public Path root() {
            return root;
        }

        @Override
        public boolean owns(Path file) {
            legacyCalls++;
            return MigrationPaths.requirePath(file, "file").startsWith(root);
        }

        @Override
        public boolean ownsResolved(Path file) {
            resolvedCalls++;
            return file.startsWith(root);
        }
    }

    private static final class RebindableIndexedParticipant implements RebindablePersistenceParticipant,
        PersistenceOwnershipProvider {
        private final Path sourceScope;
        private final Path sourceRoot;
        private final Path targetRoot;
        private Path activeRoot;

        private RebindableIndexedParticipant(Path sourceScope, Path sourceRoot, Path targetRoot) {
            this.sourceScope = sourceScope;
            this.sourceRoot = sourceRoot;
            this.targetRoot = targetRoot;
            this.activeRoot = sourceRoot;
        }

        @Override
        public String owner() {
            return "indexed";
        }

        @Override
        public Path root() {
            return activeRoot;
        }

        @Override
        public boolean owns(Path file) {
            return false;
        }

        @Override
        public PersistenceOwnershipIndex ownershipIndex(PersistenceOwnershipContext context) {
            return PersistenceOwnershipIndex.builder(context).exact("value.json").build();
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

    private static final class LegacyRebindableParticipant implements RebindablePersistenceParticipant {
        private final Path sourceScope;
        private final Path sourceRoot;
        private final Path targetRoot;
        private Path activeRoot;

        private LegacyRebindableParticipant(Path sourceScope, Path sourceRoot, Path targetRoot) {
            this.sourceScope = sourceScope;
            this.sourceRoot = sourceRoot;
            this.targetRoot = targetRoot;
            this.activeRoot = sourceRoot;
        }

        @Override
        public String owner() {
            return "legacy-rebind";
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
}
