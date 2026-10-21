package com.insidem2m.devices.api;

import com.insidem2m.devices.connection.DeviceMetrics;
import com.insidem2m.devices.device.Device;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/** Header numbers for the portal. Full metrics for monitoring: {@code /q/metrics}. */
@Path("/api/stats")
public class StatsResource {

    @Inject
    DeviceMetrics metrics;

    public record Stats(int connected, long totalDevices, long messagesReceived, long messagesPerSecond) {
    }

    @GET
    public Stats get() {
        return new Stats(metrics.connected(), Device.count(), metrics.messagesReceived(),
                metrics.messagesPerSecond());
    }
}
