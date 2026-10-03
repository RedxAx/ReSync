package restudio.resync.flow;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import restudio.flow.data.FlowGraph;
import restudio.resync.flow.function.CompiledFunction;
import restudio.resync.flow.function.CompiledFunctionRunner;
import restudio.resync.flow.function.FunctionDiagnostic;
import restudio.resync.flow.function.FunctionExecutionRequest;
import restudio.resync.flow.function.FunctionInputMap;
import restudio.resync.flow.function.FunctionParameterContract;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionResult;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.TypedFunctionCapabilityProvider;
import restudio.resync.flow.function.TypedFunctionCapabilitySet;
import restudio.resync.flow.function.TypedFunctionCompiler;
import restudio.resync.flow.function.TypedFunctionExecutionBoundary;
import restudio.resync.flow.function.TypedFunctionResolver;
import restudio.resync.flow.function.TypedFunctionSourceProvider;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.runtime.CompiledRuntimeContext;
import restudio.resync.flow.runtime.RuntimeAuthority;
import restudio.resync.flow.runtime.RuntimeExecutionContext;
import restudio.resync.flow.runtime.RuntimePrincipal;
import restudio.resync.flow.runtime.RuntimeCancellationToken;
import restudio.resync.flow.function.FunctionCancellation;
import restudio.resync.flow.type.TypedValue;

