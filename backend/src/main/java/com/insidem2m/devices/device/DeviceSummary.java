package com.insidem2m.devices.device;

import com.insidem2m.devices.device.DeviceState.TelemetryPoint;

import java.time.Instant;

/**
 * One device as the portal sees it — used by the REST list and the live stream alike.
 * Registry fields come from Postgres, live values (latest telemetry, last-seen) from memory.
 */
public record DeviceSummary(String id, String deviceType, String firmware, boolean online,
                            Instant firstSeen, Instant lastSeen, TelemetryPoint latest) {

    /** @param state null if this instance has never heard from the device */
    public static DeviceSummary of(Device device, DeviceState state) {
        // The in-memory last-seen is fresher (every frame/pong); the DB value survives restarts.
        Instant lastSeen = state != null ? state.lastSeen() : device.lastSeen;
        TelemetryPoint latest = state != null ? state.latest() : null;
        return new DeviceSummary(device.id, device.deviceType, device.firmware, device.online,
                device.firstSeen, lastSeen, latest);
    }
}
