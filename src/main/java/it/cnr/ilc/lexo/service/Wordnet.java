package it.cnr.ilc.lexo.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import it.cnr.ilc.lexo.manager.LexiconCrudSupport;
import it.cnr.ilc.lexo.manager.ManagerFactory;
import it.cnr.ilc.lexo.manager.wordnet.WordnetEntryManager;
import it.cnr.ilc.lexo.manager.wordnet.WordnetEntryStatusManager;
import it.cnr.ilc.lexo.manager.wordnet.WordnetException;
import it.cnr.ilc.lexo.manager.wordnet.WordnetFormManager;
import it.cnr.ilc.lexo.manager.wordnet.WordnetRegistryManager;
import it.cnr.ilc.lexo.manager.wordnet.WordnetRelationManager;
import it.cnr.ilc.lexo.manager.wordnet.WordnetSchemaManager;
import it.cnr.ilc.lexo.manager.wordnet.WordnetSenseManager;
import it.cnr.ilc.lexo.manager.wordnet.WordnetSynsetManager;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.EntryCreate;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.EntryPatch;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.FormCreate;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.FormPatch;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.RelationCreate;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.RelationPatch;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.SenseCreate;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.SensePatch;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.StatusBatch;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.SynsetCreate;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.SynsetPatch;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.WordnetCreate;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.WordnetPatch;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import org.slf4j.event.Level;

/** Independent WordNet CRUD API backed exclusively by LexOWordnets. */
@Path("wordnet")
@Api("Wordnet")
@Produces(MediaType.APPLICATION_JSON)
public class Wordnet extends Service {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WordnetRegistryManager registry;
    private final WordnetEntryManager entries;
    private final WordnetEntryStatusManager statuses;
    private final WordnetFormManager forms;
    private final WordnetSenseManager senses;
    private final WordnetSynsetManager synsets;
    private final WordnetRelationManager relations;
    private final WordnetSchemaManager schema;

    public Wordnet() {
        this(ManagerFactory.getManager(WordnetRegistryManager.class),
                ManagerFactory.getManager(WordnetEntryManager.class),
                ManagerFactory.getManager(WordnetEntryStatusManager.class),
                ManagerFactory.getManager(WordnetFormManager.class),
                ManagerFactory.getManager(WordnetSenseManager.class),
                ManagerFactory.getManager(WordnetSynsetManager.class),
                ManagerFactory.getManager(WordnetRelationManager.class),
                ManagerFactory.getManager(WordnetSchemaManager.class));
    }

    Wordnet(WordnetRegistryManager registry, WordnetEntryManager entries,
            WordnetEntryStatusManager statuses, WordnetFormManager forms,
            WordnetSenseManager senses, WordnetSynsetManager synsets,
            WordnetRelationManager relations, WordnetSchemaManager schema) {
        this.registry = registry; this.entries = entries; this.statuses = statuses;
        this.forms = forms; this.senses = senses; this.synsets = synsets;
        this.relations = relations; this.schema = schema;
    }

    @GET
    @ApiOperation(value = "WordNet list", notes = "Lists application WordNets discovered only in LexOWordnets")
    public Response listWordnets(
            @ApiParam(value = "optional authorization header") @HeaderParam("Authorization") String key,
            @ApiParam(value = "optional ISO 639 language filter") @QueryParam("language") String language,
            @ApiParam(value = "optional case-insensitive display-label substring") @QueryParam("label") String label,
            @ApiParam(value = "page size from 1 to 100") @DefaultValue("25") @QueryParam("limit") int limit,
            @ApiParam(value = "zero-based result offset") @DefaultValue("0") @QueryParam("offset") int offset,
            @ApiParam(value = "optional requesting account") @QueryParam("author") String author) {
        return execute(key, author, () -> registry.list(language, label, limit, offset));
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "WordNet creation", notes = "Creates a WordNet root and its dedicated application named graph in LexOWordnets")
    public Response createWordnet(
            @ApiParam(value = "optional authorization header") @HeaderParam("Authorization") String key,
            @ApiParam(value = "optional creator account") @QueryParam("author") String author,
            @ApiParam(value = "WordNet id, language, native annotations and metadata", required = true) WordnetCreate request,
            @ApiParam(hidden = true) @Context UriInfo uriInfo) {
        return executeCreated(key, author, () -> registry.create(request,
                resolveAuthor(author)), uriInfo, null, true);
    }

