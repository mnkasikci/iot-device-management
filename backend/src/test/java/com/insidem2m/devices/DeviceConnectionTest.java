package com.insidem2m.devices;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

/**
 * A test client plays a device over a real WebSocket (JDK client), then the REST API is checked.
 * Messages are processed asynchronously, so REST assertions are retried for a few seconds.
 */
@QuarkusTest
class DeviceConnectionTest {

    @TestHTTPResource("/")
    URL baseUrl;

    @Test
    void helloAndTelemetryShowUpInRestApi() {
        WebSocket device = connect("thermo-test-1");
        device.sendText("{\"type\":\"hello\",\"deviceType\":\"thermostat\",\"firmware\":\"1.0.0\"}", true).join();
        device.sendText("{\"type\":\"telemetry\",\"ts\":1000,\"data\":{\"temperature\":21.4,\"humidity\":43}}", true).join();
        device.sendText("{\"type\":\"telemetry\",\"ts\":2000,\"data\":{\"temperature\":21.6,\"humidity\":42}}", true).join();

        eventually(() -> given().get("/api/devices/thermo-test-1").then()
                .statusCode(200)
                .body("device.deviceType", equalTo("thermostat"))
                .body("device.firmware", equalTo("1.0.0"))
                .body("device.online", equalTo(true))
                .body("device.latest.ts", equalTo(2000))
                .body("device.latest.data.temperature", equalTo(21.6f))
                .body("history", hasSize(2)));

        given().get("/api/devices?type=thermostat&online=true").then()
                .statusCode(200)
                .body("items.id", hasItem("thermo-test-1"));
        given().get("/api/stats").then()
                .statusCode(200)
                .body("connected", greaterThanOrEqualTo(1))
                .body("messagesReceived", greaterThanOrEqualTo(3));

        device.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
    }

    @Test
    void closingTheSocketMarksDeviceOffline() {
        WebSocket device = connect("relay-test-1");
        device.sendText("{\"type\":\"hello\",\"deviceType\":\"relay\",\"firmware\":\"2.0.0\"}", true).join();
        eventually(() -> given().get("/api/devices/relay-test-1").then().body("device.online", equalTo(true)));

        device.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();

        eventually(() -> given().get("/api/devices/relay-test-1").then().body("device.online", equalTo(false)));
    }

    @Test
    void unknownDeviceIs404() {
        given().get("/api/devices/does-not-exist").then().statusCode(404);
    }

    private WebSocket connect(String deviceId) {
        URI uri = URI.create(baseUrl.toString().replaceFirst("^http", "ws") + "ws/devices/" + deviceId);
        return HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(uri, new WebSocket.Listener() { })
                .join();
    }

    /** Retries the assertion until it passes or 5 seconds are over. */
    private static void eventually(Runnable assertion) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
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
