package restudio.resync.core;

import org.java_websocket.WebSocket;
import restudio.resync.protocol.FrameSender;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

public class ConnectionInfo {
    private final WebSocket webSocket;
    private final FrameSender frameSender;
    private final int connectionId;
    private final long connectionTime;
    private final AtomicLong lastHeartbeat;
    private final AtomicLong bytesSent;
    private final AtomicLong bytesReceived;
    private final AtomicInteger lastInboundDataSequence;
    private volatile ConnectionState state;
    private volatile boolean protocolResourceAccess;
    private String clientId;
    private String clientVersion;
    private Set<String> clientCapabilities = Set.of();
    private volatile Set<String> negotiatedFlowCapabilities = Set.of();

    public ConnectionInfo(WebSocket webSocket, int connectionId) {
        this(webSocket, new FrameSender() {
            @Override
            public void send(byte[] frame) {
                webSocket.send(frame);
            }

            @Override
            public void close(int code, String reason) {
                webSocket.close(code, reason);
            }
        }, connectionId);
    }

    public ConnectionInfo(WebSocket webSocket, FrameSender frameSender, int connectionId) {
        this.webSocket = webSocket;
        this.frameSender = frameSender;
        this.connectionId = connectionId;
        this.connectionTime = System.currentTimeMillis();
        this.lastHeartbeat = new AtomicLong(System.currentTimeMillis());
        this.bytesSent = new AtomicLong(0);
        this.bytesReceived = new AtomicLong(0);
        this.lastInboundDataSequence = new AtomicInteger(-1);
        this.state = ConnectionState.CONNECTING;
        this.protocolResourceAccess = false;
    }

    public WebSocket getWebSocket() {
        return webSocket;
    }

    public FrameSender getFrameSender() {
        return frameSender;
    }

    public int getMaxEncodedFrameBytes() {
        return frameSender == null ? FrameSender.UNBOUNDED_MAX_ENCODED_FRAME_BYTES : frameSender.getMaxEncodedFrameBytes();
    }

    public boolean isOpen() {
        return webSocket == null || webSocket.isOpen();
    }

    public int getConnectionId() {
        return connectionId;
    }

    public long getConnectionTime() {
        return connectionTime;
    }

    public long getLastHeartbeat() {
        return lastHeartbeat.get();
    }

    public void updateHeartbeat() {
        lastHeartbeat.set(System.currentTimeMillis());
    }

    public ConnectionState getState() {
        return state;
    }

    public synchronized void setState(ConnectionState state) {
        this.state = state;
    }

    public boolean hasProtocolResourceAccess() {
        return protocolResourceAccess;
    }

    public void setProtocolResourceAccess(boolean protocolResourceAccess) {
        this.protocolResourceAccess = protocolResourceAccess;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientVersion() {
        return clientVersion;
    }

    public void setClientVersion(String clientVersion) {
        this.clientVersion = clientVersion;
    }

    public Set<String> getClientCapabilities() {
        return clientCapabilities;
    }

    public void setClientCapabilities(Set<String> clientCapabilities) {
        this.clientCapabilities = clientCapabilities != null ? Set.copyOf(clientCapabilities) : Set.of();
    }

    public Set<String> getNegotiatedFlowCapabilities() {
        return negotiatedFlowCapabilities;
    }

    public void setNegotiatedFlowCapabilities(Set<String> negotiatedFlowCapabilities) {
        this.negotiatedFlowCapabilities = negotiatedFlowCapabilities != null ? Set.copyOf(negotiatedFlowCapabilities) : Set.of();
    }

    public boolean hasNegotiatedFlowCapability(String capability) {
        return capability != null && negotiatedFlowCapabilities.contains(capability);
    }

    public synchronized void clearProtocolSession() {
        protocolResourceAccess = false;
        negotiatedFlowCapabilities = Set.of();
    }

    public long getBytesSent() {
        return bytesSent.get();
    }

    public void addBytesSent(long bytes) {
        bytesSent.addAndGet(bytes);
    }

    public long getBytesReceived() {
        return bytesReceived.get();
    }

    public void addBytesReceived(long bytes) {
        bytesReceived.addAndGet(bytes);
    }

    public long getConnectedDuration() {
        return System.currentTimeMillis() - connectionTime;
    }

    public boolean acceptInboundDataSequence(int sequence) {
        while (true) {
            int current = lastInboundDataSequence.get();
            if (sequence <= current) {
                return false;
            }
            if (lastInboundDataSequence.compareAndSet(current, sequence)) {
                return true;
            }
        }
    }
}
