/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.core.io.rest.sse;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.security.RolesAllowed;
import javax.inject.Singleton;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;

import org.glassfish.jersey.media.sse.EventOutput;
import org.glassfish.jersey.media.sse.OutboundEvent;
import org.glassfish.jersey.media.sse.SseFeature;
import org.openhab.core.auth.Role;
import org.openhab.core.io.rest.sse.internal.ItemStateChangesSseBroadcaster;
import org.openhab.core.io.rest.sse.internal.SseStateEventOutput;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.items.events.ItemStateChangedEvent;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;

/**
 * SSE Resource for pushing selected item state updates to clients.
 *
 * @author Yannick Schaus - Initial contribution
 */
@Component(service = SseStatesResource.class)
@Path(SseStatesResource.PATH_EVENTS)
@RolesAllowed({ Role.USER })
@Singleton
@Api(value = SseStatesResource.PATH_EVENTS, hidden = true)
public class SseStatesResource {

    private final Logger logger = LoggerFactory.getLogger(SseStatesResource.class);

    public static final String PATH_EVENTS = "events/states";

    private static final String X_ACCEL_BUFFERING_HEADER = "X-Accel-Buffering";

    private final ItemStateChangesSseBroadcaster broadcaster;

    private final ExecutorService executorService;

    private ItemRegistry itemRegistry;

    @Reference
    protected void setItemRegistry(ItemRegistry itemRegistry) {
        this.itemRegistry = itemRegistry;
        this.broadcaster.setItemRegistry(itemRegistry);
    }

    protected void unsetItemRegistry(ItemRegistry itemRegistry) {
        this.itemRegistry = itemRegistry;
    }

    @Context
    private UriInfo uriInfo;

    @Context
    private HttpServletResponse response;

    @Context
    private HttpServletRequest request;

    public SseStatesResource() {
        this.executorService = Executors.newSingleThreadExecutor();
        this.broadcaster = new ItemStateChangesSseBroadcaster();
    }

    /**
     * Subscribes the connecting client for state updates. It will initially only send a "ready" event with an unique
     * connectionId that the client can use to dynamically alter the list of tracked items.
     *
     * @return {@link EventOutput} object associated with the incoming
     *         connection.
     * @throws IOException
     * @throws InterruptedException
     */
    @GET
    @Produces(SseFeature.SERVER_SENT_EVENTS)
    @ApiOperation(value = "Initiates a new item state tracker connection", response = EventOutput.class)
    @ApiResponses(value = { @ApiResponse(code = 200, message = "OK") })
    public Object getStateEvents() throws IOException, InterruptedException {

        final SseStateEventOutput eventOutput = new SseStateEventOutput();
        broadcaster.add(eventOutput);

        // Disables proxy buffering when using an nginx http server proxy for this response.
        // This allows you to not disable proxy buffering in nginx and still have working sse
        response.addHeader(X_ACCEL_BUFFERING_HEADER, "no");

        // We want to make sure that the response is not compressed and buffered so that the client receives server sent
        // events at the moment of sending them.
        response.addHeader(HttpHeaders.CONTENT_ENCODING, "identity");

        return eventOutput;
    }

    /**
     * Alters the list of tracked items for a given state update connection
     *
     * @param connectionId the connection Id to change
     * @param itemNames the list of items to track
     */
    @POST
    @Path("{connectionId}")
    @ApiOperation(value = "Changes the list of items a SSE connection will receive state updates to.")
    @ApiResponses(value = { @ApiResponse(code = 200, message = "OK"),
            @ApiResponse(code = 404, message = "Unknown connectionId") })
    public Object updateTrackedItems(@PathParam("connectionId") String connectionId,
            @ApiParam("items") Set<String> itemNames) {

        try {
            broadcaster.updateTrackedItems(connectionId, itemNames);
        } catch (IllegalArgumentException e) {
            return Response.status(Status.NOT_FOUND).build();
        }

        return Response.ok().build();
    }

    /**
     * Broadcasts a state event to all currently listening clients, after transforming it to a simple map.
     *
     * @param stateChangeEvent the {@link ItemStateChangedEvent} containing the new state
     */
    public void broadcastEvent(final ItemStateChangedEvent stateChangeEvent) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                OutboundEvent event = broadcaster.buildStateEvent(Set.of(stateChangeEvent.getItemName()));
                if (event != null) {
                    broadcaster.broadcast(event);
                }
            }
        });
    }
}
