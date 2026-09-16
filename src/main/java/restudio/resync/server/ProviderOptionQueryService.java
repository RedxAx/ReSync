package restudio.resync.server;

import restudio.resync.api.OptionCatalogCapture;
import restudio.resync.api.OptionCatalogProvider;
import restudio.resync.api.OptionCatalogQuery;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.core.ConnectionInfo;
import restudio.resync.core.Session;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogAuthoringPublication;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.catalog.CatalogOwned;
import restudio.resync.flow.catalog.CatalogRuntimeActivation;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.inspector.InspectorOptionSource;
import restudio.resync.flow.inspector.OptionQuerySchemaV1;
import restudio.resync.flow.protocol.OptionPage;
import restudio.resync.flow.protocol.OptionInvalidation;
import restudio.resync.flow.protocol.OptionQuery;
import restudio.resync.flow.protocol.ProtocolBody;
import restudio.resync.flow.protocol.ProtocolEnvelope;
import restudio.resync.flow.protocol.ProtocolRejectionCode;
import restudio.resync.flow.protocol.ResourceQueryRequest;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypedValue;

import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class ProviderOptionQueryService implements AutoCloseable {
    private final ServerId serverId;
    private final OptionCatalogRegistry providers;
    private final Supplier<CatalogRuntimeActivation.ActivationRecord> activationSupplier;
    private final Function<Session, Optional<CatalogCachePublication>> publicationSupplier;
    private final OptionCatalogCaptureExecutor captureExecutor;
    private final FlowResourceOptionQueryAdapter adapter;
    private final ProtocolOptionQueryAuthorizer optionAuthorizer;
    private final ProtocolResourceAuthorizer resourceAuthorizer;
    private final Supplier<? extends Collection<Session>> sessionSupplier;
    private final Predicate<Session> sessionCurrent;
    private final EventSender eventSender;
    private final LongSupplier authorityEpochSupplier;
    private final Object stateFence = new Object();
    private final Map<SourceKey, ProviderState> providerStates = new HashMap<>();
    private final Map<Session, Long> sessionSequences = new IdentityHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private ActivationCache activationCache;

    public ProviderOptionQueryService(ServerId serverId, OptionCatalogRegistry providers,
                                      Supplier<CatalogRuntimeActivation.ActivationRecord> activationSupplier,
                                      Function<Session, Optional<CatalogCachePublication>> publicationSupplier,
                                      OptionCatalogCaptureExecutor captureExecutor, FlowResourceOptionQueryAdapter adapter) {
        this(serverId, providers, activationSupplier, publicationSupplier, captureExecutor, adapter, List::of,
            session -> true, ProtocolOptionQueryAuthorizer.serverGranted(), ProtocolResourceAuthorizer.serverGranted(),
            (session, envelope, fence) -> {
            }, () -> 0L);
    }

    public ProviderOptionQueryService(ServerId serverId, OptionCatalogRegistry providers,
                                      Supplier<CatalogRuntimeActivation.ActivationRecord> activationSupplier,
                                      Function<Session, Optional<CatalogCachePublication>> publicationSupplier,
                                      OptionCatalogCaptureExecutor captureExecutor, FlowResourceOptionQueryAdapter adapter,
                                      Supplier<? extends Collection<Session>> sessionSupplier, EventSender eventSender,
                                      LongSupplier authorityEpochSupplier) {
        this(serverId, providers, activationSupplier, publicationSupplier, captureExecutor, adapter, sessionSupplier,
            session -> currentSession(sessionSupplier, session), ProtocolOptionQueryAuthorizer.serverGranted(),
            ProtocolResourceAuthorizer.serverGranted(), eventSender, authorityEpochSupplier);
    }

    public ProviderOptionQueryService(ServerId serverId, OptionCatalogRegistry providers,
                                      Supplier<CatalogRuntimeActivation.ActivationRecord> activationSupplier,
                                      Function<Session, Optional<CatalogCachePublication>> publicationSupplier,
                                      OptionCatalogCaptureExecutor captureExecutor, FlowResourceOptionQueryAdapter adapter,
                                      Supplier<? extends Collection<Session>> sessionSupplier, Predicate<Session> sessionCurrent,
                                      ProtocolOptionQueryAuthorizer optionAuthorizer, ProtocolResourceAuthorizer resourceAuthorizer,
                                      EventSender eventSender, LongSupplier authorityEpochSupplier) {
        this.serverId = Objects.requireNonNull(serverId, "Option query server ID is required");
        this.providers = Objects.requireNonNull(providers, "Option catalog registry is required");
        this.activationSupplier = Objects.requireNonNull(activationSupplier, "Catalog activation supplier is required");
        this.publicationSupplier = Objects.requireNonNull(publicationSupplier, "Catalog publication supplier is required");
        this.captureExecutor = Objects.requireNonNull(captureExecutor, "Option capture executor is required");
        this.adapter = Objects.requireNonNull(adapter, "Option query adapter is required");
        this.optionAuthorizer = ProtocolOptionQueryAuthorizer.require(optionAuthorizer);
        this.resourceAuthorizer = ProtocolResourceAuthorizer.require(resourceAuthorizer);
        this.sessionSupplier = Objects.requireNonNull(sessionSupplier, "Option query session supplier is required");
        this.sessionCurrent = Objects.requireNonNull(sessionCurrent, "Option query current-session predicate is required");
        this.eventSender = Objects.requireNonNull(eventSender, "Option invalidation sender is required");
        this.authorityEpochSupplier = Objects.requireNonNull(authorityEpochSupplier, "Authority epoch supplier is required");
    }

    public Result query(Session session, ProtocolEnvelope<Map<String, Object>> envelope, OptionQuery request) {
        Objects.requireNonNull(session, "Option query session is required");
        Objects.requireNonNull(envelope, "Option query envelope is required");
        Objects.requireNonNull(request, "Option query is required");
        Access access = requireAccess(session.getConnection(), session, envelope, request);
        CatalogRuntimeActivation.ActivationRecord activation = access.activation();
        CatalogCachePublication publication = access.publication();
        CatalogBinding binding = access.binding();
        Binding sourceBinding = access.source();
        OptionQuerySchemaV1.Normalized normalized = access.normalized();
        OptionCatalogProvider provider = providers.provider(sourceBinding.providerSourceId());
        if (provider == null) {
            throw rejected(ProtocolRejectionCode.RESOURCE_TYPE_UNAVAILABLE, "Option catalog provider is unavailable");
        }
        Set<String> publishedKeys = new LinkedHashSet<>(sourceBinding.descriptor().querySchema().context().keySet());
        publishedKeys.addAll(sourceBinding.descriptor().querySchema().dependencies().keySet());
        Set<String> providerKeys = provider.contextKeys();
        if (providerKeys == null || !publishedKeys.containsAll(providerKeys)) {
            throw rejected(ProtocolRejectionCode.UNSUPPORTED_GENERATION,
                "Option catalog provider requires a typed query schema from a newer catalog generation");
        }
        OptionQuery normalizedRequest = new OptionQuery(request.sourceRef(), request.query(), request.serverId(), normalized.resource(),
            normalized.context(), normalized.dependencies(), request.cursor(), request.limit(), request.search(), request.revision(),
            request.invalidationKey());
        SourceKey key = new SourceKey(activation.publicationKey().orElseThrow(), request.sourceRef(), request.query(),
            sourceBinding.providerSourceId());
        ProviderState beforeCapture;
        synchronized (stateFence) {
            requireCurrentActivationLocked(activation);
            beforeCapture = providerStates.get(key);
        }
        OptionCatalogQuery providerQuery = providerQuery(sourceBinding.providerSourceId(), normalized);
        OptionCatalogCapture capture;
        try {
            capture = captureExecutor.capture(provider, providerQuery);
        } catch (OptionQueryAuthority.Rejected rejection) {
            throw rejection;
        } catch (RuntimeException exception) {
            throw rejected(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE, "Authoritative option provider capture failed", exception);
        }
        if (!"available".equalsIgnoreCase(capture.status())) {
            String diagnostic = capture.diagnostic().isBlank() ? "Authoritative option provider is unavailable" : capture.diagnostic();
            throw rejected(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE, diagnostic);
        }
        ProviderState state = state(activation, provider, key, beforeCapture, capture.revision());
        OptionQueryAuthority.Source source = new OptionQueryAuthority.Source(request.sourceRef(), sourceBinding.descriptor(),
            sourceBinding.providerSourceId(), state.epoch(), List.of());
        OptionPage page = adapter.query(normalizedRequest, source, capture);
        fence(session, envelope, request, activation, publication, provider, key, state);
        return new Result(page, publication.authoringPublication().contractVersion(), binding);
    }

    public boolean authorize(ConnectionInfo connection, Session session, ProtocolEnvelope<Map<String, Object>> envelope,
                             OptionQuery request) {
        try {
            requireAccess(connection, session, envelope, request);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public void sourceChanged(String providerSourceId) {
        if (closed.get() || providerSourceId == null || providerSourceId.isBlank()) {
            return;
        }
        CatalogRuntimeActivation.ActivationRecord activation;
        try {
            activation = activeActivation();
            synchronized (stateFence) {
                requireCurrentActivationLocked(activation);
            }
        } catch (OptionQueryAuthority.Rejected rejection) {
            return;
        }
        for (CatalogOwned<InspectorOptionSource> owned : activation.catalog().optionSources()) {
            @SuppressWarnings("unchecked")
            ContractRef<InspectorFieldId> sourceRef = (ContractRef<InspectorFieldId>) owned.key();
            InspectorOptionSource descriptor = owned.descriptor();
            Binding binding;
            try {
                binding = binding(activation, sourceRef, descriptor.capability());
            } catch (OptionQueryAuthority.Rejected rejection) {
                continue;
            }
            if (!providerSourceId.equalsIgnoreCase(binding.providerSourceId())) {
                continue;
            }
            OptionCatalogProvider provider = providers.provider(binding.providerSourceId());
            if (provider == null) {
                continue;
            }
            SourceKey key = new SourceKey(activation.publicationKey().orElseThrow(), sourceRef, descriptor.capability(),
                binding.providerSourceId());
            ProviderState state;
            synchronized (stateFence) {
                try {
                    requireCurrentActivationLocked(activation);
                } catch (OptionQueryAuthority.Rejected rejection) {
                    return;
                }
                if (providers.provider(key.providerSourceId()) != provider) {
                    continue;
                }
                ProviderState current = providerStates.get(key);
                state = current == null ? new ProviderState(1L, null) : current.next(null);
                providerStates.put(key, state);
            }
            publishInvalidation(activation, provider, key, state);
        }
    }

    private void publishInvalidation(CatalogRuntimeActivation.ActivationRecord activation, OptionCatalogProvider provider,
                                     SourceKey key, ProviderState state) {
        String hash = CanonicalJson.sha256("option.invalidation", Map.of("publication", key.publicationKey().canonicalText(),
            "sourceRef", key.sourceRef().canonicalText(), "query", key.query().canonicalText(),
            "providerSourceId", key.providerSourceId(), "providerSourceEpoch", state.epoch()));
        long revision = Long.parseLong(hash.substring(0, 15), 16);
        String invalidationKey = "provider-source:" + hash;
        Collection<Session> sessions;
        try {
            sessions = List.copyOf(sessionSupplier.get());
        } catch (RuntimeException exception) {
            return;
        }
        for (Session session : sessions) {
            CatalogCachePublication publication;
            long sequence;
            long authorityEpoch;
            try {
                Optional<CatalogCachePublication> current = publicationSupplier.apply(session);
                if (current.isEmpty()) {
                    continue;
                }
                publication = current.orElseThrow();
                authorityEpoch = authorityEpochSupplier.getAsLong();
                if (!eventCurrent(session, activation, publication, provider, key, state, authorityEpoch)) {
                    continue;
                }
                synchronized (stateFence) {
                    if (closed.get() || activationCache == null || activationCache.activation() != activation
                        || providerStates.get(key) != state || providers.provider(key.providerSourceId()) != provider) {
                        continue;
                    }
                    sequence = sessionSequences.merge(session, 1L,
                        (currentSequence, ignored) -> currentSequence == Long.MAX_VALUE ? 1L : currentSequence + 1L);
                }
            } catch (RuntimeException exception) {
                continue;
            }
            CatalogBinding binding = publication.catalogBinding();
            OptionInvalidation invalidation = new OptionInvalidation(key.sourceRef(), key.query(), serverId, null, revision,
                invalidationKey, Set.of());
            ProtocolEnvelope<Map<String, Object>> envelope = new ProtocolEnvelope<>(ProtocolEnvelope.Kind.EVENT,
                publication.authoringPublication().contractVersion(), UUID.randomUUID(), null, UUID.randomUUID(), UUID.randomUUID(),
                serverId, null, 0L, authorityEpoch, null, OptionQueryAuthority.OPERATION,
                Set.of(OptionQueryAuthority.PROTOCOL_CAPABILITY), OptionQueryAuthority.PAGE_TYPE, null, null, false,
                publication.authoringPublication().contractVersion(), binding.catalogChecksum(), binding.bindingManifestHash(),
                null, null, sequence, ProtocolEnvelope.Status.OK, List.of(), Map.of(),
                new ProtocolBody.OptionInvalidationEvent(invalidation, Map.of()));
            try {
                if (!eventCurrent(session, activation, publication, provider, key, state, authorityEpoch)) {
                    continue;
                }
                EventFence fence = () -> eventCurrent(session, activation, publication, provider, key, state, authorityEpoch);
                eventSender.send(session, envelope, fence);
            } catch (RuntimeException exception) {
                continue;
            }
        }
    }

    public void resetSession(Session session) {
        if (session != null) {
            synchronized (stateFence) {
                sessionSequences.remove(session);
            }
        }
    }

    @Override
    public void close() {
        synchronized (stateFence) {
            closed.set(true);
            providerStates.clear();
            sessionSequences.clear();
            activationCache = null;
        }
    }

    int providerStateCount() {
        synchronized (stateFence) {
            return providerStates.size();
        }
    }

    private Access requireAccess(ConnectionInfo connection, Session session,
                                 ProtocolEnvelope<Map<String, Object>> envelope, OptionQuery request) {
        requireOpen();
        Objects.requireNonNull(connection, "Option query connection is required");
        Objects.requireNonNull(session, "Option query session is required");
        Objects.requireNonNull(envelope, "Option query envelope is required");
        Objects.requireNonNull(request, "Option query is required");
        if (session.getConnection() != connection || !sessionCurrent.test(session)
            || !connection.hasNegotiatedFlowCapability(OptionQueryAuthority.PROTOCOL_CAPABILITY.id().value())
            || !optionAuthorizer.authorize(connection, session, envelope, request)) {
            throw rejected(ProtocolRejectionCode.AUTHORIZATION_DENIED,
                "Option query is not authorized for the current negotiated session");
        }
        if (envelope.revision() != 0L || !serverId.equals(request.serverId()) || !serverId.equals(envelope.serverId())) {
            throw rejected(ProtocolRejectionCode.AUTHORIZATION_DENIED, "Option query is owned by another server or wire revision");
        }
        CatalogRuntimeActivation.ActivationRecord activation = activeActivation();
        CatalogCachePublication publication = activePublication(session);
        CatalogBinding binding = exactPublication(envelope, activation, publication);
        Binding source = binding(activation, request.sourceRef(), request.query());
        requirePublishedSource(activation, publication, request.sourceRef(), request.query());
        OptionQuerySchemaV1.Normalized normalized;
        try {
            normalized = source.descriptor().querySchema().normalize(serverId, request.resource(), request.context(), request.dependencies());
        } catch (RuntimeException exception) {
            throw rejected(ProtocolRejectionCode.INVALID_PAYLOAD,
                "Option query context does not match its published schema", exception);
        }
        if (source.descriptor().optionType() instanceof TypeExpr.ResourceType) {
            OptionQueryAuthority.Source authoritySource = new OptionQueryAuthority.Source(request.sourceRef(), source.descriptor(),
                source.providerSourceId(), 1L, List.of());
            ContractRef<ResourceTypeId> type = adapter.requireResourceType(authoritySource);
            ResourceQueryRequest authorization = new ResourceQueryRequest(type, Map.of(), request.cursor(), request.limit(), request.search());
            if (!resourceAuthorizer.authorize(connection, session, envelope, authorization)) {
                throw rejected(ProtocolRejectionCode.AUTHORIZATION_DENIED, "Option resource query is not authorized");
            }
        }
        return new Access(activation, publication, binding, source, normalized);
    }

    private void requirePublishedSource(CatalogRuntimeActivation.ActivationRecord activation,
                                        CatalogCachePublication publication,
                                        ContractRef<InspectorFieldId> sourceRef,
                                        ContractRef<CapabilityId> query) {
        CatalogAuthoringPublication authoring = publication.authoringPublication();
        CatalogAuthoringPublication.SectionProjection section = authoring.section(CatalogAuthoringPublication.Section.OPTION_SOURCES);
        if (!authoring.compatible() || section == null || !section.present() || !section.acknowledged()
            || section.state() != CatalogCacheState.ACTIVE) {
            throw rejected(ProtocolRejectionCode.RESOURCE_TYPE_UNAVAILABLE,
                "Option source is not active in the acknowledged authoring publication");
        }
        CatalogAuthoringPublication.Entry entry = section.entries().stream()
            .filter(candidate -> sourceRef.canonicalText().equals(candidate.key()))
            .findFirst().orElseThrow(() -> rejected(ProtocolRejectionCode.RESOURCE_TYPE_UNAVAILABLE,
                "Option source is missing from the acknowledged authoring publication"));
        String expected = canonicalSourceData(activation, sourceRef);
        if (entry.state() != CatalogCacheState.ACTIVE || entry.opaque() || !entry.editable()
            || !entry.requiredCapabilities().contains(query) || !entry.canonicalData().equals(expected)) {
            throw rejected(ProtocolRejectionCode.RESOURCE_TYPE_UNAVAILABLE,
                "Option source grant does not match the active canonical source");
        }
    }

    private String canonicalSourceData(CatalogRuntimeActivation.ActivationRecord activation,
                                       ContractRef<InspectorFieldId> sourceRef) {
        synchronized (stateFence) {
            ActivationCache cache = requireCurrentActivationLocked(activation);
            String data = cache.canonicalSources().get(sourceRef.canonicalText());
            if (data == null) {
                throw rejected(ProtocolRejectionCode.RESOURCE_TYPE_UNAVAILABLE,
                    "Canonical option source is unavailable");
            }
            return data;
        }
    }

    private ActivationCache requireCurrentActivationLocked(CatalogRuntimeActivation.ActivationRecord activation) {
        if (closed.get()) {
            throw rejected(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE, "Option query service is closed");
        }
        CatalogRuntimeActivation.ActivationRecord current = activationSupplier.get();
        if (current != activation) {
            if (activationCache == null || activationCache.activation() != current) {
                providerStates.clear();
                activationCache = current == null ? null : buildActivationCache(current);
            }
            throw rejected(ProtocolRejectionCode.RESOURCE_REVISION_CONFLICT,
                "Option catalog activation changed");
        }
        if (activationCache == null || activationCache.activation() != activation) {
            providerStates.clear();
            activationCache = buildActivationCache(activation);
        }
        return activationCache;
    }

    private ActivationCache buildActivationCache(CatalogRuntimeActivation.ActivationRecord activation) {
        Set<ContractRef<CapabilityId>> capabilities = new LinkedHashSet<>();
        activation.catalog().capabilities().forEach(owned -> {
            @SuppressWarnings("unchecked")
            ContractRef<CapabilityId> capability = (ContractRef<CapabilityId>) owned.key();
            capabilities.add(capability);
        });
        CatalogAuthoringPublication canonical = CatalogAuthoringPublication.project(activation.catalog(), capabilities);
        Map<String, String> sources = new LinkedHashMap<>();
        canonical.optionSources().forEach(entry -> sources.put(entry.key(), entry.canonicalData()));
        return new ActivationCache(activation, Map.copyOf(sources));
    }

    private CatalogRuntimeActivation.ActivationRecord activeActivation() {
        CatalogRuntimeActivation.ActivationRecord activation = activationSupplier.get();
        if (activation == null || activation.publicationKey().isEmpty()) {
            throw rejected(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE, "Active catalog publication is unavailable");
        }
        return activation;
    }

    private CatalogCachePublication activePublication(Session session) {
        return publicationSupplier.apply(session).orElseThrow(() -> rejected(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE,
            "Session has not acknowledged the active catalog publication"));
    }

    private CatalogBinding exactPublication(ProtocolEnvelope<Map<String, Object>> envelope,
                                            CatalogRuntimeActivation.ActivationRecord activation,
                                            CatalogCachePublication publication) {
        if (!activation.publicationKey().orElseThrow().equals(publication.key()) || !publication.hasAuthoringPublication()) {
            throw rejected(ProtocolRejectionCode.RESOURCE_REVISION_CONFLICT, "Session catalog publication is stale");
        }
        CatalogBinding binding = publication.catalogBinding();
        if (binding == null || envelope.selectedVersion() == null || envelope.catalogChecksum() == null
            || envelope.bindingManifestHash() == null
            || !publication.authoringPublication().contractVersion().equals(envelope.selectedVersion())
            || !binding.catalogChecksum().equals(envelope.catalogChecksum())
            || !binding.bindingManifestHash().equals(envelope.bindingManifestHash())) {
            throw rejected(ProtocolRejectionCode.RESOURCE_REVISION_CONFLICT,
                "Option query does not acknowledge the active catalog publication");
        }
        return binding;
    }

    private Binding binding(CatalogRuntimeActivation.ActivationRecord activation,
                            ContractRef<InspectorFieldId> sourceRef, ContractRef<CapabilityId> query) {
        CoreOptionCatalogSupport.Binding binding = CoreOptionCatalogSupport.binding(activation.catalog(), sourceRef, query);
        return new Binding(binding.descriptor(), binding.providerSourceId());
    }

    private OptionCatalogQuery providerQuery(String sourceId, OptionQuerySchemaV1.Normalized normalized) {
        Map<String, Object> context = new LinkedHashMap<>();
        normalized.context().forEach((key, value) -> context.put(key, material(value)));
        normalized.dependencies().forEach((key, value) -> context.put(key, material(value)));
        if (normalized.resource() != null) {
            context.put("$resource", normalized.resource().canonicalText());
        }
        return new OptionCatalogQuery(sourceId, context);
    }

    private Object material(TypedValue value) {
        return value.locator() != null ? value.locator().canonicalText() : value.value();
    }

    private ProviderState state(CatalogRuntimeActivation.ActivationRecord activation, OptionCatalogProvider provider,
                                SourceKey key, ProviderState expected, String revision) {
        synchronized (stateFence) {
            requireCurrentActivationLocked(activation);
            if (providers.provider(key.providerSourceId()) != provider || providerStates.get(key) != expected) {
                throw rejected(ProtocolRejectionCode.RESOURCE_REVISION_CONFLICT,
                    "Option provider changed while its authoritative state was captured");
            }
            ProviderState state = expected == null ? new ProviderState(1L, revision)
                : Objects.equals(expected.revision(), revision) ? expected : expected.next(revision);
            providerStates.put(key, state);
            return state;
        }
    }

    private void fence(Session session, ProtocolEnvelope<Map<String, Object>> envelope,
                       OptionQuery request,
                       CatalogRuntimeActivation.ActivationRecord activation, CatalogCachePublication publication,
                       OptionCatalogProvider provider, SourceKey key, ProviderState state) {
        Access current;
        try {
            current = requireAccess(session.getConnection(), session, envelope, request);
        } catch (RuntimeException exception) {
            throw rejected(ProtocolRejectionCode.RESOURCE_REVISION_CONFLICT,
                "Option query authority changed while the provider was captured", exception);
        }
        synchronized (stateFence) {
            requireCurrentActivationLocked(activation);
            if (current.activation() == activation && current.publication().equals(publication)
                && current.source().providerSourceId().equals(key.providerSourceId())
                && providers.provider(key.providerSourceId()) == provider && providerStates.get(key) == state) {
                return;
            }
        }
        throw rejected(ProtocolRejectionCode.RESOURCE_REVISION_CONFLICT,
            "Option query authority changed while the provider was captured");
    }

    private boolean eventCurrent(Session session, CatalogRuntimeActivation.ActivationRecord activation,
                                 CatalogCachePublication publication, OptionCatalogProvider provider,
                                 SourceKey key, ProviderState state, long authorityEpoch) {
        try {
            ConnectionInfo connection = session.getConnection();
            OptionQuery authorization = new OptionQuery(key.sourceRef(), key.query(), serverId, null, Map.of(), Map.of(),
                null, 1, null, 0L, "event");
            ProtocolEnvelope<Map<String, Object>> envelope = eventAuthorityEnvelope(publication, authorization, authorityEpoch);
            if (!sessionCurrent.test(session)
                || !connection.hasNegotiatedFlowCapability(OptionQueryAuthority.PROTOCOL_CAPABILITY.id().value())
                || !optionAuthorizer.authorize(connection, session, envelope, authorization)
                || authorityEpochSupplier.getAsLong() != authorityEpoch
                || !publicationSupplier.apply(session).filter(publication::equals).isPresent()
                || activationSupplier.get() != activation) {
                return false;
            }
            requirePublishedSource(activation, publication, key.sourceRef(), key.query());
            synchronized (stateFence) {
                requireCurrentActivationLocked(activation);
                return providers.provider(key.providerSourceId()) == provider && providerStates.get(key) == state;
            }
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private ProtocolEnvelope<Map<String, Object>> eventAuthorityEnvelope(CatalogCachePublication publication,
                                                                          OptionQuery query, long authorityEpoch) {
        CatalogBinding binding = publication.catalogBinding();
        OptionInvalidation invalidation = new OptionInvalidation(query.sourceRef(), query.query(), serverId, null, 1L,
            "event", Set.of());
        return new ProtocolEnvelope<>(ProtocolEnvelope.Kind.EVENT,
            publication.authoringPublication().contractVersion(), UUID.randomUUID(), null, UUID.randomUUID(), UUID.randomUUID(),
            serverId, null, 0L, authorityEpoch, null, OptionQueryAuthority.OPERATION,
            Set.of(OptionQueryAuthority.PROTOCOL_CAPABILITY), OptionQueryAuthority.PAGE_TYPE, null, null, false,
            publication.authoringPublication().contractVersion(), binding.catalogChecksum(), binding.bindingManifestHash(),
            null, null, 0L, ProtocolEnvelope.Status.OK, List.of(), Map.of(),
            new ProtocolBody.OptionInvalidationEvent(invalidation, Map.of()));
    }

    private void requireOpen() {
        if (closed.get()) {
            throw rejected(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE, "Option query service is closed");
        }
    }

    private OptionQueryAuthority.Rejected rejected(ProtocolRejectionCode code, String message) {
        return new OptionQueryAuthority.Rejected(code, message);
    }

    private OptionQueryAuthority.Rejected rejected(ProtocolRejectionCode code, String message, Throwable cause) {
        return new OptionQueryAuthority.Rejected(code, message, cause);
    }

    private static boolean currentSession(Supplier<? extends Collection<Session>> sessions, Session expected) {
        Collection<Session> current;
        try {
            current = sessions.get();
        } catch (RuntimeException exception) {
            return false;
        }
        if (current == null) {
            return false;
        }
        for (Session candidate : current) {
            if (candidate == expected) {
                return true;
            }
        }
        return false;
    }

    @FunctionalInterface
    public interface EventFence {
        boolean current();
    }

    @FunctionalInterface
    public interface EventSender {
        void send(Session session, ProtocolEnvelope<Map<String, Object>> envelope, EventFence fence);
    }

    public record Result(OptionPage page, CatalogVersion selectedVersion, CatalogBinding binding) {
        public Result {
            page = Objects.requireNonNull(page, "Option page is required");
            selectedVersion = Objects.requireNonNull(selectedVersion, "Option catalog contract version is required");
            binding = Objects.requireNonNull(binding, "Option catalog binding is required");
        }
    }

    private record Access(CatalogRuntimeActivation.ActivationRecord activation, CatalogCachePublication publication,
                          CatalogBinding binding, Binding source, OptionQuerySchemaV1.Normalized normalized) {
    }

    private record ActivationCache(CatalogRuntimeActivation.ActivationRecord activation,
                                   Map<String, String> canonicalSources) {
    }

    private record Binding(InspectorOptionSource descriptor, String providerSourceId) {
    }

    private record SourceKey(CatalogCacheKey publicationKey, ContractRef<InspectorFieldId> sourceRef,
                             ContractRef<CapabilityId> query, String providerSourceId) {
    }

    private record ProviderState(long epoch, String revision) {
        private ProviderState next(String nextRevision) {
            return new ProviderState(epoch == Long.MAX_VALUE ? 1L : epoch + 1L, nextRevision);
        }
    }
}
