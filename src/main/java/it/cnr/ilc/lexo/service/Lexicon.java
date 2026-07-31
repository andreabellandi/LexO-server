package it.cnr.ilc.lexo.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import it.cnr.ilc.lexo.manager.LexiconCrudSupport;
import it.cnr.ilc.lexo.manager.LexicalConceptManager;
import it.cnr.ilc.lexo.manager.LexicalEntryListManager;
import it.cnr.ilc.lexo.manager.LexicalEntryManager;
import it.cnr.ilc.lexo.manager.LexicalEntryStatusManager;
import it.cnr.ilc.lexo.manager.ManagerFactory;
import it.cnr.ilc.lexo.service.data.lexicon.input.LexicalEntryCreationRequest;
import it.cnr.ilc.lexo.service.data.lexicon.input.LexicalConceptCreationRequest;
import it.cnr.ilc.lexo.service.data.lexicon.input.LexicalEntryStatusChangeRequest;
import it.cnr.ilc.lexo.service.data.lexicon.output.LexicalEntryCreationResult;
import it.cnr.ilc.lexo.service.data.lexicon.output.LexicalConceptCreationResult;
import it.cnr.ilc.lexo.service.data.lexicon.output.LexicalEntryStatusChangeResult;
import java.net.URI;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.PATCH;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.log4j.Level;

/**
 * Entry point for the new lexical CRUD services.
 *
 * <p>Legacy lexical resources remain available while their operations are
 * migrated incrementally to this resource.</p>
 */
@javax.ws.rs.Path("lexica")
@Api("Lexica")
public class Lexicon extends Service {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final LexicalEntryManager entryManager;
    private final LexicalEntryStatusManager statusManager;
    private final LexicalEntryListManager listManager;
    private final LexicalConceptManager conceptManager;

    public Lexicon() {
        this(ManagerFactory.getManager(LexicalEntryManager.class),
                ManagerFactory.getManager(LexicalEntryStatusManager.class),
                ManagerFactory.getManager(LexicalEntryListManager.class),
                ManagerFactory.getManager(LexicalConceptManager.class));
    }

    Lexicon(LexicalEntryManager entryManager,
            LexicalEntryStatusManager statusManager,
            LexicalEntryListManager listManager,
            LexicalConceptManager conceptManager) {
        this.entryManager = entryManager;
        this.statusManager = statusManager;
        this.listManager = listManager;
        this.conceptManager = conceptManager;
    }

