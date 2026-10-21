package com.insidem2m.devices.command;

import com.insidem2m.devices.connection.CommandFrame;
import com.insidem2m.devices.connection.ConnectionRegistry;
import com.insidem2m.devices.connection.DeviceMetrics;
import com.insidem2m.devices.stream.LiveUpdates;
import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The command flow (ADR-004, ADR-013): store → send → ack or timeout.
 *
 * <p>Every status change is its own short transaction ({@link QuarkusTransaction}), never held open
 * while talking to the network. Each change is a conditional update ({@code ... where status = X}),
 * so whichever of ack and timeout comes first wins and the other is a no-op.
 * Blocking (JDBC) — callers must not be on the Vert.x event loop (ADR-011).
 */
@ApplicationScoped
public class CommandService {

    @ConfigProperty(name = "commands.timeout")
    Duration timeout;

    @Inject
    ConnectionRegistry registry;

    @Inject
    DeviceMetrics metrics;

    @Inject
    LiveUpdates live;

    /** Stores the command and sends it if the device is online. Returns the command's current state. */
    public Command send(String deviceId, String name, Map<String, Object> params) {
        UUID id = QuarkusTransaction.requiringNew().call(() -> create(deviceId, name, params).id);

        WebSocketConnection connection = registry.get(deviceId);
        if (connection == null) {
            complete(id, CommandStatus.FAILED, "device offline");
            live.commandChanged(id);
            return Command.findById(id);
        }

        // SENT is committed *before* the frame goes out, so even a very fast ack finds the command in SENT.
        QuarkusTransaction.requiringNew().run(() -> Command.update(
                "status = ?1, sentAt = ?2 where id = ?3",
                CommandStatus.SENT, Instant.now(), id));
        try {
            connection.sendTextAndAwait(new CommandFrame(id, name, params));
            metrics.commandSent();
        } catch (RuntimeException e) {
            Log.warnf("Sending command %s to %s failed: %s", id, deviceId, e.getMessage());
            complete(id, CommandStatus.FAILED, "send failed: " + e.getMessage());
        }
        live.commandChanged(id);
        return Command.findById(id);
    }

    /** Called for every {@code ack} frame. Unknown, foreign or already finished commands are ignored. */
    public void onAck(String deviceId, String commandId, String status, String message) {
        UUID id;
        try {
            id = UUID.fromString(commandId);
        } catch (IllegalArgumentException | NullPointerException e) {
            Log.warnf("Device %s acked an invalid commandId %s", deviceId, commandId);
            return;
        }
        CommandStatus result = "OK".equals(status) ? CommandStatus.ACKED : CommandStatus.FAILED;
        int updated = QuarkusTransaction.requiringNew().call(() -> Command.update(
                "status = ?1, message = ?2, completedAt = ?3 where id = ?4 and deviceId = ?5 and status = ?6",
                result, message, Instant.now(), id, deviceId, CommandStatus.SENT));
        if (updated == 0) {
            Log.infof("Ignoring ack from %s for command %s (unknown, other device, or already finished)",
                    deviceId, commandId);
            return;
        }
        live.commandChanged(id);
        if (result == CommandStatus.ACKED) {
            metrics.commandAcked();
        }
    }

    /** SENT for longer than the timeout → TIMEOUT. One SELECT + one UPDATE, however many commands. */
    @Scheduled(every = "${commands.timeout-check-interval}")
    void timeOutStaleCommands() {
        Instant now = Instant.now();
        List<UUID> stale = QuarkusTransaction.requiringNew().call(() -> {
            List<UUID> ids = Command.<Command>list("status = ?1 and sentAt < ?2",
                    CommandStatus.SENT, now.minus(timeout)).stream().map(c -> c.id).toList();
            if (!ids.isEmpty()) {
                // "and status = SENT" again: an ack that arrived since the SELECT wins.
                Command.update("status = ?1, message = ?2, completedAt = ?3 where id in ?4 and status = ?5",
                        CommandStatus.TIMEOUT, "no ack within " + timeout.toSeconds() + "s", now,
                        ids, CommandStatus.SENT);
            }
            return ids;
        });
        if (!stale.isEmpty()) {
            stale.forEach(live::commandChanged);
            Log.infof("%d command(s) timed out", stale.size());
        }
    }

    private static Command create(String deviceId, String name, Map<String, Object> params) {
        Command command = new Command();
        command.id = UUID.randomUUID();
        command.deviceId = deviceId;
        command.name = name;
        command.params = params;
        command.status = CommandStatus.PENDING;
        command.createdAt = Instant.now();
        command.persist();
        return command;
    }

    private static void complete(UUID id, CommandStatus status, String message) {
        QuarkusTransaction.requiringNew().run(() -> Command.update(
                "status = ?1, message = ?2, completedAt = ?3 where id = ?4",
                status, message, Instant.now(), id));
    }
}
