package restudio.resync.network.paper;

import restudio.resync.Log;
import restudio.resync.core.ConnectionInfo;
import restudio.resync.network.NetworkEditorAssembler;
import restudio.resync.network.NetworkEditorChunk;
import restudio.resync.network.NetworkEditorClose;
import restudio.resync.network.NetworkEditorCodec;
import restudio.resync.network.NetworkEditorOpen;
import restudio.resync.network.NetworkFrame;
import restudio.resync.network.NetworkFrameType;
import restudio.resync.protocol.FrameSender;
import restudio.resync.server.ReSyncServer;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

final class PaperNetworkEditors {
    private static final int MAXIMUM_QUEUE_BYTES = NetworkEditorChunk.MAXIMUM_FRAME_BYTES;
    private final ReSyncServer server;
    private final String nodeId;
    private final Wire wire;
    private final Map<UUID, Tunnel> tunnels = new HashMap<>();
    private final ArrayDeque<Incoming> incoming = new ArrayDeque<>();
    private int incomingBytes;
    private boolean draining;
    private long generation;
    private int queuedBytes;
    private int assemblingBytes;
    private boolean pumping;

    PaperNetworkEditors(ReSyncServer server, String nodeId, Wire wire) {
        this.server = server;
        this.nodeId = nodeId;
        this.wire = wire;
    }

    boolean enqueue(Object session, NetworkFrame frame) {
        long stamp;
        synchronized (this) {
            if (!wire.active(session)) return false;
            int size = frame.payload().length;
            if (incoming.size() >= 1024 || incomingBytes + size > MAXIMUM_QUEUE_BYTES + 1024 * 64) return false;
            incoming.addLast(new Incoming(session, frame, size));
            incomingBytes += size;
            if (draining) return true;
            draining = true;
            stamp = generation;
            if (wire.execute(session, () -> drain(stamp, session))) return true;
        }
        rejected(stamp, session);
        return false;
    }

    private void drain(long stamp, Object session) {
        for (int count = 0; count < 8; count++) {
            Incoming next;
            synchronized (this) {
                if (generation != stamp) return;
                next = incoming.pollFirst();
                if (next == null) {
                    draining = false;
                    return;
                }
                incomingBytes -= next.bytes;
            }
            if (!wire.active(next.session)) continue;
            try {
                receive(stamp, next.session, next.frame);
            } catch (RuntimeException failure) {
                wire.failed(next.session, "Invalid Network Editor Frame");
            }
        }
        synchronized (this) {
            if (generation != stamp || wire.execute(session, () -> drain(stamp, session))) return;
        }
        rejected(stamp, session);
    }

    private void rejected(long stamp, Object session) {
        closeAll(stamp, "Network Editor Work Queue Full");
        wire.failed(session, "Network Editor Work Queue Full");
    }

    private void receive(long stamp, Object session, NetworkFrame frame) {
        if (frame.type() == NetworkFrameType.EDITOR_OPEN) {
            open(stamp, session, NetworkEditorCodec.decodeOpen(frame.payload()));
            return;
        }
        NetworkEditorChunk chunk = frame.type() == NetworkFrameType.EDITOR_DATA ? NetworkEditorCodec.decodeChunk(frame.payload()) : null;
        NetworkEditorClose closed = frame.type() == NetworkFrameType.EDITOR_CLOSE ? NetworkEditorCodec.decodeClose(frame.payload()) : null;
        UUID id = chunk != null ? chunk.tunnelId() : closed != null ? closed.tunnelId() : null;
        if (id == null) throw new IllegalArgumentException("Editor Frame Type Is Invalid");
        Tunnel tunnel;
        synchronized (this) {
            tunnel = tunnels.get(id);
        }
        if (tunnel == null || tunnel.session != session || tunnel.generation != stamp) return;
        if (frame.type() == NetworkFrameType.EDITOR_CLOSE) {
            close(tunnel, closed.code(), closed.reason(), false);
            return;
        }
        try {
            if (chunk.receipt()) {
                synchronized (this) {
                    if (!current(tunnel)) return;
                    if (tunnel.inFlight == null || tunnel.sequence != chunk.sequence()) throw new IllegalArgumentException("Editor Receipt Is Invalid");
                    queuedBytes -= tunnel.inFlight.length;
                    tunnel.inFlight = null;
                    tunnel.sequence = Math.addExact(tunnel.sequence, 1);
                }
                schedulePump();
            } else {
                byte[] bytes;
                synchronized (this) {
                    if (!current(tunnel)) return;
                    if (tunnel.incomingTotal == 0) {
                        if (assemblingBytes > MAXIMUM_QUEUE_BYTES - chunk.totalBytes()) throw new IllegalStateException("Editor Assembly Limit Reached");
                        tunnel.incomingTotal = chunk.totalBytes();
                        assemblingBytes += chunk.totalBytes();
                    }
                    bytes = tunnel.assembler.accept(chunk);
                    if (bytes == null) return;
                    assemblingBytes -= tunnel.incomingTotal;
                    tunnel.incomingTotal = 0;
                }
                if (!current(tunnel)) return;
                if (!server.onNetworkMessage(tunnel.connection, bytes)) {
                    close(tunnel, 1013, "Editor Message Was Not Admitted", true);
                    return;
                }
                if (current(tunnel) && !wire.send(session, NetworkFrameType.EDITOR_DATA,
                    NetworkEditorCodec.encodeChunk(new NetworkEditorChunk(id, chunk.sequence(), 0, 0, new byte[0])))) {
                    close(tunnel, 1013, "Editor Receipt Unavailable", true);
                }
            }
        } catch (RuntimeException failure) {
            close(tunnel, 1008, "Invalid Editor Frame", true);
        }
    }

