package com.insidem2m.devices.stream;

import com.insidem2m.devices.api.StatsResource.Stats;
import com.insidem2m.devices.command.Command;
import com.insidem2m.devices.connection.DeviceMetrics;
import com.insidem2m.devices.device.Device;
import com.insidem2m.devices.device.DeviceStateStore;
import com.insidem2m.devices.device.DeviceSummary;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live updates for the portal (ADR-006). Anything that changes a device or a command only
 * <b>marks</b> it here (cheap, any thread). Once per second {@link #tick()} turns the marked ids
 * into one {@link Batch} and pushes it to every open SSE stream. So a device sending 10 frames a
 * second still costs the browser one entry per second, and 0 if it sent nothing.
 */
@ApplicationScoped
public class LiveUpdates {

    /** Postgres allows ~32k bind parameters per statement; stay well below. */
    static final int QUERY_CHUNK = 1000;

    /** What the browser receives every second. Only devices/commands that changed since the last batch. */
    public record Batch(long ts, Stats stats, List<DeviceSummary> devices, List<Command> commands) {
    }

    private final Set<String> changedDevices = ConcurrentHashMap.newKeySet();
    private final Set<UUID> changedCommands = ConcurrentHashMap.newKeySet();

    /** Hot stream: every subscriber gets the batches emitted while it is connected. */
    private final BroadcastProcessor<Batch> batches = BroadcastProcessor.create();

    @Inject
    DeviceStateStore states;

    @Inject
    DeviceMetrics metrics;

    public void deviceChanged(String deviceId) {
        changedDevices.add(deviceId);
    }

    public void commandChanged(UUID commandId) {
        changedCommands.add(commandId);
    }

    /**
     * For {@code GET /api/stream}. A browser too slow to take one batch per second skips batches
     * instead of making the server buffer for it; devices that keep sending show up again in the next one.
     */
    public Multi<Batch> stream() {
        return batches.onOverflow().drop();
    }

    @Scheduled(every = "1s")
    void tick() {
        List<String> deviceIds = drain(changedDevices);
        List<UUID> commandIds = drain(changedCommands);

        List<DeviceSummary> devices = new ArrayList<>();
        for (List<String> chunk : chunks(deviceIds)) {
            Device.<Device>list("id in ?1", chunk)
                    .forEach(device -> devices.add(DeviceSummary.of(device, states.get(device.id))));
        }
        List<Command> commands = new ArrayList<>();
        for (List<UUID> chunk : chunks(commandIds)) {
            commands.addAll(Command.list("id in ?1", chunk));
        }
        // Always sent, even without changes: carries the stats and doubles as a heartbeat.
        batches.onNext(new Batch(System.currentTimeMillis(), Stats.current(metrics), devices, commands));
    }

    /**
     * Copies the ids, then removes exactly those. A mark arriving meanwhile either is in the copy
     * or stays for the next tick; the values are read only after this, so nothing is lost.
     */
    private static <T> List<T> drain(Set<T> changed) {
        List<T> ids = new ArrayList<>(changed);
        ids.forEach(changed::remove);
        return ids;
    }

    private static <T> List<List<T>> chunks(List<T> ids) {
        List<List<T>> chunks = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += QUERY_CHUNK) {
            chunks.add(ids.subList(i, Math.min(i + QUERY_CHUNK, ids.size())));
        }
        return chunks;
    }
}
