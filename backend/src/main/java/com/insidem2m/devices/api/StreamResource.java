package com.insidem2m.devices.api;

import com.insidem2m.devices.stream.LiveUpdates;
import com.insidem2m.devices.stream.LiveUpdates.Batch;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestStreamElementType;

/**
 * {@code GET /api/stream} — Server-Sent Events, one JSON {@link Batch} per second (ADR-006).
 * The portal loads the initial state via REST and then applies these batches on top.
 * The browser's {@code EventSource} reconnects by itself if the stream drops.
 */
@Path("/api/stream")
public class StreamResource {

    @Inject
    LiveUpdates live;

    @GET
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<Batch> stream() {
        return live.stream();
    }
}
