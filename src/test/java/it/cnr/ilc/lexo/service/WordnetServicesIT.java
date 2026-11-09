package it.cnr.ilc.lexo.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.eclipse.rdf4j.repository.http.HTTPRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Opt-in black-box verification of the deployed WordNet REST API and its
 * dedicated remote GraphDB repository.
 */
class WordnetServicesIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DEFAULT_GRAPH_BASE =
            "https://lexo.ilc.cnr.it/graphs/wordnet/";
    private static final String DEFAULT_SCHEMA_GRAPH =
            "https://lexo.ilc.cnr.it/graphs/wordnet/schema";
    private static final IRI LEXICON = iri("http://www.w3.org/ns/lemon/lime#Lexicon");
    private static final IRI ENTRY = iri(
            "http://www.w3.org/ns/lemon/ontolex#LexicalEntry");
    private static final IRI SENSE = iri(
            "http://www.w3.org/ns/lemon/ontolex#LexicalSense");
    private static final IRI SYNSET = iri(
            "http://www.w3.org/ns/lemon/ontolex#LexicalConcept");
    private static final IRI CONCEPTUAL_RELATION = iri(
            "http://www.w3.org/ns/lemon/vartrans#ConceptualRelation");

    private String baseUrl;
    private String authorization;
    private String repositoryUrl;
    private String graphBase;
    private IRI schemaGraph;
    private Client client;
    private CloseableHttpClient patchClient;
    private HTTPRepository repository;

    @BeforeEach
    void setUp() {
        baseUrl = trim(System.getProperty("lexo.test.wordnet.baseUrl",
                System.getProperty("lexo.test.baseUrl", "")));
        authorization = System.getProperty("lexo.test.wordnet.authorization",
                System.getProperty("lexo.test.authorization", "")).trim();
        repositoryUrl = trim(System.getProperty(
                "lexo.test.wordnet.repositoryUrl", ""));
        graphBase = trailingSlash(System.getProperty(
                "lexo.test.wordnet.namedGraphBase", DEFAULT_GRAPH_BASE));
        schemaGraph = iri(System.getProperty("lexo.test.wordnet.schemaGraph",
                DEFAULT_SCHEMA_GRAPH));
        Assumptions.assumeTrue(Boolean.getBoolean("lexo.test.wordnet.dedicated")
                && !baseUrl.isEmpty() && !authorization.isEmpty()
                && !repositoryUrl.isEmpty(),
                "Requires explicit opt-in and a dedicated LexOWordnets repository");
        client = ClientBuilder.newClient();
        patchClient = HttpClients.createDefault();
        repository = new HTTPRepository(repositoryUrl);
        repository.init();
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            try {
                if (client != null) client.close();
            } finally {
                if (patchClient != null) patchClient.close();
            }
        } finally {
            if (repository != null) repository.shutDown();
        }
    }

    @Test
    void schemaIsServedFromTheDedicatedRepositoryWithoutDefaultGraphData()
            throws Exception {
        long schemaSize;
        try (RepositoryConnection connection = repository.getConnection()) {
            assertThat(connection.size((Resource) null)).isZero();
            schemaSize = connection.size(schemaGraph);
            assertThat(schemaSize).isGreaterThan(0L);
        }

        JsonNode schema = get(target("wordnet/schema"), 200);
        assertThat(schema.path("version").asText()).isEqualTo("1.4");
        assertThat(schema.path("namespace").asText())
                .isEqualTo("https://globalwordnet.github.io/schemas/wn#");
        assertThat(schema.path("source").asText())
                .isEqualTo("wn-lemon-1.4.ttl");
        assertThat(schema.path("sha256").asText())
                .isEqualTo("deecbb23eb84ff9f9a3d40adb6077810f2a0bd6ab9ccedd7d0b3b26af3f422bb");
        assertThat(schema.path("partOfSpeech").isArray()).isTrue();
        assertThat(schema.path("partOfSpeech").size()).isGreaterThan(0);
        assertThat(schema.path("synsetRelations").size()).isGreaterThan(0);
        assertThat(schema.path("senseRelations").size()).isGreaterThan(0);

        try (RepositoryConnection connection = repository.getConnection()) {
            assertThat(connection.size(schemaGraph)).isEqualTo(schemaSize);
            assertThat(connection.size((Resource) null)).isZero();
        }
    }

    @Test
    void completeCrudWorkflowUsesOneApplicationGraphAndRollsBackFailures()
            throws Exception {
        String wordnetId = "e2e-" + UUID.randomUUID().toString();
        IRI applicationGraph = iri(graphBase + "en/" + wordnetId);
        String entryOne = null;
        String entryTwo = null;
        String otherForm = null;
        String synsetOne = null;
        String synsetTwo = null;
        String senseOne = null;
        String relation = null;
        boolean rootCreated = false;
        long schemaSize;
        try (RepositoryConnection connection = repository.getConnection()) {
            assertThat(connection.size((Resource) null)).isZero();
            assertThat(connection.size(applicationGraph)).isZero();
            schemaSize = connection.size(schemaGraph);
            assertThat(schemaSize).isGreaterThan(0L);
        }

        try {
            JsonNode schema = get(target("wordnet/schema"), 200);
            String partOfSpeech = category(schema.path("partOfSpeech"),
                    "wn:noun");
            String relationCategory = category(schema.path("synsetRelations"),
                    "wn:hypernym");

            ObjectNode rootRequest = JSON.createObjectNode();
            rootRequest.put("wordnetId", wordnetId);
            rootRequest.put("language", "en");
            ArrayNode metadata = rootRequest.putArray("metadata");
            ObjectNode label = metadata.addObject();
            label.put("property", "http://www.w3.org/2000/01/rdf-schema#label");
            ObjectNode labelValue = label.putArray("values").addObject();
            labelValue.put("value", "E2E " + wordnetId);
            labelValue.put("type", "literal");
            labelValue.put("language", "en");
            JsonNode root = post("wordnet", rootRequest, 201);
            rootCreated = true;
            assertThat(root.path("wordnetId").asText()).isEqualTo(wordnetId);
            assertThat(root.path("graph").asText())
                    .isEqualTo(applicationGraph.stringValue());
            assertThat(root.path("creator").asText()).isEqualTo("e2e");

            JsonNode listed = get(target("wordnet")
                    .queryParam("language", "en")
                    .queryParam("label", wordnetId), 200);
            assertThat(listed.path("total").asInt()).isEqualTo(1);
            assertThat(listed.path("items").get(0).path("wordnetId").asText())
                    .isEqualTo(wordnetId);

            JsonNode firstEntry = post("wordnet/" + wordnetId + "/entries",
                    entry("alpha", partOfSpeech), 201);
            entryOne = firstEntry.path("id").asText();
            assertThat(entryOne).isNotBlank();
            assertThat(firstEntry.path("workflowStatus").asText())
                    .isEqualTo("working");
            JsonNode secondEntry = post("wordnet/" + wordnetId + "/entries",
                    entry("beta", partOfSpeech), 201);
            entryTwo = secondEntry.path("id").asText();

            ObjectNode formRequest = JSON.createObjectNode();
            formRequest.put("entryId", entryOne);
            formRequest.put("kind", "other");
            formRequest.put("writtenRep", "alphas");
            ObjectNode tag = formRequest.putArray("tags").addObject();
            tag.put("category", "register");
            tag.put("value", "test");
            ObjectNode pronunciation = formRequest.putArray("pronunciations")
                    .addObject();
            pronunciation.put("transcription", "al-fa");
            pronunciation.put("notation", "e2e");
            pronunciation.put("phonemic", true);
            JsonNode createdForm = post("wordnet/" + wordnetId + "/forms",
                    formRequest, 201);
            otherForm = createdForm.path("id").asText();
            JsonNode form = get(target("wordnet/" + wordnetId + "/forms/"
                    + otherForm).queryParam("sections", "tags,pronunciations"),
                    200);
            assertThat(form.path("tags")).hasSize(1);
            assertThat(form.path("pronunciations")).hasSize(1);
            ObjectNode formPatch = JSON.createObjectNode();
            formPatch.put("writtenRep", "alpha variant");
            assertThat(patch("wordnet/" + wordnetId + "/forms/" + otherForm,
                    formPatch, 200).path("writtenRep").asText())
                    .isEqualTo("alpha variant");

            JsonNode firstSynset = post("wordnet/" + wordnetId + "/synsets",
                    synset(partOfSpeech, "first end-to-end concept"), 201);
            synsetOne = firstSynset.path("id").asText();
            JsonNode secondSynset = post("wordnet/" + wordnetId + "/synsets",
                    synset(partOfSpeech, "second end-to-end concept"), 201);
            synsetTwo = secondSynset.path("id").asText();

            ObjectNode senseRequest = JSON.createObjectNode();
            senseRequest.put("entryId", entryOne);
            senseRequest.put("synsetId", synsetOne);
            senseRequest.put("n", 1);
            ObjectNode example = senseRequest.putArray("examples").addObject();
            example.put("text", "alpha appears here");
            example.put("language", "en");
            senseRequest.putArray("counts").addObject().put("value", 3);
            JsonNode createdSense = post("wordnet/" + wordnetId + "/senses",
                    senseRequest, 201);
            senseOne = createdSense.path("id").asText();
            JsonNode synsetDetail = get(target("wordnet/" + wordnetId
                    + "/synsets/" + synsetOne)
                    .queryParam("sections", "members,definitions"), 200);
            assertThat(synsetDetail.path("members")).hasSize(1);
            assertThat(synsetDetail.path("members").get(0)
                    .path("senseId").asText()).isEqualTo(senseOne);
            assertThat(synsetDetail.path("definitions").get(0)
                    .path("text").asText()).isEqualTo("first end-to-end concept");

            ObjectNode staleBatch = statusBatch(entryOne, "working", "completed",
                    entryTwo, "completed", "revised");
            assertError("PATCH", "wordnet/" + wordnetId + "/entries/status",
                    staleBatch, 409, "STATUS_MISMATCH");
            assertThat(status(wordnetId, entryOne)).isEqualTo("working");
            assertThat(status(wordnetId, entryTwo)).isEqualTo("working");
            patch("wordnet/" + wordnetId + "/entries/status",
                    statusBatch(entryOne, "working", "completed",
                            entryTwo, "working", "completed"), 200);
            assertThat(status(wordnetId, entryOne)).isEqualTo("completed");

            ObjectNode relationRequest = JSON.createObjectNode();
            relationRequest.put("scope", "synset");
            relationRequest.put("sourceId", synsetOne);
            relationRequest.put("targetId", synsetTwo);
            relationRequest.put("category", relationCategory);
            JsonNode createdRelation = post("wordnet/" + wordnetId
                    + "/relations", relationRequest, 201);
            relation = createdRelation.path("id").asText();
            assertThat(createdRelation.path("scope").asText()).isEqualTo("synset");

            long beforeDuplicate;
            try (RepositoryConnection connection = repository.getConnection()) {
                beforeDuplicate = connection.size(applicationGraph);
            }
            assertError("POST", "wordnet/" + wordnetId + "/relations",
                    relationRequest, 409, "RELATION_ALREADY_EXISTS");
            try (RepositoryConnection connection = repository.getConnection()) {
                assertThat(connection.size(applicationGraph))
                        .isEqualTo(beforeDuplicate);
            }

            JsonNode relations = get(target("wordnet/" + wordnetId
                    + "/relations").queryParam("scope", "synset")
                    .queryParam("sourceId", synsetOne)
                    .queryParam("category", relationCategory), 200);
            assertThat(relations.path("total").asInt()).isEqualTo(1);
            JsonNode outbound = get(target("wordnet/" + wordnetId
                    + "/synsets/" + synsetOne)
                    .queryParam("sections", "relations")
                    .queryParam("direction", "out"), 200);
            assertThat(outbound.path("relations")).hasSize(1);
            assertThat(outbound.path("relations").get(0)
                    .path("direction").asText()).isEqualTo("out");
            assertError("GET", "wordnet/" + wordnetId
                    + "/synsets/" + synsetOne + "?sections=core,core",
                    null, 400, "INVALID_SECTION");

            try (RepositoryConnection connection = repository.getConnection()) {
                assertThat(connection.size(applicationGraph)).isGreaterThan(0L);
                assertThat(connection.size((Resource) null)).isZero();
                assertThat(connection.size(schemaGraph)).isEqualTo(schemaSize);
                IRI rootIri = iri(root.path("iri").asText());
                IRI entryIri = iri(firstEntry.path("iri").asText());
                IRI synsetIri = iri(firstSynset.path("iri").asText());
                IRI senseIri = iri(createdSense.path("iri").asText());
                IRI relationIri = iri(createdRelation.path("iri").asText());
                assertThat(connection.hasStatement(rootIri, RDF.TYPE, LEXICON,
                        false, applicationGraph)).isTrue();
                assertThat(connection.hasStatement(entryIri, RDF.TYPE, ENTRY,
                        false, applicationGraph)).isTrue();
                assertThat(connection.hasStatement(synsetIri, RDF.TYPE, SYNSET,
                        false, applicationGraph)).isTrue();
                assertThat(connection.hasStatement(senseIri, RDF.TYPE, SENSE,
                        false, applicationGraph)).isTrue();
                assertThat(connection.hasStatement(relationIri, RDF.TYPE,
                        CONCEPTUAL_RELATION, false, applicationGraph)).isTrue();
                assertThat(connection.hasStatement(rootIri, null, null, false,
                        schemaGraph)).isFalse();
                try (RepositoryResult<Statement> statements =
                        connection.getStatements(null, null, null, false,
                                applicationGraph)) {
                    while (statements.hasNext()) {
                        assertThat(statements.next().getContext())
                                .isEqualTo(applicationGraph);
                    }
                }
            }

            delete("wordnet/" + wordnetId + "/relations/" + relation, 204);
            relation = null;
            delete("wordnet/" + wordnetId + "/senses/" + senseOne, 204);
            senseOne = null;
            delete("wordnet/" + wordnetId + "/forms/" + otherForm, 204);
            otherForm = null;
            delete("wordnet/" + wordnetId + "/entries/" + entryOne, 204);
            entryOne = null;
            delete("wordnet/" + wordnetId + "/entries/" + entryTwo, 204);
            entryTwo = null;
            delete("wordnet/" + wordnetId + "/synsets/" + synsetOne, 204);
            synsetOne = null;
            delete("wordnet/" + wordnetId + "/synsets/" + synsetTwo, 204);
            synsetTwo = null;
            delete("wordnet/" + wordnetId, 204);
            rootCreated = false;
            assertError("GET", "wordnet/" + wordnetId, null, 404,
                    "WORDNET_NOT_FOUND");
            try (RepositoryConnection connection = repository.getConnection()) {
                assertThat(connection.size(applicationGraph)).isZero();
                assertThat(connection.size((Resource) null)).isZero();
                assertThat(connection.size(schemaGraph)).isEqualTo(schemaSize);
            }
        } finally {
            safeDelete(wordnetId, "relations", relation);
            safeDelete(wordnetId, "senses", senseOne);
            safeDelete(wordnetId, "forms", otherForm);
            safeDelete(wordnetId, "entries", entryOne);
            safeDelete(wordnetId, "entries", entryTwo);
            safeDelete(wordnetId, "synsets", synsetOne);
            safeDelete(wordnetId, "synsets", synsetTwo);
            if (rootCreated) safeDeleteRoot(wordnetId);
            try (RepositoryConnection connection = repository.getConnection()) {
                connection.clear(applicationGraph);
                assertThat(connection.size((Resource) null)).isZero();
                assertThat(connection.size(schemaGraph)).isEqualTo(schemaSize);
            }
        }
    }

    private ObjectNode entry(String lemma, String partOfSpeech) {
        ObjectNode request = JSON.createObjectNode();
        request.put("partOfSpeech", partOfSpeech);
        request.put("index", lemma);
        request.putObject("canonicalForm").put("writtenRep", lemma);
        return request;
    }

    private ObjectNode synset(String partOfSpeech, String definition) {
        ObjectNode request = JSON.createObjectNode();
        request.put("partOfSpeech", partOfSpeech);
        ObjectNode item = request.putArray("definitions").addObject();
        item.put("kind", "definition");
        item.put("text", definition);
        item.put("language", "en");
        return request;
    }

    private ObjectNode statusBatch(String firstId, String firstFrom,
            String firstTo, String secondId, String secondFrom, String secondTo) {
        ObjectNode request = JSON.createObjectNode();
        ArrayNode entries = request.putArray("entries");
        statusChange(entries.addObject(), firstId, firstFrom, firstTo);
        statusChange(entries.addObject(), secondId, secondFrom, secondTo);
        return request;
    }

    private void statusChange(ObjectNode item, String id, String from, String to) {
        item.put("entryId", id);
        item.put("fromStatus", from);
        item.put("toStatus", to);
    }

    private String status(String wordnetId, String entryId) throws Exception {
        return get(target("wordnet/" + wordnetId + "/entries/" + entryId
                + "/status"), 200).path("workflowStatus").asText();
    }

    private String category(JsonNode items, String preferred) {
        for (JsonNode item : items) {
            if (preferred.equals(item.path("name").asText())) return preferred;
        }
        assertThat(items.size()).isGreaterThan(0);
        return items.get(0).path("name").asText();
    }

    private JsonNode get(WebTarget target, int expectedStatus) throws Exception {
        return read(request(target).get(), expectedStatus);
    }

    private JsonNode post(String path, JsonNode body, int expectedStatus)
            throws Exception {
        return read(request(mutationTarget(path)).post(Entity.entity(
                body.toString(), MediaType.APPLICATION_JSON_TYPE)), expectedStatus);
    }

    private JsonNode patch(String path, JsonNode body, int expectedStatus)
            throws Exception {
        return patch(mutationTarget(path), body, expectedStatus);
    }

    private void delete(String path, int expectedStatus) throws Exception {
        read(request(mutationTarget(path)).delete(), expectedStatus);
    }

    private void assertError(String method, String path, JsonNode body,
            int expectedStatus, String code) throws Exception {
        WebTarget target;
        int query = path.indexOf('?');
        if (query < 0) {
            target = mutationTarget(path);
        } else {
            target = mutationTarget(path.substring(0, query));
            String[] parameters = path.substring(query + 1).split("&");
            for (String parameter : parameters) {
                String[] pair = parameter.split("=", 2);
                target = target.queryParam(pair[0], pair.length == 1 ? "" : pair[1]);
            }
        }
        JsonNode error;
        if ("PATCH".equals(method)) {
            error = patch(target, body, expectedStatus);
        } else {
            Invocation.Builder request = request(target);
            Response response = body == null ? request.method(method)
                    : request.method(method, Entity.entity(body.toString(),
                            MediaType.APPLICATION_JSON_TYPE));
            error = read(response, expectedStatus);
        }
        assertThat(error.path("error").path("code").asText()).isEqualTo(code);
    }

    private JsonNode patch(WebTarget target, JsonNode body, int expectedStatus)
            throws Exception {
        HttpPatch request = new HttpPatch(target.getUri());
        request.setHeader("Authorization", authorization);
        request.setHeader("Accept", MediaType.APPLICATION_JSON);
        request.setEntity(new StringEntity(body.toString(),
                ContentType.APPLICATION_JSON));
        try (CloseableHttpResponse response = patchClient.execute(request)) {
            String payload = response.getEntity() == null ? ""
                    : EntityUtils.toString(response.getEntity());
            return read(response.getStatusLine().getStatusCode(), payload,
                    expectedStatus);
        }
    }

    private JsonNode read(Response response, int expectedStatus) throws Exception {
        try {
            String body = response.hasEntity()
                    ? response.readEntity(String.class) : "";
            return read(response.getStatus(), body, expectedStatus);
        } finally {
            response.close();
        }
    }

    private JsonNode read(int status, String body, int expectedStatus)
            throws Exception {
        assertThat(status).isEqualTo(expectedStatus);
        return body == null || body.isEmpty()
                ? JSON.createObjectNode() : JSON.readTree(body);
    }

    private Invocation.Builder request(WebTarget target) {
        return target.request(MediaType.APPLICATION_JSON_TYPE)
                .header("Authorization", authorization);
    }

    private WebTarget target(String path) {
        return client.target(baseUrl).path(path);
    }

    private WebTarget mutationTarget(String path) {
        return target(path).queryParam("author", "e2e");
    }

    private void safeDelete(String wordnetId, String family, String id) {
        if (id == null || id.isEmpty()) return;
        Response response = request(mutationTarget("wordnet/" + wordnetId + "/"
                + family + "/" + id)).delete();
        response.close();
    }

    private void safeDeleteRoot(String wordnetId) {
        Response response = request(mutationTarget("wordnet/" + wordnetId)).delete();
        response.close();
    }

    private static IRI iri(String value) {
        return SimpleValueFactory.getInstance().createIRI(value);
    }

    private static String trim(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String trailingSlash(String value) {
        String result = value == null ? DEFAULT_GRAPH_BASE : value.trim();
        return result.endsWith("/") ? result : result + "/";
    }
}