import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class CompiledFunctionExecutionBridge {
    private static final String SOURCE_CHECKSUM = "function.sourceChecksum";
    private final TypedFunctionCompiler compiler;
    private final CompiledFunctionRunner runner;
    private final TypedFunctionSourceProvider sourceProvider;
    private final TypedFunctionCapabilityProvider capabilityProvider;
    private final TypedFunctionResolver resolver;
    private final GraphFunctions graphFunctions;

    public CompiledFunctionExecutionBridge(GraphFunctions graphFunctions) {
        this.compiler = null;
        this.runner = null;
        this.sourceProvider = null;
        this.capabilityProvider = null;
        this.resolver = null;
        this.graphFunctions = Objects.requireNonNull(graphFunctions, "Compiled Graph Function Capability Is Required");
    }

    public CompiledFunctionExecutionBridge(
        TypedFunctionSourceProvider sourceProvider,
        TypedFunctionCapabilityProvider capabilityProvider
    ) {
        this(new TypedFunctionCompiler(), new CompiledFunctionRunner(), sourceProvider, capabilityProvider);
    }

    public CompiledFunctionExecutionBridge(
        TypedFunctionCompiler compiler,
        CompiledFunctionRunner runner,
        TypedFunctionSourceProvider sourceProvider,
        TypedFunctionCapabilityProvider capabilityProvider
    ) {
        this.compiler = compiler;
        this.runner = runner;
        this.sourceProvider = sourceProvider;
        this.capabilityProvider = capabilityProvider;
        this.resolver = compiler == null || sourceProvider == null || capabilityProvider == null ? null
            : new TypedFunctionResolver(compiler, sourceProvider, capabilityProvider);
        this.graphFunctions = null;
    }

    public TypedFunctionResolver.Resolution resolve(CompiledFunctionExecutionRequest request) {
        Objects.requireNonNull(request, "Compiled Function Execution Request Is Required");
        FunctionExecutionRequest execution = request.execution();
        if (sourceProvider == null || capabilityProvider == null) {
            return TypedFunctionResolver.Resolution.rejected(diagnostic(request, "FUNCTION.PROVIDER_MISSING",
                "function-resolution", "The typed Function source and capability providers are required.", Map.of(
                    "sourceProviderPresent", sourceProvider != null,
                    "capabilityProviderPresent", capabilityProvider != null)));
        }
        if (request.catalogBinding() == null) {
            return TypedFunctionResolver.Resolution.rejected(diagnostic(request, "FUNCTION.BINDING_MISSING",
                "binding-validation", "The compiled Function catalog binding is required.", Map.of()));
        }
        if (request.capabilityFingerprint() == null) {
            return TypedFunctionResolver.Resolution.rejected(diagnostic(request, "FUNCTION.FINGERPRINT_MISSING",
                "binding-validation", "The compiled Function capability fingerprint is required.", Map.of()));
        }
        Optional<FunctionSourceDocument> source;
        try {
            source = sourceProvider.resolve(execution.function(), execution.revision());
        } catch (RuntimeException failure) {
            return TypedFunctionResolver.Resolution.rejected(diagnostic(request, "FUNCTION.PROVIDER_FAILURE",
                "function-resolution", "The typed Function source provider failed.", Map.of(
                    "provider", "source",
                    "exceptionType", failure.getClass().getName())));
        }
        if (source == null) {
            return TypedFunctionResolver.Resolution.rejected(diagnostic(request, "FUNCTION.PROVIDER_FAILURE",
                "function-resolution", "The typed Function source provider returned no resolution result.", Map.of(
                    "provider", "source")));
        }
        if (source.isEmpty()) {
            return TypedFunctionResolver.Resolution.rejected(diagnostic(request, "FUNCTION.NOT_FOUND",
                "function-resolution", "The requested typed Function source revision is not available.", Map.of()));
        }
        FunctionSourceDocument sourceDocument = source.get();
        if (!sourceDocument.signature().function().equals(execution.function())
            || !sourceDocument.signature().revision().equals(execution.revision())) {
            return TypedFunctionResolver.Resolution.rejected(diagnostic(request, "FUNCTION.RESOLVER_SOURCE_MISMATCH",
                "function-resolution", "The typed Function source provider returned a different locator or revision.", Map.of(
                    "expectedFunction", execution.function().canonicalText(),
                    "expectedRevision", execution.revision().value(),
                    "actualFunction", sourceDocument.signature().function().canonicalText(),
                    "actualRevision", sourceDocument.signature().revision().value())));
        }
        Optional<TypedFunctionCapabilitySet> capabilities;
        try {
            capabilities = capabilityProvider.resolve(sourceDocument);
        } catch (RuntimeException failure) {
            return TypedFunctionResolver.Resolution.rejected(diagnostic(request, "FUNCTION.PROVIDER_FAILURE",
                "function-resolution", "The typed Function capability provider failed.", Map.of(
                    "provider", "capability",
                    "exceptionType", failure.getClass().getName())));
        }
        if (capabilities == null) {
            return TypedFunctionResolver.Resolution.rejected(diagnostic(request, "FUNCTION.PROVIDER_FAILURE",
                "function-resolution", "The typed Function capability provider returned no resolution result.", Map.of(
                    "provider", "capability")));
        }
        if (capabilities.isEmpty()) {
            return TypedFunctionResolver.Resolution.rejected(diagnostic(request, "FUNCTION.CAPABILITY_INPUT_MISSING",
                "function-resolution", "The requested typed Function has no capability input set.", Map.of()));
        }
        TypedFunctionCapabilitySet capabilitySet = capabilities.get();
        CatalogBinding sourceBinding = sourceDocument.graph().catalogBinding();
        if (!request.catalogBinding().equals(sourceBinding)
            || !request.catalogBinding().equals(capabilitySet.catalogBinding())
            || !sourceBinding.equals(capabilitySet.catalogBinding())) {
            return TypedFunctionResolver.Resolution.rejected(diagnostic(request, "FUNCTION.BINDING_MISMATCH",
                "binding-validation", "The typed Function source and capabilities do not match the requested catalog binding.", Map.of(
                    "requestedBinding", request.catalogBinding().canonicalText(),
                    "sourceBinding", sourceBinding.canonicalText(),
                    "capabilityBinding", capabilitySet.catalogBinding().canonicalText())));
        }
        ContentHash fingerprint;
        try {
            fingerprint = resolver == null ? capabilitySet.fingerprint() : resolver.capabilityFingerprint(capabilitySet);
        } catch (RuntimeException failure) {
            return TypedFunctionResolver.Resolution.rejected(diagnostic(request, "FUNCTION.FINGERPRINT_MISSING",
                "binding-validation", "The typed Function capability fingerprint is unavailable.", Map.of(
                    "exceptionType", failure.getClass().getName())));
        }
        if (fingerprint == null) {
            return TypedFunctionResolver.Resolution.rejected(diagnostic(request, "FUNCTION.FINGERPRINT_MISSING",
                "binding-validation", "The typed Function capability fingerprint is unavailable.", Map.of()));
        }
        if (!request.capabilityFingerprint().equals(fingerprint)) {
            return TypedFunctionResolver.Resolution.rejected(diagnostic(request, "FUNCTION.FINGERPRINT_MISMATCH",
                "binding-validation", "The typed Function capability fingerprint does not match the compiled request.", Map.of(
                    "requestedFingerprint", request.capabilityFingerprint().canonicalText(),
                    "actualFingerprint", fingerprint.canonicalText())));
        }
        if (compiler == null) {
            return TypedFunctionResolver.Resolution.rejected(diagnostic(request, "FUNCTION.COMPILER_MISSING",
                "function-compilation", "The typed Function compiler is unavailable.", Map.of()));
        }
        if (runner == null) {
            return TypedFunctionResolver.Resolution.rejected(diagnostic(request, "FUNCTION.RUNNER_MISSING",
                "function-execution", "The typed Function runner is unavailable.", Map.of()));
        }
        try {
            Object checksum = execution.attributes().get(SOURCE_CHECKSUM);
            ContentHash expectedSource = checksum == null ? null : ContentHash.of((String) checksum);
            return resolver.resolveResult(sourceDocument, capabilitySet, expectedSource,
                request.catalogBinding(), request.capabilityFingerprint());
        } catch (RuntimeException failure) {
            return TypedFunctionResolver.Resolution.rejected(diagnostic(request, "FUNCTION.RESOLVER_FAILURE",
                "function-resolution", "The typed Function resolver failed.", Map.of(
                    "exceptionType", failure.getClass().getName())));
        }
    }

    public FunctionResult execute(CompiledFunctionExecutionRequest request) {
        Objects.requireNonNull(request, "Compiled Function Execution Request Is Required");
        TypedFunctionResolver.Resolution resolution = resolve(request);
        if (!resolution.resolved()) {
            return FunctionResult.failure(request.execution().signature(), resolution.diagnostics(), 0);
        }
        CompiledFunction function = resolution.function();
        return new TypedFunctionExecutionBoundary((locator, revision) ->
            locator.equals(request.function()) && revision.equals(request.revision())
                ? Optional.of(function) : Optional.empty(), runner).execute(request.execution());
    }

    public CompletionStage<FunctionResult> executeAsync(CompiledFunctionExecutionRequest request,
                                                       RuntimeCancellationToken parentCancellation) {
        if (graphFunctions != null) {
            return graphFunctions.execute(request, parentCancellation);
        }
        try {
            if (parentCancellation != null) {
                parentCancellation.throwIfCancelled();
            }
            return CompletableFuture.completedFuture(execute(request));
        } catch (RuntimeException | Error failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    public boolean hasTypedFunctionProviders() {
        return graphFunctions != null || sourceProvider != null && capabilityProvider != null;
    }

    public CompiledFunctionExecutionRequest bindSource(CompiledFunctionExecutionRequest request, FunctionSourceDocument source) {
        Objects.requireNonNull(request, "Function Request Is Required");
        Objects.requireNonNull(source, "Authoritative Function Source Is Required");
        if (!source.signature().equals(request.execution().signature()) || resolver == null && graphFunctions == null) {
            throw new IllegalArgumentException("The Authoritative Function Signature Or Provider Does Not Match The Request");
        }
        FunctionExecutionRequest execution = request.execution().withAttribute(SOURCE_CHECKSUM,
            (resolver == null ? source.checksum() : resolver.sourceChecksum(source)).canonicalText());
        return new CompiledFunctionExecutionRequest(execution, request.catalogBinding(), request.capabilityFingerprint(),
            request.authority(), request.principal(), request.runtimeContext(), request.sessionReference(), request.requestedDeadlineMillis(),
            request.creatorPrincipal(), request.creatorSessionReference());
    }

    public CompiledFunctionExecutionRequest requestForSource(
        FunctionSourceDocument expected,
        Player player,
        Event event,
        Map<String, Object> inputs,
        Map<String, Object> eventVariables,
        ServerId serverId,
        RuntimeAuthority authority,
        RuntimePrincipal principal,
        CorrelationId invocationId,
        CompiledRuntimeContext runtimeContext,
        long requestedDeadlineMillis,
        String creatorPrincipal,
        String creatorSessionReference
    ) {
        Objects.requireNonNull(expected, "Authoritative Function Source Is Required");
        Objects.requireNonNull(serverId, "Function Server Identity Is Required");
        FunctionSignature signature = expected.signature();
        if (!serverId.equals(signature.function().serverId()) || signature.revision().value() < 1) {
            throw new IllegalArgumentException("Function Source Must Have The Requested Server And A Positive Revision");
        }
        Optional<FunctionAdmission> admitted = graphFunctions == null ? Optional.empty()
            : graphFunctions.resolve(signature.function(), signature.revision());
        Optional<FunctionSourceDocument> resolved = graphFunctions == null
            ? sourceProvider == null ? Optional.empty() : sourceProvider.resolve(signature.function(), signature.revision())
            : admitted == null ? Optional.empty() : admitted.map(FunctionAdmission::source);
        if (resolved == null || resolved.isEmpty()) {
            throw new IllegalStateException("The typed Function source is unavailable");
        }
        FunctionSourceDocument source = resolved.get();
        ContentHash checksum = graphFunctions != null ? admitted.orElseThrow().sourceChecksum()
            : resolver == null ? source.checksum() : resolver.sourceChecksum(source);
        ContentHash expectedChecksum = resolver == null ? expected.checksum() : resolver.sourceChecksum(expected);
        if (!signature.equals(source.signature()) || !expected.graph().catalogBinding().equals(source.graph().catalogBinding())
            || !expectedChecksum.equals(checksum)) {
            throw new IllegalArgumentException("The Requested Function Source Signature, Checksum Or Catalog Binding Is Not Current");
        }
        Optional<TypedFunctionCapabilitySet> capabilities = graphFunctions == null && capabilityProvider != null
            ? capabilityProvider.resolve(source) : Optional.empty();
        if (graphFunctions == null && (capabilities == null || capabilities.isEmpty())) {
            throw new IllegalStateException("The typed Function capability set is unavailable");
        }
        ContentHash fingerprint = graphFunctions != null ? admitted.orElseThrow().capabilityFingerprint()
            : resolver == null ? capabilities.orElseThrow().fingerprint() : resolver.capabilityFingerprint(capabilities.orElseThrow());
        return request(source, sourceInputs(signature, inputs).require(signature), checksum, fingerprint, player, event, eventVariables,
            serverId, authority, principal, invocationId, runtimeContext, requestedDeadlineMillis, creatorPrincipal, creatorSessionReference);
    }

    private static FunctionInputMap sourceInputs(FunctionSignature signature, Map<String, Object> inputs) {
        Map<FunctionParameterId, FunctionParameterContract> parameters = new LinkedHashMap<>();
        Map<String, FunctionParameterId> names = new LinkedHashMap<>();
        signature.inputs().forEach(parameter -> {
            parameters.put(parameter.id(), parameter);
            Object declaredName = parameter.unknown().get("name");
            if (declaredName instanceof String name && !name.isBlank() && names.putIfAbsent(name, parameter.id()) != null) {
                throw new IllegalArgumentException("Function Input Display Name Is Ambiguous: " + name);
            }
        });
        Map<FunctionParameterId, TypedValue> values = new LinkedHashMap<>();
        if (inputs != null) {
            inputs.forEach((key, raw) -> {
                if (key == null || key.isBlank()) {
                    throw new IllegalArgumentException("Function Input Identity Is Required");
                }
                FunctionParameterId id = parameterId(key);
                if (id == null) {
                    id = names.get(key);
                }
                FunctionParameterContract parameter = parameters.get(id);
                if (parameter == null || values.containsKey(id)) {
                    throw new IllegalArgumentException("Function Input Is Undeclared Or Supplied More Than Once: " + key);
                }
                values.put(id, CompiledRuntimeValueCodec.encode(signature.function().serverId(), parameter.type(), raw));
            });
        }
        return new FunctionInputMap(values);
    }

    public CompiledFunctionExecutionRequest requestForLegacyGraph(
        FlowGraph graph,
        Map<String, Object> inputs,
        Map<String, Object> eventVariables,
        ServerId serverId,
        RuntimeAuthority authority,
        RuntimePrincipal principal,
        CorrelationId invocationId,
        CompiledRuntimeContext runtimeContext,
        long requestedDeadlineMillis
    ) {
        return requestForLegacyGraph(graph, inputs, eventVariables, serverId, authority, principal, invocationId,
            runtimeContext, requestedDeadlineMillis, null, null);
    }

    public CompiledFunctionExecutionRequest requestForLegacyGraph(
        FlowGraph graph,
        Map<String, Object> inputs,
        Map<String, Object> eventVariables,
        ServerId serverId,
        RuntimeAuthority authority,
        RuntimePrincipal principal,
        CorrelationId invocationId,
        CompiledRuntimeContext runtimeContext,
        long requestedDeadlineMillis,
        String creatorPrincipal,
        String creatorSessionReference
    ) {
        return requestForLegacyGraph(graph, null, null, inputs, eventVariables, serverId, authority, principal, invocationId,
            runtimeContext, requestedDeadlineMillis, creatorPrincipal, creatorSessionReference);
    }

    public CompiledFunctionExecutionRequest requestForLegacyGraph(
        FlowGraph graph,
        Player player,
        Event event,
        Map<String, Object> inputs,
        Map<String, Object> eventVariables,
        ServerId serverId,
        RuntimeAuthority authority,
        RuntimePrincipal principal,
        CorrelationId invocationId,
        CompiledRuntimeContext runtimeContext,
        long requestedDeadlineMillis,
        String creatorPrincipal,
        String creatorSessionReference
    ) {
        Objects.requireNonNull(graph, "Function Graph Is Required");
        Objects.requireNonNull(serverId, "Function Server Identity Is Required");
        Objects.requireNonNull(invocationId, "Function Invocation ID Is Required");
        if (!graph.isFunction() || !"function".equals(graph.getResourceType()) || graph.getId() == null || graph.getId().isBlank()
            || graph.getResourceRevision() < 1) {
            throw new IllegalArgumentException("Only persisted typed Function resources can cross the compiled Function boundary");
        }
        FunctionLocator locator = new FunctionLocator(serverId,
            ContractRef.of(new OwnerId("restudio.resync"), ResourceTypeId.of("function")), graph.getId());
        FunctionRevision revision = FunctionRevision.of(graph.getResourceRevision());
        Optional<FunctionAdmission> graphAdmission = graphFunctions == null ? Optional.empty() : graphFunctions.resolve(locator, revision);
        Optional<FunctionSourceDocument> source = graphFunctions == null
            ? sourceProvider == null ? Optional.empty() : sourceProvider.resolve(locator, revision)
            : graphAdmission.map(FunctionAdmission::source);
        if (source == null || source.isEmpty()) {
            throw new IllegalStateException("The typed Function source is unavailable");
        }
        Optional<TypedFunctionCapabilitySet> capabilities = capabilityProvider == null
            ? Optional.empty() : capabilityProvider.resolve(source.get());
        if (graphFunctions == null && (capabilities == null || capabilities.isEmpty())) {
            throw new IllegalStateException("The typed Function capability set is unavailable");
        }
        FunctionSourceDocument sourceDocument = source.get();
        FunctionSignature signature = sourceDocument.signature();
        Map<FunctionParameterId, FlowGraph.FunctionParameter> graphInputs = parameterMap(
            graph.getFunctionInputs(), "input");
        Map<FunctionParameterId, FlowGraph.FunctionParameter> graphOutputs = parameterMap(
            graph.getFunctionOutputs(), "output");
        requireExactParameterIds(graphInputs, signature.inputs(), "input");
        requireExactParameterIds(graphOutputs, signature.outputs(), "output");
        Map<FunctionParameterId, Object> supplied = boundaryInputs(graphInputs, inputs);
        Map<FunctionParameterId, TypedValue> values = new LinkedHashMap<>();
        for (FunctionParameterContract parameter : signature.inputs()) {
            if (!supplied.containsKey(parameter.id())) {
                continue;
            }
            Object raw = supplied.get(parameter.id());
            values.put(parameter.id(), raw instanceof TypedValue typed ? typed : raw == null
                ? TypedValue.nullValue(parameter.type()) : TypedValue.value(parameter.type(), raw));
        }
        ContentHash checksum = graphFunctions != null ? graphAdmission.orElseThrow().sourceChecksum()
            : resolver == null ? sourceDocument.checksum() : resolver.sourceChecksum(sourceDocument);
        ContentHash fingerprint = graphFunctions != null ? graphAdmission.orElseThrow().capabilityFingerprint()
            : resolver == null ? capabilities.get().fingerprint() : resolver.capabilityFingerprint(capabilities.get());
        return request(sourceDocument, new FunctionInputMap(values), checksum, fingerprint, player, event, eventVariables,
            serverId, authority, principal, invocationId, runtimeContext, requestedDeadlineMillis, creatorPrincipal, creatorSessionReference);
    }

    private static CompiledFunctionExecutionRequest request(FunctionSourceDocument source, FunctionInputMap inputs,
        ContentHash checksum, ContentHash fingerprint, Player player, Event event, Map<String, Object> eventVariables,
        ServerId serverId, RuntimeAuthority authority, RuntimePrincipal principal, CorrelationId invocationId,
        CompiledRuntimeContext runtimeContext, long requestedDeadlineMillis, String creatorPrincipal, String creatorSessionReference) {
        Objects.requireNonNull(invocationId, "Function Invocation ID Is Required");
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(SOURCE_CHECKSUM, checksum.canonicalText());
        if (eventVariables != null && !eventVariables.isEmpty()) {
            CompiledRuntimeContextAdapter.Result adapted = CompiledRuntimeContextAdapter.adapt(
                serverId, player, event, eventVariables);
            if (!adapted.accepted()) {
                throw new IllegalArgumentException("Function Event Variables Are Unsupported: " + adapted.failure());
            }
            attributes.put("eventVariables", adapted.context().variables().entrySet().stream()
                .collect(LinkedHashMap::new, (result, entry) -> result.put(entry.getKey(), entry.getValue().canonicalValue()), Map::putAll));
        }
        if (runtimeContext != null && !runtimeContext.isEmpty()) {
            attributes.put("runtimeContext", runtimeContext.canonicalValue());
        }
        FunctionCancellation cancellation = requestedDeadlineMillis == RuntimeExecutionContext.NO_DEADLINE
            ? FunctionCancellation.none()
            : new FunctionCancellation(FunctionCancellation.State.NONE, null, requestedDeadlineMillis);
        FunctionExecutionRequest execution = new FunctionExecutionRequest(source.signature(), invocationId.value(),
            inputs, cancellation, attributes);
        String sessionReference = eventVariables != null && eventVariables.get("runtime.sessionId") instanceof String value
            ? value : null;
        return new CompiledFunctionExecutionRequest(execution, source.graph().catalogBinding(), fingerprint,
            authority, principal, runtimeContext, sessionReference, requestedDeadlineMillis, creatorPrincipal,
            creatorSessionReference);
    }

    public Map<String, Object> outputsForSource(FunctionSourceDocument source, FunctionResult result) {
        Objects.requireNonNull(source, "Function Source Is Required");
        Objects.requireNonNull(result, "Function Result Is Required");
        FunctionSignature signature = source.signature();
        if (!signature.equals(result.signature())) {
            throw new IllegalArgumentException("Function Result Signature Does Not Match The Admitted Source");
        }
        result.outputs().require(signature);
        Map<FunctionParameterId, FunctionParameterContract> parameters = new LinkedHashMap<>();
        signature.outputs().forEach(parameter -> parameters.put(parameter.id(), parameter));
        Map<String, Object> outputs = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        result.outputs().values().forEach((id, value) -> {
            FunctionParameterContract parameter = parameters.get(id);
            String key = parameter.unknown().get("name") instanceof String name && !name.isBlank()
                ? name : id.canonicalText();
            if (!names.add(key)) {
                throw new IllegalArgumentException("Function Output Display Name Is Ambiguous: " + key);
            }
            outputs.put(key, value.state() == TypedValue.State.LOCATOR ? value.locator() : value.value());
        });
        return Collections.unmodifiableMap(outputs);
    }

    public Map<String, Object> outputsForLegacyGraph(FlowGraph graph, FunctionResult result) {
        Objects.requireNonNull(graph, "Function Graph Is Required");
        Objects.requireNonNull(result, "Function Result Is Required");
        Map<FunctionParameterId, FlowGraph.FunctionParameter> graphOutputs = parameterMap(
            graph.getFunctionOutputs(), "output");
        requireExactParameterIds(graphOutputs, result.signature().outputs(), "output");
        Map<String, Object> outputs = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        result.outputs().values().forEach((id, value) -> {
            FlowGraph.FunctionParameter parameter = graphOutputs.get(id);
            if (parameter == null) {
                throw new IllegalArgumentException("Function output is not declared by the Flow graph: " + id);
            }
            String name = parameter.getName();
            String key = name == null || name.isBlank() ? id.canonicalText() : name;
            if (!names.add(key)) {
                throw new IllegalArgumentException("Function output display name is ambiguous: " + key);
            }
            outputs.put(key, value.state() == TypedValue.State.LOCATOR ? value.locator() : value.value());
        });
        return Collections.unmodifiableMap(outputs);
    }

    public interface GraphFunctions {
        Optional<FunctionAdmission> resolve(FunctionLocator function, FunctionRevision revision);

        CompletionStage<FunctionResult> execute(CompiledFunctionExecutionRequest request, RuntimeCancellationToken parentCancellation);
    }

    public record FunctionAdmission(FunctionSourceDocument source, ContentHash sourceChecksum, ContentHash capabilityFingerprint) {
        public FunctionAdmission {
            Objects.requireNonNull(source, "Admitted Function Source Is Required");
            Objects.requireNonNull(sourceChecksum, "Admitted Function Checksum Is Required");
            Objects.requireNonNull(capabilityFingerprint, "Admitted Function Capability Fingerprint Is Required");
        }
    }

    private static Map<FunctionParameterId, FlowGraph.FunctionParameter> parameterMap(
        List<FlowGraph.FunctionParameter> parameters, String direction
    ) {
        if (parameters == null) {
            throw new IllegalArgumentException("Function " + direction + " parameters are required");
        }
        LinkedHashMap<FunctionParameterId, FlowGraph.FunctionParameter> result = new LinkedHashMap<>();
        for (FlowGraph.FunctionParameter parameter : parameters) {
            if (parameter == null) {
                throw new IllegalArgumentException("Function " + direction + " parameters cannot contain null entries");
            }
            FunctionParameterId id = parameter.getParameterId();
            if (id == null) {
                throw new IllegalArgumentException("Function " + direction + " parameter ID is required");
            }
            if (result.putIfAbsent(id, parameter) != null) {
                throw new IllegalArgumentException("Duplicate Function " + direction + " parameter ID: " + id);
            }
        }
        return Map.copyOf(result);
    }

    private static void requireExactParameterIds(
        Map<FunctionParameterId, FlowGraph.FunctionParameter> graphParameters,
        List<FunctionParameterContract> signatureParameters,
        String direction
    ) {
        Set<FunctionParameterId> signatureIds = new LinkedHashSet<>();
        for (FunctionParameterContract parameter : signatureParameters) {
            signatureIds.add(parameter.id());
        }
        if (!graphParameters.keySet().equals(signatureIds)) {
            Set<FunctionParameterId> missing = new LinkedHashSet<>(signatureIds);
            missing.removeAll(graphParameters.keySet());
            Set<FunctionParameterId> unknown = new LinkedHashSet<>(graphParameters.keySet());
            unknown.removeAll(signatureIds);
            throw new IllegalArgumentException("Function " + direction + " parameter IDs do not match the typed signature: missing="
                + missing + ", unknown=" + unknown);
        }
    }

    private static Map<FunctionParameterId, Object> boundaryInputs(
        Map<FunctionParameterId, FlowGraph.FunctionParameter> graphInputs,
        Map<String, Object> supplied
    ) {
        if (supplied == null || supplied.isEmpty()) {
            return Map.of();
        }
        Map<String, FunctionParameterId> names = new HashMap<>();
        graphInputs.forEach((id, parameter) -> {
            String name = parameter.getName();
            if (name != null && !name.isBlank() && names.putIfAbsent(name, id) != null) {
                throw new IllegalArgumentException("Function input display name is ambiguous: " + name);
            }
        });
        LinkedHashMap<FunctionParameterId, Object> result = new LinkedHashMap<>();
        supplied.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("Function input identity is required");
            }
            FunctionParameterId id = parameterId(key);
            if (id == null) {
                id = names.get(key);
            }
            if (id == null || !graphInputs.containsKey(id)) {
                throw new IllegalArgumentException("Function input is not declared by the Flow graph: " + key);
            }
            if (result.containsKey(id)) {
                throw new IllegalArgumentException("Function input identity is supplied more than once: " + id);
            }
            result.put(id, value);
        });
        return Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    private static FunctionParameterId parameterId(String value) {
        String normalized = value.trim();
        String prefix = "function-input-";
        if (normalized.startsWith(prefix)) {
            normalized = normalized.substring(prefix.length());
        }
        try {
            return FunctionParameterId.parseCanonicalText(normalized);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static FunctionDiagnostic diagnostic(CompiledFunctionExecutionRequest request, String code, String stage,
                                                 String message, Map<String, Object> evidence) {
        return new FunctionDiagnostic(code, FunctionDiagnostic.Severity.ERROR, FunctionDiagnostic.Phase.CAPABILITY,
            stage, message,
            "Provide the exact typed Function source, capability binding, and compiled fingerprint before execution.",
            request.function(), request.revision(), null, request.execution().invocationId(), null, Map.of(), evidence, true);
    }
}
