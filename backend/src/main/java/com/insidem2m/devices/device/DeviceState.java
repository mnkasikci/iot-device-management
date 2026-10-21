package com.insidem2m.devices.device;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;

/**
 * Live, in-memory state of one device: telemetry history and last-seen time.
 * Never written to the database (ADR-005). Lost on restart, which is fine for a demo.
 */
public class DeviceState {

    public static final int HISTORY_SIZE = 100;

    public record TelemetryPoint(long ts, Map<String, Object> data) {
    }

    /** Ring buffer: newest at the end, oldest dropped once {@link #HISTORY_SIZE} is reached. */
    private final ArrayDeque<TelemetryPoint> history = new ArrayDeque<>(HISTORY_SIZE);

    /** Any frame or pong from the device counts; the liveness check reads this. */
    private volatile Instant lastSeen = Instant.now();

    public void touch() {
        lastSeen = Instant.now();
    }

    public Instant lastSeen() {
        return lastSeen;
    }

    public synchronized void addTelemetry(TelemetryPoint point) {
        if (history.size() == HISTORY_SIZE) {
            history.removeFirst();
        }
        history.addLast(point);
    }

    /** Latest telemetry point, or null if the device has not sent any yet. */
    public synchronized TelemetryPoint latest() {
        return history.peekLast();
    }

    /** Copy, oldest first — safe to serialize while new points arrive. */
    public synchronized List<TelemetryPoint> history() {
        return List.copyOf(history);
    }
}
