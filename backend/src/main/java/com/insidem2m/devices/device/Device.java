package com.insidem2m.devices.device;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.time.Instant;

/**
 * Device registry row. Written on connect ({@code hello}) and disconnect only —
 * telemetry never touches the database (see ADR-005).
 */
@Entity
public class Device extends PanacheEntityBase {

    /** Chosen by the device itself, taken from the WebSocket path {@code /ws/devices/{deviceId}}. */
    @Id
    public String id;

    /** e.g. "thermostat", "relay" — reported in {@code hello}. */
    public String deviceType;

    public String firmware;

    public Instant firstSeen;

    /** Last connect/disconnect. The live value (every message/pong) is in {@link DeviceState}. */
    public Instant lastSeen;

    /** Mirror of the in-memory connection registry, kept in the DB so the list can filter and page. */
    public boolean online;
}
