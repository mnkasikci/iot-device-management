package com.insidem2m.devices.api;

import com.insidem2m.devices.command.Command;
import com.insidem2m.devices.command.CommandService;
import com.insidem2m.devices.device.Device;
import io.quarkus.panache.common.Sort;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * Send commands to a device and read its command history. The backend only checks that the
 * command has a name; whether name and params make sense is the device's call (ADR-013).
 */
@Path("/api/devices/{id}/commands")
public class CommandResource {

    static final int MAX_HISTORY = 500;

    @Inject
    CommandService commands;

    /** {@code {"name":"setInterval","params":{"seconds":5}}} — params optional. */
    public record CommandRequest(String name, Map<String, Object> params) {
    }

    /**
     * 202 Accepted: the command is stored and (if the device is online) sent; the outcome
     * arrives later with the ack. The body shows the status right now (SENT, or FAILED if offline).
     */
    @POST
    public Response send(@PathParam("id") String deviceId, CommandRequest request) {
        requireDevice(deviceId);
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new BadRequestException("Command needs a name");
        }
        Map<String, Object> params = request.params() != null ? request.params() : Map.of();
        Command command = commands.send(deviceId, request.name(), params);
        return Response.accepted(command).build();
    }

    /** Newest first. {@code ?limit=50} (max 500). */
    @GET
    public List<Command> history(@PathParam("id") String deviceId,
                                 @QueryParam("limit") @DefaultValue("50") int limit) {
        requireDevice(deviceId);
        return Command.<Command>find("deviceId", Sort.descending("createdAt"), deviceId)
                .page(0, Math.clamp(limit, 1, MAX_HISTORY))
                .list();
    }

    private static void requireDevice(String deviceId) {
        if (Device.findById(deviceId) == null) {
            throw new NotFoundException("Unknown device " + deviceId);
        }
    }
}
