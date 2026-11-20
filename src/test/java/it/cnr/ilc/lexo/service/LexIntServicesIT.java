package it.cnr.ilc.lexo.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.eclipse.rdf4j.repository.http.HTTPRepository;
import org.glassfish.jersey.client.ClientProperties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Opt-in black-box LexInt workflow against dedicated Tomcat and GraphDB. */
class LexIntServicesIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SimpleValueFactory VF =
            SimpleValueFactory.getInstance();
    private static final IRI RECORDS = iri(
            "https://lexo.ilc.cnr.it/graphs/lexical/lexint/records");
    private static final IRI AUDIT = iri(
            "https://lexo.ilc.cnr.it/graphs/lexical/lexint/audit");
    private static final IRI PUBLICATION = iri(
            "https://ilc.cnr.it/klab/ontology/lexint/publication#Publication");

    @Test
    void completeRestPublicationAndHistoricalAuditUseOnlyNamedGraphs()
            throws Exception {
        String base = trim(System.getProperty("lexo.test.lexint.baseUrl",
                System.getProperty("lexo.test.baseUrl", "")));
        String repositoryUrl = trim(System.getProperty(
                "lexo.test.lexint.lexicalRepositoryUrl", ""));
        Assumptions.assumeTrue(Boolean.getBoolean("lexo.test.lexint.dedicated")
                && !base.isEmpty() && !repositoryUrl.isEmpty(),
                "Requires explicit opt-in, dedicated Tomcat and dedicated LexOLexica");
        String language = System.getProperty("lexo.test.lexint.language", "it")
                .trim().toLowerCase(java.util.Locale.ROOT);
        String author = System.getProperty("lexo.test.lexint.author", "e2e")
                .trim();
        IRI active = iri("https://lexo.ilc.cnr.it/graphs/lexical/lexica/"
                + language);
        String token = UUID.randomUUID().toString();
        IRI subject = iri("urn:lexint-it:" + token + ":entry");
        IRI predicate = iri("urn:lexint-it:vocabulary:interpretation");
        IRI unexpectedSubject = iri("urn:lexint-it:" + token
                + ":unexpected");
        Client client = ClientBuilder.newClient()
                .property(ClientProperties.CONNECT_TIMEOUT, 10000)
                .property(ClientProperties.READ_TIMEOUT, 30000);
        HTTPRepository repository = new HTTPRepository(repositoryUrl);
        repository.init();
        Model originalActive = new LinkedHashModel();
        IRI sourceSnapshot = null;
        IRI targetSnapshot = null;
        try {
            try (RepositoryConnection connection = repository.getConnection()) {
                assertThat(connection.size((Resource) null)).isZero();
                assertThat(connection.size(RECORDS)).isZero();
                assertThat(connection.size(AUDIT)).isZero();
                copy(connection, active, originalActive);
            }

            ObjectNode baselineRequest = JSON.createObjectNode();
            baselineRequest.put("language", language);
            baselineRequest.put("author", author);
            JsonNode baseline = post(client, base, "lexint/versions/baseline",
                    baselineRequest, 201);
            sourceSnapshot = iri(baseline.path("snapshotGraphIri").asText());
            assertThat(baseline.path("versionCode").asText())
                    .isEqualTo("v000001");

            JsonNode draft = post(client, base, "lexint/versions/drafts",
                    baselineRequest, 201);
            assertThat(draft.path("state").asText()).isEqualTo("RESERVED");
            assertThat(draft.path("snapshotGraphIri").isNull()).isTrue();

            ObjectNode claimRequest = JSON.createObjectNode();
            claimRequest.put("description", "Dedicated LexInt E2E " + token);
            claimRequest.put("author", author);
            ObjectNode target = claimRequest.putArray("targets").addObject();
            target.put("subjectIri", subject.stringValue());
            target.put("predicateIri", predicate.stringValue());
            ObjectNode object = target.putObject("object");
            object.put("kind", "LITERAL");
            object.put("value", "interpretazione " + token);
            object.put("language", language);
            JsonNode claim = post(client, base, "lexint/claims", claimRequest,
                    201);
            String targetIri = claim.path("targets").get(0).path("iri").asText();

            ObjectNode decisionRequest = JSON.createObjectNode();
            decisionRequest.put("claimIri", claim.path("iri").asText());
            decisionRequest.put("versionIri", draft.path("iri").asText());
            decisionRequest.put("outcome", "adopted");
            decisionRequest.put("description", "E2E adopted decision");
            decisionRequest.put("author", author);
            JsonNode decision = post(client, base, "lexint/decisions",
                    decisionRequest, 201);
            decision = postEmpty(client, base, "lexint/decisions/"
                    + decision.path("id").asText() + "/finalize", 200,
                    "author", author);
            assertThat(decision.path("finalized").asBoolean()).isTrue();

            ObjectNode publicationRequest = JSON.createObjectNode();
            publicationRequest.put("language", language);
            publicationRequest.put("sourceVersionIri",
                    baseline.path("iri").asText());
            publicationRequest.put("targetVersionIri",
                    draft.path("iri").asText());
            publicationRequest.put("author", author);
            ObjectNode operation = publicationRequest.putArray("operations")
                    .addObject();
            operation.put("action", "INSERT");
            operation.put("targetAssertionIri", targetIri);
            operation.put("decisionIri", decision.path("iri").asText());

            long beforePreview;
            try (RepositoryConnection connection = repository.getConnection()) {
                beforePreview = connection.size();
            }
            JsonNode preview = post(client, base,
                    "lexint/publications/preview", publicationRequest, 200);
            assertThat(preview.path("status").asText()).isEqualTo("READY");
            assertThat(preview.path("expectedInsertions").asInt()).isEqualTo(1);
            try (RepositoryConnection connection = repository.getConnection()) {
                assertThat(connection.size()).isEqualTo(beforePreview);
            }

            String idempotencyKey = "lexint-e2e-" + token;
            try (Response response = auth(client.target(base)
                    .path("lexint/publications"))
                    .header("If-Match", "\"stale\"")
                    .header("Idempotency-Key", idempotencyKey + "-stale")
                    .post(Entity.json(publicationRequest.toString()))) {
                assertThat(response.getStatus()).isEqualTo(412);
            }
            JsonNode publication;
            try (Response response = auth(client.target(base)
                    .path("lexint/publications"))
                    .header("If-Match", preview.path("baseEtag").asText())
                    .header("Idempotency-Key", idempotencyKey)
                    .post(Entity.json(publicationRequest.toString()))) {
                assertThat(response.getStatus()).isEqualTo(201);
                publication = JSON.readTree(response.readEntity(String.class));
            }
            assertThat(publication.path("auditStatus").asText())
                    .isEqualTo("PASS");
            try (Response response = auth(client.target(base)
                    .path("lexint/publications"))
                    .header("If-Match", preview.path("baseEtag").asText())
                    .header("Idempotency-Key", idempotencyKey)
                    .post(Entity.json(publicationRequest.toString()))) {
                assertThat(response.getStatus()).isEqualTo(200);
                assertThat(JSON.readTree(response.readEntity(String.class))
                        .path("iri").asText())
                        .isEqualTo(publication.path("iri").asText());
            }

            JsonNode publishedVersion = get(client, base, "lexint/versions/"
                    + draft.path("id").asText(), 200);
            targetSnapshot = iri(publishedVersion.path("snapshotGraphIri")
                    .asText());
            try (RepositoryConnection connection = repository.getConnection()) {
                assertThat(connection.hasStatement(subject, predicate,
                        VF.createLiteral("interpretazione " + token, language),
                        false, active)).isTrue();
                assertThat(connection.size((Resource) null)).isZero();
                assertRuntimeMetadata(connection, RECORDS);
                assertRuntimeMetadata(connection, AUDIT);
            }

            JsonNode verification = postEmpty(client, base,
                    "lexint/publications/" + publication.path("id").asText()
                    + "/verify", 201, "author", author);
            assertThat(verification.path("outcome").asText()).isEqualTo("PASS");

            try (RepositoryConnection connection = repository.getConnection()) {
                connection.remove(subject, predicate,
                        VF.createLiteral("interpretazione " + token, language),
                        targetSnapshot);
                connection.add(unexpectedSubject, predicate,
                        VF.createLiteral("unexpected"), targetSnapshot);
            }
            JsonNode drift = postEmpty(client, base,
                    "lexint/publications/" + publication.path("id").asText()
                    + "/verify", 201, "author", author);
            assertThat(drift.path("outcome").asText()).isEqualTo("FAIL");
            assertThat(drift.path("missingTripleCount").asLong()).isEqualTo(1L);
            assertThat(drift.path("unexpectedTripleCount").asLong())
                    .isEqualTo(1L);
            JsonNode audit = get(client, base, "lexint/publications/"
                    + publication.path("id").asText() + "/audit", 200);
            assertThat(audit.isArray()).isTrue();
            assertThat(audit.size()).isGreaterThanOrEqualTo(3);
        } finally {
            try (RepositoryConnection connection = repository.getConnection()) {
                connection.begin();
                try {
                    connection.clear(RECORDS);
                    connection.clear(AUDIT);
                    connection.clear(active);
                    if (sourceSnapshot != null) connection.clear(sourceSnapshot);
                    if (targetSnapshot != null) connection.clear(targetSnapshot);
                    connection.add(originalActive, active);
                    connection.commit();
                    assertThat(connection.size((Resource) null)).isZero();
                } catch (RuntimeException ex) {
                    connection.rollback();
                    throw ex;
                }
            } finally {
                try { client.close(); } finally { repository.shutDown(); }
            }
        }
    }

    private static void assertRuntimeMetadata(RepositoryConnection connection,
            IRI graph) {
        Set<Resource> individuals = new LinkedHashSet<Resource>();
        try (RepositoryResult<Statement> rows = connection.getStatements(null,
                RDF.TYPE, null, false, graph)) {
            while (rows.hasNext()) individuals.add(rows.next().getSubject());
        }
        assertThat(individuals).isNotEmpty();
        for (Resource individual : individuals) {
            assertThat(connection.hasStatement(individual, DCTERMS.CREATOR,
                    null, false, graph)).isTrue();
            assertDateTime(connection, individual, DCTERMS.CREATED, graph);
            assertDateTime(connection, individual, DCTERMS.MODIFIED, graph);
        }
        if (AUDIT.equals(graph)) {
            assertThat(connection.hasStatement(null, RDF.TYPE, PUBLICATION,
                    false, graph)).isTrue();
        }
    }

    private static void assertDateTime(RepositoryConnection connection,
            Resource subject, IRI predicate, IRI graph) {
        try (RepositoryResult<Statement> rows = connection.getStatements(
                subject, predicate, null, false, graph)) {
            assertThat(rows.hasNext()).isTrue();
            assertThat(rows.next().getObject())
                    .matches(value -> value instanceof org.eclipse.rdf4j.model.Literal
                            && XSD.DATETIME.equals(
                                    ((org.eclipse.rdf4j.model.Literal) value)
                                            .getDatatype()));
            assertThat(rows.hasNext()).isFalse();
        }
    }

    private static JsonNode post(Client client, String base, String path,
            JsonNode body, int expected) throws Exception {
        try (Response response = auth(client.target(base).path(path))
                .post(Entity.json(body.toString()))) {
            assertThat(response.getStatus()).as(path + " response: "
                    + response.getStatusInfo()).isEqualTo(expected);
            return JSON.readTree(response.readEntity(String.class));
        }
    }

    private static JsonNode postEmpty(Client client, String base, String path,
            int expected, String queryName, String queryValue) throws Exception {
        WebTarget target = client.target(base).path(path);
        if (queryValue != null && !queryValue.isEmpty()) {
            target = target.queryParam(queryName, queryValue);
        }
        try (Response response = auth(target).post(Entity.text(""))) {
            assertThat(response.getStatus()).as(path + " response: "
                    + response.getStatusInfo()).isEqualTo(expected);
            return JSON.readTree(response.readEntity(String.class));
        }
    }

    private static JsonNode get(Client client, String base, String path,
            int expected) throws Exception {
        try (Response response = auth(client.target(base).path(path)).get()) {
            assertThat(response.getStatus()).as(path + " response: "
                    + response.getStatusInfo()).isEqualTo(expected);
            return JSON.readTree(response.readEntity(String.class));
        }
    }

    private static Invocation.Builder auth(WebTarget target) {
        Invocation.Builder request = target.request();
        String authorization = System.getProperty(
                "lexo.test.lexint.authorization", "").trim();
        return authorization.isEmpty() ? request
                : request.header("Authorization", authorization);
    }

    private static void copy(RepositoryConnection connection, IRI graph,
            Model target) {
        try (RepositoryResult<Statement> rows = connection.getStatements(null,
                null, null, false, graph)) {
            while (rows.hasNext()) {
                Statement statement = rows.next();
                target.add(statement.getSubject(), statement.getPredicate(),
                        statement.getObject());
            }
        }
    }

    private static IRI iri(String value) { return VF.createIRI(value); }
    private static String trim(String value) {
        return value == null ? "" : value.trim().replaceAll("/+$", "");
    }
}
