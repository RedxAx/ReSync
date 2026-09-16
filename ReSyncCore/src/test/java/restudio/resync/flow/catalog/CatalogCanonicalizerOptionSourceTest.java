package restudio.resync.flow.catalog;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.runtime.RuntimeFailureContract;
import restudio.resync.flow.runtime.RuntimeSemantics;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogCanonicalizerOptionSourceTest {
    private static final OwnerId OWNER = OwnerId.of("resync.canonical");
    private static final TypeExpr STRING = TypeExpr.named(TypeReference.of("builtin", "string"));
    private static final CatalogVersion CONTRACT = new CatalogVersion(1, 0);

    @Test
    void canonicalizesPinOptionSourceAndChangesCatalogChecksum() {
        CatalogNodeDescriptor withoutSource = node(null);
        CatalogNodeDescriptor sourceA = node(ContractRef.of(OWNER, InspectorFieldId.of("options-a")));
        CatalogNodeDescriptor sourceB = node(ContractRef.of(OWNER, InspectorFieldId.of("options-b")));

        String withoutCanonical = CatalogCanonicalizer.canonicalNodeContent(withoutSource);
        String sourceACanonical = CatalogCanonicalizer.canonicalNodeContent(sourceA);
        String sourceBCanonical = CatalogCanonicalizer.canonicalNodeContent(sourceB);

        assertFalse(withoutCanonical.contains("optionSource"));
        assertTrue(sourceACanonical.contains("\"optionSource\":{"));
        assertNotEquals(sourceACanonical, sourceBCanonical);
        assertNotEquals(checksum(withoutSource), checksum(sourceA));
        assertNotEquals(checksum(sourceA), checksum(sourceB));
    }

    @Test
    void canonicalizesPinPresentationDeterministicallyAndChangesCatalogChecksum() {
        Map<String, Object> firstConstraints = new LinkedHashMap<>();
        firstConstraints.put("max", 10);
        firstConstraints.put("min", 0);
        firstConstraints.put("step", 1);
        Map<String, Object> secondConstraints = new LinkedHashMap<>();
        secondConstraints.put("step", 1);
        secondConstraints.put("min", 0);
        secondConstraints.put("max", 10);
        CatalogNodeDescriptor.PinPresentation firstPresentation = new CatalogNodeDescriptor.PinPresentation(
            "slider-v2", List.of(TypedValue.value(STRING, "one")), firstConstraints,
            Map.of("mode", "One,Two"));
        CatalogNodeDescriptor.PinPresentation secondPresentation = new CatalogNodeDescriptor.PinPresentation(
            "slider-v2", List.of(TypedValue.value(STRING, "one")), secondConstraints,
            Map.of("mode", "One,Two"));
        CatalogNodeDescriptor first = node(null, firstPresentation);
        CatalogNodeDescriptor second = node(null, secondPresentation);

        assertEquals(CatalogCanonicalizer.canonicalNodeContent(first), CatalogCanonicalizer.canonicalNodeContent(second));
        assertTrue(CatalogCanonicalizer.canonicalNodeContent(first).contains("\"presentation\""));
        assertNotEquals(checksum(node(null)), checksum(first));
    }

    private static CatalogNodeDescriptor node(ContractRef<InspectorFieldId> optionSource) {
        return node(optionSource, CatalogNodeDescriptor.PinPresentation.empty());
    }

    private static CatalogNodeDescriptor node(ContractRef<InspectorFieldId> optionSource,
                                              CatalogNodeDescriptor.PinPresentation presentation) {
        ContractRef<CapabilityId> editor = ContractRef.of(OWNER, CapabilityId.of("generic-editor"));
        CatalogNodeDescriptor.Pin pin = new CatalogNodeDescriptor.Pin(
            PinId.of("target"), CatalogNodeDescriptor.Direction.INPUT, STRING, "Target",
            "Selects the value used by this operation.", CatalogNodeDescriptor.Requirement.REQUIRED,
            null, editor, optionSource, null, null, null, presentation);
        return CatalogNodeDescriptor.builder(NodeId.of("selector-node"))
            .displayName("Selector Node")
            .description("Selects a value through an authored option source.")
            .category(ContractRef.of(OWNER, CapabilityId.of("flow")))
            .pins(List.of(pin))
            .handler(new CatalogNodeDescriptor.Handler(
                ContractRef.of(OWNER, CapabilityId.of("execute")),
                ContractRef.of(OWNER, OperationId.of("run"))))
            .semantics(semantics())
            .build();
    }

    private static RuntimeSemantics semantics() {
        return new RuntimeSemantics(
            RuntimeSemantics.Effect.PURE,
            RuntimeSemantics.ThreadMode.CURRENT,
            ContractRef.of(OWNER, CapabilityId.of("authorize")),
            RuntimeSemantics.Cancellation.NONE,
            0, 0, 0,
            RuntimeSemantics.UnloadPolicy.DRAIN,
            RuntimeSemantics.Retry.NEVER,
            RuntimeSemantics.Idempotency.INTRINSIC,
            RuntimeSemantics.Audit.NONE,
            RuntimeSemantics.Confirmation.NONE,
            RuntimeSemantics.SensitiveData.NONE,
            RuntimeSemantics.Determinism.DETERMINISTIC,
            Set.of(), Set.of("failed"), Set.of(),
            new RuntimeFailureContract(STRING, Set.of("RUNTIME.FAILURE"), Set.of("failed"), RuntimeFailureContract.CommitBoundary.NO_MUTATION),
            Set.of(), Set.of());
    }

    private static ContentHash checksum(CatalogNodeDescriptor node) {
        CatalogContribution contribution = CatalogContribution.builder(
                OWNER,
                "1.0.0",
                new CatalogContractRange(CONTRACT, CONTRACT),
                new CatalogProvenance(CatalogProvenance.SourceKind.BUNDLED, "test:option-source", "1.0.0", "test"))
            .definitions(List.of(node))
            .build();
        return CatalogCanonicalizer.contentChecksum(CONTRACT, List.of(contribution), Set.of());
    }
}
