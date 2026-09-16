package restudio.resync.api;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowTypeRef;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.FlowValueCodec;
import restudio.resync.flow.FlowValueCodecRegistry;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.flow.handler.event.FlowEventRegistry;
import restudio.resync.flow.handler.property.PropertyRegistry;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.triggers.TriggerDispatcher;
import restudio.resync.flow.validation.FlowGraphValidationRegistry;
import restudio.resync.flow.validation.FlowGraphValidationRule;
import restudio.resync.flow.identity.PinId;
import restudio.resync.modules.flow.FlowResourceAdapter;
import restudio.resync.modules.flow.FlowResourceRegistry;
import restudio.resync.resources.ReSyncManagedResource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionRegistryActivationTest {
    @Test
    void boundDefinitionReadsCopyOnlyTheSelectedDefinition() {
        AtomicInteger registryCopies = new AtomicInteger();
        CountingDefinitions source = new CountingDefinitions(registryCopies);
        List<NodeDefinition> nodes = new ArrayList<>();
        for (int index = 0; index < 1322; index++) {
            nodes.add(new NodeDefinition.Builder("node-" + index, "Node", NodeDefinition.NodeCategory.ACTION)
                .owner("alpha").build());
        }
        source.registerAll("alpha", nodes);
        ExtensionRegistryActivation.State initial = ExtensionRegistryActivation.capture(1, source,
            null, null, null, null, null, null, null, null, null, null, null);
        ExtensionRegistryActivation activation = new ExtensionRegistryActivation(initial);
        NodeDefinitionRegistry facade = new NodeDefinitionRegistry(false);
        activation.bind(facade, null, null, null, null, null, null, null, null, null, null, null);
        registryCopies.set(0);

        for (int index = 0; index < 20; index++) {
            assertEquals("alpha", facade.get("node-0").getOwner());
            NodeDefinition selected = facade.get("alpha", "node-0");
            selected.assignOwner("mutated");
            assertEquals("alpha", facade.get("alpha", "node-0").getOwner());
        }
        assertEquals(0, registryCopies.get(), "Targeted reads must not copy the complete published catalog");

        facade.register("beta", new NodeDefinition.Builder("node-0", "Other", NodeDefinition.NodeCategory.ACTION)
            .owner("beta").build());
        assertNull(facade.get("node-0"));
        assertEquals("alpha", facade.get("alpha", "node-0").getOwner());
        assertEquals("beta", facade.get("beta", "node-0").getOwner());
        assertEquals("alpha", initial.nodeDefinitions().get("node-0").getOwner());
    }

    private static final class CountingDefinitions extends NodeDefinitionRegistry {
        private final AtomicInteger copies;

        private CountingDefinitions(AtomicInteger copies) {
            super(false);
            this.copies = copies;
        }

        @Override
        public synchronized NodeDefinitionRegistry copy() {
            copies.incrementAndGet();
            CountingDefinitions copy = new CountingDefinitions(copies);
            copy.replaceFrom(super.copy());
            return copy;
        }
    }

    @Test
    void semanticNoOpDoesNotPublishAnotherAggregate() {
        ExtensionRegistryActivation.State first = state(1, "alpha");
        ExtensionRegistryActivation activation = new ExtensionRegistryActivation(first);
        ExtensionRegistryActivation.State equivalent = ExtensionRegistryActivation.capture(2,
            first.nodeDefinitions(), first.handlers(), first.properties(), first.optionCatalogs(), first.runtimeData(),
            first.valueCodecs(), first.typeAdapters(), first.validators(), first.resources(), first.extensionData(),
            first.events(), first.flowRegistry());

        ExtensionRegistryActivation.State published = activation.publish(equivalent);

        assertEquals(1, published.generation());
        assertEquals(first, published);
        assertEquals(1, activation.snapshot().generation());
    }

    @Test
    void catalogRegistrationFingerprintDoesNotReadValuesAndStillTracksProviderReplacement() {
        RuntimeDataRegistry runtime = new RuntimeDataRegistry();
        OptionCatalogRegistry catalogs = new OptionCatalogRegistry(runtime);
        OptionCatalogProvider firstProvider = unavailableCatalog();
        assertTrue(catalogs.register(firstProvider));
        ExtensionRegistryActivation.State first = ExtensionRegistryActivation.capture(1, null, null, null,
            catalogs, runtime, null, null, null, null, null, null, null);
        ExtensionRegistryActivation.State equivalent = ExtensionRegistryActivation.capture(2, null, null, null,
            first.optionCatalogs(), first.runtimeData(), null, null, null, null, null, null, null);
        assertTrue(first.semanticallyEquals(equivalent));
        assertEquals(firstProvider, first.optionCatalogs().provider("test:unavailable"));

        catalogs.unregister("test:unavailable");
        assertTrue(catalogs.register(unavailableCatalog()));
        ExtensionRegistryActivation.State replacement = ExtensionRegistryActivation.capture(2, null, null, null,
            catalogs, runtime, null, null, null, null, null, null, null);
        assertFalse(first.semanticallyEquals(replacement));
        assertThrows(OptionCatalogRegistry.CaptureUnavailable.class,
            () -> first.optionCatalogs().values("test:unavailable", null));
    }

    private static OptionCatalogProvider unavailableCatalog() {
        return new OptionCatalogProvider() {
            @Override
            public String sourceId() {
                return "test:unavailable";
            }

            @Override
            public String revision() {
                throw new AssertionError("Registration must not query a catalog revision");
            }

            @Override
            public List<String> values() {
                throw new AssertionError("Registration must not query catalog values");
            }

            @Override
            public OptionCatalogCapture capture(OptionCatalogQuery query) {
                throw new AssertionError("Unsupported providers must not be captured");
            }
        };
    }

    @Test
    void sameShapeDirectImplementationsHaveDifferentIncarnations() {
        ExtensionRegistryActivation.State first = fullState(1, "alpha");
        ExtensionRegistryActivation.State replacement = fullState(2, "alpha");

        assertFalse(first.semanticallyEquals(replacement));
    }

    @Test
    void definitionContractChangesPublishWhenOnlyPinLabelsRuntimeNamesOrMigrationChange() {
        ExtensionRegistryActivation.State base = definitionFingerprintState(fingerprintDefinition("Value", "value", null));
        List<ExtensionRegistryActivation.State> candidates = List.of(
            definitionFingerprintState(fingerprintDefinition("Label", "value", null)),
            definitionFingerprintState(fingerprintDefinition("Value", "runtime_value", null)),
            definitionFingerprintState(fingerprintDefinition("Value", "value", migrationMapping()))
        );

        for (ExtensionRegistryActivation.State candidate : candidates) {
            assertFalse(base.semanticallyEquals(candidate));
            ExtensionRegistryActivation activation = new ExtensionRegistryActivation(base);
            assertEquals(candidate, activation.publish(candidate));
            assertEquals(candidate, activation.snapshot());
        }
    }

    @Test
    void defaultPluginIdentityPublishesThroughTheBoundFacade() {
        ExtensionRegistryActivation.State initial = state(1, "alpha");
        ExtensionRegistryActivation activation = new ExtensionRegistryActivation(initial);
        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry(false);
        activation.bind(definitions, null, null, null, null, null, null, null, null, null, null, null);

        long generation = activation.snapshot().generation();
        definitions.setDefaultPluginId("replacement");

        assertEquals(generation + 1, activation.snapshot().generation());
        assertEquals("replacement", activation.snapshot().nodeDefinitions().defaultPluginId());
    }

    @Test
    void definitionProjectionPublishesNodePropertyAndEventSlicesAsOneAggregate() {
        ExtensionRegistryActivation.State initial = definitionProjectionState(7, "alpha");
        ExtensionRegistryActivation activation = new ExtensionRegistryActivation(initial);
        AtomicInteger notifications = new AtomicInteger();
        AtomicReference<ExtensionRegistryActivation.State> observed = new AtomicReference<>();
        activation.addListener(state -> {
            notifications.incrementAndGet();
            observed.set(state);
        });

        ExtensionRegistryActivation.State published = activation.publishDefinitionProjection(initial,
            projectedDefinitions("beta"));

        assertEquals(2, notifications.get());
        assertEquals(8, published.generation());
        assertEquals(published, observed.get());
        assertEquals("beta", published.nodeDefinitions().get("shared").getOwner());
        assertFalse(published.properties().hasProperty("player", "alpha_value"));
        assertTrue(published.properties().hasProperty("player", "beta_value"));
        assertFalse(published.events().getEventDefinitions().containsKey("alpha:event"));
        assertTrue(published.events().getEventDefinitions().containsKey("beta:event"));
    }

    @Test
    void staleDefinitionProjectionCannotReplaceAConcurrentAggregate() {
        ExtensionRegistryActivation.State initial = definitionProjectionState(3, "alpha");
        ExtensionRegistryActivation.State concurrent = definitionProjectionState(4, "gamma");
        ExtensionRegistryActivation activation = new ExtensionRegistryActivation(initial);
        activation.publish(concurrent);

        assertThrows(ExtensionRegistryActivation.StaleProjectionException.class,
            () -> activation.publishDefinitionProjection(initial, projectedDefinitions("beta")));
        assertEquals(concurrent, activation.snapshot());
    }

    @Test
    void writerAndListenerRegistrationWaitForPublishedListenerEffects() throws Exception {
        ExtensionRegistryActivation.State alpha = state(1, "alpha");
        ExtensionRegistryActivation.State beta = state(2, "beta");
        ExtensionRegistryActivation activation = new ExtensionRegistryActivation(alpha);
        CountDownLatch listenerEntered = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        CountDownLatch secondWriterFinished = new CountDownLatch(1);
        CountDownLatch registrationFinished = new CountDownLatch(1);
        activation.addListener(candidate -> {
            if (candidate == beta) {
                listenerEntered.countDown();
                await(releaseListener);
            }
        });
        Thread firstWriter = new Thread(() -> activation.publish(beta));
        firstWriter.start();
        assertTrue(listenerEntered.await(5, TimeUnit.SECONDS));

        Thread secondWriter = new Thread(() -> {
            activation.publish(alpha);
            secondWriterFinished.countDown();
        });
        Thread registration = new Thread(() -> {
            activation.addListener(ignored -> {
            });
            registrationFinished.countDown();
        });
        secondWriter.start();
        registration.start();

        assertFalse(secondWriterFinished.await(100, TimeUnit.MILLISECONDS));
        assertFalse(registrationFinished.await(100, TimeUnit.MILLISECONDS));
        releaseListener.countDown();
        firstWriter.join();
        secondWriter.join();
        registration.join();

        assertEquals(0, secondWriterFinished.getCount());
        assertEquals(0, registrationFinished.getCount());
    }

    @Test
    void concurrentReadersObserveOneRegistryGeneration() throws Exception {
        ExtensionRegistryActivation.State alpha = state(1, "alpha");
        ExtensionRegistryActivation.State beta = state(2, "beta");
        ExtensionRegistryActivation activation = new ExtensionRegistryActivation(alpha);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean done = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread writer = new Thread(() -> {
            await(start);
            try {
                for (int index = 0; index < 10_000; index++) {
                    ExtensionRegistryActivation.State next = (index & 1) == 0 ? alpha : beta;
                    while (true) {
                        ExtensionRegistryActivation.State current = activation.snapshot();
                        if (activation.compareAndSet(current, next)) {
                            break;
                        }
                    }
                }
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            } finally {
                done.set(true);
            }
        });
        Thread reader = new Thread(() -> {
            await(start);
            try {
                int reads = 0;
                while (!done.get() || reads < 10_000) {
                    ExtensionRegistryActivation.State current = activation.snapshot();
                    String nodeOwner = current.nodeDefinitions().get("shared").getOwner();
                    String handlerId = current.handlers().getHandlerIds().iterator().next();
                    assertEquals(nodeOwner, handlerId);
                    assertTrue(nodeOwner.equals("alpha") || nodeOwner.equals("beta"));
                    reads++;
                }
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }
        });
        writer.start();
        reader.start();
        start.countDown();
        writer.join();
        reader.join();

        assertNull(failure.get(), failure.get() != null ? failure.get().toString() : "");
        assertFalse(activation.snapshot().fingerprint().isEmpty());
    }

    @Test
    void boundFacadesReadOneAggregateGenerationUnderConcurrentSwaps() throws Exception {
        ExtensionRegistryActivation.State alpha = state(1, "alpha");
        ExtensionRegistryActivation.State beta = state(2, "beta");
        ExtensionRegistryActivation activation = new ExtensionRegistryActivation(alpha);
        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry(false);
        HandlerRegistry handlers = new HandlerRegistry();
        activation.bind(definitions, handlers, null, null, null, null, null, null, null, null, null, null);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean done = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread writer = new Thread(() -> {
            await(start);
            try {
                for (int index = 0; index < 25_000; index++) {
                    ExtensionRegistryActivation.State next = (index & 1) == 0 ? alpha : beta;
                    for (;;) {
                        ExtensionRegistryActivation.State current = activation.snapshot();
                        if (activation.compareAndSet(current, next)) {
                            break;
                        }
                    }
                }
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            } finally {
                done.set(true);
            }
        });
        Thread reader = new Thread(() -> {
            await(start);
            try {
                int reads = 0;
                while (!done.get() || reads < 25_000) {
                    activation.readConsistent(state -> {
                        NodeDefinition definition = definitions.get("shared");
                        assertTrue(definition != null);
                        String owner = definition.getOwner();
                        assertTrue(owner.equals("alpha") || owner.equals("beta"));
                        assertTrue(handlers.getHandler(owner) != null);
                        return null;
                    });
                    reads++;
                }
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }
        });
        writer.start();
        reader.start();
        start.countDown();
        writer.join();
        reader.join();

        assertNull(failure.get(), failure.get() != null ? failure.get().toString() : "");
    }

    @Test
    void allBoundFacadesReadOneAggregateGenerationForTwentyFiveThousandSwaps() throws Exception {
        ExtensionRegistryActivation.State alpha = fullState(1, "alpha");
        ExtensionRegistryActivation.State beta = fullState(2, "beta");
        ExtensionRegistryActivation activation = new ExtensionRegistryActivation(alpha);
        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry(false);
        HandlerRegistry handlers = new HandlerRegistry();
        PropertyRegistry properties = new PropertyRegistry();
        RuntimeDataRegistry runtimeData = new RuntimeDataRegistry();
        OptionCatalogRegistry catalogs = new OptionCatalogRegistry(runtimeData);
        FlowValueCodecRegistry codecs = new FlowValueCodecRegistry();
        TypeAdapterRegistry adapters = new TypeAdapterRegistry();
        FlowGraphValidationRegistry validators = new FlowGraphValidationRegistry();
        FlowResourceRegistry resources = new FlowResourceRegistry();
        ReSyncExtensionData extensionData = new ReSyncExtensionData();
        FlowEventRegistry events = new FlowEventRegistry(null, adapters).copyForStaging(adapters);
        FlowRegistry flows = new FlowRegistry();
        activation.bind(definitions, handlers, properties, catalogs, runtimeData, codecs, adapters, validators,
            resources, extensionData, events, flows);

        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean done = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            await(start);
            try {
                for (int index = 0; index < 25_000; index++) {
                    ExtensionRegistryActivation.State next = (index & 1) == 0 ? alpha : beta;
                    for (;;) {
                        ExtensionRegistryActivation.State current = activation.snapshot();
                        if (activation.compareAndSet(current, next)) {
                            break;
                        }
                    }
                }
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            } finally {
                done.set(true);
            }
        });
        Thread reader = new Thread(() -> {
            await(start);
            try {
                int reads = 0;
                while (!done.get() || reads < 25_000) {
                    activation.readConsistent(state -> {
                        String owner = definitions.get("shared").getOwner();
                        assertTrue(owner.equals("alpha") || owner.equals("beta"));
                        assertEquals(owner, handlers.getHandlerIds().stream().findFirst().orElseThrow());
                        assertTrue(properties.hasProperty(owner, "value"));
                        assertNotNull(catalogs.provider(owner + ":catalog"));
                        assertNotNull(runtimeData.adapter(owner + ":runtime"));
                        assertTrue(codecs.codecs().containsKey(owner + "_codec"));
                        assertTrue(adapters.getAdapters().keySet().stream()
                            .anyMatch(pair -> pair.getTarget().getSimpleName().equals(owner.equals("alpha") ? "AlphaValue" : "BetaValue")));
                        assertTrue(validators.contains(owner + ":validator"));
                        assertNotNull(resources.get(owner + ":resource"));
                        assertTrue(extensionData.pluginIds().contains(owner));
                        assertTrue(events.getEventDefinitions().containsKey(owner + ":event"));
                        assertTrue(flows.getRegisteredTypes().contains(owner));
                        return null;
                    });
                    reads++;
                }
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }
        });
        writer.start();
        reader.start();
        start.countDown();
        writer.join();
        reader.join();

        assertNull(failure.get(), failure.get() != null ? failure.get().toString() : "");
    }

    @Test
    void listenerProjectionFailureRestoresThePreviousAggregate() {
        ExtensionRegistryActivation.State alpha = state(1, "alpha");
        ExtensionRegistryActivation.State beta = state(2, "beta");
        ExtensionRegistryActivation activation = new ExtensionRegistryActivation(alpha);
        AtomicBoolean fail = new AtomicBoolean(true);
        activation.addListener(candidate -> {
            if (fail.get() && candidate.nodeDefinitions().get("shared").getOwner().equals("beta")) {
                throw new IllegalStateException("projection failure");
            }
        });

        assertThrows(IllegalStateException.class, () -> activation.publish(beta));
        assertEquals("alpha", activation.snapshot().nodeDefinitions().get("shared").getOwner());
        fail.set(false);
        assertEquals(beta, activation.publish(beta));
    }

    @Test
    void doubleProjectionFailureLeavesActivationFailClosed() {
        ExtensionRegistryActivation.State alpha = state(1, "alpha");
        ExtensionRegistryActivation.State beta = state(2, "beta");
        ExtensionRegistryActivation activation = new ExtensionRegistryActivation(alpha);
        AtomicBoolean failCandidate = new AtomicBoolean();
        AtomicBoolean failRollback = new AtomicBoolean();
        activation.addListener(candidate -> {
            String owner = candidate.nodeDefinitions().get("shared").getOwner();
            if (owner.equals("beta") && failCandidate.get()) {
                throw new IllegalStateException("candidate projection failure");
            }
            if (owner.equals("alpha") && failRollback.get()) {
                throw new IllegalStateException("rollback projection failure");
            }
        });

        failCandidate.set(true);
        failRollback.set(true);

        assertThrows(IllegalStateException.class, () -> activation.publish(beta));
        assertTrue(activation.hasProjectionFailure());
        assertEquals("alpha", activation.snapshot().nodeDefinitions().get("shared").getOwner());
        assertThrows(IllegalStateException.class, () -> activation.publish(beta));
    }

    @Test
    void flowEventProjectionDoubleFailureRetainsThePreviousGenerationAndFailsClosed() {
        ExtensionRegistryActivation.State alpha = fullState(1, "alpha");
        ExtensionRegistryActivation.State beta = fullState(2, "beta");
        AtomicBoolean failCandidate = new AtomicBoolean();
        AtomicBoolean failRollback = new AtomicBoolean();
        TriggerDispatcher dispatcher = new TriggerDispatcher(null, null, null) {
            @Override
            public synchronized void replaceManagedDefinitions(Set<String> managedNodeTypes,
                                                                Collection<ManagedDefinition> replacements) {
                boolean candidate = replacements.stream().anyMatch(value -> "beta/event".equals(value.nodeType()));
                boolean rollback = replacements.stream().anyMatch(value -> "alpha/event".equals(value.nodeType()));
                if (candidate && failCandidate.get()) {
                    throw new IllegalStateException("candidate dispatcher projection failure");
                }
                if (rollback && failRollback.get()) {
                    throw new IllegalStateException("rollback dispatcher projection failure");
                }
            }
        };
        FlowEventRegistry events = new FlowEventRegistry(dispatcher, new TypeAdapterRegistry());
        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry(false);
        HandlerRegistry handlers = new HandlerRegistry();
        ExtensionRegistryActivation activation = new ExtensionRegistryActivation(alpha);
        activation.bind(definitions, handlers, null, null, null, null, null, null, null, null, events, null);

        failCandidate.set(true);
        failRollback.set(true);

        assertThrows(IllegalStateException.class, () -> activation.publish(beta));
        assertTrue(activation.hasProjectionFailure());
        assertEquals(1, activation.snapshot().generation());
        assertThrows(IllegalStateException.class, () -> activation.publish(beta));
    }

    @Test
    void closedFlowEventActivationRejectsLateAggregatePublicationsAndRebinds() {
        ExtensionRegistryActivation.State alpha = fullState(1, "alpha");
        ExtensionRegistryActivation.State beta = fullState(2, "beta");
        AtomicInteger projections = new AtomicInteger();
        TriggerDispatcher dispatcher = new TriggerDispatcher(null, null, null) {
            @Override
            public synchronized void replaceManagedDefinitions(Set<String> managedNodeTypes,
                                                                Collection<ManagedDefinition> replacements) {
                projections.incrementAndGet();
            }
        };
        FlowEventRegistry events = new FlowEventRegistry(dispatcher, new TypeAdapterRegistry());
        ExtensionRegistryActivation activation = new ExtensionRegistryActivation(alpha);

        events.bindActivation(activation);
        events.closeActivation();
        activation.publish(beta);
        events.bindActivation(activation);
        activation.publish(alpha);

        assertEquals(1, projections.get());
    }

    @Test
    void stateSlicesAreDetachedFromCandidateRegistries() {
        NodeDefinitionRegistry source = definitions("alpha");
        ExtensionRegistryActivation.State state = ExtensionRegistryActivation.capture(1, source,
            handlers("alpha"), new PropertyRegistry(), null, null, null, null,
            new FlowGraphValidationRegistry(), new FlowResourceRegistry(), null, null,
            new FlowRegistry());

        source.register("beta", definition("beta"));

        assertNotSame(source, state.nodeDefinitions());
        assertEquals("alpha", state.nodeDefinitions().get("shared").getOwner());
        assertNull(state.nodeDefinitions().get("beta"));
    }

    @Test
    void publishedSlicesCannotBeMutatedThroughReturnedRegistryCopies() {
        ExtensionRegistryActivation.State state = state(1, "alpha");
        NodeDefinitionRegistry definitions = state.nodeDefinitions();
        HandlerRegistry handlers = state.handlers();
        FlowRegistry flows = state.flowRegistry();

        definitions.get("shared").assignOwner("beta");
        definitions.register("beta", definition("beta"));
        handlers.register("beta", (context, node) -> {
        });
        assertThrows(UnsupportedOperationException.class, () -> flows.getRegisteredTypes().clear());

        assertNull(state.nodeDefinitions().get("beta"));
        assertEquals("alpha", state.nodeDefinitions().get("shared").getOwner());
        assertFalse(state.handlers().getHandlerIds().contains("beta"));
        assertFalse(state.flowRegistry().getRegisteredTypes().contains("beta"));
    }

    private ExtensionRegistryActivation.State state(long generation, String owner) {
        return ExtensionRegistryActivation.capture(generation, definitions(owner), handlers(owner),
            new PropertyRegistry(), null, null, null, null, new FlowGraphValidationRegistry(),
            new FlowResourceRegistry(), null, null, new FlowRegistry());
    }

    private ExtensionRegistryActivation.State fullState(long generation, String owner) {
        NodeDefinitionRegistry definitions = definitions(owner);
        definitions.register(owner, new NodeDefinition.Builder(owner + ":event", "Event", NodeDefinition.NodeCategory.EVENT)
            .owner(owner)
            .trigger(true)
            .eventType("org.bukkit.event.player.PlayerJoinEvent")
            .build());
        HandlerRegistry handlers = handlers(owner);
        PropertyRegistry properties = new PropertyRegistry();
        properties.registerDescriptor(new PropertyRegistry.PropertyDescriptor(owner, "value", FlowTypeRef.simple("string"),
            List.of("get"), true, false, false, false, owner));
        RuntimeDataRegistry runtimeData = new RuntimeDataRegistry();
        runtimeData.register(runtimeAdapter(owner));
        OptionCatalogRegistry catalogs = new OptionCatalogRegistry(runtimeData);
        catalogs.register(optionProvider(owner));
        FlowValueCodecRegistry codecs = new FlowValueCodecRegistry();
        codecs.register(markerCodec(owner));
        TypeAdapterRegistry adapters = new TypeAdapterRegistry();
        if (owner.equals("alpha")) {
            adapters.register(String.class, AlphaValue.class, value -> null);
        } else {
            adapters.register(String.class, BetaValue.class, value -> null);
        }
        FlowGraphValidationRegistry validators = new FlowGraphValidationRegistry();
        FlowGraphValidationRule rule = graph -> List.of();
        validators.register(owner, owner + ":validator", rule);
        FlowResourceRegistry resources = new FlowResourceRegistry();
        resources.register(owner, resourceAdapter(owner));
        ReSyncExtensionData extensionData = new ReSyncExtensionData();
        extensionData.addPlugin(owner, "1", owner);
        FlowEventRegistry events = new FlowEventRegistry(null, adapters).copyForStaging(adapters);
        events.replaceDefinitions(definitions.getAllDefinitions().values().stream()
            .filter(NodeDefinition::isTrigger).toList());
        FlowRegistry flows = new FlowRegistry();
        flows.register(owner, (context, node) -> {
        });
        flows.setHandlerRegistry(handlers);
        return ExtensionRegistryActivation.capture(generation, definitions, handlers, properties, catalogs, runtimeData,
            codecs, adapters, validators, resources, extensionData, events, flows);
    }

    private ExtensionRegistryActivation.State definitionProjectionState(long generation, String owner) {
        NodeDefinitionRegistry definitions = projectedDefinitions(owner);
        PropertyRegistry properties = new PropertyRegistry();
        properties.replaceNodeDefinitions(definitions.getAllDefinitions().values());
        TypeAdapterRegistry adapters = new TypeAdapterRegistry();
        FlowEventRegistry events = new FlowEventRegistry(null, adapters).copyForStaging(adapters);
        events.replaceDefinitions(new ArrayList<>(definitions.getAllDefinitions().values()));
        return ExtensionRegistryActivation.capture(generation, definitions, new HandlerRegistry(), properties,
            null, null, null, adapters, new FlowGraphValidationRegistry(), new FlowResourceRegistry(), null, events,
            new FlowRegistry());
    }

    private NodeDefinitionRegistry projectedDefinitions(String owner) {
        NodeDefinitionRegistry definitions = definitions(owner);
        definitions.register(owner, new NodeDefinition.Builder(owner + ":property", "Property", NodeDefinition.NodeCategory.DATA)
            .owner(owner)
            .handler("player")
            .handlerConfig(Map.of("property", owner + "_value", "action", "get"))
            .build());
        definitions.register(owner, new NodeDefinition.Builder(owner + ":event", "Event", NodeDefinition.NodeCategory.EVENT)
            .owner(owner)
            .trigger(true)
            .eventType("org.bukkit.event.player.PlayerJoinEvent")
            .build());
        return definitions;
    }

    private OptionCatalogProvider optionProvider(String owner) {
        return new OptionCatalogProvider() {
            @Override
            public String sourceId() {
                return owner + ":catalog";
            }

            @Override
            public String revision() {
                return "1";
            }

            @Override
            public List<String> values() {
                return List.of(owner);
            }
        };
    }

    private RuntimeDataAdapter<String> runtimeAdapter(String owner) {
        return new RuntimeDataAdapter<>() {
            @Override
            public String id() {
                return owner + ":runtime";
            }

            @Override
            public String domain() {
                return owner;
            }

            @Override
            public FlowTypeRef valueType() {
                return FlowTypeRef.simple("string");
            }

            @Override
            public Class<String> valueClass() {
                return String.class;
            }

            @Override
            public List<RuntimeDataRecord> records(RuntimeDataQuery query) {
                return List.of();
            }

            @Override
            public String resolve(RuntimeDataRecord record, int amount) {
                return owner;
            }
        };
    }

    private FlowValueCodec<String> markerCodec(String owner) {
        return new FlowValueCodec<>() {
            @Override
            public String id() {
                return owner + "_codec";
            }

            @Override
            public int version() {
                return 1;
            }

            @Override
            public Class<String> javaType() {
                return String.class;
            }

            @Override
            public Object encode(String value) {
                return value;
            }

            @Override
            public String decode(Object value) {
                return String.valueOf(value);
            }
        };
    }

    private FlowResourceAdapter<String> resourceAdapter(String owner) {
        return new FlowResourceAdapter<>() {
            @Override
            public ReSyncManagedResource descriptor() {
                return new ReSyncManagedResource(owner + ":resource", owner, owner, null, true);
            }

            @Override
            public String get(String id) {
                return id;
            }

            @Override
            public List<String> listIds() {
                return List.of(owner);
            }

            @Override
            public String deserialize(String json) {
                return json;
            }

            @Override
            public String id(String value) {
                return value;
            }

            @Override
            public void save(String value) {
            }

            @Override
            public void delete(String id) {
            }
        };
    }

    private static final class AlphaValue {
    }

    private static final class BetaValue {
    }

    private NodeDefinitionRegistry definitions(String owner) {
        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry(false);
        definitions.register(owner, definition(owner));
        return definitions;
    }

    private NodeDefinition definition(String owner) {
        return new NodeDefinition.Builder("shared", "Shared", NodeDefinition.NodeCategory.DATA)
            .owner(owner)
            .build();
    }

    private ExtensionRegistryActivation.State definitionFingerprintState(NodeDefinition definition) {
        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry(false);
        definitions.register(definition.getOwner(), definition);
        return ExtensionRegistryActivation.capture(1, definitions, null, new PropertyRegistry(), null, null, null, null,
            new FlowGraphValidationRegistry(), new FlowResourceRegistry(), null, null, new FlowRegistry());
    }

    private NodeDefinition fingerprintDefinition(String pinDisplayName, String runtimeName,
                                                  NodeDefinition.MigrationMapping migrationMapping) {
        NodeDefinition.PinDefinition pin = new NodeDefinition.PinBuilder(PinId.of("value"), pinDisplayName,
            NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, FlowDataType.STRING)
            .runtimeName(runtimeName)
            .build();
        return new NodeDefinition.Builder("fingerprint.node", "Fingerprint", NodeDefinition.NodeCategory.DATA)
            .owner("fingerprint")
            .input(pin)
            .migrationMapping(migrationMapping)
            .build();
    }

    private NodeDefinition.MigrationMapping migrationMapping() {
        return new NodeDefinition.MigrationMapping(1, 2, true, List.of(
            new NodeDefinition.PinMigrationMapping("legacy_value", PinId.of("value"),
                NodeDefinition.PinDirection.INPUT, 1, 2)));
    }

    private HandlerRegistry handlers(String owner) {
        HandlerRegistry handlers = new HandlerRegistry();
        handlers.register(owner, new NodeHandler() {
            @Override
            public void execute(FlowContext ctx, FlowNode node) {
            }
        });
        return handlers;
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
