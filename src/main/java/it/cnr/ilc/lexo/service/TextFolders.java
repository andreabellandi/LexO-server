package it.cnr.ilc.lexo.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import it.cnr.ilc.lexo.manager.text.organization.TextFolderManager;
import it.cnr.ilc.lexo.manager.text.organization.TextOrganizationException;
import it.cnr.ilc.lexo.service.data.text.input.FolderBulkTextMoveRequest;
import it.cnr.ilc.lexo.service.data.text.input.FolderCreateRequest;
import it.cnr.ilc.lexo.service.data.text.input.FolderMoveRequest;
import it.cnr.ilc.lexo.service.data.text.input.FolderUpdateRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.slf4j.event.Level;

/** REST API for the independent hierarchical organization of persisted texts. */
@javax.ws.rs.Path("text-folders")
@Api("Text Organization")
public class TextFolders extends Service {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Create a text folder",
            notes = "Creates a root folder or a child folder in the dedicated text-organization named graph")
    public Response create(
            @ApiParam(name = "Authorization", value = "optional authorization header")
            @HeaderParam("Authorization") String key,
            @ApiParam(name = "request", value = "folder metadata and optional parent id",
                    required = true)
            FolderCreateRequest request) {
        try {
            checkKey(key);
            return json(Response.Status.CREATED, TextFolderManager.get().create(request));
        } catch (TextOrganizationException e) {
            return organizationError(e);
        } catch (RuntimeException e) {
            return unexpected("POST /text-folders", e);
        } catch (AuthorizationException | ServiceException e) {
            return unauthorized("POST /text-folders");
        }
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Get the complete text folder tree",
            notes = "Builds the complete hierarchy from direct parent relationships without persisting paths or depths")
    public Response tree(
            @ApiParam(name = "Authorization", value = "optional authorization header")
            @HeaderParam("Authorization") String key,
            @ApiParam(name = "includeTexts", value = "include direct text members in every folder")
            @DefaultValue("false") @QueryParam("includeTexts") boolean includeTexts) {
        try {
            checkKey(key);
            return json(TextFolderManager.get().tree(includeTexts));
        } catch (TextOrganizationException e) {
            return organizationError(e);
        } catch (RuntimeException e) {
            return unexpected("GET /text-folders", e);
        } catch (AuthorizationException | ServiceException e) {
            return unauthorized("GET /text-folders");
        }
    }

    @GET
    @javax.ws.rs.Path("/{folderId}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Get a text folder",
            notes = "Returns one folder with its direct children or its complete descendant subtree and optional text memberships")
    public Response get(
            @ApiParam(name = "Authorization", value = "optional authorization header")
            @HeaderParam("Authorization") String key,
            @ApiParam(name = "folderId", value = "folder identifier", required = true)
            @PathParam("folderId") String folderId,
            @ApiParam(name = "recursive", value = "include every descendant folder")
            @DefaultValue("false") @QueryParam("recursive") boolean recursive,
            @ApiParam(name = "includeTexts", value = "include direct text memberships")
            @DefaultValue("true") @QueryParam("includeTexts") boolean includeTexts) {
        try {
            checkKey(key);
            return json(TextFolderManager.get().get(folderId, recursive, includeTexts));
        } catch (TextOrganizationException e) {
            return organizationError(e);
        } catch (RuntimeException e) {
            return unexpected("GET /text-folders/{folderId}", e);
        } catch (AuthorizationException | ServiceException e) {
            return unauthorized("GET /text-folders/{folderId}");
        }
    }

    @PUT
    @javax.ws.rs.Path("/{folderId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Update text folder metadata",
            notes = "Replaces the folder name and description without changing its parent or members")
    public Response update(
            @ApiParam(name = "Authorization", value = "optional authorization header")
            @HeaderParam("Authorization") String key,
            @ApiParam(name = "folderId", value = "folder identifier", required = true)
            @PathParam("folderId") String folderId,
            @ApiParam(name = "request", value = "replacement name and description",
                    required = true)
            FolderUpdateRequest request) {
        try {
            checkKey(key);
            return json(TextFolderManager.get().update(folderId, request));
        } catch (TextOrganizationException e) {
            return organizationError(e);
        } catch (RuntimeException e) {
            return unexpected("PUT /text-folders/{folderId}", e);
        } catch (AuthorizationException | ServiceException e) {
            return unauthorized("PUT /text-folders/{folderId}");
        }
    }

    @POST
    @javax.ws.rs.Path("/{folderId}/move")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Move a text folder",
            notes = "Atomically changes the direct parent, rejecting self-parenting and descendant cycles")
    public Response move(
            @ApiParam(name = "Authorization", value = "optional authorization header")
            @HeaderParam("Authorization") String key,
            @ApiParam(name = "folderId", value = "folder identifier", required = true)
            @PathParam("folderId") String folderId,
            @ApiParam(name = "request", value = "new parent id or null for root",
                    required = true)
            FolderMoveRequest request) {
        try {
            checkKey(key);
            return json(TextFolderManager.get().move(folderId, request));
        } catch (TextOrganizationException e) {
            return organizationError(e);
        } catch (RuntimeException e) {
            return unexpected("POST /text-folders/{folderId}/move", e);
        } catch (AuthorizationException | ServiceException e) {
            return unauthorized("POST /text-folders/{folderId}/move");
        }
    }

    @DELETE
    @javax.ws.rs.Path("/{folderId}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Delete an empty text folder",
            notes = "Deletes only a folder with no direct child folders and no direct text memberships; texts are never deleted")
    public Response delete(
            @ApiParam(name = "Authorization", value = "optional authorization header")
            @HeaderParam("Authorization") String key,
            @ApiParam(name = "folderId", value = "folder identifier", required = true)
            @PathParam("folderId") String folderId) {
        try {
            checkKey(key);
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("folderId", folderId);
            result.put("deleted", Boolean.valueOf(TextFolderManager.get().delete(folderId)));
            return json(result);
        } catch (TextOrganizationException e) {
            return organizationError(e);
        } catch (RuntimeException e) {
            return unexpected("DELETE /text-folders/{folderId}", e);
        } catch (AuthorizationException | ServiceException e) {
            return unauthorized("DELETE /text-folders/{folderId}");
        }
    }

    @PUT
    @javax.ws.rs.Path("/{folderId}/texts/{fileId}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Assign a text to a folder",
            notes = "Atomically assigns an unfiled persisted text or moves it from its previous folder using its authoritative NIF context IRI")
    public Response assign(
            @ApiParam(name = "Authorization", value = "optional authorization header")
            @HeaderParam("Authorization") String key,
            @ApiParam(name = "folderId", value = "target folder identifier", required = true)
            @PathParam("folderId") String folderId,
            @ApiParam(name = "fileId", value = "persisted text identifier", required = true)
            @PathParam("fileId") String fileId) {
        try {
            checkKey(key);
            return json(TextFolderManager.get().assign(folderId, fileId));
        } catch (TextOrganizationException e) {
            return organizationError(e);
        } catch (RuntimeException e) {
            return unexpected("PUT /text-folders/{folderId}/texts/{fileId}", e);
        } catch (AuthorizationException | ServiceException e) {
            return unauthorized("PUT /text-folders/{folderId}/texts/{fileId}");
        }
    }

    @DELETE
    @javax.ws.rs.Path("/{folderId}/texts/{fileId}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Remove a text from a folder",
            notes = "Removes only the requested direct membership and makes the text unfiled without deleting or changing its NIF data")
    public Response remove(
            @ApiParam(name = "Authorization", value = "optional authorization header")
            @HeaderParam("Authorization") String key,
            @ApiParam(name = "folderId", value = "current folder identifier", required = true)
            @PathParam("folderId") String folderId,
            @ApiParam(name = "fileId", value = "persisted text identifier", required = true)
            @PathParam("fileId") String fileId) {
        try {
            checkKey(key);
            return json(TextFolderManager.get().remove(folderId, fileId));
        } catch (TextOrganizationException e) {
            return organizationError(e);
        } catch (RuntimeException e) {
            return unexpected("DELETE /text-folders/{folderId}/texts/{fileId}", e);
        } catch (AuthorizationException | ServiceException e) {
            return unauthorized("DELETE /text-folders/{folderId}/texts/{fileId}");
        }
    }

    @POST
    @javax.ws.rs.Path("/move-texts")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Move multiple texts atomically",
            notes = "Validates the complete unique fileId list before moving every membership in one RDF transaction to a folder or to virtual unfiled state")
    public Response bulkMove(
            @ApiParam(name = "Authorization", value = "optional authorization header")
            @HeaderParam("Authorization") String key,
            @ApiParam(name = "request", value = "text ids and target folder id or null",
                    required = true)
            FolderBulkTextMoveRequest request) {
        try {
            checkKey(key);
            return json(TextFolderManager.get().bulkMove(request));
        } catch (TextOrganizationException e) {
            return organizationError(e);
        } catch (RuntimeException e) {
            return unexpected("POST /text-folders/move-texts", e);
        } catch (AuthorizationException | ServiceException e) {
            return unauthorized("POST /text-folders/move-texts");
        }
    }

    @GET
    @javax.ws.rs.Path("/unfiled")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "List unfiled texts",
            notes = "Returns every persisted NIF text context that is not referenced by a containsText statement without creating a synthetic folder")
    public Response unfiled(
            @ApiParam(name = "Authorization", value = "optional authorization header")
            @HeaderParam("Authorization") String key) {
        try {
            checkKey(key);
            return json(TextFolderManager.get().unfiled());
        } catch (TextOrganizationException e) {
            return organizationError(e);
        } catch (RuntimeException e) {
            return unexpected("GET /text-folders/unfiled", e);
        } catch (AuthorizationException | ServiceException e) {
            return unauthorized("GET /text-folders/unfiled");
        }
    }

    private Response organizationError(TextOrganizationException error) {
        Response.Status status;
        switch (error.status) {
            case NOT_FOUND:
                status = Response.Status.NOT_FOUND;
                break;
            case CONFLICT:
                status = Response.Status.CONFLICT;
                break;
            case INTERNAL_ERROR:
                status = Response.Status.INTERNAL_SERVER_ERROR;
                break;
            default:
                status = Response.Status.BAD_REQUEST;
        }
        return error(status, error.code, error.getMessage());
    }

    private Response unexpected(String endpoint, RuntimeException error) {
        log(Level.ERROR, endpoint + ": text organization failure", error);
        return error(Response.Status.INTERNAL_SERVER_ERROR,
                "TEXT_ORGANIZATION_ERROR", message(error));
    }

    private Response unauthorized(String endpoint) {
        String username = authenticationData == null
                || authenticationData.getUsername() == null
                ? "" : authenticationData.getUsername();
        log(Level.ERROR, endpoint + ": " + username + " not authorized");
        return error(Response.Status.BAD_REQUEST, "AUTHORIZATION_ERROR",
                username + " not authorized");
    }

    private static Response error(Response.Status status, String code,
                                  String message) {
        Map<String, String> body = new LinkedHashMap<String, String>();
        body.put("code", code);
        body.put("message", message == null ? "" : message);
        return json(status, body);
    }

    private static Response json(Object body) {
        return json(Response.Status.OK, body);
    }

    private static Response json(Response.Status status, Object body) {
        try {
            return Response.status(status).type(MediaType.APPLICATION_JSON)
                    .entity(MAPPER.writeValueAsString(body)).build();
        } catch (JsonProcessingException e) {
            return Response.serverError().type(MediaType.APPLICATION_JSON)
                    .entity("{\"code\":\"TEXT_ORGANIZATION_ERROR\","
                            + "\"message\":\"Unable to serialize response\"}")
                    .build();
        }
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName()
                : error.getMessage();
    }
}
