package it.cnr.ilc.lexo.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import it.cnr.ilc.lexo.manager.AttestationManager;
import it.cnr.ilc.lexo.manager.ManagerException;
import it.cnr.ilc.lexo.manager.ManagerFactory;
import it.cnr.ilc.lexo.service.data.attestation.input.AttestationByLocusInput;
import it.cnr.ilc.lexo.service.data.attestation.input.AttestationDeleteByLocusInput;
import it.cnr.ilc.lexo.service.data.attestation.input.AttestationDeleteByObservableInput;
import it.cnr.ilc.lexo.service.data.attestation.input.AttestationMetadataBatch;
import it.cnr.ilc.lexo.service.data.attestation.input.AttestationOccurrence;
import java.util.List;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.log4j.Level;

/** REST services for FRAC attestation management. */
@javax.ws.rs.Path("attestations")
@Api("Attestation")
public class Attestations extends Service {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final AttestationManager manager =
            ManagerFactory.getManager(AttestationManager.class);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Attestation batch creation",
            notes = "This method creates multiple FRAC attestations for one lexical entity and corpus in a single batch and stores their NIF loci in LexOTexts")
    public Response create(
            @HeaderParam("Authorization") String key,
            @ApiParam(name = "observable",
                    value = "IRI of the observed ontolex lexical entry, form, lexical sense, or lexical concept",
                    example = "https://lexo.ilc.cnr.it#LexO_example", required = true)
            @QueryParam("observable") String observable,
            @ApiParam(name = "corpus",
                    value = "IRI of the text or corpus where the value was observed",
                    example = "https://lexo.ilc.cnr.it/texts/example#context", required = true)
            @QueryParam("corpus") String corpus,
            @ApiParam(name = "external",
                    value = "true when corpus is an external HTTP or HTTPS resource",
                    example = "false", required = false)
            @QueryParam("external") String external,
            @ApiParam(name = "author",
                    value = "the account that is creating the attestation (if LexO user management is disabled)",
                    example = "user7", required = false)
            @QueryParam("author") String author,
            @ApiParam(name = "occurrences",
                    value = "JSON list of occurrences; every item requires value, start, and end",
                    required = true)
            List<AttestationOccurrence> occurrences) {
        try {
            checkKey(key);
            boolean externalValue = parseExternal(external);
            String creator = getUser(author);
            log(Level.INFO, "/attestations: creating attestation for observable="
                    + observable + " corpus=" + corpus);
            return json(manager.createBatch(observable, corpus, externalValue,
                    creator, occurrences));
        } catch (ManagerException | IllegalArgumentException e) {
            log(Level.ERROR, "/attestations: " + e.getMessage());
            return plain(Response.Status.BAD_REQUEST, e.getMessage());
        } catch (AuthorizationException | ServiceException e) {
            String username = authenticationData == null
                    || authenticationData.getUsername() == null
                    ? "" : authenticationData.getUsername();
            log(Level.ERROR, "/attestations: " + username + " not authorized");
            return plain(Response.Status.BAD_REQUEST, username + " not authorized");
        } catch (RuntimeException e) {
            log(Level.ERROR, "/attestations: " + e.getMessage(), e);
            return plain(Response.Status.INTERNAL_SERVER_ERROR,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    @POST
    @javax.ws.rs.Path("by-locus")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Attestation creation by locus",
            notes = "This method creates one FRAC attestation for every supplied lexical entity at a shared textual locus and returns the created attestations")
    public Response createByLocus(
            @HeaderParam("Authorization") String key,
            @ApiParam(name = "corpus",
                    value = "IRI of the text or corpus where the lexical entities were observed",
                    example = "https://lexo.ilc.cnr.it/texts/example#context", required = true)
            @QueryParam("corpus") String corpus,
            @ApiParam(name = "external",
                    value = "true when corpus is an external HTTP or HTTPS resource",
                    example = "false", required = false)
            @QueryParam("external") String external,
            @ApiParam(name = "author",
                    value = "the account that is creating the attestations (if LexO user management is disabled)",
                    example = "user7", required = false)
            @QueryParam("author") String author,
            @ApiParam(name = "locus",
                    value = "JSON object containing the required value, start, end, and non-empty observable IRI list",
                    required = true)
            AttestationByLocusInput locus) {
        try {
            checkKey(key);
            boolean externalValue = parseExternal(external);
            String creator = getUser(author);
            log(Level.INFO, "/attestations/by-locus: creating attestations for corpus="
                    + corpus);
            return json(manager.createByLocus(corpus, externalValue, creator, locus));
        } catch (ManagerException | IllegalArgumentException e) {
            log(Level.ERROR, "/attestations/by-locus: " + e.getMessage());
            return plain(Response.Status.BAD_REQUEST, e.getMessage());
        } catch (AuthorizationException | ServiceException e) {
            String username = authenticationData == null
                    || authenticationData.getUsername() == null
                    ? "" : authenticationData.getUsername();
            log(Level.ERROR, "/attestations/by-locus: " + username
                    + " not authorized");
            return plain(Response.Status.BAD_REQUEST, username + " not authorized");
        } catch (RuntimeException e) {
            log(Level.ERROR, "/attestations/by-locus: " + e.getMessage(), e);
            return plain(Response.Status.INTERNAL_SERVER_ERROR,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    @DELETE
    @javax.ws.rs.Path("{fileId}/by-observable")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Attestation deletion by observable",
            notes = "This method atomically deletes selected attestations, or every attestation, of one observable from a per-text graph and removes orphan loci generated by the attestation service")
    public Response deleteByObservable(
            @HeaderParam("Authorization") String key,
            @ApiParam(name = "fileId",
                    value = "id of the text whose attestation graph must be updated",
                    example = "550e8400-e29b-41d4-a716-446655440000", required = true)
            @PathParam("fileId") String fileId,
            @ApiParam(name = "deletion",
                    value = "observable and either all=true or a non-empty attestation IRI list",
                    required = true)
            AttestationDeleteByObservableInput deletion) {
        try {
            checkKey(key);
            log(Level.INFO, "/attestations/{fileId}/by-observable: deleting attestations for fileId="
                    + fileId);
            return json(manager.deleteByObservable(fileId, deletion));
        } catch (ManagerException | IllegalArgumentException e) {
            log(Level.ERROR, "/attestations/{fileId}/by-observable: " + e.getMessage());
            return plain(Response.Status.BAD_REQUEST, e.getMessage());
        } catch (AuthorizationException | ServiceException e) {
            String username = authenticationData == null
                    || authenticationData.getUsername() == null
                    ? "" : authenticationData.getUsername();
            log(Level.ERROR, "/attestations/{fileId}/by-observable: " + username
                    + " not authorized");
            return plain(Response.Status.BAD_REQUEST, username + " not authorized");
        } catch (RuntimeException e) {
            log(Level.ERROR, "/attestations/{fileId}/by-observable: "
                    + e.getMessage(), e);
            return plain(Response.Status.INTERNAL_SERVER_ERROR,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    @DELETE
    @javax.ws.rs.Path("{fileId}/by-locus")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Attestation deletion by locus",
            notes = "This method atomically deletes selected attestations, or every attestation, at one NIF locus and removes the orphan locus when it was generated by the attestation service")
    public Response deleteByLocus(
            @HeaderParam("Authorization") String key,
            @ApiParam(name = "fileId",
                    value = "id of the text whose attestation graph must be updated",
                    example = "550e8400-e29b-41d4-a716-446655440000", required = true)
            @PathParam("fileId") String fileId,
            @ApiParam(name = "deletion",
                    value = "locus and either all=true or a non-empty attestation IRI list",
                    required = true)
            AttestationDeleteByLocusInput deletion) {
        try {
            checkKey(key);
            log(Level.INFO, "/attestations/{fileId}/by-locus: deleting attestations for fileId="
                    + fileId);
            return json(manager.deleteByLocus(fileId, deletion));
        } catch (ManagerException | IllegalArgumentException e) {
            log(Level.ERROR, "/attestations/{fileId}/by-locus: " + e.getMessage());
            return plain(Response.Status.BAD_REQUEST, e.getMessage());
        } catch (AuthorizationException | ServiceException e) {
            String username = authenticationData == null
                    || authenticationData.getUsername() == null
                    ? "" : authenticationData.getUsername();
            log(Level.ERROR, "/attestations/{fileId}/by-locus: " + username
                    + " not authorized");
            return plain(Response.Status.BAD_REQUEST, username + " not authorized");
        } catch (RuntimeException e) {
            log(Level.ERROR, "/attestations/{fileId}/by-locus: " + e.getMessage(), e);
            return plain(Response.Status.INTERNAL_SERVER_ERROR,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    @PATCH
    @javax.ws.rs.Path("{fileId}/metadata")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Attestation metadata batch update",
            notes = "This method atomically replaces selected RDF metadata property values on one or more FRAC attestations in the per-text attestation graph; an empty values list removes the property")
    public Response patchMetadata(
            @HeaderParam("Authorization") String key,
            @ApiParam(name = "fileId",
                    value = "id of the text whose attestation graph must be updated",
                    example = "550e8400-e29b-41d4-a716-446655440000", required = true)
            @PathParam("fileId") String fileId,
            @ApiParam(name = "batch",
                    value = "atomic batch of attestation metadata property replacements",
                    required = true)
            AttestationMetadataBatch batch) {
        try {
            checkKey(key);
            log(Level.INFO, "/attestations/{fileId}/metadata: updating metadata for fileId="
                    + fileId);
            return json(manager.patchMetadata(fileId, batch));
        } catch (ManagerException | IllegalArgumentException e) {
            log(Level.ERROR, "/attestations/{fileId}/metadata: " + e.getMessage());
            return plain(Response.Status.BAD_REQUEST, e.getMessage());
        } catch (AuthorizationException | ServiceException e) {
            String username = authenticationData == null
                    || authenticationData.getUsername() == null
                    ? "" : authenticationData.getUsername();
            log(Level.ERROR, "/attestations/{fileId}/metadata: " + username
                    + " not authorized");
            return plain(Response.Status.BAD_REQUEST, username + " not authorized");
        } catch (RuntimeException e) {
            log(Level.ERROR, "/attestations/{fileId}/metadata: " + e.getMessage(), e);
            return plain(Response.Status.INTERNAL_SERVER_ERROR,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    @POST
    @javax.ws.rs.Path("{fileId}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Text attestations retrieval",
            notes = "This method returns a paginated list of the attestations stored for one text, including observable display labels and NIF locus data; attestation descriptions are not included")
    public Response list(
            @HeaderParam("Authorization") String key,
            @ApiParam(name = "fileId",
                    value = "id of the text whose attestation graph must be queried",
                    example = "550e8400-e29b-41d4-a716-446655440000", required = true)
            @PathParam("fileId") String fileId,
            @ApiParam(name = "observableType",
                    value = "optional RDF type IRI used to filter the observed lexical entities",
                    example = "http://www.w3.org/ns/lemon/ontolex#LexicalEntry", required = false)
            @QueryParam("observableType") String observableType,
            @ApiParam(name = "author",
                    value = "optional dct:creator value used to filter the attestations",
                    example = "user7", required = false)
            @QueryParam("author") String author,
            @ApiParam(name = "limit",
                    value = "optional maximum page size; defaults to 200",
                    example = "200", required = false)
            @QueryParam("limit") String limit,
            @ApiParam(name = "offset",
                    value = "optional zero-based page offset; defaults to 0",
                    example = "0", required = false)
            @QueryParam("offset") String offset) {
        try {
            checkKey(key);
            log(Level.INFO, "/attestations/{fileId}: listing attestations for fileId="
                    + fileId);
            return json(manager.list(fileId, observableType, author, limit, offset));
        } catch (ManagerException e) {
            log(Level.ERROR, "/attestations/{fileId}: " + e.getMessage());
            return plain(Response.Status.BAD_REQUEST, e.getMessage());
        } catch (AuthorizationException | ServiceException e) {
            String username = authenticationData == null
                    || authenticationData.getUsername() == null
                    ? "" : authenticationData.getUsername();
            log(Level.ERROR, "/attestations/{fileId}: " + username
                    + " not authorized");
            return plain(Response.Status.BAD_REQUEST, username + " not authorized");
        } catch (RuntimeException e) {
            log(Level.ERROR, "/attestations/{fileId}: " + e.getMessage(), e);
            return plain(Response.Status.INTERNAL_SERVER_ERROR,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static boolean parseExternal(String value) {
        if (value == null || value.trim().isEmpty() || "false".equalsIgnoreCase(value)) {
            return false;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        throw new IllegalArgumentException(
                "INVALID_BOOLEAN: external must be true or false");
    }

    private static Response json(Object body) {
        try {
            return Response.ok(MAPPER.writeValueAsString(body),
                    MediaType.APPLICATION_JSON).build();
        } catch (JsonProcessingException e) {
            return plain(Response.Status.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private static Response plain(Response.Status status, String message) {
        return Response.status(status).type(MediaType.TEXT_PLAIN)
                .entity(message == null ? "" : message).build();
    }
}
