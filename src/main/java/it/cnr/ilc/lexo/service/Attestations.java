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
import it.cnr.ilc.lexo.service.data.attestation.input.AttestationFilter;
import it.cnr.ilc.lexo.service.data.attestation.input.AttestationLocusUpdate;
import it.cnr.ilc.lexo.service.data.attestation.input.AttestationObservableUpdate;
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
import org.slf4j.event.Level;

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
            notes = "This method creates multiple FRAC attestations for one lexical entity and corpus in a single batch, stores their NIF loci in LexOTexts, and increments the observable frequency for each specific text")
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
    @javax.ws.rs.Path("by-observable")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Observable attestations retrieval",
            notes = "This method returns a paginated list of one observable's attestations with their per-text frequencies across all document graphs; an optional JSON filter supports nested AND/OR conditions on attestation creators, text metadata, and observable types")
    public Response listByObservable(
            @HeaderParam("Authorization") String key,
            @ApiParam(name = "observable",
                    value = "IRI of the observable whose attestations must be queried",
                    example = "https://lexo.ilc.cnr.it#LexO_example", required = true)
            @QueryParam("observable") String observable,
            @ApiParam(name = "limit",
                    value = "optional maximum page size; defaults to 50",
                    example = "50", required = false)
            @QueryParam("limit") String limit,
            @ApiParam(name = "offset",
                    value = "optional zero-based page offset; defaults to 0",
                    example = "0", required = false)
            @QueryParam("offset") String offset,
            @ApiParam(name = "filter",
                    value = "optional nested AND/OR filter tree",
                    required = false)
            AttestationFilter filter) {
        try {
            checkKey(key);
            log(Level.INFO, "/attestations/by-observable: listing attestations for observable="
                    + observable);
            return json(manager.listByObservable(observable, filter, limit, offset));
        } catch (ManagerException e) {
            log(Level.ERROR, "/attestations/by-observable: " + e.getMessage());
            return plain(Response.Status.BAD_REQUEST, e.getMessage());
        } catch (AuthorizationException | ServiceException e) {
            String username = authenticationData == null
                    || authenticationData.getUsername() == null
                    ? "" : authenticationData.getUsername();
            log(Level.ERROR, "/attestations/by-observable: " + username
                    + " not authorized");
            return plain(Response.Status.BAD_REQUEST, username + " not authorized");
        } catch (RuntimeException e) {
            log(Level.ERROR, "/attestations/by-observable: " + e.getMessage(), e);
            return plain(Response.Status.INTERNAL_SERVER_ERROR,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    @POST
    @javax.ws.rs.Path("by-locus")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Attestation creation by locus",
            notes = "This method creates one FRAC attestation for every supplied lexical entity at a shared textual locus, writes each entity's optional common RDF metadata on its own attestation, and updates every observable frequency for the specific text")
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
                    value = "JSON object containing the required value, start, end, and a non-empty list of observable objects with optional common RDF metadata",
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
            notes = "This method atomically deletes selected attestations, or every attestation, of one observable from a per-text graph, updates the affected observable frequencies, and removes orphan loci generated by the attestation service")
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
            notes = "This method atomically deletes selected attestations, or every attestation, at one NIF locus, updates every affected observable frequency, and removes the orphan locus when it was generated by the attestation service")
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
    @javax.ws.rs.Path("{fileId}/locus")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Attestation locus update",
            notes = "This method relinks one attestation to the NIF locus for a new Unicode code-point interval, recalculates the value from nif:isString, reuses a compatible destination, and preserves system or shared loci")
    public Response updateLocus(
            @HeaderParam("Authorization") String key,
            @ApiParam(name = "fileId",
                    value = "id of the text whose attestation and NIF locus must be updated",
                    example = "550e8400-e29b-41d4-a716-446655440000", required = true)
            @PathParam("fileId") String fileId,
            @ApiParam(name = "update",
                    value = "attestation IRI, replacement start/end offsets, and optional updateGloss flag",
                    required = true)
            AttestationLocusUpdate update) {
        try {
            checkKey(key);
            log(Level.INFO, "/attestations/{fileId}/locus: updating locus for fileId="
                    + fileId);
            return json(manager.updateLocus(fileId, update));
        } catch (ManagerException | IllegalArgumentException e) {
            log(Level.ERROR, "/attestations/{fileId}/locus: " + e.getMessage());
            return plain(Response.Status.BAD_REQUEST, e.getMessage());
        } catch (AuthorizationException | ServiceException e) {
            String username = authenticationData == null
                    || authenticationData.getUsername() == null
                    ? "" : authenticationData.getUsername();
            log(Level.ERROR, "/attestations/{fileId}/locus: " + username
                    + " not authorized");
            return plain(Response.Status.BAD_REQUEST, username + " not authorized");
        } catch (RuntimeException e) {
            log(Level.ERROR, "/attestations/{fileId}/locus: " + e.getMessage(), e);
            return plain(Response.Status.INTERNAL_SERVER_ERROR,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    @PATCH
    @javax.ws.rs.Path("{fileId}/observable")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Attestation observable batch update",
            notes = "This method atomically replaces the inverse frac:attestation link for one or more attestations in one per-text graph and recalculates the old and replacement observable frequencies")
    public Response updateObservable(
            @HeaderParam("Authorization") String key,
            @ApiParam(name = "fileId",
                    value = "id of the text whose attestation graph must be updated",
                    example = "550e8400-e29b-41d4-a716-446655440000", required = true)
            @PathParam("fileId") String fileId,
            @ApiParam(name = "update",
                    value = "replacement observable IRI and a non-empty attestation IRI list",
                    required = true)
            AttestationObservableUpdate update) {
        try {
            checkKey(key);
            log(Level.INFO, "/attestations/{fileId}/observable: updating observable for fileId="
                    + fileId);
            return json(manager.updateObservable(fileId, update));
        } catch (ManagerException | IllegalArgumentException e) {
            log(Level.ERROR, "/attestations/{fileId}/observable: "
                    + e.getMessage());
            return plain(Response.Status.BAD_REQUEST, e.getMessage());
        } catch (AuthorizationException | ServiceException e) {
            String username = authenticationData == null
                    || authenticationData.getUsername() == null
                    ? "" : authenticationData.getUsername();
            log(Level.ERROR, "/attestations/{fileId}/observable: " + username
                    + " not authorized");
            return plain(Response.Status.BAD_REQUEST, username + " not authorized");
        } catch (RuntimeException e) {
            log(Level.ERROR, "/attestations/{fileId}/observable: "
                    + e.getMessage(), e);
            return plain(Response.Status.INTERNAL_SERVER_ERROR,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    @POST
    @javax.ws.rs.Path("{fileId}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Text attestations retrieval",
            notes = "This method returns a paginated list of the attestations stored for one text, including observable display labels, per-text frequencies, and NIF locus data; optional query parameters filter by the exact observable, observable type, or creator, while an optional JSON filter supports nested AND/OR conditions on attestation creators, text metadata, and observable types; attestation descriptions are not included")
    public Response list(
            @HeaderParam("Authorization") String key,
            @ApiParam(name = "fileId",
                    value = "id of the text whose attestation graph must be queried",
                    example = "550e8400-e29b-41d4-a716-446655440000", required = true)
            @PathParam("fileId") String fileId,
            @ApiParam(name = "observable",
                    value = "optional exact IRI of the observed lexical entity whose attestations must be returned",
                    example = "https://lexo.ilc.cnr.it#LexO_example", required = false)
            @QueryParam("observable") String observable,
            @ApiParam(name = "observableType",
                    value = "optional RDF type IRI used to filter the observed lexical entities",
                    example = "http://www.w3.org/ns/lemon/ontolex#LexicalEntry", required = false)
            @QueryParam("observableType") String observableType,
            @ApiParam(name = "author",
                    value = "optional dct:creator value used to filter the attestations",
                    example = "user7", required = false)
            @QueryParam("author") String author,
            @ApiParam(name = "limit",
                    value = "optional maximum page size; defaults to 50",
                    example = "50", required = false)
            @QueryParam("limit") String limit,
            @ApiParam(name = "offset",
                    value = "optional zero-based page offset; defaults to 0",
                    example = "0", required = false)
            @QueryParam("offset") String offset,
            @ApiParam(name = "filter",
                    value = "optional nested AND/OR filter tree; observable, observableType, and author query parameters are combined with it using AND",
                    required = false)
            AttestationFilter filter) {
        try {
            checkKey(key);
            log(Level.INFO, "/attestations/{fileId}: listing attestations for fileId="
                    + fileId);
            return json(manager.list(fileId, observable, observableType, author,
                    filter, limit, offset));
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
