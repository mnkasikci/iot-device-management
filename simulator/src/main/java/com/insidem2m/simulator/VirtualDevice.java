package com.insidem2m.simulator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insidem2m.simulator.DeviceModel.Result;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * One simulated device: its WebSocket connection, telemetry timer, command handling and
 * reconnects. No thread of its own — timers run on the shared scheduler, incoming frames on the
 * HttpClient's threads. Mutable state is guarded by {@code synchronized} on this object.
 */
class VirtualDevice implements WebSocket.Listener {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FIRMWARE = "1.0.0";
    private static final long REBOOT_DELAY_MS = 3_000;
    private static final long MAX_BACKOFF_MS = 30_000;

    private final String id;
    private final DeviceModel model;
    private final URI uri;
    private final HttpClient http;
    private final ScheduledExecutorService scheduler;
    private final Stats stats;

    private int intervalSeconds;
    private WebSocket socket; // null while disconnected
    private CompletableFuture<?> lastSend = CompletableFuture.completedFuture(null);
    private ScheduledFuture<?> telemetryTask;
    private int failedAttempts;
    private boolean rebooting;
    private final StringBuilder incoming = new StringBuilder();

    VirtualDevice(String id, DeviceModel model, URI uri, int intervalSeconds,
                  HttpClient http, ScheduledExecutorService scheduler, Stats stats) {
        this.id = id;
        this.model = model;
        this.uri = uri;
        this.intervalSeconds = intervalSeconds;
        this.http = http;
        this.scheduler = scheduler;
        this.stats = stats;
    }

    void connect() {
        http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(uri, this)
                .exceptionally(error -> {
                    stats.connectFailures.incrementAndGet();
                    scheduleReconnect();
                    return null;
                });
    }

    // ---- WebSocket.Listener callbacks (the JDK calls them one at a time per socket) ----

    @Override
    public synchronized void onOpen(WebSocket webSocket) {
        socket = webSocket;
        lastSend = CompletableFuture.completedFuture(null);
        failedAttempts = 0;
        stats.connected.incrementAndGet();
        send(Map.of("type", "hello", "deviceType", model.type(), "firmware", FIRMWARE));
        // Random first delay spreads devices over the interval instead of all sending at once.
        startTelemetry(ThreadLocalRandom.current().nextLong(intervalSeconds * 1000L));
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        incoming.append(data); // a frame may arrive in several parts
        if (last) {
            String frame = incoming.toString();
            incoming.setLength(0);
            onFrame(frame);
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        disconnected(webSocket);
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        disconnected(webSocket);
    }

    // ---- Commands: the one place where every server → device command arrives ----

    private void onFrame(String text) {
        JsonNode message;
        try {
            message = JSON.readTree(text);
        } catch (JsonProcessingException e) {
            System.err.printf("%s: unreadable frame from server: %s%n", id, text);
            return;
        }
        if (!"command".equals(message.path("type").asText())) {
            return; // "command" is the only server → device message in the protocol
        }
        stats.commandsReceived.incrementAndGet();
        String commandId = message.path("commandId").asText();
        String name = message.path("name").asText();
        JsonNode params = message.path("params");

        Result result = handleCommand(name, params);
        System.out.printf("%s <- %s %s -> %s %s%n", id, name, params, result.ok() ? "OK" : "ERROR", result.message());

        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("type", "ack");
        ack.put("commandId", commandId);
        ack.put("status", result.ok() ? "OK" : "ERROR");
        ack.put("message", result.message());
        if (send(ack)) {
            stats.acksSent.incrementAndGet();
        }
        if (name.equals("reboot")) {
            closeForReboot(); // after the ack, so the backend still sees it
        }
    }

    /** Commands every device understands first, then the device type's own ones. */
    private Result handleCommand(String name, JsonNode params) {
        return switch (name) {
            case "reboot" -> Result.ok("rebooting in " + REBOOT_DELAY_MS / 1000 + "s");
            case "setInterval" -> setInterval(params);
            default -> {
                Result result = model.handleCommand(name, params);
                yield result != null ? result : Result.error("unknown command " + name);
            }
        };
    }

    private synchronized Result setInterval(JsonNode params) {
        JsonNode seconds = params.get("seconds");
        if (seconds == null || !seconds.isIntegralNumber() || seconds.asInt() < 1 || seconds.asInt() > 3600) {
            return Result.error("seconds must be a whole number between 1 and 3600");
        }
        intervalSeconds = seconds.asInt();
        startTelemetry(intervalSeconds * 1000L);
        return Result.ok("interval set to " + intervalSeconds + "s");
    }

    // ---- Connection lifecycle ----

    private synchronized void startTelemetry(long initialDelayMs) {
        if (telemetryTask != null) {
            telemetryTask.cancel(false);
        }
        telemetryTask = scheduler.scheduleAtFixedRate(this::sendTelemetry,
                initialDelayMs, intervalSeconds * 1000L, TimeUnit.MILLISECONDS);
    }

    private void sendTelemetry() {
        if (send(Map.of("type", "telemetry", "ts", System.currentTimeMillis(), "data", model.nextTelemetry()))) {
            stats.telemetrySent.incrementAndGet();
        }
    }

    private synchronized void closeForReboot() {
        WebSocket webSocket = socket;
        if (webSocket == null) {
            return;
        }
        rebooting = true;
        telemetryTask.cancel(false);
        lastSend = lastSend.handle((ok, error) -> null)
                .thenCompose(v -> webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "reboot"));
    }

    /** Called once per lost connection (close by either side, or network error). */
    private synchronized void disconnected(WebSocket webSocket) {
        if (socket != webSocket) {
            return; // already handled
        }
        socket = null;
        stats.connected.decrementAndGet();
        telemetryTask.cancel(false);
        if (rebooting) {
            rebooting = false;
            scheduler.schedule(this::connect, REBOOT_DELAY_MS, TimeUnit.MILLISECONDS);
        } else {
            stats.reconnects.incrementAndGet();
            scheduleReconnect();
        }
    }

    /**
     * Exponential backoff (1, 2, 4 … 30 s) with jitter: after a backend restart, thousands of
     * devices must not all reconnect in the same millisecond.
     */
    private synchronized void scheduleReconnect() {
        long cap = Math.min(MAX_BACKOFF_MS, 1000L << Math.min(failedAttempts, 5));
        failedAttempts++;
        long delay = cap / 2 + ThreadLocalRandom.current().nextLong(cap / 2 + 1);
        scheduler.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
    }

    /**
     * The JDK WebSocket allows only one send in flight, but telemetry (timer thread) and acks
     * (listener thread) can overlap — so each send is chained behind the previous one.
     *
     * @return false if the device is currently disconnected (message dropped)
     */
    private synchronized boolean send(Map<String, Object> message) {
        WebSocket webSocket = socket;
        if (webSocket == null) {
            return false;
        }
        String json;
        try {
            json = JSON.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e); // our own maps — cannot happen
        }
        lastSend = lastSend.handle((ok, error) -> null)
                .thenCompose(v -> webSocket.sendText(json, true));
        return true;
    }
}
