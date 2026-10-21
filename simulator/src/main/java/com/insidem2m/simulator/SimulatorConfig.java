package com.insidem2m.simulator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Command-line options. Every option has a default, so {@code java -jar simulator.jar} alone
 * starts 10 devices against a local backend.
 *
 * @param url      backend endpoint without the device id, e.g. ws://localhost:8080/ws/devices
 * @param count    number of virtual devices
 * @param interval seconds between telemetry messages per device (devices can change it via setInterval)
 * @param ramp     devices started per second — avoids a connect storm against the backend
 * @param prefix   device ids are prefix-00001, prefix-00002, ...
 * @param types    device types assigned round-robin: "thermostat,relay" = half/half
 */
public record SimulatorConfig(String url, int count, int interval, int ramp, String prefix, List<String> types) {

    static final String USAGE = """
            Usage: java -jar simulator.jar [options]
              --url <ws-url>       default ws://localhost:8080/ws/devices
              --count <n>          number of devices, default 10
              --interval <s>       telemetry interval in seconds, default 5
              --ramp <n>           devices started per second, default 50
              --prefix <text>      device id prefix, default sim
              --types <a,b,...>    device types, round-robin, default thermostat,relay
            """;

    static SimulatorConfig parse(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) {
                throw new IllegalArgumentException("Expected --option value, got: " + args[i]);
            }
            options.put(args[i].substring(2), args[++i]);
        }

        List<String> types = List.of(options.getOrDefault("types", "thermostat,relay").split(","));
        for (String type : types) {
            DeviceModel.create(type); // fails fast on an unknown type
        }
        SimulatorConfig config = new SimulatorConfig(
                options.getOrDefault("url", "ws://localhost:8080/ws/devices"),
                Integer.parseInt(options.getOrDefault("count", "10")),
                Integer.parseInt(options.getOrDefault("interval", "5")),
                Integer.parseInt(options.getOrDefault("ramp", "50")),
                options.getOrDefault("prefix", "sim"),
                types);
        if (config.count < 1 || config.interval < 1 || config.ramp < 1) {
            throw new IllegalArgumentException("count, interval and ramp must be >= 1");
        }
        return config;
    }
}
