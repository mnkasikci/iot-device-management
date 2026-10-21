package com.insidem2m.devices;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Command flow against a test client playing the device. {@code %test.commands.timeout} is 2 s
 * (application.properties), so the timeout test is quick.
 */
@QuarkusTest
class CommandFlowTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TestHTTPResource("/")
    URL baseUrl;

    @Test
    void okAckMarksCommandAcked() throws Exception {
        CompletableFuture<JsonNode> received = new CompletableFuture<>();
        WebSocket device = connect("cmd-test-acked", received, "OK");
        hello(device, "cmd-test-acked");

        String id = given().contentType(ContentType.JSON)
                .body("{\"name\":\"setInterval\",\"params\":{\"seconds\":5}}")
                .post("/api/devices/cmd-test-acked/commands")
                .then().statusCode(202)
                .body("status", equalTo("SENT"))
                .extract().path("id");

        JsonNode frame = received.get(5, TimeUnit.SECONDS);
        assertEquals("command", frame.get("type").asText());
        assertEquals(id, frame.get("commandId").asText());
        assertEquals("setInterval", frame.get("name").asText());
        assertEquals(5, frame.get("params").get("seconds").asInt());

        eventually(Duration.ofSeconds(5), () -> given().get("/api/devices/cmd-test-acked/commands").then()
                .body("[0].id", equalTo(id))
                .body("[0].status", equalTo("ACKED"))
                .body("[0].message", equalTo("done")));

        device.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
    }

    @Test
    void errorAckMarksCommandFailed() {
        WebSocket device = connect("cmd-test-error", new CompletableFuture<>(), "ERROR");
        hello(device, "cmd-test-error");

        String id = given().contentType(ContentType.JSON)
                .body("{\"name\":\"selfDestruct\"}")
                .post("/api/devices/cmd-test-error/commands")
                .then().statusCode(202)
                .extract().path("id");

        eventually(Duration.ofSeconds(5), () -> given().get("/api/devices/cmd-test-error/commands").then()
                .body("[0].id", equalTo(id))
                .body("[0].status", equalTo("FAILED")));

        device.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
    }

    @Test
    void commandToOfflineDeviceFailsImmediately() {
        WebSocket device = connect("cmd-test-offline", new CompletableFuture<>(), null);
        hello(device, "cmd-test-offline");
        device.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
        eventually(Duration.ofSeconds(5), () -> given().get("/api/devices/cmd-test-offline").then()
                .body("device.online", equalTo(false)));

        given().contentType(ContentType.JSON)
                .body("{\"name\":\"reboot\",\"params\":{}}")
                .post("/api/devices/cmd-test-offline/commands")
                .then().statusCode(202)
                .body("status", equalTo("FAILED"))
                .body("message", equalTo("device offline"));
    }

    @Test
    void commandWithoutAckTimesOut() {
        WebSocket device = connect("cmd-test-silent", new CompletableFuture<>(), null); // never acks
        hello(device, "cmd-test-silent");

        String id = given().contentType(ContentType.JSON)
                .body("{\"name\":\"setRelay\",\"params\":{\"on\":true}}")
                .post("/api/devices/cmd-test-silent/commands")
                .then().statusCode(202)
                .body("status", equalTo("SENT"))
                .extract().path("id");

        eventually(Duration.ofSeconds(10), () -> given().get("/api/devices/cmd-test-silent/commands").then()
                .body("[0].id", equalTo(id))
                .body("[0].status", equalTo("TIMEOUT")));

        device.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
    }

    @Test
    void unknownDeviceAndMissingNameAreRejected() {
        given().contentType(ContentType.JSON).body("{\"name\":\"reboot\"}")
                .post("/api/devices/does-not-exist/commands").then().statusCode(404);

        WebSocket device = connect("cmd-test-noname", new CompletableFuture<>(), null);
        hello(device, "cmd-test-noname");
        given().contentType(ContentType.JSON).body("{\"params\":{}}")
                .post("/api/devices/cmd-test-noname/commands").then().statusCode(400);
        device.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
    }

    private void hello(WebSocket device, String deviceId) {
        device.sendText("{\"type\":\"hello\",\"deviceType\":\"relay\",\"firmware\":\"1.0.0\"}", true).join();
        eventually(Duration.ofSeconds(5), () -> given().get("/api/devices/" + deviceId).then()
                .statusCode(200)
                .body("device.online", equalTo(true)));
    }

    /**
     * Connects as a device. The first command frame completes {@code received}; if {@code ackStatus}
     * is not null, the device answers every command with that ack status and message "done".
     */
    private WebSocket connect(String deviceId, CompletableFuture<JsonNode> received, String ackStatus) {
        URI uri = URI.create(baseUrl.toString().replaceFirst("^http", "ws") + "ws/devices/" + deviceId);
        WebSocket.Listener listener = new WebSocket.Listener() {
            @Override
            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                try {
                    JsonNode frame = JSON.readTree(data.toString());
                    received.complete(frame);
                    if (ackStatus != null) {
                        ws.sendText("{\"type\":\"ack\",\"commandId\":\"" + frame.get("commandId").asText()
                                + "\",\"status\":\"" + ackStatus + "\",\"message\":\"done\"}", true);
                    }
                } catch (Exception e) {
                    received.completeExceptionally(e);
                }
                ws.request(1);
                return null;
            }
        };
        return HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(uri, listener).join();
    }

    /** Retries the assertion until it passes or the time is over. */
    private static void eventually(Duration within, Runnable assertion) {
        Instant deadline = Instant.now().plus(within);
        while (true) {
            try {
                assertion.run();
                return;
            } catch (AssertionError e) {
                if (Instant.now().isAfter(deadline)) {
                    throw e;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }
}
