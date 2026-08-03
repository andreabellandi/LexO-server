package it.cnr.ilc.lexo.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import it.cnr.ilc.lexo.manager.ManagerFactory;
import it.cnr.ilc.lexo.manager.metadata.MetadataManager;
import it.cnr.ilc.lexo.service.data.metadata.MetadataDeleteRequest;
import it.cnr.ilc.lexo.service.data.metadata.MetadataPatchRequest;
import it.cnr.ilc.lexo.service.data.metadata.MetadataTarget;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.PATCH;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.log4j.Level;

/** Common RDF metadata CRUD for supported application entities. */
@Path("metadata")
@Api("Metadata")
public class Metadata extends Service {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final MetadataManager manager =
            ManagerFactory.getManager(MetadataManager.class);

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Entity metadata retrieval",
            notes = "Returns custom RDF metadata after resolving the entity-specific repository graph and protected predicates")
    public Response read(
            @ApiParam(name = "Authorization", value = "optional authorization header",
                    required = false)
            @HeaderParam("Authorization") String key,
            @ApiParam(name = "entityType", value = "supported entity kind",
                    allowableValues = "lexicalEntry,lexicalConcept,attestation",
                    required = true)
            @QueryParam("entityType") String entityType,
            @ApiParam(name = "resource", value = "absolute target resource IRI",
                    required = true)
            @QueryParam("resource") String resource,
            @ApiParam(name = "language", value = "language graph selector for lexicalEntry",
                    required = false)
            @QueryParam("language") String language,
            @ApiParam(name = "fileId", value = "document graph selector for attestation",
                    required = false)
            @QueryParam("fileId") String fileId) {
        try {
            checkKey(key);
            MetadataTarget target = new MetadataTarget();
            target.entityType = entityType;
            target.resource = resource;
            target.language = language;
            target.fileId = fileId;
            log(Level.INFO, "/metadata: reading entity metadata");
            return json(manager.read(target));
        } catch (IllegalArgumentException e) {
            return error(Response.Status.BAD_REQUEST.getStatusCode(), e);
        } catch (MetadataManager.MetadataException e) {
            return error(e.httpStatus, e);
        } catch (AuthorizationException | ServiceException e) {
            return error(Response.Status.BAD_REQUEST.getStatusCode(), e);
        } catch (RuntimeException e) {
            return error(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), e);
        }
    }

    @PATCH
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Entity metadata update",
            notes = "Atomically replaces selected metadata properties; an empty values list removes that property")
    public Response patch(
            @ApiParam(name = "Authorization", value = "optional authorization header",
                    required = false)
            @HeaderParam("Authorization") String key,
            @ApiParam(name = "metadataUpdate", value = "target and property replacements",
                    required = true)
            MetadataPatchRequest request) {
        try {
            checkKey(key);
            log(Level.INFO, "/metadata: updating entity metadata");
            return json(manager.patch(request));
        } catch (IllegalArgumentException e) {
            return error(Response.Status.BAD_REQUEST.getStatusCode(), e);
        } catch (MetadataManager.MetadataException e) {
            return error(e.httpStatus, e);
        } catch (AuthorizationException | ServiceException e) {
            return error(Response.Status.BAD_REQUEST.getStatusCode(), e);
        } catch (RuntimeException e) {
            return error(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), e);
        }
    }

    @DELETE
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Entity metadata deletion",
            notes = "Atomically removes selected custom RDF metadata properties")
    public Response delete(
            @ApiParam(name = "Authorization", value = "optional authorization header",
                    required = false)
            @HeaderParam("Authorization") String key,
            @ApiParam(name = "metadataDeletion", value = "target and property IRIs to remove",
                    required = true)
            MetadataDeleteRequest request) {
        try {
            checkKey(key);
            log(Level.INFO, "/metadata: deleting entity metadata");
            return json(manager.delete(request));
        } catch (IllegalArgumentException e) {
            return error(Response.Status.BAD_REQUEST.getStatusCode(), e);
        } catch (MetadataManager.MetadataException e) {
            return error(e.httpStatus, e);
        } catch (AuthorizationException | ServiceException e) {
            return error(Response.Status.BAD_REQUEST.getStatusCode(), e);
        } catch (RuntimeException e) {
            return error(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), e);
        }
    }

    private Response json(Object body) {
        try {
            return Response.ok(MAPPER.writeValueAsString(body),
                    MediaType.APPLICATION_JSON).build();
        } catch (JsonProcessingException e) {
            return error(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), e);
        }
    }

    private Response error(int status, Exception exception) {
        String message = exception.getMessage() == null
                ? exception.getClass().getSimpleName() : exception.getMessage();
        log(Level.ERROR, "/metadata: " + message);
        return Response.status(status).type(MediaType.TEXT_PLAIN)
                .entity(message).build();
    }
}
