package com.insidem2m.simulator;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Starts N virtual devices in one JVM and prints a status line every few seconds.
 * Devices share one scheduler (a few threads, not one thread per device) and one HttpClient.
 * Runs until Ctrl+C.
 */
public class Simulator {

    private static final int STATS_EVERY_SECONDS = 5;

    public static void main(String[] args) {
        if (List.of(args).contains("--help")) {
            System.out.print(SimulatorConfig.USAGE);
            return;
        }
        SimulatorConfig config;
        try {
            config = SimulatorConfig.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.print(SimulatorConfig.USAGE);
            System.exit(2);
            return;
        }

        ScheduledExecutorService scheduler =
                Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors());
        HttpClient http = HttpClient.newHttpClient();
        Stats stats = new Stats();

        System.out.printf("Starting %d devices (%s) against %s, %d/s, telemetry every %ds%n",
                config.count(), String.join(",", config.types()), config.url(), config.ramp(), config.interval());

        for (int i = 0; i < config.count(); i++) {
            String id = String.format("%s-%05d", config.prefix(), i + 1);
            DeviceModel model = DeviceModel.create(config.types().get(i % config.types().size()));
            VirtualDevice device = new VirtualDevice(id, model, URI.create(config.url() + "/" + id),
                    config.interval(), http, scheduler, stats);
            // Ramp: device i starts after i/ramp seconds, so the backend isn't hit by a connect storm.
            scheduler.schedule(device::connect, i * 1000L / config.ramp(), TimeUnit.MILLISECONDS);
        }

        scheduler.scheduleAtFixedRate(() -> System.out.println(stats.line(config.count(), STATS_EVERY_SECONDS)),
                STATS_EVERY_SECONDS, STATS_EVERY_SECONDS, TimeUnit.SECONDS);
        // main returns; the scheduler's (non-daemon) threads keep the JVM running.
    }
}
