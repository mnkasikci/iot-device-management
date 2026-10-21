package com.insidem2m.devices.command;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Command log row: one per command an operator sent. Status changes are written by
 * {@link CommandService} only. Also serialized as-is by the REST API.
 */
@Entity
@Table(indexes = {
        @Index(columnList = "deviceId, createdAt"), // history of one device, newest first
        @Index(columnList = "status"),              // timeout check looks for SENT
})
public class Command extends PanacheEntityBase {

    /** Also the {@code commandId} on the wire; the device echoes it in its {@code ack}. */
    @Id
    public UUID id;

    public String deviceId;

    /** e.g. "setInterval" — not checked by the backend, the device decides (ADR-013). */
    public String name;

    /** Stored as Postgres {@code jsonb}; shape depends on the command (see PLAN.md wire protocol). */
    @JdbcTypeCode(SqlTypes.JSON)
    public Map<String, Object> params;

    @Enumerated(EnumType.STRING)
    public CommandStatus status;

    /** Why it failed, or the device's ack message. */
    public String message;

    public Instant createdAt;

    public Instant sentAt;

    /** When it reached ACKED, FAILED or TIMEOUT. */
    public Instant completedAt;
}
