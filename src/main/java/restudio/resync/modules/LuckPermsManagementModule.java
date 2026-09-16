package restudio.resync.modules;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.RegisteredServiceProvider;
import restudio.resync.Log;
import restudio.resync.core.Session;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.ReSyncPersistenceCoordinator;
import restudio.resync.permissions.LuckPermsBackendPersistenceCapability;
import restudio.resync.permissions.LuckPermsBackendPersistenceParticipant;
import restudio.resync.permissions.LuckPermsOperationPersistenceParticipant;
import restudio.resync.permissions.LuckPermsManagementContract;
import restudio.resync.permissions.LuckPermsManagementContract.Action;
import restudio.resync.permissions.LuckPermsManagementContract.Invalidation;
import restudio.resync.permissions.LuckPermsManagementContract.Request;
import restudio.resync.permissions.LuckPermsManagementContract.Response;
import restudio.resync.permissions.LuckPermsManagementService;
import restudio.resync.protocol.Codec;
import restudio.resync.protocol.messages.DataMessage;
import restudio.resync.protocol.messages.SubscribeRequest;
import restudio.resync.protocol.messages.UnsubscribeRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;

public final class LuckPermsManagementModule implements Module {
    private static final ModuleMetadata METADATA = ModuleMetadata.of("luckPermsManagement", "LuckPerms Management",
        LuckPermsManagementContract.CHANNEL_ID);
    private final Gson gson = new Gson();
    private final Set<Session> sessions = ConcurrentHashMap.newKeySet();
    private Codec codec;
    private int channelId;
    private ScheduledExecutorService scheduler;
    private LuckPermsManagementService service;
    private final LuckPermsBackendPersistenceCapability configuredBackendPersistence;
    private final LuckPermsBackendPersistenceCapability.Adapter configuredBackendAdapter;
    private final LuckPermsBackendPersistenceCapability.AdapterProvider configuredBackendProvider;
    private LuckPermsBackendPersistenceCapability backendPersistence;
    private LuckPermsBackendPersistenceParticipant backendParticipant;
    private LuckPermsOperationPersistenceParticipant operationParticipant;

    public LuckPermsManagementModule() {
        this(null, null, null);
    }

    public LuckPermsManagementModule(LuckPermsBackendPersistenceCapability backendPersistence) {
        this(backendPersistence, null, null);
    }

    public LuckPermsManagementModule(LuckPermsBackendPersistenceCapability.Adapter backendAdapter) {
        this(null, backendAdapter, null);
    }

    public LuckPermsManagementModule(LuckPermsBackendPersistenceCapability.AdapterProvider backendProvider) {
        this(null, null, backendProvider);
    }

    private LuckPermsManagementModule(LuckPermsBackendPersistenceCapability backendPersistence,
                                      LuckPermsBackendPersistenceCapability.Adapter backendAdapter,
                                      LuckPermsBackendPersistenceCapability.AdapterProvider backendProvider) {
        configuredBackendPersistence = backendPersistence;
        configuredBackendAdapter = backendAdapter;
        configuredBackendProvider = backendProvider;
    }

    @Override
    public ModuleMetadata getMetadata() {
        return METADATA;
    }

    @Override
    public void initialize(ModuleContext context) {
        codec = context.getCodec();
        channelId = context.getChannelMuxer().getChannel(getChannelId()).getNumericId();
        scheduler = context.getScheduler();
        Path activeRoot = activeDataRoot(context);
        backendPersistence = resolveBackendPersistence(context);
        service = new LuckPermsManagementService(context.getPlugin(), backendPersistence, activeRoot);
        service.addListener(this::broadcast);
        backendParticipant = backendPersistence.adapterConfigured()
            ? new LuckPermsBackendPersistenceParticipant(activeRoot, backendPersistence) : null;
        operationParticipant = new LuckPermsOperationPersistenceParticipant(activeRoot, service);
        context.registerService(LuckPermsManagementService.class, service);
        context.registerService(LuckPermsBackendPersistenceCapability.class, backendPersistence);
        if (backendParticipant != null) {
            context.registerService(LuckPermsBackendPersistenceParticipant.class, backendParticipant);
        }
        context.registerService(LuckPermsOperationPersistenceParticipant.class, operationParticipant);
        Bukkit.getServicesManager().register(LuckPermsBackendPersistenceCapability.class, backendPersistence, context.getPlugin(), ServicePriority.Normal);
    }

    private Path activeDataRoot(ModuleContext context) {
        ReSyncPersistenceCoordinator persistence = context.getRequiredService(ReSyncPersistenceCoordinator.class);
        try {
            return MigrationPaths.requirePath(persistence.activeDataRoot(), "LuckPerms active data root");
        } catch (IOException exception) {
            throw new IllegalStateException("LuckPerms active data root could not be resolved", exception);
        }
    }

