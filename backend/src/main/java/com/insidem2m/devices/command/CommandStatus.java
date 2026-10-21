package com.insidem2m.devices.command;

/**
 * Lifecycle of a command (ADR-004):
 * {@code PENDING → SENT → ACKED / FAILED / TIMEOUT}, or {@code PENDING → FAILED} if the device is offline.
 */
public enum CommandStatus {
    /** Stored, not yet handed to the socket. */
    PENDING,
    /** Written to the device's socket; waiting for its {@code ack}. */
    SENT,
    /** Device acked with status OK. */
    ACKED,
    /** Device offline, send failed, or device acked with status ERROR. */
    FAILED,
    /** No ack within the timeout. */
    TIMEOUT
}
