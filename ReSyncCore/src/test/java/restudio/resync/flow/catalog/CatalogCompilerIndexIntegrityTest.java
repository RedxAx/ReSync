package restudio.resync.flow.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.canonical.CanonicalLimits;
import restudio.resync.flow.identity.BranchId;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.inspector.InspectorFallback;
import restudio.resync.flow.inspector.InspectorId;
import restudio.resync.flow.runtime.RuntimeFailureContract;
import restudio.resync.flow.runtime.RuntimeOperationDescriptor;
import restudio.resync.flow.runtime.RuntimeSemantics;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogCompilerIndexIntegrityTest {
    private static final OwnerId OWNER = OwnerId.of("resync.index");
    private static final CatalogVersion CONTRACT = new CatalogVersion(1, 0);
    private static final ContentHash MANIFEST = ContentHash.of("f".repeat(64));
    private static final TypeExpr STRING = TypeExpr.named(TypeReference.of("builtin", "string"));

    @TempDir
    Path directory;

    @Test
    void generatedDescriptionAndMetadataChangesInvalidateIdenticalAuthoredProvenance() {
        CatalogContribution first = contribution("Runs the original authored operation with a clear outcome.", Map.of("version", 1), null);
        CatalogContribution changed = contribution("Runs the revised authored operation with a clear outcome.", Map.of("version", 2), null);
        CatalogSnapshot original = compiler(first).compile(List.of(first), 4, directory).snapshot().orElseThrow();
        CatalogSnapshot revised = compiler(changed).compile(List.of(changed), 4, directory).snapshot().orElseThrow();

        assertEquals(first.provenance(), changed.provenance());
        assertNotEquals(first.startupContracts(), changed.startupContracts());
        assertNotEquals(original.contentChecksum(), revised.contentChecksum());
        assertEquals(compiler(changed).compile(List.of(changed), 4).snapshot().orElseThrow().canonicalContent(), revised.canonicalContent());
        CatalogStartupIndex.invalidate(directory);
        assertEquals(revised.canonicalContent(), compiler(changed).compile(List.of(changed), 4, directory).snapshot().orElseThrow().canonicalContent());
    }

    @Test
    void cacheHitDoesNotBypassCurrentExecutionAuthority() {
        CatalogContribution contribution = contribution("Runs an authored operation with an explicit runtime proof.", Map.of(), null);
        assertTrue(compiler(contribution).compile(List.of(contribution), 4, directory).accepted());
        CatalogCompiler unavailable = new CatalogCompiler(CONTRACT, CatalogBindingProof.fixed(Map.of(), MANIFEST));

        CatalogCompilationResult result = unavailable.compile(List.of(contribution), 4, directory);

        assertFalse(result.accepted());
        assertTrue(result.diagnostics().stream().anyMatch(value -> value.code().equals("CATALOG.BINDING_MISSING")));
    }

    @Test
    void changedUnresolvedInspectorReferenceCannotReuseAnAcceptedSnapshot() {
        CatalogContribution valid = contribution("Runs an authored operation with a known editor layout.", Map.of(), null);
        CatalogContribution invalid = contribution("Runs an authored operation with a known editor layout.", Map.of(), InspectorId.of("missing"));
        compiler(valid).compile(List.of(valid), 4, directory).snapshot().orElseThrow();

        assertNotEquals(valid.startupContracts(), invalid.startupContracts());
        assertFalse(compiler(invalid).compile(List.of(invalid), 4, directory).accepted());
    }

    @Test
    void forgedInternallyConsistentColdSnapshotIsRebuiltFromCurrentContributions() {
        CatalogContribution current = contribution("Runs the current authoritative operation with clear behavior.", Map.of(), null);
        CatalogContribution different = contribution("Runs a different cached operation with unrelated behavior.", Map.of(), null);
        CatalogSnapshot expected = compiler(current).compile(List.of(current), 4).snapshot().orElseThrow();
        CatalogSnapshot wrong = compiler(different).compile(List.of(different), 4).snapshot().orElseThrow();
        String fingerprint = CatalogStartupIndex.fingerprint(CatalogStartupIndex.sourceIdentities(List.of(current)),
            MANIFEST.canonicalText(), CONTRACT.toString(), 1);
        CatalogStartupIndex.store(directory, fingerprint, 4, CatalogCanonicalizer.derivedSnapshot(wrong.contentChecksum(),
            wrong.bindingManifestHash(), wrong.canonicalContent()));
        CatalogStartupIndex.invalidate(directory);

        CatalogSnapshot restored = compiler(current).compile(List.of(current), 4, directory).snapshot().orElseThrow();

        assertEquals(expected.contentChecksum(), restored.contentChecksum());
        assertEquals(expected.canonicalContent(), restored.canonicalContent());
        CatalogStartupIndex.invalidate(directory);
        assertEquals(expected.canonicalContent(), compiler(current).compile(List.of(current), 4, directory).snapshot().orElseThrow().canonicalContent());
    }

    @Test
    void publicStoreDoesNotAuthorizeAForgedResidentSnapshot() {
        CatalogContribution current = contribution("Runs the current authoritative operation with clear behavior.", Map.of(), null);
        CatalogContribution unrelated = contribution("Runs an unrelated authored operation with different behavior.", Map.of(), null);
        CatalogSnapshot expected = compiler(current).compile(List.of(current), 4).snapshot().orElseThrow();
        CatalogSnapshot forged = compiler(unrelated).compile(List.of(unrelated), 4).snapshot().orElseThrow();
        CatalogStartupIndex.store(directory, fingerprint(current), 4, CatalogCanonicalizer.derivedSnapshot(forged.contentChecksum(),
            forged.bindingManifestHash(), forged.canonicalContent()));

        CatalogSnapshot restored = compiler(current).compile(List.of(current), 4, directory).snapshot().orElseThrow();

        assertEquals(expected.contentChecksum(), restored.contentChecksum());
        assertEquals(expected.canonicalContent(), restored.canonicalContent());
    }

    @Test
    void aWarmHeaderGenerationMismatchCannotBeHiddenByRebasing() throws Exception {
        CatalogContribution current = contribution("Runs a current authored operation with clear behavior.", Map.of(), null);
        CatalogSnapshot original = compiler(current).compile(List.of(current), 4).snapshot().orElseThrow();
        CatalogSnapshot expected = compiler(current).compile(List.of(current), 6).snapshot().orElseThrow();
        CatalogStartupIndex.store(directory, fingerprint(current), 5, CatalogCanonicalizer.derivedSnapshot(original.contentChecksum(),
            original.bindingManifestHash(), original.canonicalContent()));

        CatalogSnapshot restored = compiler(current).compile(List.of(current), 6, directory).snapshot().orElseThrow();

        assertEquals(expected.canonicalContent(), restored.canonicalContent());
        String[] record = Files.readString(directory.resolve(CatalogStartupIndex.FILE_NAME)).split("\n", 7);
        assertEquals("6", record[2]);
        assertEquals(expected.canonicalContent(), record[6]);
    }

    @Test
    void nonpositiveStoreGenerationIsRejectedBeforeAdmission() {
        CatalogContribution current = contribution("Runs a current authored operation with clear behavior.", Map.of(), null);
        CatalogSnapshot original = compiler(current).compile(List.of(current), 4).snapshot().orElseThrow();
        CatalogCanonicalizer.DerivedSnapshot derived = CatalogCanonicalizer.derivedSnapshot(original.contentChecksum(),
            original.bindingManifestHash(), original.canonicalContent());

        assertThrows(IllegalArgumentException.class, () -> CatalogStartupIndex.store(directory, fingerprint(current), 0, derived));
        assertThrows(IllegalArgumentException.class, () -> CatalogStartupIndex.store(directory, fingerprint(current), -1, derived));
        assertTrue(CatalogStartupIndex.find(directory, fingerprint(current), 4).isEmpty());
    }

    @Test
    void coldCanonicalPayloadRequiresExactBytesEvenWithAValidTransportDigest() throws Exception {
        CatalogContribution current = contribution("Runs a current authored operation with clear behavior.", Map.of(), null);
        CatalogSnapshot expected = compiler(current).compile(List.of(current), 4, directory).snapshot().orElseThrow();
        Path file = directory.resolve(CatalogStartupIndex.FILE_NAME);
        String[] record = Files.readString(file).split("\n", 7);
        record[6] = "{ " + record[6].substring(1);
        record[5] = CanonicalJson.genericCanonicalContentHash(record[6].getBytes(StandardCharsets.UTF_8));
        Files.writeString(file, String.join("\n", record));
        CatalogStartupIndex.invalidate(directory);

        CatalogSnapshot restored = compiler(current).compile(List.of(current), 4, directory).snapshot().orElseThrow();

        assertEquals(expected.canonicalContent(), restored.canonicalContent());
        assertEquals(expected.canonicalContent(), Files.readString(file).split("\n", 7)[6]);
    }

    @Test
    void coldHeaderGenerationMustMatchTheBodyBeforeRebasing() throws Exception {
        CatalogContribution current = contribution("Runs a current authored operation with clear behavior.", Map.of(), null);
        CatalogSnapshot original = compiler(current).compile(List.of(current), 4, directory).snapshot().orElseThrow();
        Path file = directory.resolve(CatalogStartupIndex.FILE_NAME);
        String[] record = Files.readString(file).split("\n", 7);
        record[2] = "9";
        Files.writeString(file, String.join("\n", record));
        CatalogStartupIndex.invalidate(directory);

        CatalogSnapshot restored = compiler(current).compile(List.of(current), 9, directory).snapshot().orElseThrow();

        String rebased = CatalogCanonicalizer.rebaseSnapshotGeneration(original.canonicalContent(), 9);
        assertEquals(rebased, restored.canonicalContent());
        assertEquals(rebased, Files.readString(file).split("\n", 7)[6]);
    }

    @Test
    void aVerifiedColdSnapshotCanRebaseWithoutChangingItsStoredAuthority() throws Exception {
        CatalogContribution current = contribution("Runs a current authored operation with clear behavior.", Map.of(), null);
        CatalogSnapshot original = compiler(current).compile(List.of(current), 4, directory).snapshot().orElseThrow();
        Path file = directory.resolve(CatalogStartupIndex.FILE_NAME);
        String committed = Files.readString(file);
        CatalogStartupIndex.invalidate(directory);

        CatalogSnapshot restored = compiler(current).compile(List.of(current), 9, directory).snapshot().orElseThrow();

        assertEquals(original.contentChecksum(), restored.contentChecksum());
        assertEquals(CatalogCanonicalizer.rebaseSnapshotGeneration(original.canonicalContent(), 9), restored.canonicalContent());
        assertEquals(committed, Files.readString(file));
    }

    @Test
    void aLargeAdmittedCatalogSurvivesBackingRecordFailureUntilItsAuthorityIsInvalidated() throws Exception {
        CatalogContribution current = contribution("Runs a current authored operation with clear behavior.",
            Map.of("payload", "x".repeat(524_288)), null, 20);
        CatalogSnapshot original = compiler(current).compile(List.of(current), 4, directory).snapshot().orElseThrow();
        int bytes = original.canonicalContent().getBytes(StandardCharsets.UTF_8).length;
        assertTrue(bytes > 16_777_216);
        assertTrue(bytes < CanonicalLimits.catalog().canonicalBytes());
        Path file = directory.resolve(CatalogStartupIndex.FILE_NAME);
        Files.writeString(file, "interrupted record");

        CatalogSnapshot resident = compiler(current).compile(List.of(current), 9, directory).snapshot().orElseThrow();

        assertEquals(original.contentChecksum(), resident.contentChecksum());
        assertEquals(CatalogCanonicalizer.rebaseSnapshotGeneration(original.canonicalContent(), 9), resident.canonicalContent());
        assertEquals("interrupted record", Files.readString(file));
        CatalogStartupIndex.invalidate(directory);
        CatalogSnapshot recovered = compiler(current).compile(List.of(current), 11, directory).snapshot().orElseThrow();
        assertEquals(CatalogCanonicalizer.rebaseSnapshotGeneration(original.canonicalContent(), 11), recovered.canonicalContent());
        CatalogStartupIndex.invalidate(directory);
        assertEquals(recovered.canonicalContent(), CatalogStartupIndex.find(directory, fingerprint(current), 11).orElseThrow().canonicalContent());
    }

    private static String fingerprint(CatalogContribution contribution) {
        return CatalogStartupIndex.fingerprint(CatalogStartupIndex.sourceIdentities(List.of(contribution)),
            MANIFEST.canonicalText(), CONTRACT.toString(), contribution.definitions().size());
    }

    private static CatalogCompiler compiler(CatalogContribution contribution) {
        RuntimeOperationDescriptor requirement = contribution.runtimeRequirements().getFirst();
        return new CatalogCompiler(CONTRACT, CatalogBindingProof.fixed(Map.of(requirement.key(), requirement.executionFingerprint()), MANIFEST));
    }

    private static CatalogContribution contribution(String description, Map<String, Object> metadata, InspectorId inspector) {
        return contribution(description, metadata, inspector, 1);
    }

    private static CatalogContribution contribution(String description, Map<String, Object> metadata, InspectorId inspector, int definitions) {
        ContractRef<CapabilityId> capability = ContractRef.of(OWNER, CapabilityId.of("execute"));
        RuntimeSemantics semantics = new RuntimeSemantics(RuntimeSemantics.Effect.PURE, RuntimeSemantics.ThreadMode.CURRENT,
            ContractRef.of(OWNER, CapabilityId.of("authorize")), RuntimeSemantics.Cancellation.NONE, 0, 0, 0,
            RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER, RuntimeSemantics.Idempotency.INTRINSIC,
            RuntimeSemantics.Audit.NONE, RuntimeSemantics.Confirmation.NONE, RuntimeSemantics.SensitiveData.NONE,
            RuntimeSemantics.Determinism.DETERMINISTIC, Set.of(), Set.of("failed"), Set.of(),
            new RuntimeFailureContract(STRING, Set.of("RUNTIME.FAILURE"), Set.of("failed"), RuntimeFailureContract.CommitBoundary.NO_MUTATION),
            Set.of(), Set.of());
        List<CatalogNodeDescriptor> nodes = IntStream.range(0, definitions).mapToObj(index -> CatalogNodeDescriptor.builder(
            NodeId.of(definitions == 1 ? "operation" : "operation_" + index))
            .domain("flow").family("operation").displayName("Operation").description(description)
            .category(ContractRef.of(OWNER, CapabilityId.of("flow")))
            .handler(new CatalogNodeDescriptor.Handler(capability, ContractRef.of(OWNER, OperationId.of("operation"))))
            .semantics(semantics).inspector(inspector).metadata(metadata)
            .branches(List.of(new CatalogNodeDescriptor.Branch(BranchId.of("failed"), "Failed",
                "The operation did not complete successfully.", List.of(new CatalogNodeDescriptor.Case("failure", "Failure",
                    "The operation reported a structured failure.")))))
            .build()).toList();
        CatalogNodeDescriptor node = nodes.getFirst();
        RuntimeOperationDescriptor requirement = new RuntimeOperationDescriptor(node.handler().capability(), node.handler().operation(), List.of(), semantics);
        return CatalogContribution.builder(OWNER, "1.0.0", new CatalogContractRange(CONTRACT, CONTRACT),
                CatalogProvenance.fromText(CatalogProvenance.SourceKind.BUNDLED, "classpath:/nodes/index.json", "1.0.0", "test", "source"))
            .categories(List.of(new CatalogCategoryDescriptor(CapabilityId.of("flow"), "Flow", "Operations that compose a reusable flow graph.", 1)))
            .capabilities(List.of(new CatalogCapabilityDescriptor(CapabilityId.of("execute"), 1, false, InspectorFallback.GENERIC)))
            .definitions(nodes).runtimeRequirements(List.of(requirement)).build();
    }
}
