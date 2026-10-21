package com.insidem2m.devices.device;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * deviceId → {@link DeviceState}. Entries stay after a disconnect so the portal can still
 * show the last values and history of an offline device.
 */
@ApplicationScoped
public class DeviceStateStore {

    private final Map<String, DeviceState> states = new ConcurrentHashMap<>();

    public DeviceState getOrCreate(String deviceId) {
        return states.computeIfAbsent(deviceId, id -> new DeviceState());
    }

    /** Null if this instance has never heard from the device. */
    public DeviceState get(String deviceId) {
        return states.get(deviceId);
    }
}
