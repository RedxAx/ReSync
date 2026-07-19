package restudio.resync.core;

import org.java_websocket.WebSocket;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConnectionManagerReconnectTest {
    @Test
    void reconnectsEveryActiveWebSocketWithServiceRestartCode() {
        AtomicInteger closeCode = new AtomicInteger();
        AtomicReference<String> closeReason = new AtomicReference<>();
        WebSocket webSocket = (WebSocket) Proxy.newProxyInstance(WebSocket.class.getClassLoader(), new Class<?>[]{WebSocket.class},
            (proxy, method, args) -> {
                if ("close".equals(method.getName()) && args != null && args.length == 2) {
                    closeCode.set((int) args[0]);
                    closeReason.set((String) args[1]);
                }
                Class<?> returnType = method.getReturnType();
                if (returnType == boolean.class) {
                    return false;
                }
                if (returnType == int.class) {
                    return 0;
                }
                if (returnType == long.class) {
                    return 0L;
                }
                return null;
            });
        ConnectionManager manager = new ConnectionManager(30, 60);
        try {
            ConnectionInfo connection = manager.createConnection(webSocket);

            assertEquals(1, manager.reconnectWebSocketClients("Acceptance reconnect"));
            assertEquals(ConnectionState.CLOSING, connection.getState());
            assertEquals(1012, closeCode.get());
            assertEquals("Acceptance reconnect", closeReason.get());
        } finally {
            manager.shutdown();
        }
    }
}
