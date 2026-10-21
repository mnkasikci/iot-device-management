package com.insidem2m.devices.api;

import com.insidem2m.devices.device.Device;
import com.insidem2m.devices.device.DeviceState;
import com.insidem2m.devices.device.DeviceState.TelemetryPoint;
import com.insidem2m.devices.device.DeviceStateStore;
import io.quarkus.panache.common.Sort;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read API for the portal. Registry fields come from Postgres, live values (latest telemetry,
 * history, last-seen) from the in-memory {@link DeviceStateStore}.
 */
@Path("/api/devices")
public class DeviceResource {

    static final int MAX_PAGE_SIZE = 500;

    @Inject
    DeviceStateStore states;

    public record DeviceSummary(String id, String deviceType, String firmware, boolean online,
                                Instant firstSeen, Instant lastSeen, TelemetryPoint latest) {
    }

    public record DeviceDetail(DeviceSummary device, List<TelemetryPoint> history) {
    }

    public record Page<T>(List<T> items, int page, int size, long total) {
    }

    /** {@code GET /api/devices?page=0&size=50&online=true&type=relay} — all filters optional. */
    @GET
    public Page<DeviceSummary> list(@QueryParam("page") @DefaultValue("0") int page,
                                    @QueryParam("size") @DefaultValue("50") int size,
                                    @QueryParam("online") Boolean online,
                                    @QueryParam("type") String type) {
        page = Math.max(page, 0);
        size = Math.clamp(size, 1, MAX_PAGE_SIZE);

        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        if (online != null) {
            conditions.add("online = :online");
            params.put("online", online);
        }
        if (type != null && !type.isBlank()) {
            conditions.add("deviceType = :type");
            params.put("type", type);
        }

        var query = Device.<Device>find(String.join(" and ", conditions), Sort.by("id"), params)
                .page(page, size);
        List<DeviceSummary> items = query.list().stream().map(this::toSummary).toList();
        return new Page<>(items, page, size, query.count());
    }

    @GET
    @Path("/{id}")
    public DeviceDetail get(@PathParam("id") String id) {
        Device device = Device.findById(id);
        if (device == null) {
            throw new NotFoundException("Unknown device " + id);
        }
        DeviceState state = states.get(id);
        return new DeviceDetail(toSummary(device), state != null ? state.history() : List.of());
    }

    private DeviceSummary toSummary(Device device) {
        DeviceState state = states.get(device.id);
        // The in-memory last-seen is fresher (every frame/pong); the DB value survives restarts.
        Instant lastSeen = state != null ? state.lastSeen() : device.lastSeen;
        TelemetryPoint latest = state != null ? state.latest() : null;
        return new DeviceSummary(device.id, device.deviceType, device.firmware, device.online,
                device.firstSeen, lastSeen, latest);
    }
}
