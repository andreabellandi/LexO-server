package it.cnr.ilc.lexo.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import it.cnr.ilc.lexo.manager.LexiconCrudSupport;
import it.cnr.ilc.lexo.manager.LexicalEntryManager;
import it.cnr.ilc.lexo.manager.ManagerFactory;
import it.cnr.ilc.lexo.service.data.lexicon.input.LexicalEntryCreationRequest;
import it.cnr.ilc.lexo.service.data.lexicon.output.LexicalEntryCreationResult;
import java.net.URI;
import javax.ws.rs.Consumes;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
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

    public Lexicon() {
        this(ManagerFactory.getManager(LexicalEntryManager.class));
    }

    Lexicon(LexicalEntryManager entryManager) {
        this.entryManager = entryManager;
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
                    value = "lexical entry, optional canonical form, and optional senses",
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
            return jsonCreated(result);
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

    private static Response jsonCreated(LexicalEntryCreationResult body) {
        try {
            return Response.created(URI.create(body.entry))
                    .type(MediaType.APPLICATION_JSON)
                    .entity(MAPPER.writeValueAsString(body)).build();
        } catch (JsonProcessingException e) {
            return plain(Response.Status.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private static Response plain(Response.Status status, String message) {
        return Response.status(status).type(MediaType.TEXT_PLAIN)
                .entity(message == null ? "" : message).build();
    }
}
