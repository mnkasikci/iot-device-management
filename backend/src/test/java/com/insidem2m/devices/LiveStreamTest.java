package com.insidem2m.devices;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code GET /api/stream}: a device that says hello shows up in one of the next SSE batches. */
@QuarkusTest
class LiveStreamTest {

    @TestHTTPResource("/")
    URL baseUrl;

    @Test
    void changedDeviceAppearsInStream() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<Stream<String>> stream = http.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "api/stream")).build(),
                HttpResponse.BodyHandlers.ofLines());
        assertEquals(200, stream.statusCode());
        assertTrue(stream.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));

        // Read lines in the background until one mentions our device.
        CompletableFuture<String> line = CompletableFuture.supplyAsync(() -> stream.body()
                .filter(l -> l.startsWith("data:") && l.contains("\"stream-test-1\""))
                .findFirst().orElseThrow());

        URI ws = URI.create(baseUrl.toString().replaceFirst("^http", "ws") + "ws/devices/stream-test-1");
        WebSocket device = http.newWebSocketBuilder().buildAsync(ws, new WebSocket.Listener() { }).join();
        device.sendText("{\"type\":\"hello\",\"deviceType\":\"relay\",\"firmware\":\"1.0.0\"}", true).join();

        String batch = line.get(5, TimeUnit.SECONDS);
        assertTrue(batch.contains("\"stats\""), batch);
        assertTrue(batch.contains("\"online\":true"), batch);

        device.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
        stream.body().close();
    }
}
