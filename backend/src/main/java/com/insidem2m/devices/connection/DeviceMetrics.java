package com.insidem2m.devices.connection;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.atomic.AtomicLong;

/**
 * All device metrics in one place. Exported for Prometheus at {@code /q/metrics}
 * (dots become underscores, counters get {@code _total}); {@code /api/stats} reads the same numbers.
 */
@ApplicationScoped
public class DeviceMetrics {

    private final MeterRegistry meters;
    // Kept as a field: Micrometer gauges only hold a weak reference to the measured object.
    private final ConnectionRegistry registry;
    private final Counter commandsSent;
    private final Counter commandsAcked;

    private final AtomicLong messagesReceived = new AtomicLong();
    private long totalAtLastTick;
    private volatile long messagesPerSecond;

    DeviceMetrics(MeterRegistry meters, ConnectionRegistry registry) {
        this.meters = meters;
        this.registry = registry;
        Gauge.builder("devices.connected", registry, ConnectionRegistry::size)
                .description("Devices with an open WebSocket on this instance")
                .register(meters);
        // Placeholders until the command flow exists (Step 3), so dashboards can be built now.
        commandsSent = Counter.builder("devices.commands.sent").register(meters);
        commandsAcked = Counter.builder("devices.commands.acked").register(meters);
    }

    /** @param type the frame's {@code type}, or "invalid" for frames that could not be decoded */
    public void messageReceived(String type) {
        meters.counter("devices.messages.received", "type", type).increment();
        messagesReceived.incrementAndGet();
    }

    public void commandSent() {
        commandsSent.increment();
    }

    public void commandAcked() {
        commandsAcked.increment();
    }

    /** Messages received in the last full second. Prometheus computes its own rate from the counter. */
    @Scheduled(every = "1s")
    void computeRate() {
        long total = messagesReceived.get();
        messagesPerSecond = total - totalAtLastTick;
        totalAtLastTick = total;
    }

    public long messagesReceived() {
        return messagesReceived.get();
    }

    public long messagesPerSecond() {
        return messagesPerSecond;
    }

    public int connected() {
        return registry.size();
    }
}
