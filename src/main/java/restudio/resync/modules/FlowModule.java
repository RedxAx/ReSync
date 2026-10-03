package restudio.resync.modules;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import restudio.flow.data.CustomContentDefinition;
import restudio.flow.data.FlowDataType;
import restudio.resync.Log;
import restudio.resync.ReSync;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.api.OptionCatalogProvider;
import restudio.resync.api.ReSyncExtensionData;
import restudio.resync.api.ExtensionRegistryActivation;
import restudio.resync.protocol.ReSyncProtocolContract;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowSerializer;
import restudio.flow.data.FlowTypeRef;
import restudio.flow.data.GuiDefinition;
import restudio.flow.data.ScoreboardDefinition;
import restudio.flow.data.TabDefinition;
import restudio.resync.customcontent.CustomContentService;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.core.ConnectionState;
import restudio.resync.core.Session;
import restudio.resync.customcontent.CustomContentStorage;
import restudio.resync.customcontent.ItemAttributeSchemaService;
import restudio.resync.flow.CompiledTriggerExecution;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.FunctionCallSupport;
import restudio.resync.flow.FlowRuntime;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.FlowValueCodecRegistry;
import restudio.resync.flow.handler.generic.CustomFunctionCallHandler;
import restudio.resync.flow.contract.EditorDiagnostic;
import restudio.resync.flow.contract.EditorError;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.GlobalTriggers;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.CustomFunctionNodeDefinitions;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.function.FunctionParameterContract;
import restudio.resync.flow.graph.FunctionParameter;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.handler.property.PropertyRegistry;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.flow.jobs.FlowJobRegistry;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.AuthoredNodeMetadata;
import restudio.resync.flow.sync.NodeRegistrySnapshot;
import restudio.resync.flow.diagnostics.FlowDebugService;
import restudio.resync.flow.diagnostics.FlowTraceService;
import restudio.resync.flow.diagnostics.FlowTraceSink;
import restudio.resync.flow.util.TextFormatter;
import restudio.resync.flow.testing.FlowFunctionTestHarness;
import restudio.resync.messages.MessageLogService;
import restudio.resync.flow.triggers.TriggerRegistry;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.canonical.CanonicalLimits;
import restudio.resync.modules.flow.FlowBlueprintPacketHandler;
import restudio.resync.modules.flow.BuiltinOptionCatalogService;
import restudio.resync.modules.flow.CoreResourceMutationCheckpoint;
import restudio.resync.modules.flow.CoreResourceMutationRegistration;
import restudio.resync.modules.flow.CoreResourceMutationTransition;
import restudio.resync.modules.flow.FlowCatalogPublicationPacketHandler;
import restudio.resync.modules.flow.FlowCatalogPublicationPolicy;
import restudio.resync.modules.flow.FlowCollaborationService;
import restudio.resync.modules.flow.FlowMutationPayloadReader;
import restudio.resync.modules.flow.FlowNodeRegistryPacketHandler;
import restudio.resync.modules.flow.FlowOptionCatalogPacketHandler;
import restudio.resync.modules.flow.FlowPacketSender;
import restudio.resync.server.AuthorityEpoch;
import restudio.resync.server.CoreCatalogEvolution;
import restudio.resync.server.CoreGraphMutationValidator;
import restudio.resync.server.OptionCatalogCaptureExecutor;
import restudio.resync.server.TemporaryLifecycleDiagnostics;
import restudio.resync.modules.flow.FlowPlaceholderPreviewHandler;
import restudio.resync.modules.flow.FlowResourcePacketRouter;
import restudio.resync.modules.flow.FlowResourceCommitListener;
import restudio.resync.modules.flow.FlowResourceRegistry;
import restudio.resync.modules.flow.FlowWorkspaceService;
import restudio.resync.modules.flow.FlowWorkspaceDocumentProvider;
import restudio.resync.player.PlayerSessionLinkService;
import restudio.resync.protocol.Codec;
import restudio.resync.protocol.messages.DataMessage;
import restudio.resync.protocol.messages.SubscribeRequest;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.runtime.data.CustomContentItemDataAdapter;
import restudio.resync.runtime.data.RuntimeDataOptionCatalogService;
import restudio.resync.flow.catalog.CatalogActivationAuthority;
import restudio.resync.flow.catalog.CatalogBindingProof;
import restudio.resync.flow.catalog.CatalogCompilationResult;
import restudio.resync.flow.catalog.CatalogCompiler;
import restudio.resync.flow.catalog.CatalogContribution;
import restudio.resync.flow.catalog.CatalogContractRange;
import restudio.resync.flow.catalog.CatalogCategoryDescriptor;
import restudio.resync.flow.catalog.CatalogCapabilityDescriptor;
import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.catalog.CatalogProvenance;
import restudio.resync.flow.catalog.CatalogRuntimeActivation;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogSourceIngestor;
import restudio.resync.flow.catalog.CatalogStartupIndex;
import restudio.resync.flow.catalog.CatalogFunctionShape;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.catalog.CatalogCanonicalizer;
import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.inspector.InspectorCondition;
import restudio.resync.flow.inspector.InspectorFallback;
import restudio.resync.flow.inspector.InspectorOptionSource;
import restudio.resync.flow.inspector.InspectorCapability;
import restudio.resync.flow.inspector.InspectorValueSchema;
import restudio.resync.flow.inspector.OptionQuerySchemaV1;
import restudio.resync.flow.runtime.RuntimeFailureContract;
import restudio.resync.flow.runtime.CompiledRuntimeContext;
import restudio.resync.flow.CompiledRuntimeValueCodec;
import restudio.flow.data.FlowOperationResult;
import restudio.resync.flow.graph.FunctionBinding;
import java.util.concurrent.CompletionException;
import restudio.resync.flow.runtime.RuntimeBinding;
import restudio.resync.flow.runtime.RuntimeBindingKey;
import restudio.resync.flow.runtime.RuntimeBindingRegistry;
import restudio.resync.flow.runtime.RuntimeBindingRegistry.RuntimePostCommitDiagnostic;
import restudio.resync.flow.runtime.RuntimeBindingManifest;
import restudio.resync.flow.runtime.RuntimeExecutionContext;
import restudio.resync.flow.runtime.RuntimeInvocation;
import restudio.resync.flow.runtime.RuntimePrincipal;
import restudio.resync.flow.runtime.FlowRuntimeSecurityBoundary;
import restudio.resync.network.paper.PaperPlayerDataMutationAdmission;
import restudio.resync.flow.runtime.RuntimePrincipalAuthority;
import restudio.resync.flow.runtime.RuntimeOperationDescriptor;
import restudio.resync.flow.runtime.RuntimeOperationHandler;
import restudio.resync.flow.runtime.RuntimeProviderDescriptor;
import restudio.resync.flow.runtime.RuntimeProviderState;
import restudio.resync.flow.runtime.RuntimeRegistrySnapshot;
import restudio.resync.flow.runtime.RuntimeResult;
import restudio.resync.flow.runtime.RuntimeSemantics;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCachePublicationTransport;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogPublicationReceipt;
import restudio.resync.flow.cache.CatalogPublicationReceiptStore;
import restudio.resync.flow.type.CodecDescriptor;
import restudio.resync.flow.type.ConversionGraph;
import restudio.resync.flow.type.TypeDescriptor;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypeValueCodec;
import restudio.resync.flow.type.TypedValue;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.migration.PersistenceRootReadiness;
import restudio.resync.migration.ReplacementActivationRecord;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Array;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class FlowModule implements Module {
    static final CatalogVersion CATALOG_CONTRACT_VERSION = ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION;
    private static final int STARTUP_REJECTION_DIAGNOSTIC_LIMIT = 6;
    private static final int STARTUP_REJECTION_FIELD_LIMIT = 96;
    public static final String CATALOG_AUTHORING_CAPABILITY = FlowPacketSender.CATALOG_AUTHORING_CAPABILITY;
    private static final ModuleMetadata METADATA = ModuleMetadata.of("flowLegacyHandler", "FlowLegacyHandler", "flow");
    private static final long CUSTOM_CONTENT_CATALOG_POLL_INTERVAL_MS = 2000L;
    private static final long AUTHORING_REFRESH_INITIAL_RETRY_DELAY_MS = 2000L;
    private static final long AUTHORING_REFRESH_MAX_RETRY_DELAY_MS = 30000L;
    private static final long QUICK_EDIT_SESSION_TTL_MS = 30L * 60L * 1000L;
    private static final int MAX_RESOURCE_ACTIVATION_RESULTS = 2048;
    public static final String LEGACY_RESOURCE_MUTATION_UNAVAILABLE = "Resource changes require the durable typed protocol";
    private final FlowStorage storage;
    private final ServerId runtimeServerId;
    private final Set<Session> subscribedSessions = ConcurrentHashMap.newKeySet();
    private final FlowPacketSender sender;
    private final FlowBlueprintPacketHandler blueprintHandler;
    private final FlowResourcePacketRouter resourceRouter;
    private final FlowPlaceholderPreviewHandler placeholderPreviewHandler;
    private final FlowOptionCatalogPacketHandler optionCatalogHandler;
    private final OptionCatalogRegistry optionCatalogRegistry;
    private volatile OptionCatalogCaptureExecutor optionCatalogCaptureExecutor;
    private volatile Consumer<String> providerOptionQuerySourceChange = ignored -> {
    };
    private final FlowNodeRegistryPacketHandler nodeRegistryHandler;
    private final FlowCatalogPublicationPacketHandler catalogPublicationHandler;
    private final FlowCatalogPublicationPolicy catalogPublicationPolicy;
    private final CatalogCachePublicationTransport catalogPublicationTransport;
    private final CatalogCompiler catalogCompiler;
    private final CatalogRuntimeActivation catalogRuntimeActivation;
    private final CatalogActivationAuthority catalogActivationAuthority;
    private final List<CatalogSourceIngestor.CatalogSource> authoredCatalogSources;
    private final RuntimeBindingRegistry runtimeBindingRegistry;
    private final HandlerRegistry handlerRegistry;
    private final TypeAdapterRegistry runtimeTypeAdapters = new TypeAdapterRegistry();
    private final Map<String, NodeHandler> startupHandlerRegistrations;
    private DefinitionRegistryInput startupDefinitionInput;
    private final Set<ContractRef<ProviderId>> runtimeProviders = new HashSet<>();
    private final Set<ContractRef<ProviderId>> pendingRuntimeRetirements = new HashSet<>();
    private final FlowResourceRegistry resources;
    private final FlowCollaborationService collaboration;
    private final FlowWorkspaceService workspaces;
    private final List<CoreResourceMutationRegistration> coreMutationRegistrations = new ArrayList<>();
    private final NodeDefinitionRegistry definitionRegistry;
    private final ReSyncExtensionData extensionData;
    private final CustomContentStorage customContentStorage;
    private final PlayerSessionLinkService sessionLinkService;
    private FlowTraceService traceService;
    private FlowDebugService debugService;
    private FlowTraceSink traceSink;
    private FlowExecutor executor;
    private CompiledTriggerExecution compiledTriggerExecution;
    private RuntimePrincipalAuthority runtimePrincipalAuthority;
    private volatile FlowRuntimeSecurityBoundary runtimeSecurityBoundary;
    private final Gson gson = new Gson();
    private final ItemAttributeSchemaService quickEditAttributeService;
    private final PaperPlayerDataMutationAdmission playerDataAdmission = PaperPlayerDataMutationAdmission.shared();
    private final Map<String, QuickEditSession> quickEditSessions = new ConcurrentHashMap<>();
    private final Map<String, Long> trustedInvocationDeadlines = new ConcurrentHashMap<>();
    private final Map<String, ResourceActivationResult> resourceActivationResults = new LinkedHashMap<>();
    private PendingCatalogActivation pendingCatalogActivation;
    private PendingCatalogRuntimeCleanup pendingCatalogRuntimeCleanup;
    private List<RuntimePostCommitDiagnostic> catalogRuntimeCleanupDiagnostics = List.of();
    private long lastCustomContentCatalogPollAt;
    private volatile String lastCatalogRefreshPublicationFailureCode = "";
    private final Object authoringRefreshLock = new Object();
    private CatalogRuntimeActivation.ActivationRecord authoringRefreshStateActivation;
    private boolean authoringRefreshInFlight;
    private boolean authoringRefreshCompleted;
    private int authoringRefreshFailures;
    private long authoringRefreshRetryAt;
    private volatile PersistenceRootReadiness startupReadiness = PersistenceRootReadiness.empty();
    private volatile boolean startupActivationComplete;
    private CatalogCoherenceIdentity coherentCatalogIdentity;
    private Supplier<Boolean> customFunctionDefinitionRefresh = () -> false;
    private final AtomicBoolean catalogTickQueued = new AtomicBoolean();

    public static final class CoreFirstActivationTransaction {
        private final Supplier<Boolean> coreCommit;
        private final Supplier<Boolean> coreRollback;
        private final Runnable irreversibleCommit;
        private final Runnable projectionCommit;
        private final Runnable projectionRollback;
        private final Runnable coreFinalize;
        private boolean completed;
        private boolean compensated;
        private boolean compensationFailed;
        private boolean irreversible;
        private Throwable terminalFailure;

        public CoreFirstActivationTransaction(Supplier<Boolean> coreCommit, Supplier<Boolean> coreRollback,
                                               Runnable projectionCommit, Runnable projectionRollback) {
            this(coreCommit, coreRollback, projectionCommit, projectionRollback, () -> {
            });
        }

        public CoreFirstActivationTransaction(Supplier<Boolean> coreCommit, Supplier<Boolean> coreRollback,
                                               Runnable projectionCommit, Runnable projectionRollback,
                                               Runnable coreFinalize) {
            this(coreCommit, coreRollback, () -> {
            }, projectionCommit, projectionRollback, coreFinalize, false);
        }

        public CoreFirstActivationTransaction(Supplier<Boolean> coreCommit, Supplier<Boolean> coreRollback,
                                               Runnable irreversibleCommit, Runnable projectionCommit,
                                               Runnable projectionRollback, Runnable coreFinalize) {
            this(coreCommit, coreRollback, irreversibleCommit, projectionCommit, projectionRollback, coreFinalize, true);
        }

        private CoreFirstActivationTransaction(Supplier<Boolean> coreCommit, Supplier<Boolean> coreRollback,
                                                Runnable irreversibleCommit, Runnable projectionCommit,
                                                Runnable projectionRollback, Runnable coreFinalize,
                                                boolean hasIrreversibleCommit) {
            this.coreCommit = Objects.requireNonNull(coreCommit, "Core commit is required");
            this.coreRollback = Objects.requireNonNull(coreRollback, "Core rollback is required");
            this.irreversibleCommit = hasIrreversibleCommit
                ? Objects.requireNonNull(irreversibleCommit, "Irreversible Core commit is required") : null;
            this.projectionCommit = Objects.requireNonNull(projectionCommit, "Projection commit is required");
            this.projectionRollback = Objects.requireNonNull(projectionRollback, "Projection rollback is required");
            this.coreFinalize = Objects.requireNonNull(coreFinalize, "Core finalization is required");
        }

        public synchronized boolean commit() {
            if (completed) {
                return true;
            }
            if (terminalFailure != null) {
                throw new IllegalStateException("Core-first activation transaction failed closed", terminalFailure);
            }
            boolean coreCommitted;
            try {
                coreCommitted = Boolean.TRUE.equals(coreCommit.get());
            } catch (RuntimeException | Error failure) {
                if (!rollbackCore(failure)) {
                    compensationFailed = true;
                }
                terminalFailure = failure;
                throw failure;
            }
            if (!coreCommitted) {
                return false;
            }
            if (irreversibleCommit != null) {
                irreversible = true;
                try {
                    irreversibleCommit.run();
                } catch (RuntimeException | Error failure) {
                    compensationFailed = true;
                    terminalFailure = failure;
                    throw failure;
                }
            }
            try {
                projectionCommit.run();
            } catch (RuntimeException | Error failure) {
                if (irreversible) {
                    compensationFailed = true;
                    terminalFailure = failure;
                    throw failure;
                }
                boolean coreRestored = rollbackCore(failure);
                if (!coreRestored) {
                    compensationFailed = true;
                    terminalFailure = failure;
                    throw failure;
                }
                try {
                    projectionRollback.run();
                } catch (RuntimeException | Error projectionFailure) {
                    failure.addSuppressed(projectionFailure);
                    compensationFailed = true;
                    terminalFailure = failure;
                    throw failure;
                }
                compensated = true;
                terminalFailure = failure;
                throw failure;
            }
            try {
                coreFinalize.run();
                completed = true;
            } catch (RuntimeException | Error failure) {
                if (irreversible) {
                    compensationFailed = true;
                    terminalFailure = failure;
                    throw failure;
                }
                boolean coreRestored = rollbackCore(failure);
                if (coreRestored) {
                    try {
                        projectionRollback.run();
                        compensated = true;
                    } catch (RuntimeException | Error projectionFailure) {
                        failure.addSuppressed(projectionFailure);
                        compensationFailed = true;
                    }
                } else {
                    compensationFailed = true;
                }
                terminalFailure = failure;
                throw failure;
            }
            return true;
        }

        public synchronized boolean compensated() {
            return compensated;
        }

        public synchronized boolean compensationFailed() {
            return compensationFailed;
        }

        private boolean rollbackCore(Throwable failure) {
            try {
                if (!Boolean.TRUE.equals(coreRollback.get())) {
                    failure.addSuppressed(new IllegalStateException("Core catalog rollback was rejected"));
                    return false;
                }
                return true;
            } catch (RuntimeException | Error rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
                return false;
            }
        }
    }

    public FlowModule(FlowStorage storage, Codec codec, int channelId, TriggerRegistry triggerRegistry, GlobalTriggers globalTriggers,
                      FlowRegistry flowRegistry, NodeDefinitionRegistry definitionRegistry,
                      PropertyRegistry propertyRegistry, CustomContentStorage customContentStorage, CustomContentService customContentService) {
        this(storage, codec, channelId, triggerRegistry, globalTriggers, flowRegistry, definitionRegistry, propertyRegistry, customContentStorage, customContentService, null);
    }

    public FlowModule(FlowStorage storage, Codec codec, int channelId, TriggerRegistry triggerRegistry, GlobalTriggers globalTriggers,
                      FlowRegistry flowRegistry, NodeDefinitionRegistry definitionRegistry,
                      PropertyRegistry propertyRegistry, CustomContentStorage customContentStorage, CustomContentService customContentService,
                      ReSyncExtensionData extensionData) {
        this(storage, codec, channelId, triggerRegistry, globalTriggers, flowRegistry, definitionRegistry, propertyRegistry, customContentStorage, customContentService, extensionData, null);
    }

    public FlowModule(FlowStorage storage, Codec codec, int channelId, TriggerRegistry triggerRegistry, GlobalTriggers globalTriggers,
                      FlowRegistry flowRegistry, NodeDefinitionRegistry definitionRegistry,
                      PropertyRegistry propertyRegistry, CustomContentStorage customContentStorage, CustomContentService customContentService,
                      ReSyncExtensionData extensionData, OptionCatalogRegistry optionCatalogRegistry) {
        this(storage, codec, channelId, triggerRegistry, globalTriggers, flowRegistry, definitionRegistry, propertyRegistry, customContentStorage, customContentService, extensionData, optionCatalogRegistry, null);
    }

    public FlowModule(FlowStorage storage, Codec codec, int channelId, TriggerRegistry triggerRegistry, GlobalTriggers globalTriggers,
                      FlowRegistry flowRegistry, NodeDefinitionRegistry definitionRegistry,
                      PropertyRegistry propertyRegistry, CustomContentStorage customContentStorage, CustomContentService customContentService,
                      ReSyncExtensionData extensionData, OptionCatalogRegistry optionCatalogRegistry, ReSyncJsonResourceStorage jsonResourceStorage) {
        this(storage, codec, channelId, triggerRegistry, globalTriggers, flowRegistry, definitionRegistry, propertyRegistry, customContentStorage, customContentService, extensionData, optionCatalogRegistry, jsonResourceStorage, null);
    }

    public FlowModule(FlowStorage storage, Codec codec, int channelId, TriggerRegistry triggerRegistry, GlobalTriggers globalTriggers,
                      FlowRegistry flowRegistry, NodeDefinitionRegistry definitionRegistry,
                      PropertyRegistry propertyRegistry, CustomContentStorage customContentStorage, CustomContentService customContentService,
                      ReSyncExtensionData extensionData, OptionCatalogRegistry optionCatalogRegistry, ReSyncJsonResourceStorage jsonResourceStorage, MessageLogService messageLogService) {
        this(storage, codec, channelId, triggerRegistry, globalTriggers, flowRegistry, definitionRegistry, propertyRegistry, customContentStorage, customContentService, extensionData, optionCatalogRegistry, jsonResourceStorage, messageLogService, null);
    }

    public FlowModule(FlowStorage storage, Codec codec, int channelId, TriggerRegistry triggerRegistry, GlobalTriggers globalTriggers,
                      FlowRegistry flowRegistry, NodeDefinitionRegistry definitionRegistry,
                      PropertyRegistry propertyRegistry, CustomContentStorage customContentStorage, CustomContentService customContentService,
                      ReSyncExtensionData extensionData, OptionCatalogRegistry optionCatalogRegistry, ReSyncJsonResourceStorage jsonResourceStorage, MessageLogService messageLogService,
                      PlayerSessionLinkService sessionLinkService) {
        this(storage, codec, channelId, triggerRegistry, globalTriggers, flowRegistry, definitionRegistry, propertyRegistry, customContentStorage, customContentService, extensionData,
            optionCatalogRegistry, jsonResourceStorage, messageLogService, sessionLinkService,
            new BuiltinOptionCatalogService(() -> customContentService, new ItemAttributeSchemaService()));
    }

    public FlowModule(FlowStorage storage, Codec codec, int channelId, TriggerRegistry triggerRegistry, GlobalTriggers globalTriggers,
                      FlowRegistry flowRegistry, NodeDefinitionRegistry definitionRegistry,
                      PropertyRegistry propertyRegistry, CustomContentStorage customContentStorage, CustomContentService customContentService,
                      ReSyncExtensionData extensionData, OptionCatalogRegistry optionCatalogRegistry, ReSyncJsonResourceStorage jsonResourceStorage, MessageLogService messageLogService,
                      PlayerSessionLinkService sessionLinkService, BuiltinOptionCatalogService builtinOptionCatalogs) {
        this(storage, codec, channelId, triggerRegistry, globalTriggers, flowRegistry, definitionRegistry, propertyRegistry, customContentStorage, customContentService,
            extensionData, optionCatalogRegistry, jsonResourceStorage, messageLogService, sessionLinkService, builtinOptionCatalogs, new FlowResourceRegistry());
    }

    public FlowModule(FlowStorage storage, Codec codec, int channelId, TriggerRegistry triggerRegistry, GlobalTriggers globalTriggers,
                      FlowRegistry flowRegistry, NodeDefinitionRegistry definitionRegistry,
                      PropertyRegistry propertyRegistry, CustomContentStorage customContentStorage, CustomContentService customContentService,
                      ReSyncExtensionData extensionData, OptionCatalogRegistry optionCatalogRegistry, ReSyncJsonResourceStorage jsonResourceStorage, MessageLogService messageLogService,
                      PlayerSessionLinkService sessionLinkService, BuiltinOptionCatalogService builtinOptionCatalogs, FlowResourceRegistry resourceRegistry) {
        this(storage, codec, channelId, triggerRegistry, globalTriggers, flowRegistry, definitionRegistry, propertyRegistry, customContentStorage, customContentService,
            extensionData, optionCatalogRegistry, jsonResourceStorage, messageLogService, sessionLinkService, builtinOptionCatalogs, resourceRegistry, new FlowValueCodecRegistry());
    }

    public FlowModule(FlowStorage storage, Codec codec, int channelId, TriggerRegistry triggerRegistry, GlobalTriggers globalTriggers,
                      FlowRegistry flowRegistry, NodeDefinitionRegistry definitionRegistry,
                      PropertyRegistry propertyRegistry, CustomContentStorage customContentStorage, CustomContentService customContentService,
                      ReSyncExtensionData extensionData, OptionCatalogRegistry optionCatalogRegistry, ReSyncJsonResourceStorage jsonResourceStorage, MessageLogService messageLogService,
                      PlayerSessionLinkService sessionLinkService, BuiltinOptionCatalogService builtinOptionCatalogs, FlowResourceRegistry resourceRegistry,
                      FlowValueCodecRegistry valueCodecs) {
        this(storage, codec, channelId, triggerRegistry, globalTriggers, flowRegistry, definitionRegistry, propertyRegistry, customContentStorage, customContentService,
            extensionData, optionCatalogRegistry, jsonResourceStorage, messageLogService, sessionLinkService, builtinOptionCatalogs, resourceRegistry, valueCodecs, null);
    }

    public FlowModule(FlowStorage storage, Codec codec, int channelId, TriggerRegistry triggerRegistry, GlobalTriggers globalTriggers,
                      FlowRegistry flowRegistry, NodeDefinitionRegistry definitionRegistry,
                      PropertyRegistry propertyRegistry, CustomContentStorage customContentStorage, CustomContentService customContentService,
                      ReSyncExtensionData extensionData, OptionCatalogRegistry optionCatalogRegistry, ReSyncJsonResourceStorage jsonResourceStorage, MessageLogService messageLogService,
                      PlayerSessionLinkService sessionLinkService, BuiltinOptionCatalogService builtinOptionCatalogs, FlowResourceRegistry resourceRegistry,
                      FlowValueCodecRegistry valueCodecs, FlowJobRegistry flowJobs) {
        this(storage, codec, channelId, triggerRegistry, globalTriggers, flowRegistry, definitionRegistry, propertyRegistry, customContentStorage, customContentService,
            extensionData, optionCatalogRegistry, jsonResourceStorage, messageLogService, sessionLinkService, builtinOptionCatalogs, resourceRegistry, valueCodecs, flowJobs,
            null, null);
    }

    public FlowModule(FlowStorage storage, Codec codec, int channelId, TriggerRegistry triggerRegistry, GlobalTriggers globalTriggers,
                      FlowRegistry flowRegistry, NodeDefinitionRegistry definitionRegistry,
                      PropertyRegistry propertyRegistry, CustomContentStorage customContentStorage, CustomContentService customContentService,
                       ReSyncExtensionData extensionData, OptionCatalogRegistry optionCatalogRegistry, ReSyncJsonResourceStorage jsonResourceStorage, MessageLogService messageLogService,
                       PlayerSessionLinkService sessionLinkService, BuiltinOptionCatalogService builtinOptionCatalogs, FlowResourceRegistry resourceRegistry,
                       FlowValueCodecRegistry valueCodecs, FlowJobRegistry flowJobs, RuntimeBindingRegistry runtimeBindingRegistry) {
        this(storage, codec, channelId, triggerRegistry, globalTriggers, flowRegistry, definitionRegistry, propertyRegistry, customContentStorage, customContentService,
            extensionData, optionCatalogRegistry, jsonResourceStorage, messageLogService, sessionLinkService, builtinOptionCatalogs, resourceRegistry, valueCodecs, flowJobs,
            runtimeBindingRegistry, null);
    }

    public FlowModule(FlowStorage storage, Codec codec, int channelId, TriggerRegistry triggerRegistry, GlobalTriggers globalTriggers,
                      FlowRegistry flowRegistry, NodeDefinitionRegistry definitionRegistry,
                      PropertyRegistry propertyRegistry, CustomContentStorage customContentStorage, CustomContentService customContentService,
                       ReSyncExtensionData extensionData, OptionCatalogRegistry optionCatalogRegistry, ReSyncJsonResourceStorage jsonResourceStorage, MessageLogService messageLogService,
                       PlayerSessionLinkService sessionLinkService, BuiltinOptionCatalogService builtinOptionCatalogs, FlowResourceRegistry resourceRegistry,
                       FlowValueCodecRegistry valueCodecs, FlowJobRegistry flowJobs, RuntimeBindingRegistry runtimeBindingRegistry, HandlerRegistry handlerRegistry) {
        this(storage, codec, channelId, triggerRegistry, globalTriggers, flowRegistry, definitionRegistry, propertyRegistry, customContentStorage, customContentService,
            extensionData, optionCatalogRegistry, jsonResourceStorage, messageLogService, sessionLinkService, builtinOptionCatalogs, resourceRegistry, valueCodecs,
            flowJobs, runtimeBindingRegistry, handlerRegistry, CatalogActivationAuthority.forbidden(), null);
    }

    public FlowModule(FlowStorage storage, Codec codec, int channelId, TriggerRegistry triggerRegistry, GlobalTriggers globalTriggers,
                      FlowRegistry flowRegistry, NodeDefinitionRegistry definitionRegistry,
                      PropertyRegistry propertyRegistry, CustomContentStorage customContentStorage, CustomContentService customContentService,
                       ReSyncExtensionData extensionData, OptionCatalogRegistry optionCatalogRegistry, ReSyncJsonResourceStorage jsonResourceStorage, MessageLogService messageLogService,
                       PlayerSessionLinkService sessionLinkService, BuiltinOptionCatalogService builtinOptionCatalogs, FlowResourceRegistry resourceRegistry,
                      FlowValueCodecRegistry valueCodecs, FlowJobRegistry flowJobs, RuntimeBindingRegistry runtimeBindingRegistry, HandlerRegistry handlerRegistry,
                      CatalogActivationAuthority catalogActivationAuthority, ServerId serverId) {
        this(storage, codec, channelId, triggerRegistry, globalTriggers, flowRegistry, definitionRegistry, propertyRegistry, customContentStorage, customContentService,
            extensionData, optionCatalogRegistry, jsonResourceStorage, messageLogService, sessionLinkService, builtinOptionCatalogs, resourceRegistry, valueCodecs,
            flowJobs, runtimeBindingRegistry, handlerRegistry, catalogActivationAuthority, serverId, null);
    }

    public FlowModule(FlowStorage storage, Codec codec, int channelId, TriggerRegistry triggerRegistry, GlobalTriggers globalTriggers,
                      FlowRegistry flowRegistry, NodeDefinitionRegistry definitionRegistry,
                      PropertyRegistry propertyRegistry, CustomContentStorage customContentStorage, CustomContentService customContentService,
                       ReSyncExtensionData extensionData, OptionCatalogRegistry optionCatalogRegistry, ReSyncJsonResourceStorage jsonResourceStorage, MessageLogService messageLogService,
                       PlayerSessionLinkService sessionLinkService, BuiltinOptionCatalogService builtinOptionCatalogs, FlowResourceRegistry resourceRegistry,
                      FlowValueCodecRegistry valueCodecs, FlowJobRegistry flowJobs, RuntimeBindingRegistry runtimeBindingRegistry, HandlerRegistry handlerRegistry,
                      CatalogActivationAuthority catalogActivationAuthority, ServerId serverId, CatalogPublicationReceiptStore receiptStore) {
        this(storage, codec, channelId, triggerRegistry, globalTriggers, flowRegistry, definitionRegistry, propertyRegistry, customContentStorage,
            customContentService, extensionData, optionCatalogRegistry, jsonResourceStorage, messageLogService, sessionLinkService,
            builtinOptionCatalogs, resourceRegistry, valueCodecs, flowJobs, runtimeBindingRegistry, handlerRegistry,
            catalogActivationAuthority, serverId, receiptStore, List.of());
    }

    public FlowModule(FlowStorage storage, Codec codec, int channelId, TriggerRegistry triggerRegistry, GlobalTriggers globalTriggers,
                      FlowRegistry flowRegistry, NodeDefinitionRegistry definitionRegistry,
                      PropertyRegistry propertyRegistry, CustomContentStorage customContentStorage, CustomContentService customContentService,
                      ReSyncExtensionData extensionData, OptionCatalogRegistry optionCatalogRegistry, ReSyncJsonResourceStorage jsonResourceStorage, MessageLogService messageLogService,
                      PlayerSessionLinkService sessionLinkService, BuiltinOptionCatalogService builtinOptionCatalogs, FlowResourceRegistry resourceRegistry,
                       FlowValueCodecRegistry valueCodecs, FlowJobRegistry flowJobs, RuntimeBindingRegistry runtimeBindingRegistry, HandlerRegistry handlerRegistry,
                       CatalogActivationAuthority catalogActivationAuthority, ServerId serverId, CatalogPublicationReceiptStore receiptStore,
                       List<CatalogSourceIngestor.CatalogSource> authoredCatalogSources) {
        this(storage, codec, channelId, triggerRegistry, globalTriggers, flowRegistry, definitionRegistry, propertyRegistry, customContentStorage,
            customContentService, extensionData, optionCatalogRegistry, jsonResourceStorage, messageLogService, sessionLinkService,
            builtinOptionCatalogs, resourceRegistry, valueCodecs, flowJobs, runtimeBindingRegistry, handlerRegistry,
            catalogActivationAuthority, serverId, receiptStore, authoredCatalogSources, AuthorityEpoch.fixed(1L),
            FlowMutationPayloadReader::legacyCompatible);
    }

    public FlowModule(FlowStorage storage, Codec codec, int channelId, TriggerRegistry triggerRegistry, GlobalTriggers globalTriggers,
                      FlowRegistry flowRegistry, NodeDefinitionRegistry definitionRegistry,
                      PropertyRegistry propertyRegistry, CustomContentStorage customContentStorage, CustomContentService customContentService,
                      ReSyncExtensionData extensionData, OptionCatalogRegistry optionCatalogRegistry, ReSyncJsonResourceStorage jsonResourceStorage, MessageLogService messageLogService,
                      PlayerSessionLinkService sessionLinkService, BuiltinOptionCatalogService builtinOptionCatalogs, FlowResourceRegistry resourceRegistry,
                      FlowValueCodecRegistry valueCodecs, FlowJobRegistry flowJobs, RuntimeBindingRegistry runtimeBindingRegistry, HandlerRegistry handlerRegistry,
                      CatalogActivationAuthority catalogActivationAuthority, ServerId serverId, CatalogPublicationReceiptStore receiptStore,
                      List<CatalogSourceIngestor.CatalogSource> authoredCatalogSources, AuthorityEpoch authorityEpoch,
                      Predicate<Session> legacyCompatibility) {
        Objects.requireNonNull(runtimeBindingRegistry, "Flow runtime binding registry is required");
        Objects.requireNonNull(definitionRegistry, "Flow node definition registry is required");
        Objects.requireNonNull(handlerRegistry, "Flow handler registry is required");
        this.runtimeServerId = Objects.requireNonNull(serverId, "Flow server identity is required");
        Objects.requireNonNull(receiptStore, "Catalog publication receipt store is required");
        Objects.requireNonNull(authorityEpoch, "Flow authority epoch is required");
        Objects.requireNonNull(legacyCompatibility, "Flow legacy compatibility policy is required");
        if (authorityEpoch.current() < 1L) {
            throw new IllegalStateException("Flow authority epoch must be positive");
        }
        BuiltinOptionCatalogService catalogs = builtinOptionCatalogs != null ? builtinOptionCatalogs : new BuiltinOptionCatalogService(() -> customContentService, new ItemAttributeSchemaService());
        if (builtinOptionCatalogs == null) {
            catalogs.registerProviders(optionCatalogRegistry);
        }
        this.optionCatalogRegistry = optionCatalogRegistry;
        this.quickEditAttributeService = catalogs.itemAttributeSchemaService();
        this.storage = storage;
        this.definitionRegistry = definitionRegistry;
        this.extensionData = extensionData;
        this.runtimeBindingRegistry = runtimeBindingRegistry;
        this.handlerRegistry = handlerRegistry;
        this.catalogActivationAuthority = catalogActivationAuthority != null
            ? catalogActivationAuthority : CatalogActivationAuthority.forbidden();
        this.authoredCatalogSources = authoredCatalogSources == null ? List.of() : List.copyOf(authoredCatalogSources);
        this.startupHandlerRegistrations = this.handlerRegistry.snapshot();
        this.startupDefinitionInput = catalogDefinitionInput(this.definitionRegistry);
        CatalogBindingProof bindingProof = CatalogBindingProof.live(this.runtimeBindingRegistry);
        this.catalogCompiler = new CatalogCompiler(CATALOG_CONTRACT_VERSION, bindingProof);
        CatalogCompilationResult initialCatalog;
        RuntimeReplacementPlan startupRuntimePlan = null;
        try {
            long contributionStarted = TemporaryLifecycleDiagnostics.start();
            long contributionWall = System.nanoTime();
            CatalogBuild catalogBuild = buildStartupCatalog(this.authoredCatalogSources);
            List<CatalogContribution> contributions = catalogBuild.contributions();
            TemporaryLifecycleDiagnostics.event("catalog_contribution_build", contributionStarted,
                Map.of("operation", "startup", "outcome", "complete", "count", contributions.size()));
            Log.info("Flow catalog contribution build completed in "
                + Math.max(0L, (System.nanoTime() - contributionWall) / 1_000_000L) + " ms [" + contributions.size()
                + " contributions]");
            long runtimePreparationStarted = TemporaryLifecycleDiagnostics.start();
            long runtimePreparationWall = System.nanoTime();
            startupRuntimePlan = prepareRuntimeReplacement(contributions, catalogBuild.runtimeDefinitions());
            TemporaryLifecycleDiagnostics.event("catalog_runtime_prepare", runtimePreparationStarted,
                Map.of("operation", "startup", "outcome", "complete", "count", contributions.size()));
            Log.info("Flow catalog runtime prepare completed in "
                + Math.max(0L, (System.nanoTime() - runtimePreparationWall) / 1_000_000L) + " ms");
            RuntimeRegistrySnapshot stagedRuntime = startupRuntimePlan.replacement().preview();
            CatalogBinding persistedBinding = loadCatalogGeneration(storage);
            long publicationGeneration = latestPublishedGeneration(serverId, receiptStore);
            long persistedGeneration = persistedBinding != null ? persistedBinding.generation() : 1L;
            long generation = publicationGeneration > persistedGeneration
                ? Math.addExact(publicationGeneration, 1L) : persistedGeneration;
            CatalogCompilationResult preflight = preflightCatalog(contributions, generation, stagedRuntime);
            if (!preflight.accepted()) {
                throw new IllegalStateException(startupCatalogRejection("Flow catalog startup preflight was rejected", preflight.diagnostics()));
            }
            CatalogSnapshot preflightSnapshot = preflight.snapshot().orElseThrow();
            CatalogBinding startupBinding = startupCatalogBinding(persistedBinding, preflightSnapshot.contentChecksum(),
                preflightSnapshot.bindingManifestHash(), preflightSnapshot.canonicalContent(), publicationGeneration);
            if (startupBinding.generation() != generation) {
                generation = startupBinding.generation();
                preflight = CatalogCompilationResult.accepted(preflightSnapshot.withGeneration(generation),
                    preflight.diagnostics());
            }
            initialCatalog = preflight;
            if (!initialCatalog.accepted()) {
                throw new IllegalStateException(startupCatalogRejection("Flow catalog startup activation was rejected", initialCatalog.diagnostics()));
            }
            CatalogSnapshot startupCatalog = initialCatalog.snapshot().orElseThrow(
                () -> new IllegalStateException("Flow catalog startup produced no active snapshot"));
            CatalogBinding actualBinding = new CatalogBinding(startupCatalog.generation(), startupCatalog.contentChecksum(),
                startupCatalog.bindingManifestHash());
            if (!startupBinding.equals(actualBinding)) {
                throw new IllegalStateException("Flow catalog startup binding changed after generation allocation");
            }
            CoreCatalogEvolution.select(actualBinding).flatMap(evolution ->
                evolution.bootstrapProof(persistedBinding, actualBinding, startupCatalog.canonicalContent()))
                .ifPresent(proof -> {
                    if (!actualBinding.equals(proof.target())) {
                        throw new IllegalStateException("Flow catalog startup does not satisfy its proven generation floor");
                    }
                });
            this.catalogRuntimeActivation = CatalogRuntimeActivation.bootstrap(
                startupCatalog, startupRuntimePlan.replacement(), null);
            applyRuntimeReplacement(startupRuntimePlan);
        } catch (RuntimeException exception) {
            if (startupRuntimePlan != null) {
                startupRuntimePlan.replacement().close();
            }
            throw exception instanceof IllegalStateException
                ? exception : new IllegalStateException("Flow catalog startup failed closed", exception);
        }
        this.customContentStorage = customContentStorage;
        this.sessionLinkService = sessionLinkService;
        this.sender = new FlowPacketSender(codec, channelId, subscribedSessions, flowJobs);
        this.sender.setAuthorityEpoch(authorityEpoch);
        this.collaboration = new FlowCollaborationService(subscribedSessions, sender);
        FlowResourceRegistry resources = resourceRegistry != null ? resourceRegistry : new FlowResourceRegistry();
        this.resources = resources;
        this.workspaces = new FlowWorkspaceService(storage, customContentStorage, sender, collaboration, resources);
        resources.setWorkspaceService(workspaces);
        resources.setCommitListener(new FlowResourceCommitListener() {
            @Override
            public void saved(String type, String resourceId, String payload) {
                workspaces.resourceSaved(type, resourceId, payload);
                collaboration.saved(type, resourceId, payload);
                refreshSharedResource(type, resourceId, false);
            }

            @Override
            public void saved(Session session, String type, String resourceId, String payload) {
                workspaces.resourceSaved(type, resourceId, payload);
                collaboration.saved(session, type, resourceId, payload);
                refreshSharedResource(type, resourceId, false);
            }

            @Override
            public void deleted(String type, String resourceId) {
                collaboration.deleted(type, resourceId);
                workspaces.resourceDeleted(type, resourceId);
                refreshSharedResource(type, resourceId, true);
            }

            @Override
            public void deleted(Session session, String type, String resourceId) {
                collaboration.deleted(session, type, resourceId);
                workspaces.resourceDeleted(type, resourceId);
                refreshSharedResource(type, resourceId, true);
            }
        });
        this.blueprintHandler = new FlowBlueprintPacketHandler(storage, triggerRegistry, globalTriggers, definitionRegistry, sender, resources,
            authorityEpoch, legacyCompatibility);
        this.placeholderPreviewHandler = new FlowPlaceholderPreviewHandler(sender);
        this.optionCatalogHandler = new FlowOptionCatalogPacketHandler(sender, optionCatalogRegistry, catalogs);
        resources.setChangeListener(this::broadcastOptionCatalog);
        this.resourceRouter = new FlowResourcePacketRouter(storage, customContentStorage, customContentService, jsonResourceStorage, sender, messageLogService,
            this::broadcastCustomContentCatalogs, resources, this::broadcastOptionCatalog, quickEditAttributeService,
            authorityEpoch, legacyCompatibility);
        this.nodeRegistryHandler = new FlowNodeRegistryPacketHandler(definitionRegistry, sender, propertyRegistry, customContentService, extensionData, optionCatalogRegistry, resources, valueCodecs);
        this.nodeRegistryHandler.setCanonicalServerIdentity(serverId);
        this.nodeRegistryHandler.setActiveCatalogMetadataSupplier(this::activeFlowCatalogMetadata);
        this.nodeRegistryHandler.requireCanonicalCatalogAuthority();
        this.catalogPublicationTransport = new CatalogCachePublicationTransport(serverId, catalogRuntimeActivation);
        Path expectedReceiptPath = storage.getAssetsPath().toAbsolutePath().normalize().getParent()
            .resolve(CatalogPublicationReceiptStore.FILE_NAME);
        if (!receiptStore.root().equals(expectedReceiptPath)
            || !receiptStore.rebindScope().equals(expectedReceiptPath.getParent())) {
            throw new IllegalArgumentException("Flow catalog publication receipt store is not bound to the data root");
        }
        CatalogPublicationReceiptStore publicationReceiptStore = receiptStore;
        this.catalogPublicationHandler = new FlowCatalogPublicationPacketHandler(sender, catalogPublicationTransport,
            publicationReceiptStore, subscribedSessions);
        this.catalogPublicationPolicy = new FlowCatalogPublicationPolicy(subscribedSessions, catalogPublicationHandler);
        this.workspaces.setAuthorityAdmission((session, claim) -> session != null && claim != null
            && claim.documentVersion() == 1 && authorityEpoch.acceptsTyped(claim.authorityEpoch())
            && session.getConnection() != null && session.getConnection().hasProtocolResourceAccess()
            && session.getConnection().getNegotiatedFlowCapabilities().contains("live_workspace")
            && catalogPublicationHandler.catalogPublicationForSession(session)
                .filter(publication -> publication.authoringPublication() != null
                    && publication.key().canonicalText().equals(claim.catalogKey())).isPresent());
        this.nodeRegistryHandler.requireTypedCatalogPublicationAuthority();
        activateCoreMutationSubscribers();
    }

    private synchronized FlowNodeRegistryPacketHandler.ActiveCatalogMetadata activeFlowCatalogMetadata() {
        CatalogRuntimeActivation.ActivationRecord activation = catalogRuntimeActivation.active();
        CatalogSnapshot active = activation.catalog();
        RuntimeRegistrySnapshot runtime = activation.runtime();
        FlowNodeRegistryPacketHandler.ActiveCatalogMetadata metadata = FlowNodeRegistryPacketHandler.activeCatalogMetadata(active);
        Map<String, Object> values = new LinkedHashMap<>(metadata.metadata());
        values.put("runtimeBindingManifestHash", runtime.bindingManifestHash().canonicalText());
        return FlowNodeRegistryPacketHandler.ActiveCatalogMetadata.of(metadata.generation(), metadata.checksum(),
            metadata.dropContributions(), metadata.functionBoundaries(), values);
    }

    @Override
    public ModuleMetadata getMetadata() {
        return METADATA;
    }

    @Override
    public String getChannelId() {
        return "flow";
    }

    public FlowStorage getStorage() {
        return storage;
    }

    public synchronized CatalogSnapshot activeCatalogSnapshot() {
        return catalogRuntimeActivation.active().catalog();
    }

    public void refreshRuntimeGraphBindings() {
        blueprintHandler.refreshAllGraphBindings();
    }

    public CatalogRuntimeActivation.ActivationRecord activeCatalogRuntimeActivation() {
        return catalogRuntimeActivation.active();
    }

    public synchronized <T> T executeCoreGraphAdmission(
        CatalogRuntimeActivation.ActivationRecord expected, Supplier<T> action
    ) {
        Objects.requireNonNull(expected, "Expected Core graph activation is required");
        Objects.requireNonNull(action, "Core graph admitted action is required");
        if (activeCatalogRuntimeActivation() != expected) {
            throw new CoreGraphMutationValidator.AdmissionFenceException();
        }
        return action.get();
    }

    public CatalogRuntimeActivation catalogRuntimeActivation() {
        return catalogRuntimeActivation;
    }

    public boolean catalogAuthoringAvailable() {
        return catalogPublicationTransport.catalogAuthoringAvailable();
    }

    public CompletableFuture<Boolean> ensureCatalogAuthoringAvailable() {
        return catalogPublicationTransport.ensureCatalogAuthoringAvailable();
    }

    public Optional<Map<String, Object>> catalogAuthoringCapability() {
        int maxChunkBytes = sender.effectiveMaxChunkDataBytes();
        int maxPublicationBytes = sender.effectiveMaxPublicationBytes();
        if (maxChunkBytes < 1 || maxPublicationBytes < 1) {
            return Optional.empty();
        }
        return catalogPublicationTransport.catalogAuthoringCapability().map(capability -> {
            Map<String, Object> values = new LinkedHashMap<>(capability);
            values.put("maxChunkBytes", maxChunkBytes);
            values.put("maxPublicationBytes", maxPublicationBytes);
            return Map.copyOf(values);
        });
    }

    public Optional<CatalogCachePublication> catalogPublicationForSession(Session session) {
        return catalogPublicationHandler.catalogPublicationForSession(session);
    }

    public synchronized RuntimeBindingManifest activeRuntimeBindingManifest() {
        return catalogRuntimeActivation.active().runtimeManifest();
    }

    public synchronized void completeStartupActivation(PersistenceRootReadiness readiness) {
        PersistenceRootReadiness verified = Objects.requireNonNull(readiness, "Startup persistence readiness is required");
        CatalogRuntimeActivation.ActivationRecord activation = catalogRuntimeActivation.active();
        CatalogSnapshot active = activation.catalog();
        RuntimeBindingManifest manifest = activation.runtimeManifest();
        CatalogActivationAuthority.Decision decision = CatalogActivationAuthority.evaluate(
            active, manifest, verified, catalogActivationAuthority);
        if (!decision.allowed()) {
            throw new IllegalStateException(decision.code() + ": " + decision.detail());
        }
        startupReadiness = verified;
        startupActivationComplete = true;
        coherentCatalogIdentity = captureCatalogCoherenceIdentity();
        startupDefinitionInput = null;
    }

    public synchronized void resetStartupActivation() {
        startupReadiness = PersistenceRootReadiness.empty();
        startupActivationComplete = false;
        coherentCatalogIdentity = null;
    }

    public boolean startupActivationComplete() {
        return startupActivationComplete;
    }

    public synchronized void activateCoreMutationSubscribers() {
        if (!coreMutationRegistrations.isEmpty()) {
            return;
        }
        List<CoreResourceMutationRegistration> registrations = new ArrayList<>(3);
        try {
            registrations.add(resources.addCoreMutationListener(
                new IdempotentCoreMutationSubscriber(this::projectCoreMutationToWorkspace)));
            registrations.add(resources.addCoreMutationListener(
                new IdempotentCoreMutationSubscriber(collaboration::publishCoreMutation)));
            registrations.add(resources.addCoreMutationListener(
                new IdempotentCoreMutationSubscriber(new CoreMutationRuntimeSubscriber(
                    this::consumeTypedCommandRefreshProof, this::refreshCoreMutationRuntime))));
            coreMutationRegistrations.addAll(registrations);
        } catch (RuntimeException exception) {
            RuntimeException cleanupFailure = closeCoreMutationRegistrations(registrations);
            if (cleanupFailure != null) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    public synchronized void deactivateCoreMutationSubscribers() {
        RuntimeException failure = closeCoreMutationRegistrations(coreMutationRegistrations);
        coreMutationRegistrations.clear();
        if (failure != null) {
            throw failure;
        }
    }

    private RuntimeException closeCoreMutationRegistrations(List<CoreResourceMutationRegistration> registrations) {
        RuntimeException failure = null;
        for (int index = registrations.size() - 1; index >= 0; index--) {
            try {
                registrations.get(index).close();
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        return failure;
    }

    private void projectCoreMutationToWorkspace(CoreResourceMutationTransition transition) {
        String type = transition.locator().resourceType().value();
        String resourceId = transition.locator().id();
        if (transition.deleted()) {
            workspaces.resourceDeleted(type, resourceId);
        } else {
            workspaces.resourceSaved(type, resourceId, transition.canonicalEnvelope());
        }
    }

    private void refreshCoreMutationRuntime(CoreResourceMutationTransition transition) {
        if (ReSyncResourceCatalog.FUNCTION.equals(transition.locator().resourceType().value())
            && transition.deleted() && consumeTypedCommandRefreshProof(transition)) {
            if (customFunctionDefinitionChanged(transition.locator().id(), true)) {
                refreshCustomFunctionDefinitions();
            }
            return;
        }
        refreshSharedResource(transition.locator().resourceType().value(), transition.locator().id(), transition.deleted());
    }

    private boolean consumeTypedCommandRefreshProof(CoreResourceMutationTransition transition) {
        return storage.consumeTypedCommandRefreshProof(transition.locator(), transition.revision(),
            transition.mutationId(), transition.deleted());
    }

    @Override
    public void onSubscribe(Session session, SubscribeRequest req) {
        subscribedSessions.add(session);
        collaboration.subscribe(session);
        catalogPublicationHandler.resetSession(session);
        catalogPublicationPolicy.resetForReconnect(session);
        boolean admitted = catalogPublicationPolicy.admitInitial(session);
        if (TemporaryLifecycleDiagnostics.enabled()) {
            FlowCatalogPublicationPacketHandler.DiagnosticSnapshot snapshot = catalogPublicationHandler.diagnosticSnapshot();
            Map<String, Object> diagnostic = TemporaryLifecycleDiagnostics.with(
                TemporaryLifecycleDiagnostics.identity(null, null, "subscribe", null, null, null, null, null, null, null),
                "moduleId", "flow", "resourceType", "catalog", "outcome", admitted ? "accepted" : "rejected",
                "diagnosticCode", admitted ? "" : catalogPublicationPolicy.lastFailureCode()
                    .orElse("CATALOG_PUBLICATION.UNAVAILABLE"),
                "authoringAvailable", catalogPublicationTransport.catalogAuthoringAvailable(),
                "queuedCount", snapshot.queuedWork(), "waitingCount", snapshot.waitingWork(),
                "outboxEntries", snapshot.outboxEntries(), "outboxBytes", snapshot.outboxBytes());
            if (session.getConnection() != null) {
                diagnostic = TemporaryLifecycleDiagnostics.with(diagnostic, "connectionHash",
                    TemporaryLifecycleDiagnostics.safeHash(session.getConnection().getConnectionId()));
            }
            TemporaryLifecycleDiagnostics.event("catalog_session", 0L, diagnostic);
        }
    }

    @Override
    public void cleanup(Session session) {
        subscribedSessions.remove(session);
        catalogPublicationPolicy.cleanup(session);
        catalogPublicationHandler.cleanupSession(session);
        collaboration.cleanup(session);
        workspaces.cleanup(session);
    }

    @Override
    public void stop(ModuleContext context) {
        catalogPublicationPolicy.shutdown();
    }

    @Override
    public void onTick() {
        if (deferCatalogTickToPrimary()) {
            return;
        }
        if (subscribedSessions.isEmpty()) {
            return;
        }
        publishAuthoringWhenReady();
        long now = System.currentTimeMillis();
        catalogPublicationPolicy.admitTick(now);
        if (now - lastCustomContentCatalogPollAt < CUSTOM_CONTENT_CATALOG_POLL_INTERVAL_MS) {
            return;
        }
        lastCustomContentCatalogPollAt = now;
        optionCatalogHandler.broadcastChangedCustomContentCatalogs().forEach(providerOptionQuerySourceChange);
    }

    private boolean deferCatalogTickToPrimary() {
        if (Bukkit.getServer() == null || Bukkit.isPrimaryThread()) {
            return false;
        }
        ReSync plugin = ReSync.getInstance();
        if (plugin == null || !plugin.isEnabled()) {
            return true;
        }
        if (!catalogTickQueued.compareAndSet(false, true)) {
            return true;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    onTick();
                } finally {
                    catalogTickQueued.set(false);
                }
            });
        } catch (RuntimeException exception) {
            catalogTickQueued.set(false);
            Log.warn("Flow catalog tick could not return to the Bukkit primary thread: " + exception.getMessage());
        }
        return true;
    }

    @Override
    public void onData(Session session, DataMessage req) {
        byte[] payload = req.getPayload();
        if (payload == null || payload.length < 1) {
            sender.sendError(session, "EMPTY_PACKET", "Packet payload is empty");
            return;
        }

        if (payload.length > FlowPacketSender.MAX_PACKET_SIZE) {
            sender.sendError(session, "PACKET_TOO_LARGE", "Packet exceeds maximum size");
            return;
        }

        ByteBuffer buffer = ByteBuffer.wrap(payload);
        byte packetId = buffer.get();

        if (packetId == ReSyncProtocolContract.FLOW_PACKET_PRESENCE_UPDATE || packetId == ReSyncProtocolContract.FLOW_PACKET_WORKSPACE_JOIN
            || packetId == ReSyncProtocolContract.FLOW_PACKET_WORKSPACE_LEAVE || packetId == ReSyncProtocolContract.FLOW_PACKET_WORKSPACE_OPERATION
            || packetId == ReSyncProtocolContract.FLOW_PACKET_WORKSPACE_AWARENESS || packetId == ReSyncProtocolContract.FLOW_PACKET_COLLABORATION_CHAT) {
            try {
                switch (packetId) {
                    case ReSyncProtocolContract.FLOW_PACKET_PRESENCE_UPDATE -> collaboration.handleUpdate(session, buffer);
                    case ReSyncProtocolContract.FLOW_PACKET_WORKSPACE_JOIN -> workspaces.handleJoin(session, buffer);
                    case ReSyncProtocolContract.FLOW_PACKET_WORKSPACE_LEAVE -> workspaces.handleLeave(session, buffer);
                    case ReSyncProtocolContract.FLOW_PACKET_WORKSPACE_OPERATION -> workspaces.handleOperation(session, buffer);
                    case ReSyncProtocolContract.FLOW_PACKET_WORKSPACE_AWARENESS -> workspaces.handleAwareness(session, buffer);
                    case ReSyncProtocolContract.FLOW_PACKET_COLLABORATION_CHAT -> collaboration.handleMessage(session, buffer);
                    default -> {
                    }
                }
            } catch (RuntimeException exception) {
                Log.error("Error handling collaboration packet 0x" + String.format("%02X", packetId) + ": " + exception.getMessage());
                sender.sendError(session, "PROCESSING_ERROR", "Collaboration packet could not be processed");
            }
            return;
        }

        collaboration.enter(session);
        try {
            if (!resourceRouter.handle(session, packetId, buffer)) {
                switch (packetId) {
                    case ReSyncProtocolContract.FLOW_PACKET_REQUEST -> blueprintHandler.handleRequest(session, buffer);
                    case ReSyncProtocolContract.FLOW_PACKET_DATA -> {
                    }
                    case ReSyncProtocolContract.FLOW_PACKET_SAVE -> blueprintHandler.handleSave(session, buffer);
                    case ReSyncProtocolContract.FLOW_PACKET_GUI_STATE -> {
                    }
                    case ReSyncProtocolContract.FLOW_PACKET_TRIGGER_UPDATE -> blueprintHandler.handleTriggerUpdate(session, buffer);
                    case ReSyncProtocolContract.FLOW_PACKET_DELETE -> blueprintHandler.handleDelete(session, buffer);
                    case ReSyncProtocolContract.FLOW_PACKET_LIST_REQUEST -> blueprintHandler.handleListRequest(session);
                    case ReSyncProtocolContract.FLOW_PACKET_NODE_REGISTRY_REQUEST -> nodeRegistryHandler.handleRequest(session, buffer);
                    case ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION_REQUEST -> catalogPublicationHandler.handleRequest(session, buffer);
                    case ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION_CLIENT_RECEIVED,
                         ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION_CACHE_APPLIED,
                         ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION_CACHE_REJECTED ->
                        catalogPublicationHandler.handleReceipt(session, packetId, buffer);
                    case ReSyncProtocolContract.FLOW_PACKET_PLACEHOLDER_PREVIEW_REQUEST -> placeholderPreviewHandler.handle(session, buffer);
                    case ReSyncProtocolContract.FLOW_PACKET_OPTION_CATALOG_REQUEST -> optionCatalogHandler.handle(session, buffer);
                    case ReSyncProtocolContract.FLOW_PACKET_TRACE_TOGGLE -> handleTraceToggle(session, buffer);
                    case ReSyncProtocolContract.FLOW_PACKET_TRACE_CLEAR -> handleTraceClear(session);
                    case ReSyncProtocolContract.FLOW_PACKET_JOB_SNAPSHOT_REQUEST -> {
                        sender.sendJobSnapshot(session, session.getClientId());
                        sender.sendScheduledTaskSnapshot(session, executor != null ? executor.getScheduledTaskSnapshots() : List.of());
                    }
                    case ReSyncProtocolContract.FLOW_PACKET_DEBUG_COMMAND -> handleDebugCommand(session, buffer);
                    case ReSyncProtocolContract.FLOW_PACKET_FUNCTION_TEST_REQUEST -> handleFunctionTest(session, buffer);
                    case ReSyncProtocolContract.FLOW_PACKET_QUICK_EDIT_APPLY -> handleQuickEditApply(session, buffer);
                    case ReSyncProtocolContract.FLOW_PACKET_RESOURCE_ACTIVATION -> handleResourceActivation(session, buffer);
                    default -> {
                        Log.warn("Unknown flow packet: 0x" + String.format("%02X", packetId));
                        sender.sendError(session, "UNKNOWN_PACKET", "Unknown flow packet: 0x" + String.format("%02X", packetId));
                    }
                }
            }
        } catch (Exception e) {
            Log.error("Error handling flow packet 0x" + String.format("%02X", packetId) + ": " + e.getMessage());
            sender.sendError(session, "PROCESSING_ERROR", e.getMessage());
        } finally {
            collaboration.exit();
        }

    }

    public boolean refreshCustomFunctionDefinitions() {
        try {
            return Boolean.TRUE.equals(customFunctionDefinitionRefresh.get());
        } catch (RuntimeException exception) {
            Log.warn("Custom Function catalog refresh was rejected: " + exception.getMessage());
            return false;
        }
    }

    private void publishAuthoringWhenReady() {
        if (catalogPublicationHandler.hasPendingDispatches() || hasCurrentAuthoringPublication()) {
            return;
        }
        if (!catalogPublicationTransport.catalogAuthoringAvailable()) {
            return;
        }
        CatalogRuntimeActivation.ActivationRecord active = catalogRuntimeActivation.active();
        long now = System.currentTimeMillis();
        synchronized (authoringRefreshLock) {
            if (authoringRefreshStateActivation != active) {
                authoringRefreshStateActivation = active;
                authoringRefreshInFlight = false;
                authoringRefreshCompleted = false;
                authoringRefreshFailures = 0;
                authoringRefreshRetryAt = 0L;
            }
            if (authoringRefreshCompleted || authoringRefreshInFlight || authoringRefreshRetryAt > now) {
                return;
            }
            authoringRefreshInFlight = true;
        }

        boolean admitted = false;
        try {
            admitted = catalogPublicationPolicy.admitRefresh();
        } catch (RuntimeException exception) {
            Log.warn("Catalog authoring refresh failed: " + exception.getMessage());
        } finally {
            synchronized (authoringRefreshLock) {
                if (authoringRefreshStateActivation == active) {
                    authoringRefreshInFlight = false;
                    if (admitted) {
                        authoringRefreshCompleted = true;
                        authoringRefreshFailures = 0;
                        authoringRefreshRetryAt = 0L;
                    } else {
                        authoringRefreshCompleted = false;
                        authoringRefreshFailures = nextAuthoringRefreshFailureCount(authoringRefreshFailures);
                        authoringRefreshRetryAt = retryAt(now, authoringRefreshDelay(authoringRefreshFailures));
                    }
                }
            }
        }
        if (!admitted) {
            lastCatalogRefreshPublicationFailureCode = catalogPublicationPolicy.lastFailureCode()
                .orElse("CATALOG_PUBLICATION.UNAVAILABLE");
            reportCatalogPublicationFailure(lastCatalogRefreshPublicationFailureCode);
        }
    }

    private boolean hasCurrentAuthoringPublication() {
        Optional<CatalogCachePublication> publication = catalogPublicationHandler.lastValidPublication();
        Optional<CatalogCacheKey> activeKey = catalogPublicationTransport.activeKey();
        return publication.filter(CatalogCachePublication::hasAuthoringPublication)
            .flatMap(value -> activeKey.filter(value.key()::equals))
            .isPresent();
    }

    private static int nextAuthoringRefreshFailureCount(int failures) {
        return failures == Integer.MAX_VALUE ? Integer.MAX_VALUE : failures + 1;
    }

    private static long authoringRefreshDelay(int failures) {
        long delay = AUTHORING_REFRESH_INITIAL_RETRY_DELAY_MS;
        for (int index = 1; index < failures && delay < AUTHORING_REFRESH_MAX_RETRY_DELAY_MS; index++) {
            delay = Math.min(AUTHORING_REFRESH_MAX_RETRY_DELAY_MS, delay * 2L);
        }
        return delay;
    }

    private static long retryAt(long now, long delay) {
        return now > Long.MAX_VALUE - delay ? Long.MAX_VALUE : now + delay;
    }

    public synchronized CatalogRuntimeTransaction prepareCatalogRuntimeTransaction(
        NodeDefinitionRegistry stagedDefinitions,
        HandlerRegistry stagedHandlers,
        ReSyncExtensionData stagedExtensionData,
        Set<ContractRef<ProviderId>> additionalRetirements
    ) {
        return prepareCatalogRuntimeTransaction(stagedDefinitions, stagedHandlers, stagedExtensionData,
            additionalRetirements, authoredCatalogSources);
    }

    public synchronized CatalogRuntimeTransaction prepareCatalogRuntimeTransaction(
        NodeDefinitionRegistry stagedDefinitions,
        HandlerRegistry stagedHandlers,
        ReSyncExtensionData stagedExtensionData,
        Set<ContractRef<ProviderId>> additionalRetirements,
        List<CatalogSourceIngestor.CatalogSource> stagedAuthoredSources
    ) {
        return prepareCatalogRuntimeTransaction(stagedDefinitions, stagedHandlers, stagedExtensionData,
            additionalRetirements, stagedAuthoredSources, false);
    }

    synchronized CatalogRuntimeTransaction prepareCatalogRuntimeTransaction(
        NodeDefinitionRegistry stagedDefinitions,
        HandlerRegistry stagedHandlers,
        ReSyncExtensionData stagedExtensionData,
        Set<ContractRef<ProviderId>> additionalRetirements,
        List<CatalogSourceIngestor.CatalogSource> stagedAuthoredSources,
        boolean startupReuseEligible
    ) {
        if (pendingCatalogRuntimeCleanup != null) {
            throw new IllegalStateException("Flow catalog runtime cleanup is pending");
        }
        Objects.requireNonNull(stagedDefinitions, "Staged node definitions are required");
        Objects.requireNonNull(stagedHandlers, "Staged handler registry is required");
        Objects.requireNonNull(stagedExtensionData, "Staged extension data is required");
        Set<ContractRef<ProviderId>> retirements = additionalRetirements == null ? Set.of() : Set.copyOf(additionalRetirements);
        List<CatalogSourceIngestor.CatalogSource> sources = stagedAuthoredSources == null
            ? List.of() : List.copyOf(stagedAuthoredSources);
        CatalogRuntimeTransaction startupReuse = prepareStartupCatalogReuse(stagedDefinitions, stagedHandlers,
            stagedExtensionData, sources, retirements, startupReuseEligible);
        if (startupReuse != null) {
            return startupReuse;
        }
        List<CatalogContribution> contributions = buildCatalogContributions(stagedDefinitions, stagedHandlers, stagedExtensionData,
            sources);
        RuntimeReplacementPlan runtimePlan = prepareRuntimeReplacement(contributions, stagedDefinitions, stagedHandlers, retirements);
        RuntimeBindingRegistry.RuntimeReplacement runtimeReplacement = runtimePlan.replacement();
        try {
            if (runtimeReplacement.blockedLeases() > 0) {
                return new CatalogRuntimeTransaction(null, runtimePlan, true, false,
                    catalogRuntimeActivation.active().catalog().contributions(), definitionRegistry.copy(), handlerRegistry.copy(),
                    Set.copyOf(runtimeProviders), Set.copyOf(pendingRuntimeRetirements));
            }
            RuntimeRegistrySnapshot stagedRuntime = runtimeReplacement.preview();
            CatalogRuntimeActivation.ActivationRecord activeRecord = catalogRuntimeActivation.active();
            CatalogSnapshot active = activeRecord.catalog();
            long generation = Math.addExact(active.generation(), 1L);
            CatalogCompilationResult preflight = catalogCompilerFor(stagedRuntime).compile(contributions, generation);
            if (!preflight.accepted()) {
                throw new IllegalStateException("Flow catalog preflight was rejected");
            }
            CatalogSnapshot candidate = preflight.snapshot().orElseThrow();
            if (startupActivationComplete) {
                CatalogActivationAuthority.Decision authorityDecision = CatalogActivationAuthority.evaluate(
                    candidate, stagedRuntime.manifest(), startupReadiness, catalogActivationAuthority);
                if (!authorityDecision.allowed()) {
                    throw new IllegalStateException("Flow catalog preflight was rejected by activation authority: "
                        + authorityDecision.detail());
                }
            }
            if (active.contentChecksum().equals(candidate.contentChecksum())
                && active.bindingManifestHash().equals(candidate.bindingManifestHash())) {
                return new CatalogRuntimeTransaction(null, runtimePlan, false, true, active.contributions(), definitionRegistry.copy(),
                    handlerRegistry.copy(), Set.copyOf(runtimeProviders), Set.copyOf(pendingRuntimeRetirements));
            }
            CatalogRuntimeActivation.ActivationTransaction activation = catalogRuntimeActivation.stageReplacement(
                candidate, runtimeReplacement);
            return new CatalogRuntimeTransaction(activation, runtimePlan, false, false, active.contributions(), definitionRegistry.copy(),
                handlerRegistry.copy(), Set.copyOf(runtimeProviders), Set.copyOf(pendingRuntimeRetirements));
        } catch (RuntimeException | Error failure) {
            runtimeReplacement.close();
            throw failure;
        }
    }

    public synchronized RuntimeBindingRetirement stageRuntimeBindingRetirementForOwner(String owner) {
        if (owner == null || owner.isBlank()) {
            return new RuntimeBindingRetirement();
        }
        OwnerId ownerId = OwnerId.of(owner);
        RuntimeRegistrySnapshot active = catalogRuntimeActivation.active().runtime();
        Set<ContractRef<ProviderId>> providers = active.bindingValues().stream()
            .filter(binding -> ownerId.equals(binding.descriptor().capability().owner()))
            .map(RuntimeBinding::provider)
            .collect(Collectors.toCollection(HashSet::new));
        if (providers.isEmpty()) {
            return new RuntimeBindingRetirement();
        }
        if (providers.stream().anyMatch(provider -> active.bindingValues().stream()
            .filter(binding -> provider.equals(binding.provider()))
            .anyMatch(binding -> !ownerId.equals(binding.descriptor().capability().owner())))) {
            return new RuntimeBindingRetirement(providers, "Runtime provider is shared by multiple owners");
        }
        RuntimeBindingRegistry.RuntimeReplacement replacement = runtimeBindingRegistry.prepareReplacement(List.of(), providers);
        return new RuntimeBindingRetirement(providers, replacement, replacement.blockedLeases(), "");
    }

    public synchronized boolean refreshCatalog() {
        long diagnosticStarted = TemporaryLifecycleDiagnostics.start();
        try {
        if (pendingCatalogRuntimeCleanup != null) {
            Log.warn("Flow catalog refresh is waiting for runtime cleanup retry");
            return false;
        }
        if (pendingCatalogActivation != null) {
            return retryPendingCatalogActivation();
        }
        RuntimeBindingRegistry.RuntimeReplacement preparedReplacement = null;
        try {
            List<CatalogContribution> contributions = buildCatalogContributions();
            RuntimeReplacementPlan runtimePlan = prepareRuntimeReplacement(contributions);
            RuntimeBindingRegistry.RuntimeReplacement runtimeReplacement = runtimePlan.replacement();
            preparedReplacement = runtimeReplacement;
            RuntimeRegistrySnapshot stagedRuntime = runtimeReplacement.preview();
            CatalogCompiler stagedCompiler = catalogCompilerFor(stagedRuntime);
            CatalogRuntimeActivation.ActivationRecord activeRecord = catalogRuntimeActivation.active();
            long generation = Math.addExact(activeRecord.catalog().generation(), 1L);
            CatalogCompilationResult preflight = stagedCompiler.compile(contributions, generation);
            if (!preflight.accepted()) {
                runtimeReplacement.close();
                Log.warn("Flow catalog refresh was rejected; retaining the last valid active catalog");
                return false;
            }
            CatalogSnapshot active = activeRecord.catalog();
            CatalogSnapshot candidate = preflight.snapshot().orElseThrow();
            if (startupActivationComplete) {
                CatalogActivationAuthority.Decision authorityDecision = CatalogActivationAuthority.evaluate(
                    candidate, stagedRuntime.manifest(), startupReadiness, catalogActivationAuthority);
                if (!authorityDecision.allowed()) {
                    runtimeReplacement.close();
                    Log.warn("Flow catalog refresh was rejected by activation authority: " + authorityDecision.detail());
                    return false;
                }
            }
            if (active.contentChecksum().equals(candidate.contentChecksum())
                && active.bindingManifestHash().equals(candidate.bindingManifestHash())) {
                runtimeReplacement.close();
                return true;
            }
            CatalogRuntimeActivation.ActivationTransaction transaction = catalogRuntimeActivation.stageReplacement(
                candidate, runtimeReplacement);
            CatalogRuntimeActivation.ActivationResult activation = transaction.commit();
            if (activation.status() == CatalogRuntimeActivation.ActivationStatus.BLOCKED) {
                pendingCatalogActivation = new PendingCatalogActivation(transaction, runtimePlan);
                Log.warn("Flow catalog refresh is waiting for " + activation.detail());
                return false;
            }
            if (!activation.committed()) {
                transaction.close();
                Log.warn("Flow catalog refresh was rejected; retaining the last valid active catalog");
                return false;
            }
            retainCatalogRuntimeCleanup(transaction, activation);
            applyRuntimeReplacement(runtimePlan);
            catalogPublicationTransport.prewarmAuthoring();
            boolean publicationAccepted = catalogPublicationPolicy.admitRefresh();
            if (!publicationAccepted) {
                lastCatalogRefreshPublicationFailureCode = catalogPublicationPolicy.lastFailureCode()
                    .orElse("CATALOG_PUBLICATION.UNAVAILABLE");
                reportCatalogPublicationFailure(lastCatalogRefreshPublicationFailureCode);
            }
            if (publicationAccepted) {
                lastCatalogRefreshPublicationFailureCode = "";
            }
            return true;
        } catch (RuntimeException exception) {
            if (preparedReplacement != null) {
                try {
                    preparedReplacement.close();
                } catch (RuntimeException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
            }
            Log.warn("Flow catalog projection was rejected; retaining the last valid active catalog: " + exception.getMessage());
            return false;
        }
        } finally {
            CatalogRuntimeActivation.ActivationRecord active = catalogRuntimeActivation.active();
            TemporaryLifecycleDiagnostics.event("catalog_refresh", diagnosticStarted,
                TemporaryLifecycleDiagnostics.with(Map.of("moduleId", "flow", "resourceType", "catalog", "operation", "refresh",
                    "outcome", "observed", "generation", active.catalog().generation(),
                    "revision", active.catalog().generation(), "contributionCount", active.catalog().contributions().size())));
        }
    }

    public synchronized boolean retryPendingCatalogRuntimeCleanup() {
        PendingCatalogRuntimeCleanup pending = pendingCatalogRuntimeCleanup;
        if (pending == null) {
            return true;
        }
        CatalogRuntimeActivation.ActivationResult result;
        try {
            result = pending.transaction().commit();
        } catch (RuntimeException exception) {
            Log.warn("Flow catalog runtime cleanup retry failed: " + exception.getMessage());
            return false;
        }
        catalogRuntimeCleanupDiagnostics = result.diagnostics();
        if (result.healthDegraded()) {
            return false;
        }
        if (!result.committed()) {
            return false;
        }
        pendingCatalogRuntimeCleanup = null;
        catalogRuntimeCleanupDiagnostics = List.of();
        return true;
    }

    public synchronized boolean catalogRuntimeCleanupPending() {
        return pendingCatalogRuntimeCleanup != null;
    }

    public synchronized List<RuntimePostCommitDiagnostic> catalogRuntimeCleanupDiagnostics() {
        return List.copyOf(catalogRuntimeCleanupDiagnostics);
    }

    public synchronized Map<String, Object> activeCatalogRuntimeDiagnostics() {
        List<Map<String, Object>> diagnostics = catalogRuntimeCleanupDiagnostics.stream()
            .map(value -> Map.<String, Object>of(
                "provider", value.provider().canonicalText(),
                "operation", value.operation(),
                "code", value.code(),
                "detail", value.detail(),
                "attempts", value.attempts()))
            .toList();
        return Map.of(
            "healthy", pendingCatalogRuntimeCleanup == null,
            "pending", pendingCatalogRuntimeCleanup != null,
            "diagnostics", diagnostics);
    }

    private void retainCatalogRuntimeCleanup(
        CatalogRuntimeActivation.ActivationTransaction transaction,
        CatalogRuntimeActivation.ActivationResult result
    ) {
        if (!result.healthDegraded()) {
            return;
        }
        if (pendingCatalogRuntimeCleanup != null) {
            throw new IllegalStateException("Flow catalog runtime cleanup is already pending");
        }
        pendingCatalogRuntimeCleanup = new PendingCatalogRuntimeCleanup(transaction);
        catalogRuntimeCleanupDiagnostics = result.diagnostics();
    }

    private boolean retryPendingCatalogActivation() {
        PendingCatalogActivation pending = pendingCatalogActivation;
        if (!pendingActivationMatchesLiveDefinitions(pending)) {
            pending.transaction().close();
            pendingCatalogActivation = null;
            Log.warn("Pending Flow catalog activation was discarded because the live definitions changed");
            return false;
        }
        CatalogRuntimeActivation.ActivationResult result;
        try {
            result = pending.transaction().commit();
        } catch (RuntimeException exception) {
            pending.transaction().close();
            pendingCatalogActivation = null;
            Log.warn("Pending Flow catalog activation was rejected: " + exception.getMessage());
            return false;
        }
        if (result.status() == CatalogRuntimeActivation.ActivationStatus.BLOCKED) {
            return false;
        }
        pendingCatalogActivation = null;
        if (!result.committed()) {
            pending.transaction().close();
            return false;
        }
        retainCatalogRuntimeCleanup(pending.transaction(), result);
        applyRuntimeReplacement(pending.runtimePlan());
        catalogPublicationTransport.prewarmAuthoring();
        boolean publicationAccepted = catalogPublicationPolicy.admitRefresh();
        if (!publicationAccepted) {
            lastCatalogRefreshPublicationFailureCode = catalogPublicationPolicy.lastFailureCode()
                .orElse("CATALOG_PUBLICATION.UNAVAILABLE");
            reportCatalogPublicationFailure(lastCatalogRefreshPublicationFailureCode);
        } else {
            lastCatalogRefreshPublicationFailureCode = "";
        }
        return true;
    }

    private boolean pendingActivationMatchesLiveDefinitions(PendingCatalogActivation pending) {
        try {
            CatalogRuntimeActivation.ActivationRecord candidate = pending.transaction().candidate();
            RuntimeRegistrySnapshot stagedRuntime = pending.runtimePlan().replacement().preview();
            CatalogCompilationResult current = catalogCompilerFor(stagedRuntime).compile(
                buildCatalogContributions(), candidate.catalog().generation());
            return current.accepted() && current.snapshot().map(snapshot ->
                snapshot.contentChecksum().equals(candidate.catalog().contentChecksum())
                    && snapshot.bindingManifestHash().equals(candidate.catalog().bindingManifestHash())).orElse(false);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public synchronized void discardPendingCatalogActivation() {
        if (pendingCatalogActivation != null) {
            pendingCatalogActivation.transaction().close();
            pendingCatalogActivation = null;
        }
    }

    public synchronized boolean isCatalogCoherent() {
        try {
            CatalogRuntimeActivation.ActivationRecord activeRecord = catalogRuntimeActivation.active();
            CatalogSnapshot active = activeRecord.catalog();
            RuntimeRegistrySnapshot runtime = activeRecord.runtime();
            ExtensionRegistryActivation.State projectionState = activeProjectionState();
            if (startupActivationComplete && coherentCatalogIdentity != null
                && coherentCatalogIdentity.matches(active, runtime, projectionState)) {
                return true;
            }
            CatalogCompilationResult projection = preflightCatalog(buildCatalogContributions(), active.generation(), runtime);
            boolean coherent = projection.snapshot()
                .map(snapshot -> snapshot.contentChecksum().equals(active.contentChecksum())
                    && snapshot.bindingManifestHash().equals(active.bindingManifestHash()))
                .orElse(false);
            if (coherent) {
                coherentCatalogIdentity = captureCatalogCoherenceIdentity();
            }
            return coherent;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private ExtensionRegistryActivation.State activeProjectionState() {
        ExtensionRegistryActivation activation = definitionRegistry.activation();
        return activation != null ? activation.snapshot() : null;
    }

    private CatalogCoherenceIdentity captureCatalogCoherenceIdentity() {
        ExtensionRegistryActivation.State projectionState = activeProjectionState();
        if (projectionState == null) {
            return null;
        }
        CatalogRuntimeActivation.ActivationRecord active = catalogRuntimeActivation.active();
        return new CatalogCoherenceIdentity(active.catalog(), active.runtime(), projectionState);
    }

    private CatalogRuntimeTransaction prepareStartupCatalogReuse(NodeDefinitionRegistry stagedDefinitions,
                                                                 HandlerRegistry stagedHandlers,
                                                                 ReSyncExtensionData stagedExtensionData,
                                                                 List<CatalogSourceIngestor.CatalogSource> stagedAuthoredSources,
                                                                 Set<ContractRef<ProviderId>> retirements,
                                                                 boolean startupReuseEligible) {
        if (!startupReuseEligible) {
            return null;
        }
        CatalogRuntimeActivation.ActivationRecord active = catalogRuntimeActivation.active();
        StartupCatalogReuseProof proof = startupCatalogReuseProof(stagedDefinitions, stagedHandlers,
            stagedExtensionData, stagedAuthoredSources, retirements, startupReuseEligible);
        Optional<RuntimeBindingRegistry.RuntimeReplacement> prepared = prepareStartupNoopRuntime(
            proof, runtimeBindingRegistry, active.runtime());
        if (prepared.isEmpty()) {
            Log.fine("Flow startup catalog reuse unavailable: " + proof.mismatch(runtimeBindingRegistry, active.runtime()));
            return null;
        }
        RuntimeBindingRegistry.RuntimeReplacement replacement = prepared.orElseThrow();
        if (catalogRuntimeActivation.active() != active) {
            replacement.close();
            return null;
        }
        RuntimeReplacementPlan runtimePlan = new RuntimeReplacementPlan(replacement, Set.of(), Set.of());
        return new CatalogRuntimeTransaction(null, runtimePlan, false, true, active.catalog().contributions(), definitionRegistry.copy(),
            handlerRegistry.copy(), Set.copyOf(runtimeProviders), Set.copyOf(pendingRuntimeRetirements));
    }

    private StartupCatalogReuseProof startupCatalogReuseProof(NodeDefinitionRegistry stagedDefinitions,
                                                              HandlerRegistry stagedHandlers,
                                                              ReSyncExtensionData stagedExtensionData,
                                                              List<CatalogSourceIngestor.CatalogSource> stagedAuthoredSources,
                                                              Set<ContractRef<ProviderId>> retirements,
                                                              boolean startupReuseEligible) {
        return new StartupCatalogReuseProof(startupReuseEligible, startupActivationComplete,
            stagedExtensionData == extensionData, extensionData != null && extensionData.pluginIds().isEmpty(),
            retirements, pendingRuntimeRetirements, authoredCatalogSources, stagedAuthoredSources,
            startupHandlerRegistrations, stagedHandlers.snapshot(), startupDefinitionInput,
            catalogDefinitionInput(stagedDefinitions));
    }

    static DefinitionRegistryInput catalogDefinitionInput(NodeDefinitionRegistry registry) {
        Objects.requireNonNull(registry, "Node definition registry is required");
        DefinitionProjection projection = new DefinitionProjection();
        Map<String, List<DefinitionInput>> definitions = new LinkedHashMap<>();
        registry.getPluginIds().stream().sorted().forEach(pluginId -> definitions.put(pluginId,
            registry.getDefinitionsForPlugin(pluginId).stream().map(value -> definitionInput(value, projection)).toList()));
        return new DefinitionRegistryInput(projection.supported, registry.defaultPluginId(), definitions);
    }

    static boolean sameAuthoredCatalogSources(List<CatalogSourceIngestor.CatalogSource> expected,
                                              List<CatalogSourceIngestor.CatalogSource> actual) {
        List<CatalogSourceIngestor.CatalogSource> left = expected == null ? List.of() : expected;
        List<CatalogSourceIngestor.CatalogSource> right = actual == null ? List.of() : actual;
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            CatalogSourceIngestor.CatalogSource first = left.get(index);
            CatalogSourceIngestor.CatalogSource second = right.get(index);
            if (!first.owner().equals(second.owner())
                || first.sourceKind() != second.sourceKind()
                || !first.sourceUri().equals(second.sourceUri())
                || !first.sourceVersion().equals(second.sourceVersion())
                || !first.buildId().equals(second.buildId())
                || !Arrays.equals(first.bytes(), second.bytes())) {
                return false;
            }
        }
        return true;
    }

    static boolean sameHandlerRegistrations(Map<String, NodeHandler> expected, Map<String, NodeHandler> actual) {
        if (expected == null || actual == null || !expected.keySet().equals(actual.keySet())) {
            return false;
        }
        for (String id : expected.keySet()) {
            if (expected.get(id) != actual.get(id)) {
                return false;
            }
        }
        return true;
    }

    private static DefinitionInput definitionInput(NodeDefinition definition, DefinitionProjection projection) {
        Objects.requireNonNull(definition, "Node definition is required");
        NodeDefinition.NodeCategory category = definition.getCategory();
        NodeDefinition.Availability availability = definition.getAvailability();
        return new DefinitionInput(definition.getId(), definition.getDisplayName(),
            category != null ? new CategoryInput(category.getId(), category.getDisplayName(), category.getColor(), category.getPriority()) : null,
            definition.getInputs().stream().map(value -> pinInput(value, projection)).toList(),
            definition.getOutputs().stream().map(value -> pinInput(value, projection)).toList(),
            definition.getColor(), definition.getPriority(), definition.isHidden(), definition.getHiddenReason(), definition.getOwner(),
            definition.getDescription(), definition.getHandler(), definitionValue(definition.getHandlerConfig(), projection),
            definition.isTrigger(), definition.getEventType(), List.copyOf(definition.getAliases()),
            List.copyOf(definition.getOutputMappings()), definition.getSchemaVersion(), definition.getKind(),
            availability != null ? new AvailabilityInput(availability.getPlugin(), availability.getPlatform(), availability.getMinVersion()) : null,
            definition.getCanonicalId(), List.copyOf(definition.getLegacyIds()), definition.isDeprecated(), List.copyOf(definition.getTags()),
            definition.getAuthorizationPolicy(), definition.isSensitive(), definition.isDestructive(), definition.getAuditPolicy(),
            definition.getConfirmationPolicy(), definition.getClockDomain(), definition.getAuthoredMetadata(), definition.getMigrationMapping(),
            List.copyOf(definition.getExamples()), definition.getFamily(), definition.isRecommended(), definition.getReplacementFor());
    }

    private static PinInput pinInput(NodeDefinition.PinDefinition pin, DefinitionProjection projection) {
        Objects.requireNonNull(pin, "Node definition pin is required");
        NodeDefinition.PinConstraints constraints = pin.getConstraints();
        NodeDefinition.RepeatablePin repeatable = pin.getRepeatable();
        return new PinInput(pin.getId().value(), pin.getRuntimeName(), pin.getDisplayName(), pin.getType(), pin.getDirection(),
            dataTypeInput(pin.getDataType(), projection), typeRefInput(pin.getTypeRef(), projection),
            repeatable != null ? new RepeatableInput(repeatable.getGroupId(), repeatable.getMinItems(), repeatable.getMaxItems(), repeatable.getItemLabel()) : null,
            pin.getWidgetType(), List.copyOf(pin.getOptions()), pin.getOptionsSource(), pin.getDefaultValue(),
            constraints != null ? new ConstraintsInput(constraints.getMin(), constraints.getMax(), constraints.getStep()) : null,
            Collections.unmodifiableMap(new LinkedHashMap<>(pin.getVisibleWhen())), pin.getDescription(), pin.isOptional());
    }

    private static DataTypeInput dataTypeInput(FlowDataType type, DefinitionProjection projection) {
        if (type == null) {
            return null;
        }
        if (!projection.enter(type)) {
            return null;
        }
        try {
            return new DataTypeInput(type.getId(), type.getCanonicalId(), dataTypeInput(type.getParent(), projection),
                type.getJavaType(), type.getDataClass(), type.getColor(), type.getOwner(), type.isResolved());
        } finally {
            projection.exit(type);
        }
    }

    private static TypeRefInput typeRefInput(FlowTypeRef type, DefinitionProjection projection) {
        if (type == null) {
            return null;
        }
        if (!projection.enter(type)) {
            return null;
        }
        try {
            return new TypeRefInput(type.getTypeId(), type.getArguments().stream()
                .map(argument -> typeRefInput(argument, projection)).toList());
        } finally {
            projection.exit(type);
        }
    }

    private static Object definitionValue(Object value, DefinitionProjection projection) {
        if (value == null || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Number number) {
            return new ScalarInput(number.getClass().getName(), number.toString());
        }
        if (value instanceof Character character) {
            return new ScalarInput(Character.class.getName(), character.toString());
        }
        if (value instanceof Enum<?> enumeration) {
            return new ScalarInput(enumeration.getDeclaringClass().getName(), enumeration.name());
        }
        if (value instanceof Class<?> type) {
            return new ScalarInput(Class.class.getName(), type.getName());
        }
        if (value instanceof UUID identifier) {
            return new ScalarInput(UUID.class.getName(), identifier.toString());
        }
        if (value instanceof FlowDataType type) {
            return dataTypeInput(type, projection);
        }
        if (value instanceof FlowTypeRef type) {
            return typeRefInput(type, projection);
        }
        if (value instanceof Map<?, ?> map) {
            if (!projection.enter(map)) {
                return null;
            }
            try {
                Map<String, Object> values = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        projection.supported = false;
                        return null;
                    }
                    values.put(key, definitionValue(entry.getValue(), projection));
                }
                return Collections.unmodifiableMap(values);
            } finally {
                projection.exit(map);
            }
        }
        if (value instanceof Collection<?> collection) {
            if (!projection.enter(collection)) {
                return null;
            }
            try {
                List<Object> values = new ArrayList<>(collection.size());
                for (Object item : collection) {
                    values.add(definitionValue(item, projection));
                }
                return Collections.unmodifiableList(values);
            } finally {
                projection.exit(collection);
            }
        }
        if (value.getClass().isArray()) {
            if (!projection.enter(value)) {
                return null;
            }
            try {
                List<Object> values = new ArrayList<>(Array.getLength(value));
                for (int index = 0; index < Array.getLength(value); index++) {
                    values.add(definitionValue(Array.get(value, index), projection));
                }
                return Collections.unmodifiableList(values);
            } finally {
                projection.exit(value);
            }
        }
        projection.supported = false;
        return null;
    }

    record DefinitionRegistryInput(boolean supported, String defaultPluginId,
                                   Map<String, List<DefinitionInput>> definitions) {
        DefinitionRegistryInput {
            defaultPluginId = Objects.requireNonNull(defaultPluginId, "Default definition plugin is required");
            Map<String, List<DefinitionInput>> copy = new LinkedHashMap<>();
            definitions.forEach((pluginId, values) -> copy.put(pluginId, List.copyOf(values)));
            definitions = Collections.unmodifiableMap(copy);
        }
    }

    record DefinitionInput(String id, String displayName, CategoryInput category, List<PinInput> inputs,
                           List<PinInput> outputs, int color, int priority, boolean hidden, String hiddenReason,
                           String owner, String description, String handler, Object handlerConfig, boolean trigger,
                           String eventType, List<String> aliases, List<NodeDefinition.PinMapping> outputMappings,
                           int schemaVersion, NodeDefinition.NodeKind kind, AvailabilityInput availability,
                           String canonicalId, List<String> legacyIds, boolean deprecated, List<String> tags,
                           String authorizationPolicy, boolean sensitive, boolean destructive, String auditPolicy,
                           String confirmationPolicy, String clockDomain, AuthoredNodeMetadata authoredMetadata,
                           NodeDefinition.MigrationMapping migrationMapping, List<String> examples, String family,
                           boolean recommended, String replacementFor) {
    }

    record CategoryInput(String id, String displayName, int color, int priority) {
    }

    record AvailabilityInput(String plugin, String platform, String minimumVersion) {
    }

    record PinInput(String id, String runtimeName, String displayName, NodeDefinition.PinType type,
                    NodeDefinition.PinDirection direction, DataTypeInput dataType, TypeRefInput typeRef,
                    RepeatableInput repeatable, NodeDefinition.WidgetType widgetType, List<String> options,
                    String optionsSource, String defaultValue, ConstraintsInput constraints,
                    Map<String, String> visibleWhen, String description, boolean optional) {
    }

    record DataTypeInput(String id, String canonicalId, DataTypeInput parent, Class<?> javaType,
                         Class<?> dataClass, int color, String owner, boolean resolved) {
    }

    record TypeRefInput(String id, List<TypeRefInput> arguments) {
    }

    record RepeatableInput(String groupId, int minimum, int maximum, String itemLabel) {
    }

    record ConstraintsInput(Double minimum, Double maximum, Double step) {
    }

    record ScalarInput(String type, String value) {
    }

    private static final class DefinitionProjection {
        private final Set<Object> active = Collections.newSetFromMap(new IdentityHashMap<>());
        private boolean supported = true;

        private boolean enter(Object value) {
            if (active.size() >= CanonicalLimits.catalog().depth() || !active.add(value)) {
                supported = false;
                return false;
            }
            return true;
        }

        private void exit(Object value) {
            active.remove(value);
        }
    }

    record StartupCatalogReuseProof(boolean eligible,
                                    boolean activationComplete,
                                    boolean sameExtensionAuthority,
                                    boolean extensionDataEmpty,
                                    Set<ContractRef<ProviderId>> retirements,
                                    Set<ContractRef<ProviderId>> pendingRetirements,
                                    List<CatalogSourceIngestor.CatalogSource> expectedSources,
                                    List<CatalogSourceIngestor.CatalogSource> actualSources,
                                    Map<String, NodeHandler> expectedHandlers,
                                    Map<String, NodeHandler> actualHandlers,
                                    DefinitionRegistryInput expectedDefinitions,
                                    DefinitionRegistryInput actualDefinitions) {
        StartupCatalogReuseProof {
            retirements = Set.copyOf(Objects.requireNonNull(retirements, "Startup retirements are required"));
            pendingRetirements = Set.copyOf(Objects.requireNonNull(pendingRetirements, "Startup pending retirements are required"));
            expectedSources = List.copyOf(Objects.requireNonNull(expectedSources, "Expected startup sources are required"));
            actualSources = List.copyOf(Objects.requireNonNull(actualSources, "Actual startup sources are required"));
            expectedHandlers = Map.copyOf(Objects.requireNonNull(expectedHandlers, "Expected startup handlers are required"));
            actualHandlers = Map.copyOf(Objects.requireNonNull(actualHandlers, "Actual startup handlers are required"));
        }

        boolean exact() {
            return eligible
                && !activationComplete
                && sameExtensionAuthority
                && extensionDataEmpty
                && retirements.isEmpty()
                && pendingRetirements.isEmpty()
                && sameAuthoredCatalogSources(expectedSources, actualSources)
                && sameHandlerRegistrations(expectedHandlers, actualHandlers)
                && expectedDefinitions != null
                && expectedDefinitions.supported()
                && actualDefinitions != null
                && actualDefinitions.supported()
                && expectedDefinitions.equals(actualDefinitions);
        }

        String mismatch(RuntimeBindingRegistry registry, RuntimeRegistrySnapshot active) {
            if (!eligible) return "dynamic custom functions are present";
            if (activationComplete) return "startup activation is already complete";
            if (!sameExtensionAuthority) return "extension authority changed";
            if (!extensionDataEmpty) return "extension data is not empty";
            if (!retirements.isEmpty()) return "runtime retirements are staged";
            if (!pendingRetirements.isEmpty()) return "runtime retirements are pending";
            if (!sameAuthoredCatalogSources(expectedSources, actualSources)) return "authored sources changed";
            if (!sameHandlerRegistrations(expectedHandlers, actualHandlers)) return "handler registrations changed";
            if (expectedDefinitions == null || !expectedDefinitions.supported()) return "initial definitions are not comparable";
            if (actualDefinitions == null || !actualDefinitions.supported()) return "staged definitions are not comparable";
            if (!expectedDefinitions.equals(actualDefinitions)) return "definition projection changed";
            if (registry.snapshot() != active) return "runtime snapshot changed";
            return "runtime no-op replacement was unavailable";
        }
    }

    private record CatalogCoherenceIdentity(CatalogSnapshot catalog,
                                            RuntimeRegistrySnapshot runtime,
                                            ExtensionRegistryActivation.State projection) {
        private boolean matches(CatalogSnapshot activeCatalog,
                                 RuntimeRegistrySnapshot activeRuntime,
                                 ExtensionRegistryActivation.State activeProjection) {
            return catalog == activeCatalog && runtime == activeRuntime && projection == activeProjection;
        }
    }

    static Optional<RuntimeBindingRegistry.RuntimeReplacement> prepareStartupNoopRuntime(
        StartupCatalogReuseProof proof,
        RuntimeBindingRegistry registry,
        RuntimeRegistrySnapshot active
    ) {
        Objects.requireNonNull(proof, "Startup catalog reuse proof is required");
        Objects.requireNonNull(registry, "Runtime binding registry is required");
        Objects.requireNonNull(active, "Active runtime snapshot is required");
        if (!proof.exact() || registry.snapshot() != active) {
            return Optional.empty();
        }
        RuntimeBindingRegistry.RuntimeReplacement replacement = registry.prepareReplacement(List.of(), Set.of());
        if (!replacement.isNoop() || replacement.baseline() != active || registry.snapshot() != active) {
            replacement.close();
            return Optional.empty();
        }
        return Optional.of(replacement);
    }

    enum StartupNoopCommitStatus {
        COMMITTED,
        STALE
    }

    record StartupNoopCommitResult(StartupNoopCommitStatus status, boolean retryable) {
    }

    static StartupNoopCommitResult commitStartupNoopRuntime(RuntimeBindingRegistry registry,
                                                             RuntimeBindingRegistry.RuntimeReplacement replacement) {
        Objects.requireNonNull(registry, "Runtime binding registry is required");
        Objects.requireNonNull(replacement, "Runtime replacement is required");
        if (registry.snapshot() != replacement.baseline()) {
            return new StartupNoopCommitResult(StartupNoopCommitStatus.STALE, true);
        }
        replacement.close();
        return new StartupNoopCommitResult(StartupNoopCommitStatus.COMMITTED, false);
    }

    private void reportCatalogPublicationFailure(String diagnostic) {
        String message = "Flow catalog refresh is active, but typed publication delivery is pending: " + diagnostic;
        Log.warn(message);
        for (Session session : subscribedSessions) {
            sender.sendError(session, "CATALOG_PUBLICATION_UNAVAILABLE", message);
        }
    }

    private CatalogCompilationResult preflightCatalog(List<CatalogContribution> contributions, long generation,
                                                      RuntimeRegistrySnapshot runtimeSnapshot) {
        long started = TemporaryLifecycleDiagnostics.start();
        long compileWall = System.nanoTime();
        try {
            CatalogCompilationResult result = catalogCompilerFor(runtimeSnapshot)
                .compile(contributions, generation, catalogStartupIndexDirectory());
            TemporaryLifecycleDiagnostics.event("catalog_compile", started,
                Map.of("generation", generation, "count", contributions.size(),
                    "outcome", result.accepted() ? "complete" : "rejected", "diagnosticCount", result.diagnostics().size()));
            Log.info("Flow catalog compile completed in " + Math.max(0L, (System.nanoTime() - compileWall) / 1_000_000L)
                + " ms [" + contributions.size() + " contributions]");
            return result;
        } catch (RuntimeException exception) {
            TemporaryLifecycleDiagnostics.event("catalog_compile", started,
                Map.of("generation", generation, "count", contributions.size(), "outcome", "failed",
                    "errorType", exception.getClass().getSimpleName()));
            throw exception;
        }
    }

    static String startupCatalogRejection(String message, List<Diagnostic> diagnostics) {
        List<Diagnostic> values = diagnostics == null ? List.of() : diagnostics;
        List<Diagnostic> selected = new ArrayList<>(STARTUP_REJECTION_DIAGNOSTIC_LIMIT);
        for (Diagnostic diagnostic : values) {
            if (selected.size() == STARTUP_REJECTION_DIAGNOSTIC_LIMIT) {
                break;
            }
            if (!"CATALOG.CONTRIBUTION_REJECTED".equals(diagnostic.code())) {
                selected.add(diagnostic);
            }
        }
        for (Diagnostic diagnostic : values) {
            if (selected.size() == STARTUP_REJECTION_DIAGNOSTIC_LIMIT) {
                break;
            }
            if ("CATALOG.CONTRIBUTION_REJECTED".equals(diagnostic.code())) {
                selected.add(diagnostic);
            }
        }
        StringBuilder detail = new StringBuilder(message).append(": total=").append(values.size());
        for (Diagnostic diagnostic : selected) {
            detail.append("; code=").append(startupDiagnosticField(diagnostic.code()))
                .append(", owner=").append(startupDiagnosticField(diagnostic.evidence().get("owner")))
                .append(", subject=").append(startupDiagnosticField(diagnostic.evidence().get("subject")));
        }
        if (values.size() > selected.size()) {
            detail.append("; omitted=").append(values.size() - selected.size());
        }
        return detail.toString();
    }

    private static String startupDiagnosticField(Object value) {
        String text = value == null ? "unknown" : value.toString().strip();
        if (text.isBlank()) {
            return "unknown";
        }
        return text.length() <= STARTUP_REJECTION_FIELD_LIMIT ? text : text.substring(0, STARTUP_REJECTION_FIELD_LIMIT);
    }

    private CatalogCompiler catalogCompilerFor(RuntimeRegistrySnapshot runtimeSnapshot) {
        return new CatalogCompiler(catalogCompiler.contractVersion(), CatalogBindingProof.snapshot(runtimeSnapshot));
    }

    private RuntimeReplacementPlan prepareRuntimeReplacement(List<CatalogContribution> contributions) {
        return prepareRuntimeReplacement(contributions, definitionRegistry, handlerRegistry, Set.of());
    }

    private RuntimeReplacementPlan prepareRuntimeReplacement(
        List<CatalogContribution> contributions,
        Map<RuntimeOperationDescriptor, NodeDefinition> runtimeDefinitions
    ) {
        return prepareRuntimeReplacement(contributions, definitionRegistry, handlerRegistry, Set.of(),
            Set.copyOf(runtimeProviders), Set.copyOf(pendingRuntimeRetirements), Set.of(), runtimeDefinitions);
    }

    private RuntimeReplacementPlan prepareRuntimeReplacement(List<CatalogContribution> contributions,
                                                             NodeDefinitionRegistry definitionsRegistry,
                                                             HandlerRegistry handlers,
                                                             Set<ContractRef<ProviderId>> additionalRetirements) {
        return prepareRuntimeReplacement(contributions, definitionsRegistry, handlers, additionalRetirements,
            Set.copyOf(runtimeProviders), Set.copyOf(pendingRuntimeRetirements), Set.of());
    }

    private RuntimeReplacementPlan prepareRuntimeReplacement(List<CatalogContribution> contributions,
                                                             NodeDefinitionRegistry definitionsRegistry,
                                                             HandlerRegistry handlers,
                                                             Set<ContractRef<ProviderId>> additionalRetirements,
                                                             Set<ContractRef<ProviderId>> knownRuntimeProviders,
                                                             Set<ContractRef<ProviderId>> knownPendingRetirements) {
        return prepareRuntimeReplacement(contributions, definitionsRegistry, handlers, additionalRetirements,
            knownRuntimeProviders, knownPendingRetirements, Set.of());
    }

    private RuntimeReplacementPlan prepareRuntimeReplacement(List<CatalogContribution> contributions,
                                                             NodeDefinitionRegistry definitionsRegistry,
                                                             HandlerRegistry handlers,
                                                             Set<ContractRef<ProviderId>> additionalRetirements,
                                                             Set<ContractRef<ProviderId>> knownRuntimeProviders,
                                                             Set<ContractRef<ProviderId>> knownPendingRetirements,
                                                             Set<ContractRef<ProviderId>> reusableRuntimeProviders) {
        return prepareRuntimeReplacement(contributions, definitionsRegistry, handlers, additionalRetirements,
            knownRuntimeProviders, knownPendingRetirements, reusableRuntimeProviders, null);
    }

    private RuntimeReplacementPlan prepareRuntimeReplacement(List<CatalogContribution> contributions,
                                                             NodeDefinitionRegistry definitionsRegistry,
                                                             HandlerRegistry handlers,
                                                             Set<ContractRef<ProviderId>> additionalRetirements,
                                                             Set<ContractRef<ProviderId>> knownRuntimeProviders,
                                                             Set<ContractRef<ProviderId>> knownPendingRetirements,
                                                             Set<ContractRef<ProviderId>> reusableRuntimeProviders,
                                                             Map<RuntimeOperationDescriptor, NodeDefinition> preparedRuntimeDefinitions) {
        Map<RuntimeBindingKey, RuntimeOperationDescriptor> requirements = runtimeRequirements(contributions);
        RuntimeRegistrySnapshot baseline = runtimeSnapshotForPlanning();
        if (catalogRuntimeActivation != null && baseline != runtimeBindingRegistry.snapshot()) {
            throw new IllegalStateException("Flow runtime registry changed outside the active catalog runtime activation");
        }
        Set<ContractRef<ProviderId>> retiring = knownPendingRetirements.stream()
            .filter(baseline.providers()::containsKey)
            .collect(Collectors.toCollection(HashSet::new));
        retiring.addAll(additionalRetirements.stream().filter(baseline.providers()::containsKey).toList());
        for (ContractRef<ProviderId> provider : knownRuntimeProviders) {
            RuntimeProviderDescriptor descriptor = baseline.providers().get(provider);
            if (descriptor == null) {
                continue;
            }
            boolean replace = baseline.bindingValues().stream()
                .filter(binding -> provider.equals(binding.provider()))
                .anyMatch(binding -> {
                    RuntimeOperationDescriptor requirement = requirements.get(binding.key());
                    return requirement == null || !binding.executionFingerprint().equals(requirement.executionFingerprint());
                });
            if (replace) {
                retiring.add(provider);
            }
        }
        Map<OwnerId, List<RuntimeOperationDescriptor>> missingByOwner = new LinkedHashMap<>();
        for (RuntimeOperationDescriptor requirement : requirements.values()) {
            RuntimeBinding binding = baseline.bindings().get(requirement.key());
            if (binding == null || retiring.contains(binding.provider())) {
                missingByOwner.computeIfAbsent(requirement.capability().owner(), ignored -> new ArrayList<>()).add(requirement);
                continue;
            }
            if (!binding.executionFingerprint().equals(requirement.executionFingerprint())) {
                throw new IllegalStateException("Active Flow runtime binding fingerprint changed: "
                    + requirement.key().canonical());
            }
            RuntimeProviderDescriptor provider = baseline.providers().get(binding.provider());
            if (provider == null || provider.state() != RuntimeProviderState.ACTIVE) {
                throw new IllegalStateException("Active Flow runtime provider is unavailable: "
                    + binding.provider().canonicalText());
            }
        }
        List<RuntimeBindingRegistry.RuntimeProviderContribution> additions = new ArrayList<>();
        Map<RuntimeOperationDescriptor, NodeDefinition> runtimeDefinitionIndex = missingByOwner.isEmpty()
            ? null : preparedRuntimeDefinitions != null ? preparedRuntimeDefinitions
                : runtimeDefinitionIndex(definitionsRegistry, handlers);
        for (Map.Entry<OwnerId, List<RuntimeOperationDescriptor>> entry : missingByOwner.entrySet()) {
            OwnerId owner = entry.getKey();
            ContractRef<ProviderId> provider = nextRuntimeProvider(owner, knownRuntimeProviders, reusableRuntimeProviders);
            String providerVersion = "1.0.0";
            List<RuntimeBinding> bindings = entry.getValue().stream()
                .map(requirement -> RuntimeBinding.available(requirement, provider, providerVersion,
                    runtimeHandler(requirement, definitionsRegistry, handlers, runtimeDefinitionIndex)))
                .toList();
            additions.add(new RuntimeBindingRegistry.RuntimeProviderContribution(
                new RuntimeProviderDescriptor(provider, providerVersion, 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN),
                bindings));
        }
        RuntimeBindingRegistry.RuntimeReplacement replacement = runtimeBindingRegistry.prepareReplacement(additions, retiring);
        return new RuntimeReplacementPlan(replacement, additions.stream()
            .map(value -> value.provider().provider()).collect(Collectors.toSet()), retiring);
    }

    private void applyRuntimeReplacement(RuntimeReplacementPlan plan) {
        runtimeProviders.removeAll(plan.retiring());
        runtimeProviders.addAll(plan.additions());
        pendingRuntimeRetirements.removeAll(plan.retiring());
    }

    private Map<RuntimeBindingKey, RuntimeOperationDescriptor> runtimeRequirements(List<CatalogContribution> contributions) {
        Map<RuntimeBindingKey, RuntimeOperationDescriptor> requirements = new LinkedHashMap<>();
        for (CatalogContribution contribution : contributions) {
            for (RuntimeOperationDescriptor requirement : contribution.runtimeRequirements()) {
                RuntimeOperationDescriptor previous = requirements.putIfAbsent(requirement.key(), requirement);
                if (previous != null && !previous.equals(requirement)) {
                    throw new IllegalArgumentException("Flow runtime requirements conflict: " + requirement.key().canonical());
                }
            }
        }
        return requirements;
    }

    private ContractRef<ProviderId> nextRuntimeProvider(OwnerId owner) {
        return nextRuntimeProvider(owner, runtimeProviders, Set.of());
    }

    private ContractRef<ProviderId> nextRuntimeProvider(OwnerId owner,
                                                        Set<ContractRef<ProviderId>> knownRuntimeProviders) {
        return nextRuntimeProvider(owner, knownRuntimeProviders, Set.of());
    }

    private ContractRef<ProviderId> nextRuntimeProvider(OwnerId owner,
                                                        Set<ContractRef<ProviderId>> knownRuntimeProviders,
                                                        Set<ContractRef<ProviderId>> reusableRuntimeProviders) {
        Set<ContractRef<ProviderId>> providers = runtimeSnapshotForPlanning().providers().keySet();
        for (int index = 0; ; index++) {
            String id = index == 0 ? "flow" : "flow." + index;
            ContractRef<ProviderId> candidate = ContractRef.of(owner, ProviderId.of(id));
            if (reusableRuntimeProviders.contains(candidate)
                || (!providers.contains(candidate) && !knownRuntimeProviders.contains(candidate))) {
                return candidate;
            }
        }
    }

    private RuntimeRegistrySnapshot runtimeSnapshotForPlanning() {
        CatalogRuntimeActivation activation = catalogRuntimeActivation;
        return activation == null ? runtimeBindingRegistry.snapshot() : activation.active().runtime();
    }

    private RuntimeOperationHandler runtimeHandler(RuntimeOperationDescriptor requirement) {
        return runtimeHandler(requirement, definitionRegistry, handlerRegistry);
    }

    private RuntimeOperationHandler runtimeHandler(RuntimeOperationDescriptor requirement,
                                                   NodeDefinitionRegistry definitionsRegistry,
                                                   HandlerRegistry handlers) {
        return runtimeHandler(requirement, definitionsRegistry, handlers, null);
    }

    private RuntimeOperationHandler runtimeHandler(RuntimeOperationDescriptor requirement,
                                                   NodeDefinitionRegistry definitionsRegistry,
                                                   HandlerRegistry handlers,
                                                   Map<RuntimeOperationDescriptor, NodeDefinition> runtimeDefinitionIndex) {
        Object handlerIdValue = requirement.unknown().get("legacyHandlerId");
        if (!(handlerIdValue instanceof String handlerId) || handlerId.isBlank()) {
            NodeDefinition definition = runtimeDefinition(requirement, definitionsRegistry, handlers, runtimeDefinitionIndex);
            return invocation -> invokeEmptyRuntimeOperation(definition, requirement, invocation);
        }
        if (handlers == null) {
            throw new IllegalStateException("Flow handler registry is unavailable for " + handlerId);
        }
        NodeHandler handler = handlers.getHandler(handlerId);
        if (handler == null) {
            throw new IllegalStateException("Flow handler is unavailable for " + handlerId);
        }
        NodeDefinition definition = runtimeDefinition(requirement, definitionsRegistry, handlers, runtimeDefinitionIndex);
        if (CustomFunctionCallHandler.HANDLER_ID.equals(handlerId)) {
            return invocation -> invokeCustomFunction(definition, requirement, invocation);
        }
        if ("FunctionHandler".equals(handlerId) && "call_function".equals(definition.getHandlerConfig().get("operation"))) {
            return invocation -> invokeFunctionCall(definition, requirement, invocation);
        }
        return invocation -> invokeLegacyHandler(handler, definition, requirement, definitionsRegistry, invocation);
    }

    private CompletableFuture<RuntimeResult> invokeEmptyRuntimeOperation(NodeDefinition definition,
                                                                          RuntimeOperationDescriptor requirement,
                                                                          RuntimeInvocation invocation) {
        try {
            invocation.throwIfCancelled();
            establishRuntimeEvidence(invocation);
            return CompletableFuture.completedFuture(legacyRuntimeResult(requirement, definition,
                compiledTriggerOutputs(definition, invocation.runtimeContext()), flowOutputAliases(definition)));
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private NodeDefinition runtimeDefinition(RuntimeOperationDescriptor requirement) {
        return runtimeDefinition(requirement, definitionRegistry, handlerRegistry);
    }

    private Map<RuntimeOperationDescriptor, NodeDefinition> runtimeDefinitionIndex(NodeDefinitionRegistry definitionsRegistry,
                                                                                    HandlerRegistry handlers) {
        Map<RuntimeOperationDescriptor, NodeDefinition> index = new LinkedHashMap<>();
        for (NodeDefinition definition : definitionsRegistry.getAllDefinitions().values()) {
            if (definition == null) {
                continue;
            }
            try {
                index.putIfAbsent(runtimeOperationDescriptor(definition, handlers), definition);
            } catch (RuntimeException ignored) {
            }
        }
        return index;
    }

    private NodeDefinition runtimeDefinition(RuntimeOperationDescriptor requirement,
                                             NodeDefinitionRegistry definitionsRegistry,
                                             HandlerRegistry handlers) {
        return runtimeDefinition(requirement, definitionsRegistry, handlers, null);
    }

    private NodeDefinition runtimeDefinition(RuntimeOperationDescriptor requirement,
                                             NodeDefinitionRegistry definitionsRegistry,
                                             HandlerRegistry handlers,
                                             Map<RuntimeOperationDescriptor, NodeDefinition> runtimeDefinitionIndex) {
        if (runtimeDefinitionIndex != null) {
            NodeDefinition definition = runtimeDefinitionIndex.get(requirement);
            if (definition != null) {
                return definition;
            }
            throw new IllegalStateException("Flow runtime binding has no source node definition: " + requirement.key().canonical());
        }
        return definitionsRegistry.getAllDefinitions().values().stream()
            .filter(definition -> definition != null)
            .filter(definition -> {
                try {
                    return requirement.equals(runtimeOperationDescriptor(definition, handlers));
                } catch (RuntimeException exception) {
                    return false;
                }
            })
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Flow runtime binding has no source node definition: " + requirement.key().canonical()));
    }

    private CompletableFuture<RuntimeResult> invokeLegacyHandler(NodeHandler handler, NodeDefinition definition,
                                                                 RuntimeOperationDescriptor requirement,
                                                                 NodeDefinitionRegistry definitionsRegistry,
                                                                 RuntimeInvocation invocation) {
        FlowContext context = null;
        try {
            invocation.throwIfCancelled();
            authorizeLegacyInvocation(definition, requirement, invocation);
            establishRuntimeEvidence(invocation);
            LegacyInvocationContext legacyContext = legacyInvocationContext(invocation.runtimeContext());
            Map<String, Object> inputs = legacyInputs(definition, invocation.inputs());
            FlowNode node = new FlowNode(definition.getId(), 0, 0, inputs);
            node.setHandlerConfig(definition.getHandlerConfig());
            FlowGraph graph = new FlowGraph("runtime", Map.of("runtime", node), List.of(), List.of());
            String operation = definition.getHandlerConfig().get("operation") instanceof String value ? value : "";
            boolean returning = "FunctionHandler".equals(definition.getHandler())
                && ("return_value".equals(operation) || "function_output".equals(operation))
                && invocation.scope() != null && invocation.scope().functionSignature() != null;
            if ("FunctionHandler".equals(definition.getHandler()) && invocation.scope() != null
                && invocation.scope().functionSignature() != null) {
                graph.setFunction(true);
                graph.setFunctionInputs(invocation.scope().functionSignature().inputs().stream().map(parameter ->
                    new FlowGraph.FunctionParameter(parameter.id(), parameter.unknown().get("name") instanceof String name
                        && !name.isBlank() ? name : parameter.id().canonicalText(), FlowDataType.ANY)).toList());
                graph.setFunctionOutputs(invocation.scope().functionSignature().outputs().stream().map(parameter ->
                    new FlowGraph.FunctionParameter(parameter.id(), parameter.unknown().get("name") instanceof String name
                        && !name.isBlank() ? name : parameter.id().canonicalText(), FlowDataType.ANY)).toList());
            }
            FlowRuntime runtime = new FlowRuntime(graph, runtimeTypeAdapters, executor != null ? executor.getGlobalVariables() : Map.of(),
                legacyContext.variables(), definitionsRegistry, storage.legacyRuntimeGate(), invocation.invocationId(),
                invocation.scope() == null ? null : returning ? new LinkedHashMap<>(invocation.scope().locals()) : invocation.scope().locals());
            Map<FunctionParameterId, Object> functionInputs = new LinkedHashMap<>();
            if (invocation.scope() != null) {
                invocation.scope().functionInputs().values().forEach((id, value) -> functionInputs.put(id, legacyValue(value)));
            }
            if (returning) {
                runtime.callFunctionById(graph, "runtime", functionInputs);
            } else {
                functionInputs.forEach(runtime::setFunctionInput);
            }
            List<String> deferredOutputs = new CopyOnWriteArrayList<>();
            context = new FlowContext(runtime, legacyContext.player(), legacyContext.event(), deferredOutputs::add, executor,
                invocation.principal(), invocation.invocationId(), invocation.idempotencyKey(), invocation.runtimeContext(),
                invocation.deadlineMillis(), node, invocation.cancellationToken());
            FlowContext invocationContext = context;
            handler.execute(context, node);
            context.finishSynchronousCapture();
            List<String> synchronousOutputs = context.consumeTriggeredOutputs();
            String triggeredOutput = runtime.consumeTriggeredOutput();
            if (triggeredOutput != null && !triggeredOutput.isBlank() && !synchronousOutputs.contains(triggeredOutput)) {
                synchronousOutputs = new ArrayList<>(synchronousOutputs);
                synchronousOutputs.add(triggeredOutput);
            }
            if (context.getAsyncOperations().isEmpty()) {
                if (!deferredOutputs.isEmpty()) {
                    synchronousOutputs = new ArrayList<>(synchronousOutputs);
                    synchronousOutputs.addAll(deferredOutputs);
                }
                invocation.throwIfCancelled();
                return CompletableFuture.completedFuture(legacyRuntimeResult(requirement, definition, runtime,
                    synchronousOutputs));
            }
            return completeLegacyOutputCapture(synchronousOutputs, deferredOutputs,
                () -> new ArrayList<>(invocationContext.getAsyncOperations().values()), triggeredOutputs -> {
                invocation.throwIfCancelled();
                return legacyRuntimeResult(requirement, definition, runtime, triggeredOutputs);
            });
        } catch (Throwable exception) {
            if (context == null) {
                return CompletableFuture.failedFuture(exception);
            }
            context.finishSynchronousCapture();
            FlowContext invocationContext = context;
            return completeLegacyFailure(() -> new ArrayList<>(invocationContext.getAsyncOperations().values()), exception);
        }
    }

    static <T> CompletableFuture<T> completeLegacyFailure(Supplier<List<CompletableFuture<?>>> pendingSupplier,
                                                         Throwable failure) {
        Set<CompletableFuture<?>> awaited = Collections.newSetFromMap(new IdentityHashMap<>());
        return awaitPendingOperations(pendingSupplier, awaited, Objects.requireNonNull(failure, "Legacy invocation failure is required"))
            .thenCompose(ignored -> CompletableFuture.failedFuture(failure));
    }

    static <T> CompletableFuture<T> completeLegacyOutputCapture(List<String> synchronousOutputs,
                                                                 List<String> deferredOutputs,
                                                                 Supplier<List<CompletableFuture<?>>> pendingSupplier,
                                                                 Function<List<String>, T> completion) {
        Set<CompletableFuture<?>> awaited = Collections.newSetFromMap(new IdentityHashMap<>());
        return awaitPendingOperations(pendingSupplier, awaited, null).thenApply(ignored -> {
            List<String> triggeredOutputs = new ArrayList<>(synchronousOutputs);
            triggeredOutputs.addAll(deferredOutputs);
            return completion.apply(triggeredOutputs);
        });
    }

    private static CompletableFuture<Void> awaitPendingOperations(Supplier<List<CompletableFuture<?>>> pendingSupplier,
                                                                   Set<CompletableFuture<?>> awaited,
                                                                   Throwable firstFailure) {
        List<CompletableFuture<?>> pending = pendingSupplier.get().stream()
            .filter(awaited::add)
            .toList();
        if (pending.isEmpty()) {
            return firstFailure == null
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.failedFuture(firstFailure);
        }
        return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
            .handle((ignored, failure) -> firstFailure == null ? failure : firstFailure)
            .thenCompose(failure -> awaitPendingOperations(pendingSupplier, awaited, failure));
    }

    private void authorizeLegacyInvocation(NodeDefinition definition, RuntimeOperationDescriptor requirement,
                                           RuntimeInvocation invocation) {
        String policy = definition == null ? "trusted_server_flow" : definition.getAuthorizationPolicy();
        policy = policy == null || policy.isBlank() ? "trusted_server_flow" : policy.strip().toLowerCase(Locale.ROOT);
        if ("public".equals(policy) || "trusted_server_flow".equals(policy)) {
            return;
        }
        RuntimePrincipal principal = invocation.principal();
        if (principal == null) {
            throw new IllegalStateException("Flow legacy handler authorization denied: a runtime principal is required for " + policy);
        }
        FlowRuntimeSecurityBoundary security = runtimeSecurityBoundary;
        RuntimeExecutionContext executionContext = invocation.executionContext();
        if (security == null || executionContext == null || !security.principalAllowed(executionContext)) {
            throw new IllegalStateException("Flow legacy handler authorization denied: the runtime principal is not trusted for " + policy);
        }
        if ("external_webhook".equals(policy)) {
            boolean trustedSystem = principal.kind() == RuntimePrincipal.Kind.SYSTEM;
            boolean capabilityAuthorized = security.authorize(executionContext.authority(), requirement.capability());
            if (trustedSystem || capabilityAuthorized) {
                return;
            }
        }
        throw new IllegalStateException("Flow legacy handler authorization denied: " + policy);
    }

    private CompletableFuture<RuntimeResult> invokeCustomFunction(NodeDefinition definition, RuntimeOperationDescriptor requirement,
                                                                   RuntimeInvocation invocation) {
        try {
            invocation.throwIfCancelled();
            establishRuntimeEvidence(invocation);
            LegacyInvocationContext legacyContext = legacyInvocationContext(invocation.runtimeContext());
            if (executor == null) {
                throw new IllegalStateException("Flow executor is unavailable for custom function runtime dispatch");
            }
            Object functionIdValue = definition.getHandlerConfig().get("functionId");
            if (!(functionIdValue instanceof String functionId) || functionId.isBlank()) {
                throw new IllegalArgumentException("Custom function runtime binding has no function ID");
            }
            FunctionSourceDocument function = boundFunction(invocation, functionId);
            Map<String, Object> inputs = new LinkedHashMap<>();
            for (Map.Entry<PinId, TypedValue> entry : invocation.inputs().entrySet()) {
                NodeDefinition.PinDefinition pin = inputPin(definition, entry.getKey());
                if (pin != null && pin.getType() == NodeDefinition.PinType.FLOW) {
                    continue;
                }
                FunctionParameterContract parameter = sourceParameter(function.signature().inputs(), entry.getKey().value(), "function-input-");
                if (parameter == null || pin == null || !parameter.type().equals(entry.getValue().type())) {
                    throw new IllegalArgumentException("Custom Function Input Does Not Match Its Declared Parameter: " + entry.getKey().value());
                }
                String parameterKey = parameter.id().canonicalText();
                if (inputs.containsKey(parameterKey)) {
                    throw new IllegalArgumentException("Custom function runtime input is supplied more than once: " + parameterKey);
                }
                inputs.put(parameterKey, entry.getValue());
            }
            RuntimePrincipal principal = invocation.principal();
            CorrelationId invocationId = invocation.invocationId();
            if (principal == null || invocationId == null) {
                throw new IllegalStateException("Compiled custom Function invocation requires an authenticated principal and root ID");
            }
            String childIdentity = "runtime-operation|" + invocation.idempotencyKey() + "|" + functionId;
            FlowExecutor.FunctionInvocationContext functionContext = new FlowExecutor.FunctionInvocationContext(
                principal, invocationId, invocation.runtimeContext(), invocation.deadlineMillis()).child(childIdentity);
            return executor.withInheritedLiveEventScope(invocation.runtimeContext(), invocationId, functionContext.invocationId(),
                    () -> executor.executeFunctionSource(function, legacyContext.player(), legacyContext.event(), inputs, legacyContext.variables(),
                        functionContext, invocation.cancellationToken()))
                .thenApply(outputs -> customFunctionRuntimeResult(requirement, definition, function, outputs,
                    flowOutputAliases(definition)));
        } catch (RuntimeException | Error exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private FunctionSourceDocument boundFunction(RuntimeInvocation invocation, String functionId) {
        var stored = storage.getCoreGraph("function", functionId)
            .orElseThrow(() -> new IllegalStateException("The Typed Function Is Unavailable: " + functionId));
        FunctionSourceDocument source = stored.functionSourceDocument();
        ServerResourceLocator resource = new ServerResourceLocator(runtimeServerId,
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("function")), functionId);
        if (source == null || stored.envelope().assetActivationState() != ResourceActivationState.ACTIVE
            || !resource.equals(source.signature().function().resource())
            || source.signature().revision().value() != stored.envelope().assetRevision()
            || source.graph().revision() != stored.envelope().assetRevision()) {
            throw new IllegalStateException("The Typed Function Is Not Active At Its Committed Revision: " + functionId);
        }
        if (invocation.scope() != null) {
            FunctionBinding binding = invocation.functionBindings().stream().filter(value -> value.function().equals(resource))
                .findFirst().orElseThrow(() -> new IllegalStateException("The Function Is Not Declared In The Compiled Parent Closure: " + functionId));
            if (binding.revision() != source.signature().revision().value()
                || !sourceParametersMatch(binding.inputs(), source.signature().inputs())
                || !sourceParametersMatch(binding.outputs(), source.signature().outputs())) {
                throw new IllegalStateException("The Function Signature No Longer Matches The Compiled Parent Closure: " + functionId);
            }
        }
        return source;
    }

    private static boolean sourceParametersMatch(List<FunctionParameter> bound, List<FunctionParameterContract> declared) {
        if (bound.size() != declared.size()) {
            return false;
        }
        for (FunctionParameter parameter : bound) {
            FunctionParameterContract contract = declared.stream().filter(value -> parameter.parameterId().equals(value.id())).findFirst().orElse(null);
            if (contract == null || !parameter.type().equals(contract.type())) {
                return false;
            }
        }
        return true;
    }

    private static FunctionParameterContract sourceParameter(List<FunctionParameterContract> parameters, String key, String prefix) {
        if (key == null) {
            return null;
        }
        return parameters.stream().filter(parameter -> key.equals(parameter.id().canonicalText())
            || key.equals(prefix + parameter.id().canonicalText())).findFirst().orElse(null);
    }

    private CompletableFuture<RuntimeResult> invokeFunctionCall(NodeDefinition definition, RuntimeOperationDescriptor requirement,
                                                                RuntimeInvocation invocation) {
        try {
            invocation.throwIfCancelled();
            authorizeLegacyInvocation(definition, requirement, invocation);
            establishRuntimeEvidence(invocation);
            Object target = legacyValue(invocation.inputs().get(PinId.of("function")));
            String functionId;
            if (target instanceof ServerResourceLocator resource && runtimeServerId.equals(resource.serverId())
                && Set.of("builtin", "restudio.resync").contains(resource.owner().canonicalText())
                && "function".equals(resource.resourceType().canonicalText())) {
                functionId = resource.id();
            } else if (target instanceof String text && !text.isBlank()) {
                functionId = text;
            } else {
                throw new IllegalArgumentException("A Current Typed Function Reference Is Required");
            }
            FunctionSourceDocument function = boundFunction(invocation, functionId);
            Object arguments = legacyValue(invocation.inputs().get(PinId.of("arguments")));
            Map<String, Object> inputs = FunctionCallSupport.normalizeSourceArguments(function, arguments);
            LegacyInvocationContext context = legacyInvocationContext(invocation.runtimeContext());
            FlowExecutor.FunctionInvocationContext child = new FlowExecutor.FunctionInvocationContext(invocation.principal(),
                invocation.invocationId(), invocation.runtimeContext(), invocation.deadlineMillis())
                .child("runtime-operation|" + invocation.idempotencyKey() + "|" + functionId);
            boolean continueOnFailure = Boolean.TRUE.equals(legacyValue(invocation.inputs().get(PinId.of("continue_on_failure"))));
            return executor.withInheritedLiveEventScope(invocation.runtimeContext(), invocation.invocationId(), child.invocationId(),
                    () -> executor.executeFunctionSource(function, context.player(), context.event(), inputs, context.variables(), child,
                        invocation.cancellationToken()))
                .handle((outputs, failure) -> {
                    invocation.throwIfCancelled();
                    if (failure != null && !continueOnFailure) {
                        throw new CompletionException(failure);
                    }
                    Map<String, Object> values = new LinkedHashMap<>();
                    values.put("results", failure == null ? outputs : Map.of());
                    values.put("result", failure == null ? FlowOperationResult.success(outputs)
                        : FlowOperationResult.failure("FUNCTION_EXECUTION_FAILED", "Function Execution Failed", Map.of("functionId", functionId)));
                    return legacyRuntimeResult(requirement, definition, values, flowOutputAliases(definition));
                });
        } catch (RuntimeException | Error failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private static void establishRuntimeEvidence(RuntimeInvocation invocation) {
        RuntimeExecutionContext executionContext = invocation.executionContext();
        if (executionContext == null) {
            return;
        }
        RuntimeSemantics.ThreadMode actualThread = switch (executionContext.thread()) {
            case CURRENT, COMPOSED -> executionContext.thread();
            case MAIN, ASYNCHRONOUS -> Bukkit.isPrimaryThread() ? RuntimeSemantics.ThreadMode.MAIN : RuntimeSemantics.ThreadMode.ASYNCHRONOUS;
        };
        executionContext.establishThread(actualThread);
        executionContext.establishEffect(executionContext.effect());
    }

    private RuntimeResult legacyRuntimeResult(RuntimeOperationDescriptor requirement, NodeDefinition definition,
                                              FlowRuntime runtime, List<String> triggeredOutputs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (NodeDefinition.PinDefinition pin : definition.getOutputs()) {
            Object value = runtime.getNodeOutput("runtime", pin.getRuntimeName());
            if (value == null && !pin.getRuntimeName().equals(pin.getName())) {
                value = runtime.getNodeOutput("runtime", pin.getName());
            }
            values.put(pin.getName(), value);
        }
        return legacyRuntimeResult(requirement, definition, values, triggeredOutputs);
    }

    private RuntimeResult legacyRuntimeResult(RuntimeOperationDescriptor requirement, NodeDefinition definition,
                                              Map<String, Object> values, List<String> triggeredOutputs) {
        if (requirement.outputs().isEmpty()) {
            return RuntimeResult.success();
        }
        Map<PinId, TypedValue> outputs = new LinkedHashMap<>();
        Map<PinId, TypeExpr> types = new LinkedHashMap<>();
        for (RuntimeOperationDescriptor.Pin pin : requirement.outputPins()) {
            types.put(pin.id(), pin.type());
        }
        List<NodeDefinition.PinDefinition> pins = definition.getOutputs();
        for (NodeDefinition.PinDefinition pin : pins) {
            TypeExpr type = types.get(pin.getId());
            if (type == null) {
                throw new IllegalStateException("Runtime operation is missing output pin " + pin.getId().value());
            }
            Object value = outputValue(values, pin);
            if (pin.getType() == NodeDefinition.PinType.FLOW) {
                value = containsPinAlias(triggeredOutputs, pin) ? pin.getId().value() : null;
            }
            outputs.put(pin.getId(), runtimeTypedValue(type, value));
        }
        return RuntimeResult.success(outputs, null);
    }

    private Map<String, Object> legacyInputs(NodeDefinition definition, Map<PinId, TypedValue> invocationInputs) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        for (Map.Entry<PinId, TypedValue> entry : invocationInputs.entrySet()) {
            NodeDefinition.PinDefinition pin = inputPin(definition, entry.getKey());
            String runtimeName = pin != null ? pin.getRuntimeName() : entry.getKey().value();
            inputs.put(runtimeName, legacyValue(entry.getValue()));
        }
        return inputs;
    }

    private static NodeDefinition.PinDefinition inputPin(NodeDefinition definition, PinId id) {
        return definition == null || id == null ? null : definition.getInputs().stream()
            .filter(pin -> pin != null && (id.equals(pin.getId()) || id.value().equals(pin.getRuntimeName())))
            .findFirst()
            .orElse(null);
    }

    private static FlowGraph.FunctionParameter functionParameter(FlowGraph function, NodeDefinition.PinDefinition pin) {
        return CustomFunctionNodeDefinitions.parameterForPin(function, pin, NodeDefinition.PinDirection.INPUT);
    }

    private static NodeDefinition.PinDefinition customFunctionPin(NodeDefinition definition,
                                                                   FlowGraph.FunctionParameter parameter,
                                                                   NodeDefinition.PinDirection direction) {
        if (definition == null || parameter == null) {
            return null;
        }
        PinId expected = CustomFunctionNodeDefinitions.parameterPinId(parameter, direction);
        List<NodeDefinition.PinDefinition> pins = direction == NodeDefinition.PinDirection.INPUT
            ? definition.getInputs() : definition.getOutputs();
        return pins.stream().filter(pin -> pin != null && expected.equals(pin.getId())).findFirst().orElse(null);
    }

    private RuntimeResult customFunctionRuntimeResult(RuntimeOperationDescriptor requirement, NodeDefinition definition,
            FunctionSourceDocument source, Map<String, Object> values, List<String> triggeredOutputs) {
        Map<PinId, TypedValue> outputs = new LinkedHashMap<>();
        for (RuntimeOperationDescriptor.Pin expected : requirement.outputPins()) {
            NodeDefinition.PinDefinition pin = definition.getOutputs().stream()
                .filter(value -> expected.id().equals(value.getId())).findFirst()
                .orElseThrow(() -> new IllegalStateException("The Function Definition Is Missing An Output Pin: " + expected.id().value()));
            Object value;
            if (pin.getType() == NodeDefinition.PinType.FLOW) {
                value = containsPinAlias(triggeredOutputs, pin) ? pin.getId().value() : null;
            } else {
                FunctionParameterContract parameter = sourceParameter(source.signature().outputs(), expected.id().value(), "function-output-");
                if (parameter == null || !expected.type().equals(parameter.type())) {
                    throw new IllegalStateException("The Function Output Does Not Match Its Declared Parameter: " + expected.id().value());
                }
                String id = parameter.id().canonicalText();
                Object authoredName = parameter.unknown().get("name");
                String name = authoredName instanceof String text && !text.isBlank() ? text : id;
                value = values.containsKey(id) ? values.get(id) : values.get(name);
            }
            outputs.put(expected.id(), runtimeTypedValue(expected.type(), value));
        }
        return outputs.isEmpty() ? RuntimeResult.success() : RuntimeResult.success(outputs, null);
    }

    private RuntimeResult customFunctionRuntimeResult(RuntimeOperationDescriptor requirement,
                                                             NodeDefinition definition,
                                                             FlowGraph function,
                                                             Map<String, Object> values,
                                                             List<String> triggeredOutputs) {
        if (requirement.outputs().isEmpty()) {
            return RuntimeResult.success();
        }
        Map<PinId, TypedValue> outputs = new LinkedHashMap<>();
        Map<PinId, TypeExpr> types = new LinkedHashMap<>();
        for (RuntimeOperationDescriptor.Pin pin : requirement.outputPins()) {
            types.put(pin.id(), pin.type());
        }
        List<NodeDefinition.PinDefinition> pins = definition.getOutputs();
        for (NodeDefinition.PinDefinition pin : pins) {
            TypeExpr type = types.get(pin.getId());
            if (type == null) {
                throw new IllegalStateException("Runtime operation is missing output pin " + pin.getId().value());
            }
            Object value = customFunctionOutputValue(function, values, pin);
            if (pin.getType() == NodeDefinition.PinType.FLOW) {
                value = containsPinAlias(triggeredOutputs, pin) ? pin.getId().value() : null;
            }
            outputs.put(pin.getId(), runtimeTypedValue(type, value));
        }
        return RuntimeResult.success(outputs, null);
    }

    private static Object customFunctionOutputValue(FlowGraph function, Map<String, Object> values,
                                                    NodeDefinition.PinDefinition pin) {
        if (values == null || pin == null) {
            return null;
        }
        String pinId = pin.getId().value();
        if (values.containsKey(pinId)) {
            return values.get(pinId);
        }
        FlowGraph.FunctionParameter parameter = CustomFunctionNodeDefinitions.parameterForPin(function, pin,
            NodeDefinition.PinDirection.OUTPUT);
        if (parameter == null) {
            return null;
        }
        String parameterId = CustomFunctionNodeDefinitions.parameterKey(parameter);
        if (values.containsKey(parameterId)) {
            return values.get(parameterId);
        }
        String runtimeName = pin.getRuntimeName();
        if (runtimeName != null && values.containsKey(runtimeName)) {
            FlowGraph.FunctionParameter resolved = CustomFunctionNodeDefinitions.parameterForKey(function, runtimeName,
                NodeDefinition.PinDirection.OUTPUT, true);
            if (parameter.equals(resolved)) {
                return values.get(runtimeName);
            }
        }
        String displayName = parameter.getName();
        if (displayName != null && values.containsKey(displayName)) {
            FlowGraph.FunctionParameter resolved = CustomFunctionNodeDefinitions.parameterForKey(function, displayName,
                NodeDefinition.PinDirection.OUTPUT, true);
            if (parameter.equals(resolved)) {
                return values.get(displayName);
            }
        }
        return null;
    }

    private static List<String> flowOutputAliases(NodeDefinition definition) {
        if (definition == null || definition.getOutputs() == null) {
            return List.of();
        }
        List<String> aliases = new ArrayList<>();
        for (NodeDefinition.PinDefinition pin : definition.getOutputs()) {
            if (pin != null && pin.getType() == NodeDefinition.PinType.FLOW) {
                aliases.add(pin.getName());
                if (!pin.getRuntimeName().equals(pin.getName())) {
                    aliases.add(pin.getRuntimeName());
                }
            }
        }
        return List.copyOf(aliases);
    }

    private static boolean containsPinAlias(List<String> values, NodeDefinition.PinDefinition pin) {
        return values != null && (values.contains(pin.getName()) || values.contains(pin.getRuntimeName()));
    }

    private static Object outputValue(Map<String, Object> values, NodeDefinition.PinDefinition pin) {
        if (values == null || pin == null) {
            return null;
        }
        for (String alias : List.of(pin.getRuntimeName(), pin.getName())) {
            if (alias != null && values.containsKey(alias)) {
                return values.get(alias);
            }
        }
        return null;
    }

    private Map<String, Object> compiledTriggerOutputs(NodeDefinition definition, CompiledRuntimeContext context) {
        if (definition == null || context == null || !definition.isTrigger()) {
            return Map.of();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (NodeDefinition.PinDefinition pin : definition.getOutputs()) {
            if (pin == null || pin.getType() == NodeDefinition.PinType.FLOW) {
                continue;
            }
            String pinName = pin.getName();
            String runtimeName = pin.getRuntimeName();
            if ("event.player".equals(pinName) || "event.player".equals(runtimeName)) {
                if (context.player() != null) {
                    Player player = Bukkit.getPlayer(context.player().uniqueId());
                    if (player == null) {
                        throw new IllegalStateException("Compiled player context is no longer available");
                    }
                    values.put(pinName, player);
                }
                continue;
            }
            TypedValue value = runtimeVariable(context.variables(), runtimeName, pinName);
            if (value != null) {
                values.put(pinName, legacyValue(value));
            }
        }
        return values;
    }

    private static TypedValue runtimeVariable(Map<String, TypedValue> variables, String runtimeName, String pinName) {
        if (variables == null || variables.isEmpty()) {
            return null;
        }
        if (runtimeName != null && variables.containsKey(runtimeName)) {
            return variables.get(runtimeName);
        }
        if (pinName != null && variables.containsKey(pinName)) {
            return variables.get(pinName);
        }
        return null;
    }

    private TypedValue runtimeTypedValue(TypeExpr type, Object value) {
        return CompiledRuntimeValueCodec.encode(runtimeServerId, type, value);
    }

    private Object legacyValue(TypedValue value) {
        return CompiledRuntimeValueCodec.decode(runtimeServerId, value);
    }

    private LegacyInvocationContext legacyInvocationContext(CompiledRuntimeContext context) {
        if (context == null) {
            return new LegacyInvocationContext(null, null, new HashMap<>());
        }
        Player player = null;
        if (context.player() != null) {
            player = Bukkit.getPlayer(context.player().uniqueId());
            if (player == null) {
                throw new IllegalStateException("Compiled player context is no longer available");
            }
        }
        Map<String, Object> variables = new HashMap<>();
        for (Map.Entry<String, TypedValue> entry : context.variables().entrySet()) {
            if (entry.getValue().state() != TypedValue.State.ABSENT) {
                variables.put(entry.getKey(), legacyValue(entry.getValue()));
            }
        }
        if (player != null) {
            variables.put("event.player", player);
        }
        return new LegacyInvocationContext(player, null, variables);
    }

    private record LegacyInvocationContext(Player player, Event event, Map<String, Object> variables) {
    }

    public synchronized boolean drainRuntimeBindingsForOwner(String owner) {
        RuntimeBindingRetirement retirement = stageRuntimeBindingRetirementForOwner(owner);
        try {
            return !retirement.blocked();
        } finally {
            retirement.close();
        }
    }

    static boolean drainRuntimeBindingsForOwner(RuntimeBindingRegistry registry, String owner) {
        Objects.requireNonNull(registry, "Flow runtime binding registry is required");
        if (owner == null || owner.isBlank()) {
            return true;
        }
        OwnerId ownerId = OwnerId.of(owner);
        Set<ContractRef<ProviderId>> providers = registry.snapshot().bindingValues().stream()
            .filter(binding -> ownerId.equals(binding.descriptor().capability().owner()))
            .map(RuntimeBinding::provider)
            .collect(Collectors.toCollection(HashSet::new));
        if (providers.isEmpty()) {
            return true;
        }
        if (providers.stream().anyMatch(provider -> registry.snapshot().bindingValues().stream()
            .filter(binding -> provider.equals(binding.provider()))
            .anyMatch(binding -> !ownerId.equals(binding.descriptor().capability().owner())))) {
            return false;
        }
        RuntimeBindingRegistry.RuntimeReplacement replacement = registry.prepareReplacement(List.of(), providers);
        try {
            return replacement.blockedLeases() == 0;
        } finally {
            replacement.close();
        }
    }

    public void refreshSharedResource(String type, String resourceId, boolean deleted) {
        if (!Set.of(ReSyncResourceCatalog.FLOW, ReSyncResourceCatalog.FUNCTION, ReSyncResourceCatalog.COMMAND).contains(type)) {
            return;
        }
        blueprintHandler.refreshGraphBinding(type, resourceId, deleted);
        if (ReSyncResourceCatalog.FUNCTION.equals(type) && customFunctionDefinitionChanged(resourceId, deleted)) {
            refreshCustomFunctionDefinitions();
        }
    }

    private boolean customFunctionDefinitionChanged(String resourceId, boolean deleted) {
        String nodeId = CustomFunctionNodeDefinitions.NODE_PREFIX + resourceId;
        List<NodeDefinition> current = definitionRegistry.getDefinitionsForPlugin(CustomFunctionNodeDefinitions.PLUGIN_ID)
            .stream().filter(definition -> nodeId.equals(definition.getId())).toList();
        return customFunctionDefinitionChanged(current, deleted,
            () -> storage.getCoreGraph(ReSyncResourceCatalog.FUNCTION, resourceId)
                .map(value -> value.functionSourceDocument()).orElse(null));
    }

    static boolean customFunctionDefinitionChanged(List<NodeDefinition> current, boolean deleted,
                                                    Supplier<FunctionSourceDocument> storedGraph) {
        Objects.requireNonNull(current, "Current custom Function definitions are required");
        Objects.requireNonNull(storedGraph, "Stored custom Function graph supplier is required");
        if (deleted) {
            return !current.isEmpty();
        }
        if (current.isEmpty()) {
            return true;
        }
        try {
            FunctionSourceDocument source = storedGraph.get();
            if (source == null) {
                return !current.isEmpty();
            }
            NodeDefinition candidate = CustomFunctionNodeDefinitions.buildDefinition(source);
            return current.size() != 1 || !sameDefinition(current.getFirst(), candidate);
        } catch (RuntimeException exception) {
            return true;
        }
    }

    static boolean sameDefinition(NodeDefinition first, NodeDefinition second) {
        DefinitionProjection firstProjection = new DefinitionProjection();
        DefinitionProjection secondProjection = new DefinitionProjection();
        DefinitionInput firstInput = definitionInput(first, firstProjection);
        DefinitionInput secondInput = definitionInput(second, secondProjection);
        return firstProjection.supported && secondProjection.supported && firstInput.equals(secondInput);
    }

    static final class IdempotentCoreMutationSubscriber implements Consumer<CoreResourceMutationTransition> {
        private final Consumer<CoreResourceMutationTransition> subscriber;
        private final Map<ServerResourceLocator, CoreResourceMutationCheckpoint> checkpoints = new HashMap<>();

        IdempotentCoreMutationSubscriber(Consumer<CoreResourceMutationTransition> subscriber) {
            this.subscriber = Objects.requireNonNull(subscriber, "Core mutation subscriber is required");
        }

        @Override
        public void accept(CoreResourceMutationTransition transition) {
            Objects.requireNonNull(transition, "Core mutation transition is required");
            CoreResourceMutationCheckpoint checkpoint = transition.checkpoint();
            synchronized (checkpoints) {
                CoreResourceMutationCheckpoint current = checkpoints.get(transition.locator());
                if (current != null && checkpoint.revision() < current.revision()) {
                    return;
                }
                if (current != null && checkpoint.revision() == current.revision()) {
                    if (current.equals(checkpoint)) {
                        return;
                    }
                    throw new IllegalStateException("Core mutation subscriber revision collision: "
                        + transition.locator().canonicalText() + "@" + transition.revision());
                }
                subscriber.accept(transition);
                checkpoints.put(transition.locator(), checkpoint);
            }
        }
    }

    static final class CoreMutationRuntimeSubscriber implements Consumer<CoreResourceMutationTransition> {
        private final Predicate<CoreResourceMutationTransition> alreadyRefreshed;
        private final Consumer<CoreResourceMutationTransition> refresh;

        CoreMutationRuntimeSubscriber(Predicate<CoreResourceMutationTransition> alreadyRefreshed,
                                      Consumer<CoreResourceMutationTransition> refresh) {
            this.alreadyRefreshed = Objects.requireNonNull(alreadyRefreshed,
                "Core mutation refresh proof is required");
            this.refresh = Objects.requireNonNull(refresh, "Core mutation runtime refresh is required");
        }

        @Override
        public void accept(CoreResourceMutationTransition transition) {
            CoreResourceMutationTransition checked = Objects.requireNonNull(transition,
                "Core mutation transition is required");
            if (ReSyncResourceCatalog.COMMAND.equals(checked.locator().resourceType().value())
                && alreadyRefreshed.test(checked)) {
                return;
            }
            refresh.accept(checked);
        }
    }

    private NodeRegistrySnapshot buildFullNodeRegistrySnapshot() {
        return nodeRegistryHandler.buildFullSnapshot();
    }

    public String getNodeRegistryChecksum() {
        return nodeRegistryHandler.computeRegistryChecksum();
    }

    public void setNodeRegistryDiagnosticsSupplier(Supplier<Map<String, Object>> diagnosticsSupplier) {
        nodeRegistryHandler.setDiagnosticsSupplier(diagnosticsSupplier);
    }

    public void setCustomFunctionDefinitionRefresh(Supplier<Boolean> refresh) {
        customFunctionDefinitionRefresh = Objects.requireNonNull(refresh, "Custom Function definition refresh is required");
    }

    public void setConversionAdapterRegistry(TypeAdapterRegistry conversionAdapters) {
        nodeRegistryHandler.setConversionAdapterRegistry(conversionAdapters);
    }

    public void broadcastOptionCatalog(String sourceId) {
        if (sourceId != null && !sourceId.isBlank()) {
            providerOptionQuerySourceChange.accept(sourceId);
            optionCatalogHandler.broadcastCatalog(sourceId);
        }
    }

    private void broadcastCustomContentCatalogs() {
        optionCatalogHandler.broadcastCustomContentCatalogs();
        if (optionCatalogRegistry != null) {
            optionCatalogRegistry.providers().stream().filter(provider -> "custom_content".equals(provider.providerId()))
                .map(OptionCatalogProvider::sourceId).forEach(providerOptionQuerySourceChange);
        }
        OptionCatalogCaptureExecutor captureExecutor = optionCatalogCaptureExecutor;
        if (captureExecutor != null) {
            RuntimeDataOptionCatalogService.refresh(optionCatalogRegistry, captureExecutor, CustomContentItemDataAdapter.ID)
                .whenComplete((ignored, failure) -> publishRuntimeDataCategoryRefresh(failure));
        } else {
            publishRuntimeDataCategoryRefresh(null);
        }
    }

    private void publishRuntimeDataCategoryRefresh(Throwable failure) {
        if (failure != null) {
            Log.warn("Runtime data category catalog refresh failed: " + failure.getMessage());
        }
        providerOptionQuerySourceChange.accept(RuntimeDataOptionCatalogService.CATEGORY_SOURCE);
        optionCatalogHandler.broadcastCatalog(RuntimeDataOptionCatalogService.CATEGORY_SOURCE);
    }

    public void setProviderOptionQuerySourceChange(Consumer<String> sourceChange) {
        providerOptionQuerySourceChange = sourceChange != null ? sourceChange : ignored -> {
        };
    }

    public void setOptionCatalogCaptureExecutor(OptionCatalogCaptureExecutor captureExecutor) {
        optionCatalogCaptureExecutor = Objects.requireNonNull(captureExecutor, "Option catalog capture executor is required");
        optionCatalogRegistry.bindCapture(captureExecutor::capture);
        optionCatalogHandler.setCaptureExecutor(captureExecutor);
    }

    CompletionStage<Void> prepareRuntimeDataCategories() {
        OptionCatalogCaptureExecutor captureExecutor = Objects.requireNonNull(optionCatalogCaptureExecutor,
            "Option catalog capture executor is required");
        return RuntimeDataOptionCatalogService.prewarm(optionCatalogRegistry, captureExecutor)
            .whenComplete((ignored, failure) -> publishRuntimeDataCategoryRefresh(failure));
    }

    public int getSubscribedSessionCount() {
        return subscribedSessions.size();
    }

    public Optional<String> lastCatalogPublicationFailureCode() {
        return lastCatalogRefreshPublicationFailureCode.isBlank()
            ? catalogPublicationPolicy.lastFailureCode()
            : Optional.of(lastCatalogRefreshPublicationFailureCode);
    }

    public Optional<String> activeCatalogPublicationKey() {
        return catalogRuntimeActivation.activePublicationKey().map(CatalogCacheKey::canonicalText);
    }

    public synchronized boolean restoreCatalogPublicationKey(String canonicalKey) {
        if (canonicalKey == null || canonicalKey.isBlank()) {
            return true;
        }
        try {
            CatalogCacheKey key = CatalogCacheKey.parseCanonicalText(canonicalKey);
            if (!key.snapshotChecksum().equals(activeCatalogSnapshot().contentChecksum())) {
                return false;
            }
            catalogRuntimeActivation.publishPublicationKey(key);
            return true;
        } catch (RuntimeException exception) {
            Log.warn("Flow catalog publication key restoration was rejected: " + exception.getMessage());
            return false;
        }
    }

    public boolean hasPendingCatalogPublications() {
        return catalogPublicationPolicy.hasPending();
    }

    public List<String> pendingCatalogPublicationSessionIds() {
        return catalogPublicationPolicy.pendingSessionIds();
    }

    public Map<String, Object> catalogPublicationDiagnostics() {
        FlowCatalogPublicationPacketHandler.DiagnosticSnapshot snapshot = catalogPublicationHandler.diagnosticSnapshot();
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("queuedWork", snapshot.queuedWork());
        diagnostics.put("waitingWork", snapshot.waitingWork());
        diagnostics.put("executorQueue", snapshot.executorQueue());
        diagnostics.put("outboxEntries", snapshot.outboxEntries());
        diagnostics.put("outboxBytes", snapshot.outboxBytes());
        diagnostics.put("reservedOutboxEntries", snapshot.reservedOutboxEntries());
        diagnostics.put("reservedOutboxBytes", snapshot.reservedOutboxBytes());
        diagnostics.put("pendingSessions", pendingCatalogPublicationSessionIds());
        diagnostics.put("lastFailure", lastCatalogPublicationFailureCode().orElse(""));
        diagnostics.put("shuttingDown", snapshot.shuttingDown());
        return Map.copyOf(diagnostics);
    }

    public boolean supportsResourceActivation(String type) {
        return false;
    }

    public List<String> resourceIds(String type) {
        var adapter = resources.get(type);
        return adapter != null ? adapter.listIds() : List.of();
    }

    public List<String> resourceTypes() {
        return resources.typeIds();
    }

    public JsonObject resourceJson(String type, String resourceId) {
        return JsonParser.parseString(resources.serialize(type, resourceId)).getAsJsonObject();
    }

    public void createResource(String type, JsonObject resource) {
        rejectLegacyResourceMutation();
    }

    public void updateResource(String type, JsonObject resource) {
        rejectLegacyResourceMutation();
    }

    public void deleteResource(String type, String resourceId) {
        rejectLegacyResourceMutation();
    }

    private void rejectLegacyResourceMutation() {
        throw new IllegalArgumentException(LEGACY_RESOURCE_MUTATION_UNAVAILABLE);
    }

    public void setResourceEnabled(String type, String resourceId, boolean enabled) {
        rejectLegacyResourceMutation();
    }

    public void registerWorkspaceDocumentProvider(FlowWorkspaceDocumentProvider provider) {
        workspaces.registerDocumentProvider(provider);
    }

    public QuickEditResult startQuickEdit(Player player) {
        pruneQuickEditSessions();
        if (player == null) {
            return QuickEditResult.failure("Player Required");
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            return QuickEditResult.failure("Hold An Item");
        }
        List<Session> targets = quickEditTargets(player);
        if (targets.isEmpty()) {
            return QuickEditResult.failure("No Remotely Client Connected");
        }
        if (targets.size() > 1) {
            return QuickEditResult.failure("Multiple Remotely Clients Connected");
        }
        CustomContentDefinition existingContent = existingContentDefinition(held);
        if (existingContent != null) {
            sendOpenCustomContent(targets.getFirst(), existingContent);
            return QuickEditResult.success("Content Opened");
        }
        String sessionId = UUID.randomUUID().toString();
        CustomContentDefinition definition = quickEditDefinition(sessionId, held);
        quickEditSessions.put(sessionId, new QuickEditSession(sessionId, player.getUniqueId(), normalizedSnapshot(held), System.currentTimeMillis()));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", sessionId);
        payload.put("player", player.getName());
        payload.put("definition", definition);
        String json = gson.toJson(payload);
        for (Session target : targets) {
            sender.sendJsonPayload(target, ReSyncProtocolContract.FLOW_PACKET_QUICK_EDIT_OPEN, json, "QUICK_EDIT_TOO_LARGE", "Quick edit data exceeds maximum size");
        }
        return QuickEditResult.success("Quick Edit Opened");
    }

    private List<Session> quickEditTargets(Player player) {
        Session linked = sessionLinkService != null ? sessionLinkService.getLinkedSession(player.getUniqueId()) : null;
        if (isOpenFlowSession(linked)) {
            return List.of(linked);
        }
        List<Session> open = subscribedSessions.stream()
            .filter(this::isOpenFlowSession)
            .toList();
        return open.size() == 1 ? open : open.isEmpty() ? List.of() : List.of(open.getFirst(), open.get(1));
    }

    private CustomContentDefinition existingContentDefinition(ItemStack item) {
        if (item == null || !item.hasItemMeta() || customContentStorage == null) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        String contentId = meta.getPersistentDataContainer().get(new NamespacedKey(ReSync.getInstance(), "content_id"), PersistentDataType.STRING);
        if (contentId == null || contentId.isBlank()) {
            return null;
        }
        return customContentStorage.get(contentId);
    }

    private void sendOpenCustomContent(Session session, CustomContentDefinition definition) {
        if (definition.getGraph() == null && definition.getFlowId() != null && !definition.getFlowId().isBlank()) {
            FlowGraph graph = storage.getGraph(definition.getFlowId());
            if (graph != null) {
                definition.setGraph(graph);
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", definition.getId());
        payload.put("content", definition);
        sender.sendJsonPayload(session, ReSyncProtocolContract.FLOW_PACKET_OPEN_CUSTOM_CONTENT, gson.toJson(payload), "CONTENT_OPEN_TOO_LARGE", "Content open data exceeds maximum size");
    }

    private boolean isOpenFlowSession(Session session) {
        return session != null
            && subscribedSessions.contains(session)
            && session.getConnection() != null
            && session.getConnection().isOpen();
    }

    private CustomContentDefinition quickEditDefinition(String sessionId, ItemStack item) {
        CustomContentDefinition definition = new CustomContentDefinition();
        definition.setId("quickedit_" + sessionId.substring(0, 8));
        definition.setFlowId("quickedit." + sessionId);
        definition.setType("item");
        definition.setProvider("vanilla");
        definition.setMaterial(item.getType().name());
        definition.setDisplayName(customDisplayName(item));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (meta.hasLore() && meta.getLore() != null) {
                definition.setLore(meta.getLore());
            } else {
                definition.setLore(List.of());
            }
            if (meta.hasCustomModelData()) {
                definition.setCustomModelData(meta.getCustomModelData());
            }
        }
        definition.setComponents(quickEditAttributeService.customComponentsFromStack(item));
        definition.setTags(List.of());
        definition.setAbilities(List.of());
        return definition;
    }

    private String customDisplayName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return meta.getDisplayName();
        }
        return "";
    }

    private void handleQuickEditApply(Session session, ByteBuffer buffer) {
        pruneQuickEditSessions();
        byte[] jsonBytes = new byte[buffer.remaining()];
        buffer.get(jsonBytes);
        JsonObject root = JsonParser.parseString(new String(jsonBytes, StandardCharsets.UTF_8)).getAsJsonObject();
        String sessionId = text(root, "sessionId");
        if (sessionId.isBlank()) {
            sendQuickEditFailure(session, "", "Missing quick edit session");
            return;
        }
        QuickEditSession editSession = quickEditSessions.get(sessionId);
        if (editSession == null) {
            sendQuickEditFailure(session, sessionId, "Quick edit session expired");
            return;
        }
        JsonElement definitionElement = root.get("definition");
        if (definitionElement == null || !definitionElement.isJsonObject()) {
            sendQuickEditFailure(session, sessionId, "Missing item definition");
            return;
        }
        CustomContentDefinition definition = gson.fromJson(definitionElement, CustomContentDefinition.class);
        applyQuickEdit(session, editSession, definition);
    }

    private void applyQuickEdit(Session session, QuickEditSession editSession, CustomContentDefinition definition) {
        if (definition == null) {
            sendQuickEditFailure(session, editSession.sessionId(), "Missing item definition");
            return;
        }
        Player player = Bukkit.getPlayer(editSession.playerId());
        if (player == null || !player.isOnline()) {
            sendQuickEditFailure(session, editSession.sessionId(), "Player is offline");
            return;
        }
        playerDataAdmission.mutatePlayer("flow-quick-edit:" + editSession.playerId(), player,
            () -> applyQuickEditAdmitted(session, editSession, definition, player));
    }

    private void applyQuickEditAdmitted(Session session, QuickEditSession editSession,
                                        CustomContentDefinition definition, Player player) {
        ItemStack current = player.getInventory().getItemInMainHand();
        if (current == null || current.getType().isAir()) {
            sendQuickEditFailure(session, editSession.sessionId(), "Hold An Item");
            return;
        }
        if (!sameEditedItem(current, editSession.originalItem())) {
            sendQuickEditFailure(session, editSession.sessionId(), "Held Item Changed");
            return;
        }
        String materialName = definition.getMaterial();
        Material material = Material.matchMaterial(materialName == null || materialName.isBlank() ? "STICK" : materialName);
        if (material == null || !material.isItem()) {
            sendQuickEditFailure(session, editSession.sessionId(), "Invalid material: " + materialName);
            return;
        }
        definition.setComponents(quickEditAttributeService.customComponentsForMaterial(material.name(), definition.getComponents()));
        List<Map<String, Object>> errors = quickEditAttributeService.validate(material.name(), definition.getComponents());
        if (!errors.isEmpty()) {
            sendQuickEditFailure(session, editSession.sessionId(), quickEditError(errors));
            return;
        }
        int amount = current.getAmount();
        ItemStack edited = current.clone();
        edited.setAmount(Math.max(1, amount));
        if (edited.getType() != material) {
            edited.setType(material);
        }
        try {
            edited = quickEditAttributeService.applyComponents(edited, definition.getComponents());
        } catch (RuntimeException failure) {
            sendQuickEditFailure(session, editSession.sessionId(), failure.getMessage());
            return;
        }
        ItemMeta meta = edited.getItemMeta();
        if (meta != null) {
            boolean hasNameComponent = hasAnyComponent(definition, "minecraft:custom_name", "minecraft:item_name");
            boolean hasLoreComponent = hasAnyComponent(definition, "minecraft:lore");
            if (!hasNameComponent) {
                if (definition.getDisplayName() != null && !definition.getDisplayName().isBlank()) {
                    meta.displayName(TextFormatter.parseItemName(definition.getDisplayName()));
                } else {
                    meta.displayName(null);
                }
            }
            if (!hasLoreComponent) {
                if (definition.getLore() != null && !definition.getLore().isEmpty()) {
                    meta.lore(definition.getLore().stream().map(TextFormatter::parseItemLore).toList());
                } else {
                    meta.lore(null);
                }
            }
            meta.setCustomModelData(definition.getCustomModelData());
            edited.setItemMeta(meta);
        }
        player.getInventory().setItemInMainHand(edited);
        quickEditSessions.put(editSession.sessionId(), new QuickEditSession(editSession.sessionId(), editSession.playerId(), normalizedSnapshot(edited), System.currentTimeMillis()));
        sender.sendJsonPayload(session, ReSyncProtocolContract.FLOW_PACKET_QUICK_EDIT_RESULT, gson.toJson(Map.of("sessionId", editSession.sessionId(), "status", "applied")), "QUICK_EDIT_RESULT_TOO_LARGE", "Quick edit result exceeds maximum size");
        player.sendMessage("§8[ReSync] §aQuick Edit Applied");
    }

    private boolean hasAnyComponent(CustomContentDefinition definition, String... keys) {
        if (definition == null || definition.getComponents() == null || definition.getComponents().isEmpty()) {
            return false;
        }
        for (String key : keys) {
            if (definition.getComponents().containsKey(key)) {
                return true;
            }
        }
        return false;
    }

    private ItemStack normalizedSnapshot(ItemStack item) {
        ItemStack snapshot = item.clone();
        snapshot.setAmount(1);
        return snapshot;
    }

    private boolean sameEditedItem(ItemStack current, ItemStack original) {
        if (current == null || original == null) {
            return false;
        }
        ItemStack currentSnapshot = normalizedSnapshot(current);
        return currentSnapshot.isSimilar(original);
    }

    private void sendQuickEditFailure(Session session, String sessionId, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", sessionId != null ? sessionId : "");
        payload.put("status", "failed");
        payload.put("message", message != null && !message.isBlank() ? message : "Apply Failed");
        sender.sendJsonPayload(session, ReSyncProtocolContract.FLOW_PACKET_QUICK_EDIT_RESULT, gson.toJson(payload), "QUICK_EDIT_RESULT_TOO_LARGE", "Quick edit result exceeds maximum size");
    }

    private String quickEditError(List<Map<String, Object>> errors) {
        Map<String, Object> first = errors.getFirst();
        Object component = first.getOrDefault("component", first.getOrDefault("id", ""));
        Object message = first.getOrDefault("message", "Invalid component");
        String prefix = component != null && !component.toString().isBlank() ? component + ": " : "";
        return prefix + message;
    }

    private String text(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        return object.get(key).getAsString();
    }

    private void pruneQuickEditSessions() {
        long cutoff = System.currentTimeMillis() - QUICK_EDIT_SESSION_TTL_MS;
        quickEditSessions.entrySet().removeIf(entry -> entry.getValue().lastTouchedAt() < cutoff);
    }

    public record QuickEditResult(boolean success, String message) {
        public static QuickEditResult success(String message) {
            return new QuickEditResult(true, message);
        }

        public static QuickEditResult failure(String message) {
            return new QuickEditResult(false, message);
        }
    }

    private record QuickEditSession(String sessionId, UUID playerId, ItemStack originalItem, long lastTouchedAt) {
    }

    static CatalogBinding startupCatalogBinding(CatalogBinding persisted, ContentHash contentChecksum,
                                                 ContentHash bindingManifestHash, String canonicalContent) {
        return startupCatalogBinding(persisted, contentChecksum, bindingManifestHash, canonicalContent, 0L);
    }

    static CatalogBinding startupCatalogBinding(CatalogBinding persisted, ContentHash contentChecksum,
                                                 ContentHash bindingManifestHash, String canonicalContent,
                                                 long publicationGeneration) {
        if (publicationGeneration < 0L) {
            throw new IllegalArgumentException("Catalog publication generation cannot be negative");
        }
        long persistedGeneration = persisted != null ? persisted.generation() : 1L;
        boolean bindingChanged = persisted != null && (!persisted.catalogChecksum().equals(contentChecksum)
            || !persisted.bindingManifestHash().equals(bindingManifestHash));
        long highWater = Math.max(persistedGeneration, publicationGeneration);
        long generation = bindingChanged || publicationGeneration > persistedGeneration
            ? Math.addExact(highWater, 1L) : persistedGeneration;
        CatalogBinding candidate = new CatalogBinding(generation, contentChecksum, bindingManifestHash);
        long provenGeneration = CoreCatalogEvolution.select(candidate).flatMap(evolution ->
            evolution.bootstrapProof(persisted, candidate, canonicalContent))
            .map(proof -> proof.target().generation()).orElse(generation);
        return new CatalogBinding(Math.max(generation, provenGeneration), contentChecksum, bindingManifestHash);
    }

    static long latestPublishedGeneration(ServerId serverId, CatalogPublicationReceiptStore receiptStore) {
        Objects.requireNonNull(serverId, "Flow server identity is required");
        Objects.requireNonNull(receiptStore, "Catalog publication receipt store is required");
        return receiptStore.baselines().stream()
            .map(CatalogPublicationReceipt::publicationKey)
            .filter(key -> serverId.equals(key.serverId()))
            .mapToLong(CatalogCacheKey::catalogGeneration)
            .max().orElse(0L);
    }

    private CatalogBinding loadCatalogGeneration(FlowStorage storage) {
        Path activationRecord = replacementActivationRecordFile(storage);
        if (Files.isRegularFile(activationRecord)) {
            try {
                ReplacementActivationRecord.Values values = ReplacementActivationRecord.read(dataRoot(storage));
                return new CatalogBinding(values.catalogGeneration(), ContentHash.of(values.catalogChecksum()),
                    ContentHash.of(values.runtimeBindingManifestHash()));
            } catch (IOException | RuntimeException exception) {
                throw new IllegalStateException("Flow replacement activation record could not be loaded", exception);
            }
        }
        return null;
    }

    private Path replacementActivationRecordFile(FlowStorage storage) {
        return ReplacementActivationRecord.path(dataRoot(storage));
    }

    private Path dataRoot(FlowStorage storage) {
        Path assets = Objects.requireNonNull(storage, "Flow storage is required").getAssetsPath().toAbsolutePath().normalize();
        Path root = assets.getParent();
        if (root == null) {
            throw new IllegalStateException("Flow asset root has no data root");
        }
        return root;
    }

    private Path catalogStartupIndexDirectory() {
        return dataRoot(storage).resolve(CatalogStartupIndex.DIRECTORY);
    }

    public void sendFlowData(Session session, FlowGraph graph) {
        sender.sendFlowData(session, graph);
    }

    public void sendGuiData(Session session, GuiDefinition gui) {
        sender.sendGuiData(session, gui);
    }

    public void sendScoreboardData(Session session, ScoreboardDefinition scoreboard) {
        sender.sendScoreboardData(session, scoreboard);
    }

    public void sendTabData(Session session, TabDefinition tab) {
        sender.sendTabData(session, tab);
    }

    public void sendGuiState(Session session, boolean editable, String guiId, String flowId) {
        sender.sendGuiState(session, editable, guiId, flowId);
    }

    public void setTraceService(FlowTraceService traceService) {
        this.traceService = traceService;
    }

    public void setDebugService(FlowDebugService debugService) {
        this.debugService = debugService;
    }

    public void setExecutor(FlowExecutor executor) {
        this.executor = executor;
    }

    public void setCompiledTriggerExecution(CompiledTriggerExecution compiledTriggerExecution) {
        if (compiledTriggerExecution != null) {
            compiledTriggerExecution.bindCoreStorage(storage, runtimeServerId);
        }
        this.compiledTriggerExecution = compiledTriggerExecution;
    }

    public void setRuntimePrincipalAuthority(RuntimePrincipalAuthority runtimePrincipalAuthority) {
        this.runtimePrincipalAuthority = runtimePrincipalAuthority;
        this.runtimeSecurityBoundary = runtimePrincipalAuthority == null ? null : new FlowRuntimeSecurityBoundary(
            runtimePrincipalAuthority.authority(), runtimeBindingRegistry::snapshot, runtimePrincipalAuthority);
    }

    public void setAuthorityEpoch(AuthorityEpoch authorityEpoch) {
        sender.setAuthorityEpoch(Objects.requireNonNull(authorityEpoch, "Flow authority epoch is required"));
    }

    private void handleTraceToggle(Session session, ByteBuffer buffer) {
        if (traceService == null) {
            sender.sendTraceSnapshot(session, List.of());
            return;
        }
        boolean enabled = buffer.remaining() <= 0 || buffer.get() != 0;
        traceService.setEnabled(enabled);
        if (enabled) {
            ensureTraceSink();
        }
        sender.sendTraceSnapshot(session, traceService.snapshot());
    }

    private void handleTraceClear(Session session) {
        if (traceService != null) {
            traceService.clear();
        }
        sender.sendTraceSnapshot(session, List.of());
    }

    private void handleDebugCommand(Session session, ByteBuffer buffer) {
        if (debugService == null) {
            sender.sendDebugSnapshot(session, Map.of("type", "snapshot", "enabled", false));
            return;
        }
        String json = readRemaining(buffer);
        JsonObject root = json == null || json.isBlank() ? new JsonObject() : gson.fromJson(json, JsonObject.class);
        String action = string(root, "action");
        switch (action) {
            case "enable" -> {
                ensureTraceSink();
                debugService.setEnabled(true);
            }
            case "disable" -> debugService.setEnabled(false);
            case "pause" -> debugService.pauseAll();
            case "resume" -> debugService.resume(string(root, "sessionId"), "Resumed", "");
            case "resumeAll" -> debugService.resumeAll("Resumed");
            case "stepInto" -> debugService.resume(string(root, "sessionId"), "Step Into", "into");
            case "stepOver" -> debugService.resume(string(root, "sessionId"), "Step Over", "over");
            case "stepOut" -> debugService.resume(string(root, "sessionId"), "Step Out", "out");
            case "stop" -> debugService.stop(string(root, "sessionId"));
            case "clear" -> debugService.clear();
            case "breakpoints" -> debugService.setBreakpoints(string(root, "graphId"), nodeSet(root.get("nodeIds")));
            case "testRun" -> startDebugTestRun(session, root);
            default -> {
            }
        }
        sender.sendDebugSnapshot(session, debugService.snapshot());
    }

    private void handleFunctionTest(Session session, ByteBuffer buffer) {
        JsonObject request;
        try {
            String json = readRemaining(buffer);
            request = json == null || json.isBlank() ? new JsonObject() : gson.fromJson(json, JsonObject.class);
        } catch (RuntimeException exception) {
            sendFunctionTestFailure(session, "", "", "INVALID_FIXTURE", "Function fixture is invalid");
            return;
        }
        String requestId = string(request, "requestId");
        String graphId = string(request, "graphId");
        if (!trustedDebugSession(session) || runtimePrincipalAuthority == null) {
            sendFunctionTestFailure(session, requestId, graphId, "AUTHENTICATION_REQUIRED", "An authenticated client session is required");
            return;
        }
        CorrelationId invocationId = functionTestInvocationId(request);
        if (invocationId == null) {
            sendFunctionTestFailure(session, requestId, graphId, "INVALID_INVOCATION_ID", "A canonical invocation ID is required");
            return;
        }
        RuntimePrincipal principal = runtimePrincipalAuthority.issueAuthenticatedClient(session.getIdentity().clientId());
        if (!runtimePrincipalAuthority.trusts(principal, runtimePrincipalAuthority.authority())) {
            sendFunctionTestFailure(session, requestId, graphId, "AUTHORIZATION_DENIED", "The client principal is not trusted");
            return;
        }
        FlowGraph graph;
        try {
            graph = request.has("graph") && request.get("graph").isJsonObject()
                ? FlowSerializer.deserialize(request.get("graph").toString())
                : graphId != null && !graphId.isBlank() ? storage.getGraph(graphId) : null;
        } catch (RuntimeException exception) {
            sendFunctionTestFailure(session, requestId, graphId, "INVALID_FUNCTION_GRAPH", "Function graph is invalid");
            return;
        }
        if (executor == null) {
            sendFunctionTestFailure(session, requestId, graphId, "EXECUTOR_UNAVAILABLE", "Flow executor is unavailable");
            return;
        }
        if (graph == null || !graph.isFunction()) {
            sendFunctionTestFailure(session, requestId, graphId, "FUNCTION_NOT_FOUND", "Function not found: " + graphId);
            return;
        }
        Map<String, Object> inputs = objectMap(request.get("inputs"));
        Map<String, Object> expectedOutputs = objectMap(request.get("expectedOutputs"));
        Map<String, Object> serverContext = new LinkedHashMap<>(objectMap(request.get("serverContext")));
        serverContext.put("runtime.sessionId", session.getSessionId());
        serverContext.put("runtime.clientId", session.getIdentity().clientId());
        long timeoutMillis = Math.clamp(longValue(request, "timeoutMillis", 5000L), 1L, 30000L);
        Clock fixtureClock;
        try {
            ZoneId zone = ZoneId.of(defaultText(string(request, "zoneId"), "UTC"));
            String instant = string(request, "clockInstant");
            fixtureClock = instant == null || instant.isBlank() ? Clock.system(zone) : Clock.fixed(Instant.parse(instant), zone);
        } catch (RuntimeException exception) {
            sendFunctionTestFailure(session, requestId, graphId, "INVALID_FIXTURE_CLOCK", "Fixture clock or time zone is invalid");
            return;
        }
        FlowFunctionTestHarness harness = new FlowFunctionTestHarness(executor, fixtureClock);
        long requestedDeadline;
        try {
            requestedDeadline = stableRequestedDeadline(session, principal, invocationId, request, timeoutMillis);
        } catch (IllegalArgumentException exception) {
            sendFunctionTestFailure(session, requestId, graphId, "INVALID_DEADLINE", "Function deadline is invalid");
            return;
        }
        FlowFunctionTestHarness.Fixture fixture = new FlowFunctionTestHarness.Fixture(defaultText(string(request, "name"), "Fixture"), inputs,
            expectedOutputs, serverContext, null, null, Duration.ofMillis(timeoutMillis), principal, invocationId, null,
            requestedDeadline);
        harness.run(graph, fixture).whenComplete((result, failure) -> {
            if (failure != null) {
                sendFunctionTestFailure(session, requestId, graphId, "FUNCTION_TEST_FAILED", failure.getMessage());
                return;
            }
            sender.sendJsonPayload(session, ReSyncProtocolContract.FLOW_PACKET_FUNCTION_TEST_RESULT,
                gson.toJson(Map.of("requestId", requestId, "graphId", graphId, "result", result)), "FUNCTION_TEST_RESULT_TOO_LARGE",
                "Function test result exceeds maximum size");
        });
    }

    private void sendFunctionTestFailure(Session session, String requestId, String graphId, String code, String message) {
        sender.sendJsonPayload(session, ReSyncProtocolContract.FLOW_PACKET_FUNCTION_TEST_RESULT,
            gson.toJson(Map.of("requestId", defaultText(requestId, ""), "graphId", defaultText(graphId, ""), "error",
                Map.of("code", code, "message", defaultText(message, code)))), "FUNCTION_TEST_RESULT_TOO_LARGE", "Function test result exceeds maximum size");
    }

    private void handleResourceActivation(Session session, ByteBuffer buffer) {
        ResourceActivationRequest request;
        try {
            request = gson.fromJson(readRemaining(buffer), ResourceActivationRequest.class);
        } catch (RuntimeException exception) {
            sendResourceActivationResult(session, null, false, "Request is invalid");
            return;
        }
        if (request == null || request.type() == null || request.type().isBlank() || request.resourceId() == null || request.resourceId().isBlank()
            || request.requestId() == null || request.requestId().isBlank()) {
            sendResourceActivationResult(session, request, false, "Resource is required");
            return;
        }
        synchronized (resourceActivationResults) {
            ResourceActivationResult previous = resourceActivationResults.get(request.requestId());
            if (previous != null) {
                if (previous.type().equals(request.type()) && previous.resourceId().equals(request.resourceId()) && previous.enabled() == request.enabled()) {
                    sendResourceActivationResult(session, previous);
                } else {
                    sendResourceActivationResult(session, request, false, "This update request is invalid");
                }
                return;
            }
            if (!resources.metadata().stream().anyMatch(metadata -> request.type().equals(metadata.getTypeId()) && metadata.isAvailable())) {
                sendResourceActivationResult(session, request, false, "This resource cannot be enabled or disabled");
                return;
            }
            sendResourceActivationResult(session, request, false, LEGACY_RESOURCE_MUTATION_UNAVAILABLE);
        }
    }

    private void sendResourceActivationResult(Session session, ResourceActivationRequest request, boolean success, String message) {
        sendResourceActivationResult(session, request, success, message, false);
    }

    private void sendResourceActivationResult(Session session, ResourceActivationRequest request, boolean success, String message, boolean editorError) {
        ResourceActivationResult result = new ResourceActivationResult(success, request != null ? request.type() : "",
            request != null ? request.resourceId() : "", request != null && request.enabled(),
            request != null ? request.requestId() : "", message != null ? message : "", editorError);
        if (request != null && request.requestId() != null && !request.requestId().isBlank()) {
            synchronized (resourceActivationResults) {
                resourceActivationResults.put(request.requestId(), result);
                while (resourceActivationResults.size() > MAX_RESOURCE_ACTIVATION_RESULTS) {
                    resourceActivationResults.remove(resourceActivationResults.keySet().iterator().next());
                }
            }
        }
        sendResourceActivationResult(session, result);
    }

    private void sendResourceActivationResult(Session session, ResourceActivationResult result) {
        sender.sendJsonPayload(session, ReSyncProtocolContract.FLOW_PACKET_RESOURCE_ACTIVATION_RESULT, gson.toJson(result),
            "RESOURCE_ACTIVATION_RESULT_TOO_LARGE", "Resource update result exceeds maximum size");
    }

    private Map<String, Object> objectMap(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return Map.of();
        }
        Map<?, ?> values = gson.fromJson(element, Map.class);
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private long longValue(JsonObject root, String key, long fallback) {
        try {
            return root != null && root.has(key) && !root.get(key).isJsonNull() ? root.get(key).getAsLong() : fallback;
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static String defaultText(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private void ensureTraceSink() {
        if (traceService != null && traceSink == null) {
            traceSink = record -> {
                for (Session subscribedSession : subscribedSessions) {
                    sender.sendTraceEvent(subscribedSession, record);
                }
            };
            traceService.addSink(traceSink);
        }
    }

    private void startDebugTestRun(Session session, JsonObject root) {
        if (compiledTriggerExecution == null || runtimePrincipalAuthority == null || !trustedDebugSession(session)) {
            return;
        }
        RuntimePrincipal principal = runtimePrincipalAuthority.issueAuthenticatedClient(session.getIdentity().clientId());
        if (!runtimePrincipalAuthority.trusts(principal, runtimePrincipalAuthority.authority())) {
            return;
        }
        String graphId = string(root, "graphId");
        FlowGraph graph = graphId != null && !graphId.isBlank() ? storage.getGraph(graphId) : null;
        if (graph == null) {
            return;
        }
        String nodeId = string(root, "nodeId");
        if (nodeId == null || nodeId.isBlank()) {
            nodeId = firstExecutableNode(graph);
        }
        if (nodeId != null && !nodeId.isBlank()) {
            CorrelationId invocationId = debugInvocationId(root);
            if (invocationId != null) {
                long timeoutMillis = Math.clamp(longValue(root, "timeoutMillis", 5000L), 1L, 30000L);
                long requestedDeadline;
                try {
                    requestedDeadline = stableRequestedDeadline(session, principal, invocationId, root, timeoutMillis);
                } catch (IllegalArgumentException exception) {
                    return;
                }
                compiledTriggerExecution.execute(graph, nodeId, null, null,
                    Map.of("runtime.sessionId", session.getSessionId(), "runtime.clientId", session.getIdentity().clientId()),
                    principal, invocationId, requestedDeadline);
            }
        }
    }

    private boolean trustedDebugSession(Session session) {
        if (session == null || session.getConnection() == null
            || session.getConnection().getState() != ConnectionState.AUTHENTICATED
            || session.getIdentity() == null || session.getIdentity().clientId() == null
            || session.getIdentity().clientId().isBlank()) {
            return false;
        }
        return Objects.equals(session.getClientId(), session.getIdentity().clientId())
            && session.getConnection().getClientId() != null
            && session.getConnection().getClientId().equals(session.getIdentity().clientId());
    }

    private CorrelationId debugInvocationId(JsonObject root) {
        String value = string(root, "invocationId");
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return CorrelationId.parseCanonicalText(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private CorrelationId functionTestInvocationId(JsonObject request) {
        String value = string(request, "invocationId");
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return CorrelationId.parseCanonicalText(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private long stableRequestedDeadline(Session session, RuntimePrincipal principal, CorrelationId invocationId,
                                         JsonObject request, long timeoutMillis) {
        Objects.requireNonNull(session, "Authenticated Session Is Required");
        Objects.requireNonNull(principal, "Authenticated Principal Is Required");
        Objects.requireNonNull(invocationId, "Invocation ID Is Required");
        boolean suppliedPresent = request != null && request.has("deadlineMillis") && !request.get("deadlineMillis").isJsonNull();
        long supplied;
        if (suppliedPresent) {
            try {
                supplied = request.get("deadlineMillis").getAsLong();
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Deadline Must Be An Integer", exception);
            }
        } else {
            supplied = Long.MIN_VALUE;
        }
        OptionalLong stored = runtimeBindingRegistry == null
            ? OptionalLong.empty()
            : runtimeBindingRegistry.receiptStore().storedDeadline(
                runtimePrincipalAuthority.authority().identity(), principal.canonical(), invocationId);
        long candidate;
        if (stored.isPresent()) {
            candidate = stored.getAsLong();
            if (suppliedPresent && supplied != candidate) {
                throw new IllegalArgumentException("Invocation Deadline Changed For An Existing Invocation ID");
            }
        } else if (supplied == Long.MIN_VALUE) {
            try {
                candidate = Math.addExact(System.currentTimeMillis(), timeoutMillis);
            } catch (ArithmeticException exception) {
                candidate = Long.MAX_VALUE;
            }
        } else {
            if (supplied < 0) {
                throw new IllegalArgumentException("Deadline Cannot Be Negative");
            }
            candidate = supplied;
        }
        String key = session.getSessionId() + "|" + session.getIdentity().clientId() + "|" + invocationId.canonicalText();
        Long previous = trustedInvocationDeadlines.putIfAbsent(key, candidate);
        if (previous != null && previous.longValue() != candidate) {
            throw new IllegalArgumentException("Invocation Deadline Changed For An Existing Invocation ID");
        }
        if (trustedInvocationDeadlines.size() > 4096) {
            trustedInvocationDeadlines.keySet().stream().findFirst().ifPresent(trustedInvocationDeadlines::remove);
        }
        return previous == null ? candidate : previous;
    }

    private String firstExecutableNode(FlowGraph graph) {
        if (graph == null || graph.getNodes() == null || graph.getNodes().isEmpty()) {
            return null;
        }
        for (Map.Entry<String, FlowNode> entry : graph.getNodes().entrySet()) {
            if (entry.getValue() != null && entry.getValue().getType() != null
                && (entry.getValue().getType().startsWith("event.") || entry.getValue().getType().startsWith("event:"))) {
                return entry.getKey();
            }
        }
        return graph.getNodes().keySet().stream().sorted(String.CASE_INSENSITIVE_ORDER).findFirst().orElse(null);
    }

    private Set<String> nodeSet(JsonElement element) {
        Set<String> values = ConcurrentHashMap.newKeySet();
        if (element != null && element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (item != null && !item.isJsonNull()) {
                    values.add(item.getAsString());
                }
            }
        }
        return values;
    }

    private List<CatalogContribution> buildCatalogContributions() {
        return buildCatalogContributions(authoredCatalogSources);
    }

    private List<CatalogContribution> buildCatalogContributions(List<CatalogSourceIngestor.CatalogSource> sources) {
        ExtensionRegistryActivation activation = definitionRegistry.activation();
        if (activation != null) {
            return activation.readConsistent(ignored -> buildCatalogContributions(definitionRegistry, handlerRegistry,
                extensionData, sources, catalogCompiler.contractVersion(), null, optionCatalogRegistry));
        }
        return buildCatalogContributions(definitionRegistry, handlerRegistry, extensionData, sources,
            catalogCompiler.contractVersion(), null, optionCatalogRegistry);
    }

    private CatalogBuild buildStartupCatalog(List<CatalogSourceIngestor.CatalogSource> sources) {
        Map<NodeDefinition, RuntimeOperationDescriptor> runtimeDescriptors = new IdentityHashMap<>();
        ExtensionRegistryActivation activation = definitionRegistry.activation();
        List<CatalogContribution> contributions = activation != null
            ? activation.readConsistent(ignored -> buildCatalogContributions(definitionRegistry, handlerRegistry,
                extensionData, sources, catalogCompiler.contractVersion(), runtimeDescriptors, optionCatalogRegistry))
            : buildCatalogContributions(definitionRegistry, handlerRegistry, extensionData, sources,
                catalogCompiler.contractVersion(), runtimeDescriptors, optionCatalogRegistry);
        Map<RuntimeOperationDescriptor, NodeDefinition> runtimeDefinitions = new LinkedHashMap<>();
        runtimeDescriptors.forEach((definition, descriptor) -> runtimeDefinitions.putIfAbsent(descriptor, definition));
        return new CatalogBuild(contributions, runtimeDefinitions);
    }

    private List<CatalogContribution> buildCatalogContributions(NodeDefinitionRegistry definitionsRegistry,
                                                                  HandlerRegistry handlers,
                                                                  ReSyncExtensionData contributionData) {
        return buildCatalogContributions(definitionsRegistry, handlers, contributionData, authoredCatalogSources);
    }

    private List<CatalogContribution> buildCatalogContributions(NodeDefinitionRegistry definitionsRegistry,
                                                                  HandlerRegistry handlers,
                                                                  ReSyncExtensionData contributionData,
                                                                  List<CatalogSourceIngestor.CatalogSource> sources) {
        return buildCatalogContributions(definitionsRegistry, handlers, contributionData, sources,
            catalogCompiler.contractVersion(), null, optionCatalogRegistry);
    }

    static List<CatalogContribution> buildCatalogContributions(NodeDefinitionRegistry definitionsRegistry,
                                                               HandlerRegistry handlers, ReSyncExtensionData contributionData,
                                                               List<CatalogSourceIngestor.CatalogSource> sources,
                                                               CatalogVersion contractVersion) {
        return buildCatalogContributions(definitionsRegistry, handlers, contributionData, sources, contractVersion, null, null);
    }

    static List<CatalogContribution> buildCatalogContributions(NodeDefinitionRegistry definitionsRegistry,
                                                               HandlerRegistry handlers, ReSyncExtensionData contributionData,
                                                               List<CatalogSourceIngestor.CatalogSource> sources,
                                                               CatalogVersion contractVersion,
                                                               OptionCatalogRegistry optionCatalogs) {
        return buildCatalogContributions(definitionsRegistry, handlers, contributionData, sources, contractVersion, null,
            optionCatalogs);
    }

    private static List<CatalogContribution> buildCatalogContributions(NodeDefinitionRegistry definitionsRegistry,
                                                                       HandlerRegistry handlers,
                                                                       ReSyncExtensionData contributionData,
                                                                       List<CatalogSourceIngestor.CatalogSource> sources,
                                                                       CatalogVersion contractVersion,
                                                                       Map<NodeDefinition, RuntimeOperationDescriptor> runtimeDescriptors,
                                                                       OptionCatalogRegistry optionCatalogs) {
        Map<OwnerId, List<NodeDefinition>> grouped = new LinkedHashMap<>();
        Map<OwnerId, List<TypeDescriptor>> groupedTypes = catalogTypes();
        List<NodeDefinition> sourceDefinitions = new ArrayList<>(definitionsRegistry.getAllDefinitions().values());
        List<NodeDefinition> definitions = new ArrayList<>(sourceDefinitions);
        if (definitions.isEmpty()) {
            throw new IllegalStateException("Flow catalog contains no node definitions");
        }
        if (definitions.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Flow catalog contains a null node definition");
        }
        definitions.sort(Comparator.comparing(FlowModule::catalogOwner)
            .thenComparing(value -> nodeLocalId(catalogOwner(value), value)));
        for (NodeDefinition definition : definitions) {
            OwnerId owner = catalogOwner(definition);
            nodeLocalId(owner, definition);
            grouped.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(definition);
        }
        List<CatalogContribution> contributions = new ArrayList<>();
        Map<RuntimeBindingKey, RuntimeOperationDescriptor> runtimeRequirements = new LinkedHashMap<>();
        CatalogContractRange contractRange = new CatalogContractRange(contractVersion, contractVersion);
        List<OwnerId> generatedOwners = new ArrayList<>(grouped.keySet());
        groupedTypes.keySet().stream().filter(owner -> !generatedOwners.contains(owner)).sorted().forEach(generatedOwners::add);
        for (OwnerId owner : generatedOwners) {
            Map<String, CatalogCategoryDescriptor> categories = new LinkedHashMap<>();
            Map<String, CatalogCapabilityDescriptor> capabilities = new LinkedHashMap<>();
            Map<String, InspectorOptionSource> optionSources = new LinkedHashMap<>();
            List<CatalogNodeDescriptor> nodes = new ArrayList<>();
            for (NodeDefinition definition : grouped.getOrDefault(owner, List.of())) {
                nodes.add(catalogNode(owner, definition, categories, capabilities, optionSources, runtimeRequirements,
                    handlers, runtimeDescriptors, optionCatalogs));
            }
            List<TypeDescriptor> types = groupedTypes.getOrDefault(owner, List.of());
            if (!types.isEmpty()) {
                capabilities.putIfAbsent("generic-editor", new CatalogCapabilityDescriptor(
                    CapabilityId.of("generic-editor"), 1, false, InspectorFallback.GENERIC));
            }
            if (nodes.isEmpty() && types.isEmpty()) {
                continue;
            }
            nodes.sort(Comparator.comparing(CatalogNodeDescriptor::id));
            CatalogProvenance.SourceKind sourceKind = "builtin".equals(owner.value())
                ? CatalogProvenance.SourceKind.BUNDLED : CatalogProvenance.SourceKind.EXTENSION;
            String sourceVersion = contributionVersion(owner.value(), contributionData);
            CatalogProvenance provenance = new CatalogProvenance(sourceKind, "runtime://flow/catalog/" + owner.value(), sourceVersion, "resync-flow");
            contributions.add(CatalogContribution.builder(owner, sourceVersion, contractRange, provenance)
                .definitions(nodes)
                .types(types)
                .conversions(catalogConversions(owner, grouped.getOrDefault(owner, List.of()), handlers))
                .categories(new ArrayList<>(categories.values()))
                .capabilities(new ArrayList<>(capabilities.values()))
                .optionSources(new ArrayList<>(optionSources.values()))
                .runtimeRequirements(runtimeRequirementsForOwner(owner, runtimeRequirements))
                .build());
        }
        List<CatalogContribution> authored = ingestAuthoredCatalogSources(sources, sourceDefinitions, handlers,
            contractVersion, runtimeDescriptors, optionCatalogs);
        if (authored.isEmpty()) {
            return List.copyOf(contributions);
        }
        return mergeGeneratedAndAuthoredContributions(contributions, authored);
    }

    static List<ConversionGraph.ConversionEdge> catalogConversions(OwnerId owner,
                                                                   List<NodeDefinition> definitions,
                                                                   HandlerRegistry handlers) {
        if (!"restudio.resync".equals(owner.value())) {
            return List.of();
        }
        NodeDefinition definition = definitions.stream()
            .filter(value -> "to_number".equals(nodeLocalId(owner, value)))
            .findFirst()
            .orElse(null);
        if (definition == null) {
            return List.of();
        }
        RuntimeOperationDescriptor requirement = runtimeOperationDescriptor(definition, handlers);
        TypeExpr source = TypeExpr.named(TypeReference.of("builtin", "string"));
        TypeExpr target = TypeExpr.named(TypeReference.of("builtin", "number"));
        if (!requirement.inputs().equals(List.of(source)) || !requirement.outputs().equals(List.of(target))) {
            throw new IllegalStateException("String To Number Runtime Signature Is Invalid");
        }
        return List.of(new ConversionGraph.ConversionEdge(
            TypeReference.of(owner.value(), "string-to-number"), source, target, 1,
            ConversionGraph.Losslessness.LOSSY, ConversionGraph.FailureBehavior.INFALLIBLE,
            requirement.capability(), requirement.operation()));
    }

    private static Map<OwnerId, List<TypeDescriptor>> catalogTypes() {
        Map<OwnerId, List<TypeDescriptor>> grouped = new LinkedHashMap<>();
        FlowDataType.values().stream()
            .filter(FlowDataType::isResolved)
            .map(FlowModule::catalogType)
            .sorted(Comparator.comparing(type -> type.id().canonicalKey()))
            .forEach(type -> grouped.computeIfAbsent(OwnerId.of(type.id().ownerId()), ignored -> new ArrayList<>()).add(type));
        grouped.replaceAll((owner, types) -> List.copyOf(types));
        return Collections.unmodifiableMap(grouped);
    }

    private static TypeDescriptor catalogType(FlowDataType type) {
        TypeReference id = catalogTypeReference(type);
        CodecDescriptor codec = new CodecDescriptor(TypeReference.of("builtin", "flow-value-json"), 1, true, true);
        boolean serializable = type.canStringify();
        return new TypeDescriptor(id, typeDisplayName(id.localId()), TypeExpr.named(id), Map.of(), codec, codec,
            ContractRef.of(OwnerId.of(id.ownerId()), CapabilityId.of("generic-editor")), List.of(),
            serializable, serializable);
    }

    private static TypeReference catalogTypeReference(FlowDataType type) {
        String id = type.getId().strip().toLowerCase(Locale.ROOT);
        if (id.contains(":")) {
            return typeReference(id, "type");
        }
        String owner = type.getOwner();
        String normalizedOwner = owner == null || owner.isBlank() || "builtin".equalsIgnoreCase(owner)
            ? "builtin" : owner.strip().toLowerCase(Locale.ROOT);
        return TypeReference.of(normalizedOwner, exactLocalId(id, "type"));
    }

    private static String typeDisplayName(String id) {
        String[] words = id.replace('-', '_').split("_+");
        StringBuilder display = new StringBuilder();
        for (String word : words) {
            if (word.isBlank()) {
                continue;
            }
            if (!display.isEmpty()) {
                display.append(' ');
            }
            display.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) {
                display.append(word.substring(1));
            }
        }
        return display.isEmpty() ? "Value" : display.toString();
    }

    private static List<CatalogContribution> mergeGeneratedAndAuthoredContributions(
        List<CatalogContribution> generated,
        List<CatalogContribution> authored
    ) {
        Map<OwnerId, CatalogContribution> generatedByOwner = uniqueContributionsByOwner(generated, "generated");
        Map<OwnerId, CatalogContribution> authoredByOwner = uniqueContributionsByOwner(authored, "authored");
        Set<OwnerId> owners = new HashSet<>(generatedByOwner.keySet());
        owners.addAll(authoredByOwner.keySet());
        return owners.stream()
            .sorted()
            .map(owner -> {
                CatalogContribution generatedContribution = generatedByOwner.get(owner);
                CatalogContribution authoredContribution = authoredByOwner.get(owner);
                return generatedContribution == null ? authoredContribution
                    : authoredContribution == null ? generatedContribution
                    : mergeGeneratedAndAuthoredContribution(generatedContribution, authoredContribution);
            })
            .toList();
    }

    private static Map<OwnerId, CatalogContribution> uniqueContributionsByOwner(
        List<CatalogContribution> values,
        String sourceLabel
    ) {
        Map<OwnerId, CatalogContribution> result = new LinkedHashMap<>();
        if (values == null) {
            return result;
        }
        for (CatalogContribution value : values) {
            CatalogContribution contribution = Objects.requireNonNull(value, sourceLabel + " catalog contribution cannot be null");
            CatalogContribution previous = result.putIfAbsent(contribution.ownerId(), contribution);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate " + sourceLabel + " catalog contribution owner: "
                    + contribution.ownerId().canonicalText());
            }
        }
        return result;
    }

    private static CatalogContribution mergeGeneratedAndAuthoredContribution(
        CatalogContribution generated,
        CatalogContribution authored
    ) {
        if (!generated.ownerId().equals(authored.ownerId())) {
            throw new IllegalArgumentException("Catalog contribution owners cannot be merged");
        }
        if (!generated.version().equals(authored.version())) {
            throw new IllegalArgumentException("Catalog contribution versions conflict for owner: "
                + generated.ownerId().canonicalText());
        }
        if (!generated.contractRange().equals(authored.contractRange())) {
            throw new IllegalArgumentException("Catalog contribution contract ranges conflict for owner: "
                + generated.ownerId().canonicalText());
        }
        Set<String> authoredDefinitionIds = authored.definitions().stream()
            .map(value -> value.id().canonicalText())
            .collect(Collectors.toSet());
        List<CatalogProvenance.SourceEntry> entries = new ArrayList<>();
        generated.provenance().entries().stream()
            .filter(entry -> !authoredDefinitionIds.contains(entry.definitionId()))
            .forEach(entries::add);
        for (CatalogProvenance.SourceEntry entry : authored.provenance().entries()) {
            if (!entries.contains(entry)) {
                entries.add(entry);
            }
        }
        CatalogProvenance authoredProvenance = authored.provenance();
        CatalogProvenance provenance = new CatalogProvenance(authoredProvenance.sourceKind(),
            authoredProvenance.sourceUri(), authoredProvenance.sourceHash(), authoredProvenance.sourceVersion(),
            authoredProvenance.buildId(), authoredProvenance.loadedAt(), entries);
        return CatalogContribution.builder(authored.ownerId(), authored.version(), authored.contractRange(), provenance)
            .dependencies(mergeContributionValues(generated.dependencies(), authored.dependencies(),
                value -> value.ownerId().canonicalText() + '\u0000' + value.versionRange(), "dependency"))
            .definitions(mergeNodeDefinitions(generated.definitions(), authored.definitions()))
            .types(mergeTypeDescriptors(generated.types(), authored.types()))
            .conversions(mergeContributionValues(generated.conversions(), authored.conversions(),
                value -> value.id().canonicalKey(), "conversion"))
            .categories(mergeContributionValues(generated.categories(), authored.categories(),
                value -> value.id().canonicalText(), "category"))
            .inspectors(mergeContributionValues(generated.inspectors(), authored.inspectors(),
                value -> value.owner().canonicalText() + '\u0000' + value.id().canonicalText(), "inspector"))
            .capabilities(mergeContributionValues(generated.capabilities(), authored.capabilities(),
                value -> value.id().canonicalText(), "capability"))
            .runtimeRequirements(mergeContributionValues(generated.runtimeRequirements(), authored.runtimeRequirements(),
                value -> value.key().canonical(), "runtime requirement"))
            .migrations(mergeContributionValues(generated.migrations(), authored.migrations(),
                value -> value.id().canonicalText(), "migration"))
            .optionSources(mergeContributionValues(generated.optionSources(), authored.optionSources(),
                value -> value.id().canonicalText(), "option source"))
            .validators(mergeContributionValues(generated.validators(), authored.validators(),
                value -> value.id().canonicalText(), "validator"))
            .editors(mergeContributionValues(generated.editors(), authored.editors(),
                value -> value.id().canonicalText(), "editor"))
            .previews(mergeContributionValues(generated.previews(), authored.previews(),
                value -> value.id().canonicalText(), "preview"))
            .build();
    }

    private static List<CatalogNodeDescriptor> mergeNodeDefinitions(
        List<CatalogNodeDescriptor> generated,
        List<CatalogNodeDescriptor> authored
    ) {
        Map<String, CatalogNodeDescriptor> result = new LinkedHashMap<>();
        addNodeDefinitions(result, generated, "generated");
        Map<String, CatalogNodeDescriptor> authoredById = new LinkedHashMap<>();
        addNodeDefinitions(authoredById, authored, "authored");
        authoredById.forEach(result::put);
        return result.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(Map.Entry::getValue)
            .toList();
    }

    private static List<TypeDescriptor> mergeTypeDescriptors(List<TypeDescriptor> generated,
                                                              List<TypeDescriptor> authored) {
        Map<String, TypeDescriptor> result = new LinkedHashMap<>();
        addContributionValues(result, generated, value -> value.id().canonicalKey(), "type", "generated");
        Map<String, TypeDescriptor> authoredById = new LinkedHashMap<>();
        addContributionValues(authoredById, authored, value -> value.id().canonicalKey(), "type", "authored");
        authoredById.forEach(result::put);
        return result.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).toList();
    }

    private static void addNodeDefinitions(
        Map<String, CatalogNodeDescriptor> target,
        List<CatalogNodeDescriptor> values,
        String sourceLabel
    ) {
        if (values == null) {
            return;
        }
        for (CatalogNodeDescriptor value : values) {
            CatalogNodeDescriptor definition = Objects.requireNonNull(value, sourceLabel + " catalog definition cannot be null");
            String identity = definition.id().canonicalText();
            CatalogNodeDescriptor previous = target.putIfAbsent(identity, definition);
            if (previous != null && !CatalogCanonicalizer.canonicalNodeContent(previous)
                .equals(CatalogCanonicalizer.canonicalNodeContent(definition))) {
                throw new IllegalArgumentException("Conflicting " + sourceLabel + " catalog definition identity: " + identity);
            }
        }
    }

    private static <T> List<T> mergeContributionValues(
        List<T> generated,
        List<T> authored,
        Function<T, String> identity,
        String label
    ) {
        Map<String, T> values = new LinkedHashMap<>();
        addContributionValues(values, generated, identity, label, "generated");
        if (authored != null) {
            Map<String, T> authoredValues = new LinkedHashMap<>();
            for (T value : authored) {
                T item = Objects.requireNonNull(value, "Authored catalog " + label + " cannot be null");
                String key = Objects.requireNonNull(identity.apply(item), "Authored catalog " + label + " identity is required");
                T previous = authoredValues.putIfAbsent(key, item);
                if (previous != null && !Objects.equals(previous, item)) {
                    throw new IllegalArgumentException("Conflicting authored catalog " + label + " identity: " + key);
                }
            }
            for (Map.Entry<String, T> entry : authoredValues.entrySet()) {
                T previous = values.putIfAbsent(entry.getKey(), entry.getValue());
                if (previous != null && !Objects.equals(previous, entry.getValue())) {
                    throw new IllegalArgumentException("Conflicting catalog " + label + " identity: " + entry.getKey());
                }
            }
        }
        return values.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(Map.Entry::getValue)
            .toList();
    }

    private static <T> void addContributionValues(
        Map<String, T> target,
        List<T> values,
        Function<T, String> identity,
        String label,
        String sourceLabel
    ) {
        if (values == null) {
            return;
        }
        for (T value : values) {
            T item = Objects.requireNonNull(value, sourceLabel + " catalog " + label + " cannot be null");
            String key = Objects.requireNonNull(identity.apply(item), sourceLabel + " catalog " + label + " identity is required");
            T previous = target.putIfAbsent(key, item);
            if (previous != null && !Objects.equals(previous, item)) {
                throw new IllegalArgumentException("Conflicting " + sourceLabel + " catalog " + label + " identity: " + key);
            }
        }
    }

    private static List<CatalogContribution> ingestAuthoredCatalogSources(
        List<CatalogSourceIngestor.CatalogSource> sources,
        List<NodeDefinition> sourceDefinitions,
        HandlerRegistry handlers,
        CatalogVersion contractVersion,
        Map<NodeDefinition, RuntimeOperationDescriptor> runtimeDescriptors,
        OptionCatalogRegistry optionCatalogs
    ) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        Map<String, NodeDefinition> definitions = new HashMap<>();
        Map<InspectorFieldId, InspectorOptionSource> options = new HashMap<>();
        for (NodeDefinition definition : sourceDefinitions) {
            OwnerId owner = catalogOwner(definition);
            definitions.put(authoredNodeKey(owner, nodeLocalId(owner, definition)), definition);
            collectAuthoredOptionSources(owner, definition, options, optionCatalogs);
        }
        List<CatalogCategoryDescriptor> categories = authoredCategories(sourceDefinitions);
        Map<OwnerId, List<CatalogContribution>> byOwner = new LinkedHashMap<>();
        CatalogSourceIngestor ingestor = new CatalogSourceIngestor();
        for (CatalogSourceIngestor.CatalogSource source : sources) {
            OwnerId owner = source.owner();
            CatalogSourceIngestor.CatalogIngestionContext context = new CatalogSourceIngestor.CatalogIngestionContext(
                new CatalogContractRange(contractVersion, contractVersion),
                categories,
                authoredEditor(owner),
                id -> Optional.ofNullable(options.get(id)),
                request -> {
                    NodeDefinition definition = definitions.get(authoredNodeKey(request.owner(), request.node().value()));
                    if (definition == null) {
                        return Optional.empty();
                    }
                    RuntimeOperationDescriptor descriptor = runtimeDescriptors != null
                        ? runtimeDescriptors.get(definition) : null;
                    return Optional.of(descriptor != null ? descriptor : runtimeOperationDescriptor(definition, handlers));
                },
                resource -> Optional.of(canonicalAuthoredResourceType(resource, owner)));
            CatalogContribution contribution = ingestor.ingest(source, context,
                identity -> definitions.containsKey(authoredNodeKey(identity.owner(), identity.id().value())));
            byOwner.computeIfAbsent(contribution.ownerId(), ignored -> new ArrayList<>()).add(contribution);
        }
        List<CatalogContribution> result = new ArrayList<>();
        byOwner.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> result.add(CatalogSourceIngestor.combineContributions(entry.getValue())));
        return List.copyOf(result);
    }

    private static String authoredNodeKey(OwnerId owner, String nodeId) {
        return owner.canonicalText() + '\u0000' + nodeId;
    }

    static List<CatalogCategoryDescriptor> authoredCategories(NodeDefinitionRegistry definitionsRegistry) {
        return authoredCategories(definitionsRegistry.getAllDefinitions().values());
    }

    private static List<CatalogCategoryDescriptor> authoredCategories(Collection<NodeDefinition> definitions) {
        Map<String, CatalogCategoryDescriptor> categories = new LinkedHashMap<>();
        for (NodeDefinition.NodeCategory category : NodeDefinition.NodeCategory.values()) {
            addAuthoredCategory(categories, category);
        }
        for (NodeDefinition definition : definitions) {
            addAuthoredCategory(categories, definition.getCategory());
        }
        return List.copyOf(categories.values());
    }

    private static void addAuthoredCategory(Map<String, CatalogCategoryDescriptor> categories,
                                            NodeDefinition.NodeCategory category) {
        if (category == null) {
            return;
        }
        String id = exactLocalId(category.getId(), "utility");
        categories.putIfAbsent(id, new CatalogCategoryDescriptor(id, text(category.getDisplayName(), id),
            description("Organizes " + id + " flow capabilities."), category.getPriority()));
    }

    private static InspectorCapability authoredEditor(OwnerId owner) {
        TypeExpr any = TypeExpr.named(TypeReference.of("builtin", "any"));
        ContractRef<CapabilityId> editor = ContractRef.of(owner, CapabilityId.of("generic-editor"));
        return new InspectorCapability(editor, "Generic Editor", "Provides the shared editor capability for authored Flow pins.",
            new InspectorValueSchema(any), new InspectorValueSchema(any), List.of(), editor, InspectorFallback.GENERIC);
    }

    static void collectAuthoredOptionSources(OwnerId owner, NodeDefinition definition,
                                             Map<InspectorFieldId, InspectorOptionSource> options) {
        collectAuthoredOptionSources(owner, definition, options, null);
    }

    private static void collectAuthoredOptionSources(OwnerId owner, NodeDefinition definition,
                                                     Map<InspectorFieldId, InspectorOptionSource> options,
                                                     OptionCatalogRegistry optionCatalogs) {
        for (NodeDefinition.PinDefinition pin : definition.getInputs()) {
            collectAuthoredOptionSource(owner, definition, pin, options, optionCatalogs);
        }
        for (NodeDefinition.PinDefinition pin : definition.getOutputs()) {
            collectAuthoredOptionSource(owner, definition, pin, options, optionCatalogs);
        }
    }

    private static void collectAuthoredOptionSource(OwnerId owner, NodeDefinition definition,
                                                    NodeDefinition.PinDefinition pin,
                                                    Map<InspectorFieldId, InspectorOptionSource> options,
                                                    OptionCatalogRegistry optionCatalogs) {
        String source = pin.getOptionsSource();
        if (source == null || source.isBlank()) {
            return;
        }
        TypeExpr type = typeExpression(pin.getTypeRef());
        InspectorOptionSource option = catalogOptionSource(owner, source, type,
            optionQuerySchema(source, definition, optionCatalogs));
        InspectorFieldId id = option.id();
        InspectorOptionSource previous = options.putIfAbsent(id, option);
        if (previous != null && !previous.equals(option)) {
            throw new IllegalArgumentException("Conflicting authored option source: " + id.value());
        }
    }

    static InspectorOptionSource catalogOptionSource(OwnerId owner, String source, TypeExpr type) {
        return catalogOptionSource(owner, source, type, OptionQuerySchemaV1.empty());
    }

    private static InspectorOptionSource catalogOptionSource(OwnerId owner, String source, TypeExpr type,
                                                              OptionQuerySchemaV1 querySchema) {
        String optionId = derivedLocalId(source, "option-source");
        String capabilityId = derivedLocalId("options." + optionId, "option-capability");
        return new InspectorOptionSource(
            InspectorFieldId.of(optionId),
            text(optionId, "Options"),
            description("Provides server-authored options for " + optionId + " values."),
            type,
            querySchema,
            ContractRef.of(owner, CapabilityId.of(capabilityId)),
            100,
            optionId);
    }

    private static OptionQuerySchemaV1 optionQuerySchema(String source, NodeDefinition definition,
                                                         OptionCatalogRegistry optionCatalogs) {
        OptionCatalogProvider provider = optionCatalogs != null ? optionCatalogs.provider(source) : null;
        Set<String> keys = provider != null && provider.contextKeys() != null ? provider.contextKeys() : Set.of();
        if (keys.isEmpty()) {
            return OptionQuerySchemaV1.empty();
        }
        Map<String, OptionQuerySchemaV1.Field> context = new LinkedHashMap<>();
        Map<String, OptionQuerySchemaV1.Field> dependencies = new LinkedHashMap<>();
        for (String key : keys.stream().filter(Objects::nonNull).filter(value -> !value.isBlank()).sorted().toList()) {
            TypeExpr fieldType = optionQueryType(definition, key);
            TypedValue defaultValue = "data_type".equals(key)
                ? TypedValue.value(fieldType, "item") : null;
            OptionQuerySchemaV1.Field field = new OptionQuerySchemaV1.Field(fieldType, false, defaultValue);
            (key.startsWith("$") ? context : dependencies).put(key, field);
        }
        return new OptionQuerySchemaV1(null, context, dependencies);
    }

    private static TypeExpr optionQueryType(NodeDefinition definition, String key) {
        if (definition != null) {
            for (NodeDefinition.PinDefinition pin : definition.getInputs()) {
                if (pin != null && pin.getId() != null && pin.getId().canonicalText().equals(key)) {
                    return typeExpression(pin.getTypeRef());
                }
            }
        }
        return TypeExpr.named(TypeReference.of("builtin", "string"));
    }

    private static List<RuntimeOperationDescriptor> runtimeRequirementsForOwner(OwnerId owner,
                                                                           Map<RuntimeBindingKey, RuntimeOperationDescriptor> requirements) {
        return requirements.values().stream()
            .filter(value -> value.capability().owner().equals(owner))
            .toList();
    }

    private static OwnerId catalogOwner(NodeDefinition definition) {
        String authoredOwner = defaultText(definition.getOwner(), "builtin");
        try {
            return OwnerId.of(authoredOwner);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid node owner: " + authoredOwner, exception);
        }
    }

    private CatalogNodeDescriptor catalogNode(OwnerId owner, NodeDefinition definition,
                                              Map<String, CatalogCategoryDescriptor> categories,
                                              Map<String, CatalogCapabilityDescriptor> capabilities,
                                              Map<String, InspectorOptionSource> optionSources,
                                              List<RuntimeOperationDescriptor> runtimeRequirements) {
        return catalogNode(owner, definition, categories, capabilities, optionSources, runtimeRequirements,
            handlerRegistry, optionCatalogRegistry);
    }

    static CatalogNodeDescriptor catalogNode(OwnerId owner, NodeDefinition definition,
                                             Map<String, CatalogCategoryDescriptor> categories,
                                             Map<String, CatalogCapabilityDescriptor> capabilities,
                                             Map<String, InspectorOptionSource> optionSources,
                                             List<RuntimeOperationDescriptor> runtimeRequirements,
                                             HandlerRegistry handlers) {
        return catalogNode(owner, definition, categories, capabilities, optionSources, runtimeRequirements, handlers, null);
    }

    static CatalogNodeDescriptor catalogNode(OwnerId owner, NodeDefinition definition,
                                             Map<String, CatalogCategoryDescriptor> categories,
                                             Map<String, CatalogCapabilityDescriptor> capabilities,
                                             Map<String, InspectorOptionSource> optionSources,
                                             List<RuntimeOperationDescriptor> runtimeRequirements,
                                             HandlerRegistry handlers, OptionCatalogRegistry optionCatalogs) {
        return catalogNode(owner, definition, categories, capabilities, optionSources,
            requirement -> addRuntimeRequirement(runtimeRequirements, requirement), handlers, optionCatalogs);
    }

    private static CatalogNodeDescriptor catalogNode(OwnerId owner, NodeDefinition definition,
                                                     Map<String, CatalogCategoryDescriptor> categories,
                                                     Map<String, CatalogCapabilityDescriptor> capabilities,
                                                     Map<String, InspectorOptionSource> optionSources,
                                                     Map<RuntimeBindingKey, RuntimeOperationDescriptor> runtimeRequirements,
                                                     HandlerRegistry handlers) {
        return catalogNode(owner, definition, categories, capabilities, optionSources, runtimeRequirements, handlers, null, null);
    }

    private static CatalogNodeDescriptor catalogNode(OwnerId owner, NodeDefinition definition,
                                                     Map<String, CatalogCategoryDescriptor> categories,
                                                     Map<String, CatalogCapabilityDescriptor> capabilities,
                                                     Map<String, InspectorOptionSource> optionSources,
                                                     Map<RuntimeBindingKey, RuntimeOperationDescriptor> runtimeRequirements,
                                                     HandlerRegistry handlers,
                                                     Map<NodeDefinition, RuntimeOperationDescriptor> runtimeDescriptors,
                                                     OptionCatalogRegistry optionCatalogs) {
        return catalogNode(owner, definition, categories, capabilities, optionSources,
            requirement -> {
                addRuntimeRequirement(runtimeRequirements, requirement);
                if (runtimeDescriptors != null) {
                    runtimeDescriptors.put(definition, requirement);
                }
            }, handlers, optionCatalogs);
    }

    private static CatalogNodeDescriptor catalogNode(OwnerId owner, NodeDefinition definition,
                                                     Map<String, CatalogCategoryDescriptor> categories,
                                                     Map<String, CatalogCapabilityDescriptor> capabilities,
                                                     Map<String, InspectorOptionSource> optionSources,
                                                     Consumer<RuntimeOperationDescriptor> runtimeRequirementSink,
                                                     HandlerRegistry handlers,
                                                     OptionCatalogRegistry optionCatalogs) {
        String nodeId = nodeLocalId(owner, definition);
        String categoryId = exactLocalId(definition.getCategory() != null ? definition.getCategory().getId() : "utility", "category");
        CatalogCategoryDescriptor category = categories.computeIfAbsent(categoryId,
            ignored -> new CatalogCategoryDescriptor(categoryId, text(definition.getCategory() != null ? definition.getCategory().getDisplayName() : "Utility", "Utility"),
                description("Organizes " + categoryId + " flow capabilities."), definition.getCategory() != null ? definition.getCategory().getPriority() : 0));
        RuntimeOperationDescriptor requirement = runtimeOperationDescriptor(definition, handlers);
        String handlerId = requirement.capability().id().value();
        CatalogCapabilityDescriptor handlerCapability = capabilities.computeIfAbsent(handlerId,
            ignored -> new CatalogCapabilityDescriptor(CapabilityId.of(handlerId), 1, false, InspectorFallback.GENERIC));
        String editorId = "generic-editor";
        capabilities.computeIfAbsent(editorId,
            ignored -> new CatalogCapabilityDescriptor(CapabilityId.of(editorId), 1, false, InspectorFallback.GENERIC));
        List<CatalogNodeDescriptor.Pin> pins = new ArrayList<>();
        Set<PinId> pinIds = new HashSet<>();
        for (NodeDefinition.PinDefinition pin : definition.getInputs()) {
            pins.add(catalogPin(owner, definition, pin, CatalogNodeDescriptor.Direction.INPUT, pinIds, capabilities,
                optionSources, optionCatalogs));
        }
        for (NodeDefinition.PinDefinition pin : definition.getOutputs()) {
            pins.add(catalogPin(owner, definition, pin, CatalogNodeDescriptor.Direction.OUTPUT, pinIds, capabilities,
                optionSources, optionCatalogs));
        }
        List<CatalogNodeDescriptor.Branch> branches = List.of(new CatalogNodeDescriptor.Branch("failure", "Failure",
            "Reports that this flow capability could not complete.", List.of(new CatalogNodeDescriptor.Case("failure", "Failure",
            "The flow capability reported a structured failure."))));
        ContractRef<CapabilityId> handler = requirement.capability();
        RuntimeSemantics semantics = requirement.semantics();
        runtimeRequirementSink.accept(requirement);
        Map<String, Object> metadata = nodeMetadata(definition);
        AuthoredNodeMetadata authored = definition.getAuthoredMetadata();
        CatalogNodeDescriptor.Lifecycle lifecycle = authored != null
            ? authoredLifecycle(authored.lifecycle())
            : definition.isDeprecated() ? CatalogNodeDescriptor.Lifecycle.DEPRECATED : CatalogNodeDescriptor.Lifecycle.ACTIVE;
        CatalogNodeDescriptor.Builder descriptor = CatalogNodeDescriptor.builder(NodeId.of(nodeId))
            .schemaVersion(Math.max(1, definition.getSchemaVersion()))
            .lifecycle(lifecycle)
            .domain(authored != null ? authored.domain() : "flow")
            .family(authored != null ? authored.family() : exactLocalId(definition.getFamily(), "default"))
            .displayName(text(definition.getDisplayName(), nodeId))
            .description(description(definition))
            .category(ContractRef.of(owner, category.id()))
            .pins(pins)
            .branches(branches)
            .handler(new CatalogNodeDescriptor.Handler(handler, requirement.operation()))
            .semantics(semantics)
            .requiredCapabilities(Set.of(handler))
            .metadata(metadata);
        if (lifecycle == CatalogNodeDescriptor.Lifecycle.RETIRING && definition.getReplacementFor() != null
            && !definition.getReplacementFor().isBlank()) {
            descriptor.replacementIdentity(ContractRef.of(owner, NodeId.of(definition.getReplacementFor())));
        }
        return descriptor.build();
    }

    private static void addRuntimeRequirement(List<RuntimeOperationDescriptor> runtimeRequirements,
                                               RuntimeOperationDescriptor requirement) {
        RuntimeOperationDescriptor previous = runtimeRequirements.stream()
            .filter(value -> value.key().equals(requirement.key()))
            .findFirst()
            .orElse(null);
        if (previous != null && !previous.equals(requirement)) {
            throw new IllegalArgumentException("Flow handler has conflicting runtime requirements: " + requirement.key().canonical());
        }
        if (previous == null) {
            runtimeRequirements.add(requirement);
        }
    }

    private static void addRuntimeRequirement(Map<RuntimeBindingKey, RuntimeOperationDescriptor> runtimeRequirements,
                                               RuntimeOperationDescriptor requirement) {
        RuntimeOperationDescriptor previous = runtimeRequirements.putIfAbsent(requirement.key(), requirement);
        if (previous != null && !previous.equals(requirement)) {
            throw new IllegalArgumentException("Flow handler has conflicting runtime requirements: " + requirement.key().canonical());
        }
    }

    private static CatalogNodeDescriptor.Lifecycle authoredLifecycle(String value) {
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "active" -> CatalogNodeDescriptor.Lifecycle.ACTIVE;
            case "deprecated" -> CatalogNodeDescriptor.Lifecycle.DEPRECATED;
            case "retiring" -> CatalogNodeDescriptor.Lifecycle.RETIRING;
            case "migration-only" -> CatalogNodeDescriptor.Lifecycle.MIGRATION_ONLY;
            default -> throw new IllegalArgumentException("Unsupported authored node lifecycle: " + value);
        };
    }

    private static CatalogNodeDescriptor.Pin catalogPin(OwnerId owner, NodeDefinition definition,
                                                        NodeDefinition.PinDefinition source,
                                                        CatalogNodeDescriptor.Direction direction, Set<PinId> usedIds,
                                                        Map<String, CatalogCapabilityDescriptor> capabilities,
                                                        Map<String, InspectorOptionSource> optionSources,
                                                        OptionCatalogRegistry optionCatalogs) {
        PinId pinId = source.getId();
        if (!usedIds.add(pinId)) {
            throw new IllegalArgumentException("Duplicate Flow catalog pin ID: " + pinId.value());
        }
        TypeExpr type = functionPinType(definition, source);
        String editorId = "generic-editor";
        ContractRef<CapabilityId> editor = ContractRef.of(owner, CapabilityId.of(editorId));
        ContractRef<InspectorFieldId> optionSource = null;
        if (source.getOptionsSource() != null && !source.getOptionsSource().isBlank()) {
            InspectorOptionSource option = catalogOptionSource(owner, source.getOptionsSource(), type,
                optionQuerySchema(source.getOptionsSource(), definition, optionCatalogs));
            String optionId = option.id().value();
            String capabilityId = option.capability().id().value();
            capabilities.computeIfAbsent(capabilityId,
                ignored -> new CatalogCapabilityDescriptor(CapabilityId.of(capabilityId), 1, false, InspectorFallback.GENERIC));
            InspectorOptionSource previous = optionSources.putIfAbsent(optionId, option);
            if (previous != null && !previous.equals(option)) {
                throw new IllegalArgumentException("Conflicting catalog option source: " + optionId);
            }
            optionSource = ContractRef.of(owner, option.id());
        }
        TypedValue defaultValue = functionDefault(definition, source, type);
        CatalogNodeDescriptor.Requirement requirement = source.isOptional()
            ? CatalogNodeDescriptor.Requirement.OPTIONAL : CatalogNodeDescriptor.Requirement.REQUIRED;
        if (defaultValue != null) {
            requirement = CatalogNodeDescriptor.Requirement.DEFAULTED;
        } else if (source.getDefaultValue() != null && !source.getDefaultValue().isBlank()) {
            try {
                defaultValue = TypedValue.value(type, catalogDefaultValue(type, source.getDefaultValue()));
                requirement = CatalogNodeDescriptor.Requirement.DEFAULTED;
            } catch (RuntimeException ignored) {
                throw new IllegalArgumentException("Invalid typed default for pin " + pinId.value(), ignored);
            }
        }
        NodeDefinition.RepeatablePin repeatable = source.getRepeatable();
        CatalogNodeDescriptor.RepeatableIntent repeatableIntent = repeatable == null
            ? CatalogNodeDescriptor.RepeatableIntent.disabled()
            : new CatalogNodeDescriptor.RepeatableIntent(true, repeatable.getMinItems(), repeatable.getMaxItems(), true);
        String resourceRole = type instanceof TypeExpr.ResourceType ? "reference" : null;
        return new CatalogNodeDescriptor.Pin(pinId, direction, type, text(source.getDisplayName(), pinId.value()),
            pinDescription(source.getDescription()), requirement, defaultValue, editor, optionSource, InspectorCondition.always(), repeatableIntent, resourceRole);
    }

    private static TypedValue functionDefault(NodeDefinition definition, NodeDefinition.PinDefinition source, TypeExpr type) {
        Map<String, Object> config = definition.getHandlerConfig();
        if (config == null || !config.containsKey(CustomFunctionNodeDefinitions.FUNCTION_DEFAULTS)) {
            return null;
        }
        if (!CustomFunctionCallHandler.HANDLER_ID.equals(definition.getHandler())
            || !CustomFunctionCallHandler.OPERATION.equals(config.get("operation"))
            || !(config.get(CustomFunctionNodeDefinitions.FUNCTION_DEFAULTS) instanceof Map<?, ?> defaults)) {
            throw new IllegalArgumentException("Typed Function defaults require a custom Function call descriptor");
        }
        for (Object key : defaults.keySet()) {
            if (!(key instanceof String id)) {
                throw new IllegalArgumentException("Typed Function defaults require stable parameter pin IDs");
            }
            String prefix = id.startsWith("function-input-") ? "function-input-"
                : id.startsWith("function-output-") ? "function-output-" : null;
            if (prefix == null) {
                throw new IllegalArgumentException("Typed Function default is not a parameter pin: " + id);
            }
            FunctionParameterId.parseCanonicalText(id.substring(prefix.length()));
            List<NodeDefinition.PinDefinition> pins = "function-input-".equals(prefix)
                ? definition.getInputs() : definition.getOutputs();
            long matches = pins.stream().filter(pin -> pin.getType() == NodeDefinition.PinType.DATA && id.equals(pin.getId().value())).count();
            if (matches != 1) {
                throw new IllegalArgumentException("Typed Function default must identify exactly one parameter pin: " + id);
            }
        }
        if (!defaults.containsKey(source.getId().value())) {
            return null;
        }
        TypedValue value = TypeValueCodec.INSTANCE.decode(JsonValue.fromJava(defaults.get(source.getId().value())));
        if (!type.equals(value.type())) {
            throw new IllegalArgumentException("Typed Function default type must match its qualified parameter type: " + source.getId().value());
        }
        if (!source.isOptional() && value.state() == TypedValue.State.ABSENT) {
            throw new IllegalArgumentException("Required Function parameters cannot use an absent default");
        }
        return value;
    }

    private static TypeExpr functionPinType(NodeDefinition definition, NodeDefinition.PinDefinition source) {
        Map<String, Object> config = definition.getHandlerConfig();
        if (config == null || !config.containsKey(CustomFunctionNodeDefinitions.FUNCTION_TYPES)) {
            return typeExpression(source.getTypeRef());
        }
        if (!CustomFunctionCallHandler.HANDLER_ID.equals(definition.getHandler())
            || !CustomFunctionCallHandler.OPERATION.equals(config.get("operation"))
            || !(config.get(CustomFunctionNodeDefinitions.FUNCTION_TYPES) instanceof Map<?, ?> types)) {
            throw new IllegalArgumentException("Typed Function pin types require a custom Function call descriptor");
        }
        Set<String> expected = new HashSet<>();
        Set<FunctionParameterId> parameters = new HashSet<>();
        for (NodeDefinition.PinDefinition pin : definition.getInputs()) {
            requireFunctionTypeIdentity(pin, NodeDefinition.PinDirection.INPUT, expected, parameters);
        }
        for (NodeDefinition.PinDefinition pin : definition.getOutputs()) {
            requireFunctionTypeIdentity(pin, NodeDefinition.PinDirection.OUTPUT, expected, parameters);
        }
        if (!expected.equals(types.keySet())) {
            throw new IllegalArgumentException("Typed Function pin types must identify every declared parameter exactly once");
        }
        if (source.getType() != NodeDefinition.PinType.DATA) {
            return typeExpression(source.getTypeRef());
        }
        return TypeValueCodec.INSTANCE.decodeType(JsonValue.fromJava(types.get(source.getId().value())));
    }

    private static void requireFunctionTypeIdentity(NodeDefinition.PinDefinition pin, NodeDefinition.PinDirection direction,
                                                     Set<String> expected, Set<FunctionParameterId> parameters) {
        if (pin.getType() != NodeDefinition.PinType.DATA) {
            return;
        }
        String prefix = direction == NodeDefinition.PinDirection.INPUT ? "function-input-" : "function-output-";
        String id = pin.getId().value();
        if (pin.getDirection() != direction || !id.startsWith(prefix)
            || !parameters.add(FunctionParameterId.parseCanonicalText(id.substring(prefix.length()))) || !expected.add(id)) {
            throw new IllegalArgumentException("Typed Function parameter pins require unique stable UUID identities: " + id);
        }
    }

    private static Object catalogDefaultValue(TypeExpr type, String value) {
        if (type instanceof TypeExpr.Named named && named.arguments().isEmpty()
            && "builtin".equals(named.reference().ownerId())) {
            return switch (named.reference().localId()) {
                case "boolean" -> parseCatalogBoolean(value);
                case "integer" -> Long.valueOf(value.strip());
                case "number" -> new BigDecimal(value.strip());
                case "uuid" -> UUID.fromString(value.strip());
                default -> value;
            };
        }
        return value;
    }

    private static Boolean parseCatalogBoolean(String value) {
        String normalized = value.strip();
        if ("true".equalsIgnoreCase(normalized)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(normalized)) {
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("Boolean default must be true or false");
    }

    static RuntimeOperationDescriptor runtimeOperationDescriptor(NodeDefinition definition, HandlerRegistry handlers) {
        if (definition == null) {
            throw new IllegalArgumentException("A Flow node definition is required");
        }
        OwnerId owner = catalogOwner(definition);
        String nodeId = nodeLocalId(owner, definition);
        String handlerId = defaultText(definition.getHandler(), "");
        if (!definition.isTrigger() && handlerId.isBlank()) {
            throw new IllegalArgumentException("Flow node handler is required: " + definition.getId());
        }
        if (!handlerId.isBlank() && handlers == null) {
            throw new IllegalStateException("Flow handler registry is required for " + handlerId);
        }
        AuthoredNodeMetadata authored = definition.getAuthoredMetadata();
        String capabilityId = authored != null
            ? authored.handlerCapability()
            : handlerCapabilityId(definition, nodeId, handlers);
        ContractRef<CapabilityId> handler = ContractRef.of(owner, CapabilityId.of(capabilityId));
        OperationId operation = OperationId.of(operationId(definition, handlers));
        NodeHandler runtimeHandler = !handlerId.isBlank() ? handlers.getHandler(handlerId) : null;
        if (!handlerId.isBlank() && runtimeHandler == null) {
            throw new IllegalArgumentException("Flow handler is unavailable: " + handlerId);
        }
        Map<String, Object> unknown = new LinkedHashMap<>();
        unknown.put("legacyHandlerId", defaultText(definition.getHandler(), ""));
        unknown.put("legacyHandlerClass", runtimeHandler != null ? runtimeHandler.getClass().getName() : defaultText(definition.getEventType(), "event"));
        unknown.put("handlerConfig", portable(definition.getHandlerConfig()));
        if (definition.isTrigger()) {
            unknown.put("eventType", defaultText(definition.getEventType(), "event"));
        }
        List<RuntimeOperationDescriptor.Pin> pins = new ArrayList<>(definition.getInputs().size() + definition.getOutputs().size());
        for (NodeDefinition.PinDefinition pin : definition.getInputs()) {
            pins.add(new RuntimeOperationDescriptor.Pin(pin.getId(), RuntimeOperationDescriptor.Direction.INPUT,
                functionPinType(definition, pin)));
        }
        for (NodeDefinition.PinDefinition pin : definition.getOutputs()) {
            pins.add(new RuntimeOperationDescriptor.Pin(pin.getId(), RuntimeOperationDescriptor.Direction.OUTPUT,
                functionPinType(definition, pin)));
        }
        if (localFunction(definition)) {
            operation = CatalogFunctionShape.operation(pins);
        }
        RuntimeSemantics semantics = runtimeSemantics(owner, handler, definition, runtimeHandler, pins);
        return new RuntimeOperationDescriptor(handler, ContractRef.of(owner, operation), pins, semantics, unknown);
    }

    private static boolean localFunction(NodeDefinition definition) {
        return CustomFunctionCallHandler.HANDLER_ID.equals(definition.getHandler())
            && definition.getId() != null && definition.getId().startsWith(CustomFunctionNodeDefinitions.NODE_PREFIX)
            && definition.getSchemaVersion() == 1 && "local".equals(definition.getHandlerConfig().get("functionNamespace"));
    }

    private static String handlerCapabilityId(NodeDefinition definition, String nodeId, HandlerRegistry handlers) {
        if (localFunction(definition)) {
            return CatalogFunctionShape.capability(ContractRef.of(catalogOwner(definition), NodeId.of(nodeId))).id().value();
        }
        String handler = defaultText(definition.getHandler(), "");
        String source = definition.isTrigger()
            ? "event." + defaultText(definition.getEventType(), nodeId)
            : "handler." + defaultText(handler, nodeId);
        source += "." + executionIdentitySuffix(definition, nodeId, handlers) + "." + nodeId;
        return derivedLocalId(source, "handler");
    }

    private static String executionIdentitySuffix(NodeDefinition definition, String nodeId, HandlerRegistry handlers) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("nodeId", nodeId);
        identity.put("handler", defaultText(definition.getHandler(), ""));
        NodeHandler handler = handlers != null && definition.getHandler() != null ? handlers.getHandler(definition.getHandler()) : null;
        identity.put("handlerClass", handler != null ? handler.getClass().getName() : "");
        identity.put("handlerThreadPolicy", handler != null ? handler.getThreadPolicy().name() : "MAIN");
        identity.put("handlerConfig", portable(definition.getHandlerConfig()));
        identity.put("inputs", pinIdentity(definition.getInputs()));
        identity.put("outputs", pinIdentity(definition.getOutputs()));
        identity.put("kind", portable(definition.getKind()));
        identity.put("destructive", definition.isDestructive());
        identity.put("sensitive", definition.isSensitive());
        identity.put("auditPolicy", definition.getAuditPolicy());
        identity.put("confirmationPolicy", definition.getConfirmationPolicy());
        return CanonicalJson.sha256("flow-runtime-capability", identity).substring(0, 12);
    }

    private static List<Map<String, Object>> pinIdentity(List<NodeDefinition.PinDefinition> pins) {
        if (pins == null || pins.isEmpty()) {
            return List.of();
        }
        return pins.stream().map(pin -> Map.<String, Object>of(
            "name", pin.getId().value(),
            "runtimeName", pin.getRuntimeName(),
            "type", pin.getTypeRef().toString(),
            "direction", String.valueOf(pin.getDirection()))).toList();
    }

    private static String operationId(NodeDefinition definition, HandlerRegistry handlers) {
        Map<String, Object> config = definition.getHandlerConfig();
        if (config != null && config.containsKey("operation")) {
            Object value = config.get("operation");
            if (value instanceof String operation && !operation.isBlank()) {
                String normalized = operation.strip();
                if (handlers != null && definition.getHandler() != null && !handlers.hasOperation(definition.getHandler(), normalized)) {
                    throw new IllegalArgumentException("Flow handler does not declare operation: " + definition.getId() + "." + normalized);
                }
                return normalized;
            }
            throw new IllegalArgumentException("Flow handler operation must be a non-blank string: " + definition.getId());
        }
        if (definition.isTrigger()) {
            return "trigger_" + nodeLocalId(catalogOwner(definition), definition);
        }
        if (handlers != null && definition.getHandler() != null) {
            Set<String> operations = handlers.getSupportedOperations(definition.getHandler());
            if (operations.size() == 1) {
                return operations.stream().findFirst().orElseThrow();
            }
            if (operations.size() > 1) {
                throw new IllegalArgumentException("Flow node has no exact handler operation: " + definition.getId());
            }
        }
        throw new IllegalArgumentException("Flow node has no exact handler operation: " + definition.getId());
    }

    private static RuntimeSemantics runtimeSemantics(OwnerId owner, ContractRef<CapabilityId> handler, NodeDefinition definition,
                                                     NodeHandler runtimeHandler, List<RuntimeOperationDescriptor.Pin> pins) {
        RuntimeSemantics.Effect effect = definition.isDestructive() ? RuntimeSemantics.Effect.DESTRUCTIVE
            : definition.getKind() == NodeDefinition.NodeKind.PURE ? RuntimeSemantics.Effect.PURE
            : definition.getKind() == NodeDefinition.NodeKind.QUERY ? RuntimeSemantics.Effect.STATE_READING
            : RuntimeSemantics.Effect.STATE_MUTATING;
        RuntimeSemantics.Audit audit = switch (definition.getAuditPolicy().toLowerCase(Locale.ROOT)) {
            case "metadata" -> RuntimeSemantics.Audit.METADATA;
            case "full", "full-redacted" -> RuntimeSemantics.Audit.FULL_REDACTED;
            default -> RuntimeSemantics.Audit.NONE;
        };
        RuntimeSemantics.Confirmation confirmation = definition.isDestructive() ? RuntimeSemantics.Confirmation.SERVER : switch (definition.getConfirmationPolicy().toLowerCase(Locale.ROOT)) {
            case "client" -> RuntimeSemantics.Confirmation.CLIENT;
            case "server", "explicit_flow_intent" -> RuntimeSemantics.Confirmation.SERVER;
            default -> RuntimeSemantics.Confirmation.NONE;
        };
        RuntimeSemantics.SensitiveData sensitive = definition.isSensitive() ? RuntimeSemantics.SensitiveData.REDACTED : RuntimeSemantics.SensitiveData.NONE;
        TypeExpr any = TypeExpr.named(TypeReference.of("builtin", "any"));
        RuntimeFailureContract failure = new RuntimeFailureContract(any, Set.of("RUNTIME.HANDLER_FAILURE"), Set.of("failure"), RuntimeFailureContract.CommitBoundary.NO_MUTATION);
        RuntimeSemantics.ThreadMode thread = runtimeHandler == null ? RuntimeSemantics.ThreadMode.MAIN : switch (runtimeHandler.getThreadPolicy()) {
            case MAIN -> RuntimeSemantics.ThreadMode.MAIN;
            case ASYNC -> RuntimeSemantics.ThreadMode.ASYNCHRONOUS;
            case CURRENT -> RuntimeSemantics.ThreadMode.CURRENT;
        };
        return new RuntimeSemantics(effect, thread, handler, RuntimeSemantics.Cancellation.NONE,
            0, 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER, RuntimeSemantics.Idempotency.NONE,
            audit, confirmation, sensitive, RuntimeSemantics.Determinism.DETERMINISTIC, Set.of(), Set.of("failure"), Set.of(), failure,
            runtimeResourceReads(pins), Set.of());
    }

    private static Set<ContractRef<ResourceTypeId>> runtimeResourceReads(List<RuntimeOperationDescriptor.Pin> pins) {
        Set<ContractRef<ResourceTypeId>> resources = new HashSet<>();
        pins.forEach(pin -> collectRuntimeResources(pin.type(), resources));
        return Set.copyOf(resources);
    }

    private static void collectRuntimeResources(TypeExpr type, Set<ContractRef<ResourceTypeId>> resources) {
        switch (type) {
            case TypeExpr.ResourceType resource -> resources.add(ContractRef.of(OwnerId.of(resource.resourceType().ownerId()),
                ResourceTypeId.of(resource.resourceType().localId())));
            case TypeExpr.Named named -> named.arguments().forEach(argument -> collectRuntimeResources(argument, resources));
            case TypeExpr.OptionalType optional -> collectRuntimeResources(optional.element(), resources);
            case TypeExpr.ListType list -> collectRuntimeResources(list.element(), resources);
            case TypeExpr.MapType map -> {
                collectRuntimeResources(map.key(), resources);
                collectRuntimeResources(map.value(), resources);
            }
            case TypeExpr.TupleType tuple -> tuple.elements().forEach(element -> collectRuntimeResources(element, resources));
            case TypeExpr.ResultType result -> {
                collectRuntimeResources(result.success(), resources);
                collectRuntimeResources(result.failure(), resources);
            }
            case TypeExpr.UnionType union -> union.variants().forEach(variant -> collectRuntimeResources(variant.type(), resources));
            case TypeExpr.OpaqueType ignored -> {
            }
        }
    }

    private static TypeExpr typeExpression(FlowTypeRef source) {
        if (source == null) {
            return TypeExpr.named(TypeReference.of("builtin", "any"));
        }
        String id = source.getTypeId() != null && !source.getTypeId().isBlank() ? source.getTypeId().strip().toLowerCase(Locale.ROOT) : "any";
        FlowDataType flowType = FlowDataType.fromString(id);
        if ("resource_reference".equals(id) || flowType.getParent() == FlowDataType.RESOURCE_REFERENCE || namedResourceReference(id)) {
            if ("resource_reference".equals(id) && (source.getArguments() == null || source.getArguments().isEmpty())) {
                throw new IllegalArgumentException("Resource reference type must declare a resource type");
            }
            String resourceId = "resource_reference".equals(id) && source.getArguments() != null && !source.getArguments().isEmpty()
                ? source.getArguments().getFirst().getTypeId() : id;
            return TypeExpr.resource(resourceTypeReference(resourceId));
        }
        List<TypeExpr> arguments = source.getArguments() == null ? List.of() : source.getArguments().stream().map(FlowModule::typeExpression).toList();
        return switch (id) {
            case "optional" -> TypeExpr.optional(arguments.isEmpty() ? TypeExpr.named(TypeReference.of("builtin", "any")) : arguments.getFirst());
            case "list", "set", "queue", "stack" -> TypeExpr.list(arguments.isEmpty() ? TypeExpr.named(TypeReference.of("builtin", "any")) : arguments.getFirst());
            case "map" -> TypeExpr.map(arguments.size() > 0 ? arguments.getFirst() : TypeExpr.named(TypeReference.of("builtin", "any")), arguments.size() > 1 ? arguments.get(1) : TypeExpr.named(TypeReference.of("builtin", "any")));
            case "result" -> TypeExpr.result(arguments.size() > 0 ? arguments.getFirst() : TypeExpr.named(TypeReference.of("builtin", "any")), arguments.size() > 1 ? arguments.get(1) : TypeExpr.named(TypeReference.of("builtin", "any")));
            default -> TypeExpr.named(typeReference(id, "type"), arguments);
        };
    }

    private static boolean namedResourceReference(String id) {
        int separator = id.indexOf(':');
        String localId = separator > 0 && separator < id.length() - 1 ? id.substring(separator + 1) : id;
        if (FlowDataType.JOB_REFERENCE.getId().equals(localId)) {
            return false;
        }
        if (localId.endsWith("_id") || (localId.endsWith("_reference") && !"resource_reference".equals(localId))
            || Set.of("function", "flow", "gui", "scoreboard", "tab").contains(localId)) {
            return true;
        }
        return false;
    }

    private static TypeReference resourceTypeReference(String value) {
        String normalized = value != null ? value.toLowerCase(Locale.ROOT).strip() : "";
        if (normalized.isBlank() || "resource_reference".equals(normalized)) {
            throw new IllegalArgumentException("Resource reference type must declare a resource type");
        }
        TypeReference reference = typeReference(normalized, "resource");
        return TypeReference.of(reference.ownerId(), declaredResourceType(reference.localId()));
    }

    static ContractRef<ResourceTypeId> canonicalAuthoredResourceType(ContractRef<ResourceTypeId> resource,
                                                                       OwnerId sourceOwner) {
        if (!resource.owner().equals(sourceOwner)) {
            return resource;
        }
        String requested = resource.id().value();
        String declared = declaredResourceType(requested);
        if (ReSyncResourceCatalog.STRUCTURE.equals(declared)
            && ReSyncResourceCatalog.STRUCTURE_OWNER.equals(resource.owner().value())) {
            return resource;
        }
        String canonical = ReSyncResourceCatalog.byType(declared) != null ? declared : requested;
        return ContractRef.of(OwnerId.of("builtin"), ResourceTypeId.of(canonical));
    }

    private static TypeReference typeReference(String value, String fallback) {
        String normalized = defaultText(value, fallback).strip().toLowerCase(Locale.ROOT);
        int separator = normalized.indexOf(':');
        if (separator < 0) {
            return TypeReference.of("builtin", exactLocalId(normalized, fallback));
        }
        if (separator == 0 || separator == normalized.length() - 1 || separator != normalized.lastIndexOf(':')) {
            throw new IllegalArgumentException("Type identity must contain one owner separator: " + normalized);
        }
        return TypeReference.of(normalized.substring(0, separator), normalized.substring(separator + 1));
    }

    private static String declaredResourceType(String value) {
        String candidate = value;
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalArgumentException("Resource reference type must declare a resource type");
        }
        if (candidate != null && candidate.endsWith("_id")) {
            candidate = candidate.substring(0, candidate.length() - 3);
        } else if (candidate != null && candidate.endsWith("_reference") && !"resource_reference".equals(candidate)) {
            candidate = candidate.substring(0, candidate.length() - "_reference".length());
        }
        if (candidate.isBlank()) {
            throw new IllegalArgumentException("Resource reference type must declare a resource type");
        }
        if (candidate != null && ReSyncResourceCatalog.byType(candidate) != null) {
            return exactLocalId(candidate, "resource");
        }
        String definitionType = candidate != null ? candidate + "_definition" : "resource_reference";
        if (ReSyncResourceCatalog.byType(definitionType) != null) {
            return exactLocalId(definitionType, "resource");
        }
        return exactLocalId(defaultText(candidate, "resource_reference"), "resource");
    }

    private static Map<String, Object> nodeMetadata(NodeDefinition definition) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sourceNodeId", defaultText(definition.getId(), "unknown"));
        metadata.put("sourceOwner", defaultText(definition.getOwner(), "builtin"));
        metadata.put("color", definition.getColor());
        metadata.put("priority", definition.getPriority());
        metadata.put("hidden", definition.isHidden());
        metadata.put("hiddenReason", definition.getHiddenReason());
        metadata.put("handler", defaultText(definition.getHandler(), ""));
        metadata.put("handlerConfig", portable(definition.getHandlerConfig()));
        metadata.put("trigger", definition.isTrigger());
        metadata.put("eventType", defaultText(definition.getEventType(), ""));
        metadata.put("aliases", portable(definition.getAliases()));
        metadata.put("outputMappings", mappingMetadata(definition.getOutputMappings()));
        metadata.put("inputs", pinMetadata(definition.getInputs()));
        metadata.put("outputs", pinMetadata(definition.getOutputs()));
        metadata.put("schemaVersion", definition.getSchemaVersion());
        metadata.put("kind", portable(definition.getKind()));
        metadata.put("deprecated", definition.isDeprecated());
        metadata.put("legacyIds", portable(definition.getLegacyIds()));
        metadata.put("canonicalId", portable(definition.getCanonicalId()));
        metadata.put("tags", portable(definition.getTags()));
        metadata.put("examples", portable(definition.getExamples()));
        metadata.put("availability", availability(definition.getAvailability()));
        metadata.put("family", defaultText(definition.getFamily(), "default"));
        metadata.put("recommended", definition.isRecommended());
        metadata.put("replacementFor", defaultText(definition.getReplacementFor(), ""));
        metadata.put("authorizationPolicy", definition.getAuthorizationPolicy());
        metadata.put("sensitive", definition.isSensitive());
        metadata.put("destructive", definition.isDestructive());
        metadata.put("auditPolicy", definition.getAuditPolicy());
        metadata.put("confirmationPolicy", definition.getConfirmationPolicy());
        metadata.put("clockDomain", definition.getClockDomain());
        AuthoredNodeMetadata authored = definition.getAuthoredMetadata();
        if (authored != null) {
            metadata.put("authoredSource", authored.toMetadata());
        }
        Map<String, Object> handlerConfig = definition.getHandlerConfig();
        if (handlerConfig != null) {
            for (String key : List.of("dropContributions", "drop_contributions", "resourceDropContributions", "functionBoundaries", "functionBoundaryIntents", "functionBoundary")) {
                if (handlerConfig.containsKey(key)) {
                    metadata.put(key, portable(handlerConfig.get(key)));
                }
            }
            if (definition.getId() != null && definition.getId().startsWith("custom_function:")) {
                Object functionId = handlerConfig.get("functionId");
                if (functionId instanceof String value && !value.isBlank()) {
                    String defaultOwner = defaultText(definition.getOwner(), "server");
                    String functionOwner = handlerConfig.get("functionOwner") instanceof String ownerValue
                        ? defaultText(ownerValue, defaultOwner) : defaultOwner;
                    String functionNamespace = handlerConfig.get("functionNamespace") instanceof String namespaceValue
                        ? defaultText(namespaceValue, "local") : "local";
                    metadata.put("customFunctionIdentity", Map.of(
                        "id", value,
                        "owner", functionOwner,
                        "namespace", functionNamespace));
                }
            }
        }
        return portableMetadata(metadata);
    }

    private static List<Map<String, Object>> mappingMetadata(List<NodeDefinition.PinMapping> mappings) {
        if (mappings == null || mappings.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> values = new ArrayList<>();
        for (NodeDefinition.PinMapping mapping : mappings) {
            if (mapping == null) {
                values.add(Map.of());
                continue;
            }
            values.add(Map.of("source", defaultText(mapping.source(), ""), "target", defaultText(mapping.target(), "")));
        }
        return List.copyOf(values);
    }

    private static List<Map<String, Object>> pinMetadata(List<NodeDefinition.PinDefinition> pins) {
        if (pins == null || pins.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> values = new ArrayList<>();
        for (NodeDefinition.PinDefinition pin : pins) {
            if (pin == null) {
                values.add(Map.of());
                continue;
            }
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("name", pin.getName());
            value.put("runtimeName", pin.getRuntimeName());
            value.put("type", portable(pin.getType()));
            value.put("direction", portable(pin.getDirection()));
            value.put("dataType", pin.getDataType() != null ? pin.getDataType().getId() : "");
            value.put("typeRef", pin.getTypeRef() != null ? pin.getTypeRef().toString() : "any");
            value.put("repeatable", repeatableMetadata(pin.getRepeatable()));
            value.put("widgetType", portable(pin.getWidgetType()));
            value.put("options", pin.getOptions());
            value.put("optionsSource", defaultText(pin.getOptionsSource(), ""));
            value.put("defaultValue", defaultText(pin.getDefaultValue(), ""));
            value.put("constraints", constraintsMetadata(pin.getConstraints()));
            value.put("visibleWhen", pin.getVisibleWhen());
            value.put("description", defaultText(pin.getDescription(), ""));
            value.put("optional", pin.isOptional());
            values.add(portableMetadata(value));
        }
        return List.copyOf(values);
    }

    private static Map<String, Object> portableMetadata(Map<String, Object> metadata) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            result.put(entry.getKey(), portable(entry.getValue()));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Object> repeatableMetadata(NodeDefinition.RepeatablePin repeatable) {
        if (repeatable == null) {
            return Map.of();
        }
        return Map.of("groupId", defaultText(repeatable.getGroupId(), ""), "minItems", repeatable.getMinItems(),
            "maxItems", repeatable.getMaxItems(), "itemLabel", defaultText(repeatable.getItemLabel(), ""));
    }

    private static Map<String, Object> constraintsMetadata(NodeDefinition.PinConstraints constraints) {
        if (constraints == null) {
            return Map.of();
        }
        Map<String, Object> value = new LinkedHashMap<>();
        if (constraints.getMin() != null) {
            value.put("min", constraints.getMin());
        }
        if (constraints.getMax() != null) {
            value.put("max", constraints.getMax());
        }
        if (constraints.getStep() != null) {
            value.put("step", constraints.getStep());
        }
        return Collections.unmodifiableMap(value);
    }

    private static Map<String, Object> availability(NodeDefinition.Availability availability) {
        if (availability == null) {
            return Map.of();
        }
        return Map.of("plugin", defaultText(availability.getPlugin(), ""), "platform", defaultText(availability.getPlatform(), ""), "minVersion", defaultText(availability.getMinVersion(), ""));
    }

    private static Object portable(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Character) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key) || key.isBlank()) {
                    throw new IllegalArgumentException("Catalog metadata map keys must be non-blank strings");
                }
                result.put(key, portable(entry.getValue()));
            }
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(FlowModule::portable).toList();
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> result = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                result.add(portable(Array.get(value, index)));
            }
            return Collections.unmodifiableList(result);
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        throw new IllegalArgumentException("Unsupported catalog metadata value: " + value.getClass().getName());
    }

    private static String exactLocalId(String value, String fallback) {
        return CapabilityId.of(defaultText(value, fallback)).value();
    }

    private static String nodeLocalId(OwnerId owner, NodeDefinition definition) {
        return nodeLocalId(owner, definition.getId(), definition.getCanonicalId());
    }

    private static String nodeLocalId(OwnerId owner, String value) {
        return nodeLocalId(owner, value, null);
    }

    private static String nodeLocalId(OwnerId owner, String value, String canonicalId) {
        String authored = defaultText(value, "node");
        if (authored.startsWith("custom_function:")) {
            String functionId = authored.substring("custom_function:".length());
            if (functionId.isBlank()) {
                throw new IllegalArgumentException("Custom function node identity is required");
            }
            if (canonicalId != null && !canonicalId.isBlank()) {
                return exactLocalId(canonicalId, "node");
            }
            String readable = derivedLocalId(functionId, "node");
            String suffix = UUID.nameUUIDFromBytes(("custom-function\u0000" + functionId).getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "").substring(0, 12);
            int readableLimit = 128 - suffix.length() - 1;
            if (readable.length() > readableLimit) {
                readable = readable.substring(0, readableLimit);
            }
            return exactLocalId(readable + "-" + suffix, "node");
        }
        String namespace = owner.value() + ":";
        if (authored.startsWith(namespace) && authored.substring(namespace.length()).isBlank()) {
            authored = "node";
        }
        return NodeDefinition.sourceReference(owner, authored).id().value();
    }

    private static String derivedLocalId(String value, String fallback) {
        String source = defaultText(value, fallback).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-");
        String[] parts = source.split("[._-]+");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            String normalized = part.replaceAll("[^a-z0-9]+", "");
            if (normalized.isBlank()) {
                continue;
            }
            if (!Character.isLetter(normalized.charAt(0))) {
                normalized = "x" + normalized;
            }
            if (normalized.length() > 32) {
                normalized = normalized.substring(0, 32);
            }
            if (!result.isEmpty()) {
                result.append('-');
            }
            result.append(normalized);
        }
        String normalized = result.isEmpty() ? fallback : result.toString();
        return normalized.length() > 128 ? normalized.substring(0, 128) : normalized;
    }

    private String contributionVersion(String owner) {
        return contributionVersion(owner, extensionData);
    }

    private static String contributionVersion(String owner, ReSyncExtensionData contributionData) {
        String value = contributionData != null ? contributionData.version(owner) : null;
        if (value == null || !value.matches("\\d+\\.\\d+\\.\\d+(?:[-+][A-Za-z0-9.-]+)?")) {
            return "1.0.0";
        }
        return value;
    }

    private static String text(String value, String fallback) {
        String normalized = defaultText(value, fallback).trim();
        if (normalized.length() > 128) {
            throw new IllegalArgumentException("Catalog text exceeds 128 characters");
        }
        return normalized;
    }

    private static String description(NodeDefinition definition) {
        String value = definition != null ? definition.getDescription() : null;
        String generated = definition != null ? defaultText(definition.getDisplayName(), "Node") + " Flow capability." : "";
        String generatedFunction = definition != null && definition.getId() != null && definition.getId().startsWith("custom_function:")
            ? "Run " + defaultText(definition.getDisplayName(), "Node") + "." : "";
        if (value != null && (value.trim().equals(generated) || value.trim().equals(generatedFunction))) {
            throw new IllegalArgumentException("Catalog requires an authored node description");
        }
        return description(value);
    }

    private static String description(String value) {
        String normalized = value != null ? value.trim() : "";
        if (normalized.length() < 24 || normalized.length() > 280) {
            throw new IllegalArgumentException("Catalog requires an authored description between 24 and 280 characters");
        }
        return normalized;
    }

    private static String pinDescription(String value) {
        String normalized = value != null ? value.trim() : "";
        if (normalized.length() < 16 || normalized.length() > 240) {
            throw new IllegalArgumentException("Catalog requires an authored pin description between 16 and 240 characters");
        }
        return normalized;
    }

    private String string(JsonObject root, String key) {
        if (root == null || key == null || !root.has(key) || root.get(key).isJsonNull()) {
            return "";
        }
        return root.get(key).getAsString();
    }

    private String readRemaining(ByteBuffer buffer) {
        if (buffer == null || !buffer.hasRemaining()) {
            return "";
        }
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private record ResourceActivationRequest(String type, String resourceId, boolean enabled, String requestId) {
    }

    private record ResourceActivationResult(boolean success, String type, String resourceId, boolean enabled, String requestId, String message,
                                            boolean editorError) {
    }

    public final class CatalogRuntimeTransaction implements AutoCloseable {
        private final CatalogRuntimeActivation.ActivationTransaction activation;
        private final RuntimeReplacementPlan runtimePlan;
        private final List<CatalogContribution> baselineContributions;
        private final NodeDefinitionRegistry baselineDefinitions;
        private final HandlerRegistry baselineHandlers;
        private final Set<ContractRef<ProviderId>> baselineRuntimeProviders;
        private final Set<ContractRef<ProviderId>> baselinePendingRuntimeRetirements;
        private final boolean blocked;
        private final boolean noop;
        private boolean committed;
        private boolean published;
        private boolean rolledBack;
        private boolean blockedOrStale;
        private boolean staleActivation;
        private boolean rollbackStale;
        private boolean publicationFinalized;
        private Throwable finalizationFailure;
        private Throwable rollbackFailure;

        private CatalogRuntimeTransaction(CatalogRuntimeActivation.ActivationTransaction activation,
                                          RuntimeReplacementPlan runtimePlan, boolean blocked, boolean noop,
                                          List<CatalogContribution> baselineContributions,
                                          NodeDefinitionRegistry baselineDefinitions,
                                          HandlerRegistry baselineHandlers,
                                          Set<ContractRef<ProviderId>> baselineRuntimeProviders,
                                          Set<ContractRef<ProviderId>> baselinePendingRuntimeRetirements) {
            this.activation = activation;
            this.runtimePlan = Objects.requireNonNull(runtimePlan, "Runtime Replacement Plan Is Required");
            this.baselineContributions = List.copyOf(Objects.requireNonNull(baselineContributions, "Baseline catalog contributions are required"));
            this.baselineDefinitions = Objects.requireNonNull(baselineDefinitions, "Baseline node definitions are required");
            this.baselineHandlers = Objects.requireNonNull(baselineHandlers, "Baseline handler registry is required");
            this.baselineRuntimeProviders = Set.copyOf(Objects.requireNonNull(baselineRuntimeProviders, "Baseline runtime providers are required"));
            this.baselinePendingRuntimeRetirements = Set.copyOf(Objects.requireNonNull(baselinePendingRuntimeRetirements,
                "Baseline pending runtime retirements are required"));
            this.blocked = blocked;
            this.noop = noop;
        }

        public boolean blocked() {
            return blocked;
        }

        public boolean noop() {
            return noop;
        }

        public boolean committed() {
            return committed;
        }

        public synchronized boolean blockedOrStale() {
            return blocked || blockedOrStale;
        }

        public synchronized boolean retryable() {
            return !rollbackStale && blockedOrStale() && !published && !committed && !rolledBack
                && finalizationFailure == null && rollbackFailure == null;
        }

        public synchronized boolean rollback() {
            if (noop || rolledBack) {
                return true;
            }
            if (rollbackFailure != null) {
                return false;
            }
            if (!published) {
                return rollbackFailure == null;
            }
            if (activation == null) {
                return rejectRollback(new IllegalStateException("Flow catalog rollback has no active candidate"));
            }
            if (!liveCandidateMatches()) {
                rollbackStale = true;
                blockedOrStale = true;
                return false;
            }
            if (pendingCatalogRuntimeCleanup != null) {
                if (pendingCatalogRuntimeCleanup.transaction() != activation) {
                    return rejectRollback(new IllegalStateException("Flow catalog rollback cleanup belongs to another activation"));
                }
                boolean cleanupRetried;
                try {
                    cleanupRetried = retryPendingCatalogRuntimeCleanup();
                } catch (RuntimeException | Error cleanupFailure) {
                    return rejectRollback(cleanupFailure);
                }
                if (!cleanupRetried) {
                    return rejectRollback(new IllegalStateException("Flow catalog rollback cleanup was rejected"));
                }
                if (!liveCandidateMatches()) {
                    rollbackStale = true;
                    blockedOrStale = true;
                    return false;
                }
            }
            RuntimeReplacementPlan rollbackPlan = null;
            CatalogRuntimeActivation.ActivationTransaction rollbackActivation = null;
            try {
                Set<ContractRef<ProviderId>> liveRuntimeProviders = new HashSet<>(catalogRuntimeActivation.active().runtime().providers().keySet());
                liveRuntimeProviders.addAll(runtimeProviders);
                rollbackPlan = prepareRuntimeReplacement(
                    baselineContributions, baselineDefinitions, baselineHandlers, Set.of(),
                    liveRuntimeProviders, Set.copyOf(pendingRuntimeRetirements), baselineRuntimeProviders);
                RuntimeBindingRegistry.RuntimeReplacement replacement = rollbackPlan.replacement();
                rollbackActivation = catalogRuntimeActivation.stageExactRestore(activation, replacement);
                CatalogRuntimeActivation.ActivationResult result = rollbackActivation.commit();
                if (!result.committed()) {
                    throw new IllegalStateException("Flow catalog rollback activation was rejected: " + result.detail());
                }
                applyRuntimeReplacement(rollbackPlan);
                catalogPublicationTransport.prewarmAuthoring();
                runtimeProviders.clear();
                runtimeProviders.addAll(baselineRuntimeProviders);
                pendingRuntimeRetirements.clear();
                pendingRuntimeRetirements.addAll(baselinePendingRuntimeRetirements);
                retainCatalogRuntimeCleanup(rollbackActivation, result);
                committed = false;
                published = false;
                rolledBack = true;
                return true;
            } catch (RuntimeException | Error exception) {
                try {
                    if (rollbackActivation != null) {
                        rollbackActivation.close();
                    } else if (rollbackPlan != null) {
                        rollbackPlan.replacement().close();
                    }
                } catch (RuntimeException | Error closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
                return rejectRollback(exception);
            }
        }

        private boolean rejectRollback(Throwable failure) {
            rollbackFailure = Objects.requireNonNull(failure, "Rollback failure is required");
            Log.warn("Flow catalog Core rollback was rejected: " + failure.getMessage());
            return false;
        }

        static boolean sameCatalogRuntimeCandidate(CatalogRuntimeActivation.ActivationRecord live,
                                                   CatalogRuntimeActivation.ActivationRecord candidate) {
            return live.catalog().generation() == candidate.catalog().generation()
                && live.catalog().contentChecksum().equals(candidate.catalog().contentChecksum())
                && live.catalog().bindingManifestHash().equals(candidate.catalog().bindingManifestHash())
                && live.runtime().generation() == candidate.runtime().generation()
                && live.runtime().bindingManifestHash().equals(candidate.runtime().bindingManifestHash());
        }

        private boolean liveCandidateMatches() {
            CatalogRuntimeActivation.ActivationRecord live = catalogRuntimeActivation.active();
            CatalogRuntimeActivation.ActivationRecord candidate = activation.candidate();
            return live.catalog() == candidate.catalog() && sameCatalogRuntimeCandidate(live, candidate);
        }

        private boolean isLiveCandidate() {
            try {
                return activation != null && liveCandidateMatches();
            } catch (RuntimeException | Error ignored) {
                return false;
            }
        }

        public synchronized boolean commit() {
            if (!commitCore()) {
                return false;
            }
            finalizePublication();
            return true;
        }

        public synchronized boolean commitCore() {
            if (rolledBack) {
                throw new IllegalStateException("Flow catalog runtime transaction was rolled back");
            }
            if (rollbackFailure != null) {
                throw new IllegalStateException("Flow catalog runtime transaction failed during rollback", rollbackFailure);
            }
            if (finalizationFailure != null) {
                throw new IllegalStateException("Flow catalog runtime transaction failed after Core publication", finalizationFailure);
            }
            if (staleActivation || rollbackStale) {
                throw new IllegalStateException("Flow catalog runtime transaction became stale");
            }
            if (committed) {
                return retryPendingCatalogRuntimeCleanup();
            }
            if (blocked) {
                blockedOrStale = true;
                return false;
            }
            if (noop) {
                try {
                    StartupNoopCommitResult result = commitStartupNoopRuntime(runtimeBindingRegistry,
                        runtimePlan.replacement());
                    if (result.status() == StartupNoopCommitStatus.STALE) {
                        blockedOrStale = result.retryable();
                        staleActivation = true;
                        return false;
                    }
                    catalogPublicationTransport.prewarmAuthoring();
                    committed = true;
                    return true;
                } catch (RuntimeException | Error failure) {
                    finalizationFailure = failure;
                    throw failure;
                }
            }
            CatalogRuntimeActivation.ActivationResult result;
            try {
                result = activation.commit();
            } catch (RuntimeException | Error failure) {
                if (isLiveCandidate()) {
                    published = true;
                }
                finalizationFailure = failure;
                throw failure;
            }
            if (!result.committed()) {
                if (result.pending()) {
                    blockedOrStale = true;
                    staleActivation = result.status() == CatalogRuntimeActivation.ActivationStatus.STALE;
                }
                return false;
            }
            if (result.status() != CatalogRuntimeActivation.ActivationStatus.NOOP) {
                published = true;
            }
            try {
                retainCatalogRuntimeCleanup(activation, result);
                applyRuntimeReplacement(runtimePlan);
                catalogPublicationTransport.prewarmAuthoring();
                committed = true;
                return true;
            } catch (RuntimeException | Error failure) {
                finalizationFailure = failure;
                throw failure;
            }
        }

        public synchronized void finalizePublication() {
            if (!committed) {
                throw new IllegalStateException("Flow catalog runtime publication requires a committed Core activation");
            }
            if (publicationFinalized) {
                return;
            }
            publicationFinalized = true;
            if (noop) {
                return;
            }
            try {
                boolean publicationAccepted = catalogPublicationPolicy.admitRefresh();
                if (!publicationAccepted) {
                    lastCatalogRefreshPublicationFailureCode = catalogPublicationPolicy.lastFailureCode()
                        .orElse("CATALOG_PUBLICATION.UNAVAILABLE");
                    reportCatalogPublicationFailure(lastCatalogRefreshPublicationFailureCode);
                } else {
                    lastCatalogRefreshPublicationFailureCode = "";
                }
            } catch (RuntimeException publicationFailure) {
                lastCatalogRefreshPublicationFailureCode = "CATALOG_PUBLICATION.UNAVAILABLE";
                Log.error("Flow catalog activation committed, but typed publication finalization failed: "
                    + publicationFailure.getMessage(), publicationFailure);
            }
        }

        @Override
        public void close() {
            if (committed || published) {
                return;
            }
            if (pendingCatalogRuntimeCleanup != null) {
                return;
            }
            if (activation != null) {
                activation.close();
            } else {
                runtimePlan.replacement().close();
            }
        }
    }

    public final class RuntimeBindingRetirement implements AutoCloseable {
        private final Set<ContractRef<ProviderId>> providers;
        private final RuntimeBindingRegistry.RuntimeReplacement replacement;
        private final int blockedLeases;
        private final String detail;

        private RuntimeBindingRetirement(Set<ContractRef<ProviderId>> providers,
                                         RuntimeBindingRegistry.RuntimeReplacement replacement,
                                         int blockedLeases, String detail) {
            this.providers = Set.copyOf(providers);
            this.replacement = replacement;
            this.blockedLeases = blockedLeases;
            this.detail = detail == null ? "" : detail;
        }

        private RuntimeBindingRetirement() {
            this(Set.of(), null, 0, "");
        }

        private RuntimeBindingRetirement(Set<ContractRef<ProviderId>> providers, String detail) {
            this(providers, null, 0, detail);
        }

        public Set<ContractRef<ProviderId>> providers() {
            return providers;
        }

        public int blockedLeases() {
            return blockedLeases;
        }

        public boolean blocked() {
            return !detail.isBlank() || blockedLeases > 0;
        }

        public String detail() {
            return detail;
        }

        @Override
        public void close() {
            if (replacement != null) {
                replacement.close();
            }
        }
    }

    private record RuntimeReplacementPlan(
        RuntimeBindingRegistry.RuntimeReplacement replacement,
        Set<ContractRef<ProviderId>> additions,
        Set<ContractRef<ProviderId>> retiring
    ) {
        private RuntimeReplacementPlan {
            replacement = Objects.requireNonNull(replacement, "Runtime Replacement Is Required");
            additions = Set.copyOf(Objects.requireNonNull(additions, "Runtime Additions Are Required"));
            retiring = Set.copyOf(Objects.requireNonNull(retiring, "Runtime Retirements Are Required"));
        }
    }

    private record CatalogBuild(
        List<CatalogContribution> contributions,
        Map<RuntimeOperationDescriptor, NodeDefinition> runtimeDefinitions
    ) {
        private CatalogBuild {
            contributions = List.copyOf(contributions);
            runtimeDefinitions = Collections.unmodifiableMap(new LinkedHashMap<>(runtimeDefinitions));
        }
    }

    private record PendingCatalogActivation(
        CatalogRuntimeActivation.ActivationTransaction transaction,
        RuntimeReplacementPlan runtimePlan
    ) {
        private PendingCatalogActivation {
            transaction = Objects.requireNonNull(transaction, "Catalog Activation Transaction Is Required");
            runtimePlan = Objects.requireNonNull(runtimePlan, "Runtime Replacement Plan Is Required");
        }
    }

    private record PendingCatalogRuntimeCleanup(
        CatalogRuntimeActivation.ActivationTransaction transaction
    ) {
        private PendingCatalogRuntimeCleanup {
            transaction = Objects.requireNonNull(transaction, "Catalog Activation Transaction Is Required");
        }
    }
}
