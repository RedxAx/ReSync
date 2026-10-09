package restudio.resync.velocity;

import org.java_websocket.WebSocket;
import restudio.resync.network.NetworkEditorChunk;
import restudio.resync.network.NetworkEditorClose;
import restudio.resync.network.NetworkEditorCodec;
import restudio.resync.network.NetworkEditorOpen;
import restudio.resync.network.NetworkFrame;
import restudio.resync.network.NetworkFrameType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

final class VelocityEditorGateway {
    private static final long TIMEOUT = TimeUnit.SECONDS.toNanos(15);
    private final Transport transport;
    private final Map<UUID, Tunnel> tunnels = new HashMap<>();
    private final Map<Object, Set<UUID>> used = new HashMap<>();

    VelocityEditorGateway(Transport transport) {
        this.transport = transport;
    }

    synchronized void receive(Endpoint source, NetworkFrame frame) {
        if (!transport.active(source)) return;
        if (frame.type() == NetworkFrameType.EDITOR_OPEN) {
            open(source, NetworkEditorCodec.decodeOpen(frame.payload()));
            return;
        }
        NetworkEditorChunk chunk = frame.type() == NetworkFrameType.EDITOR_DATA ? NetworkEditorCodec.decodeChunk(frame.payload()) : null;
        NetworkEditorClose closed = frame.type() == NetworkFrameType.EDITOR_CLOSE ? NetworkEditorCodec.decodeClose(frame.payload()) : null;
        UUID id = switch (frame.type()) {
            case EDITOR_DATA -> chunk.tunnelId();
            case EDITOR_CLOSE -> closed.tunnelId();
            case EDITOR_OPENED -> NetworkEditorCodec.decodeOpened(frame.payload());
            default -> throw new IllegalArgumentException("Editor Frame Type Is Invalid");
        };
        Tunnel tunnel = tunnels.get(id);
        if (tunnel == null) return;
        if (!source.equals(tunnel.observer) && !source.equals(tunnel.backend)) {
            throw new SecurityException("Editor Tunnel Belongs To Another Session");
        }
        if (!valid(tunnel)) {
            close(tunnel, 1012, "Editor Server Reconnected");
            return;
        }
        try {
            if (frame.type() == NetworkFrameType.EDITOR_CLOSE) {
                close(tunnel, closed.code(), closed.reason());
            } else if (frame.type() == NetworkFrameType.EDITOR_OPENED) {
                if (!source.equals(tunnel.backend) || tunnel.open) throw new IllegalArgumentException("Editor Open Reply Is Invalid");
                tunnel.open = true;
                if (!transport.send(tunnel.observer, frame.type(), id, frame.payload())) close(tunnel, 1013, "Editor Transport Unavailable");
            } else {
                if (!tunnel.open) throw new IllegalArgumentException("Editor Tunnel Is Not Open");
                boolean observer = source.equals(tunnel.observer);
                Direction direction = chunk.receipt() ? observer ? tunnel.toObserver : tunnel.toBackend : observer ? tunnel.toBackend : tunnel.toObserver;
                if (chunk.receipt()) {
                    direction.receipt(chunk);
                } else {
                    if (chunk.offset() == 0 && buffered(observer ? tunnel.backend : tunnel.observer) + chunk.totalBytes() > NetworkEditorChunk.MAXIMUM_FRAME_BYTES) {
                        throw new IllegalStateException("Editor Connection Queue Full");
                    }
                    direction.chunk(chunk);
                }
                Endpoint target = observer ? tunnel.backend : tunnel.observer;
                if (!transport.send(target, frame.type(), id, frame.payload())) close(tunnel, 1013, "Editor Transport Unavailable");
            }
        } catch (RuntimeException failure) {
            close(tunnel, 1008, failure.getMessage() == null ? "Invalid Editor Frame" : failure.getMessage());
        }
    }