    @POST
    @Path("lexicalConcept")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Lexical concept creation",
            notes = "Creates a lexical concept with multilingual labels and optional validated links in the fixed LexOLexica lexical-concept named graph")
    public Response createLexicalConcept(
            @ApiParam(name = "Authorization",
                    value = "optional authorization header when LexO user management is enabled",
                    required = false)
            @HeaderParam("Authorization") String key,
            @ApiParam(name = "author",
                    value = "account creating the lexical concept when LexO user management is disabled",
                    example = "editor", required = false)
            @QueryParam("author") String author,
            @ApiParam(name = "lexicalConcept",
                    value = "lexical concept labels, definitions, and optional existing resource links",
                    required = true)
            LexicalConceptCreationRequest request) {
        try {
            checkKey(key);
            String creator = resolveAuthor(author);
            log(Level.INFO, "/lexica/lexicalConcept: creating lexical concept");
            LexicalConceptCreationResult result = conceptManager.create(
                    request, creator);
            log(Level.INFO, "/lexica/lexicalConcept: created lexical concept");
            return jsonCreated(result.lexicalConcept, result);
        } catch (IllegalArgumentException e) {
            log(Level.ERROR, "/lexica/lexicalConcept: " + e.getMessage());
            return plain(Response.Status.BAD_REQUEST, e.getMessage());
        } catch (LexicalConceptManager.LexicalConceptCreationException e) {
            log(Level.ERROR, "/lexica/lexicalConcept: " + e.getMessage());
            return plain(e.httpStatus, e.getMessage());
        } catch (AuthorizationException | ServiceException e) {
            String username = authenticationData == null
                    || authenticationData.getUsername() == null
                    ? "" : authenticationData.getUsername();
            log(Level.ERROR, "/lexica/lexicalConcept: " + username
                    + " not authorized");
            return plain(Response.Status.BAD_REQUEST,
                    username + " not authorized");
        } catch (RuntimeException e) {
            log(Level.ERROR, "/lexica/lexicalConcept: " + e.getMessage(), e);
            return plain(Response.Status.INTERNAL_SERVER_ERROR,
                    e.getMessage() == null ? e.getClass().getSimpleName()
                            : e.getMessage());
        }
    }

    @GET
    @Path("{language}/entries")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Advanced lexical entry list",
            notes = "Returns the lexical entries of one language-specific lexicon matching all supplied filters")
    public Response listEntries(
            @ApiParam(name = "Authorization",
                    value = "optional authorization header when LexO user management is enabled",
                    required = false)
            @HeaderParam("Authorization") String authorization,
            @ApiParam(name = "language",
                    value = "ISO 639 language code selecting the lexical named graph",
                    example = "it", required = true)
            @PathParam("language") String language,
            @ApiParam(name = "key",
                    value = "optional text searched in the effective entry label",
                    example = "cas", required = false)
            @QueryParam("key") String key,
            @ApiParam(name = "searchMode",
                    value = "optional key matching mode; defaults to startsWith",
                    allowableValues = "startsWith, contains, endsWith",
                    required = false)
            @QueryParam("searchMode") String searchMode,
            @ApiParam(name = "case",
                    value = "optional key case handling; defaults to sensitive",
                    allowableValues = "sensitive, insensitive", required = false)
            @QueryParam("case") String matchCase,
            @ApiParam(name = "type",
                    value = "optional existing lexical entry RDF type IRI",
                    required = false)
            @QueryParam("type") String type,
            @ApiParam(name = "pos",
                    value = "optional IRI of a lexinfo:PartOfSpeech individual",
                    required = false)
            @QueryParam("pos") String pos,
            @ApiParam(name = "author",
                    value = "optional exact creator string", required = false)
            @QueryParam("author") String author,
            @ApiParam(name = "status",
                    value = "optional lexical entry workflow status",
                    allowableValues = "working, completed, revised", required = false)
            @QueryParam("status") String status,
            @ApiParam(name = "senseNumber",
                    value = "optional exact number of distinct senses; must be at least zero",
                    required = false)
            @QueryParam("senseNumber") String senseNumber) {
        try {
            checkKey(authorization);
            log(Level.INFO, "/lexica/{language}/entries: listing lexical entries");
            return jsonOk(listManager.list(language, key, searchMode, matchCase,
                    type, pos, author, status, senseNumber));
        } catch (IllegalArgumentException e) {
            log(Level.ERROR, "/lexica/{language}/entries: " + e.getMessage());
            return plain(Response.Status.BAD_REQUEST, e.getMessage());
        } catch (LexicalEntryListManager.EntryListException e) {
            log(Level.ERROR, "/lexica/{language}/entries: " + e.getMessage());
            return plain(e.httpStatus, e.getMessage());
        } catch (AuthorizationException | ServiceException e) {
            String username = authenticationData == null
                    || authenticationData.getUsername() == null
                    ? "" : authenticationData.getUsername();
            log(Level.ERROR, "/lexica/{language}/entries: " + username
                    + " not authorized");
            return plain(Response.Status.BAD_REQUEST,
                    username + " not authorized");
        } catch (RuntimeException e) {
            log(Level.ERROR, "/lexica/{language}/entries: " + e.getMessage(), e);
            return plain(Response.Status.INTERNAL_SERVER_ERROR,
                    e.getMessage() == null ? e.getClass().getSimpleName()
                            : e.getMessage());
        }
    }

    @PATCH
    @Path("entries/status")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Lexical entry status change",
            notes = "Atomically changes the workflow status of one or more lexical entries in one language-specific LexOLexica named graph")
    public Response changeEntryStatuses(
            @ApiParam(name = "Authorization",
                    value = "optional authorization header when LexO user management is enabled",
                    required = false)
            @HeaderParam("Authorization") String key,
            @ApiParam(name = "author",
                    value = "account changing the lexical entry statuses when LexO user management is disabled",
                    example = "editor", required = false)
            @QueryParam("author") String author,
            @ApiParam(name = "statusChanges",
                    value = "one or more expected lexical entry workflow transitions",
                    required = true)
            LexicalEntryStatusChangeRequest request) {
        try {
            checkKey(key);
            String updater = resolveAuthor(author);
            log(Level.INFO, "/lexica/entries/status: changing lexical entry statuses");
            LexicalEntryStatusChangeResult result = statusManager.change(
                    request, updater);
            log(Level.INFO, "/lexica/entries/status: changed entries="
                    + result.entries.size());
            return jsonOk(result);
        } catch (IllegalArgumentException e) {
            log(Level.ERROR, "/lexica/entries/status: " + e.getMessage());
            return plain(Response.Status.BAD_REQUEST, e.getMessage());
        } catch (LexicalEntryStatusManager.StatusChangeException e) {
            log(Level.ERROR, "/lexica/entries/status: " + e.getMessage());
            return plain(e.httpStatus, e.getMessage());
        } catch (AuthorizationException | ServiceException e) {
            String username = authenticationData == null
                    || authenticationData.getUsername() == null
                    ? "" : authenticationData.getUsername();
            log(Level.ERROR, "/lexica/entries/status: " + username
                    + " not authorized");
            return plain(Response.Status.BAD_REQUEST,
                    username + " not authorized");
        } catch (RuntimeException e) {
            log(Level.ERROR, "/lexica/entries/status: " + e.getMessage(), e);
            return plain(Response.Status.INTERNAL_SERVER_ERROR,
                    e.getMessage() == null ? e.getClass().getSimpleName()
                            : e.getMessage());
        }
    }

    @POST
    @Path("entry")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Lexical entry creation",
            notes = "Creates a lexical entry, its optional canonical form and senses in the language-specific LexOLexica named graph, reusing or creating the corresponding lime:Lexicon")
    public Response createEntry(
            @ApiParam(name = "Authorization",
                    value = "optional authorization header when LexO user management is enabled",
                    required = false)
            @HeaderParam("Authorization") String key,
            @ApiParam(name = "author",
                    value = "account creating the lexical entry when LexO user management is disabled",
                    example = "editor", required = false)
            @QueryParam("author") String author,
            @ApiParam(name = "entry",
                    value = "lexical entry with optional metadata, canonical form, "
                            + "and senses",
                    required = true)
            LexicalEntryCreationRequest entry) {
        try {
            checkKey(key);
            String creator = resolveAuthor(author);
            log(Level.INFO, "/lexica/entry: creating lexical entry");
            LexicalEntryCreationResult result = entryManager.create(entry, creator);
            log(Level.INFO, "/lexica/entry: created entry=" + result.entry
                    + " lexicon=" + (result.lexiconCreated ? "created" : "reused")
                    + " senses=" + result.senses.size());
            return jsonCreated(result.entry, result);
        } catch (IllegalArgumentException e) {
            log(Level.ERROR, "/lexica/entry: " + e.getMessage());
            return plain(Response.Status.BAD_REQUEST, e.getMessage());
        } catch (AuthorizationException | ServiceException e) {
            String username = authenticationData == null
                    || authenticationData.getUsername() == null
                    ? "" : authenticationData.getUsername();
            log(Level.ERROR, "/lexica/entry: " + username + " not authorized");
            return plain(Response.Status.BAD_REQUEST, username + " not authorized");
        } catch (RuntimeException e) {
            log(Level.ERROR, "/lexica/entry: " + e.getMessage(), e);
            return plain(Response.Status.INTERNAL_SERVER_ERROR,
                    e.getMessage() == null ? e.getClass().getSimpleName()
                            : e.getMessage());
        }
    }

    /**
     * Resolves authentication and applies the author fallback shared by all
     * new lexical endpoints.
     *
     * @param author optional author supplied by the request
     * @return the authenticated or explicit author, or {@code anonymous}
     */
    protected String resolveAuthor(String author) {
        return LexiconCrudSupport.author(getUser(author));
    }

    private static Response jsonCreated(String location, Object body) {
        try {
            return Response.created(URI.create(location))
                    .type(MediaType.APPLICATION_JSON)
                    .entity(MAPPER.writeValueAsString(body)).build();
        } catch (JsonProcessingException e) {
            return plain(Response.Status.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private static Response jsonOk(Object body) {
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

    private static Response plain(int status, String message) {
        return Response.status(status).type(MediaType.TEXT_PLAIN)
                .entity(message == null ? "" : message).build();
    }
}
