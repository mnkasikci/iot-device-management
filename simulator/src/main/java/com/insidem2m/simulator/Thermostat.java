package com.insidem2m.simulator;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Temperature drifts towards the target with some noise; humidity is a bounded random walk.
 * Telemetry: {@code {"temperature":21.4,"humidity":43.0,"targetTemp":21.0}}.
 * Command: {@code setTargetTemp {"target": 22.5}} (5–30 °C).
 */
class Thermostat implements DeviceModel {

    private double temperature = 18 + ThreadLocalRandom.current().nextDouble(6);
    private double humidity = 35 + ThreadLocalRandom.current().nextDouble(20);
    private double targetTemp = 21;

    @Override
    public String type() {
        return "thermostat";
    }

    @Override
    public synchronized Map<String, Object> nextTelemetry() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        temperature += (targetTemp - temperature) * 0.1 + random.nextDouble(-0.2, 0.2);
        humidity = Math.clamp(humidity + random.nextDouble(-1, 1), 20, 80);
        return Map.of("temperature", round(temperature), "humidity", round(humidity), "targetTemp", targetTemp);
    }

    @Override
    public synchronized Result handleCommand(String name, JsonNode params) {
        if (!name.equals("setTargetTemp")) {
            return null;
        }
        JsonNode target = params.get("target");
        if (target == null || !target.isNumber() || target.asDouble() < 5 || target.asDouble() > 30) {
            return Result.error("target must be a number between 5 and 30");
        }
        targetTemp = target.asDouble();
        return Result.ok("target temperature set to " + targetTemp);
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
