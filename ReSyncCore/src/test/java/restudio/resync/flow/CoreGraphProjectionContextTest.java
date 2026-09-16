package restudio.resync.flow;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.catalog.CatalogBindingProof;
import restudio.resync.flow.catalog.CatalogCapabilityDescriptor;
import restudio.resync.flow.catalog.CatalogCompiler;
import restudio.resync.flow.catalog.CatalogContractRange;
import restudio.resync.flow.catalog.CatalogContribution;
import restudio.resync.flow.catalog.CatalogProvenance;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.type.CodecDescriptor;
import restudio.resync.flow.type.TypeDescriptor;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.inspector.InspectorFallback;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CoreGraphProjectionContextTest {
    private static final CatalogVersion CONTRACT = new CatalogVersion(1, 0);
    private static final TypeReference TYPE = TypeReference.of("projection.test", "number");
    private static final UUID SERVER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID NAMESPACE = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Test
    void capturesCatalogIdentityAndResolvesOwnedTypes() {
        CatalogSnapshot catalog = catalog();
        CatalogBinding binding = CatalogBinding.of(catalog.generation(), catalog.contentChecksum(), catalog.bindingManifestHash());
        ServerResourceLocator resource = new ServerResourceLocator(SERVER,
            ContractRef.of(OwnerId.of("resync"), new ResourceTypeId("flow")), "main");

        CoreGraphProjectionContext context = new CoreGraphProjectionContext(resource, catalog, binding, 7, NAMESPACE);

        assertEquals(resource, context.resource());
        assertEquals(catalog, context.catalog());
        assertEquals(binding, context.binding());
        assertEquals(7, context.resourceRevision());
        assertEquals(NAMESPACE, context.identityNamespace());
        assertEquals(catalog.contractVersion(), context.catalogVersion());
        assertEquals(TYPE, context.requireType(TYPE).id());
    }

    @Test
    void rejectsMismatchedBindingAndNegativeRevision() {
        CatalogSnapshot catalog = catalog();
        CatalogBinding binding = CatalogBinding.of(catalog.generation(), catalog.contentChecksum(), catalog.bindingManifestHash());
        ServerResourceLocator resource = new ServerResourceLocator(SERVER,
            ContractRef.of(OwnerId.of("resync"), new ResourceTypeId("flow")), "main");

        assertThrows(IllegalArgumentException.class, () -> new CoreGraphProjectionContext(resource, catalog,
            CatalogBinding.of(catalog.generation() + 1, catalog.contentChecksum(), catalog.bindingManifestHash()), 7, NAMESPACE));
        assertThrows(IllegalArgumentException.class, () -> new CoreGraphProjectionContext(resource, catalog, binding, -1, NAMESPACE));
    }

    private static CatalogSnapshot catalog() {
        CodecDescriptor codec = new CodecDescriptor(TypeReference.of("projection.test", "number-json"), 1, true, true);
        TypeDescriptor descriptor = new TypeDescriptor(TYPE, "Number", TypeExpr.named(TYPE), codec, codec, List.of(), true, true);
        CatalogContractRange range = new CatalogContractRange(CONTRACT, CONTRACT);
        CatalogProvenance provenance = CatalogProvenance.fromText(CatalogProvenance.SourceKind.BUNDLED,
            "classpath:/projection-test.json", "1.0.0", "test", "projection");
        CatalogContribution contribution = CatalogContribution.builder(OwnerId.of("projection.test"), "1.0.0", range, provenance)
            .capabilities(List.of(new CatalogCapabilityDescriptor(CapabilityId.of("generic-editor"), 1, false,
                InspectorFallback.READ_ONLY_FIELD)))
            .types(List.of(descriptor))
            .build();
        return new CatalogCompiler(CONTRACT, CatalogBindingProof.unavailable()).compile(List.of(contribution), 3)
            .snapshot().orElseThrow();
    }
}
