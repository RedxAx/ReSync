package restudio.resync.server;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import restudio.resync.core.ConnectionInfo;
import restudio.resync.core.ConnectionState;
import restudio.resync.core.Session;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ProtocolBody;
import restudio.resync.flow.protocol.ProtocolEnvelope;
import restudio.resync.flow.protocol.ResourceActivateRequest;
import restudio.resync.flow.protocol.ResourceCreateRequest;
import restudio.resync.flow.protocol.ResourceDeleteRequest;
import restudio.resync.flow.protocol.ResourceDuplicateRequest;
import restudio.resync.flow.protocol.ResourceLoadRequest;
import restudio.resync.flow.protocol.ResourceListRequest;
import restudio.resync.flow.protocol.ResourceMoveRequest;
import restudio.resync.flow.protocol.ResourceOperation;
import restudio.resync.flow.protocol.ResourceQueryRequest;
import restudio.resync.flow.protocol.ResourceRenameRequest;
import restudio.resync.flow.protocol.ResourceSaveRequest;
import restudio.resync.flow.protocol.ResourceSubscribeRequest;
import restudio.resync.security.ClientIdentity;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ProtocolRequestAuthority {
    private static final String OPERATOR_PREFIX = "resync.qa/";

    private ProtocolRequestAuthority() {
    }

    static String trustedClientId(ConnectionInfo connection, Session session) {
        if (connection == null || session == null || session.getConnection() != connection
            || connection.getState() != ConnectionState.AUTHENTICATED) {
            return null;
        }
        ClientIdentity identity = session.getIdentity();
        String connectionClientId = connection.getClientId();
        String sessionClientId = session.getClientId();
        String identityClientId = identity != null ? identity.clientId() : null;
        if (connectionClientId == null || connectionClientId.isBlank() || connectionClientId.startsWith(OPERATOR_PREFIX)
            || sessionClientId == null || sessionClientId.isBlank()
            || identityClientId == null || identityClientId.isBlank()) {
            return null;
        }
        return connectionClientId.equals(sessionClientId) && connectionClientId.equals(identityClientId) ? connectionClientId : null;
    }

    public static String trustedOperatorId(CommandSender actor) {
        if (!Bukkit.isPrimaryThread() || actor == null || !actor.hasPermission("resync.qa")) {
            return null;
        }
        if (actor instanceof Player player) {
            return OPERATOR_PREFIX + "player/" + player.getUniqueId();
        }
        if (actor instanceof ConsoleCommandSender) {
            return OPERATOR_PREFIX + "console";
        }
        String name = actor.getName();
        if (name == null || name.isBlank()) {
            return null;
        }
        return OPERATOR_PREFIX + "sender/" + name;
    }

    public static OperatorGrant admitOperator(CommandSender actor, ProtocolEnvelope<Map<String, Object>> envelope) {
        String actorId = trustedOperatorId(actor);
        if (actorId == null) {
            throw new SecurityException("ReSync QA permission and server thread admission are required");
        }
        if (envelope == null || envelope.kind() != ProtocolEnvelope.Kind.REQUEST || envelope.requestId() == null
            || envelope.authorityEpoch() < 1L || !owns(envelope.serverId(), envelope)) {
            throw new IllegalArgumentException("A typed resource request is required");
        }
        return new OperatorGrant(actorId, envelope);
    }

    public static final class OperatorGrant {
        private final String actorId;
        private final ProtocolEnvelope<Map<String, Object>> request;
        private final AtomicBoolean claimed = new AtomicBoolean();

        private OperatorGrant(String actorId, ProtocolEnvelope<Map<String, Object>> request) {
            this.actorId = actorId;
            this.request = request;
        }

        public String actorId() {
            return actorId;
        }

        public ServerId serverId() {
            return request.serverId();
        }

        public UUID requestId() {
            return request.requestId();
        }

        public long authorityEpoch() {
            return request.authorityEpoch();
        }

        boolean matches(ServerId serverId, ProtocolEnvelope<Map<String, Object>> envelope) {
            return request == envelope && request.serverId().equals(serverId) && !claimed.get();
        }

        boolean claim(ServerId serverId, ProtocolEnvelope<Map<String, Object>> envelope) {
            return request == envelope && request.serverId().equals(serverId) && claimed.compareAndSet(false, true);
        }
    }

    static boolean owns(ServerId serverId, ProtocolEnvelope<Map<String, Object>> envelope) {
        if (serverId == null || envelope == null || !serverId.equals(envelope.serverId()) || !owns(serverId, envelope.resource())) {
            return false;
        }
        if (!(envelope.body() instanceof ProtocolBody.ResourceRequest request)) {
            return false;
        }
        return owns(serverId, request.operation()) && envelopeMatchesOperation(envelope.resource(), request.operation());
    }

    static boolean owns(ServerId serverId, ProtocolEnvelope<Map<String, Object>> envelope, ResourceOperation operation) {
        return owns(serverId, envelope) && envelope.body() instanceof ProtocolBody.ResourceRequest request
            && Objects.equals(request.operation(), operation) && owns(serverId, operation);
    }

    private static boolean owns(ServerId serverId, ResourceOperation operation) {
        return switch (operation) {
            case ResourceLoadRequest load -> owns(serverId, load.resource());
            case ResourceCreateRequest<?> create -> owns(serverId, create.resource());
            case ResourceSaveRequest<?> save -> owns(serverId, save.resource());
            case ResourceRenameRequest rename -> owns(serverId, rename.resource());
            case ResourceMoveRequest move -> owns(serverId, move.resource());
            case ResourceDuplicateRequest duplicate -> owns(serverId, duplicate.source()) && owns(serverId, duplicate.target());
            case ResourceActivateRequest activate -> owns(serverId, activate.resource());
            case ResourceDeleteRequest delete -> owns(serverId, delete.resource());
            case ResourceSubscribeRequest subscribe -> owns(serverId, subscribe.resource());
            default -> true;
        };
    }

    private static boolean envelopeMatchesOperation(ServerResourceLocator envelopeResource, ResourceOperation operation) {
        return switch (operation) {
            case ResourceLoadRequest load -> Objects.equals(envelopeResource, load.resource());
            case ResourceCreateRequest<?> create -> Objects.equals(envelopeResource, create.resource());
            case ResourceSaveRequest<?> save -> Objects.equals(envelopeResource, save.resource());
            case ResourceRenameRequest rename -> Objects.equals(envelopeResource, rename.resource());
            case ResourceMoveRequest move -> Objects.equals(envelopeResource, move.resource());
            case ResourceDuplicateRequest duplicate -> Objects.equals(envelopeResource, duplicate.target());
            case ResourceActivateRequest activate -> Objects.equals(envelopeResource, activate.resource());
            case ResourceDeleteRequest delete -> Objects.equals(envelopeResource, delete.resource());
            case ResourceSubscribeRequest subscribe -> Objects.equals(envelopeResource, subscribe.resource());
            case ResourceListRequest ignored -> envelopeResource == null;
            case ResourceQueryRequest ignored -> envelopeResource == null;
            default -> true;
        };
    }

    private static boolean owns(ServerId serverId, ServerResourceLocator resource) {
        return resource == null || Objects.equals(serverId, resource.serverId());
    }
}