    private void open(long stamp, Object session, NetworkEditorOpen request) {
        if (!request.targetNodeId().equals(nodeId)) throw new SecurityException("Editor Target Does Not Match This Server");
        Tunnel tunnel = new Tunnel(request.tunnelId(), session, stamp);
        synchronized (this) {
            if (generation != stamp || !wire.active(session)) return;
            if (tunnels.size() >= 32 || tunnels.containsKey(tunnel.id)) {
                wire.send(session, NetworkFrameType.EDITOR_CLOSE, NetworkEditorCodec.encodeClose(new NetworkEditorClose(tunnel.id, 1013, "Editor Tunnel Limit Reached")));
                return;
            }
            tunnels.put(tunnel.id, tunnel);
        }
        ConnectionInfo connection;
        try {
            connection = server.onNetworkOpen(tunnel);
        } catch (RuntimeException failure) {
            close(tunnel, 1011, "Editor Connection Unavailable", true);
            return;
        }
        synchronized (this) {
            if (generation == stamp && tunnels.get(tunnel.id) == tunnel && wire.active(session) && connection != null) tunnel.connection = connection;
        }
        if (tunnel.connection == null) {
            if (connection != null) {
                try {
                    server.onNetworkClose(connection);
                } catch (RuntimeException failure) {
                    Log.warn("Network Editor Cleanup Failed: " + failure.getMessage());
                    wire.failed(tunnel.session, "Network Editor Cleanup Failed");
                }
            }
            close(tunnel, 1013, "Editor Server Unavailable", true);
        } else if (!wire.send(session, NetworkFrameType.EDITOR_OPENED, NetworkEditorCodec.encodeOpened(tunnel.id))) {
            close(tunnel, 1013, "Editor Open Reply Unavailable", true);
        }
    }

    private synchronized boolean current(Tunnel tunnel) {
        return generation == tunnel.generation && tunnels.get(tunnel.id) == tunnel && wire.active(tunnel.session) && tunnel.connection != null && tunnel.connection.isOpen();
    }

    private void schedulePump() {
        long stamp;
        Object session;
        synchronized (this) {
            if (pumping) return;
            Tunnel next = tunnels.values().stream().filter(tunnel -> current(tunnel) && tunnel.inFlight == null && !tunnel.queue.isEmpty()).findFirst().orElse(null);
            if (next == null) return;
            session = next.session;
            stamp = generation;
            pumping = true;
            if (wire.execute(session, () -> pumpNext(stamp, session))) return;
        }
        rejected(stamp, session);
    }

    private void pumpNext(long stamp, Object session) {
        Tunnel next;
        synchronized (this) {
            if (generation != stamp) return;
            next = tunnels.values().stream().filter(tunnel -> current(tunnel) && tunnel.inFlight == null && !tunnel.queue.isEmpty()).findFirst().orElse(null);
            if (next == null) {
                pumping = false;
                return;
            }
        }
        pump(next);
        synchronized (this) {
            if (generation != stamp || wire.execute(session, () -> pumpNext(stamp, session))) return;
        }
        rejected(stamp, session);
    }

