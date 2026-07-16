package restudio.resync.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import restudio.resync.network.NetworkPlayerLifecycle;
import restudio.resync.network.NetworkPlayerLifecycleType;

import java.nio.file.Path;
import java.time.Instant;

@Plugin(id = "resyncvelocity", name = "ReSyncVelocity", version = "1.3.0", description = "ReSync Network Hub")
public class ReSyncVelocity {
    private final Logger logger;
    private final Path dataDirectory;
    private final ProxyServer proxyServer;
    private ReSyncVelocityHub hub;

    @Inject
    public ReSyncVelocity(Logger logger, @DataDirectory Path dataDirectory, ProxyServer proxyServer) {
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.proxyServer = proxyServer;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        try {
            VelocityNetworkConfig config = VelocityNetworkConfigLoader.load(dataDirectory);
            if (!config.enabled()) {
                logger.info("ReSync network hub is disabled");
                return;
            }
            hub = new ReSyncVelocityHub(config, logger, proxyServer);
            hub.startHub();
        } catch (Exception exception) {
            logger.error("Failed to start ReSync network hub", exception);
            if (hub != null) {
                hub.stopHub();
                hub = null;
            }
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (hub != null) {
            hub.stopHub();
            hub = null;
        }
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        ReSyncVelocityHub current = hub;
        if (current == null || !event.getResult().isAllowed()) {
            return;
        }
        String routeName = event.getResult().getServer().orElse(event.getOriginalServer()).getServerInfo().getName();
        if (event.getPreviousServer() == null) {
            ReSyncVelocityHub.InitialRoutingDecision decision = current.initialRoutingDestination(event.getPlayer(), routeName);
            if (decision.matched() && decision.destination() == null) {
                event.setResult(ServerPreConnectEvent.ServerResult.denied());
                return;
            }
            if (decision.destination() != null) {
                event.setResult(ServerPreConnectEvent.ServerResult.allowed(decision.destination()));
                routeName = decision.destination().getServerInfo().getName();
            }
        }
        if (current.acceptsRoute(routeName)) {
            return;
        }
        var destination = current.maintenanceDestination(routeName);
        event.setResult(destination == null ? ServerPreConnectEvent.ServerResult.denied() : ServerPreConnectEvent.ServerResult.allowed(destination));
    }

    @Subscribe(order = PostOrder.LAST)
    public EventTask onServerPreConnectObserved(ServerPreConnectEvent event) {
        ReSyncVelocityHub current = hub;
        if (current == null) {
            return null;
        }
        String target = event.getResult().getServer().orElse(event.getOriginalServer()).getServerInfo().getName();
        if (event.getPreviousServer() == null) {
            if (!event.getResult().isAllowed() || !current.requiresInitialPlayerState(target)) {
                return null;
            }
            var preparation = current.prepareInitialPlayer(event.getPlayer().getUniqueId(), target, Instant.now().plusSeconds(30).toEpochMilli()).handle((destination, throwable) -> {
                if (throwable != null) {
                    event.setResult(ServerPreConnectEvent.ServerResult.denied());
                    current.publishPlayerLifecycle(new NetworkPlayerLifecycle(NetworkPlayerLifecycleType.TRANSFER_FAILED, event.getPlayer().getUniqueId(), event.getPlayer().getUsername(), "", target, "STATE_RESTORE_FAILED", Instant.now().toEpochMilli()));
                    logger.warn("Initial player state preparation failed for {}: {}", event.getPlayer().getUsername(), rootMessage(throwable));
                } else {
                    event.setResult(ServerPreConnectEvent.ServerResult.allowed(destination));
                }
                return null;
            });
            return EventTask.resumeWhenComplete(preparation);
        }
        String source = event.getPreviousServer().getServerInfo().getName();
        if (source.equalsIgnoreCase(target)) {
            return null;
        }
        long now = Instant.now().toEpochMilli();
        current.publishPlayerLifecycle(new NetworkPlayerLifecycle(NetworkPlayerLifecycleType.TRANSFER_STARTED, event.getPlayer().getUniqueId(), event.getPlayer().getUsername(), source, target, "", now));
        if (!event.getResult().isAllowed()) {
            current.playerTransferFailed(event.getPlayer().getUniqueId(), source, target, "CONNECTION_CANCELLED");
            current.publishPlayerLifecycle(new NetworkPlayerLifecycle(NetworkPlayerLifecycleType.TRANSFER_FAILED, event.getPlayer().getUniqueId(), event.getPlayer().getUsername(), source, target, "CONNECTION_CANCELLED", now));
            return null;
        }
        if (!current.requiresPlayerStateTransfer(source, target)) {
            return null;
        }
        var preparation = current.preparePlayerTransfer(event.getPlayer().getUniqueId(), source, target, Instant.now().plusSeconds(30).toEpochMilli()).handle((transfer, throwable) -> {
            if (throwable != null) {
                event.setResult(ServerPreConnectEvent.ServerResult.denied());
                current.playerTransferFailed(event.getPlayer().getUniqueId(), source, target, "STATE_HANDOFF_FAILED");
                current.publishPlayerLifecycle(new NetworkPlayerLifecycle(NetworkPlayerLifecycleType.TRANSFER_FAILED, event.getPlayer().getUniqueId(), event.getPlayer().getUsername(), source, target, "STATE_HANDOFF_FAILED", Instant.now().toEpochMilli()));
                logger.warn("Player state handoff failed for {}: {}", event.getPlayer().getUsername(), rootMessage(throwable));
            }
            return null;
        });
        return EventTask.resumeWhenComplete(preparation);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        ReSyncVelocityHub current = hub;
        if (current == null) {
            return;
        }
        String source = event.getPlayer().getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse("");
        current.publishPlayerLifecycle(new NetworkPlayerLifecycle(NetworkPlayerLifecycleType.LEFT, event.getPlayer().getUniqueId(), event.getPlayer().getUsername(), source, "", event.getLoginStatus().name(), Instant.now().toEpochMilli()));
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        ReSyncVelocityHub current = hub;
        if (current == null || event.getPlayer().getCurrentServer().isEmpty()) {
            return;
        }
        String target = event.getPlayer().getCurrentServer().orElseThrow().getServerInfo().getName();
        if (event.getPreviousServer() == null) {
            current.playerConnected(event.getPlayer().getUniqueId(), "", target);
            current.playerJoined(event.getPlayer().getUniqueId(), target);
            current.publishPlayerLifecycle(new NetworkPlayerLifecycle(NetworkPlayerLifecycleType.JOINED, event.getPlayer().getUniqueId(), event.getPlayer().getUsername(), "", target, "", Instant.now().toEpochMilli()));
            return;
        }
        String source = event.getPreviousServer().getServerInfo().getName();
        if (!source.equalsIgnoreCase(target)) {
            current.playerConnected(event.getPlayer().getUniqueId(), source, target);
            current.publishPlayerLifecycle(new NetworkPlayerLifecycle(NetworkPlayerLifecycleType.TRANSFER_COMPLETED, event.getPlayer().getUniqueId(), event.getPlayer().getUsername(), source, target, "", Instant.now().toEpochMilli()));
        }
    }

    @Subscribe
    public void onKickedFromServer(KickedFromServerEvent event) {
        ReSyncVelocityHub current = hub;
        if (current == null || !event.kickedDuringServerConnect()) {
            return;
        }
        String source = event.getPlayer().getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse("");
        String target = event.getServer().getServerInfo().getName();
        current.playerTransferFailed(event.getPlayer().getUniqueId(), source, target, "SERVER_DISCONNECTED");
        current.publishPlayerLifecycle(new NetworkPlayerLifecycle(NetworkPlayerLifecycleType.TRANSFER_FAILED, event.getPlayer().getUniqueId(), event.getPlayer().getUsername(), source, target, "SERVER_DISCONNECTED", Instant.now().toEpochMilli()));
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
