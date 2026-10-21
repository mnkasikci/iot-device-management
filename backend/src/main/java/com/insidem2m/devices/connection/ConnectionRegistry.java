package com.insidem2m.devices.connection;

import io.quarkus.logging.Log;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which device is connected right now, and over which socket. In memory, so it only knows
 * the sockets of this instance (multi-instance routing: ADR-007).
 */
@ApplicationScoped
public class ConnectionRegistry {

    private final Map<String, WebSocketConnection> connections = new ConcurrentHashMap<>();

    /** A device that reconnects while its old socket is still open: the newest socket wins. */
    public void register(String deviceId, WebSocketConnection connection) {
        WebSocketConnection previous = connections.put(deviceId, connection);
        if (previous != null && previous.isOpen()) {
            Log.infof("Device %s reconnected, closing its previous connection", deviceId);
            previous.close().subscribe().with(ok -> { }, err -> Log.debugf("close failed: %s", err));
        }
    }

    /**
     * Removes the mapping only if it still points to this connection.
     *
     * @return false if a newer connection of the same device already replaced it —
     *         then the device is still online and must not be marked offline.
     */
    public boolean unregister(String deviceId, WebSocketConnection connection) {
        return connections.remove(deviceId, connection);
    }

    public boolean isConnected(String deviceId) {
        return connections.containsKey(deviceId);
    }

    public WebSocketConnection get(String deviceId) {
        return connections.get(deviceId);
    }

    public Map<String, WebSocketConnection> all() {
        return connections;
    }

    public int size() {
        return connections.size();
    }
}
