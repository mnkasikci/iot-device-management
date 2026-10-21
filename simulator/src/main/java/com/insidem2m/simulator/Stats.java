package com.insidem2m.simulator;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Counters shared by all virtual devices, printed periodically by {@link Simulator}. */
class Stats {

    final AtomicInteger connected = new AtomicInteger();
    final AtomicLong telemetrySent = new AtomicLong();
    final AtomicLong commandsReceived = new AtomicLong();
    final AtomicLong acksSent = new AtomicLong();
    final AtomicLong reconnects = new AtomicLong();
    final AtomicLong connectFailures = new AtomicLong();

    private long telemetryAtLastPrint;

    /** One status line; the msgs/s figure is the average since the previous call. */
    String line(int total, long secondsSinceLastPrint) {
        long sent = telemetrySent.get();
        long perSecond = (sent - telemetryAtLastPrint) / Math.max(1, secondsSinceLastPrint);
        telemetryAtLastPrint = sent;
        return String.format("connected %d/%d | telemetry %d (%d/s) | commands %d, acks %d | reconnects %d, connect failures %d",
                connected.get(), total, sent, perSecond, commandsReceived.get(), acksSent.get(),
                reconnects.get(), connectFailures.get());
    }
}