    @GET
    @Path("schema")
    @ApiOperation(value = "WordNet schema", notes = "Returns supported PoS and relation categories from the pinned WordNet schema graph")
    public Response getSchema(
            @ApiParam(value = "optional authorization header") @HeaderParam("Authorization") String key,
            @ApiParam(value = "optional requesting account") @QueryParam("author") String author) {
        return execute(key, author, () -> schema.get());
    }

    @GET
    @Path("{wordnetId}")
    @ApiOperation(value = "WordNet details", notes = "Returns one WordNet root with selectable metadata and statistics")
    public Response getWordnet(
            @ApiParam(value = "optional authorization header") @HeaderParam("Authorization") String key,
            @ApiParam(value = "WordNet slug", required = true) @PathParam("wordnetId") String wordnetId,
            @ApiParam(value = "comma-separated detail sections") @QueryParam("sections") String sections,
            @ApiParam(value = "optional requesting account") @QueryParam("author") String author) {
        return execute(key, author, () -> registry.get(wordnetId, sections));
    }

    @PATCH
    @Path("{wordnetId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "WordNet update", notes = "Atomically updates native annotations and arbitrary RDF metadata")
    public Response patchWordnet(
            @ApiParam(value = "optional authorization header") @HeaderParam("Authorization") String key,
            @ApiParam(value = "WordNet slug", required = true) @PathParam("wordnetId") String wordnetId,
            @ApiParam(value = "optional updater account") @QueryParam("author") String author,
            @ApiParam(value = "presence-aware WordNet patch", required = true) WordnetPatch request) {
        return execute(key, author, () -> registry.patch(wordnetId, request,
                resolveAuthor(author)));
    }

    @DELETE
    @Path("{wordnetId}")
    @ApiOperation(value = "WordNet deletion", notes = "Deletes an empty WordNet application graph")
    public Response deleteWordnet(
            @ApiParam(value = "optional authorization header") @HeaderParam("Authorization") String key,
            @ApiParam(value = "WordNet slug", required = true) @PathParam("wordnetId") String wordnetId,
            @ApiParam(value = "optional exact modified timestamp") @QueryParam("expectedModified") String expectedModified,
            @ApiParam(value = "optional requesting account") @QueryParam("author") String author) {
        return executeDelete(key, author, () -> registry.delete(wordnetId, expectedModified));
    }

    @GET @Path("{wordnetId}/entries")
    @ApiOperation(value = "WordNet Entry list", notes = "Returns compact Entry summaries with filters and pagination")
    public Response listEntries(
            @ApiParam(value = "optional authorization header") @HeaderParam("Authorization") String key,
            @ApiParam(value = "WordNet slug", required = true) @PathParam("wordnetId") String wordnetId,
            @ApiParam(value = "canonical-form substring") @QueryParam("lemma") String lemma,
            @ApiParam(value = "WordNet PoS IRI or wn prefix") @QueryParam("partOfSpeech") String pos,
            @ApiParam(value = "exact index string") @QueryParam("index") String index,
            @ApiParam(value = "whether the Entry has Senses") @QueryParam("hasSenses") Boolean hasSenses,
            @ApiParam(value = "working, completed or revised") @QueryParam("workflowStatus") String status,
            @ApiParam(value = "page size") @DefaultValue("25") @QueryParam("limit") int limit,
            @ApiParam(value = "result offset") @DefaultValue("0") @QueryParam("offset") int offset,
            @ApiParam(value = "optional requesting account") @QueryParam("author") String author) {
        return execute(key, author, () -> entries.list(wordnetId, lemma, pos,
                index, hasSenses, status, limit, offset));
    }

    @POST @Path("{wordnetId}/entries") @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "WordNet Entry creation", notes = "Creates an Entry with its canonical Form and workflow status working")
    public Response createEntry(
            @ApiParam(value = "optional authorization header") @HeaderParam("Authorization") String key,
            @ApiParam(value = "WordNet slug", required = true) @PathParam("wordnetId") String wordnetId,
            @ApiParam(value = "optional creator account") @QueryParam("author") String author,
            @ApiParam(value = "Entry, canonical Form and optional children", required = true) EntryCreate request,
            @ApiParam(hidden = true) @Context UriInfo uriInfo) {
        return executeCreated(key, author, () -> entries.create(wordnetId,
                request, resolveAuthor(author)), uriInfo, "id", false);
    }

