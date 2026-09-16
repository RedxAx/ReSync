package restudio.resync.server;

import org.junit.jupiter.api.Test;
import restudio.resync.api.OptionCatalogCapture;
import restudio.resync.api.OptionCatalogItem;
import restudio.resync.api.OptionCatalogProvider;
import restudio.resync.api.OptionCatalogQuery;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.core.ConnectionInfo;
import restudio.resync.core.ConnectionState;
import restudio.resync.core.Session;
import restudio.resync.flow.cache.CatalogAuthoringPublication;
import restudio.resync.flow.cache.CatalogCacheOpaque;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.catalog.CatalogBindingProof;
import restudio.resync.flow.catalog.CatalogCapabilityDescriptor;
import restudio.resync.flow.catalog.CatalogCategoryDescriptor;
import restudio.resync.flow.catalog.CatalogCompiler;
import restudio.resync.flow.catalog.CatalogContractRange;
import restudio.resync.flow.catalog.CatalogContribution;
import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.catalog.CatalogProvenance;
import restudio.resync.flow.catalog.CatalogRuntimeActivation;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.inspector.InspectorCondition;
import restudio.resync.flow.inspector.InspectorFallback;
import restudio.resync.flow.inspector.InspectorOptionSource;
import restudio.resync.flow.inspector.OptionQuerySchemaV1;
import restudio.resync.flow.protocol.OptionInvalidation;
import restudio.resync.flow.protocol.OptionPage;
import restudio.resync.flow.protocol.OptionQuery;
import restudio.resync.flow.protocol.ProtocolBody;
import restudio.resync.flow.protocol.ProtocolEnvelope;
import restudio.resync.flow.protocol.ProtocolRejectionCode;
import restudio.resync.flow.runtime.RuntimeFailureContract;
import restudio.resync.flow.runtime.RuntimeOperationDescriptor;
import restudio.resync.flow.runtime.RuntimeRegistrySnapshot;
import restudio.resync.flow.runtime.RuntimeSemantics;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;
import restudio.resync.modules.flow.FlowResourceRegistry;
import restudio.resync.protocol.FrameSender;
import restudio.resync.security.ClientIdentity;
import restudio.resync.contract.cache.CatalogProjectionVersion;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderOptionQueryServiceTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final OwnerId OWNER = OwnerId.of("restudio.resync");
    private static final CatalogVersion VERSION = new CatalogVersion(1, 3);
    private static final ContractRef<InspectorFieldId> SOURCE_REF = ContractRef.of(OWNER,
        InspectorFieldId.of("fixture-options"));
    private static final ContractRef<CapabilityId> QUERY = ContractRef.of(OWNER, CapabilityId.of("fixture-options"));
    private static final ContractRef<CapabilityId> EDITOR = ContractRef.of(OWNER, CapabilityId.of("fixture-editor"));
    private static final ContractRef<OperationId> RESOLVE = ContractRef.of(OWNER, OperationId.of("fixture-resolve"));
    private static final TypeExpr STRING = TypeExpr.named(TypeReference.of("builtin", "string"));
    private static final String PROVIDER_SOURCE = "server:test:values";

    @Test
    void callerExecutorUsesExactlyOneCoherentCapture() {
        AtomicInteger captures = new AtomicInteger();
        OptionCatalogProvider provider = provider(OptionCatalogProvider.CaptureAffinity.CALLER, captures);
        OptionCatalogCapture capture = OptionCatalogCaptureExecutor.callerOnly().capture(provider,
            new OptionCatalogQuery(provider.sourceId(), Map.of()));

        assertEquals(1, captures.get());
        assertEquals(List.of("value"), capture.values());
    }

    @Test
    void callerExecutorFailsClosedForAnotherAffinity() {
        AtomicInteger captures = new AtomicInteger();
        OptionCatalogProvider provider = provider(OptionCatalogProvider.CaptureAffinity.SERVER_MAIN, captures);

        assertThrows(UnsupportedOperationException.class, () -> OptionCatalogCaptureExecutor.callerOnly()
            .capture(provider, new OptionCatalogQuery(provider.sourceId(), Map.of())));
        assertEquals(0, captures.get());
    }

    @Test
    void requiresTheExactAcknowledgedPublicationTupleBeforeCapture() {
        Fixture fixture = fixture(OptionQuerySchemaV1.empty(), Set.of());
        OptionQuery query = query(SOURCE_REF, Map.of(), null, 10, null, 0L, "initial");

        assertRejected(ProtocolRejectionCode.RESOURCE_REVISION_CONFLICT,
            () -> fixture.service.query(fixture.session, envelope(query, null, null, null), query));
        assertRejected(ProtocolRejectionCode.RESOURCE_REVISION_CONFLICT,
            () -> fixture.service.query(fixture.session, envelope(query, new CatalogVersion(1, 4),
                fixture.binding.catalogChecksum(), fixture.binding.bindingManifestHash()), query));
        assertRejected(ProtocolRejectionCode.RESOURCE_REVISION_CONFLICT,
            () -> fixture.service.query(fixture.session, envelope(query, VERSION, hash('a'),
                fixture.binding.bindingManifestHash()), query));
        assertRejected(ProtocolRejectionCode.RESOURCE_REVISION_CONFLICT,
            () -> fixture.service.query(fixture.session, envelope(query, VERSION,
                fixture.binding.catalogChecksum(), hash('b')), query));

        ProviderOptionQueryService.Result result = fixture.service.query(fixture.session, fixture.envelope(query), query);

        assertEquals(VERSION, result.selectedVersion());
        assertEquals(fixture.binding, result.binding());
        assertEquals(1, fixture.provider.captures.get());
    }

    @Test
    void rejectsMissingNegotiationPolicyAndNonzeroOuterRevisionBeforeCapture() {
        Fixture fixture = fixture(OptionQuerySchemaV1.empty(), Set.of());
        OptionQuery query = query(SOURCE_REF, Map.of(), null, 10, null, 0L, "initial");

        fixture.connection.setNegotiatedFlowCapabilities(Set.of());
        assertRejected(ProtocolRejectionCode.AUTHORIZATION_DENIED,
            () -> fixture.service.query(fixture.session, fixture.envelope(query), query));
        fixture.connection.setNegotiatedFlowCapabilities(Set.of(OptionQueryAuthority.PROTOCOL_CAPABILITY.id().value()));

        ProviderOptionQueryService denied = fixture.service(fixture.activation::get, ignored -> fixture.publication.get(),
            () -> List.of(fixture.session), session -> true, ProtocolOptionQueryAuthorizer.denyAll(),
            ProtocolResourceAuthorizer.serverGranted(), (session, envelope, fence) -> {
            }, () -> 1L);
        assertRejected(ProtocolRejectionCode.AUTHORIZATION_DENIED,
            () -> denied.query(fixture.session, fixture.envelope(query), query));
        assertRejected(ProtocolRejectionCode.AUTHORIZATION_DENIED,
            () -> fixture.service.query(fixture.session,
                envelope(query, VERSION, fixture.binding.catalogChecksum(), fixture.binding.bindingManifestHash(), 1L), query));
        assertEquals(0, fixture.provider.captures.get());
    }

    @Test
    void rejectsReadOnlyOpaqueMissingAndMismatchedCanonicalSourceGrantsBeforeCapture() {
        Fixture fixture = fixture(OptionQuerySchemaV1.empty(), Set.of());
        OptionQuery query = query(SOURCE_REF, Map.of(), null, 10, null, 0L, "initial");
        CatalogAuthoringPublication active = fixture.publication.get().orElseThrow().authoringPublication();
        CatalogAuthoringPublication.Entry source = active.optionSources().getFirst();

        CatalogRuntimeActivation.ActivationRecord activation = fixture.activation.get();
        assertUnavailableSource(fixture, query, CatalogAuthoringPublication.project(fixture.binding,
            activation.catalog(), Set.of()));
        assertUnavailableSource(fixture, query, new CatalogAuthoringPublication(active.binding(),
            active.contractVersion(), active.projectionVersion(), List.of(
                new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.OPTION_SOURCES,
                    true, false, CatalogCacheState.UNAVAILABLE, List.of(
                        new CatalogAuthoringPublication.Entry(CatalogAuthoringPublication.Section.OPTION_SOURCES,
                            source.key(), CatalogCacheState.UNAVAILABLE, source.requiredCapabilities(),
                            source.requiredSections(), true, source.data())))), Set.of()));
        assertUnavailableSource(fixture, query, replaceOptionSection(active,
            new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.OPTION_SOURCES,
                true, true, CatalogCacheState.ACTIVE, List.of())));
        assertUnavailableSource(fixture, query, replaceOptionSection(active,
            new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.OPTION_SOURCES,
                true, true, CatalogCacheState.ACTIVE,
                List.of(new CatalogAuthoringPublication.Entry(CatalogAuthoringPublication.Section.OPTION_SOURCES,
                    source.key(), CatalogCacheState.ACTIVE, source.requiredCapabilities(), source.requiredSections(), false,
                    new CatalogCacheOpaque("{}".getBytes(StandardCharsets.UTF_8)))))));
        assertEquals(0, fixture.provider.captures.get());
    }

    @Test
    void rejectsUnsupportedAuthoringProjectionBeforeActivation() {
        Fixture fixture = fixture(OptionQuerySchemaV1.empty(), Set.of());
        CatalogCachePublication current = fixture.publication.get().orElseThrow();
        CatalogRuntimeActivation.ActivationRecord activation = fixture.activation.get();
        CatalogAuthoringPublication unsupported = CatalogAuthoringPublication.project(fixture.binding,
            activation.catalog(), Set.of(QUERY), new CatalogProjectionVersion(2, 0));
        CatalogCacheKey key = CatalogCacheKey.of(current.serverId(), current.catalogBinding(),
            unsupported.projectionVersion());
        CatalogCachePublication publication = new CatalogCachePublication(current.kind(), key,
            current.catalogBinding(), current.revision(), current.entries(), unsupported, current.unknown());

        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
            () -> new CatalogRuntimeActivation.ActivationRecord(activation.catalog(), activation.runtime(),
                Optional.of(publication.key())));

        assertEquals("Publication Key Does Not Bind Candidate Catalog", rejected.getMessage());
        assertEquals(0, fixture.provider.captures.get());
    }

    @Test
    void rejectsTheWrongSourceReferenceEvenWhenItSharesTheCapability() {
        Fixture fixture = fixture(OptionQuerySchemaV1.empty(), Set.of());
        ContractRef<InspectorFieldId> wrongSource = ContractRef.of(OWNER, InspectorFieldId.of("other-options"));
        OptionQuery query = query(wrongSource, Map.of(), null, 10, null, 0L, "initial");

        assertRejected(ProtocolRejectionCode.RESOURCE_TYPE_UNAVAILABLE,
            () -> fixture.service.query(fixture.session, fixture.envelope(query), query));
        assertEquals(0, fixture.provider.captures.get());
    }

    @Test
    void returnsScalarItemsWithExactIdentityAndOpaqueRevisionState() {
        OptionQuerySchemaV1 schema = new OptionQuerySchemaV1(null,
            Map.of("mode", new OptionQuerySchemaV1.Field(STRING, true)), Map.of());
        Fixture fixture = fixture(schema, Set.of("mode"));
        fixture.provider.items.set(List.of(
            new OptionCatalogItem("beta", "Beta", "Second option", "", "", Map.of("available", false,
                "reason", "Disabled by fixture")),
            new OptionCatalogItem("alpha", "Alpha", "First option", "", "", Map.of())));
        OptionQuery query = query(SOURCE_REF, Map.of("mode", TypedValue.value(STRING, "edit")), null, 10,
            null, 0L, "initial");

        ProviderOptionQueryService.Result result = fixture.service.query(fixture.session, fixture.envelope(query), query);
        OptionPage page = result.page();

        assertEquals(SOURCE_REF, page.sourceRef());
        assertEquals(QUERY, page.query());
        assertTrue(page.revision() > 0L);
        assertTrue(page.invalidationKey().startsWith("provider-options:"));
        assertEquals(List.of("alpha", "beta"), page.items().stream().map(item -> item.value().value()).toList());
        assertEquals(STRING, page.items().getFirst().value().type());
        assertNull(page.items().getFirst().value().locator());
        assertFalse(page.items().get(1).available());
        assertEquals("Disabled by fixture", page.items().get(1).reason());
        assertEquals(PROVIDER_SOURCE, fixture.provider.lastQuery.get().sourceId());
        assertEquals(Map.of("mode", "edit"), fixture.provider.lastQuery.get().context());
    }

    @Test
    void rejectsContextfulProvidersWhosePublishedSchemaIsEmpty() {
        Fixture fixture = fixture(OptionQuerySchemaV1.empty(), Set.of("mode"));
        OptionQuery query = query(SOURCE_REF, Map.of(), null, 10, null, 0L, "initial");

        assertRejected(ProtocolRejectionCode.UNSUPPORTED_GENERATION,
            () -> fixture.service.query(fixture.session, fixture.envelope(query), query));
        assertEquals(0, fixture.provider.captures.get());
    }

    @Test
    void rejectsActivationPublicationSessionProviderAndSourceEpochChangesDuringCapture() {
        Fixture activation = fixture(OptionQuerySchemaV1.empty(), Set.of());
        activation.provider.afterCapture.set(() -> activation.activation.set(activation.replacementActivation()));
        assertConflict(activation);

        Fixture publication = fixture(OptionQuerySchemaV1.empty(), Set.of());
        publication.provider.afterCapture.set(() -> publication.publication.set(Optional.of(publication.replacementPublication())));
        assertConflict(publication);

        Fixture session = fixture(OptionQuerySchemaV1.empty(), Set.of());
        session.provider.afterCapture.set(() -> session.connection.setState(ConnectionState.CONNECTING));
        assertConflict(session);

        Fixture provider = fixture(OptionQuerySchemaV1.empty(), Set.of());
        provider.provider.afterCapture.set(() -> {
            provider.providers.unregister(PROVIDER_SOURCE);
            provider.providers.register(new MutableProvider(Set.of()));
        });
        assertConflict(provider);

        Fixture epoch = fixture(OptionQuerySchemaV1.empty(), Set.of());
        epoch.provider.afterCapture.set(() -> epoch.service.sourceChanged(PROVIDER_SOURCE));
        assertConflict(epoch);
    }

    @Test
    void rejectsAContinuationAfterTheProviderSourceEpochChanges() {
        Fixture fixture = fixture(OptionQuerySchemaV1.empty(), Set.of());
        fixture.provider.items.set(List.of(new OptionCatalogItem("alpha"), new OptionCatalogItem("beta")));
        OptionQuery initial = query(SOURCE_REF, Map.of(), null, 1, null, 0L, "initial");
        OptionPage first = fixture.service.query(fixture.session, fixture.envelope(initial), initial).page();
        fixture.service.sourceChanged(PROVIDER_SOURCE);
        OptionQuery stale = query(SOURCE_REF, Map.of(), null, 1, first.nextCursor(), first.revision(),
            first.invalidationKey());

        assertRejected(ProtocolRejectionCode.INVALID_CURSOR,
            () -> fixture.service.query(fixture.session, fixture.envelope(stale), stale));
        assertEquals(2, fixture.provider.captures.get());
    }

    @Test
    void publishesExactInvalidationsAndResetsPerSessionSequence() {
        Fixture fixture = fixture(OptionQuerySchemaV1.empty(), Set.of());
        List<ProtocolEnvelope<Map<String, Object>>> events = new ArrayList<>();
        ProviderOptionQueryService service = fixture.service(() -> List.of(fixture.session),
            (session, envelope, fence) -> {
                if (fence.current()) {
                    events.add(envelope);
                }
            }, () -> 17L);

        service.sourceChanged(PROVIDER_SOURCE);
        service.sourceChanged(PROVIDER_SOURCE);
        service.resetSession(fixture.session);
        service.sourceChanged(PROVIDER_SOURCE);

        assertEquals(3, events.size());
        assertEquals(List.of(1L, 2L, 1L), events.stream().map(ProtocolEnvelope::sequence).toList());
        ProtocolEnvelope<Map<String, Object>> event = events.getFirst();
        assertEquals(ProtocolEnvelope.Kind.EVENT, event.kind());
        assertEquals(OptionQueryAuthority.OPERATION, event.operation());
        assertEquals(OptionQueryAuthority.PAGE_TYPE, event.payloadType());
        assertEquals(Set.of(OptionQueryAuthority.PROTOCOL_CAPABILITY), event.capabilities());
        assertEquals(VERSION, event.selectedVersion());
        assertEquals(fixture.binding.catalogChecksum(), event.catalogChecksum());
        assertEquals(fixture.binding.bindingManifestHash(), event.bindingManifestHash());
        assertEquals(17L, event.authorityEpoch());
        ProtocolBody.OptionInvalidationEvent body = assertInstanceOf(ProtocolBody.OptionInvalidationEvent.class,
            event.body());
        OptionInvalidation invalidation = body.invalidation();
        assertEquals(SOURCE_REF, invalidation.sourceRef());
        assertEquals(QUERY, invalidation.query());
        assertEquals(SERVER, invalidation.serverId());
        assertEquals(0L, event.revision());
        assertTrue(invalidation.revision() > 0L);
        assertTrue(invalidation.invalidationKey().startsWith("provider-source:"));
    }

    @Test
    void suppressesInvalidationWhenAnyPresendFenceChanges() {
        Fixture activation = fixture(OptionQuerySchemaV1.empty(), Set.of());
        List<ProtocolEnvelope<Map<String, Object>>> activationEvents = new ArrayList<>();
        ProviderOptionQueryService activationService = activation.service(() -> {
            activation.activation.set(activation.replacementActivation());
            return List.of(activation.session);
        }, (session, envelope, fence) -> activationEvents.add(envelope), () -> 1L);
        activationService.sourceChanged(PROVIDER_SOURCE);
        assertTrue(activationEvents.isEmpty());

        Fixture publication = fixture(OptionQuerySchemaV1.empty(), Set.of());
        AtomicInteger publicationReads = new AtomicInteger();
        CatalogCachePublication replacement = publication.replacementPublication();
        List<ProtocolEnvelope<Map<String, Object>>> publicationEvents = new ArrayList<>();
        ProviderOptionQueryService publicationService = publication.service(publication.activation::get,
            ignored -> publicationReads.incrementAndGet() == 1 ? publication.publication.get() : Optional.of(replacement),
            () -> List.of(publication.session), (session, envelope, fence) -> publicationEvents.add(envelope), () -> 1L);
        publicationService.sourceChanged(PROVIDER_SOURCE);
        assertTrue(publicationEvents.isEmpty());

        Fixture session = fixture(OptionQuerySchemaV1.empty(), Set.of());
        AtomicInteger activationReads = new AtomicInteger();
        List<ProtocolEnvelope<Map<String, Object>>> sessionEvents = new ArrayList<>();
        ProviderOptionQueryService sessionService = session.service(() -> {
            if (activationReads.incrementAndGet() > 1) {
                session.connection.setState(ConnectionState.CONNECTING);
            }
            return session.activation.get();
        }, ignored -> session.publication.get(), () -> List.of(session.session),
            (target, envelope, fence) -> sessionEvents.add(envelope), () -> 1L);
        sessionService.sourceChanged(PROVIDER_SOURCE);
        assertTrue(sessionEvents.isEmpty());

        Fixture provider = fixture(OptionQuerySchemaV1.empty(), Set.of());
        List<ProtocolEnvelope<Map<String, Object>>> providerEvents = new ArrayList<>();
        ProviderOptionQueryService providerService = provider.service(() -> {
            provider.providers.unregister(PROVIDER_SOURCE);
            provider.providers.register(new MutableProvider(Set.of()));
            return List.of(provider.session);
        }, (target, envelope, fence) -> providerEvents.add(envelope), () -> 1L);
        providerService.sourceChanged(PROVIDER_SOURCE);
        assertTrue(providerEvents.isEmpty());

        Fixture grant = fixture(OptionQuerySchemaV1.empty(), Set.of());
        List<ProtocolEnvelope<Map<String, Object>>> grantEvents = new ArrayList<>();
        ProviderOptionQueryService grantService = grant.service(() -> List.of(grant.session),
            (target, envelope, fence) -> grantEvents.add(envelope), () -> 1L);
        grant.connection.clearProtocolSession();
        grantService.sourceChanged(PROVIDER_SOURCE);
        assertTrue(grantEvents.isEmpty());
    }

    @Test
    void captureCannotRepopulateStateAfterCloseOrActivationReplacement() {
        Fixture closed = fixture(OptionQuerySchemaV1.empty(), Set.of());
        closed.provider.afterCapture.set(closed.service::close);
        OptionQuery query = query(SOURCE_REF, Map.of(), null, 10, null, 0L, "initial");
        assertRejected(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE,
            () -> closed.service.query(closed.session, closed.envelope(query), query));
        assertEquals(0, closed.service.providerStateCount());

        Fixture replaced = fixture(OptionQuerySchemaV1.empty(), Set.of());
        replaced.provider.afterCapture.set(() -> replaced.activation.set(replaced.replacementActivation()));
        assertRejected(ProtocolRejectionCode.RESOURCE_REVISION_CONFLICT,
            () -> replaced.service.query(replaced.session, replaced.envelope(query), query));
        assertEquals(0, replaced.service.providerStateCount());
    }

    @Test
    void handlerEchoesTheExactPublicationTupleAndPageRevision() {
        Fixture fixture = fixture(OptionQuerySchemaV1.empty(), Set.of());
        OptionQuery query = query(SOURCE_REF, Map.of(), null, 10, null, 0L, "initial");
        ProtocolEnvelope<Map<String, Object>> request = fixture.envelope(query);
        FlowResourceProtocolEnvelopeHandler handler = new FlowResourceProtocolEnvelopeHandler(new FlowResourceRegistry(), SERVER,
            ProtocolResourceMutationAuthority.failClosed(), ProtocolResourceAuthorizer.serverGranted(), AuthorityEpoch.fixed(9L),
            null, fixture.service);

        ProtocolEnvelopeDispatchResult result = handler.handle(fixture.connection, fixture.session, request);

        assertTrue(result.handled());
        ProtocolEnvelope<Map<String, Object>> response = result.response();
        OptionPage page = assertInstanceOf(ProtocolBody.OptionPageResponse.class, response.body()).page();
        assertEquals(ProtocolEnvelope.Kind.RESPONSE, response.kind());
        assertEquals(request.requestId(), response.requestId());
        assertEquals(request.sequence(), response.sequence());
        assertEquals(0L, response.revision());
        assertTrue(page.revision() > 0L);
        assertEquals(SOURCE_REF, page.sourceRef());
        assertEquals(QUERY, page.query());
        assertEquals(VERSION, response.selectedVersion());
        assertEquals(fixture.binding.catalogChecksum(), response.catalogChecksum());
        assertEquals(fixture.binding.bindingManifestHash(), response.bindingManifestHash());
    }

    private static void assertConflict(Fixture fixture) {
        OptionQuery query = query(SOURCE_REF, Map.of(), null, 10, null, 0L, "initial");
        assertRejected(ProtocolRejectionCode.RESOURCE_REVISION_CONFLICT,
            () -> fixture.service.query(fixture.session, fixture.envelope(query), query));
        assertEquals(1, fixture.provider.captures.get());
    }

    private static void assertRejected(ProtocolRejectionCode code, Runnable action) {
        OptionQueryAuthority.Rejected rejected = assertThrows(OptionQueryAuthority.Rejected.class, action::run);
        assertEquals(code, rejected.code());
    }

    private static Fixture fixture(OptionQuerySchemaV1 schema, Set<String> contextKeys) {
        MutableProvider provider = new MutableProvider(contextKeys);
        RuntimeRegistrySnapshot runtime = RuntimeRegistrySnapshot.empty();
        InspectorOptionSource source = new InspectorOptionSource(SOURCE_REF.id(), "Fixture Options",
            "Provides authoritative fixture choices for option transport tests.", STRING, schema, QUERY, 50,
            "fixture-options");
        CatalogNodeDescriptor.Pin pin = new CatalogNodeDescriptor.Pin(PinId.of("value"),
            CatalogNodeDescriptor.Direction.INPUT, STRING, "Value", "Selects one fixture value.",
            CatalogNodeDescriptor.Requirement.REQUIRED, null, EDITOR, SOURCE_REF, InspectorCondition.always(),
            CatalogNodeDescriptor.RepeatableIntent.disabled());
        RuntimeSemantics semantics = semantics();
        CatalogNodeDescriptor node = CatalogNodeDescriptor.builder("fixture-node")
            .displayName("Fixture Node")
            .description("Resolves one authoritative fixture option for protocol tests.")
            .category(ContractRef.of(OWNER, CapabilityId.of("fixture")))
            .pins(List.of(pin))
            .branches(List.of(new CatalogNodeDescriptor.Branch("failed", "Failed",
                "Reports that the fixture option could not be resolved.",
                List.of(new CatalogNodeDescriptor.Case("failure", "Failure", "The fixture option resolution failed.")))))
            .handler(new CatalogNodeDescriptor.Handler(QUERY, RESOLVE))
            .semantics(semantics)
            .requiredCapabilities(Set.of(QUERY))
            .metadata(Map.of("inputs", List.of(Map.of("name", "value", "direction", "INPUT",
                "optionsSource", PROVIDER_SOURCE))))
            .build();
        RuntimeOperationDescriptor requirement = new RuntimeOperationDescriptor(QUERY, RESOLVE,
            List.of(new RuntimeOperationDescriptor.Pin(PinId.of("value"), RuntimeOperationDescriptor.Direction.INPUT,
                STRING)), semantics);
        CatalogContribution contribution = CatalogContribution.builder(OWNER, "1.0.0",
                new CatalogContractRange(VERSION, VERSION), CatalogProvenance.fromText(
                    CatalogProvenance.SourceKind.BUNDLED, "test://provider-option-query", "1.0.0", "test",
                    "provider-option-query"))
            .definitions(List.of(node))
            .categories(List.of(new CatalogCategoryDescriptor("fixture", "Fixture", "Fixture option query tests.", 1)))
            .capabilities(List.of(
                new CatalogCapabilityDescriptor(QUERY.id(), 1, false, InspectorFallback.GENERIC),
                new CatalogCapabilityDescriptor(EDITOR.id(), 1, true, InspectorFallback.READ_ONLY_FIELD)))
            .runtimeRequirements(List.of(requirement))
            .optionSources(List.of(source))
            .build();
        CatalogSnapshot catalog = new CatalogCompiler(VERSION, CatalogBindingProof.fixed(
            Map.of(requirement.key(), requirement.executionFingerprint()), runtime.bindingManifestHash()))
            .compile(List.of(contribution), 7L).snapshot()
            .orElseThrow(() -> new AssertionError("Fixture catalog was rejected"));
        CatalogBinding binding = new CatalogBinding(catalog.generation(), catalog.contentChecksum(),
            catalog.bindingManifestHash());
        CatalogCacheKey key = CatalogCacheKey.of(SERVER, binding);
        CatalogAuthoringPublication authoring = CatalogAuthoringPublication.project(binding, catalog, Set.of(QUERY));
        CatalogCachePublication publication = new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key,
            binding, 11L, List.of(), authoring, Map.of());
        CatalogRuntimeActivation.ActivationRecord activation = new CatalogRuntimeActivation.ActivationRecord(catalog,
            runtime, Optional.of(key));
        OptionCatalogRegistry providers = new OptionCatalogRegistry();
        assertTrue(providers.register(provider));
        ConnectionInfo connection = authenticatedConnection("client");
        Session session = new Session("fixture-session", "client", connection,
            new ClientIdentity("client", "2.1.0"));
        return new Fixture(provider, providers, connection, session, activation, publication, binding);
    }

    private static RuntimeSemantics semantics() {
        RuntimeFailureContract failure = new RuntimeFailureContract(STRING, Set.of("RUNTIME.FAILURE"), Set.of("failed"),
            RuntimeFailureContract.CommitBoundary.NO_MUTATION);
        return new RuntimeSemantics(RuntimeSemantics.Effect.PURE, RuntimeSemantics.ThreadMode.CURRENT, QUERY,
            RuntimeSemantics.Cancellation.NONE, 0L, 0L, 0L, RuntimeSemantics.UnloadPolicy.DRAIN,
            RuntimeSemantics.Retry.NEVER, RuntimeSemantics.Idempotency.INTRINSIC, RuntimeSemantics.Audit.NONE,
            RuntimeSemantics.Confirmation.NONE, RuntimeSemantics.SensitiveData.NONE,
            RuntimeSemantics.Determinism.DETERMINISTIC, Set.of(), Set.of("failed"), Set.of(), failure, Set.of(),
            Set.of());
    }

    private static OptionQuery query(ContractRef<InspectorFieldId> sourceRef, Map<String, TypedValue> context,
                                     String search, int limit, String cursor, long revision, String invalidationKey) {
        return new OptionQuery(sourceRef, QUERY, SERVER, null, context, Map.of(), cursor, limit, search, revision,
            invalidationKey);
    }

    private static ProtocolEnvelope<Map<String, Object>> envelope(OptionQuery query, CatalogVersion selectedVersion,
                                                                   ContentHash checksum, ContentHash manifestHash) {
        return envelope(query, selectedVersion, checksum, manifestHash, 0L);
    }

    private static ProtocolEnvelope<Map<String, Object>> envelope(OptionQuery query, CatalogVersion selectedVersion,
                                                                   ContentHash checksum, ContentHash manifestHash,
                                                                   long outerRevision) {
        return new ProtocolEnvelope<>(ProtocolEnvelope.Kind.REQUEST, VERSION, UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), SERVER, null, outerRevision, 1L, null, OptionQueryAuthority.OPERATION,
            Set.of(OptionQueryAuthority.PROTOCOL_CAPABILITY), OptionQueryAuthority.PAGE_TYPE, null, null, false,
            selectedVersion, checksum, manifestHash, null, null, 23L, ProtocolEnvelope.Status.ACCEPTED, List.of(),
            Map.of(), new ProtocolBody.OptionQueryRequest(query, Map.of()));
    }

    private static ContentHash hash(char value) {
        return ContentHash.of(String.valueOf(value).repeat(64));
    }

    private static void assertUnavailableSource(Fixture fixture, OptionQuery query,
                                                CatalogAuthoringPublication authoring) {
        CatalogCachePublication current = fixture.publication.get().orElseThrow();
        fixture.publication.set(Optional.of(new CatalogCachePublication(current.kind(), current.key(),
            current.catalogBinding(), current.revision(), current.entries(), authoring, current.unknown())));
        assertRejected(ProtocolRejectionCode.RESOURCE_TYPE_UNAVAILABLE,
            () -> fixture.service.query(fixture.session, fixture.envelope(query), query));
    }

    private static CatalogAuthoringPublication replaceOptionSection(CatalogAuthoringPublication publication,
                                                                     CatalogAuthoringPublication.SectionProjection optionSection) {
        List<CatalogAuthoringPublication.SectionProjection> sections = publication.sections().stream()
            .map(section -> section.section() == CatalogAuthoringPublication.Section.OPTION_SOURCES ? optionSection : section)
            .toList();
        return new CatalogAuthoringPublication(publication.binding(), publication.contractVersion(),
            publication.projectionVersion(), sections, null);
    }

    private static ConnectionInfo authenticatedConnection(String clientId) {
        ConnectionInfo connection = new ConnectionInfo(null, new FrameSender() {
            @Override
            public void send(byte[] frame) {
            }

            @Override
            public void close(int code, String reason) {
            }
        }, 1);
        connection.setClientId(clientId);
        connection.setClientVersion("2.1.0");
        connection.setState(ConnectionState.AUTHENTICATED);
        connection.setProtocolResourceAccess(true);
        connection.setNegotiatedFlowCapabilities(Set.of(OptionQueryAuthority.PROTOCOL_CAPABILITY.id().value()));
        return connection;
    }

    private static OptionCatalogProvider provider(OptionCatalogProvider.CaptureAffinity affinity,
                                                   AtomicInteger captures) {
        return new OptionCatalogProvider() {
            @Override
            public String sourceId() {
                return PROVIDER_SOURCE;
            }

            @Override
            public CaptureAffinity captureAffinity() {
                return affinity;
            }

            @Override
            public OptionCatalogCapture capture(OptionCatalogQuery query) {
                captures.incrementAndGet();
                return new OptionCatalogCapture("revision", List.of(new OptionCatalogItem("value")), "available", "");
            }

            @Override
            public String revision() {
                return "legacy";
            }

            @Override
            public List<String> values() {
                return List.of("legacy");
            }
        };
    }

    private static final class MutableProvider implements OptionCatalogProvider {
        private final Set<String> contextKeys;
        private final AtomicInteger captures = new AtomicInteger();
        private final AtomicReference<String> revision = new AtomicReference<>("provider-revision-1");
        private final AtomicReference<List<OptionCatalogItem>> items = new AtomicReference<>(
            List.of(new OptionCatalogItem("value")));
        private final AtomicReference<OptionCatalogQuery> lastQuery = new AtomicReference<>();
        private final AtomicReference<Runnable> afterCapture = new AtomicReference<>(() -> {
        });

        private MutableProvider(Set<String> contextKeys) {
            this.contextKeys = Set.copyOf(contextKeys);
        }

        @Override
        public String sourceId() {
            return PROVIDER_SOURCE;
        }

        @Override
        public CaptureAffinity captureAffinity() {
            return CaptureAffinity.CALLER;
        }

        @Override
        public OptionCatalogCapture capture(OptionCatalogQuery query) {
            captures.incrementAndGet();
            lastQuery.set(query);
            OptionCatalogCapture capture = new OptionCatalogCapture(revision.get(), items.get(), "available", "");
            afterCapture.get().run();
            return capture;
        }

        @Override
        public Set<String> contextKeys() {
            return contextKeys;
        }

        @Override
        public String revision() {
            return revision.get();
        }

        @Override
        public List<String> values() {
            return items.get().stream().map(OptionCatalogItem::value).toList();
        }
    }

    private static final class Fixture {
        private final MutableProvider provider;
        private final OptionCatalogRegistry providers;
        private final ConnectionInfo connection;
        private final Session session;
        private final AtomicReference<CatalogRuntimeActivation.ActivationRecord> activation;
        private final AtomicReference<Optional<CatalogCachePublication>> publication;
        private final CatalogBinding binding;
        private final ProviderOptionQueryService service;

        private Fixture(MutableProvider provider, OptionCatalogRegistry providers, ConnectionInfo connection,
                        Session session, CatalogRuntimeActivation.ActivationRecord activation,
                        CatalogCachePublication publication, CatalogBinding binding) {
            this.provider = provider;
            this.providers = providers;
            this.connection = connection;
            this.session = session;
            this.activation = new AtomicReference<>(activation);
            this.publication = new AtomicReference<>(Optional.of(publication));
            this.binding = binding;
            this.service = service(() -> List.of(session), (target, envelope, fence) -> {
            }, () -> 1L);
        }

        private ProviderOptionQueryService service(Supplier<? extends List<Session>> sessions,
                                                   ProviderOptionQueryService.EventSender sender,
                                                   LongSupplier authorityEpoch) {
            return service(activation::get, ignored -> publication.get(), sessions, sender, authorityEpoch);
        }

        private ProviderOptionQueryService service(Supplier<CatalogRuntimeActivation.ActivationRecord> activations,
                                                   Function<Session, Optional<CatalogCachePublication>> publications,
                                                   Supplier<? extends List<Session>> sessions,
                                                   ProviderOptionQueryService.EventSender sender,
                                                   LongSupplier authorityEpoch) {
            return service(activations, publications, sessions,
                session -> sessions.get().stream().anyMatch(candidate -> candidate == session),
                ProtocolOptionQueryAuthorizer.serverGranted(), ProtocolResourceAuthorizer.serverGranted(), sender,
                authorityEpoch);
        }

        private ProviderOptionQueryService service(Supplier<CatalogRuntimeActivation.ActivationRecord> activations,
                                                   Function<Session, Optional<CatalogCachePublication>> publications,
                                                   Supplier<? extends List<Session>> sessions,
                                                   Predicate<Session> current, ProtocolOptionQueryAuthorizer optionAuthorizer,
                                                   ProtocolResourceAuthorizer resourceAuthorizer,
                                                   ProviderOptionQueryService.EventSender sender,
                                                   LongSupplier authorityEpoch) {
            return new ProviderOptionQueryService(SERVER, providers, activations, publications,
                OptionCatalogCaptureExecutor.callerOnly(),
                new FlowResourceOptionQueryAdapter(new FlowResourceRegistry(), SERVER), sessions,
                current, optionAuthorizer, resourceAuthorizer, sender, authorityEpoch);
        }

        private ProtocolEnvelope<Map<String, Object>> envelope(OptionQuery query) {
            return ProviderOptionQueryServiceTest.envelope(query, VERSION, binding.catalogChecksum(),
                binding.bindingManifestHash());
        }

        private CatalogRuntimeActivation.ActivationRecord replacementActivation() {
            CatalogRuntimeActivation.ActivationRecord current = activation.get();
            return new CatalogRuntimeActivation.ActivationRecord(current.catalog(), current.runtime(),
                current.publicationKey());
        }

        private CatalogCachePublication replacementPublication() {
            CatalogCachePublication current = publication.get().orElseThrow();
            return new CatalogCachePublication(current.kind(), current.key(), current.catalogBinding(),
                current.revision() + 1L, current.entries(), current.authoringPublication(), current.unknown());
        }
    }
}