    private void pump(Tunnel tunnel) {
        byte[] bytes;
        long sequence;
        synchronized (this) {
            if (!current(tunnel) || tunnel.inFlight != null || tunnel.queue.isEmpty()) return;
            bytes = tunnel.queue.removeFirst();
            tunnel.inFlight = bytes;
            tunnel.startedAt = System.nanoTime();
            sequence = tunnel.sequence;
        }
        int limit = wire.chunkBytes();
        if (limit < 1) {
            close(tunnel, 1009, "Editor Network Frame Limit Too Small", true);
            return;
        }
        for (int offset = 0; offset < bytes.length; offset += limit) {
            if (!current(tunnel)) return;
            NetworkEditorChunk chunk = new NetworkEditorChunk(tunnel.id, sequence, offset, bytes.length,
                Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + limit)));
            if (!wire.send(tunnel.session, NetworkFrameType.EDITOR_DATA, NetworkEditorCodec.encodeChunk(chunk))) {
                close(tunnel, 1013, "Editor Transport Unavailable", true);
                return;
            }
        }
    }

    void expire() {
        Tunnel[] snapshot;
        synchronized (this) {
            snapshot = tunnels.values().toArray(Tunnel[]::new);
        }
        for (Tunnel tunnel : snapshot) {
            boolean expired;
            synchronized (this) {
                expired = tunnel.inFlight != null && System.nanoTime() - tunnel.startedAt >= TimeUnit.SECONDS.toNanos(15);
            }
            if (!wire.active(tunnel.session) || expired) close(tunnel, 1001, "Editor Delivery Timed Out", true);
        }
    }

    void closeAll(String reason) {
        closeAll(null, reason);
    }

    private void closeAll(Long stamp, String reason) {
        Tunnel[] snapshot;
        synchronized (this) {
            if (stamp != null && generation != stamp) return;
            generation++;
            snapshot = tunnels.values().toArray(Tunnel[]::new);
            incoming.clear();
            incomingBytes = 0;
            draining = false;
            pumping = false;
        }
        for (Tunnel tunnel : snapshot) {
            try {
                close(tunnel, 1012, reason, true);
            } catch (RuntimeException failure) {
                Log.warn("Network Editor Cleanup Failed: " + failure.getMessage());
                wire.failed(tunnel.session, "Network Editor Cleanup Failed");
            }
        }
    }

    private void close(Tunnel tunnel, int code, String reason, boolean notify) {
        ConnectionInfo connection;
        synchronized (this) {
            if (!tunnels.remove(tunnel.id, tunnel)) return;
            if (tunnel.inFlight != null) queuedBytes -= tunnel.inFlight.length;
            for (byte[] bytes : tunnel.queue) queuedBytes -= bytes.length;
            tunnel.queue.clear();
            tunnel.inFlight = null;
            assemblingBytes -= tunnel.incomingTotal;
            tunnel.incomingTotal = 0;
            tunnel.assembler.clear();
            connection = tunnel.connection;
            tunnel.connection = null;
        }
        try {
            if (notify) {
                String bounded = reason == null ? "Editor Closed" : reason.length() > 256 ? reason.substring(0, 256) : reason;
                int validCode = code >= 1000 && code <= 4999 ? code : 1011;
                wire.close(tunnel.session, NetworkEditorCodec.encodeClose(new NetworkEditorClose(tunnel.id, validCode, bounded)));
            }
        } finally {
            if (connection != null) server.onNetworkClose(connection);
        }
    }

    interface Wire {
        boolean active(Object session);
        boolean send(Object session, NetworkFrameType type, byte[] payload);
        void close(Object session, byte[] payload);
        boolean execute(Object session, Runnable work);
        void failed(Object session, String reason);
        int chunkBytes();
    }

    private record Incoming(Object session, NetworkFrame frame, int bytes) {
    }

    private final class Tunnel implements FrameSender {
        private final UUID id;
        private final Object session;
        private final long generation;
        private final NetworkEditorAssembler assembler = new NetworkEditorAssembler();
        private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
        private volatile ConnectionInfo connection;
        private byte[] inFlight;
        private int incomingTotal;
        private long sequence;
        private long startedAt;

        private Tunnel(UUID id, Object session, long generation) {
            this.id = id;
            this.session = session;
            this.generation = generation;
        }

        @Override
        public void send(byte[] frame) {
            SendResult result = trySend(frame);
            if (result != SendResult.ACCEPTED) {
                close(1013, "Editor Response Queue Full");
                throw new IllegalStateException("Editor Response Was Not Admitted");
            }
        }

        @Override
        public SendResult trySend(byte[] frame) {
            synchronized (PaperNetworkEditors.this) {
                if (!current(this)) return SendResult.CLOSED;
                if (frame == null || frame.length == 0 || frame.length > MAXIMUM_QUEUE_BYTES) return SendResult.CLOSED;
                if (queue.size() + (inFlight == null ? 0 : 1) >= 16 || queuedBytes > MAXIMUM_QUEUE_BYTES - frame.length) return SendResult.BACKPRESSURED;
                queue.addLast(frame.clone());
                queuedBytes += frame.length;
            }
            schedulePump();
            return SendResult.ACCEPTED;
        }

        @Override
        public void close(int code, String reason) {
            PaperNetworkEditors.this.close(this, code, reason, true);
        }

        @Override
        public int getMaxEncodedFrameBytes() {
            return MAXIMUM_QUEUE_BYTES;
        }
    }
}
