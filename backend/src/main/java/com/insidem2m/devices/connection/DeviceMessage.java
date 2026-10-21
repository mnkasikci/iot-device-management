package com.insidem2m.devices.connection;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Map;

/**
 * Device → server frames (wire protocol in PLAN.md). Jackson picks the record from the
 * JSON {@code type} field; {@link DeviceSocket#onMessage} switches over them.
 * A new message type = one record here + one {@code case} there.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = DeviceMessage.Hello.class, name = "hello"),
        @JsonSubTypes.Type(value = DeviceMessage.Telemetry.class, name = "telemetry"),
        @JsonSubTypes.Type(value = DeviceMessage.Ack.class, name = "ack"),
})
public sealed interface DeviceMessage {

    /** {@code {"type":"hello","deviceType":"thermostat","firmware":"1.0.0"}} */
    record Hello(String deviceType, String firmware) implements DeviceMessage {
    }

    /** {@code {"type":"telemetry","ts":1761080000000,"data":{"temperature":21.4}}} — ts in epoch millis, optional. */
    record Telemetry(Long ts, Map<String, Object> data) implements DeviceMessage {
    }

    /** {@code {"type":"ack","commandId":"<uuid>","status":"OK","message":"..."}} — status OK or ERROR. */
    record Ack(String commandId, String status, String message) implements DeviceMessage {
    }
}