    @GET @Path("{wordnetId}/entries/{id}")
    @ApiOperation(value = "WordNet Entry details")
    public Response getEntry(
            @ApiParam(value = "optional authorization header") @HeaderParam("Authorization") String key,
            @ApiParam(value = "WordNet slug") @PathParam("wordnetId") String wordnetId,
            @ApiParam(value = "Base64URL Entry id") @PathParam("id") String id,
            @ApiParam(value = "comma-separated detail sections") @QueryParam("sections") String sections,
            @ApiParam(value = "optional requesting account") @QueryParam("author") String author) {
        return execute(key, author, () -> entries.get(wordnetId, id, sections));
    }

    @PATCH @Path("{wordnetId}/entries/{id}") @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "WordNet Entry update")
    public Response patchEntry(
            @ApiParam(value = "optional authorization header") @HeaderParam("Authorization") String key,
            @ApiParam(value = "WordNet slug") @PathParam("wordnetId") String wordnetId,
            @ApiParam(value = "Base64URL Entry id") @PathParam("id") String id,
            @ApiParam(value = "optional updater account") @QueryParam("author") String author,
            @ApiParam(value = "presence-aware Entry patch") EntryPatch request) {
        return execute(key, author, () -> entries.patch(wordnetId, id, request,
                resolveAuthor(author)));
    }

    @DELETE @Path("{wordnetId}/entries/{id}")
    @ApiOperation(value = "WordNet Entry deletion")
    public Response deleteEntry(
            @ApiParam(value = "optional authorization header") @HeaderParam("Authorization") String key,
            @ApiParam(value = "WordNet slug") @PathParam("wordnetId") String wordnetId,
            @ApiParam(value = "Base64URL Entry id") @PathParam("id") String id,
            @ApiParam(value = "optional exact modified timestamp") @QueryParam("expectedModified") String expectedModified,
            @ApiParam(value = "optional requesting account") @QueryParam("author") String author) {
        return executeDelete(key, author, () -> entries.delete(wordnetId, id, expectedModified));
    }

    @GET @Path("{wordnetId}/entries/{id}/status")
    @ApiOperation(value = "WordNet Entry workflow status")
    public Response getEntryStatus(
            @ApiParam(value = "optional authorization header") @HeaderParam("Authorization") String key,
            @ApiParam(value = "WordNet slug") @PathParam("wordnetId") String wordnetId,
            @ApiParam(value = "Base64URL Entry id") @PathParam("id") String id,
            @ApiParam(value = "optional requesting account") @QueryParam("author") String author) {
        return execute(key, author, () -> statuses.get(wordnetId, id));
    }

    @PATCH @Path("{wordnetId}/entries/status") @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "WordNet Entry workflow batch update")
    public Response patchEntryStatuses(
            @ApiParam(value = "optional authorization header") @HeaderParam("Authorization") String key,
            @ApiParam(value = "WordNet slug") @PathParam("wordnetId") String wordnetId,
            @ApiParam(value = "optional updater account") @QueryParam("author") String author,
            @ApiParam(value = "non-empty atomic status transition batch") StatusBatch request) {
        return execute(key, author, () -> statuses.change(wordnetId, request,
                resolveAuthor(author)));
    }

    @GET @Path("{wordnetId}/forms")
    @ApiOperation(value = "WordNet Form list")
    public Response listForms(
            @ApiParam(value = "optional authorization header") @HeaderParam("Authorization") String key,
            @ApiParam(value = "WordNet slug") @PathParam("wordnetId") String wordnetId,
            @ApiParam(value = "owner Entry id") @QueryParam("entryId") String entryId,
            @ApiParam(value = "written representation substring") @QueryParam("writtenRep") String writtenRep,
            @ApiParam(value = "canonical or other") @QueryParam("kind") String kind,
            @ApiParam(value = "page size") @DefaultValue("25") @QueryParam("limit") int limit,
            @ApiParam(value = "result offset") @DefaultValue("0") @QueryParam("offset") int offset,
            @ApiParam(value = "optional requesting account") @QueryParam("author") String author) {
        return execute(key, author, () -> forms.list(wordnetId, entryId,
                writtenRep, kind, limit, offset));
    }

    @POST @Path("{wordnetId}/forms") @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "WordNet Form creation")
    public Response createForm(
            @ApiParam(value = "optional authorization header") @HeaderParam("Authorization") String key,
            @ApiParam(value = "WordNet slug") @PathParam("wordnetId") String wordnetId,
            @ApiParam(value = "optional creator account") @QueryParam("author") String author,
            @ApiParam(value = "Form owner, kind and value") FormCreate request,
            @ApiParam(hidden = true) @Context UriInfo uriInfo) {
        return executeCreated(key, author, () -> forms.create(wordnetId, request,
                resolveAuthor(author)), uriInfo, "id", false);
    }

    @GET @Path("{wordnetId}/forms/{id}")
    @ApiOperation(value = "WordNet Form details")
    public Response getForm(
            @ApiParam(value = "optional authorization header") @HeaderParam("Authorization") String key,
            @ApiParam(value = "WordNet slug") @PathParam("wordnetId") String wordnetId,
            @ApiParam(value = "Base64URL Form id") @PathParam("id") String id,
            @ApiParam(value = "comma-separated sections") @QueryParam("sections") String sections,
            @ApiParam(value = "optional requesting account") @QueryParam("author") String author) {
        return execute(key, author, () -> forms.get(wordnetId, id, sections));
    }

    @PATCH @Path("{wordnetId}/forms/{id}") @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "WordNet Form update")
    public Response patchForm(
            @ApiParam(value = "optional authorization header") @HeaderParam("Authorization") String key,
            @ApiParam(value = "WordNet slug") @PathParam("wordnetId") String wordnetId,
            @ApiParam(value = "Base64URL Form id") @PathParam("id") String id,
            @ApiParam(value = "optional updater account") @QueryParam("author") String author,
            @ApiParam(value = "presence-aware Form patch") FormPatch request) {
        return execute(key, author, () -> forms.patch(wordnetId, id, request,
                resolveAuthor(author)));
    }

    @DELETE @Path("{wordnetId}/forms/{id}")
    @ApiOperation(value = "WordNet Form deletion")
    public Response deleteForm(
            @ApiParam(value = "optional authorization header") @HeaderParam("Authorization") String key,
            @ApiParam(value = "WordNet slug") @PathParam("wordnetId") String wordnetId,
            @ApiParam(value = "Base64URL Form id") @PathParam("id") String id,
            @ApiParam(value = "optional exact modified timestamp") @QueryParam("expectedModified") String expectedModified,
            @ApiParam(value = "optional requesting account") @QueryParam("author") String author) {
        return executeDelete(key, author, () -> forms.delete(wordnetId, id, expectedModified));
    }

    @GET @Path("{wordnetId}/senses")
    @ApiOperation(value = "WordNet Sense list")
    public Response listSenses(
            @ApiParam(value = "optional authorization header") @HeaderParam("Authorization") String key,
            @ApiParam(value = "WordNet slug") @PathParam("wordnetId") String wordnetId,
            @ApiParam(value = "owner Entry id") @QueryParam("entryId") String entryId,
            @ApiParam(value = "target Synset id") @QueryParam("synsetId") String synsetId,
            @ApiParam(value = "Sense number") @QueryParam("n") Integer n,
            @ApiParam(value = "page size") @DefaultValue("25") @QueryParam("limit") int limit,
            @ApiParam(value = "result offset") @DefaultValue("0") @QueryParam("offset") int offset,
            @ApiParam(value = "optional requesting account") @QueryParam("author") String author) {
        return execute(key, author, () -> senses.list(wordnetId, entryId,
                synsetId, n, limit, offset));
    }

    @POST @Path("{wordnetId}/senses") @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "WordNet Sense creation")
    public Response createSense(
            @ApiParam(value = "optional authorization header") @HeaderParam("Authorization") String key,
            @ApiParam(value = "WordNet slug") @PathParam("wordnetId") String wordnetId,
            @ApiParam(value = "optional creator account") @QueryParam("author") String author,
            @ApiParam(value = "Sense Entry, Synset and optional children") SenseCreate request,
            @ApiParam(hidden = true) @Context UriInfo uriInfo) {
        return executeCreated(key, author, () -> senses.create(wordnetId, request,
                resolveAuthor(author)), uriInfo, "id", false);
    }

    @GET @Path("{wordnetId}/senses/{id}")
    @ApiOperation(value = "WordNet Sense details")
    public Response getSense(
            @ApiParam(value = "optional authorization header") @HeaderParam("Authorization") String key,
            @ApiParam(value = "WordNet slug") @PathParam("wordnetId") String wordnetId,
            @ApiParam(value = "Base64URL Sense id") @PathParam("id") String id,
            @ApiParam(value = "comma-separated sections") @QueryParam("sections") String sections,
            @ApiParam(value = "relation direction: in, out or both") @QueryParam("direction") String direction,
            @ApiParam(value = "optional requesting account") @QueryParam("author") String author) {
        return execute(key, author, () -> senses.get(wordnetId, id, sections,
                direction));
    }

    @PATCH @Path("{wordnetId}/senses/{id}") @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "WordNet Sense update")
    public Response patchSense(
            @ApiParam(value = "optional authorization header") @HeaderParam("Authorization") String key,
            @ApiParam(value = "WordNet slug") @PathParam("wordnetId") String wordnetId,
            @ApiParam(value = "Base64URL Sense id") @PathParam("id") String id,
            @ApiParam(value = "optional updater account") @QueryParam("author") String author,
            @ApiParam(value = "presence-aware Sense patch") SensePatch request) {
        return execute(key, author, () -> senses.patch(wordnetId, id, request,
                resolveAuthor(author)));
    }

    @DELETE @Path("{wordnetId}/senses/{id}")
    @ApiOperation(value = "WordNet Sense deletion")
    public Response deleteSense(
            @ApiParam(value = "optional authorization header") @HeaderParam("Authorization") String key,
            @ApiParam(value = "WordNet slug") @PathParam("wordnetId") String wordnetId,
            @ApiParam(value = "Base64URL Sense id") @PathParam("id") String id,
            @ApiParam(value = "optional exact modified timestamp") @QueryParam("expectedModified") String expectedModified,
            @ApiParam(value = "optional requesting account") @QueryParam("author") String author) {
        return executeDelete(key, author, () -> senses.delete(wordnetId, id, expectedModified));
    }

    @GET @Path("{wordnetId}/synsets")
    @ApiOperation(value = "WordNet Synset list")
    public Response listSynsets(
            @ApiParam(value = "optional authorization header") @HeaderParam("Authorization") String key,
            @ApiParam(value = "WordNet slug") @PathParam("wordnetId") String wordnetId,
            @ApiParam(value = "WordNet PoS") @QueryParam("partOfSpeech") String pos,
            @ApiParam(value = "exact external ILI IRI") @QueryParam("ili") String ili,
            @ApiParam(value = "member canonical-form substring") @QueryParam("memberLemma") String memberLemma,
            @ApiParam(value = "definition substring") @QueryParam("definition") String definition,
            @ApiParam(value = "page size") @DefaultValue("25") @QueryParam("limit") int limit,
            @ApiParam(value = "result offset") @DefaultValue("0") @QueryParam("offset") int offset,
            @ApiParam(value = "optional requesting account") @QueryParam("author") String author) {
        return execute(key, author, () -> synsets.list(wordnetId, pos, ili,
                memberLemma, definition, limit, offset));
    }

    @POST @Path("{wordnetId}/synsets") @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "WordNet Synset creation")
    public Response createSynset(
            @ApiParam(value = "optional authorization header") @HeaderParam("Authorization") String key,
            @ApiParam(value = "WordNet slug") @PathParam("wordnetId") String wordnetId,
            @ApiParam(value = "optional creator account") @QueryParam("author") String author,
            @ApiParam(value = "Synset PoS, ILI and optional children") SynsetCreate request,
            @ApiParam(hidden = true) @Context UriInfo uriInfo) {
        return executeCreated(key, author, () -> synsets.create(wordnetId, request,
                resolveAuthor(author)), uriInfo, "id", false);
    }

    @GET @Path("{wordnetId}/synsets/{id}")
    @ApiOperation(value = "WordNet Synset details")
    public Response getSynset(
            @ApiParam(value = "optional authorization header") @HeaderParam("Authorization") String key,
            @ApiParam(value = "WordNet slug") @PathParam("wordnetId") String wordnetId,
            @ApiParam(value = "Base64URL Synset id") @PathParam("id") String id,
            @ApiParam(value = "comma-separated sections") @QueryParam("sections") String sections,
            @ApiParam(value = "relation direction: in, out or both") @QueryParam("direction") String direction,
            @ApiParam(value = "optional requesting account") @QueryParam("author") String author) {
        return execute(key, author, () -> synsets.get(wordnetId, id, sections,
                direction));
    }

    @PATCH @Path("{wordnetId}/synsets/{id}") @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "WordNet Synset update")
    public Response patchSynset(
            @ApiParam(value = "optional authorization header") @HeaderParam("Authorization") String key,
            @ApiParam(value = "WordNet slug") @PathParam("wordnetId") String wordnetId,
            @ApiParam(value = "Base64URL Synset id") @PathParam("id") String id,
            @ApiParam(value = "optional updater account") @QueryParam("author") String author,
            @ApiParam(value = "presence-aware Synset patch") SynsetPatch request) {
        return execute(key, author, () -> synsets.patch(wordnetId, id, request,
                resolveAuthor(author)));
    }

    @DELETE @Path("{wordnetId}/synsets/{id}")
    @ApiOperation(value = "WordNet Synset deletion")
    public Response deleteSynset(
            @ApiParam(value = "optional authorization header") @HeaderParam("Authorization") String key,
            @ApiParam(value = "WordNet slug") @PathParam("wordnetId") String wordnetId,
            @ApiParam(value = "Base64URL Synset id") @PathParam("id") String id,
            @ApiParam(value = "optional exact modified timestamp") @QueryParam("expectedModified") String expectedModified,
            @ApiParam(value = "optional requesting account") @QueryParam("author") String author) {
        return executeDelete(key, author, () -> synsets.delete(wordnetId, id, expectedModified));
    }

    @GET @Path("{wordnetId}/relations")
    @ApiOperation(value = "WordNet Relation list")
    public Response listRelations(
            @ApiParam(value = "optional authorization header") @HeaderParam("Authorization") String key,
            @ApiParam(value = "WordNet slug") @PathParam("wordnetId") String wordnetId,
            @ApiParam(value = "synset or sense") @QueryParam("scope") String scope,
            @ApiParam(value = "outbound endpoint id") @QueryParam("sourceId") String sourceId,
            @ApiParam(value = "inbound endpoint id") @QueryParam("targetId") String targetId,
            @ApiParam(value = "relation category") @QueryParam("category") String category,
            @ApiParam(value = "page size") @DefaultValue("25") @QueryParam("limit") int limit,
            @ApiParam(value = "result offset") @DefaultValue("0") @QueryParam("offset") int offset,
            @ApiParam(value = "optional requesting account") @QueryParam("author") String author) {
        return execute(key, author, () -> relations.list(wordnetId, scope,
                sourceId, targetId, category, limit, offset));
    }

    @POST @Path("{wordnetId}/relations") @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "WordNet Relation creation")
    public Response createRelation(
            @ApiParam(value = "optional authorization header") @HeaderParam("Authorization") String key,
            @ApiParam(value = "WordNet slug") @PathParam("wordnetId") String wordnetId,
            @ApiParam(value = "optional creator account") @QueryParam("author") String author,
            @ApiParam(value = "relation scope, endpoints and category") RelationCreate request,
            @ApiParam(hidden = true) @Context UriInfo uriInfo) {
        return executeCreated(key, author, () -> relations.create(wordnetId,
                request, resolveAuthor(author)), uriInfo, "id", false);
    }

    @GET @Path("{wordnetId}/relations/{id}")
    @ApiOperation(value = "WordNet Relation details")
    public Response getRelation(
            @ApiParam(value = "optional authorization header") @HeaderParam("Authorization") String key,
            @ApiParam(value = "WordNet slug") @PathParam("wordnetId") String wordnetId,
            @ApiParam(value = "Base64URL Relation id") @PathParam("id") String id,
            @ApiParam(value = "comma-separated sections") @QueryParam("sections") String sections,
            @ApiParam(value = "optional requesting account") @QueryParam("author") String author) {
        return execute(key, author, () -> relations.get(wordnetId, id, sections));
    }

    @PATCH @Path("{wordnetId}/relations/{id}") @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "WordNet Relation update")
    public Response patchRelation(
            @ApiParam(value = "optional authorization header") @HeaderParam("Authorization") String key,
            @ApiParam(value = "WordNet slug") @PathParam("wordnetId") String wordnetId,
            @ApiParam(value = "Base64URL Relation id") @PathParam("id") String id,
            @ApiParam(value = "optional updater account") @QueryParam("author") String author,
            @ApiParam(value = "presence-aware Relation patch") RelationPatch request) {
        return execute(key, author, () -> relations.patch(wordnetId, id, request,
                resolveAuthor(author)));
    }

    @DELETE @Path("{wordnetId}/relations/{id}")
    @ApiOperation(value = "WordNet Relation deletion")
    public Response deleteRelation(
            @ApiParam(value = "optional authorization header") @HeaderParam("Authorization") String key,
            @ApiParam(value = "WordNet slug") @PathParam("wordnetId") String wordnetId,
            @ApiParam(value = "Base64URL Relation id") @PathParam("id") String id,
            @ApiParam(value = "optional exact modified timestamp") @QueryParam("expectedModified") String expectedModified,
            @ApiParam(value = "optional requesting account") @QueryParam("author") String author) {
        return executeDelete(key, author, () -> relations.delete(wordnetId, id, expectedModified));
    }

    protected String resolveAuthor(String author) {
        return LexiconCrudSupport.author(getUser(author));
    }

    private Response execute(String key, String author, Operation operation) {
        try {
            checkKey(key);
            resolveAuthor(author);
            return json(Response.Status.OK.getStatusCode(), operation.run(), null);
        } catch (WordnetException ex) {
            return error(ex.httpStatus, ex.code, ex.getMessage());
        } catch (IllegalArgumentException ex) {
            return error(400, inferCode(ex), ex.getMessage());
        } catch (AuthorizationException | ServiceException ex) {
            return error(401, "AUTHORIZATION_FAILED", "Authorization failed");
        } catch (RuntimeException ex) {
            log(Level.ERROR, "WordNet service failure", ex);
            return error(500, "INTERNAL_ERROR", "Unexpected WordNet service failure");
        }
    }

    private Response executeCreated(String key, String author, Operation operation,
            UriInfo uriInfo, String idField, boolean root) {
        try {
            checkKey(key);
            resolveAuthor(author);
            Map<String, Object> result = operation.run();
            String id = String.valueOf(result.get(root ? "wordnetId" : idField));
            URI location = uriInfo == null ? URI.create(id)
                    : uriInfo.getAbsolutePathBuilder().path(id).build();
            return json(Response.Status.CREATED.getStatusCode(), result, location);
        } catch (WordnetException ex) {
            return error(ex.httpStatus, ex.code, ex.getMessage());
        } catch (IllegalArgumentException ex) {
            return error(400, inferCode(ex), ex.getMessage());
        } catch (AuthorizationException | ServiceException ex) {
            return error(401, "AUTHORIZATION_FAILED", "Authorization failed");
        } catch (RuntimeException ex) {
            log(Level.ERROR, "WordNet service failure", ex);
            return error(500, "INTERNAL_ERROR", "Unexpected WordNet service failure");
        }
    }

    private Response executeDelete(String key, String author, Action action) {
        try {
            checkKey(key);
            resolveAuthor(author);
            action.run();
            return Response.noContent().build();
        } catch (WordnetException ex) {
            return error(ex.httpStatus, ex.code, ex.getMessage());
        } catch (IllegalArgumentException ex) {
            return error(400, inferCode(ex), ex.getMessage());
        } catch (AuthorizationException | ServiceException ex) {
            return error(401, "AUTHORIZATION_FAILED", "Authorization failed");
        } catch (RuntimeException ex) {
            log(Level.ERROR, "WordNet service failure", ex);
            return error(500, "INTERNAL_ERROR", "Unexpected WordNet service failure");
        }
    }

    private Response json(int status, Object body, URI location) {
        try {
            Response.ResponseBuilder builder = Response.status(status)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(MAPPER.writeValueAsString(body));
            if (location != null) builder.location(location);
            return builder.build();
        } catch (JsonProcessingException ex) {
            return error(500, "INTERNAL_ERROR", "Unable to serialize response");
        }
    }

    private Response error(int status, String code, String message) {
        Map<String, Object> error = new LinkedHashMap<String, Object>();
        error.put("code", code);
        error.put("message", message == null ? "" : message);
        error.put("details", new ArrayList<Object>());
        Map<String, Object> envelope = new LinkedHashMap<String, Object>();
        envelope.put("error", error);
        return json(status, envelope, null);
    }

    private String inferCode(IllegalArgumentException ex) {
        String message = ex.getMessage();
        if (message != null) {
            int separator = message.indexOf(':');
            if (separator > 0) {
                String prefix = message.substring(0, separator);
                if (prefix.matches("[A-Z][A-Z0-9_]+")) return prefix;
            }
            if (message.toLowerCase().contains("language")) return "INVALID_LANGUAGE";
        }
        return "INVALID_PATCH";
    }

    private interface Operation { Map<String, Object> run(); }
    private interface Action { void run(); }
}