    @Override
    public void stop(ModuleContext context) {
        sessions.clear();
        if (service != null) {
            service.closeForModuleShutdown();
        }
        if (backendPersistence != null && !backendParticipantRegistered(context)) {
            try {
                if (backendParticipant != null && backendPersistence.replacementReady()) {
                    backendParticipant.close();
                } else {
                    backendPersistence.close();
                }
            } catch (IOException | RuntimeException exception) {
                Log.warn("LuckPerms backend persistence could not close during module shutdown: " + exception.getMessage());
                try {
                    backendPersistence.close();
                } catch (IOException | RuntimeException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
            }
        }
        if (backendPersistence != null) {
            Bukkit.getServicesManager().unregister(LuckPermsBackendPersistenceCapability.class, backendPersistence);
        }
    }

    private LuckPermsBackendPersistenceCapability resolveBackendPersistence(ModuleContext context) {
        if (configuredBackendPersistence != null) {
            return configuredBackendPersistence;
        }
        LuckPermsBackendPersistenceCapability.Adapter adapter = configuredBackendAdapter;
        LuckPermsBackendPersistenceCapability.AdapterProvider provider = configuredBackendProvider;
        if (adapter == null && provider == null) {
            RegisteredServiceProvider<LuckPermsBackendPersistenceCapability.AdapterProvider> providerRegistration =
                Bukkit.getServicesManager().getRegistration(LuckPermsBackendPersistenceCapability.AdapterProvider.class);
            provider = providerRegistration == null ? null : providerRegistration.getProvider();
        }
        if (adapter == null && provider != null) {
            try {
                adapter = provider.create(context.getPlugin());
            } catch (RuntimeException exception) {
                Log.warn("LuckPerms backend adapter provider failed: " + exception.getMessage());
            }
        }
        if (adapter == null) {
            RegisteredServiceProvider<LuckPermsBackendPersistenceCapability.Adapter> adapterRegistration =
                Bukkit.getServicesManager().getRegistration(LuckPermsBackendPersistenceCapability.Adapter.class);
            adapter = adapterRegistration == null ? null : adapterRegistration.getProvider();
        }
        return adapter == null ? LuckPermsBackendPersistenceCapability.unavailable()
            : new LuckPermsBackendPersistenceCapability(adapter);
    }

    private boolean backendParticipantRegistered(ModuleContext context) {
        ReSyncPersistenceCoordinator persistence = context.getService(ReSyncPersistenceCoordinator.class);
        return persistence != null && backendParticipant != null
            && persistence.registeredParticipants().stream().anyMatch(participant -> participant == backendParticipant);
    }

    @Override
    public void onSubscribe(Session session, SubscribeRequest request) {
        sessions.add(session);
    }

    @Override
    public void onUnsubscribe(Session session, UnsubscribeRequest request) {
        sessions.remove(session);
    }

    @Override
    public void cleanup(Session session) {
        sessions.remove(session);
    }

    @Override
    public void onData(Session session, DataMessage message) {
        if (message.getPayload() == null || message.getPayload().length == 0) {
            return;
        }
        try {
            Request request = gson.fromJson(new String(message.getPayload(), StandardCharsets.UTF_8), Request.class);
            service.handle(request, actor(session)).whenCompleteAsync((response, failure) ->
                send(session, failure == null ? response : error(request, failure)), scheduler);
        } catch (RuntimeException exception) {
            Log.warn("LuckPerms management request failed: " + exception.getMessage());
            send(session, error(null, exception));
        }
    }

    private void broadcast(Invalidation invalidation) {
        Response response = new Response(LuckPermsManagementContract.VERSION, "", Action.OVERVIEW, true, "Permissions Changed",
            invalidation.revision(), null, null, null, List.of(), null, null, null, invalidation);
        for (Session session : sessions) {
            send(session, response);
        }
    }

    private Response error(Request request, Throwable failure) {
        String message = failure.getMessage() == null || failure.getMessage().isBlank() ? "Permission Request Failed" : failure.getMessage();
        return new Response(LuckPermsManagementContract.VERSION, request == null ? "" : request.requestId(),
            request == null ? Action.OVERVIEW : request.action(), false, message, 0, null, null, null, List.of(), null, null, null, null);
    }

    private void send(Session session, Response response) {
        if (session == null || response == null || session.getConnection() == null || !session.getConnection().isOpen()) {
            return;
        }
        DataMessage output = new DataMessage();
        output.setChannel(channelId);
        output.setPayload(gson.toJson(response).getBytes(StandardCharsets.UTF_8));
        codec.sendMessage(session.getConnection().getFrameSender(), output, channelId, true);
    }

    private String actor(Session session) {
        return session == null || session.getClientId() == null || session.getClientId().isBlank() ? "Remotely" : session.getClientId();
    }
}
