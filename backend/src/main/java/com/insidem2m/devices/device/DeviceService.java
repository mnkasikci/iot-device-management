package com.insidem2m.devices.device;

import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;

import java.time.Instant;

/**
 * All database writes for the device registry. Blocking (JDBC) — callers must not be on the
 * Vert.x event loop (see ADR-011).
 */
@ApplicationScoped
public class DeviceService {

    /** Upsert on {@code hello}: first contact creates the row, later ones refresh type/firmware. */
    @Transactional
    public void onHello(String deviceId, String deviceType, String firmware) {
        Instant now = Instant.now();
        Device device = Device.findById(deviceId);
        if (device == null) {
            device = new Device();
            device.id = deviceId;
            device.firstSeen = now;
        }
        device.deviceType = deviceType;
        device.firmware = firmware;
        device.lastSeen = now;
        device.online = true;
        device.persist();
    }

    @Transactional
    public void markOffline(String deviceId, Instant lastSeen) {
        Device device = Device.findById(deviceId);
        if (device != null) {
            device.online = false;
            device.lastSeen = lastSeen;
        }
    }

    /** After a restart no device is connected yet; clear flags left over from the previous run. */
    @Transactional
    void resetOnlineFlags(@Observes StartupEvent event) {
        long updated = Device.update("online = false where online = true");
        Log.infof("Startup: marked %d devices offline", updated);
    }
}