    private void open(Endpoint observer, NetworkEditorOpen request) {
        Tunnel existing = tunnels.get(request.tunnelId());
        if (existing != null) {
            if (existing.observer.equals(observer)) {
                close(existing, 1008, "Editor Tunnel ID Already Used");
                return;
            }
            throw new SecurityException("Editor Tunnel Belongs To Another Session");
        }
        if (!transport.canOpen(observer)) {
            transport.send(observer, NetworkFrameType.EDITOR_CLOSE, request.tunnelId(),
                NetworkEditorCodec.encodeClose(new NetworkEditorClose(request.tunnelId(), 1008, "Editor Access Is Not Granted")));
            return;
        }
        Set<UUID> ids = used.computeIfAbsent(observer.session(), ignored -> new HashSet<>());
        Endpoint backend = transport.backend(request.targetNodeId());
        String reason = backend == null ? "Editor Server Offline" : backend.session() == observer.session() ? "Editor Target Must Be Another Backend" : tunnels.size() >= 128 || count(observer, true) >= 8 || count(backend, false) >= 32
            ? "Editor Tunnel Limit Reached" : ids.size() >= 1024 || ids.contains(request.tunnelId()) || tunnels.containsKey(request.tunnelId())
            || used.getOrDefault(backend.session(), Set.of()).contains(request.tunnelId()) ? "Editor Tunnel ID Already Used" : "";
        if (!reason.isEmpty()) {
            transport.send(observer, NetworkFrameType.EDITOR_CLOSE, request.tunnelId(), NetworkEditorCodec.encodeClose(new NetworkEditorClose(request.tunnelId(), 1013, reason)));
            return;
        }
        Set<UUID> backendIds = used.computeIfAbsent(backend.session(), ignored -> new HashSet<>());
        if (backendIds.size() >= 4096) {
            transport.send(observer, NetworkFrameType.EDITOR_CLOSE, request.tunnelId(),
                NetworkEditorCodec.encodeClose(new NetworkEditorClose(request.tunnelId(), 1013, "Editor Backend Session Must Reconnect")));
            return;
        }
        ids.add(request.tunnelId());
        backendIds.add(request.tunnelId());
        Tunnel tunnel = new Tunnel(request.tunnelId(), observer, backend);
        tunnels.put(tunnel.id, tunnel);
        if (!transport.send(backend, NetworkFrameType.EDITOR_OPEN, tunnel.id, NetworkEditorCodec.encodeOpen(request))) {
            close(tunnel, 1013, "Editor Server Unavailable");
        }
    }

    private boolean valid(Tunnel tunnel) {
        return transport.active(tunnel.observer) && transport.canOpen(tunnel.observer)
            && tunnel.backend.equals(transport.backend(tunnel.backend.nodeId()));
    }

    private long count(Endpoint endpoint, boolean observer) {
        return tunnels.values().stream().filter(tunnel -> endpoint.equals(observer ? tunnel.observer : tunnel.backend)).count();
    }

    private long buffered(Endpoint target) {
        long bytes = 0;
        for (Tunnel tunnel : tunnels.values()) {
            if (target.equals(tunnel.backend)) bytes += tunnel.toBackend.total;
            if (target.equals(tunnel.observer)) bytes += tunnel.toObserver.total;
        }
        return bytes;
    }

    synchronized void remove(Object session, String reason) {
        for (Tunnel tunnel : tunnels.values().toArray(Tunnel[]::new)) {
            if (tunnel.observer.session() == session || tunnel.backend.session() == session) close(tunnel, 1012, reason);
        }
        used.remove(session);
    }

    synchronized void expire() {
        long now = System.nanoTime();
        for (Tunnel tunnel : tunnels.values().toArray(Tunnel[]::new)) {
            if (!valid(tunnel)) close(tunnel, 1012, "Editor Session Unavailable");
            else if (!tunnel.open && now - tunnel.createdAt >= TIMEOUT || tunnel.toBackend.expired(now) || tunnel.toObserver.expired(now)) {
                close(tunnel, 1001, "Editor Delivery Timed Out");
            }
        }
    }

    private void close(Tunnel tunnel, int code, String reason) {
        if (!tunnels.remove(tunnel.id, tunnel)) return;
        String bounded = reason.length() > 256 ? reason.substring(0, 256) : reason;
        byte[] payload = NetworkEditorCodec.encodeClose(new NetworkEditorClose(tunnel.id, code, bounded));
        transport.send(tunnel.observer, NetworkFrameType.EDITOR_CLOSE, tunnel.id, payload);
        transport.send(tunnel.backend, NetworkFrameType.EDITOR_CLOSE, tunnel.id, payload);
    }

    record Endpoint(WebSocket connection, Object session, String nodeId) {
    }

    interface Transport {
        boolean active(Endpoint endpoint);
        boolean canOpen(Endpoint endpoint);
        Endpoint backend(String nodeId);
        boolean send(Endpoint endpoint, NetworkFrameType type, UUID tunnelId, byte[] payload);
    }

    private static final class Tunnel {
        private final UUID id;
        private final Endpoint observer;
        private final Endpoint backend;
        private final Direction toBackend = new Direction();
        private final Direction toObserver = new Direction();
        private final long createdAt = System.nanoTime();
        private boolean open;

        private Tunnel(UUID id, Endpoint observer, Endpoint backend) {
            this.id = id;
            this.observer = observer;
            this.backend = backend;
        }
    }

    private static final class Direction {
        private long sequence;
        private int offset;
        private int total;
        private long startedAt;

        private void chunk(NetworkEditorChunk chunk) {
            if (chunk.sequence() != sequence || chunk.offset() != offset || total > 0 && (offset == total || chunk.totalBytes() != total)) {
                throw new IllegalArgumentException("Editor Chunk Sequence Is Invalid");
            }
            if (total == 0) {
                total = chunk.totalBytes();
                startedAt = System.nanoTime();
            }
            offset += chunk.size();
        }

        private void receipt(NetworkEditorChunk chunk) {
            if (chunk.sequence() != sequence || total == 0 || offset != total) throw new IllegalArgumentException("Editor Receipt Is Invalid");
            total = 0;
            offset = 0;
            sequence = Math.addExact(sequence, 1);
        }

        private boolean expired(long now) {
            return total > 0 && now - startedAt >= TIMEOUT;
        }
    }
}
