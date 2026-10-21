package com.insidem2m.devices.connection;

import com.insidem2m.devices.command.CommandService;
import com.insidem2m.devices.device.DeviceService;
import com.insidem2m.devices.device.DeviceState;
import com.insidem2m.devices.device.DeviceState.TelemetryPoint;
import com.insidem2m.devices.device.DeviceStateStore;
import io.quarkus.logging.Log;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnError;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnPongMessage;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.TextDecodeException;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.vertx.core.buffer.Buffer;
import jakarta.inject.Inject;

import java.util.Map;

/**
 * The device-facing WebSocket endpoint: one persistent connection per device.
 *
 * <p>All callbacks return {@code void}, so WebSockets Next runs them on a <b>worker thread</b>,
 * not on the event loop — required because {@code hello} and close write to Postgres (ADR-011).
 */
@WebSocket(path = "/ws/devices/{deviceId}")
public class DeviceSocket {

    @Inject
    ConnectionRegistry registry;

    @Inject
    DeviceStateStore states;

    @Inject
    DeviceService devices;

    @Inject
    DeviceMetrics metrics;

    @Inject
    CommandService commands;

    @OnOpen
    void onOpen(WebSocketConnection connection) {
        String deviceId = deviceId(connection);
        registry.register(deviceId, connection);
        states.getOrCreate(deviceId).touch();
        Log.debugf("Device %s connected", deviceId);
    }

    /** The one place where device → server messages are handled, by type. */
    @OnTextMessage
    void onMessage(WebSocketConnection connection, DeviceMessage message) {
        String deviceId = deviceId(connection);
        DeviceState state = states.getOrCreate(deviceId);
        state.touch();

        switch (message) {
            case DeviceMessage.Hello hello -> {
                metrics.messageReceived("hello");
                devices.onHello(deviceId, hello.deviceType(), hello.firmware());
            }
            case DeviceMessage.Telemetry telemetry -> {
                metrics.messageReceived("telemetry");
                state.addTelemetry(new TelemetryPoint(
                        telemetry.ts() != null ? telemetry.ts() : System.currentTimeMillis(),
                        telemetry.data() != null ? telemetry.data() : Map.of()));
            }
            case DeviceMessage.Ack ack -> {
                metrics.messageReceived("ack");
                commands.onAck(deviceId, ack.commandId(), ack.status(), ack.message());
            }
        }
    }

    /** Pong = answer to the server's auto-ping; proves the device is alive even if it sends nothing. */
    @OnPongMessage
    void onPong(WebSocketConnection connection, Buffer data) {
        states.getOrCreate(deviceId(connection)).touch();
    }

    @OnClose
    void onClose(WebSocketConnection connection) {
        String deviceId = deviceId(connection);
        if (registry.unregister(deviceId, connection)) {
            devices.markOffline(deviceId, states.getOrCreate(deviceId).lastSeen());
            Log.debugf("Device %s disconnected", deviceId);
        }
    }

    /** Malformed JSON or unknown {@code type}: log and keep the connection (other errors close it). */
    @OnError
    void onDecodeError(WebSocketConnection connection, TextDecodeException e) {
        metrics.messageReceived("invalid");
        Log.warnf("Device %s sent an unreadable frame: %s", deviceId(connection), e.getMessage());
    }

    private static String deviceId(WebSocketConnection connection) {
        return connection.pathParam("deviceId");
    }
}
