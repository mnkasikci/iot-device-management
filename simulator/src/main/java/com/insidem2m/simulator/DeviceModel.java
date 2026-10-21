package com.insidem2m.simulator;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * What a device type measures and which type-specific commands it understands.
 * Commands every device understands (reboot, setInterval) are in {@link VirtualDevice}.
 *
 * <p>New device type = new class implementing this + one line in {@link #create}.
 */
public interface DeviceModel {

    /** Value of {@code deviceType} in the hello message. */
    String type();

    /** Next telemetry values; called once per interval. */
    Map<String, Object> nextTelemetry();

    /** Handles a type-specific command, or returns {@code null} if this type doesn't know it. */
    Result handleCommand(String name, JsonNode params);

    record Result(boolean ok, String message) {
        static Result ok(String message) {
            return new Result(true, message);
        }

        static Result error(String message) {
            return new Result(false, message);
        }
    }

    static DeviceModel create(String type) {
        return switch (type) {
            case "thermostat" -> new Thermostat();
            case "relay" -> new Relay();
            default -> throw new IllegalArgumentException("Unknown device type: " + type);
        };
    }
}
