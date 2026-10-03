package restudio.resync.flow.function;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.FunctionBinding;
import restudio.resync.flow.graph.FunctionParameter;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.GraphVariable;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.graph.PinValue;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypedFunctionResolverIdentityTest {
    private static final ServerResourceLocator RESOURCE = new ServerResourceLocator(new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111")),
        ContractRef.of(new OwnerId("resync"), new ResourceTypeId("function")), "identity-function");
    private static final CatalogBinding BINDING = new CatalogBinding(1, new ContentHash("0".repeat(64)), new ContentHash("1".repeat(64)));
    private static final TypeExpr TEXT = TypeExpr.named(new TypeReference("builtin", "string"));
    private static final TypeExpr NUMBER = TypeExpr.named(new TypeReference("builtin", "number"));
    private static final FunctionParameterId OUTPUT = FunctionParameterId.of(UUID.fromString("22222222-2222-4222-8222-222222222222"));
    private static final NodeInstanceId NODE = NodeInstanceId.of(UUID.fromString("33333333-3333-4333-8333-333333333333"));
    private static final PinId VALUE = PinId.of("value");
    private static final ContractRef<NodeId> DEFINITION = ContractRef.of(new OwnerId("typed"), NodeId.of("literal"));
    private static final ContractRef<CapabilityId> CAPABILITY = ContractRef.of(new OwnerId("typed"), CapabilityId.of("function-test"));
    private static final ContractRef<OperationId> OPERATION = ContractRef.of(new OwnerId("typed"), OperationId.of("literal"));

    @Test
    void everyCommittedStampIsValidatedBeforeAResidentBodyCanBeReturned() {
        FunctionSourceDocument source = source(4, TEXT, "original", BINDING);
        TypedFunctionCapabilitySet capabilities = capabilities(BINDING, literal());
        TypedFunctionResolver resolver = detachedResolver();
        ContentHash checksum = resolver.sourceChecksum(source);
        ContentHash fingerprint = resolver.capabilityFingerprint(capabilities);
        CompiledFunction accepted = resolver.resolveResult(source, capabilities, checksum, BINDING, fingerprint).function();
        assertTrue(accepted != null);
        assertEquals(source.checksum(), checksum);
        assertEquals(capabilities.fingerprint(), fingerprint);

        assertCode("FUNCTION.RESOLVER_SOURCE_MISMATCH", resolver.resolveResult(source, capabilities,
            new ContentHash("f".repeat(64)), BINDING, fingerprint));
        assertCode("FUNCTION.FINGERPRINT_MISMATCH", resolver.resolveResult(source, capabilities,
            checksum, BINDING, new ContentHash("e".repeat(64))));
        CatalogBinding newerGeneration = new CatalogBinding(2, BINDING.catalogChecksum(), BINDING.bindingManifestHash());
        assertCode("FUNCTION.BINDING_MISMATCH", resolver.resolveResult(source, capabilities, checksum, newerGeneration, fingerprint));
        assertSame(accepted, resolver.resolveResult(source, capabilities, checksum, BINDING, fingerprint).function());

        FunctionSourceDocument changed = source(4, TEXT, "changed", BINDING);
        assertCode("FUNCTION.RESOLVER_SOURCE_MISMATCH", resolver.resolveResult(changed, capabilities, checksum, BINDING, fingerprint));
        CompiledFunction replacement = resolver.resolveResult(changed, capabilities, resolver.sourceChecksum(changed), BINDING, fingerprint).function();
        assertEquals("changed", output(replacement).value());
        assertEquals("original", output(accepted).value());
    }

    @Test
    void catalogGenerationAndCompilerReplacementCannotReuseAnEarlierExecutable() {
        FunctionSourceDocument source = source(4, TEXT, "literal", BINDING);
        TypedFunctionResolver resolver = detachedResolver();
        TypedFunctionCapabilitySet first = capabilities(BINDING, node -> frame -> frame.withOutput(OUTPUT, TypedValue.value(TEXT, "first")));
        TypedFunctionCapabilitySet replaced = capabilities(BINDING, node -> frame -> frame.withOutput(OUTPUT, TypedValue.value(TEXT, "replaced")));
        assertEquals(first.fingerprint(), replaced.fingerprint());
        CompiledFunction old = resolver.resolveResult(source, first, source.checksum(), BINDING, first.fingerprint()).function();
        CompiledFunction current = resolver.resolveResult(source, replaced, source.checksum(), BINDING, replaced.fingerprint()).function();
        assertEquals("first", output(old).value());
        assertEquals("replaced", output(current).value());
        TypedFunctionNodeCapability originalBinding = replaced.nodes().getFirst();
        TypedFunctionCapabilitySet changedFingerprint = new TypedFunctionCapabilitySet(BINDING, List.of(new TypedFunctionNodeCapability(
            originalBinding.nodeId(), originalBinding.definition(), originalBinding.definitionVersion(), originalBinding.order(),
            originalBinding.capability(), originalBinding.operation(), new ContentHash("3".repeat(64)),
            originalBinding.handledConnections(), originalBinding.compiler())));
        assertCode("FUNCTION.FINGERPRINT_MISMATCH", resolver.resolveResult(source, changedFingerprint, source.checksum(), BINDING,
            first.fingerprint()));

        CatalogBinding next = new CatalogBinding(2, BINDING.catalogChecksum(), BINDING.bindingManifestHash());
        FunctionSourceDocument nextSource = source(4, TEXT, "next", next);
        TypedFunctionCapabilitySet nextCapabilities = capabilities(next, literal());
        assertCode("FUNCTION.BINDING_MISMATCH", resolver.resolveResult(nextSource, nextCapabilities, null, BINDING, null));
        assertEquals("next", output(resolver.resolveResult(nextSource, nextCapabilities, nextSource.checksum(), next,
            nextCapabilities.fingerprint()).function()).value());
    }

    @Test
    void providerFailuresAndWrongRevisionsCannotFallBackToAResidentBody() {
        FunctionSourceDocument source = source(4, TEXT, "value", BINDING);
        TypedFunctionCapabilitySet capabilities = capabilities(BINDING, literal());
        AtomicReference<FunctionSourceDocument> liveSource = new AtomicReference<>(source);
        AtomicBoolean sourceFailure = new AtomicBoolean();
        AtomicBoolean capabilityFailure = new AtomicBoolean();
        TypedFunctionResolver resolver = new TypedFunctionResolver(new TypedFunctionCompiler(), (function, revision) -> {
            if (sourceFailure.get()) {
                throw new AssertionError("source unavailable");
            }
            return Optional.ofNullable(liveSource.get());
        }, value -> {
            if (capabilityFailure.get()) {
                throw new AssertionError("capabilities unavailable");
            }
            return Optional.of(capabilities);
        });
        FunctionLocator locator = source.signature().function();
        FunctionRevision revision = source.signature().revision();
        CompiledFunction accepted = resolver.resolveResult(locator, revision).function();
        assertTrue(accepted != null);
        sourceFailure.set(true);
        assertCode("FUNCTION.PROVIDER_FAILURE", resolver.resolveResult(locator, revision));
        sourceFailure.set(false);
        capabilityFailure.set(true);
        assertCode("FUNCTION.PROVIDER_FAILURE", resolver.resolveResult(locator, revision));
        capabilityFailure.set(false);
        liveSource.set(source(5, TEXT, "wrong revision", BINDING));
        assertCode("FUNCTION.RESOLVER_SOURCE_MISMATCH", resolver.resolveResult(locator, revision));
        liveSource.set(null);
        assertCode("FUNCTION.NOT_FOUND", resolver.resolveResult(locator, revision));
        liveSource.set(source);
        assertSame(accepted, resolver.resolveResult(locator, revision).function());
    }

    @Test
    void failedCompilerAdmissionCanRecoverWithoutChangingTheCommittedSource() {
        FunctionSourceDocument source = source(4, TEXT, "recovered", BINDING);
        AtomicBoolean unavailable = new AtomicBoolean(true);
        TypedFunctionCapabilitySet capabilities = capabilities(BINDING, node -> {
            if (unavailable.get()) {
                throw new IllegalStateException("temporary compiler failure");
            }
            return frame -> frame.withOutput(OUTPUT, node.values().get(VALUE).value());
        });
        TypedFunctionResolver resolver = detachedResolver();
        assertCode("FUNCTION.COMPILER_STEP_FAILURE", resolver.resolveResult(source, capabilities, source.checksum(), BINDING,
            capabilities.fingerprint()));
        unavailable.set(false);
        assertEquals("recovered", output(resolver.resolveResult(source, capabilities, source.checksum(), BINDING,
            capabilities.fingerprint()).function()).value());
    }

    @Test
    void mutableNumericSubclassesCannotChangeAnAdmittedBodyOrReuseAnOldChecksum() {
        MutableDecimal mutable = new MutableDecimal("1");
        FunctionSourceDocument source = source(4, NUMBER, mutable, BINDING);
        TypedFunctionCapabilitySet capabilities = capabilities(BINDING, literal());
        TypedFunctionResolver resolver = detachedResolver();
        ContentHash admittedChecksum = resolver.sourceChecksum(source);
        CompiledFunction admitted = resolver.resolveResult(source, capabilities, admittedChecksum, BINDING,
            capabilities.fingerprint()).function();
        assertEquals(new BigDecimal("1"), output(admitted).value());

        mutable.replace("2");

        assertEquals(new BigDecimal("1"), output(admitted).value());
        assertCode("FUNCTION.RESOLVER_SOURCE_MISMATCH", resolver.resolveResult(source, capabilities, admittedChecksum, BINDING,
            capabilities.fingerprint()));
        ContentHash changedChecksum = resolver.sourceChecksum(source);
        assertEquals(source.checksum(), changedChecksum);
        assertEquals(new BigDecimal("2"), output(resolver.resolveResult(source, capabilities, changedChecksum, BINDING,
            capabilities.fingerprint()).function()).value());
    }

    @Test
    void mutableLocalDefaultsAndNestedFunctionEvidenceCannotRetainAnOldSourceChecksum() {
        for (int field = 0; field < 3; field++) {
            MutableDecimal mutable = new MutableDecimal("1");
            TypedValue value = TypedValue.value(NUMBER, mutable);
            List<GraphVariable> variables = field == 0 ? List.of(new GraphVariable(UUID.randomUUID(), "value", NUMBER, value)) : List.of();
            ServerResourceLocator child = field == 2
                ? new ServerResourceLocator(RESOURCE.serverId(), RESOURCE.type(), "child", Map.of("evidence", mutable))
                : new ServerResourceLocator(RESOURCE.serverId(), RESOURCE.type(), "child");
            FunctionParameter parameter = new FunctionParameter(OUTPUT, "value", NUMBER, "Provides the nested Function input value.",
                field == 1 ? value : TypedValue.value(NUMBER, BigDecimal.ONE));
            GraphDocument graph = new GraphDocument(GraphDocument.CURRENT_SCHEMA_VERSION, RESOURCE, 4, BINDING,
                Set.of(), List.of(), List.of(), variables, List.of(new FunctionBinding(child, 1, List.of(parameter), List.of())), OpaqueData.empty());
            FunctionSourceDocument source = new FunctionSourceDocument(source(4, TEXT, "body", BINDING).signature(), graph);
            ContentHash original = source.checksum();
            assertEquals(original, source.checksum());

            mutable.replace("2");

            assertNotEquals(original, source.checksum());
            assertEquals(source.checksum(), detachedResolver().sourceChecksum(source));
        }
    }

    @Test
    void mutableMetadataInDefinitionsRequirementsAndCapabilitiesCannotReuseAdmittedHashes() {
        for (int field = 0; field < 3; field++) {
            MutableDecimal mutable = new MutableDecimal("1");
            ContractRef<NodeId> definition = field == 0
                ? ContractRef.of(DEFINITION.owner(), DEFINITION.id(), Map.of("metadata", mutable)) : DEFINITION;
            ContractRef<CapabilityId> requirement = field == 1
                ? ContractRef.of(CAPABILITY.owner(), CAPABILITY.id(), Map.of("metadata", mutable)) : CAPABILITY;
            ContractRef<OperationId> operation = field == 2
                ? ContractRef.of(OPERATION.owner(), OPERATION.id(), Map.of("metadata", mutable)) : OPERATION;
            GraphNode node = new GraphNode(NODE, definition, 1, Map.of(VALUE, new PinValue(VALUE, TypedValue.value(TEXT, "value"))));
            FunctionSourceDocument source = new FunctionSourceDocument(source(4, TEXT, "value", BINDING).signature(),
                new GraphDocument(GraphDocument.CURRENT_SCHEMA_VERSION, RESOURCE, 4, BINDING, Set.of(requirement),
                    List.of(node), List.of(), List.of(), OpaqueData.empty()));
            TypedFunctionCapabilitySet capabilities = new TypedFunctionCapabilitySet(BINDING,
                List.of(new TypedFunctionNodeCapability(NODE, DEFINITION, 1, 0, CAPABILITY, operation,
                    new ContentHash("2".repeat(64)), Set.of(), literal())));
            TypedFunctionResolver resolver = detachedResolver();
            ContentHash sourceChecksum = resolver.sourceChecksum(source);
            ContentHash fingerprint = resolver.capabilityFingerprint(capabilities);
            assertTrue(resolver.resolveResult(source, capabilities, sourceChecksum, BINDING, fingerprint).resolved());

            mutable.replace("2");

            assertCode(field == 2 ? "FUNCTION.FINGERPRINT_MISMATCH" : "FUNCTION.RESOLVER_SOURCE_MISMATCH",
                resolver.resolveResult(source, capabilities, sourceChecksum, BINDING, fingerprint));
            assertEquals(source.checksum(), resolver.sourceChecksum(source));
            assertEquals(capabilities.fingerprint(), resolver.capabilityFingerprint(capabilities));
        }
    }

    @Test
    void capabilityMutationDuringCompilationRejectsAdmissionBeforeAnyBodyCanRun() {
        MutableDecimal mutable = new MutableDecimal("1");
        FunctionSourceDocument source = source(4, TEXT, "value", BINDING);
        ContractRef<OperationId> operation = ContractRef.of(OPERATION.owner(), OPERATION.id(), Map.of("metadata", mutable));
        TypedFunctionCapabilitySet capabilities = new TypedFunctionCapabilitySet(BINDING,
            List.of(new TypedFunctionNodeCapability(NODE, DEFINITION, 1, 0, CAPABILITY, operation,
                new ContentHash("2".repeat(64)), Set.of(), node -> {
                    mutable.replace("2");
                    return frame -> frame.withOutput(OUTPUT, node.values().get(VALUE).value());
                })));
        TypedFunctionResolver resolver = detachedResolver();
        ContentHash fingerprint = resolver.capabilityFingerprint(capabilities);

        assertCode("FUNCTION.FINGERPRINT_MISMATCH", resolver.resolveResult(source, capabilities, source.checksum(), BINDING, fingerprint));
        assertCode("FUNCTION.FINGERPRINT_MISMATCH", resolver.resolveResult(source, capabilities, source.checksum(), BINDING, fingerprint));
        assertEquals("value", output(resolver.resolveResult(source, capabilities, source.checksum(), BINDING,
            resolver.capabilityFingerprint(capabilities)).function()).value());
    }

    @Test
    void boundedResidentsEvictColdRevisionsAndExplicitInvalidationPreservesTheirResults() {
        TypedFunctionResolver resolver = detachedResolver();
        TypedFunctionCapabilitySet capabilities = capabilities(BINDING, literal());
        FunctionSourceDocument first = source(0, TEXT, "first", BINDING);
        CompiledFunction original = resolver.resolveResult(first, capabilities, null, null, null).function();
        for (int revision = 1; revision <= 70; revision++) {
            assertTrue(resolver.resolveResult(source(revision, TEXT, "revision " + revision, BINDING), capabilities, null, null, null).resolved());
        }
        CompiledFunction readmitted = resolver.resolveResult(first, capabilities, first.checksum(), BINDING, capabilities.fingerprint()).function();
        assertNotSame(original, readmitted);
        assertEquals("first", output(original).value());
        assertEquals("first", output(readmitted).value());
        resolver.clearCache();
        CompiledFunction afterClear = resolver.resolveResult(first, capabilities, first.checksum(), BINDING, capabilities.fingerprint()).function();
        assertNotSame(readmitted, afterClear);
        assertEquals("first", output(afterClear).value());
    }

    private static TypedFunctionResolver detachedResolver() {
        return new TypedFunctionResolver(new TypedFunctionCompiler(), (function, revision) -> {
            throw new AssertionError("A resolved admission cannot perform another source lookup");
        }, source -> {
            throw new AssertionError("A resolved admission cannot perform another capability lookup");
        });
    }

    private static FunctionSourceDocument source(long revision, TypeExpr type, Object value, CatalogBinding binding) {
        FunctionSignature signature = new FunctionSignature(new FunctionLocator(RESOURCE), new FunctionRevision(revision),
            List.of(), List.of(new FunctionParameterContract(OUTPUT, type)));
        GraphNode node = new GraphNode(NODE, DEFINITION, 1, Map.of(VALUE, new PinValue(VALUE, TypedValue.value(type, value))));
        return new FunctionSourceDocument(signature, new GraphDocument(RESOURCE, revision, binding, List.of(node), List.of()));
    }

    private static TypedFunctionCapabilitySet capabilities(CatalogBinding binding, TypedFunctionNodeCompiler compiler) {
        return new TypedFunctionCapabilitySet(binding, List.of(new TypedFunctionNodeCapability(NODE, DEFINITION, 1, 0,
            CAPABILITY, OPERATION, new ContentHash("2".repeat(64)), Set.of(), compiler)));
    }

    private static TypedFunctionNodeCompiler literal() {
        return node -> frame -> frame.withOutput(OUTPUT, node.values().get(VALUE).value());
    }

    private static TypedValue output(CompiledFunction function) {
        assertTrue(function != null);
        FunctionExecutionRequest request = new FunctionExecutionRequest(function.signature(), UUID.randomUUID(), new FunctionInputMap(Map.of()));
        FunctionResult result = new TypedFunctionExecutionBoundary((locator, revision) -> Optional.of(function)).execute(request);
        assertTrue(result.successful(), result.diagnostics()::toString);
        return result.outputs().value(OUTPUT);
    }

    private static void assertCode(String code, TypedFunctionResolver.Resolution resolution) {
        assertFalse(resolution.resolved());
        assertEquals(code, resolution.diagnostics().getFirst().code());
    }

    private static final class MutableDecimal extends BigDecimal {
        private BigDecimal current;

        private MutableDecimal(String value) {
            super(value);
            current = new BigDecimal(value);
        }

        private void replace(String value) {
            current = new BigDecimal(value);
        }

        @Override
        public int signum() {
            return current == null ? super.signum() : current.signum();
        }

        @Override
        public BigDecimal stripTrailingZeros() {
            return current == null ? super.stripTrailingZeros() : current.stripTrailingZeros();
        }
    }
}
