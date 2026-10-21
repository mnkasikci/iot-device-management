package com.insidem2m.devices.connection;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;
import java.util.UUID;

/**
 * The only server → device frame:
 * {@code {"type":"command","commandId":"<uuid>","name":"setInterval","params":{"seconds":5}}}.
 * Kept apart from {@link DeviceMessage}, which is device → server only.
 */
public record CommandFrame(UUID commandId, String name, Map<String, Object> params) {

    @JsonProperty("type")
    public String type() {
        return "command";
    }
}
