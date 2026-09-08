package it.cnr.ilc.lexo.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.SKOS;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.http.HTTPRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Opt-in REST/GraphDB test: both repositories must belong to an isolated deployment. */
class AttestationsExportIT {
    private static final String FRAC = "http://www.w3.org/ns/lemon/frac#";
    private static final String NIF = "http://persistence.uni-leipzig.org/nlp2rdf/ontologies/nif-core#";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void exportsDedicatedDocumentGraphsAndFailsBeforePartialSuccess() throws Exception {
        String baseUrl = System.getProperty("lexo.test.export.baseUrl", "");
        String lexicalUrl = System.getProperty("lexo.test.export.lexicalRepositoryUrl", "");
        String textUrl = System.getProperty("lexo.test.export.textRepositoryUrl", "");
        Assumptions.assumeTrue(Boolean.getBoolean("lexo.test.export.dedicated")
                && !baseUrl.isEmpty() && !lexicalUrl.isEmpty() && !textUrl.isEmpty(),
                "Requires explicit opt-in and a deployment with two dedicated test repositories");
        assertThat(lexicalUrl).isNotEqualTo(textUrl);
        String id = "wa-test-" + UUID.randomUUID();
        IRI graph = iri(System.getProperty("lexo.test.export.attestationGraphBase",
                "https://lexo.ilc.cnr.it/graphs/lexical/attestations/documents/") + id);
        IRI textGraph = iri(System.getProperty("lexo.test.export.textGraphBase",
                "https://lexo.ilc.cnr.it/graphs/nif/documents/") + id);
        IRI annotation = iri("https://example.org/" + id);
        IRI body = iri("https://example.org/body/" + id);
        IRI context = iri("https://example.org/text/" + id + "#context");
        IRI locus = iri("https://example.org/text/" + id + "#char=1,2");
        HTTPRepository lexical = new HTTPRepository(lexicalUrl);
        HTTPRepository texts = new HTTPRepository(textUrl);
        Client client = ClientBuilder.newClient();
        try {
            lexical.init();
            texts.init();
            try (RepositoryConnection l = lexical.getConnection(); RepositoryConnection t = texts.getConnection()) {
                assertThat(l.size(graph)).isZero();
                assertThat(t.size(textGraph)).isZero();
                long defaultLexical = l.size((Resource) null);
                long defaultText = t.size((Resource) null);
                try {
                    l.add(annotation, RDF.TYPE, iri(FRAC + "Attestation"), graph);
                    l.add(body, iri(FRAC + "attestation"), annotation, graph);
                    l.add(annotation, iri(FRAC + "locus"), locus, graph);
                    l.add(annotation, iri(FRAC + "observedIn"), context, graph);
                    l.add(annotation, DCTERMS.CREATOR, SimpleValueFactory.getInstance().createLiteral("imported"), graph);
                    l.add(annotation, SKOS.NOTE, SimpleValueFactory.getInstance().createLiteral("nota", "it"), graph);
                    t.add(locus, iri(NIF + "referenceContext"), context, textGraph);
                    t.add(context, RDF.TYPE, iri(NIF + "Context"), textGraph);
                    t.add(context, iri(NIF + "isString"), SimpleValueFactory.getInstance().createLiteral("A😀B", "it"), textGraph);
                    long lexicalSize = l.size(graph);
                    long textSize = t.size(textGraph);
                    WebTarget target = client.target(baseUrl).path("attestations/export/web-annotation")
                            .queryParam("context", graph.stringValue());
                    try (Response response = get(target)) {
                        assertThat(response.getStatus()).isEqualTo(200);
                        assertThat(response.getMediaType().getSubtype()).isEqualTo("ld+json");
                        JsonNode result = JSON.readTree(response.readEntity(String.class)).path("@graph");
                        assertThat(result).hasSize(1);
                        assertThat(result.get(0).path("target").path("selector").get(1).path("exact").asText()).isEqualTo("😀");
                        assertThat(result.get(0).has("metadata")).isFalse();
                    }
                    try (Response response = get(target.queryParam("includeMetadata", "true"))) {
                        assertThat(response.getStatus()).isEqualTo(200);
                        JsonNode result = JSON.readTree(response.readEntity(String.class)).path("@graph").get(0);
                        assertThat(result.path("lexoProvenance").path("creator").asText()).isEqualTo("imported");
                        assertThat(result.path("metadata").path(SKOS.NOTE.stringValue()).get(0).path("language").asText()).isEqualTo("it");
                    }
                    assertThat(l.size(graph)).isEqualTo(lexicalSize);
                    assertThat(t.size(textGraph)).isEqualTo(textSize);
                    t.remove(context, iri(NIF + "isString"), null, textGraph);
                    try (Response response = get(target)) {
                        assertThat(response.getStatus()).isEqualTo(422);
                        assertThat(response.readEntity(String.class)).startsWith("WA_CANONICAL_TEXT_UNAVAILABLE:");
                    }
                    assertThat(l.size((Resource) null)).isEqualTo(defaultLexical);
                    assertThat(t.size((Resource) null)).isEqualTo(defaultText);
                } finally {
                    try { l.clear(graph); } finally { t.clear(textGraph); }
                }
            }
        } finally {
            try { client.close(); } finally {
                try { lexical.shutDown(); } finally { texts.shutDown(); }
            }
        }
    }

    private Response get(WebTarget target) {
        String authorization = System.getProperty("lexo.test.export.authorization", "");
        return target.request().header("Authorization", authorization.isEmpty() ? null : authorization).get();
    }

    private IRI iri(String value) { return SimpleValueFactory.getInstance().createIRI(value); }
}
