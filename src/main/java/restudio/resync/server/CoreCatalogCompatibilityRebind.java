package restudio.resync.server;

import restudio.resync.contract.identity.IdentityCodec;
import restudio.resync.flow.CoreGraphStorageBoundary;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

final class CoreCatalogCompatibilityRebind {
    static final String ID = "core-catalog-compatibility-rebind-v1";
    static final String ACTOR = "core-catalog-compatibility-rebind-v1";

    private CoreCatalogCompatibilityRebind() {
    }

    static boolean eligible(CoreGraphStorageBoundary.Decoded source, CatalogBinding target) {
        if (source == null || target == null
            || source.envelope().assetFormatVersion() != CoreGraphStorageBoundary.CURRENT_ASSET_FORMAT_VERSION) {
            return false;
        }
        CatalogBinding current = graph(source).catalogBinding();
        return current.generation() < target.generation()
            && !current.equals(target);
    }

    static CoreGraphStorageBoundary.Decoded project(CoreGraphStorageBoundary.Decoded source, CatalogBinding target,
                                                     UUID mutationId) {
        Objects.requireNonNull(source, "Core catalog compatibility source is required");
        Objects.requireNonNull(target, "Core catalog compatibility target is required");
        Objects.requireNonNull(mutationId, "Core catalog compatibility mutation ID is required");
        if (!eligible(source, target)) {
            throw new IllegalArgumentException("Core catalog binding is not eligible for a compatible forward rebind");
        }
        long revision = Math.addExact(source.envelope().assetRevision(), 1L);
        GraphDocument graph = graph(source);
        GraphDocument rebound = new GraphDocument(graph.schemaVersion(), graph.resource(), revision, target,
            graph.requiredCapabilities(), graph.nodes(), graph.connections(), graph.passthroughs(), graph.variables(), graph.functions(),
            graph.unknown());
        CoreGraphStorageBoundary.AssetMetadata metadata = new CoreGraphStorageBoundary.AssetMetadata(
            source.envelope().resourceType(), revision, mutationId, source.envelope().assetActivationState());
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        if (source.graphDocument() != null) {
            return boundary.decode(boundary.encode(rebound, metadata, rebound.resource()), rebound.resource());
        }
        FunctionSourceDocument current = source.functionSourceDocument();
        FunctionSignature signature = current.signature();
        FunctionSignature reboundSignature = new FunctionSignature(signature.function(), new FunctionRevision(revision),
            signature.inputs(), signature.outputs(), signature.unknown());
        FunctionSourceDocument reboundSource = new FunctionSourceDocument(reboundSignature, rebound, current.unknown());
        return boundary.decode(boundary.encode(reboundSource, metadata, rebound.resource()), rebound.resource());
    }

    static UUID mutationId(ServerResourceLocator resource, CoreGraphStorageBoundary.Decoded source,
                           CatalogBinding target) {
        Objects.requireNonNull(resource, "Core catalog compatibility resource is required");
        Objects.requireNonNull(source, "Core catalog compatibility source is required");
        Objects.requireNonNull(target, "Core catalog compatibility target is required");
        if (!eligible(source, target) || !resource.equals(graph(source).resource())) {
            throw new IllegalArgumentException("Core catalog compatibility source identity is invalid");
        }
        String seed = String.join("\n", resource.canonicalText(), Long.toString(source.envelope().assetRevision()),
            source.envelope().assetMutationId(), source.envelope().assetHash().canonicalText(),
            graph(source).catalogBinding().canonicalText(), target.canonicalText());
        return IdentityCodec.deterministicUuid(ID, seed);
    }

    static boolean exactProjection(ServerResourceLocator resource, CoreGraphStorageBoundary.Decoded source,
                                   CoreGraphStorageBoundary.Decoded requested, CatalogBinding target) {
        if (resource == null || source == null || requested == null || target == null || !eligible(source, target)) {
            return false;
        }
        UUID mutationId;
        try {
            mutationId = UUID.fromString(requested.envelope().assetMutationId());
        } catch (RuntimeException invalid) {
            return false;
        }
        if (!mutationId(resource, source, target).equals(mutationId)) {
            return false;
        }
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        return Arrays.equals(boundary.encode(project(source, target, mutationId)), boundary.encode(requested));
    }

    static GraphDocument graph(CoreGraphStorageBoundary.Decoded decoded) {
        Objects.requireNonNull(decoded, "Core graph payload is required");
        return decoded.graphDocument() != null ? decoded.graphDocument() : decoded.functionSourceDocument().graph();
    }
}
