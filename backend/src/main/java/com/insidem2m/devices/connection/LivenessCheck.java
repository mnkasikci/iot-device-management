package com.insidem2m.devices.connection;

import com.insidem2m.devices.device.DeviceState;
import com.insidem2m.devices.device.DeviceStateStore;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;

/**
 * Detects devices that vanished without closing the socket (power loss, NAT timeout).
 * The server auto-pings every connection; a live device answers with a pong, which refreshes
 * its last-seen. A connection silent for longer than the timeout is closed → {@code @OnClose}
 * marks the device offline. Without this, a half-open TCP connection can look "online" for minutes.
 */
@ApplicationScoped
public class LivenessCheck {

    @ConfigProperty(name = "devices.liveness-timeout")
    Duration timeout;

    @Inject
    ConnectionRegistry registry;

    @Inject
    DeviceStateStore states;

    @Scheduled(every = "${devices.liveness-check-interval}")
    void closeSilentConnections() {
        Instant deadline = Instant.now().minus(timeout);
        registry.all().forEach((deviceId, connection) -> {
            DeviceState state = states.get(deviceId);
            if (state != null && state.lastSeen().isBefore(deadline)) {
                Log.infof("Device %s silent since %s, closing its connection", deviceId, state.lastSeen());
                // Fire-and-forget: don't block the scheduler thread on a dead peer. A dead peer never
                // answers the close frame; Vert.x drops the TCP connection after its 10s closing
                // timeout, until then the next runs may log and close again (harmless).
                connection.close().subscribe().with(ok -> { }, err -> Log.debugf("close failed: %s", err));
            }
        });
    }
}
