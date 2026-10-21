package com.insidem2m.simulator;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A switchable power outlet. When on, it reports a noisy power draw around its load; off = 0 W.
 * Telemetry: {@code {"on":true,"power":61.3}}.
 * Command: {@code setRelay {"on": true}}.
 */
class Relay implements DeviceModel {

    private final double load = 20 + ThreadLocalRandom.current().nextDouble(180);
    private boolean on = ThreadLocalRandom.current().nextBoolean();

    @Override
    public String type() {
        return "relay";
    }

    @Override
    public synchronized Map<String, Object> nextTelemetry() {
        double power = on ? load * ThreadLocalRandom.current().nextDouble(0.9, 1.1) : 0;
        return Map.of("on", on, "power", Math.round(power * 10) / 10.0);
    }

    @Override
    public synchronized Result handleCommand(String name, JsonNode params) {
        if (!name.equals("setRelay")) {
            return null;
        }
        JsonNode value = params.get("on");
        if (value == null || !value.isBoolean()) {
            return Result.error("on must be true or false");
        }
        on = value.asBoolean();
        return Result.ok("relay switched " + (on ? "on" : "off"));
    }
}
