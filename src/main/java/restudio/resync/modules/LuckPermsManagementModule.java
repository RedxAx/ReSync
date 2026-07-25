package restudio.resync.modules;

import com.google.gson.Gson;
import restudio.resync.Log;
import restudio.resync.core.Session;
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

import java.nio.charset.StandardCharsets;
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

    @Override
    public ModuleMetadata getMetadata() {
        return METADATA;
    }

    @Override
    public void initialize(ModuleContext context) {
        codec = context.getCodec();
        channelId = context.getChannelMuxer().getChannel(getChannelId()).getNumericId();
        scheduler = context.getScheduler();
        service = new LuckPermsManagementService(context.getPlugin());
        service.addListener(this::broadcast);
        context.registerService(LuckPermsManagementService.class, service);
    }

    @Override
    public void stop(ModuleContext context) {
        sessions.clear();
        if (service != null) {
            service.close();
        }
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
