package com.splitttr.collab.client;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "document-service")
@Path("/api/documents")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface DocumentClient {

    @GET
    @Path("/{id}")
    DocumentResponse getById(@PathParam("id") String id);

    @PUT
    @Path("/{id}")
    DocumentResponse update(@PathParam("id") String id, DocumentUpdateRequest req);
}