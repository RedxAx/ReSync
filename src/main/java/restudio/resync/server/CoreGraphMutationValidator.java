package restudio.resync.server;

import restudio.resync.flow.CoreGraphStorageBoundary;
import restudio.resync.flow.catalog.CatalogActivationAuthority;
import restudio.resync.flow.catalog.CatalogOwned;
import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.catalog.CatalogRuntimeActivation;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.command.CommandGraphContract;
import restudio.resync.flow.command.CommandGraphMetadata;
import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.diagnostic.DiagnosticPhase;
import restudio.resync.flow.diagnostic.DiagnosticSeverity;
import restudio.resync.flow.function.FunctionDiagnostic;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.function.FunctionSourceMaterializer;
import restudio.resync.flow.graph.FunctionBoundaryPins;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphValidator;
import restudio.resync.flow.graph.ValidationResult;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.runtime.RuntimeBindingManifest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class CoreGraphMutationValidator {
    private static final OwnerId CORE_OWNER = OwnerId.of("restudio.resync");
    private static final OwnerId DIAGNOSTIC_OWNER = OwnerId.of("resync");
    private static final ContractRef<OperationId> DIAGNOSTIC_MESSAGE_KEY =
        ContractRef.of(DIAGNOSTIC_OWNER, OperationId.of("graph-validation"));
    private static final Set<String> GRAPH_TYPES = Set.of("flow", "function", "command");
    private final ServerId serverId;
    private final Supplier<CatalogRuntimeActivation.ActivationRecord> activationSupplier;
    private final Supplier<CatalogActivationAuthority> authoritySupplier;
    private final AdmissionExecutor admissionExecutor;
    private final boolean strict;
    private volatile EvolutionCache evolutionCache;

    public CoreGraphMutationValidator(
        ServerId serverId,
        Supplier<CatalogRuntimeActivation.ActivationRecord> activationSupplier,
        Supplier<CatalogActivationAuthority> authoritySupplier
    ) {
        this(serverId, activationSupplier, authoritySupplier, new AdmissionExecutor() {
            @Override
            public <T> T execute(CatalogRuntimeActivation.ActivationRecord expected, Supplier<T> action) {
                return action.get();
            }
        });
    }

    public CoreGraphMutationValidator(
        ServerId serverId,
        Supplier<CatalogRuntimeActivation.ActivationRecord> activationSupplier,
        Supplier<CatalogActivationAuthority> authoritySupplier,
        AdmissionExecutor admissionExecutor
    ) {
        this.serverId = Objects.requireNonNull(serverId, "Core graph validator server ID is required");
        this.activationSupplier = Objects.requireNonNull(activationSupplier, "Core graph activation supplier is required");
        this.authoritySupplier = Objects.requireNonNull(authoritySupplier, "Core graph authority supplier is required");
        this.admissionExecutor = Objects.requireNonNull(admissionExecutor, "Core graph admission executor is required");
        this.strict = true;
    }

    public CoreGraphMutationValidator(
        ServerId serverId,
        CatalogRuntimeActivation activation,
        CatalogActivationAuthority authority
    ) {
        this(serverId, Objects.requireNonNull(activation, "Core graph activation is required")::active,
            () -> Objects.requireNonNull(authority, "Core graph activation authority is required"));
    }

    static CoreGraphMutationValidator testOnlyUnrestricted() {
        return new CoreGraphMutationValidator();
    }

    public AdmissionResult validate(ServerResourceLocator resource, CoreGraphStorageBoundary.Decoded decoded) {
        return validateInternal(resource, decoded).result();
    }

    public AdmissionProof admit(ServerResourceLocator resource, CoreGraphStorageBoundary.Decoded decoded) {
        ValidationOutcome outcome = validateInternal(resource, decoded);
        if (!outcome.result().valid()) {
            throw new CoreGraphMutationValidationException(outcome.result().diagnostics());
        }
        return outcome.proof();
    }

    public CatalogBinding activeBinding() {
        if (!strict) {
            return CoreCatalogBindingMigration.TARGET;
        }
        CatalogRuntimeActivation.ActivationRecord activation = Objects.requireNonNull(activationSupplier.get(),
            "An active catalog runtime activation is required");
        CatalogSnapshot catalog = activation.catalog();
        RuntimeBindingManifest runtimeManifest = activation.runtimeManifest();
        CatalogActivationAuthority authority = Objects.requireNonNull(authoritySupplier.get(),
            "An active catalog activation authority is required");
        if (!catalog.bindingManifestHash().equals(runtimeManifest.bindingManifestHash())
            || CatalogActivationAuthority.requiresGate2Approval(catalog) && !authority.approved()) {
            throw new IllegalStateException("Active catalog runtime binding is not approved");
        }
        return new CatalogBinding(catalog.generation(), catalog.contentChecksum(),
            runtimeManifest.bindingManifestHash());
    }

    public Optional<CoreCatalogEvolution.Proof> activeEvolution() {
        if (!strict) {
            return Optional.empty();
        }
        EvolutionCache observed = evolutionCache;
        try {
            CatalogRuntimeActivation.ActivationRecord activation = activationSupplier.get();
            CatalogBinding binding = activeBinding();
            if (activation == null || activationSupplier.get() != activation) {
                throw new IllegalStateException("Active catalog changed while obtaining evolution authority");
            }
            CoreCatalogEvolution evolution = CoreCatalogEvolution.select(binding).orElse(null);
            if (evolution == null) {
                discardEvolutionCache(observed);
                return Optional.empty();
            }
            EvolutionCache cached = evolutionCache;
            if (cached == null || cached.activation() != activation) {
                synchronized (this) {
                    cached = evolutionCache;
                    if (cached == null || cached.activation() != activation) {
                        cached = new EvolutionCache(activation, evolution.prove(activation.catalog(), binding));
                        evolutionCache = cached;
                    }
                }
            }
            observed = cached;
            if (activationSupplier.get() != activation || !binding.equals(activeBinding())) {
                throw new IllegalStateException("Active catalog changed during evolution proof");
            }
            return Optional.of(cached.proof());
        } catch (RuntimeException failure) {
            discardEvolutionCache(observed);
            throw failure;
        }
    }

    private synchronized void discardEvolutionCache(EvolutionCache observed) {
        if (evolutionCache == observed) {
            evolutionCache = null;
        }
    }

    private record EvolutionCache(CatalogRuntimeActivation.ActivationRecord activation, CoreCatalogEvolution.Proof proof) {
    }

    public AdmissionProof admitCatalogRebind(ServerResourceLocator resource,
                                             CoreGraphStorageBoundary.Decoded source,
                                             CoreGraphStorageBoundary.Decoded candidate,
                                             CoreCatalogBindingMigration migration) {
        Objects.requireNonNull(migration, "Core catalog binding migration is required");
        if (!migration.target().equals(activeBinding())) {
            throw new IllegalStateException("Core catalog binding migration target is not the active binding");
        }
        if (!migration.eligible(resource, source)) {
            throw new IllegalArgumentException("Core graph does not match the frozen migration source binding");
        }
        UUID mutationId = UUID.fromString(candidate.envelope().assetMutationId());
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        if (!Arrays.equals(boundary.encode(migration.project(source, mutationId)), boundary.encode(candidate))) {
            throw new IllegalArgumentException("Core catalog binding migration candidate changes non-migration fields");
        }
        return admit(resource, candidate);
    }

    public void requireCurrent(AdmissionProof proof, ServerResourceLocator resource,
                               CoreGraphStorageBoundary.Decoded decoded) {
        if (!strict) {
            return;
        }
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (proof == null || resource == null || decoded == null) {
            diagnostics.add(graphDiagnostic("GRAPH.NULL", resource, null,
                Map.of("field", proof == null ? "admissionProof" : resource == null ? "resource" : "payload")));
            throw new CoreGraphMutationValidationException(diagnostics);
        }
        if (!proof.resource().equals(resource)) {
            diagnostics.add(graphDiagnostic("GRAPH.CATALOG_MISMATCH", resource, proof.catalogGeneration(),
                Map.of("reason", "admission proof resource does not match the requested resource",
                    "proofResource", proof.resource().canonicalText(), "requestedResource", resource.canonicalText())));
        }
        ContentHash payloadChecksum = null;
        try {
            payloadChecksum = payloadChecksum(decoded);
        } catch (RuntimeException failure) {
            diagnostics.add(graphDiagnostic("GRAPH.OPAQUE_UNAVAILABLE", resource, proof.catalogGeneration(),
                Map.of("reason", "admission proof payload is not a supported typed document",
                    "failureType", failure.getClass().getName())));
        }
        if (payloadChecksum != null && (!proof.payloadChecksum().equals(payloadChecksum)
            || !proof.payloadKind().equals(decoded.corePayloadKind()))) {
            diagnostics.add(graphDiagnostic("GRAPH.CATALOG_MISMATCH", resource, proof.catalogGeneration(),
                Map.of("reason", "admission proof payload does not match the requested payload",
                    "proofPayloadKind", proof.payloadKind(), "payloadKind", decoded.corePayloadKind(),
                    "proofPayloadChecksum", proof.payloadChecksum().canonicalText(),
                    "payloadChecksum", payloadChecksum.canonicalText())));
        }
        CatalogRuntimeActivation.ActivationRecord currentActivation = currentActivation(resource, diagnostics);
        CatalogActivationAuthority currentAuthority = currentAuthority(resource, diagnostics);
        if (currentActivation != null) {
            if (currentActivation != proof.activation()) {
                diagnostics.add(graphDiagnostic("GRAPH.CATALOG_MISMATCH", resource, currentActivation.catalog().generation(),
                    Map.of("reason", "active catalog runtime activation changed after graph admission",
                        "admittedGeneration", proof.catalogGeneration(),
                        "activeGeneration", currentActivation.catalog().generation())));
            }
            if (currentActivation.catalog().generation() != proof.catalogGeneration()
                || currentActivation.runtime().generation() != proof.runtimeGeneration()
                || !currentActivation.catalog().contentChecksum().equals(proof.catalogChecksum())
                || !currentActivation.catalog().bindingManifestHash().equals(proof.runtimeBindingManifestHash())
                || !currentActivation.runtimeManifest().bindingManifestHash().equals(proof.runtimeBindingManifestHash())) {
                diagnostics.add(graphDiagnostic("GRAPH.CATALOG_MISMATCH", resource, currentActivation.catalog().generation(),
                    Map.of("reason", "active catalog binding identity no longer matches the admission proof",
                        "admittedBinding", proof.binding().canonicalText(),
                        "activeGeneration", currentActivation.catalog().generation(),
                        "admittedRuntimeGeneration", proof.runtimeGeneration(),
                        "activeRuntimeGeneration", currentActivation.runtime().generation(),
                        "activeCatalogChecksum", currentActivation.catalog().contentChecksum().canonicalText(),
                        "activeCatalogBindingManifestHash", currentActivation.catalog().bindingManifestHash().canonicalText(),
                        "activeRuntimeBindingManifestHash", currentActivation.runtimeManifest().bindingManifestHash().canonicalText())));
            }
        }
        if (currentAuthority == null
            || (CatalogActivationAuthority.requiresGate2Approval(proof.activation().catalog())
                && !currentAuthority.approved())) {
            diagnostics.add(graphDiagnostic("GRAPH.CATALOG_REQUIRED", resource, proof.catalogGeneration(),
                Map.of("reason", "active catalog activation authority changed or is not approved")));
        }
        if (!diagnostics.isEmpty()) {
            throw new CoreGraphMutationValidationException(diagnostics);
        }
    }

    public <T> T executeCurrent(AdmissionProof proof, ServerResourceLocator resource,
                               CoreGraphStorageBoundary.Decoded decoded, Supplier<T> action) {
        Objects.requireNonNull(action, "Core graph admitted action is required");
        if (!strict) {
            return action.get();
        }
        if (proof == null || resource == null || decoded == null) {
            requireCurrent(proof, resource, decoded);
            return action.get();
        }
        try {
            return admissionExecutor.execute(proof.activation(), () -> {
                requireCurrent(proof, resource, decoded);
                return action.get();
            });
        } catch (AdmissionFenceException failure) {
            requireCurrent(proof, resource, decoded);
            throw failure;
        }
    }

    private ValidationOutcome validateInternal(ServerResourceLocator resource,
                                               CoreGraphStorageBoundary.Decoded decoded) {
        if (!strict) {
            return new ValidationOutcome(AdmissionResult.accepted(), null);
        }
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (resource == null) {
            diagnostics.add(graphDiagnostic("GRAPH.NULL", null, null, Map.of("field", "resource")));
        }
        if (decoded == null) {
            diagnostics.add(graphDiagnostic("GRAPH.NULL", resource, null, Map.of("field", "payload")));
        }
        if (!diagnostics.isEmpty()) {
            return rejected(diagnostics);
        }
        if (!serverId.equals(resource.serverId())) {
            diagnostics.add(graphDiagnostic("GRAPH.CATALOG_REQUIRED", resource, null,
                Map.of("reason", "resource server does not match the active Core graph authority")));
            return rejected(diagnostics);
        }
        if (!CORE_OWNER.equals(resource.owner())) {
            diagnostics.add(graphDiagnostic("GRAPH.CATALOG_MISMATCH", resource, null,
                Map.of("reason", "resource owner does not match the server-owned Core graph namespace",
                    "expectedOwner", CORE_OWNER.value(), "actualOwner", resource.owner().value())));
            return rejected(diagnostics);
        }

        CatalogRuntimeActivation.ActivationRecord activation;
        try {
            activation = activationSupplier.get();
        } catch (RuntimeException failure) {
            diagnostics.add(graphDiagnostic("GRAPH.CATALOG_REQUIRED", resource, null,
                Map.of("reason", "active catalog runtime activation could not be read",
                    "failureType", failure.getClass().getName())));
            return rejected(diagnostics);
        }
        if (activation == null) {
            diagnostics.add(graphDiagnostic("GRAPH.CATALOG_REQUIRED", resource, null,
                Map.of("reason", "an active catalog runtime activation is required")));
            return rejected(diagnostics);
        }
        CatalogSnapshot catalog = activation.catalog();
        RuntimeBindingManifest runtimeManifest = activation.runtimeManifest();
        CatalogActivationAuthority authority;
        try {
            authority = authoritySupplier.get();
        } catch (RuntimeException failure) {
            authority = null;
            diagnostics.add(graphDiagnostic("GRAPH.CATALOG_REQUIRED", resource, catalog.generation(),
                Map.of("reason", "active catalog activation authority could not be read",
                    "failureType", failure.getClass().getName())));
        }
        if (authority == null || (CatalogActivationAuthority.requiresGate2Approval(catalog) && !authority.approved())) {
            diagnostics.add(graphDiagnostic("GRAPH.CATALOG_REQUIRED", resource, catalog.generation(),
                Map.of("reason", "the active catalog activation authority is not approved")));
        }
        if (!catalog.bindingManifestHash().equals(runtimeManifest.bindingManifestHash())) {
            diagnostics.add(graphDiagnostic("GRAPH.RUNTIME_FINGERPRINT_MISMATCH", resource, catalog.generation(),
                Map.of("catalogBindingManifestHash", catalog.bindingManifestHash().canonicalText(),
                    "runtimeBindingManifestHash", runtimeManifest.bindingManifestHash().canonicalText())));
        }

        Object payload = decoded.payload();
        String resourceType = resource.resourceType().value();
        if (!GRAPH_TYPES.contains(resourceType)) {
            diagnostics.add(graphDiagnostic("GRAPH.DEFINITION_MISSING", resource, catalog.generation(),
                Map.of("reason", "resource type is not a Core graph type", "resourceType", resourceType)));
        } else if (payload instanceof GraphDocument graph) {
            validateGraph(resource, resourceType, graph, catalog, runtimeManifest, diagnostics);
        } else if (payload instanceof FunctionSourceDocument source) {
            validateFunction(resource, resourceType, source, catalog, runtimeManifest, diagnostics);
        } else {
            diagnostics.add(graphDiagnostic("GRAPH.OPAQUE_UNAVAILABLE", resource, catalog.generation(),
                Map.of("reason", "Core graph payload is not a supported typed document")));
        }
        if (!diagnostics.isEmpty()) {
            return rejected(diagnostics);
        }
        CatalogRuntimeActivation.ActivationRecord currentActivation = currentActivation(resource, diagnostics);
        CatalogActivationAuthority currentAuthority = currentAuthority(resource, diagnostics);
        if (currentActivation != activation) {
            diagnostics.add(graphDiagnostic("GRAPH.CATALOG_MISMATCH", resource, catalog.generation(),
                Map.of("reason", "active catalog runtime activation changed during graph admission",
                    "admittedGeneration", catalog.generation(),
                    "activeGeneration", currentActivation == null ? "unavailable" : currentActivation.catalog().generation())));
        }
        if (currentAuthority == null
            || (CatalogActivationAuthority.requiresGate2Approval(catalog) && !currentAuthority.approved())) {
            diagnostics.add(graphDiagnostic("GRAPH.CATALOG_REQUIRED", resource, catalog.generation(),
                Map.of("reason", "active catalog activation authority changed or is not approved")));
        }
        if (!diagnostics.isEmpty()) {
            return rejected(diagnostics);
        }
        CatalogBinding binding = new CatalogBinding(catalog.generation(), catalog.contentChecksum(),
            runtimeManifest.bindingManifestHash());
        AdmissionProof proof = new AdmissionProof(resource, payloadChecksum(decoded), decoded.corePayloadKind(),
            activation, authority, binding, catalog.generation(), activation.runtime().generation(),
            catalog.contentChecksum(),
            runtimeManifest.bindingManifestHash());
        return new ValidationOutcome(AdmissionResult.accepted(), proof);
    }

    private ValidationOutcome rejected(List<Diagnostic> diagnostics) {
        return new ValidationOutcome(AdmissionResult.rejected(diagnostics), null);
    }

    public void requireValid(ServerResourceLocator resource, CoreGraphStorageBoundary.Decoded decoded) {
        AdmissionResult result = validate(resource, decoded);
        if (!result.valid()) {
            throw new CoreGraphMutationValidationException(result.diagnostics());
        }
    }

    private CatalogRuntimeActivation.ActivationRecord currentActivation(ServerResourceLocator resource,
                                                                         List<Diagnostic> diagnostics) {
        try {
            CatalogRuntimeActivation.ActivationRecord activation = activationSupplier.get();
            if (activation == null) {
                diagnostics.add(graphDiagnostic("GRAPH.CATALOG_REQUIRED", resource, null,
                    Map.of("reason", "an active catalog runtime activation is required for the admission proof")));
            }
            return activation;
        } catch (RuntimeException failure) {
            diagnostics.add(graphDiagnostic("GRAPH.CATALOG_REQUIRED", resource, null,
                Map.of("reason", "active catalog runtime activation could not be rechecked",
                    "failureType", failure.getClass().getName())));
            return null;
        }
    }

    private CatalogActivationAuthority currentAuthority(ServerResourceLocator resource,
                                                        List<Diagnostic> diagnostics) {
        try {
            CatalogActivationAuthority authority = authoritySupplier.get();
            if (authority == null) {
                diagnostics.add(graphDiagnostic("GRAPH.CATALOG_REQUIRED", resource, null,
                    Map.of("reason", "an active catalog activation authority is required for the admission proof")));
            }
            return authority;
        } catch (RuntimeException failure) {
            diagnostics.add(graphDiagnostic("GRAPH.CATALOG_REQUIRED", resource, null,
                Map.of("reason", "active catalog activation authority could not be rechecked",
                    "failureType", failure.getClass().getName())));
            return null;
        }
    }

    private ContentHash payloadChecksum(CoreGraphStorageBoundary.Decoded decoded) {
        Object payload = decoded.payload();
        if (payload instanceof GraphDocument graph) {
            return graph.checksum();
        }
        if (payload instanceof FunctionSourceDocument source) {
            return source.checksum();
        }
        throw new IllegalArgumentException("Core graph payload is not a supported typed document");
    }

    private void validateGraph(ServerResourceLocator resource, String resourceType, GraphDocument graph,
                               CatalogSnapshot catalog, RuntimeBindingManifest runtimeManifest,
                               List<Diagnostic> diagnostics) {
        validateBinding(resource, graph, catalog, runtimeManifest, diagnostics);
        if (!resource.equals(graph.resource())) {
            diagnostics.add(graphDiagnostic("GRAPH.CATALOG_MISMATCH", resource, catalog.generation(),
                Map.of("reason", "graph resource locator does not match the requested typed resource",
                    "requestedResource", resource.canonicalText(), "graphResource", graph.resource().canonicalText())));
        }
        if (!resourceType.equals(graph.resource().resourceType().value())) {
            diagnostics.add(graphDiagnostic("GRAPH.CATALOG_MISMATCH", resource, catalog.generation(),
                Map.of("reason", "graph resource type does not match the requested typed resource",
                    "requestedType", resourceType, "graphType", graph.resource().resourceType().value())));
        }
        if ("function".equals(resourceType)) {
            diagnostics.add(graphDiagnostic("GRAPH.DEFINITION_MISSING", resource, catalog.generation(),
                Map.of("reason", "function resources require a FunctionSourceDocument payload")));
        }
        if ("command".equals(resourceType)) {
            long canonicalStarts = graph.nodes().stream()
                .filter(node -> CommandGraphContract.isCanonicalStart(node.definition()))
                .count();
            long legacyStarts = graph.nodes().stream()
                .filter(node -> CommandGraphContract.isLegacyStart(node.definition()))
                .count();
            if (legacyStarts > 0L) {
                diagnostics.add(graphDiagnostic("GRAPH.DEFINITION_MISSING", resource, catalog.generation(),
                    Map.of("reason", "legacy command-start aliases are not accepted by Core command resources",
                        "legacyCommandStartCount", legacyStarts,
                        "canonicalCommandStart", CommandGraphContract.CANONICAL_SERIALIZED_START)));
            }
            if (canonicalStarts != 1L) {
                diagnostics.add(graphDiagnostic("GRAPH.DEFINITION_MISSING", resource, catalog.generation(),
                    Map.of("reason", "a command graph must contain exactly one canonical command-start node",
                        "canonicalCommandStartCount", canonicalStarts,
                        "canonicalCommandStart", CommandGraphContract.CANONICAL_SERIALIZED_START)));
            }
            try {
                CommandGraphMetadata.from(graph);
            } catch (IllegalArgumentException failure) {
                diagnostics.add(graphDiagnostic("GRAPH.OPAQUE_UNAVAILABLE", resource, catalog.generation(),
                    Map.of("reason", "command metadata is invalid", "failure", boundedMessage(failure))));
            }
        }
        appendGraphValidation(graph, catalog, runtimeManifest, diagnostics);
    }

    private void validateFunction(ServerResourceLocator resource, String resourceType, FunctionSourceDocument source,
                                  CatalogSnapshot catalog, RuntimeBindingManifest runtimeManifest,
                                  List<Diagnostic> diagnostics) {
        if (!"function".equals(resourceType)) {
            diagnostics.add(graphDiagnostic("GRAPH.CATALOG_MISMATCH", resource, catalog.generation(),
                Map.of("reason", "FunctionSourceDocument payloads are valid only for function resources",
                    "requestedType", resourceType)));
        }
        GraphDocument graph = source.graph();
        validateBinding(resource, graph, catalog, runtimeManifest, diagnostics);
        if (!resource.equals(graph.resource()) || !resource.equals(source.signature().function().resource())) {
            diagnostics.add(functionDiagnostic(source, "FUNCTION.RESOURCE_MISMATCH",
                "The Function source, signature, and requested resource must use one exact locator."));
        }
        if (graph.revision() != source.signature().revision().value()) {
            diagnostics.add(functionDiagnostic(source, "FUNCTION.REVISION_MISMATCH",
                "The Function graph revision and signature revision must match exactly."));
        }
        Set<ContractRef<?>> functionStarts = functionBoundaryDefinitions(catalog, "function_start");
        Set<ContractRef<?>> functionEnds = functionBoundaryDefinitions(catalog, "function_end");
        long starts = graph.nodes().stream().filter(node -> functionStarts.contains(node.definition())).count();
        long ends = graph.nodes().stream().filter(node -> functionEnds.contains(node.definition())).count();
        if (starts != 1L || ends != 1L) {
            diagnostics.add(functionDiagnostic(source, "FUNCTION.SIGNATURE_MISMATCH",
                "A typed Function source must contain exactly one Function Start and one Function End boundary."));
        }
        FunctionSourceMaterializer.Result sourceValidation;
        try {
            sourceValidation = new FunctionSourceMaterializer().validate(source);
            diagnostics.addAll(sourceValidation.diagnostics().stream().map(FunctionDiagnostic::diagnostic).toList());
        } catch (RuntimeException failure) {
            diagnostics.add(functionDiagnostic(source, "FUNCTION.SOURCE_INVALID",
                "The typed Function source could not be validated: " + safeMessage(failure)));
        }
        appendFunctionGraphValidation(source, catalog, runtimeManifest, diagnostics);
    }

    private void validateBinding(ServerResourceLocator resource, GraphDocument graph, CatalogSnapshot catalog,
                                 RuntimeBindingManifest runtimeManifest, List<Diagnostic> diagnostics) {
        CatalogBinding expected = new CatalogBinding(catalog.generation(), catalog.contentChecksum(),
            runtimeManifest.bindingManifestHash());
        if (!expected.equals(graph.catalogBinding())) {
            diagnostics.add(graphDiagnostic("GRAPH.CATALOG_MISMATCH", resource, catalog.generation(),
                Map.of("expectedBinding", expected.canonicalText(),
                    "actualBinding", graph.catalogBinding().canonicalText())));
        }
    }

    private void appendGraphValidation(GraphDocument graph, CatalogSnapshot catalog,
                                       RuntimeBindingManifest runtimeManifest, List<Diagnostic> diagnostics) {
        try {
            ValidationResult result = new GraphValidator().validate(graph, catalog, runtimeManifest);
            diagnostics.addAll(result.diagnostics());
        } catch (RuntimeException failure) {
            diagnostics.add(graphDiagnostic("GRAPH.OPAQUE_UNAVAILABLE", graph.resource(), catalog.generation(),
                Map.of("reason", "the graph could not be validated against the active catalog and runtime",
                    "failureType", failure.getClass().getName(), "failure", safeMessage(failure))));
        }
    }

    private void appendFunctionGraphValidation(FunctionSourceDocument source, CatalogSnapshot catalog,
                                               RuntimeBindingManifest runtimeManifest, List<Diagnostic> diagnostics) {
        try {
            ValidationResult result = new GraphValidator().validate(source, catalog, runtimeManifest);
            diagnostics.addAll(result.diagnostics());
        } catch (RuntimeException failure) {
            diagnostics.add(graphDiagnostic("GRAPH.OPAQUE_UNAVAILABLE", source.graph().resource(), catalog.generation(),
                Map.of("reason", "the Function graph could not be validated against its signature, active catalog, and runtime",
                    "failureType", failure.getClass().getName(), "failure", safeMessage(failure))));
        }
    }

    private Set<ContractRef<?>> functionBoundaryDefinitions(CatalogSnapshot catalog, String operation) {
        return catalog.definitions().stream()
            .filter(owned -> isFunctionBoundaryDefinition(owned, operation))
            .map(CatalogOwned::key)
            .collect(Collectors.toUnmodifiableSet());
    }

    private boolean isFunctionBoundaryDefinition(CatalogOwned<CatalogNodeDescriptor> owned, String operation) {
        return FunctionBoundaryPins.isBoundary(owned, operation);
    }

    private Diagnostic graphDiagnostic(String code, ServerResourceLocator resource, Long generation,
                                       Map<String, ?> evidence) {
        String identity = code + "\u0000" + (resource == null ? "" : resource.canonicalText()) + "\u0000"
            + (generation == null ? "" : generation) + "\u0000" + evidence;
        Diagnostic.Builder builder = Diagnostic.builder(code, DiagnosticSeverity.ERROR, DiagnosticPhase.SEMANTIC, "graph")
            .messageKey(DIAGNOSTIC_MESSAGE_KEY)
            .correlationId(UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)))
            .evidence(evidence);
        if (resource != null) {
            builder.serverId(resource.serverId()).resource(resource);
        }
        if (generation != null) {
            builder.catalogGeneration(generation);
        }
        return builder.build();
    }

    private Diagnostic functionDiagnostic(FunctionSourceDocument source, String code, String message) {
        FunctionDiagnostic diagnostic = new FunctionDiagnostic(code, FunctionDiagnostic.Severity.ERROR,
            FunctionDiagnostic.Phase.SEMANTIC, "signature-validation", message,
            "Resolve a Function with the requested signature and retry.", source.signature().function(),
            source.signature().revision(), null);
        return diagnostic.diagnostic();
    }

    private String safeMessage(RuntimeException failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
            ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    private String boundedMessage(IllegalArgumentException failure) {
        String message = safeMessage(failure);
        return message.length() <= 256 ? message : message.substring(0, 256);
    }

    public record AdmissionProof(ServerResourceLocator resource, ContentHash payloadChecksum, String payloadKind,
                                 CatalogRuntimeActivation.ActivationRecord activation,
                                 CatalogActivationAuthority authority, CatalogBinding binding,
                                 long catalogGeneration, long runtimeGeneration, ContentHash catalogChecksum,
                                 ContentHash runtimeBindingManifestHash) {
        public AdmissionProof {
            resource = Objects.requireNonNull(resource, "Core graph proof resource is required");
            payloadChecksum = Objects.requireNonNull(payloadChecksum, "Core graph proof payload checksum is required");
            payloadKind = Objects.requireNonNull(payloadKind, "Core graph proof payload kind is required");
            activation = Objects.requireNonNull(activation, "Core graph proof activation is required");
            authority = Objects.requireNonNull(authority, "Core graph proof authority is required");
            binding = Objects.requireNonNull(binding, "Core graph proof catalog binding is required");
            catalogChecksum = Objects.requireNonNull(catalogChecksum, "Core graph proof catalog checksum is required");
            runtimeBindingManifestHash = Objects.requireNonNull(runtimeBindingManifestHash,
                "Core graph proof runtime binding hash is required");
            CatalogBinding expected = new CatalogBinding(catalogGeneration, catalogChecksum,
                runtimeBindingManifestHash);
            if (!binding.equals(expected)) {
                throw new IllegalArgumentException("Core graph proof binding fields do not match");
            }
            if (activation.catalog().generation() != catalogGeneration
                || activation.runtime().generation() != runtimeGeneration
                || !activation.catalog().contentChecksum().equals(catalogChecksum)
                || !activation.catalog().bindingManifestHash().equals(runtimeBindingManifestHash)
                || !activation.runtimeManifest().bindingManifestHash().equals(runtimeBindingManifestHash)) {
                throw new IllegalArgumentException("Core graph proof activation identity does not match");
            }
        }
    }

    @FunctionalInterface
    public interface AdmissionExecutor {
        <T> T execute(CatalogRuntimeActivation.ActivationRecord expected, Supplier<T> action);
    }

    public static final class AdmissionFenceException extends IllegalStateException {
        public AdmissionFenceException() {
            super("Core graph admission fence rejected a changed catalog runtime activation");
        }
    }

    private record ValidationOutcome(AdmissionResult result, AdmissionProof proof) {
    }

    public record AdmissionResult(List<Diagnostic> diagnostics) {
        public AdmissionResult {
            diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "Core graph diagnostics are required"));
        }

        public static AdmissionResult accepted() {
            return new AdmissionResult(List.of());
        }

        public static AdmissionResult rejected(List<Diagnostic> diagnostics) {
            return new AdmissionResult(diagnostics);
        }

        public boolean valid() {
            return diagnostics.isEmpty();
        }
    }

    private CoreGraphMutationValidator() {
        serverId = null;
        activationSupplier = null;
        authoritySupplier = null;
        admissionExecutor = null;
        strict = false;
    }
}
