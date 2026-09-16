package restudio.resync.flow.catalog;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.inspector.InspectorCapability;
import restudio.resync.flow.inspector.InspectorCondition;
import restudio.resync.flow.inspector.InspectorFallback;
import restudio.resync.flow.inspector.InspectorOptionSource;
import restudio.resync.flow.inspector.InspectorValueSchema;
import restudio.resync.flow.inspector.OptionQuerySchemaV1;
import restudio.resync.flow.runtime.RuntimeFailureContract;
import restudio.resync.flow.runtime.RuntimeOperationDescriptor;
import restudio.resync.flow.runtime.RuntimeSemantics;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogSourceIngestorTest {
    private static final OwnerId OWNER = OwnerId.of("example.catalog");
    private static final OwnerId CORE_OWNER = OwnerId.of("restudio.resync");
    private static final CatalogVersion CONTRACT = new CatalogVersion(1, 0);
    private static final CatalogContractRange RANGE = new CatalogContractRange(CONTRACT, CONTRACT);
    private static final TypeExpr DIAGNOSTIC = TypeExpr.named(TypeReference.of("restudio.resync", "diagnostic"));
    private static final RuntimeSemantics SEMANTICS = pureSemantics();

    @Test
    void ingestsAnObjectAndAnArrayWithRootProvenance() {
        Map<String, Object> first = node("demo.first", "run");
        Map<String, Object> second = node("demo.second", "run");
        String objectText = json(first);
        CatalogContribution object = ingest(objectText, resolverFor(SEMANTICS));

        assertEquals(List.of(NodeId.of("demo.first")), object.definitions().stream().map(CatalogNodeDescriptor::id).toList());
        assertEquals(OWNER, object.ownerId());
        CatalogNodeDescriptor objectDefinition = object.definitions().getFirst();
        assertEquals(ContractRef.of(OWNER, CapabilityId.of("logic")), objectDefinition.category());
        assertEquals(ContractRef.of(OWNER, CapabilityId.of("demo-handler")), objectDefinition.handler().capability());
        assertEquals(ContractRef.of(OWNER, OperationId.of("run")), objectDefinition.handler().operation());
        assertEquals(SEMANTICS, objectDefinition.semantics());
        assertTrue(objectDefinition.pins().isEmpty());
        assertEquals(List.of("completed", "failed"), branchIds(objectDefinition));
        assertEquals(List.of(editor()), object.editors());
        assertEquals("nodes/demo.json", object.provenance().sourceUri());
        assertEquals(ContentHash.of(CanonicalJson.genericCanonicalContentHash(CanonicalJson.parse(objectText))), object.provenance().sourceHash());
        assertEquals(0, object.provenanceFor(object.definitions().getFirst()).entries().getFirst().rowIndex());

        String arrayText = json(List.of(first, second));
        CatalogContribution array = ingest(arrayText, resolverFor(SEMANTICS));

        assertEquals(List.of(NodeId.of("demo.first"), NodeId.of("demo.second")), array.definitions().stream().map(CatalogNodeDescriptor::id).toList());
        assertEquals(ContentHash.of(CanonicalJson.genericCanonicalContentHash(CanonicalJson.parse(arrayText))), array.provenance().sourceHash());
        assertEquals(List.of(0, 1), array.definitions().stream()
            .map(array::provenanceFor)
            .map(value -> value.entries().getFirst().rowIndex())
            .toList());
    }

    @Test
    void materializesRuntimeBranchesAndCompilesTheAuthoredDefinition() {
        Map<String, Object> authored = node("demo.branches", "run");
        authored.put("inputs", List.of(authoredPin("input", "Input", "DATA", "string")));
        CatalogContribution contribution = ingestWithEditor(authored, resolverFor(SEMANTICS));
        CatalogNodeDescriptor definition = contribution.definitions().getFirst();
        RuntimeOperationDescriptor requirement = contribution.runtimeRequirements().getFirst();
        CatalogCompiler compiler = new CatalogCompiler(CONTRACT, CatalogBindingProof.fixed(
            Map.of(requirement.key(), requirement.executionFingerprint()), ContentHash.of("f".repeat(64))));

        CatalogCompilationResult result = compiler.compile(List.of(contribution), 1);

        assertTrue(result.accepted(), result.diagnostics().toString());
        assertEquals(List.of("completed", "failed"), branchIds(definition));
        assertEquals(List.of(CapabilityId.of("demo-handler"), CapabilityId.of("generic-editor")),
            contribution.capabilities().stream().map(CatalogCapabilityDescriptor::id).sorted().toList());
        CatalogNodeDescriptor.Branch failure = definition.branches().stream()
            .filter(value -> value.id().value().equals("failed"))
            .findFirst().orElseThrow();
        assertEquals("Failed", failure.title());
        assertEquals("Reports that this flow capability could not complete.", failure.description());
        assertEquals(List.of(new CatalogNodeDescriptor.Case("failed", "Failed",
            "The flow capability reported a structured failure.")), failure.cases());
    }

    @Test
    void rejectsRuntimeBranchClassificationConflicts() {
        RuntimeSemantics conflicting = semanticsWithBranches(Set.of("shared"), Set.of("shared"), Set.of(), Set.of("shared"));

        assertThrows(IllegalArgumentException.class,
            () -> ingest(node("demo.conflicting-branches", "run"), resolverFor(conflicting)));
    }

    @Test
    void combinesContributionsIndependentlyOfSourceOrder() {
        CatalogContribution first = ingestFile("nodes/a.json", node("demo.a", "run"), resolverFor(SEMANTICS));
        CatalogContribution second = ingestFile("nodes/b.json", node("demo.b", "run"), resolverFor(SEMANTICS));
        CatalogSourceIngestor ingestor = new CatalogSourceIngestor();

        CatalogContribution forward = ingestor.combineContributions(List.of(first, second));
        CatalogContribution reversed = ingestor.combineContributions(List.of(second, first));

        assertEquals(CatalogCanonicalizer.canonicalContribution(forward), CatalogCanonicalizer.canonicalContribution(reversed));
        assertEquals(List.of(NodeId.of("demo.a"), NodeId.of("demo.b")), forward.definitions().stream()
            .map(CatalogNodeDescriptor::id).toList());
    }

    @Test
    void combinesContributionsWithAggregateProvenance() {
        CatalogContribution first = ingestFile("nodes/a.json", node("demo.a", "run"), resolverFor(SEMANTICS));
        CatalogContribution second = ingestFile("nodes/b.json", node("demo.b", "run"), resolverFor(SEMANTICS));
        CatalogContribution combined = new CatalogSourceIngestor().combineContributions(List.of(first, second));

        assertTrue(combined.provenance().sourceUri().startsWith("catalog://aggregate/"));
        assertTrue(!combined.provenance().sourceUri().equals(first.provenance().sourceUri()));
        assertTrue(!combined.provenance().sourceHash().equals(first.provenance().sourceHash()));
        assertEquals(List.of("nodes/a.json", "nodes/b.json"), combined.provenance().entries().stream()
            .map(CatalogProvenance.SourceEntry::sourceUri).toList());
        assertEquals(List.of("demo.a", "demo.b"), combined.provenance().entries().stream()
            .map(CatalogProvenance.SourceEntry::definitionId).toList());
    }

    @Test
    void deduplicatesIdenticalSharedContributionMaterial() {
        InspectorOptionSource options = optionSource(InspectorFieldId.of("demo-options"), type("string"), OWNER, "options.demo");
        CatalogSourceIngestor.OptionSourceResolver optionResolver = id -> Optional.of(options);
        Map<String, Object> firstNode = node("demo.a", "run");
        Map<String, Object> firstPin = authoredPin("choice", "Choice", "DATA", "string");
        firstPin.put("optionsSource", "demo-options");
        firstNode.put("inputs", List.of(firstPin));
        Map<String, Object> secondNode = node("demo.b", "run");
        Map<String, Object> secondPin = authoredPin("choice", "Choice", "DATA", "string");
        secondPin.put("optionsSource", "demo-options");
        secondNode.put("inputs", List.of(secondPin));

        CatalogContribution first = ingestFile("nodes/a.json", firstNode, optionResolver, resolverFor(SEMANTICS));
        CatalogContribution second = ingestFile("nodes/b.json", secondNode, optionResolver, resolverFor(SEMANTICS));
        CatalogContribution combined = new CatalogSourceIngestor().combineContributions(List.of(first, second));

        assertEquals(1, combined.categories().size());
        assertEquals(3, combined.capabilities().size());
        assertEquals(1, combined.editors().size());
        assertEquals(1, combined.optionSources().size());
        assertEquals(1, combined.runtimeRequirements().size());
        assertEquals(2, combined.definitions().size());
    }

    @Test
    void rejectsConflictingSharedIdentityContent() {
        InspectorOptionSource firstOptions = optionSource(InspectorFieldId.of("demo-options"), type("string"), OWNER, "options.demo");
        InspectorOptionSource conflictingOptions = new InspectorOptionSource(firstOptions.id(), "Different Options",
            firstOptions.description(), firstOptions.optionType(), firstOptions.querySchema(), firstOptions.capability(),
            firstOptions.pageLimit(), firstOptions.invalidationKey());
        Map<String, Object> firstNode = node("demo.a", "run");
        Map<String, Object> firstPin = authoredPin("choice", "Choice", "DATA", "string");
        firstPin.put("optionsSource", "demo-options");
        firstNode.put("inputs", List.of(firstPin));
        Map<String, Object> secondNode = node("demo.b", "run");
        Map<String, Object> secondPin = authoredPin("choice", "Choice", "DATA", "string");
        secondPin.put("optionsSource", "demo-options");
        secondNode.put("inputs", List.of(secondPin));
        CatalogContribution first = ingestFile("nodes/a.json", firstNode,
            id -> Optional.of(firstOptions), resolverFor(SEMANTICS));
        CatalogContribution second = ingestFile("nodes/b.json", secondNode,
            id -> Optional.of(conflictingOptions), resolverFor(SEMANTICS));

        assertThrows(IllegalArgumentException.class,
            () -> new CatalogSourceIngestor().combineContributions(List.of(first, second)));
    }

    @Test
    void keepsSharedForeignOptionCapabilitiesAsReferencesAcrossDistinctSources() {
        OwnerId sharedOwner = OwnerId.of("shared.options");
        ContractRef<CapabilityId> sharedCapability = ContractRef.of(sharedOwner, CapabilityId.of("query"));
        InspectorOptionSource firstSource = optionSource(InspectorFieldId.of("shared-a"), type("string"), sharedOwner, "query");
        InspectorOptionSource secondSource = optionSource(InspectorFieldId.of("shared-b"), type("string"), sharedOwner, "query");
        Map<String, Object> first = node("demo.a", "run");
        Map<String, Object> firstPin = authoredPin("choice", "Choice", "DATA", "string");
        firstPin.put("optionsSource", "shared-a");
        first.put("inputs", List.of(firstPin));
        Map<String, Object> second = node("demo.b", "run");
        Map<String, Object> secondPin = authoredPin("choice", "Choice", "DATA", "string");
        secondPin.put("optionsSource", "shared-b");
        second.put("inputs", List.of(secondPin));
        CatalogSourceIngestor.OptionSourceResolver resolver = id -> {
            if (id.equals(firstSource.id())) {
                return Optional.of(firstSource);
            }
            return id.equals(secondSource.id()) ? Optional.of(secondSource) : Optional.empty();
        };

        CatalogContribution contribution = ingest(json(List.of(first, second)), resolver, resolverFor(SEMANTICS),
            resource -> Optional.of(resource));

        assertEquals(2, contribution.optionSources().size());
        assertTrue(contribution.optionSources().stream().allMatch(source -> source.capability().equals(sharedCapability)));
        assertTrue(contribution.capabilities().stream().noneMatch(capability -> capability.id().equals(sharedCapability.id())));
        assertEquals(List.of(ContractRef.of(OWNER, firstSource.id()), ContractRef.of(OWNER, secondSource.id())),
            contribution.definitions().stream().map(definition -> definition.pins().getFirst().optionSource()).toList());
    }

    @Test
    void rejectsMixedOwnerAndVersionContributions() {
        CatalogContribution owner = ingestFile("nodes/a.json", node("demo.a", "run"), resolverFor(SEMANTICS));
        CatalogContribution otherOwner = ingestFile(OwnerId.of("other.catalog"), "nodes/b.json",
            "1.0.0", node("demo.b", "run"), resolverFor(SEMANTICS));
        CatalogContribution otherVersion = ingestFile("nodes/c.json", "2.0.0", node("demo.c", "run"), resolverFor(SEMANTICS));
        CatalogSourceIngestor ingestor = new CatalogSourceIngestor();

        assertThrows(IllegalArgumentException.class, () -> ingestor.combineContributions(List.of(owner, otherOwner)));
        assertThrows(IllegalArgumentException.class, () -> ingestor.combineContributions(List.of(owner, otherVersion)));
    }

    @Test
    void fallsBackToSourceOwnerAndRejectsAnAuthoredOwnerMismatch() {
        Map<String, Object> fallback = node("demo.owner", "run");
        CatalogContribution contribution = ingest(json(fallback), resolverFor(SEMANTICS));

        assertEquals(OWNER, contribution.ownerId());
        assertEquals(OWNER, contribution.definitions().getFirst().reference(OWNER).owner());
        assertEquals(ContractRef.of(OWNER, CapabilityId.of("logic")), contribution.definitions().getFirst().category());

        Map<String, Object> mismatch = node("demo.owner", "run");
        mismatch.put("owner", "other.catalog");
        assertThrows(IllegalArgumentException.class, () -> ingest(json(mismatch), resolverFor(SEMANTICS)));
    }

    @Test
    void preservesTheCompleteSourceDescriptorAndAuthoredSourceProvenance() {
        Map<String, Object> sourceDescriptor = new LinkedHashMap<>();
        sourceDescriptor.put("sourcePath", "migrated/demo.json");
        sourceDescriptor.put("sourceKey", "demo.json\u0000demo.node");
        sourceDescriptor.put("legacyOwner", "restudio");
        sourceDescriptor.put("legacyNodeId", "demo.node");
        sourceDescriptor.put("replacementOwner", OWNER.value());
        sourceDescriptor.put("replacementNodeId", "demo.node");
        sourceDescriptor.put("future", Map.of("keep", List.of("value", true)));
        Map<String, Object> node = node("demo.node", "run");
        node.put("sourceDescriptor", sourceDescriptor);
        String sourceText = json(node);

        CatalogContribution contribution = ingest(sourceText, resolverFor(SEMANTICS));
        CatalogNodeDescriptor definition = contribution.definitions().getFirst();

        assertEquals(sourceDescriptor, definition.metadata().get("sourceDescriptor"));
        Map<?, ?> authored = metadataMap(definition.metadata(), "authoredSource");
        assertEquals("demo.node", authored.get("id"));
        assertEquals("demo-handler", authored.get("handlerCapability"));
        assertEquals("Demo Node", authored.get("displayName"));
        assertEquals(sourceDescriptor, authored.get("sourceDescriptor"));
        Map<?, ?> provenance = metadataMap(authored, "sourceProvenance");
        assertEquals("nodes/demo.json", provenance.get("sourceUri"));
        assertEquals(0, provenance.get("rowIndex"));
        assertEquals(OWNER.value(), provenance.get("owner"));
        assertEquals("demo.node", provenance.get("definitionId"));
        assertEquals(CanonicalJson.genericCanonicalContentHash(CanonicalJson.parse(sourceText)), provenance.get("sourceHash"));
        assertEquals("migrated/demo.json", ((Map<?, ?>) definition.metadata().get("sourceDescriptor")).get("sourcePath"));
    }

    @Test
    void producesTheSameContributionForTheSameCanonicalSource() {
        String sourceText = json(node("demo.deterministic", "run"));

        CatalogContribution first = ingest(sourceText, resolverFor(SEMANTICS));
        CatalogContribution second = ingest(sourceText, resolverFor(SEMANTICS));

        assertEquals(first.ownerId(), second.ownerId());
        assertEquals(first.version(), second.version());
        assertEquals(first.contractRange(), second.contractRange());
        assertEquals(first.categories(), second.categories());
        assertEquals(first.runtimeRequirements(), second.runtimeRequirements());
        assertEquals(first.provenance(), second.provenance());
        assertEquals(definitionSnapshot(first), definitionSnapshot(second));
    }

    @Test
    void preparedSourcesAreEquivalentImmutableAndBoundToOwnerAndHash() {
        Map<String, Object> authored = node("demo.prepared", "run");
        authored.put("owner", OWNER.value());
        byte[] bytes = json(authored).getBytes(StandardCharsets.UTF_8);
        byte[] expectedBytes = Arrays.copyOf(bytes, bytes.length);
        ContentHash hash = ContentHash.of(CanonicalJson.genericCanonicalContentHash(CanonicalJson.parse(bytes)));
        CatalogSourceIngestor.CatalogSource source = CatalogSourceIngestor.CatalogSource.prepared(
            OWNER, CatalogProvenance.SourceKind.BUNDLED, "nodes/prepared.json", "1.0.0", "test-build", bytes, hash);
        CatalogSourceIngestor.CatalogSource baseline = new CatalogSourceIngestor.CatalogSource(
            OWNER, CatalogProvenance.SourceKind.BUNDLED, "nodes/prepared.json", "1.0.0", "test-build", expectedBytes);
        bytes[0] = (byte) (bytes[0] + 1);
        byte[] exposed = source.bytes();
        exposed[0] = (byte) (exposed[0] + 1);
        CatalogSourceIngestor.CatalogIngestionContext context = new CatalogSourceIngestor.CatalogIngestionContext(
            RANGE, List.of(new CatalogCategoryDescriptor("logic", "Logic", "Logic operations used by the test catalog.", 1)),
            editor(), ignored -> Optional.empty(), resolverFor(SEMANTICS), resource -> Optional.of(resource));

        CatalogContribution prepared = new CatalogSourceIngestor().ingest(source, context);
        CatalogContribution ordinary = new CatalogSourceIngestor().ingest(baseline, context);

        assertArrayEquals(expectedBytes, source.bytes());
        assertEquals(hash, source.sourceHash());
        assertEquals(OWNER, source.ownerProof());
        assertEquals(CatalogCanonicalizer.canonicalContribution(ordinary),
            CatalogCanonicalizer.canonicalContribution(prepared));
        assertThrows(IllegalArgumentException.class, () -> CatalogSourceIngestor.CatalogSource.prepared(
            OwnerId.of("other.catalog"), CatalogProvenance.SourceKind.BUNDLED, "nodes/prepared.json", "1.0.0",
            "test-build", expectedBytes, hash));
        assertThrows(IllegalArgumentException.class, () -> CatalogSourceIngestor.CatalogSource.prepared(
            OWNER, CatalogProvenance.SourceKind.BUNDLED, "nodes/prepared.json", "1.0.0", "test-build",
            expectedBytes, ContentHash.of("0".repeat(64))));
        CatalogSourceIngestor.CatalogSource malformed = new CatalogSourceIngestor.CatalogSource(
            OWNER, CatalogProvenance.SourceKind.BUNDLED, "nodes/malformed.json", "1.0.0", "test-build",
            "{".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> new CatalogSourceIngestor().ingest(malformed, context));
    }

    @Test
    void rejectsMalformedRootsMissingAuthoredFieldsAndDuplicateIds() {
        assertThrows(IllegalArgumentException.class, () -> ingest("null", resolverFor(SEMANTICS)));
        assertThrows(IllegalArgumentException.class, () -> ingest("{\"id\":", resolverFor(SEMANTICS)));
        assertThrows(IllegalArgumentException.class, () -> ingest("[]", resolverFor(SEMANTICS)));
        assertThrows(IllegalArgumentException.class, () -> ingest("[1]", resolverFor(SEMANTICS)));

        for (String required : List.of("id", "displayName", "description", "domain", "family", "lifecycle",
            "handlerCapability", "selectorIntent", "inspectorIntent", "category", "schemaVersion",
            "kind", "handler", "handlerConfig")) {
            Map<String, Object> missing = node("demo.missing", "run");
            missing.remove(required);
            assertThrows(IllegalArgumentException.class, () -> ingest(json(missing), resolverFor(SEMANTICS)), required);
        }

        Map<String, Object> duplicate = node("demo.duplicate", "run");
        assertThrows(IllegalArgumentException.class, () -> ingest(json(List.of(duplicate, duplicate)), resolverFor(SEMANTICS)));
    }

    @Test
    void requiresRuntimeResolverToReturnAnExactBinding() {
        Map<String, Object> node = node("demo.runtime", "run");
        AtomicReference<CatalogSourceIngestor.RuntimeRequest> seen = new AtomicReference<>();
        CatalogSourceIngestor.OptionSourceResolver options = source -> Optional.empty();
        CatalogSourceIngestor.ResourceTypeResolver resources = resource -> Optional.of(resource);
        CatalogSourceIngestor.RuntimeRequirementResolver resolver = request -> {
            seen.set(request);
            if (!request.owner().equals(OWNER)
                || !request.node().equals(NodeId.of("demo.runtime"))
                || !request.capability().equals(ContractRef.of(OWNER, CapabilityId.of("demo-handler")))
                || !request.operation().equals(ContractRef.of(OWNER, OperationId.of("run")))
                || !request.handler().equals("DemoHandler")
                || !request.handlerConfig().equals(Map.of("operation", "run"))
                || request.trigger()) {
                return Optional.empty();
            }
            return Optional.of(runtime(request.capability(), request.operation(), request.pins(), SEMANTICS));
        };

        CatalogContribution contribution = ingest(node, options, resolver, resources);

        assertNotNull(seen.get());
        assertEquals(Map.of("operation", "run"), seen.get().handlerConfig());
        Map<String, Object> runtimeSource = seen.get().source();
        for (String field : List.of("id", "kind", "handlerCapability", "sourceDescriptor")) {
            assertTrue(runtimeSource.containsKey(field), field);
        }
        assertEquals(node.get("id"), runtimeSource.get("id"));
        assertEquals(node.get("kind"), runtimeSource.get("kind"));
        assertEquals(node.get("handlerCapability"), runtimeSource.get("handlerCapability"));
        assertEquals(node.get("sourceDescriptor"), runtimeSource.get("sourceDescriptor"));
        assertEquals(1, contribution.runtimeRequirements().size());
        assertEquals(ContractRef.of(OWNER, CapabilityId.of("demo-handler")), contribution.runtimeRequirements().getFirst().capability());
        assertEquals(ContractRef.of(OWNER, OperationId.of("run")), contribution.runtimeRequirements().getFirst().operation());
        assertTrue(contribution.runtimeRequirements().getFirst().pins().isEmpty());
        assertEquals(SEMANTICS, contribution.runtimeRequirements().getFirst().semantics());
        assertEquals(ContractRef.of(OWNER, CapabilityId.of("logic")), contribution.definitions().getFirst().category());

        CatalogSourceIngestor.RuntimeRequirementResolver absent = request -> Optional.empty();
        assertThrows(IllegalArgumentException.class, () -> ingest(node, options, absent, resources));

        CatalogSourceIngestor.RuntimeRequirementResolver mismatched = request -> Optional.of(runtime(
            request.capability(), ContractRef.of(OWNER, OperationId.of("other")), request.pins(), SEMANTICS));
        assertThrows(IllegalArgumentException.class, () -> ingest(node, options, mismatched, resources));

        CatalogSourceIngestor.RuntimeRequirementResolver pinful = request -> Optional.of(runtime(
            request.capability(), request.operation(), List.of(new RuntimeOperationDescriptor.Pin("value",
                RuntimeOperationDescriptor.Direction.INPUT, DIAGNOSTIC)), SEMANTICS));
        assertThrows(IllegalArgumentException.class, () -> ingest(node, options, pinful, resources));
    }

    @Test
    void filtersUnavailableNodesBeforeStrictRuntimeValidation() {
        Map<String, Object> available = node("demo.available", "run");
        Map<String, Object> unavailable = node("demo.unavailable", "run");
        String sourceText = json(List.of(available, unavailable));
        CatalogSourceIngestor.RuntimeRequirementResolver resolver = request -> {
            if (request.node().equals(NodeId.of("demo.unavailable"))) {
                return Optional.empty();
            }
            return Optional.of(runtime(request.capability(), request.operation(), request.pins(), SEMANTICS));
        };

        assertThrows(IllegalArgumentException.class, () -> ingest(sourceText, source -> Optional.empty(), resolver,
            resource -> Optional.of(resource)));

        CatalogContribution contribution = ingest(sourceText, source -> Optional.empty(), resolver,
            resource -> Optional.of(resource), identity -> identity.id().equals(NodeId.of("demo.available")));

        assertEquals(List.of(NodeId.of("demo.available")), contribution.definitions().stream()
            .map(CatalogNodeDescriptor::id).toList());
    }

    @Test
    void ingestsTriggersWithoutHandlersAndDerivesTheirOperation() {
        String eventType = "restudio.resync.flow.automation.event.TestEvent";
        Map<String, Object> authored = triggerNode("event.demo");
        AtomicReference<CatalogSourceIngestor.RuntimeRequest> seen = new AtomicReference<>();
        CatalogContribution contribution = ingest(authored, source -> Optional.empty(), request -> {
            seen.set(request);
            return Optional.of(runtime(request.capability(), request.operation(), request.pins(), SEMANTICS,
                Map.of("eventType", eventType, "handlerConfig", request.handlerConfig())));
        }, resource -> Optional.empty());

        assertNotNull(seen.get());
        assertNull(seen.get().handler());
        assertTrue(seen.get().trigger());
        assertEquals(ContractRef.of(OWNER, OperationId.of("trigger_event.demo")), seen.get().operation());
        assertEquals(json(authored), json(seen.get().source()));
        assertEquals(eventType, contribution.runtimeRequirements().getFirst().unknown().get("eventType"));
        assertEquals(authored.get("handlerConfig"), contribution.runtimeRequirements().getFirst().unknown().get("handlerConfig"));
        assertEquals(List.of("completed", "failed"), branchIds(contribution.definitions().getFirst()));
    }

    @Test
    void prefersAnExplicitTriggerOperationWhenAuthored() {
        String eventType = "restudio.resync.flow.automation.event.TestEvent";
        Map<String, Object> authored = triggerNode("event.explicit");
        authored.put("handlerConfig", Map.of("operation", "explicit_trigger", "playerEvent", false));

        CatalogContribution contribution = ingest(authored, source -> Optional.empty(), request -> Optional.of(
            runtime(request.capability(), request.operation(), request.pins(), SEMANTICS,
                Map.of("eventType", eventType, "handlerConfig", request.handlerConfig()))), resource -> Optional.empty());

        assertEquals(ContractRef.of(OWNER, OperationId.of("explicit_trigger")),
            contribution.definitions().getFirst().handler().operation());
    }

    @Test
    void rejectsInvalidTriggerShapeAndRuntimeMetadata() {
        Map<String, Object> missingEventType = triggerNode("event.missing-type");
        missingEventType.remove("eventType");
        assertThrows(IllegalArgumentException.class, () -> ingest(missingEventType, resolverFor(SEMANTICS)));

        Map<String, Object> wrongKind = triggerNode("event.wrong-kind");
        wrongKind.put("kind", "ACTION");
        assertThrows(IllegalArgumentException.class, () -> ingest(wrongKind, resolverFor(SEMANTICS)));

        Map<String, Object> noFlowOutput = triggerNode("event.no-flow");
        noFlowOutput.put("outputs", List.of(authoredPin("value", "Value", "DATA", "string")));
        assertThrows(IllegalArgumentException.class, () -> ingest(noFlowOutput, resolverFor(SEMANTICS)));

        String eventType = "restudio.resync.flow.automation.event.TestEvent";
        Map<String, Object> authored = triggerNode("event.runtime-metadata");
        List<Map<String, Object>> mismatchedUnknown = List.of(
            Map.of("eventType", "restudio.resync.flow.automation.event.OtherEvent", "handlerConfig", authored.get("handlerConfig")),
            Map.of("eventType", eventType, "handlerConfig", Map.of("playerEvent", true)));
        for (Map<String, Object> unknown : mismatchedUnknown) {
            CatalogSourceIngestor.RuntimeRequirementResolver resolver = request -> Optional.of(
                runtime(request.capability(), request.operation(), request.pins(), SEMANTICS, unknown));
            assertThrows(IllegalArgumentException.class, () -> ingest(authored, resolver));
        }
    }

    @Test
    void keepsNonTriggerHandlerAndOperationRequired() {
        Map<String, Object> missingHandler = node("demo.missing-handler", "run");
        missingHandler.remove("handler");
        assertThrows(IllegalArgumentException.class, () -> ingest(missingHandler, resolverFor(SEMANTICS)));

        Map<String, Object> missingOperation = node("demo.missing-operation", "run");
        missingOperation.put("handlerConfig", Map.of());
        assertThrows(IllegalArgumentException.class, () -> ingest(missingOperation, resolverFor(SEMANTICS)));
    }

    @Test
    void buildsANoPinContributionWithoutMigrationMaterial() {
        CatalogContribution contribution = ingest(json(node("demo.compile", "run")), resolverFor(SEMANTICS));
        assertTrue(contribution.definitions().getFirst().pins().isEmpty());
        assertEquals(List.of("completed", "failed"), branchIds(contribution.definitions().getFirst()));
        assertTrue(contribution.migrations().isEmpty());
        assertEquals(1, contribution.definitions().size());
        assertEquals(1, contribution.runtimeRequirements().size());
        assertEquals(List.of(editor()), contribution.editors());
    }

    @Test
    void emitsMigrationEdgeWithCasePreservingSourcesAndIdentity() {
        Map<String, Object> authored = node("demo.list-concat", "run");
        authored.put("schemaVersion", 2);
        authored.put("inputs", List.of(
            authoredPin("lista", "List A", "DATA", "list<string>"),
            authoredPin("listb", "List B", "DATA", "list<string>")));
        authored.put("outputs", List.of(authoredPin("result", "Result", "DATA", "list<string>")));
        authored.put("migrationMapping", migration(1, 2, true, List.of(
            migrationPin("output", "result", "result"),
            migrationPin("input", "listB", "listb"),
            migrationPin("input", "listA", "lista"))));

        CatalogMigrationEdge edge = ingest(authored, resolverFor(SEMANTICS)).migrations().getFirst();

        assertEquals(CapabilityId.of("migration.demo.list-concat"), edge.id());
        assertEquals(1, edge.fromVersion());
        assertEquals(2, edge.toVersion());
        assertEquals(CatalogMigrationEdge.Kind.DECLARATIVE, edge.kind());
        assertEquals(List.of("demo.list-concat"), edge.touchedIds());
        assertEquals(CatalogMigrationEdge.ConnectionPolicy.REMAP, edge.connectionPolicy());
        assertEquals(OWNER, edge.ownerId());
        assertEquals(NodeId.of("demo.list-concat"), edge.nodeId());
        assertEquals(List.of("listA", "listB", "result"), edge.pinMappings().stream()
            .map(value -> value.source().value()).toList());
        assertEquals(List.of("lista", "listb", "result"), edge.pinMappings().stream()
            .map(value -> value.target().value()).toList());
        assertEquals(List.of(false, false, true), edge.pinMappings().stream()
            .map(CatalogMigrationEdge.PinMapping::identityMeaningful).toList());
    }

    @Test
    void permitsSameRawMigrationSourceInBothDirections() {
        Map<String, Object> authored = node("demo.directional", "run");
        authored.put("schemaVersion", 2);
        authored.put("migrationMapping", migration(1, 2, true, List.of(
            migrationPin("output", "flow", "flow"),
            migrationPin("input", "flow", "flow"))));

        List<CatalogMigrationEdge.PinMapping> mappings = ingest(authored, resolverFor(SEMANTICS))
            .migrations().getFirst().pinMappings();

        assertEquals(2, mappings.size());
        assertEquals(List.of(CatalogNodeDescriptor.Direction.INPUT, CatalogNodeDescriptor.Direction.OUTPUT),
            mappings.stream().map(CatalogMigrationEdge.PinMapping::direction).toList());
        assertEquals(List.of("flow", "flow"), mappings.stream().map(value -> value.source().value()).toList());
        assertTrue(mappings.stream().allMatch(CatalogMigrationEdge.PinMapping::identityMeaningful));
    }

    @Test
    void canonicalizesMigrationMappingOrderDeterministically() {
        Map<String, Object> first = node("demo.order", "run");
        first.put("schemaVersion", 2);
        first.put("migrationMapping", migration(1, 2, true, List.of(
            migrationPin("output", "result", "result"),
            migrationPin("input", "listB", "listb"),
            migrationPin("input", "listA", "lista"))));
        Map<String, Object> second = node("demo.order", "run");
        second.put("schemaVersion", 2);
        second.put("migrationMapping", migration(1, 2, true, List.of(
            migrationPin("input", "listA", "lista"),
            migrationPin("input", "listB", "listb"),
            migrationPin("output", "result", "result"))));

        CatalogMigrationEdge firstEdge = ingest(first, resolverFor(SEMANTICS)).migrations().getFirst();
        CatalogMigrationEdge secondEdge = ingest(second, resolverFor(SEMANTICS)).migrations().getFirst();

        assertEquals(firstEdge, secondEdge);
    }

    @Test
    void rejectsMalformedAndPartialMigrationRows() {
        Map<String, Object> missingPins = node("demo.missing-pins", "run");
        missingPins.put("schemaVersion", 2);
        missingPins.put("migrationMapping", Map.of("sourceSchemaVersion", 1, "targetSchemaVersion", 2, "complete", true));
        assertThrows(IllegalArgumentException.class, () -> ingest(missingPins, resolverFor(SEMANTICS)));

        Map<String, Object> emptyPins = node("demo.empty-pins", "run");
        emptyPins.put("schemaVersion", 2);
        emptyPins.put("migrationMapping", migration(1, 2, true, List.of()));
        assertThrows(IllegalArgumentException.class, () -> ingest(emptyPins, resolverFor(SEMANTICS)));

        Map<String, Object> incomplete = node("demo.incomplete", "run");
        incomplete.put("schemaVersion", 2);
        incomplete.put("migrationMapping", migration(1, 2, false, List.of(migrationPin("input", "value", "value"))));
        assertThrows(IllegalArgumentException.class, () -> ingest(incomplete, resolverFor(SEMANTICS)));

        Map<String, Object> missingSource = node("demo.missing-source", "run");
        missingSource.put("schemaVersion", 2);
        Map<String, Object> malformedPin = migrationPin("input", "value", "value");
        malformedPin.remove("source");
        missingSource.put("migrationMapping", migration(1, 2, true, List.of(malformedPin)));
        assertThrows(IllegalArgumentException.class, () -> ingest(missingSource, resolverFor(SEMANTICS)));

        Map<String, Object> duplicate = node("demo.duplicate-migration-pin", "run");
        duplicate.put("schemaVersion", 2);
        duplicate.put("migrationMapping", migration(1, 2, true, List.of(
            migrationPin("input", "value", "left"), migrationPin("input", "value", "right"))));
        assertThrows(IllegalArgumentException.class, () -> ingest(duplicate, resolverFor(SEMANTICS)));

        Map<String, Object> identityMarker = node("demo.identity-marker", "run");
        identityMarker.put("schemaVersion", 2);
        Map<String, Object> markedPin = migrationPin("input", "value", "value");
        markedPin.put("identityMeaningful", false);
        identityMarker.put("migrationMapping", migration(1, 2, true, List.of(markedPin)));
        assertThrows(IllegalArgumentException.class, () -> ingest(identityMarker, resolverFor(SEMANTICS)));
    }

    @Test
    void preservesHistoricalMigrationIdentityAlongsideAnExplicitCurrentEdge() {
        Map<String, Object> authored = node("demo.history", "run");
        authored.put("schemaVersion", 2);
        Map<String, Object> first = migration(1, 2, true, List.of(migrationPin("output", "result", "result")));
        authored.put("migrationMapping", first);
        CatalogMigrationEdge historical = ingest(authored, resolverFor(SEMANTICS)).migrations().getFirst();
        Map<String, Object> retained = new LinkedHashMap<>(first);
        retained.put("id", historical.id().value());
        Map<String, Object> current = migration(2, 3, true, List.of(migrationPin("output", "result", "result")));
        current.put("id", "migration.demo.history.v2-v3");
        authored.put("schemaVersion", 3);
        authored.put("migrationMapping", current);
        authored.put("migrationHistory", List.of(retained));

        List<CatalogMigrationEdge> edges = ingest(authored, resolverFor(SEMANTICS)).migrations();

        assertEquals(2, edges.size());
        assertEquals(historical, edges.getFirst());
        assertEquals(CapabilityId.of("migration.demo.history.v2-v3"), edges.getLast().id());
        assertEquals(2, edges.getLast().fromVersion());
        assertEquals(3, edges.getLast().toVersion());
    }

    @Test
    void rejectsAmbiguousIncompleteOrDisconnectedMigrationHistory() {
        Map<String, Object> first = migration(1, 2, true, List.of(migrationPin("output", "result", "result")));
        first.put("id", "migration.demo.history");
        Map<String, Object> current = migration(2, 3, true, List.of(migrationPin("output", "result", "result")));
        current.put("id", "migration.demo.history.v2-v3");
        for (String defect : List.of("duplicate", "gap", "historical-id", "current-id", "incomplete", "scope", "missing-current")) {
            Map<String, Object> authored = node("demo.history", "run");
            authored.put("schemaVersion", 3);
            Map<String, Object> history = new LinkedHashMap<>(first);
            Map<String, Object> latest = new LinkedHashMap<>(current);
            switch (defect) {
                case "duplicate" -> history.put("id", latest.get("id"));
                case "gap" -> latest.put("sourceSchemaVersion", 1);
                case "historical-id" -> history.remove("id");
                case "current-id" -> latest.remove("id");
                case "incomplete" -> history.put("complete", false);
                case "scope" -> history.put("nodeId", "other");
                default -> { }
            }
            if (!defect.equals("missing-current")) {
                authored.put("migrationMapping", latest);
            }
            authored.put("migrationHistory", List.of(history));
            assertThrows(IllegalArgumentException.class, () -> ingest(authored, resolverFor(SEMANTICS)), defect);
        }
    }

    @Test
    void rejectsMigrationTargetVersionThatDoesNotMatchNodeSchema() {
        Map<String, Object> authored = node("demo.version-mismatch", "run");
        authored.put("schemaVersion", 2);
        authored.put("migrationMapping", migration(1, 3, true, List.of(
            migrationPin("input", "value", "value"))));

        assertThrows(IllegalArgumentException.class, () -> ingest(authored, resolverFor(SEMANTICS)));
    }

    @Test
    void preservesAuthoredPinIdsDirectionOrderAndDataFlowTypes() {
        Map<String, Object> authored = node("demo.pin-order", "run");
        authored.put("inputs", List.of(
            authoredPin("input-first", "First Input", null, "string"),
            authoredPin("flow-input", "Flow Input", "FLOW", "execution"),
            authoredPin("nested-input", "Nested Input", "DATA", "list<map<string,result<type:t>>>")
        ));
        authored.put("outputs", List.of(
            authoredPin("output-result", "Result Output", "DATA", "result<map<number,type:u>>")
        ));

        CatalogContribution contribution = ingest(authored, source -> Optional.empty(),
            request -> Optional.of(runtime(request.capability(), request.operation(), request.pins(), SEMANTICS)),
            resource -> Optional.empty());
        List<CatalogNodeDescriptor.Pin> pins = contribution.definitions().getFirst().pins();

        assertEquals(List.of("input-first", "flow-input", "nested-input", "output-result"),
            pins.stream().map(pin -> pin.id().value()).toList());
        assertEquals(List.of(CatalogNodeDescriptor.Direction.INPUT, CatalogNodeDescriptor.Direction.INPUT,
            CatalogNodeDescriptor.Direction.INPUT, CatalogNodeDescriptor.Direction.OUTPUT),
            pins.stream().map(CatalogNodeDescriptor.Pin::direction).toList());
        assertNamedReference(pins.get(0).type(), "builtin", "string");
        assertNamedReference(pins.get(1).type(), "builtin", "execution");

        assertTrue(pins.get(2).type() instanceof TypeExpr.ListType);
        TypeExpr map = ((TypeExpr.ListType) pins.get(2).type()).element();
        assertTrue(map instanceof TypeExpr.MapType);
        assertNamedReference(((TypeExpr.MapType) map).key(), "builtin", "string");
        assertTrue(((TypeExpr.MapType) map).value() instanceof TypeExpr.ResultType);
        TypeExpr.ResultType result = (TypeExpr.ResultType) ((TypeExpr.MapType) map).value();
        assertNamedReference(result.success(), "type", "t");
        assertNamedReference(result.failure(), "builtin", "any");

        assertTrue(pins.get(3).type() instanceof TypeExpr.ResultType);
        TypeExpr.ResultType outputResult = (TypeExpr.ResultType) pins.get(3).type();
        assertNamedReference(outputResult.failure(), "builtin", "any");
        TypeExpr outputMap = outputResult.success();
        assertTrue(outputMap instanceof TypeExpr.MapType);
        assertNamedReference(((TypeExpr.MapType) outputMap).key(), "builtin", "number");
        assertNamedReference(((TypeExpr.MapType) outputMap).value(), "type", "u");
    }

    @Test
    void materializesExactTypedRepeatableGroupMembership() {
        Map<String, Object> input = authoredPin("choice", "Choice", "DATA", "string");
        input.put("repeatable", repeatable("cases", 1, 8, "Case"));
        Map<String, Object> output = authoredPin("matched-flow", "Matched Flow", "FLOW", "execution");
        output.put("repeatable", repeatable("cases", 1, 8, "Case"));
        Map<String, Object> authored = node("demo.repeatable", "run");
        authored.put("inputs", List.of(input));
        authored.put("outputs", List.of(output));

        CatalogNodeDescriptor descriptor = ingest(authored, resolverFor(SEMANTICS)).definitions().getFirst();
        CatalogNodeDescriptor.Pin inputPin = descriptor.pins().getFirst();
        CatalogNodeDescriptor.Pin outputPin = descriptor.pins().getLast();
        CatalogNodeDescriptor.RepeatableGroup group = descriptor.repeatables().getFirst();

        assertEquals("cases", inputPin.repeatable().groupId().value());
        assertEquals("cases", outputPin.repeatable().groupId().value());
        assertEquals(1, inputPin.repeatable().minimum());
        assertEquals(8, inputPin.repeatable().maximum());
        assertTrue(inputPin.repeatable().ordered());
        assertEquals("cases", group.id().value());
        assertEquals("Case", group.title());
        assertEquals(1, group.minimum());
        assertEquals(8, group.maximum());
        assertTrue(group.ordered());
        assertEquals(List.of(
            new CatalogNodeDescriptor.RepeatableMember(inputPin.id(), CatalogNodeDescriptor.Direction.INPUT, type("string")),
            new CatalogNodeDescriptor.RepeatableMember(outputPin.id(), CatalogNodeDescriptor.Direction.OUTPUT, type("execution"))),
            group.members());
        assertEquals(TypeExpr.tuple(List.of(type("string"), type("execution"))), group.elementType());
        String canonical = CatalogCanonicalizer.canonicalNodeContent(descriptor);
        assertTrue(canonical.contains("\"groupId\":\"cases\""));
        assertTrue(canonical.contains("\"members\""));
        assertTrue(canonical.contains("\"pinId\":\"choice\""));
        assertTrue(canonical.contains("\"pinId\":\"matched-flow\""));
    }

    @Test
    void rejectsConflictingOrMalformedRepeatableGroupMetadata() {
        for (String defect : List.of("bounds", "label", "unknown")) {
            Map<String, Object> input = authoredPin("choice", "Choice", "DATA", "string");
            Map<String, Object> output = authoredPin("matched-flow", "Matched Flow", "FLOW", "execution");
            Map<String, Object> inputRepeatable = repeatable("cases", 1, 8, "Case");
            Map<String, Object> outputRepeatable = repeatable("cases", 1, 8, "Case");
            switch (defect) {
                case "bounds" -> outputRepeatable.put("maxItems", 9);
                case "label" -> outputRepeatable.put("itemLabel", "Branch");
                case "unknown" -> outputRepeatable.put("future", true);
                default -> throw new IllegalStateException(defect);
            }
            input.put("repeatable", inputRepeatable);
            output.put("repeatable", outputRepeatable);
            Map<String, Object> authored = node("demo.repeatable-" + defect, "run");
            authored.put("inputs", List.of(input));
            authored.put("outputs", List.of(output));

            assertThrows(IllegalArgumentException.class, () -> ingest(authored, resolverFor(SEMANTICS)), defect);
        }
    }

    @Test
    void retainsGroupLessLegacyRepeatableConstructors() {
        CatalogNodeDescriptor.RepeatableIntent intent = new CatalogNodeDescriptor.RepeatableIntent(true, 0, 4, true);
        CatalogNodeDescriptor.RepeatableGroup group = new CatalogNodeDescriptor.RepeatableGroup("legacy", "Legacy",
            "Preserves the original repeatable group constructor.", type("string"), 0, 4, true);

        assertNull(intent.groupId());
        assertTrue(intent.enabled());
        assertTrue(group.members().isEmpty());
        assertEquals(type("string"), group.elementType());
    }

    @Test
    void resolvesRuntimeBuiltinsAndWorldgenProjectAsAResource() {
        Map<String, Object> authored = node("demo.runtime-builtins", "run");
        authored.put("inputs", List.of(
            authoredPin("player", "Player", "DATA", "player"),
            authoredPin("entity", "Entity", "DATA", "entity"),
            authoredPin("block", "Block", "DATA", "block"),
            authoredPin("item", "Item", "DATA", "itemstack"),
            authoredPin("velocity", "Velocity", "DATA", "vector"),
            authoredPin("item-value", "Item Value", "DATA", "item"),
            authoredPin("job", "Job", "DATA", "job_reference<any>"),
            authoredPin("npc", "NPC", "DATA", "npc_handle"),
            authoredPin("worldgen-job", "WorldGen Job", "DATA", "worldgen_job"),
            authoredPin("gamemode", "GameMode", "DATA", "gamemode"),
            authoredPin("inventory", "Inventory", "DATA", "inventory"),
            authoredPin("material", "Material", "DATA", "material"),
            authoredPin("enchantment", "Enchantment", "DATA", "enchantment"),
            authoredPin("potion-effect", "Potion Effect", "DATA", "potion_effect"),
            authoredPin("sound", "Sound", "DATA", "sound"),
            authoredPin("advancement", "Advancement", "DATA", "advancement"),
            authoredPin("project", "Project", "DATA", "worldgen_project")));

        CatalogContribution contribution = ingest(authored, resolverFor(SEMANTICS));
        List<CatalogNodeDescriptor.Pin> pins = contribution.definitions().getFirst().pins();
        assertNamedReference(pins.get(0).type(), "builtin", "player");
        assertNamedReference(pins.get(1).type(), "builtin", "entity");
        assertNamedReference(pins.get(2).type(), "builtin", "block");
        assertNamedReference(pins.get(3).type(), "builtin", "itemstack");
        assertNamedReference(pins.get(4).type(), "builtin", "vector");
        assertNamedReference(pins.get(5).type(), "builtin", "item");
        assertNamedReference(pins.get(6).type(), "builtin", "job_reference");
        assertNamedReference(pins.get(7).type(), "builtin", "npc_handle");
        assertNamedReference(pins.get(8).type(), "builtin", "worldgen_job");
        assertNamedReference(pins.get(9).type(), "builtin", "gamemode");
        assertNamedReference(pins.get(10).type(), "builtin", "inventory");
        assertNamedReference(pins.get(11).type(), "builtin", "material");
        assertNamedReference(pins.get(12).type(), "builtin", "enchantment");
        assertNamedReference(pins.get(13).type(), "builtin", "potion_effect");
        assertNamedReference(pins.get(14).type(), "builtin", "sound");
        assertNamedReference(pins.get(15).type(), "builtin", "advancement");
        assertTrue(pins.get(16).type() instanceof TypeExpr.ResourceType);
        TypeExpr.ResourceType project = (TypeExpr.ResourceType) pins.get(16).type();
        assertEquals(OWNER.value(), project.resourceType().ownerId());
        assertEquals("worldgen_project", project.resourceType().localId());
    }

    @Test
    void acceptsAllDirectCatalogCoreTypesWithResourceReferences() {
        List<String> builtinTypes = List.of(
            "advancement_tree_definition", "biome", "chat_profile", "command_definition", "component",
            "custom_content_definition", "dialog_definition", "difficulty", "entity_data", "entity_type",
            "flow_definition", "function_definition", "gui_definition", "http_response", "item_attribute",
            "item_component", "item_component_list", "item_components", "living_entity", "loot_entry_definition",
            "loot_pool_definition", "loot_table_definition", "message_rule", "motd_profile", "network_transfer_result",
            "network_variable", "npc_definition", "permission_context", "recipe_definition", "recipe_ingredient_definition",
            "runtime_data_category", "runtime_data_entry", "scoreboard_definition", "tab_definition", "text_template",
            "trade_definition", "trade_profile");
        List<String> resourceTypes = List.of("network_route", "player_identity");
        Map<String, Object> authored = node("demo.direct-core-types", "run");
        List<Map<String, Object>> pins = new ArrayList<>();
        builtinTypes.forEach(type -> pins.add(authoredPin(type, type, "DATA", type)));
        resourceTypes.forEach(type -> pins.add(authoredPin(type, type, "DATA", type)));
        authored.put("inputs", pins);

        CatalogContribution contribution = ingest(authored, resolverFor(SEMANTICS));
        List<CatalogNodeDescriptor.Pin> parsed = contribution.definitions().getFirst().pins();
        assertEquals(builtinTypes.size() + resourceTypes.size(), parsed.size());
        for (int index = 0; index < builtinTypes.size(); index++) {
            assertNamedReference(parsed.get(index).type(), "builtin", builtinTypes.get(index));
        }
        for (int index = 0; index < resourceTypes.size(); index++) {
            TypeExpr.ResourceType resource = (TypeExpr.ResourceType) parsed.get(builtinTypes.size() + index).type();
            assertEquals(OWNER.value(), resource.resourceType().ownerId());
            assertEquals(resourceTypes.get(index), resource.resourceType().localId());
        }
    }

    @Test
    void rejectsPinIdsDuplicatedAcrossInputAndOutputNamespaces() {
        Map<String, Object> authored = node("demo.duplicate-pins", "run");
        authored.put("inputs", List.of(authoredPin("shared", "Input", "DATA", "string")));
        authored.put("outputs", List.of(authoredPin("shared", "Output", "DATA", "string")));

        assertThrows(IllegalArgumentException.class, () -> ingest(json(authored), resolverFor(SEMANTICS)));
    }

    @Test
    void rejectsMissingPinIdEvenWhenLegacyNameExists() {
        Map<String, Object> authored = node("demo.missing-pin-id", "run");
        Map<String, Object> pin = authoredPin("value", "Value", "DATA", "string");
        pin.remove("id");
        authored.put("inputs", List.of(pin));

        assertThrows(IllegalArgumentException.class, () -> ingest(json(authored), resolverFor(SEMANTICS)));
    }

    @Test
    void resolvesResourceTypesAndRejectsUnavailableResourceTypes() {
        Map<String, Object> authored = node("demo.resource-pin", "run");
        Map<String, Object> resource = authoredPin("flow", "Flow", "DATA", "resource<flow>");
        resource.put("resourceRole", "reference");
        authored.put("inputs", List.of(resource));

        AtomicReference<ContractRef<ResourceTypeId>> seen = new AtomicReference<>();
        CatalogSourceIngestor.ResourceTypeResolver resources = reference -> {
            seen.set(reference);
            return Optional.of(reference);
        };
        CatalogContribution contribution = ingest(authored, source -> Optional.empty(),
            request -> Optional.of(runtime(request.capability(), request.operation(), request.pins(), SEMANTICS)), resources);
        CatalogNodeDescriptor.Pin pin = contribution.definitions().getFirst().pins().getFirst();

        assertNotNull(seen.get());
        assertEquals("flow", seen.get().id().value());
        assertTrue(pin.type() instanceof TypeExpr.ResourceType);
        assertEquals("flow", ((TypeExpr.ResourceType) pin.type()).resourceType().localId());
        assertEquals("reference", pin.resourceRole());

        CatalogSourceIngestor.ResourceTypeResolver unavailable = reference -> Optional.empty();
        assertThrows(IllegalArgumentException.class, () -> ingest(authored, source -> Optional.empty(),
            request -> Optional.of(runtime(request.capability(), request.operation(), request.pins(), SEMANTICS)), unavailable));
    }

    @Test
    void resolvesAuthoredOptionSourcesAndDeduplicatesTheirDeclarations() {
        String authoredSource = "Server:Resync.Variable Definition";
        InspectorFieldId sourceId = InspectorFieldId.of("server-resync-variable-definition");
        InspectorOptionSource option = optionSource(sourceId, type("string"), OWNER, "variable-options");
        Map<String, Object> first = authoredPin("first-value", "First Value", "DATA", "string");
        first.put("optionsSource", authoredSource);
        Map<String, Object> second = authoredPin("second-value", "Second Value", "DATA", "string");
        second.put("optionsSource", authoredSource);
        Map<String, Object> authored = node("demo.option-source", "run");
        authored.put("inputs", List.of(first, second));

        CatalogContribution contribution = ingest(authored, requested -> {
            assertEquals(sourceId, requested);
            return Optional.of(option);
        }, resolverFor(SEMANTICS), resource -> Optional.empty());

        assertEquals(List.of(option), contribution.optionSources());
        assertEquals(1, contribution.capabilities().stream()
            .filter(value -> value.id().equals(CapabilityId.of("variable-options"))).count());
        ContractRef<InspectorFieldId> reference = ContractRef.of(OWNER, sourceId);
        assertEquals(reference, pin(contribution, "first-value").optionSource());
        assertEquals(reference, pin(contribution, "second-value").optionSource());
    }

    @Test
    void validatesAuthoredOptionSourceIdentityTypeAndProviderOwnership() {
        Map<String, Object> authored = node("demo.option-source-errors", "run");
        Map<String, Object> pin = authoredPin("value", "Value", "DATA", "string");
        pin.put("optionsSource", "server:resync:variable_definition");
        authored.put("inputs", List.of(pin));

        assertThrows(IllegalArgumentException.class, () -> ingest(authored, source -> Optional.empty(),
            resolverFor(SEMANTICS), resource -> Optional.empty()));

        InspectorFieldId expectedId = InspectorFieldId.of("server-resync-variable-definition");
        InspectorOptionSource wrongId = optionSource(InspectorFieldId.of("other-source"), type("string"), OWNER,
            "variable-options");
        assertThrows(IllegalArgumentException.class, () -> ingest(authored, source -> Optional.of(wrongId),
            resolverFor(SEMANTICS), resource -> Optional.empty()));

        InspectorOptionSource wrongType = optionSource(expectedId, type("number"), OWNER, "variable-options");
        assertThrows(IllegalArgumentException.class, () -> ingest(authored, source -> Optional.of(wrongType),
            resolverFor(SEMANTICS), resource -> Optional.empty()));

        OwnerId providerOwner = OwnerId.of("other.catalog");
        InspectorOptionSource foreignProvider = optionSource(expectedId, type("string"), providerOwner,
            "variable-options");
        CatalogContribution foreignContribution = ingest(authored, source -> Optional.of(foreignProvider),
            resolverFor(SEMANTICS), resource -> Optional.empty());

        assertEquals(List.of(foreignProvider), foreignContribution.optionSources());
        assertEquals(ContractRef.of(providerOwner, CapabilityId.of("variable-options")), foreignProvider.capability());
        assertEquals(ContractRef.of(OWNER, expectedId), pin(foreignContribution, "value").optionSource());
        assertTrue(foreignContribution.capabilities().stream()
            .noneMatch(capability -> capability.id().equals(CapabilityId.of("variable-options"))));
    }

    @Test
    void rejectsMalformedAndUnknownPinTypeExpressions() {
        for (String dataType : List.of("list<>", "map<string>", "result<>", "list<map<string>>",
            "list<type:>", "list<type:t", "tuple<string>", "union<string,number>", "opaque<string>",
            "unknown_type", "unknown<type:t>")) {
            Map<String, Object> authored = node("demo.bad-type-" + dataType.hashCode(), "run");
            authored.put("inputs", List.of(authoredPin("value", "Value", "DATA", dataType)));
            assertThrows(IllegalArgumentException.class, () -> ingest(json(authored), resolverFor(SEMANTICS)), dataType);
        }
    }

    @Test
    void requiresExplicitPinTypesInsteadOfInferringAny() {
        Map<String, Object> authored = node("demo.explicit-pin-type", "run");
        Map<String, Object> pin = authoredPin("value", "Value", "DATA", "string");
        pin.remove("dataType");
        authored.put("inputs", List.of(pin));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> ingest(authored, resolverFor(SEMANTICS)));

        assertTrue(error.getMessage().startsWith("TYPE.UNKNOWN:"));
    }

    @Test
    void acceptsExplicitOwnerQualifiedNamedTypes() {
        Map<String, Object> authored = node("demo.qualified-type", "run");
        authored.put("inputs", List.of(authoredPin("value", "Value", "DATA", "extension.types:document")));

        CatalogContribution contribution = ingest(authored, source -> Optional.empty(),
            request -> Optional.of(runtime(request.capability(), request.operation(), request.pins(), SEMANTICS)),
            resource -> Optional.empty());

        assertNamedReference(contribution.definitions().getFirst().pins().getFirst().type(), "extension.types", "document");
    }

    @Test
    void requiresAnExactRuntimePinSignature() {
        Map<String, Object> authored = node("demo.runtime-pins", "run");
        authored.put("inputs", List.of(authoredPin("value", "Value", "DATA", "string")));
        authored.put("outputs", List.of(authoredPin("result", "Result", "DATA", "number")));

        List<RuntimeOperationDescriptor.Pin> expected = List.of(
            new RuntimeOperationDescriptor.Pin("value", RuntimeOperationDescriptor.Direction.INPUT,
                TypeExpr.named(TypeReference.of("builtin", "string"))),
            new RuntimeOperationDescriptor.Pin("result", RuntimeOperationDescriptor.Direction.OUTPUT,
                TypeExpr.named(TypeReference.of("builtin", "number")))
        );
        List<List<RuntimeOperationDescriptor.Pin>> mismatches = List.of(
            List.of(new RuntimeOperationDescriptor.Pin("other", RuntimeOperationDescriptor.Direction.INPUT, expected.getFirst().type()), expected.get(1)),
            List.of(new RuntimeOperationDescriptor.Pin("value", RuntimeOperationDescriptor.Direction.OUTPUT, expected.getFirst().type()), expected.get(1)),
            List.of(expected.get(1), expected.getFirst()),
            List.of(new RuntimeOperationDescriptor.Pin("value", RuntimeOperationDescriptor.Direction.INPUT,
                TypeExpr.named(TypeReference.of("builtin", "boolean"))), expected.get(1)),
            List.of(expected.getFirst()),
            List.of(expected.getFirst(), expected.get(1),
                new RuntimeOperationDescriptor.Pin("extra", RuntimeOperationDescriptor.Direction.OUTPUT, expected.get(1).type()))
        );

        for (List<RuntimeOperationDescriptor.Pin> mismatch : mismatches) {
            CatalogSourceIngestor.RuntimeRequirementResolver resolver = request -> Optional.of(
                runtime(request.capability(), request.operation(), mismatch, SEMANTICS));
            assertThrows(IllegalArgumentException.class, () -> ingest(authored, source -> Optional.empty(), resolver,
                resource -> Optional.empty()));
        }
    }

    @Test
    void blockPropertiesWorldOutputUsesTheBuiltinRuntimeType() {
        Map<String, Object> authored = node("block_properties", "get");
        authored.put("outputs", List.of(authoredPin("world", "World", "DATA", "world")));

        CatalogContribution contribution = ingest(authored, resolverFor(SEMANTICS));
        CatalogNodeDescriptor.Pin world = pin(contribution, "world");
        TypeExpr expected = TypeExpr.named(TypeReference.of("builtin", "world"));

        assertEquals(expected, world.type());
        assertEquals(List.of(new RuntimeOperationDescriptor.Pin("world", RuntimeOperationDescriptor.Direction.OUTPUT, expected)),
            contribution.runtimeRequirements().getFirst().pins());
    }

    @Test
    void convertsTypedDefaultsAndRequirementPrecedenceWithoutChangingRuntimePins() {
        UUID expectedUuid = UUID.fromString("123e4567-e89b-42d3-a456-426614174000");
        Map<String, Object> authored = node("demo.defaults", "run");
        Map<String, Object> booleanPin = authoredPin("boolean-value", "Boolean Value", "DATA", "boolean");
        booleanPin.put("optional", true);
        booleanPin.put("defaultValue", true);
        Map<String, Object> integerPin = authoredPin("integer-value", "Integer Value", "DATA", "integer");
        BigInteger integerDefault = new BigInteger("1234567890123456789012345678901234567890");
        integerPin.put("defaultValue", integerDefault);
        Map<String, Object> numberPin = authoredPin("number-value", "Number Value", "DATA", "number");
        numberPin.put("defaultValue", new BigDecimal("1.25"));
        Map<String, Object> optionalPin = authoredPin("optional-value", "Optional Value", "DATA", "string");
        optionalPin.put("optional", true);
        Map<String, Object> requiredPin = authoredPin("required-value", "Required Value", "DATA", "any");
        Map<String, Object> stringPin = authoredPin("string-value", "String Value", "DATA", "string");
        stringPin.put("defaultValue", "hello");
        Map<String, Object> uuidPin = authoredPin("uuid-value", "UUID Value", "DATA", "uuid");
        uuidPin.put("defaultValue", expectedUuid.toString());
        Map<String, Object> listPin = authoredPin("list-value", "List Value", "DATA", "list<string>");
        listPin.put("defaultValue", List.of("one", "two"));
        Map<String, Object> mapPin = authoredPin("map-value", "Map Value", "DATA", "map<string,integer>");
        mapPin.put("defaultValue", Map.of("answer", 42));
        authored.put("inputs", List.of(booleanPin, integerPin, numberPin, optionalPin, requiredPin, stringPin, uuidPin, listPin, mapPin));

        AtomicReference<CatalogSourceIngestor.RuntimeRequest> seen = new AtomicReference<>();
        CatalogContribution contribution = ingest(authored, source -> Optional.empty(), request -> {
            seen.set(request);
            return Optional.of(runtime(request.capability(), request.operation(), request.pins(), SEMANTICS));
        }, resource -> Optional.empty());

        CatalogNodeDescriptor.Pin booleanDescriptor = pin(contribution, "boolean-value");
        CatalogNodeDescriptor.Pin integerDescriptor = pin(contribution, "integer-value");
        CatalogNodeDescriptor.Pin numberDescriptor = pin(contribution, "number-value");
        CatalogNodeDescriptor.Pin optionalDescriptor = pin(contribution, "optional-value");
        CatalogNodeDescriptor.Pin stringDescriptor = pin(contribution, "string-value");
        CatalogNodeDescriptor.Pin uuidDescriptor = pin(contribution, "uuid-value");
        CatalogNodeDescriptor.Pin listDescriptor = pin(contribution, "list-value");
        CatalogNodeDescriptor.Pin mapDescriptor = pin(contribution, "map-value");

        assertEquals(CatalogNodeDescriptor.Requirement.DEFAULTED, booleanDescriptor.requirement());
        assertEquals(TypedValue.value(type("boolean"), true), booleanDescriptor.defaultValue());
        assertEquals(CatalogNodeDescriptor.Requirement.DEFAULTED, integerDescriptor.requirement());
        assertEquals(integerDefault, integerDescriptor.defaultValue().value());
        assertEquals(CatalogNodeDescriptor.Requirement.DEFAULTED, numberDescriptor.requirement());
        assertEquals(0, new BigDecimal("1.25").compareTo(number(numberDescriptor.defaultValue())));
        assertEquals(CatalogNodeDescriptor.Requirement.OPTIONAL, optionalDescriptor.requirement());
        assertNull(optionalDescriptor.defaultValue());
        CatalogNodeDescriptor.Pin requiredDescriptor = pin(contribution, "required-value");
        assertEquals(CatalogNodeDescriptor.Requirement.REQUIRED, requiredDescriptor.requirement());
        assertNull(requiredDescriptor.defaultValue());
        assertEquals(CatalogNodeDescriptor.Requirement.DEFAULTED, stringDescriptor.requirement());
        assertEquals(TypedValue.value(type("string"), "hello"), stringDescriptor.defaultValue());
        assertEquals(TypedValue.value(type("uuid"), expectedUuid), uuidDescriptor.defaultValue());
        assertEquals(List.of("one", "two"), listDescriptor.defaultValue().value());
        assertEquals("{\"answer\":42}", CanonicalJson.canonicalize(mapDescriptor.defaultValue().value()));

        assertNotNull(seen.get());
        assertEquals(List.of(
            new RuntimeOperationDescriptor.Pin("boolean-value", RuntimeOperationDescriptor.Direction.INPUT, type("boolean")),
            new RuntimeOperationDescriptor.Pin("integer-value", RuntimeOperationDescriptor.Direction.INPUT, type("integer")),
            new RuntimeOperationDescriptor.Pin("number-value", RuntimeOperationDescriptor.Direction.INPUT, type("number")),
            new RuntimeOperationDescriptor.Pin("optional-value", RuntimeOperationDescriptor.Direction.INPUT, type("string")),
            new RuntimeOperationDescriptor.Pin("required-value", RuntimeOperationDescriptor.Direction.INPUT, type("any")),
            new RuntimeOperationDescriptor.Pin("string-value", RuntimeOperationDescriptor.Direction.INPUT, type("string")),
            new RuntimeOperationDescriptor.Pin("uuid-value", RuntimeOperationDescriptor.Direction.INPUT, type("uuid")),
            new RuntimeOperationDescriptor.Pin("list-value", RuntimeOperationDescriptor.Direction.INPUT, type("list<string>")),
            new RuntimeOperationDescriptor.Pin("map-value", RuntimeOperationDescriptor.Direction.INPUT, type("map<string,integer>"))
        ), seen.get().pins());
    }

    @Test
    void rejectsTypedDefaultMismatchesFractionalIntegersAndInvalidNumbers() {
        assertRejectingDefault("boolean", "true");
        assertRejectingDefault("integer", new BigDecimal("1.5"));
        assertRejectingDefault("number", "1.5");
        assertRejectingDefault("string", 7);
        assertRejectingDefault("uuid", "not-a-uuid");
        assertRejectingDefault("list<string>", List.of("ok", 7));
        assertRejectingDefault("map<string,integer>", Map.of("answer", "wrong"));
        assertRejectingDefault("extension.types:document", Map.of("unverified", true));
        assertRejectingDefault("list<type:t>", List.of("unverified"));
        assertRejectingDefault("resource_reference<example.catalog:flow>", null);

        Map<String, Object> nonFinite = authoredPin("non-finite", "Non-Finite", "DATA", "number");
        nonFinite.put("defaultValue", Double.NaN);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> ingest(defaultNode(nonFinite), resolverFor(SEMANTICS)));
        assertEquals("Canonical numbers must be finite", error.getMessage());

    }

    @Test
    void ingestsTypedStaticOptionsAndPreservesPinPresentation() {
        Map<String, Object> authored = node("demo.presentation", "run");
        Map<String, Object> number = authoredPin("amount", "Amount", "DATA", "number");
        number.put("widget", "slider-v2");
        number.put("options", List.of(1, new BigDecimal("1.5")));
        number.put("constraints", Map.of("min", 0, "max", 10, "step", new BigDecimal("0.5")));
        number.put("visibleWhen", Map.of("action", "Increment,Decrement"));
        Map<String, Object> optional = authoredPin("optional", "Optional", "DATA", "optional<string>");
        optional.put("options", Arrays.asList(null, "value"));
        authored.put("inputs", List.of(number, optional));

        CatalogContribution contribution = ingest(authored, resolverFor(SEMANTICS));
        CatalogNodeDescriptor.Pin amount = pin(contribution, "amount");
        CatalogNodeDescriptor.Pin optionalPin = pin(contribution, "optional");

        assertEquals("slider-v2", amount.presentation().widget());
        assertEquals(List.of(TypedValue.value(type("number"), new BigDecimal("1")),
            TypedValue.value(type("number"), new BigDecimal("1.5"))), amount.presentation().options());
        assertEquals(Map.of("min", new BigDecimal("0"), "max", new BigDecimal("10").stripTrailingZeros(), "step", new BigDecimal("0.5")), amount.presentation().constraints());
        assertEquals(Map.of("action", "Increment,Decrement"), amount.presentation().visibleWhen());
        assertEquals(InspectorCondition.always(), amount.visibility());
        assertEquals(TypedValue.nullValue(optionalPin.type()), optionalPin.presentation().options().getFirst());
        assertEquals(TypedValue.value(optionalPin.type(), "value"), optionalPin.presentation().options().get(1));
    }

    @Test
    void rejectsMalformedStaticPinPresentationAndUnsupportedNullOptions() {
        Map<String, Object> wrongOptions = authoredPin("wrong-options", "Wrong Options", "DATA", "string");
        wrongOptions.put("options", "not-an-array");
        Map<String, Object> wrongWidget = authoredPin("wrong-widget", "Wrong Widget", "DATA", "string");
        wrongWidget.put("widget", 4);
        Map<String, Object> wrongConstraints = authoredPin("wrong-constraints", "Wrong Constraints", "DATA", "string");
        wrongConstraints.put("constraints", List.of("min", 0));
        Map<String, Object> wrongVisibleWhen = authoredPin("wrong-visible-when", "Wrong Visible When", "DATA", "string");
        wrongVisibleWhen.put("visibleWhen", List.of(Map.of("action", "Run")));
        for (Map<String, Object> pin : List.of(wrongOptions, wrongWidget, wrongConstraints, wrongVisibleWhen)) {
            assertThrows(IllegalArgumentException.class, () -> ingest(defaultNode(pin), resolverFor(SEMANTICS)));
        }

        Map<String, Object> resource = authoredPin("resource-option", "Resource Option", "DATA", "resource<flow>");
        resource.put("options", Arrays.asList((Object) null));
        assertThrows(IllegalArgumentException.class, () -> ingest(defaultNode(resource), source -> Optional.empty(),
            resolverFor(SEMANTICS), resourceType -> Optional.of(resourceType)));
    }

    private static void assertRejectingDefault(String dataType, Object defaultValue) {
        Map<String, Object> pin = authoredPin("invalid-default", "Invalid Default", "DATA", dataType);
        pin.put("defaultValue", defaultValue);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> ingest(defaultNode(pin), resolverFor(SEMANTICS)), dataType);
        assertTrue(error.getMessage().startsWith("TYPE.VALUE_INVALID:"), dataType);
    }

    private static Map<String, Object> defaultNode(Map<String, Object> pin) {
        Map<String, Object> node = node("demo.invalid-default", "run");
        node.put("inputs", List.of(pin));
        return node;
    }

    private static CatalogNodeDescriptor.Pin pin(CatalogContribution contribution, String id) {
        return contribution.definitions().getFirst().pins().stream()
            .filter(value -> value.id().value().equals(id))
            .findFirst()
            .orElseThrow();
    }

    private static List<String> branchIds(CatalogNodeDescriptor definition) {
        return definition.branches().stream().map(value -> value.id().value()).toList();
    }

    private static BigDecimal number(TypedValue value) {
        return new BigDecimal(value.value().toString());
    }

    private static TypeExpr type(String expression) {
        if (expression.contains("<")) {
            String outer = expression.substring(0, expression.indexOf('<'));
            String inner = expression.substring(expression.indexOf('<') + 1, expression.length() - 1);
            if (outer.equals("list")) {
                return TypeExpr.list(type(inner));
            }
            int separator = inner.indexOf(',');
            return TypeExpr.map(type(inner.substring(0, separator)), type(inner.substring(separator + 1)));
        }
        return TypeExpr.named(TypeReference.of("builtin", expression));
    }

    private static CatalogContribution ingest(String sourceText, CatalogSourceIngestor.RuntimeRequirementResolver resolver) {
        return ingest(sourceText, source -> Optional.empty(), resolver, resource -> Optional.of(resource));
    }

    private static CatalogContribution ingest(Map<String, Object> node, CatalogSourceIngestor.RuntimeRequirementResolver resolver) {
        return ingest(node, source -> Optional.empty(), resolver, resource -> Optional.of(resource));
    }

    private static CatalogContribution ingest(Map<String, Object> node, CatalogSourceIngestor.OptionSourceResolver options,
                                              CatalogSourceIngestor.RuntimeRequirementResolver resolver,
                                              CatalogSourceIngestor.ResourceTypeResolver resources) {
        return ingest(json(node), options, resolver, resources);
    }

    private static CatalogContribution ingest(String sourceText, CatalogSourceIngestor.OptionSourceResolver options,
                                              CatalogSourceIngestor.RuntimeRequirementResolver resolver,
                                              CatalogSourceIngestor.ResourceTypeResolver resources) {
        return ingest(sourceText, options, resolver, resources, ignored -> true);
    }

    private static CatalogContribution ingest(String sourceText, CatalogSourceIngestor.OptionSourceResolver options,
                                              CatalogSourceIngestor.RuntimeRequirementResolver resolver,
                                              CatalogSourceIngestor.ResourceTypeResolver resources,
                                              Predicate<ContractRef<NodeId>> availability) {
        CatalogSourceIngestor.CatalogSource source = new CatalogSourceIngestor.CatalogSource(
            OWNER, CatalogProvenance.SourceKind.BUNDLED, "nodes/demo.json", "1.0.0", "test-build",
            sourceText.getBytes(StandardCharsets.UTF_8));
        CatalogSourceIngestor.CatalogIngestionContext context = new CatalogSourceIngestor.CatalogIngestionContext(
            RANGE, List.of(new CatalogCategoryDescriptor("logic", "Logic", "Logic operations used by the test catalog.", 1)),
            editor(), options, resolver, resources);
        return new CatalogSourceIngestor().ingest(source, context, availability);
    }

    private static CatalogContribution ingestFile(String sourceUri, Map<String, Object> node,
                                                   CatalogSourceIngestor.RuntimeRequirementResolver resolver) {
        return ingestFile(OWNER, sourceUri, "1.0.0", node, source -> Optional.empty(), resolver);
    }

    private static CatalogContribution ingestFile(String sourceUri, String version, Map<String, Object> node,
                                                   CatalogSourceIngestor.RuntimeRequirementResolver resolver) {
        return ingestFile(OWNER, sourceUri, version, node, source -> Optional.empty(), resolver);
    }

    private static CatalogContribution ingestFile(String sourceUri, Map<String, Object> node,
                                                   CatalogSourceIngestor.OptionSourceResolver options,
                                                   CatalogSourceIngestor.RuntimeRequirementResolver resolver) {
        return ingestFile(OWNER, sourceUri, "1.0.0", node, options, resolver);
    }

    private static CatalogContribution ingestFile(OwnerId owner, String sourceUri, String version,
                                                   Map<String, Object> node,
                                                   CatalogSourceIngestor.RuntimeRequirementResolver resolver) {
        return ingestFile(owner, sourceUri, version, node, source -> Optional.empty(), resolver);
    }

    private static CatalogContribution ingestFile(OwnerId owner, String sourceUri, String version,
                                                   Map<String, Object> node,
                                                   CatalogSourceIngestor.OptionSourceResolver options,
                                                   CatalogSourceIngestor.RuntimeRequirementResolver resolver) {
        CatalogSourceIngestor.CatalogSource source = new CatalogSourceIngestor.CatalogSource(
            owner, CatalogProvenance.SourceKind.BUNDLED, sourceUri, version, "test-build", json(node).getBytes(StandardCharsets.UTF_8));
        CatalogSourceIngestor.CatalogIngestionContext context = new CatalogSourceIngestor.CatalogIngestionContext(
            RANGE, List.of(new CatalogCategoryDescriptor("logic", "Logic", "Logic operations used by the test catalog.", 1)),
            editor(), options, resolver, resource -> Optional.of(resource));
        return new CatalogSourceIngestor().ingest(source, context);
    }

    private static CatalogContribution ingestWithEditor(Map<String, Object> node,
                                                         CatalogSourceIngestor.RuntimeRequirementResolver resolver) {
        CatalogSourceIngestor.CatalogSource source = new CatalogSourceIngestor.CatalogSource(
            OWNER, CatalogProvenance.SourceKind.BUNDLED, "nodes/editor.json", "1.0.0", "test-build",
            json(node).getBytes(StandardCharsets.UTF_8));
        CatalogSourceIngestor.CatalogIngestionContext context = new CatalogSourceIngestor.CatalogIngestionContext(
            RANGE, List.of(new CatalogCategoryDescriptor("logic", "Logic", "Logic operations used by the test catalog.", 1)),
            editor(OWNER), ignored -> Optional.empty(), resolver, resource -> Optional.of(resource));
        return new CatalogSourceIngestor().ingest(source, context);
    }

    private static CatalogSourceIngestor.RuntimeRequirementResolver resolverFor(RuntimeSemantics semantics) {
        return request -> Optional.of(runtime(request.capability(), request.operation(), request.pins(), semantics));
    }

    private static RuntimeOperationDescriptor runtime(ContractRef<CapabilityId> capability, ContractRef<OperationId> operation,
                                                       List<RuntimeOperationDescriptor.Pin> pins, RuntimeSemantics semantics) {
        return new RuntimeOperationDescriptor(capability, operation, pins, semantics);
    }

    private static RuntimeOperationDescriptor runtime(ContractRef<CapabilityId> capability, ContractRef<OperationId> operation,
                                                       List<RuntimeOperationDescriptor.Pin> pins, RuntimeSemantics semantics,
                                                       Map<String, ?> unknown) {
        return new RuntimeOperationDescriptor(capability, operation, pins, semantics, unknown);
    }

    private static RuntimeSemantics pureSemantics() {
        return new RuntimeSemantics(RuntimeSemantics.Effect.PURE, RuntimeSemantics.ThreadMode.CURRENT,
            ContractRef.of(CORE_OWNER, CapabilityId.of("flow.execute")), RuntimeSemantics.Cancellation.NONE,
            0, 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER,
            RuntimeSemantics.Idempotency.INTRINSIC, RuntimeSemantics.Audit.NONE,
            RuntimeSemantics.Confirmation.NONE, RuntimeSemantics.SensitiveData.NONE,
            RuntimeSemantics.Determinism.DETERMINISTIC, Set.of("completed"), Set.of("failed"), Set.of(),
            new RuntimeFailureContract(DIAGNOSTIC, Set.of("RUNTIME.FAILURE"), Set.of("failed"),
            RuntimeFailureContract.CommitBoundary.NO_MUTATION), Set.of(), Set.of());
    }

    private static RuntimeSemantics semanticsWithBranches(Set<String> success, Set<String> failure,
                                                           Set<String> cancellation, Set<String> failureContract) {
        return new RuntimeSemantics(SEMANTICS.effect(), SEMANTICS.thread(), SEMANTICS.authorization(),
            SEMANTICS.cancellation(), SEMANTICS.timeoutMillis(), SEMANTICS.drainDeadlineMillis(),
            SEMANTICS.hardDeadlineMillis(), SEMANTICS.unloadPolicy(), SEMANTICS.retry(), SEMANTICS.idempotency(),
            SEMANTICS.audit(), SEMANTICS.confirmation(), SEMANTICS.sensitiveData(), SEMANTICS.determinism(),
            success, failure, cancellation,
            new RuntimeFailureContract(DIAGNOSTIC, Set.of("RUNTIME.FAILURE"), failureContract,
                RuntimeFailureContract.CommitBoundary.NO_MUTATION), SEMANTICS.resourceReads(), SEMANTICS.resourceWrites());
    }

    private static InspectorCapability editor() {
        return editor(CORE_OWNER);
    }

    private static InspectorCapability editor(OwnerId owner) {
        TypeExpr text = TypeExpr.named(TypeReference.of("restudio.resync", "text"));
        return new InspectorCapability(ContractRef.of(owner, CapabilityId.of("generic-editor")), "Generic Editor",
            "Provides the shared editor capability used by this catalog test.", new InspectorValueSchema(text),
            new InspectorValueSchema(text), List.of(), ContractRef.of(owner, CapabilityId.of("generic-editor")),
            InspectorFallback.GENERIC);
    }

    private static InspectorOptionSource optionSource(InspectorFieldId id, TypeExpr optionType, OwnerId owner,
                                                      String capabilityId) {
        return new InspectorOptionSource(id, "Authored Options", "Provides authored choices for this focused test.",
            optionType, OptionQuerySchemaV1.empty(), ContractRef.of(owner, CapabilityId.of(capabilityId)), 50);
    }

    private static Map<String, Object> node(String id, String operation) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", id);
        value.put("displayName", "Demo Node");
        value.put("description", "Runs the demo operation and reports its completion result.");
        value.put("domain", "logic");
        value.put("family", "demo");
        value.put("lifecycle", "active");
        value.put("category", "logic");
        value.put("schemaVersion", 1);
        value.put("kind", "PURE");
        value.put("handlerCapability", "demo-handler");
        value.put("selectorIntent", "none");
        value.put("inspectorIntent", "generic");
        value.put("handler", "DemoHandler");
        value.put("handlerConfig", Map.of("operation", operation));
        value.put("trigger", false);
        value.put("inputs", List.of());
        value.put("outputs", List.of());
        value.put("semantics", semanticsMap());
        value.put("sourceDescriptor", Map.of());
        return value;
    }

    private static Map<String, Object> triggerNode(String id) {
        Map<String, Object> value = node(id, "unused");
        value.put("kind", "EVENT");
        value.put("handlerCapability", "automation.trigger");
        value.remove("handler");
        value.put("handlerConfig", Map.of("playerEvent", false));
        value.put("trigger", true);
        value.put("eventType", "restudio.resync.flow.automation.event.TestEvent");
        value.put("outputs", List.of(authoredPin("flow", "Flow", "FLOW", "execution")));
        return value;
    }

    private static Map<String, Object> authoredPin(String id, String displayName, String pinType, String dataType) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", id);
        value.put("name", "legacy-" + id);
        value.put("displayName", displayName);
        value.put("description", "A catalog pin used by this focused test.");
        if (pinType != null) {
            value.put("pinType", pinType);
        }
        value.put("dataType", dataType);
        return value;
    }

    private static Map<String, Object> repeatable(String groupId, int minimum, int maximum, String itemLabel) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("groupId", groupId);
        value.put("minItems", minimum);
        value.put("maxItems", maximum);
        value.put("itemLabel", itemLabel);
        return value;
    }

    private static Map<String, Object> migration(int sourceVersion, int targetVersion, boolean complete,
                                                 List<Map<String, Object>> pins) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("sourceSchemaVersion", sourceVersion);
        value.put("targetSchemaVersion", targetVersion);
        value.put("complete", complete);
        value.put("pins", pins);
        return value;
    }

    private static Map<String, Object> migrationPin(String direction, String source, String target) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("direction", direction);
        value.put("source", source);
        value.put("target", target);
        return value;
    }

    private static void assertNamedReference(TypeExpr expression, String expectedOwnerId, String expectedLocalId) {
        assertTrue(expression instanceof TypeExpr.Named, "Expected a named type: " + expression);
        TypeReference reference = ((TypeExpr.Named) expression).reference();
        assertEquals(expectedOwnerId, reference.ownerId());
        assertEquals(expectedLocalId, reference.localId());
    }

    private static Map<String, Object> semanticsMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("effect", "pure");
        value.put("thread", "current");
        value.put("authorization", Map.of("ownerId", CORE_OWNER.value(), "localId", "flow.execute"));
        value.put("cancellation", "none");
        value.put("timeoutMillis", 0);
        value.put("drainDeadlineMillis", 0);
        value.put("hardDeadlineMillis", 0);
        value.put("unloadPolicy", "drain");
        value.put("retry", "never");
        value.put("idempotency", "intrinsic");
        value.put("audit", "none");
        value.put("confirmation", "none");
        value.put("sensitiveData", "none");
        value.put("determinism", "deterministic");
        value.put("success", List.of("completed"));
        value.put("failure", List.of("failed"));
        value.put("cancelled", List.of());
        value.put("failureContract", Map.of(
            "payloadType", DIAGNOSTIC.canonicalValue(),
            "diagnosticCodes", List.of("RUNTIME.FAILURE"),
            "branches", List.of("failed"),
            "commitBoundary", "no-mutation"));
        value.put("resourceReads", List.of());
        value.put("resourceWrites", List.of());
        return value;
    }

    private static String json(Object value) {
        return CanonicalJson.canonicalize(value);
    }

    private static Map<?, ?> metadataMap(Object value, String key) {
        assertTrue(value instanceof Map<?, ?>, "Expected metadata map");
        Object nested = ((Map<?, ?>) value).get(key);
        assertTrue(nested instanceof Map<?, ?>, "Expected metadata map for " + key);
        return (Map<?, ?>) nested;
    }

    private static List<Map<String, Object>> definitionSnapshot(CatalogContribution contribution) {
        return contribution.definitions().stream().map(definition -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", definition.id().value());
            value.put("schemaVersion", definition.schemaVersion());
            value.put("lifecycle", definition.lifecycle().name());
            value.put("domain", definition.domain());
            value.put("family", definition.family());
            value.put("displayName", definition.displayName());
            value.put("description", definition.description());
            value.put("category", definition.category().canonicalText());
            value.put("pins", definition.pins());
            value.put("modes", definition.modes());
            value.put("branches", definition.branches());
            value.put("repeatables", definition.repeatables());
            value.put("inspector", definition.inspector() == null ? null : definition.inspector().value());
            value.put("handlerCapability", definition.handler().capability().canonicalText());
            value.put("handlerOperation", definition.handler().operation().canonicalText());
            value.put("semantics", definition.semantics().canonicalValue());
            value.put("requiredCapabilities", definition.requiredCapabilities().stream()
                .map(ContractRef::canonicalText).sorted().toList());
            value.put("replacementIdentity", definition.replacementIdentity() == null
                ? null : definition.replacementIdentity().canonicalText());
            value.put("preview", Map.of("intent", definition.preview().intent(), "readOnly", definition.preview().readOnly()));
            value.put("metadata", definition.metadata());
            return value;
        }).toList();
    }
}
